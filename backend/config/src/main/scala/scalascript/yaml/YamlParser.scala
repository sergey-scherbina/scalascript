package scalascript.yaml

import java.util.{LinkedHashMap as JMap, ArrayList as JList}

/** Pure-Scala YAML subset parser — no external dependencies.
 *
 *  Supported subset:
 *  - Block mappings (`key: value`, `key:` with indented block)
 *  - Block sequences (`- value`, `- ` with indented block)
 *  - Inline mappings starting on the same line as `- `
 *  - Double-quoted scalars with `\"` and `\\` escapes
 *  - Single-quoted scalars with `''` escape
 *  - Flow mappings `{k: v, ...}` and flow sequences `[a, b, ...]`
 *  - Scalar auto-coercion: `null`/`~` → null, `true`/`false` → Boolean,
 *    integers → java.lang.Integer/Long, floats → java.lang.Double, else String
 *  - Inline comments stripped (`# ` not inside quotes)
 *
 *  Not supported: anchors, aliases, tags, block scalars (`|`/`>`).
 *  Throws [[YamlParser.ParseError]] on malformed input.
 *
 *  Returns `java.util.LinkedHashMap[String, Any]` / `java.util.ArrayList[Any]`
 *  for collections — the same types as snakeyaml returned — so callers that already
 *  pattern-match on `java.util.Map[?, ?]` / `java.util.List[?]` need no changes. */
object YamlParser:

  final class ParseError(msg: String, lineNo: Int = 0)
    extends RuntimeException(s"$msg (line ${lineNo + 1})")

  /** Parse `yaml` text and return the root value.
   *
   *  Return type is one of: `null`, `String`, `java.lang.Integer`,
   *  `java.lang.Long`, `java.lang.Double`, `java.lang.Boolean`,
   *  `java.util.LinkedHashMap[String, Any]`, or `java.util.ArrayList[Any]`. */
  def load(yaml: String): Any =
    val text = yaml.trim
    if text.isEmpty then return null
    // Flow-collection root — JSON documents start with `{` or `[`
    if text.startsWith("{") then return parseFlowMap(text, 0)._1
    if text.startsWith("[") then return parseFlowSeq(text, 0)._1
    new BlockParser(yaml.linesIterator.toIndexedSeq).parse()

  // ── Block parser ──────────────────────────────────────────────────────────

  private class BlockParser(lines: IndexedSeq[String]):
    var pos: Int = 0

    def parse(): Any =
      skipBlanks()
      if pos >= lines.length then null
      else parseBlock(indentOf(pos))

    private def parseBlock(baseInd: Int): Any =
      skipBlanks()
      if pos >= lines.length then return null
      val ind = indentOf(pos)
      if ind < baseInd then return null
      val content = contentAt(pos)
      if content.startsWith("- ") || content == "-" then parseSeq(ind)
      else if looksLikeMapEntry(content) then parseMap(ind)
      else { pos += 1; parseScalar(content) }

    private def parseMap(myInd: Int): JMap[String, Any] =
      val m = new JMap[String, Any]()
      while peek(myInd, isSeq = false) do
        val content = contentAt(pos); pos += 1
        val (key, rest) = parseKey(content, pos - 1)
        m.put(key, resolveValue(rest, myInd))
      m

    private def parseSeq(myInd: Int): JList[Any] =
      val l = new JList[Any]()
      while peek(myInd, isSeq = true) do
        val content = contentAt(pos); pos += 1
        val itemStr = if content.startsWith("- ") then content.drop(2).stripLeading() else ""
        // Determine the continuation indent (where block children will sit)
        val childInd = myInd + 2
        val value =
          if itemStr.isEmpty then
            skipBlanks()
            if !done && indentOf(pos) > myInd then parseBlock(indentOf(pos)) else null
          else if looksLikeMapEntry(itemStr) then
            // `- key: value` — item is a mapping; peek for continuation
            skipBlanks()
            val contInd = if !done && indentOf(pos) > myInd then indentOf(pos) else childInd
            parseInlineMap(itemStr, contInd, pos - 1)
          else
            parseScalar(itemStr)
        l.add(value)
      l

    /** Parse a mapping whose first entry is the inline `firstEntry` string and
     *  whose continuation entries are at indent `contInd`. */
    private def parseInlineMap(firstEntry: String, contInd: Int, lineIdx: Int): JMap[String, Any] =
      val m = new JMap[String, Any]()
      val (k, rest) = parseKey(firstEntry, lineIdx)
      m.put(k, resolveValue(rest, contInd - 1))
      while peek(contInd, isSeq = false) do
        val content = contentAt(pos); pos += 1
        val (k2, rest2) = parseKey(content, pos - 1)
        m.put(k2, resolveValue(rest2, contInd))
      m

    private def resolveValue(inline: String, parentInd: Int): Any =
      if inline.nonEmpty then parseScalar(inline)
      else
        skipBlanks()
        if !done && indentOf(pos) > parentInd then parseBlock(indentOf(pos))
        else null

    /** Check whether the next non-blank line is at `targetInd` and matches the
     *  expected collection type (seq/map). Advances past blanks as a side-effect
     *  so the body can read `pos` directly. */
    private def peek(targetInd: Int, isSeq: Boolean): Boolean =
      skipBlanks()
      if done then return false
      val ind = indentOf(pos)
      if ind != targetInd then return false
      val c = contentAt(pos)
      if isSeq then c.startsWith("- ") || c == "-"
      else !c.startsWith("- ") && c != "-"

    private def indentOf(i: Int): Int =
      val n = lines(i).indexWhere(_ != ' ')
      if n < 0 then Int.MaxValue else n

    private def contentAt(i: Int): String =
      stripInlineComment(lines(i).stripLeading())

    private def done: Boolean = pos >= lines.length

    private def skipBlanks(): Unit =
      while pos < lines.length && isBlankOrComment(lines(pos)) do pos += 1

  // ── Key parsing ───────────────────────────────────────────────────────────

  private def parseKey(content: String, lineIdx: Int): (String, String) =
    if content.startsWith("\"") then
      val (k, end) = unquoteDouble(content, 1, lineIdx)
      val after = content.substring(end).stripLeading()
      val rest =
        if after.startsWith(": ") then after.drop(2).stripLeading()
        else if after == ":" || after.isEmpty then ""
        else throw ParseError(s"expected ':' after quoted key", lineIdx)
      (k, rest)
    else if content.startsWith("'") then
      val (k, end) = unquoteSingle(content, 1, lineIdx)
      val after = content.substring(end).stripLeading()
      val rest =
        if after.startsWith(": ") then after.drop(2).stripLeading()
        else if after == ":" || after.isEmpty then ""
        else throw ParseError(s"expected ':' after quoted key", lineIdx)
      (k, rest)
    else
      val idx = content.indexOf(": ")
      if idx >= 0 then
        (content.substring(0, idx).trim, content.substring(idx + 2).stripLeading())
      else if content.endsWith(":") then
        (content.dropRight(1).trim, "")
      else
        throw ParseError(s"invalid mapping entry: $content", lineIdx)

  // ── Scalar coercion ───────────────────────────────────────────────────────

  private[yaml] def parseScalar(s: String): Any =
    s match
      case "null" | "~" | "" => null
      case "true"            => java.lang.Boolean.TRUE
      case "false"           => java.lang.Boolean.FALSE
      case _ if s.startsWith("\"") => unquoteDouble(s, 1, 0)._1
      case _ if s.startsWith("'")  => unquoteSingle(s, 1, 0)._1
      case _ if s.startsWith("{")  => parseFlowMap(s, 0)._1
      case _ if s.startsWith("[")  => parseFlowSeq(s, 0)._1
      case _ =>
        s.toIntOption.map(java.lang.Integer.valueOf).getOrElse(
          s.toLongOption.map(java.lang.Long.valueOf).getOrElse(
            s.toDoubleOption.map(java.lang.Double.valueOf).getOrElse(s)
          )
        )

  // ── Flow collections ─────────────────────────────────────────────────────

  private def parseFlowMap(s: String, start: Int): (JMap[String, Any], Int) =
    val m = new JMap[String, Any]()
    var i = skipWS(s, start + 1)  // skip '{'
    while i < s.length && s(i) != '}' do
      val (k, i2) = parseFlowKey(s, i)
      val i3 = skipWS(s, i2)
      if i3 >= s.length || s(i3) != ':' then throw ParseError(s"expected ':' in flow map near pos $i3")
      val i4 = skipWS(s, i3 + 1)
      val (v, i5) = parseFlowValue(s, i4)
      m.put(k, v)
      val i6 = skipWS(s, i5)
      if i6 < s.length && s(i6) == ',' then i = skipWS(s, i6 + 1) else i = i6
    (m, if i < s.length then i + 1 else i)

  private def parseFlowSeq(s: String, start: Int): (JList[Any], Int) =
    val l = new JList[Any]()
    var i = skipWS(s, start + 1)  // skip '['
    while i < s.length && s(i) != ']' do
      val (v, i2) = parseFlowValue(s, i)
      l.add(v)
      val i3 = skipWS(s, i2)
      if i3 < s.length && s(i3) == ',' then i = skipWS(s, i3 + 1) else i = i3
    (l, if i < s.length then i + 1 else i)

  private def parseFlowKey(s: String, i: Int): (String, Int) =
    if i >= s.length then throw ParseError("unexpected end in flow map key")
    else if s(i) == '"' then
      val (k, end) = unquoteDouble(s, i + 1, 0)
      (k, end)
    else if s(i) == '\'' then
      val (k, end) = unquoteSingle(s, i + 1, 0)
      (k, end)
    else
      var j = i
      while j < s.length && s(j) != ':' && s(j) != ',' && s(j) != '}' do j += 1
      (s.substring(i, j).trim, j)

  private def parseFlowValue(s: String, i: Int): (Any, Int) =
    if i >= s.length then (null, i)
    else s(i) match
      case '"'  => val (v, end) = unquoteDouble(s, i + 1, 0); (v, end)
      case '\'' => val (v, end) = unquoteSingle(s, i + 1, 0); (v, end)
      case '{'  => parseFlowMap(s, i)
      case '['  => parseFlowSeq(s, i)
      case _    =>
        var j = i
        while j < s.length && s(j) != ',' && s(j) != '}' && s(j) != ']' do j += 1
        (parseScalar(s.substring(i, j).trim), j)

  private def skipWS(s: String, i: Int): Int =
    var j = i
    while j < s.length && (s(j) == ' ' || s(j) == '\t') do j += 1
    j

  // ── String helpers ────────────────────────────────────────────────────────

  private def unquoteDouble(s: String, pos: Int, lineIdx: Int): (String, Int) =
    val sb = new StringBuilder
    var i = pos
    while i < s.length && s(i) != '"' do
      if s(i) == '\\' then
        i += 1
        if i < s.length then
          s(i) match
            case '"'  => sb += '"'
            case '\\' => sb += '\\'
            case 'n'  => sb += '\n'
            case 't'  => sb += '\t'
            case 'r'  => sb += '\r'
            case c    => sb += '\\'; sb += c
      else
        sb += s(i)
      i += 1
    if i >= s.length then throw ParseError("unclosed double-quoted string", lineIdx)
    (sb.toString, i + 1)

  private def unquoteSingle(s: String, pos: Int, lineIdx: Int): (String, Int) =
    val sb = new StringBuilder
    var i = pos
    var done = false
    while !done && i < s.length do
      if s(i) == '\'' then
        if i + 1 < s.length && s(i + 1) == '\'' then { sb += '\''; i += 2 }
        else { done = true; i += 1 }
      else { sb += s(i); i += 1 }
    if !done then throw ParseError("unclosed single-quoted string", lineIdx)
    (sb.toString, i)

  private def stripInlineComment(s: String): String =
    var i = 0; var inDQ = false; var inSQ = false
    while i < s.length do
      s(i) match
        case '"' if !inSQ  => inDQ = !inDQ
        case '\'' if !inDQ => inSQ = !inSQ
        case '#' if !inDQ && !inSQ && (i == 0 || s(i - 1) == ' ' || s(i - 1) == '\t') =>
          return s.substring(0, i).stripTrailing()
        case _ =>
      i += 1
    s

  private def isBlankOrComment(line: String): Boolean =
    val s = line.stripLeading()
    s.isEmpty || s.startsWith("#")

  private def looksLikeMapEntry(s: String): Boolean =
    s.contains(": ") || (s.endsWith(":") && !s.startsWith("-"))
