#!/usr/bin/env scala-cli
//> using toolkit latest

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
val sscBin   = root / "bin" / "ssc"
val jsscBin  = root / "bin" / "jssc"
val ssccBin  = root / "bin" / "sscc"
val compiler = root / "compiler"

// bin/ssc is the pre-built launcher (gitignored); jssc / sscc are shell
// wrappers around `scala-cli run compiler -- emit-js/compile`. Fall back to
// the scala-cli path when a launcher isn't present (CI checkout).
def sscProc(file: os.Path): os.proc =
  if os.exists(sscBin) then os.proc(sscBin.toString, file.toString)
  else os.proc("scala-cli", "run", compiler.toString, "--", file.toString)

def jsscProc(file: os.Path): os.proc = os.proc(jsscBin.toString, file.toString)
def ssccProc(file: os.Path): os.proc = os.proc(ssccBin.toString, file.toString)

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
  "lang-split.ssc",
  "default-params.ssc",
)

val sep = "-" * 60

def banner(name: String): Unit =
  println(s"\n$sep")
  println(s"  $name")
  println(sep)

case class Run(out: String, code: Int, err: String)

def runProc(p: os.proc): Run =
  val r = p.call(stderr = os.Pipe, check = false)
  Run(r.out.text().stripTrailing(), r.exitCode, r.err.text())

case class Mismatch(example: String, backend: String, expected: String, actual: String)
val mismatches = scala.collection.mutable.ArrayBuffer.empty[Mismatch]
val errors     = scala.collection.mutable.ArrayBuffer.empty[(String, String, String)]

examples.foreach { name =>
  banner(name)
  val file = dir / name

  val int = runProc(sscProc(file))
  val js  = runProc(jsscProc(file))
  val jvm = runProc(ssccProc(file))

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
