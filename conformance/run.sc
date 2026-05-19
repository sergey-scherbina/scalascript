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

// Per-backend feature support — mirrors backend-{interpreter,js,jvm}/.../Capabilities.scala.
// When backends add or drop features, update this map in lockstep.
val backendFeatures: Map[String, Set[String]] = Map(
  "int" -> Set(
    "AlgebraicEffects", "MutableState", "PatternMatching", "TypeClasses",
    "ExtensionMethods", "DefaultParameters", "ForComprehensions", "WhileLoops",
    "TailCallOptimization", "StringInterpolators", "ModuleImports",
    "ConsoleIO", "HttpServer", "WebSockets", "Auth", "FileSystem", "Crypto",
    "Dataset"
  ),
  "js" -> Set(
    "AlgebraicEffects", "MutableState", "PatternMatching", "TypeClasses",
    "ExtensionMethods", "DefaultParameters", "ForComprehensions", "WhileLoops",
    "TailCallOptimization", "StringInterpolators", "ModuleImports",
    "ConsoleIO", "HttpServer", "WebSockets", "Auth", "FileSystem", "Crypto",
    "McpServer", "McpClient", "Dataset"
  ),
  "jvm" -> Set(
    "AlgebraicEffects", "MutableState", "PatternMatching", "TypeClasses",
    "ExtensionMethods", "DefaultParameters", "ForComprehensions", "WhileLoops",
    "TailCallOptimization", "StringInterpolators", "ModuleImports",
    "ConsoleIO", "HttpServer", "WebSockets", "Auth", "FileSystem", "Crypto",
    "McpServer", "McpClient", "Dataset"
  )
)

// Parse `requires:` from YAML frontmatter (between opening and closing `---`).
// Handles two forms:
//   requires: Feature.McpServer          (scalar)
//   requires:                            (sequence)
//     - McpServer
//     - McpClient
// Strips the optional "Feature." prefix so values match backendFeatures keys.
def parseRequires(src: String): List[String] =
  val lines = src.linesIterator.toList
  if !lines.headOption.contains("---") then return List.empty
  val fmEnd = lines.tail.indexOf("---")
  if fmEnd < 0 then return List.empty
  val fm = lines.slice(1, fmEnd + 1)
  fm.indexWhere(_.startsWith("requires:")) match
    case -1 => List.empty
    case i  =>
      val header = fm(i)
      val scalar = header.stripPrefix("requires:").trim
      val raw =
        if scalar.nonEmpty then List(scalar)
        else
          fm.drop(i + 1).takeWhile(l => l.startsWith("  -") || l.startsWith("- "))
            .map(_.dropWhile(c => c == ' ' || c == '-').trim)
      raw.map(_.stripPrefix("Feature.").trim).filter(_.nonEmpty)

// Parse `backends:` from YAML frontmatter — limits which of int/js/jvm a
// test is allowed to run on. Supported forms:
//   backends: [jvm, js]                  (flow sequence)
//   backends:                            (block sequence)
//     - jvm
//     - js
// Returns Some(set) when present (only those backends run); None means
// eligible for all three (existing behavior). Accepts "interpreter" as an
// alias for "int" so frontmatter can use the long form for clarity.
def parseBackends(src: String): Option[Set[String]] =
  val lines = src.linesIterator.toList
  if !lines.headOption.contains("---") then return None
  val fmEnd = lines.tail.indexOf("---")
  if fmEnd < 0 then return None
  val fm = lines.slice(1, fmEnd + 1)
  fm.indexWhere(_.startsWith("backends:")) match
    case -1 => None
    case i  =>
      val header = fm(i)
      val scalar = header.stripPrefix("backends:").trim
      val raw =
        if scalar.startsWith("[") then
          scalar.stripPrefix("[").stripSuffix("]").split(',').toList.map(_.trim)
        else if scalar.nonEmpty then
          List(scalar)
        else
          fm.drop(i + 1).takeWhile(l => l.startsWith("  -") || l.startsWith("- "))
            .map(_.dropWhile(c => c == ' ' || c == '-').trim)
      val normalised = raw.map(_.toLowerCase).map {
        case "interpreter" => "int"
        case other         => other
      }.filter(_.nonEmpty).toSet
      if normalised.isEmpty then None else Some(normalised)

println(s"\nConformance suite — ${tests.length} tests\n$sep")

for test <- tests do
  val name         = test.baseName
  val expectedFile = expectedDir / s"$name.txt"
  val src          = os.read(test)
  val requires     = parseRequires(src)
  val backendsGate = parseBackends(src)

  if !os.exists(expectedFile) then
    if requires.nonEmpty then
      val eligible = List("js", "jvm").filter(b => requires.forall(backendFeatures(b).contains))
      val reason   = requires.mkString(", ")
      println(s"$name: SKIP (requires: $reason — add expected/$name.txt to enable on eligible backends: ${eligible.mkString(", ")})")
    else
      println(s"$name: SKIP (no expected/$name.txt)")
  else
    println(s"$name:")
    val expected = os.read(expectedFile).stripTrailing()

    def backendSupports(b: String): Boolean =
      backendsGate.forall(_.contains(b)) &&
      (requires.isEmpty || requires.forall(backendFeatures.getOrElse(b, Set.empty).contains))

    def skipReason(b: String): String =
      if backendsGate.exists(!_.contains(b)) then
        s"backends: ${backendsGate.get.toList.sorted.mkString(", ")}"
      else
        s"requires: ${requires.mkString(", ")}"

    // Interpreter
    val intOk =
      if !backendSupports("int") then
        println(s"  SKIP [INT] (${skipReason("int")})")
        true
      else
        val intOut = run(ssc(test.toString))
        check("INT", intOut, expected)

    // JS via Node.js
    val jsOk =
      if !backendSupports("js") then
        println(s"  SKIP [JS ] (${skipReason("js")})")
        true
      else
        val jsSource = run(ssc("emit-js", test.toString))
        val jsOut = os.proc("node").call(
          stdin  = jsSource,
          stderr = os.Pipe,
          check  = false
        ).out.text().stripTrailing()
        check("JS ", jsOut, expected)

    // JVM via JvmGen + scala-cli compile
    val jvmOk =
      if !backendSupports("jvm") then
        println(s"  SKIP [JVM] (${skipReason("jvm")})")
        true
      else
        val jvmOut = run(ssc("compile", test.toString))
        check("JVM", jvmOut, expected)

    if intOk && jsOk && jvmOk then passed += 1 else failed += 1

println(s"\n$sep")
println(s"Results: $passed passed, $failed failed out of ${passed + failed} tests")

if failed > 0 then System.exit(1)
