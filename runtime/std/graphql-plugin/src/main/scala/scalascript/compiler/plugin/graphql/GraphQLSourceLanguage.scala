package scalascript.compiler.plugin.graphql

import scalascript.backend.spi.*
import scalascript.ir
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.errors.SchemaProblem
import scala.jdk.CollectionConverters.*

/** SourceLanguage plugin for `graphql` fenced blocks (compile-time path).
 *
 *  Phase 1: passthrough — SDL embedded in IR verbatim.
 *  Phase 4: validate SDL through graphql-java's SchemaParser; populate
 *  `BlockArtifact.diagnostics` with `Diagnostic.GraphQLSdlError` entries.
 *  Invalid SDL blocks still produce an `EmbeddedBlock` so downstream passes
 *  can continue; callers that care about errors (CLI build, LSP) inspect
 *  `BlockArtifact.diagnostics`.
 *
 *  Registration: META-INF/services/scalascript.backend.spi.SourceLanguage
 */
class GraphQLSourceLanguage extends SourceLanguage:
  def id:            String = "graphql-source"
  def displayName:   String = "GraphQL SDL"
  def spiVersion:    String = SpiVersion.Current
  def canonicalName: String = "graphql"

  def signatures(source: String, scope: ScopeContext): List[SymbolExport] = Nil

  def compileBlock(source: String, scope: ScopeContext, opts: BackendOptions): BlockArtifact =
    val embedded = ir.Content.EmbeddedBlock(language = canonicalName, source = source)
    try
      new SchemaParser().parse(source)
      BlockArtifact(embedded)
    catch
      case e: SchemaProblem =>
        val diags = e.getErrors.asScala.toList.map { err =>
          val loc = Option(err.getLocations).flatMap(_.asScala.headOption)
          Diagnostic.GraphQLSdlError(
            message = err.getMessage,
            line    = loc.map(_.getLine).getOrElse(0),
            col     = loc.map(_.getColumn).getOrElse(0)
          )
        }
        BlockArtifact(embedded, diagnostics = diags)
      case e: Exception =>
        BlockArtifact(embedded,
          diagnostics = List(Diagnostic.GraphQLSdlError(e.getMessage, 0, 0)))
