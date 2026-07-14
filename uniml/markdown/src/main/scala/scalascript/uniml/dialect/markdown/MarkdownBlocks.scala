package scalascript.uniml.dialect.markdown

import scalascript.uniml.*
import MarkdownInlines.{InlinePiece, LinkRef}

/** Result of the block pass: the ordered VM-token stream and structure-level
  * diagnostics (limit hits, unterminated fences, etc.). */
private[markdown] final case class MarkdownBlockResult(
    tokens: Vector[VmToken],
    diagnostics: Vector[Diagnostic],
)

/** Turns the whole buffered source into a lossless VM-token stream. A first pass
  * collects link reference definitions (so forward references resolve); the main
  * pass walks lines with a container stack, opening/closing block frames through
  * `Reframe` transitions and delegating leaf content to [[MarkdownInlines]].
  *
  * All parsing state (the token cursor plus the block/container state that used
  * to live in a mutable `TokenSink` and mutable object fields) lives in local
  * `var`s inside `parse`, with immutable `Vector`/`Map` accumulation and a local
  * imperative shell; the state-touching helpers are nested defs over those
  * locals and the pure classifiers stay class methods. */
// container stack: each entry is a frame kept open across continuation lines.
// Hoisted to TOP LEVEL (from inside MarkdownBlocks): ScalaScript v2 attributes a
// NESTED case class's body method to the enclosing class (`Bq.frame` was registered
// as `MarkdownBlocks.frame`), so `container.frame` dispatch Stub'd. Top-level
// case-class body methods dispatch correctly; file-private, so scalac is unchanged.
private[markdown] sealed trait Container:
  def frame: String
private[markdown] final case class Blockquote() extends Container:
  def frame = MdBranch.Blockquote
private[markdown] final case class ListFrame(ordered: Boolean) extends Container:
  def frame = MdBranch.List
private[markdown] final case class ListItemFrame(ordered: Boolean, contentIndent: Int) extends Container:
  def frame = MdBranch.ListItem

// Hoisted from inside MarkdownBlocks (a plain class) — v2 doesn't lower type decls
// nested in a plain-class body. File-private, so scalac is unchanged.
private[markdown] enum OpenLeaf:
  case None
  case Paragraph
  case FencedCode(char: Char, len: Int)

// One physical paragraph line: the stripped container-continuation prefix (a `> `
// marker or list indent; empty on the first line and lazy lines), the de-prefixed
// content, and the exact ending.
private[markdown] final case class ParaSeg(prefix: String, content: String, ending: String)

private[markdown] final class MarkdownBlocks(
    source: SourceId,
    profile: MarkdownProfile,
    limits: MarkdownLimits,
):
  private val gfm = profile == MarkdownProfile.Gfm
  private val scala = profile == MarkdownProfile.ScalaScript

  def parse(text: String): MarkdownBlockResult =
    // ── token cursor state (was TokenSink) ──────────────────────────────────
    var pos = SourcePosition.Start
    var nextId = 0L
    var out: Vector[VmToken] = Vector.empty
    var frames: Vector[String] = Vector.empty
    // ── block / container state ─────────────────────────────────────────────
    var diagnostics: Vector[Diagnostic] = Vector.empty
    var refs: Map[String, LinkRef] = Map.empty
    var containers: Vector[Container] = Vector.empty
    // frames pending closure, to fold into the next emitted structural token
    var pendingClose: Vector[String] = Vector.empty
    // leaf accumulation state
    var open: OpenLeaf = OpenLeaf.None
    var paragraphSegs: Vector[ParaSeg] = Vector.empty
    // container-continuation prefix stripped on the current line (set by
    // matchContainers), attached to the next appended segment
    var paragraphPendingPrefix = ""

    // ── token cursor: emits VmTokens in source order via one advancing pos ────

    def emit(kind: String, lexeme: String, instruction: VmInstruction, channel: TokenChannel): Unit =
      if lexeme.isEmpty then ()
      else
        val start = pos
        pos = Unicode.advance(pos, lexeme)
        val token = SourceToken(nextId, kind, lexeme, SourceSpan(source, start, pos), channel)
        nextId += 1L
        out = out :+ VmToken(token, instruction)
        track(instruction)

    def track(instruction: VmInstruction): Unit = instruction match
      case VmInstruction.Open(k, _) => frames = frames :+ k
      case VmInstruction.Close(expected, _) =>
        if frames.nonEmpty && expected.forall(_ == frames.last) then frames = frames.dropRight(1)
      case VmInstruction.Reframe(closeBefore, opens, closeAfter, _) =>
        closeBefore.foreach(_ => if frames.nonEmpty then frames = frames.dropRight(1))
        opens.foreach(spec => frames = frames :+ spec.kind)
        closeAfter.foreach(_ => if frames.nonEmpty then frames = frames.dropRight(1))
      case _ => ()

    /** Rewrites the final token so it also closes every still-open frame
      * (innermost first), avoiding the VM's end-of-input "unclosed node" errors
      * for blocks that legitimately have no closing delimiter (e.g. a paragraph
      * at EOF with no trailing newline). No-op for an empty document. */
    def closeDangling(): Unit =
      if frames.nonEmpty && out.nonEmpty then
        val remaining = frames.reverse
        val last = out.last
        val rewritten = last.instruction match
          case VmInstruction.Emit(role) =>
            // closeBefore/open passed explicitly in field order: v2 does not reorder an
            // all-named enum-case construction that omits leading defaults.
            VmInstruction.Reframe(closeBefore = Vector.empty, open = Vector.empty, closeAfter = remaining, role = role)
          case VmInstruction.Close(expected, role) =>
            VmInstruction.Reframe(closeBefore = Vector.empty, open = Vector.empty, closeAfter = expected.toVector ++ remaining, role = role)
          case VmInstruction.Reframe(cb, op, ca, role) =>
            VmInstruction.Reframe(cb, op, ca ++ remaining, role)
          case other => other
        out = out.updated(out.size - 1, last.copy(instruction = rewritten))
        frames = Vector.empty

    def leaf(kind: String, lexeme: String, role: Option[String], channel: TokenChannel): Unit =
      emit(kind, lexeme, VmInstruction.Emit(role), channel)

    def openBranch(branch: String, kind: String, lexeme: String, role: Option[String], channel: TokenChannel = TokenChannel.Syntax): Unit =
      emit(kind, lexeme, VmInstruction.Open(branch, role), channel)

    def close(branch: String, kind: String, lexeme: String, role: Option[String], channel: TokenChannel = TokenChannel.Syntax): Unit =
      emit(kind, lexeme, VmInstruction.Close(Some(branch), role), channel)

    // ── main line loop ────────────────────────────────────────────────────────

    def processLine(lines: Vector[MdLine], index: Int): Int =
      val line = lines(index)
      open match
        case OpenLeaf.FencedCode(fchar, flen) =>
          handleFenceBody(line, fchar, flen); index + 1
        case _ =>
          // match / adjust containers, get the content portion of the line
          val content = matchContainers(line)
          val indentWidth = MdChars.indentWidth(content)
          if content.forall(c => c == ' ' || c == '\t') then
            handleBlank(line, content)
            index + 1
          else dispatchLeaf(lines, index, line, content, indentWidth)

    /** Dispatch a non-blank content line to the right leaf handler. */
    def dispatchLeaf(
        lines: Vector[MdLine], index: Int, line: MdLine,
        content: String, indentWidth: Int,
    ): Int =
      val trimmed = content.substring(MdChars.indentPrefixLength(content))
      // indented code (4+ spaces) only when not continuing a paragraph
      if indentWidth >= 4 && open != OpenLeaf.Paragraph && !startsListOrQuote(trimmed) then
        handleIndentedCode(line); index + 1
      else if open == OpenLeaf.Paragraph && isSetextUnderline(trimmed) then
        // a setext underline takes precedence over a thematic break
        emitSetextUnderline(line); index + 1
      else if isThematicBreak(trimmed) then
        finishParagraph(); emitThematicBreak(line); index + 1
      else if startsAtxHeading(trimmed) then
        finishParagraph(); emitAtxHeading(line, content); index + 1
      else if startsFence(trimmed).isDefined then
        finishParagraph(); startFence(line, content, startsFence(trimmed).get); index + 1
      else if startsBlockquote(trimmed) then
        openBlockquoteAndReprocess(lines, index, line, content)
      else if startsListItem(trimmed).isDefined then
        openListItemAndReprocess(lines, index, line, content, startsListItem(trimmed).get)
      else if indentWidth < 4 && htmlBlockType(trimmed, open == OpenLeaf.Paragraph).isDefined then
        // types 1-6 interrupt a paragraph; type 7 does not (returns None when open)
        val ht = htmlBlockType(trimmed, open == OpenLeaf.Paragraph).get
        finishParagraph(); handleHtmlBlock(lines, index, ht)
      else if open != OpenLeaf.Paragraph && isRefDefLine(content) then
        emitDefinition(line, content); index + 1
      else if gfm && open != OpenLeaf.Paragraph && isTableStart(lines, index, content) then
        emitTable(lines, index)
      else
        appendParagraph(line, content); index + 1

    // ── container matching ────────────────────────────────────────────────────

    /** Consumes as many open-container prefixes as this line satisfies, emitting
      * their exact marker/indent tokens, and returns the remaining content.
      * Unmatched trailing containers are scheduled to close (no lazy-continuation
      * support in M4). */
    def matchContainers(line: MdLine): String =
      var rest = line.content
      var matched = 0
      var done = false
      var i = 0
      // When a paragraph is open, its container-continuation markers must be
      // emitted in source order *with* the deferred paragraph text, so we buffer
      // them here instead of emitting them ahead of the text.
      val buffering = open == OpenLeaf.Paragraph
      var prefix: Vector[String] = Vector.empty
      def consume(kind: String, lex: String): Unit =
        if buffering then prefix = prefix :+ lex
        else if kind == MdKind.BlockquoteMarker then emitContainerMarker(kind, lex)
        else emitContainerIndent(lex)
      while i < containers.size && !done do
        containers(i) match
          case _: Blockquote =>
            stripBlockquoteMarker(rest) match
              case Some((marker, remainder)) =>
                consume(MdKind.BlockquoteMarker, marker)
                rest = remainder
                matched += 1
              case None => done = true
          case item: ListItemFrame =>
            if MdChars.indentWidth(rest) >= item.contentIndent || rest.forall(c => c == ' ' || c == '\t') then
              val take = consumeIndent(rest, item.contentIndent)
              if take.nonEmpty then consume(MdKind.Indent, take)
              rest = rest.substring(take.length)
              matched += 1
            else done = true
          case _: ListFrame => matched += 1 // list frame matches whenever its item does
        i += 1
      if matched >= containers.size then
        // full match: hand a continuation prefix to appendParagraph, if buffering
        if buffering then paragraphPendingPrefix = prefix.mkString
      else if buffering && isLazyContinuation(rest) then
        // lazy paragraph continuation: a plain paragraph-text line continues the
        // open paragraph even though a container marker is missing; keep the
        // unmatched containers open so the paragraph stays inside them
        paragraphPendingPrefix = prefix.mkString
      else
        // fewer containers matched — the paragraph (if any) ends here
        finishParagraph()
        if prefix.nonEmpty then flushPending(MdKind.Indent, prefix.mkString, Vector.empty, Some("continuation"), TokenChannel.Trivia)
        scheduleContainerClose(matched)
      rest

    def scheduleContainerClose(keep: Int): Unit =
      finishParagraph()
      while containers.size > keep do
        val ctr = containers.last
        containers = containers.dropRight(1)
        pendingClose = pendingClose :+ ctr.frame

    def emitContainerMarker(kind: String, lexeme: String): Unit =
      flushPending(kind, lexeme, Vector.empty, Some("marker"), TokenChannel.Syntax)

    def emitContainerIndent(lexeme: String): Unit =
      flushPending(MdKind.Indent, lexeme, Vector.empty, Some("continuation"), TokenChannel.Trivia)

    // ── blank lines ────────────────────────────────────────────────────────

    def handleBlank(line: MdLine, content: String): Unit =
      finishParagraph()
      val lexeme = content + line.ending
      if lexeme.nonEmpty then flushPending(MdKind.Blank, lexeme, Vector.empty, Some("blank"), TokenChannel.Trivia)

    // ── paragraphs ────────────────────────────────────────────────────────

    def appendParagraph(line: MdLine, content: String): Unit =
      if open != OpenLeaf.Paragraph then
        open = OpenLeaf.Paragraph
        // the first line's marker was already emitted by the container opener
        paragraphSegs = Vector(ParaSeg("", content, line.ending))
      else
        paragraphSegs = paragraphSegs :+ ParaSeg(paragraphPendingPrefix, content, line.ending)
      paragraphPendingPrefix = ""

    def finishParagraph(): Unit =
      if open == OpenLeaf.Paragraph then
        val segs = paragraphSegs
        open = OpenLeaf.None
        paragraphSegs = Vector.empty
        // inline content is the de-prefixed lines joined by their exact endings —
        // no container markers, so multi-line inline spans resolve cleanly
        val content = segs.iterator.map(s => s.content + s.ending).mkString
        val pieces = MarkdownInlines.parse(content, refs, profile)
        emitParagraphWithSegments(pieces, segs)
      // a continuation prefix buffered for a line that turned out to start a new
      // block (not continue the paragraph) is emitted here so nothing is lost
      if paragraphPendingPrefix.nonEmpty then
        flushPending(MdKind.Indent, paragraphPendingPrefix, Vector.empty, Some("continuation"), TokenChannel.Trivia)
        paragraphPendingPrefix = ""

    /** Emits the paragraph's inline pieces wrapped in a paragraph frame, splicing
      * each line's continuation prefix back in as trivia at its source position —
      * i.e. right after the soft/hard break that ends the preceding line. The
      * k-th break in the stream ends segment k, so segment k+1's prefix follows. */
    def emitParagraphWithSegments(pieces: Vector[InlinePiece], segs: Vector[ParaSeg]): Unit =
      if pieces.nonEmpty then
        val n = pieces.size
        var i = 0
        var breakCount = 0
        while i < n do
          val piece = pieces(i)
          if n == 1 then emitFirstLast(MdBranch.Paragraph, piece, Some("content"))
          else if i == 0 then emitFirst(MdBranch.Paragraph, piece, Some("content"))
          else if i == n - 1 then emitLast(MdBranch.Paragraph, piece)
          else replay(piece)
          if isBreakPiece(piece) then
            breakCount += 1
            if breakCount < segs.size && segs(breakCount).prefix.nonEmpty then
              leaf(MdKind.Indent, segs(breakCount).prefix, Some("continuation"), TokenChannel.Trivia)
          i += 1

    // ── ATX headings ────────────────────────────────────────────────────────

    def emitAtxHeading(line: MdLine, content: String): Unit =
      val lead = MdChars.indentPrefixLength(content)
      if lead > 0 then flushPending(MdKind.Indent, content.substring(0, lead), Vector.empty, Some("indent"), TokenChannel.Trivia)
      val body = content.substring(lead)
      var h = 0
      while h < body.length && body.charAt(h) == '#' do h += 1
      val marker = body.substring(0, h)
      // open heading on the marker
      flushPending(MdKind.AtxMarker, marker, Vector(FrameSpec(MdBranch.Heading)), Some("marker"), TokenChannel.Syntax)
      var rest = body.substring(h)
      // leading spaces of the heading text
      val restLead = rest.takeWhile(c => c == ' ' || c == '\t')
      if restLead.nonEmpty then leaf(MdKind.Indent, restLead, Some("space"), TokenChannel.Trivia)
      rest = rest.substring(restLead.length)
      // optional closing sequence of #'s
      val (rawText, closing) = splitAtxClosing(rest)
      // trailing whitespace of the heading text is trivia, not content
      var textEnd = rawText.length
      while textEnd > 0 && (rawText.charAt(textEnd - 1) == ' ' || rawText.charAt(textEnd - 1) == '\t') do textEnd -= 1
      val hText = rawText.substring(0, textEnd)
      val trailWs = rawText.substring(textEnd)
      val pieces = MarkdownInlines.parse(hText, refs, profile)
      pieces.foreach(replay)
      if trailWs.nonEmpty then leaf(MdKind.Indent, trailWs, Some("space"), TokenChannel.Trivia)
      if closing.nonEmpty then leaf(MdKind.AtxClose, closing, Some("close"), TokenChannel.Syntax)
      // close heading on the line ending (or dangling at EOF)
      if line.ending.nonEmpty then close(MdBranch.Heading, MdKind.LineBreak, line.ending, Some("trailing"), TokenChannel.Trivia)

    // ── setext headings ────────────────────────────────────────────────────

    def emitSetextUnderline(line: MdLine): Unit =
      // reinterpret the just-open paragraph as a setext heading. Setext headings
      // are almost always single-line; for the rare multi-line-in-container case
      // the continuation prefix is woven into the text (kept lossless).
      val segs = paragraphSegs
      open = OpenLeaf.None
      paragraphSegs = Vector.empty
      val content = segs.iterator.zipWithIndex.map { (s, idx) =>
        val pfx = if idx == 0 then "" else s.prefix
        val end = if idx == segs.size - 1 then "" else s.ending
        pfx + s.content + end
      }.mkString
      val interior = segs.lastOption.map(_.ending).getOrElse("")
      val pieces = MarkdownInlines.parse(content, refs, profile)
      if pieces.isEmpty then
        flushPending(MdKind.SetextUnderline, line.content, Vector(FrameSpec(MdBranch.Heading)), Some("underline"), TokenChannel.Syntax)
        if line.ending.nonEmpty then close(MdBranch.Heading, MdKind.LineBreak, line.ending, Some("trailing"), TokenChannel.Trivia)
      else
        emitInlineOpenOnly(MdBranch.Heading, pieces)
        // the newline that ended the heading text, then the underline line
        if interior.nonEmpty then leaf(MdKind.SoftBreak, interior, Some("space"), TokenChannel.Trivia)
        if line.ending.nonEmpty then
          leaf(MdKind.SetextUnderline, line.content, Some("underline"), TokenChannel.Syntax)
          close(MdBranch.Heading, MdKind.LineBreak, line.ending, Some("trailing"), TokenChannel.Trivia)
        else
          close(MdBranch.Heading, MdKind.SetextUnderline, line.content, Some("underline"), TokenChannel.Syntax)

    // ── thematic break ────────────────────────────────────────────────────

    def emitThematicBreak(line: MdLine): Unit =
      flushPending(MdKind.ThematicMarker, line.content, Vector(FrameSpec(MdBranch.ThematicBreak)), Some("marker"), TokenChannel.Syntax)
      if line.ending.nonEmpty then close(MdBranch.ThematicBreak, MdKind.LineBreak, line.ending, Some("trailing"), TokenChannel.Trivia)
      else pendingClose = pendingClose :+ MdBranch.ThematicBreak

    // ── fenced code ────────────────────────────────────────────────────────

    def startFence(line: MdLine, content: String, fence: (Char, Int)): Unit =
      val lead = MdChars.indentPrefixLength(content)
      if lead > 0 then flushPending(MdKind.Indent, content.substring(0, lead), Vector.empty, Some("indent"), TokenChannel.Trivia)
      val body = content.substring(lead)
      val flen = fence._2
      val fenceLex = body.substring(0, flen)
      flushPending(MdKind.FenceOpen, fenceLex, Vector(FrameSpec(MdBranch.CodeBlock)), Some("fence.open"), TokenChannel.Syntax)
      val info = body.substring(flen)
      if info.nonEmpty then leaf(MdKind.Info, info, Some("info"), TokenChannel.Embedded)
      if line.ending.nonEmpty then leaf(MdKind.LineBreak, line.ending, Some("trailing"), TokenChannel.Trivia)
      open = OpenLeaf.FencedCode(fence._1, flen)

    def handleFenceBody(line: MdLine, fchar: Char, flen: Int): Unit =
      val trimmed = line.content.substring(MdChars.indentPrefixLength(line.content))
      val closes = trimmed.nonEmpty && trimmed.forall(_ == fchar) && countRun(trimmed, fchar) >= flen &&
        MdChars.indentWidth(line.content) <= 3
      if closes then
        open = OpenLeaf.None
        val lead = MdChars.indentPrefixLength(line.content)
        if lead > 0 then leaf(MdKind.Indent, line.content.substring(0, lead), Some("indent"), TokenChannel.Trivia)
        close(MdBranch.CodeBlock, MdKind.FenceClose, line.content.substring(lead), Some("fence.close"), TokenChannel.Syntax)
        if line.ending.nonEmpty then leaf(MdKind.LineBreak, line.ending, Some("trailing"), TokenChannel.Trivia)
      else
        if line.content.nonEmpty then leaf(MdKind.CodeContent, line.content, Some("code"), TokenChannel.Embedded)
        if line.ending.nonEmpty then leaf(MdKind.LineBreak, line.ending, Some("code"), TokenChannel.Embedded)

    // ── indented code ────────────────────────────────────────────────────────

    def handleIndentedCode(line: MdLine): Unit =
      // one code line; consecutive indented lines each open/emit/close for M4 simplicity
      flushPending(MdKind.CodeContent, line.content, Vector(FrameSpec(MdBranch.CodeBlock)), Some("code"), TokenChannel.Embedded)
      if line.ending.nonEmpty then close(MdBranch.CodeBlock, MdKind.LineBreak, line.ending, Some("trailing"), TokenChannel.Trivia)
      else pendingClose = pendingClose :+ MdBranch.CodeBlock

    // ── HTML blocks ────────────────────────────────────────────────────────

    def handleHtmlBlock(lines: Vector[MdLine], index: Int, htmlType: Int): Int =
      // types 1-5 end at (and include) a line containing their close marker;
      // types 6-7 end before a blank line. End of input ends any block.
      val endMarkers: Option[Vector[String]] = htmlType match
        case 1 => Some(Vector("</script>", "</pre>", "</style>", "</textarea>"))
        case 2 => Some(Vector("-->"))
        case 3 => Some(Vector("?>"))
        case 4 => Some(Vector(">"))
        case 5 => Some(Vector("]]>"))
        case _ => None
      var i = index
      var first = true
      var done = false
      while i < lines.size && !done do
        val l = lines(i)
        endMarkers match
          case None =>
            if l.isBlank then done = true
            else { emitHtmlLine(l, first); first = false; i += 1 }
          case Some(markers) =>
            emitHtmlLine(l, first); first = false; i += 1
            val lc = l.content.toLowerCase
            if markers.exists(lc.contains) then done = true
      pendingClose = pendingClose :+ MdBranch.HtmlBlock
      i

    def emitHtmlLine(l: MdLine, first: Boolean): Unit =
      if first then flushPending(MdKind.Html, l.content, Vector(FrameSpec(MdBranch.HtmlBlock)), Some("html"), TokenChannel.Embedded)
      else if l.content.nonEmpty then leaf(MdKind.Html, l.content, Some("html"), TokenChannel.Embedded)
      if l.ending.nonEmpty then leaf(MdKind.LineBreak, l.ending, Some("html"), TokenChannel.Embedded)

    // ── GFM tables ────────────────────────────────────────────────────────

    def emitTable(lines: Vector[MdLine], index: Int): Int =
      // open the table on the header row's content
      val header = lines(index)
      flushPending(MdKind.TableRow, header.content, Vector(FrameSpec(MdBranch.Table)), Some("header"), TokenChannel.Syntax)
      if header.ending.nonEmpty then leaf(MdKind.LineBreak, header.ending, Some("trailing"), TokenChannel.Trivia)
      val delim = lines(index + 1)
      leaf(MdKind.TableDelim, delim.content, Some("delimiter"), TokenChannel.Syntax)
      if delim.ending.nonEmpty then leaf(MdKind.LineBreak, delim.ending, Some("trailing"), TokenChannel.Trivia)
      var i = index + 2
      while i < lines.size && !lines(i).isBlank && lines(i).content.contains('|') do
        leaf(MdKind.TableRow, lines(i).content, Some("row"), TokenChannel.Syntax)
        if lines(i).ending.nonEmpty then leaf(MdKind.LineBreak, lines(i).ending, Some("trailing"), TokenChannel.Trivia)
        i += 1
      // close the table on the next structural token (or at EOF via closeDangling)
      pendingClose = pendingClose :+ MdBranch.Table
      i

    // ── block quotes & lists (containers) ─────────────────────────────────────

    def openBlockquoteAndReprocess(
        lines: Vector[MdLine], index: Int, line: MdLine, content: String): Int =
      finishParagraph()
      val stripped = stripBlockquoteMarker(content).get
      val lead = MdChars.indentPrefixLength(content)
      if lead > 0 && !stripped._1.startsWith(content.substring(0, lead)) then ()
      flushPending(MdKind.BlockquoteMarker, stripped._1, Vector(FrameSpec(MdBranch.Blockquote)), Some("marker"), TokenChannel.Syntax)
      containers = containers :+ Blockquote()
      // reprocess the remainder of the line as inner content by rebuilding a line
      val innerLine = MdLine(stripped._2, line.ending)
      reprocessInner(lines, index, innerLine)

    def openListItemAndReprocess(
        lines: Vector[MdLine], index: Int, line: MdLine, content: String,
        item: (Boolean, String, Int)): Int =
      finishParagraph()
      val lead = MdChars.indentPrefixLength(content)
      val (ordered, marker, contentIndent) = item
      // open a list frame if the parent isn't already the matching list
      val needList = !containers.lastOption.exists {
        case lf: ListFrame => lf.ordered == ordered
        case _             => false
      }
      var opens: Vector[FrameSpec] = Vector.empty
      // a list of a different marker type ends the current sibling list; close it
      // so the new list is a sibling, not nested inside the old frame
      if needList then containers.lastOption match
        case Some(lf: ListFrame) =>
          containers = containers.dropRight(1)
          pendingClose = pendingClose :+ lf.frame
        case _ => ()
      if lead > 0 then flushPending(MdKind.Indent, content.substring(0, lead), Vector.empty, Some("indent"), TokenChannel.Trivia)
      if needList then
        opens = opens :+ FrameSpec(MdBranch.List)
        containers = containers :+ ListFrame(ordered)
      opens = opens :+ FrameSpec(MdBranch.ListItem)
      containers = containers :+ ListItemFrame(ordered, contentIndent + lead)
      val body = content.substring(lead)
      val markerLex = body.substring(0, marker.length)
      flushPending(MdKind.ListMarker, markerLex, opens, Some("marker"), TokenChannel.Syntax)
      // GFM task marker
      var remainder = body.substring(marker.length)
      if gfm && (remainder.startsWith("[ ] ") || remainder.startsWith("[x] ") || remainder.startsWith("[X] ")) then
        leaf(MdKind.TaskMarker, remainder.substring(0, 3), Some("task"), TokenChannel.Syntax)
        remainder = remainder.substring(3)
      val innerLine = MdLine(remainder, line.ending)
      reprocessInner(lines, index, innerLine)

    /** Re-runs leaf detection on the content remaining after a container marker
      * on the same physical line. */
    def reprocessInner(lines: Vector[MdLine], index: Int, innerLine: MdLine): Int =
      val content = innerLine.content
      val indentWidth = MdChars.indentWidth(content)
      if innerLine.isBlank then
        finishParagraph()
        if innerLine.ending.nonEmpty then leaf(MdKind.LineBreak, innerLine.ending, Some("blank"), TokenChannel.Trivia)
        index + 1
      else dispatchLeaf(lines, index, innerLine, content, indentWidth)

    // ── ScalaScript YAML front matter ─────────────────────────────────────────

    def scanFrontMatter(lines: Vector[MdLine], index: Int): Int =
      if index < lines.size && lines(index).content == "---" then
        var i = index + 1
        while i < lines.size && lines(i).content != "---" && lines(i).content != "..." do i += 1
        if i < lines.size then
          // open front-matter on the opening fence
          openBranch(MdBranch.FrontMatter, MdKind.FrontMatterFence, lines(index).content, Some("open"), TokenChannel.Syntax)
          if lines(index).ending.nonEmpty then leaf(MdKind.LineBreak, lines(index).ending, Some("open"), TokenChannel.Trivia)
          var j = index + 1
          while j < i do
            if lines(j).content.nonEmpty then leaf(MdKind.CodeContent, lines(j).content, Some("yaml"), TokenChannel.Embedded)
            if lines(j).ending.nonEmpty then leaf(MdKind.LineBreak, lines(j).ending, Some("yaml"), TokenChannel.Embedded)
            j += 1
          close(MdBranch.FrontMatter, MdKind.FrontMatterFence, lines(i).content, Some("close"), TokenChannel.Syntax)
          if lines(i).ending.nonEmpty then leaf(MdKind.LineBreak, lines(i).ending, Some("close"), TokenChannel.Trivia)
          i + 1
        else index
      else index

    // ── link reference definitions ────────────────────────────────────────────

    def collectReferences(lines: Vector[MdLine]): Unit =
      var inFence = false
      var fenceChar = ' '
      var i = 0
      while i < lines.size do
        val content = lines(i).content
        val trimmed = content.substring(MdChars.indentPrefixLength(content))
        if inFence then
          if trimmed.nonEmpty && (trimmed.forall(_ == fenceChar)) && trimmed.length >= 3 then inFence = false
        else if trimmed.startsWith("```") || trimmed.startsWith("~~~") then
          inFence = true; fenceChar = trimmed.charAt(0)
        else if MdChars.indentWidth(content) < 4 && trimmed.startsWith("[") then
          parseReferenceDefinition(lines, i) match
            case Some((label, ref, consumed)) =>
              val norm = MarkdownInlines.normalizeLabel(label)
              if !refs.contains(norm) && refs.size < limits.maxReferences then refs = refs + (norm -> ref)
              i = consumed - 1
            case None => ()
        i += 1

    def emitDefinition(line: MdLine, content: String): Unit =
      val start = MdChars.indentPrefixLength(content)
      if start > 0 then flushPending(MdKind.Indent, content.substring(0, start), Vector.empty, Some("indent"), TokenChannel.Trivia)
      val closeIdx = content.indexOf(']', start + 1)
      val labelTok = content.substring(start, closeIdx + 1)
      flushPending(MdKind.ReferenceLabel, labelTok, Vector(FrameSpec(MdBranch.Definition)), Some("label"), TokenChannel.Syntax)
      leaf(MdKind.Colon, ":", Some("colon"), TokenChannel.Syntax)
      val after = content.substring(closeIdx + 2)
      var i = 0
      while i < after.length && (after.charAt(i) == ' ' || after.charAt(i) == '\t') do i += 1
      if i > 0 then leaf(MdKind.Indent, after.substring(0, i), Some("space"), TokenChannel.Trivia)
      val destStart = i
      if i < after.length && after.charAt(i) == '<' then
        val end = after.indexOf('>', i + 1)
        i = if end < 0 then after.length else end + 1
      else
        while i < after.length && after.charAt(i) != ' ' && after.charAt(i) != '\t' do i += 1
      leaf(MdKind.Destination, after.substring(destStart, i), Some("destination"), TokenChannel.Syntax)
      val tail = after.substring(i)
      if tail.trim.nonEmpty then
        val ws = tail.takeWhile(c => c == ' ' || c == '\t')
        if ws.nonEmpty then leaf(MdKind.Indent, ws, Some("space"), TokenChannel.Trivia)
        leaf(MdKind.Title, tail.substring(ws.length), Some("title"), TokenChannel.Syntax)
      else if tail.nonEmpty then leaf(MdKind.Indent, tail, Some("space"), TokenChannel.Trivia)
      if line.ending.nonEmpty then close(MdBranch.Definition, MdKind.LineBreak, line.ending, Some("trailing"), TokenChannel.Trivia)
      else pendingClose = pendingClose :+ MdBranch.Definition

    // ── emission helpers ────────────────────────────────────────────────────

    /** Open `branch` on the first piece only (used by setext where the underline
      * closes the frame explicitly). */
    def emitInlineOpenOnly(branch: String, pieces: Vector[InlinePiece]): Unit =
      if pieces.isEmpty then ()
      else
        emitFirst(branch, pieces.head, Some("content"))
        var i = 1
        while i < pieces.size do { replay(pieces(i)); i += 1 }

    def emitFirst(branch: String, piece: InlinePiece, role: Option[String]): Unit = piece match
      case InlinePiece.Tok(kind, lex, r, ch) =>
        flushPending(kind, lex, Vector(FrameSpec(branch)), r.orElse(role), ch)
      case InlinePiece.Open(b2, kind, lex, r) =>
        flushPending(kind, lex, Vector(FrameSpec(branch), FrameSpec(b2)), r, TokenChannel.Syntax)
      case InlinePiece.Close(_, kind, lex, r) =>
        // shouldn't be first; emit as close of branch to stay balanced
        flushPending(kind, lex, Vector(FrameSpec(branch)), r, TokenChannel.Syntax)

    def emitLast(branch: String, piece: InlinePiece): Unit = piece match
      case InlinePiece.Tok(kind, lex, r, ch) =>
        emit(kind, lex, VmInstruction.Reframe(closeBefore = Vector.empty, open = Vector.empty, closeAfter = Vector(branch), role = r), ch)
      case InlinePiece.Close(b2, kind, lex, r) =>
        emit(kind, lex, VmInstruction.Reframe(closeBefore = Vector.empty, open = Vector.empty, closeAfter = Vector(b2, branch), role = r), TokenChannel.Syntax)
      case InlinePiece.Open(b2, kind, lex, r) =>
        // degenerate trailing open; open then rely on closeDangling
        emit(kind, lex, VmInstruction.Open(b2, r), TokenChannel.Syntax)

    def emitFirstLast(branch: String, piece: InlinePiece, role: Option[String]): Unit = piece match
      case InlinePiece.Tok(kind, lex, r, ch) =>
        emit(kind, lex, foldPending(VmInstruction.Reframe(closeBefore = Vector.empty, open = Vector(FrameSpec(branch)), closeAfter = Vector(branch), role = r.orElse(role))), ch)
      case InlinePiece.Open(b2, kind, lex, r) =>
        emit(kind, lex, foldPending(VmInstruction.Reframe(closeBefore = Vector.empty, open = Vector(FrameSpec(branch), FrameSpec(b2)), closeAfter = Vector(b2, branch), role = r)), TokenChannel.Syntax)
      case InlinePiece.Close(b2, kind, lex, r) =>
        emit(kind, lex, foldPending(VmInstruction.Reframe(closeBefore = Vector.empty, open = Vector(FrameSpec(branch)), closeAfter = Vector(b2, branch), role = r)), TokenChannel.Syntax)

    /** Replay a resolved inline piece as its plain VM instruction. */
    def replay(piece: InlinePiece): Unit = piece match
      case InlinePiece.Tok(kind, lex, role, ch) => leaf(kind, lex, role, ch)
      case InlinePiece.Open(b2, kind, lex, role) => openBranch(b2, kind, lex, role)
      case InlinePiece.Close(b2, kind, lex, role) => close(b2, kind, lex, role)

    /** Emit a token that also applies any scheduled container closures (as a
      * `Reframe` closeBefore) plus the given `opens` frames. */
    def flushPending(kind: String, lexeme: String, opens: Vector[FrameSpec], role: Option[String], channel: TokenChannel): Unit =
      if lexeme.isEmpty then
        // can't attach transitions to nothing; keep pending for the next token
        ()
      else if pendingClose.isEmpty && opens.isEmpty then
        leaf(kind, lexeme, role, channel)
      else
        val instr = VmInstruction.Reframe(closeBefore = pendingClose, open = opens, closeAfter = Vector.empty, role = role)
        pendingClose = Vector.empty
        emit(kind, lexeme, instr, channel)

    def foldPending(reframe: VmInstruction.Reframe): VmInstruction =
      val folded = reframe.copy(closeBefore = pendingClose ++ reframe.closeBefore)
      pendingClose = Vector.empty
      folded

    def finishOpenBlocks(): Unit =
      finishParagraph()
      open match
        case OpenLeaf.FencedCode(_, _) =>
          // unterminated fence: close it, record a diagnostic
          diagnostics = diagnostics :+ Diagnostic(
            code = "uniml.markdown.unterminated-fence",
            message = "fenced code block was not closed before end of input",
            severity = Severity.Warning,
            span = None,
            dialect = Some("markdown"),
          )
          open = OpenLeaf.None
        case _ => ()

    // ── driver ────────────────────────────────────────────────────────────────

    if Unicode.codePointCount(text) > limits.maxSourceCodePoints then
      MarkdownBlockResult(Vector.empty, Vector(limitDiag("uniml.markdown.limit.source",
        s"Markdown source exceeds the ${limits.maxSourceCodePoints} code-point limit")))
    else
      val lines = MdLine.split(text)
      collectReferences(lines)
      var index = 0
      // ScalaScript YAML front matter, only at the very start
      if scala then index = scanFrontMatter(lines, index)
      while index < lines.size do
        index = processLine(lines, index)
      finishOpenBlocks()
      closeDangling()
      MarkdownBlockResult(out, diagnostics)

  // ── pure classifiers / helpers (no parsing state) ─────────────────────────

  private def isLazyContinuation(rest: String): Boolean =
    if rest.forall(c => c == ' ' || c == '\t') then false
    else
      val t = rest.substring(MdChars.indentPrefixLength(rest))
      !(startsAtxHeading(t) || isThematicBreak(t) || startsFence(t).isDefined ||
        startsBlockquote(t) || startsListItem(t).isDefined ||
        htmlBlockType(t, paragraphOpen = true).isDefined || isSetextUnderline(t))

  private def stripBlockquoteMarker(content: String): Option[(String, String)] =
    val lead = MdChars.indentPrefixLength(content)
    if MdChars.indentWidth(content) <= 3 && lead < content.length && content.charAt(lead) == '>' then
      var end = lead + 1
      if end < content.length && content.charAt(end) == ' ' then end += 1
      else if end < content.length && content.charAt(end) == '\t' then end += 1
      Some((content.substring(0, end), content.substring(end)))
    else None

  private def consumeIndent(content: String, columns: Int): String =
    var col = 0
    var i = 0
    while i < content.length && col < columns && (content.charAt(i) == ' ' || content.charAt(i) == '\t') do
      col += (if content.charAt(i) == '\t' then 4 - (col % 4) else 1)
      i += 1
    content.substring(0, i)

  private def isBreakPiece(piece: InlinePiece): Boolean = piece match
    case InlinePiece.Tok(kind, _, _, _) => kind == MdKind.SoftBreak || kind == MdKind.HardBreak
    case _                              => false

  private def startsAtxHeading(trimmed: String): Boolean =
    var i = 0
    while i < trimmed.length && trimmed.charAt(i) == '#' do i += 1
    i >= 1 && i <= 6 && (i == trimmed.length || trimmed.charAt(i) == ' ' || trimmed.charAt(i) == '\t')

  private def splitAtxClosing(rest: String): (String, String) =
    // trailing run of #'s preceded by a space is the optional closing sequence
    var end = rest.length
    while end > 0 && (rest.charAt(end - 1) == ' ' || rest.charAt(end - 1) == '\t') do end -= 1
    var hashEnd = end
    while hashEnd > 0 && rest.charAt(hashEnd - 1) == '#' do hashEnd -= 1
    if hashEnd < end && (hashEnd == 0 || rest.charAt(hashEnd - 1) == ' ' || rest.charAt(hashEnd - 1) == '\t') then
      (rest.substring(0, hashEnd), rest.substring(hashEnd))
    else (rest, "")

  private def isSetextUnderline(trimmed: String): Boolean =
    trimmed.nonEmpty && (trimmed.forall(_ == '=') || trimmed.forall(_ == '-')) &&
      { val t = trimmed.trim; t.nonEmpty && (t.forall(_ == '=') || t.forall(_ == '-')) }

  private def isThematicBreak(trimmed: String): Boolean =
    val stripped = trimmed.filter(c => c != ' ' && c != '\t')
    stripped.length >= 3 && (stripped.forall(_ == '*') || stripped.forall(_ == '-') || stripped.forall(_ == '_'))

  private def startsFence(trimmed: String): Option[(Char, Int)] =
    if trimmed.startsWith("```") then Some(('`', countRun(trimmed, '`')))
    else if trimmed.startsWith("~~~") then Some(('~', countRun(trimmed, '~')))
    else None

  private def countRun(s: String, c: Char): Int =
    var i = 0
    while i < s.length && s.charAt(i) == c do i += 1
    i

  // CommonMark type-6 HTML block tag names.
  private val htmlBlock6Tags: Set[String] = Set(
    "address", "article", "aside", "base", "basefont", "blockquote", "body", "caption", "center",
    "col", "colgroup", "dd", "details", "dialog", "dir", "div", "dl", "dt", "fieldset", "figcaption",
    "figure", "footer", "form", "frame", "frameset", "h1", "h2", "h3", "h4", "h5", "h6", "head",
    "header", "hr", "html", "iframe", "legend", "li", "link", "main", "menu", "menuitem", "nav",
    "noframes", "ol", "optgroup", "option", "p", "param", "section", "summary", "table", "tbody",
    "td", "tfoot", "th", "thead", "title", "tr", "track", "ul",
  )
  private val htmlType1Tags = Vector("script", "pre", "style", "textarea")

  /** Classifies the CommonMark HTML block type (1..7) a line starts, or None.
    * Type 7 (a bare complete tag) cannot interrupt a paragraph. */
  private def htmlBlockType(trimmed: String, paragraphOpen: Boolean): Option[Int] =
    if !trimmed.startsWith("<") then None
    else
      val lower = trimmed.toLowerCase
      if htmlType1Start(lower) then Some(1)
      else if trimmed.startsWith("<!--") then Some(2)
      else if trimmed.startsWith("<?") then Some(3)
      else if trimmed.startsWith("<![CDATA[") then Some(5)
      else if trimmed.startsWith("<!") && trimmed.length > 2 && MdChars.isAsciiLetter(trimmed.charAt(2)) then Some(4)
      else if htmlType6Start(trimmed) then Some(6)
      else if !paragraphOpen && htmlType7Start(trimmed) then Some(7)
      else None

  private def htmlType1Start(lower: String): Boolean =
    htmlType1Tags.exists { tag =>
      lower.startsWith("<" + tag) && {
        val next = 1 + tag.length
        next >= lower.length || { val c = lower.charAt(next); c == ' ' || c == '\t' || c == '>' }
      }
    }

  private def htmlTagName(t: String): (String, Int) =
    var i = 1
    if i < t.length && t.charAt(i) == '/' then i += 1
    val start = i
    while i < t.length && (MdChars.isAsciiAlnum(t.charAt(i)) || t.charAt(i) == '-') do i += 1
    (t.substring(start, i).toLowerCase, i)

  private def htmlType6Start(t: String): Boolean =
    val (name, after) = htmlTagName(t)
    htmlBlock6Tags.contains(name) && (after >= t.length || {
      val c = t.charAt(after); c == ' ' || c == '\t' || c == '>' || c == '/'
    })

  private def htmlType7Start(t: String): Boolean =
    val (name, _) = htmlTagName(t)
    if name.isEmpty || htmlType1Tags.contains(name) then false
    else completeTagLength(t) match
      case Some(len) => t.substring(len).forall(c => c == ' ' || c == '\t')
      case None      => false

  /** Length of a single complete open/close HTML tag at position 0, else None. */
  private def completeTagLength(t: String): Option[Int] =
    val n = t.length
    if n < 2 || t.charAt(0) != '<' then None
    else
      var i = 1
      if i < n && t.charAt(i) == '/' then i += 1
      if i >= n || !MdChars.isAsciiLetter(t.charAt(i)) then None
      else
        while i < n && (MdChars.isAsciiAlnum(t.charAt(i)) || t.charAt(i) == '-') do i += 1
        // after the tag name only whitespace, '/', or '>' may follow (so e.g.
        // an autolink "<https://x>" is not a tag: ':' is not valid here)
        if i < n && !(t.charAt(i) == ' ' || t.charAt(i) == '\t' || t.charAt(i) == '/' || t.charAt(i) == '>') then None
        else
          var ok = true
          while ok && i < n && t.charAt(i) != '>' do
            if t.charAt(i) == '<' then ok = false else i += 1
          if !ok || i >= n || t.charAt(i) != '>' then None else Some(i + 1)

  private def isTableStart(lines: Vector[MdLine], index: Int, content: String): Boolean =
    content.contains('|') && index + 1 < lines.size && isTableDelimiter(lines(index + 1).content)

  private def isTableDelimiter(content: String): Boolean =
    val t = content.trim
    t.nonEmpty && t.forall(c => c == '|' || c == '-' || c == ':' || c == ' ' || c == '\t') &&
      t.contains('-') && t.count(_ == '|') >= 1

  private def startsBlockquote(trimmed: String): Boolean = trimmed.startsWith(">")

  private def startsListOrQuote(trimmed: String): Boolean =
    startsBlockquote(trimmed) || startsListItem(trimmed).isDefined

  private def startsListItem(trimmed: String): Option[(Boolean, String, Int)] =
    if trimmed.isEmpty then None
    else
      val c = trimmed.charAt(0)
      if (c == '-' || c == '+' || c == '*') && (trimmed.length == 1 || trimmed.charAt(1) == ' ' || trimmed.charAt(1) == '\t') then
        val spaces = trimmed.drop(1).takeWhile(ch => ch == ' ' || ch == '\t')
        Some((false, trimmed.substring(0, 1 + spaces.length), 1 + math.max(spaces.length, 1)))
      else
        var i = 0
        while i < trimmed.length && i < 9 && MdChars.isAsciiDigit(trimmed.charAt(i)) do i += 1
        if i >= 1 && i < trimmed.length && (trimmed.charAt(i) == '.' || trimmed.charAt(i) == ')') &&
          (i + 1 == trimmed.length || trimmed.charAt(i + 1) == ' ' || trimmed.charAt(i + 1) == '\t') then
          val markerCore = trimmed.substring(0, i + 1)
          val spaces = trimmed.drop(i + 1).takeWhile(ch => ch == ' ' || ch == '\t')
          Some((true, markerCore + spaces, markerCore.length + math.max(spaces.length, 1)))
        else None

  private def parseReferenceDefinition(lines: Vector[MdLine], index: Int): Option[(String, LinkRef, Int)] =
    val content = lines(index).content
    val start = MdChars.indentPrefixLength(content)
    if start >= content.length || content.charAt(start) != '[' then return None
    val closeBracket = content.indexOf(']', start + 1)
    if closeBracket < 0 || closeBracket + 1 >= content.length || content.charAt(closeBracket + 1) != ':' then return None
    val label = content.substring(start + 1, closeBracket)
    if label.trim.isEmpty then return None
    val rest = content.substring(closeBracket + 2).trim
    if rest.isEmpty then return None // title-only continuation unsupported in M4
    val (dest, afterDest) =
      if rest.startsWith("<") then
        val end = rest.indexOf('>')
        if end < 0 then return None
        (rest.substring(1, end), rest.substring(end + 1).trim)
      else
        val sp = rest.indexWhere(c => c == ' ' || c == '\t')
        if sp < 0 then (rest, "") else (rest.substring(0, sp), rest.substring(sp).trim)
    val title =
      if afterDest.isEmpty then None
      else if (afterDest.startsWith("\"") && afterDest.endsWith("\"") && afterDest.length >= 2) ||
        (afterDest.startsWith("'") && afterDest.endsWith("'") && afterDest.length >= 2) then
        Some(afterDest.substring(1, afterDest.length - 1))
      else None
    Some((label, LinkRef(dest, title), index + 1))

  private def isRefDefLine(content: String): Boolean =
    val start = MdChars.indentPrefixLength(content)
    MdChars.indentWidth(content) < 4 && start < content.length && content.charAt(start) == '[' && {
      val close = content.indexOf(']', start + 1)
      close > start + 1 && close + 1 < content.length && content.charAt(close + 1) == ':' &&
        content.substring(close + 2).trim.nonEmpty
    }

  private def limitDiag(code: String, message: String): Diagnostic =
    Diagnostic(code, message, Severity.Fatal, None, Some("markdown"))
