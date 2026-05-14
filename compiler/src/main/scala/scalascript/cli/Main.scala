package scalascript.cli

import scalascript.parser.Parser
import scalascript.typer.Typer
import scalascript.interpreter.Interpreter
import scalascript.codegen.{JsGen, JsRuntime}
import scalascript.ast.*

@main def ssc(args: String*): Unit =
  if args.isEmpty then { printUsage(); System.exit(1) }
  args.head match
    case "parse"               => parseCommand(args.tail.toList)
    case "check"               => checkCommand(args.tail.toList)
    case "run"                 => runCommand(args.tail.toList)
    case "emit-js"             => emitJsCommand(args.tail.toList)
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
    |  run      Execute .ssc files  (default)
    |  emit-js  Transpile .ssc to JavaScript and print to stdout
    |  serve    Start HTTP server serving .ssc files as web pages
    |  parse    Parse .ssc files and print AST
    |  check    Type-check .ssc files
    |  help     Show this help message
    |
    |Examples:
    |  ssc examples/hello.ssc
    |  ssc emit-js examples/hello.ssc | node
    |  ssc serve 8080
    |  ssc serve 8080 examples/
    |  ssc run  examples/hello.ssc
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
