#!/usr/bin/env scala-cli
//> using toolkit 0.9.2

// Compiles and runs all wasm-*.ssc examples through the WASM backend.
// Each example is compiled with `ssc emit-wasm` (writes .wasm + .js +
// __loader.js into a temp dir) then executed with `node`.
//
// Usage: scala-cli examples/run-wasm.sc [examples-dir]
//   examples-dir defaults to the examples/ directory next to this script.

val dir: os.Path =
  args.filterNot(_ == "--").headOption match
    case Some(p) => os.Path(p, os.pwd)
    case None    =>
      val candidate = os.pwd / "examples"
      if os.isDir(candidate) then candidate else os.pwd

val root   = dir / os.up
val sscBin = root / "bin" / "ssc"

if !os.exists(sscBin) then
  System.err.println(s"bin/ssc not found at $sscBin — run ./install.sh first")
  System.exit(2)

val hasNode =
  try os.proc("node", "--version").call(check = false).exitCode == 0
  catch case _: Throwable => false

if !hasNode then
  System.err.println("node not found — install Node.js to run WASM bundles")
  System.exit(2)

val examples =
  os.list(dir)
    .filter(p => p.last.startsWith("wasm-") && p.last.endsWith(".ssc"))
    .sortBy(_.last)

if examples.isEmpty then
  System.err.println(s"No wasm-*.ssc files found in $dir")
  System.exit(1)

val sep = "-" * 60

case class Result(name: String, output: String, exitCode: Int, err: String)

def run(file: os.Path): Result =
  val stem   = file.last.stripSuffix(".ssc")
  val tmpDir = os.temp.dir(deleteOnExit = true)
  try
    val emit = os.proc(sscBin.toString, "emit-wasm", file.toString)
      .call(cwd = tmpDir, stderr = os.Pipe, check = false)
    if emit.exitCode != 0 then
      return Result(stem, "", emit.exitCode, emit.err.text())

    val jsFile = tmpDir / s"$stem.js"
    if !os.exists(jsFile) then
      return Result(stem, "", 1, s"emit-wasm did not produce $stem.js")

    val node = os.proc("node", jsFile.toString)
      .call(cwd = tmpDir, stderr = os.Pipe, check = false)
    Result(stem, node.out.text().stripTrailing(), node.exitCode, node.err.text())
  finally
    os.remove.all(tmpDir)

var failures = 0

examples.foreach { file =>
  println(s"\n$sep")
  println(s"  ${file.last}")
  println(sep)
  val r = run(file)
  if r.exitCode == 0 then
    println(r.output)
  else
    failures += 1
    System.err.println(s"FAILED (exit ${r.exitCode})")
    val trimmed = r.err.linesIterator
      .filterNot(l => l.startsWith("[") || l.contains("Downloading"))
      .mkString("\n").trim
    if trimmed.nonEmpty then System.err.println(trimmed)
}

println(s"\n$sep")
println(s"  ${examples.length} examples, $failures failed")
println(sep)

if failures > 0 then System.exit(1)
