package scalascript.backend.spi

import scalascript.ir.{NormalizedBlock, QualifiedName}

/** A source-language plugin — parses, type-checks, and lowers blocks of
 *  one fence-tag dialect to IR.  See specs/backend-spi.md §9.
 *
 *  Core handles only `scalascript`/`ssc` + the Markdown host syntax.
 *  Every other fence tag (`scala`, `html`, `css`, `wat`, `csharp`,
 *  `sql`, `python`, …) is a SourceLanguage plugin's responsibility.
 *  Three plugins (`scala-source`, `html`, `css`) ship bundled with the
 *  CLI but use the same SPI as any third-party plugin. */
trait SourceLanguage:
  def id: String
  def displayName: String
  def spiVersion: String

  /** The canonical fence tag (e.g. "scala", "html"). */
  def canonicalName: String

  /** Alternate fence tags that resolve to the same language. */
  def aliases: Set[String] = Set.empty

  /** `.ssc` files loaded into module-wide scope before user code.
   *  See §9 — "Prelude contributions".  Lets a SourceLanguage publish
   *  the types and DSL bindings its blocks rely on (e.g. `html` plugin
   *  contributes the `Html` type and `div`/`p`/`body` tag bindings). */
  def preludeFiles: List[PreludeContribution] = Nil

  /** Surface-level signatures declared by this block, for cross-block
   *  type checking before per-block compilation (§10 — two-pass typing
   *  for cycles).  Implementations parse the block; no full lowering. */
  def signatures(source: String, scope: ScopeContext): List[SymbolExport]

  /** Compile a single block to an IR fragment + diagnostics. */
  def compileBlock(source: String, scope: ScopeContext, opts: BackendOptions): BlockArtifact

  /** Compile a single block with parsed fence attributes.
   *
   *  Older plugins implement the 3-argument method above.  The default
   *  delegation keeps those plugins binary/source-compatible while built-in
   *  languages such as `sql` can consume attributes like `db` and `side`.
   */
  def compileBlock(
    source: String,
    scope:  ScopeContext,
    opts:   BackendOptions,
    attrs:  Map[String, String]
  ): BlockArtifact =
    attrs.foreach(_ => ())
    compileBlock(source, scope, opts)

/** A symbol declared by a foreign-language block, visible to other
 *  blocks (and to scalascript) in the same module.  See §10. */
case class SymbolExport(
  name: QualifiedName,
  kind: SymbolKind,
  signature: String           // for diagnostics + cross-block resolution
)

enum SymbolKind:
  case Value
  case Function
  case Type
  case Module

/** Output of `SourceLanguage.compileBlock`: the lowered IR plus any
 *  diagnostics raised during parsing / lowering of this single block. */
case class BlockArtifact(
  fragment: NormalizedBlock,
  diagnostics: List[Diagnostic] = Nil
)

/** A `.ssc` file a SourceLanguage plugin contributes to the module-wide
 *  prelude (§9).  Loaded into scope before user code, so the plugin's
 *  types and DSL helpers are visible without explicit imports. */
case class PreludeContribution(
  pluginId: String,           // for collision reporting
  filename: String,           // for source-position diagnostics
  source:   String            // raw .ssc text
)

/** Read-only view of the surrounding module's symbol table — handed to
 *  `signatures` and `compileBlock` so cross-block references resolve
 *  without each plugin re-implementing scope lookup. */
trait ScopeContext:
  def isInScope(name: QualifiedName): Boolean
  def resolve(name: QualifiedName): Option[SymbolKind]
