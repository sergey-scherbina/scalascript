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
  * to live in a mutable `TokenSink` and mutable object fields) is one immutable
  * [[BlockState]] record threaded through `BlockState => BlockState` helpers; the
  * line loop is a tail-recursive walk and the pure classifiers stay class
  * methods. The reference map is not state at all: the pre-pass computes it once
  * and nothing writes it afterwards, so it is a plain parameter. */
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
  // An indented code block stays open across lines so a run of them coalesces
  // into ONE block (CommonMark 4.4), with interior blank lines held back until
  // another indented line proves they are interior rather than trailing.
  case IndentedCode

// One physical paragraph line: the stripped container-continuation prefix (a `> `
// marker or list indent; empty on the first line and lazy lines), the de-prefixed
// content, and the exact ending.
private[markdown] final case class ParaSeg(prefix: String, content: String, ending: String)

/** The whole block-pass state: the token cursor (was `TokenSink`) followed by the
  * block/container fields, one per former `var`, declaration order kept. All
  * state-threading records live at TOP LEVEL like [[OpenLeaf]] above — v2 doesn't
  * lower type decls nested in a plain-class body. */
private[markdown] final case class BlockState(
    // ── token cursor state ──
    pos: SourcePosition,
    nextId: Long,
    out: Vector[VmToken],
    frames: Vector[String],
    // ── block / container state ──
    diagnostics: Vector[Diagnostic],
    // `maxBlocks` counts BLOCK OPENINGS, which is what the limit's own doc promises to bound
    // ("every buffer, stack and delegated region"): a block opening is one frame pushed and one
    // branch in the tree, so it is the quantity that grows with a hostile document.
    blocksOpened: Int,
    blockLimitHit: Option[Diagnostic],
    // `maxFenceCodePoints` counts the BODY of the currently open fence. Reset on every
    // FenceOpen; only knowable during the parse, so a hit is recorded and reported at the end,
    // like the block limit — a truncated token stream inside a tree that looks complete is the
    // failure mode all these limits exist to avoid.
    fenceCp: Long,
    fenceLimitHit: Option[Diagnostic],
    containers: Vector[Container],
    // frames pending closure, to fold into the next emitted structural token
    pendingClose: Vector[String],
    // blank lines seen while an indented code block is open: interior until an
    // indented line follows, trailing if the block ends first
    indentedCodeBlanks: Vector[(String, String)],
    // container prefix of the CURRENT line, buffered while an indented code
    // block is open so it cannot overtake blank lines still being held
    indentedCodePendingPrefix: String,
    // leaf accumulation state
    open: OpenLeaf,
    paragraphSegs: Vector[ParaSeg],
    // container-continuation prefix stripped on the current line (set by
    // matchContainers), attached to the next appended segment
    paragraphPendingPrefix: String,
)

/** A state transition that also advanced the line index. */
private[markdown] final case class BlockStep(state: BlockState, index: Int)
/** `matchContainers`' result: the transition plus the de-prefixed content. */
private[markdown] final case class ContainerMatch(state: BlockState, rest: String)
/** The container-stack walk in flight: how many matched, and the buffered prefix. */
private[markdown] final case class ContainerScan(state: BlockState, rest: String, matched: Int, prefix: Vector[String])
/** One consumed container prefix: emitted into the state or buffered, never both. */
private[markdown] final case class ConsumeStep(state: BlockState, prefix: Vector[String])
/** The paragraph-emission fold: the k-th break ends segment k. */
private[markdown] final case class ParaEmitStep(state: BlockState, breakCount: Int)
/** `foldPending`'s result: the drained state and the widened instruction. */
private[markdown] final case class PendingFold(state: BlockState, instruction: VmInstruction)
/** The reference pre-pass in flight (fence tracking keeps definitions inside
  * code blocks from registering; paragraph tracking keeps continuations out). */
// `fenceChar` as Int CODE POINT: a Char field written from `charAt` inside the TCO loop
// missed the SscChar unwrap on the Rust lane (E0308)
private[markdown] final case class RefsAcc(inFence: Boolean, fenceChar: Int, inParagraph: Boolean, refs: Map[String, MarkdownInlines.LinkRef])

private[markdown] final class MarkdownBlocks(
    source: SourceId,
    profile: MarkdownProfile,
    limits: MarkdownLimits,
):
  private val gfm = profile == MarkdownProfile.Gfm
  private val scala = profile == MarkdownProfile.ScalaScript

  def parse(text: String): MarkdownBlockResult =
    if Unicode.codePointCount(text) > limits.maxSourceCodePoints then
      MarkdownBlockResult(Vector.empty, Vector(limitDiag("uniml.markdown.limit.source",
        s"Markdown source exceeds the ${limits.maxSourceCodePoints} code-point limit")))
    else
      val lines = MdLine.split(text)
      // A single line is one scanner pass and one inline buffer, so `maxLineCodePoints` bounds a
      // real allocation — and until now it bounded nothing: it was ACCEPTED and never read.
      val longest = lines.foldLeft(0)((m, l) => math.max(m, Unicode.codePointCount(l.content)))
      if longest > limits.maxLineCodePoints then
        MarkdownBlockResult(Vector.empty, Vector(limitDiag("uniml.markdown.limit.line",
          s"Markdown line exceeds the ${limits.maxLineCodePoints} code-point limit")))
      // A delimiter run (`***…`, `` ``` ``…) cannot cross a line ending, so the longest run in any
      // LINE bounds the longest run the inline lexer will ever walk — which is why this check can
      // live here, where `limits` already is, instead of threading limits through the inline
      // lexer's whole call surface. One O(line) pass, and only for runs of the three run chars.
      else if lines.exists(l => MdLine.longestRun(l.content) > limits.maxDelimiterRun) then
        MarkdownBlockResult(Vector.empty, Vector(limitDiag("uniml.markdown.limit.delimiter-run",
          s"delimiter run exceeds the ${limits.maxDelimiterRun} code-point limit")))
      else run(lines, collectReferences(lines))

  private def replaceContainers(st: BlockState, cs: Vector[Container]): BlockState =
    st.copy(containers = cs)

  private def run(lines: Vector[MdLine], refs: Map[String, LinkRef]): MarkdownBlockResult =

    // ── token cursor: emits VmTokens in source order via one advancing pos ────

    def emit(st: BlockState, kind: String, lexeme: String, instruction: VmInstruction, channel: TokenChannel): BlockState =
      if lexeme.isEmpty then st
      else
        val start = st.pos
        val nextPos = Unicode.advance(st.pos, lexeme)
        val token = SourceToken(st.nextId, kind, lexeme, SourceSpan(source, start, nextPos), channel)
        track(st.copy(pos = nextPos, nextId = st.nextId + 1L, out = st.out :+ VmToken(token, instruction)), instruction)

    def countOpens(st: BlockState, n: Int): BlockState =
      val opened = st.blocksOpened + n
      val hit =
        if opened > limits.maxBlocks && st.blockLimitHit.isEmpty then
          Some(limitDiag("uniml.markdown.limit.blocks",
            s"Markdown document exceeds the ${limits.maxBlocks} block limit"))
        else st.blockLimitHit
      st.copy(blocksOpened = opened, blockLimitHit = hit)

    def track(st: BlockState, instruction: VmInstruction): BlockState = instruction match
      case VmInstruction.Open(k, _) =>
        val counted: BlockState = countOpens(st, 1)
        counted.copy(frames = counted.frames :+ k)
      case VmInstruction.Close(expected, _) =>
        if st.frames.nonEmpty && expected.forall(_ == st.frames.last) then st.copy(frames = st.frames.dropRight(1)) else st
      case VmInstruction.Reframe(closeBefore, opens, closeAfter, _) =>
        val afterCloseBefore = closeBefore.foldLeft(st.frames)((f, _) => f.dropRight(1))
        val counted: BlockState = countOpens(st.copy(frames = afterCloseBefore), opens.size)
        val afterOpens = opens.foldLeft(counted.frames)((f, spec) => f :+ spec.kind)
        counted.copy(frames = closeAfter.foldLeft(afterOpens)((f, _) => f.dropRight(1)))
      case _ => st

    /** Rewrites the final token so it also closes every still-open frame
      * (innermost first), avoiding the VM's end-of-input "unclosed node" errors
      * for blocks that legitimately have no closing delimiter (e.g. a paragraph
      * at EOF with no trailing newline). No-op for an empty document. */
    def closeDangling(st: BlockState): BlockState =
      if st.frames.nonEmpty && st.out.nonEmpty then
        val remaining = st.frames.reverse
        val last = st.out.last
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
        st.copy(out = st.out.updated(st.out.size - 1, last.copy(instruction = rewritten)), frames = Vector.empty)
      else st

    def leaf(st: BlockState, kind: String, lexeme: String, role: Option[String], channel: TokenChannel): BlockState =
      emit(st, kind, lexeme, VmInstruction.Emit(role), channel)

    def openBranch(st: BlockState, branch: String, kind: String, lexeme: String, role: Option[String], channel: TokenChannel = TokenChannel.Syntax): BlockState =
      emit(st, kind, lexeme, VmInstruction.Open(branch, role), channel)

    def close(st: BlockState, branch: String, kind: String, lexeme: String, role: Option[String], channel: TokenChannel = TokenChannel.Syntax): BlockState =
      emit(st, kind, lexeme, VmInstruction.Close(Some(branch), role), channel)

    // ── main line loop ────────────────────────────────────────────────────────

    def processLine(st: BlockState, index: Int): BlockStep =
      val line: MdLine = lines(index)
      st.open match
        case OpenLeaf.FencedCode(fchar, flen) =>
          // A fence body inside a container still carries that container's
          // prefix, and it has to come off before the CLOSING fence can be
          // recognised at all. Without this, `1.  foo\n\n    ```\n    bar\n
          // ```` never closed: the closing line read as four spaces of indent,
          // so the block swallowed the rest of the document.
          val matchStep = matchContainers(st, line)
          BlockStep(handleFenceBody(matchStep.state, MdLine(matchStep.rest, line.ending), fchar, flen), index + 1)
        case _ =>
          // match / adjust containers, get the content portion of the line
          val matchStep = matchContainers(st, line)
          val content: String = matchStep.rest
          val indentWidth = MdChars.indentWidth(content)
          if content.forall(c => c == ' ' || c == '\t') then
            BlockStep(handleBlank(matchStep.state, line, content), index + 1)
          else dispatchLeaf(matchStep.state, index, line, content, indentWidth)

    /** Dispatch a non-blank content line to the right leaf handler. */
    def dispatchLeaf(
        st0: BlockState, index: Int, line: MdLine,
        content: String, indentWidth: Int,
    ): BlockStep =
      val trimmed: String = content.substring(MdChars.indentPrefixLength(content))
      // indented code (4+ spaces) only when not continuing a paragraph
      val isIndentedCode = indentWidth >= 4 && st0.open != OpenLeaf.Paragraph && !startsListOrQuote(trimmed)
      // any other leaf ends an open indented block; the branches below that do not
      // route through finishParagraph (appendParagraph) would otherwise nest into it
      // ORDER IS THE WHOLE POINT: held blanks first (they precede this line in
      // the source), then this line's container prefix, then the line itself.
      val held = if isIndentedCode then releaseInteriorBlanks(st0) else finishIndentedCode(st0)
      val st: BlockState = emitIndentedCodePrefix(held)
      if isIndentedCode then
        BlockStep(handleIndentedCode(st, line, content), index + 1)
      else if st.open == OpenLeaf.Paragraph && isSetextUnderline(trimmed) then
        // a setext underline takes precedence over a thematic break
        BlockStep(emitSetextUnderline(st, line), index + 1)
      else if isThematicBreak(trimmed) then
        BlockStep(emitThematicBreak(finishParagraph(st), line), index + 1)
      else if startsAtxHeading(trimmed) then
        BlockStep(emitAtxHeading(finishParagraph(st), line, content), index + 1)
      else if startsFence(trimmed).isDefined then
        BlockStep(startFence(finishParagraph(st), line, content, startsFence(trimmed).get), index + 1)
      else if startsBlockquote(trimmed) then
        openBlockquoteAndReprocess(st, index, line, content)
      else if startsListItem(trimmed).isDefined then
        openListItemAndReprocess(st, index, line, content, startsListItem(trimmed).get)
      else if indentWidth < 4 && htmlBlockType(trimmed, st.open == OpenLeaf.Paragraph).isDefined then
        // types 1-6 interrupt a paragraph; type 7 does not (returns None when open)
        val ht = htmlBlockType(trimmed, st.open == OpenLeaf.Paragraph).get
        handleHtmlBlock(finishParagraph(st), index, ht)
      else if refDefAt(lines, index, line, content, st.containers, st.open).isDefined then
        // the scan owns the extent: a definition may span several lines
        val defn = refDefAt(lines, index, line, content, st.containers, st.open).get
        BlockStep(emitDefinition(st, defn), index + defn.linesConsumed)
      else if gfm && st.open != OpenLeaf.Paragraph && isTableStart(lines, index, content) then
        emitTable(st, index)
      else
        BlockStep(appendParagraph(st, line, content), index + 1)

    // ── container matching ────────────────────────────────────────────────────

    /** Consumes as many open-container prefixes as this line satisfies, emitting
      * their exact marker/indent tokens, and returns the remaining content.
      * Unmatched trailing containers are scheduled to close (no lazy-continuation
      * support in M4). */
    def matchContainers(st0: BlockState, line: MdLine): ContainerMatch =
      // When a paragraph is open, its container-continuation markers must be
      // emitted in source order *with* the deferred paragraph text, so we buffer
      // them here instead of emitting them ahead of the text.
      // Buffer for an open indented code block as well as a paragraph. Emitting
      // this line's container prefix immediately would put it BEFORE blank lines
      // the block is still holding, which is exactly how
      // `…indented code\n\n    > A block quote.` reconstructed as
      // `…indented code\n    \n> A block quote.` — the prefix and the blank had
      // swapped places, and the source axis caught it.
      val buffering = st0.open == OpenLeaf.Paragraph || st0.open == OpenLeaf.IndentedCode
      def consume(st: BlockState, prefix: Vector[String], kind: String, lex: String): ConsumeStep =
        if buffering then ConsumeStep(st, prefix :+ lex)
        else if kind == MdKind.BlockquoteMarker then ConsumeStep(emitContainerMarker(st, kind, lex), prefix)
        else ConsumeStep(emitContainerIndent(st, lex), prefix)
      // walks the container stack; stops at the first unsatisfied container
      def walk(st: BlockState, i: Int, rest: String, matched: Int, prefix: Vector[String]): ContainerScan =
        if i >= st.containers.size then ContainerScan(st, rest, matched, prefix)
        else
          // TWO steps: apply-on-a-FIELD (`st.containers(i)`) does not lower as indexing on
          // the Rust lane (it emitted a method call, E0599) — index a typed LOCAL instead.
          val containersHere: Vector[Container] = st.containers
          val containerAt: Container = containersHere(i)
          containerAt match
          case _: Blockquote =>
            stripBlockquoteMarker(rest) match
              case Some((marker, remainder)) =>
                val step: ConsumeStep = consume(st, prefix, MdKind.BlockquoteMarker, marker)
                walk(step.state, i + 1, remainder, matched + 1, step.prefix)
              case None => ContainerScan(st, rest, matched, prefix)
          case item: ListItemFrame =>
            if MdChars.indentWidth(rest) >= item.contentIndent || rest.forall(c => c == ' ' || c == '\t') then
              val take: String = consumeIndent(rest, item.contentIndent)
              val step = if take.nonEmpty then consume(st, prefix, MdKind.Indent, take) else ConsumeStep(st, prefix)
              walk(step.state, i + 1, rest.substring(take.length), matched + 1, step.prefix)
            else ContainerScan(st, rest, matched, prefix)
          case _: ListFrame => walk(st, i + 1, rest, matched + 1, prefix) // list frame matches whenever its item does
      val scan: ContainerScan = walk(st0, 0, line.content, 0, Vector.empty)
      val st1 = scan.state
      val rest = scan.rest
      if scan.matched >= st1.containers.size then
        // full match: hand the continuation prefix to whichever leaf is open
        if st1.open == OpenLeaf.IndentedCode then ContainerMatch(st1.copy(indentedCodePendingPrefix = scan.prefix.mkString("")), rest)
        else if buffering then ContainerMatch(st1.copy(paragraphPendingPrefix = scan.prefix.mkString("")), rest)
        else ContainerMatch(st1, rest)
      else if buffering && st1.open == OpenLeaf.Paragraph && isLazyContinuation(rest) then
        // lazy paragraph continuation: a plain paragraph-text line continues the
        // open paragraph even though a container marker is missing; keep the
        // unmatched containers open so the paragraph stays inside them
        ContainerMatch(st1.copy(paragraphPendingPrefix = scan.prefix.mkString("")), rest)
      else
        // fewer containers matched — the paragraph (if any) ends here
        val st2: BlockState = finishParagraph(st1)
        val st3 =
          if scan.prefix.nonEmpty then flushPending(st2, MdKind.Indent, scan.prefix.mkString(""), Vector.empty, Some("continuation"), TokenChannel.Trivia)
          else st2
        ContainerMatch(scheduleContainerClose(st3, listAwareKeep(st3, scan.matched, rest)), rest)

    /** A list frame outlives the item that just closed ONLY when this line opens
      * a sibling item. Otherwise the list ends here too.
      *
      * Without this the frame stayed open and the following block was emitted as
      * a direct child of the list — and the projection collects only list items,
      * so it DROPPED it: `- one\n\n two` lost `two` from the output entirely.
      * Silent content loss, which is why it outranked the prettier list bugs. */
    def listAwareKeep(st: BlockState, matched: Int, rest: String): Int =
      if matched <= 0 || matched > st.containers.size then matched
      else
        val containersAll: Vector[Container] = st.containers
        val lastMatched: Container = containersAll(matched - 1)
        lastMatched match
          case lf: ListFrame =>
            val trimmed: String = rest.substring(MdChars.indentPrefixLength(rest))
            startsListItem(trimmed) match
              case Some(item) if item._1 == lf.ordered => matched
              case _                                   => matched - 1
          case _ => matched

    def scheduleContainerClose(st0: BlockState, keep: Int): BlockState =
      def drop(st: BlockState): BlockState =
        if st.containers.size > keep then
          drop(st.copy(containers = st.containers.dropRight(1), pendingClose = st.pendingClose :+ st.containers.last.frame))
        else st
      drop(finishParagraph(st0))

    def emitContainerMarker(st: BlockState, kind: String, lexeme: String): BlockState =
      flushPending(st, kind, lexeme, Vector.empty, Some("marker"), TokenChannel.Syntax)

    def emitContainerIndent(st: BlockState, lexeme: String): BlockState =
      flushPending(st, MdKind.Indent, lexeme, Vector.empty, Some("continuation"), TokenChannel.Trivia)

    // ── blank lines ────────────────────────────────────────────────────────

    def handleBlank(st: BlockState, line: MdLine, content: String): BlockState =
      // Inside an indented code block a blank line is not yet decidable: it is
      // interior content if another indented line follows and trailing trivia if
      // the block ends. Hold it until one of the two happens.
      if st.open == OpenLeaf.IndentedCode then
        // the blank's own container prefix belongs WITH it, not before the next line
        st.copy(
          indentedCodeBlanks = st.indentedCodeBlanks :+ ((st.indentedCodePendingPrefix + content, line.ending)),
          indentedCodePendingPrefix = "")
      else
        val finished: BlockState = finishParagraph(st)
        val lexeme: String = content + line.ending
        if lexeme.nonEmpty then flushPending(finished, MdKind.Blank, lexeme, Vector.empty, Some("blank"), TokenChannel.Trivia)
        else finished

    // ── paragraphs ────────────────────────────────────────────────────────

    def appendParagraph(st: BlockState, line: MdLine, content: String): BlockState =
      if st.open != OpenLeaf.Paragraph then
        // the first line's marker was already emitted by the container opener
        st.copy(open = OpenLeaf.Paragraph, paragraphSegs = Vector(ParaSeg("", content, line.ending)), paragraphPendingPrefix = "")
      else
        st.copy(paragraphSegs = st.paragraphSegs :+ ParaSeg(st.paragraphPendingPrefix, content, line.ending), paragraphPendingPrefix = "")

    def finishParagraph(st0: BlockState): BlockState =
      // the single "close whatever leaf is open" hook — all twelve call sites get
      // indented-code termination for free, and it must run before the paragraph
      // branch below so the two states never overlap
      val st: BlockState = finishIndentedCode(st0)
      val afterPara: BlockState =
        if st.open == OpenLeaf.Paragraph then
          val segs = st.paragraphSegs
          // inline content is the de-prefixed lines joined by their exact endings —
          // no container markers, so multi-line inline spans resolve cleanly
          val content: String = segs.iterator.map(s => s"${s.content}${s.ending}").mkString("")
          val pieces = MarkdownInlines.parse(content, refs, profile)
          emitParagraphWithSegments(st.copy(open = OpenLeaf.None, paragraphSegs = Vector.empty), pieces, segs)
        else st
      // a continuation prefix buffered for a line that turned out to start a new
      // block (not continue the paragraph) is emitted here so nothing is lost
      if afterPara.paragraphPendingPrefix.nonEmpty then
        flushPending(afterPara.copy(paragraphPendingPrefix = ""), MdKind.Indent, afterPara.paragraphPendingPrefix,
          Vector.empty, Some("continuation"), TokenChannel.Trivia)
      else afterPara

    /** Emits the paragraph's inline pieces wrapped in a paragraph frame, splicing
      * each line's continuation prefix back in as trivia at its source position —
      * i.e. right after the soft/hard break that ends the preceding line. The
      * k-th break in the stream ends segment k, so segment k+1's prefix follows. */
    def emitParagraphWithSegments(st: BlockState, pieces: Vector[InlinePiece], segs: Vector[ParaSeg]): BlockState =
      if pieces.isEmpty then st
      else
        val n: Int = pieces.size
        def step(cur: ParaEmitStep, i: Int): ParaEmitStep =
          if i >= n then cur
          else
            // A code span can SWALLOW the break it crosses: the newline sits INSIDE its single
            // content lexeme, so `isBreakPiece` is false and the prefix that follows that break has
            // no position between pieces. It was then never consumed and `finishParagraph` flushed it
            // after the whole block — every character present, in the wrong ORDER, which is why a
            // length check passes and only comparing the string catches it. Splice it back where the
            // source had it: immediately after the embedded newline.
            val (piece, consumed) = spliceSwallowedBreaks(pieces(i), segs, cur.breakCount)
            val emitted =
              if n == 1 then emitFirstLast(cur.state, MdBranch.Paragraph, piece, Some("content"))
              else if i == 0 then emitFirst(cur.state, MdBranch.Paragraph, piece, Some("content"))
              else if i == n - 1 then emitLast(cur.state, MdBranch.Paragraph, piece)
              else replay(cur.state, piece)
            if isBreakPiece(piece) then
              val bc = consumed + 1
              val withPrefix =
                if bc < segs.size && segs(bc).prefix.nonEmpty then
                  leaf(emitted, MdKind.Indent, segs(bc).prefix, Some("continuation"), TokenChannel.Trivia)
                else emitted
              step(ParaEmitStep(withPrefix, bc), i + 1)
            else step(ParaEmitStep(emitted, consumed), i + 1)
        step(ParaEmitStep(st, 0), 0).state

    // ── ATX headings ────────────────────────────────────────────────────────

    def emitAtxHeading(st0: BlockState, line: MdLine, content: String): BlockState =
      val lead = MdChars.indentPrefixLength(content)
      val st1 =
        if lead > 0 then flushPending(st0, MdKind.Indent, content.substring(0, lead), Vector.empty, Some("indent"), TokenChannel.Trivia)
        else st0
      val body = content.substring(lead)
      val h = countRun(body, '#')
      val marker = body.substring(0, h)
      // open heading on the marker
      val st2: BlockState = flushPending(st1, MdKind.AtxMarker, marker, Vector(FrameSpec(MdBranch.Heading)), Some("marker"), TokenChannel.Syntax)
      val afterMarker = body.substring(h)
      // leading spaces of the heading text
      val restLead: String = afterMarker.takeWhile(c => c == ' ' || c == '\t')
      val st3: BlockState = if restLead.nonEmpty then leaf(st2, MdKind.Indent, restLead, Some("space"), TokenChannel.Trivia) else st2
      val rest = afterMarker.substring(restLead.length)
      // optional closing sequence of #'s
      val atxSplit = splitAtxClosing(rest)
      val rawText: String = atxSplit._1
      val closing: String = atxSplit._2
      // trailing whitespace of the heading text is trivia, not content
      val textEnd = trailingWsStart(rawText)
      val hText = rawText.substring(0, textEnd)
      val trailWs: String = rawText.substring(textEnd)
      val pieces = MarkdownInlines.parse(hText, refs, profile)
      val st4 = pieces.foldLeft(st3)((s, p) => replay(s, p))
      val st5: BlockState = if trailWs.nonEmpty then leaf(st4, MdKind.Indent, trailWs, Some("space"), TokenChannel.Trivia) else st4
      val st6: BlockState = if closing.nonEmpty then leaf(st5, MdKind.AtxClose, closing, Some("close"), TokenChannel.Syntax) else st5
      // close heading on the line ending (or dangling at EOF)
      if line.ending.nonEmpty then close(st6, MdBranch.Heading, MdKind.LineBreak, line.ending, Some("trailing"), TokenChannel.Trivia)
      else st6

    // ── setext headings ────────────────────────────────────────────────────

    def emitSetextUnderline(st0: BlockState, line: MdLine): BlockState =
      // reinterpret the just-open paragraph as a setext heading. Setext headings
      // are almost always single-line; for the rare multi-line-in-container case
      // the continuation prefix is woven into the text (kept lossless).
      // annotated: the Rust lane needs the declared type for member reads below
      val segs: Vector[ParaSeg] = st0.paragraphSegs
      val st: BlockState = st0.copy(open = OpenLeaf.None, paragraphSegs = Vector.empty)
      val content: String = segs.iterator.zipWithIndex.map { (s, idx) =>
        val pfx = if idx == 0 then "" else s.prefix
        val end = if idx == segs.size - 1 then "" else s.ending
        pfx + s.content + end
      }.mkString("")
      val interior: String = segs.lastOption.map(_.ending).getOrElse("")
      val pieces = MarkdownInlines.parse(content, refs, profile)
      if pieces.isEmpty then
        val st1: BlockState = flushPending(st, MdKind.SetextUnderline, line.content, Vector(FrameSpec(MdBranch.Heading)), Some("underline"), TokenChannel.Syntax)
        if line.ending.nonEmpty then close(st1, MdBranch.Heading, MdKind.LineBreak, line.ending, Some("trailing"), TokenChannel.Trivia)
        else st1
      else
        val st1: BlockState = emitInlineOpenOnly(st, MdBranch.Heading, pieces)
        // the newline that ended the heading text, then the underline line
        val st2: BlockState = if interior.nonEmpty then leaf(st1, MdKind.SoftBreak, interior, Some("space"), TokenChannel.Trivia) else st1
        if line.ending.nonEmpty then
          val st3: BlockState = leaf(st2, MdKind.SetextUnderline, line.content, Some("underline"), TokenChannel.Syntax)
          close(st3, MdBranch.Heading, MdKind.LineBreak, line.ending, Some("trailing"), TokenChannel.Trivia)
        else
          close(st2, MdBranch.Heading, MdKind.SetextUnderline, line.content, Some("underline"), TokenChannel.Syntax)

    // ── thematic break ────────────────────────────────────────────────────

    def emitThematicBreak(st: BlockState, line: MdLine): BlockState =
      val st1: BlockState = flushPending(st, MdKind.ThematicMarker, line.content, Vector(FrameSpec(MdBranch.ThematicBreak)), Some("marker"), TokenChannel.Syntax)
      if line.ending.nonEmpty then close(st1, MdBranch.ThematicBreak, MdKind.LineBreak, line.ending, Some("trailing"), TokenChannel.Trivia)
      else st1.copy(pendingClose = st1.pendingClose :+ MdBranch.ThematicBreak)

    // ── fenced code ────────────────────────────────────────────────────────

    def startFence(st0: BlockState, line: MdLine, content: String, fence: (Char, Int)): BlockState =
      val lead = MdChars.indentPrefixLength(content)
      val st1 =
        if lead > 0 then flushPending(st0, MdKind.Indent, content.substring(0, lead), Vector.empty, Some("indent"), TokenChannel.Trivia)
        else st0
      val body = content.substring(lead)
      val flen = fence._2
      val fenceLex = body.substring(0, flen)
      val st2: BlockState = flushPending(st1, MdKind.FenceOpen, fenceLex, Vector(FrameSpec(MdBranch.CodeBlock)), Some("fence.open"), TokenChannel.Syntax)
      val info: String = body.substring(flen)
      val st3: BlockState = if info.nonEmpty then leaf(st2, MdKind.Info, info, Some("info"), TokenChannel.Embedded) else st2
      val st4: BlockState = if line.ending.nonEmpty then leaf(st3, MdKind.LineBreak, line.ending, Some("trailing"), TokenChannel.Trivia) else st3
      st4.copy(fenceCp = 0L, open = OpenLeaf.FencedCode(fence._1, flen))

    def handleFenceBody(st: BlockState, line: MdLine, fchar: Char, flen: Int): BlockState =
      val trimmed: String = line.content.substring(MdChars.indentPrefixLength(line.content))
      val closes = trimmed.nonEmpty && trimmed.forall(_ == fchar) && countRun(trimmed, fchar) >= flen &&
        MdChars.indentWidth(line.content) <= 3
      if closes then
        val lead = MdChars.indentPrefixLength(line.content)
        val st1 = st.copy(open = OpenLeaf.None)
        val st2: BlockState = if lead > 0 then leaf(st1, MdKind.Indent, line.content.substring(0, lead), Some("indent"), TokenChannel.Trivia) else st1
        val st3: BlockState = close(st2, MdBranch.CodeBlock, MdKind.FenceClose, line.content.substring(lead), Some("fence.close"), TokenChannel.Syntax)
        if line.ending.nonEmpty then leaf(st3, MdKind.LineBreak, line.ending, Some("trailing"), TokenChannel.Trivia) else st3
      else
        val counted = st.fenceCp + Unicode.codePointCount(line.raw)
        val hit =
          if counted > limits.maxFenceCodePoints && st.fenceLimitHit.isEmpty then
            Some(limitDiag("uniml.markdown.limit.fence",
              s"fenced code block exceeds the ${limits.maxFenceCodePoints} code-point limit"))
          else st.fenceLimitHit
        val st1 = st.copy(fenceCp = counted, fenceLimitHit = hit)
        val st2: BlockState = if line.content.nonEmpty then leaf(st1, MdKind.CodeContent, line.content, Some("code"), TokenChannel.Embedded) else st1
        if line.ending.nonEmpty then leaf(st2, MdKind.LineBreak, line.ending, Some("code"), TokenChannel.Embedded) else st2

    // ── indented code ────────────────────────────────────────────────────────

    /** One line of an indented code block. Consecutive indented lines coalesce
      * into a single block, and the four columns that make it code are stripped
      * from the literal — they stay in the token stream as trivia, so the source
      * still reconstructs exactly. */
    def handleIndentedCode(st: BlockState, line: MdLine, content: String): BlockState =
      val cut = MdChars.indentCut(content, 4)
      // A tab straddling column 4 would have to be split into spaces to be cut.
      // No token can hold half a tab, so keep the whole run as trivia and let the
      // literal start after it — the case is recorded, not silently approximated.
      val indent: String = if cut >= 0 then content.substring(0, cut) else content.take(MdChars.indentPrefixLength(content))
      val code = content.substring(indent.length)
      val opened =
        if st.open != OpenLeaf.IndentedCode then
          val finishedBase: BlockState = finishParagraph(st)
          val finished: BlockState = finishedBase.copy(open = OpenLeaf.IndentedCode)
          flushPending(finished, MdKind.Indent, indent, Vector(FrameSpec(MdBranch.CodeBlock)), Some("indent"), TokenChannel.Trivia)
        else
          leaf(st, MdKind.Indent, indent, Some("indent"), TokenChannel.Trivia)
      val withCode: BlockState = leaf(opened, MdKind.CodeContent, code, Some("code"), TokenChannel.Embedded)
      if line.ending.nonEmpty then leaf(withCode, MdKind.LineBreak, line.ending, Some("code"), TokenChannel.Embedded)
      else withCode

    def emitIndentedCodePrefix(st: BlockState): BlockState =
      if st.indentedCodePendingPrefix.nonEmpty then
        flushPending(st.copy(indentedCodePendingPrefix = ""), MdKind.Indent, st.indentedCodePendingPrefix,
          Vector.empty, Some("continuation"), TokenChannel.Trivia)
      else st

    /** Blank lines held while an indented code block is open turned out to be
      * INTERIOR — another indented line followed — so they belong to the literal. */
    def releaseInteriorBlanks(st: BlockState): BlockState =
      val held = st.indentedCodeBlanks
      held.foldLeft(st.copy(indentedCodeBlanks = Vector.empty)) { (s, blank) =>
        val blankContent = blank._1
        val ending: String = blank._2
        // up to four columns of a blank line are the block's indentation, not content
        val cut = MdChars.indentCut(blankContent, 4)
        val trivia: String = if cut >= 0 then blankContent.substring(0, cut) else blankContent
        val keep: String = if cut >= 0 then blankContent.substring(cut) else ""
        val s1 = if trivia.nonEmpty then leaf(s, MdKind.Indent, trivia, Some("indent"), TokenChannel.Trivia) else s
        val s2 = if keep.nonEmpty then leaf(s1, MdKind.CodeContent, keep, Some("code"), TokenChannel.Embedded) else s1
        if ending.nonEmpty then leaf(s2, MdKind.LineBreak, ending, Some("code"), TokenChannel.Embedded) else s2
      }

    /** Closes an open indented code block. Blank lines still held are TRAILING —
      * they belong to the parent container, so they are re-emitted as ordinary
      * blanks after the block's close. */
    def finishIndentedCode(st: BlockState): BlockState =
      if st.open == OpenLeaf.IndentedCode then
        val held = st.indentedCodeBlanks
        val closed = st.copy(
          open = OpenLeaf.None,
          pendingClose = st.pendingClose :+ MdBranch.CodeBlock,
          indentedCodeBlanks = Vector.empty)
        held.foldLeft(closed) { (s, blank) =>
          val lexeme: String = s"${blank._1}${blank._2}"
          if lexeme.nonEmpty then flushPending(s, MdKind.Blank, lexeme, Vector.empty, Some("blank"), TokenChannel.Trivia)
          else s
        }
      else st

    // ── HTML blocks ────────────────────────────────────────────────────────

    def handleHtmlBlock(st0: BlockState, index: Int, htmlType: Int): BlockStep =
      // types 1-5 end at (and include) a line containing their close marker;
      // types 6-7 end before a blank line. End of input ends any block.
      val endMarkers: Option[Vector[String]] = htmlType match
        case 1 => Some(Vector("</script>", "</pre>", "</style>", "</textarea>"))
        case 2 => Some(Vector("-->"))
        case 3 => Some(Vector("?>"))
        case 4 => Some(Vector(">"))
        case 5 => Some(Vector("]]>"))
        case _ => None
      def loop(st: BlockState, i: Int, first: Boolean): BlockStep =
        if i >= lines.size then BlockStep(st, i)
        else
          val l: MdLine = lines(i)
          // `getOrElse` + emptiness, not `match Some(markers)`: destructuring the CAPTURED
          // Option partially moves it on the Rust lane while the self-call still needs it
          // (E0382); the extracted copy is per-iteration and small.
          val markers: Vector[String] = endMarkers.getOrElse(Vector.empty)
          if markers.isEmpty then
            if l.isBlank then BlockStep(st, i)
            else loop(emitHtmlLine(st, l, first), i + 1, false)
          else
            val emitted: BlockState = emitHtmlLine(st, l, first)
            val lc = MdChars.asciiLower(l.content)
            if markers.exists(lc.contains) then BlockStep(emitted, i + 1)
            else loop(emitted, i + 1, false)
      val ended: BlockStep = loop(st0, index, true)
      BlockStep(ended.state.copy(pendingClose = ended.state.pendingClose :+ MdBranch.HtmlBlock), ended.index)

    def emitHtmlLine(st: BlockState, l: MdLine, first: Boolean): BlockState =
      val withContent =
        if first then flushPending(st, MdKind.Html, l.content, Vector(FrameSpec(MdBranch.HtmlBlock)), Some("html"), TokenChannel.Embedded)
        else if l.content.nonEmpty then leaf(st, MdKind.Html, l.content, Some("html"), TokenChannel.Embedded)
        else st
      if l.ending.nonEmpty then leaf(withContent, MdKind.LineBreak, l.ending, Some("html"), TokenChannel.Embedded)
      else withContent

    // ── GFM tables ────────────────────────────────────────────────────────

    def emitTable(st0: BlockState, index: Int): BlockStep =
      // open the table on the header row's content
      val header: MdLine = lines(index)
      val st1: BlockState = flushPending(st0, MdKind.TableRow, header.content, Vector(FrameSpec(MdBranch.Table)), Some("header"), TokenChannel.Syntax)
      val st2: BlockState = if header.ending.nonEmpty then leaf(st1, MdKind.LineBreak, header.ending, Some("trailing"), TokenChannel.Trivia) else st1
      val delim: MdLine = lines(index + 1)
      val st3: BlockState = leaf(st2, MdKind.TableDelim, delim.content, Some("delimiter"), TokenChannel.Syntax)
      val st4: BlockState = if delim.ending.nonEmpty then leaf(st3, MdKind.LineBreak, delim.ending, Some("trailing"), TokenChannel.Trivia) else st3
      def rows(st: BlockState, i: Int): BlockStep =
        if i < lines.size && !lines(i).isBlank && lines(i).content.contains('|') then
          val a: BlockState = leaf(st, MdKind.TableRow, lines(i).content, Some("row"), TokenChannel.Syntax)
          val b = if lines(i).ending.nonEmpty then leaf(a, MdKind.LineBreak, lines(i).ending, Some("trailing"), TokenChannel.Trivia) else a
          rows(b, i + 1)
        else BlockStep(st, i)
      val ended: BlockStep = rows(st4, index + 2)
      // close the table on the next structural token (or at EOF via closeDangling)
      BlockStep(ended.state.copy(pendingClose = ended.state.pendingClose :+ MdBranch.Table), ended.index)

    // ── block quotes & lists (containers) ─────────────────────────────────────

    def openBlockquoteAndReprocess(
        st0: BlockState, index: Int, line: MdLine, content: String): BlockStep =
      val st1: BlockState = finishParagraph(st0)
      // The gate that routed here checked `startsBlockquote(trimmed)` — indent removed —
      // while `stripBlockquoteMarker` re-checks `indentWidth(content) <= 3` on the RAW line,
      // and `isIndentedCode` deliberately exempts quote-starting lines. A deeply indented `>`
      // (scalascript's own tests/SPRINT.md) passed the gate and panicked on the `.get`. The
      // marker token absorbs its indentation (lossless); the inner strip is on the trimmed
      // line, whose first char the gate proved is `>`.
      val stripped = stripBlockquoteMarker(content).getOrElse {
        val leadLen = MdChars.indentPrefixLength(content)
        val inner = stripBlockquoteMarker(content.substring(leadLen)).get
        (content.substring(0, leadLen) + inner._1, inner._2)
      }
      val st2: BlockState = flushPending(st1, MdKind.BlockquoteMarker, stripped._1, Vector(FrameSpec(MdBranch.Blockquote)), Some("marker"), TokenChannel.Syntax)
      val st3 = st2.copy(containers = st2.containers :+ Blockquote())
      // reprocess the remainder of the line as inner content by rebuilding a line
      reprocessInner(st3, index, MdLine(stripped._2, line.ending))

    def openListItemAndReprocess(
        st0: BlockState, index: Int, line: MdLine, content: String,
        item: (Boolean, String, Int)): BlockStep =
      val st1: BlockState = finishParagraph(st0)
      val lead = MdChars.indentPrefixLength(content)
      val (ordered, marker, contentIndent) = item
      // open a list frame if the parent isn't already the matching list
      val needList = !st1.containers.lastOption.exists {
        case lf: ListFrame => lf.ordered == ordered
        case _             => false
      }
      // a list of a different marker type ends the current sibling list; close it
      // so the new list is a sibling, not nested inside the old frame
      val st2 =
        if needList then st1.containers.lastOption match
          case Some(lf: ListFrame) =>
            st1.copy(containers = st1.containers.dropRight(1), pendingClose = st1.pendingClose :+ lf.frame)
          case _ => st1
        else st1
      val st3 =
        if lead > 0 then flushPending(st2, MdKind.Indent, content.substring(0, lead), Vector.empty, Some("indent"), TokenChannel.Trivia)
        else st2
      val opens =
        if needList then Vector(FrameSpec(MdBranch.List), FrameSpec(MdBranch.ListItem))
        else Vector(FrameSpec(MdBranch.ListItem))
      // `replaceContainers` (a one-field rebuild) instead of `.copy(containers = …)`: this
      // ONE copy site kept lowering positionally whatever its spelling (E0599) — a named
      // method sidesteps the copy machinery entirely. The no-list branch passes identical
      // containers — same semantics.
      val withFrame: Vector[Container] =
        if needList then st3.containers :+ ListFrame(ordered) else st3.containers
      val withList: BlockState = replaceContainers(st3, withFrame)
      val st4 = withList.copy(containers = withList.containers :+ ListItemFrame(ordered, contentIndent + lead))
      val body = content.substring(lead)
      val markerLex = body.substring(0, marker.length)
      val st5: BlockState = flushPending(st4, MdKind.ListMarker, markerLex, opens, Some("marker"), TokenChannel.Syntax)
      // GFM task marker
      val remainder0 = body.substring(marker.length)
      val (st6, remainder) =
        if gfm && (remainder0.startsWith("[ ] ") || remainder0.startsWith("[x] ") || remainder0.startsWith("[X] ")) then
          (leaf(st5, MdKind.TaskMarker, remainder0.substring(0, 3), Some("task"), TokenChannel.Syntax), remainder0.substring(3))
        else (st5, remainder0)
      reprocessInner(st6, index, MdLine(remainder, line.ending))

    /** Re-runs leaf detection on the content remaining after a container marker
      * on the same physical line. */
    def reprocessInner(st: BlockState, index: Int, innerLine: MdLine): BlockStep =
      val content: String = innerLine.content
      val indentWidth = MdChars.indentWidth(content)
      if innerLine.isBlank then
        val finished: BlockState = finishParagraph(st)
        val emitted =
          if innerLine.ending.nonEmpty then leaf(finished, MdKind.LineBreak, innerLine.ending, Some("blank"), TokenChannel.Trivia)
          else finished
        BlockStep(emitted, index + 1)
      else dispatchLeaf(st, index, innerLine, content, indentWidth)

    // ── ScalaScript YAML front matter ─────────────────────────────────────────

    def scanFrontMatter(st0: BlockState, index: Int): BlockStep =
      if index < lines.size && lines(index).content == "---" then
        def findClose(i: Int): Int =
          if i < lines.size && lines(i).content != "---" && lines(i).content != "..." then findClose(i + 1) else i
        val i = findClose(index + 1)
        if i < lines.size then
          // open front-matter on the opening fence
          val st1: BlockState = openBranch(st0, MdBranch.FrontMatter, MdKind.FrontMatterFence, lines(index).content, Some("open"), TokenChannel.Syntax)
          val st2 =
            if lines(index).ending.nonEmpty then leaf(st1, MdKind.LineBreak, lines(index).ending, Some("open"), TokenChannel.Trivia)
            else st1
          def emitYaml(st: BlockState, j: Int): BlockState =
            if j >= i then st
            else
              val a = if lines(j).content.nonEmpty then leaf(st, MdKind.CodeContent, lines(j).content, Some("yaml"), TokenChannel.Embedded) else st
              val b = if lines(j).ending.nonEmpty then leaf(a, MdKind.LineBreak, lines(j).ending, Some("yaml"), TokenChannel.Embedded) else a
              emitYaml(b, j + 1)
          val st3: BlockState = emitYaml(st2, index + 1)
          val st4: BlockState = close(st3, MdBranch.FrontMatter, MdKind.FrontMatterFence, lines(i).content, Some("close"), TokenChannel.Syntax)
          val st5 =
            if lines(i).ending.nonEmpty then leaf(st4, MdKind.LineBreak, lines(i).ending, Some("close"), TokenChannel.Trivia)
            else st4
          BlockStep(st5, i + 1)
        else BlockStep(st0, index)
      else BlockStep(st0, index)

    // ── link reference definitions ────────────────────────────────────────────

    /** The lines a definition scan may look at, in the form the EMITTER will
      * write them.
      *
      * `matchContainers` has already consumed this line's container prefix and
      * handed back the de-prefixed content, so scanning the RAW line would emit
      * that prefix a second time — which is exactly what happened: `- a\n- b\n\n
      * [ref]: /url` reconstructed with four spaces instead of two, breaking the
      * source axis. Inside a container the scan therefore sees only the single
      * de-prefixed line; a definition SPANNING lines is offered at top level
      * only, where no prefix has been stripped. Multi-line definitions inside a
      * list item or block quote stay unsupported, and stay red in the corpus
      * rather than lossy. */
    /** Emits one definition from the SAME scan the pre-pass used, as a straight
      * sequence of its slices. Because every slice is source text and their
      * concatenation is what the scan consumed, the source axis is preserved by
      * construction rather than by matching two hand-written scanners. The
      * caller reads the lines consumed off the definition itself — one may span
      * several. */
    def emitDefinition(st0: BlockState, defn: RefDef): BlockState =
      val st1 =
        if defn.indent.nonEmpty then flushPending(st0, MdKind.Indent, defn.indent, Vector.empty, Some("indent"), TokenChannel.Trivia)
        else st0
      val st2: BlockState = flushPending(st1, MdKind.ReferenceLabel, defn.labelLex, Vector(FrameSpec(MdBranch.Definition)), Some("label"), TokenChannel.Syntax)
      val st3: BlockState = leaf(st2, MdKind.Colon, defn.colon, Some("colon"), TokenChannel.Syntax)
      // whitespace between the parts may CROSS A LINE ENDING; it stays trivia
      val st4: BlockState = leaf(st3, MdKind.Indent, defn.afterColon, Some("space"), TokenChannel.Trivia)
      val st5: BlockState = leaf(st4, MdKind.Destination, defn.destLex, Some("destination"), TokenChannel.Syntax)
      val st6: BlockState = leaf(st5, MdKind.Indent, defn.betweenDestTitle, Some("space"), TokenChannel.Trivia)
      val st7: BlockState = leaf(st6, MdKind.Title, defn.titleLex, Some("title"), TokenChannel.Syntax)
      if defn.trailing.nonEmpty then
        close(st7, MdBranch.Definition, MdKind.LineBreak, defn.trailing, Some("trailing"), TokenChannel.Trivia)
      else st7.copy(pendingClose = st7.pendingClose :+ MdBranch.Definition)

    // ── emission helpers ────────────────────────────────────────────────────

    /** Open `branch` on the first piece only (used by setext where the underline
      * closes the frame explicitly). */
    def emitInlineOpenOnly(st: BlockState, branch: String, pieces: Vector[InlinePiece]): BlockState =
      if pieces.isEmpty then st
      else pieces.drop(1).foldLeft(emitFirst(st, branch, pieces.head, Some("content")))((s, p) => replay(s, p))

    def emitFirst(st: BlockState, branch: String, piece: InlinePiece, role: Option[String]): BlockState = piece match
      case InlinePiece.Tok(kind, lex, r, ch) =>
        flushPending(st, kind, lex, Vector(FrameSpec(branch)), r.orElse(role), ch)
      case InlinePiece.Open(b2, kind, lex, r) =>
        flushPending(st, kind, lex, Vector(FrameSpec(branch), FrameSpec(b2)), r, TokenChannel.Syntax)
      case InlinePiece.Close(_, kind, lex, r) =>
        // shouldn't be first; emit as close of branch to stay balanced
        flushPending(st, kind, lex, Vector(FrameSpec(branch)), r, TokenChannel.Syntax)

    def emitLast(st: BlockState, branch: String, piece: InlinePiece): BlockState = piece match
      case InlinePiece.Tok(kind, lex, r, ch) =>
        emit(st, kind, lex, VmInstruction.Reframe(closeBefore = Vector.empty, open = Vector.empty, closeAfter = Vector(branch), role = r), ch)
      case InlinePiece.Close(b2, kind, lex, r) =>
        emit(st, kind, lex, VmInstruction.Reframe(closeBefore = Vector.empty, open = Vector.empty, closeAfter = Vector(b2, branch), role = r), TokenChannel.Syntax)
      case InlinePiece.Open(b2, kind, lex, r) =>
        // degenerate trailing open; open then rely on closeDangling
        emit(st, kind, lex, VmInstruction.Open(b2, r), TokenChannel.Syntax)

    def emitFirstLast(st: BlockState, branch: String, piece: InlinePiece, role: Option[String]): BlockState = piece match
      case InlinePiece.Tok(kind, lex, r, ch) =>
        val folded = foldPending(st, VmInstruction.Reframe(closeBefore = Vector.empty, open = Vector(FrameSpec(branch)), closeAfter = Vector(branch), role = r.orElse(role)))
        emit(folded.state, kind, lex, folded.instruction, ch)
      case InlinePiece.Open(b2, kind, lex, r) =>
        val folded = foldPending(st, VmInstruction.Reframe(closeBefore = Vector.empty, open = Vector(FrameSpec(branch), FrameSpec(b2)), closeAfter = Vector(b2, branch), role = r))
        emit(folded.state, kind, lex, folded.instruction, TokenChannel.Syntax)
      case InlinePiece.Close(b2, kind, lex, r) =>
        val folded = foldPending(st, VmInstruction.Reframe(closeBefore = Vector.empty, open = Vector(FrameSpec(branch)), closeAfter = Vector(b2, branch), role = r))
        emit(folded.state, kind, lex, folded.instruction, TokenChannel.Syntax)

    /** Replay a resolved inline piece as its plain VM instruction. */
    def replay(st: BlockState, piece: InlinePiece): BlockState = piece match
      case InlinePiece.Tok(kind, lex, role, ch) => leaf(st, kind, lex, role, ch)
      case InlinePiece.Open(b2, kind, lex, role) => openBranch(st, b2, kind, lex, role)
      case InlinePiece.Close(b2, kind, lex, role) => close(st, b2, kind, lex, role)

    /** Emit a token that also applies any scheduled container closures (as a
      * `Reframe` closeBefore) plus the given `opens` frames. */
    def flushPending(st: BlockState, kind: String, lexeme: String, opens: Vector[FrameSpec], role: Option[String], channel: TokenChannel): BlockState =
      if lexeme.isEmpty then
        // can't attach transitions to nothing; keep pending for the next token
        st
      else if st.pendingClose.isEmpty && opens.isEmpty then
        leaf(st, kind, lexeme, role, channel)
      else
        val instr = VmInstruction.Reframe(closeBefore = st.pendingClose, open = opens, closeAfter = Vector.empty, role = role)
        emit(st.copy(pendingClose = Vector.empty), kind, lexeme, instr, channel)

    def foldPending(st: BlockState, reframe: VmInstruction.Reframe): PendingFold =
      PendingFold(st.copy(pendingClose = Vector.empty), reframe.copy(closeBefore = st.pendingClose ++ reframe.closeBefore))

    def finishOpenBlocks(st0: BlockState): BlockState =
      val st: BlockState = finishParagraph(st0)
      st.open match
        case OpenLeaf.FencedCode(_, _) =>
          // unterminated fence: close it, record a diagnostic
          st.copy(
            diagnostics = st.diagnostics :+ Diagnostic(
              code = "uniml.markdown.unterminated-fence",
              message = "fenced code block was not closed before end of input",
              severity = Severity.Warning,
              span = None,
              dialect = Some("markdown"),
            ),
            open = OpenLeaf.None)
        case _ => st

    // ── driver ────────────────────────────────────────────────────────────────

    val initial = BlockState(
      pos = SourcePosition.Start, nextId = 0L, out = Vector.empty, frames = Vector.empty,
      diagnostics = Vector.empty, blocksOpened = 0, blockLimitHit = None, fenceCp = 0L,
      fenceLimitHit = None, containers = Vector.empty, pendingClose = Vector.empty,
      indentedCodeBlanks = Vector.empty, indentedCodePendingPrefix = "", open = OpenLeaf.None,
      paragraphSegs = Vector.empty, paragraphPendingPrefix = "")
    // ScalaScript YAML front matter, only at the very start
    val afterFront = if scala then scanFrontMatter(initial, 0) else BlockStep(initial, 0)
    def walkLines(st: BlockState, index: Int): BlockState =
      if index >= lines.size then st
      else
        val step: BlockStep = processLine(st, index)
        walkLines(step.state, step.index)
    val ended: BlockState = closeDangling(finishOpenBlocks(walkLines(afterFront.state, afterFront.index)))
    // A block-count overflow is only knowable DURING the parse, so it is reported here rather
    // than as a pre-check. Same shape as the source and line limits: no tokens plus one fatal
    // diagnostic, because a truncated token stream would sit in a tree that looks complete.
    ended.blockLimitHit.orElse(ended.fenceLimitHit) match
      case Some(d) => MarkdownBlockResult(Vector.empty, Vector(d))
      case None    => MarkdownBlockResult(ended.out, ended.diagnostics)

  // ── link reference definitions (CommonMark 4.7) ───────────────────────────

  /** Forward references: `[foo]` may be used before `[foo]: /url` appears, so
    * definitions are collected before any inline is parsed. A PURE pre-pass:
    * nothing writes the map after it, which is why `refs` is a parameter of
    * `run` and not a field of [[BlockState]].
    *
    * It tracks whether a paragraph is open, because a definition CANNOT
    * interrupt one — `Foo\n[bar]: /baz` is two lines of one paragraph. Without
    * that, this pre-pass registered `bar` while the emitter (which does know)
    * emitted paragraph text, and the two answers met in the output as
    * `<a href="">bar</a>: /baz`. */
  private def collectReferences(lines: Vector[MdLine]): Map[String, LinkRef] =
    def step(acc: RefsAcc, i: Int): Map[String, LinkRef] =
      if i >= lines.size then acc.refs
      else
        val content: String = lines(i).content
        val trimmed: String = content.substring(MdChars.indentPrefixLength(content))
        if acc.inFence then
          if trimmed.nonEmpty && trimmed.forall(_.toInt == acc.fenceChar) && trimmed.length >= 3 then
            step(acc.copy(inFence = false), i + 1)
          else step(acc, i + 1)
        else if trimmed.startsWith("```") || trimmed.startsWith("~~~") then
          step(acc.copy(inFence = true, fenceChar = trimmed.charAt(0).toInt, inParagraph = false), i + 1)
        else if lines(i).isBlank then step(acc.copy(inParagraph = false), i + 1)
        else if !acc.inParagraph && MdChars.indentWidth(content) < 4 && trimmed.startsWith("[") then
          scanRefDef(lines, i) match
            case Some(defn0) =>
              // typed re-bind: the Rust lane mis-resolved the bare binder's type (E0609), and
              // `.get(k).nonEmpty` instead of `.contains(k)` — Map.contains does not lower on
              // an accumulator field
              val defn: RefDef = defn0
              val norm = MarkdownInlines.normalizeLabel(defn.label)
              val updated =
                if acc.refs.get(norm).isEmpty && acc.refs.size < limits.maxReferences then
                  acc.refs + (norm -> LinkRef(defn.destination, defn.title))
                else acc.refs
              step(acc.copy(refs = updated), i + defn.linesConsumed)
            case None => step(acc.copy(inParagraph = true), i + 1)
        else step(acc.copy(inParagraph = true), i + 1)
    step(RefsAcc(inFence = false, fenceChar = ' '.toInt, inParagraph = false, refs = Map.empty), 0)

  /** The ONE scan of a link reference definition, in slices whose concatenation
    * is exactly the source it consumed. Three call sites used to decide this
    * independently — the forward-reference pre-pass, the token emitter, and the
    * `isRefDefLine` classifier — and they disagreed: `Foo\n[bar]: /baz` is a
    * paragraph continuation, but the pre-pass registered `bar` anyway, so the
    * emitter rendered text while the inline resolver produced `href=""`.
    *
    * Every field is a SOURCE SLICE, not a cleaned value, so emission is a
    * concatenation and losslessness is structural rather than argued. */
  private[markdown] final case class RefDef(
      indent: String,
      labelLex: String,
      colon: String,
      afterColon: String,
      destLex: String,
      betweenDestTitle: String,
      titleLex: String,
      trailing: String,
      linesConsumed: Int,
  ):
    def label: String = labelLex.substring(1, labelLex.length - 1)
    def destination: String = MarkdownProjection.unwrapDestinationSlice(destLex)
    def title: Option[String] =
      if titleLex.isEmpty then None else Some(titleLex.substring(1, titleLex.length - 1))
    /** Exactly the text consumed — asserted against the source by the emitter. */
    def consumedText: String =
      indent + labelLex + colon + afterColon + destLex + betweenDestTitle + titleLex + trailing

  /** `scanRefDef` over the window the caller means, WITHOUT materialising it.
    *
    * `scanRefDef` already takes a start index, so `scanRefDef(lines.drop(index), 0)` and
    * `scanRefDef(lines, index)` are the same scan — but the first COPIES the tail of the line
    * vector first. On the JVM that copy is O(log n) (`Vector` shares structure); the ScalaScript
    * Rust backend lowers `Vector` to `Vec`, where it is a full O(n) copy of every `MdLine` and
    * every `String` in it. The old shape ran that copy once per LINE and then AGAIN in the branch
    * body to reach `.get`, i.e. O(n²) per document, and it sat at the top of a profile once the
    * accumulator copies were gone.
    *
    * A CLASS METHOD rather than a local def inside `parse`, deliberately: the Rust backend does
    * not yet resolve `.isDefined`/`.get` on a call to a LIFTED LOCAL def (its return type never
    * reaches the global table), so the local-def spelling emitted `no field isDefined on type
    * Option<RefDef>`. `containers` and `open` are therefore passed explicitly. The
    * `open == Paragraph` guard lives inside so the scan stays as lazy as it was in the caller's
    * `else if` chain — a paragraph line still does no work. */
  private def refDefAt(lines: Vector[MdLine], index: Int, line: MdLine, content: String,
                       containers: Vector[Container], open: OpenLeaf): Option[RefDef] =
    if open == OpenLeaf.Paragraph then None
    else if containers.isEmpty then scanRefDef(lines, index)
    else scanRefDef(Vector(MdLine(content, line.ending)), 0)

  private def scanRefDef(lines: Vector[MdLine], index: Int): Option[RefDef] =
    // A definition may not contain a blank line, so the window can never run
    // past the first one — that is also what bounds this scan.
    def lastAt(i: Int): Int = if i < lines.size && !lines(i).isBlank then lastAt(i + 1) else i
    val last = lastAt(index)
    if last == index then return None
    val window = lines.slice(index, last)
    // annotated: captured by lifted local defs (Rust lane)
    val joined: String = window.iterator.map(_.raw).mkString("")
    val n: Int = joined.length

    def isSpace(c: Char): Boolean = c == ' ' || c == '\t'
    def isBreakChar(c: Char): Boolean = c == '\n' || c == '\r'

    /** Whitespace run that may cross at most ONE line ending. -1 when it crosses
      * more, which means a blank line and therefore the end of the definition. */
    def skipWs(from: Int, maxBreaks: Int): Int =
      def walk(j: Int, breaks: Int): Int =
        if j >= n then j
        else
          val c = joined.charAt(j)
          if isSpace(c) then walk(j + 1, breaks)
          else if isBreakChar(c) then
            if breaks + 1 > maxBreaks then -1
            else if c == '\r' && j + 1 < n && joined.charAt(j + 1) == '\n' then walk(j + 2, breaks + 1)
            else walk(j + 1, breaks + 1)
          else j
      walk(from, 0)

    /** Index just past the line ending that terminates the line holding `at`. */
    def endOfLine(at: Int): Int =
      def toBreak(j: Int): Int = if j < n && !isBreakChar(joined.charAt(j)) then toBreak(j + 1) else j
      val j = toBreak(at)
      if j < n && joined.charAt(j) == '\r' && j + 1 < n && joined.charAt(j + 1) == '\n' then j + 2
      else if j < n then j + 1
      else j

    def onlySpacesTo(from: Int, to: Int): Boolean =
      from >= to ||
        ((isSpace(joined.charAt(from)) || isBreakChar(joined.charAt(from))) && onlySpacesTo(from + 1, to))

    if MdChars.indentWidth(joined) >= 4 then return None
    val labelStart = MdChars.indentPrefixLength(joined)
    if labelStart >= n || joined.charAt(labelStart) != '[' then return None

    // label: to the first UNESCAPED `]` (-2 marks an inner `[`, which rejects the definition);
    // `[Foo*bar\]]` is one label, not two
    def labelEndAt(i: Int): Int =
      if i >= n then -1
      else
        val c = joined.charAt(i)
        if c == '\\' && i + 1 < n then labelEndAt(i + 2)
        else if c == ']' then i
        else if c == '[' then -2
        else labelEndAt(i + 1)
    val labelEnd = labelEndAt(labelStart + 1)
    if labelEnd < 0 then return None
    val labelLex = joined.substring(labelStart, labelEnd + 1)
    if labelLex.length > 1002 then return None
    if labelLex.substring(1, labelLex.length - 1).trim.isEmpty then return None
    if labelEnd + 1 >= n || joined.charAt(labelEnd + 1) != ':' then return None
    val afterColonStart = labelEnd + 2

    val destStart = skipWs(afterColonStart, 1)
    if destStart < 0 || destStart >= n then return None
    val destEnd =
      if joined.charAt(destStart) == '<' then
        def angleEnd(j: Int): Int = // -1: broke before closing
          if j >= n then -1
          else
            val c = joined.charAt(j)
            if c == '\\' && j + 1 < n then angleEnd(j + 2)
            else if isBreakChar(c) || c == '<' then -1
            else if c == '>' then j
            else angleEnd(j + 1)
        val closed = angleEnd(destStart + 1)
        if closed < 0 then return None
        closed + 1
      else
        def bareEnd(j: Int, parens: Int): Int =
          if j >= n then j
          else
            val c = joined.charAt(j)
            if c == '\\' && j + 1 < n then bareEnd(j + 2, parens)
            else if isSpace(c) || isBreakChar(c) || c.toInt < 0x20 then j
            else if c == '(' then bareEnd(j + 1, parens + 1)
            else if c == ')' then
              if parens - 1 < 0 then j else bareEnd(j + 1, parens - 1)
            else bareEnd(j + 1, parens)
        val stop = bareEnd(destStart, 0)
        if stop == destStart then return None
        stop

    // Optional title. It may sit on the NEXT line, may span lines, and must be
    // followed by nothing but whitespace — otherwise it is not a title at all,
    // and the definition is only valid if the destination ended its own line.
    val titleStart = skipWs(destEnd, 1)
    val titleEnd =
      if titleStart > destEnd && titleStart < n then
        // As Int CODE POINTS, not Char: the lifted `titleClose` captures these, so they need
        // explicit types (Rust lane) — and a `Char`-annotated val desyncs the lane's SscChar
        // handling (`(open).0` on an `i64`, E0610). `.toInt` is the portable spelling MdChars
        // already uses.
        val open: Int = joined.charAt(titleStart).toInt
        val close: Int = if open == '('.toInt then ')'.toInt else open
        if open == '"'.toInt || open == '\''.toInt || open == '('.toInt then
          def titleClose(j: Int): Int = // -1: never closed, or the attempt failed
            if j >= n then -1
            else
              val c = joined.charAt(j)
              if c == '\\' && j + 1 < n then titleClose(j + 2)
              else if c == '\n' then
                // a blank line inside a title ends the definition attempt
                if j + 1 < n && lineIsBlankAt(joined, j + 1) then -1 else titleClose(j + 1)
              else if c.toInt == close then j
              else if c.toInt == open && open == '('.toInt then -1
              else titleClose(j + 1)
          val found = titleClose(titleStart + 1)
          if found >= 0 && onlySpacesTo(found + 1, endOfLine(found)) then found + 1 else -1
        else -1
      else -1

    val (bodyEnd, titleSlice, betweenSlice) =
      if titleEnd > 0 then (titleEnd, joined.substring(titleStart, titleEnd), joined.substring(destEnd, titleStart))
      else (destEnd, "", "")
    val lineEnd = endOfLine(bodyEnd)
    if !onlySpacesTo(bodyEnd, lineEnd) then return None

    val consumed = joined.substring(0, lineEnd)
    def consumedLines(used: Int, acc: Int): Int =
      if acc < lineEnd && used < window.length then consumedLines(used + 1, acc + window(used).raw.length)
      else used
    val linesUsed = consumedLines(0, 0)
    Some(RefDef(
      indent = joined.substring(0, labelStart),
      labelLex = labelLex,
      colon = ":",
      afterColon = joined.substring(afterColonStart, destStart),
      destLex = joined.substring(destStart, destEnd),
      betweenDestTitle = betweenSlice,
      titleLex = titleSlice,
      trailing = joined.substring(bodyEnd, lineEnd),
      linesConsumed = linesUsed,
    )).filter(_.consumedText == consumed)

  private def lineIsBlankAt(joined: String, from: Int): Boolean =
    def wsEnd(j: Int): Int =
      if j < joined.length && (joined.charAt(j) == ' ' || joined.charAt(j) == '\t') then wsEnd(j + 1) else j
    val j = wsEnd(from)
    j >= joined.length || joined.charAt(j) == '\n' || joined.charAt(j) == '\r'

  // ── pure classifiers / helpers (no parsing state) ─────────────────────────
  //
  // Converted var-free in the stage-10 closing pass. The holdout declared here
  // earlier allowed parking only on a measurement it did not have (v3/BACKLOG.md
  // item 10); each scan below is the SAME CommonMark clause it always mirrored,
  // as a recursion instead of a cursor loop — shape and slices unchanged.

  private def isLazyContinuation(rest: String): Boolean =
    if rest.forall(c => c == ' ' || c == '\t') then false
    else
      val t: String = rest.substring(MdChars.indentPrefixLength(rest))
      !(startsAtxHeading(t) || isThematicBreak(t) || startsFence(t).isDefined ||
        startsBlockquote(t) || startsListItem(t).isDefined ||
        htmlBlockType(t, paragraphOpen = true).isDefined || isSetextUnderline(t))

  private def stripBlockquoteMarker(content: String): Option[(String, String)] =
    val lead = MdChars.indentPrefixLength(content)
    if MdChars.indentWidth(content) <= 3 && lead < content.length && content.charAt(lead) == '>' then
      val marker = lead + 1
      val end =
        if marker < content.length && (content.charAt(marker) == ' ' || content.charAt(marker) == '\t') then marker + 1
        else marker
      Some((content.substring(0, end), content.substring(end)))
    else None

  private def consumeIndent(content: String, columns: Int): String =
    def walk(i: Int, col: Int): Int =
      if i < content.length && col < columns && (content.charAt(i) == ' ' || content.charAt(i) == '\t') then
        walk(i + 1, col + (if content.charAt(i) == '\t' then 4 - (col % 4) else 1))
      else i
    content.substring(0, walk(0, 0))

  /** Put back the continuation prefixes that belong INSIDE a piece whose lexeme swallowed the line
    * break — a code span crossing the break is the case that occurs; any inline construct whose
    * lexeme carries a raw newline has the same shape.
    *
    * Returns the rewritten piece and the number of breaks consumed so far, so the caller's counter
    * stays the index into `segs` that the between-pieces path also uses. BREAK PIECES ARE LEFT
    * ALONE: a `SoftBreak` lexeme IS the newline, and rewriting it here would insert the prefix
    * twice — once inside the lexeme and once by the caller's `isBreakPiece` branch.
    */
  private def spliceSwallowedBreaks(
      piece: InlinePiece,
      segs: Vector[ParaSeg],
      breaksSoFar: Int
  ): (InlinePiece, Int) =
    val lexeme: String = pieceLexeme(piece)
    if isBreakPiece(piece) || lexeme.indexOf('\n') < 0 then (piece, breaksSoFar)
    else
      // `Vector[String]` + `.mkString("")` — the portable accumulator (`specs/uniml-portable-gapmap.md`);
      // v2 has no StringBuilder. This one appends BOTH single chars and whole segment prefixes, and
      // a Vector of strings takes either without the two cases needing different code.
      def walk(i: Int, k: Int, out: Vector[String]): (Vector[String], Int) =
        if i >= lexeme.length then (out, k)
        else
          val c = lexeme.charAt(i)
          if c == '\n' then
            val k1 = k + 1
            val withPrefix = if k1 < segs.size then out :+ c.toString :+ segs(k1).prefix else out :+ c.toString
            walk(i + 1, k1, withPrefix)
          else walk(i + 1, k, out :+ c.toString)
      val walked = walk(0, breaksSoFar, Vector.empty)
      (withLexeme(piece, walked._1.mkString("")), walked._2)

  private def pieceLexeme(piece: InlinePiece): String = piece match
    case InlinePiece.Tok(_, lexeme, _, _)      => lexeme
    case InlinePiece.Open(_, _, lexeme, _)     => lexeme
    case InlinePiece.Close(_, _, lexeme, _)    => lexeme

  private def withLexeme(piece: InlinePiece, lexeme: String): InlinePiece = piece match
    case p: InlinePiece.Tok   => p.copy(lexeme = lexeme)
    case p: InlinePiece.Open  => p.copy(lexeme = lexeme)
    case p: InlinePiece.Close => p.copy(lexeme = lexeme)

  private def isBreakPiece(piece: InlinePiece): Boolean = piece match
    case InlinePiece.Tok(kind, _, _, _) => kind == MdKind.SoftBreak || kind == MdKind.HardBreak
    case _                              => false

  /** Index where the trailing space/tab run of `s` begins (s.length if none). */
  private def trailingWsStart(s: String): Int =
    def scan(i: Int): Int =
      if i > 0 && (s.charAt(i - 1) == ' ' || s.charAt(i - 1) == '\t') then scan(i - 1) else i
    scan(s.length)

  private def startsAtxHeading(trimmed: String): Boolean =
    val i = countRun(trimmed, '#')
    i >= 1 && i <= 6 && (i == trimmed.length || trimmed.charAt(i) == ' ' || trimmed.charAt(i) == '\t')

  private def splitAtxClosing(rest: String): (String, String) =
    // trailing run of #'s preceded by a space is the optional closing sequence
    def wsStart(e: Int): Int =
      if e > 0 && (rest.charAt(e - 1) == ' ' || rest.charAt(e - 1) == '\t') then wsStart(e - 1) else e
    def hashStart(e: Int): Int =
      if e > 0 && rest.charAt(e - 1) == '#' then hashStart(e - 1) else e
    val end = wsStart(rest.length)
    val hashEnd = hashStart(end)
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
    def scan(i: Int): Int = if i < s.length && s.charAt(i) == c then scan(i + 1) else i
    scan(0)

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
      val lower = MdChars.asciiLower(trimmed)
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
    val start = if 1 < t.length && t.charAt(1) == '/' then 2 else 1
    def nameEnd(i: Int): Int =
      if i < t.length && (MdChars.isAsciiAlnum(t.charAt(i)) || t.charAt(i) == '-') then nameEnd(i + 1) else i
    val i = nameEnd(start)
    (MdChars.asciiLower(t.substring(start, i)), i)

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
    // annotated: captured by the lifted `nameEnd` (Rust lane needs the declared type)
    val n: Int = t.length
    if n < 2 || t.charAt(0) != '<' then None
    else
      val nameStart = if 1 < n && t.charAt(1) == '/' then 2 else 1
      if nameStart >= n || !MdChars.isAsciiLetter(t.charAt(nameStart)) then None
      else
        def nameEnd(i: Int): Int =
          if i < n && (MdChars.isAsciiAlnum(t.charAt(i)) || t.charAt(i) == '-') then nameEnd(i + 1) else i
        val afterName = nameEnd(nameStart)
        // after the tag name only whitespace, '/', or '>' may follow (so e.g.
        // an autolink "<https://x>" is not a tag: ':' is not valid here)
        if afterName < n && !(t.charAt(afterName) == ' ' || t.charAt(afterName) == '\t' || t.charAt(afterName) == '/' || t.charAt(afterName) == '>') then None
        else
          def toClose(i: Int): Int = // -1 on a nested '<'
            if i >= n || t.charAt(i) == '>' then i
            else if t.charAt(i) == '<' then -1
            else toClose(i + 1)
          val i = toClose(afterName)
          if i < 0 || i >= n || t.charAt(i) != '>' then None else Some(i + 1)

  private def isTableStart(lines: Vector[MdLine], index: Int, content: String): Boolean =
    content.contains('|') && index + 1 < lines.size && isTableDelimiter(lines(index + 1).content)

  private def isTableDelimiter(content: String): Boolean =
    val t: String = content.trim
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
        def digitEnd(k: Int): Int =
          if k < trimmed.length && k < 9 && MdChars.isAsciiDigit(trimmed.charAt(k)) then digitEnd(k + 1) else k
        val i = digitEnd(0)
        if i >= 1 && i < trimmed.length && (trimmed.charAt(i) == '.' || trimmed.charAt(i) == ')') &&
          (i + 1 == trimmed.length || trimmed.charAt(i + 1) == ' ' || trimmed.charAt(i + 1) == '\t') then
          val markerCore = trimmed.substring(0, i + 1)
          val spaces = trimmed.drop(i + 1).takeWhile(ch => ch == ' ' || ch == '\t')
          Some((true, markerCore + spaces, markerCore.length + math.max(spaces.length, 1)))
        else None

  private def limitDiag(code: String, message: String): Diagnostic =
    Diagnostic(code, message, Severity.Fatal, None, Some("markdown"))
