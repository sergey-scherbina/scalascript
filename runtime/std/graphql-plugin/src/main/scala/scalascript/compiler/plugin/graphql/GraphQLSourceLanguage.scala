package scalascript.compiler.plugin.graphql

import scalascript.backend.spi.*
import scalascript.ir

/** SourceLanguage plugin for `graphql` fenced blocks (compile-time path).
 *
 *  Phase 1: passthrough — SDL is embedded in IR verbatim.
 *  Phase 4: override `compileBlock` to run SDL through graphql-java's
 *  SchemaParser and populate `BlockArtifact.diagnostics`.
 *
 *  Registration: META-INF/services/scalascript.backend.spi.SourceLanguage
 */
class GraphQLSourceLanguage extends SourceLanguage:
  def id:            String = "graphql-source"
  def displayName:   String = "GraphQL SDL (passthrough)"
  def spiVersion:    String = SpiVersion.Current
  def canonicalName: String = "graphql"

  def signatures(source: String, scope: ScopeContext): List[SymbolExport] = Nil

  def compileBlock(source: String, scope: ScopeContext, opts: BackendOptions): BlockArtifact =
    BlockArtifact(ir.Content.EmbeddedBlock(language = canonicalName, source = source))
