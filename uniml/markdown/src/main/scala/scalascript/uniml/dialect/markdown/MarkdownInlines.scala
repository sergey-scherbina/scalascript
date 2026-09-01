package scalascript.uniml.dialect.markdown

import scalascript.uniml.TokenChannel
import scalascript.uniml.UniAlphabet

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
    // Index walk + `substring` slice, not `foreach`/`c.toString`: ScalaScript v2 has
    // no Char box, so `c.toString` yields the code point's decimal digits — slicing
    // the source is exactly `charAt(i).toString` on the JVM (surrogates included).
    def walk(li: Int, builder: Vector[String], inSpace: Boolean): Vector[String] =
      if li >= trimmed.length then builder
      else if MdChars.isUnicodeWhitespace(trimmed.charAt(li)) then walk(li + 1, builder, true)
      else
        val spaced = if inSpace && builder.nonEmpty then builder :+ " " else builder
        walk(li + 1, spaced :+ trimmed.substring(li, li + 1), false)
    MdChars.foldCase(walk(0, Vector.empty, false).mkString)

  def parse(content: String, refs: Map[String, LinkRef], profile: MarkdownProfile): Vector[InlinePiece] =
    val atoms = tokenize(content, refs, profile)
    val processed = processEmphasis(atoms)
    processed.iterator.flatMap(flatten).toVector

  // ── working node model ────────────────────────────────────────────────

  private sealed trait WNode
  private final case class WFixed(pieces: Vector[InlinePiece]) extends WNode
  // Immutable delimiter node: `processEmphasis` reduces a run's length by
  // replacing the node in the vector with a new `WDelim`, never mutating it.
  private final case class WDelim(
      lexeme: String,
      ch: Char,
      canOpen: Boolean,
      canClose: Boolean,
  ) extends WNode

  private def flatten(node: WNode): Vector[InlinePiece] = node match
    case WFixed(pieces) => pieces
    case delim: WDelim  => if delim.lexeme.isEmpty then Vector.empty else Vector(Tok(MdKind.DelimiterRun, delim.lexeme, Some("literal"), TokenChannel.Syntax))

  private def text(lexeme: String): WNode = WFixed(Vector(Tok(MdKind.Text, lexeme, None, TokenChannel.Syntax)))

  // ── tokenization ──────────────────────────────────────────────────────

  /** The tokenizer in flight: finished nodes plus the pending literal-text run. */
  private final case class TokState(nodes: Vector[WNode], pending: Vector[String])

  private def tokenize(content: String, refs: Map[String, LinkRef], profile: MarkdownProfile): Vector[WNode] =
    val n = content.length
    val gfm = profile == MarkdownProfile.Gfm
    val scala = profile == MarkdownProfile.ScalaScript

    def flushText(st: TokState): TokState =
      if st.pending.nonEmpty then TokState(st.nodes :+ text(st.pending.mkString), Vector.empty)
      else st

    def emitFixed(st: TokState, node: WNode): TokState =
      val flushed = flushText(st)
      flushed.copy(nodes = flushed.nodes :+ node)

    def walk(st: TokState, i: Int): TokState =
      if i >= n then flushText(st)
      else
        val c = content.charAt(i)
        c match
          case '\n' | '\r' =>
            // line ending within a content unit: hard break if preceded by 2+ spaces or a backslash
            val ending =
              if c == '\r' && i + 1 < n && content.charAt(i + 1) == '\n' then "\r\n" else content.substring(i, i + 1)
            val pend = st.pending.mkString
            val hard = pend.endsWith("  ") || pend.endsWith("\\")
            if hard && pend.endsWith("\\") then
              // strip the trailing backslash into a hard-break marker
              val stripped = st.copy(pending = st.pending.dropRight(1))
              walk(emitFixed(stripped, WFixed(Vector(Tok(MdKind.HardBreak, "\\" + ending, None, TokenChannel.Syntax)))), i + ending.length)
            else
              walk(emitFixed(st, WFixed(Vector(Tok(if hard then MdKind.HardBreak else MdKind.SoftBreak, ending, None, if hard then TokenChannel.Syntax else TokenChannel.Trivia)))), i + ending.length)

          case '\\' =>
            if i + 1 < n && MdChars.isAsciiPunctuation(content.charAt(i + 1)) then
              walk(emitFixed(st, WFixed(Vector(Tok(MdKind.Escape, content.substring(i, i + 2), None, TokenChannel.Syntax)))), i + 2)
            else
              walk(st.copy(pending = st.pending :+ "\\"), i + 1)

          case '`' =>
            val runLen = runLength(content, i, '`')
            findBacktickClose(content, i + runLen, runLen) match
              case Some(start) =>
                val openLex = content.substring(i, i + runLen)
                val inner = content.substring(i + runLen, start)
                val closeLex = content.substring(start, start + runLen)
                val pieces =
                  Vector[InlinePiece](Open(MdBranch.CodeSpan, MdKind.BacktickRun, openLex, Some("delimiter.open"))) ++
                    (if inner.nonEmpty then Vector[InlinePiece](Tok(MdKind.CodeContent, inner, Some("code"), TokenChannel.Embedded)) else Vector.empty) :+
                    Close(MdBranch.CodeSpan, MdKind.BacktickRun, closeLex, Some("delimiter.close"))
                walk(emitFixed(st, WFixed(pieces)), start + runLen)
              case None =>
                walk(st.copy(pending = st.pending :+ content.substring(i, i + runLen)), i + runLen)

          case '<' =>
            scanAngle(content, i) match
              case Some((kind, endEx)) =>
                val lex = content.substring(i, endEx)
                val (mdKind, role, channel) = kind match
                  case AngleKind.Autolink => (MdKind.Autolink, Some("autolink"), TokenChannel.Syntax)
                  case AngleKind.Html     => (MdKind.Html, Some("html"), TokenChannel.Embedded)
                walk(emitFixed(st, WFixed(Vector(Tok(mdKind, lex, role, channel)))), endEx)
              case None =>
                walk(st.copy(pending = st.pending :+ "<"), i + 1)

          case '&' =>
            scanEntity(content, i) match
              case Some(endEx) =>
                walk(emitFixed(st, WFixed(Vector(Tok(MdKind.Entity, content.substring(i, endEx), Some("entity"), TokenChannel.Syntax)))), endEx)
              case None =>
                walk(st.copy(pending = st.pending :+ "&"), i + 1)

          case '$' if scala && i + 1 < n && content.charAt(i + 1) == '{' =>
            scanExpression(content, i) match
              case Some(endEx) =>
                val open = content.substring(i, i + 2)
                val inner = content.substring(i + 2, endEx - 1)
                val close = content.substring(endEx - 1, endEx)
                val pieces =
                  Vector[InlinePiece](Open(MdBranch.Expression, MdKind.ExpressionOpen, open, Some("delimiter.open"))) ++
                    (if inner.nonEmpty then Vector[InlinePiece](Tok(MdKind.ExpressionContent, inner, Some("expression"), TokenChannel.Embedded)) else Vector.empty) :+
                    Close(MdBranch.Expression, MdKind.ExpressionClose, close, Some("delimiter.close"))
                walk(emitFixed(st, WFixed(pieces)), endEx)
              case None =>
                walk(st.copy(pending = st.pending :+ "$"), i + 1)

          case '!' if i + 1 < n && content.charAt(i + 1) == '[' =>
            tryLink(content, i, image = true, refs, profile) match
              case Some((node, endEx)) => walk(emitFixed(st, node), endEx)
              case None                => walk(st.copy(pending = st.pending :+ "!"), i + 1)

          case '[' =>
            tryLink(content, i, image = false, refs, profile) match
              case Some((node, endEx)) => walk(emitFixed(st, node), endEx)
              case None                => walk(st.copy(pending = st.pending :+ "["), i + 1)

          case '*' | '_' =>
            walk(emitFixed(st, delimiterRun(content, i, c)), i + runLength(content, i, c))

          case '~' if gfm =>
            walk(emitFixed(st, delimiterRun(content, i, c)), i + runLength(content, i, c))

          case _ if gfm && isExtendedAutolinkStart(content, i, st.pending) =>
            // GFM 6.9 extended autolinks. An `@` looks BACKWARD: the local part is
            // already in `pending` — and possibly in nodes BEFORE it, because `_`
            // is a legal local-part character that the emphasis scanner has
            // already split off as a delimiter run. `a.b-c_d@a.b` is exactly that
            // case, and without walking back through those nodes it produced a
            // link over `d@a.b` alone: worse than not matching at all.
            val (dropNodes, keepText, localPart) =
              if content.charAt(i) == '@' then emailLocalBackscan(st.nodes, st.pending)
              else (0, "", "")
            extendedAutolink(content, i, localPart) match
              case Some((backtrack, lexeme, _)) =>
                val adjusted =
                  if backtrack > 0 then
                    TokState(
                      if dropNodes > 0 then st.nodes.dropRight(dropNodes) else st.nodes,
                      if keepText.isEmpty then Vector.empty else Vector(keepText))
                  else st
                // the role says WHICH autolink form this is; the destination is
                // derived from the lexeme in the projection, so the token carries
                // no data the source does not already hold
                walk(emitFixed(adjusted, WFixed(Vector(
                  Tok(MdKind.Autolink, lexeme, Some("extended"), TokenChannel.Syntax)
                ))), i - backtrack + lexeme.length)
              case None =>
                walk(st.copy(pending = st.pending :+ content.substring(i, i + 1)), i + 1)

          case _ =>
            walk(st.copy(pending = st.pending :+ content.substring(i, i + 1)), i + 1)

    walk(TokState(Vector.empty, Vector.empty), 0).nodes

  // ── GFM extended autolinks (6.9) ────────────────────────────────────────

  /** Cheap gate so the scan only pays for the four characters that can begin
    * one: `w`(ww.), `h`(ttp), `f`(tp), and `@` for the email form. */
  private def isExtendedAutolinkStart(content: String, i: Int, pending: Vector[String]): Boolean =
    content.charAt(i) match
      case 'w' | 'h' | 'f' => validAutolinkPredecessor(content, i)
      case '@'             => pending.nonEmpty
      case _               => false

  /** "valid preceding character": start of line, whitespace, or one of `*_~(`. */
  private def validAutolinkPredecessor(content: String, i: Int): Boolean =
    if i == 0 then true
    else
      val p = content.charAt(i - 1)
      MdChars.isUnicodeWhitespace(p) || p == '*' || p == '_' || p == '~' || p == '('

  /** Returns (chars to take back from pending, the full autolink lexeme, href).
    * Backtrack is non-zero only for the email form, whose local part precedes
    * the `@` that triggered the match. */
  private def extendedAutolink(
      content: String, i: Int, localPart: String,
  ): Option[(Int, String, String)] =
    if content.charAt(i) == '@' then emailAutolink(content, i, localPart)
    else
      val schemes = Vector("http://", "https://", "ftp://")
      val scheme = schemes.find(s => content.regionMatches(true, i, s, 0, s.length))
      if scheme.isDefined then
        domainAndPath(content, i + scheme.get.length).map { end =>
          val lexeme = trimAutolinkTail(content.substring(i, end))
          (0, lexeme, lexeme)
        }.filter(_._2.length > scheme.get.length)
      else if content.regionMatches(true, i, "www.", 0, 4) then
        domainAndPath(content, i).map { end =>
          val lexeme = trimAutolinkTail(content.substring(i, end))
          (0, lexeme, "http://" + lexeme)
        }.filter(_._2.length > 4)
      else None

  /** `local@domain`, with the local part already recovered by the backscan. */
  private def emailAutolink(
      content: String, at: Int, local: String,
  ): Option[(Int, String, String)] =
    if local.isEmpty then None
    else
      def domainEnd(j: Int): Int =
        if j < content.length && isEmailDomainChar(content.charAt(j)) then domainEnd(j + 1) else j
      // A trailing `.` is sentence punctuation after the address. A trailing `-`
      // or `_` is NOT trimmed: GFM says the address is invalid, so `a@b-` links
      // nothing rather than linking `a@b`.
      def dropDots(j: Int): Int =
        if j > at + 1 && content.charAt(j - 1) == '.' then dropDots(j - 1) else j
      val j = dropDots(domainEnd(at + 1))
      val domain = content.substring(at + 1, j)
      if !validEmailDomain(domain) then None
      else
        val lexeme = local + "@" + domain
        Some((local.length, lexeme, "mailto:" + lexeme))

  /** Walks back over `pending` and, once it is exhausted, over already-emitted
    * text and `_` delimiter nodes, collecting the longest run of legal
    * local-part characters. Returns (nodes to drop, text to keep from the last
    * partially consumed node, the local part). */
  private def emailLocalBackscan(
      nodes: Vector[WNode], pending: Vector[String],
  ): (Int, String, String) =
    def localTextOf(node: WNode): Option[String] = node match
      case WFixed(Vector(Tok(MdKind.Text, lexeme, _, _))) => Some(lexeme)
      case d: WDelim if d.ch == '_'                       => Some(d.lexeme)
      case _                                              => None
    // index where the trailing run of legal local-part characters begins
    def localCut(chunk: String): Int =
      def scan(cut: Int): Int =
        if cut > 0 && isEmailLocalChar(chunk.charAt(cut - 1)) then scan(cut - 1) else cut
      scan(chunk.length)
    def back(drop: Int, local: String, keep: String, exhausted: Boolean): (Int, String, String) =
      if exhausted && drop < nodes.length && localTextOf(nodes(nodes.length - 1 - drop)).isDefined then
        val chunk = localTextOf(nodes(nodes.length - 1 - drop)).get
        val cut = localCut(chunk)
        back(drop + 1, chunk.substring(cut) + local, chunk.substring(0, cut), cut == 0)
      else (drop, keep, local)
    val chunk0 = pending.mkString
    val cut0 = localCut(chunk0)
    val (drop, keep, local) = back(0, chunk0.substring(cut0), chunk0.substring(0, cut0), cut0 == 0)
    if keep.nonEmpty && !validEmailPredecessor(keep.charAt(keep.length - 1)) then (0, "", "")
    else (drop, keep, local)

  private def isEmailLocalChar(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
      c == '.' || c == '-' || c == '_' || c == '+'

  private def validEmailPredecessor(c: Char): Boolean =
    MdChars.isUnicodeWhitespace(c) || c == '*' || c == '_' || c == '~' || c == '('

  private def isEmailDomainChar(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
      c == '.' || c == '-' || c == '_'

  private def validEmailDomain(domain: String): Boolean =
    val segments = domain.split("\\.", -1).toVector
    val last = if domain.isEmpty then ' ' else domain.charAt(domain.length - 1)
    // a trailing `-` or `_` INVALIDATES the address rather than being trimmed —
    // `a@b-` must link nothing, not link `a@b`
    segments.length >= 2 && segments.forall(_.nonEmpty) &&
      segments.takeRight(2).forall(!_.contains("_")) &&
      last != '-' && last != '_'

  /** Scans the domain and everything after it up to whitespace or `<`, which
    * cuts an extended autolink. Returns the exclusive end, or None when the
    * domain itself is not valid. */
  private def domainAndPath(content: String, from: Int): Option[Int] =
    def domainEnd(j: Int): Int =
      if j < content.length && isEmailDomainChar(content.charAt(j)) then domainEnd(j + 1) else j
    val de = domainEnd(from)
    val domain = content.substring(from, de)
    val segments = domain.split("\\.", -1).toVector
    if segments.length < 2 || segments.take(segments.length - 1).exists(_.isEmpty) then None
    else
      // the rest of the URL runs to whitespace; `<` always ends it
      def pathEnd(j: Int): Int =
        if j < content.length && !MdChars.isUnicodeWhitespace(content.charAt(j)) && content.charAt(j) != '<' then pathEnd(j + 1) else j
      Some(pathEnd(de))

  /** GFM's three trailing rules, applied until a pass changes nothing: strip
    * `?!.,:*_~`; strip an unbalanced `)`; strip a whole trailing `&entity;`. */
  private def trimAutolinkTail(raw: String): String =
    def pass(s: String): String =
      if s.isEmpty then s
      else
        val last = s.charAt(s.length - 1)
        if "?!.,:*_~".indexOf(last) >= 0 then pass(s.substring(0, s.length - 1))
        else if last == ')' then
          // closing parens are kept while they are matched by an opening one
          if s.count(_ == ')') > s.count(_ == '(') then pass(s.substring(0, s.length - 1)) else s
        else if last == ';' then
          // Entity names are ASCII alphanumeric per HTML5, so this is narrower than the host test on
          // purpose as well as tableless: `&café;` was never an entity reference.
          def nameStart(k: Int): Int =
            if k >= 0 && UniAlphabet.isAsciiAlnum(s.charAt(k)) then nameStart(k - 1) else k
          val k = nameStart(s.length - 2)
          if k >= 0 && s.charAt(k) == '&' && k < s.length - 2 then pass(s.substring(0, k)) else s
        else s
    pass(raw)


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
    def scan(i: Int): Int =
      if i < content.length && content.charAt(i) == ch then scan(i + 1) else i
    scan(start) - start

  private def findBacktickClose(content: String, from: Int, runLen: Int): Option[Int] =
    def scan(i: Int): Option[Int] =
      if i >= content.length then None
      else if content.charAt(i) == '`' then
        val len = runLength(content, i, '`')
        if len == runLen then Some(i) else scan(i + len)
      else scan(i + 1)
    scan(from)

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
        // "Links may not contain other links, at any level of nesting"
        // (CommonMark 6.3). The OUTER bracket loses: declining here leaves `[`
        // as literal text, the scan continues, and the inner link is built on
        // its own — which is exactly what the spec's expected output shows for
        // `[foo [bar](/uri)](/uri)`. Images are exempt: an image whose alt text
        // contains a link is still an image, and the inner link renders as the
        // plain text of the alt.
        if !image && containsLink(parse(labelText, refs, profile)) then return None
        val cursor = labelEnd + 1 // just past ']'
        // inline destination: [text](dest "title")
        if cursor < content.length && content.charAt(cursor) == '(' then
          parseInlineDestination(content, cursor) match
            case Some((destTitleSpans, endEx)) =>
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

  /** True when these pieces already open a link branch — an autolink counts, so
    * `[foo<https://x>](uri)` declines the same way a bracketed link does. */
  private def containsLink(pieces: Vector[InlinePiece]): Boolean =
    pieces.exists {
      case Open(branch, _, _, _)  => branch == MdBranch.Link
      case Tok(kind, _, _, _)     => kind == MdKind.Autolink
      case _                      => false
    }

  private def buildLink(
      content: String, start: Int, labelStart: Int, labelEnd: Int,
      labelText: String, image: Boolean,
      spans: DestTitleSpans, endEx: Int, refs: Map[String, LinkRef], profile: MarkdownProfile,
  ): WNode =
    val branch = if image then MdBranch.Image else MdBranch.Link
    def slice(kind: String, from: Int, to: Int, role: String, ch: TokenChannel): Vector[InlinePiece] =
      if from < to then Vector(Tok(kind, content.substring(from, to), Some(role), ch)) else Vector.empty
    WFixed(
      (Vector[InlinePiece](Open(branch, MdKind.LinkOpen, content.substring(start, labelStart), Some("delimiter.open"))) ++
        parse(labelText, refs, profile) :+
        Tok(MdKind.LinkClose, content.substring(labelEnd, labelEnd + 1), Some("label.close"), TokenChannel.Syntax)) ++
        // (dest "title") — every source slice is emitted so nothing is lost
        slice(MdKind.DestOpen, labelEnd + 1, spans.destStart, "dest.open", TokenChannel.Syntax) ++
        slice(MdKind.Destination, spans.destStart, spans.destEnd, "destination", TokenChannel.Syntax) ++
        slice(MdKind.Indent, spans.destEnd, spans.titleStart, "space", TokenChannel.Trivia) ++
        slice(MdKind.Title, spans.titleStart, spans.titleEnd, "title", TokenChannel.Syntax) ++
        slice(MdKind.Indent, spans.titleEnd, spans.closeStart, "space", TokenChannel.Trivia) :+
        Close(branch, MdKind.DestClose, content.substring(spans.closeStart, endEx), Some("dest.close")))

  private def buildRefLink(
      content: String, start: Int, labelStart: Int, labelEnd: Int,
      labelText: String, image: Boolean, endEx: Int,
      refs: Map[String, LinkRef], profile: MarkdownProfile,
  ): WNode =
    val branch = if image then MdBranch.Image else MdBranch.Link
    WFixed(
      (Vector[InlinePiece](Open(branch, MdKind.LinkOpen, content.substring(start, labelStart), Some("delimiter.open"))) ++
        parse(labelText, refs, profile)) :+
        Close(branch, MdKind.ReferenceLabel, content.substring(labelEnd, endEx), Some("reference")))

  private final case class DestTitleSpans(
      destStart: Int, destEnd: Int, titleStart: Int, titleEnd: Int, closeStart: Int)

  /** Parses `(dest "title")` starting at the `(`; returns (spans, endExclusive).
    * The spans are SOURCE SLICES and are all the caller reads — the old decoded
    * dest/title return values were computed and then discarded at the only call
    * site, so they are gone. */
  private def parseInlineDestination(content: String, open: Int): Option[(DestTitleSpans, Int)] =
    val n = content.length
    def skipWs(i: Int): Int =
      if i < n && MdChars.isUnicodeWhitespace(content.charAt(i)) then skipWs(i + 1) else i
    val destStart = skipWs(open + 1)
    // destination: <...> or a run of non-space, balanced parens (escapes skip
    // their escaped char so `\)` neither closes nor unbalances)
    val destEndOpt: Option[Int] =
      if destStart < n && content.charAt(destStart) == '<' then
        val end = content.indexOf('>', destStart + 1)
        if end < 0 || content.substring(destStart + 1, end).contains('\n') then None
        else Some(end + 1)
      else
        def bare(i: Int, depth: Int): Int =
          if i >= n then i
          else
            val c = content.charAt(i)
            if c == '\\' && i + 1 < n then bare(i + 2, depth)
            else if MdChars.isUnicodeWhitespace(c) then i
            else if c == '(' then bare(i + 1, depth + 1)
            else if c == ')' then
              if depth == 0 then i else bare(i + 1, depth - 1)
            else bare(i + 1, depth)
        Some(bare(destStart, 0))
    destEndOpt match
      case None => None
      case Some(destEnd) =>
        val afterDest = skipWs(destEnd)
        val titleEndOpt: Option[(Int, Int)] =
          if afterDest < n && (content.charAt(afterDest) == '"' || content.charAt(afterDest) == '\'' || content.charAt(afterDest) == '(') then
            val open2 = content.charAt(afterDest)
            val close2 = if open2 == '(' then ')' else open2
            val end = content.indexOf(close2, afterDest + 1)
            if end < 0 then None else Some((afterDest, end + 1))
          else Some((afterDest, afterDest))
        titleEndOpt match
          case None => None
          case Some((titleStart, titleEnd)) =>
            val closeStart = skipWs(titleEnd)
            if closeStart >= n || content.charAt(closeStart) != ')' then None
            else Some((DestTitleSpans(destStart, destEnd, titleStart, titleEnd, closeStart), closeStart + 1))

  /** Finds the matching `]` for a `[` whose content starts at `from`, honoring
    * nested brackets, escapes and code spans. Returns the index of `]`. */
  private def matchBracket(content: String, from: Int): Option[Int] =
    val n = content.length
    def scan(i: Int, depth: Int): Option[Int] =
      if i >= n then None
      else
        content.charAt(i) match
          case '\\' if i + 1 < n => scan(i + 2, depth)
          case '`' =>
            val len = runLength(content, i, '`')
            findBacktickClose(content, i + len, len) match
              case Some(closeAt) => scan(closeAt + len, depth)
              case None          => scan(i + len, depth)
          case '[' => scan(i + 1, depth + 1)
          case ']' =>
            if depth == 0 then Some(i) else scan(i + 1, depth - 1)
          case _ => scan(i + 1, depth)
    scan(from, 0)

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
      if content.startsWith("<!--", start) then scanComment(content, start)
      else if content.startsWith("<![CDATA[", start) then
        val end = content.indexOf("]]>", start + 9)
        if end >= 0 then Some(end + 3) else None
      else
        val end = content.indexOf('>', start + 2)
        if end >= 0 then Some(end + 1) else None
    else if c1 == '?' then
      val end = content.indexOf("?>", start + 2)
      if end >= 0 then Some(end + 2) else None
    else if c1 == '/' then scanClosingTag(content, start)
    else if MdChars.isAsciiLetter(c1) then scanOpenTag(content, start)
    else None

  /** CommonMark 0.31.2 comments: `<!-->` and `<!--->` are complete on their own,
    * and otherwise the text runs to the first `-->`. The old scan searched for
    * `-->` from index 4, so `<!-->` never matched and the trailing `-->` of
    * `foo <!--> foo -->` leaked out as raw HTML. */
  private def scanComment(content: String, start: Int): Option[Int] =
    if content.startsWith("<!-->", start) then Some(start + 5)
    else if content.startsWith("<!--->", start) then Some(start + 6)
    else
      val end = content.indexOf("-->", start + 4)
      if end >= 0 then Some(end + 3) else None

  /** `</tagname whitespace? >` — nothing else may appear, so `</a href="foo">`
    * is TEXT, not a closing tag. */
  private def scanClosingTag(content: String, start: Int): Option[Int] =
    val n = content.length
    def nameEnd(i: Int): Int =
      if i < n && (MdChars.isAsciiAlnum(content.charAt(i)) || content.charAt(i) == '-') then nameEnd(i + 1) else i
    def wsEnd(i: Int): Int =
      if i < n && MdChars.isUnicodeWhitespace(content.charAt(i)) then wsEnd(i + 1) else i
    if start + 2 >= n || !MdChars.isAsciiLetter(content.charAt(start + 2)) then None
    else
      val i = wsEnd(nameEnd(start + 2))
      if i < n && content.charAt(i) == '>' then Some(i + 1) else None

  /** CommonMark 6.6's open-tag grammar, which the old scan did not have at all —
    * it took everything up to the next `>` that held no `<`. That accepted
    * `<a h*#ref="hi">`, `<a href='bar'title=title>` and `<a href="\"">` as raw
    * HTML and passed them through unescaped: a MALFORMED tag is text, and
    * emitting it as HTML is the difference between showing a user their typo and
    * injecting it into the document. */
  private def scanOpenTag(content: String, start: Int): Option[Int] =
    val n = content.length
    def wsEnd(i: Int): Int =
      if i < n && MdChars.isUnicodeWhitespace(content.charAt(i)) then wsEnd(i + 1) else i
    def tagNameEnd(i: Int): Int =
      if i < n && (MdChars.isAsciiAlnum(content.charAt(i)) || content.charAt(i) == '-') then tagNameEnd(i + 1) else i
    def attrNameEnd(i: Int): Int =
      if i < n && (MdChars.isAsciiAlnum(content.charAt(i)) || "_.:-".indexOf(content.charAt(i)) >= 0) then attrNameEnd(i + 1) else i
    def unquotedEnd(i: Int): Int =
      if i < n && !MdChars.isUnicodeWhitespace(content.charAt(i)) && "\"'=<>`".indexOf(content.charAt(i)) < 0 then unquotedEnd(i + 1) else i
    // one attribute (or the tag close) per round; running off the end fails the
    // next round's checks, which is how the old loop's `i >= n` bail behaved
    def attrs(i0: Int): Option[Int] =
      val i1 = wsEnd(i0)
      if i1 < n && content.charAt(i1) == '>' then Some(i1 + 1)
      else if i1 + 1 < n && content.charAt(i1) == '/' && content.charAt(i1 + 1) == '>' then Some(i1 + 2)
      else if i1 == i0 then None // an attribute must be preceded by whitespace
      else
        // attribute name
        val nameEnd =
          if i1 < n && (MdChars.isAsciiLetter(content.charAt(i1)) || content.charAt(i1) == '_' || content.charAt(i1) == ':') then
            attrNameEnd(i1 + 1)
          else i1
        if nameEnd == i1 then None
        else
          val afterName = wsEnd(nameEnd)
          if afterName < n && content.charAt(afterName) == '=' then
            val valPos = wsEnd(afterName + 1)
            if valPos >= n then None
            else
              val q = content.charAt(valPos)
              if q == '\'' || q == '"' then
                val close = content.indexOf(q.toInt, valPos + 1)
                if close < 0 then None else attrs(close + 1)
              else
                val valEnd = unquotedEnd(valPos)
                if valEnd == valPos then None else attrs(valEnd)
          else attrs(nameEnd) // valueless attribute
    if start + 1 >= n || !MdChars.isAsciiLetter(content.charAt(start + 1)) then None
    else attrs(tagNameEnd(start + 1))

  private def scanEntity(content: String, start: Int): Option[Int] =
    val n = content.length
    def hexEnd(i: Int): Int = if i < n && isHex(content.charAt(i)) then hexEnd(i + 1) else i
    def decEnd(i: Int): Int = if i < n && MdChars.isAsciiDigit(content.charAt(i)) then decEnd(i + 1) else i
    def nameEnd(i: Int): Int = if i < n && MdChars.isAsciiAlnum(content.charAt(i)) then nameEnd(i + 1) else i
    if start + 1 >= n then None
    else if content.charAt(start + 1) == '#' then
      val i0 = start + 2
      if i0 < n && (content.charAt(i0) == 'x' || content.charAt(i0) == 'X') then
        val hexStart = i0 + 1
        val i = hexEnd(hexStart)
        if i > hexStart && i - hexStart <= 6 && i < n && content.charAt(i) == ';' then Some(i + 1) else None
      else
        val i = decEnd(i0)
        if i > i0 && i - i0 <= 7 && i < n && content.charAt(i) == ';' then Some(i + 1) else None
    else
      val i = nameEnd(start + 1)
      if i > start + 1 && i < n && content.charAt(i) == ';' then Some(i + 1) else None

  private def scanExpression(content: String, start: Int): Option[Int] =
    // ${ ... } with brace nesting; bounded to the content unit
    val n = content.length
    def scan(i: Int, depth: Int): Option[Int] =
      if depth == 0 then Some(i)
      else if i >= n then None
      else
        content.charAt(i) match
          case '{' => scan(i + 1, depth + 1)
          case '}' => scan(i + 1, depth - 1)
          case _   => scan(i + 1, depth)
    scan(start + 2, 1)

  private def isHex(c: Char): Boolean =
    MdChars.isAsciiDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')

  // ── emphasis / strong / strikethrough (delimiter algorithm) ──────────────

  private def processEmphasis(input: Vector[WNode]): Vector[WNode] =
    // scan back for an opener of the same char
    def findOpener(nodes: Vector[WNode], closer: WDelim, idx: Int): Int =
      if idx < 0 then -1
      else nodes(idx) match
        case opener: WDelim if opener.ch == closer.ch && opener.canOpen && opener.lexeme.nonEmpty =>
          if compatible(opener, closer) then idx else findOpener(nodes, closer, idx - 1)
        case _ => findOpener(nodes, closer, idx - 1)
    def loop(nodes: Vector[WNode], closerIdx: Int): Vector[WNode] =
      if closerIdx >= nodes.size then nodes
      else nodes(closerIdx) match
        case closer: WDelim if closer.canClose && closer.lexeme.nonEmpty =>
          val found = findOpener(nodes, closer, closerIdx - 1)
          if found >= 0 then
            val opener = nodes(found).asInstanceOf[WDelim]
            val use = if opener.lexeme.length >= 2 && closer.lexeme.length >= 2 then 2 else 1
            val (branch, kind) =
              if closer.ch == '~' then (MdBranch.Strikethrough, MdKind.StrikethroughRun)
              else if use == 2 then (MdBranch.Strong, MdKind.DelimiterRun)
              else (MdBranch.Emphasis, MdKind.DelimiterRun)
            val openLex = opener.lexeme.substring(opener.lexeme.length - use)
            val closeLex = closer.lexeme.substring(0, use)
            // reduce opener/closer by replacing them with shortened copies
            val newOpener = WDelim(opener.lexeme.substring(0, opener.lexeme.length - use), opener.ch, opener.canOpen, opener.canClose)
            val newCloser = WDelim(closer.lexeme.substring(use), closer.ch, closer.canOpen, closer.canClose)
            val inner = nodes.slice(found + 1, closerIdx).flatMap(flatten).toVector
            val wrap = WFixed(
              (Open(branch, kind, openLex, Some("delimiter.open")) +: inner) :+
                Close(branch, kind, closeLex, Some("delimiter.close")))
            // splice: [..opener), reduced-opener, wrap, reduced-closer, (closer..]
            // — dropping only the inner nodes between opener and closer.
            // Reprocess from just after the opener to catch further matches;
            // each match strictly shortens the two runs, so this terminates.
            // Emptied delimiters are dropped lazily on flatten.
            loop(nodes.take(found) ++ Vector(newOpener, wrap, newCloser) ++ nodes.drop(closerIdx + 1), found + 1)
          else loop(nodes, closerIdx + 1)
        case _ => loop(nodes, closerIdx + 1)
    loop(input, 0)

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
