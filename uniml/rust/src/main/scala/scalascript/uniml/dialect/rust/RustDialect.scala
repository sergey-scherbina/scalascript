package scalascript.uniml.dialect.rust

import scalascript.uniml.*

/** The source range of one Rust item, in TOKEN indices: `[start, end]` inclusive.
  *
  * `start` is the first token of the item's HEAD — its doc comments and attributes, not the
  * keyword — so a chunk carries the prose that explains it. `name` is best-effort and is used
  * only to build a citation id; a wrong name is a worse label, never a wrong boundary.
  *
  * `bodyOpen` is the index of the `{` that opens the item's body, or -1 for a body-less
  * declaration. It is what separates the head — kept token by token, because the chunker reads
  * the item's name back out of it — from the body, which is emitted as one token. */
private final case class ItemSpan(start: Int, end: Int, kind: String, name: String, bodyOpen: Int)

/** Structural item finder: a brace-depth machine over the lexed token stream.
  *
  * This does NOT parse Rust. It answers one question — where does each item begin and end — and
  * it needs the lexer only because a `{` inside a string, a char or a comment must not count.
  */
private object RustStructure:

  private def isModifier(w: String): Boolean =
    w == "pub" || w == "async" || w == "unsafe" || w == "extern" || w == "default" || w == "move"

  private def itemKind(w: String): String =
    if w == "fn" then "rust.fn"
    else if w == "struct" then "rust.struct"
    else if w == "enum" then "rust.enum"
    else if w == "trait" then "rust.trait"
    else if w == "impl" then "rust.impl"
    else if w == "mod" then "rust.mod"
    else if w == "use" then "rust.use"
    else if w == "const" then "rust.const"
    else if w == "static" then "rust.const"
    else if w == "type" then "rust.type"
    else if w == "union" then "rust.struct"
    else if w == "macro_rules" then "rust.macro"
    else ""

  private def isTrivia(kind: String): Boolean =
    kind == "rust.ws" || kind == "rust.line-comment" || kind == "rust.block-comment"

  private def isPunct(t: RustLexToken, s: String): Boolean =
    t.kind == "rust.punct" && t.lexeme == s

  /** Index of the next non-trivia token at or after `from`, or `until` when there is none. */
  private def nextSignificant(tokens: Vector[RustLexToken], from: Int, until: Int): Int =
    if from < until && isTrivia(tokens(from).kind) then nextSignificant(tokens, from + 1, until) else from

  /** First token of an item HEAD at or after `from`: whitespace is skipped, but a COMMENT is
    * part of the head — that is how a doc comment ends up inside the chunk it explains.
    *
    * The one subtlety is a TRAILING comment: in `fn a() {} // note` the comment belongs to the
    * line it sits on, not to whatever follows, so a comment is only taken as a head start once a
    * line break has been crossed. `atRangeStart` treats the very first token of the range as
    * already past a break, so a file (or an `impl` body) that opens with a comment still attaches
    * it to the item that follows.
    */
  private def headStartIndex(
      tokens: Vector[RustLexToken], from: Int, until: Int, atRangeStart: Boolean,
  ): Int =
    def scan(i: Int, crossed: Boolean): Int =
      if i >= until then until
      else
        val k = tokens(i).kind
        if k == "rust.ws" then scan(i + 1, crossed || tokens(i).lexeme.contains("\n"))
        else if k == "rust.line-comment" || k == "rust.block-comment" then
          if crossed then i else scan(i + 1, crossed)
        else i
    scan(from, atRangeStart)

  /** Skip a balanced bracket group that OPENS at `from` (`from` must be the opener). Returns the
    * index just past the matching closer, or `until` if it never closes. Used for `#[…]`
    * attributes and for `pub(crate)`. */
  private def skipBalanced(
      tokens: Vector[RustLexToken], from: Int, until: Int, open: String, close: String,
  ): Int =
    def scan(i: Int, depth: Int): Int =
      if i >= until then i
      else if isPunct(tokens(i), open) then scan(i + 1, depth + 1)
      else if isPunct(tokens(i), close) then
        if depth - 1 == 0 then i + 1 else scan(i + 1, depth - 1)
      else scan(i + 1, depth)
    scan(from, 0)

  /** Walk the item HEAD from `from`: doc comments, attributes and modifiers, in any order and any
    * number. Returns the index of the token that ends the head — the item keyword if this is an
    * item, otherwise whatever else was found. */
  private def skipHead(tokens: Vector[RustLexToken], from: Int, until: Int): Int =
    def loop(i: Int): Int =
      if i >= until then i
      else
        val t = tokens(i)
        if isPunct(t, "#") then
          // `#[…]` and `#![…]` alike: find the bracket group and step over it whole.
          val br = nextSignificant(tokens, i + 1, until)
          val br2 = if br < until && isPunct(tokens(br), "!") then nextSignificant(tokens, br + 1, until) else br
          if br2 < until && isPunct(tokens(br2), "[") then
            loop(nextSignificant(tokens, skipBalanced(tokens, br2, until, "[", "]"), until))
          else i
        else if t.kind == "rust.ident" && isModifier(t.lexeme) then
          val after = nextSignificant(tokens, i + 1, until)
          // `pub(crate)` / `pub(super)` — the visibility qualifier belongs to the modifier.
          // `extern "C"` — the ABI string likewise.
          if after < until && isPunct(tokens(after), "(") then
            loop(nextSignificant(tokens, skipBalanced(tokens, after, until, "(", ")"), until))
          else if after < until && tokens(after).kind == "rust.string" then
            loop(nextSignificant(tokens, after + 1, until))
          else loop(after)
        else i
    loop(nextSignificant(tokens, from, until))

  /** The item keyword at `kw`, resolved. `const`/`static` are MODIFIERS when a `fn` follows
    * (`const fn new()`), and items otherwise (`const MAX: usize = 8;`) — the same word doing two
    * jobs, which is why this cannot be a plain lookup. */
  private def resolveKeyword(tokens: Vector[RustLexToken], kw: Int, until: Int): Int =
    val w = tokens(kw).lexeme
    if w == "const" || w == "static" then
      val after = nextSignificant(tokens, kw + 1, until)
      if after < until && tokens(after).kind == "rust.ident" && tokens(after).lexeme == "fn" then after
      else kw
    else kw

  /** Best-effort item name: the first identifier after the keyword that is not a keyword itself.
    * For `impl` this lands on the trait or type, which is what a reader would cite. */
  private def itemName(tokens: Vector[RustLexToken], kw: Int, until: Int): String =
    val at = nextSignificant(tokens, kw + 1, until)
    // `impl<'a, T>` — step over a generic parameter list before the name.
    val i =
      if at < until && isPunct(tokens(at), "<") then
        nextSignificant(tokens, skipBalanced(tokens, at, until, "<", ">"), until)
      else at
    if i < until && tokens(i).kind == "rust.ident" then tokens(i).lexeme else ""

  /** End token index (INCLUSIVE) of the item whose keyword is at `kw`: the `}` that closes its
    * body, or the `;` that ends a body-less declaration. Returns `until - 1` for an item that
    * never closes, so a truncated file still produces a well-formed (if long) last item. */
  private def itemEnd(tokens: Vector[RustLexToken], kw: Int, until: Int): Int =
    def scan(i: Int, depth: Int): Int =
      if i >= until then until - 1
      else
        val t = tokens(i)
        if isPunct(t, "{") then scan(i + 1, depth + 1)
        else if isPunct(t, "}") then
          if depth - 1 <= 0 then i else scan(i + 1, depth - 1)
        else if isPunct(t, ";") && depth == 0 then i
        else scan(i + 1, depth)
    scan(kw, 0)

  /** Index just past the opening `{` of the item's body, or -1 when it has none. */
  private def bodyStart(tokens: Vector[RustLexToken], kw: Int, endIdx: Int): Int =
    def scan(i: Int): Int =
      if i > endIdx then -1
      else if isPunct(tokens(i), "{") then i + 1
      else scan(i + 1)
    scan(kw)

  /** Every item in `[from, until)`, in source order, parents immediately followed by the items
    * nested in their bodies — which is the order the instruction walk below needs, and is
    * sorted by `start` because a child always starts after its parent's `{`. */
  def collect(tokens: Vector[RustLexToken], from: Int, until: Int, depth: Int): Vector[ItemSpan] =
    def loop(i: Int, out: Vector[ItemSpan]): Vector[ItemSpan] =
      if i >= until then out
      else
        val headStart = headStartIndex(tokens, i, until, i == from)
        if headStart >= until then out
        else
          val kwRaw = skipHead(tokens, headStart, until)
          val kw = if kwRaw < until then resolveKeyword(tokens, kwRaw, until) else kwRaw
          val kind = if kw < until && tokens(kw).kind == "rust.ident" then itemKind(tokens(kw).lexeme) else ""
          if kind == "" then
            // Not an item head. Step over whatever this is — a statement, a stray token — up to the
            // next `;` or the end of a balanced brace group, so the scan resynchronises instead of
            // treating every following token as a fresh candidate.
            val skipTo = itemEnd(tokens, headStart, until)
            loop(if skipTo >= headStart then skipTo + 1 else headStart + 1, out)
          else
            val end = itemEnd(tokens, kw, until)
            val bs = bodyStart(tokens, kw, end)
            val withItem = out :+ ItemSpan(headStart, end, kind, itemName(tokens, kw, until), if bs < 0 then -1 else bs - 1)
            // One nesting level, per the spec: an `impl` or `mod` body holds items a reader cites
            // directly, deeper nesting produces chunks too small to rank.
            val withKids =
              if depth == 0 && (kind == "rust.impl" || kind == "rust.mod") && bs >= 0 && bs < end then
                withItem ++ collect(tokens, bs, end, depth + 1)
              else withItem
            loop(end + 1, withKids)
    loop(from, Vector.empty)

object RustDialect extends DialectAdapter:
  val id: String = "uniml.rust"

  override val aliases: Set[String] = Set("rust", "rs")

  def instructions(source: SourceInput): Processor[String, SourceChunk, VmToken] =
    RustProcessor(source.source)

/** Accumulates the source (chunk-invariant, as `Literal` is) and does all the work at `stop`:
  * lex, find item boundaries, then assign one VM instruction per token.
  *
  * The item NAME is deliberately not carried on the instruction: `Open`'s `role` lands on the
  * EDGE that attaches a closed frame to its parent (`buildBranch` keeps only `kind`), so a
  * TOP-LEVEL item — exactly the case the chunker cares about — would lose it. The name is
  * recoverable from the branch's own tokens instead, which is where the chunker reads it.
  */
/** The Rust emit walk's threaded state (was six `var`s in `stop`). */
private final case class RustEmit(
    out: Vector[VmToken],
    position: SourcePosition,
    nextTokenId: Long,
    nextItem: Int,
    open: Vector[Int],
    openBody: Vector[Int],
)

private final case class RustProcessor(source: SourceId) extends Processor[String, SourceChunk, VmToken]:
  def start: String = ""

  def step(state: String, input: SourceChunk): Stepped[String, VmToken] =
    Stepped(state + input.text, ProcessBatch.empty)

  def stop(text: String): ProcessBatch[VmToken] =
    val lexed = RustLexer.lex(text)
    val items = RustStructure.collect(lexed, 0, lexed.length, 0)
    // Which token indices must reach the VM ON THEIR OWN. Everything else is run together into
    // one token per stretch, and the reason is a hard cost, not tidiness: `TreeVm.addTop` rebuilds
    // the open frame on every token, so on the ScalaScript Rust backend — where a `Vector` is a
    // plain vector and `edges :+ edge` copies it, deeply, because an edge owns its subtree — a
    // frame holding k tokens costs O(k²). A markdown block holds a handful of tokens and never
    // notices; a Rust function body holds thousands. MEASURED at ~18 KB of source: 400 small
    // functions 1.57 s, eight large ones 3.60 s, ONE function 40.85 s — same bytes, and the only
    // variable is how many tokens sit in one frame.
    //
    // A structural chunker does not need a node per token inside a body: it slices the source by
    // the item's span. So the HEAD is kept token by token — the chunker reads the item's name back
    // out of those tokens, and a signature is worth keeping legible — while the BODY becomes a
    // single token. That is what makes the token stream proportional to the STRUCTURE rather than
    // to the source, and it leaves losslessness untouched: the runs are concatenated, never
    // summarised, so the lexemes still reproduce the file byte for byte.
    // The emit walk's state. The items currently open, innermost last: `open` holds their end
    // indices and `openBody` the matching `{` positions. `collect` returns parents immediately
    // before the items nested in them and every child closes before its parent, so plain stacks
    // are enough — no lookup structure and no second pass.
    def walk(st: RustEmit, i: Int): RustEmit =
      if i >= lexed.length then st
      else
        def emit(st2: RustEmit, kind: String, lexeme: String, instruction: VmInstruction, iNext: Int): RustEmit =
          val startPos = st2.position
          val endPos = Unicode.advance(startPos, lexeme)
          val channel =
            if kind == "rust.ws" then TokenChannel.Trivia
            else if kind == "rust.line-comment" || kind == "rust.block-comment" then TokenChannel.Comment
            else TokenChannel.Syntax
          val token = SourceToken(
            id = st2.nextTokenId,
            kind = kind,
            lexeme = lexeme,
            span = SourceSpan(source, startPos, endPos),
            channel = channel,
          )
          walk(st2.copy(
            out = st2.out :+ VmToken(token, instruction),
            position = endPos,
            nextTokenId = st2.nextTokenId + 1,
          ), iNext)
        if st.nextItem < items.length && items(st.nextItem).start == i then
          val item = items(st.nextItem)
          emit(st.copy(nextItem = st.nextItem + 1, open = st.open :+ item.end, openBody = st.openBody :+ item.bodyOpen),
            lexed(i).kind, lexed(i).lexeme, VmInstruction.Open(item.kind), i + 1)
        else if st.open.nonEmpty && st.open.last == i then
          emit(st.copy(open = st.open.dropRight(1), openBody = st.openBody.dropRight(1)),
            lexed(i).kind, lexed(i).lexeme, VmInstruction.Close(), i + 1)
        else if st.open.nonEmpty && st.openBody.last >= 0 && i <= st.openBody.last then
          // Still in the item's HEAD — its doc comments, attributes and signature, through the `{`.
          emit(st, lexed(i).kind, lexed(i).lexeme, VmInstruction.Emit(), i + 1)
        else
          // A BODY, or the gap between two items: one token for the whole run. It ends where the
          // next item begins or where the enclosing item closes, whichever comes first, so no
          // boundary token is ever swallowed.
          val itemLimit =
            if st.nextItem < items.length && items(st.nextItem).start < lexed.length then items(st.nextItem).start
            else lexed.length
          val limit = if st.open.nonEmpty && st.open.last < itemLimit then st.open.last else itemLimit
          if limit <= i then emit(st, lexed(i).kind, lexed(i).lexeme, VmInstruction.Emit(), i + 1)
          else
            // Accumulated as pieces and joined once: `s = s + piece` in a loop would copy the whole
            // string every time, which is the same shape of quadratic this coalescing exists to
            // remove.
            def gather(k: Int, pieces: Vector[String]): Vector[String] =
              if k < limit then gather(k + 1, pieces :+ lexed(k).lexeme) else pieces
            emit(st, "rust.span", gather(i, Vector.empty).mkString, VmInstruction.Emit(), limit)
    val done = walk(RustEmit(Vector.empty, SourcePosition.Start, 0L, 0, Vector.empty, Vector.empty), 0)
    ProcessBatch(done.out, Vector.empty)
