package scalascript.markup

/** Pure-Scala XML 1.0 parser and serializer.  Zero dependencies.
 *  Used as the fallback codec for all backends and the sole codec for
 *  Scala Native.  Handles well-formed XML; rejects DTDs (other than the
 *  XML declaration line) and does not resolve external entities. */
object PureMarkupCodec extends MarkupCodec:

  val id = "pure"

  // ── Parser ───────────────────────────────────────────────────────────────

  def parse(src: String, dialect: Dialect = Dialect.Xml1_0): Either[ParseError, Markup.Doc] =
    try Right(Parser(src).parseDoc())
    catch case e: ParseError => Left(e)

  // ── Serializer ───────────────────────────────────────────────────────────

  def serialize(doc: Markup.Doc, opts: SerializeOpts = SerializeOpts.default): String =
    val declParts: Vector[String] =
      if opts.omitXmlDecl then Vector.empty
      else
        val decl = doc.decl.getOrElse(Markup.XmlDecl("1.0"))
        Vector(s"""<?xml version="${decl.version}"""") ++
          decl.encoding.map(enc => s""" encoding="$enc"""").toVector ++
          decl.standalone.map(sa => s""" standalone="${if sa then "yes" else "no"}"""").toVector ++
          Vector("?>") ++ (if opts.pretty then Vector("\n") else Vector.empty)
    val docTypeParts: Vector[String] = doc.docType.toVector.flatMap { dt =>
      Vector(s"<!DOCTYPE ${dt.name}") ++
        dt.publicId.map(p => s""" PUBLIC "$p"""").toVector ++
        dt.systemId.map(sy => s""" "$sy"""").toVector ++
        Vector(">") ++ (if opts.pretty then Vector("\n") else Vector.empty)
    }
    (declParts ++ docTypeParts ++ serializeNode(doc.root, opts, depth = 0) ++
      doc.trailing.toVector.flatMap(n => serializeNode(n, opts, depth = 0))).mkString

  private def serializeNode(node: Markup.Node, opts: SerializeOpts, depth: Int): Vector[String] =
    val pad = if opts.pretty then opts.indent * depth else ""
    node match
      case e: Markup.Element =>
        val head = (if opts.pretty then Vector(pad) else Vector.empty) ++
          Vector("<", e.name.toXml) ++
          e.attrs.toVector.flatMap(a => Vector(" ", a.name.toXml, "=", "\"", XmlEscape.escapeAttr(a.value), "\""))
        val body =
          if e.children.isEmpty then Vector("/>")
          else
            val hasElementChild = e.children.exists(_.isInstanceOf[Markup.Element])
            Vector(">") ++
              (if opts.pretty && hasElementChild then Vector("\n") else Vector.empty) ++
              e.children.toVector.flatMap(serializeNode(_, opts, depth + 1)) ++
              (if opts.pretty && hasElementChild then Vector(pad) else Vector.empty) ++
              Vector("</", e.name.toXml, ">")
        head ++ body ++ (if opts.pretty then Vector("\n") else Vector.empty)

      case Markup.Text(chars) =>
        Vector(XmlEscape.escapeText(chars))

      case Markup.CData(chars) =>
        Vector("<![CDATA[", chars, "]]>")

      case Markup.Comment(text) =>
        (if opts.pretty then Vector(pad) else Vector.empty) ++
          Vector("<!--", text, "-->") ++
          (if opts.pretty then Vector("\n") else Vector.empty)

      case Markup.PI(target, data) =>
        (if opts.pretty then Vector(pad) else Vector.empty) ++
          Vector("<?", target) ++
          (if data.nonEmpty then Vector(" ", data) else Vector.empty) ++
          Vector("?>") ++
          (if opts.pretty then Vector("\n") else Vector.empty)

      case Markup.Raw(chars) =>
        Vector(chars)

      case _ => Vector.empty   // Doc / Attr / DocType / XmlDecl handled at top level

  // ── Inner parser ─────────────────────────────────────────────────────────

  /** The parser's cursor: position plus the line/column its error messages cite. */
  private final case class Cursor(pos: Int, line: Int, col: Int)

  private final class Parser(src: String):
    private def cur(c: Cursor): Char = if c.pos < src.length then src.charAt(c.pos) else 0.toChar

    private def advance(c: Cursor, n: Int = 1): Cursor =
      if n <= 0 || c.pos >= src.length then c
      else if src.charAt(c.pos) == '\n' then advance(Cursor(c.pos + 1, c.line + 1, 1), n - 1)
      else advance(Cursor(c.pos + 1, c.line, c.col + 1), n - 1)

    private def err(c: Cursor, msg: String): Nothing =
      throw ParseError(msg, c.line, c.col)

    private def require(c: Cursor, expected: String): Cursor =
      if !src.startsWith(expected, c.pos) then err(c, s"expected '$expected'")
      advance(c, expected.length)

    // XML 1.0 §2.3: `S ::= (#x20 | #x9 | #xD | #xA)+`. FOUR characters, and the host's
    // `Char.isWhitespace` is not that set: it also admits vertical tab, form feed, the file/group/
    // record/unit separators and the Unicode space separators, none of which XML calls whitespace.
    // Being stricter is the correct direction here — the grammar is the authority, not the host —
    // and it is the same range-comparison form the rest of the alphabet work uses.
    private def isXmlSpace(c: Char): Boolean =
      c == ' ' || c == '\t' || c == '\r' || c == '\n'

    private def skipWhitespace(c: Cursor): Cursor =
      if c.pos < src.length && isXmlSpace(src.charAt(c.pos)) then skipWhitespace(advance(c)) else c

    // Scan until we see `until` (exclusive) — returns the scanned text.
    private def scanUntil(c0: Cursor, until: String): (Cursor, String) =
      def scan(c: Cursor): Cursor =
        if c.pos < src.length && !src.startsWith(until, c.pos) then scan(advance(c)) else c
      val stop = scan(c0)
      if stop.pos >= src.length then err(stop, s"unexpected end of input (expected '$until')")
      (stop, src.substring(c0.pos, stop.pos))

    def parseDoc(): Markup.Doc =
      val c0 = skipWhitespace(Cursor(0, 1, 1))
      // XML declaration
      val (c1, decl) =
        if src.startsWith("<?xml", c0.pos) then
          val (cT, target) = readName(advance(c0, 2)) // <?
          if target != "xml" then err(cT, "expected <?xml")
          val (cA, attrs) = readPseudoAttrs(skipWhitespace(cT))
          val cEnd = skipWhitespace(require(skipWhitespace(cA), "?>"))
          (cEnd, Some(buildXmlDecl(attrs)))
        else (c0, None)

      // DOCTYPE
      val (c2, docType) =
        if src.startsWith("<!DOCTYPE", c1.pos) then
          val (cN, name) = readName(skipWhitespace(advance(c1, 9)))
          val cAfterName = skipWhitespace(cN)
          val (cIds, publicId, systemId) =
            if src.startsWith("PUBLIC", cAfterName.pos) then
              val (cP, pub) = readQuotedValue(skipWhitespace(advance(cAfterName, 6)))
              val (cS, sys) = readQuotedValue(skipWhitespace(cP))
              (cS, Some(pub), Some(sys))
            else if src.startsWith("SYSTEM", cAfterName.pos) then
              val (cS, sys) = readQuotedValue(skipWhitespace(advance(cAfterName, 6)))
              (cS, None, Some(sys))
            else (cAfterName, None, None)
          val cSubsetStart = skipWhitespace(cIds)
          // skip internal subset
          def subset(c: Cursor, depth: Int): Cursor =
            if c.pos >= src.length || depth == 0 then c
            else if cur(c) == '[' then subset(advance(c), depth + 1)
            else if cur(c) == ']' then subset(advance(c), depth - 1)
            else subset(advance(c), depth)
          val cSubset =
            if cur(cSubsetStart) == '[' then subset(advance(cSubsetStart), 1)
            else cSubsetStart
          val cEnd = skipWhitespace(require(skipWhitespace(cSubset), ">"))
          (cEnd, Some(Markup.DocType(name, publicId, systemId)))
        else (c1, None)

      // skip comments and PIs before root element
      def preRoot(c: Cursor, acc: Vector[Markup.Node]): (Cursor, Vector[Markup.Node]) =
        val cW = skipWhitespace(c)
        if src.startsWith("<!--", cW.pos) then
          val (cC, comment) = readComment(cW)
          preRoot(cC, acc :+ comment)
        else if src.startsWith("<?", cW.pos) then
          val (cP, pi) = readPI(cW)
          preRoot(cP, acc :+ pi)
        else (cW, acc)
      val (c3, _) = preRoot(c2, Vector.empty)

      if cur(c3) != '<' then err(c3, "expected root element")
      val (c4, root) = readElement(c3)

      // trailing PIs/comments
      def trailing(c: Cursor, acc: Vector[Markup.Node]): Vector[Markup.Node] =
        if c.pos >= src.length then acc
        else if src.startsWith("<!--", c.pos) then
          val (cC, comment) = readComment(c)
          trailing(cC, acc :+ comment)
        else if src.startsWith("<?", c.pos) then
          val (cP, pi) = readPI(c)
          trailing(cP, acc :+ pi)
        else if isXmlSpace(src.charAt(c.pos)) then trailing(skipWhitespace(c), acc)
        else err(c, s"unexpected content after root element at position ${c.pos}")
      val trail = trailing(skipWhitespace(c4), Vector.empty)

      Markup.Doc(decl, docType, root, trail.toList)

    private def buildXmlDecl(attrs: Map[String, String]): Markup.XmlDecl =
      Markup.XmlDecl(
        version    = attrs.getOrElse("version", "1.0"),
        encoding   = attrs.get("encoding"),
        standalone = attrs.get("standalone").map(_ == "yes"),
      )

    private def readPseudoAttrs(c0: Cursor): (Cursor, Map[String, String]) =
      def loop(c: Cursor, acc: Map[String, String]): (Cursor, Map[String, String]) =
        if cur(c) == '?' then (c, acc)
        else
          val cW = skipWhitespace(c)
          if cur(cW) == '?' then loop(cW, acc)
          else
            val (cN, name) = readName(cW)
            val cEq = skipWhitespace(require(skipWhitespace(cN), "="))
            val (cV, value) = readQuotedValue(cEq)
            loop(cV, acc.updated(name, value))
      loop(c0, Map.empty)

    private def readElement(c0: Cursor): (Cursor, Markup.Element) =
      val (cN, name) = readQName(require(c0, "<"))
      val (cA, attrs) = readAttrs(cN)
      val cW = skipWhitespace(cA)
      if cur(cW) == '/' then
        (require(advance(cW), ">"), Markup.Element(name, attrs))
      else
        val (cC, children) = readContent(require(cW, ">"), name)
        (cC, Markup.Element(name, attrs, children))

    private def readAttrs(c0: Cursor): (Cursor, List[Markup.Attr]) =
      def loop(c: Cursor, acc: Vector[Markup.Attr]): (Cursor, Vector[Markup.Attr]) =
        if cur(c) != '>' && cur(c) != '/' then
          val (cN, name) = readQName(c)
          val cEq = skipWhitespace(require(skipWhitespace(cN), "="))
          val (cV, raw) = readQuotedValue(cEq)
          loop(skipWhitespace(cV), acc :+ Markup.Attr(name, XmlEscape.unescape(raw)))
        else (c, acc)
      val (cEnd, attrs) = loop(skipWhitespace(c0), Vector.empty)
      (cEnd, attrs.toList)

    private def readContent(c0: Cursor, parentName: Markup.QName): (Cursor, List[Markup.Node]) =
      def loop(c: Cursor, acc: Vector[Markup.Node]): (Cursor, Vector[Markup.Node]) =
        if c.pos >= src.length then
          err(c, s"unexpected end of input inside element <${parentName.toXml}>")
        else if src.startsWith("</", c.pos) then
          val (cN, closeName) = readQName(advance(c, 2))
          if closeName.localName != parentName.localName then
            err(cN, s"mismatched closing tag: expected </${parentName.toXml}>, got </${closeName.toXml}>")
          (require(skipWhitespace(cN), ">"), acc)
        else if src.startsWith("<!--", c.pos) then
          val (cC, comment) = readComment(c)
          loop(cC, acc :+ comment)
        else if src.startsWith("<![CDATA[", c.pos) then
          val (cC, cdata) = readCData(c)
          loop(cC, acc :+ cdata)
        else if src.startsWith("<?", c.pos) then
          val (cP, pi) = readPI(c)
          loop(cP, acc :+ pi)
        else if cur(c) == '<' then
          val (cE, element) = readElement(c)
          loop(cE, acc :+ element)
        else
          val (cT, text) = readText(c)
          loop(cT, acc :+ text)
      val (cEnd, nodes) = loop(c0, Vector.empty)
      (cEnd, nodes.toList)

    private def readText(c0: Cursor): (Cursor, Markup.Text) =
      def loop(c: Cursor, pieces: Vector[String]): (Cursor, Vector[String]) =
        if c.pos >= src.length || cur(c) == '<' then (c, pieces)
        else if cur(c) == '&' then
          val (cE, entity) = readEntity(c)
          loop(cE, pieces :+ entity)
        else loop(advance(c), pieces :+ src.substring(c.pos, c.pos + 1))
      val (cEnd, pieces) = loop(c0, Vector.empty)
      (cEnd, Markup.Text(pieces.mkString))

    private def readEntity(c0: Cursor): (Cursor, String) =
      val c1 = advance(c0)  // skip &
      if cur(c1) == '#' then
        val c2 = advance(c1)
        val hex = cur(c2) == 'x'
        val c3 = if hex then advance(c2) else c2
        val (cN, numStr) = scanUntil(c3, ";")
        val code = if hex then Integer.parseInt(numStr, 16) else numStr.toInt
        (advance(cN), String.valueOf(code.toChar))  // skip ;
      else
        val (cN, name) = scanUntil(c1, ";")
        val decoded = name match
          case "amp"  => "&"
          case "lt"   => "<"
          case "gt"   => ">"
          case "quot" => "\""
          case "apos" => "'"
          case other  => s"&$other;"   // pass through unknown named entities
        (advance(cN), decoded)  // skip ;

    private def readComment(c0: Cursor): (Cursor, Markup.Comment) =
      val (cT, text) = scanUntil(require(c0, "<!--"), "-->")
      (advance(cT, 3), Markup.Comment(text))  // -->

    private def readCData(c0: Cursor): (Cursor, Markup.CData) =
      val (cT, text) = scanUntil(require(c0, "<![CDATA["), "]]>")
      (advance(cT, 3), Markup.CData(text))  // ]]>

    private def readPI(c0: Cursor): (Cursor, Markup.PI) =
      val (cN, target) = readName(require(c0, "<?"))
      val (cD, data) = scanUntil(skipWhitespace(cN), "?>")
      (advance(cD, 2), Markup.PI(target, data.trim))  // ?>

    private def readQName(c0: Cursor): (Cursor, Markup.QName) =
      val (cF, first) = readName(c0)
      if cur(cF) == ':' then
        val (cL, local) = readName(advance(cF))
        (cL, Markup.QName(Some(first), local, None))
      else
        (cF, Markup.QName(None, first, None))

    private def readName(c0: Cursor): (Cursor, String) =
      if c0.pos >= src.length || !isNameStart(cur(c0)) then err(c0, s"expected XML name, got '${cur(c0)}'")
      def nameEnd(c: Cursor): Cursor =
        if c.pos < src.length && isNameChar(cur(c)) then nameEnd(advance(c)) else c
      val stop = nameEnd(c0)
      (stop, src.substring(c0.pos, stop.pos))

    private def readQuotedValue(c0: Cursor): (Cursor, String) =
      val quote = cur(c0)
      if quote != '"' && quote != '\'' then err(c0, s"expected quote, got '$quote'")
      val start = advance(c0)
      def valueEnd(c: Cursor): Cursor =
        if c.pos < src.length && cur(c) != quote then valueEnd(advance(c)) else c
      val stop = valueEnd(start)
      if stop.pos >= src.length then err(stop, "unterminated quoted value")
      (advance(stop), src.substring(start.pos, stop.pos))  // closing quote

    // ── the XML 1.0 alphabet, spelled from the grammar rather than borrowed ──────────────
    //
    // These used to be `c.isLetter` / `c.isLetterOrDigit`, which answer from the HOST's Unicode
    // tables. Two things were wrong with that, and only one of them is UNIML-SSC3-ALPHABET's:
    //
    //  1. host-dependence — the same document parses differently depending on which runtime is
    //     asking, which is what that item exists to remove;
    //  2. it is not the XML grammar. `isLetter` is neither a superset nor a subset of
    //     NameStartChar: XML admits `:` and a `.`-free set of ranges Java calls non-letters, and
    //     Java admits letters XML excludes. Borrowing the host's notion of "letter" for a
    //     production that spells its own ranges was a guess that happened to mostly work.
    //
    // So these are XML 1.0 (5th ed.) §2.3 verbatim, every line a range comparison and no table:
    //
    // NOT `Name` but `NCName`, and the difference is one character. XML 1.0's NameStartChar
    // includes ":", but this parser implements NAMESPACES: it splits `ns:root` into prefix and
    // local name by letting the colon TERMINATE the name scan. Admitting ":" as a name character
    // made `ns:root` one undivided name and `prefix` came back None — caught by the existing
    // `namespaced element and attribute` test, which is the whole reason to run it before
    // believing a grammar quotation. `NCName` (Namespaces in XML §3) is exactly Name minus ":",
    // and QName ::= NCName ':' NCName is handled a level up.
    //
    //   NameStartChar ::= [A-Z] | "_" | [a-z] | [#xC0-#xD6] | [#xD8-#xF6] | [#xF8-#x2FF]
    //                   | [#x370-#x37D] | [#x37F-#x1FFF] | [#x200C-#x200D] | [#x2070-#x218F]
    //                   | [#x2C00-#x2FEF] | [#x3001-#xD7FF] | [#xF900-#xFDCF] | [#xFDF0-#xFFFD]
    //                   | [#x10000-#xEFFFF]
    //   NameChar      ::= NameStartChar | "-" | "." | [0-9] | #xB7 | [#x0300-#x036F]
    //                   | [#x203F-#x2040]
    //
    // THE SUPPLEMENTARY RANGE IS EXPRESSED IN SURROGATES, and the bound is not arbitrary. This
    // parser is `Char`-based, so a code point above the BMP arrives as a pair. `[#x10000-#xEFFFF]`
    // is planes 1-14; plane 15 begins at #xF0000, whose high surrogate is #xDB80. So high
    // surrogates #xD800-#xDB7F are exactly the allowed planes and #xDB80-#xDBFF are the private-use
    // ones XML excludes — the split falls on a surrogate boundary, which is why it can be written
    // as a range at all. Low surrogates are admitted as continuation units.
    private def isNameStart(c: Char): Boolean =
      (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' ||
        (c >= '\u00C0' && c <= '\u00D6') || (c >= '\u00D8' && c <= '\u00F6') ||
        (c >= '\u00F8' && c <= '\u02FF') || (c >= '\u0370' && c <= '\u037D') ||
        (c >= '\u037F' && c <= '\u1FFF') || (c >= '\u200C' && c <= '\u200D') ||
        (c >= '\u2070' && c <= '\u218F') || (c >= '\u2C00' && c <= '\u2FEF') ||
        (c >= '\u3001' && c <= '\uD7FF') || (c >= '\uF900' && c <= '\uFDCF') ||
        (c >= '\uFDF0' && c <= '\uFFFD') ||
        (c >= '\uD800' && c <= '\uDB7F') || (c >= '\uDC00' && c <= '\uDFFF')

    private def isNameChar(c: Char): Boolean =
      isNameStart(c) || c == '-' || c == '.' || (c >= '0' && c <= '9') || c == '\u00B7' ||
        (c >= '\u0300' && c <= '\u036F') || (c >= '\u203F' && c <= '\u2040')
