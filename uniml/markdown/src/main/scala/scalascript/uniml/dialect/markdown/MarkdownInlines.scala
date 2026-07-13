package scalascript.uniml.dialect.markdown

import scalascript.uniml.TokenChannel
import scala.collection.mutable.ArrayBuffer

/** Inline structure over a single contiguous content region. Emits a flat list
  * of `InlinePiece`s that the block emitter replays through the shared token
  * cursor, so every character of `content` is preserved exactly once. Emphasis,
  * links, code spans, autolinks, raw HTML, breaks and `${expr}` are resolved
  * with bounded left-to-right scanning plus the CommonMark delimiter algorithm. */
private[markdown] object MarkdownInlines:

  /** A resolved link reference definition (destination + optional title). */
  final case class LinkRef(destination: String, title: Option[String])

  /** One inline emission: a leaf token, or a branch open/close carrying its
    * exact delimiter lexeme. */
  enum InlinePiece:
    case Tok(kind: String, lexeme: String, role: Option[String], channel: TokenChannel)
    case Open(branch: String, kind: String, lexeme: String, role: Option[String])
    case Close(branch: String, kind: String, lexeme: String, role: Option[String])

  import InlinePiece.*

  /** CommonMark reference-label normalization: trim, collapse internal
    * whitespace to a single space, case-fold (approximated by lower-casing). */
  def normalizeLabel(raw: String): String =
    val trimmed = raw.trim
    val builder = StringBuilder()
    var inSpace = false
    trimmed.foreach { c =>
      if MdChars.isUnicodeWhitespace(c) then inSpace = true
      else
        if inSpace && builder.nonEmpty then builder.append(' ')
        inSpace = false
        builder.append(c)
    }
    builder.result().toLowerCase

  def parse(content: String, refs: Map[String, LinkRef], profile: MarkdownProfile): Vector[InlinePiece] =
    val atoms = tokenize(content, refs, profile)
    val processed = processEmphasis(atoms)
    processed.iterator.flatMap(flatten).toVector

  // ── working node model ────────────────────────────────────────────────

  private sealed trait WNode
  private final case class WFixed(pieces: Vector[InlinePiece]) extends WNode
  private final class WDelim(
      var lexeme: String,
      val ch: Char,
      val canOpen: Boolean,
      val canClose: Boolean,
  ) extends WNode

  private def flatten(node: WNode): Vector[InlinePiece] = node match
    case WFixed(pieces) => pieces
    case delim: WDelim  => if delim.lexeme.isEmpty then Vector.empty else Vector(Tok(MdKind.DelimiterRun, delim.lexeme, Some("literal"), TokenChannel.Syntax))

  private def text(lexeme: String): WNode = WFixed(Vector(Tok(MdKind.Text, lexeme, None, TokenChannel.Syntax)))

  // ── tokenization ──────────────────────────────────────────────────────

  private def tokenize(content: String, refs: Map[String, LinkRef], profile: MarkdownProfile): ArrayBuffer[WNode] =
    val nodes = ArrayBuffer.empty[WNode]
    val pending = StringBuilder()
    var i = 0
    val n = content.length
    val gfm = profile == MarkdownProfile.Gfm
    val scala = profile == MarkdownProfile.ScalaScript

    def flushText(): Unit =
      if pending.nonEmpty then
        nodes += text(pending.result())
        pending.clear()

    while i < n do
      val c = content.charAt(i)
      c match
        case '\n' | '\r' =>
          // line ending within a content unit: hard break if preceded by 2+ spaces or a backslash
          val ending =
            if c == '\r' && i + 1 < n && content.charAt(i + 1) == '\n' then "\r\n" else c.toString
          val pend = pending.result()
          val hard = pend.endsWith("  ") || pend.endsWith("\\")
          if hard && pend.endsWith("\\") then
            // strip the trailing backslash into a hard-break marker
            pending.setLength(pending.length - 1)
            flushText()
            nodes += WFixed(Vector(Tok(MdKind.HardBreak, "\\" + ending, None, TokenChannel.Syntax)))
          else
            flushText()
            nodes += WFixed(Vector(Tok(if hard then MdKind.HardBreak else MdKind.SoftBreak, ending, None, if hard then TokenChannel.Syntax else TokenChannel.Trivia)))
          i += ending.length

        case '\\' =>
          if i + 1 < n && MdChars.isAsciiPunctuation(content.charAt(i + 1)) then
            flushText()
            nodes += WFixed(Vector(Tok(MdKind.Escape, content.substring(i, i + 2), None, TokenChannel.Syntax)))
            i += 2
          else
            pending.append('\\')
            i += 1

        case '`' =>
          val runLen = runLength(content, i, '`')
          val closeAt = findBacktickClose(content, i + runLen, runLen)
          closeAt match
            case Some(start) =>
              flushText()
              val openLex = content.substring(i, i + runLen)
              val inner = content.substring(i + runLen, start)
              val closeLex = content.substring(start, start + runLen)
              val pieces = Vector.newBuilder[InlinePiece]
              pieces += Open(MdBranch.CodeSpan, MdKind.BacktickRun, openLex, Some("delimiter.open"))
              if inner.nonEmpty then pieces += Tok(MdKind.CodeContent, inner, Some("code"), TokenChannel.Embedded)
              pieces += Close(MdBranch.CodeSpan, MdKind.BacktickRun, closeLex, Some("delimiter.close"))
              nodes += WFixed(pieces.result())
              i = start + runLen
            case None =>
              pending.append(content.substring(i, i + runLen))
              i += runLen

        case '<' =>
          scanAngle(content, i) match
            case Some((kind, endEx)) =>
              flushText()
              val lex = content.substring(i, endEx)
              val (mdKind, role, channel) = kind match
                case AngleKind.Autolink => (MdKind.Autolink, Some("autolink"), TokenChannel.Syntax)
                case AngleKind.Html     => (MdKind.Html, Some("html"), TokenChannel.Embedded)
              nodes += WFixed(Vector(Tok(mdKind, lex, role, channel)))
              i = endEx
            case None =>
              pending.append('<')
              i += 1

        case '&' =>
          scanEntity(content, i) match
            case Some(endEx) =>
              flushText()
              nodes += WFixed(Vector(Tok(MdKind.Entity, content.substring(i, endEx), Some("entity"), TokenChannel.Syntax)))
              i = endEx
            case None =>
              pending.append('&')
              i += 1

        case '$' if scala && i + 1 < n && content.charAt(i + 1) == '{' =>
          scanExpression(content, i) match
            case Some(endEx) =>
              flushText()
              val open = content.substring(i, i + 2)
              val inner = content.substring(i + 2, endEx - 1)
              val close = content.substring(endEx - 1, endEx)
              val pieces = Vector.newBuilder[InlinePiece]
              pieces += Open(MdBranch.Expression, MdKind.ExpressionOpen, open, Some("delimiter.open"))
              if inner.nonEmpty then pieces += Tok(MdKind.ExpressionContent, inner, Some("expression"), TokenChannel.Embedded)
              pieces += Close(MdBranch.Expression, MdKind.ExpressionClose, close, Some("delimiter.close"))
              nodes += WFixed(pieces.result())
              i = endEx
            case None =>
              pending.append('$')
              i += 1

        case '!' if i + 1 < n && content.charAt(i + 1) == '[' =>
          tryLink(content, i, image = true, refs, profile) match
            case Some((node, endEx)) =>
              flushText()
              nodes += node
              i = endEx
            case None =>
              pending.append('!')
              i += 1

        case '[' =>
          tryLink(content, i, image = false, refs, profile) match
            case Some((node, endEx)) =>
              flushText()
              nodes += node
              i = endEx
            case None =>
              pending.append('[')
              i += 1

        case '*' | '_' =>
          flushText()
          nodes += delimiterRun(content, i, c)
          i += runLength(content, i, c)

        case '~' if gfm =>
          flushText()
          nodes += delimiterRun(content, i, c)
          i += runLength(content, i, c)

        case other =>
          pending.append(other)
          i += 1
    flushText()
    nodes

  private def delimiterRun(content: String, start: Int, ch: Char): WDelim =
    val len = runLength(content, start, ch)
    val before = if start == 0 then ' ' else content.charAt(start - 1)
    val after = if start + len >= content.length then ' ' else content.charAt(start + len)
    val leftFlanking =
      !MdChars.isUnicodeWhitespace(after) &&
        (!MdChars.isPunctuation(after) || MdChars.isUnicodeWhitespace(before) || MdChars.isPunctuation(before))
    val rightFlanking =
      !MdChars.isUnicodeWhitespace(before) &&
        (!MdChars.isPunctuation(before) || MdChars.isUnicodeWhitespace(after) || MdChars.isPunctuation(after))
    val (canOpen, canClose) =
      if ch == '_' then
        (leftFlanking && (!rightFlanking || MdChars.isPunctuation(before)),
         rightFlanking && (!leftFlanking || MdChars.isPunctuation(after)))
      else (leftFlanking, rightFlanking)
    WDelim(content.substring(start, start + len), ch, canOpen, canClose)

  private def runLength(content: String, start: Int, ch: Char): Int =
    var i = start
    while i < content.length && content.charAt(i) == ch do i += 1
    i - start

  private def findBacktickClose(content: String, from: Int, runLen: Int): Option[Int] =
    var i = from
    while i < content.length do
      if content.charAt(i) == '`' then
        val len = runLength(content, i, '`')
        if len == runLen then return Some(i)
        i += len
      else i += 1
    None

  // ── links / images ──────────────────────────────────────────────────────

  private def tryLink(
      content: String,
      start: Int,
      image: Boolean,
      refs: Map[String, LinkRef],
      profile: MarkdownProfile,
  ): Option[(WNode, Int)] =
    val openLen = if image then 2 else 1
    val textStart = start + openLen
    val closeBracket = matchBracket(content, textStart)
    closeBracket match
      case None => None
      case Some(labelEnd) =>
        val labelText = content.substring(textStart, labelEnd)
        val cursor = labelEnd + 1 // just past ']'
        // inline destination: [text](dest "title")
        if cursor < content.length && content.charAt(cursor) == '(' then
          parseInlineDestination(content, cursor) match
            case Some((_, _, destTitleSpans, endEx)) =>
              Some(buildLink(content, start, labelStart = textStart, labelEnd = labelEnd,
                labelText, image, destTitleSpans, endEx, refs, profile) -> endEx)
            case None => tryReference(content, start, textStart, labelEnd, labelText, image, refs, profile)
        else tryReference(content, start, textStart, labelEnd, labelText, image, refs, profile)

  private def tryReference(
      content: String,
      start: Int,
      labelStart: Int,
      labelEnd: Int,
      labelText: String,
      image: Boolean,
      refs: Map[String, LinkRef],
      profile: MarkdownProfile,
  ): Option[(WNode, Int)] =
    val cursor = labelEnd + 1
    // full reference [text][label]
    if cursor < content.length && content.charAt(cursor) == '[' then
      matchBracket(content, cursor + 1) match
        case Some(refEnd) =>
          val refLabel = content.substring(cursor + 1, refEnd)
          val label = if refLabel.trim.isEmpty then labelText else refLabel
          resolveRef(label, refs).map { _ =>
            buildRefLink(content, start, labelStart, labelEnd, labelText, image, refEnd + 1, refs, profile) -> (refEnd + 1)
          }
        case None => None
    else
      // shortcut reference [label]
      resolveRef(labelText, refs).map { _ =>
        buildRefLink(content, start, labelStart, labelEnd, labelText, image, labelEnd + 1, refs, profile) -> (labelEnd + 1)
      }

  private def resolveRef(label: String, refs: Map[String, LinkRef]): Option[LinkRef] =
    refs.get(normalizeLabel(label))

  private def buildLink(
      content: String, start: Int, labelStart: Int, labelEnd: Int,
      labelText: String, image: Boolean,
      spans: DestTitleSpans, endEx: Int, refs: Map[String, LinkRef], profile: MarkdownProfile,
  ): WNode =
    val branch = if image then MdBranch.Image else MdBranch.Link
    val pieces = Vector.newBuilder[InlinePiece]
    def slice(kind: String, from: Int, to: Int, role: String, ch: TokenChannel): Unit =
      if from < to then pieces += Tok(kind, content.substring(from, to), Some(role), ch)
    pieces += Open(branch, MdKind.LinkOpen, content.substring(start, labelStart), Some("delimiter.open"))
    pieces ++= parse(labelText, refs, profile)
    pieces += Tok(MdKind.LinkClose, content.substring(labelEnd, labelEnd + 1), Some("label.close"), TokenChannel.Syntax)
    // (dest "title") — every source slice is emitted so nothing is lost
    slice(MdKind.DestOpen, labelEnd + 1, spans.destStart, "dest.open", TokenChannel.Syntax)
    slice(MdKind.Destination, spans.destStart, spans.destEnd, "destination", TokenChannel.Syntax)
    slice(MdKind.Indent, spans.destEnd, spans.titleStart, "space", TokenChannel.Trivia)
    slice(MdKind.Title, spans.titleStart, spans.titleEnd, "title", TokenChannel.Syntax)
    slice(MdKind.Indent, spans.titleEnd, spans.closeStart, "space", TokenChannel.Trivia)
    pieces += Close(branch, MdKind.DestClose, content.substring(spans.closeStart, endEx), Some("dest.close"))
    WFixed(pieces.result())

  private def buildRefLink(
      content: String, start: Int, labelStart: Int, labelEnd: Int,
      labelText: String, image: Boolean, endEx: Int,
      refs: Map[String, LinkRef], profile: MarkdownProfile,
  ): WNode =
    val branch = if image then MdBranch.Image else MdBranch.Link
    val pieces = Vector.newBuilder[InlinePiece]
    pieces += Open(branch, MdKind.LinkOpen, content.substring(start, labelStart), Some("delimiter.open"))
    pieces ++= parse(labelText, refs, profile)
    pieces += Close(branch, MdKind.ReferenceLabel, content.substring(labelEnd, endEx), Some("reference"))
    WFixed(pieces.result())

  private final case class DestTitleSpans(
      destStart: Int, destEnd: Int, titleStart: Int, titleEnd: Int, closeStart: Int)

  /** Parses `(dest "title")` starting at the `(`; returns (dest, title, spans, endExclusive). */
  private def parseInlineDestination(content: String, open: Int): Option[(String, Option[String], DestTitleSpans, Int)] =
    var i = open + 1
    val n = content.length
    while i < n && MdChars.isUnicodeWhitespace(content.charAt(i)) do i += 1
    val destStart = i
    // destination: <...> or a run of non-space, balanced parens
    var dest = ""
    if i < n && content.charAt(i) == '<' then
      val end = content.indexOf('>', i + 1)
      if end < 0 || content.substring(i + 1, end).contains('\n') then return None
      dest = content.substring(i + 1, end)
      i = end + 1
    else
      var depth = 0
      val sb = StringBuilder()
      var done = false
      while i < n && !done do
        val c = content.charAt(i)
        if c == '\\' && i + 1 < n then { sb.append(c).append(content.charAt(i + 1)); i += 2 }
        else if MdChars.isUnicodeWhitespace(c) then done = true
        else if c == '(' then { depth += 1; sb.append(c); i += 1 }
        else if c == ')' then
          if depth == 0 then done = true else { depth -= 1; sb.append(c); i += 1 }
        else { sb.append(c); i += 1 }
      dest = sb.result()
    val destEnd = i
    while i < n && MdChars.isUnicodeWhitespace(content.charAt(i)) do i += 1
    var titleStart = i
    var titleEnd = i
    var title: Option[String] = None
    if i < n && (content.charAt(i) == '"' || content.charAt(i) == '\'' || content.charAt(i) == '(') then
      val open2 = content.charAt(i)
      val close2 = if open2 == '(' then ')' else open2
      val end = content.indexOf(close2, i + 1)
      if end < 0 then return None
      titleStart = i
      titleEnd = end + 1
      title = Some(content.substring(i + 1, end))
      i = end + 1
    while i < n && MdChars.isUnicodeWhitespace(content.charAt(i)) do i += 1
    if i >= n || content.charAt(i) != ')' then None
    else Some((dest, title, DestTitleSpans(destStart, destEnd, titleStart, titleEnd, i), i + 1))

  /** Finds the matching `]` for a `[` whose content starts at `from`, honoring
    * nested brackets, escapes and code spans. Returns the index of `]`. */
  private def matchBracket(content: String, from: Int): Option[Int] =
    var i = from
    var depth = 0
    val n = content.length
    while i < n do
      val c = content.charAt(i)
      c match
        case '\\' if i + 1 < n => i += 2
        case '`' =>
          val len = runLength(content, i, '`')
          findBacktickClose(content, i + len, len) match
            case Some(closeAt) => i = closeAt + len
            case None          => i += len
        case '[' => depth += 1; i += 1
        case ']' =>
          if depth == 0 then return Some(i) else { depth -= 1; i += 1 }
        case _ => i += 1
    None

  // ── angle brackets: autolink vs raw HTML ─────────────────────────────────

  private enum AngleKind:
    case Autolink, Html

  private def scanAngle(content: String, start: Int): Option[(AngleKind, Int)] =
    val n = content.length
    if start + 1 >= n then None
    else
      scanAutolink(content, start).map(end => (AngleKind.Autolink, end))
        .orElse(scanRawHtml(content, start).map(end => (AngleKind.Html, end)))

  private def scanAutolink(content: String, start: Int): Option[Int] =
    val close = content.indexOf('>', start + 1)
    if close < 0 then return None
    val inner = content.substring(start + 1, close)
    if inner.isEmpty || inner.exists(c => MdChars.isUnicodeWhitespace(c) || c == '<') then return None
    // URI autolink: scheme:rest
    val colon = inner.indexOf(':')
    val isUri = colon >= 2 && {
      val scheme = inner.substring(0, colon)
      MdChars.isAsciiLetter(scheme.charAt(0)) &&
        scheme.forall(c => MdChars.isAsciiAlnum(c) || c == '+' || c == '.' || c == '-') &&
        scheme.length <= 32
    }
    val isEmail = !isUri && {
      val at = inner.indexOf('@')
      at > 0 && at < inner.length - 1 && !inner.substring(at + 1).contains('@') && inner.forall(c => c != ' ')
    }
    if isUri || isEmail then Some(close + 1) else None

  private def scanRawHtml(content: String, start: Int): Option[Int] =
    val n = content.length
    if start + 1 >= n then return None
    val c1 = content.charAt(start + 1)
    if c1 == '!' then
      if content.startsWith("<!--", start) then
        val end = content.indexOf("-->", start + 4)
        if end >= 0 then Some(end + 3) else None
      else if content.startsWith("<![CDATA[", start) then
        val end = content.indexOf("]]>", start + 9)
        if end >= 0 then Some(end + 3) else None
      else
        val end = content.indexOf('>', start + 2)
        if end >= 0 then Some(end + 1) else None
    else if c1 == '?' then
      val end = content.indexOf("?>", start + 2)
      if end >= 0 then Some(end + 2) else None
    else if c1 == '/' then
      // closing tag </name>
      if start + 2 < n && MdChars.isAsciiLetter(content.charAt(start + 2)) then
        val end = content.indexOf('>', start + 2)
        if end >= 0 && !content.substring(start + 2, end).contains('<') then Some(end + 1) else None
      else None
    else if MdChars.isAsciiLetter(c1) then
      // open tag <name attrs...>; no '<' inside
      val end = content.indexOf('>', start + 1)
      if end >= 0 && !content.substring(start + 1, end).contains('<') then Some(end + 1) else None
    else None

  private def scanEntity(content: String, start: Int): Option[Int] =
    val n = content.length
    if start + 1 >= n then return None
    if content.charAt(start + 1) == '#' then
      var i = start + 2
      if i < n && (content.charAt(i) == 'x' || content.charAt(i) == 'X') then
        i += 1
        val hexStart = i
        while i < n && isHex(content.charAt(i)) do i += 1
        if i > hexStart && i - hexStart <= 6 && i < n && content.charAt(i) == ';' then Some(i + 1) else None
      else
        val decStart = i
        while i < n && MdChars.isAsciiDigit(content.charAt(i)) do i += 1
        if i > decStart && i - decStart <= 7 && i < n && content.charAt(i) == ';' then Some(i + 1) else None
    else
      var i = start + 1
      while i < n && MdChars.isAsciiAlnum(content.charAt(i)) do i += 1
      if i > start + 1 && i < n && content.charAt(i) == ';' then Some(i + 1) else None

  private def scanExpression(content: String, start: Int): Option[Int] =
    // ${ ... } with brace nesting; bounded to the content unit
    var i = start + 2
    var depth = 1
    val n = content.length
    while i < n && depth > 0 do
      content.charAt(i) match
        case '{' => depth += 1; i += 1
        case '}' => depth -= 1; i += 1
        case _   => i += 1
    if depth == 0 then Some(i) else None

  private def isHex(c: Char): Boolean =
    MdChars.isAsciiDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')

  // ── emphasis / strong / strikethrough (delimiter algorithm) ──────────────

  private def processEmphasis(nodes: ArrayBuffer[WNode]): ArrayBuffer[WNode] =
    var closerIdx = 0
    while closerIdx < nodes.size do
      nodes(closerIdx) match
        case closer: WDelim if closer.canClose && closer.lexeme.nonEmpty =>
          // scan back for an opener of the same char
          var openerIdx = closerIdx - 1
          var found = -1
          while openerIdx >= 0 && found < 0 do
            nodes(openerIdx) match
              case opener: WDelim if opener.ch == closer.ch && opener.canOpen && opener.lexeme.nonEmpty =>
                if compatible(opener, closer) then found = openerIdx else openerIdx -= 1
              case _ => openerIdx -= 1
          if found >= 0 then
            val opener = nodes(found).asInstanceOf[WDelim]
            val use = if opener.lexeme.length >= 2 && closer.lexeme.length >= 2 then 2 else 1
            val (branch, kind) =
              if closer.ch == '~' then (MdBranch.Strikethrough, MdKind.StrikethroughRun)
              else if use == 2 then (MdBranch.Strong, MdKind.DelimiterRun)
              else (MdBranch.Emphasis, MdKind.DelimiterRun)
            val openLex = opener.lexeme.substring(opener.lexeme.length - use)
            val closeLex = closer.lexeme.substring(0, use)
            opener.lexeme = opener.lexeme.substring(0, opener.lexeme.length - use)
            closer.lexeme = closer.lexeme.substring(use)
            val inner = nodes.slice(found + 1, closerIdx).flatMap(flatten).toVector
            val wrap = WFixed(
              (Open(branch, kind, openLex, Some("delimiter.open")) +: inner) :+
                Close(branch, kind, closeLex, Some("delimiter.close")))
            // remove only the inner nodes (keep opener and closer with their
            // possibly-reduced lexemes) and splice the wrap between them
            nodes.remove(found + 1, closerIdx - found - 1)
            nodes.insert(found + 1, wrap)
            // reprocess from just after the opener to catch further matches
            closerIdx = found + 1
            // emptied delimiters are dropped lazily on flatten
          else closerIdx += 1
        case _ => closerIdx += 1
    nodes

  /** CommonMark "rule of three": if either delimiter can both open and close,
    * the sum of the two run lengths must not be a multiple of three (unless both
    * are). */
  private def compatible(opener: WDelim, closer: WDelim): Boolean =
    val oc = opener.canOpen && opener.canClose
    val cc = closer.canOpen && closer.canClose
    if !oc && !cc then true
    else
      val sum = opener.lexeme.length + closer.lexeme.length
      if sum % 3 != 0 then true
      else opener.lexeme.length % 3 == 0 && closer.lexeme.length % 3 == 0
