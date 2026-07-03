package scalascript.cli

import scalascript.parser.Parser
import scalascript.transform.Normalize
import scalascript.typer.{AnyEvidenceInventory, GraphQLEvidenceInventory, RouteEvidenceInventory, TypedDef, Typer}

/** `ssc check-types <file.ssc>` — prints a three-section evidence inventory table:
 *  route metadata, Any-typed exported symbols, and GraphQL SDL type coverage.
 *  Exits 0 when all routes and all GraphQL types have declared evidence; 1 otherwise.
 *
 *  - Route section: `RouteEvidenceInventory` on the normalized IR manifest.
 *  - Symbol section: `Typer.typeCheck` + `AnyEvidenceInventory` on DefSummary entries.
 *  - GraphQL section: `GraphQLEvidenceInventory` on the normalized IR module sections. */
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
    val graphqlCounts = GraphQLEvidenceInventory.count(irModule)

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
    println("GraphQL evidence:")
    println(f"  object/interface/input types:  ${graphqlCounts.typesDeclared}%d declared, ${graphqlCounts.typesUnknown}%d unknown")
    println(f"  fields:                        ${graphqlCounts.fieldsDeclared}%d declared, ${graphqlCounts.fieldsUnknown}%d unknown")
    println()

    val unknownRoutes  = routeCounts.endpointsUnknown + routeCounts.handlersUnknown
    val unknownGraphQL = graphqlCounts.typesUnknown + graphqlCounts.fieldsUnknown
    if unknownRoutes == 0 && unknownGraphQL == 0 then
      println("All routes and GraphQL types have declared types.")
      CommandResult.Success
    else
      val parts = List(
        if unknownRoutes  > 0 then Some(s"$unknownRoutes route${if unknownRoutes  == 1 then "" else "s"} have unknown types") else None,
        if unknownGraphQL > 0 then Some(s"$unknownGraphQL GraphQL type${if unknownGraphQL == 1 then "" else "s"} have unknown field types") else None
      ).flatten
      println(parts.mkString("; ") + ".")
      CommandResult.failure()
