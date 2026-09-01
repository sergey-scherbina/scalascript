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
    def scan(i: Int): Int =
      if i >= n then n
      else
        val c = chars(i)
        if c == '\\' then scan(i + 2)
        else if c == '"' then i + 1
        else scan(i + 1)
    scan(from + 1)

  /** End (exclusive) of a char literal opened at `from`. Same escape handling, same
    * run-to-the-end tolerance. */
  private def charLiteralEnd(chars: Vector[Char], from: Int, n: Int): Int =
    def scan(i: Int): Int =
      if i >= n then n
      else
        val c = chars(i)
        if c == '\\' then scan(i + 2)
        else if c == '\'' then i + 1
        else scan(i + 1)
    scan(from + 1)

  /** How many `#` a raw string opened at `from` uses, or -1 when `from` does not start one.
    *
    * `r"…"`, `r#"…"#`, `br##"…"##` — the hash count is what terminates it, which is exactly why
    * a regex cannot do this job: the closing delimiter is chosen by the opening one. `r#ident`
    * (a RAW IDENTIFIER, not a string) is rejected here by requiring the quote, and falls through
    * to the identifier path. */
  private def rawStringHashes(chars: Vector[Char], from: Int, n: Int): Int =
    val afterB = if from < n && chars(from) == 'b' then from + 1 else from
    if afterB >= n || chars(afterB) != 'r' then -1
    else
      def hashEnd(i: Int): Int = if i < n && chars(i) == '#' then hashEnd(i + 1) else i
      val he = hashEnd(afterB + 1)
      if he < n && chars(he) == '"' then he - (afterB + 1) else -1

  /** End (exclusive) of the raw string opened at `from` with `hashes` hashes. */
  private def rawStringEnd(chars: Vector[Char], from: Int, n: Int, hashes: Int): Int =
    val afterB = if from < n && chars(from) == 'b' then from + 1 else from
    val bodyStart = afterB + 1 + hashes + 1 // 'r', the hashes, the opening quote
    def hashRun(k: Int, matched: Int): Int =
      if matched < hashes && k < n && chars(k) == '#' then hashRun(k + 1, matched + 1) else k
    def scan(i: Int): Int =
      if i >= n then n
      else if chars(i) == '"' then
        val k = hashRun(i + 1, 0)
        if k - (i + 1) == hashes then k else scan(i + 1)
      else scan(i + 1)
    scan(bodyStart)

  /** End (exclusive) of a block comment opened at `from`, honouring Rust's NESTING. */
  private def blockCommentEnd(chars: Vector[Char], from: Int, n: Int): Int =
    def scan(i: Int, depth: Int): Int =
      if i >= n then n
      else if depth == 0 then i
      else if i + 1 < n && chars(i) == '/' && chars(i + 1) == '*' then scan(i + 2, depth + 1)
      else if i + 1 < n && chars(i) == '*' && chars(i + 1) == '/' then scan(i + 2, depth - 1)
      else scan(i + 1, depth)
    scan(from + 2, 1)

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

  /** Result of lexing one window: the tokens that fit ENTIRELY inside it, and how many code units
    * of the window they cover. `consumed == 0` means not even one token fit and the caller must
    * retry with a larger window. */
  private final case class WindowLex(tokens: Vector[RustLexToken], consumed: Int)

  /** How much source one window covers. See `lex` for why this is a window at all and why the
    * size is a balance rather than "as big as possible". */
  private val WindowCodeUnits = 1024

  /** Lexes source in WINDOWS, because on the ScalaScript Rust backend the cost of a token is set
    * by the length of the string it is sliced from, not by the token's own length.
    *
    * That backend stores a String as UTF-8 and emulates JVM code-unit indexing over it, so
    * `s.substring(a, b)` is O(b): it can answer from a byte slice only while the prefix is ASCII,
    * and otherwise walks the whole string as UTF-16. One non-ASCII character — an em dash in a
    * comment, which is most of this repo's Rust — puts every later slice on that path. Slicing
    * each token straight out of the file therefore costs O(offset) per token and O(n²) overall.
    * MEASURED, before this: 7.6 KB took 0.175 s, 40 KB took 3.18 s (5× the input, 18× the time),
    * and a 200 KB file had not finished after two minutes.
    *
    * Hoisting `toVector` — the fix that made the markdown lexer linear — does not help here. It
    * makes SCANNING O(1) per character, and the scan below is already linear; what remains is
    * building each token's lexeme, and a lexeme has to come from a slice of the source. Building
    * it from characters instead is not open to us: v2 has no Char box, so stringifying a matched
    * character renders its code point's decimal digits (verified — `"abc"` yields `979899`).
    *
    * So we bound the string being sliced FROM instead. Each window is extracted with one
    * `substring` of the whole text and every lexeme is then sliced out of that window, making a
    * token cost O(window) rather than O(offset). The size trades the two costs off: extraction is
    * O(n²/W) in total, token slicing O(nW/4) at Rust's roughly four characters per token, which
    * balance near W = 2√n — about 900 for a 200 KB file, so 1024 is the round number that sits
    * right for the range of file sizes that matter.
    *
    * A token that would run past the window end is NOT emitted — it might continue past the
    * boundary — so the window is retried at double the size until the token fits. That keeps the
    * split invisible: the tokens produced are exactly the tokens of an unwindowed lex, and
    * concatenating their lexemes still reproduces the source byte for byte.
    */
  def lex(text: String): Vector[RustLexToken] =
    val total = text.length
    def place(pos: Int, span: Int, out: Vector[RustLexToken]): Vector[RustLexToken] =
      if pos >= total then out
      else
        val atEof = pos + span >= total
        val end = if atEof then total else pos + span
        val window = lexWindow(text.substring(pos, end), atEof)
        if window.consumed > 0 then place(pos + window.consumed, WindowCodeUnits, out ++ window.tokens)
        else if atEof then
          // Unreachable: at end of input every token is complete, and `pos < total` guarantees the
          // window is non-empty, so at least one token is always emitted. Kept as a spin guard.
          out
        else place(pos, span * 2, out)
    place(0, WindowCodeUnits, Vector.empty)

  /** One token's classification: its kind and END index in the window. */
  private final case class TokenAt(kind: String, end: Int)

  private def lexWindow(text: String, atEof: Boolean): WindowLex =
    // INDEX THE CODE UNITS, NOT THE STRING: `charAt`/`length` are O(1) on the JVM but O(i) on the
    // Rust backend, so an index-based scan written the ordinary way is quadratic there. One
    // `toVector` pays that conversion once and every index after it is a vector index.
    val chars = text.toVector
    val n = chars.length
    def spaceEnd(i: Int): Int = if i < n && isSpace(chars(i)) then spaceEnd(i + 1) else i
    def lineCommentEnd(i: Int): Int = if i < n && chars(i) != '\n' then lineCommentEnd(i + 1) else i
    def identEnd(i: Int): Int = if i < n && isIdentPart(chars(i)) then identEnd(i + 1) else i
    def numberEnd(i: Int): Int =
      if i >= n then i
      else
        val d = chars(i)
        // A `.` continues the number only when a digit follows, so `1.0` is one token while
        // the `..` in `0..n` stays punctuation and cannot be mistaken for part of a literal.
        if isIdentPart(d) then numberEnd(i + 1)
        else if d == '.' && i + 1 < n && isDigit(chars(i + 1)) then numberEnd(i + 1)
        else i
    def tokenAt(i: Int): TokenAt =
      val c = chars(i)
      val hashes = if c == 'r' || c == 'b' then rawStringHashes(chars, i, n) else -1
      if isSpace(c) then TokenAt("rust.ws", spaceEnd(i + 1))
      // A line comment stops BEFORE the newline, which the following whitespace token then
      // carries — so a line comment never swallows the line break that ends it.
      else if c == '/' && i + 1 < n && chars(i + 1) == '/' then TokenAt("rust.line-comment", lineCommentEnd(i))
      else if c == '/' && i + 1 < n && chars(i + 1) == '*' then TokenAt("rust.block-comment", blockCommentEnd(chars, i, n))
      else if hashes >= 0 then TokenAt("rust.string", rawStringEnd(chars, i, n, hashes))
      else if c == '"' then TokenAt("rust.string", plainStringEnd(chars, i, n))
      else if c == 'b' && i + 1 < n && chars(i + 1) == '"' then TokenAt("rust.string", plainStringEnd(chars, i + 1, n))
      else if c == 'b' && i + 2 < n && chars(i + 1) == '\'' then TokenAt("rust.char", charLiteralEnd(chars, i + 1, n))
      else if c == '\'' && isLifetime(chars, i, n) then TokenAt("rust.lifetime", identEnd(i + 1))
      else if c == '\'' then TokenAt("rust.char", charLiteralEnd(chars, i, n))
      else if isDigit(c) then TokenAt("rust.number", numberEnd(i + 1))
      else if isIdentStart(c) then TokenAt("rust.ident", identEnd(i))
      else TokenAt("rust.punct", i + 1)
    // Every classification above ends at least one past its start, so the walk cannot spin.
    //
    // A token ending AT the window edge may be a token that was cut in half — an identifier
    // whose rest is in the next window, a block comment whose `*/` is further on — so it is
    // dropped and the caller retries with more source. At end of input there is nothing more to
    // wait for and every token stands.
    def walk(i: Int, out: Vector[RustLexToken], consumed: Int): WindowLex =
      if i >= n then WindowLex(out, consumed)
      else
        val t = tokenAt(i)
        if !atEof && t.end >= n then WindowLex(out, consumed)
        else walk(t.end, out :+ RustLexToken(t.kind, text.substring(i, t.end)), t.end)
    walk(0, Vector.empty, 0)
