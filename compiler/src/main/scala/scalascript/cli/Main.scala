package scalascript.cli

import scalascript.parser.Parser
import scalascript.typer.Typer
import scalascript.lexer.Lexer

@main def ssc(args: String*): Unit =
  if args.isEmpty then
    printUsage()
    System.exit(1)

  val command = args.head
  val rest = args.tail.toList

  command match
    case "parse" => parseCommand(rest)
    case "check" => checkCommand(rest)
    case "tokens" => tokensCommand(rest)
    case "help" | "--help" | "-h" => printUsage()
    case _ =>
      // Assume it's a file path
      checkCommand(args.toList)

def printUsage(): Unit =
  println("""
    |ScalaScript Compiler (ssc) - M1 Preview
    |
    |Usage: ssc <command> [options] <files...>
    |
    |Commands:
    |  parse   Parse .ssc files and print AST
    |  check   Type-check .ssc files
    |  tokens  Tokenize .ssc files and print tokens
    |  help    Show this help message
    |
    |Examples:
    |  ssc check examples/hello.ssc
    |  ssc parse examples/typed-data.ssc
    |  ssc tokens examples/hello.ssc
    |""".stripMargin)

def parseCommand(args: List[String]): Unit =
  if args.isEmpty then
    println("Error: No files specified")
    System.exit(1)

  for file <- args do
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then
      println(s"Error: File not found: $file")
    else
      println(s"=== Parsing: $file ===")
      try
        val source = os.read(path)
        val module = Parser.parse(source)
        printModule(module)
      catch
        case e: Exception =>
          println(s"Parse error: ${e.getMessage}")

def checkCommand(args: List[String]): Unit =
  if args.isEmpty then
    println("Error: No files specified")
    System.exit(1)

  var hasErrors = false

  for file <- args do
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then
      println(s"Error: File not found: $file")
      hasErrors = true
    else
      println(s"=== Type checking: $file ===")
      try
        val source = os.read(path)
        val module = Parser.parse(source)
        val typed = Typer.typeCheck(module)

        if typed.hasErrors then
          hasErrors = true
          println("Errors:")
          typed.errors.foreach { e =>
            val loc = e.span.map(s => s" at ${s.start}").getOrElse("")
            println(s"  - ${e.msg}$loc")
          }
        else
          println("OK")
          println()
          println(typed.show)
      catch
        case e: Exception =>
          hasErrors = true
          println(s"Error: ${e.getMessage}")
          e.printStackTrace()

  if hasErrors then System.exit(1)

def tokensCommand(args: List[String]): Unit =
  if args.isEmpty then
    println("Error: No files specified")
    System.exit(1)

  for file <- args do
    val path = os.Path(file, os.pwd)
    if !os.exists(path) then
      println(s"Error: File not found: $file")
    else
      println(s"=== Tokens: $file ===")
      try
        val source = os.read(path)
        val tokens = Lexer.tokenize(source)
        tokens.foreach { t =>
          println(f"${t.span.start.line}%3d:${t.span.start.column}%-3d ${t.kind}%-30s ${escape(t.text)}")
        }
      catch
        case e: Exception =>
          println(s"Lexer error: ${e.getMessage}")

def escape(s: String): String =
  s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

def printModule(module: scalascript.ast.Module): Unit =
  import scalascript.ast.*

  println(s"Module:")
  module.manifest.foreach { m =>
    println(s"  name: ${m.name.getOrElse("<none>")}")
    println(s"  version: ${m.version.getOrElse("<none>")}")
    if m.exports.nonEmpty then
      println(s"  exports: ${m.exports.mkString(", ")}")
    if m.dependencies.nonEmpty then
      println(s"  dependencies: ${m.dependencies.mkString(", ")}")
  }

  def printSection(s: Section, indent: Int): Unit =
    val prefix = "  " * indent
    println(s"$prefix${"#" * s.heading.level} ${s.heading.text}")

    s.content.foreach {
      case Content.CodeBlock(lang, code, stmts, _) =>
        println(s"$prefix  [CodeBlock: $lang, ${stmts.size} statements]")
        stmts.take(5).foreach(stmt => println(s"$prefix    - ${stmtSummary(stmt)}"))
        if stmts.size > 5 then println(s"$prefix    ... and ${stmts.size - 5} more")

      case Content.Import(path, bindings, _) =>
        println(s"$prefix  [Import: $path (${bindings.map(_.name).mkString(", ")})]")

      case Content.Prose(text, _, _) =>
        val preview = text.take(50).replace("\n", " ")
        println(s"$prefix  [Prose: $preview...]")

      case Content.DataList(items, ordered, _) =>
        println(s"$prefix  [List: ${items.size} items, ordered=$ordered]")
    }

    s.subsections.foreach(sub => printSection(sub, indent + 1))

  module.sections.foreach(s => printSection(s, 1))

def stmtSummary(stmt: scalascript.ast.Statement): String =
  import scalascript.ast.Statement.*
  stmt match
    case ValDef(name, tpe, _, _) => s"val $name${tpe.map(t => s": $t").getOrElse("")}"
    case VarDef(name, tpe, _, _) => s"var $name${tpe.map(t => s": $t").getOrElse("")}"
    case DefDef(name, _, params, ret, _, _) => s"def $name(...)"
    case ClassDef(name, _, _, _, _, isCase, _) => s"${if isCase then "case " else ""}class $name"
    case EnumDef(name, _, _, cases, _) => s"enum $name { ${cases.map(_.name).mkString(", ")} }"
    case ObjectDef(name, _, _, isCase, _) => s"${if isCase then "case " else ""}object $name"
    case TraitDef(name, _, _, _, _) => s"trait $name"
    case TypeAlias(name, _, _, _) => s"type $name"
    case ExprStmt(_, _) => "<expr>"
