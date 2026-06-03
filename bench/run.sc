#!/usr/bin/env scala-cli
//> using scala "3.8.3"
//> using javaOpt "-Xss8m"

/**
 * ScalaScript interpreter benchmark harness.
 *
 * Usage (from repo root):
 *   ./bench.sh                              # run all workloads, interpreter only
 *   ./bench.sh arith-loop recursion-fib    # filter by name
 *   ./bench.sh --compare                   # interp vs jvm vs js (wall-clock incl. compile)
 *   ./bench.sh --baseline                  # write bench/BASELINE.md
 *
 * The harness invokes `ssc` (the ScalaScript CLI) for each corpus file,
 * measuring wall-clock time over WARMUP + REPS runs.  It prints a markdown
 * table and optionally writes bench/BASELINE.md when --baseline flag is given.
 *
 * Design notes:
 *   - Uses subprocess `ssc` rather than embedding the interpreter directly so
 *     the benchmark is backend-agnostic and reflects real-world startup cost.
 *   - For tight interpreter microbenchmarks that need JMH-level accuracy, see
 *     runtime/backend/interpreter-bench/ (sbt-jmh module, v1.61.0 deliverable).
 *   - WARMUP runs are discarded; the median of REPS is reported.
 *   - --compare mode uses fewer reps for jvm/js because each run includes
 *     a full scala-cli / node compilation round-trip.
 */

import scala.sys.process.*
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

// ── configuration ────────────────────────────────────────────────────────────

val WARMUP = 2
val REPS   = 7

val root        = Paths.get(getClass.getResource("/").toURI).getParent.getParent.getParent.toAbsolutePath
                  .toString.replaceAll("/bench/.*", "") match
                    case s if s.endsWith("/bench") => s.dropRight(6)
                    case s => s
val corpusDir   = Paths.get(s"$root/bench/corpus")
val sscBin      = Paths.get(s"$root/bin/ssc")
val baselineOut = Paths.get(s"$root/bench/BASELINE.md")

val compareMode   = args.contains("--compare")
val writeBaseline = args.contains("--baseline")
val filterNames   = args.filterNot(_.startsWith("--")).toSet

// ── backend descriptors ──────────────────────────────────────────────────────

case class Backend(label: String, subCmd: Option[String], warmup: Int, reps: Int)

val interpBackend = Backend("interp", None,            WARMUP, REPS)
val jvmBackend    = Backend("jvm",    Some("run-jvm"), 1,      3)
val jsBackend     = Backend("js",     Some("run-js"),  1,      3)

val activeBackends: Seq[Backend] =
  if compareMode then Seq(interpBackend, jvmBackend, jsBackend)
  else Seq(interpBackend)

// ── helpers ──────────────────────────────────────────────────────────────────

case class BenchResult(name: String, medianMs: Long, minMs: Long, maxMs: Long, output: String)

def logStderr(line: String): Unit =
  if !line.startsWith("NOTE: Picked up") && !line.contains("skipping backend plugin") then
    System.err.println(line)

def runOnce(sscPath: String, subCmd: Option[String], corpusFile: String,
            errLog: String => Unit = logStderr): Option[(Long, String)] =
  val cmd = Seq(sscPath) ++ subCmd.toSeq ++ Seq(corpusFile)
  val buf = new java.io.ByteArrayOutputStream
  val ps  = new java.io.PrintStream(buf, true)
  val t0  = System.currentTimeMillis()
  val rc  = Process(cmd).!(ProcessLogger(ps.println, errLog))
  val t1  = System.currentTimeMillis()
  if rc != 0 then None else Some((t1 - t0, buf.toString.trim))

def benchFile(sscPath: String, backend: Backend, file: java.io.File): Option[BenchResult] =
  val name = file.getName.replaceAll("\\.ssc$", "")
  val tag  = if activeBackends.size > 1 then s"$name [${backend.label}]" else name
  val errLog: String => Unit = if backend.subCmd.isDefined then _ => () else logStderr
  print(s"  $tag: warming up... ")
  Console.flush()
  (1 to backend.warmup).foreach(_ => runOnce(sscPath, backend.subCmd, file.getAbsolutePath, errLog))
  val results = (1 to backend.reps).flatMap(_ => runOnce(sscPath, backend.subCmd, file.getAbsolutePath, errLog))
  if results.isEmpty then
    println("n/a")
    None
  else
    val times  = results.map(_._1).sorted
    val output = results.last._2
    val med    = times(times.length / 2)
    val min    = times.head
    val max    = times.last
    println(s"$med ms (min $min, max $max)")
    Some(BenchResult(name, med, min, max, output))

// ── table formatters ─────────────────────────────────────────────────────────

def formatTable(results: Seq[BenchResult], label: String): String =
  val nameCells  = results.map(r => s"`${r.name}`")
  val timeCells  = results.map(r => s"${r.medianMs} (${r.minMs}...${r.maxMs})")
  val colLabel   = s"$label (ms)"

  val w0 = ("Workload" +: nameCells).map(_.length).max
  val w1 = (colLabel   +: timeCells).map(_.length).max

  def pad(s: String, w: Int)  = s.padTo(w, ' ')
  def rpad(s: String, w: Int) = (" " * (w - s.length)) + s

  val header = s"| ${pad("Workload", w0)} | ${rpad(colLabel, w1)} |"
  val sep    = s"| ${"-" * w0} | ${"-" * w1} |"
  val rows   = results.zip(nameCells).zip(timeCells).map { case ((r, name), time) =>
    s"| ${pad(name, w0)} | ${rpad(time, w1)} |"
  }
  (header +: sep +: rows).mkString("\n")

def formatCompareTable(
    workloads: Seq[String],
    byBackend: Map[String, Map[String, Option[Long]]]  // backend → workload → median
): String =
  val bLabels   = activeBackends.map(b => s"${b.label} (ms)")
  val nameCells = workloads.map(n => s"`$n`")

  val w0 = ("Workload" +: nameCells).map(_.length).max
  val ws = activeBackends.zipWithIndex.map { (b, i) =>
    val vals = workloads.map(n => byBackend.get(b.label).flatMap(_.get(n)).flatten.fold("n/a")(_.toString))
    (bLabels(i) +: vals).map(_.length).max
  }

  def pad(s: String, w: Int)  = s.padTo(w, ' ')
  def rpad(s: String, w: Int) = (" " * (w - s.length)) + s

  val header = s"| ${pad("Workload", w0)} | ${bLabels.zip(ws).map((l, w) => rpad(l, w)).mkString(" | ")} |"
  val sep    = s"| ${"-" * w0} | ${ws.map(w => "─" * (w - 1) + ":").mkString(" | ")} |"
  val rows   = workloads.zip(nameCells).map { (name, cell) =>
    val vals = activeBackends.zip(ws).map { (b, w) =>
      val v = byBackend.get(b.label).flatMap(_.get(name)).flatten.fold("n/a")(_.toString)
      rpad(v, w)
    }
    s"| ${pad(cell, w0)} | ${vals.mkString(" | ")} |"
  }
  (header +: sep +: rows).mkString("\n")

// ── main ─────────────────────────────────────────────────────────────────────

println()
println("ScalaScript benchmark harness — v1.61.0")
println("=" * 60)

val sscPath = if Files.exists(sscBin) then sscBin.toString
             else sys.env.getOrElse("SSC", "ssc")  // fallback: PATH

// Verify ssc is usable
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
if compareMode then
  println(s"Backends: ${activeBackends.map(b => s"${b.label} (warmup=${b.warmup} reps=${b.reps})").mkString(", ")}")
  println(s"Note:     jvm/js times include a full compile round-trip")
else
  println(s"Warmup: $WARMUP × $REPS reps; ssc = $sscPath")
println()

if compareMode then
  // Run all backends for each corpus file; collect into backend→workload→median
  val byBackend = scala.collection.mutable.Map.empty[String, scala.collection.mutable.Map[String, Option[Long]]]
  for b <- activeBackends do
    val bMap = scala.collection.mutable.Map.empty[String, Option[Long]]
    for f <- corpusFiles do
      val name = f.getName.replaceAll("\\.ssc$", "")
      bMap(name) = benchFile(sscPath, b, f).map(_.medianMs)
    byBackend(b.label) = bMap
    println()

  val workloads = corpusFiles.map(_.getName.replaceAll("\\.ssc$", ""))
  val table = formatCompareTable(workloads, byBackend.view.mapValues(_.toMap).toMap)
  println(table)
  println()
else
  val results = corpusFiles.flatMap(f => benchFile(sscPath, interpBackend, f))
  val table   = formatTable(results, interpBackend.label)
  println()
  println(table)
  println()

  if writeBaseline then
    val ts      = java.time.LocalDate.now.toString
    val content = s"""# Benchmark Baseline — $ts\n\nGenerated by `./bench.sh --baseline`.\nEach number is the median of $REPS runs after $WARMUP warmup runs, wall-clock ms.\n\n${formatTable(results, interpBackend.label)}\n"""
    Files.writeString(baselineOut, content)
    println(s"Baseline written to bench/BASELINE.md")
