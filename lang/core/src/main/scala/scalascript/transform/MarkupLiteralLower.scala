package scalascript.transform

import scalascript.ast

/** Source-level lowering of element-literal syntax (v1.55.5).
 *
 *  When `import scalascript.markup.*` appears anywhere in a scalascript
 *  code block, every angle-bracket element expression in that block is
 *  rewritten to an equivalent `Markup.*` constructor call before the
 *  block is handed to scalameta for parsing.
 *
 *  Supported forms:
 *
 *    Self-closing:
 *      `<name/>`                → `Markup.Element(QName.local("name"), Nil, Nil)`
 *      `<name a="v"/>`          → `Markup.Element(QName.local("name"), List(Attr(QName.local("a"), "v")), Nil)`
 *      `<name a={expr}/>`       → `Markup.Element(QName.local("name"), List(Attr(QName.local("a"), expr.toString)), Nil)`
 *
 *    Open/close:
 *      `<name>children</name>`  → `Markup.Element(QName.local("name"), Nil, List(children...))`
 *
 *    Text children:
 *      plain text between tags  → `Markup.Text("text")`
 *
 *    Nested elements:
 *      recursively lowered
 *
 *  Namespaced tags:
 *      `<ns:tag/>`              → `Markup.Element(QName.prefixed("ns", "tag"), Nil, Nil)`
 *
 *  The transform is intentionally conservative: it runs only when
 *  the import sentinel is present and only on well-formed element
 *  expressions.  Anything it does not recognise is left as-is so the
 *  user sees a natural Scala parse error rather than a cryptic rewrite
 *  failure.
 *
 *  It is wired into `Parser.parse` (after `RouteDeriver.derive`) so
 *  every path through the compiler pipeline benefits from the lowering.
 */
object MarkupLiteralLower:

  /** Sentinel that activates element-literal lowering for a code block. */
  private val ImportSentinel = "import scalascript.markup"

  /** Lower all scalascript code blocks in a module that opt in via the
   *  `import scalascript.markup.*` sentinel. */
  def lower(module: ast.Module): ast.Module =
    module.copy(sections = module.sections.map(lowerSection))

  private def lowerSection(s: ast.Section): ast.Section =
    s.copy(
      content     = s.content.map(lowerContent),
      subsections = s.subsections.map(lowerSection)
    )

  private def lowerContent(c: ast.Content): ast.Content = c match
    case cb: ast.Content.CodeBlock if ast.Lang.isScalaScript(cb.lang) =>
      if cb.source.contains(ImportSentinel) then
        val rewritten = lowerSource(cb.source)
        if rewritten == cb.source then cb
        else cb.copy(source = rewritten, tree = None) // re-parse after rewrite
      else cb
    case other => other

  // ── Source rewriter ────────────────────────────────────────────────────────

  /** Rewrite all top-level element literals in `src`.
   *
   *  Scans the source character-by-character.  When `<` is found at a
   *  position that is not inside a string literal or comment and is
   *  followed by a valid XML name character, it is treated as the start
   *  of an element literal and handed to `parseElement`.  Everything
   *  else is copied verbatim. */
  private[transform] def lowerSource(src: String): String =
    val sb  = StringBuilder()
    val len = src.length
    var i   = 0
    while i < len do
      val ch = src.charAt(i)
      ch match
        case '/' if i + 1 < len && src.charAt(i + 1) == '/' =>
          // Line comment — copy to end of line
          val end = src.indexOf('\n', i)
          if end == -1 then { sb.append(src.substring(i)); i = len }
          else { sb.append(src.substring(i, end)); i = end }

        case '/' if i + 1 < len && src.charAt(i + 1) == '*' =>
          // Block comment — copy to */
          val end = src.indexOf("*/", i + 2)
          if end == -1 then { sb.append(src.substring(i)); i = len }
          else { sb.append(src.substring(i, end + 2)); i = end + 2 }

        case '"' =>
          // String literal — copy including its contents verbatim.
          // Handle triple-quoted strings.
          if i + 2 < len && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"' then
            val end = src.indexOf("\"\"\"", i + 3)
            if end == -1 then { sb.append(src.substring(i)); i = len }
            else { sb.append(src.substring(i, end + 3)); i = end + 3 }
          else
            sb.append('"')
            i += 1
            var closed = false
            while i < len && !closed do
              val c2 = src.charAt(i)
              sb.append(c2)
              if c2 == '\\' && i + 1 < len then { sb.append(src.charAt(i + 1)); i += 2 }
              else if c2 == '"' then { closed = true; i += 1 }
              else i += 1

        case '<' if i + 1 < len && isNameStart(src.charAt(i + 1)) =>
          // Possible element literal.  Try to parse it.
          tryParseElement(src, i) match
            case Some((elem, after)) =>
              sb.append(elem)
              i = after
            case None =>
              sb.append('<')
              i += 1

        case _ =>
          sb.append(ch)
          i += 1
    sb.toString

  // ── Element parser ─────────────────────────────────────────────────────────

  /** Attempt to parse an element literal starting at position `start` in `src`.
   *  Returns `Some((rewritten, nextPos))` on success, `None` on failure. */
  private def tryParseElement(src: String, start: Int): Option[(String, Int)] =
    try Some(parseElement(src, start + 1)) // skip the '<'
    catch case _: Exception => None

  /** Parse `name attrs />`  or  `name attrs > children </name>` starting
   *  just after the opening `<`.
   *  Returns `(scalascript-expr, position-after-close)`. */
  private def parseElement(src: String, pos0: Int): (String, Int) =
    val len = src.length

    // 1. Tag name
    var i    = pos0
    val nameStart = i
    while i < len && isNameChar(src.charAt(i)) do i += 1
    val tagName = src.substring(nameStart, i)

    // 2. Attributes
    val attrs = scala.collection.mutable.ListBuffer[(String, String)]()
    i = skipWs(src, i)
    while i < len && src.charAt(i) != '>' && !(src.charAt(i) == '/' && i + 1 < len && src.charAt(i + 1) == '>') do
      // attribute name
      val anStart = i
      while i < len && isNameChar(src.charAt(i)) do i += 1
      val attrName = src.substring(anStart, i)
      i = skipWs(src, i)
      require(i < len && src.charAt(i) == '=', s"expected '=' after attribute name '$attrName'")
      i += 1 // skip '='
      i = skipWs(src, i)
      // attribute value: "..." or {...}
      val attrVal =
        if src.charAt(i) == '"' then
          i += 1
          val vStart = i
          while i < len && src.charAt(i) != '"' do i += 1
          val v = src.substring(vStart, i)
          i += 1 // skip closing '"'
          s""""$v""""
        else if src.charAt(i) == '{' then
          val (expr, after) = readBraced(src, i)
          i = after
          s"($expr).toString"
        else
          throw new RuntimeException(s"unexpected char '${src.charAt(i)}' in attribute value")
      attrs += ((attrName, attrVal))
      i = skipWs(src, i)

    val qNameCall = buildQName(tagName)
    val attrsScala = buildAttrsList(attrs.toList)

    // 3. Self-closing or open/close
    if i < len && src.charAt(i) == '/' then
      // self-closing: />
      require(i + 1 < len && src.charAt(i + 1) == '>', "expected '>' after '/'")
      i += 2
      (s"Markup.Element($qNameCall, $attrsScala, Nil)", i)
    else
      // open tag end
      require(i < len && src.charAt(i) == '>', s"expected '>' to close opening tag <$tagName>")
      i += 1
      // 4. Children
      val (childrenScala, afterChildren) = parseChildren(src, i, tagName)
      (s"Markup.Element($qNameCall, $attrsScala, $childrenScala)", afterChildren)

  /** Parse children of an element until `</tagName>`.
   *  Returns `(List(...) scala expr, position after </tagName>)`. */
  private def parseChildren(src: String, pos0: Int, parentTag: String): (String, Int) =
    val len  = src.length
    var i    = pos0
    val kids = scala.collection.mutable.ListBuffer[String]()

    while i < len do
      if src.startsWith("</", i) then
        // Closing tag
        i += 2 // skip </
        val cnStart = i
        while i < len && isNameChar(src.charAt(i)) do i += 1
        val closeTag = src.substring(cnStart, i)
        require(closeTag == parentTag, s"mismatched tag: expected </$parentTag> but found </$closeTag>")
        i = skipWs(src, i)
        require(i < len && src.charAt(i) == '>', s"expected '>' after closing tag </$parentTag>")
        i += 1
        val listExpr =
          if kids.isEmpty then "Nil"
          else s"List(${kids.mkString(", ")})"
        return (listExpr, i)
      else if src.charAt(i) == '<' && i + 1 < len && isNameStart(src.charAt(i + 1)) then
        // Nested element literal
        val (childExpr, after) = parseElement(src, i + 1)
        kids += childExpr
        i = after
      else
        // Text node: gather text until < or end
        val tStart = i
        while i < len && !(src.charAt(i) == '<') do i += 1
        val text = src.substring(tStart, i).trim
        if text.nonEmpty then
          val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
          kids += s"""Markup.Text("$escaped")"""

    throw new RuntimeException(s"missing closing tag </$parentTag>")

  // ── Helpers ────────────────────────────────────────────────────────────────

  /** Build `QName.local("name")` or `QName.prefixed("ns", "local")`. */
  private def buildQName(name: String): String =
    val colonIdx = name.indexOf(':')
    if colonIdx < 0 then
      s"""QName.local("$name")"""
    else
      val prefix = name.substring(0, colonIdx)
      val local  = name.substring(colonIdx + 1)
      s"""QName.prefixed("$prefix", "$local")"""

  /** Build `Nil` or `List(Attr(QName.local("a"), val), ...)`. */
  private def buildAttrsList(attrs: List[(String, String)]): String =
    if attrs.isEmpty then "Nil"
    else
      val parts = attrs.map { case (name, value) =>
        val qn = buildQName(name)
        s"Attr($qn, $value)"
      }
      s"List(${parts.mkString(", ")})"

  /** Read a `{...}` brace group, returning (inner-expr, position-after-close).
   *  Handles nested braces. */
  private def readBraced(src: String, pos: Int): (String, Int) =
    require(src.charAt(pos) == '{')
    val len   = src.length
    var depth = 0
    var i     = pos
    val sb    = StringBuilder()
    while i < len do
      val ch = src.charAt(i)
      if ch == '{' then { depth += 1; i += 1 }
      else if ch == '}' then
        depth -= 1
        i += 1
        if depth == 0 then
          return (sb.toString, i)
        else sb.append(ch)
      else { sb.append(ch); i += 1 }
    throw new RuntimeException("unterminated brace group")

  private def skipWs(src: String, pos: Int): Int =
    var i = pos
    while i < src.length && src.charAt(i).isWhitespace do i += 1
    i

  private def isNameStart(ch: Char): Boolean =
    ch.isLetter || ch == '_'

  private def isNameChar(ch: Char): Boolean =
    ch.isLetterOrDigit || ch == '_' || ch == '-' || ch == ':' || ch == '.'
