package scalascript.cli.lsp

import scalascript.ast.Module
import scalascript.parser.Parser
import scalascript.typer.{Typer, TypedModule, TypeError, SectionSnapshot}
import scalascript.ir.ModuleInterface

import java.net.URI
import java.nio.file.Paths
import scala.collection.mutable

/** A single document tracked by the LSP server. */
case class DocumentState(
    uri:     String,
    version: Int,
    text:    String,
    module:  Option[Module],
    typed:   Option[TypedModule],
    /** Diagnostics aggregated from parse + type-check phases. */
    diagnostics: List[LspDiagnostic],
    /** Per-section snapshots from the last successful type-check run.
     *  Threaded into [[Typer.typeCheckIncremental]] on the next `didChange`
     *  so that unchanged sections are not re-typed. */
    snapshots: List[SectionSnapshot] = Nil
)

/** A diagnostic at LSP precision — 0-indexed line / character. */
case class LspDiagnostic(
    line: Int,
    column: Int,
    endLine: Int,
    endColumn: Int,
    severity: Int,   // 1 = Error, 2 = Warning, 3 = Info, 4 = Hint
    message: String,
    source:  String  // "parser" | "typer"
)

/** In-memory document store keyed by URI.  Also stores the workspace
 *  artifact-dir interface map for cross-module name resolution. */
class Documents:
  private val docs = mutable.Map.empty[String, DocumentState]

  /** Map from import alias (the last package segment or module name) to
   *  the loaded `.scim` interface.  Populated at server initialisation. */
  @volatile private var interfaces: Map[String, ModuleInterface] = Map.empty

  /** Map from `sourceHash` → file URI of `.ssc` source files found in
   *  workspace / artifact dir.  Used by `definition` to back-resolve an
   *  imported `.scim` symbol to its source file. */
  @volatile private var sourceByHash: Map[String, String] = Map.empty

  def setInterfaces(ifaces: Map[String, ModuleInterface]): Unit =
    interfaces = ifaces

  def setSourceIndex(idx: Map[String, String]): Unit =
    sourceByHash = idx

  def importedInterfaces: Map[String, ModuleInterface] = interfaces

  def sourceUriForHash(hash: String): Option[String] = sourceByHash.get(hash)

  def open(uri: String, version: Int, text: String): DocumentState =
    val prev  = docs.get(uri)
    val state = parseAndCheck(uri, version, text, prev.map(_.snapshots).getOrElse(Nil))
    docs(uri) = state
    state

  def change(uri: String, version: Int, text: String): DocumentState =
    val prev  = docs.get(uri)
    val state = parseAndCheck(uri, version, text, prev.map(_.snapshots).getOrElse(Nil))
    docs(uri) = state
    state

  def close(uri: String): Unit = docs.remove(uri)

  def get(uri: String): Option[DocumentState] = docs.get(uri)

  /** All open documents — used by `definition` to look up names exported
   *  by other in-editor files when the artifact dir isn't authoritative. */
  def all: Iterable[DocumentState] = docs.values

  /** All open document URIs. */
  def allUris: Iterable[String] = docs.keys

  // ─── Internals ──────────────────────────────────────────────────────

  private def parseAndCheck(
      uri:           String,
      version:       Int,
      text:          String,
      prevSnapshots: List[SectionSnapshot]
  ): DocumentState =
    val (mod, parseDiags) =
      try
        val m = Parser.parse(text)
        (Some(m), collectParseDiagnostics(m))
      catch case e: Exception =>
        (None, List(LspDiagnostic(0, 0, 0, 1, 1, s"parse failure: ${e.getMessage}", "parser")))

    val (typed, newSnapshots, typerDiags) = mod match
      case None    => (None, Nil, Nil)
      case Some(m) =>
        try
          val (tm, snaps) = Typer.typeCheckIncrementalModule(
            m,
            prevSnapshots,
            interfaces,
            strict = true
          )
          (Some(tm), snaps, tm.errors.map(typeErrorToDiagnostic))
        catch case e: Exception =>
          (None, Nil, List(LspDiagnostic(0, 0, 0, 1, 1, s"type-check failure: ${e.getMessage}", "typer")))

    val unusedImportDiags = detectUnusedImports(text)
    DocumentState(uri, version, text, mod, typed, parseDiags ++ typerDiags ++ unusedImportDiags, newSnapshots)

  /** Walk a parsed module's sections and collect parse-error diagnostics
   *  from `Content.CodeBlock.parseError` (positions in block-local
   *  coordinates, which we don't try to remap to file-level — MVP). */
  private def collectParseDiagnostics(m: Module): List[LspDiagnostic] =
    import scalascript.ast.*
    val buf = scala.collection.mutable.ListBuffer.empty[LspDiagnostic]
    def walk(s: Section): Unit =
      s.content.foreach {
        case cb: Content.CodeBlock if cb.tree.isEmpty && cb.parseError.isDefined =>
          val pe = cb.parseError.get
          val line0 = math.max(0, pe.line - 1)
          val col0  = math.max(0, pe.column - 1)
          buf += LspDiagnostic(
            line = line0, column = col0,
            endLine = line0, endColumn = col0 + 1,
            severity = 1,
            message = pe.message,
            source  = "parser"
          )
        case _ => ()
      }
      s.subsections.foreach(walk)
    m.sections.foreach(walk)
    buf.toList

  private def detectUnusedImports(text: String): List[LspDiagnostic] =
    val importRe = """^\s*import\s+(\S+)""".r
    val lines    = text.linesIterator.toIndexedSeq
    val buf      = scala.collection.mutable.ListBuffer.empty[LspDiagnostic]
    lines.zipWithIndex.foreach { case (line, idx) =>
      importRe.findFirstMatchIn(line).foreach { m =>
        val importExpr = m.group(1)
        val names: List[String] =
          if importExpr.endsWith(".*") then
            List(importExpr.stripSuffix(".*").split("\\.").last)
          else
            val braceIdx = importExpr.indexOf('{')
            if braceIdx >= 0 then
              importExpr.drop(braceIdx + 1).stripSuffix("}").split(",").toList
                .map(_.trim).filterNot(_.isEmpty)
            else
              List(importExpr.split("\\.").last)
        val isUsed = names.exists { name =>
          lines.zipWithIndex.exists { case (l, i) => i != idx && l.contains(name) }
        }
        if !isUsed then
          buf += LspDiagnostic(
            line      = idx,
            column    = line.indexOf("import"),
            endLine   = idx,
            endColumn = line.length,
            severity  = 4,
            message   = s"Unused import: $importExpr",
            source    = "typer"
          )
      }
    }
    buf.toList

  private def typeErrorToDiagnostic(te: TypeError): LspDiagnostic =
    val severity = if te.isWarning then 2 else 1
    te.span match
      case Some(sp) =>
        LspDiagnostic(
          line      = sp.start.line,
          column    = sp.start.column,
          endLine   = sp.end.line,
          endColumn = sp.end.column,
          severity  = severity,
          message   = te.msg,
          source    = "typer"
        )
      case None =>
        LspDiagnostic(0, 0, 0, 1, severity, te.msg, "typer")

object Documents:

  /** Convert an LSP URI string to an absolute filesystem path, if it is
   *  a `file://` URI.  Returns `None` for any other scheme. */
  def uriToPath(uri: String): Option[os.Path] =
    scala.util.Try {
      val u = new URI(uri)
      if u.getScheme == "file" then Some(os.Path(Paths.get(u)))
      else None
    }.getOrElse(None)

  /** Convert an absolute filesystem path to a `file://` URI. */
  def pathToUri(p: os.Path): String =
    p.toNIO.toUri.toString
