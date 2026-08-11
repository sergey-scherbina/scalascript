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
 *   ./bench.sh --v2-bytecode                # v2 VM + v2 JVM bytecode lane
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
// `bench` is a TOOLS-tier command. After the standard/tools split, `bin/ssc` (the
// standard entry) answers `--backend` with "requires the optional ScalaScript
// tools/compatibility tier" on stderr and exits — so every cell of this harness
// silently became `n/a`. Prefer `bin/ssc-tools`, and probe rather than assume
// (below): a launcher that cannot run `bench --backend` is not a usable ssc here.
val sscBinCandidates = Seq(s"$root/bin/ssc-tools", s"$root/bin/ssc").map(Paths.get(_))
val baselineOut = Paths.get(s"$root/bench/BASELINE.md")

// ── arg parsing ───────────────────────────────────────────────────────────────

val writeBaseline = args.contains("--baseline")
val v2BackendMode = args.contains("--v2-backends")
val v2BytecodeMode = args.contains("--v2-bytecode")
/** `--strict-front`: refuse to measure any v2 row F did not compile.
 *
 *  The FRONT column already NAMES the fallback rows, which is enough when a person reads the table.
 *  It is not enough when a script reads it, or when the table is skimmed for the one row that
 *  moved: a `GAP` row still prints a plausible number, and that number belongs to the reference
 *  front. This makes such a row fail loudly instead — the right default for an A/B where the whole
 *  claim is "F got faster". Off unless asked, because on a mixed corpus most rows are legitimately
 *  not F's and refusing them all would just stop the run. */
val strictFront = args.contains("--strict-front")

// --backend <b>: limit to a single backend; default is all three.
// Synthetic backend "interp-asm" runs ssc --backend interp with SSC_JIT_BACKEND=asm.
val backendFlag: Option[String] =
  val idx = args.indexOf("--backend")
  if idx >= 0 && idx + 1 < args.length then Some(args(idx + 1))
  else args.collectFirst { case s if s.startsWith("--backend=") => s.stripPrefix("--backend=") }

// --backends a,b,c: an explicit comma-separated column set. Needed for
// cross-tier comparisons the single-backend flag and the two canned v2 modes
// cannot express (e.g. `ssc,v2,v2-bytecode` — v1 interp vs v2 VM vs v2 bytecode
// side by side in ONE table, so every row is measured on the same machine state).
val backendsFlag: Option[Seq[String]] =
  val idx = args.indexOf("--backends")
  val raw =
    if idx >= 0 && idx + 1 < args.length then Some(args(idx + 1))
    else args.collectFirst { case s if s.startsWith("--backends=") => s.stripPrefix("--backends=") }
  raw.map(_.split(",").iterator.map(_.trim).filter(_.nonEmpty).toSeq).filter(_.nonEmpty)

val backends: Seq[String] = backendsFlag match
  case Some(bs) => bs
  case None => backendFlag match
    case Some(b)             => Seq(b)
    case None if v2BackendMode => Seq("v2", "v2-jvm", "v2-rust")
    case None if v2BytecodeMode => Seq("v2", "v2-bytecode")
    // THE DEFAULT SPANS ALL THREE VERSIONS, which is the question people actually bring to this
    // table: is the new one faster than the old one. Until 2026-08-07 the default was the six v1/v2
    // columns and v3 had no column at all, so "compare the versions" meant knowing to pass a flag
    // that did not exist. Ordered v1 → v2 → v3 so the table reads left to right as the project's
    // own history.
    //
    // v2-bytecode is in and v2-jvm/v2-rust are not: bytecode is v2's own execution lane and belongs
    // beside the VM, while the other two are source-emitting backends already reachable through
    // `--v2-backends` and each costs a full external toolchain per row. `--backends a,b,c` still
    // expresses any set this default does not.
    case None                => Seq("ssc", "ssc-asm", "jvm", "js", "rust", "v2", "v2-bytecode", "v3")

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

// non-flag args that don't belong to --backend(s)/--warmup/--warmup-time/--reps are workload filters
val filterNames: Set[String] =
  val backendVal    = backendFlag.getOrElse("")
  val backendsVal   = args.indexOf("--backends")    match { case i if i >= 0 && i+1 < args.length => args(i+1); case _ => "" }
  val warmupVal     = args.indexOf("--warmup")      match { case i if i >= 0 && i+1 < args.length => args(i+1); case _ => "" }
  val warmupTimeVal = args.indexOf("--warmup-time") match { case i if i >= 0 && i+1 < args.length => args(i+1); case _ => "" }
  val repsVal       = args.indexOf("--reps")        match { case i if i >= 0 && i+1 < args.length => args(i+1); case _ => "" }
  args.filterNot(a => a.startsWith("--") || a == backendVal || a == backendsVal || a == warmupVal || a == warmupTimeVal || a == repsVal).toSet

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

/** The v3 column. v3 is a different PRODUCT, not another backend of the same CLI: it has its own
 *  driver (`v3/ssc3`), its own IR and its own executor, and `bin/ssc --backend v3` does not exist.
 *
 *  It is not yet timed by the shared wrapper the other columns use. The reason this comment gave
 *  until 2026-08-11 — "v3 has no clock" — is STALE: v3 has had one since 2026-08-09, spelled
 *  `nanoTime()` in the language and `io.nanoTime` in the prim table. Two measured gaps remain,
 *  and they are small and named rather than a standing exception:
 *
 *    1. THE SPELLING. The wrapper must keep `System.nanoTime()`, because the js backend maps only
 *       that spelling — it compiles to `Math.round(performance.now() * 1e6)`, while a bare
 *       `nanoTime()` is emitted verbatim as an undefined JS function and dies at run time. So the
 *       wrapper cannot move to v3's spelling; v3 has to resolve Scala's. Today it answers
 *       `unknown name 'System'` (`v3/src/Lower.scala`, the `builtins` table).
 *    2. THE FALLBACK SINK. For a workload returning anything other than Int/Long/Double/Boolean
 *       the wrapper declares `var _ssc_sink: Any = null`, and v3 has no `null`. This one is the
 *       WRAPPER's to fix, not v3's: a null-free language is a feature, and `= 0` serves every lane.
 *
 *  Everything else the wrapper uses already runs on v3 — measured 2026-08-11, not assumed:
 *  underscore numeric literals (`100_000_000L`), nested `while`, a `def` with a parameter,
 *  `Long`/`Double` vars, and string concatenation of both.
 *
 *  `ssc3 bench` does the loop driver-side meanwhile, keeping the two things that make the numbers
 *  comparable: compilation is excluded (lower + verify happen before the clock starts) and the
 *  window doubles until it reaches 100 ms.
 *
 *  The asymmetry that costs, stated because a reader will otherwise assume it away: v3's rep
 *  counter, seed increment and sink update are a HOST loop, so v3 is not charged for executing
 *  them while every other column is. The direction is known — it flatters v3, and most on the
 *  cheapest rows — but the SIZE is unmeasured, so no adjective here is load-bearing. Closing gaps
 *  1 and 2 is what makes it zero; that is §55 B1.
 *
 *  A blank v3 cell means the row produced no number, and as of 2026-08-11 that is no longer
 *  usually a front refusal: v3 ACCEPTS all 36 corpus files. **The count is not repeated here on
 *  purpose.** It said "23 of the 36 as of 2026-08-07" and was read as v3 barely covering the
 *  table while the real figure had reached 34 — a number in a comment rots, and this one rotted
 *  by half in four days.
 *
 *  `v3/bench-corpus-gate.sh` computes it on every run and names the rows that do not compute; it
 *  is also what now fails when a row STOPS computing, which is how `typeclass-fold` was able to
 *  regress for three days while the conformance number `N` never moved.
 */
def runV3Bench(file: java.io.File): Option[Double] =
  val buf = new java.io.ByteArrayOutputStream
  val ps  = new java.io.PrintStream(buf, true)
  try
    Process(
      Seq(s"$root/v3/ssc3", "bench", "--warmup", warmup.toString, "--reps", reps.toString,
          file.getAbsolutePath),
      new java.io.File(root)
    ).!(ProcessLogger(ps.println, _ => ()))
    buf.toString.linesIterator.collectFirst {
      case l if l.startsWith("BENCH_MS:") => l.stripPrefix("BENCH_MS:").trim.toDoubleOption
    }.flatten
  catch case _: Throwable => None

def runSscBenchBackend(sscPath: String, file: java.io.File, b: String): Option[Double] =
  if b == "rust" then return runRustBench(sscPath, file)
  if b == "v3" then return runV3Bench(file)
  val errLog: String => Unit = line =>
    if !line.startsWith("NOTE: Picked up") && !line.contains("skipping backend plugin") then
      System.err.println(line)
  // "ssc-asm" is a synthetic backend: run ssc --backend ssc with SSC_JIT_BACKEND=asm.
  // "interp-asm" accepted as a backward-compatible alias.
  val (actualBackend, extraEnv) = b match
    case "ssc-asm" | "interp-asm" => ("ssc", Seq("SSC_JIT_BACKEND" -> "asm"))
    case other                    => (other, Nil)
  // Only the v2 lane has a front to be strict ABOUT; setting it for v1 backends would be noise.
  val strictEnv =
    if strictFront && b.startsWith("v2") then Seq("SSC_FRONT_STRICT" -> "1") else Nil
  // --backend is a global flag; must come before the subcommand name.
  // --warmup-time overrides --warmup when present.
  val warmupArgs = warmupTimeMs match
    case Some(ms) => Seq("--warmup-time", ms.toString)
    case None     => Seq("--warmup", warmup.toString)
  val cmd = Seq(sscPath, "--backend", actualBackend, "bench", "--machine") ++
            warmupArgs ++ Seq("--reps", reps.toString, file.getAbsolutePath)
  val buf = new java.io.ByteArrayOutputStream
  val ps  = new java.io.PrintStream(buf, true)
  Process(cmd, None, (extraEnv ++ strictEnv)*).!(ProcessLogger(ps.println, errLog))
  parseBenchLine(buf.toString.trim)

/** Which FRONT compiled this workload — `F`, or a fallback verdict (`GAP`, `BOTH-UNBOUND`, …).
 *
 *  This column exists because the fallback is SILENT BY DESIGN: when F declines a program the
 *  reference front compiles it, the output is correct, and nothing in the table looks wrong. So a
 *  perf number can be a measurement of a different compiler than the reader assumes.
 *
 *  Measured 2026-07-31: **5 of 36 corpus rows are not compiled by F**, and three of those are among
 *  the worst rows in the ratio table. Two wrong conclusions were drawn from that in one sitting
 *  before anyone thought to ask which front had run. Asking costs one process per row.
 */
def frontOf(file: java.io.File): String =
  val buf = new java.io.ByteArrayOutputStream
  val ps  = new java.io.PrintStream(buf, true)
  try
    // NOT `sscPath`: that resolves to bin/ssc-tools, whose `info` is the ARTIFACT inspector
    // (.scim/.scir/…) and does not know --front-report. The front report lives on bin/ssc.
    Process(Seq(s"$root/bin/ssc", "info", "--front-report", file.getAbsolutePath))
      .!(ProcessLogger(ps.println, _ => ()))
    buf.toString.linesIterator.toSeq.lastOption
      .map(_.split("\t").toSeq).collect { case _ +: verdict +: _ => verdict.trim }
      .getOrElse("?")
  catch case _: Throwable => "?"

def formatTable(
    workloads: Seq[String],
    byBackend: Map[String, Map[String, Option[Double]]],
    fronts: Map[String, String] = Map.empty
): String =
  val bLabels   = backends.map(b => s"${displayName(b)} (ms/iter)")
  val nameCells = workloads.map(n => s"`$n`")
  val showFront = fronts.nonEmpty
  val frontCells = workloads.map(n => fronts.getOrElse(n, "?"))
  val wF = ("front" +: frontCells).map(_.length).max

  val w0 = ("Workload" +: nameCells).map(_.length).max
  val ws = backends.zipWithIndex.map { (b, i) =>
    val vals = workloads.map(n => byBackend.get(b).flatMap(_.get(n)).flatten.fold("n/a")(fmtMs))
    (bLabels(i) +: vals).map(_.length).max
  }

  def pad(s: String, w: Int)  = s.padTo(w, ' ')
  def rpad(s: String, w: Int) = (" " * (w - s.length)) + s

  val fHead  = if showFront then s" ${pad("front", wF)} |" else ""
  val fSep   = if showFront then s" ${"-" * wF} |" else ""
  val header = s"| ${pad("Workload", w0)} |$fHead ${bLabels.zip(ws).map((l, w) => rpad(l, w)).mkString(" | ")} |"
  val sep    = s"| ${"-" * w0} |$fSep ${ws.map(w => "-" * w).mkString(" | ")} |"
  val rows   = workloads.zip(nameCells).map { (name, cell) =>
    val vals = backends.zip(ws).map { (b, w) =>
      val v = byBackend.get(b).flatMap(_.get(name)).flatten.fold("n/a")(fmtMs)
      rpad(v, w)
    }
    val fCell = if showFront then s" ${pad(fronts.getOrElse(name, "?"), wF)} |" else ""
    s"| ${pad(cell, w0)} |$fCell ${vals.mkString(" | ")} |"
  }
  (header +: sep +: rows).mkString("\n")

// ── main ─────────────────────────────────────────────────────────────────────

println()
println("ScalaScript benchmark harness")
println("=" * 60)

// Pick the launcher by CAPABILITY, not by existence. `ssc help` exits 0 on the
// standard tier too, so the old existence check happily selected a launcher that
// rejects every `--backend` invocation this harness makes. Probe the real thing:
// `--backend ssc bench --machine` on a trivial program must print a BENCH line.
def canBench(bin: String): Boolean =
  val probe = Paths.get(s"$root/bench/corpus/hello-world.ssc")
  if !Files.exists(probe) then return Process(Seq(bin, "help")).!(ProcessLogger(_ => (), _ => ())) == 0
  val buf = new java.io.ByteArrayOutputStream
  val ps  = new java.io.PrintStream(buf, true)
  try
    Process(Seq(bin, "--backend", "ssc", "bench", "--machine", "--warmup", "0", "--reps", "1",
                probe.toAbsolutePath.toString)).!(ProcessLogger(ps.println, _ => ()))
    buf.toString.linesIterator.exists(_.startsWith("BENCH "))
  catch case _: Throwable => false

val sscPath =
  val fromEnv = sys.env.get("SSC").filter(canBench)
  val fromBin = sscBinCandidates.filter(p => Files.exists(p)).map(_.toString).find(canBench)
  fromEnv.orElse(fromBin).getOrElse {
    val tried = (sys.env.get("SSC").toSeq ++ sscBinCandidates.map(_.toString)).mkString(", ")
    System.err.println(s"[ERROR] no ssc launcher here can run `bench --backend`. Tried: $tried")
    System.err.println(s"        `bench` is a TOOLS-tier command — build with `sbt installBin` so bin/ssc-tools exists,")
    System.err.println(s"        or point SSC= at a launcher that has it.")
    sys.exit(1)
  }

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
// The front column is computed only when a v2 lane is being measured — it is the v2 lanes whose
// numbers can silently be the fallback front's. One extra process per row, and only then.
val wantFront = backends.exists(b => b.startsWith("v2"))
val frontByName: Map[String, String] =
  if !wantFront then Map.empty
  // From `workloads`, NOT `corpusFiles`: the latter is a single-use Iterator that is already
  // exhausted by the time the table is built, so mapping it here yielded an EMPTY map and the
  // column silently vanished. (Caught by the column rendering `?` and then disappearing.)
  else workloads.map(n => n -> frontOf(corpusDir.resolve(s"$n.ssc").toFile)).toMap
val table = formatTable(workloads, byBackend.view.mapValues(_.toMap).toMap, frontByName)
println(table)
println()

// ── dead-lane detector (AGENTS.md "apparatus must COMPARE, never PRE-JUDGE") ──
// `n/a` is the HONEST signal for a workload a backend genuinely cannot run (rust has
// no LazyList, js has no LazyList.from). It is a LIE when the backend is broken: on
// 2026-07-28 `ssc bench --backend v2` had been emitting nothing at all — its wrapper
// calls System.nanoTime(), which the v2 native lane turned into an unhandled effect —
// and every v2 sweep since had read as "unsupported" instead of "not measured".
// A per-workload blank and a WHOLE-LANE blank are structurally different claims, so
// they must not print the same character. Fail loud on the second one.
val blanks = backends.map(b => b -> workloads.filter(n => byBackend(b).get(n).flatten.isEmpty))
val deadLanes = blanks.collect { case (b, miss) if workloads.length >= 2 && miss.length == workloads.length => b }
for (b, miss) <- blanks if miss.nonEmpty && miss.length < workloads.length do
  println(s"note: $b produced no measurement for ${miss.length}/${workloads.length} workload(s): ${miss.mkString(", ")}")
if deadLanes.nonEmpty then
  System.err.println()
  for b <- deadLanes do
    System.err.println(s"[ERROR] backend '$b' produced NO measurement on ANY of the ${workloads.length} workloads.")
    System.err.println(s"        That is a DEAD LANE, not an unsupported workload — the whole column is unmeasured, not slow.")
    System.err.println(s"        Reproduce one case directly and read its output:")
    System.err.println(s"          SSC_BENCH_DEBUG=1 $sscPath --backend $b bench ${corpusFiles.head.getAbsolutePath}")
  System.exit(1)

if writeBaseline then
  val ts      = java.time.LocalDate.now.toString
  val content = s"""# Benchmark Baseline — $ts\n\nGenerated by `./bench.sh --baseline`.\nDelegates per-file timing to `ssc bench --machine --backend <b>`.\n\n$table\n"""
  Files.writeString(baselineOut, content)
  println(s"Baseline written to bench/BASELINE.md")
