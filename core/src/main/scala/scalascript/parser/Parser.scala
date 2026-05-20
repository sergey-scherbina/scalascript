package scalascript.parser

import scalascript.ast.*
import org.commonmark.node.{
  Node            as CmNode,
  Document        as CmDocument,
  Heading         as CmHeading,
  Paragraph       as CmParagraph,
  FencedCodeBlock as CmFenced,
  BulletList      as CmBulletList,
  OrderedList     as CmOrderedList,
  ListItem        as CmListItem,
  Link            as CmLink,
  Text            as CmText,
  Code            as CmCode,
}
import org.commonmark.parser.{Parser as CmParser, IncludeSourceSpans}
import org.yaml.snakeyaml.Yaml
import scala.collection.mutable.{ListBuffer, Stack}
import scala.jdk.CollectionConverters.*

object Parser:
  // `includeSourceSpans(BLOCKS)` makes CommonMark populate `Node.getSourceSpans()`
  // with the input-file (0-indexed) line ranges of every block node, including
  // fenced code blocks.  We use this in `extractSections` to compute the
  // file-level line offset of each `Content.CodeBlock` so the LSP server can
  // translate block-local scalameta positions back to file-level coordinates.
  private val mdParser  = CmParser.builder()
    .includeSourceSpans(IncludeSourceSpans.BLOCKS)
    .build()
  private val snakeYaml = Yaml()

  def parse(source: String): Module =
    // Strip shebang line so files can be self-executing: #!/usr/bin/env ssc
    val shebangLines = if source.startsWith("#!") then 1 else 0
    val noShebang =
      if shebangLines == 1 then source.dropWhile(_ != '\n').drop(1)
      else source
    val (fmOpt, body, fmStripped) = splitFrontMatterCounting(noShebang)
    val isWrapped = isPureScala(body)
    // Pure-Scala script (no Markdown headings or fences): wrap in a synthetic section
    val mdSrc =
      if isWrapped then s"# Script\n\n```scala\n${body.trim}\n```\n"
      else body
    val doc = mdParser.parse(mdSrc).asInstanceOf[CmDocument]
    val manifest = fmOpt.map(parseManifest)
    // Map a CommonMark 0-indexed line in `mdSrc` back to a 0-indexed line in
    // the ORIGINAL `source` file.  For the standard path this is a simple
    // additive offset (shebang lines + front-matter lines that were stripped
    // before parsing).  For the pure-Scala-wrap path we have to subtract the
    // 3 synthetic header lines (`# Script`, blank, ```` ```scala ```` ) we
    // prepended AND add the count of leading whitespace lines in `body` that
    // `.trim` discarded, so the first code line still maps to the user's
    // original source line.
    val mdLineToFileLine: Int => Int =
      if isWrapped then
        // Number of leading newline-terminated whitespace lines in `body`
        // that `.trim` collapsed.  Lines wholly consumed by `dropWhile(_ ==
        // '\n')` plus any whitespace-only prefix lines.
        val leadingWs = body.takeWhile(c => c == '\n' || c == ' ' || c == '\t' || c == '\r')
        val leadingWsLines = leadingWs.count(_ == '\n')
        val base = shebangLines + fmStripped + leadingWsLines
        (mdLine: Int) => base + math.max(0, mdLine - 3)
      else
        val base = shebangLines + fmStripped
        (mdLine: Int) => base + mdLine
    val sections = extractSections(doc, mdLineToFileLine)
    val pkg      = manifest.flatMap(_.pkg).getOrElse(Nil)
    if pkg.isEmpty then Module(manifest, sections)
    else Module(manifest, sections.map(wrapSectionInPackage(_, pkg)))

  /** Wrap every scalascript-block's contents in nested `object`s matching
   *  the front-matter `package:` segments.  `package: org.example.ui`
   *  on a block holding `object Card { … }` becomes
   *  `object org { object example { object ui { object Card { … } } } }`,
   *  so importers see the module's names under the dotted prefix and
   *  two libraries can each export `Card` without collision. */
  private def wrapSectionInPackage(section: Section, pkg: List[String]): Section =
    val newContent = section.content.map {
      case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
        // Preprocess `extern def` / `effect` / list-form imports BEFORE wrapping
        // in objects.  Otherwise the surface forms survive into the nested code
        // that scalameta sees and the whole block silently fails to parse
        // (cb.tree = None → imports from this file see no symbols).
        val preprocessed =
          preprocessExtern(preprocessEffects(preprocessInlineImports(preprocessSlashImports(cb.source))))
        val nested = pkg.foldRight(preprocessed) { (seg, body) =>
          val indented = body.linesIterator.map("  " + _).mkString("\n")
          s"object $seg:\n$indented"
        }
        val tree = scala.util.Try {
          import scala.meta.{dialects, *}
          dialects.Scala3(Input.VirtualFile(s"<package-wrap>", nested))
            .parse[Source].toOption.map(scalascript.ast.ScalaNode(_))
        }.toOption.flatten
        // Preserve the original parseError (when present) so the CLI still sees
        // a positional diagnostic for blocks that even the package-wrap retry
        // failed to rescue.
        val pe = if tree.isEmpty then cb.parseError else None
        // Carry the ORIGINAL block's `lineOffset` through the wrap.  The
        // wrap rewrites `cb.source` into a synthetic `object pkg: …` body
        // that no longer corresponds line-for-line with the user's `.ssc`,
        // but downstream consumers (LSP, error reporting) want the file
        // line of the user's first code line — not a position into the
        // synthesized wrapper.  Preserving the field here means
        // `extractor` + LSP can keep using `cb.lineOffset` as the single
        // source of truth regardless of whether `package:` is set.
        Content.CodeBlock(cb.lang, nested, tree, cb.span, pe, cb.lineOffset)
      case other => other
    }
    section.copy(
      content     = newContent,
      subsections = section.subsections.map(wrapSectionInPackage(_, pkg))
    )

  def parseFile(path: os.Path): Module = parse(os.read(path))

  // A source is "pure Scala" when it has no Markdown headings (# ...) or fences (```).
  // After shebang stripping, this reliably distinguishes plain scripts from .ssc documents.
  private def isPureScala(src: String): Boolean =
    !src.linesIterator.exists(l => l.startsWith("#") || l.startsWith("```"))

  // ─── Front-matter ────────────────────────────────────────────────

  /** Returns the number of lines from the start of `src` that were consumed
   *  before `body` begins.  Used by [[parse]] to translate CommonMark's
   *  (mdSrc-local) line indices back to file-level lines for
   *  [[Content.CodeBlock.lineOffset]]. */
  private def splitFrontMatterCounting(src: String): (Option[String], String, Int) =
    if !src.startsWith("---") then return (None, src, 0)
    val nl = src.indexOf('\n')
    if nl < 0 then return (None, src, 0)
    val rest = src.substring(nl + 1)
    val end  = rest.indexOf("\n---")
    if end < 0 then return (None, src, 0)
    val tail = rest.substring(end + 4).dropWhile(_ == '\n')
    val body = tail
    // Lines consumed = everything from start of `src` up to (but not
    // including) the first character of `body`.  Equivalently: total
    // newlines in `src` − total newlines in `body`, when `body` is what
    // remains.  Compute directly from substring lengths to stay correct
    // when the front-matter is empty or has trailing whitespace.
    val stripped     = src.length - body.length
    val strippedText = src.substring(0, stripped)
    val lines = strippedText.count(_ == '\n')
    (Some(rest.substring(0, end)), body, lines)

  /** SnakeYAML's "mapping values are not allowed here" surfaces with the
   *  line/column of the offending construct but with no hint about which
   *  key/value caused it.  Wrap the exception to add a one-line hint
   *  pointing at the most common cause (unquoted colons in string values
   *  like `description: foo: bar`). */
  private def parseManifest(yaml: String): Manifest =
    val raw =
      try Option(snakeYaml.load[java.util.Map[String, Any]](yaml))
            .map(_.asScala.toMap).getOrElse(Map.empty)
      catch case e: org.yaml.snakeyaml.scanner.ScannerException =>
        val lines = yaml.linesIterator.toIndexedSeq
        val mark  = Option(e.getProblemMark)
        val hint  = mark.map { m =>
          val lineNo = m.getLine + 1
          val col    = m.getColumn + 1
          val ctx    = lines.lift(m.getLine).getOrElse("")
          val pointer = " " * (col - 1) + "^"
          val likely =
            if ctx.contains(": ") && !ctx.trim.startsWith("#") then
              "  hint: this looks like an unquoted colon inside a string value;\n" +
              "        quote the value, e.g.  description: \"foo: bar\""
            else ""
          s"\n  at line $lineNo, column $col:\n    $ctx\n    $pointer\n$likely"
        }.getOrElse("")
        throw new RuntimeException(s"YAML front-matter: ${e.getProblem}$hint")
    Manifest(
      name         = raw.get("name").collect { case s: String => s },
      version      = raw.get("version").collect { case s: String => s },
      description  = raw.get("description").collect { case s: String => s },
      dependencies = raw.get("dependencies").collect {
        case m: java.util.Map[?, ?] =>
          m.asScala.map { case (k, v) => k.toString -> v.toString }.toMap
      }.getOrElse(Map.empty),
      exports = raw.get("exports").collect {
        case l: java.util.List[?] => l.asScala.map(_.toString).toList
      }.getOrElse(Nil),
      targets = raw.get("targets").collect {
        case l: java.util.List[?] => l.asScala.map(_.toString).toList
      }.getOrElse(Nil),
      routes = parseRoutes(raw),
      pkg = raw.get("package").collect {
        case s: String if s.nonEmpty => s.split('.').toList.filter(_.nonEmpty)
      },
      translations = raw.get("translations").collect {
        case m: java.util.Map[?, ?] =>
          m.asScala.collect { case (locale: String, v: java.util.Map[?, ?]) =>
            locale -> (v.asScala.collect { case (k: String, vv) => k -> vv.toString }.toMap: Map[String, String])
          }.toMap
      }.getOrElse(Map.empty),
      databases = parseDatabases(raw),
      frontendFramework = raw.get("frontend").orElse(raw.get("frontend-framework")).collect { case s: String => s },
      raw = raw
    )

  /** Pull `routes: [{method: ..., path: ..., handler: ...}]` out of the
   *  raw YAML map.  Entries that lack any of the three required fields
   *  are silently skipped — strict validation lives in the typer, not
   *  here, so a misspelled key surfaces as an "unknown route" later
   *  rather than blocking the whole parse. */
  private def parseRoutes(raw: Map[String, Any]): List[RouteDecl] =
    raw.get("routes").collect {
      case xs: java.util.List[?] =>
        xs.asScala.toList.flatMap {
          case m: java.util.Map[?, ?] =>
            val mm: Map[String, Any] = m.asScala.iterator.collect {
              case (k: String, v) => k -> (v: Any)
            }.toMap
            val method  = mm.get("method").collect { case s: String => s.toUpperCase }
            val path    = mm.get("path").collect { case s: String => s }
            val handler = mm.get("handler").collect { case s: String => s }
            (method, path, handler) match
              case (Some(m), Some(p), Some(h)) => Some(RouteDecl(m, p, h))
              case _                            => None
          case _ => None
        }
    }.getOrElse(Nil)

  /** Pull `databases: { name: { url, user?, password?, driver? } }` out
   *  of the raw YAML map (SPEC § 3.3.1, v1.26).  Entries that lack a
   *  `url` are silently skipped — the runtime's `ConnectionRegistry`
   *  surfaces a more specific diagnostic when an `sql` block actually
   *  tries to resolve a missing connection.  `${env:NAME}` references
   *  inside the values are *preserved verbatim* here and resolved at
   *  runtime — keeping env access out of parse time means a `.ssc`
   *  file with the right schema parses on any machine, even one that
   *  doesn't have the secrets set. */
  /** Parse `@key=value` markers that may follow the lang tag on a
   *  fence line.  Values may be unquoted (whitespace-delimited) or
   *  double-quoted (allowing whitespace and equals signs inside).
   *  Keys are lower-cased — `@DB=x` is `db -> x`.
   *
   *  Used today by `sql` blocks to read `@db=name` (v1.26).  The
   *  syntax is general; future tags can pick up their own attrs
   *  without changing the parser. */
  private val FenceAttrPat = """@([A-Za-z_][A-Za-z0-9_-]*)=(?:"([^"]*)"|(\S+))""".r

  private def parseFenceAttrs(tail: String): Map[String, String] =
    if tail.isEmpty then Map.empty
    else
      FenceAttrPat.findAllMatchIn(tail).map { m =>
        val key   = m.group(1).toLowerCase
        val value = Option(m.group(2)).getOrElse(m.group(3))
        key -> value
      }.toMap

  private def parseDatabases(raw: Map[String, Any]): List[DatabaseDecl] =
    raw.get("databases").collect {
      case m: java.util.Map[?, ?] =>
        m.asScala.iterator.collect {
          case (k: String, v: java.util.Map[?, ?]) =>
            val mm: Map[String, Any] = v.asScala.iterator.collect {
              case (kk: String, vv) => kk -> (vv: Any)
            }.toMap
            mm.get("url").collect { case s: String => s }.map { url =>
              DatabaseDecl(
                name     = k,
                url      = url,
                user     = mm.get("user").collect { case s: String => s },
                password = mm.get("password").collect { case s: String => s },
                driver   = mm.get("driver").collect { case s: String => s }
              )
            }
        }.flatten.toList
    }.getOrElse(Nil)

  // ─── Section extraction from the flat CommonMark tree ────────────
  //
  // CommonMark produces a flat sequence of block nodes; headings are siblings,
  // not parents, of the content that follows them.  We use a mutable stack to
  // fold that flat sequence into our nested Section tree.

  private case class Frame(
    level: Int,
    heading: Heading,
    content: ListBuffer[Content],
    subsections: ListBuffer[Section]
  )

  private def extractSections(
      doc:              CmDocument,
      mdLineToFileLine: Int => Int
  ): List[Section] =
    val roots = ListBuffer[Section]()
    val stack = Stack[Frame]()

    // Pop all frames whose level >= toLevel and wire them into their parents.
    def flush(toLevel: Int): Unit =
      while stack.nonEmpty && stack.top.level >= toLevel do
        val f = stack.pop()
        val s = Section(f.heading, f.content.toList, f.subsections.toList)
        if stack.nonEmpty then stack.top.subsections += s else roots += s

    var node = doc.getFirstChild
    while node != null do
      node match
        case h: CmHeading =>
          flush(h.getLevel)
          stack.push(Frame(
            level      = h.getLevel,
            heading    = Heading(h.getLevel, textOf(h)),
            content    = ListBuffer.empty,
            subsections = ListBuffer.empty
          ))
        case other =>
          toContent(other, mdLineToFileLine).foreach { c =>
            if stack.nonEmpty then stack.top.content += c
          }
      node = node.getNext

    flush(0)
    roots.toList

  // ─── Node → Content ──────────────────────────────────────────────

  private def toContent(node: CmNode, mdLineToFileLine: Int => Int): Option[Content] = node match
    case f: CmFenced =>
      val info     = Option(f.getInfo).getOrElse("").trim
      val lang     = info.takeWhile(!_.isWhitespace).toLowerCase
      val tailAttrs = info.drop(lang.length).trim
      val attrs    = parseFenceAttrs(tailAttrs)
      val src  = Option(f.getLiteral).getOrElse("")
      val (tree, parseError) =
        if Lang.isParseable(lang) then parseScalaWithDiagnostic(src)
        else (None, None)
      // CommonMark's source span on a `FencedCodeBlock` covers the whole
      // block including the opening + closing fence rows.  The first line
      // of code INSIDE the fence is one row past the fence open, so the
      // 0-indexed file-level line of `src`'s first row is
      // `fenceStartLine + 1` (translated through the mdSrc→file mapping).
      val fenceStart0 =
        val spans = f.getSourceSpans
        if spans != null && !spans.isEmpty then spans.get(0).getLineIndex
        else 0
      val lineOffset = mdLineToFileLine(fenceStart0 + 1)
      Some(Content.CodeBlock(lang, src, tree, None, parseError, lineOffset, attrs))

    case p: CmParagraph =>
      asImport(p).orElse {
        val text = textOf(p)
        if text.nonEmpty then Some(Content.Prose(text)) else None
      }

    case l: CmBulletList  => Some(Content.DataList(listItems(l), ordered = false))
    case l: CmOrderedList => Some(Content.DataList(listItems(l), ordered = true))
    case _                => None

  private def asImport(para: CmParagraph): Option[Content.Import] =
    val child = para.getFirstChild
    if child == null || child.getNext != null then return None
    child match
      case link: CmLink =>
        val path     = link.getDestination
        // `Name` or `Name as Alias` — multiple bindings separated by commas.
        // Whitespace around the `as` keyword is required so it doesn't collide
        // with names that happen to contain the substring.
        val asPattern = """^([A-Za-z_][\w]*)\s+as\s+([A-Za-z_][\w]*)$""".r
        val bindings = textOf(link).split(",").map(_.trim).filter(_.nonEmpty).map { s =>
          s match
            case asPattern(name, alias) => ImportBinding(name, Some(alias))
            case bare                   => ImportBinding(bare, None)
        }.toList
        if path.nonEmpty && bindings.nonEmpty then Some(Content.Import(path, bindings)) else None
      case _ => None

  private def listItems(list: CmNode): List[ListItem] =
    val buf = ListBuffer[ListItem]()
    var item = list.getFirstChild
    while item != null do
      item match
        case li: CmListItem => buf += ListItem(textOf(li), Nil)
        case _              => ()
      item = item.getNext
    buf.toList

  // ─── Scala parsing via scalameta ─────────────────────────────────

  /** Preprocess `extern` surface forms into scalameta-friendly Scala 3:
   *
   *    extern def foo(...): T          → def foo(...): T = __extern__
   *    extern class Name[T]:           → class Name[T]:
   *      def m(...): T                 →   def m(...): T = __extern__
   *      val v: T                      →   val v: T = __extern__.asInstanceOf[T]
   *    extern object Name:             → object Name:
   *      def m(...): T                 →   def m(...): T = __extern__
   *      val v: T                      →   val v: T = __extern__.asInstanceOf[T]
   *
   *  The `extern` modifier isn't a Scala 3 keyword; this preprocess step
   *  is the same pattern as `preprocessEffects` for `effect Name:` blocks
   *  — surface syntax we want, expanded into scalameta-friendly form
   *  before parsing.  The codegens recognise `__extern__` stubs as
   *  body-less declarations (`EffectAnalysis.isExternDef`).
   *
   *  For `extern class` / `extern object` we strip the `extern` modifier
   *  and rewrite every body-less `def` / `val` inside the block (detected
   *  by indentation) to use `__extern__` as a stub right-hand side.
   *
   *  Original: Stage 5+/A.6 (Б-1); class/object forms added 2026-05-19. */
  private def preprocessExtern(code: String): String =
    val externDefPat   = """^(\s*)extern\s+def\s+(.+)$""".r
    val externTypePat  = """^(\s*)extern\s+(class|object|trait)\s+(.+?:)\s*$""".r
    val lines = code.linesIterator.toArray
    if !lines.exists(l =>
         externDefPat.findFirstIn(l).isDefined ||
         externTypePat.findFirstIn(l).isDefined
       ) then return code

    val result = new StringBuilder()

    // Track active `extern class/object` blocks by their declaration-line
    // indentation; any def/val whose indentation is STRICTLY GREATER than
    // a tracked one belongs to that block and gets `__extern__`-rewritten.
    val externBlockIndents = scala.collection.mutable.Stack.empty[Int]

    def indentOf(s: String): Int = s.takeWhile(_ == ' ').length
    def isInsideExternBlock(ind: Int): Boolean =
      externBlockIndents.headOption.exists(decl => ind > decl)

    // Body-less member (no `=`, doesn't end with a brace/colon) — needs a stub.
    val bodylessDefPat = """^(\s*)def\s+(.+)$""".r
    val bodylessValPat = """^(\s*)val\s+([^=]+):\s*([^=].*)$""".r

    var i = 0
    while i < lines.length do
      val line = lines(i)

      // Pop the indent stack when we leave the block (current line dedents
      // back to the declaration line's level or further).  Blank lines do
      // not pop — they're allowed inside a block.
      if line.strip.nonEmpty then
        val ind = indentOf(line)
        while externBlockIndents.nonEmpty && ind <= externBlockIndents.head do
          externBlockIndents.pop()

      externTypePat.findFirstMatchIn(line) match
        case Some(m) =>
          // `extern class Foo[T]:` / `extern object Foo:` — strip modifier,
          // keep the rest; mark the block open by remembering its indent.
          val indent = m.group(1)
          val kind   = m.group(2)
          val head   = m.group(3)
          result.append(indent).append(kind).append(" ").append(head).append("\n")
          externBlockIndents.push(indent.length)
          i += 1
        case None =>
          externDefPat.findFirstMatchIn(line) match
            case Some(m) =>
              val indent = m.group(1)
              val sigBuf = new StringBuilder(m.group(2).stripTrailing)
              // Multi-line extern def: collect continuation until paren depth = 0.
              var depth = sigBuf.count(_ == '(') - sigBuf.count(_ == ')')
              while depth > 0 && i + 1 < lines.length do
                i += 1
                val cont = lines(i).strip
                sigBuf.append(" ").append(cont)
                depth += cont.count(_ == '(') - cont.count(_ == ')')
              result.append(indent).append("def ").append(sigBuf.toString.stripTrailing)
                    .append(" = __extern__\n")
              i += 1
            case None if isInsideExternBlock(indentOf(line)) && line.strip.nonEmpty =>
              // Inside an `extern class/object` block — body-less def/val
              // members get `= __extern__` stubs so scalameta accepts them.
              line match
                case bodylessDefPat(indent, sigText) if !sigText.contains("=") =>
                  // Multi-line def signature handling.
                  val sigBuf = new StringBuilder(sigText.stripTrailing)
                  var depth = sigBuf.count(_ == '(') - sigBuf.count(_ == ')')
                  while depth > 0 && i + 1 < lines.length do
                    i += 1
                    val cont = lines(i).strip
                    sigBuf.append(" ").append(cont)
                    depth += cont.count(_ == '(') - cont.count(_ == ')')
                  result.append(indent).append("def ").append(sigBuf.toString.stripTrailing)
                        .append(" = __extern__\n")
                  i += 1
                case bodylessValPat(indent, name, tpe) =>
                  // Strip a trailing `// ...` line comment from the
                  // captured type so the `asInstanceOf[T]` we splice in
                  // doesn't end up as `asInstanceOf[String  // foo]`.
                  // Doc comments tied to vals in `extern class` blocks
                  // are common; preserve them by re-appending after the
                  // synthesised stub.
                  val cmtIdx   = tpe.indexOf("//")
                  val cleanTpe = (if cmtIdx >= 0 then tpe.substring(0, cmtIdx) else tpe).strip
                  val trailing = if cmtIdx >= 0 then "  " + tpe.substring(cmtIdx) else ""
                  result.append(indent).append("val ").append(name.strip)
                        .append(": ").append(cleanTpe)
                        .append(" = __extern__.asInstanceOf[").append(cleanTpe).append("]")
                        .append(trailing).append("\n")
                  i += 1
                case _ =>
                  result.append(line).append("\n")
                  i += 1
            case None =>
              result.append(line).append("\n")
              i += 1
    result.toString

  /** Strip Markdown-link-style list-form imports that std/ modules write
   *  *inside* scalascript code blocks:
   *
   *    [ToolResult, ResourceResult, Transport](./types.ssc)
   *    [List, Map, Card as UICard](./std/collections.ssc)
   *
   *  These are documented in SPEC.md §3.2 as top-level selective imports
   *  (Markdown links surfaced as `Content.Import` AST nodes), but the std/
   *  library convention embeds them in fenced code blocks for grouping
   *  with the code that uses them.  Inside a fence, the Markdown parser
   *  doesn't see them — scalameta does, and `[X, Y](z)` isn't valid Scala.
   *
   *  Strategy: comment the line(s) out so scalameta sees them as Scala
   *  comments.  The names they introduce are resolved upstream by the
   *  linker (FQN-rewrite via `Linker.rewriteExpr`) or by the typer's
   *  permissive default (Any fallback for undeclared names); strict-mode
   *  flags them, which is the correct surface-level diagnostic.
   *
   *  Multi-line spans (continuation after the comma) are handled by
   *  tracking bracket depth across lines.  Lines that are not list-form
   *  imports — including ordinary Scala `[...](...)` such as type-argument
   *  applications — pass through untouched. */

  /** Translate path-shaped `import` statements:
   *
   *    import std/mapreduce/dataset.{Dataset}        →
   *    import std.mapreduce.dataset.{Dataset}
   *
   *  Some std/ modules (and external libraries written by people coming
   *  from Python / ES module / Rust paths) use `/` as the segment
   *  separator inside `import` clauses.  scalameta strictly expects `.`,
   *  so the `/`-form fails parsing.  This preprocessor rewrites the
   *  segment separator BEFORE the `.{` member selector, leaving the
   *  selector contents untouched.
   *
   *  Conservative: only rewrites lines starting with `import` (allowing
   *  leading whitespace).  String/comment-position detection isn't
   *  needed because `import` outside a top-level position is rare enough
   *  to be hand-fixed; this preprocessor solves the import-statement
   *  case which is what std/ relies on. */
  private def preprocessSlashImports(code: String): String =
    val importPat = """^(\s*import\s+)([A-Za-z_][\w]*(?:/[A-Za-z_][\w]*)+)(\.\{.*|\..*|\s*$)""".r
    val lines = code.linesIterator.toArray
    if !lines.exists(l => l.startsWith("import ") || l.matches("""^\s+import\s.*""")) then
      return code
    val out = new StringBuilder
    var changed = false
    for line <- lines do
      importPat.findFirstMatchIn(line) match
        case Some(m) if m.group(2).contains("/") =>
          out.append(m.group(1))
            .append(m.group(2).replace('/', '.'))
            .append(m.group(3))
            .append('\n')
          changed = true
        case _ =>
          out.append(line).append('\n')
    if changed then out.toString else code

  private def preprocessInlineImports(code: String): String =
    val lines = code.linesIterator.toArray
    // Quick reject: only inspect files that actually contain a `]( ... .ssc)` or
    // `]( dep:` etc.  Cheap pre-filter.
    if !lines.exists(l => l.contains("](") && (l.contains(".ssc") || l.contains("dep:") || l.contains("://"))) then
      return code
    val result = new StringBuilder()

    // Match a single-line list-form import:
    //   leading whitespace, `[Names...]`, `(path)` where path ends in `.ssc`,
    //   contains a scheme (`://`), or starts with `dep:`.
    val singleLine = """^(\s*)\[\s*([A-Za-z_][\w]*(?:\s+as\s+[A-Za-z_][\w]*)?(?:\s*,\s*[A-Za-z_][\w]*(?:\s+as\s+[A-Za-z_][\w]*)?)*)\s*\]\(\s*([^)]+)\s*\)\s*$""".r
    // Match the start of a multi-line list-form import: opening `[` with names
    // but no closing `]` on the same line.
    val multiStart  = """^(\s*)\[\s*([A-Za-z_][\w]*(?:\s+as\s+[A-Za-z_][\w]*)?(?:\s*,\s*[A-Za-z_][\w]*(?:\s+as\s+[A-Za-z_][\w]*)?)*)\s*,\s*$""".r

    var i = 0
    while i < lines.length do
      val line = lines(i)
      singleLine.findFirstMatchIn(line) match
        case Some(m) =>
          // Replace whole line with a comment preserving indentation.
          result.append(m.group(1)).append("// list-import: [")
                .append(m.group(2)).append("](").append(m.group(3)).append(")\n")
          i += 1
        case None =>
          multiStart.findFirstMatchIn(line) match
            case Some(m) =>
              // Multi-line span: gather continuation until we hit `](path)` close.
              val buf = new StringBuilder(line)
              var j = i + 1
              var closed = false
              while j < lines.length && !closed do
                buf.append("\n").append(lines(j))
                if lines(j).contains("](") && lines(j).indexOf(')', lines(j).indexOf("](")) >= 0 then
                  closed = true
                j += 1
              if closed then
                val joined = buf.toString.replaceAll("\\s*\n\\s*", " ")
                singleLine.findFirstMatchIn(joined) match
                  case Some(im) =>
                    result.append(m.group(1)).append("// list-import: [")
                          .append(im.group(2)).append("](").append(im.group(3)).append(")\n")
                    i = j
                  case None =>
                    // Couldn't re-match as a list-import after merging; fall
                    // through to passthrough.
                    result.append(line).append("\n")
                    i += 1
              else
                result.append(line).append("\n")
                i += 1
            case None =>
              result.append(line).append("\n")
              i += 1
    result.toString

  // Preprocess `effect Name:` declarations into `object Name { def op(...) = __effectOp__ }`.
  private def preprocessEffects(code: String): String =
    val effectLine = """^(\s*)effect\s+(\w+)(?:\s+extends\s+\S+)?\s*:""".r
    val lines = code.linesIterator.toArray
    if !lines.exists(l => effectLine.findFirstIn(l).isDefined) then return code
    val result = new StringBuilder()
    var i = 0
    while i < lines.length do
      val line = lines(i)
      effectLine.findFirstMatchIn(line) match
        case Some(m) =>
          val baseIndent = m.group(1).length
          val effectName = m.group(2)
          result.append(m.group(1)).append("object ").append(effectName).append(" {\n")
          i += 1
          while i < lines.length && {
            val l = lines(i)
            l.isBlank || (l.nonEmpty && l.indexWhere(_ != ' ') > baseIndent)
          } do
            val bodyLine = lines(i)
            val processed =
              if bodyLine.trim.startsWith("def ") && !bodyLine.contains("=")
              then bodyLine.stripTrailing + " = __effectOp__"
              else bodyLine
            result.append(processed).append("\n")
            i += 1
          result.append(m.group(1)).append("}\n")
        case None =>
          result.append(line).append("\n")
          i += 1
    result.toString

  /** Re-parse a scalascript code-block body to a scalameta `ScalaNode`.
   *
   *  Public so `transform.Denormalize` can rebuild the AST trees that
   *  `Normalize` strips when serialising to IR — backends still consume
   *  the parsed tree until they migrate to IR-native traversal. */
  def parseScalaSource(code: String): Option[ScalaNode] =
    parseScalaWithDiagnostic(code)._1

  /** Same as `parseScalaSource` but also returns a structured
   *  `CodeBlockParseError` (populated when both the source-mode parse AND the
   *  block-wrapped term-mode parse fail).  The error's `line` / `column` come
   *  from scalameta's `Parsed.Error.pos` and refer to positions inside the
   *  ORIGINAL `code` argument (we map the wrapped-`{ }` position back when the
   *  fallback parse produced the diagnostic by subtracting the synthetic line).
   *
   *  Returned tuple: `(tree, parseError)`.  `tree` is `Some` and `parseError`
   *  is `None` on success; on failure `tree` is `None` and `parseError` is
   *  `Some` carrying the diagnostic. */
  def parseScalaWithDiagnostic(code: String): (Option[ScalaNode], Option[CodeBlockParseError]) =
    import scala.meta.*
    given Dialect = dialects.Scala3
    val processed = preprocessExtern(preprocessEffects(preprocessInlineImports(preprocessSlashImports(code))))
    processed.parse[Source] match
      case Parsed.Success(tree) => (Some(ScalaNode(tree)), None)
      case sourceErr: Parsed.Error =>
        // Script mode: code may contain top-level expressions.
        // Wrap in a block so scalameta accepts arbitrary statement sequences.
        // Note: the wrap prepends a synthetic `{\n` line so positions in the
        // wrapped parse are offset by +1 line.  We undo that mapping below.
        s"{\n$processed\n}".parse[Term] match
          case Parsed.Success(tree) => (Some(ScalaNode(tree)), None)
          case _: Parsed.Error      =>
            // Both attempts failed.  We prefer the source-mode error since
            // its positions map 1:1 to the user's block body (no wrap-line
            // shift); the term-mode error tends to be misleading anyway
            // (it complains about an unexpected `{` introduction).
            (None, Some(buildParseError(code, sourceErr)))

  /** Build a `CodeBlockParseError` from scalameta's `Parsed.Error` against the
   *  ORIGINAL block source `code`.  Scalameta's `pos.startLine` / `startColumn`
   *  are 0-indexed; we expose 1-indexed numbers to match human convention. */
  private def buildParseError(code: String, err: scala.meta.parsers.Parsed.Error): CodeBlockParseError =
    val pos      = err.pos
    // 0-indexed → 1-indexed.  `code` here is the block body before
    // `preprocessExtern`/`preprocessEffects`; in the common case those
    // preprocessors are no-ops, so positions in `processed` align with `code`.
    // When they do rewrite, positions may be slightly off — still useful as
    // a coarse pointer, and far better than the previous no-position output.
    val line0    = math.max(0, pos.startLine)
    val col0     = math.max(0, pos.startColumn)
    val line     = line0 + 1
    val column   = col0 + 1
    val lines    = code.linesIterator.toArray
    // Defensive: clamp the line if the preprocessor shifted things.
    val safeLine = if line0 < lines.length then line0 else math.max(0, lines.length - 1)
    val snippet  = buildSnippet(lines, safeLine, col0)
    CodeBlockParseError(
      message = err.message,
      line    = line,
      column  = column,
      snippet = snippet
    )

  /** Render a 3-line context window (1 before + failing + 1 after) with a
   *  `^` caret line under the failing column.  Boundaries are handled by
   *  emitting fewer context lines when the failing line is at the start /
   *  end of `lines`.  Each line is rendered with a 2-space indent so the
   *  CLI can emit the snippet verbatim under its diagnostic header. */
  private def buildSnippet(lines: Array[String], lineIdx0: Int, colIdx0: Int): String =
    if lines.isEmpty then return ""
    val safeIdx = math.max(0, math.min(lineIdx0, lines.length - 1))
    val from    = math.max(0, safeIdx - 1)
    val to      = math.min(lines.length - 1, safeIdx + 1)
    val buf     = new StringBuilder()
    var i       = from
    while i <= to do
      buf.append("  ").append(lines(i)).append('\n')
      if i == safeIdx then
        // Caret line: 2-space prefix (matches the snippet indent above) +
        // colIdx0 spaces + `^`.
        buf.append("  ")
        val safeCol = math.max(0, colIdx0)
        var j = 0
        while j < safeCol do { buf.append(' '); j += 1 }
        buf.append('^').append('\n')
      i += 1
    // Trim trailing newline so callers can println without a blank line.
    if buf.nonEmpty && buf.charAt(buf.length - 1) == '\n' then buf.setLength(buf.length - 1)
    buf.toString

  // ─── Text extraction from CommonMark nodes ────────────────────────

  private def textOf(node: CmNode): String =
    val buf = StringBuilder()
    def walk(n: CmNode): Unit =
      n match
        case t: CmText => buf ++= t.getLiteral
        case c: CmCode => buf ++= "`" ++= c.getLiteral ++= "`"
        case _         => ()
      var child = n.getFirstChild
      while child != null do
        walk(child)
        child = child.getNext
    walk(node)
    buf.toString.trim
