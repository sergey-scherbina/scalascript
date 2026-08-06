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
    val sb = StringBuilder()
    if !opts.omitXmlDecl then
      val decl = doc.decl.getOrElse(Markup.XmlDecl("1.0"))
      sb.append(s"""<?xml version="${decl.version}"""")
      decl.encoding.foreach(enc => sb.append(s""" encoding="$enc""""))
      decl.standalone.foreach(s => sb.append(s""" standalone="${if s then "yes" else "no"}""""))
      sb.append("?>")
      if opts.pretty then sb.append('\n')
    doc.docType.foreach { dt =>
      sb.append(s"<!DOCTYPE ${dt.name}")
      dt.publicId.foreach(p => sb.append(s""" PUBLIC "$p""""))
      dt.systemId.foreach(s => sb.append(s""" "$s""""))
      sb.append('>')
      if opts.pretty then sb.append('\n')
    }
    serializeNode(doc.root, sb, opts, depth = 0)
    doc.trailing.foreach { n => serializeNode(n, sb, opts, depth = 0) }
    sb.toString

  private def serializeNode(node: Markup.Node, sb: StringBuilder, opts: SerializeOpts, depth: Int): Unit =
    val pad = if opts.pretty then opts.indent * depth else ""
    node match
      case e: Markup.Element =>
        if opts.pretty then sb.append(pad)
        sb.append('<').append(e.name.toXml)
        e.attrs.foreach { a =>
          sb.append(' ').append(a.name.toXml)
             .append('=').append('"').append(XmlEscape.escapeAttr(a.value)).append('"')
        }
        if e.children.isEmpty then
          sb.append("/>")
        else
          sb.append('>')
          val hasElementChild = e.children.exists(_.isInstanceOf[Markup.Element])
          if opts.pretty && hasElementChild then sb.append('\n')
          e.children.foreach(serializeNode(_, sb, opts, depth + 1))
          if opts.pretty && hasElementChild then sb.append(pad)
          sb.append("</").append(e.name.toXml).append('>')
        if opts.pretty then sb.append('\n')

      case Markup.Text(chars) =>
        sb.append(XmlEscape.escapeText(chars))

      case Markup.CData(chars) =>
        sb.append("<![CDATA[").append(chars).append("]]>")

      case Markup.Comment(text) =>
        if opts.pretty then sb.append(pad)
        sb.append("<!--").append(text).append("-->")
        if opts.pretty then sb.append('\n')

      case Markup.PI(target, data) =>
        if opts.pretty then sb.append(pad)
        sb.append("<?").append(target)
        if data.nonEmpty then sb.append(' ').append(data)
        sb.append("?>")
        if opts.pretty then sb.append('\n')

      case Markup.Raw(chars) =>
        sb.append(chars)

      case _ => ()   // Doc / Attr / DocType / XmlDecl handled at top level

  // ── Inner parser ─────────────────────────────────────────────────────────

  private final class Parser(src: String):
    private var pos    = 0
    private var line   = 1
    private var col    = 1

    private def cur: Char = if pos < src.length then src.charAt(pos) else 0.toChar

    private def advance(n: Int = 1): Unit =
      var i = 0
      while i < n do
        if pos < src.length then
          if src.charAt(pos) == '\n' then { line += 1; col = 1 }
          else col += 1
          pos += 1
        i += 1

    private def err(msg: String): Nothing =
      throw ParseError(msg, line, col)

    private def require(expected: String): Unit =
      if !src.startsWith(expected, pos) then err(s"expected '$expected'")
      advance(expected.length)

    // XML 1.0 §2.3: `S ::= (#x20 | #x9 | #xD | #xA)+`. FOUR characters, and the host's
    // `Char.isWhitespace` is not that set: it also admits vertical tab, form feed, the file/group/
    // record/unit separators and the Unicode space separators, none of which XML calls whitespace.
    // Being stricter is the correct direction here — the grammar is the authority, not the host —
    // and it is the same range-comparison form the rest of the alphabet work uses.
    private def isXmlSpace(c: Char): Boolean =
      c == ' ' || c == '\t' || c == '\r' || c == '\n'

    private def skipWhitespace(): Unit =
      while pos < src.length && isXmlSpace(src.charAt(pos)) do advance()

    // Scan until we see `until` (exclusive) — returns the scanned text.
    private def scanUntil(until: String): String =
      val start = pos
      while pos < src.length && !src.startsWith(until, pos) do advance()
      if pos >= src.length then err(s"unexpected end of input (expected '$until')")
      src.substring(start, pos)

    def parseDoc(): Markup.Doc =
      skipWhitespace()
      // XML declaration
      val decl = if src.startsWith("<?xml", pos) then
        advance(2)  // <?
        val target = readName()
        if target != "xml" then err("expected <?xml")
        skipWhitespace()
        val attrs = readPseudoAttrs()
        skipWhitespace()
        require("?>")
        skipWhitespace()
        Some(buildXmlDecl(attrs))
      else None

      // DOCTYPE
      val docType = if src.startsWith("<!DOCTYPE", pos) then
        advance(9)
        skipWhitespace()
        val name = readName()
        skipWhitespace()
        var publicId: Option[String] = None
        var systemId: Option[String] = None
        if src.startsWith("PUBLIC", pos) then
          advance(6); skipWhitespace()
          publicId = Some(readQuotedValue())
          skipWhitespace()
          systemId = Some(readQuotedValue())
        else if src.startsWith("SYSTEM", pos) then
          advance(6); skipWhitespace()
          systemId = Some(readQuotedValue())
        skipWhitespace()
        // skip internal subset
        if cur == '[' then
          advance()
          var depth = 1
          while pos < src.length && depth > 0 do
            if cur == '[' then depth += 1
            else if cur == ']' then depth -= 1
            advance()
        skipWhitespace()
        require(">")
        skipWhitespace()
        Some(Markup.DocType(name, publicId, systemId))
      else None

      // skip comments and PIs before root element
      val preRoot = scala.collection.mutable.ListBuffer.empty[Markup.Node]
      var done = false
      while !done do
        skipWhitespace()
        if src.startsWith("<!--", pos) then preRoot += readComment()
        else if src.startsWith("<?", pos) then preRoot += readPI()
        else done = true

      if cur != '<' then err("expected root element")
      val root = readElement()

      // trailing PIs/comments
      val trailing = scala.collection.mutable.ListBuffer.empty[Markup.Node]
      skipWhitespace()
      while pos < src.length do
        if src.startsWith("<!--", pos) then trailing += readComment()
        else if src.startsWith("<?", pos) then trailing += readPI()
        else if isXmlSpace(src.charAt(pos)) then skipWhitespace()
        else err(s"unexpected content after root element at position $pos")

      Markup.Doc(decl, docType, root, trailing.toList)

    private def buildXmlDecl(attrs: Map[String, String]): Markup.XmlDecl =
      Markup.XmlDecl(
        version    = attrs.getOrElse("version", "1.0"),
        encoding   = attrs.get("encoding"),
        standalone = attrs.get("standalone").map(_ == "yes"),
      )

    private def readPseudoAttrs(): Map[String, String] =
      val m = scala.collection.mutable.LinkedHashMap.empty[String, String]
      while cur != '?' do
        skipWhitespace()
        if cur == '?' then ()
        else
          val name = readName()
          skipWhitespace()
          require("=")
          skipWhitespace()
          val value = readQuotedValue()
          m(name) = value
      m.toMap

    private def readElement(): Markup.Element =
      require("<")
      val name = readQName()
      val attrs = readAttrs()
      skipWhitespace()
      if cur == '/' then
        advance(); require(">")
        Markup.Element(name, attrs)
      else
        require(">")
        val children = readContent(name)
        Markup.Element(name, attrs, children)

    private def readAttrs(): List[Markup.Attr] =
      val buf = scala.collection.mutable.ListBuffer.empty[Markup.Attr]
      skipWhitespace()
      while cur != '>' && cur != '/' do
        val name = readQName()
        skipWhitespace()
        require("=")
        skipWhitespace()
        val value = XmlEscape.unescape(readQuotedValue())
        buf += Markup.Attr(name, value)
        skipWhitespace()
      buf.toList

    private def readContent(parentName: Markup.QName): List[Markup.Node] =
      val buf = scala.collection.mutable.ListBuffer.empty[Markup.Node]
      while pos < src.length do
        if src.startsWith("</", pos) then
          advance(2)
          val closeName = readQName()
          if closeName.localName != parentName.localName then
            err(s"mismatched closing tag: expected </${parentName.toXml}>, got </${closeName.toXml}>")
          skipWhitespace()
          require(">")
          return buf.toList
        else if src.startsWith("<!--", pos) then
          buf += readComment()
        else if src.startsWith("<![CDATA[", pos) then
          buf += readCData()
        else if src.startsWith("<?", pos) then
          buf += readPI()
        else if cur == '<' then
          buf += readElement()
        else
          buf += readText()
      err(s"unexpected end of input inside element <${parentName.toXml}>")

    private def readText(): Markup.Text =
      val sb = StringBuilder()
      while pos < src.length && cur != '<' do
        if cur == '&' then sb.append(readEntity())
        else { sb.append(cur); advance() }
      Markup.Text(sb.toString)

    private def readEntity(): String =
      advance()  // skip &
      if cur == '#' then
        advance()
        val hex = cur == 'x'
        if hex then advance()
        val numStr = scanUntil(";")
        advance()  // skip ;
        val code = if hex then Integer.parseInt(numStr, 16) else numStr.toInt
        String.valueOf(code.toChar)
      else
        val name = scanUntil(";")
        advance()  // skip ;
        name match
          case "amp"  => "&"
          case "lt"   => "<"
          case "gt"   => ">"
          case "quot" => "\""
          case "apos" => "'"
          case other  => s"&$other;"   // pass through unknown named entities

    private def readComment(): Markup.Comment =
      require("<!--")
      val text = scanUntil("-->")
      advance(3)  // -->
      Markup.Comment(text)

    private def readCData(): Markup.CData =
      require("<![CDATA[")
      val text = scanUntil("]]>")
      advance(3)  // ]]>
      Markup.CData(text)

    private def readPI(): Markup.PI =
      require("<?")
      val target = readName()
      skipWhitespace()
      val data = scanUntil("?>")
      advance(2)  // ?>
      Markup.PI(target, data.trim)

    private def readQName(): Markup.QName =
      val first = readName()
      if cur == ':' then
        advance()
        val local = readName()
        Markup.QName(Some(first), local, None)
      else
        Markup.QName(None, first, None)

    private def readName(): String =
      if pos >= src.length || !isNameStart(cur) then err(s"expected XML name, got '${cur}'")
      val start = pos
      while pos < src.length && isNameChar(cur) do advance()
      src.substring(start, pos)

    private def readQuotedValue(): String =
      val quote = cur
      if quote != '"' && quote != '\'' then err(s"expected quote, got '$quote'")
      advance()
      val start = pos
      while pos < src.length && cur != quote do advance()
      if pos >= src.length then err("unterminated quoted value")
      val v = src.substring(start, pos)
      advance()  // closing quote
      v

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
