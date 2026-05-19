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
        "textDocumentSync"   -> 1,    // full sync
        "definitionProvider" -> true,
        "hoverProvider"      -> true,
        "referencesProvider" -> true,
        "renameProvider"     -> ujson.Obj("prepareProvider" -> true),
        "completionProvider" -> ujson.Obj(
          "triggerCharacters" -> ujson.Arr(".", " ")
        )
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
                //    Use the symbol's recorded (definitionLine, definitionColumn)
                //    when present; fall back to (0,0) for .scim artifacts emitted
                //    before that field existed (backward-compat MVP behaviour).
                docs.importedInterfaces.values.flatMap { iface =>
                  (iface.exports ++ iface.externDefs)
                    .find(_.name == name)
                    .map(sym => (iface, sym))
                }.headOption match
                  case Some((iface, sym)) =>
                    docs.sourceUriForHash(iface.sourceHash) match
                      case Some(srcUri) =>
                        val line = sym.definitionLine
                        val col  = sym.definitionColumn
                        val endCol = col + sym.name.length
                        ujson.Obj(
                          "uri"   -> srcUri,
                          "range" -> rangeJson(line, col, line, endCol)
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

  // ─── completion ─────────────────────────────────────────────────────

  /** LSP completion item kinds used by this server. */
  private object CompletionItemKind:
    val Function    = 3
    val Constructor = 4
    val Variable    = 6
    val Keyword     = 14

  /** ScalaScript keywords surfaced as completion candidates. */
  private val keywords: List[String] = List(
    "def", "val", "var", "if", "else", "match", "case", "for", "yield",
    "while", "trait", "given", "using", "object", "class", "sealed",
    "enum", "type", "extension", "opaque", "extern", "async", "await",
    "import", "export", "println", "print", "summon"
  )

  /** `textDocument/completion`.  Returns a `CompletionList`. */
  def completion(params: ujson.Value): ujson.Value =
    val (uri, line, character) = extractCursor(params)

    // Walk back from cursor to find the identifier prefix being typed.
    def isIdentPart(c: Char): Boolean = c.isLetterOrDigit || c == '_'
    val prefix: String = docs.get(uri) match
      case None        => ""
      case Some(state) =>
        val lines = state.text.linesIterator.toIndexedSeq
        if line < 0 || line >= lines.length then ""
        else
          val lineText = lines(line)
          val col = math.min(character, lineText.length)
          var start = col
          while start > 0 && isIdentPart(lineText.charAt(start - 1)) do start -= 1
          lineText.substring(start, col)

    // Collect all user-defined symbol names from the typed module.
    def collectNames(state: DocumentState): List[(String, Int)] =
      state.typed.toList.flatMap { tm =>
        def fromSection(sec: scalascript.typer.TypedSection): List[(String, Int)] =
          val local = sec.definitions.flatMap {
            case scalascript.typer.TypedDef.CodeBlock(_, _, defs) =>
              defs.map { d =>
                val kind = d.kind match
                  case scalascript.typer.SymbolKind.Def   => CompletionItemKind.Function
                  case scalascript.typer.SymbolKind.Class => CompletionItemKind.Constructor
                  case _                                  => CompletionItemKind.Variable
                (d.name, kind)
              }
            case _ => Nil
          }
          local ++ sec.subsections.flatMap(fromSection)
        tm.sections.flatMap(fromSection)
      }

    // Collect names from the current document (if open) or yield nothing.
    val docNames: List[(String, Int)] =
      docs.get(uri).map(collectNames).getOrElse(Nil)

    // Collect names from imported interfaces.
    val ifaceNames: List[(String, Int)] =
      docs.importedInterfaces.values.toList.flatMap { iface =>
        (iface.exports ++ iface.externDefs).map { sym =>
          val kind = if sym.kind == "def" then CompletionItemKind.Function
                     else CompletionItemKind.Variable
          (sym.name, kind)
        }
      }

    // Keyword items.
    val kwItems: List[(String, Int)] =
      keywords.map(k => (k, CompletionItemKind.Keyword))

    // Merge, deduplicate (keep first occurrence), filter by prefix.
    val all = (docNames ++ ifaceNames ++ kwItems)
      .distinctBy(_._1)
      .filter { case (name, _) =>
        prefix.isEmpty || name.toLowerCase.startsWith(prefix.toLowerCase)
      }

    val items = ujson.Arr.from(all.map { case (name, kind) =>
      ujson.Obj("label" -> name, "kind" -> kind)
    })

    ujson.Obj(
      "isIncomplete" -> false,
      "items"        -> items
    )

  // ─── references ─────────────────────────────────────────────────────

  /** `textDocument/references`.  Returns a (possibly empty) JSON array of
   *  `Location` objects for every identifier occurrence in the document that
   *  matches the name under the cursor.
   *
   *  `context.includeDeclaration` (default `true`) controls whether the
   *  definition site itself is included.  When `false`, the location returned
   *  by [[findLocalDef]] is excluded from results. */
  def references(params: ujson.Value): ujson.Value =
    val (uri, line, character) = extractCursor(params)
    val includeDecl = params.objOpt
      .flatMap(_.get("context")).flatMap(_.objOpt)
      .flatMap(_.get("includeDeclaration")).flatMap(_.boolOpt)
      .getOrElse(true)
    docs.get(uri) match
      case None => ujson.Arr()
      case Some(state) =>
        findNameAt(state, line, character) match
          case None => ujson.Arr()
          case Some(name) =>
            val defLoc: Option[(Int, Int)] =
              if !includeDecl then findLocalDef(state, name).map { case (sl, sc, _, _) => (sl, sc) }
              else None
            val locs = findAllOccurrences(state, name).filterNot { case (sl, sc, _, _) =>
              defLoc.exists { case (dl, dc) => sl == dl && sc == dc }
            }
            ujson.Arr.from(locs.map { case (sl, sc, el, ec) =>
              ujson.Obj("uri" -> uri, "range" -> rangeJson(sl, sc, el, ec))
            })

  /** Scan every line of the document text for word-boundary occurrences of
   *  `name`.  Returns file-level `(startLine, startCol, endLine, endCol)`
   *  quads (0-indexed), one per occurrence.  Only returns matches where
   *  `name` is not adjacent to another identifier character — i.e. a
   *  whole-word match. */
  private def findAllOccurrences(
      state: DocumentState,
      name:  String
  ): List[(Int, Int, Int, Int)] =
    def isIdentPart(c: Char): Boolean = c.isLetterOrDigit || c == '_'
    val lines   = state.text.linesIterator.toIndexedSeq
    val nameLen = name.length
    val buf     = List.newBuilder[(Int, Int, Int, Int)]
    for (lineText, lineIdx) <- lines.zipWithIndex do
      var col = 0
      while col <= lineText.length - nameLen do
        val idx = lineText.indexOf(name, col)
        if idx < 0 then col = lineText.length
        else
          val boundBefore = idx == 0 || !isIdentPart(lineText.charAt(idx - 1))
          val boundAfter  = idx + nameLen >= lineText.length ||
                            !isIdentPart(lineText.charAt(idx + nameLen))
          if boundBefore && boundAfter then
            buf += ((lineIdx, idx, lineIdx, idx + nameLen))
          col = idx + 1
    buf.result()

  // ─── rename / prepareRename ─────────────────────────────────────────

  /** `textDocument/prepareRename`.  Returns the range of the identifier under
   *  the cursor (so the editor can pre-fill the rename prompt), or `null` if
   *  there is no renameable identifier at that position. */
  def prepareRename(params: ujson.Value): ujson.Value =
    val (uri, line, character) = extractCursor(params)
    docs.get(uri) match
      case None => ujson.Null
      case Some(state) =>
        findNameAt(state, line, character) match
          case None => ujson.Null
          case Some(name) =>
            // Locate the exact column span of the name on this line.
            val lineText = state.text.linesIterator.toIndexedSeq.applyOrElse(line, (_: Int) => "")
            val col = lineText.indexOf(name, math.max(0, character - name.length))
            if col < 0 then ujson.Null
            else rangeJson(line, col, line, col + name.length)

  /** `textDocument/rename`.  Applies a whole-word find-and-replace of the
   *  identifier under the cursor throughout the document, returning a
   *  `WorkspaceEdit` with `TextEdit` objects for each occurrence.
   *
   *  Only renames within the currently-open document (single-file scope).
   *  Cross-file rename is out of scope for the MVP. */
  def rename(params: ujson.Value): ujson.Value =
    val (uri, line, character) = extractCursor(params)
    val newName = params.objOpt.flatMap(_.get("newName")).flatMap(_.strOpt).getOrElse("")
    if newName.isEmpty then return ujson.Null
    docs.get(uri) match
      case None => ujson.Null
      case Some(state) =>
        findNameAt(state, line, character) match
          case None => ujson.Null
          case Some(oldName) =>
            val edits = findAllOccurrences(state, oldName).map { case (sl, sc, el, ec) =>
              ujson.Obj("range" -> rangeJson(sl, sc, el, ec), "newText" -> newName)
            }
            ujson.Obj(
              "changes" -> ujson.Obj(uri -> ujson.Arr.from(edits))
            )

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
   *  file-level first-content-line offset.
   *
   *  Uses the `Content.CodeBlock.lineOffset` field populated by
   *  `Parser.extractSections` from CommonMark source spans.  For .scim
   *  artifacts / `Module` instances built before that field existed the
   *  default `0` keeps the legacy (block-local) behaviour. */
  private def collectBlocks(m: Module): List[(Content.CodeBlock, Int)] =
    val cbs = scala.collection.mutable.ListBuffer.empty[Content.CodeBlock]
    def walk(s: Section): Unit =
      s.content.foreach {
        case cb: Content.CodeBlock => cbs += cb
        case _                     => ()
      }
      s.subsections.foreach(walk)
    m.sections.foreach(walk)
    cbs.toList.map(cb => (cb, cb.lineOffset))

  private def rangeJson(sl: Int, sc: Int, el: Int, ec: Int): ujson.Value =
    ujson.Obj(
      "start" -> ujson.Obj("line" -> sl, "character" -> sc),
      "end"   -> ujson.Obj("line" -> el, "character" -> ec)
    )
