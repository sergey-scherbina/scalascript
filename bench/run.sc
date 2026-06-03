#!/usr/bin/env scala-cli
//> using scala "3.8.3"
//> using javaOpt "-Xss8m"

/**
 * ScalaScript benchmark harness.
 *
 * Usage (from repo root):
 *   ./bench.sh                              # run all workloads, interpreter only
 *   ./bench.sh arith-loop recursion-fib    # filter by name
 *   ./bench.sh --compare                   # interp vs jvm vs js
 *   ./bench.sh --baseline                  # write bench/BASELINE.md
 *
 * Delegates per-file timing to `ssc bench --machine`, which generates a
 * wrapper with warmup+timing harness, runs each backend once, and prints
 * BENCH <backend> <ms> lines.  Compilation is excluded from timing.
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

val compareMode   = args.contains("--compare")
val writeBaseline = args.contains("--baseline")
val filterNames   = args.filterNot(_.startsWith("--")).toSet

val backends = if compareMode then Seq("interp", "jvm", "js") else Seq("interp")

// ── helpers ──────────────────────────────────────────────────────────────────

def fmtMs(ms: Double): String =
  if ms < 1.0 then f"$ms%.3f" else if ms < 10.0 then f"$ms%.2f" else f"$ms%.1f"

def runSscBench(sscPath: String, file: java.io.File): Map[String, Option[Double]] =
  val name = file.getName.replaceAll("\\.ssc$", "")
  print(s"  $name: ")
  Console.flush()
  val extraFlags = if compareMode then Seq.empty else Seq("--no-jvm", "--no-js")
  val cmd = Seq(sscPath, "bench", "--machine") ++ extraFlags ++ Seq(file.getAbsolutePath)
  val buf = new java.io.ByteArrayOutputStream
  val ps  = new java.io.PrintStream(buf, true)
  val errLog: String => Unit = line =>
    if !line.startsWith("NOTE: Picked up") && !line.contains("skipping backend plugin") then
      System.err.println(line)
  Process(cmd).!(ProcessLogger(ps.println, errLog))
  val output = buf.toString.trim
  val results: Map[String, Option[Double]] = output.linesIterator.collect {
    case line if line.startsWith("BENCH ") =>
      val parts = line.split(" ", 3)
      if parts.length == 3 then parts(1) -> parts(2).toDoubleOption
      else parts(1) -> None
  }.toMap
  val summary = backends.map(b => results.get(b).flatten.fold("n/a")(fmtMs)).mkString("  ")
  println(summary)
  results

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
println(s"ssc:      $sscPath")
println()

val byBackend = scala.collection.mutable.Map.empty[String, scala.collection.mutable.Map[String, Option[Double]]]
for b <- backends do byBackend(b) = scala.collection.mutable.Map.empty

for f <- corpusFiles do
  val results = runSscBench(sscPath, f)
  val name = f.getName.replaceAll("\\.ssc$", "")
  for b <- backends do
    byBackend(b)(name) = results.get(b).flatten

println()
val workloads = corpusFiles.map(_.getName.replaceAll("\\.ssc$", ""))
val table = formatTable(workloads, byBackend.view.mapValues(_.toMap).toMap)
println(table)
println()

if writeBaseline then
  val ts      = java.time.LocalDate.now.toString
  val content = s"""# Benchmark Baseline — $ts\n\nGenerated by `./bench.sh --baseline`.\nDelegates per-file timing to `ssc bench --machine`.\n\n$table\n"""
  Files.writeString(baselineOut, content)
  println(s"Baseline written to bench/BASELINE.md")
