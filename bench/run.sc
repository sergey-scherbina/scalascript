#!/usr/bin/env -S scala-cli --server=false
//> using scala "3.8.3"
//> using javaOpt "-Xss8m"

/**
 * ScalaScript benchmark harness.
 *
 * Usage (from repo root):
 *   ./bench.sh                              # compare all backends (ssc, ssc-asm, jvm, js)
 *   ./bench.sh arith-loop recursion-fib    # filter by workload name
 *   ./bench.sh --backend ssc               # single backend only
 *   ./bench.sh --backend ssc-asm           # ASM JIT backend only
 *   ./bench.sh --v2-backends                # v2 VM + v2 source backend columns
 *   ./bench.sh --warmup 10 --reps 50       # custom warmup / measured iterations
 *   ./bench.sh --baseline                  # write bench/BASELINE.md
 *
 * Delegates per-file timing to `ssc bench --machine --backend <b>` (one
 * call per backend per file).  Compilation is excluded from all timings.
 */

import scala.sys.process.*
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

// ── paths ─────────────────────────────────────────────────────────────────────

val root        = Paths.get(getClass.getResource("/").toURI).getParent.getParent.getParent.toAbsolutePath
                  .toString.replaceAll("/bench/.*", "") match
                    case s if s.endsWith("/bench") => s.dropRight(6)
                    case s => s
val corpusDir   = Paths.get(s"$root/bench/corpus")
val sscBin      = Paths.get(s"$root/bin/ssc")
val baselineOut = Paths.get(s"$root/bench/BASELINE.md")

// ── arg parsing ───────────────────────────────────────────────────────────────

val writeBaseline = args.contains("--baseline")
val v2BackendMode = args.contains("--v2-backends")

// --backend <b>: limit to a single backend; default is all three.
// Synthetic backend "interp-asm" runs ssc --backend interp with SSC_JIT_BACKEND=asm.
val backendFlag: Option[String] =
  val idx = args.indexOf("--backend")
  if idx >= 0 && idx + 1 < args.length then Some(args(idx + 1))
  else args.collectFirst { case s if s.startsWith("--backend=") => s.stripPrefix("--backend=") }

val backends: Seq[String] = backendFlag match
  case Some(b)             => Seq(b)
  case None if v2BackendMode => Seq("v2", "v2-jvm", "v2-rust")
  case None                => Seq("ssc", "ssc-asm", "v2", "jvm", "js", "rust")

// --warmup N / --reps N / --warmup-time N: pass-through to ssc bench (defaults mirror BenchCmd)
def parseInt2(flag: String, default: Int): Int =
  val idx = args.indexOf(flag)
  if idx >= 0 && idx + 1 < args.length then args(idx + 1).toIntOption.getOrElse(default)
  else args.collectFirst { case s if s.startsWith(flag + "=") => s.stripPrefix(flag + "=").toIntOption.getOrElse(default) }
       .getOrElse(default)

val warmup = parseInt2("--warmup", 5)
val reps   = parseInt2("--reps", 100)

// --warmup-time N: time-based warmup in milliseconds.
// Default: 2000 ms unless --warmup (count-based) is explicitly passed.
val warmupTimeMs: Option[Long] =
  val explicit =
    val idx = args.indexOf("--warmup-time")
    if idx >= 0 && idx + 1 < args.length then args(idx + 1).toLongOption
    else args.collectFirst { case s if s.startsWith("--warmup-time=") =>
      s.stripPrefix("--warmup-time=").toLongOption }.flatten
  if explicit.isDefined then explicit
  else if args.exists(a => a == "--warmup" || a.startsWith("--warmup=")) then None
  else Some(2000L)

// non-flag args that don't belong to --backend/--warmup/--warmup-time/--reps are workload filters
val filterNames: Set[String] =
  val backendVal    = backendFlag.getOrElse("")
  val warmupVal     = args.indexOf("--warmup")      match { case i if i >= 0 && i+1 < args.length => args(i+1); case _ => "" }
  val warmupTimeVal = args.indexOf("--warmup-time") match { case i if i >= 0 && i+1 < args.length => args(i+1); case _ => "" }
  val repsVal       = args.indexOf("--reps")        match { case i if i >= 0 && i+1 < args.length => args(i+1); case _ => "" }
  args.filterNot(a => a.startsWith("--") || a == backendVal || a == warmupVal || a == warmupTimeVal || a == repsVal).toSet

// ── helpers ──────────────────────────────────────────────────────────────────

def displayName(b: String): String = b

def fmtMs(ms: Double): String =
  if ms < 0.001 then f"$ms%.6f"
  else if ms < 0.01 then f"$ms%.4f"
  else if ms < 1.0 then f"$ms%.3f"
  else if ms < 10.0 then f"$ms%.2f"
  else f"$ms%.1f"

def parseBenchLine(output: String): Option[Double] =
  output.linesIterator.collectFirst {
    case line if line.startsWith("BENCH ") =>
      val parts = line.split(" ", 3)
      if parts.length == 3 then parts(2).toDoubleOption else None
  }.flatten

// LLVM -O3 anti-folding: wrap each assignment inside *every* `pub fn` body
// with `std::hint::black_box(...)`.  This stops scalar-evolution from
// deriving closed-form (Gauss-like) solutions that replace whole loops with
// single constant loads.  Without this, pure-arith bench workloads report
// ~1ns/iter on Rust — measuring only `mov reg, const; ret`.
//
// All helpers + `workload` itself get the same treatment so recursive /
// helper-driven workloads (e.g. `sumTco`, `compute`) also resist folding.
// For functions with no assignment (pure expression bodies like
// `intMonoid.combine(...)` or `(1..=10).map.filter.fold(...)`), the
// trailing expression is also wrapped if it's the function's return value.
def patchGenWorkloadForAntiFold(src: String): String =
  // Locate every `pub fn …(…) -> … {` (or `pub fn …(…) {`) header and
  // brace-count its body so we can patch each independently.
  val pubFnRe = """pub fn [A-Za-z_]\w*""".r
  val starts  = pubFnRe.findAllMatchIn(src).map(_.start).toList
  if starts.isEmpty then return src

  // For each start, find the body's open-brace, then walk to the matching close.
  // Returns (bodyStart, bodyEnd) inclusive of the inner content.
  def bodyOf(fnStart: Int): Option[(Int, Int)] =
    val ob = src.indexOf('{', fnStart)
    if ob < 0 then None
    else
      var depth = 1; var k = ob + 1
      while k < src.length && depth > 0 do
        src.charAt(k) match
          case '{' => depth += 1
          case '}' => depth -= 1
          case _   => ()
        k += 1
      if depth == 0 then Some((ob + 1, k - 1)) else None

  // Patch a single function body — minimal anti-fold (see `bareReassignRe` /
  // `patchBody` below). Imperative loop bodies get ONE barrier on the first
  // loop-carried reassignment; pure-expression / iterator-chain bodies fall back
  // to closure-body then first-literal wrapping.
  //
  // CAVEAT: a body with TWO *sequential independent* loops would barrier only the
  // first, leaving the second foldable. No corpus workload has that shape today
  // (multi-loop cases are nested — shared accumulator — or have a loop-invariant
  // iterator chain that is legitimately hoisted). Such a regression is also
  // self-revealing: a folded loop shows up as a ~0 ms cell in the table.

  // Wraps the first integer/float literal in a body with `black_box(...)`.
  // This makes a literal opaque so LLVM can't derive closed-form solutions
  // for pure-call chains and iterator pipelines (e.g. `combine(empty, 1)`
  // chains, `(1..=10).map.fold` ranges).  Idempotent — skipped if the body
  // already contains a black_box.
  val firstLitRe = """\b(\d+(?:\.\d+)?i(?:8|16|32|64)?|\d+(?:\.\d+)?f(?:32|64))\b""".r
  def wrapFirstLit(body: String): String =
    if body.contains("std::hint::black_box(") then body
    else firstLitRe.findFirstMatchIn(body) match
      case Some(m) =>
        body.substring(0, m.start) + "std::hint::black_box(" + m.matched + ")" + body.substring(m.end)
      case None => body

  // Wraps every closure body `move |...| { EXPR }` with black_box.  This
  // catches iterator-chain workloads where LLVM would otherwise derive a
  // closed-form across the entire `.map.filter.fold` pipeline.
  // Captures: outer prefix `move |x| { `, inner expression up to `}`.
  val closureBodyRe = """(move\s*\|[^|]*\|\s*\{\s*)(?!std::hint::black_box)([^{}]+?)(\s*\})""".r
  def wrapClosureBodies(body: String): String =
    closureBodyRe.replaceAllIn(body, m =>
      val pre   = scala.util.matching.Regex.quoteReplacement(m.group(1))
      val expr  = scala.util.matching.Regex.quoteReplacement(m.group(2))
      val post  = scala.util.matching.Regex.quoteReplacement(m.group(3))
      s"${pre}std::hint::black_box($expr)$post"
    )

  // A single black_box on ONE per-iteration reassignment is necessary AND
  // sufficient to stop LLVM deriving a closed form for an imperative loop.
  // Measured on `sumTco(100000,0)` (release -O3):
  //   0 barriers           → 0.000001 ms  (folded to a constant load — dishonest)
  //   1 barrier on `acc =`  → 0.101 ms     (honest; loop actually runs)
  //   4 barriers (old "all")→ 0.341 ms     (honest but 3.4× redundant tax)
  // Opaque inits/inputs do NOT suffice — LLVM solves the recurrence symbolically
  // — so the barrier must sit on a per-iteration reassignment (`x = …;`), never a
  // `let` init. This mirrors the other backends' lighter anti-fold (jvm/js/interp
  // rely on the carried-LCG-seed idiom + sink, no per-statement barriers), so the
  // rust column stops looking 3–4× slower than codegen-equal jvm on tight loops.
  val bareReassignRe =
    """(?m)^([ \t]*)([A-Za-z_]\w* = )(?!std::hint::black_box\()([^{};]+);""".r

  def patchBody(body: String): String =
    // Wrap only the FIRST bare reassignment (the loop-carried update); leave the
    // rest untouched. `let` inits are intentionally excluded (an opaque init does
    // not block the closed-form derivation).
    var wrapped = false
    val withAssign = bareReassignRe.replaceAllIn(body, m =>
      if wrapped then scala.util.matching.Regex.quoteReplacement(m.matched)
      else
        wrapped = true
        val ws  = scala.util.matching.Regex.quoteReplacement(m.group(1))
        val lhs = scala.util.matching.Regex.quoteReplacement(m.group(2))
        val rhs = scala.util.matching.Regex.quoteReplacement(m.group(3))
        s"$ws${lhs}std::hint::black_box($rhs);"
    )
    // Iterator-chain / pure-expression bodies have no imperative reassignment —
    // wrap their closure bodies (e.g. `.map(move |x| { x * 2 })`) instead.
    val afterClosures = wrapClosureBodies(withAssign)
    if afterClosures != body then afterClosures
    else
      // Pure-expression bodies with no assignment or closure (e.g. recursive
      // `fib(n-1)+fib(n-2)`): wrap the FIRST integer literal so LLVM can't derive
      // a closed-form for the chain. (`black_box(WholeExpr)` doesn't work — LLVM
      // still computes WholeExpr statically, then makes only the result opaque.)
      wrapFirstLit(body)

  // Walk the function starts in REVERSE so we don't invalidate earlier offsets.
  var out = src
  for fnStart <- starts.sortBy(-_) do
    bodyOf(fnStart) match
      case Some((bs, be)) =>
        val before = out.substring(0, bs)
        val body   = out.substring(bs, be)
        val after  = out.substring(be)
        out = before + patchBody(body) + after
      case None => ()
  out

// Build a Rust binary that benchmarks `workload()`.
// Strategy:
//   1. emit-rust the corpus file (library crate, exports `workload()`).
//   2. Inject a custom `src/main.rs` that uses `std::time::Instant` for
//      nanosecond timing and prints `BENCH_MS: <f64>`.
//   3. cargo build --release --quiet, run the binary, parse BENCH_MS.
def runRustBench(sscPath: String, file: java.io.File): Option[Double] =
  val errLog: String => Unit = line =>
    if !line.startsWith("NOTE: Picked up") && !line.contains("skipping backend plugin") &&
       !line.startsWith("warning:") && !line.startsWith("    Compiling") &&
       !line.startsWith("    Finished") && !line.startsWith("    Blocking") &&
       !line.startsWith("   ") && !line.trim.startsWith("|") &&
       !line.trim.startsWith("=") && !line.trim.startsWith("-->") &&
       !line.trim.startsWith("help:") && !line.contains("[warn]")
    then System.err.println(line)

  val stem      = file.getName.replaceAll("\\.ssc$", "")
  val stemSafe  = stem.replace('-', '_').replace(' ', '_')
  val warmupN   = warmupTimeMs.map(_ => 200).getOrElse(warmup.max(1))
  val warmupMs  = warmupTimeMs.getOrElse(0L)

  val crateDir  = java.nio.file.Files.createTempDirectory(s"ssc-rust-bench-crate-$stem-").toFile
  def rm(f: java.io.File): Unit = { if f.isDirectory then Option(f.listFiles).foreach(_.foreach(rm)); f.delete() }
  def cleanup() = try rm(crateDir) catch case _ => ()

  // Custom main.rs: wraps the generated lib with Instant timing.
  // The Rust backend always emits generated code as `generated::ssc_program`.
  // Problem: workload() is a pure zero-arg fn; LLVM constant-folds the
  // entire body away in --release, giving 0 ns timing.  Fix: the wrapper
  // reads a volatile global (AtomicI64) that it never writes back, so the
  // optimizer cannot prove the value is constant and keeps the call live.
  // We call this indirection function _run_workload() which is #[inline(never)]
  // to prevent inlining and further hoisting.
  val rustReps = reps  // keep user reps; ns timer gives enough precision

  // Build the injected main.rs.  When workload() returns Unit we must NOT
  // pass () to black_box<T>(T) — emit a 0i64 sentinel instead.
  // `isUnit` is set after genContent is read (step 2) so we define this
  // as a function called at step 4.
  def buildMainRs(isUnit: Boolean, hasSeed: Boolean): String =
    // Seed-threaded workloads (`pub fn workload(seed: i64)`) take the opaque
    // atomic seed `_s` directly — that data dependency is what stops LLVM from
    // constant-folding the carried-LCG body (see docs/bench/corpus-antifold.md).
    val arg = if hasSeed then "_s" else ""
    val workloadCall =
      if isUnit then s"generated::ssc_program::workload($arg); std::hint::black_box(0i64);"
      else            s"let r = generated::ssc_program::workload($arg); std::hint::black_box(r);"
    s"""mod runtime;
mod value;
mod generated;

// Opaque seed prevents LLVM from hoisting/constant-folding workload().
static _SSC_BENCH_SEED: std::sync::atomic::AtomicI64 =
    std::sync::atomic::AtomicI64::new(1);

#[inline(never)]
fn _run_workload() {
    // Load seed so optimizer sees a data dependency; value is always 1.
    let _s = _SSC_BENCH_SEED.load(std::sync::atomic::Ordering::Relaxed);
    $workloadCall
}"""

  val mainRsSuffix = s"""

fn main() {
    // count-based warmup
    for _ in 0..$warmupN { _run_workload(); }
    // time-based warmup (ms)
    if $warmupMs > 0 {
        let wt = std::time::Duration::from_millis($warmupMs as u64);
        let wend = std::time::Instant::now() + wt;
        while std::time::Instant::now() < wend { _run_workload(); }
    }
    // timed loop
    let t0 = std::time::Instant::now();
    for _ in 0..$rustReps { _run_workload(); }
    let elapsed_ns = t0.elapsed().as_nanos() as f64;
    let ms_per_iter = elapsed_ns / (${rustReps}.0 * 1_000_000.0);
    println!("BENCH_MS: {:.6}", ms_per_iter);
}
"""

  try
    // 1. emit-rust → crateDir (library crate)
    val emitErrBuf = new java.io.ByteArrayOutputStream
    val emitCode = Process(
      Seq(sscPath, "emit-rust", "-o", crateDir.getAbsolutePath, file.getAbsolutePath),
      None
    ).!(ProcessLogger(
      _ => (),
      line => emitErrBuf.write((line + "\n").getBytes)
    ))
    if emitCode != 0 then
      emitErrBuf.toString.linesIterator.foreach(errLog)
      cleanup(); return None

    // 2. Check the generated crate exports workload()
    val genFile = new java.io.File(crateDir, "src/generated/ssc_program.rs")
    if !genFile.exists then { cleanup(); return None }
    val rawGenContent = scala.io.Source.fromFile(genFile).mkString
    if !rawGenContent.contains("workload") then { cleanup(); return None }

    // 2.5 Anti-folding patch: wrap every `let mut <var> = <init>;` and every
    // assignment inside a while body with `std::hint::black_box(...)`.  Without
    // this LLVM -O3 derives closed-form solutions for pure-arithmetic loops
    // (e.g. `for i in 0..N { sum += i }` → Gauss formula = a single constant),
    // making the bench measure ~1ns of `mov reg, const; ret`.  The patch only
    // touches the body of `pub fn workload(...)`; helpers (defined above
    // workload) keep their original code so they can be inlined normally.
    val genContent = patchGenWorkloadForAntiFold(rawGenContent)
    val patchedGen = new java.io.PrintWriter(genFile)
    patchedGen.print(genContent); patchedGen.close()

    // Detect Unit-returning workload: `pub fn workload() {` has no `-> T` in the signature.
    // When Unit, black_box must receive an i64 sentinel rather than the () result.
    val workloadIsUnit = genContent.contains("pub fn workload() {") || genContent.contains("pub fn workload() {\n")

    // Detect seed-threaded workload: a non-empty `pub fn workload(<args>)` arg
    // list (emitted from `def workload(seed: Long)`).  We then pass the opaque
    // atomic seed `_s` so the carried-LCG body can't be constant-folded.
    val workloadHasSeed = raw"pub fn workload\(\s*[A-Za-z_]".r.findFirstIn(genContent).isDefined

    // 3. Patch Cargo.toml: switch from [[bin]] / [lib] to [[bin]] with our main.rs
    val cargoTomlFile = new java.io.File(crateDir, "Cargo.toml")
    val cargoToml = scala.io.Source.fromFile(cargoTomlFile).mkString
    val patched = if cargoToml.contains("[[bin]]") then cargoToml
      else cargoToml
        .replaceAll("""\[lib\][^\[]*""", "")  // remove [lib] section
        .trim + s"\n\n[[bin]]\nname = \"bench_ssc_program\"\npath = \"src/main.rs\"\n"
    val cw = new java.io.PrintWriter(cargoTomlFile)
    cw.print(patched); cw.close()

    // 4. Write our custom main.rs (Unit-aware: sentinel for black_box)
    val mainFile = new java.io.File(crateDir, "src/main.rs")
    val mw = new java.io.PrintWriter(mainFile)
    mw.print(buildMainRs(workloadIsUnit, workloadHasSeed) + mainRsSuffix); mw.close()

    // 5. cargo build --release --quiet
    val cargoBuf = new java.io.ByteArrayOutputStream
    val cargo = sys.env.getOrElse("CARGO", "cargo")
    val buildCode = Process(
      Seq(cargo, "build", "--release", "--quiet"),
      Some(crateDir)
    ).!(ProcessLogger(_ => (), line => cargoBuf.write((line + "\n").getBytes)))
    if buildCode != 0 then
      cargoBuf.toString.linesIterator.foreach(errLog)
      cleanup(); return None

    // 6. Locate and run the binary
    val binExt = if scala.util.Properties.isWin then ".exe" else ""
    val binFile = new java.io.File(crateDir, s"target/release/bench_ssc_program$binExt")
    if !binFile.exists then { cleanup(); return None }

    val runBuf = new java.io.ByteArrayOutputStream
    val runPs  = new java.io.PrintStream(runBuf, true)
    Process(Seq(binFile.getAbsolutePath), None).!(ProcessLogger(runPs.println, errLog))

    val result = runBuf.toString.trim.linesIterator.collectFirst {
      case l if l.startsWith("BENCH_MS:") => l.stripPrefix("BENCH_MS:").trim.toDoubleOption
    }.flatten
    cleanup()
    result
  catch
    case e: Throwable => cleanup(); None

def runSscBenchBackend(sscPath: String, file: java.io.File, b: String): Option[Double] =
  if b == "rust" then return runRustBench(sscPath, file)
  val errLog: String => Unit = line =>
    if !line.startsWith("NOTE: Picked up") && !line.contains("skipping backend plugin") then
      System.err.println(line)
  // "ssc-asm" is a synthetic backend: run ssc --backend ssc with SSC_JIT_BACKEND=asm.
  // "interp-asm" accepted as a backward-compatible alias.
  val (actualBackend, extraEnv) = b match
    case "ssc-asm" | "interp-asm" => ("ssc", Seq("SSC_JIT_BACKEND" -> "asm"))
    case other                    => (other, Nil)
  // --backend is a global flag; must come before the subcommand name.
  // --warmup-time overrides --warmup when present.
  val warmupArgs = warmupTimeMs match
    case Some(ms) => Seq("--warmup-time", ms.toString)
    case None     => Seq("--warmup", warmup.toString)
  val cmd = Seq(sscPath, "--backend", actualBackend, "bench", "--machine") ++
            warmupArgs ++ Seq("--reps", reps.toString, file.getAbsolutePath)
  val buf = new java.io.ByteArrayOutputStream
  val ps  = new java.io.PrintStream(buf, true)
  Process(cmd, None, extraEnv*).!(ProcessLogger(ps.println, errLog))
  parseBenchLine(buf.toString.trim)

def formatTable(
    workloads: Seq[String],
    byBackend: Map[String, Map[String, Option[Double]]]
): String =
  val bLabels   = backends.map(b => s"${displayName(b)} (ms/iter)")
  val nameCells = workloads.map(n => s"`$n`")

  val w0 = ("Workload" +: nameCells).map(_.length).max
  val ws = backends.zipWithIndex.map { (b, i) =>
    val vals = workloads.map(n => byBackend.get(b).flatMap(_.get(n)).flatten.fold("n/a")(fmtMs))
    (bLabels(i) +: vals).map(_.length).max
  }

  def pad(s: String, w: Int)  = s.padTo(w, ' ')
  def rpad(s: String, w: Int) = (" " * (w - s.length)) + s

  val header = s"| ${pad("Workload", w0)} | ${bLabels.zip(ws).map((l, w) => rpad(l, w)).mkString(" | ")} |"
  val sep    = s"| ${"-" * w0} | ${ws.map(w => "-" * w).mkString(" | ")} |"
  val rows   = workloads.zip(nameCells).map { (name, cell) =>
    val vals = backends.zip(ws).map { (b, w) =>
      val v = byBackend.get(b).flatMap(_.get(name)).flatten.fold("n/a")(fmtMs)
      rpad(v, w)
    }
    s"| ${pad(cell, w0)} | ${vals.mkString(" | ")} |"
  }
  (header +: sep +: rows).mkString("\n")

// ── main ─────────────────────────────────────────────────────────────────────

println()
println("ScalaScript benchmark harness")
println("=" * 60)

val sscPath = if Files.exists(sscBin) then sscBin.toString
             else sys.env.getOrElse("SSC", "ssc")

val sscCheck = Process(Seq(sscPath, "help")).!(ProcessLogger(_ => (), _ => ()))
if sscCheck != 0 then
  System.err.println(s"[ERROR] ssc not found at $sscPath. Build with `sbt cli/installBin` or set SSC env.")
  sys.exit(1)

val corpusFiles = Files.list(corpusDir).iterator().asScala
  .filter(p => p.toString.endsWith(".ssc"))
  .map(_.toFile)
  .filter(f => filterNames.isEmpty || filterNames.contains(f.getName.replaceAll("\\.ssc$", "")))
  .toSeq.sortBy(_.getName)

if corpusFiles.isEmpty then
  System.err.println(s"[ERROR] No corpus files found in $corpusDir matching $filterNames")
  sys.exit(1)

println(s"Corpus:   ${corpusFiles.map(_.getName.replaceAll("\\.ssc$","")).mkString(", ")}")
println(s"Backends: ${backends.map(displayName).mkString(", ")}")
val warmupDisplay = warmupTimeMs match
  case Some(ms) => s"${ms}ms (time-based)"
  case None     => s"$warmup iters"
println(s"Warmup:   $warmupDisplay   Reps: $reps")
println(s"ssc:      $sscPath")
println()

// Collect results: byBackend(backend)(workload) = Option[Double]
val byBackend = scala.collection.mutable.Map.empty[String, scala.collection.mutable.Map[String, Option[Double]]]
for b <- backends do byBackend(b) = scala.collection.mutable.Map.empty

for f <- corpusFiles do
  val wname = f.getName.replaceAll("\\.ssc$", "")
  print(s"  $wname:")
  for b <- backends do
    print(s"  ${displayName(b)}...")
    Console.flush()
    val ms = runSscBenchBackend(sscPath, f, b)
    byBackend(b)(wname) = ms
    print(ms.fold("n/a")(fmtMs))
  println()

println()
val workloads = corpusFiles.map(_.getName.replaceAll("\\.ssc$", ""))
val table = formatTable(workloads, byBackend.view.mapValues(_.toMap).toMap)
println(table)
println()

if writeBaseline then
  val ts      = java.time.LocalDate.now.toString
  val content = s"""# Benchmark Baseline — $ts\n\nGenerated by `./bench.sh --baseline`.\nDelegates per-file timing to `ssc bench --machine --backend <b>`.\n\n$table\n"""
  Files.writeString(baselineOut, content)
  println(s"Baseline written to bench/BASELINE.md")
