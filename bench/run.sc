#!/usr/bin/env scala-cli
//> using scala "3.8.3"
//> using javaOpt "-Xss8m"

/**
 * ScalaScript benchmark harness — in-process edition.
 *
 * Usage (from repo root):
 *   ./bench.sh                              # run all workloads, interpreter only
 *   ./bench.sh arith-loop recursion-fib    # filter by name
 *   ./bench.sh --compare                   # interp vs jvm vs js
 *   ./bench.sh --baseline                  # write bench/BASELINE.md
 *
 * Each corpus file manages its own warmup + timing internally and prints
 * a final line "BENCH_MS: X.X" (ms per iteration).  The harness runs each
 * file once per backend and parses that line, so process startup is never
 * counted in the measurement.
 *
 * Backend emit strategy (compilation excluded from timing):
 *   interp — ssc <file.ssc>     (interpreter process; internal warmup warms interp)
 *   jvm    — ssc emit-scala → stable /tmp/…sc → scala-cli (bytecode cached by scala-cli)
 *   js     — ssc emit-js    → stable /tmp/…cjs → node
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

// ── backend descriptors ──────────────────────────────────────────────────────

// cmd: (sscPath, effectiveFile) => command to run once
case class Backend(label: String, cmd: (String, String) => Seq[String])

val interpBackend = Backend("interp", (ssc, f) => Seq(ssc, f))
// JVM: emit-scala → stable temp .sc; scala-cli caches bytecode so the
// in-corpus warmup iterations run on JIT-compiled code.
val jvmBackend    = Backend("jvm",    (_,   f) => Seq("scala-cli", f))
// JS: emit-js → stable temp .cjs; V8 JITs the code during in-corpus warmup.
val jsBackend     = Backend("js",     (_,   f) => Seq("node", f))

val activeBackends: Seq[Backend] =
  if compareMode then Seq(interpBackend, jvmBackend, jsBackend)
  else Seq(interpBackend)

// ── helpers ──────────────────────────────────────────────────────────────────

case class BenchResult(name: String, msPerIter: Double, output: String)

def logStderr(line: String): Unit =
  if !line.startsWith("NOTE: Picked up") && !line.contains("skipping backend plugin") then
    System.err.println(line)

def parseBenchMs(output: String): Option[Double] =
  output.linesIterator
    .filter(_.startsWith("BENCH_MS:"))
    .flatMap(l => l.stripPrefix("BENCH_MS:").trim.toDoubleOption)
    .toSeq.lastOption

def runFile(cmd: Seq[String], errLog: String => Unit): Option[String] =
  val buf = new java.io.ByteArrayOutputStream
  val ps  = new java.io.PrintStream(buf, true)
  val rc  = Process(cmd).!(ProcessLogger(ps.println, errLog))
  if rc != 0 then None else Some(buf.toString.trim)

def benchFile(sscPath: String, backend: Backend, file: java.io.File): Option[BenchResult] =
  val name   = file.getName.replaceAll("\\.ssc$", "")
  val tag    = if activeBackends.size > 1 then s"$name [${backend.label}]" else name
  val errLog: String => Unit = if backend.label == "interp" then logStderr else _ => ()

  // Emit to a stable temp file for JVM/JS so transpilation is not counted.
  // Returns None if the backend does not support this file (e.g. effects in JvmGen).
  val effectiveFile: Option[String] = backend.label match
    case "jvm" =>
      val tmp = java.io.File(s"/tmp/ssc-bench-jvm-$name.sc")
      val rc  = Process(Seq(sscPath, "emit-scala", file.getAbsolutePath))
                  .#>(tmp).!(ProcessLogger(_ => (), _ => ()))
      if rc != 0 then None else Some(tmp.getAbsolutePath)
    case "js" =>
      val tmp = java.io.File(s"/tmp/ssc-bench-js-$name.cjs")
      val rc  = Process(Seq(sscPath, "emit-js", file.getAbsolutePath))
                  .#>(tmp).!(ProcessLogger(_ => (), _ => ()))
      if rc != 0 then None else Some(tmp.getAbsolutePath)
    case _ => Some(file.getAbsolutePath)

  print(s"  $tag: running... ")
  Console.flush()

  effectiveFile match
    case None =>
      println("n/a")
      None
    case Some(f) =>
      val runCmd = backend.cmd(sscPath, f)
      runFile(runCmd, errLog) match
        case None =>
          println("n/a")
          None
        case Some(output) =>
          parseBenchMs(output) match
            case None =>
              println("n/a (no BENCH_MS line)")
              None
            case Some(ms) =>
              val fmt = if ms < 1.0 then f"$ms%.3f" else if ms < 10.0 then f"$ms%.2f" else f"$ms%.1f"
              println(s"$fmt ms/iter")
              Some(BenchResult(name, ms, output))

// ── table formatters ─────────────────────────────────────────────────────────

def fmtMs(ms: Double): String =
  if ms < 1.0 then f"$ms%.3f" else if ms < 10.0 then f"$ms%.2f" else f"$ms%.1f"

def formatTable(results: Seq[BenchResult], label: String): String =
  val nameCells  = results.map(r => s"`${r.name}`")
  val timeCells  = results.map(r => fmtMs(r.msPerIter))
  val colLabel   = s"$label (ms/iter)"

  val w0 = ("Workload" +: nameCells).map(_.length).max
  val w1 = (colLabel   +: timeCells).map(_.length).max

  def pad(s: String, w: Int)  = s.padTo(w, ' ')
  def rpad(s: String, w: Int) = (" " * (w - s.length)) + s

  val header = s"| ${pad("Workload", w0)} | ${rpad(colLabel, w1)} |"
  val sep    = s"| ${"-" * w0} | ${"-" * w1} |"
  val rows   = results.zip(nameCells).zip(timeCells).map { case ((_, name), time) =>
    s"| ${pad(name, w0)} | ${rpad(time, w1)} |"
  }
  (header +: sep +: rows).mkString("\n")

def formatCompareTable(
    workloads: Seq[String],
    byBackend: Map[String, Map[String, Option[Double]]]
): String =
  val bLabels   = activeBackends.map(b => s"${b.label} (ms/iter)")
  val nameCells = workloads.map(n => s"`$n`")

  val w0 = ("Workload" +: nameCells).map(_.length).max
  val ws = activeBackends.zipWithIndex.map { (b, i) =>
    val vals = workloads.map(n => byBackend.get(b.label).flatMap(_.get(n)).flatten.fold("n/a")(fmtMs))
    (bLabels(i) +: vals).map(_.length).max
  }

  def pad(s: String, w: Int)  = s.padTo(w, ' ')
  def rpad(s: String, w: Int) = (" " * (w - s.length)) + s

  val header = s"| ${pad("Workload", w0)} | ${bLabels.zip(ws).map((l, w) => rpad(l, w)).mkString(" | ")} |"
  val sep    = s"| ${"-" * w0} | ${ws.map(w => "─" * (w - 1) + ":").mkString(" | ")} |"
  val rows   = workloads.zip(nameCells).map { (name, cell) =>
    val vals = activeBackends.zip(ws).map { (b, w) =>
      val v = byBackend.get(b.label).flatMap(_.get(name)).flatten.fold("n/a")(fmtMs)
      rpad(v, w)
    }
    s"| ${pad(cell, w0)} | ${vals.mkString(" | ")} |"
  }
  (header +: sep +: rows).mkString("\n")

// ── main ─────────────────────────────────────────────────────────────────────

println()
println("ScalaScript benchmark harness — v1.62.0")
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

println(s"Corpus: ${corpusFiles.map(_.getName.replaceAll("\\.ssc$","")).mkString(", ")}")
if compareMode then
  println(s"Mode:   in-process (warmup + timing inside each corpus file)")
  println(s"Backends: ${activeBackends.map(_.label).mkString(", ")}")
else
  println(s"Mode:   in-process (warmup + timing inside each corpus file); ssc = $sscPath")
println()

if compareMode then
  val byBackend = scala.collection.mutable.Map.empty[String, scala.collection.mutable.Map[String, Option[Double]]]
  for b <- activeBackends do
    val bMap = scala.collection.mutable.Map.empty[String, Option[Double]]
    for f <- corpusFiles do
      val name = f.getName.replaceAll("\\.ssc$", "")
      bMap(name) = benchFile(sscPath, b, f).map(_.msPerIter)
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
    val content = s"""# Benchmark Baseline — $ts\n\nGenerated by `./bench.sh --baseline`.\nIn-process timing: warmup + measurement inside each corpus file.\n\n${formatTable(results, interpBackend.label)}\n"""
    Files.writeString(baselineOut, content)
    println(s"Baseline written to bench/BASELINE.md")
