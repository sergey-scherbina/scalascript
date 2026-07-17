#!/usr/bin/env -S scala-cli --server=false
//> using toolkit 0.9.2

// Runs all ScalaScript examples through the interpreter (INT), JS backend
// (JS), and JVM/Scala 3 codegen (JVM). Prints the interpreter's output for
// each example and, at the end, reports any backend whose output diverges.
//
// Usage: scala-cli examples/run-all.sc [examples-dir]
//   examples-dir defaults to the directory of this script (examples/)

val dir: os.Path =
  args.filterNot(_ == "--").headOption match
    case Some(p) => os.Path(p, os.pwd)
    case None    =>
      val candidate = os.pwd / "examples"
      if os.isDir(candidate) then candidate else os.pwd

val root     = dir / os.up
val toolsBin = root / "bin" / "ssc-tools"

if !os.exists(toolsBin) then
  System.err.println(s"required installed launcher not found: $toolsBin")
  System.err.println("Build the full distribution first: bash install.sh --dev")
  System.exit(2)

case class Run(out: String, code: Int, err: String)

def runProc(p: os.proc): Run =
  val r = p.call(stderr = os.Pipe, check = false)
  Run(r.out.text().stripTrailing(), r.exitCode, r.err.text())

def runInt(file: os.Path): Run =
  // Keep all three comparisons on the same v1 frontend/runtime family. The
  // default bin/ssc is the separate v2 native product after the 2.1 cutover.
  runProc(os.proc(toolsBin.toString, "run", "--v1", file.toString))

def runJvm(file: os.Path): Run =
  // `run-jvm` compiles via JVM codegen AND runs, so we get program stdout to
  // compare against the interpreter. (The old `sscc`/`ssc compile` path invoked
  // a removed `compile` subcommand — `Error: File not found: compile` — which
  // failed every example on the JVM lane. `compile-jvm` only writes an artifact
  // and produces no stdout, so it can't be used here.)
  runProc(os.proc(toolsBin.toString, "run-jvm", file.toString))

def runJs(file: os.Path): Run =
  val emit = os.proc(toolsBin.toString, "emit-js", file.toString)
    .call(stderr = os.Pipe, check = false)
  if emit.exitCode != 0 then Run("", emit.exitCode, emit.err.text())
  else
    val nodeRes = os.proc("node").call(stdin = emit.out.text(), stderr = os.Pipe, check = false)
    Run(nodeRes.out.text().stripTrailing(), nodeRes.exitCode, nodeRes.err.text())

val examples = Seq(
  "hello.ssc",
  "index.ssc",
  "script.ssc",
  "data-types.ssc",
  "extensions.ssc",
  "imports.ssc",
  "functional.ssc",
  "enums.ssc",
  "typeclass.ssc",
  "typed-data.ssc",
  "content.ssc",
  "recursion.ssc",
  "effects.ssc",
  "lenses.ssc",
  "lang-split.ssc",
  "default-params.ssc",
  "scala-js-demo.ssc",
)

val sep = "-" * 60

def banner(name: String): Unit =
  println(s"\n$sep")
  println(s"  $name")
  println(sep)

case class Mismatch(example: String, backend: String, expected: String, actual: String)
val mismatches = scala.collection.mutable.ArrayBuffer.empty[Mismatch]
val errors     = scala.collection.mutable.ArrayBuffer.empty[(String, String, String)]

examples.foreach { name =>
  banner(name)
  val file = dir / name

  val int = runInt(file)
  val js  = runJs(file)
  val jvm = runJvm(file)

  // Visual output — interpreter is canonical for display.
  println(int.out)

  // Record any non-zero exits.
  if int.code != 0 then errors += ((name, "INT", int.err))
  if js.code  != 0 then errors += ((name, "JS",  js.err))
  if jvm.code != 0 then errors += ((name, "JVM", jvm.err))

  // Compare outputs against the interpreter (canonical).
  if js.code  == 0 && js.out  != int.out then mismatches += Mismatch(name, "JS",  int.out, js.out)
  if jvm.code == 0 && jvm.out != int.out then mismatches += Mismatch(name, "JVM", int.out, jvm.out)
}

println(s"\n$sep")
println(s"  Summary: ${examples.length} examples")
println(sep)

if errors.isEmpty && mismatches.isEmpty then
  println("All backends produced identical output.")
else
  errors.foreach { (name, backend, err) =>
    System.err.println(s"[$backend] $name — non-zero exit")
    val trimmed = err.linesIterator
      .filterNot(l => l.contains("Downloading") || l.contains("Failed") || l.contains("hint") || l.startsWith("["))
      .mkString("\n")
      .trim
    if trimmed.nonEmpty then System.err.println(trimmed)
  }
  mismatches.foreach { m =>
    System.err.println(s"\n[${m.backend}] ${m.example} — output differs from INT")
    val expLines = m.expected.linesIterator.toList
    val actLines = m.actual.linesIterator.toList
    val maxLen   = expLines.length max actLines.length
    var shown    = 0
    for i <- 0 until maxLen if shown < 5 do
      val e = expLines.lift(i).getOrElse("<missing>")
      val a = actLines.lift(i).getOrElse("<missing>")
      if e != a then
        System.err.println(s"    line ${i+1}: INT=${e.take(80)}  ${m.backend}=${a.take(80)}")
        shown += 1
  }
  System.exit(1)
