#!/usr/bin/env scala-cli
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

// --backend <b>: limit to a single backend; default is all three.
// Synthetic backend "interp-asm" runs ssc --backend interp with SSC_JIT_BACKEND=asm.
val backendFlag: Option[String] =
  val idx = args.indexOf("--backend")
  if idx >= 0 && idx + 1 < args.length then Some(args(idx + 1))
  else args.collectFirst { case s if s.startsWith("--backend=") => s.stripPrefix("--backend=") }

val backends: Seq[String] = backendFlag match
  case Some(b) => Seq(b)
  case None    => Seq("ssc", "ssc-asm", "jvm", "js", "rust")

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

// LLVM -O3 anti-folding: wrap each assignment inside `pub fn workload(...)`'s
// body with `std::hint::black_box(...)`.  This stops scalar-evolution from
// deriving closed-form (Gauss-like) solutions that replace the entire loop
// with a single constant load.  Without this, pure-arith bench workloads
// report ~1ns/iter on Rust — measuring only `mov reg, const; ret`.
//
// We only patch the `workload` function body.  Helper functions defined
// before `workload` keep their original code so inlining still works.
def patchGenWorkloadForAntiFold(src: String): String =
  val workloadIdx = src.indexOf("pub fn workload(")
  if workloadIdx < 0 then return src
  // Find the matching `}` for the workload function by brace-counting from
  // its opening `{`.  We deliberately stop at the function-closing brace so
  // helpers AFTER workload (if any) are left untouched.
  val openBrace = src.indexOf('{', workloadIdx)
  if openBrace < 0 then return src
  var depth = 1
  var i = openBrace + 1
  while i < src.length && depth > 0 do
    src.charAt(i) match
      case '{' => depth += 1
      case '}' => depth -= 1
      case _   => ()
    i += 1
  if depth != 0 then return src
  val bodyStart = openBrace + 1
  val bodyEnd   = i - 1
  val before    = src.substring(0, bodyStart)
  val body      = src.substring(bodyStart, bodyEnd)
  val after     = src.substring(bodyEnd)

  // Patch every `<name> = <expr>;` assignment to `<name> = std::hint::black_box(<expr>);`.
  // This includes both `let mut x = init;` (initial values) and `x = …;`
  // reassignments inside the loop.  The negated lookahead avoids re-wrapping
  // an already-black-boxed expression on repeated patches.
  //
  // The rhs match excludes `{`, `}`, and `;` to avoid spanning across closure
  // bodies (e.g. `let s = .map(move |i| { ... }).fold(0, move |a,b| { ... });`
  // would otherwise be miscaptured).  This means closure-containing rhs are
  // skipped — acceptable since their inner closure bodies are not on the
  // critical anti-folding path; LLVM still gets the surrounding wrapper.
  val assignRe = """(?m)(let mut [A-Za-z_]\w*(?:: [A-Za-z_:<>0-9, ]+)? = |[A-Za-z_]\w* = )(?!std::hint::black_box\()([^{};]+);""".r
  val patchedBody = assignRe.replaceAllIn(body, m =>
    val lhs = scala.util.matching.Regex.quoteReplacement(m.group(1))
    val rhs = scala.util.matching.Regex.quoteReplacement(m.group(2))
    s"${lhs}std::hint::black_box($rhs);"
  )
  before + patchedBody + after

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
  def buildMainRs(isUnit: Boolean): String =
    val workloadCall =
      if isUnit then "generated::ssc_program::workload(); std::hint::black_box(0i64);"
      else            "let r = generated::ssc_program::workload(); std::hint::black_box(r);"
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
    mw.print(buildMainRs(workloadIsUnit) + mainRsSuffix); mw.close()

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
