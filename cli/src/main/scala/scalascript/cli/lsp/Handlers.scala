package scalascript.cli.lsp

import scalascript.ast.*
import scalascript.artifact.ArtifactIO
import scalascript.ir.ModuleInterface

import java.security.MessageDigest
import scala.collection.mutable

/** LSP request / notification handlers.
 *
 *  Each method takes the params as a `ujson.Value` and returns a
 *  `ujson.Value` result (for requests) or `Unit` (for notifications),
 *  plus optionally a list of `Notification`s to push back to the client
 *  (e.g. `textDocument/publishDiagnostics` after `didOpen`/`didChange`). */
class Handlers(docs: Documents):

  // ─── initialize / shutdown ──────────────────────────────────────────

  /** `initialize` — return server capabilities and, as a side effect,
   *  pre-load `.scim` interfaces from the `artifactDir` initialisation
   *  option (or scan the workspace recursively if absent). */
  def initialize(params: ujson.Value): ujson.Value =
    val initOpts = params.objOpt.flatMap(_.get("initializationOptions"))
    val artifactDir = initOpts
      .flatMap(_.objOpt)
      .flatMap(_.get("artifactDir"))
      .flatMap(_.strOpt)
      .map(os.Path(_, os.pwd))

    val workspaceFolders = params.objOpt
      .flatMap(_.get("workspaceFolders"))
      .flatMap(_.arrOpt)
      .map(_.toList)
      .getOrElse(Nil)
      .flatMap { f =>
        f.objOpt.flatMap(_.get("uri")).flatMap(_.strOpt)
          .flatMap(Documents.uriToPath)
      }

    // Load interfaces
    val (ifaces, sourceIdx) = loadInterfaces(artifactDir, workspaceFolders)
    docs.setInterfaces(ifaces)
    docs.setSourceIndex(sourceIdx)

    ujson.Obj(
      "capabilities" -> ujson.Obj(
        "textDocumentSync"  -> 1,    // full sync
        "definitionProvider" -> true,
        "hoverProvider"      -> true
      ),
      "serverInfo" -> ujson.Obj(
        "name"    -> "scalascript-lsp",
        "version" -> "0.1.0"
      )
    )

  /** Discover interface artifacts and source files.
   *
   *  Returns `(interfacesByAlias, sourceByHash)`:
   *    - `interfacesByAlias` — keyed by alias (moduleName, or last package
   *      segment, or filename stem).  Last one wins on collision.
   *    - `sourceByHash` — SHA-256 of `.ssc` source bytes → file URI. */
  private def loadInterfaces(
      artifactDir: Option[os.Path],
      workspaces:  List[os.Path]
  ): (Map[String, ModuleInterface], Map[String, String]) =
    val ifaceMap  = mutable.LinkedHashMap.empty[String, ModuleInterface]
    val sourceMap = mutable.LinkedHashMap.empty[String, String]

    // Directories to scan: explicit artifactDir + every workspace root.
    val searchRoots: List[os.Path] = (artifactDir.toList ++ workspaces).distinct

    // Find all .scim files
    for root <- searchRoots if os.exists(root) && os.isDir(root) do
      val scims = scala.util.Try(os.walk(root, includeTarget = false)
        .filter(p => p.last.endsWith(".scim"))).getOrElse(Nil)
      scims.foreach { p =>
        ArtifactIO.readInterfaceFile(p) match
          case Right(iface) =>
            val alias = iface.moduleName
              .orElse(iface.pkg.lastOption)
              .getOrElse(p.last.stripSuffix(".scim"))
            ifaceMap(alias) = iface
          case _ => ()
      }
      // Find all .ssc files and hash them — to back-resolve imported symbols.
      val sscs = scala.util.Try(os.walk(root, includeTarget = false)
        .filter(p => p.last.endsWith(".ssc"))).getOrElse(Nil)
      sscs.foreach { p =>
        val bytes = scala.util.Try(os.read.bytes(p)).getOrElse(Array.empty[Byte])
        if bytes.nonEmpty then
          val h = sha256(bytes)
          sourceMap(h) = Documents.pathToUri(p)
      }

    (ifaceMap.toMap, sourceMap.toMap)

  private def sha256(bytes: Array[Byte]): String =
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(bytes)
    digest.map("%02x".format(_)).mkString

  // ─── Text document lifecycle ────────────────────────────────────────

  /** `textDocument/didOpen`.  Returns the diagnostics notification payload
   *  the server should publish back to the client. */
  def didOpen(params: ujson.Value): Option[LspProtocol.Notification] =
    val td = params.obj("textDocument")
    val uri  = td("uri").str
    val ver  = td("version").num.toInt
    val text = td("text").str
    val state = docs.open(uri, ver, text)
    Some(publishDiagnostics(state))

  /** `textDocument/didChange`. */
  def didChange(params: ujson.Value): Option[LspProtocol.Notification] =
    val td  = params.obj("textDocument")
    val uri = td("uri").str
    val ver = td("version").num.toInt
    // Full-sync only — last contentChange's text is the full document.
    val changes = params.obj.get("contentChanges").flatMap(_.arrOpt).getOrElse(scala.collection.mutable.ArrayBuffer.empty)
    val text = changes.lastOption.flatMap(_.objOpt).flatMap(_.get("text")).flatMap(_.strOpt).getOrElse("")
    val state = docs.change(uri, ver, text)
    Some(publishDiagnostics(state))

  /** `textDocument/didClose`. */
  def didClose(params: ujson.Value): Unit =
    val td  = params.obj("textDocument")
    val uri = td("uri").str
    docs.close(uri)

  /** Build a `textDocument/publishDiagnostics` notification from a doc state. */
  private def publishDiagnostics(state: DocumentState): LspProtocol.Notification =
    val arr = ujson.Arr.from(state.diagnostics.map(diagToJson))
    LspProtocol.Notification(
      "textDocument/publishDiagnostics",
      ujson.Obj(
        "uri"         -> state.uri,
        "diagnostics" -> arr
      )
    )

  private def diagToJson(d: LspDiagnostic): ujson.Value =
    ujson.Obj(
      "range" -> ujson.Obj(
        "start" -> ujson.Obj("line" -> d.line,    "character" -> d.column),
        "end"   -> ujson.Obj("line" -> d.endLine, "character" -> d.endColumn)
      ),
      "severity" -> d.severity,
      "source"   -> d.source,
      "message"  -> d.message
    )

  // ─── definition / hover ─────────────────────────────────────────────

  /** `textDocument/definition`.  Returns either:
   *   - `null` (no location found), or
   *   - a `Location` object `{ uri, range }`. */
  def definition(params: ujson.Value): ujson.Value =
    val (uri, line, character) = extractCursor(params)
    docs.get(uri) match
      case None => ujson.Null
      case Some(state) =>
        findNameAt(state, line, character) match
          case None       => ujson.Null
          case Some(name) =>
            // 1) Try local def in the current document — scan scalameta trees
            //    in the module and emit the first matching Defn.
            findLocalDef(state, name) match
              case Some((dl, dc, el, ec)) =>
                ujson.Obj(
                  "uri"   -> uri,
                  "range" -> rangeJson(dl, dc, el, ec)
                )
              case None =>
                // 2) Try imported interface — look up name in any loaded .scim,
                //    then back-resolve sourceHash to a workspace file URI.
                docs.importedInterfaces.values.find { iface =>
                  iface.exports.exists(_.name == name) ||
                  iface.externDefs.exists(_.name == name)
                } match
                  case Some(iface) =>
                    docs.sourceUriForHash(iface.sourceHash) match
                      case Some(srcUri) =>
                        ujson.Obj(
                          "uri"   -> srcUri,
                          "range" -> rangeJson(0, 0, 0, 0)
                        )
                      case None => ujson.Null
                  case None => ujson.Null

  /** `textDocument/hover`.  Returns either `null` or a Hover object with
   *  `contents` (Markdown-string) and (optionally) a `range`. */
  def hover(params: ujson.Value): ujson.Value =
    val (uri, line, character) = extractCursor(params)
    docs.get(uri) match
      case None => ujson.Null
      case Some(state) =>
        findNameAt(state, line, character) match
          case None => ujson.Null
          case Some(name) =>
            hoverContents(state, name) match
              case None       => ujson.Null
              case Some(text) =>
                ujson.Obj(
                  "contents" -> ujson.Obj(
                    "kind"  -> "markdown",
                    "value" -> s"```scala\n$text\n```"
                  )
                )

  /** Look up `name` in the document's typed module and produce a Scala-
   *  flavoured hover string (`<kind> <name>: <type>`).  Falls back to
   *  the imported interface if the local lookup misses. */
  private def hoverContents(state: DocumentState, name: String): Option[String] =
    state.typed.flatMap { tm =>
      def search(section: scalascript.typer.TypedSection): Option[String] =
        section.definitions.collectFirst {
          case scalascript.typer.TypedDef.CodeBlock(_, _, defs) =>
            defs.find(_.name == name)
        }.flatten.map(_.show)
          .orElse(section.subsections.iterator.flatMap(search).nextOption())
      tm.sections.iterator.flatMap(search).nextOption()
    }.orElse {
      docs.importedInterfaces.values.iterator.flatMap { iface =>
        (iface.exports ++ iface.externDefs)
          .find(_.name == name)
          .map(s => s"${s.kind} ${s.name}: ${s.tpe}")
      }.nextOption()
    }

  // ─── Position / name lookup ────────────────────────────────────────

  /** Extract `(uri, line, character)` from a TextDocumentPositionParams. */
  private def extractCursor(params: ujson.Value): (String, Int, Int) =
    val td = params.obj("textDocument")
    val uri = td("uri").str
    val pos = params.obj("position")
    val line = pos("line").num.toInt
    val character = pos("character").num.toInt
    (uri, line, character)

  /** Find the identifier under the cursor in the document text.
   *
   *  Scans the LSP-coordinate (`line`, `character`) position in the raw
   *  source and returns the contiguous identifier that brackets it.  We
   *  operate directly on text to avoid having to map back from scalameta
   *  positions (which are block-local and may be offset by the
   *  package-wrap preprocessor). */
  private def findNameAt(state: DocumentState, line: Int, character: Int): Option[String] =
    val lines = state.text.linesIterator.toIndexedSeq
    if line < 0 || line >= lines.length then return None
    val text = lines(line)
    if character < 0 || character > text.length then return None
    // Find identifier boundaries around `character`.
    def isIdentPart(c: Char): Boolean = c.isLetterOrDigit || c == '_'
    var start = character
    while start > 0 && isIdentPart(text.charAt(start - 1)) do start -= 1
    var end = character
    while end < text.length && isIdentPart(text.charAt(end)) do end += 1
    if start == end then None
    else Some(text.substring(start, end))

  /** Look for a Defn whose name is `name` inside any scalameta tree of
   *  the current document, returning its `(startLine, startCol, endLine,
   *  endCol)` in file-level coordinates (where lines are 0-indexed). */
  private def findLocalDef(
      state: DocumentState,
      name:  String
  ): Option[(Int, Int, Int, Int)] =
    import scala.meta.*
    import scala.util.boundary, scala.util.boundary.break
    state.module match
      case None    => None
      case Some(mod) =>
        val blocks = collectBlocks(mod)
        boundary[Option[(Int, Int, Int, Int)]]:
          for (cb, blockLine0) <- blocks do
            cb.tree.foreach { node =>
              ScalaNode.fold(node) { tree =>
                tree.collect {
                  case d: Defn.Def    if d.name.value == name =>
                    break(Some(adjust(d.name.pos, blockLine0)))
                  case d: Defn.Val    if d.pats.exists {
                    case Pat.Var(n) => n.value == name
                    case _          => false
                  } => break(Some(adjust(d.pats.head.pos, blockLine0)))
                  case d: Defn.Var    if d.pats.exists {
                    case Pat.Var(n) => n.value == name
                    case _          => false
                  } => break(Some(adjust(d.pats.head.pos, blockLine0)))
                  case d: Defn.Class  if d.name.value == name =>
                    break(Some(adjust(d.name.pos, blockLine0)))
                  case d: Defn.Object if d.name.value == name =>
                    break(Some(adjust(d.name.pos, blockLine0)))
                  case d: Defn.Enum   if d.name.value == name =>
                    break(Some(adjust(d.name.pos, blockLine0)))
                }
              }
            }
          None

  private def adjust(pos: scala.meta.Position, blockLine0: Int): (Int, Int, Int, Int) =
    // pos.startLine is 0-indexed (block-local). Translate to file-level.
    (pos.startLine + blockLine0, pos.startColumn,
     pos.endLine   + blockLine0, pos.endColumn)

  /** Walk the parsed module, returning every code block paired with its
   *  approximate first-content-line offset in the file.
   *
   *  We don't have a precise per-block line offset in `Content.CodeBlock`
   *  today (it's `None`), so we scan the raw text for fenced opens and
   *  associate them in order with the blocks we encounter via the AST. */
  private def collectBlocks(m: Module): List[(Content.CodeBlock, Int)] =
    val cbs = scala.collection.mutable.ListBuffer.empty[Content.CodeBlock]
    def walk(s: Section): Unit =
      s.content.foreach {
        case cb: Content.CodeBlock => cbs += cb
        case _                     => ()
      }
      s.subsections.foreach(walk)
    m.sections.foreach(walk)
    // For an MVP, return all blocks at offset 0.  Position accuracy can
    // be tightened in a follow-up.
    cbs.toList.map(cb => (cb, 0))

  private def rangeJson(sl: Int, sc: Int, el: Int, ec: Int): ujson.Value =
    ujson.Obj(
      "start" -> ujson.Obj("line" -> sl, "character" -> sc),
      "end"   -> ujson.Obj("line" -> el, "character" -> ec)
    )
