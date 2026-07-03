package scalascript.parser

import scala.collection.mutable

// ─── Event model ─────────────────────────────────────────────────────────────

enum YScalarStyle:
  case Plain, SingleQuoted, DoubleQuoted, Literal, Folded

sealed trait YamlEvent
case object YStreamStart extends YamlEvent
case object YStreamEnd   extends YamlEvent
case class  YDocumentStart(explicit: Boolean = false) extends YamlEvent
case class  YDocumentEnd(explicit: Boolean = false)   extends YamlEvent
/** flow=true → `{ … }`, flow=false → block mapping. */
case class  YMappingStart(flow: Boolean = false, anchor: Option[String] = None, tag: Option[String] = None) extends YamlEvent
case object YMappingEnd extends YamlEvent
/** flow=true → `[ … ]`, flow=false → block sequence. */
case class  YSequenceStart(flow: Boolean = false, anchor: Option[String] = None, tag: Option[String] = None) extends YamlEvent
case object YSequenceEnd extends YamlEvent
case class  YScalar(value: String, style: YScalarStyle = YScalarStyle.Plain, anchor: Option[String] = None, tag: Option[String] = None) extends YamlEvent
case class  YAlias(anchor: String) extends YamlEvent
case class  YDirective(name: String, value: String) extends YamlEvent

// ─── Public API ───────────────────────────────────────────────────────────────

object YamlEvents:

  /** Parse a YAML document to a flat event sequence.
   *
   *  Covers the surface used in `.ssc` frontmatter:
   *  block/flow mappings + sequences, plain/single/double/literal/folded
   *  scalars, anchors (`&name`), aliases (`*name`), tags (`!tag`),
   *  YAML directives (`%YAML`, `%TAG`), multi-document separators (`---`/`...`).
   *  Always wraps output in `YStreamStart`/`YStreamEnd` and
   *  `YDocumentStart`/`YDocumentEnd`. */
  def parse(src: String): IndexedSeq[YamlEvent] = new YamlEventParser(src).run()

  /** Emit events back to YAML text.  Produces output parseable by `SimpleYaml.load`. */
  def emit(events: Seq[YamlEvent]): String = new YamlEventEmitter(events.toIndexedSeq).run()

  /** Build events from a `SimpleYaml.load` result (`Map[String,Any]`, `List[Any]`, or scalar).
   *  Used in the v3 write path when the raw YAML text is not preserved (takes `Manifest.raw`). */
  def fromAny(value: Any): IndexedSeq[YamlEvent] =
    val buf = mutable.ArrayBuffer.empty[YamlEvent]
    buf += YStreamStart
    buf += YDocumentStart()
    appendAny(value, buf)
    buf += YDocumentEnd()
    buf += YStreamEnd
    buf.toIndexedSeq

  private def appendAny(v: Any, buf: mutable.ArrayBuffer[YamlEvent]): Unit = v match
    case null                       => buf += YScalar("null")
    case b: java.lang.Boolean       => buf += YScalar(b.toString)
    case i: java.lang.Integer       => buf += YScalar(i.toString)
    case d: java.lang.Double        =>
      val s = if d == d.toLong.toDouble then d.toLong.toString else d.toString
      buf += YScalar(s)
    case s: String =>
      buf += YScalar(s, if s.contains('\n') then YScalarStyle.Literal
                        else if needsQuoting(s) then YScalarStyle.DoubleQuoted
                        else YScalarStyle.Plain)
    case m: java.util.LinkedHashMap[?, ?] =>
      buf += YMappingStart()
      m.entrySet().forEach { e => buf += YScalar(e.getKey.toString); appendAny(e.getValue, buf) }
      buf += YMappingEnd
    case m: java.util.Map[?, ?] =>
      buf += YMappingStart()
      m.entrySet().forEach { e => buf += YScalar(e.getKey.toString); appendAny(e.getValue, buf) }
      buf += YMappingEnd
    case m: scala.collection.Map[?, ?] =>
      buf += YMappingStart()
      m.foreach((k, v2) => { buf += YScalar(k.toString); appendAny(v2, buf) })
      buf += YMappingEnd
    case l: java.util.List[?] =>
      buf += YSequenceStart()
      l.forEach(appendAny(_, buf))
      buf += YSequenceEnd
    case l: scala.collection.Seq[?] =>
      buf += YSequenceStart()
      l.foreach(appendAny(_, buf))
      buf += YSequenceEnd
    case _ => buf += YScalar(v.toString)

  private[parser] def needsQuoting(s: String): Boolean =
    s.isEmpty ||
    s.startsWith("#")  || s.startsWith("&")  || s.startsWith("*")  ||
    s.startsWith("!")  || s.startsWith("|")  || s.startsWith(">")  ||
    s.startsWith("'")  || s.startsWith("\"") || s.startsWith("{")  ||
    s.startsWith("[")  || s.startsWith("- ") || s.contains(": ")   ||
    s.endsWith(":") ||
    Set("null","~","true","false","yes","no","on","off").contains(s.toLowerCase) ||
    s.toIntOption.isDefined || s.toDoubleOption.isDefined

// ─── Parser ───────────────────────────────────────────────────────────────────

private[parser] class YamlEventParser(src: String):

  private val raw   = src.split("\n", -1).toIndexedSeq
  private val lines = raw.map(stripComment)
  private var i     = 0
  private val buf   = mutable.ArrayBuffer.empty[YamlEvent]

  def run(): IndexedSeq[YamlEvent] =
    buf += YStreamStart
    // consume %YAML / %TAG directives before the document
    while !done && lines(i).trim.startsWith("%") do
      val t     = lines(i).trim; i += 1
      val parts = t.drop(1).split("\\s+", 2)
      buf += YDirective(parts(0), if parts.length > 1 then parts(1) else "")
    // optional explicit document-start marker
    val explicit = !done && lines(i).trim == "---"
    if explicit then i += 1
    buf += YDocumentStart(explicit)
    skipBlank()
    if !done && lines(i).trim != "..." then parseValue(0)
    val explicitEnd = !done && lines(i).trim == "..."
    if explicitEnd then i += 1
    buf += YDocumentEnd(explicitEnd)
    buf += YStreamEnd
    buf.toIndexedSeq

  // ── value dispatch ──────────────────────────────────────────────────────────

  private def parseValue(minIndent: Int): Unit =
    skipBlank()
    if done then return
    val ind = indentOf(lines(i))
    if ind < minIndent then return
    val t = lines(i).stripLeading()
    if t.startsWith("- ") || t == "-" then parseBlockSeq(ind)
    else if isMapLine(t)              then parseBlockMap(ind)
    else                                   { i += 1; emitScalar(t) }

  // ── block mapping ───────────────────────────────────────────────────────────

  private def parseBlockMap(mapIndent: Int): Unit =
    buf += YMappingStart()
    while !done && (lines(i).isBlank || (indentOf(lines(i)) == mapIndent && isMapLine(lines(i).stripLeading()))) do
      skipBlank()
      if done || indentOf(lines(i)) != mapIndent then
        buf += YMappingEnd; return
      val t = lines(i).stripLeading(); i += 1
      val ci = findKeyColon(t)
      val rawKey = t.take(ci).trim
      val (anchor, keyText) = parseAnchorPrefix(rawKey)
      buf += YScalar(unquote(keyText), styleOf(keyText), anchor)
      parseValueAfter(t.drop(ci + 1).trim, mapIndent)
    buf += YMappingEnd

  // ── block sequence ──────────────────────────────────────────────────────────

  private def parseBlockSeq(seqIndent: Int): Unit =
    buf += YSequenceStart()
    while !done && (lines(i).isBlank || (indentOf(lines(i)) == seqIndent &&
                    { val t = lines(i).stripLeading(); t.startsWith("- ") || t == "-" })) do
      skipBlank()
      if done || indentOf(lines(i)) != seqIndent then
        buf += YSequenceEnd; return
      val t = lines(i).stripLeading(); i += 1
      val content = t.drop(if t.startsWith("- ") then 2 else 1).trim
      parseValueAfter(content, seqIndent)
    buf += YSequenceEnd

  // ── after key/dash: inline value or next-line nested ───────────────────────

  private def parseValueAfter(after: String, parentIndent: Int): Unit =
    if after.isEmpty then
      skipBlank()
      if done || indentOf(lines(i)) <= parentIndent then
        buf += YScalar("null")
        return
      val ci = indentOf(lines(i))
      val t  = lines(i).stripLeading()
      if t.startsWith("- ") || t == "-" then parseBlockSeq(ci)
      else if isMapLine(t)              then parseBlockMap(ci)
      else                                   { i += 1; emitScalar(t) }
    else if after == "|" then parseLiteralBlock(parentIndent, folded = false)
    else if after == ">" then parseLiteralBlock(parentIndent, folded = true)
    else if after.startsWith("[") then parseFlowSeq(after.drop(1))
    else if after.startsWith("{") then parseFlowMap(after.drop(1))
    else if after.startsWith("*") then buf += YAlias(after.drop(1).trim)
    else if isMapLine(after) then
      // inline map in sequence item: "- key: val"
      buf += YMappingStart()
      val ci = findKeyColon(after)
      buf += YScalar(unquote(after.take(ci).trim))
      parseValueAfter(after.drop(ci + 1).trim, parentIndent)
      // consume further sibling entries at deeper indent
      skipBlank()
      if !done then
        val childInd = indentOf(lines(i))
        if childInd > parentIndent then
          while !done && indentOf(lines(i)) == childInd && isMapLine(lines(i).stripLeading()) do
            val t2 = lines(i).stripLeading(); i += 1
            val ci2 = findKeyColon(t2)
            buf += YScalar(unquote(t2.take(ci2).trim))
            parseValueAfter(t2.drop(ci2 + 1).trim, childInd)
      buf += YMappingEnd
    else emitScalar(after)

  // ── literal / folded block scalars ──────────────────────────────────────────

  private def parseLiteralBlock(parentIndent: Int, folded: Boolean): Unit =
    skipBlank()
    if done || indentOf(lines(i)) <= parentIndent then
      buf += YScalar("", if folded then YScalarStyle.Folded else YScalarStyle.Literal)
      return
    val blockIndent = indentOf(lines(i))
    val sb = new StringBuilder
    while !done && (lines(i).isBlank || indentOf(lines(i)) >= blockIndent) do
      if lines(i).isBlank then sb += '\n'
      else                     sb.append(lines(i).substring(blockIndent)).append('\n')
      i += 1
    buf += YScalar(sb.toString.stripTrailing(), if folded then YScalarStyle.Folded else YScalarStyle.Literal)

  // ── flow sequences / mappings ───────────────────────────────────────────────

  private def parseFlowSeq(afterBracket: String): Unit =
    val content = collectFlow(afterBracket, '[', ']')
    buf += YSequenceStart(flow = true)
    splitFlow(content).foreach { part =>
      val t = part.trim
      if t.nonEmpty then
        if t.startsWith("{") then parseFlowMap(t.drop(1))
        else if t.startsWith("[") then parseFlowSeq(t.drop(1))
        else if t.startsWith("*") then buf += YAlias(t.drop(1).trim)
        else emitScalar(t)
    }
    buf += YSequenceEnd

  private def parseFlowMap(afterBrace: String): Unit =
    val content = collectFlow(afterBrace, '{', '}')
    buf += YMappingStart(flow = true)
    splitFlow(content).foreach { entry =>
      val t  = entry.trim
      val ci = findKeyColon(t)
      if ci >= 0 then
        buf += YScalar(unquote(t.take(ci).trim))
        val v = t.drop(ci + 1).trim
        if v.startsWith("*") then buf += YAlias(v.drop(1).trim)
        else emitScalar(v)
    }
    buf += YMappingEnd

  // ── scalar emission ─────────────────────────────────────────────────────────

  private def emitScalar(raw: String): Unit =
    val t = raw.trim
    if t.startsWith("*") then { buf += YAlias(t.drop(1).trim); return }
    val (anchor, rest1) = parseAnchorPrefix(t)
    val (tag, rest2) =
      if rest1.startsWith("!") then
        val sp = rest1.indexOf(' ')
        if sp < 0 then (Some(rest1), "") else (Some(rest1.take(sp)), rest1.drop(sp + 1).trim)
      else (None, rest1)
    buf += YScalar(unquote(rest2), styleOf(rest2), anchor, tag)

  // ── helpers ─────────────────────────────────────────────────────────────────

  private def done: Boolean = i >= lines.length
  private def skipBlank(): Unit = while !done && lines(i).isBlank do i += 1
  private def indentOf(line: String): Int = line.takeWhile(_ == ' ').length
  private def isMapLine(t: String): Boolean = findKeyColon(t) >= 0

  private def parseAnchorPrefix(s: String): (Option[String], String) =
    if s.startsWith("&") then
      val sp = s.indexOf(' ')
      if sp < 0 then (Some(s.drop(1)), "") else (Some(s.substring(1, sp)), s.drop(sp + 1).trim)
    else (None, s)

  private def styleOf(s: String): YScalarStyle =
    if s.length >= 2 && s.head == '"' && s.last == '"' then YScalarStyle.DoubleQuoted
    else if s.length >= 2 && s.head == '\'' && s.last == '\'' then YScalarStyle.SingleQuoted
    else YScalarStyle.Plain

  private def findKeyColon(s: String): Int =
    var inS = false; var inD = false; var j = 0
    while j < s.length do
      s(j) match
        case '\'' if !inD => inS = !inS
        case '"'  if !inS => inD = !inD
        case '\\' if inD && j + 1 < s.length => j += 1
        case ':' if !inS && !inD =>
          if j + 1 >= s.length || s(j + 1) == ' ' || s(j + 1) == '\t' then return j
        case _ => ()
      j += 1
    -1

  private def unquote(s: String): String =
    val t = s.trim
    if t.length >= 2 && t.head == '"' && t.last == '"' then
      t.substring(1, t.length - 1)
        .replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r").replace("\\\\", "\\")
    else if t.length >= 2 && t.head == '\'' && t.last == '\'' then
      t.substring(1, t.length - 1).replace("''", "'")
    else t

  private def collectFlow(start: String, open: Char, close: Char): String =
    val sb = new StringBuilder; var depth = 0
    def feed(s: String): Boolean =
      var found = false; var j = 0
      while j < s.length && !found do
        s(j) match
          case c if c == open  => depth += 1; sb += c
          case c if c == close => if depth == 0 then found = true else { depth -= 1; sb += c }
          case '\'' =>
            sb += '\''; j += 1
            while j < s.length && s(j) != '\'' do { sb += s(j); j += 1 }
            if j < s.length then sb += '\''
          case '"' =>
            sb += '"'; j += 1
            while j < s.length && s(j) != '"' do
              if s(j) == '\\' && j + 1 < s.length then { sb += '\\'; sb += s(j + 1); j += 1 }
              else sb += s(j)
              j += 1
            if j < s.length then sb += '"'
          case c => sb += c
        j += 1
      found
    var closed = feed(start)
    while !closed && !done do { val line = lines(i); i += 1; closed = feed(line) }
    sb.toString

  private def splitFlow(content: String): List[String] =
    val parts = mutable.ListBuffer.empty[String]; val sb = new StringBuilder
    var depth = 0; var inS = false; var inD = false; var j = 0
    while j < content.length do
      content(j) match
        case '\'' if !inD => inS = !inS; sb += '\''
        case '"'  if !inS => inD = !inD; sb += '"'
        case '[' | '{' if !inS && !inD => depth += 1; sb += content(j)
        case ']' | '}' if !inS && !inD => depth -= 1; sb += content(j)
        case ',' if !inS && !inD && depth == 0 => parts += sb.toString; sb.clear()
        case c => sb += c
      j += 1
    val last = sb.toString.trim
    if last.nonEmpty then parts += last
    parts.toList

  private def stripComment(line: String): String =
    var inS = false; var inD = false; var j = 0; val sb = new StringBuilder
    while j < line.length do
      line(j) match
        case '\'' if !inD => inS = !inS; sb += '\''; j += 1
        case '"'  if !inS => inD = !inD; sb += '"';  j += 1
        case '\\' if inD && j + 1 < line.length => sb += '\\'; sb += line(j + 1); j += 2
        case '#'  if !inS && !inD => j = line.length
        case c => sb += c; j += 1
    sb.toString.stripTrailing()

// ─── Emitter ─────────────────────────────────────────────────────────────────

private[parser] class YamlEventEmitter(evs: IndexedSeq[YamlEvent]):

  private val sb  = new StringBuilder
  private var pos = 0

  def run(): String =
    while pos < evs.length do
      evs(pos) match
        case YStreamStart | YStreamEnd => pos += 1
        case YDirective(n, v)     => sb.append(s"%$n $v\n"); pos += 1
        case YDocumentStart(exp)  => if exp then sb.append("---\n"); pos += 1; emitNode(0)
        case YDocumentEnd(exp)    => if exp then sb.append("...\n"); pos += 1
        case _                    => pos += 1
    sb.toString.stripTrailing

  private def emitNode(indent: Int): Unit =
    if pos >= evs.length then return
    evs(pos) match
      case YMappingStart(flow, anchor, tag) =>
        pos += 1
        val pfx = anchor.map("&" + _ + " ").getOrElse("") + tag.map(_ + " ").getOrElse("")
        if flow then { if pfx.nonEmpty then sb.append(pfx); sb.append("{"); emitFlowMapBody(); sb.append("}") }
        else         { if pfx.nonEmpty then sb.append("\n").append(" " * indent).append(pfx); emitBlockMapBody(indent) }
      case YSequenceStart(flow, anchor, tag) =>
        pos += 1
        val pfx = anchor.map("&" + _ + " ").getOrElse("") + tag.map(_ + " ").getOrElse("")
        if flow then { if pfx.nonEmpty then sb.append(pfx); sb.append("["); emitFlowSeqBody(); sb.append("]") }
        else         { if pfx.nonEmpty then sb.append("\n").append(" " * indent).append(pfx); emitBlockSeqBody(indent) }
      case YScalar(v, style, anchor, tag) =>
        pos += 1
        emitScalar(v, style, anchor, tag, indent)
      case YAlias(name) =>
        pos += 1; sb.append("*").append(name)
      case _ => pos += 1

  private def emitBlockMapBody(indent: Int): Unit =
    while pos < evs.length && evs(pos) != YMappingEnd do
      sb.append(" " * indent)
      emitInlineScalar()   // key — always plain inline
      sb.append(": ")
      val nextIsBlock = pos < evs.length && (
        evs(pos).isInstanceOf[YMappingStart]  && !evs(pos).asInstanceOf[YMappingStart].flow ||
        evs(pos).isInstanceOf[YSequenceStart] && !evs(pos).asInstanceOf[YSequenceStart].flow )
      if nextIsBlock then { sb.append("\n"); emitNode(indent + 2) }
      else                { emitNode(indent); sb.append("\n") }
    if pos < evs.length then pos += 1

  private def emitBlockSeqBody(indent: Int): Unit =
    while pos < evs.length && evs(pos) != YSequenceEnd do
      sb.append(" " * indent).append("- ")
      val nextIsBlock = pos < evs.length && (
        evs(pos).isInstanceOf[YMappingStart]  && !evs(pos).asInstanceOf[YMappingStart].flow ||
        evs(pos).isInstanceOf[YSequenceStart] && !evs(pos).asInstanceOf[YSequenceStart].flow )
      if nextIsBlock then { sb.append("\n"); emitNode(indent + 2) }
      else                { emitNode(indent); sb.append("\n") }
    if pos < evs.length then pos += 1

  private def emitFlowMapBody(): Unit =
    var first = true
    while pos < evs.length && evs(pos) != YMappingEnd do
      if !first then sb.append(", "); first = false
      emitInlineScalar(); sb.append(": "); emitFlowNode()
    if pos < evs.length then pos += 1

  private def emitFlowSeqBody(): Unit =
    var first = true
    while pos < evs.length && evs(pos) != YSequenceEnd do
      if !first then sb.append(", "); first = false; emitFlowNode()
    if pos < evs.length then pos += 1

  private def emitFlowNode(): Unit =
    if pos >= evs.length then return
    evs(pos) match
      case YMappingStart(_, anchor, tag) =>
        pos += 1
        val pfx = anchor.map("&" + _ + " ").getOrElse("") + tag.map(_ + " ").getOrElse("")
        if pfx.nonEmpty then sb.append(pfx)
        sb.append("{"); emitFlowMapBody(); sb.append("}")
      case YSequenceStart(_, anchor, tag) =>
        pos += 1
        val pfx = anchor.map("&" + _ + " ").getOrElse("") + tag.map(_ + " ").getOrElse("")
        if pfx.nonEmpty then sb.append(pfx)
        sb.append("["); emitFlowSeqBody(); sb.append("]")
      case YScalar(v, style, anchor, tag) =>
        pos += 1; emitScalar(v, style, anchor, tag, 0)
      case YAlias(name) =>
        pos += 1; sb.append("*").append(name)
      case _ => pos += 1

  private def emitInlineScalar(): Unit =
    if pos >= evs.length then return
    evs(pos) match
      case YScalar(v, style, anchor, tag) =>
        pos += 1
        val pfx = anchor.map("&" + _ + " ").getOrElse("") + tag.map(_ + " ").getOrElse("")
        if pfx.nonEmpty then sb.append(pfx)
        // keys are always emitted as plain or quoted (never literal/folded)
        val normalised = style match
          case YScalarStyle.Literal | YScalarStyle.Folded => YScalarStyle.DoubleQuoted
          case other => other
        emitScalarText(v, normalised)
      case _ => emitFlowNode()

  private def emitScalar(v: String, style: YScalarStyle, anchor: Option[String], tag: Option[String], indent: Int): Unit =
    val pfx = anchor.map("&" + _ + " ").getOrElse("") + tag.map(_ + " ").getOrElse("")
    if pfx.nonEmpty then sb.append(pfx)
    style match
      case YScalarStyle.Literal =>
        sb.append("|\n")
        v.linesWithSeparators.foreach(line => sb.append(" " * (indent + 2)).append(line))
        if !v.endsWith("\n") then sb.append("\n")
      case YScalarStyle.Folded =>
        sb.append(">\n")
        v.linesWithSeparators.foreach(line => sb.append(" " * (indent + 2)).append(line))
        if !v.endsWith("\n") then sb.append("\n")
      case other => emitScalarText(v, other)

  private def emitScalarText(v: String, style: YScalarStyle): Unit = style match
    case YScalarStyle.Plain =>
      // Trust Plain style: emit as-is for YAML keywords (null, true, false, numbers).
      // Only quote when structural chars would confuse the parser.
      if structurallyProblematic(v) then sb.append('"').append(escapeDouble(v)).append('"')
      else sb.append(v)
    case YScalarStyle.SingleQuoted =>
      sb.append('\'').append(v.replace("'", "''")).append('\'')
    case YScalarStyle.DoubleQuoted =>
      sb.append('"').append(escapeDouble(v)).append('"')
    case _ => sb.append(v)   // Literal/Folded already handled in emitScalar

  // Only chars that are structurally ambiguous in a plain YAML scalar context.
  // Does NOT include YAML keywords (null, true, false, numbers) — those are
  // intentionally emitted unquoted so SimpleYaml can interpret them as typed values.
  private def structurallyProblematic(s: String): Boolean =
    s.isEmpty ||
    s.startsWith("#") || s.startsWith("&") || s.startsWith("*") ||
    s.startsWith("!") || s.startsWith("|") || s.startsWith(">") ||
    s.startsWith("'") || s.startsWith("\"") ||
    s.startsWith("{") || s.startsWith("[") ||
    s.startsWith("- ") || s.contains(": ") || s.endsWith(":") ||
    s.contains(" #")

  private def escapeDouble(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")
     .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
