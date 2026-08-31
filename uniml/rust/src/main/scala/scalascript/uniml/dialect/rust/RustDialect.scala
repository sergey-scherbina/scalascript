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
    var i = from
    while i < until && isTrivia(tokens(i).kind) do i += 1
    i

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
    var i = from
    var crossed = atRangeStart
    var result = -1
    while result < 0 && i < until do
      val k = tokens(i).kind
      if k == "rust.ws" then
        if tokens(i).lexeme.contains("\n") then crossed = true
        i += 1
      else if k == "rust.line-comment" || k == "rust.block-comment" then
        if crossed then result = i else i += 1
      else result = i
    if result < 0 then until else result

  /** Skip a balanced bracket group that OPENS at `from` (`from` must be the opener). Returns the
    * index just past the matching closer, or `until` if it never closes. Used for `#[…]`
    * attributes and for `pub(crate)`. */
  private def skipBalanced(
      tokens: Vector[RustLexToken], from: Int, until: Int, open: String, close: String,
  ): Int =
    var i = from
    var depth = 0
    var done = false
    while i < until && !done do
      if isPunct(tokens(i), open) then depth += 1
      else if isPunct(tokens(i), close) then
        depth -= 1
        if depth == 0 then done = true
      i += 1
    i

  /** Walk the item HEAD from `from`: doc comments, attributes and modifiers, in any order and any
    * number. Returns the index of the token that ends the head — the item keyword if this is an
    * item, otherwise whatever else was found. */
  private def skipHead(tokens: Vector[RustLexToken], from: Int, until: Int): Int =
    var i = nextSignificant(tokens, from, until)
    var going = true
    while going && i < until do
      val t = tokens(i)
      if isPunct(t, "#") then
        // `#[…]` and `#![…]` alike: find the bracket group and step over it whole.
        val br = nextSignificant(tokens, i + 1, until)
        val br2 = if br < until && isPunct(tokens(br), "!") then nextSignificant(tokens, br + 1, until) else br
        if br2 < until && isPunct(tokens(br2), "[") then
          i = nextSignificant(tokens, skipBalanced(tokens, br2, until, "[", "]"), until)
        else going = false
      else if t.kind == "rust.ident" && isModifier(t.lexeme) then
        val after = nextSignificant(tokens, i + 1, until)
        // `pub(crate)` / `pub(super)` — the visibility qualifier belongs to the modifier.
        // `extern "C"` — the ABI string likewise.
        if after < until && isPunct(tokens(after), "(") then
          i = nextSignificant(tokens, skipBalanced(tokens, after, until, "(", ")"), until)
        else if after < until && tokens(after).kind == "rust.string" then
          i = nextSignificant(tokens, after + 1, until)
        else i = after
      else going = false
    i

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
    var i = nextSignificant(tokens, kw + 1, until)
    // `impl<'a, T>` — step over a generic parameter list before the name.
    if i < until && isPunct(tokens(i), "<") then
      i = nextSignificant(tokens, skipBalanced(tokens, i, until, "<", ">"), until)
    if i < until && tokens(i).kind == "rust.ident" then tokens(i).lexeme else ""

  /** End token index (INCLUSIVE) of the item whose keyword is at `kw`: the `}` that closes its
    * body, or the `;` that ends a body-less declaration. Returns `until - 1` for an item that
    * never closes, so a truncated file still produces a well-formed (if long) last item. */
  private def itemEnd(tokens: Vector[RustLexToken], kw: Int, until: Int): Int =
    var i = kw
    var depth = 0
    var end = -1
    while i < until && end < 0 do
      val t = tokens(i)
      if isPunct(t, "{") then depth += 1
      else if isPunct(t, "}") then
        depth -= 1
        if depth <= 0 then end = i
      else if isPunct(t, ";") && depth == 0 then end = i
      i += 1
    if end < 0 then until - 1 else end

  /** Index just past the opening `{` of the item's body, or -1 when it has none. */
  private def bodyStart(tokens: Vector[RustLexToken], kw: Int, endIdx: Int): Int =
    var i = kw
    var found = -1
    while i <= endIdx && found < 0 do
      if isPunct(tokens(i), "{") then found = i + 1
      i += 1
    found

  /** Every item in `[from, until)`, in source order, parents immediately followed by the items
    * nested in their bodies — which is the order the instruction walk below needs, and is
    * sorted by `start` because a child always starts after its parent's `{`. */
  def collect(tokens: Vector[RustLexToken], from: Int, until: Int, depth: Int): Vector[ItemSpan] =
    var out: Vector[ItemSpan] = Vector.empty
    var i = from
    while i < until do
      val headStart = headStartIndex(tokens, i, until, i == from)
      if headStart >= until then i = until
      else
        val kwRaw = skipHead(tokens, headStart, until)
        val kw = if kwRaw < until then resolveKeyword(tokens, kwRaw, until) else kwRaw
        val kind = if kw < until && tokens(kw).kind == "rust.ident" then itemKind(tokens(kw).lexeme) else ""
        if kind == "" then
          // Not an item head. Step over whatever this is — a statement, a stray token — up to the
          // next `;` or the end of a balanced brace group, so the scan resynchronises instead of
          // treating every following token as a fresh candidate.
          val skipTo = itemEnd(tokens, headStart, until)
          i = if skipTo >= headStart then skipTo + 1 else headStart + 1
        else
          val end = itemEnd(tokens, kw, until)
          val bs = bodyStart(tokens, kw, end)
          out = out :+ ItemSpan(headStart, end, kind, itemName(tokens, kw, until), if bs < 0 then -1 else bs - 1)
          // One nesting level, per the spec: an `impl` or `mod` body holds items a reader cites
          // directly, deeper nesting produces chunks too small to rank.
          if depth == 0 && (kind == "rust.impl" || kind == "rust.mod") then
            if bs >= 0 && bs < end then out = out ++ collect(tokens, bs, end, depth + 1)
          i = end + 1
    out

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
    var out: Vector[VmToken] = Vector.empty
    var position = SourcePosition.Start
    var nextTokenId = 0L
    var nextItem = 0
    // The items currently open, innermost last: `open` holds their end indices and `openBody` the
    // matching `{` positions. `collect` returns parents immediately before the items nested in
    // them and every child closes before its parent, so plain stacks are enough — no lookup
    // structure and no second pass.
    var open: Vector[Int] = Vector.empty
    var openBody: Vector[Int] = Vector.empty
    var i = 0
    while i < lexed.length do
      var lexeme = lexed(i).lexeme
      var kind = lexed(i).kind
      var instruction: VmInstruction = VmInstruction.Emit()
      if nextItem < items.length && items(nextItem).start == i then
        instruction = VmInstruction.Open(items(nextItem).kind)
        open = open :+ items(nextItem).end
        openBody = openBody :+ items(nextItem).bodyOpen
        nextItem += 1
        i += 1
      else if open.nonEmpty && open.last == i then
        instruction = VmInstruction.Close()
        open = open.dropRight(1)
        openBody = openBody.dropRight(1)
        i += 1
      else if open.nonEmpty && openBody.last >= 0 && i <= openBody.last then
        // Still in the item's HEAD — its doc comments, attributes and signature, through the `{`.
        i += 1
      else
        // A BODY, or the gap between two items: one token for the whole run. It ends where the
        // next item begins or where the enclosing item closes, whichever comes first, so no
        // boundary token is ever swallowed.
        var limit = lexed.length
        if nextItem < items.length && items(nextItem).start < limit then limit = items(nextItem).start
        if open.nonEmpty && open.last < limit then limit = open.last
        if limit <= i then i += 1
        else
          // Accumulated as pieces and joined once: `s = s + piece` in a loop would copy the whole
          // string every time, which is the same shape of quadratic this coalescing exists to
          // remove.
          var pieces: Vector[String] = Vector.empty
          while i < limit do
            pieces = pieces :+ lexed(i).lexeme
            i += 1
          kind = "rust.span"
          lexeme = pieces.mkString
      val start = position
      val end = Unicode.advance(start, lexeme)
      position = end
      val channel =
        if kind == "rust.ws" then TokenChannel.Trivia
        else if kind == "rust.line-comment" || kind == "rust.block-comment" then TokenChannel.Comment
        else TokenChannel.Syntax
      val token = SourceToken(
        id = nextTokenId,
        kind = kind,
        lexeme = lexeme,
        span = SourceSpan(source, start, end),
        channel = channel,
      )
      nextTokenId += 1
      out = out :+ VmToken(token, instruction)
    ProcessBatch(out, Vector.empty)
