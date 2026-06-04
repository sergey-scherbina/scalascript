package scalascript.cli

import scalascript.parser.Parser
import scalascript.transform.Normalize
import scalascript.typer.{AnyEvidenceInventory, RouteEvidenceInventory, TypedDef, Typer}

/** `ssc check-types <file.ssc>` — prints a two-section evidence inventory table
 *  for route metadata and Any-typed exported symbols, then exits 0 if all routes
 *  have declared evidence or 1 if any route has unknown evidence.
 *
 *  Route section uses `RouteEvidenceInventory` on the normalized IR manifest.
 *  Symbol section uses `Typer.typeCheck` + `AnyEvidenceInventory` on DefSummary
 *  entries — no interpreter run required. */
final class CheckTypesCmd extends CliCommand:
  def name = "check-types"
  override def summary = "Print type evidence inventory for routes and exported symbols"
  override def category = "Diagnostics"
  override def details  = List("Usage: ssc check-types <file.ssc>")

  def run(args: List[String]): Unit =
    System.exit(runResult(args).exitCode.value)

  override def runResult(args: List[String]): CommandResult =
    val files = args.filterNot(_.startsWith("-"))
    if files.isEmpty then
      System.err.println("usage: ssc check-types <file.ssc>")
      return CommandResult.failure()
    if files.size > 1 then
      System.err.println("check-types: exactly one input file is supported")
      return CommandResult.failure()

    val path = os.Path(files.head, os.pwd)
    if !os.exists(path) then
      System.err.println(s"check-types: file not found: ${files.head}")
      return CommandResult.failure()

    val module     = Parser.parseFile(path)
    val irModule   = Normalize(module)
    val routeCounts =
      irModule.manifest.map(RouteEvidenceInventory.count).getOrElse(
        scalascript.typer.RouteEvidenceCounts()
      )

    val typed   = Typer.typeCheck(module)
    val allDefs = scala.collection.mutable.ListBuffer.empty[scalascript.typer.DefSummary]
    def gatherSection(s: scalascript.typer.TypedSection): Unit =
      s.definitions.foreach {
        case TypedDef.CodeBlock(_, _, defs) => allDefs ++= defs
        case _ => ()
      }
      s.subsections.foreach(gatherSection)
    typed.sections.foreach(gatherSection)
    val symCounts = AnyEvidenceInventory.count(allDefs)

    println("Route evidence:")
    println(f"  api endpoints:    ${routeCounts.endpointsDeclared}%d declared, ${routeCounts.endpointsUnknown}%d unknown")
    println(f"  remote handlers:  ${routeCounts.handlersDeclared}%d declared, ${routeCounts.handlersUnknown}%d unknown")
    println()
    println("Symbol evidence (Any-typed exports):")
    println(f"  declared:       ${symCounts.declared}%d")
    println(f"  inferred:       ${symCounts.inferred}%d")
    println(f"  derived:        ${symCounts.derived}%d")
    println(f"  imported:       ${symCounts.imported}%d")
    println(f"  pluginProvided: ${symCounts.pluginProvided}%d")
    println(f"  dynamic:        ${symCounts.dynamic}%d")
    println(f"  unknown:        ${symCounts.unknown}%d")
    println()

    val unknownRoutes = routeCounts.endpointsUnknown + routeCounts.handlersUnknown
    if unknownRoutes == 0 then
      println("All routes have declared types.")
      CommandResult.Success
    else
      println(s"$unknownRoutes route${if unknownRoutes == 1 then "" else "s"} have unknown types.")
      CommandResult.failure()
