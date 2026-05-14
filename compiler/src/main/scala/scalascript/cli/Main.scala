package scalascript.cli

import scalascript.parser.Parser
import scalascript.typer.Typer
import scalascript.interpreter.Interpreter
import scalascript.codegen.{JsGen, JsRuntime, JvmGen}
import scalascript.ast.*

@main def ssc(args: String*): Unit =
  if args.isEmpty then { printUsage(); System.exit(1) }
  args.head match
    case "parse"               => parseCommand(args.tail.toList)
    case "check"               => checkCommand(args.tail.toList)
    case "run"                 => runCommand(args.tail.toList)
    case "emit-js"             => emitJsCommand(args.tail.toList)
    case "emit-scala"          => emitScalaCommand(args.tail.toList)
    case "compile"             => compileCommand(args.tail.toList)
    case "package"             => packageCommand(args.tail.toList)
    case "serve"               => serveCommand(args.tail.toList)
    case "help" | "--help" | "-h" => printUsage()
    case _                     => runCommand(args.toList)

def printUsage(): Unit =
  println("""
    |ScalaScript (ssc)
    |
    |Usage: ssc <command> [options] <files...>
    |
    |Commands:
    |  run                    Execute .ssc via tree-walking interpreter (default)
    |  compile                Compile and run .ssc on JVM via scala-cli
    |  package [flags] <f>    Package .ssc via scala-cli package (see flags below)
    |  emit-scala             Print generated Scala 3 script to stdout
    |  emit-js                Transpile .ssc to JavaScript and print to stdout
    |  serve                  Start HTTP server serving .ssc files as web pages
    |  parse                  Parse .ssc files and print AST
    |  check                  Type-check .ssc files
    |  help                   Show this help message
    |
    |Package flags (passed through to scala-cli package):
    |  --assembly             Fat JAR with all dependencies bundled
    |  --standalone           Self-contained binary (like the ssc binary itself)
    |  --native               GraalVM native image (requires native-image)
    |  -o, --output <path>    Output file (default: input filename without .ssc)
    |  --force, -f            Overwrite existing output
    |  (any other scala-cli package flag is forwarded as-is)
    |
    |Examples:
    |  ssc examples/hello.ssc
    |  ssc compile examples/hello.ssc
    |  ssc package examples/hello.ssc
    |  ssc package --assembly examples/hello.ssc
    |  ssc package --assembly -o hello.jar examples/hello.ssc
    |  ssc package --standalone -o hello examples/hello.ssc
    |  ssc emit-scala examples/hello.ssc
    |  ssc emit-js examples/hello.ssc | node
    |  ssc serve 8080
    |  ssc parse examples/typeclass.ssc
    |""".stripMargin)

def serveCommand(args: List[String]): Unit =
  val port = args.headOption.flatMap(_.toIntOption).getOrElse(8080)
  val dir  = args.drop(1).headOption.getOrElse(".")
  scalascript.server.WebServer.start(port, dir, System.out)

def parseCommand(args: List[String]): Unit =
  if args.isEmpty then { println("Error: No files specified"); System.exit(1) }
  for file <- args do
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then println(s"Error: File not found: $file")
    else
      println(s"=== Parsing: $file ===")
      try   printModule(Parser.parse(os.read(path)))
      catch case e: Exception => println(s"Parse error: ${e.getMessage}")

def runCommand(args: List[String]): Unit =
  if args.isEmpty then { println("Error: No files specified"); System.exit(1) }
  for file <- args do
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then { println(s"Error: File not found: $file"); System.exit(1) }
    else
      try   Interpreter.run(Parser.parse(os.read(path)))
      catch case e: Exception =>
        System.err.println(s"Runtime error: ${e.getMessage}")
        System.exit(1)

def emitJsCommand(args: List[String]): Unit =
  if args.isEmpty then { println("Error: No files specified"); System.exit(1) }
  for file <- args do
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then { println(s"Error: File not found: $file"); System.exit(1) }
    else
      try
        val module = Parser.parse(os.read(path))
        val body   = JsGen.generate(module)
        // Emit a self-contained Node.js-runnable script
        println(JsRuntime)
        println(body)
        println("""console.log(_output.join("\n"));""")
      catch case e: Exception =>
        System.err.println(s"JS generation error: ${e.getMessage}")
        System.exit(1)

def emitScalaCommand(args: List[String]): Unit =
  if args.isEmpty then { println("Error: No files specified"); System.exit(1) }
  for file <- args do
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then { println(s"Error: File not found: $file"); System.exit(1) }
    else
      try   println(JvmGen.generate(Parser.parse(os.read(path))))
      catch case e: Exception =>
        System.err.println(s"Scala generation error: ${e.getMessage}")
        System.exit(1)

def compileCommand(args: List[String]): Unit =
  if args.isEmpty then { println("Error: No files specified"); System.exit(1) }
  for file <- args do
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then { println(s"Error: File not found: $file"); System.exit(1) }
    else
      try
        val module = Parser.parse(os.read(path))
        val script = JvmGen.generate(module)
        val tmp    = os.temp(script, suffix = ".sc", deleteOnExit = true)
        try
          val result = os.proc("scala-cli", "run", tmp).call(
            stdout = os.Inherit,
            stderr = os.Inherit,
            check  = false
          )
          if result.exitCode != 0 then System.exit(result.exitCode)
        finally
          os.remove(tmp)
      catch case e: Exception =>
        System.err.println(s"Compile error: ${e.getMessage}")
        System.exit(1)

def packageCommand(args: List[String]): Unit =
  // Separate the .ssc input file from scala-cli flags
  val (sscFiles, scalaCliFlags) = args.partition(_.endsWith(".ssc"))
  if sscFiles.isEmpty then
    System.err.println("Error: No .ssc file specified")
    System.err.println("Usage: ssc package [scala-cli-package-flags] <file.ssc>")
    System.exit(1)
  for file <- sscFiles do
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then { System.err.println(s"Error: File not found: $file"); System.exit(1) }
    else
      try
        val module = Parser.parse(os.read(path))
        val script = JvmGen.generate(module)
        val tmp    = os.temp(script, suffix = ".sc")
        try
          // Default output name: same as input file without .ssc extension
          val hasOutput = scalaCliFlags.exists(f => f == "-o" || f == "--output")
          val outputFlags =
            if hasOutput then Nil
            else List("--output", path.last.stripSuffix(".ssc"))
          val result = os.proc(
            "scala-cli", "--power", "package", tmp,
            outputFlags,
            scalaCliFlags
          ).call(stdout = os.Inherit, stderr = os.Inherit, cwd = os.pwd, check = false)
          if result.exitCode != 0 then System.exit(result.exitCode)
        finally
          os.remove(tmp)
      catch case e: Exception =>
        System.err.println(s"Package error: ${e.getMessage}")
        System.exit(1)

def checkCommand(args: List[String]): Unit =
  if args.isEmpty then { println("Error: No files specified"); System.exit(1) }
  var hasErrors = false
  for file <- args do
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then { println(s"Error: File not found: $file"); hasErrors = true }
    else
      println(s"=== Type checking: $file ===")
      try
        val module = Parser.parse(os.read(path))
        val typed  = Typer.typeCheck(module)
        if typed.hasErrors then
          hasErrors = true
          typed.errors.foreach(e => println(s"  Error: ${e.msg}"))
        else
          println("OK")
          println(typed.show)
      catch case e: Exception =>
        hasErrors = true
        println(s"Error: ${e.getMessage}")
  if hasErrors then System.exit(1)

def printModule(module: Module): Unit =
  println("Module:")
  module.manifest.foreach { m =>
    println(s"  name:    ${m.name.getOrElse("<none>")}")
    println(s"  version: ${m.version.getOrElse("<none>")}")
    if m.exports.nonEmpty      then println(s"  exports: ${m.exports.mkString(", ")}")
    if m.dependencies.nonEmpty then println(s"  deps:    ${m.dependencies.keys.mkString(", ")}")
  }
  module.sections.foreach(s => printSection(s, 1))

def printSection(s: Section, indent: Int): Unit =
  val prefix = "  " * indent
  println(s"$prefix${"#" * s.heading.level} ${s.heading.text}")
  s.content.foreach {
    case Content.CodeBlock(lang, src, tree, _) =>
      val lines  = src.linesIterator.length
      val status = tree.map(_ => "parsed").getOrElse(
        if lang == "scala" || lang == "ssc" then "PARSE ERROR" else "untyped"
      )
      println(s"$prefix  [code:$lang  $lines lines  $status]")
    case Content.Import(path, bindings, _) =>
      println(s"$prefix  [import $path (${bindings.map(_.name).mkString(", ")})]")
    case Content.Prose(text, _) =>
      println(s"$prefix  [prose: ${text.take(60).replace("\n", " ")}]")
    case Content.DataList(items, ordered, _) =>
      println(s"$prefix  [list: ${items.size} items, ordered=$ordered]")
  }
  s.subsections.foreach(sub => printSection(sub, indent + 1))
