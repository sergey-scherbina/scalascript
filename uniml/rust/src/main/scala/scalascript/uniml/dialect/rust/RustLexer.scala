package scalascript.uniml.dialect.rust

/** One lexical token: its kind plus the EXACT source slice it covers.
  *
  * Losslessness is the invariant everything else rests on — concatenating every `lexeme` in
  * order reproduces the input byte for byte — because the chunker slices the ORIGINAL text by
  * the spans these tokens carry. A lexer that normalised anything (collapsed whitespace, dropped
  * a comment) would silently shift every span after it.
  */
final case class RustLexToken(kind: String, lexeme: String)

/** A structural lexer for Rust. It does NOT parse Rust: it exists so that a brace-matching pass
  * above it can tell a real `{` from one inside a string, a char literal or a comment. That is
  * the whole job, and keeping it to that is what keeps it small enough to be correct.
  */
object RustLexer:

  // ASCII only, deliberately. Rust allows non-ASCII identifiers; treating those code points as
  // `rust.punct` keeps the lexer lossless (every char still lands in exactly one token) and costs
  // only a slightly noisier token stream in a file that uses them, which the brace machine above
  // does not care about. Recognising unicode identifier classes would need tables this dialect has
  // no other use for.
  private def isIdentStart(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_'

  private def isDigit(c: Char): Boolean = c >= '0' && c <= '9'

  private def isIdentPart(c: Char): Boolean = isIdentStart(c) || isDigit(c)

  private def isSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'

  /** End (exclusive) of a `"…"` or `b"…"` string opened at the quote `from`. An unterminated
    * string runs to end of input rather than failing: this lexer never rejects, it only ever
    * describes, and the structure pass above decides what a malformed file means. */
  private def plainStringEnd(chars: Vector[Char], from: Int, n: Int): Int =
    var i = from + 1
    var closed = false
    while i < n && !closed do
      val c = chars(i)
      if c == '\\' then i += 2
      else if c == '"' then
        i += 1
        closed = true
      else i += 1
    if i > n then n else i

  /** End (exclusive) of a char literal opened at `from`. Same escape handling, same
    * run-to-the-end tolerance. */
  private def charLiteralEnd(chars: Vector[Char], from: Int, n: Int): Int =
    var i = from + 1
    var closed = false
    while i < n && !closed do
      val c = chars(i)
      if c == '\\' then i += 2
      else if c == '\'' then
        i += 1
        closed = true
      else i += 1
    if i > n then n else i

  /** How many `#` a raw string opened at `from` uses, or -1 when `from` does not start one.
    *
    * `r"…"`, `r#"…"#`, `br##"…"##` — the hash count is what terminates it, which is exactly why
    * a regex cannot do this job: the closing delimiter is chosen by the opening one. `r#ident`
    * (a RAW IDENTIFIER, not a string) is rejected here by requiring the quote, and falls through
    * to the identifier path. */
  private def rawStringHashes(chars: Vector[Char], from: Int, n: Int): Int =
    var i = from
    if i < n && chars(i) == 'b' then i += 1
    if i >= n || chars(i) != 'r' then -1
    else
      i += 1
      var hashes = 0
      while i < n && chars(i) == '#' do
        hashes += 1
        i += 1
      if i < n && chars(i) == '"' then hashes else -1

  /** End (exclusive) of the raw string opened at `from` with `hashes` hashes. */
  private def rawStringEnd(chars: Vector[Char], from: Int, n: Int, hashes: Int): Int =
    var i = from
    if i < n && chars(i) == 'b' then i += 1
    i += 1 // 'r'
    i += hashes
    i += 1 // opening quote
    var closed = false
    while i < n && !closed do
      if chars(i) == '"' then
        var k = i + 1
        var matched = 0
        while matched < hashes && k < n && chars(k) == '#' do
          matched += 1
          k += 1
        if matched == hashes then
          i = k
          closed = true
        else i += 1
      else i += 1
    if i > n then n else i

  /** End (exclusive) of a block comment opened at `from`, honouring Rust's NESTING. */
  private def blockCommentEnd(chars: Vector[Char], from: Int, n: Int): Int =
    var i = from + 2
    var depth = 1
    while i < n && depth > 0 do
      if i + 1 < n && chars(i) == '/' && chars(i + 1) == '*' then
        depth += 1
        i += 2
      else if i + 1 < n && chars(i) == '*' && chars(i + 1) == '/' then
        depth -= 1
        i += 2
      else i += 1
    if i > n then n else i

  /** Is the quote at `from` a LIFETIME (`'a`, `'static`, `'_`) rather than a char literal?
    *
    * The distinguishing test is the character AFTER the identifier start: `'a'` closes, so it is
    * a char; `'a>` and `'a,` do not, so they are lifetimes. Getting this wrong is not cosmetic —
    * reading `'a` as an unterminated char literal would swallow the rest of the file up to the
    * next quote, taking every brace in between with it. */
  private def isLifetime(chars: Vector[Char], from: Int, n: Int): Boolean =
    if from + 1 >= n then false
    else if !isIdentStart(chars(from + 1)) then false
    else if from + 2 >= n then true
    else chars(from + 2) != '\''

  def lex(text: String): Vector[RustLexToken] =
    // INDEX THE CODE UNITS, NOT THE STRING. `charAt`/`length` are O(1) on the JVM but not on the
    // ScalaScript Rust backend, which stores a String as UTF-8 and emulates JVM code-unit
    // indexing over it — an index-based scan of a string is O(n²) there. One `toVector` pays that
    // conversion once and every index after it is a vector index. This is the same hoist that
    // took the markdown parse from 173 s to 0.28 s on a 256 KB input.
    val chars = text.toVector
    val n = chars.length
    var out: Vector[RustLexToken] = Vector.empty
    var i = 0
    while i < n do
      val start = i
      val c = chars(i)
      var kind = "rust.punct"
      val hashes = if c == 'r' || c == 'b' then rawStringHashes(chars, i, n) else -1
      if isSpace(c) then
        kind = "rust.ws"
        while i < n && isSpace(chars(i)) do i += 1
      else if c == '/' && i + 1 < n && chars(i + 1) == '/' then
        kind = "rust.line-comment"
        // Stops BEFORE the newline, which the following whitespace token then carries — so a
        // line comment never swallows the line break that ends it.
        while i < n && chars(i) != '\n' do i += 1
      else if c == '/' && i + 1 < n && chars(i + 1) == '*' then
        kind = "rust.block-comment"
        i = blockCommentEnd(chars, i, n)
      else if hashes >= 0 then
        kind = "rust.string"
        i = rawStringEnd(chars, i, n, hashes)
      else if c == '"' then
        kind = "rust.string"
        i = plainStringEnd(chars, i, n)
      else if c == 'b' && i + 1 < n && chars(i + 1) == '"' then
        kind = "rust.string"
        i = plainStringEnd(chars, i + 1, n)
      else if c == 'b' && i + 2 < n && chars(i + 1) == '\'' then
        kind = "rust.char"
        i = charLiteralEnd(chars, i + 1, n)
      else if c == '\'' && isLifetime(chars, i, n) then
        kind = "rust.lifetime"
        i += 1
        while i < n && isIdentPart(chars(i)) do i += 1
      else if c == '\'' then
        kind = "rust.char"
        i = charLiteralEnd(chars, i, n)
      else if isDigit(c) then
        kind = "rust.number"
        i += 1
        var going = true
        while i < n && going do
          val d = chars(i)
          // A `.` continues the number only when a digit follows, so `1.0` is one token while
          // the `..` in `0..n` stays punctuation and cannot be mistaken for part of a literal.
          if isIdentPart(d) then i += 1
          else if d == '.' && i + 1 < n && isDigit(chars(i + 1)) then i += 1
          else going = false
      else if isIdentStart(c) then
        kind = "rust.ident"
        while i < n && isIdentPart(chars(i)) do i += 1
      else i += 1
      // Every branch above advances `i` by at least one, so this cannot spin.
      out = out :+ RustLexToken(kind, text.substring(start, i))
    out
