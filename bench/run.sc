#!/usr/bin/env scala-cli
//> using scala "3.8.3"
//> using javaOpt "-Xss8m"

/**
 * ScalaScript benchmark harness.
 *
 * Usage (from repo root):
 *   ./bench.sh                              # compare all backends (interp, jvm, js)
 *   ./bench.sh --asm                        # add interp-asm column (AsmJitBackend)
 *   ./bench.sh arith-loop recursion-fib    # filter by workload name
 *   ./bench.sh --backend interp            # single backend only
 *   ./bench.sh --backend interp-asm        # ASM JIT backend only
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
val includeAsm    = args.contains("--asm")

// --backend <b>: limit to a single backend; default is all three.
// Synthetic backend "interp-asm" runs ssc --backend interp with SSC_JIT_BACKEND=asm.
val backendFlag: Option[String] =
  val idx = args.indexOf("--backend")
  if idx >= 0 && idx + 1 < args.length then Some(args(idx + 1))
  else args.collectFirst { case s if s.startsWith("--backend=") => s.stripPrefix("--backend=") }

val backends: Seq[String] = backendFlag match
  case Some(b) => Seq(b)
  case None    => Seq("interp", "jvm", "js") ++ (if includeAsm then Seq("interp-asm") else Nil)

// --warmup N / --reps N / --warmup-time N: pass-through to ssc bench (defaults mirror BenchCmd)
def parseInt2(flag: String, default: Int): Int =
  val idx = args.indexOf(flag)
  if idx >= 0 && idx + 1 < args.length then args(idx + 1).toIntOption.getOrElse(default)
  else args.collectFirst { case s if s.startsWith(flag + "=") => s.stripPrefix(flag + "=").toIntOption.getOrElse(default) }
       .getOrElse(default)

val warmup = parseInt2("--warmup", 5)
val reps   = parseInt2("--reps",  20)

// --warmup-time N: time-based warmup in milliseconds.
// Default: 3000 ms unless --warmup (count-based) is explicitly passed.
val warmupTimeMs: Option[Long] =
  val explicit =
    val idx = args.indexOf("--warmup-time")
    if idx >= 0 && idx + 1 < args.length then args(idx + 1).toLongOption
    else args.collectFirst { case s if s.startsWith("--warmup-time=") =>
      s.stripPrefix("--warmup-time=").toLongOption }.flatten
  if explicit.isDefined then explicit
  else if args.exists(a => a == "--warmup" || a.startsWith("--warmup=")) then None
  else Some(3000L)

// non-flag args that don't belong to --backend/--warmup/--warmup-time/--reps are workload filters
val filterNames: Set[String] =
  val backendVal    = backendFlag.getOrElse("")
  val warmupVal     = args.indexOf("--warmup")      match { case i if i >= 0 && i+1 < args.length => args(i+1); case _ => "" }
  val warmupTimeVal = args.indexOf("--warmup-time") match { case i if i >= 0 && i+1 < args.length => args(i+1); case _ => "" }
  val repsVal       = args.indexOf("--reps")        match { case i if i >= 0 && i+1 < args.length => args(i+1); case _ => "" }
  args.filterNot(a => a.startsWith("--") || a == backendVal || a == warmupVal || a == warmupTimeVal || a == repsVal).toSet

// ── helpers ──────────────────────────────────────────────────────────────────

def fmtMs(ms: Double): String =
  if ms < 1.0 then f"$ms%.3f" else if ms < 10.0 then f"$ms%.2f" else f"$ms%.1f"

def parseBenchLine(output: String): Option[Double] =
  output.linesIterator.collectFirst {
    case line if line.startsWith("BENCH ") =>
      val parts = line.split(" ", 3)
      if parts.length == 3 then parts(2).toDoubleOption else None
  }.flatten

def runSscBenchBackend(sscPath: String, file: java.io.File, b: String): Option[Double] =
  val errLog: String => Unit = line =>
    if !line.startsWith("NOTE: Picked up") && !line.contains("skipping backend plugin") then
      System.err.println(line)
  // "interp-asm" is a synthetic backend: run ssc --backend interp with SSC_JIT_BACKEND=asm.
  val (actualBackend, extraEnv) = b match
    case "interp-asm" => ("interp", Seq("SSC_JIT_BACKEND" -> "asm"))
    case other        => (other,    Nil)
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
  val bLabels   = backends.map(b => s"$b (ms/iter)")
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
println(s"Backends: ${backends.mkString(", ")}")
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
    print(s"  $b...")
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
