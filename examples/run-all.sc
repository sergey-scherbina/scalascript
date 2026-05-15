#!/usr/bin/env scala-cli
//> using toolkit latest

// Runs all ScalaScript examples in order and prints their output.
// Usage: scala-cli examples/run-all.sc [examples-dir]
//   examples-dir defaults to the directory of this script (examples/)

val dir: os.Path =
  args.filterNot(_ == "--").headOption match
    case Some(p) => os.Path(p, os.pwd)
    case None    =>
      val candidate = os.pwd / "examples"
      if os.isDir(candidate) then candidate else os.pwd

val sscBin = dir / os.up / "bin" / "ssc"

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
)

val sep = "-" * 60

def banner(name: String): Unit =
  println(s"\n$sep")
  println(s"  $name")
  println(sep)

examples.foreach { name =>
  banner(name)
  val result = os.proc(sscBin, dir / name).call(
    stderr = os.Pipe,
    check  = false,
  )
  print(result.out.text())
  if result.exitCode != 0 then
    System.err.println(
      result.err.text().linesIterator
        .filterNot(l => l.contains("Downloading") || l.contains("Failed") || l.contains("hint") || l.startsWith("["))
        .mkString("\n")
    )
}
