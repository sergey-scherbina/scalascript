package scalascript.source

import scalascript.backend.spi.*
import scalascript.ir

/** SourceLanguage plugin for `scala` fence blocks (docs/backend-spi.md §9).
 *
 *  Stage 9 skeleton.  Registers via ServiceLoader so
 *  `SourceLanguageRegistry.lookup("scala")` resolves to this instance.
 *  The plugin currently passes the block source through verbatim — the
 *  bundled JvmGen / JsGen / ScalaJsBackend already know how to embed
 *  `scala` source today.  Stage 9 follow-ups route through this plugin
 *  instead of the hardcoded paths in those backends:
 *
 *    - signatures(): extract top-level `def` / `val` / `class` / `object`
 *      symbols so other foreign-language blocks can reference them via
 *      cross-block typing (§10).
 *    - compileBlock(): produce an IR fragment so consumers can pass it
 *      around as `Content.EmbeddedBlock` payload.
 *    - preludeFiles: empty — `scala` blocks don't contribute helpers.
 *
 *  Until the follow-up lands, both methods return minimal stubs: enough
 *  for the registry test to verify discovery, but no real parsing. */
class ScalaSourceLanguage extends SourceLanguage:
  def id:            String = "scala-source"
  def displayName:   String = "Scala (scalameta passthrough)"
  def spiVersion:    String = SpiVersion.Current
  def canonicalName: String = "scala"
  override def aliases: Set[String] = Set.empty

  // No prelude contributions — `scala` blocks are stand-alone Scala 3,
  // no DSL helpers are required to make them parse.
  override def preludeFiles: List[PreludeContribution] = Nil

  /** Stage 9 stub.  Real impl walks scalameta's Source tree
   *  (`Parser.parseScalaSource` in core) and emits SymbolExport entries
   *  for `Defn.Def`, `Defn.Val`, `Defn.Class`, `Defn.Object`.  The
   *  scalameta dep moves to this module then. */
  def signatures(source: String, scope: ScopeContext): List[SymbolExport] = Nil

  /** Stage 9 stub.  Real impl produces an `ir.Content.EmbeddedBlock`
   *  carrying the verbatim source plus optional lowered IR for backends
   *  that want to inspect rather than re-parse. */
  def compileBlock(source: String, scope: ScopeContext, opts: BackendOptions): BlockArtifact =
    BlockArtifact(
      fragment    = ir.Content.EmbeddedBlock(language = "scala", source = source),
      diagnostics = Nil
    )
