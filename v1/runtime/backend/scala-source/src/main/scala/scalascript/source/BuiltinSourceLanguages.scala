package scalascript.source

import scalascript.backend.spi.*
import scalascript.ir
import scalascript.transform.SqlBindRewriter

/** SourceLanguage plugin for browser JavaScript fence blocks.
 *
 *  The emitted IR shape intentionally matches the previous EmbeddedBlock
 *  fallback so existing backends keep their current behavior.
 */
class JavaScriptSourceLanguage extends SourceLanguage:
  def id:            String = "javascript-source"
  def displayName:   String = "JavaScript (browser passthrough)"
  def spiVersion:    String = SpiVersion.Current
  def canonicalName: String = "javascript"
  override def aliases: Set[String] = Set("js")

  def signatures(source: String, scope: ScopeContext): List[SymbolExport] = Nil

  def compileBlock(source: String, scope: ScopeContext, opts: BackendOptions): BlockArtifact =
    BlockArtifact(ir.Content.EmbeddedBlock(language = canonicalName, source = source))

/** SourceLanguage plugin for `rust` fence blocks.
 *
 *  Phase R.1 of the rust target — passthrough.  Blocks are emitted into
 *  `src/generated/<crate>.rs` verbatim by RustGen.  Backends that don't
 *  accept rust source (jvm / js / interpreter) surface a
 *  `Diagnostic.Generic` at CapabilityCheck time, never a silent
 *  miscompile.  Real cross-block typing (Stage 10) lands when a future
 *  follow-up extracts `pub fn` / `pub struct` signatures here. */
class RustSourceLanguage extends SourceLanguage:
  def id:            String = "rust-source"
  def displayName:   String = "Rust (passthrough)"
  def spiVersion:    String = SpiVersion.Current
  def canonicalName: String = "rust"

  def signatures(source: String, scope: ScopeContext): List[SymbolExport] = Nil

  def compileBlock(source: String, scope: ScopeContext, opts: BackendOptions): BlockArtifact =
    BlockArtifact(ir.Content.EmbeddedBlock(language = canonicalName, source = source))

/** SourceLanguage plugin for XML fence blocks.
 *
 *  Runtime XML parsing/validation still happens in the interpreter/JVM path;
 *  this plugin owns source-language registration and IR routing.
 */
class XmlSourceLanguage extends SourceLanguage:
  def id:            String = "xml-source"
  def displayName:   String = "XML (markup passthrough)"
  def spiVersion:    String = SpiVersion.Current
  def canonicalName: String = "xml"

  def signatures(source: String, scope: ScopeContext): List[SymbolExport] = Nil

  def compileBlock(source: String, scope: ScopeContext, opts: BackendOptions): BlockArtifact =
    BlockArtifact(ir.Content.EmbeddedBlock(language = canonicalName, source = source))

/** SourceLanguage plugin for SQL fence blocks.
 *
 *  SQL is more than a passthrough fence: `${expr}` occurrences become bind
 *  expressions in IR, and fence attributes select the database and execution
 *  side.  Keeping that logic here makes SQL follow the same SourceLanguage
 *  extension path as other built-in fenced languages.
 */
class SqlSourceLanguage extends SourceLanguage:
  def id:            String = "sql-source"
  def displayName:   String = "SQL (bind-aware)"
  def spiVersion:    String = SpiVersion.Current
  def canonicalName: String = "sql"

  def signatures(source: String, scope: ScopeContext): List[SymbolExport] = Nil

  def compileBlock(source: String, scope: ScopeContext, opts: BackendOptions): BlockArtifact =
    compileBlock(source, scope, opts, Map.empty)

  override def compileBlock(
    source: String,
    scope:  ScopeContext,
    opts:   BackendOptions,
    attrs:  Map[String, String]
  ): BlockArtifact =
    try
      val rewritten = SqlBindRewriter.rewriteJdbc(source)
      val side = attrs.get("side") match
        case Some("client") => ir.SqlSide.Client
        case _              => ir.SqlSide.Server
      BlockArtifact(
        ir.Content.SqlBlock(
          source = source,
          binds  = rewritten.binds,
          dbName = attrs.get("db"),
          side   = side
        )
      )
    catch case _: SqlBindRewriter.RewriteError =>
      BlockArtifact(ir.Content.EmbeddedBlock(language = canonicalName, source = source))

/** SourceLanguage plugin for `transaction` fence blocks.
 *
 *  Each `transaction` block holds multiple `;`-separated SQL statements
 *  executed atomically.  `compileBlock` splits on `;` and applies the
 *  bind-parameter rewriter to each statement, producing a `TransactionBlock`
 *  IR node.  Falls back to `EmbeddedBlock` on rewrite errors so a single
 *  malformed block does not abort the pipeline.
 */
class TransactionSourceLanguage extends SourceLanguage:
  def id:            String = "transaction-source"
  def displayName:   String = "Transaction (multi-statement SQL)"
  def spiVersion:    String = SpiVersion.Current
  def canonicalName: String = "transaction"

  def signatures(source: String, scope: ScopeContext): List[SymbolExport] = Nil

  def compileBlock(source: String, scope: ScopeContext, opts: BackendOptions): BlockArtifact =
    compileBlock(source, scope, opts, Map.empty)

  override def compileBlock(
    source: String,
    scope:  ScopeContext,
    opts:   BackendOptions,
    attrs:  Map[String, String]
  ): BlockArtifact =
    try
      val stmts     = SqlBindRewriter.splitStatements(source)
      val rewritten = stmts.map(SqlBindRewriter.rewriteJdbc)
      BlockArtifact(
        ir.Content.TransactionBlock(
          sources = stmts,
          binds   = rewritten.map(_.binds),
          dbName  = attrs.get("db")
        )
      )
    catch case _: SqlBindRewriter.RewriteError =>
      BlockArtifact(ir.Content.EmbeddedBlock(language = canonicalName, source = source))
