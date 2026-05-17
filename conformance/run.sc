#!/usr/bin/env scala-cli
//> using toolkit latest

// Conformance test runner for ScalaScript.
// For each .ssc file in conformance/ it:
//   1. Runs via the JVM interpreter   (INT label)
//   2. Transpiles to JS, runs via Node (JS  label)
//   3. Generates Scala 3 source and compiles+runs via scala-cli (JVM label)
// All three outputs are compared against the same expected/*.txt.
// Usage: scala-cli conformance/run.sc [conformance-dir]
//   conformance-dir defaults to the directory of this script

val dir: os.Path =
  args.filterNot(_ == "--").headOption match
    case Some(p) => os.Path(p, os.pwd)
    case None    => os.pwd / "conformance"

val expectedDir = dir / "expected"
val sscBin      = dir / os.up / "bin" / "ssc"

// Requires the pre-built launcher. Build with `bash scripts/install.sh ./bin/ssc`
// (which produces cli/target/scala-3.8.3/ssc.jar via sbt-assembly and writes
// bin/ssc as a tiny java -jar wrapper).
if !os.exists(sscBin) then
  System.err.println(s"bin/ssc not found at $sscBin. Build it first: bash scripts/install.sh ./bin/ssc")
  System.exit(2)

def ssc(args: String*): os.proc =
  os.proc(sscBin.toString +: args.toSeq)

val tests = os.list(dir)
  .filter(_.ext == "ssc")
  .sortBy(_.last)

val sep = "-" * 50
var passed = 0
var failed = 0

def run(cmd: os.proc): String =
  cmd.call(stderr = os.Pipe, check = false).out.text().stripTrailing()

def check(label: String, got: String, expected: String): Boolean =
  if got == expected then
    println(s"  PASS [$label]")
    true
  else
    println(s"  FAIL [$label]")
    val gotLines  = got.linesIterator.toList
    val expLines  = expected.linesIterator.toList
    val maxLen    = expLines.length max gotLines.length
    for i <- 0 until maxLen do
      val e = expLines.lift(i).getOrElse("<missing>")
      val g = gotLines.lift(i).getOrElse("<missing>")
      if e != g then println(s"    line ${i+1}: expected=${e.take(80)}  got=${g.take(80)}")
    false

println(s"\nConformance suite — ${tests.length} tests\n$sep")

for test <- tests do
  val name        = test.baseName
  val expectedFile = expectedDir / s"$name.txt"

  if !os.exists(expectedFile) then
    println(s"$name: SKIP (no expected/$name.txt)")
  else
    println(s"$name:")
    val expected = os.read(expectedFile).stripTrailing()

    // Interpreter
    val intOut = run(ssc(test.toString))
    val intOk  = check("INT", intOut, expected)

    // JS via Node.js
    val jsSource = run(ssc("emit-js", test.toString))
    val jsOut = os.proc("node").call(
      stdin  = jsSource,
      stderr = os.Pipe,
      check  = false
    ).out.text().stripTrailing()
    val jsOk = check("JS ", jsOut, expected)

    // JVM via JvmGen + scala-cli compile
    val jvmOut = run(ssc("compile", test.toString))
    val jvmOk  = check("JVM", jvmOut, expected)

    if intOk && jsOk && jvmOk then passed += 1 else failed += 1

println(s"\n$sep")
println(s"Results: $passed passed, $failed failed out of ${passed + failed} tests")

if failed > 0 then System.exit(1)
