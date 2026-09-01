package scalascript.uniml.dialect.scalascript

import scalascript.uniml.UniAlphabet

import scalascript.uniml.*

/** v2.2 spike — a UniML dialect for a growing Scala subset.
  *   `def NAME(p: Int, ...): Int = BODY` where BODY is either an inline EXPR or an
  *   OFFSIDE (indented) BLOCK of `val NAME = EXPR` statements ending in an expr;
  *   EXPR over int / id / call `f(a,b)` / `( )` / `if c then t else e` / the full
  *   ssc-v2 infix operator set with the `ssc1-front` precedence table.
  *
  * P6.0 gate: precedence via a Pratt parse INSIDE the dialect, serialised to
  *   `VmToken`s (open-on-first-token / `Reframe.closeAfter`-on-last). P6.1: total,
  *   error-resilient (never throws, diagnostics, `def`-boundary resync, holes). P6.2a:
  *   full infix table (byte-identical Core IR vs `ssc1-front`). P6.2b (this slice):
  *   OFFSIDE LAYOUT — an indented def body is a block; block structure is computed in
  *   the parser from token COLUMNS (no synthetic tokens, so the CST stays lossless),
  *   and the block frame is emitted uniformly. Statement separation is implicit
  *   (`parseExpr` stops at a non-operator; a leading infix op / call continues the
  *   line, matching ssc1-front's continuation rule). `mkVal`/`Pair("block",…)`/
  *   `Pair("expr",…)` mirror ssc1-front, so `lowerBlock` produces identical nested lets.
  */

enum Node:
  case Leaf(tok: SourceToken, role: Option[String])
  case Frame(kind: String, role: Option[String], children: Vector[Node])

// ── lexer ────────────────────────────────────────────────────────────────────
object SpikeLex:
  private val keywords = Set("def", "val", "if", "then", "else", "match", "case", "class", "given", "enum", "extension")
  /** ScalaScript's identifier classes. Deliberately NOT [[UniAlphabet.isIdStart]]: that admits
    * `$`, and this lexer needs `$` to stay outside identifiers so string interpolation can see it.
    * The primitives are shared; the RULE is the dialect's, which is the distinction that keeps a
    * shared helper from acquiring two callers that need it to disagree. */
  private def isSpikeIdStart(c: Char): Boolean =
    UniAlphabet.isAsciiLetter(c) || c == '_' || UniAlphabet.isNonAscii(c)
  private def isSpikeIdPart(c: Char): Boolean = isSpikeIdStart(c) || UniAlphabet.isDigit(c)

  private def isOpChar(c: Char): Boolean = "+-*/%<>=!&|^~:?".indexOf(c.toInt) >= 0 // `?` only forms `???`

  // EXACT mirror of ssc1-front's operator lexer (ssc1-front.ssc0:375-445): a per-leading-char dispatch over a
  // FIXED table — NOT a greedy munch over op chars. Returns (lexeme, charsConsumed); the lexeme may differ
  // from the source text where ssc1-front rewrites at lex time (`:::`→`++`, `+:`→`::`).
  //
  // The distinction is observable, which is the whole point: a greedy munch turns `<~>` into ONE token, but
  // ssc1-front lexes `<` + `~` → `<~` and leaves `>`; likewise `~~` is `~` + `~`. That is exactly why the
  // oracle truncates a symbolic-operator def name — `def <~>(b: Int): Int = a * 100 + b` becomes `def <~`
  // with a UNIT body, which the corpus expects us to reproduce bug-for-bug.
  //
  // A char with NO table entry (`/`, `%`, `^`) falls through to ssc1-front's final `else` → a ONE-char op
  // token, so `/=` lexes as `/` then `=` and never as a single compound-assign token.
  private def opAt(text: String, i: Int): (String, Int) =
    // MAXIMAL MUNCH over operator characters, which is Scala's rule and — measured, not assumed —
    // the reference front's: `extension (a: Int) def <~>(b: Int): Int = a * 100 + b` then
    // `println(3 <~> 4)` prints 304 on the interpreter, and
    // `tests/conformance/js-symbolic-infix-operator.ssc` passes on int.
    //
    // ⚠️ The comment that stood here said the opposite — that ssc1-front lexes `<` + `~` and leaves
    // `>`, truncating a symbolic def name to `def <~` with a unit body, "which the corpus expects
    // us to reproduce bug-for-bug". That is false today: the oracle computes 304. Whether it was
    // ever true, reproducing it now is what makes this dialect DIVERGE, and it cost three
    // diagnostics — `<~>` and `~~` as def names, and `3 <~> 4` at the call site — plus `++=`, which
    // split into `++` and `=` for the same reason.
    //
    // The lexeme is the SOURCE SLICE (see the caller), so widening the munch cannot lose a
    // character; only the MEANING is decided here.
    val n = text.length
    def opEnd(k: Int): Int = if k < n && isOpChar(text.charAt(k)) then opEnd(k + 1) else k
    val raw = text.substring(i, opEnd(i))
    // Two lex-time REWRITES survive, and they are statements about meaning rather than munching:
    // in v2 every Seq is a Cons-list, so `xs ::: ys` IS `xs ++ ys` and `x +: xs` IS `x :: xs`
    // (ssc1-front.ssc0:385/428). Everything else means itself, including an operator this dialect
    // has never seen — a user-defined `<~>` is not the lexer's business to know.
    val meaning = raw match
      case ":::" => "++"
      case "+:"  => "::"
      case other => other
    (meaning, raw.length)

  private def isHexDigit(c: Char): Boolean = UniAlphabet.isHexDigit(c)

  /** The lexer state: index/line/column plus the next token id and the emitted tokens.
    * Every token's lexeme is a SOURCE SLICE `[start, end)` — the old StringBuilders either
    * duplicated the slice or were explicitly discarded (`val _ = sb`) — so each branch only
    * computes its END index and `emitTo` does the rest. */
  private final case class LexSt(i: Int, line: Int, col: Int, id: Long, out: Vector[SourceToken])

  def scan(src: SourceId, text: String): Vector[SourceToken] =
    val n = text.length
    // line/col advanced over the half-open slice [from, to): the per-char cursor rule
    // (a newline bumps the line and resets the column) applied to a whole run at once.
    def lineColTo(from: Int, to: Int, line: Int, col: Int): (Int, Int) =
      if from >= to then (line, col)
      else if text.charAt(from) == '\n' then lineColTo(from + 1, to, line + 1, 1)
      else lineColTo(from + 1, to, line, col + 1)
    def emitTo(st: LexSt, kind: String, end: Int, chan: TokenChannel): LexSt =
      val lc = lineColTo(st.i, end, st.line, st.col)
      val endPos = SourcePosition(end, lc._1, lc._2)
      LexSt(end, lc._1, lc._2, st.id + 1,
        st.out :+ SourceToken(st.id, kind, text.substring(st.i, end),
          SourceSpan(src, SourcePosition(st.i, st.line, st.col), endPos), chan))

    def isWsChar(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'
    def wsEnd(k: Int): Int = if k < n && isWsChar(text.charAt(k)) then wsEnd(k + 1) else k
    def idEnd(k: Int): Int = if k < n && isSpikeIdPart(text.charAt(k)) then idEnd(k + 1) else k
    def digitsEnd(k: Int): Int =
      if k < n && (UniAlphabet.isDigit(text.charAt(k)) || text.charAt(k) == '_') then digitsEnd(k + 1) else k
    def hexEnd(k: Int): Int = if k < n && isHexDigit(text.charAt(k)) then hexEnd(k + 1) else k
    // optional scientific exponent `e`/`E` [`+`/`-`] digits; None when the shape is not there
    def exponentEnd(k: Int): Option[Int] =
      if k < n && (text.charAt(k) == 'e' || text.charAt(k) == 'E') then
        val signLen = if k + 1 < n && (text.charAt(k + 1) == '+' || text.charAt(k + 1) == '-') then 1 else 0
        if k + 1 + signLen < n && UniAlphabet.isDigit(text.charAt(k + 1 + signLen)) then
          Some(digitsEnd(k + 1 + signLen))
        else None
      else None
    // `0d` / `1.5f` / `3D` — Scala's FLOAT SUFFIX, which this lexer knew nothing about. The
    // digit run stopped at the letter, so `val a: Double = 0d` lexed as the integer `0`
    // followed by the identifier `d`, and the statement after it read as a bare name. Three
    // corpus cases came out with more statements than they had, which is the shape that
    // hides: the tree is well-formed and says something the source did not.
    // The lookahead guard keeps `2fooBar` — not a literal in any Scala — from being read as
    // one; the suffix must be the LAST character of the token.
    def floatSuffixEnd(k: Int): Option[Int] =
      val c1 = if k < n then text.charAt(k) else ' '
      if (c1 == 'f' || c1 == 'F' || c1 == 'd' || c1 == 'D')
         && !(k + 1 < n && isSpikeIdPart(text.charAt(k + 1))) then Some(k + 1)
      else None

    def walk(st: LexSt): LexSt =
      if st.i >= n then st
      else
        val c = text.charAt(st.i)
        if isWsChar(c) then
          walk(emitTo(st, "spike.ws", wsEnd(st.i + 1), TokenChannel.Trivia))
        else if UniAlphabet.isDigit(c) then
          // Number lexer — matches ssc1-front (v2/lib/ssc1-front.ssc0:295-338):
          //   • hex 0x/0X → the DECIMAL value string (strip trailing L/l Long suffix)
          //   • decimal: `_` digit-separators stripped from the lexeme; `d.d` or `1e10`/`1.0e100`
          //     exponent → float; otherwise int with a trailing L/l suffix stripped.
          // (The VALUE is recomputed by SpikeNum.decode from the raw slice.)
          if c == '0' && st.i + 1 < n && (text.charAt(st.i + 1) == 'x' || text.charAt(st.i + 1) == 'X')
             && st.i + 2 < n && isHexDigit(text.charAt(st.i + 2)) then
            val h = hexEnd(st.i + 2)
            val end = if h < n && (text.charAt(h) == 'L' || text.charAt(h) == 'l') then h + 1 else h
            walk(emitTo(st, "spike.int", end, TokenChannel.Syntax))
          else
            val d1 = digitsEnd(st.i)
            // `1.5` is a float; `1.field` (dot NOT followed by a digit) stays int + `.` + selector
            if d1 + 1 < n && text.charAt(d1) == '.' && UniAlphabet.isDigit(text.charAt(d1 + 1)) then
              val d2 = digitsEnd(d1 + 1)
              val d3 = exponentEnd(d2).getOrElse(d2)
              walk(emitTo(st, "spike.float", floatSuffixEnd(d3).getOrElse(d3), TokenChannel.Syntax))
            else exponentEnd(d1) match
              case Some(e1) => // `1e10` (no decimal point) is still a float
                walk(emitTo(st, "spike.float", floatSuffixEnd(e1).getOrElse(e1), TokenChannel.Syntax))
              case None => floatSuffixEnd(d1) match
                case Some(f1) => // `0d`, `3F` — a float with no point and no exponent
                  walk(emitTo(st, "spike.float", f1, TokenChannel.Syntax))
                case None =>
                  val end = if d1 < n && (text.charAt(d1) == 'L' || text.charAt(d1) == 'l') then d1 + 1 else d1 // Long suffix
                  walk(emitTo(st, "spike.int", end, TokenChannel.Syntax))
        else if isSpikeIdStart(c) then
          val end = idEnd(st.i)
          val w = text.substring(st.i, end)
          val idKind = if keywords(w) then "spike.kw" else if UniAlphabet.isTypeNameStart(w.head) then "spike.uid" else "spike.id"
          walk(emitTo(st, idKind, end, TokenChannel.Syntax))
        else if c == '/' && st.i + 1 < n && text.charAt(st.i + 1) == '/' then
          // line comment → trivia (parser skips it via skipTrivia); lossless, text kept in the token
          def lineEnd(k: Int): Int = if k < n && text.charAt(k) != '\n' then lineEnd(k + 1) else k
          walk(emitTo(st, "spike.ws", lineEnd(st.i), TokenChannel.Trivia))
        else if c == '/' && st.i + 1 < n && text.charAt(st.i + 1) == '*' then
          // block comment → trivia, and it NESTS, as it does in Scala.
          //
          // It used to stop at the FIRST `*/`, matching ssc1-front's skipBlockComment. For
          // `/* a /* b */ c */` that left ` c */` to be lexed as code, and the failure surfaced as
          // `missing.right` — a complaint about an operator, several tokens past the construct that
          // actually broke.
          //
          // This makes the dialect MORE PERMISSIVE than the reference front, deliberately: v3
          // supports nesting because Scala does, and ssc1-front's gap is shared rather than
          // authoritative here. The divergence is in the only safe direction — strictly more input
          // parses — so no file that parses today can stop parsing, and the corpus counts are checked
          // rather than assumed.
          //
          // Losslessness is untouched: the comment remains ONE trivia token holding its text
          // verbatim; only where that token ends has changed.
          def blockEnd(k: Int, depth: Int): Int =
            if depth == 0 || k >= n then k
            else if text.charAt(k) == '/' && k + 1 < n && text.charAt(k + 1) == '*' then blockEnd(k + 2, depth + 1)
            else if text.charAt(k) == '*' && k + 1 < n && text.charAt(k + 1) == '/' then blockEnd(k + 2, depth - 1)
            else blockEnd(k + 1, depth)
          walk(emitTo(st, "spike.ws", blockEnd(st.i + 2, 1), TokenChannel.Trivia))
        else if isOpChar(c) then
          val opInfo = opAt(text, st.i)
          val kind = opInfo._1 match
            case "=" => "spike.eq"
            case ":" => "spike.colon"
            case _   => "spike.op"
          // The lexeme is the SOURCE SLICE, not the meaning. `opAt` REWRITES two operators —
          // `:::`→`++` and `+:`→`::` — mirroring ssc1-front's lex-time rewrite, and a
          // rewritten lexeme is one the source cannot be rebuilt from: `xs ::: ys` came
          // back as `xs ++ ys`, one character short. The rewrite is a statement about
          // MEANING (in v2 every Seq is a Cons-list, so `:::` IS `++`), so it belongs
          // where meaning is read — `SpikeOp.meaning`, used by the precedence table and
          // the projection.
          walk(emitTo(st, kind, st.i + opInfo._2, TokenChannel.Syntax))
        else if c == '"' then
          // string literal → spike.str whose lexeme is the RAW SOURCE SLICE, quotes and
          // escapes included. It used to hold the DECODED value ("mirrors ssc1-front
          // buildStr"), which is convenient for the projection and fatal for the CST: the
          // quotes and every `\n` never reached the tree, so a source with strings could
          // not be reconstructed. Decoding is `SpikeStr.decode`, applied where the
          // projection needs a VALUE — the same split Markdown uses, where the CST is
          // canonical and `MarkdownProjection` unescapes.
          val strStart = st.i
          if st.i + 2 < n && text.charAt(st.i + 1) == '"' && text.charAt(st.i + 2) == '"' then
            def tripleBody(k: Int): Int =
              if k >= n then k
              else if k + 2 < n && text.charAt(k) == '"' && text.charAt(k + 1) == '"' && text.charAt(k + 2) == '"' then k
              else tripleBody(k + 1)
            // A run of MORE THAN THREE quotes closes with its LAST three; the extras are
            // CONTENT. `""" aria-invalid="true""""` ends with four, because the content
            // itself ends in `"`. Closing on the first three left a stray quote that opened a
            // new string and mis-lexed the rest of the file — 20 diagnostics in
            // examples/std-ui/textarea.ssc, all of them from this one character.
            def extraQuotes(k: Int): Int =
              if k + 3 < n && text.charAt(k) == '"' && text.charAt(k + 1) == '"' &&
                 text.charAt(k + 2) == '"' && text.charAt(k + 3) == '"' then extraQuotes(k + 1)
              else k
            val b = extraQuotes(tripleBody(strStart + 3))
            val end = if b + 2 < n then b + 3 else b
            walk(emitTo(st, "spike.str", end, TokenChannel.Syntax))
          else
            // A `${` is special ONLY IN AN INTERPOLATION, which is what the character BEFORE the
            // opening quote says: `s"…"`, `html"…"`, `f"…"` have an identifier there, a plain
            // `"…"` does not — in a plain one it is the two characters `$` and `{`, exactly as in
            // Scala. `holeCloses` alone was not enough and the miss was narrow: it stops the
            // look-ahead at a NEWLINE, so a plain string whose `${` and a later `}` sit on ONE
            // LINE still took the interpolation branch. `"<code>${" + escapeHtml(s) + "}</code>"`
            // — real code, in `tests/conformance/markdown-html.ssc` — collapsed into a SINGLE
            // string literal holding a well-formed tree of the wrong program.
            //
            // A balanced `${ … }` is copied verbatim so its inner quotes don't end the string
            // (matches ssc1-front scanStr → scanInterpEnd); the parts split later, in projection.
            // ONLY WHEN THE HOLE ACTUALLY CLOSES, which `holeCloses` decides by LOOKING AHEAD.
            // Without that guard this branch ran for EVERY string, and a plain `"${"` — two
            // ordinary characters, in a program that is writing a hole rather than using one —
            // sent the scan hunting a `}` that was not there. It ran to the END OF THE FILE.
            // Worse where it happened to find a `}` later: `"${"` followed by `"}"` two lines
            // down SILENTLY SWALLOWED the code between them.
            def holeEnd(j: Int, depth: Int): Int =
              if depth == 0 || j >= n then j
              else
                val ch = text.charAt(j)
                holeEnd(j + 1, if ch == '{' then depth + 1 else if ch == '}' then depth - 1 else depth)
            def strBody(k: Int): Int =
              if k >= n then k
              else
                val ch = text.charAt(k)
                if ch == '"' then k
                else if ch == '\\' && k + 1 < n then strBody(k + 2)
                else if ch == '$' && k + 1 < n && text.charAt(k + 1) == '{' &&
                        strStart > 0 && isSpikeIdPart(text.charAt(strStart - 1)) &&
                        holeCloses(text, k + 2, n) then
                  strBody(holeEnd(k + 2, 1))
                else strBody(k + 1)
            val b = strBody(strStart + 1)
            val end = if b < n then b + 1 else b
            walk(emitTo(st, "spike.str", end, TokenChannel.Syntax))
        else if c == '`' then
          // Scala's escaped identifier — `` `type` `` lets a keyword be a name. The lexer had
          // no case for it, so the backtick fell through to a one-char token and every
          // parameter list holding one desynced. The lexeme is the SOURCE SLICE, backticks
          // included, so the CST still reconstructs; stripping them for the PROJECTED name is
          // a separate question, and the parse is what breadth measures.
          def tickEnd(k: Int): Int = if k < n && text.charAt(k) != '`' then tickEnd(k + 1) else k
          val b = tickEnd(st.i + 1)
          val end = if b < n then b + 1 else b
          walk(emitTo(st, "spike.id", end, TokenChannel.Syntax))
        else if c == '\'' then
          // char literal 'x' / '\n' / '\uXXXX' → spike.int whose lexeme is the RAW SLICE. The
          // VM still treats chars as CODES (scodeAt, `'a' == c`, ssc1-front.ssc0:361-374) — the
          // code is computed by SpikeNum.decode, so the CST keeps the quotes and the escape.
          // Like ssc1-front, every `'` is assumed to open a char literal (fixed 3/4/8-char forms).
          if st.i + 1 < n && text.charAt(st.i + 1) == '\\' then
            val e = if st.i + 2 < n then text.charAt(st.i + 2) else '\u0000'
            val width = if e == 'u' then 8 else 4
            walk(emitTo(st, "spike.int", math.min(st.i + width, n), TokenChannel.Syntax))
          // A char literal only when the quote actually CLOSES. Taking three characters
          // unconditionally lexed `'x)` as the "char" `'x)` and `'{ ` as `'{ ` — both `spike.int`,
          // both nonsense — which is how Scala 3's quote forms arrived at the parser as numbers.
          else if st.i + 2 < n && text.charAt(st.i + 2) == '\'' then
            walk(emitTo(st, "spike.int", st.i + 3, TokenChannel.Syntax))
          // `'{ … }` — a Scala 3 quote block. The quote is its own token so the brace that follows
          // is the ordinary `{` every block parser already knows.
          else if st.i + 1 < n && text.charAt(st.i + 1) == '{' then
            walk(emitTo(st, "spike.quote", st.i + 1, TokenChannel.Syntax))
          // `'x` — a quoted name (an `Expr` reference in a macro). Not a char: no closing quote.
          else if st.i + 1 < n && isSpikeIdStart(text.charAt(st.i + 1)) then
            walk(emitTo(st, "spike.qname", idEnd(st.i + 1), TokenChannel.Syntax))
          else
            walk(emitTo(st, "spike.int", math.min(st.i + 3, n), TokenChannel.Syntax))
        else
          val kind = c match
            case '(' => "spike.lparen"
            case ')' => "spike.rparen"
            case '{' => "spike.lbrace"
            case '}' => "spike.rbrace"
            case ',' => "spike.comma"
            case ';' => "spike.semi"
            case '.' => "spike.dot"
            case '[' => "spike.lbracket"
            case ']' => "spike.rbracket"
            case '@' => "spike.at"
            // `$` opens a Scala 3 splice — `${ e }` or `$x`. It was `spike.junk`, which is how the
            // parser came to report "unexpected token '$' in expression" on a construct the
            // reference front runs.
            case '$' => "spike.splice"
            case _   => "spike.junk"
          walk(emitTo(st, kind, st.i + 1, TokenChannel.Syntax))
    walk(LexSt(0, 1, 1, 0L, Vector.empty)).out

extension (n: Node)
  private def withRole(role: String): Node = n match
    case Node.Leaf(t, _)        => Node.Leaf(t, Some(role))
    case Node.Frame(k, _, kids) => Node.Frame(k, Some(role), kids)

// ── parser: total, error-resilient, offside-aware ─────────────────────────────
/** Decodes a `spike.str` RAW lexeme to its value. Mirrors what `SpikeLex` used to
  * store directly, moved here so the CST can keep the source slice: strip the
  * quotes, then `\n`→NL, `\t`→TAB, `\<c>`→c, with a balanced `${ … }` copied
  * verbatim so its inner quotes do not end the string. Triple-quoted is raw. */
/** Decodes a `spike.int` / `spike.float` RAW lexeme to the VALUE string the
  * projection needs. Mirrors what `SpikeLex` used to store directly, moved here
  * so the CST can keep the source slice — the same reason `SpikeStr` exists.
  *
  * Four forms, all previously normalised away at lex time: a char literal
  * (`'a'`, `'\n'`, `'\uXXXX'`) is its code, hex folds to decimal, `_` digit
  * separators drop out, and a trailing `L`/`l` is not part of the value. */
/** The MEANING of an operator lexeme, for the two that ssc1-front rewrites while
  * lexing: in v2 every Seq is a Cons-list, so `xs ::: ys` is exactly `xs ++ ys`
  * and `x +: xs` exactly `x :: xs`. The CST keeps the source spelling; this is
  * what the precedence table and the projection read. */
private[scalascript] object SpikeOp:
  def meaning(lex: String): String = lex match
    case ":::" => "++"
    case "+:"  => "::"
    case other => other

/** The escape table, in ONE place.
  *
  * There were two, and both knew only `\n` and `\t`; everything else fell through to the character
  * itself. That is right for `\\`, `\"` and `\'` and silently wrong for the rest: `'\r'` was the
  * letter `r` (114 rather than 13) and `"a\rb"` was `arb`. The tree was well-formed and said
  * something the source did not, which is why nothing caught it until two fronts printed the same
  * program side by side and disagreed on one character.
  *
  * `\u` stays with each caller — the char form reads a fixed four digits from a known offset, the
  * string form scans — but the SIMPLE table is shared, so a new escape is added once. */
/** Does a `${` at `from` close before the string does?
  *
  * A single-quoted string ends at its closing `"` or, being malformed, at a newline — so a hole
  * that has not balanced by then is not a hole at all, just the two characters `$` and `{`. The
  * lexer used to assume every `${` opened one and scanned to the end of the FILE looking for the
  * match.
  *
  * Nesting is counted so `${ f(x) { y } }` still closes at the right brace, and a `\"` inside the
  * hole does not end the search — an escaped quote is content. */
private[scalascript] def holeCloses(text: String, from: Int, n: Int): Boolean =
  def scan(i: Int, depth: Int): Boolean =
    if i >= n then false
    else
      val ch = text.charAt(i)
      if ch == '\\' && i + 1 < n then scan(i + 2, depth)
      else if ch == '{' then scan(i + 1, depth + 1)
      else if ch == '}' then depth == 1 || scan(i + 1, depth - 1)
      else if ch == '\n' then false
      else scan(i + 1, depth)
  scan(from, 1)

private[scalascript] object SpikeEsc:
  def simple(e: Char): Char = e match
    case 'n'   => '\n'
    case 't'   => '\t'
    case 'r'   => '\r'
    case 'b'   => '\b'
    case 'f'   => '\f'
    case '0'   => '\u0000'
    case other => other // `\\`, `\"`, `\'` — the character itself

private[scalascript] object SpikeNum:
  def decode(lex: String): String =
    if lex.startsWith("'") then charCode(lex).toString
    else if lex.startsWith("0x") || lex.startsWith("0X") then
      def fold(i: Int, v: Long): Long =
        if i < lex.length && isHex(lex.charAt(i)) then fold(i + 1, v * 16 + hex(lex.charAt(i))) else v
      fold(2, 0L).toString
    else
      val stripped = lex.filter(_ != '_')
      // `L`/`l` is the Long suffix; `f`/`F`/`d`/`D` the float ones. All are TYPE marks, not part of
      // the value — `0d.toDouble` is `0.0`, and `"0d".toDouble` throws.
      val last = if stripped.isEmpty then ' ' else stripped.last
      if last == 'L' || last == 'l' || last == 'f' || last == 'F' || last == 'd' || last == 'D'
      then stripped.dropRight(1)
      else stripped

  private def isHex(c: Char): Boolean = UniAlphabet.isHexDigit(c)
  private def hex(c: Char): Int =
    if UniAlphabet.isDigit(c) then c - '0' else if c >= 'a' then c - 'a' + 10 else c - 'A' + 10

  private def charCode(lex: String): Int =
    val body = lex.stripPrefix("'")
    if body.startsWith("\\") then
      val e = if body.length > 1 then body.charAt(1) else '\u0000'
      if e == 'u' && body.length >= 6 then
        (hex(body.charAt(2)) << 12) | (hex(body.charAt(3)) << 8) | (hex(body.charAt(4)) << 4) | hex(body.charAt(5))
      else SpikeEsc.simple(e).toInt
    else if body.nonEmpty then body.charAt(0).toInt
    else 0

private[scalascript] object SpikeStr:
  private def hexOf(c: Char): Int =
    if UniAlphabet.isDigit(c) then c - '0' else if c >= 'a' then c - 'a' + 10 else c - 'A' + 10

  def decode(lex: String): String =
    if lex.startsWith("\"\"\"") then
      val body = lex.stripPrefix("\"\"\"")
      if body.endsWith("\"\"\"") then body.dropRight(3) else body
    else
      val body =
        val b = lex.stripPrefix("\"")
        if b.endsWith("\"") then b.dropRight(1) else b
      val n = body.length
      def hole(i: Int, depth: Int, acc: Vector[String]): (Int, Vector[String]) =
        if depth == 0 || i >= n then (i, acc)
        else
          val ch = body.charAt(i)
          val d = if ch == '{' then depth + 1 else if ch == '}' then depth - 1 else depth
          hole(i + 1, d, acc :+ ch.toString)
      def walk(i: Int, acc: Vector[String]): Vector[String] =
        if i >= n then acc
        else
          val c = body.charAt(i)
          if c == '\\' && i + 1 < n then
            val e = body.charAt(i + 1)
            // `\uXXXX` — four hex digits, the one escape that is not a single character. The char
            // decoder had it and this one did not, so `"\u0041"` came out as the six characters.
            if e == 'u' && i + 5 < n && (2 to 5).forall(k => UniAlphabet.isHexDigit(body.charAt(i + k))) then
              val v = (2 to 5).foldLeft(0)((a, k) => (a << 4) | hexOf(body.charAt(i + k)))
              walk(i + 6, acc :+ v.toChar.toString)
            else walk(i + 2, acc :+ SpikeEsc.simple(e).toString)
          else if c == '$' && i + 1 < n && body.charAt(i + 1) == '{' then
            val stepped = hole(i + 2, 1, acc :+ "${")
            walk(stepped._1, stepped._2)
          else walk(i + 1, acc :+ c.toString)
      walk(0, Vector.empty).mkString

object SpikeParse:
  final case class Parsed(tree: Node, diagnostics: Vector[Diagnostic])

  // exact mirror of v2/lib/ssc1-front.ssc0 `opPrec` (0 = not an infix op).
  private def opPrec(op: String): Int = op match
    case "~" | "~>"                         => 9
    case "*" | "/" | "%"                    => 8
    case "+" | "-"                          => 7
    case "++" | ":+" | "<<" | ">>" | ">>>"  => 6
    case "==" | "!=" | "<" | "<~" | ">" | "<=" | ">=" | "&" => 5
    case "&&" | "^" | "|"                   => 4
    case "||"                               => 3
    case "!"                                => 2
    case "::"                               => 5 // cons, right-associative (see parseExpr)
    case "->"                               => 1 // pair
    case ":="                               => 1
    // An operator the table does not name is USER-DEFINED, not a non-operator. Scala decides
    // precedence by the operator's FIRST CHARACTER, and falling through to 0 meant `--` (Set
    // difference, `tests/conformance/set-ops-infix.ssc`), `/:` and any `def <~>` were not infix at
    // all — the parser stopped at them. The cases above still win, so no known operator moves.
    // ⚠️ Precedence 0 did NOT mean "unknown" for every token that reached here — for the ARROWS it
    // meant "deliberately not infix", and the first version of this fallback gave `=>` precedence 5
    // and broke every lambda: 12 diagnostics became 54 and the floor caught it. They are excluded
    // by name, ahead of the rule.
    case "=>" | "<-" | "<:" | ">:" | "@"    => 0
    case other if other.nonEmpty            => firstCharPrec(other.charAt(0))
    case _                                  => 0

  /** Words that can follow an expression on the SAME LINE and are not infix operators.
    *
    * THE LEXER HAS ELEVEN KEYWORDS (`keywords`, ScalaSpike.scala:31) and Scala has about forty, so
    * everything else — `yield`, `catch`, `var`, `object`, `with` — arrives here as an ordinary
    * `spike.id` and is dispatched by VALUE further up. That is fine while `to`/`until` are the only
    * id-infix words; it stops being fine the moment ANY identifier can be one, because `try f()
    * catch …` and `for x <- xs yield e` would parse as the applications `f().catch(…)` and
    * `xs.yield(e)`. So this list is load-bearing, not belt-and-braces.
    *
    * It holds three groups: the words the parser itself dispatches on by value (grep
    * `peekLexeme ==` / `isWord`), the declaration starters and modifiers that may legally begin a
    * statement, and `_`, which is a placeholder rather than a name. `to`/`until` are NOT here — the
    * arm above this one still claims them, so their behaviour is unchanged. */
  private val notInfixWord = Set(
    // structural words the parser reads by value — each of these can sit on the same line as the
    // end of an expression in legal source
    "yield", "catch", "finally", "do", "while", "for", "try", "new", "throw", "return", "end",
    "with", "extends", "derives", "forSome",
    // declaration starters and modifiers
    "var", "type", "object", "trait", "effect", "package", "import", "export",
    "implicit", "using", "override", "sealed", "abstract", "final", "private", "protected",
    "lazy", "inline", "open", "opaque", "transparent", "infix", "erased",
    // not a name
    "_",
  )

  /** Can this token kind BEGIN the right operand of an id-infix application?
    *
    * Deliberately narrower than `parseAtom` accepts. A symbolic operator is excluded so `a b - c`
    * cannot be read as `a.b(-c)`, and a keyword is excluded so `x foo if …` cannot start one. Both
    * are still reachable with a parenthesis, which is what Scala itself asks for in the ambiguous
    * cases. */
  private def startsIdInfixOperand(kind: String): Boolean = kind match
    case "spike.int" | "spike.float" | "spike.str" | "spike.id" | "spike.uid" |
         "spike.lparen" | "spike.lbracket" | "spike.lbrace" | "spike.qname" => true
    case _ => false

  /** Scala's precedence-by-first-character, for operators no table entry names. The numbers match
    * the table above so a user operator sits where its spelling says it should. */
  private def firstCharPrec(c: Char): Int = c match
    case '~'             => 9
    case '*' | '/' | '%' => 8
    case '+' | '-'       => 7
    case ':'             => 5
    case '<' | '>' | '=' | '!' => 5
    case '&' | '^' | '|' => 4
    case _               => 0

  /** The IMMUTABLE parser cursor. Every read (`peek*`, the layout probes) is a pure
    * function of `p`; the five things the old class MUTATED — the position, the
    * diagnostics, the paren depth, the block-column stack — are fields, and each
    * former mutator returns the next cursor. Functions that consume input return
    * a [[St]] pairing the advanced cursor with their result. */
  private final case class Cur(
      toks: Vector[SourceToken],
      p: Int,
      diags: Vector[Diagnostic],
      // Depth of the `( … )` ARGUMENT groups we are lexically inside — a coarse stand-in for ssc1-front's
      // layout DELIMITER STACK. Its closeToDelim (ssc1-front.ssc0:2902) closes only the virtual layout blocks
      // opened INSIDE the matching delimiter: "a different explicit delimiter is a hard boundary". So an
      // offside lambda body opened inside a call's `(` ends at that call's `)`, while one opened OUTSIDE any
      // group (`val f = (a, b) =>` ⏎ body) is NOT ended by a stray `)` in its own body.
      pdepth: Int,
      // The column of the innermost offside block being parsed. `parseIf` needs it: an `else` at a
      // column that would CLOSE the enclosing block cannot belong to an `if` inside that block. A
      // stack rather than a single value because blocks nest, and `-1` when none is open so a
      // top-level `if` is unconstrained.
      blockCols: List[Int],
  ):
    /** first significant (non-trivia) index at or after `q` — the old mutating skipTrivia, as a read */
    private def sig(q: Int): Int =
      if q < toks.length && toks(q).kind == "spike.ws" then sig(q + 1) else q
    def parenDepth: Int = pdepth
    def enterParen: Cur = copy(pdepth = pdepth + 1)
    def exitParen: Cur = if pdepth > 0 then copy(pdepth = pdepth - 1) else this
    def pushBlockCol(n: Int): Cur = copy(blockCols = n :: blockCols)
    def popBlockCol: Cur = copy(blockCols = blockCols.drop(1))
    def curBlockCol: Int = blockCols.headOption.getOrElse(-1)
    def eof: Boolean = sig(p) >= toks.length
    def peek: Option[SourceToken] = { val q = sig(p); if q < toks.length then Some(toks(q)) else None }
    def peekKind: String = peek.map(_.kind).getOrElse("spike.eof")
    def peekLexeme: String = peek.map(_.lexeme).getOrElse("<eof>")
    def peekLine: Int = peek.map(_.span.start.line).getOrElse(-1)
    def peekCol: Int = peek.map(_.span.start.column).getOrElse(-1)
    // line where the previous significant token ENDS (used for same-line trailing-block detection:
    // ssc1-front's layout inserts `;` on a newline, so `f\n{…}` is two statements, `f {…}` is a call).
    def prevEndLine: Int =
      def back(q: Int): Int = if q >= 0 && toks(q).kind == "spike.ws" then back(q - 1) else q
      val q = back(p - 1)
      if q >= 0 then toks(q).span.end.line else -1
    /** true when the next token ENDS exactly where the one after it begins — no
      * whitespace, no comment. `html"""…"""` is an interpolation; `foo "bar"` is not.
      *
      * THE SECOND TOKEN IS THE NEXT SIGNIFICANT ONE, not `toks(p + 1)`. Whitespace IS a token
      * here, so the raw neighbour of an identifier is usually the space or newline after it — and
      * that always begins exactly where the identifier ends, which made this predicate answer TRUE
      * for every identifier followed by a string ANYWHERE. `isInterpPrefix` accepts any word (that
      * is deliberate: `html"…"`, `uri"…"`), so the adjacency test is the ONLY thing separating a
      * custom interpolator from two unrelated statements, and it was not testing adjacency at all.
      *
      * Measured: a two-line body `a` ⏎ `"b"` was read as the interpolator `a"…"` and REFUSED by the
      * projection, on the DEFAULT front, while v3's own front, native and the interpreter all ran
      * it and printed `b`. (BUGS.md `uniml-reads-an-identifier-and-a-later-string-as-an-interpolator`.)
      *
      * The walk is the same one `peek2Kind` does, and it is spelled out rather than shared because
      * this is the ONE place that then compares OFFSETS — a helper returning the token would be
      * used by both and is the change to make if a third caller appears. */
    def peekAbutsNext: Boolean =
      val a = peek
      val q = sig(sig(p) + 1)
      val b = if q < toks.length then Some(toks(q)) else None
      // THE SAME LINE, as well as the same offset. The offset test alone did not reject a LINE
      // BREAK — measured, not assumed:
      //
      //     val tag = "h" + level
      //     "<" + tag + ">"
      //
      // read as `level"<"` and was refused as an interpolator outside Tier 0, on the DEFAULT front,
      // while v3's own parser read the two lines as two expressions. A blank line between them did
      // not help and parenthesising the first did, which is what says the test was passing rather
      // than the tokens being genuinely adjacent. Since every identifier is an interpolator prefix
      // here — `isInterpPrefix` accepts any word, whatever the comment above it says — this affects
      // ordinary string-building code, where a name ends one line and a literal opens the next.
      a.isDefined && b.isDefined &&
        a.get.span.end.offset == b.get.span.start.offset &&
        a.get.span.end.line == b.get.span.start.line

    def peekPrec: Int = if peekKind == "spike.op" then opPrec(SpikeOp.meaning(peekLexeme)) else 0
    def peek2Lexeme: String = // the second significant (non-trivia) token's lexeme
      val q = sig(sig(p) + 1)
      if q < toks.length then toks(q).lexeme else ""
    def peek2Kind: String = // the second significant (non-trivia) token's kind
      val q = sig(sig(p) + 1)
      if q < toks.length then toks(q).kind else "spike.eof"
    def peek2Line: Int = // the second significant token's starting line (-1 at eof)
      val q = sig(sig(p) + 1)
      if q < toks.length then toks(q).span.start.line else -1
    /** Is the `:` under the cursor Scala 3's fewer-braces argument, rather than a type ascription?
      *
      * `:` is the most overloaded token in the language — ascription, a pattern's type, a parameter
      * — so this answers only for the two shapes fewer-braces actually takes, and says no otherwise:
      *   `f(x):` NEWLINE indented-block     the argument is a block or a set of `case` arms
      *   `f.foreach: (a, b) =>`             the lambda header is on the colon's own line
      * The second needs a bounded scan for `=>` before the line ends, because `x: (Int, String)` is
      * an ascription that looks identical until the arrow decides it.
      */
    def colonOpensBlockArg: Boolean =
      val p0 = sig(p)
      if p0 >= toks.length || toks(p0).kind != "spike.colon" then false
      else
        val colonLine = toks(p0).span.start.line
        val q = sig(p0 + 1)
        if q >= toks.length then false
        else if toks(q).span.start.line > colonLine then true // indented block on the next line
        else
          // same line: a lambda header, so look for `=>` before this line ends
          def findArrow(k: Int): Boolean =
            if k < toks.length && toks(k).span.start.line == colonLine then
              toks(k).lexeme == "=>" || findArrow(k + 1)
            else false
          findArrow(q)

    def advance: St[Option[SourceToken]] =
      val q = sig(p)
      if q < toks.length then St(copy(p = q + 1), Some(toks(q))) else St(copy(p = q), None)
    /** advance, discarding the token — for the `c.advance()` sites that ignore it */
    def bump: Cur = advance.c
    def skipSemis: Cur = if peekKind == "spike.semi" then bump.skipSemis else this
    def report(code: String, msg: String): Cur =
      val span = peek.map(_.span).orElse(toks.lastOption.map(_.span))
      copy(diags = diags :+ Diagnostic(code, msg, Severity.Error, span, Some("scalascript.spike")))
    /** the old `reset(mark)`: back to a saved cursor's POSITION, keeping everything else */
    def resetTo(saved: Cur): Cur = copy(p = saved.p)

  /** An advanced cursor paired with a parse result — the threading record. */
  private final case class St[+A](c: Cur, v: A)

  private def isDefStart(c: Cur): Boolean = c.peekKind == "spike.kw" && c.peekLexeme == "def"

  // A parse-only no-op (`("sealed", "")`) MUST carry at least one token. An EMPTY Frame does not survive
  // the Node→UniNode emit, so the statement silently VANISHES — and if EVERY statement in a file vanishes
  // the file projects NO ROOTS AT ALL (the batch then reports the whole module as `EMPTY`). `v1/runtime/
  // std/ui/offline.ssc` is exactly that: nothing but `extern def` signatures. Single-file corpus programs
  // always have some real statement, which is why only the MULTI-FILE gate could surface this — these std
  // modules are only ever IMPORTED, never roots. The projection ignores these children (`stmt()` maps any
  // spike.sealed to `Pair("sealed", "")`), so the token is purely a carrier.
  private def sealedNoop(t: Option[SourceToken]): Node =
    Node.Frame("spike.sealed", None, t.map(x => Node.Leaf(x, Some("noop.tok"))).toVector)

  // exact mirror of ssc1-front's `isLayoutOpener` (ssc1-front.ssc0:2841): these tokens, when immediately
  // followed by a newline, open a brace-less LAYOUT block — ssc1-front's layout pass rewrites the indented
  // lines beneath them into a virtual `{ … }`. The spike computes block structure from COLUMNS instead of
  // synthesising tokens, so this predicate is only needed where the distinction is observable (currently the
  // statement-level `_err` recovery, where `=>` ⏎ body must become a 0-arity block argument).
  private def isLayoutOpenerTok(t: SourceToken): Boolean = t.lexeme match
    case "=" | "=>" | "then" | "else" | "do" | "yield" | "with" | "match" => true
    case _                                                                => false

  // consume a `[ … ]` type-parameter clause (erased). Plain params only — a context bound
  // `[A: TC]` would need the `__tc_TC`-param rewrite (deferred; finicky even in ssc1-front).
  /** Like `skipTypeParams`, but KEEPS the names and their context bounds as ordered leaves.
    *
    * Only at depth 1: `[F[_]]` contributes `F` and nothing from inside its own brackets, and
    * `[A <: B]` contributes `A` alone — an upper bound constrains a type, and there is no checker
    * here to constrain one with. A CONTEXT bound is different in kind: `[A: Monoid]` means a value
    * is passed, which is why it survives while `<:` does not. */
  private def collectTypeParams(c0: Cur): St[Vector[Node]] =
    if c0.peekKind != "spike.lbracket" then St(c0, Vector.empty)
    else
      // `[A: Monoid[Int]]` — the bound may carry its own arguments, which are erased
      // like every other type argument here.
      def bounds(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
        if c.peekKind == "spike.colon" then
          val c1 = c.bump
          if c1.peekKind == "spike.id" || c1.peekKind == "spike.uid" then
            val step = c1.advance
            val withBound = acc ++ step.v.map(t => Node.Leaf(t, Some("def.tbound"))).toVector
            val c2 = if step.c.peekKind == "spike.lbracket" then skipTypeParams(step.c) else step.c
            bounds(c2, withBound)
          else St(c1, acc)
        else St(c, acc)
      def walk(c: Cur, depth: Int, acc: Vector[Node]): St[Vector[Node]] =
        if depth == 0 || c.eof then St(c, acc)
        else c.peekKind match
          case "spike.lbracket" => walk(c.bump, depth + 1, acc)
          case "spike.rbracket" => walk(c.bump, depth - 1, acc)
          // BOTH identifier kinds. A type parameter is `A`, which this lexer calls `spike.uid`
          // — the uppercase kind — and matching only `spike.id` collected nothing at all, which
          // is why `tagless-resolution` still reached the arity check with no `A` to solve for.
          case k if (k == "spike.id" || k == "spike.uid") && depth == 1 =>
            val step = c.advance
            val withParam = acc ++ step.v.map(t => Node.Leaf(t, Some("def.tparam"))).toVector
            val bounded = bounds(step.c, withParam)
            walk(bounded.c, depth, bounded.v)
          case _ => walk(c.bump, depth, acc)
      walk(c0.bump, 1, Vector.empty)

  private def skipTypeParams(c0: Cur): Cur =
    if c0.peekKind != "spike.lbracket" then c0
    else
      def walk(c: Cur, depth: Int): Cur =
        if depth == 0 || c.eof then c
        else c.peekKind match
          case "spike.lbracket" => walk(c.bump, depth + 1)
          case "spike.rbracket" => walk(c.bump, depth - 1)
          case _                => walk(c.bump, depth)
      walk(c0.bump, 1)

  private def skipBalancedParens(c0: Cur): Cur =
    if c0.peekKind != "spike.lparen" then c0
    else
      def walk(c: Cur, depth: Int): Cur =
        if depth == 0 || c.eof then c
        else c.peekKind match
          case "spike.lparen" => walk(c.bump, depth + 1)
          case "spike.rparen" => walk(c.bump, depth - 1)
          case _              => walk(c.bump, depth)
      walk(c0.bump, 1)

  private def skipBalancedBraces(c0: Cur): Cur =
    if c0.peekKind != "spike.lbrace" then c0
    else
      def walk(c: Cur, depth: Int): Cur =
        if depth == 0 || c.eof then c
        else c.peekKind match
          case "spike.lbrace" => walk(c.bump, depth + 1)
          case "spike.rbrace" => walk(c.bump, depth - 1)
          case _              => walk(c.bump, depth)
      walk(c0.bump, 1)

  // after a base type name, consume its `[T]` args and any `=> Codomain` function-type tail
  // (all erased). Handles `List[Int]`, `Int => Int`, `Int => List[A]`, `A => B => C`, `(A, B) => C`.
  // consume a type's `.segment` chain and generic `[…]` args ONLY — NOT a function `=>` arrow. Used where
  // a following `=>` is a case arrow (arm pattern `case x: A.B =>`), so skipTypeTail would wrongly eat it.
  private def skipTypeSegments(c0: Cur): Cur =
    def segs(c: Cur): Cur =
      if c.peekKind == "spike.dot" && (c.peek2Kind == "spike.id" || c.peek2Kind == "spike.uid") then segs(c.bump.bump)
      else c
    val c1 = segs(c0)
    if c1.peekKind == "spike.lbracket" then skipTypeParams(c1) else c1

  private def skipTypeTail(c0: Cur): Cur =
    // a fully-qualified type `a.b.C` — consume the `.segment` chain (the base name was already taken)
    def segs(c: Cur): Cur =
      if c.peekKind == "spike.dot" && (c.peek2Kind == "spike.id" || c.peek2Kind == "spike.uid") then segs(c.bump.bump)
      else c
    val c1 = segs(c0)
    val c2 = if c1.peekKind == "spike.lbracket" then skipTypeParams(c1) else c1
    // VARARGS — `def card(body: TkNode*)`. In TYPE position a trailing `*` is unambiguous
    // (it cannot be multiplication there), and without it the param clause failed at the `*`
    // and took the rest of the def with it.
    val c3 = if c2.peekKind == "spike.op" && c2.peekLexeme == "*" then c2.bump else c2
    // `A throws E` — an INFIX TYPE operator, not a keyword: `throws` lexes as a plain identifier.
    // It appears in both return and parameter position throughout `v1/runtime/std/error-handling.ssc`
    // — `def raise[A, E](e: E): A throws E` — and the whole type was rejected without it, taking
    // the `=` and the body with it. Erased like every other type here.
    // `A | E` and `A & B` — union and intersection types, infix operators in TYPE position where
    // they cannot be the boolean/bitwise operators of the same spelling. `def unbox(…): A | E` in
    // error-handling.ssc lost its body to the `|`.
    val c4 =
      if c3.peekKind == "spike.op" && (c3.peekLexeme == "|" || c3.peekLexeme == "&") then
        val cA = c3.bump
        if cA.peekKind == "spike.lparen" then skipBalancedParens(cA)
        else if cA.peekKind == "spike.uid" || cA.peekKind == "spike.id" then skipTypeTail(cA.bump)
        else cA
      else c3
    val c5 =
      if c4.peekKind == "spike.id" && c4.peekLexeme == "throws" then
        val cA = c4.bump
        if cA.peekKind == "spike.lparen" then skipBalancedParens(cA)
        else if cA.peekKind == "spike.uid" || cA.peekKind == "spike.id" then skipTypeTail(cA.bump)
        else cA
      else c4
    if c5.peekKind == "spike.op" && c5.peekLexeme == "=>" then
      val cA = c5.bump
      val cB =
        if cA.peekKind == "spike.lparen" then skipBalancedParens(cA)
        else if cA.peekKind == "spike.uid" || cA.peekKind == "spike.id" then cA.bump
        else cA
      skipTypeTail(cB)
    else c5

  private def isKw(c: Cur, w: String): Boolean = c.peekKind == "spike.kw" && c.peekLexeme == w

  // `: T` value/param type annotation — erased. Depth-based skip mirroring ssc1-front skipTypeAt
  // (ssc1-front.ssc0): consume through balanced `[]`/`()` until a depth-0 terminator (`= , ; { }` or a
  // depth-0 closing `]`/`)`), covering the whole generic/function/union/dotted type grammar without modelling it.
  private def skipTypeAnnotation(c0: Cur): Cur =
    if c0.peekKind != "spike.colon" then c0
    else
      def walk(c: Cur, depth: Int): Cur =
        if c.eof then c
        // A TYPE ENDS AT THE END OF ITS LINE, at depth 0 — the same rule `postfix` states for a
        // trailing `(` and `parseIdOrCall` was given on 2026-08-19: ssc1-front's layout inserts `;`
        // at a newline, so nothing after one continues the construct.
        //
        // WITHOUT IT AN ABSTRACT `val` STOLE THE NEXT DECLARATION. This loop stops at `=` and a
        // newline was not a stop, so
        //
        //     extern class UploadedFile:
        //       val name: String
        //
        //     def main(): Unit = println("ok")
        //
        // skipped `String`, walked over `def main(): Unit` — the brackets balance, so `depth`
        // returns to 0 — and stopped at THAT `=`. `val name` then took `println("ok")` as its
        // right-hand side, `main` vanished from the program, and the tree printed
        // `(val "name" (call "println" (str "ok")))` with no complaint at all. Every later
        // declaration in that block was lost the same way.
        //
        // DEPTH GUARDS THE REAL MULTI-LINE TYPE: `: Map[String,` ⏎ `  Int]` is inside brackets, so
        // it is unaffected — only a type that has already closed everything it opened ends here.
        else if depth == 0 && c.peekLine > c.prevEndLine then c
        else c.peekKind match
          case "spike.lbracket" | "spike.lparen" => walk(c.bump, depth + 1)
          case "spike.rbracket" | "spike.rparen" =>
            if depth == 0 then c else walk(c.bump, depth - 1)
          case _ if depth > 0 => walk(c.bump, depth)
          case "spike.eq" | "spike.comma" | "spike.semi" | "spike.lbrace" | "spike.rbrace" => c
          case _ => walk(c.bump, depth)
      walk(c0.bump, 0)

  private def expect(c: Cur, kind: String, role: String, what: String): St[Option[Node]] =
    if c.peekKind == kind then
      val step = c.advance
      St(step.c, step.v.map(t => Node.Leaf(t, Some(role))))
    else St(c.report("spike.expected", s"expected $what, found '${c.peekLexeme}'"), None)

  /** Any identifier is a name. Scala imposes no capitalisation on declarations — `class foo`,
    * `object bar`, `type baz` are all legal — and the dialect used to demand an uppercase one for
    * classes, objects, enums, enum cases and type aliases, which was stricter than the language it
    * models. Case is load-bearing in exactly ONE position, and it is not this one: see
    * `parsePattern`. */
  private def isNameKind(kind: String): Boolean = kind == "spike.id" || kind == "spike.uid"

  // a val/var binder name — a lowercase id OR an uppercase uid (`val Schema = …` / `val Pi = …` are valid).
  private def expectName(c: Cur, role: String, what: String): St[Option[Node]] =
    if c.peekKind == "spike.id" || c.peekKind == "spike.uid" then
      val step = c.advance
      St(step.c, step.v.map(t => Node.Leaf(t, Some(role))))
    else St(c.report("spike.expected", s"expected $what, found '${c.peekLexeme}'"), None)

  // a type name is an uppercase `uid` (Int, String) or a lowercase type param (`id`).
  private def expectType(c: Cur, role: String): St[Option[Node]] =
    if c.peekKind == "spike.uid" || c.peekKind == "spike.id" then
      val step = c.advance
      St(step.c, step.v.map(t => Node.Leaf(t, Some(role))))
    else St(c.report("spike.expected", s"expected type, found '${c.peekLexeme}'"), None)

  // `derives Name[T][, Name…]*` → cc.derive leaves (each name's `[T]` type args skipped), matching
  // ssc1-front parseDeriveNames (ssc1-front.ssc0:2176).
  // skip forward to the closing `)` of the current param list (and consume it), tracking nested `(`/`[`
  // depth so an inner `@rdf("…")` / generic `[…]` does not end it early. Used by parseCaseClass's synthetic-
  // field recovery to consume the leftover `@ann name: T, …` after an unsupported annotated field.
  private def skipToParamListEnd(c0: Cur): Cur =
    def walk(c: Cur, depth: Int): Cur =
      if c.eof then c
      else c.peekKind match
        case "spike.lparen" | "spike.lbracket" => walk(c.bump, depth + 1)
        case "spike.rbracket"                  => walk(c.bump, if depth > 0 then depth - 1 else depth)
        case "spike.rparen"                    => if depth == 0 then c.bump else walk(c.bump, depth - 1)
        case _                                 => walk(c.bump, depth)
    walk(c0, 0)

  private def captureDerives(c0: Cur): St[Vector[Node]] =
    if !isWord(c0, "derives") then St(c0, Vector.empty)
    else
      def walk(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
        if c.peekKind == "spike.uid" || c.peekKind == "spike.id" then
          val step = c.advance
          val withName = acc ++ step.v.map(t => Node.Leaf(t, Some("cc.derive"))).toVector
          val c1 = skipTypeParams(step.c)
          if c1.peekKind == "spike.comma" then walk(c1.bump, withName) else St(c1, withName)
        else St(c, acc)
      walk(c0.bump, Vector.empty)

  // capture a case-class field type as its raw TOKENS (base + balanced `[…]` generics + `=> …` fn tails) in a
  // spike.cctype frame, so the projection reproduces ssc1-front's full type string (`List[User]`,
  // `Map[String,User]` — token lexemes concatenated with no spaces).
  private def nodeLexeme(n: Node): String = n match
    case Node.Leaf(t, _) => t.lexeme
    case _               => ""

  // capture a `[T, U]` type-argument clause's INNER tokens (for the Prism variant string, concatenated no-space);
  // kept as leaves so the frame survives the emit. The outer `[`/`]` are consumed but not kept.
  private def captureTypeArgTokens(c0: Cur): St[Vector[Node]] =
    if c0.peekKind != "spike.lbracket" then St(c0, Vector.empty)
    else
      def walk(c: Cur, depth: Int, acc: Vector[Node]): St[Vector[Node]] =
        if depth == 0 || c.eof then St(c, acc)
        else c.peekKind match
          case "spike.lbracket" =>
            val step = c.advance
            walk(step.c, depth + 1, acc ++ step.v.map(t => Node.Leaf(t, Some("ta.tok"))).toVector)
          case "spike.rbracket" =>
            val step = c.advance
            if depth - 1 > 0 then walk(step.c, depth - 1, acc ++ step.v.map(t => Node.Leaf(t, Some("ta.tok"))).toVector)
            else St(step.c, acc)
          case _ =>
            val step = c.advance
            walk(step.c, depth, acc ++ step.v.map(t => Node.Leaf(t, Some("ta.tok"))).toVector)
      walk(c0.bump, 1, Vector.empty)

  private def captureFieldType(c: Cur): St[Node] = captureType(c, "cc.fieldType")
  private def captureType(c0: Cur, role: String): St[Node] =
    def take(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
      val step = c.advance
      St(step.c, acc ++ step.v.map(t => Node.Leaf(t, Some("ct.tok"))).toVector)
    def takeBalanced(c: Cur, open: String, close: String, acc: Vector[Node]): St[Vector[Node]] =
      def walk(cc: Cur, depth: Int, a: Vector[Node]): St[Vector[Node]] =
        if depth == 0 || cc.eof then St(cc, a)
        else
          val d = if cc.peekKind == open then depth + 1 else if cc.peekKind == close then depth - 1 else depth
          val step = take(cc, a)
          walk(step.c, d, step.v)
      val first = take(c, acc)
      walk(first.c, 1, first.v)
    val head: St[Vector[Node]] =
      if c0.peekKind == "spike.lparen" then takeBalanced(c0, "spike.lparen", "spike.rparen", Vector.empty) // `(A, B)` domain
      else if c0.peekKind == "spike.uid" || c0.peekKind == "spike.id" then take(c0, Vector.empty)
      else St(c0, Vector.empty)
    def more(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
      // A QUALIFIED type name — `a.b.C[Book]`. Only the head was taken, so the type ended at `a` and
      // whatever followed was read as a new statement: `given x: a.b.C[Book] = …` reported TWO
      // diagnostics, and the anonymous spelling reported one. Both are ordinary Scala and the
      // reference front parses them. Dots are consumed here rather than in either `given` branch
      // because every caller of `captureType` has the same gap — a `def`'s result type, a `val`'s
      // ascription, a parameter type.
      if c.peekKind == "spike.dot" && (c.peek2Kind == "spike.uid" || c.peek2Kind == "spike.id") then
        val dot = take(c, acc)
        val seg = take(dot.c, dot.v)
        more(seg.c, seg.v)
      else if c.peekKind == "spike.lbracket" then
        val step = takeBalanced(c, "spike.lbracket", "spike.rbracket", acc)
        more(step.c, step.v)
      else if c.peekKind == "spike.op" && c.peekLexeme == "=>" then
        val arrow = take(c, acc)
        val after =
          if arrow.c.peekKind == "spike.lparen" then takeBalanced(arrow.c, "spike.lparen", "spike.rparen", arrow.v)
          else if arrow.c.peekKind == "spike.uid" || arrow.c.peekKind == "spike.id" then take(arrow.c, arrow.v)
          else arrow
        more(after.c, after.v)
      else St(c, acc)
    val done = more(head.c, head.v)
    St(done.c, Node.Frame("spike.cctype", Some(role), done.v))

  // `extends T [(args)] [with T]* ` / `derives T, …` inheritance clause — erased (ssc1-lower tracks subtypes
  // from the AST separately; the spike does not model subtyping). Stops at the body opener `{`/`:`.
  private def skipExtendsClause(c0: Cur): Cur =
    // Scala 3 separates further parents with COMMAS where Scala 2 wrote `with`:
    // `trait Traversable[T[_]] extends Functor[T], Foldable[T]`. Without this the comma ended the
    // declaration and `v1/runtime/std/foldable-traversable.ssc` lost its trait — the reference
    // front parses that file, so it was a gap here and not bad source.
    def moreParents(c: Cur): Cur =
      if c.peekKind == "spike.comma" && (c.peek2Kind == "spike.uid" || c.peek2Kind == "spike.id") then
        moreParents(skipTypeRef(c.bump))
      else c
    def withParents(c: Cur): Cur =
      if isWord(c, "with") then withParents(moreParents(skipTypeRef(c.bump))) else c
    val c1 =
      if isWord(c0, "extends") then
        val base = skipTypeRef(c0.bump)
        val args = if base.peekKind == "spike.lparen" then skipBalancedParens(base) else base // parent constructor args
        withParents(moreParents(args))
      else c0
    if isWord(c1, "derives") then
      def derivesTail(c: Cur): Cur =
        if c.peekKind == "spike.comma" then derivesTail(skipTypeRef(c.bump)) else c
      derivesTail(skipTypeRef(c1.bump))
    else c1

  // `extends A with B` for a declaration that KEEPS its parents. The head token of each type ref is
  // the parent tag, which is what `cc.parent` keeps for a case class and all dispatch needs.
  private def captureExtendsClause(c0: Cur): St[Vector[Node]] =
    def parent(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
      val named =
        if isNameKind(c.peekKind) then
          val step = c.advance
          St(step.c, acc ++ step.v.map(t => Node.Leaf(t, Some("td.parent"))).toVector)
        else St(c, acc)
      St(skipTypeTail(named.c), named.v)
    // Scala 3 separates further parents with COMMAS where Scala 2 wrote `with`. This is the SECOND
    // copy of the inheritance clause — `skipExtendsClause` (erasing, for `extern`) already grew the
    // comma loop; this one CAPTURES parents as `td.parent`, and `parseTraitOrClassNoop` calls only
    // this one. So `trait Traversable[T[_]] extends Functor[T], Foldable[T]` still ended at the
    // comma (v1/runtime/std/foldable-traversable.ssc:48) after the other copy was fixed.
    def moreParents(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
      if c.peekKind == "spike.comma" && isNameKind(c.peek2Kind) then
        val step = parent(c.bump, acc)
        moreParents(step.c, step.v)
      else St(c, acc)
    def withParents(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
      if isWord(c, "with") then
        val step = parent(c.bump, acc)
        val step2 = moreParents(step.c, step.v)
        withParents(step2.c, step2.v)
      else St(c, acc)
    val ext: St[Vector[Node]] =
      if isWord(c0, "extends") then
        val step = parent(c0.bump, Vector.empty)
        val args = if step.c.peekKind == "spike.lparen" then skipBalancedParens(step.c) else step.c // parent constructor args
        val step2 = moreParents(args, step.v)
        withParents(step2.c, step2.v)
      else St(c0, Vector.empty)
    if isWord(ext.c, "derives") then
      def derivesTail(c: Cur): Cur =
        if c.peekKind == "spike.comma" then derivesTail(skipTypeRef(c.bump)) else c
      St(derivesTail(skipTypeRef(ext.c.bump)), ext.v)
    else ext

  // `var`/`while`/`for`/`do` are not lexer keywords (like ssc1-front they are identifiers dispatched by value).
  private def isWord(c: Cur, w: String): Boolean = c.peekKind == "spike.id" && c.peekLexeme == w

  // an annotation is `@` immediately followed by a NAME (`@main`, `@tailrec`, `@nowarn`). A bare `@` not
  // followed by a name is junk (kept as a spike.error), not an annotation — so guard the skip on the name.
  private def isAnnotationStart(c: Cur): Boolean =
    c.peekKind == "spike.at" && (c.peek2Kind == "spike.id" || c.peek2Kind == "spike.uid")

  // Leading declaration modifiers — all bare ids in the spike (not lexer keywords) — erased before the decl.
  // NOT `inline`: ssc1-front's isLeadModTok (ssc1-front.ssc0:2486) erases only `final`/`private`, and `inline`
  // is neither that nor a keyword there — so `inline def f(x) = …` is TWO statements for the oracle, the var
  // `inline` (parseAtom on a plain id) and then the def, giving a `(global inline)` in the entry seq per
  // `inline def`. Erasing it dropped those statements. (`sealed`/`abstract`/`override` ARE ssc1-front keywords
  // and are consumed by its own decl/class-body parsers — ssc1-front.ssc0:2379/2384/2427/2431 — so they stay
  // erased here; `sealed` is corpus-verified by 2 matching programs.)
  // `extern` is not a Scala modifier, but ssc1-front treats it as a declaration-STARTING
  // identifier (ssc1-front.ssc0:2705, :3038): `extern def f(…): T` is a signature with no body,
  // which parseDef already supports. It lands together with the qualified-name fix above, and
  // that pairing is deliberate — on its own it made the corpus WORSE (streams.ssc 159 -> 228
  // diagnostics), because getting past `extern` only exposed `def Source.from[A](…)`.
  private val declModifiers =
    Set("sealed", "final", "abstract", "open", "private", "protected", "implicit", "override",
        "lazy", "extern")
  private def skipDeclModifiers(c: Cur): Cur =
    if c.peekKind == "spike.id" && declModifiers(c.peekLexeme) then skipDeclModifiers(c.bump) else c

  // `@name` / `@name(args)` annotation (e.g. `@main`, `@tailrec`, `@nowarn(…)`) — fully erased, matching
  // ssc1-front skipAnn (ssc1-front.ssc0:2483). Consumes ONE annotation; callers loop for stacked annotations.
  private def skipAnnotation(c0: Cur): Cur =
    val c1 = c0.bump // `@`
    val c2 = if c1.peekKind == "spike.id" || c1.peekKind == "spike.uid" then c1.bump else c1 // annotation name
    if c2.peekKind == "spike.lparen" then skipBalancedParens(c2) else c2                     // annotation arguments

  // skip a type reference after `:` in a lambda param (`Int`, `List[Int]`, `A => B`).
  private def skipTypeRef(c0: Cur): Cur =
    val c1 =
      if c0.peekKind == "spike.lparen" then skipBalancedParens(c0)
      else if c0.peekKind == "spike.uid" || c0.peekKind == "spike.id" then c0.bump
      else c0
    skipTypeTail(c1)

  // `trait X …` / `class X …` / `abstract class X …` — the spike does not lower trait/class bodies yet, so a
  // bare/abstract-only declaration is erased to a no-op (matches ssc1-front for marker & abstract traits).
  // `type X = Y` / `type X[..] = A | B` alias — erased to a no-op (ssc1-front.ssc0:2721 → Pair("sealed", ""),
  // skipToStmt). Consume the whole single-line declaration (ssc1-front's `;`-at-newline layout ends it there).
  private def parseTypeAlias(c0: Cur): St[Node] =
    val t0 = c0.peek // carrier for the no-op frame (see sealedNoop)
    val typeLine = c0.peekLine
    def eatLine(c: Cur): Cur =
      if !c.eof && c.peekLine == typeLine then eatLine(c.bump) else c
    St(eatLine(c0.bump), sealedNoop(t0))

  // a compound-assignment operator `+=`/`-=`/`*=`/… — ends with `=` but is not a comparison (`==`,`!=`,`<=`,`>=`)
  // or `:=` (a ref-set op). `x += e` desugars to `x = x + e` (ssc1-front.ssc0:1517).
  private def isCompoundAssign(op: String): Boolean =
    op.length >= 2 && op.last == '=' && op != "==" && op != "!=" && op != "<=" && op != ">=" && op != ":="

  /** `Cfg.count = 7` — an assignment whose TARGET is a dotted path, not a bare name.
    *
    * It used to reach `parseExpr`, which parsed `Cfg.count` and then met a `=` it had no rule for,
    * so the statement projected as `spike.error` — a whole assignment lost with a diagnostic
    * pointing at the `=`. Every object with a `var` member is written this way, so it is not an
    * edge case; `v3/tests/front/object-members.ssc` was the fixture that surfaced it.
    *
    * Bounded lookahead over `id (. id)+ =`, refusing `==` — with an immutable cursor the old
    * mark/advance/reset dance is simply a walk over a COPY that is then discarded. */
  private def isDottedAssign(c: Cur): Boolean =
    if !isNameKind(c.peekKind) then false
    else
      def walk(cc: Cur, segs: Int): Boolean =
        if cc.peekKind == "spike.dot" && isNameKind(cc.peek2Kind) then walk(cc.bump.bump, segs + 1)
        else segs > 0 && cc.peekKind == "spike.eq"
      walk(c.bump, 0)

  // an indented block: statements at column >= blockCol; a dedent (col < blockCol),
  // EOF, or a top-level `def` ends it. parseStmt always consumes ≥1 token (progress).
  // stopAtParen: also end at a closing `)`/`]` of an ENCLOSING group — needed for a lambda-body block
  // inside `foo(x => …)`, whose closing `)` sits (higher-column) on the body's last line so the col guard
  // misses it. NOT wanted for def/branch bodies: there a dangling `)` (e.g. after an unsupported custom
  // interpolator broke arg parsing) must fall through to parseStmt's `_err` to match ssc1-front's recovery.
  private def parseBlock(c0: Cur, blockCol: Int, stopAtParen: Boolean = false): St[Node] =
    // a block ends at a dedent (col < blockCol), the next `case` (an arm-body block), or a `}` (a braced
    // match). A nested `def` at col >= blockCol is a LOCAL def (→ letrec via parseStmt), part of the
    // block — NOT a terminator; a sibling `def` is dedented and already stopped by the col guard.
    // A COMMA ends the enclosing group's element just as its closer ends the group. `vstack([ … ,
    // if c then a else b, … ])` — the else branch is an offside block bounded by column, and the
    // `,` that ends the list element sits at a column the block still accepts, so it tried to parse
    // a statement there: "expected statement, found ','" in examples/graph-fullstack.ssc:155, which
    // the reference front parses (it fails at RUNTIME on an undefined name, not at parse).
    def enclClose(c: Cur): Boolean = stopAtParen &&
      (c.peekKind == "spike.rparen" || c.peekKind == "spike.rbracket" || c.peekKind == "spike.comma")
    def loop(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
      if !c.eof && c.peekCol >= blockCol && !isKw(c, "case") && c.peekKind != "spike.rbrace" && !enclClose(c) then
        val st = parseStmt(c)
        loop(st.c.skipSemis, acc :+ st.v)
      else St(c, acc)
    val body = loop(c0.pushBlockCol(blockCol), Vector.empty)
    St(body.c.popBlockCol, Node.Frame("spike.block", None, body.v))

  // `{ val x = e … finalExpr }` — a braced block at expression position (Scala optional-braces). Projects
  // to the SAME spike.block as an offside def-body/branch block, so lowerProg folds the vals into nested
  // lets byte-identically to ssc1-front (which treats braced and offside blocks alike). Note: a `match`
  // scrutinee's `{ … }` is consumed by parseMatch, never reaching here — only a leading `{` is a block.
  // `[e1, e2, …, en]` in EXPRESSION position → List(e1, …, en) (ssc bracket sugar, ssc1-front.ssc0:1117).
  // Statement-position `[names](path)` link-imports are handled earlier in parseStmt. `[]` → List().
  private def parseListLiteral(c0: Cur): St[Node] =
    // keep the `[`/`]` tokens as leaves so an EMPTY `[]` frame still has tokens to open/close on
    // (an empty Frame does not survive the Node→UniNode emit — cf. the tuple empty-marker case).
    val open = c0.advance
    val kids0 = open.v.map(t => Node.Leaf(t, Some("list.open"))).toVector
    def elems(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
      val el = parseExpr(c, 1)
      val withEl = acc ++ el.v.map(_.withRole("list.el")).toVector
      if el.c.peekKind == "spike.comma" then
        val comma = el.c.advance
        elems(comma.c, withEl ++ comma.v.map(t => Node.Leaf(t, Some("list.comma"))).toVector)
      else St(el.c, withEl)
    val body = if open.c.peekKind != "spike.rbracket" then elems(open.c, kids0) else St(open.c, kids0)
    if body.c.peekKind == "spike.rbracket" then
      val close = body.c.advance
      St(close.c, Node.Frame("spike.listlit", None, body.v ++ close.v.map(t => Node.Leaf(t, Some("list.close"))).toVector))
    else
      St(body.c.report("spike.expected", "expected ']' to close list literal"),
        Node.Frame("spike.listlit", None, body.v))

  private def parseBracedBlock(c0: Cur): St[Node] =
    def loop(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
      if !c.eof && c.peekKind != "spike.rbrace" then
        val st = parseStmt(c)
        loop(st.c.skipSemis, acc :+ st.v)
      else St(c, acc)
    val body = loop(c0.bump.skipSemis, Vector.empty) // consume `{`
    val closed =
      if body.c.peekKind == "spike.rbrace" then body.c.bump
      else body.c.report("spike.expected", "expected '}' to close block")
    St(closed, Node.Frame("spike.block", None, body.v))

  // topLevel: ssc1-front handles `import` ONLY in parseOneStmt (its TOP-LEVEL statement parser,
  // ssc1-front.ssc0:2526). Inside a block `import` is just a keyword in atom position, and parseAtom's
  // fallback turns an unhandled keyword into `mkVar(v)` (ssc1-front.ssc0:1121) — so an in-body
  // `import a.b.C` recovers as TWO statements: the var `import`, then the selection chain `a.b.C`
  // (parseExpr stops after `import` — the next token is an id, not an operator). Reproduce that.
  private def parseStmt(c00: Cur, topLevel: Boolean = false): St[Node] =
    def eatAnns(c: Cur): Cur = if isAnnotationStart(c) then eatAnns(skipAnnotation(c)) else c
    val c0 = eatAnns(c00)                                                     // erase `@ann` before a statement
    if c0.peekKind == "spike.lbracket" then parseLinkImport(c0)               // `[a, b, c](path.ssc)` link import
    else if topLevel && isWord(c0, "import") then parseImportStmt(c0)         // `import a.b.{x, y}` / `import a.b.*`
    else if isKw(c0, "val") then parseVal(c0)
    else if isWord(c0, "var") then parseVarStmt(c0)                           // `var x [: T] = e`
    else if isWord(c0, "while") then parseWhile(c0)                           // `while cond do body`
    else if isDefStart(c0) then parseDef(c0)                                  // nested `def` in a block → letrec
    else if (c0.peekKind == "spike.id" && c0.peek2Kind == "spike.eq") || isDottedAssign(c0) then parseAssign(c0) // `x = e`
    else if c0.peekKind == "spike.id" && c0.peek2Kind == "spike.op" && isCompoundAssign(c0.peek2Lexeme) then parseCompoundAssign(c0) // `x += e`
    else
      val parsed = parseExpr(c0, 1)
      parsed.v match
        case Some(e) =>
          // `a(idx) = rhs` array/collection update: parseExpr stops at the `=` (not an operator). When the LHS
          // is an apply, ssc1-front emits an idx_assign node (ssc1-lower:3849 → arr.set(a, idx, rhs)). A plain
          // `x = e` is caught above; a `.field = e` LHS is left as-is (an exprStmt + stray `=`), as before.
          e match
            case Node.Frame("spike.call", _, _) if parsed.c.peekKind == "spike.eq" =>
              val rhsStep = parseExpr(parsed.c.bump, 1) // `=`
              val rhs = rhsStep.v.getOrElse(Node.Frame("spike.error", None, Vector.empty))
              St(rhsStep.c, Node.Frame("spike.idxassign", None, Vector(e.withRole("idxassign.lhs"), rhs.withRole("idxassign.rhs"))))
            case _ => St(parsed.c, Node.Frame("spike.exprStmt", None, Vector(e.withRole("stmt.expr"))))
        case None =>
          val reported = parsed.c.report("spike.expected", s"expected statement, found '${parsed.c.peekLexeme}'")
          val errLine = reported.peekLine
          val adv = reported.advance
          adv.v match
            case Some(t) =>
              // ssc1-front's parseAtom NEVER fails: an unrecognised token yields the var `_err`
              // (ssc1-front.ssc0:1169-1170) and the CALLER's buildPostfix continues the chain. So the Scala-3
              // "fewer braces" call `xs.foreach: (a, b) =>` ⏎ <indented body> recovers as THREE statements:
              //   `xs.foreach`  |  `_err(a, b)`  |  `_err(lam 0 { body })`
              // — the `:` and the `=>` each become an `_err` atom; `(a, b)` applies to the first as call args;
              // and the `=>`, a LAYOUT OPENER at end-of-line (isLayoutOpener, ssc1-front.ssc0:2841), opens a
              // virtual-brace block that buildPostfix takes as a 0-arity block ARG. (a/b stay FREE vars — the
              // oracle never binds them, so this is bug-for-bug reproduction, not a lambda.)
              // An unhandled KEYWORD in atom position is NOT an error: ssc1-front's parseAtom falls through to
              // `pr(mkVar(v), advance(toks))` (ssc1-front.ssc0:1121), i.e. a var NAMED AFTER THE KEYWORD. So a
              // stray `else` (e.g. after an `if … then` whose branch a custom interpolator cut short) lowers to
              // `(global else)`, not `(global _err)`. Only a non-keyword token takes the `_err` path (:1169-1170).
              val err =
                if t.kind == "spike.kw" then Node.Leaf(t, Some("var"))
                else Node.Frame("spike.error", None, Vector(Node.Leaf(t, Some("error.token"))))
              val headStep: St[Node] =
                if isLayoutOpenerTok(t) && !adv.c.eof && adv.c.peekLine > errLine then
                  val blockStep = parseBlock(adv.c, adv.c.peekCol)
                  val thunk = Node.Frame("spike.lambda", None, Vector(blockStep.v.withRole("lam.body")))
                  St(blockStep.c, Node.Frame("spike.blockapp", None, Vector(err.withRole("blkapp.fn"), thunk.withRole("blkapp.arg"))))
                else St(adv.c, err)
              // ALWAYS an expression statement, even for a bare `_err`: ssc1-front's parseOneStmt ends at
              // parseExpr, so an unparseable token yields `Pair("expr", mkVar("_err"))` — a REAL statement that
              // reaches the entry seq as `(global _err)`. `println((42: Int))` is the canonical case: the arg
              // list stops at the `:`, and the oracle emits `_err`, `Int`, `_err`, `_err` for the residue.
              // Leaving it bare meant `isTopStmt` silently DROPPED one statement per stray token.
              // NOTE: this is only correct together with the top-level stray-`}` SKIP in parseProgram — before
              // that, wrapping measured NET-NEGATIVE (-2 quoted-macros) because a `}` orphaned by the char
              // lexer manufactured an `_err` the oracle never has, and the drop was hiding it.
              // …and then through the INFIX loop, exactly like ssc1-front's parseExprCore continues after
              // parseAtom: the residue of `println(((1 + 2): Int) + 1)` recovers as the infix `_err + 1`
              // (`(prim __arith__ "+" (global _err) (lit (int 1)))`), not as two separate statements.
              val post = postfix(headStep.c, headStep.v)
              val inf = infixLoop(post.c, post.v, 1)
              St(inf.c, Node.Frame("spike.exprStmt", None, Vector(inf.v.withRole("stmt.expr"))))
            case None => St(adv.c, Node.Frame("spike.error", None, Vector.empty))

  private def parseVal(c0: Cur): St[Node] =
    val kw = c0.advance // `val`
    val kids0 = kw.v.map(t => Node.Leaf(t, Some("val.kw"))).toVector
    // tuple-destructuring `val (a, b) = expr` → Pair("tuppat", Pair([names], expr)) (ssc1-front parseVal:1798)
    if kw.c.peekKind == "spike.lparen" then
      val tup = tupleBinderNames(kw.c.bump, kids0) // `(`
      val eq = expect(skipTypeAnnotation(tup.c), "spike.eq", "val.eq", "'='")
      val rhs = parseExpr(eq.c, 1)
      St(rhs.c, Node.Frame("spike.tuppatval", None, tup.v ++ rhs.v.map(_.withRole("val.rhs")).toVector))
    else
      val name = expectName(kw.c, "val.name", "val name")
      val kids1 = kids0 ++ name.v.toVector
      val c1 = skipTypeAnnotation(name.c) // optional `: T` (erased)
      // An ABSTRACT val — `val id: String` inside a class or trait body, with no `=` and no RHS.
      // `v1/runtime/std/geo.ssc:103` and `http.ssc:175` are both this, and demanding `=` consumed
      // the rest of the file looking for one: "expected '=', found '<eof>'".
      if c1.peekKind != "spike.eq" then St(c1, Node.Frame("spike.valdecl", None, kids1))
      else
        val eqLine = c1.peekLine
        val eq = expect(c1, "spike.eq", "val.eq", "'='")
        val kids2 = kids1 ++ eq.v.toVector
        // `=` is a LAYOUT OPENER (isLayoutOpener, ssc1-front.ssc0:2841), so an RHS starting on a LATER line is
        // an indented BLOCK — exactly like a def body — not a single expression. A lone-expression RHS lowers
        // identically (lowerBlock's last-item case is the bare expr), so this only matters when the RHS spans
        // several statements: `val x =` ⏎ `if c then html"…"` ⏎ `else html"…"` is FIVE statements once the
        // custom interpolator splits each `html"…"` into a var + a string. parseExpr kept only the first and
        // leaked the rest into the enclosing block.
        val rhs = branchExpr(eq.c, eqLine)
        St(rhs.c, Node.Frame("spike.val", None, kids2 :+ rhs.v.withRole("val.rhs")))

  /** the `(a, b, …)` binder-name run shared by `val`/`var` destructuring (the `(` already consumed) */
  private def tupleBinderNames(c0: Cur, kids0: Vector[Node]): St[Vector[Node]] =
    def loop(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
      if c.peekKind != "spike.rparen" && !c.eof then
        val withName =
          if c.peekKind == "spike.id" || c.peekKind == "spike.uid" then
            val step = c.advance
            St(step.c, acc ++ step.v.map(t => Node.Leaf(t, Some("tup.name"))).toVector)
          else St(c, acc)
        if withName.c.peekKind == "spike.comma" then loop(withName.c.bump, withName.v)
        else if withName.c.peekKind != "spike.rparen" then loop(withName.c.bump, withName.v) // skip a stray token to guarantee progress
        else loop(withName.c, withName.v)
      else St(c, acc)
    val names = loop(c0, kids0)
    St(if names.c.peekKind == "spike.rparen" then names.c.bump else names.c, names.v)

  // `var x [: T] = e` → Pair("var", (name, e)); lowerProg backs it with an lcell and rewrites reads/writes.
  private def parseVarStmt(c0: Cur): St[Node] =
    val kw = c0.advance // `var`
    val kids0 = kw.v.map(t => Node.Leaf(t, Some("var.kw"))).toVector
    // `var (a, b) = e` — DESTRUCTURING, exactly as `val (a, b) = e` has always been. Only the `val`
    // twin had it, so `var` reported `expected var name, found '('` and the file stopped there;
    // `v3/src/Parser.scala:283` is `var (lhs, ts) = parseUnary(ts0)`.
    //
    // It projects to the SAME `spike.tuppatval` frame the `val` form uses, with the `val.*` roles,
    // because what follows is identical: bind the names off a tuple. The mutability the `var`
    // announces is not modelled at Tier 0 — v3's destructuring binds each name once — so spelling
    // it a second way would be two shapes for one meaning.
    if kw.c.peekKind == "spike.lparen" then
      val tup = tupleBinderNames(kw.c.bump, kids0) // `(`
      val eq = expect(skipTypeAnnotation(tup.c), "spike.eq", "val.eq", "'='")
      val rhs = parseExpr(eq.c, 1)
      St(rhs.c, Node.Frame("spike.tuppatval", None, tup.v ++ rhs.v.map(_.withRole("val.rhs")).toVector))
    else
      val name = expectName(kw.c, "var.name", "var name")
      val c1 = skipTypeAnnotation(name.c) // optional `: T` (erased) — full generic/function type, not just one token
      val eq = expect(c1, "spike.eq", "var.eq", "'='")
      val kids1 = kids0 ++ name.v.toVector ++ eq.v.toVector
      val rhs = parseExpr(eq.c, 1)
      rhs.v match
        case Some(e) => St(rhs.c, Node.Frame("spike.var", None, kids1 :+ e.withRole("var.rhs")))
        case None    => St(rhs.c.report("spike.missing-rhs", "missing var right-hand side"), Node.Frame("spike.var", None, kids1))

  private def parseCompoundAssign(c0: Cur): St[Node] =
    val name = c0.advance // id
    val op = name.c.advance // `+=` etc. (base op = lexeme minus `=`)
    val kids0 = name.v.map(t => Node.Leaf(t, Some("ca.name"))).toVector ++ op.v.map(t => Node.Leaf(t, Some("ca.op"))).toVector
    val rhs = parseExpr(op.c, 1)
    St(rhs.c, Node.Frame("spike.compoundassign", None, kids0 ++ rhs.v.map(_.withRole("ca.rhs")).toVector))

  private def parseAssign(c0: Cur): St[Node] =
    val name = c0.advance // id
    // …and the rest of a dotted target, one leaf per segment. The `def.name`/`def.nameseg` pair
    // already in this file is the convention; a consumer that ignores the segments still sees the
    // head, which is what it saw before this existed.
    def segs(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
      if c.peekKind == "spike.dot" && isNameKind(c.peek2Kind) then
        val seg = c.bump.advance // `.`
        segs(seg.c, acc ++ seg.v.map(t => Node.Leaf(t, Some("assign.nameseg"))).toVector)
      else St(c, acc)
    val dotted = segs(name.c, name.v.map(t => Node.Leaf(t, Some("assign.name"))).toVector)
    val rhs = parseExpr(dotted.c.bump, 1) // `=`
    rhs.v match
      case Some(e) => St(rhs.c, Node.Frame("spike.assign", None, dotted.v :+ e.withRole("assign.rhs")))
      case None    => St(rhs.c.report("spike.missing-rhs", "missing assignment right-hand side"), Node.Frame("spike.assign", None, dotted.v))

  // `while cond [do] body` → Pair("while", (cond, body)); lowerProg emits a (while …) form.
  private def parseWhile(c0: Cur): St[Node] =
    val kw = c0.advance // `while`
    val cond = parseExpr(kw.c, 1)
    val kids0 = kw.v.map(t => Node.Leaf(t, Some("while.kw"))).toVector ++ cond.v.map(_.withRole("while.cond")).toVector
    val doLine = cond.c.peekLine
    val c1 = if isWord(cond.c, "do") then cond.c.bump else cond.c // optional `do`
    // an indented body on a LATER line is a block (`while c do⏎ s1⏎ s2`), not a single expr (like a def body)
    val body = branchExpr(c1, doLine)
    St(body.c, Node.Frame("spike.while", None, kids0 :+ body.v.withRole("while.body")))

  private def parseMatch(c0: Cur, scrut: Node): St[Node] =
    val kw = c0.advance // `match`
    val kids0 = Vector(scrut.withRole("match.scrut")) ++ kw.v.map(t => Node.Leaf(t, Some("match.kw"))).toVector
    val braced = kw.c.peekKind == "spike.lbrace"
    val open = if braced then kw.c.advance else St(kw.c, None)
    val kids1 = kids0 ++ open.v.map(t => Node.Leaf(t, Some("match.open"))).toVector
    val c1 = open.c.skipSemis
    // Non-braced arms are offside-bounded: a `case` dedented below the first arm's column, or a
    // top-level `case class`, ends the match (else `def f = e match … / case class C` swallows C).
    val armCol = if isKw(c1, "case") then c1.peekCol else 0
    def arms(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
      if isKw(c, "case") && c.peek2Lexeme != "class" && (braced || c.peekCol >= armCol) then
        val arm0 = parseArm(c)
        arms(arm0.c.skipSemis, acc :+ arm0.v)
      else St(c, acc)
    val armsStep = arms(c1, kids1)
    if braced && armsStep.c.peekKind == "spike.rbrace" then
      val close = armsStep.c.advance
      St(close.c, Node.Frame("spike.match", None, armsStep.v ++ close.v.map(t => Node.Leaf(t, Some("match.close"))).toVector))
    else St(armsStep.c, Node.Frame("spike.match", None, armsStep.v))

  private def parseArm(c0: Cur): St[Node] =
    val kw = c0.advance // `case`
    val kids0 = kw.v.map(t => Node.Leaf(t, Some("case.kw"))).toVector
    val pat = parseArmPattern(kw.c)
    val kids1 = kids0 :+ pat.v.withRole("case.pat")
    val guarded: St[Vector[Node]] =
      if isKw(pat.c, "if") then
        val ifKw = pat.c.advance
        val g = parseExpr(ifKw.c, 1)
        St(g.c, kids1 ++ ifKw.v.map(t => Node.Leaf(t, Some("case.ifkw"))).toVector ++ g.v.map(_.withRole("case.guard")).toVector)
      else St(pat.c, kids1)
    val arrowLine = guarded.c.peekLine
    val arrow: St[Vector[Node]] =
      if guarded.c.peekKind == "spike.op" && guarded.c.peekLexeme == "=>" then
        val a = guarded.c.advance
        St(a.c, guarded.v ++ a.v.map(t => Node.Leaf(t, Some("case.arrow"))).toVector)
      else St(guarded.c.report("spike.expected", "expected '=>' in case arm"), guarded.v)
    // An EMPTY body used to be carried by the `=>` token itself, added a SECOND time under
    // the role `unit.tok` because "an empty Frame would not survive the emit". That put one
    // token in the tree twice, which a reconstruction prints twice — found by the corpus
    // sweep in tests/conformance/js-generator-next-option.ssc. The absence of a `case.body`
    // child is signal enough: the projection reads Unit from it.
    val body = armBody(arrow.c, arrowLine)
    St(body.c, Node.Frame("spike.arm", None, arrow.v ++ body.v.map(_.withRole("case.body")).toVector))

  // An arm body is a STATEMENT LIST terminated by `case` / `}` / EOF, and then: exactly ONE `expr`
  // statement → the BARE expr; anything else (a single val/var/assign, or 2+ statements) → a
  // `("block", stmts)` (ssc1-front parseArmBody, ssc1-front.ssc0:1974-1996).
  //
  // ssc1-front needs no column rule there because its layout pass emits a virtual `}` at a dedent; the
  // spike has no synthetic tokens, so it emulates the dedent with parseBlock's column guard, and stops at
  // an enclosing `)`/`]` (which the layout's closeToDelim closes for ssc1-front). Both are REQUIRED: without
  // the paren stop, `f(x match ⏎ case A => 1)` eats the call's `)` into an `_err` statement.
  //
  // An OFFSIDE body was already a block (the `=>` opens a layout block) — unchanged. A SAME-LINE body is
  // almost always ONE expression and unwraps to exactly what parseExpr produced before, so this is a strict
  // superset of the old behaviour. The list matters for a CUSTOM INTERPOLATOR, which ssc1-front does not lex
  // as one token: `case Some(u) => html"<p>…</p>"` is the var `html` THEN the string — two statements → a
  // block. Keeping the single-expr UNWRAP is what makes this safe (wrapping every same-line arm in a block
  // changes the body's tag for hundreds of programs — the trap that sank the earlier attempt).
  private def armBody(c0: Cur, arrowLine: Int): St[Option[Node]] =
    if !c0.eof && c0.peekLine > arrowLine then
      val branch = branchExpr(c0, arrowLine)
      St(branch.c, Some(branch.v))
    else
      val bodyCol = c0.peekCol
      def loop(cRaw: Cur, acc: Vector[Node]): St[Vector[Node]] =
        // parseArmBody skipSemis's BEFORE each terminator test — an explicit `;` between arms
        // (`{ case Text(s) => s; case _ => "?" }`) is a separator, NOT a statement. parseBlock does not skip
        // semis, so borrowing it here turned the `;` into an `_err` statement and broke the bare-expr unwrap.
        val c = cRaw.skipSemis
        if c.eof || isKw(c, "case") || c.peekKind == "spike.rbrace" ||
           c.peekKind == "spike.rparen" || c.peekKind == "spike.rbracket" || c.peekCol < bodyCol
        then St(c, acc)
        else
          val st = parseStmt(c)
          if st.c.p == c.p then St(st.c, acc :+ st.v) // guarantee progress
          else loop(st.c, acc :+ st.v)
      val stmts = loop(c0, Vector.empty)
      val ss = stmts.v
      val node =
        if ss.isEmpty then None // `case A =>` with NO body → Unit, signalled by the absence
        else if ss.length == 1 then
          Some(ss.head match
            case Node.Frame("spike.exprStmt", _, inner) if inner.length == 1 => inner.head
            case _                                                           => Node.Frame("spike.block", None, ss))
        else Some(Node.Frame("spike.block", None, ss))
      St(stmts.c, node)

  // a full arm pattern: `alias @ PAT` (bind, bpat) around `PAT | PAT | …` (alternatives, apat).
  private def parseArmPattern(c0: Cur): St[Node] =
    val aliasStep: St[Option[SourceToken]] =
      if c0.peekKind == "spike.id" && c0.peekLexeme != "_" && c0.peek2Lexeme == "@" then
        val a = c0.advance
        St(a.c.bump, a.v) // consume name + `@`
      else St(c0, None)
    // ONE ALTERNAND = a cons pattern with its OWN optional type ascription. The ascription used to
    // sit OUTSIDE the alternation — alternatives first, then one `: T` for all of them — so
    // `case _: Int | _: String =>` read `_`, took `: Int`, and then met the `|` where the arm
    // wanted `=>`: "expected '=>' in case arm". Scala binds the ascription TIGHTER than the bar,
    // and `v3/src/Parser.scala:270` is the shape that needs it —
    // `case _: Expr.While | _: Expr.If | _: Expr.Match | _: Expr.Block =>`.
    def alternand(c: Cur): St[Node] =
      val p0 = parseConsPattern(c)
      if p0.c.peekKind == "spike.colon" then
        val ty = expectType(p0.c.bump, "tpat.type") // `:`
        val c1 = skipTypeSegments(ty.c) // qualified `case x: A.B =>` — consume `.B` (+ generics); the head is the tag
        St(c1, Node.Frame("spike.tpat", None, Vector(p0.v.withRole("tpat.pat")) ++ ty.v.toVector))
      else p0
    val first = alternand(aliasStep.c)
    def alts(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
      if c.peekKind == "spike.op" && c.peekLexeme == "|" then
        val next = alternand(c.bump) // `|`
        alts(next.c, acc :+ next.v)
      else St(c, acc)
    val altStep = alts(first.c, Vector(first.v))
    val typed =
      if altStep.v.length > 1 then Node.Frame("spike.apat", None, altStep.v.map(_.withRole("apat.alt")))
      else first.v
    aliasStep.v match
      case Some(a) => St(altStep.c, Node.Frame("spike.bpat", None, Vector(Node.Leaf(a, Some("bpat.alias")), typed.withRole("bpat.inner"))))
      case None    => St(altStep.c, typed)

  // cons-infix pattern: `h :: t` → Cons(h, t), right-associative (`a :: b :: c` = `a :: (b :: c)`), binding
  // tighter than `|` alternatives and `: T` ascription. Projects to the same cpat "Cons" as ssc1-front.
  private def parseConsPattern(c0: Cur): St[Node] =
    val head = parsePattern(c0)
    if head.c.peekKind == "spike.op" && head.c.peekLexeme == "::" then
      val tail = parseConsPattern(head.c.bump) // `::`
      St(tail.c, Node.Frame("spike.conspat", None, Vector(head.v.withRole("conspat.arg"), tail.v.withRole("conspat.arg"))))
    else head

  // patterns: int literal (lpat) / `_` (wpat) / lowercase binder (vpat) / ctor `Name(subpats)`
  // (cpat) / tuple `(a, b)` (→ cpat "Pair"/"TupleN"). Recursive for sub-patterns.
  private def parsePattern(c0: Cur): St[Node] =
    def leafOf(role: String): St[Node] =
      val step = c0.advance
      St(step.c, step.v.map(t => Node.Leaf(t, Some(role))).get)
    c0.peekKind match
      case "spike.int"   => leafOf("pat.lit")
      case "spike.str"   => leafOf("pat.lit") // `case "ping" =>` literal
      case "spike.float" => leafOf("pat.lit")
      case "spike.id" if c0.peekLexeme == "_" => leafOf("pat.wild")
      // A lowercase name APPLIED to arguments is an extractor, not a binder — `case foo(x) =>`.
      // Scala binds only a SIMPLE identifier, and now that a class may be named `foo` this is
      // reachable. `case foo =>` below still binds, which is the rule in §"patterns" of the Scala
      // spec and the one place capitalisation changes meaning.
      case "spike.id" if c0.peek2Kind == "spike.lparen" => parseCtorPat(c0)
      // A DOTTED name is a stable identifier, never a binder: `case scala.util.Failure(e) =>` and
      // `case Status.Ok =>` both REFER. Scala binds only a simple identifier, so the case of the
      // first segment is irrelevant once a `.` follows it — which is why this sits beside the rule
      // above rather than under `spike.uid`. parseCtorPat already walks the `.seg` chain and keeps
      // the last segment as the tag.
      case "spike.id" if c0.peek2Kind == "spike.dot" => parseCtorPat(c0)
      case "spike.id"  => leafOf("pat.var") // incl. true/false → lpat bool
      case "spike.uid" => parseCtorPat(c0)
      case "spike.lparen" => parseTuplePat(c0)
      case _ =>
        val reported = c0.report("spike.bad-pattern", s"unsupported pattern '${c0.peekLexeme}'")
        val step = reported.advance
        St(step.c, step.v.map(t => Node.Frame("spike.error", None, Vector(Node.Leaf(t, Some("error.token")))))
          .getOrElse(Node.Frame("spike.error", None, Vector.empty)))

  private def parseCtorPat(c0: Cur): St[Node] =
    // a qualified pattern `Logger.log(a, resume)` (effect-handler op / `pkg.Ctor`) uses the LAST segment as
    // the tag (ssc1-front parsePatAtom:1895) — walk any `.seg` chain and keep the final name.
    def lastSeg(c: Cur, name: SourceToken): St[SourceToken] =
      if c.peekKind == "spike.dot" && (c.peek2Kind == "spike.id" || c.peek2Kind == "spike.uid") then
        val seg = c.bump.advance
        lastSeg(seg.c, seg.v.get)
      else St(c, name)
    val head = c0.advance // uid, or a lowercase name applied to arguments
    val named = lastSeg(head.c, head.v.get)
    val kids0 = Vector[Node](Node.Leaf(named.v, Some("cpat.name")))
    if named.c.peekKind == "spike.lparen" then
      val open = named.c.advance
      val kids1 = kids0 ++ open.v.map(t => Node.Leaf(t, Some("cpat.open"))).toVector
      def args(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
        if c.peekKind != "spike.rparen" && !c.eof && !isKw(c, "case") then
          val sub = parseSubPattern(c)
          val withArg = acc :+ sub.v.withRole("cpat.arg")
          if sub.c.peekKind == "spike.comma" then
            val comma = sub.c.advance
            args(comma.c, withArg ++ comma.v.map(t => Node.Leaf(t, Some("cpat.comma"))).toVector)
          else args(sub.c, withArg)
        else St(c, acc)
      val argStep = args(open.c, kids1)
      if argStep.c.peekKind == "spike.rparen" then
        val close = argStep.c.advance
        St(close.c, Node.Frame("spike.cpat", None, argStep.v ++ close.v.map(t => Node.Leaf(t, Some("cpat.close"))).toVector))
      else
        St(argStep.c.report("spike.expected", "expected ')' in constructor pattern"),
          Node.Frame("spike.cpat", None, argStep.v))
    else St(named.c, Node.Frame("spike.cpat", None, kids0))

  // a sub-pattern inside a tuple/constructor pattern. Each element may itself be a cons pattern —
  // `(x :: xs, y :: ys)`, `Some(h :: t)` — which binds tighter than the enclosing comma, so parse a
  // base sub-pattern and fold a trailing `:: rest` into a conspat (right-associative, → cpat "Cons").
  private def parseSubPattern(c0: Cur): St[Node] =
    val base = parseSubPatternCons(c0)
    // ALTERNATIVES nest. `case C("a" | "b", k) =>` is ordinary Scala and only the ARM's top level
    // had the bar; inside a constructor the `|` reached `parseSubPatternAtom`, which reported
    // `unsupported pattern '|'`. `v3/src/Lower.scala:828` is the shape:
    // `case Expr.Call("Array" | "Vector", argEs, p)`.
    //
    // Same frame as the top level (`spike.apat`), so the projection and the lowering need nothing
    // new: alternatives bind nothing wherever they appear, which is what makes them composable.
    if !(base.c.peekKind == "spike.op" && base.c.peekLexeme == "|") then base
    else
      def alts(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
        if c.peekKind == "spike.op" && c.peekLexeme == "|" then
          val next = parseSubPatternCons(c.bump)
          alts(next.c, acc :+ next.v)
        else St(c, acc)
      val altStep = alts(base.c, Vector(base.v))
      St(altStep.c, Node.Frame("spike.apat", None, altStep.v.map(_.withRole("apat.alt"))))

  private def parseSubPatternCons(c0: Cur): St[Node] =
    val base = parseSubPatternAtom(c0)
    if base.c.peekKind == "spike.op" && base.c.peekLexeme == "::" then
      val tail = parseSubPattern(base.c.bump) // `::`
      St(tail.c, Node.Frame("spike.conspat", None, Vector(base.v.withRole("conspat.arg"), tail.v.withRole("conspat.arg"))))
    else base

  // a base sub-pattern with an optional `: T` ascription (`(word: String, _: Int)`, `Foo(x: Int)`),
  // mirroring parseArmPattern's tpat but at nesting depth. The type head is kept (one token, generics
  // skipped) exactly like ssc1-front's patternTypeHead.
  private def parseSubPatternAtom(c0: Cur): St[Node] =
    val base = parsePattern(c0)
    if base.c.peekKind == "spike.colon" then
      val ty = expectType(base.c.bump, "tpat.type")
      val c1 = skipTypeTail(ty.c) // `: List[Int]` — generic args erased, head kept
      St(c1, Node.Frame("spike.tpat", None, Vector(base.v.withRole("tpat.pat")) ++ ty.v.toVector))
    else base

  private def parseTuplePat(c0: Cur): St[Node] =
    val open = c0.advance
    val kids0 = open.v.map(t => Node.Leaf(t, Some("tup.open"))).toVector
    def args(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
      if c.peekKind != "spike.rparen" && !c.eof && !isKw(c, "case") then
        val sub = parseSubPattern(c)
        val withArg = acc :+ sub.v.withRole("tup.arg")
        if sub.c.peekKind == "spike.comma" then
          val comma = sub.c.advance
          args(comma.c, withArg ++ comma.v.map(t => Node.Leaf(t, Some("tup.comma"))).toVector)
        else args(sub.c, withArg)
      else St(c, acc)
    val argStep = args(open.c, kids0)
    if argStep.c.peekKind == "spike.rparen" then
      val close = argStep.c.advance
      St(close.c, Node.Frame("spike.tuppat", None, argStep.v ++ close.v.map(t => Node.Leaf(t, Some("tup.close"))).toVector))
    else
      St(argStep.c.report("spike.expected", "expected ')' in tuple pattern"),
        Node.Frame("spike.tuppat", None, argStep.v))

  // `x => body` and `(x, y) => body` are lambdas; they bind loosest, so only at the outer level
  // (minPrec ≤ 1), mirroring ssc1-front parseExprCore. `(…)` may instead be a paren/tuple, so the
  // paren form is tried and DISCARDED if no `=>` follows (the old mark/reset backtrack: the
  // speculative cursor is simply dropped).
  private def tryParseLambda(c0: Cur): St[Option[Node]] =
    if c0.peekKind == "spike.id" && c0.peek2Lexeme == "=>" then
      val name = c0.advance
      val arrowLine = name.c.peekLine // line of `=>`
      val body = parseLambdaBody(name.c.bump, arrowLine) // =>
      St(body.c, Some(Node.Frame("spike.lambda", None, Vector(Node.Leaf(name.v.get, Some("lam.param")), body.v.withRole("lam.body")))))
    else if c0.peekKind == "spike.lparen" then
      tryLambdaParams(c0) match
        case Some(ps) if ps.c.peekLexeme == "=>" =>
          val arrowLine = ps.c.peekLine // line of `=>`
          val body = parseLambdaBody(ps.c.bump, arrowLine) // =>
          St(body.c, Some(Node.Frame("spike.lambda", None, ps.v.map(p => Node.Leaf(p, Some("lam.param"))) :+ body.v.withRole("lam.body"))))
        case _ => St(c0, None)
    else St(c0, None)

  // a lambda body starting on a LATER line than its `=>` is an indented block (`xs.map(x =>\n  val d = …\n  d*d)`),
  // exactly like a def body — else a single inline expression. The block folds to nested lets in projection.
  private def parseLambdaBody(c0: Cur, arrowLine: Int): St[Node] =
    // An offside body (first token on a LATER line than the `=>`) is a BLOCK — `=>` is a layout opener, so
    // ssc1-front's layout pass wraps the indented lines in a virtual `{ … }` (isLayoutOpener,
    // ssc1-front.ssc0:2841) and the body projects as `("block", …)`. Keep that wrapper: a lone `expr`
    // statement lowers to the bare expr anyway (lowerBlock's last-item case is `lowerE(scope, data)` —
    // NO let, ssc1-lower.ssc0:3950), so the block is IR-neutral — but the TAG is NOT. ssc1-lower's lambda
    // path treats `lam([p], match(var p, arms))` as a partial-function/effect-handler literal and marks every
    // arm with `__handler_dispatch_selected__` (ssc1-lower.ssc0:2648-2659); an unwrapped one-statement body
    // hits that path, while the oracle's block-tagged body does not. Hence: DO NOT unwrap.
    // The body ends at an enclosing `)`/`]` ONLY when the lambda actually sits inside a `( … )` argument
    // group (`xs.map(x =>` ⏎ `body)`), mirroring closeToDelim: a layout block opened inside a delimiter is
    // closed by it, one opened outside is not. A top-level `val f = (a, b) =>` ⏎ body is at depth 0, so a
    // STRAY `)` inside its body — e.g. the leftover of `req.get(uri"$url")` once the custom interpolator
    // breaks arg parsing — stays a statement and recovers as `_err.send(…)`, as ssc1-front does, instead
    // of truncating the body (which dropped the rest of the lambda into the enclosing scope).
    if !c0.eof && c0.peekLine > arrowLine then parseBlock(c0, c0.peekCol, stopAtParen = c0.parenDepth > 0)
    else
      val e = parseExpr(c0, 1)
      St(e.c, e.v.getOrElse(Node.Frame("spike.error", None, Vector.empty)))

  // scan `( id [: T] (, id [: T])* )` — the shape of a lambda parameter clause. Returns the param
  // tokens with the cursor left just after `)` on success, else None (the caller keeps its own cursor).
  private def tryLambdaParams(c0: Cur): Option[St[Vector[SourceToken]]] =
    def loop(c: Cur, first: Boolean, acc: Vector[SourceToken]): Option[St[Vector[SourceToken]]] =
      if c.peekKind == "spike.rparen" || c.eof then Some(St(c, acc))
      else
        val afterComma: Option[Cur] =
          if first then Some(c)
          else if c.peekKind == "spike.comma" then Some(c.bump)
          else None
        afterComma.flatMap { c1 =>
          if c1.peekKind == "spike.id" then
            val nm = c1.advance
            val c2 = if nm.c.peekKind == "spike.colon" then skipTypeRef(nm.c.bump) else nm.c
            loop(c2, false, acc :+ nm.v.get)
          else None
        }
    loop(c0.bump, true, Vector.empty) match // (
      case Some(st) if st.c.peekKind == "spike.rparen" => Some(St(st.c.bump, st.v))
      case _ => None

  private def parseExpr(c0: Cur, minPrec: Int): St[Option[Node]] =
    val lam = if minPrec <= 1 then tryParseLambda(c0) else St(c0, None)
    if lam.v.isDefined then lam else parseInfixExpr(lam.c, minPrec)

  private def parseInfixExpr(c0: Cur, minPrec: Int): St[Option[Node]] =
    val first = parsePostfix(c0)
    first.v match
      case Some(f) =>
        val looped = infixLoop(first.c, f, minPrec)
        St(looped.c, Some(looped.v))
      case None => St(first.c, None)

  // the infix (Pratt) loop over an ALREADY-PARSED left operand. Split out of parseInfixExpr so the
  // statement-level `_err` recovery can feed its error atom through the SAME loop: ssc1-front's parseAtom
  // returns `_err` and its caller (parseExprCore) carries straight on into the infix table, so a residual
  // `) + 1` recovers as the infix `_err + 1`, not as two separate statements.
  private def infixLoop(c0: Cur, first: Node, minPrec: Int): St[Node] =
    def loop(c: Cur, left: Node): St[Node] =
      val p = c.peekPrec
      // `to`/`until` are id-infix range words that bind loosest (only at the outer level)
      val isRange = minPrec <= 1 && c.peekKind == "spike.id" && (c.peekLexeme == "to" || c.peekLexeme == "until")
      // ANY other identifier is an infix application too — `b add 2` is `b.add(2)`, Scala's rule.
      //
      // `to`/`until` used to be the only two, an exact mirror of ssc1-front's parseInfix
      // (v2/lib/ssc1-front.ssc0:1901-1909), which also stops there. v3's own front lowers the
      // general form since 92e90e34e, so this front refusing it was a split between two fronts of
      // ONE compiler — v3/BUGS.md `infix-application-does-not-reach-a-declared-class-method`.
      //
      // IT BUILDS THE SAME NODE `to`/`until` build. `spike.rangeop` is not a range: it lowers to
      // `mkApp(mkSel(lhs, word), [rhs])` here and to `RangeOp` -> `Expr.Bin(op, …)` in the typed
      // projection, which is exactly `lhs.word(rhs)` — the shape an id-infix application means. The
      // kind keeps its old spelling because renaming it would move byte-exact CST pins for nothing.
      //
      // THE GUARDS, each because legal source would otherwise change meaning:
      //   - `spike.id`, so a `spike.uid` stays a constructor reference rather than an operator;
      //   - not a reserved word (`notInfixWord`), since only 11 words are lexer keywords here;
      //   - the operator on the SAME LINE as the end of the left operand, or the two statements
      //     `a` NEWLINE `b` fuse into one application;
      //   - the right operand on that same line and able to start an atom (`startsIdInfixOperand`).
      // Every one of them can only WITHHOLD the new arm, so a program the old loop handled takes
      // the old route unchanged; the arm is reachable only where the loop used to stop and the
      // caller then failed to parse.
      val isIdInfix = minPrec <= 1 && !isRange && c.peekKind == "spike.id" &&
        !notInfixWord(c.peekLexeme) &&
        c.peekLine == c.prevEndLine &&
        c.peek2Line == c.peekLine && startsIdInfixOperand(c.peek2Kind)
      if p >= minPrec && p > 0 then
        val opStep = c.advance // spike.op
        val op = opStep.v.get
        val rightMin = if op.lexeme == "::" then p else p + 1 // `::` is right-associative
        val right = parseExpr(opStep.c, rightMin)
        right.v match
          case Some(r) =>
            loop(right.c, Node.Frame("spike.infix", None,
              Vector(left.withRole("bin.left"), Node.Leaf(op, Some("bin.op")), r.withRole("bin.right"))))
          case None =>
            loop(right.c.report("spike.missing-operand", s"missing right operand after '${op.lexeme}'"),
              Node.Frame("spike.infix", None, Vector(left.withRole("bin.left"), Node.Leaf(op, Some("bin.op")))))
      else if isRange || isIdInfix then
        val word = c.advance
        val rhs = parsePostfix(word.c)
        val rhsNode = rhs.v.getOrElse(Node.Frame("spike.error", None, Vector.empty))
        loop(rhs.c, Node.Frame("spike.rangeop", None,
          Vector(left.withRole("range.lhs"), Node.Leaf(word.v.get, Some("range.op")), rhsNode.withRole("range.rhs"))))
      else St(c, left)
    loop(c0, first)

  private def parseAtom(c0: Cur): St[Option[Node]] =
    def leafOf(role: String): St[Option[Node]] =
      val step = c0.advance
      St(step.c, step.v.map(t => Node.Leaf(t, Some(role))))
    c0.peekKind match
      case "spike.int"    => leafOf("int")
      case "spike.float"  => leafOf("float")
      case "spike.str"    => leafOf("str")
      case "spike.lparen" => parseParen(c0)
      // Scala 3 metaprogramming. All three erase to their contents for this dialect — it parses the
      // language, it does not run the macro — but they must PARSE, because the reference front runs
      // examples/quoted-macro-constfold.ssc and prints `literal: 7`.
      //   'x        a quoted name          -> spike.qname leaf
      //   '{ e }    a quote block          -> spike.quote  wrapping a block
      //   ${ e }    a splice               -> spike.splice wrapping a block
      //   $x        a splice of a name     -> spike.splice wrapping that name
      case "spike.qname" => leafOf("qname")
      case "spike.quote" =>
        val kw = c0.advance
        val body: St[Node] =
          if kw.c.peekKind == "spike.lbrace" then parseBracedBlock(kw.c)
          else St(kw.c, Node.Frame("spike.error", None, Vector.empty))
        St(body.c, Some(Node.Frame("spike.quote", None, Vector(Node.Leaf(kw.v.get, Some("quote.kw")), body.v.withRole("quote.body")))))
      case "spike.splice" =>
        val kw = c0.advance
        val body: St[Node] =
          if kw.c.peekKind == "spike.lbrace" then parseBracedBlock(kw.c)
          else
            val post = parsePostfix(kw.c)
            St(post.c, post.v.getOrElse(Node.Frame("spike.error", None, Vector.empty)))
        St(body.c, Some(Node.Frame("spike.splice", None, Vector(Node.Leaf(kw.v.get, Some("splice.kw")), body.v.withRole("splice.body")))))
      case "spike.lbracket" =>
        val lit = parseListLiteral(c0) // `[e1, e2, …]` bracket sugar → List(e1, …) (K62.6f)
        St(lit.c, Some(lit.v))
      case "spike.lbrace" =>
        val blk = parseBracedBlock(c0) // `{ val … ; expr }` braced block (non-match position)
        St(blk.c, Some(blk.v))
      case "spike.kw" if c0.peekLexeme == "if" => parseIf(c0)
      case "spike.op" if c0.peekLexeme == "???" => leafOf("notimpl") // Predef.??? → __notImplemented__
      case "spike.op" if c0.peekLexeme == "-" || c0.peekLexeme == "!" || c0.peekLexeme == "~" =>
        val pre = parsePrefix(c0)
        St(pre.c, Some(pre.v))
      case "spike.id" if c0.peekLexeme == "summon" =>
        val s = parseSummon(c0)
        St(s.c, Some(s.v))
      // `throw e` → prim __throw__(e); `new C(args)` == `C(args)` (new is stripped). ssc1-front dispatches
      // these on the identifier value in parseAtom (not lexer keywords), so we mirror it here.
      case "spike.id" if c0.peekLexeme == "throw" =>
        val t = parseThrow(c0)
        St(t.c, Some(t.v))
      case "spike.id" if c0.peekLexeme == "new"   => parseAtom(c0.bump)
      case "spike.id" if c0.peekLexeme == "for"   =>
        val f = parseFor(c0)
        St(f.c, Some(f.v))
      case "spike.id" if c0.peekLexeme == "try"   =>
        val t = parseTry(c0)
        St(t.c, Some(t.v))
      // interpolator: an `s`/`f`/`raw`/`md` prefix immediately before a string token (ssc1-front
      // detects interpolation the same way — id value + following str, no adjacency check).
      case "spike.id" if isInterpPrefix(c0.peekLexeme) && c0.peek2Kind == "spike.str" && c0.peekAbutsNext =>
        val i = parseInterp(c0)
        St(i.c, Some(i.v))
      case "spike.id" | "spike.uid" => parseIdOrCall(c0) // uid = uppercase ctor/type ref → mkUVar
      case "spike.junk" =>
        val reported = c0.report("spike.unexpected-expr", s"unexpected token '${c0.peekLexeme}' in expression")
        val step = reported.advance
        St(step.c, step.v.map(t => Node.Frame("spike.error", None, Vector(Node.Leaf(t, Some("error.token"))))))
      case _ => St(c0, None) // eof / kw boundary / operator / `)` / `=` / `:` / `,` — not an atom

  // `summon[T]` — resolved to the matching given by lowerProg. A bare `summon` (no `[`) is a var.
  // The payload is the WHOLE type application as ONE string, exactly as ssc1-front builds it: its
  // `[` handler runs readTypeApply (ssc1-front.ssc0:1305-1317), which concatenates every token up to the
  // matching `]` with joinStrs — NO separator — and hands `Pair("summon", "Show[Int]")` to the lowerer.
  // ssc1-lower matches that string against the given registry, so the FULL application is load-bearing:
  // capturing only the head (`"Show"`) never matches an instance and degrades to `__summon_value_Show`.
  private def parseSummon(c0: Cur): St[Node] =
    val id = c0.advance // `summon`
    if id.c.peekKind != "spike.lbracket" then St(id.c, Node.Leaf(id.v.get, Some("var")))
    else
      // captureTypeArgTokens consumes the balanced `[ … ]` and yields the inner tokens (depth-1 `]` dropped)
      val ta = captureTypeArgTokens(id.c)
      St(ta.c, Node.Frame("spike.summon", None,
        Node.Leaf(id.v.get, Some("summon.kw")) +: ta.v.map(_.withRole("summon.tok"))))

  // `throw e` → spike.throw holding the operand (a full expr, like ssc1-front's `parseExpr(advance)`).
  private def parseThrow(c0: Cur): St[Node] =
    val e = parseExpr(c0.bump, 1) // `throw`
    St(e.c, Node.Frame("spike.throw", None, Vector(e.v.getOrElse(Node.Frame("spike.error", None, Vector.empty)).withRole("throw.expr"))))

  // `try BODY [catch handler] [finally F]` (ssc1-front.ssc0:1061) → __tryCatch__ / __tryCatchFinally__ /
  // __tryFinally__ prims (BODY & finally become 0-arg thunks in the projection; the catch is a partial fn).
  private def parseTry(c0: Cur): St[Node] =
    val tryLine = c0.peekLine
    val c1 = c0.bump // `try`
    // A body starting on a LATER line is an indented block, exactly like a def body. Taking a single
    // expression kept only the first statement and left the rest — and the `catch` — to the
    // enclosing block, which then read `case` at statement level and asked for `case class`.
    // `examples/graphql-client.ssc:74` is `try` ⏎ two statements ⏎ `catch`.
    val body: St[Vector[Node]] =
      if !c1.eof && c1.peekLine > tryLine then
        val blk = parseBlock(c1, c1.peekCol)
        St(blk.c, Vector(blk.v.withRole("try.body")))
      else
        val e = parseExpr(c1, 1)
        St(e.c, e.v.map(_.withRole("try.body")).toVector)
    val caught: St[Vector[Node]] =
      if isWord(body.c, "catch") then
        val c2 = body.c.bump // `catch`
        val handler: St[Node] =
          if c2.peekKind == "spike.lbrace" then parseBlockArg(c2)      // `catch { case … }` → spike.pfblock/lambda
          else if isKw(c2, "case") then parsePartialFn(c2)             // braceless `catch case … => …`
          else
            val e = parseExpr(c2, 1)
            St(e.c, e.v.getOrElse(Node.Frame("spike.error", None, Vector.empty))) // a PartialFunction value
        St(handler.c, body.v :+ handler.v.withRole("try.catch"))
      else body
    val finalized: St[Vector[Node]] =
      if isWord(caught.c, "finally") then
        // `finally` is a LAYOUT OPENER, for the same reason `try` is (the comment above): taking a
        // single expression kept only the finalizer's FIRST statement and left the rest to the
        // enclosing block — a two-println finalizer ran one println inside the try and one after it,
        // and the try's own value was discarded as a statement. `try-multistmt-body` shape (3).
        val finLine = caught.c.peekLine
        val c3 = caught.c.bump // `finally`
        if !c3.eof && c3.peekLine > finLine then
          val blk = parseBlock(c3, c3.peekCol)
          St(blk.c, caught.v :+ blk.v.withRole("try.finally"))
        else
          val e = parseExpr(c3, 1)
          St(e.c, caught.v ++ e.v.map(_.withRole("try.finally")).toVector)
      else caught
    St(finalized.c, Node.Frame("spike.try", None, finalized.v))

  // braceless `case P => B; …` arms (Scala 3 fewer-braces catch) → __pf => __pf match { arms } (spike.pfblock).
  private def parsePartialFn(c0: Cur): St[Node] =
    val armCol = if isKw(c0, "case") then c0.peekCol else 0
    def arms(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
      if isKw(c, "case") && c.peek2Lexeme != "class" && c.peekCol >= armCol then
        val a = parseArm(c)
        arms(a.c.skipSemis, acc :+ a.v)
      else St(c, acc)
    val armStep = arms(c0, Vector.empty)
    St(armStep.c, Node.Frame("spike.pfblock", None, armStep.v))

  // `for g1 ; g2 ; … (do|yield) body` desugared EXACTLY like ssc1-front's parseForFrom: each generator
  // `binder <- gen [if guard]` becomes `gen[.filter(binderLam(guard))].{flatMap|map|foreach}(binderLam(…))`
  // — flatMap for every generator but the last, map (yield) / foreach (do) for the last. A tuple binder
  // `(a,b)` desugars to a `__fp => { val a = __fp._1; … }` destructuring lambda (mkBinderLam).
  private def parseFor(c0: Cur): St[Node] =
    val c1 = c0.bump // `for`
    val c2 = if c1.peekKind == "spike.lparen" || c1.peekKind == "spike.lbrace" then c1.bump else c1
    def gens(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
      val g = parseForGen(c)
      val withGen = acc :+ g.v.withRole("for.gen")
      // generators are `;`-separated in the `( … )`/`{ … }` forms, but NEWLINE-separated in the braceless
      // multiline form `for⏎ x <- … ⏎ y <- … ⏎ yield …` (newline is trivia here) — so also continue when the
      // next token starts another generator (an id/`(` binder that is not the terminating `yield`/`do`).
      if g.c.peekKind == "spike.semi" then gens(g.c.bump, withGen)
      else if (g.c.peekKind == "spike.id" || g.c.peekKind == "spike.lparen") && !isWord(g.c, "yield") && !isWord(g.c, "do") then
        gens(g.c, withGen)
      else St(g.c, withGen)
    val genStep = gens(c2, Vector.empty)
    val c3 = if genStep.c.peekKind == "spike.rparen" || genStep.c.peekKind == "spike.rbrace" then genStep.c.bump else genStep.c
    val yielded: St[Vector[Node]] =
      if isWord(c3, "yield") then
        val y = c3.advance
        St(y.c, genStep.v ++ y.v.map(t => Node.Leaf(t, Some("for.yield"))).toVector)
      else if isWord(c3, "do") then St(c3.bump, genStep.v)
      else St(c3, genStep.v)
    val body = parseForBody(yielded.c)
    St(body.c, Node.Frame("spike.for", None, yielded.v :+ body.v.withRole("for.body")))

  // one generator: `binder <- gen [if guard]`. The binder is a single id, or a tuple `(a, b, …)` whose
  // opening `(` was already consumed by parseFor (the leading `(`); a `,` after the first name marks a
  // tuple, then the closing `)` is skipped — mirroring ssc1-front's parseForFrom.
  private def parseForGen(c0: Cur): St[Node] =
    val first = expect(c0, "spike.id", "gen.binder", "binder") // name0
    val kids0 = first.v.toVector
    val bound: St[Vector[Node]] =
      if first.c.peekKind == "spike.comma" then // ≥2 binders ⇒ a tuple binder (detected by count in the projection)
        def more(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
          if c.peekKind == "spike.comma" then
            val nm = expect(c.bump, "spike.id", "gen.binder", "binder")
            more(nm.c, acc ++ nm.v.toVector)
          else St(c, acc)
        val names = more(first.c, kids0)
        St(if names.c.peekKind == "spike.rparen" then names.c.bump else names.c, names.v) // closing `)` of the tuple binder
      else St(first.c, kids0)
    val c1 = if bound.c.peekKind == "spike.op" && bound.c.peekLexeme == "<-" then bound.c.bump else bound.c
    val gen = parseExpr(c1, 1)
    val kids1 = bound.v ++ gen.v.map(_.withRole("gen.gen")).toVector
    if isKw(gen.c, "if") then
      val guard = parseExpr(gen.c.bump, 1)
      St(guard.c, Node.Frame("spike.forgen", None, kids1 ++ guard.v.map(_.withRole("gen.guard")).toVector))
    else St(gen.c, Node.Frame("spike.forgen", None, kids1))

  // a for-body may be an assignment (imperative `for … do s = …`) or a plain expression.
  private def parseForBody(c0: Cur): St[Node] =
    if (c0.peekKind == "spike.id" && c0.peek2Kind == "spike.eq") || isDottedAssign(c0) then parseAssign(c0)
    // `for k <- 1 to 3 do g += k` — the same compound assignment the statement parser accepts,
    // with the same condition rather than a second spelling of it. The body had only the
    // plain-assignment case, so `g += k` reached parseExpr, which reads `g` and stops at the `+=`.
    // `tests/conformance/js-compound-assign.ssc:53` is this, and it passes on int.
    else if c0.peekKind == "spike.id" && c0.peek2Kind == "spike.op" && isCompoundAssign(c0.peek2Lexeme) then
      parseCompoundAssign(c0)
    else
      val e = parseExpr(c0, 1)
      St(e.c, e.v.getOrElse(Node.Frame("spike.error", None, Vector.empty)))

  /** Scala's rule: ANY identifier immediately abutting a string literal is an
    * interpolator — `html"""…"""`, `sql"…"`, `json"…"`. The set used to be the four the
    * projection knows how to lower (s/f/raw/md), so an unknown one lexed as an id followed by
    * a separate string and the fence content leaked into the ScalaScript grammar: 20
    * diagnostics in examples/std-ui/textarea.ssc from `html"""<small class="…">"""`.
    * Adjacency is the discriminator — `foo "bar"` with a space between is not one — and the
    * projection lowers an unrecognised prefix like `s`, which is a better answer than a
    * cascade. */
  private def isInterpPrefix(w: String): Boolean = w.nonEmpty

  // `s"a $x b ${e}"` → spike.interp holding the prefix + the (decoded) string token. The parts
  // split + concatenation happen in the projection (mirroring ssc1-front interpParts/partsToExpr).
  private def parseInterp(c0: Cur): St[Node] =
    val pfx = c0.advance
    val str = pfx.c.advance
    St(str.c, Node.Frame("spike.interp", None,
      Vector(Node.Leaf(pfx.v.get, Some("interp.prefix")), Node.Leaf(str.v.get, Some("interp.raw")))))

  // `( e )` is grouping (spike.paren); `( a, b, … )` and `()` are tuple literals (spike.tuple).
  // prefix operator `- e` / `! e` / `~ e` → mkPre(op, e). Binds at the atom level.
  private def parsePrefix(c0: Cur): St[Node] =
    val op = c0.advance
    // a prefix `!`/`-`/`~` binds LOOSER than the postfix chain: `!a.b.c(1)` is `!(a.b.c(1))`, not `(!a).b.c(1)`
    // (ssc1-front lowers `!e` → `if e then false else true` over the WHOLE chain). Apply postfix to the atom
    // before wrapping. Binary infix stays looser still (`!a + b` = `(!a) + b`) — handled by the caller.
    val atom = parseAtom(op.c)
    val sub = postfix(atom.c, atom.v.getOrElse(Node.Frame("spike.error", None, Vector.empty)))
    St(sub.c, Node.Frame("spike.pre", None, Vector(Node.Leaf(op.v.get, Some("pre.op")), sub.v.withRole("pre.sub"))))

  private def parseParen(c0: Cur): St[Option[Node]] =
    val open = c0.advance
    val kids0 = open.v.map(t => Node.Leaf(t, Some("group.open"))).toVector
    // A TYPE ASCRIPTION — `(42: Int)`, `(xs: List[Int])`. The type is ERASED, exactly as
    // parameter and val types are: the spike does not model types, and an ascription is a
    // claim about the expression rather than part of it. Without this the `:` ended the
    // group and `println((42: Int))` lost its closing paren and everything after it.
    def elemThenAscription(c: Cur, acc: Vector[Node]): St[(Vector[Node], Boolean)] =
      val e = parseExpr(c, 1)
      val withEl = acc ++ e.v.map(_.withRole("group.elem")).toVector
      St(skipTypeAnnotation(e.c), (withEl, e.v.isDefined))
    def elems(c: Cur, acc: Vector[Node], isTuple: Boolean, elemCount: Int): St[(Vector[Node], Boolean, Int)] =
      if c.peekKind == "spike.comma" then
        val comma = c.advance
        val next = elemThenAscription(comma.c, acc ++ comma.v.map(t => Node.Leaf(t, Some("group.comma"))).toVector)
        elems(next.c, next.v._1, true, elemCount + (if next.v._2 then 1 else 0))
      else St(c, (acc, isTuple, elemCount))
    val body: St[(Vector[Node], Boolean, Int)] =
      if open.c.peekKind != "spike.rparen" then
        val first = elemThenAscription(open.c, kids0)
        elems(first.c, first.v._1, false, if first.v._2 then 1 else 0)
      else St(open.c, (kids0, false, 0))
    val (kids1, isTuple, elemCount) = body.v
    if body.c.peekKind == "spike.rparen" then
      val close = body.c.advance
      St(close.c, Some(Node.Frame(if isTuple || elemCount == 0 then "spike.tuple" else "spike.paren", None,
        kids1 ++ close.v.map(t => Node.Leaf(t, Some("group.close"))).toVector)))
    else
      St(body.c.report("spike.expected", "expected ')'"),
        Some(Node.Frame(if isTuple || elemCount == 0 then "spike.tuple" else "spike.paren", None, kids1)))

  // a branch that starts on a LATER line than its keyword is an indented block (Scala optional-braces),
  // exactly like a def body — else it is a single inline expression.
  private def branchExpr(c0: Cur, kwLine: Int): St[Node] =
    // stopAtParen for the same reason a lambda body needs it: inside `f(x =>` the branch's last
    // line ends with the `)` that closes the CALL, and a column-bounded block would try to start a
    // statement there. `modules.foreach(module =>\n  if n > 0 then\n    println(n))` is the shape,
    // and scripts/smoke-ci.ssc spends four diagnostics on it.
    if !c0.eof && c0.peekLine > kwLine then parseBlock(c0, c0.peekCol, stopAtParen = c0.parenDepth > 0)
    else if (c0.peekKind == "spike.id" && c0.peek2Kind == "spike.eq") || isDottedAssign(c0) then parseAssign(c0) // `then r = n` (Scala 3)
    else
      val parsed = parseExpr(c0, 1)
      val e = parsed.v.getOrElse(Node.Frame("spike.error", None, Vector.empty))
      // `if c then a(i) = v` — an INDEXED assignment as a single-line branch body.
      //
      // The `then r = n` case above is decided by a two-token lookahead, and an indexed target
      // CANNOT be: `a(i)` is only known to be an assignment target once it has been parsed as a
      // call. That is exactly why `parseStmt` asks the same question AFTER parsing rather
      // than before, and the branch position needed the same treatment — not a wider lookahead.
      //
      // It failed silently in the way that costs most: `a(i)` parsed cleanly as the branch body
      // and the `= v` was left to the enclosing block, so the diagnostic landed on whatever came
      // next rather than on the construct that broke.
      e match
        case Node.Frame("spike.call", _, _) if parsed.c.peekKind == "spike.eq" =>
          val rhsStep = parseExpr(parsed.c.bump, 1) // `=`
          val rhs = rhsStep.v.getOrElse(Node.Frame("spike.error", None, Vector.empty))
          St(rhsStep.c, Node.Frame("spike.idxassign", None, Vector(e.withRole("idxassign.lhs"), rhs.withRole("idxassign.rhs"))))
        case _ => St(parsed.c, e)

  private def parseIf(c0: Cur): St[Option[Node]] =
    val kw = c0.advance
    val kids0 = kw.v.map(t => Node.Leaf(t, Some("if.kw"))).toVector
    // C-style `if (cond) body` / `if (cond) { body }`: parse the PARENTHESISED condition ourselves so a
    // trailing same-line `{ body }` does NOT attach as a block-arg to the condition (ssc1-front parseIfExpr
    // resumes at parseInfix after the `)`, ssc1-front.ssc0:1280) — the `{ }` is then the then-branch, and
    // `then` is optional. Otherwise the Scala-3 `if cond then body` form. `(cond)` projects like `cond`.
    val parenCond = kw.c.peekKind == "spike.lparen"
    val cond: St[Vector[Node]] =
      if parenCond then
        val inner = parseExpr(kw.c.bump, 1) // `(`
        val closed = if inner.c.peekKind == "spike.rparen" then inner.c.bump else inner.c
        // ssc1-front "resumes at parseInfix" AFTER the `)` (ssc1-front.ssc0:1280), so the parenthesised group is
        // only the LEFT OPERAND of a condition that may continue: `if (read.value & signBit) != 0 then …` has the
        // WHOLE `(…) != 0` as its condition. Treating the group as the entire condition left the `!= 0` dangling,
        // so `then` was never found and the then-branch projected a HOLE — which poisoned every program importing
        // std/scljet/{bytes,header,wal}.ssc (106 of the 108 multi-file HOLE roots).
        inner.v match
          case Some(e) =>
            val looped = infixLoop(closed, e, 1)
            St(looped.c, kids0 :+ looped.v.withRole("if.cond"))
          case None => St(closed, kids0)
      else
        val e = parseExpr(kw.c, 1)
        St(e.c, kids0 ++ e.v.map(_.withRole("if.cond")).toVector)
    val thenLine = cond.c.peekLine
    val thenStep: St[Vector[Node]] =
      if isKw(cond.c, "then") then
        val t = cond.c.advance
        St(t.c, cond.v ++ t.v.map(tok => Node.Leaf(tok, Some("if.then"))).toVector)
      else if !parenCond then St(cond.c.report("spike.expected", "expected 'then'"), cond.v)
      else St(cond.c, cond.v)
    val thenBranch = branchExpr(thenStep.c, thenLine)
    val kids1 = thenStep.v :+ thenBranch.v.withRole("if.thenE")
    // `else` is OPTIONAL (`if c then e` is a statement whose else defaults to Unit) — see ifExpr projection.
    // elseLine is the line of `else` itself (BEFORE consuming), so an offside else-branch block is detected.
    // AN `else` THAT WOULD CLOSE THE ENCLOSING BLOCK IS NOT THIS `if`'s. Taking it unconditionally
    // was the dangling-else bug:
    //
    //     if x == 1 then
    //       r = 1
    //       if x == 1 then r = 11      <- inner `if`, a statement of the block at column 5
    //     else if x == 2 then          <- `else` at column 3 CLOSES that block
    //
    // The inner `if` swallowed the `else`, so the OUTER `if` was left with no else branch at all
    // and `f(2)` computed 0 where the reference front answers 2. A WRONG ANSWER, not a loss, which
    // is why no diagnostic count saw it: this was every one of the 74 corpus disagreements in
    // `v3/front-diff.sh`, all of them `scljet-*` cases importing one `sql.ssc` that spells
    // `else if` after an indented single-line `if`.
    //
    // The rule is columnar and NOT "an `else` binds to the nearest `if`": in an `else if` chain the
    // inner `if` sits at the same block level, so a following `else` at that column is >= and does
    // bind to it, which is what Scala means. `-1` when no block is open leaves a top-level `if`
    // unconstrained.
    if isKw(thenBranch.c, "else") && thenBranch.c.peekCol >= thenBranch.c.curBlockCol then
      val elseLine = thenBranch.c.peekLine
      val elseKw = thenBranch.c.advance
      val elseBranch = branchExpr(elseKw.c, elseLine)
      St(elseBranch.c, Some(Node.Frame("spike.if", None,
        (kids1 ++ elseKw.v.map(t => Node.Leaf(t, Some("if.else"))).toVector) :+ elseBranch.v.withRole("if.elseE"))))
    else St(thenBranch.c, Some(Node.Frame("spike.if", None, kids1)))

  private def parseIdOrCall(c0: Cur): St[Option[Node]] =
    val id = c0.advance
    // THE SAME-LINE RULE, WHICH THIS SITE DID NOT HAVE. `postfix` guards chained application with
    // `c.peekLine == c.prevEndLine` and says why: ssc1-front's layout inserts `;` at a newline, so a
    // `(` on a LATER line begins a fresh statement. This is the OTHER place an application is built —
    // the first one, for a bare identifier — and it applied whatever `(` came next, however far away:
    //
    //     val value = Nil
    //     (k, value)          // parsed as Nil(k, value); `value` then does not exist
    //
    // It refused `std/mapreduce/shuffle.ssc:438` and it is why `distributed-shuffle` disagreed
    // between the two fronts once v3's own front learned to read the file at all. LITERALS HID IT:
    // `val v = 0` followed by a tuple line is fine because a literal takes no argument list, so only
    // a right-hand side that is a BARE NAME reaches here — which is why this survived so long.
    if id.c.peekKind != "spike.lparen" || id.c.peekLine != id.c.prevEndLine then
      St(id.c, Some(Node.Leaf(id.v.get, Some("var"))))
    else
      val applied = applyArgs(id.c, Node.Leaf(id.v.get, None))
      St(applied.c, Some(applied.v))

  // apply `fn` to the argument list at the cursor's `(` → a spike.call. Shared by `f(a)` and, via
  // postfix, chained/curried application `f(a)(b)` (the fn is itself a call).
  private def roleKind(n: Node): String = n match
    case Node.Frame(k, _, _) => k
    case _                   => ""
  private def roleOf(n: Node): Option[String] = n match
    case Node.Leaf(_, r)  => r
    case Node.Frame(_, r, _) => r

  // `f(a)(using g …)` — rebuild f(a) with the using args appended to its argument list (flatten, don't curry).
  private def mergeUsingArgs(c0: Cur, call: Node): St[Node] =
    val (fnNode, oldArgs) = call match
      case Node.Frame("spike.call", _, ks) =>
        val fnc = ks.find(n => roleOf(n).contains("call.fn")).getOrElse(call)
        (fnc, ks.filter(n => roleOf(n).contains("call.arg")))
      case _ => (call, Vector.empty[Node])
    val kids0 = fnNode.withRole("call.fn") +: oldArgs
    val c1 = c0.bump // `(`
    val c2 = if isWord(c1, "using") then c1.bump else c1
    def args(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
      if c.peekKind != "spike.rparen" && !c.eof then
        val a = parseExpr(c, 1)
        val withArg = acc ++ a.v.map(_.withRole("call.arg")).toVector
        if a.c.peekKind == "spike.comma" then args(a.c.bump, withArg) else args(a.c, withArg)
      else St(c, acc)
    val argStep = args(c2, kids0)
    val closed = if argStep.c.peekKind == "spike.rparen" then argStep.c.bump else argStep.c
    St(closed, Node.Frame("spike.call", None, argStep.v))

  private def applyArgs(c0: Cur, fn: Node): St[Node] =
    val open = c0.advance
    val kids0 = (fn.withRole("call.fn") +: open.v.map(t => Node.Leaf(t, Some("call.open"))).toVector)
    val entered = open.c.enterParen // inside the call's `( … )` group until this list closes (see Cur.parenDepth)
    // args are `,`-separated: after each arg, only a comma continues the list. A NON-comma token ends the args
    // (ssc1-front's moreArgs stops there too) — e.g. `f(html"…")` closes as `f(html)` and leaves `"…"`/`)` as
    // trailing tokens, matching ssc1-front's unrecognised-interpolator recovery rather than reading two args.
    def args(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
      if c.peekKind != "spike.rparen" && !c.eof && !isDefStart(c) then
        // named argument `label = value` (single `=`, not `==` which lexes as spike.op) → spike.narg;
        // ssc1-lower reorders it by declared case-class field order (mkNArg, ssc1-front.ssc0:1357).
        val argStep: St[Vector[Node]] =
          if (c.peekKind == "spike.id" && c.peek2Kind == "spike.eq") || isDottedAssign(c) then
            val nameTok = c.advance // label
            val v = parseExpr(nameTok.c.bump, 1) // `=`
            St(v.c, acc :+ Node.Frame("spike.narg", None,
              Vector(Node.Leaf(nameTok.v.get, Some("narg.name")),
                v.v.getOrElse(Node.Frame("spike.error", None, Vector.empty)).withRole("narg.val"))).withRole("call.arg"))
          // `f(a, { case P => … })` — a PARTIAL FUNCTION inside the argument list. It reached
          // `parseExpr`, which reads `{` as a block, and a block cannot begin with `case`: the whole
          // call failed to parse. The trailing form `xs.map { case … }` has always worked because it
          // goes through `parseBlockArg`; this is the same argument in a different position, and one
          // position having the construct while another does not is a difference nobody can predict.
          // `v3/src/Ir.scala:180` is the shape — `scan(fn.body, { case Instr.Perform(…) => … })`.
          else if c.peekKind == "spike.lbrace" && c.peek2Lexeme == "case" then
            val blk = parseBlockArg(c)
            St(blk.c, acc :+ blk.v.withRole("call.arg"))
          else
            val e = parseExpr(c, 1)
            e.v match
              case Some(a) => St(e.c, acc :+ a.withRole("call.arg"))
              case None =>
                val reported = e.c.report("spike.expected", "expected call argument")
                St(if reported.peekKind != "spike.rparen" && !reported.eof then reported.bump else reported, acc)
        if argStep.c.peekKind == "spike.comma" then
          val comma = argStep.c.advance
          val withComma = argStep.v ++ comma.v.map(t => Node.Leaf(t, Some("call.comma"))).toVector
          // tolerate a trailing comma before `)`
          if comma.c.peekKind != "spike.rparen" && !comma.c.eof && !isDefStart(comma.c) then args(comma.c, withComma)
          else St(comma.c, withComma)
        else St(argStep.c, argStep.v)
      else St(c, acc)
    val argStep = args(entered, kids0)
    val exited = argStep.c.exitParen // leaving the group — whether or not the `)` was actually reached (a broken arg list
                                     // leaves it as a stray token, which is exactly the recovery ssc1-front performs)
    if exited.peekKind == "spike.rparen" then
      val close = exited.advance
      St(close.c, Node.Frame("spike.call", None, argStep.v ++ close.v.map(t => Node.Leaf(t, Some("call.close"))).toVector))
    else
      St(exited.report("spike.expected", "expected ')' to close call"),
        Node.Frame("spike.call", None, argStep.v))

  // postfix layer: an atom followed by chained `.field` selections and/or `match { … }`
  // (mirroring ssc1-front's buildPostfix so precedence vs. infix ops matches exactly).
  private def parsePostfix(c0: Cur): St[Option[Node]] =
    val atom = parseAtom(c0)
    atom.v match
      case Some(a) =>
        val post = postfix(atom.c, a)
        St(post.c, Some(post.v))
      case None => St(atom.c, None)

  private def postfix(c0: Cur, atom: Node): St[Node] =
    if c0.peekKind == "spike.dot" then
      val dot = c0.advance // `.`
      val kids0 = Vector(atom.withRole("sel.obj")) ++ dot.v.map(t => Node.Leaf(t, Some("sel.dot"))).toVector
      val field = dot.c.advance
      field.v match
        case Some(f) => postfix(field.c, Node.Frame("spike.sel", None, kids0 :+ Node.Leaf(f, Some("sel.field"))))
        case None =>
          postfix(field.c.report("spike.expected", "expected field name after '.'"),
            Node.Frame("spike.sel", None, kids0))
    // `f(a)(using g)` merges the explicit using args INTO f(a) (KC8 flattening), not a curried apply.
    else if c0.peekKind == "spike.lparen" && c0.peek2Lexeme == "using" && roleKind(atom) == "spike.call" then
      val merged = mergeUsingArgs(c0, atom)
      postfix(merged.c, merged.v)
    // chained application `f(a)(b)` — ONLY when the `(` is on the same line as the preceding expression;
    // a `(` on a LATER line is a fresh statement (ssc1-front's layout inserts `;` at the newline), so
    // `val cols = line.split(",")\n  ("order", …)` must NOT apply the tuple to `split(",")`.
    else if c0.peekKind == "spike.lparen" && c0.peekLine == c0.prevEndLine then
      val applied = applyArgs(c0, atom)
      postfix(applied.c, applied.v)
    // `e[T]` type application (`Array.empty[Int]`, `x.asInstanceOf[List[Int]]`, `foo[A](x)`) — the type
    // args are erased (ssc1-front buildPostfix readTypeApply); continue the chain with the same `e`. Guarded
    // to the SAME line as `e` so a following-line list-literal statement is not swallowed (newline = trivia).
    else if c0.peekKind == "spike.lbracket" && c0.peekLine == c0.prevEndLine then
      // `Focus[T]` / `Prism[T]` are optics markers (ssc1-front buildPostfix:1379): a following `(_.a.b)` accessor
      // is introspected by the lowerer (resolveFocusArgs, AST-derived) into `optics.focus([OField…])`. Other
      // `e[T]` type applications just erase the type args and continue the chain.
      nodeLexeme(atom) match
        // CAPTURED, not skipped — `Prism` two lines down already does this and `Focus` did not, which
        // is one arm of one match disagreeing with its neighbour. It cost nothing while the marker
        // was refused outright; it costs the type argument the moment a rewrite wants it, and the
        // two fronts then print different trees for the same file (v3's own parser keeps them).
        case "Focus"  =>
          val ta = captureTypeArgTokens(c0)
          postfix(ta.c, Node.Frame("spike.focusmarker", None, atom +: ta.v))
        case "Prism"  =>
          val ta = captureTypeArgTokens(c0)
          postfix(ta.c, Node.Frame("spike.prism", None, atom +: ta.v))
        // `direct.kw`, NOT the `var` role `parseAtom` gave it. `direct` is read as an identifier
        // before postfix recognises the marker, and the role travels: `spike.direct` ends up with a
        // `var`-role child that its projection reads by role and correctly ignores — the comment in
        // SpikeTyped's arm explains why taking it as a type argument was the bug there. But
        // SpikeTypedCoverageSpec counts a content token that is neither modelled nor reported as
        // `Unsupported` as a SILENT DROP, and this one accounted for all 35 of them against a
        // ceiling of 0 (`spike.direct / var -> spike.id`, e.g. examples/direct-syntax-demo.ssc:37).
        // The marker word IS syntax, so a syntax role is the accurate classification and not a
        // suppression: `.kw` is in that spec's SyntaxRoleSuffixes for exactly this class of token.
        // `spike.directmarker`'s own projection takes kids POSITIONALLY (head-as-inner), so the
        // rename cannot reach it.
        case "direct" =>
          val ta = captureTypeArgTokens(c0)
          postfix(ta.c, Node.Frame("spike.directmarker", None, atom.withRole("direct.kw") +: ta.v))
        case _ =>
          postfix(skipTypeParams(c0), atom)
    // `direct[F] { … }` direct-style monadic block → Pair("direct", (typeArgs, block)); ssc1-lower desugars
    // it to a flatMap chain (ssc1-front.ssc0:1394). The `{ … }` is a plain block, not a lambda arg.
    else if c0.peekKind == "spike.lbrace" && roleKind(atom) == "spike.directmarker" then
      val markerKids = atom match { case Node.Frame(_, _, ks) => ks; case _ => Vector.empty[Node] } // direct leaf + ta.tok
      val blk = parseBracedBlock(c0)
      postfix(blk.c, Node.Frame("spike.direct", None, markerKids :+ blk.v.withRole("direct.block")))
    // trailing block argument `e { body }` → e(body) (ssc1-front buildPostfix / parseBlockArg). Only when
    // the `{` is on the SAME line as `e` (else it is a fresh statement, per ssc1-front's newline→`;` layout).
    else if c0.peekKind == "spike.lbrace" && c0.peekLine == c0.prevEndLine then
      val arg = parseBlockArg(c0)
      postfix(arg.c, Node.Frame("spike.blockapp", None, Vector(atom.withRole("blkapp.fn"), arg.v.withRole("blkapp.arg"))))
    // Scala 3 fewer-braces: `e: <arg>` is `e { <arg> }`.
    //
    // The receiver may be ANY expression, including a bare name — `apiClients:` followed by an
    // indented block is what the reference front accepts, and it was refused here until 2026-08-05
    // by a guard requiring a call or a selection. The dialect's job is to match the reference
    // front's PARSE, not to judge meaning: v1 parses that and fails at runtime with "Undefined:
    // apiClients", which is a type error, not a syntax one.
    //
    // Two guards carry it instead, and `SpikeFewerBracesSpec`'s ascription cases are what keep them
    // honest: `colonOpensBlockArg` requires an indented block on the next line or a `=>` before the
    // current line ends — so `val x: Int = 1` and `(x: Int)` are untouched — and the argument
    // backtracks to the colon if it does not parse as one.
    else if c0.peekKind == "spike.colon" && c0.colonOpensBlockArg then
      val arg = parseColonBlockArg(c0.bump) // `:`
      arg.v match
        case Some(a) =>
          postfix(arg.c, Node.Frame("spike.blockapp", None, Vector(atom.withRole("blkapp.fn"), a.withRole("blkapp.arg"))))
        case None => St(arg.c.resetTo(c0), atom)
    // Type ascription in EXPRESSION position — `compute(1): Int`. The reference front accepts it
    // (verified: `val n = compute(1): Int` prints 1 on the interpreter) and this dialect did not,
    // which left the colon for the statement parser and produced "expected statement, found ':'".
    // The type is ERASED, like every other type here.
    //
    // Reached only AFTER the fewer-braces branch above has declined, so the two cannot compete: a
    // colon that opens a block or a lambda is an argument, and anything else that follows an
    // expression is an ascription. `val x: Int` and `case n: Int =>` never arrive — their colons
    // are consumed by the declaration and pattern parsers.
    else if c0.peekKind == "spike.colon" && c0.peekLine == c0.prevEndLine then
      val afterColon = c0.bump // `:`
      val afterType =
        if afterColon.peekKind == "spike.lparen" then skipBalancedParens(afterColon) else skipTypeRef(afterColon)
      val done = skipTypeTail(afterType)
      // "Nothing consumed as a type — leave the colon." The MUTABLE cursor tested this as
      // `c.mark == m + 1`, comparing raw positions AFTER its trivia-skipping peeks had migrated
      // `p` — so whenever WHITESPACE followed the colon the positions differed and it never
      // reset: the colon was silently swallowed and the chain continued. `version: 1.8.0` in a
      // bare `.ssc`'s front matter parses through this branch, and the dialect-arm pin
      // (41 diagnostics / 1791 nodes on std/actors.ssc) counts on it. The immutable cursor's
      // positions do not migrate, so the accident is reproduced deliberately: reset only when
      // nothing was consumed AND the token right after the colon is significant, which is
      // exactly when the old raw positions matched.
      if done.p == afterColon.p && !afterColon.toks.lift(afterColon.p).exists(_.kind == "spike.ws") then
        St(c0, atom)
      else postfix(done, atom)
    else if isKw(c0, "match") then parseMatch(c0, atom)
    else St(c0, atom)

  /** The argument after a fewer-braces `:`. Mirrors [[parseBlockArg]]'s three shapes — `case` arms,
    * a lambda, a plain block — but bounded by INDENTATION instead of a closing brace. */
  private def parseColonBlockArg(c00: Cur): St[Option[Node]] =
    val c0 = c00.skipSemis
    if isKw(c0, "case") then
      val armCol = c0.peekCol
      def arms(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
        if isKw(c, "case") && c.peek2Lexeme != "class" && c.peekCol >= armCol then
          val a = parseArm(c)
          arms(a.c.skipSemis, acc :+ a.v)
        else St(c, acc)
      val armStep = arms(c0, Vector.empty)
      St(armStep.c, Some(Node.Frame("spike.pfblock", None, armStep.v)))
    else
      val params: St[Vector[SourceToken]] =
        if c0.peekKind == "spike.id" && c0.peek2Lexeme == "=>" then
          val nm = c0.advance
          St(nm.c.bump, Vector(nm.v.get))
        else if c0.peekKind == "spike.lparen" then
          tryLambdaParams(c0) match
            case Some(ps) if ps.c.peekLexeme == "=>" => St(ps.c.bump, ps.v)
            case _ => St(c0, Vector.empty)
        else St(c0, Vector.empty)
      val c1 = params.c.skipSemis
      if c1.eof then St(c1, None)
      else
        val body = parseBlock(c1, c1.peekCol, stopAtParen = c1.parenDepth > 0)
        val node =
          if params.v.isEmpty then body.v
          else Node.Frame("spike.lam", None, params.v.map(t => Node.Leaf(t, Some("lam.param"))) :+ body.v.withRole("lam.body"))
        St(body.c, Some(node))

  // `{ … }` as a call argument, wrapped as a lambda — mirrors ssc1-front parseBlockArg (ssc1-front.ssc0:1750):
  //   `{ case P => B; … }`  → __pf => __pf match { … }  (spike.pfblock)
  //   `{ id => body }`      → mkLam([id], block)
  //   `{ (p,…) => body }`   → mkLam([p,…], block)
  //   `{ stmts }`           → mkLam([], block)          (0-arity thunk)
  private def parseBlockArg(c00: Cur): St[Node] =
    val c0 = c00.bump.skipSemis // consume `{`
    if isKw(c0, "case") then
      def arms(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
        if isKw(c, "case") then
          val a = parseArm(c)
          arms(a.c.skipSemis, acc :+ a.v)
        else St(c, acc)
      val armStep = arms(c0, Vector.empty)
      val closed =
        if armStep.c.peekKind == "spike.rbrace" then armStep.c.bump
        else armStep.c.report("spike.expected", "expected '}' to close partial-function block")
      St(closed, Node.Frame("spike.pfblock", None, armStep.v))
    else
      // optional lambda header: `id =>` or `(params) =>` (paren form declines when no `=>` follows)
      val params: St[Vector[SourceToken]] =
        if c0.peekKind == "spike.id" && c0.peek2Lexeme == "=>" then
          val nm = c0.advance
          St(nm.c.bump, Vector(nm.v.get))
        else if c0.peekKind == "spike.lparen" then
          tryLambdaParams(c0) match
            case Some(ps) if ps.c.peekLexeme == "=>" => St(ps.c.bump, ps.v)
            case _ => St(c0, Vector.empty)
        else St(c0, Vector.empty)
      def stmts(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
        if !c.eof && c.peekKind != "spike.rbrace" then
          val st = parseStmt(c)
          stmts(st.c.skipSemis, acc :+ st.v)
        else St(c, acc)
      val body = stmts(params.c.skipSemis, Vector.empty)
      val closed =
        if body.c.peekKind == "spike.rbrace" then body.c.bump
        else body.c.report("spike.expected", "expected '}' to close block argument")
      val block = Node.Frame("spike.block", None, body.v)
      St(closed, Node.Frame("spike.lambda", None, params.v.map(p => Node.Leaf(p, Some("lam.param"))) :+ block.withRole("lam.body")))

  private def parseDef(c0: Cur): St[Node] =
    val kw = c0.advance // `def`
    // The name is WHATEVER token follows `def`, consumed unconditionally — ssc1-front does exactly
    // `let name = tokVal(peek(toks)) in let toks2 = advance(toks)` (ssc1-front.ssc0:1689-1690), with no
    // kind check. That matters for SYMBOLIC operator names: `def <~>(b: Int)` takes the `<~` OP token as the
    // name (the lexer table stops there, leaving `>`), which is how the oracle ends up with a truncated
    // `def <~` and a unit body. Requiring a spike.id left the op token unconsumed and desynced the whole def.
    val name = kw.c.advance
    val kids0 = kw.v.map(t => Node.Leaf(t, Some("def.kw"))).toVector ++ name.v.map(t => Node.Leaf(t, Some("def.name"))).toVector
    // A QUALIFIED name — `def Source.from[A](xs: Iterable[A]): Source[A]` declares a method on a
    // type. The name above consumed `Source` and left `.from`, which desynced the rest of the
    // def. The remaining segments are consumed with their own role so the parse stays in step;
    // the PROJECTED name is still the first segment, which is what defNode already reads, and
    // getting the qualified form into the lowered name is a separate question this does not
    // pretend to answer.
    // The qualifier may carry TYPE PARAMS — `extern def Source[A].distributed(…)`. They sit
    // BETWEEN the segment and the dot, so a plain dot-chain stopped at `[` and left
    // `.distributed` behind, which is what the first version of this loop did.
    //
    // COLLECTED, not skipped, and the reason is that this call sees a PLAIN def's own parameters
    // too: `def display[A](a: A)` has no dot, so `[A]` is consumed right here and the collecting
    // call further down never sees it. That is why `tagless-resolution` reached the arity check on
    // this front with nothing to solve `A` from.
    val tp0 = collectTypeParams(name.c)
    def nameSegs(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
      if c.peekKind == "spike.dot" && (c.peek2Kind == "spike.id" || c.peek2Kind == "spike.uid") then
        val dot = c.advance
        val seg = dot.c.advance
        nameSegs(skipTypeParams(seg.c),
          acc ++ dot.v.map(t => Node.Leaf(t, Some("def.namedot"))).toVector ++ seg.v.map(t => Node.Leaf(t, Some("def.nameseg"))).toVector)
      else St(c, acc)
    val segs = nameSegs(tp0.c, kids0 ++ tp0.v)
    // A DEFINITION'S OWN TYPE PARAMETERS, kept since 2026-08-09 instead of erased.
    //
    // They were dropped here, and that is why the v3 projection had to refuse `using`: telling a
    // type VARIABLE from a type is the whole of instance resolution — `A` in `Show[A]` is solved
    // for, `Int` in `Show[Int]` is matched — and with `[A]` gone there was nothing to tell them
    // apart with. (BUGS.md v3-uniml-def-has-no-type-parameters.)
    //
    // Emitted as ORDERED leaves, `def.tparam` and `def.tbound`, so a context bound stays attached
    // to the name it bounds: `[A: Monoid: Pretty, B]` is tparam A, tbound Monoid, tbound Pretty,
    // tparam B, and the typed layer pairs each bound with the last name it saw. Two flat lists
    // could not express that, and `[A: Monoid, B: Pretty]` is the case that would come out wrong.
    val tp1 = collectTypeParams(segs.c)
    // the `( … )` param clause is OPTIONAL — `def f: T = e` is a parameterless def. MULTIPLE clauses (curried
    // `def f(a)(b)`) are FLATTENED into one param list — ssc1-front appends the 2nd clause's params, so the
    // def lowers to a single `(lam N)` and lowerProg flattens the call by arity (all params share the
    // `def.param` role, so defNode collects them in order across clauses).
    def paramClauses(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
      if c.peekKind != "spike.lparen" then St(c, acc)
      else
        val open = c.advance
        val acc1 = acc ++ open.v.map(t => Node.Leaf(t, Some("def.lparen"))).toVector
        val usingClause = isWord(open.c, "using")
        val cU = if usingClause then open.c.bump else open.c // `(using s: T)` context param — `using` stripped, `s` kept as a param
        def params(c2: Cur, a: Vector[Node]): St[Vector[Node]] =
          val nameP = expect(c2, "spike.id", "def.param", "parameter name")
          nameP.v match
            case None => St(nameP.c, a)
            case Some(pn) =>
              // For a `using` param the TYPE head is what defNode needs for the usingSig
              // (call-site given injection), so it is ROLED as `def.usingtype` — it is not
              // emitted a SECOND time. It used to be added here from `peek` without advancing
              // and then added again by expectType, putting one token in the tree twice: 17
              // leaves over 16 distinct ids for `def f(using ev: Int)(a: Int)`. A token
              // appearing twice makes the tree unable to reconstruct its source (it would
              // print `Int` twice) and defeats any scheme that maps tokens back by id.
              // Nothing reads `def.paramType`; `def.usingtype` is read at usingTypes.
              val colon = expect(nameP.c, "spike.colon", "def.paramColon", "':'")
              val a2 = (a :+ pn) ++ colon.v.toVector
              // A BY-NAME parameter — `(block: => Unit)`. The `=>` comes BEFORE the type, so the
              // type parser met an arrow where it wanted a name. `v1/runtime/std/http.ssc:89`,
              // `extern def httpClient(baseUrl: String)(block: => Unit)`. Erased with the type.
              // KEPT, with a role, since 2026-08-08. It used to be `c.advance()` — dropped on the floor
              // with the comment "Erased with the type" — and that erasure escaped the front: v3's
              // `Param.byName` drives a lowering rewrite, so a by-name argument coming through THIS
              // dialect was evaluated EAGERLY while the same source through v3's own parser was not.
              // `def twice(x: => Int) = x + x` on a counting argument gave 3 there and 2 here: one
              // language, two evaluation orders, chosen by which front the working tree registered.
              // (BUGS.md v3-uniml-front-drops-by-name.)
              //
              // A LEAF WITH A ROLE, not a flag on the type: the token is real source and the tree is the
              // storage, so dropping it also broke reconstruction. Consumers that do not care ignore
              // the role, exactly as they ignore `def.comma`.
              val byname: St[Vector[Node]] =
                if colon.c.peekKind == "spike.op" && colon.c.peekLexeme == "=>" then
                  val bn = colon.c.advance
                  St(bn.c, a2 ++ bn.v.map(t => Node.Leaf(t, Some("def.byname"))).toVector)
                else St(colon.c, a2)
              // A PARENTHESISED parameter type is CAPTURED, not skipped. `def go(t: (Int, String))`
              // used to consume the parens and record nothing, so `Param.tpe` arrived as `None` and the
              // receiver's type was unknown at the call site: `t.bimap(…)` resolved on v3's own front
              // and was refused here with "the type of the receiver is not known" — one language, two
              // answers, decided by whether `v3/.jars/uniml.cp` exists. That is invariant I-3.
              // (v3/BUGS.md `v3-uniml-drops-a-parenthesised-parameter-type`.)
              //
              // `captureType` rather than a new capture loop: it already opens with
              // `if c.peekKind == "spike.lparen" then takeBalanced(…)` under the comment `(A, B) domain`,
              // and returns a `Frame` carrying the role, which is what `SpikeTyped.text` concatenates
              // into a `TypeRef`. It also swallows a trailing `=> C`, which `skipBalancedParens` left
              // for the arrow branch further up — the whole function type rather than half of it.
              val ty: St[Vector[Node]] =
                if byname.c.peekKind == "spike.lparen" then
                  val ct = captureType(byname.c, if usingClause then "def.usingtype" else "def.paramType")
                  St(ct.c, byname.v :+ ct.v)
                else
                  val et = expectType(byname.c, if usingClause then "def.usingtype" else "def.paramType")
                  St(et.c, byname.v ++ et.v.toVector)
              // A TYPE ARGUMENT IS KEPT for a `using` parameter, as ordered `def.usingtypearg` leaves.
              //
              // `skipTypeTail` erases it, which is right for a type nobody reads — and wrong for this
              // one: `Show[A]` and `Show[Int]` differ only there, and instance resolution matches on
              // exactly that. Measured 2026-08-09 with a diagnostic, after guessing twice: the
              // projection was receiving `s:Show(using)` where it needed `Show[A]`, so nothing could
              // ever match an instance declared `Show[Int]`.
              //
              // FOR EVERY PARAMETER, not just `using`. It was limited to `using` on the reasoning that
              // nothing read an ordinary parameter's type — and stage 2b reads it: solving `List[A]`
              // against `List[Int]` is how a call site's type argument is found at all. With the head
              // alone, `xs: List[A]` arrived as `List` and `tagless-context-bounds` could not resolve
              // on this front while it ran on the other.
              val ta: St[Vector[Node]] =
                if ty.c.peekKind == "spike.lbracket" then
                  def taWalk(cc: Cur, d0: Int, aa: Vector[Node]): St[Vector[Node]] =
                    if d0 == 0 || cc.eof then St(cc, aa)
                    else cc.peekKind match
                      case "spike.lbracket" => taWalk(cc.bump, d0 + 1, aa)
                      case "spike.rbracket" => taWalk(cc.bump, d0 - 1, aa)
                      case k if k == "spike.id" || k == "spike.uid" =>
                        val t = cc.advance
                        taWalk(t.c, d0, aa ++ t.v.map(tt => Node.Leaf(tt, Some("def.typearg"))).toVector)
                      case _ => taWalk(cc.bump, d0, aa)
                  taWalk(ty.c.bump, 1, ty.v)
                else ty
              // VARARGS — `def of[T](items: T*)`. Taken HERE rather than left to `skipTypeTail`, which
              // consumes the star and throws it away (its comment says so, and that is right for every
              // OTHER caller: a return type or a pattern has nobody to tell). A parameter does: `T*` is
              // `List[T]` at Tier 0 and the CALL SITE has to collect its tail, so the fact has to reach
              // the projection or the arity check refuses `passes 3, it takes 1`.
              //
              // A LEAF WITH A ROLE, exactly as `def.byname` above and for the same reason — the token
              // is real source and the tree is the storage. `skipTypeTail` still runs below and finds
              // no star, so its own case is left alone and its other callers are untouched.
              //
              // AFTER the type args, never before: `xs: List[Int]*` reaches here with `[Int]` already
              // taken, so the star is what is next in both `T*` and `List[Int]*`.
              val vararg: St[Vector[Node]] =
                if ta.c.peekKind == "spike.op" && ta.c.peekLexeme == "*" then
                  val star = ta.c.advance
                  St(star.c, ta.v ++ star.v.map(t => Node.Leaf(t, Some("def.vararg"))).toVector)
                else ta
              val cT = skipTypeTail(vararg.c) // generic `List[T]` / function `A => B` param types (erased)
              // a default value `param: T = expr` — captured (def.dflt) so defNodes can emit the funcdefaults node
              // for call-site synthesis (`f(a)`→`f(a, dflt…)`); appears right after its param, before the next one.
              val dflt: St[Vector[Node]] =
                if cT.peekKind == "spike.eq" then
                  val e = parseExpr(cT.bump, 1)
                  St(e.c, vararg.v ++ e.v.map(_.withRole("def.dflt")).toVector)
                else St(cT, vararg.v)
              if dflt.c.peekKind == "spike.comma" then
                val comma = dflt.c.advance
                params(comma.c, dflt.v ++ comma.v.map(t => Node.Leaf(t, Some("def.comma"))).toVector)
              else St(dflt.c, dflt.v)
        val inner: St[Vector[Node]] =
          if cU.peekKind != "spike.rparen" && !cU.eof && !isDefStart(cU) then params(cU, acc1)
          else St(cU, acc1)
        val close = expect(inner.c, "spike.rparen", "def.rparen", "')'")
        paramClauses(close.c, inner.v ++ close.v.toVector)
    val clauses = paramClauses(tp1.c, segs.v ++ tp1.v)
    // no `(` → parameterless def; the projection detects it by the absent `def.lparen` child.
    // The result type is OPTIONAL: Scala infers it, and `def f(x: Int) = x + 1` is ordinary code.
    // The dialect used to demand it, and that single omission was 76 of the 172 diagnostics coming
    // from tagged fences — 44% of everything the language column was reporting.
    val ret: St[Vector[Node]] =
      if clauses.c.peekKind == "spike.colon" then
        val colon = expect(clauses.c, "spike.colon", "def.retColon", "':'")
        val afterTy: St[Vector[Node]] =
          if colon.c.peekKind == "spike.lparen" then St(skipBalancedParens(colon.c), clauses.v ++ colon.v.toVector) // `(A, B) => C` domain
          else
            val et = expectType(colon.c, "def.retType")
            St(et.c, clauses.v ++ colon.v.toVector ++ et.v.toVector)
        St(skipTypeTail(afterTy.c), afterTy.v) // function return type `: A => B` (the `=>` is part of the type; `=` ends it)
      else clauses
    // an algebraic-effect row `! L` / `! (L1 & L2)` on the return type (`def f: T ! L = …`) — erased.
    val eff: Cur =
      if ret.c.peekKind == "spike.op" && ret.c.peekLexeme == "!" then
        val c1 = ret.c.bump
        if c1.peekKind == "spike.lparen" then skipBalancedParens(c1) else skipTypeTail(skipTypeRef(c1))
      else ret.c
    if eff.peekKind == "spike.eq" then
      val eqLine = eff.peekLine // line of `=` before consuming
      val eq = eff.advance
      val kids2 = ret.v ++ eq.v.map(t => Node.Leaf(t, Some("def.eq"))).toVector
      // offside: a body starting on a LATER line is an indented block (Scala optional-braces)
      if !eq.c.eof && eq.c.peekLine > eqLine then
        val blk = parseBlock(eq.c, eq.c.peekCol)
        St(blk.c, Node.Frame("spike.def", None, kids2 :+ blk.v.withRole("def.body")))
      // a same-line body that is an assignment `x = e` (e.g. `def save(t) = cell = t`) must lower to a store,
      // not a read — parseExpr stops at the second `=`, so dispatch to parseAssign like branchExpr does.
      else if (eq.c.peekKind == "spike.id" && eq.c.peek2Kind == "spike.eq") || isDottedAssign(eq.c) then
        val asn = parseAssign(eq.c)
        St(asn.c, Node.Frame("spike.def", None, kids2 :+ asn.v.withRole("def.body")))
      else
        val body = parseExpr(eq.c, 1)
        body.v match
          case Some(b) => St(body.c, Node.Frame("spike.def", None, kids2 :+ b.withRole("def.body")))
          case None => St(body.c.report("spike.missing-body", "missing def body expression"), Node.Frame("spike.def", None, kids2))
    // else: abstract def signature (no `=`, no body) — a trait method or effect op. No body is consumed (so the
    // parser can't swallow the next decl); defNode gives it a harmless unit placeholder (the lowering ignores it).
    else St(eff, Node.Frame("spike.def", None, ret.v))

  // `case class Name(f1: T1, f2: T2)` — a top-level declaration. lowerProg does all the work
  // (ctor def + Mirror + `_sel_<field>` accessors + `__regfields__`) from the `casecls` AST node.
  private def parseCaseClass(c0: Cur): St[Node] =
    val caseKw = c0.advance // `case`
    val kids0 = caseKw.v.map(t => Node.Leaf(t, Some("cc.case"))).toVector
    val cls: St[Vector[Node]] =
      if isKw(caseKw.c, "class") then
        val k = caseKw.c.advance
        St(k.c, kids0 ++ k.v.map(t => Node.Leaf(t, Some("cc.class"))).toVector)
      else St(caseKw.c.report("spike.expected", "expected 'class' after 'case'"), kids0)
    val name = expectName(cls.c, "cc.name", "class name")
    val c1 = skipTypeParams(name.c) // `case class Box[A](…)`
    val open = expect(c1, "spike.lparen", "cc.lparen", "'('")
    val kids1 = cls.v ++ name.v.toVector ++ open.v.toVector
    // (recovered, cursor, kids) — the field loop; `recovered` skips the closing-paren expect below
    def fields(c: Cur, acc: Vector[Node]): St[(Vector[Node], Boolean)] =
      if c.peekKind != "spike.id" then
        // ssc1-front parseCaseParam (ssc1-front.ssc0:2160): a NON-id field name (an `@annotation` like
        // `@key id`/`@rdf("schema:name") name`) is NOT consumed and yields a synthetic ("_", "Any") field
        // that ENDS the param list — annotated case-class fields are UNSUPPORTED by the oracle. Mirror it:
        // append the synthetic field (cc.synthfield marker → "_"/"Any" in the projection) and skip the
        // leftover `@ann name: T, …` up to the param-list `)` (depth-aware, past `@rdf("…")` inner parens),
        // so the existing captureDerives (ssc1-front finds them via a forward findCaseDerives scan) still runs.
        val marked = acc ++ c.peek.map(t => Node.Leaf(t, Some("cc.synthfield"))).toVector
        St(skipToParamListEnd(c), (marked, true))
      else
        val fname = c.advance
        val colon = expect(fname.c, "spike.colon", "cc.fieldColon", "':'")
        val ftype = captureFieldType(colon.c) // full type TEXT incl generics (`List[User]`) for the mirror metadata
        val acc1 = acc ++ fname.v.map(t => Node.Leaf(t, Some("cc.field"))).toVector ++ colon.v.toVector :+ ftype.v
        // a field default `= 10` — captured (cc.dflt) so caseClsNodes emits the ctor's funcdefaults entry
        // (`C(a)` with a defaulted trailing field synthesises it); appears after its field, before the next.
        val dflt: St[Vector[Node]] =
          if ftype.c.peekKind == "spike.eq" then
            val e = parseExpr(ftype.c.bump, 1)
            St(e.c, acc1 ++ e.v.map(_.withRole("cc.dflt")).toVector)
          else St(ftype.c, acc1)
        if dflt.c.peekKind == "spike.comma" then
          val comma = dflt.c.advance
          // NO re-check of the entry condition after a comma — the old loop ran the next
          // iteration unconditionally, so a trailing comma falls into the synthfield arm.
          fields(comma.c, dflt.v ++ comma.v.map(t => Node.Leaf(t, Some("cc.comma"))).toVector)
        else St(dflt.c, (dflt.v, false))
    val fieldStep: St[(Vector[Node], Boolean)] =
      if open.c.peekKind != "spike.rparen" && !open.c.eof && !isDefStart(open.c) && !isKw(open.c, "case") then
        fields(open.c, kids1)
      else St(open.c, (kids1, false))
    val (kids2, recovered) = fieldStep.v
    val closed: St[Vector[Node]] =
      if !recovered then
        val close = expect(fieldStep.c, "spike.rparen", "cc.rparen", "')'")
        St(close.c, kids2 ++ close.v.toVector)
      else St(fieldStep.c, kids2)
    // `extends Y with Z` is erased, but a `derives A, B` clause is CAPTURED (cc.derive leaves) — the
    // lowerer generates the derived typeclass/codec instances from it (ssc1-front mkCaseCls's 4th field).
    val extended: St[Vector[Node]] =
      if isWord(closed.c, "extends") then
        val cE = closed.c.bump
        // capture the FIRST nominal (uppercase) parent for the subtype registry: `case class Circle(…) extends
        // Shape` lets `case _: Shape` expand to its child tags. ssc1-front registers only a `uid` parent
        // (subtypeRegCell); caseClsNodes emits a companion ("subtype", (parent, child)) node (variant-A).
        val withParent =
          if isNameKind(cE.peekKind) then closed.v ++ cE.peek.map(t => Node.Leaf(t, Some("cc.parent"))).toVector
          else closed.v
        val cT0 = skipTypeRef(cE)
        val cT1 = if cT0.peekKind == "spike.lparen" then skipBalancedParens(cT0) else cT0
        // Scala 3 separates further parents with COMMAS — `extends Functor[T], Foldable[T]` — where
        // Scala 2 wrote `with`. Only the first nominal parent is registered (the subtype registry
        // models one), the rest are erased like every other type here; without this the comma ended
        // the declaration and `v1/runtime/std/foldable-traversable.ssc` lost its trait.
        def commas(c: Cur): Cur =
          if c.peekKind == "spike.comma" && isNameKind(c.peek2Kind) then commas(skipTypeRef(c.bump)) else c
        def withs(c: Cur): Cur =
          if isWord(c, "with") then withs(commas(skipTypeRef(c.bump))) else c
        St(withs(commas(cT1)), withParent)
      else closed
    val derived = captureDerives(extended.c)
    val kids3 = extended.v ++ derived.v
    // an EXPLICIT body `{ def m … }` / `: def m …` carries BODY METHODS. ssc1-front registers them in a
    // parser cell the spike bypasses; instead we capture them (cc.method) and project a companion
    // `("casemethods", (name, (fields, defs)))` node that lowerProg's collectCaseMethodsNodes unions in.
    // Only a `{`/`:` opener starts a body — a bodyless case class must NOT swallow a following top-level decl.
    val braced = derived.c.peekKind == "spike.lbrace"
    if braced || derived.c.peekKind == "spike.colon" then
      val cB = derived.c.bump.skipSemis
      val bodyCol = cB.peekCol
      def members(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
        if !c.eof && c.peekKind != "spike.rbrace" && (braced || c.peekCol >= bodyCol) && isMemberStart(c) then
          val cM = skipDeclModifiers(c)
          val m = parseMember(cM)
          val progressed = if m.c.p == cM.p then m.c.bump else m.c
          members(progressed.skipSemis, acc :+ m.v.withRole("cc.method"))
        else St(c, acc)
      val memberStep = members(cB, kids3)
      val closedB = if braced && memberStep.c.peekKind == "spike.rbrace" then memberStep.c.bump else memberStep.c
      St(closedB, Node.Frame("spike.casecls", None, memberStep.v))
    else St(derived.c, Node.Frame("spike.casecls", None, kids3))

  // `enum E: case A; case B(x: Int); case Red, Green` (offside or `{ … }`). Emits
  // ("enum", (name, [(caseName, [fieldNames])…])); lowerProg reuses the case-class ctor path.
  private def parseEnum(c0: Cur): St[Node] =
    val kw = c0.advance // `enum`
    val name = expectName(kw.c, "enum.name", "enum name")
    val c1 = skipTypeParams(name.c) // `enum Opt[A]: …`
    val braced = c1.peekKind == "spike.lbrace"
    val opener: St[Vector[Node]] =
      if c1.peekKind == "spike.colon" then
        val t = c1.advance
        St(t.c, t.v.map(tok => Node.Leaf(tok, Some("enum.colon"))).toVector)
      else if braced then
        val t = c1.advance
        St(t.c, t.v.map(tok => Node.Leaf(tok, Some("enum.lbrace"))).toVector)
      else St(c1, Vector.empty)
    val kids0 = kw.v.map(t => Node.Leaf(t, Some("enum.kw"))).toVector ++ name.v.toVector ++ opener.v
    def cases(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
      // a following top-level `case class` (peek2 == "class") is NOT an enum case
      if isKw(c, "case") && c.peek2Lexeme != "class" then
        val caseKw = c.advance
        val first = parseEnumCase(caseKw.c, allowParams = true)
        def commaTail(cc: Cur, a: Vector[Node]): St[Vector[Node]] =
          if cc.peekKind == "spike.comma" then
            val comma = cc.advance
            val nxt = parseEnumCase(comma.c, allowParams = false) // comma tail = nullary cases
            commaTail(nxt.c, a ++ comma.v.map(t => Node.Leaf(t, Some("enum.comma"))).toVector :+ nxt.v)
          else St(cc, a)
        val tail = commaTail(first.c,
          (acc ++ caseKw.v.map(t => Node.Leaf(t, Some("enum.casekw"))).toVector) :+ first.v)
        cases(tail.c.skipSemis, tail.v)
      else St(c, acc)
    val caseStep = cases(opener.c.skipSemis, kids0)
    if braced && caseStep.c.peekKind == "spike.rbrace" then
      val close = caseStep.c.advance
      St(close.c, Node.Frame("spike.enum", None, caseStep.v ++ close.v.map(t => Node.Leaf(t, Some("enum.rbrace"))).toVector))
    else St(caseStep.c, Node.Frame("spike.enum", None, caseStep.v))

  private def parseEnumCase(c0: Cur, allowParams: Boolean): St[Node] =
    val name = expectName(c0, "ec.name", "case name")
    if allowParams && name.c.peekKind == "spike.lparen" then
      val open = name.c.advance
      val kids0 = name.v.toVector ++ open.v.map(t => Node.Leaf(t, Some("ec.lparen"))).toVector
      def fields(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
        val fn = expect(c, "spike.id", "ec.field", "field name")
        fn.v match
          case None => St(fn.c, acc)
          case Some(f) =>
            val colon = expect(fn.c, "spike.colon", "ec.fieldColon", "':'")
            // THE SAME TYPE READER A `case class` USES. This expected an IDENTIFIER and then skipped a
            // generic tail, so an enum case field whose type opens with a paren — a FUNCTION type,
            // `case L(step: () => Option[(V, V)])` — stopped at the `(` with `expected type, found
            // '('`. The `case class` twin has read the full type text all along, which is why the
            // same field parsed in one declaration form and not the other.
            val ftype = captureType(colon.c, "ec.fieldType")
            val acc1 = (acc :+ f) ++ colon.v.toVector :+ ftype.v
            // a field default `case Square(side: Int = 2)` — captured (ec.dflt) so the case's ctor can
            // synthesise `Square()` → `Square(2)`; without consuming it the `= 2` leaked, ending the enum
            // early and spuriously lowering the last case as a stray case class (a phantom `__mirror_*`).
            val dflt: St[Vector[Node]] =
              if ftype.c.peekKind == "spike.eq" then
                val e = parseExpr(ftype.c.bump, 1)
                St(e.c, acc1 ++ e.v.map(_.withRole("ec.dflt")).toVector)
              else St(ftype.c, acc1)
            if dflt.c.peekKind == "spike.comma" then
              val comma = dflt.c.advance
              fields(comma.c, dflt.v ++ comma.v.map(t => Node.Leaf(t, Some("ec.comma"))).toVector)
            else St(dflt.c, dflt.v)
      val fieldStep: St[Vector[Node]] =
        if open.c.peekKind != "spike.rparen" && !open.c.eof then fields(open.c, kids0)
        else St(open.c, kids0)
      val close = expect(fieldStep.c, "spike.rparen", "ec.rparen", "')'")
      St(close.c, Node.Frame("spike.enumcase", None, fieldStep.v ++ close.v.toVector))
    else St(name.c, Node.Frame("spike.enumcase", None, name.v.toVector))

  // `extension (recv: T) def m: R = body` — the receiver is prepended to the method's params
  // (projected) and the group is bracketed by `extension_start`/`extension_end` markers, so
  // lowerProg's collectExtensionMethods registers `m` for `.m` dispatch.
  private def parseExtension(c0: Cur): St[Node] =
    val kw = c0.advance // `extension`
    val c1 = skipTypeParams(kw.c) // `extension [A](fa: F[A])` — the group's own type params are erased
    val open = expect(c1, "spike.lparen", "ext.open", "'('")
    val recv = expect(open.c, "spike.id", "ext.recv", "receiver name")
    val colon = expect(recv.c, "spike.colon", "ext.colon", "':'")
    val rtype = expectType(colon.c, "ext.recvType")
    val c2 = skipTypeTail(rtype.c) // a generic receiver `(xs: List[Int])` — the `[Int]` tail must be consumed before `)`
    val close = expect(c2, "spike.rparen", "ext.close", "')'")
    val kids0 = kw.v.map(t => Node.Leaf(t, Some("ext.kw"))).toVector ++
      open.v.toVector ++ recv.v.toVector ++ colon.v.toVector ++ rtype.v.toVector ++ close.v.toVector
    // the method group: `{ def m …; def n … }`, a single inline `def m …`, or an offside block of
    // defs indented under the header. extensionNodes projects EVERY def child (receiver prepended),
    // so consume them all — a group closes at a dedent (col < the first method's) or a non-def.
    if close.c.peekKind == "spike.lbrace" then
      def defs(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
        if isDefStart(c) && !c.eof then
          val d = parseDef(c)
          defs(d.c.skipSemis, acc :+ d.v)
        else St(c, acc)
      val defStep = defs(close.c.bump.skipSemis, kids0) // `{`
      val closedB =
        if defStep.c.peekKind == "spike.rbrace" then defStep.c.bump
        else defStep.c.report("spike.expected", "expected '}' to close extension")
      St(closedB, Node.Frame("spike.extension", None, defStep.v))
    else
      def defs(c: Cur, groupCol: Int, acc: Vector[Node]): St[(Vector[Node], Int)] =
        if isDefStart(c) && !c.eof && (groupCol < 0 || c.peekCol >= groupCol) then
          val col = if groupCol < 0 then c.peekCol else groupCol
          val d = parseDef(c)
          defs(d.c, col, acc :+ d.v)
        else St(c, (acc, groupCol))
      val defStep = defs(close.c, -1, kids0)
      val reported =
        if defStep.v._2 < 0 then defStep.c.report("spike.expected", "expected a method def in extension")
        else defStep.c
      St(reported, Node.Frame("spike.extension", None, defStep.v._1))

  /** the shared member loop: one member per round, progress guaranteed. `eraseModifiers`
    * is true for object/trait/case-class/given bodies (their old loops called
    * skipDeclModifiers) and FALSE for an effect declaration's ops, whose old loop did not. */
  private def memberLoop(c0: Cur, role: String, braced: Boolean, bodyCol: => Int,
                         hasBody: Boolean, eraseModifiers: Boolean, acc0: Vector[Node]): St[Vector[Node]] =
    def loop(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
      if !c.eof && hasBody && c.peekKind != "spike.rbrace" && (braced || c.peekCol >= bodyCol) && isMemberStart(c) then
        val cM = if eraseModifiers then skipDeclModifiers(c) else c
        val m = parseMember(cM)
        val progressed = if m.c.p == cM.p then m.c.bump else m.c // guarantee progress
        loop(progressed.skipSemis, acc :+ m.v.withRole(role))
      else St(c, acc)
    loop(c0, acc0)

  // `given name: T = expr` — a named typeclass instance (dictionary). lowerProg's resolve pass
  // does the dict-passing; the projection only emits the `("given", (name, typeStr, body))` node.
  // `given name: T = body` → ("given", …) for KC5 injection; `given name: T with { defs }` → ("given_obj", …)
  // a typeclass instance whose body methods lower to `name_method` (ssc1-front.ssc0:2603). `given name = body`
  // (no type) is a plain val; an anonymous given is a no-op. buildGivenTable/collectObjects are AST-derived.
  private def parseGiven(c0: Cur): St[Node] =
    val t0 = c0.peek // carrier for a no-op frame (see sealedNoop)
    val kw = c0.advance // `given`
    val kids0 = kw.v.map(t => Node.Leaf(t, Some("given.kw"))).toVector
    if kw.c.peekKind == "spike.id" && kw.c.peek2Kind == "spike.colon" then
      val name = kw.c.advance
      val ty = captureType(name.c.bump, "given.type") // `:`
      val kids1 = kids0 ++ name.v.map(t => Node.Leaf(t, Some("given.name"))).toVector :+ ty.v
      if ty.c.peekKind == "spike.eq" then
        val body = parseExpr(ty.c.bump, 1)
        St(body.c, Node.Frame("spike.given", None, kids1 ++ body.v.map(_.withRole("given.body")).toVector))
      else if isWord(ty.c, "with") then
        val c1 = ty.c.bump // `with` — followed by a braced `{ … }` or an offside indented body
        val braced = c1.peekKind == "spike.lbrace"
        val c2 = (if braced then c1.bump else c1).skipSemis
        val bodyCol = c2.peekCol
        val members = memberLoop(c2, "obj.member", braced, bodyCol, hasBody = true, eraseModifiers = true, kids1)
        val closed = if braced && members.c.peekKind == "spike.rbrace" then members.c.bump else members.c
        St(closed, Node.Frame("spike.givenobj", None, members.v))
      else St(ty.c, sealedNoop(t0))
    else if (kw.c.peekKind == "spike.id" && kw.c.peek2Kind == "spike.eq") || isDottedAssign(kw.c) then
      val name = kw.c.advance // `given name = body` → a plain val
      val body = parseExpr(name.c.bump, 1) // `=`
      St(body.c, Node.Frame("spike.givenval", None,
        (kids0 ++ name.v.map(t => Node.Leaf(t, Some("given.name"))).toVector) ++ body.v.map(_.withRole("given.body")).toVector))
    else
      // AN ANONYMOUS GIVEN — `given T = body`, `given T with { … }` — is a no-op to this dialect, and
      // it used to be a no-op that CONSUMED NOTHING. So `given a.b.C[Book] = a.b.C.derived` left the
      // cursor on `a`, the parser resumed there as if it were a statement, and reported
      // "expected statement, found '='" — the last diagnostic in the whole tagged corpus
      // (examples/graph-rdf4j-http-storage.ssc:21). The reference front parses and runs the same
      // file, so it was a gap here.
      //
      // It stays SEMANTICALLY a no-op: the projection documents `spike.sealed` as the node an
      // anonymous given produces because it "genuinely carries nothing", and giving it a name-less
      // `spike.given` would hand the typed AST a node whose `given.name` is absent. What changes is
      // only that the construct is now EATEN, which is what a lossless parser owes a form it does
      // not model.
      val ty = captureType(kw.c, "given.type")
      if ty.c.peekKind == "spike.eq" then
        val body = parseExpr(ty.c.bump, 1)
        St(body.c, sealedNoop(t0))
      else
        // ONLY THE `= body` FORM IS CONSUMED, and an anonymous `given … with` deliberately is NOT.
        //
        // I wrote the `with` arm first and then removed it. Consuming a `with` body here would parse
        // its members and DISCARD them — `sealedNoop` carries nothing — which is the exact shape
        // `40-front-on-uniml.md` §5b item 3 is about: a construct swallowed into a contentless node
        // is invisible to the diagnostic count, to the drop census and to coverage, all at once.
        // Trading a loud diagnostic for a silent drop is the wrong direction, and the corpus does
        // not ask for it: ZERO files write an anonymous `given … with`.
        //
        // So the cursor goes back and the form keeps its diagnostic. Half-consuming would be worse
        // than either — it would move the complaint onto whatever followed.
        St(ty.c.resetTo(kw.c), sealedNoop(t0))

  // `extern def f(…): T` / `extern class C { … }` — external signatures, erased to a no-op (ssc1-front:2738).
  private def parseExtern(c0: Cur): St[Node] =
    val t0 = c0.peek // carrier for the no-op frame (see sealedNoop)
    val c1 = c0.bump // `extern`
    if isKw(c1, "class") then
      val c2 = c1.bump
      val c3 = if c2.peekKind == "spike.uid" then c2.bump else c2
      val c4 = skipTypeParams(c3)
      val c5 = if c4.peekKind == "spike.lparen" then skipBalancedParens(c4) else c4
      val c6 = skipExtendsClause(c5)
      if c6.peekKind == "spike.lbrace" then St(skipBalancedBraces(c6), sealedNoop(t0))
      else if c6.peekKind == "spike.colon" then
        val c7 = c6.bump.skipSemis
        val bodyCol = c7.peekCol
        def loop(c: Cur): Cur =
          if !c.eof && c.peekCol >= bodyCol && isMemberStart(c) then
            val m = parseMember(c)
            val progressed = if m.c.p == c.p then m.c.bump else m.c
            loop(progressed.skipSemis)
          else c
        St(loop(c7), sealedNoop(t0))
      else St(c6, sealedNoop(t0))
    else // `extern def NAME(params): RetType` — a bodyless signature: consume def/name/params/return type
      val c2 = if isDefStart(c1) then c1.bump else c1
      val c3 = if c2.peekKind == "spike.id" then c2.bump else c2
      val c4 = skipTypeParams(c3)
      def clauses(c: Cur): Cur = if c.peekKind == "spike.lparen" then clauses(skipBalancedParens(c)) else c
      val c5 = clauses(c4)
      val c6 = if c5.peekKind == "spike.colon" then skipTypeRef(c5.bump) else c5
      St(c6, sealedNoop(t0))

  // `effect L:` / `effect L { ops }` → Pair("effect_decl", Pair(name, Pair(false, [op-defs]))); the lowerer
  // materializes E_op closures from the op SIGNATURES (ssc1-front.ssc0:2657, AST-derived — bodies ignored).
  // `effect { … }` (a brace right after `effect`, no name) is NOT a decl but the reactive call — left to exprs.
  private def parseEffectDecl(c0: Cur, multi: Boolean): St[Node] =
    val multiStep: St[Vector[Node]] =
      if multi then
        val m = c0.advance // `multi` kept as a leaf so the flag survives the emit
        St(m.c, m.v.map(t => Node.Leaf(t, Some("eff.multi"))).toVector)
      else St(c0, Vector.empty)
    val c1 = multiStep.c.bump // `effect`
    val name: St[Vector[Node]] =
      if c1.peekKind == "spike.uid" || c1.peekKind == "spike.id" then
        val n = c1.advance
        St(n.c, multiStep.v ++ n.v.map(t => Node.Leaf(t, Some("eff.name"))).toVector)
      else St(c1, multiStep.v)
    val c2 = skipTypeParams(name.c) // `effect State[S]`
    val braced = c2.peekKind == "spike.lbrace"
    val c3 =
      if braced then c2.bump
      else if c2.peekKind == "spike.colon" then c2.bump
      else c2
    val c4 = c3.skipSemis
    val bodyCol = c4.peekCol
    val members = memberLoop(c4, "eff.op", braced, bodyCol, hasBody = true, eraseModifiers = false, name.v)
    val closed = if braced && members.c.peekKind == "spike.rbrace" then members.c.bump else members.c
    St(closed, Node.Frame("spike.effectdecl", None, members.v))

  // `object X [extends …]: members` / `object X { members }` → Pair("object", Pair(name, [member-stmts]))
  // (ssc1-front.ssc0:2687). The lowerer emits `X_member` globals from the body; `X.member` resolves to them.
  private def parseObject(c0: Cur, caseTok: Option[SourceToken] = None): St[Node] =
    // The DECLARATION's own column, not the `object` keyword's — for `case object` the head token is
    // `case`, already consumed by the caller. A member must be indented past THIS. See `hasBody`.
    val declCol = caseTok.map(_.span.start.column).getOrElse(c0.peekCol)
    val kids0 = caseTok.map(t => Node.Leaf(t, Some("obj.case"))).toVector // `case object` — the marker, kept
    val name = expectName(c0.bump, "obj.name", "object name") // `object`
    // The parents are KEPT now, under the same `td.parent` role the trait uses. They used to be
    // erased by `skipExtendsClause`, which is right for the v2 lane — it resolves inheritance
    // elsewhere — and wrong for a front built on this tree: `case object SqlNull extends
    // SqliteValue` is a CONSTRUCTOR of `SqliteValue`, and without the parent it is a class that
    // belongs to no hierarchy, so no `match` on the trait can dispatch to it. Additive: consumers
    // read by role, so the v2 lane sees the same object it saw before.
    val parents = captureExtendsClause(name.c)
    val braced = parents.c.peekKind == "spike.lbrace"
    val c1 =
      if braced then parents.c.bump
      else if parents.c.peekKind == "spike.colon" then parents.c.bump
      else parents.c
    val c2 = c1.skipSemis
    val bodyCol = c2.peekCol
    // A BODY EXISTS only if there were braces, or the next token is indented PAST the declaration.
    // Without this the member loop ran for a body-less `object X extends Y`, `bodyCol` became the
    // column of the NEXT TOP-LEVEL DECLARATION, `peekCol >= bodyCol` held trivially, and the
    // sibling was swallowed as a member — cascading, so `trait K` + `case object A` + `case class B`
    // + `def f` collapsed into ONE nested declaration and three of them vanished from the program.
    // Silent: the tree was well-formed, just smaller. Found by v3's front differential, where the
    // two fronts printed different programs for `v3/tests/front/case-object.ssc`.
    val hasBody = braced || bodyCol > declCol
    val members = memberLoop(c2, "obj.member", braced, bodyCol, hasBody, eraseModifiers = true, kids0 ++ name.v.toVector ++ parents.v)
    val closed = if braced && members.c.peekKind == "spike.rbrace" then members.c.bump else members.c
    St(closed, Node.Frame("spike.object", None, members.v))

  // one object/enum body member: def / val / var / case class (reuses the top-level declaration parsers).
  private def isMemberStart(c: Cur): Boolean =
    isDefStart(c) || isKw(c, "case") || isKw(c, "val") || isWord(c, "var") || isKw(c, "extension") ||
    (c.peekKind == "spike.id" && declModifiers(c.peekLexeme))
  private def parseMember(c: Cur): St[Node] =
    if isDefStart(c) then parseDef(c)
    // `case object X extends Y` — the TWIN of this dispatch (parseProgram, the top-level
    // one) has always handled it and this one did not, so a `case object` anywhere other
    // than the top level reported "expected class name, found 'object'". 94 diagnostics
    // across the corpus came from that one missing branch.
    else if isKw(c, "case") && c.peek2Lexeme == "object" then
      val caseKw = c.advance
      parseObject(caseKw.c, caseKw.v)
    else if isKw(c, "case") then parseCaseClass(c)
    else if isKw(c, "val") then parseVal(c)
    else if isWord(c, "var") then parseVarStmt(c)
    // `extension [A](fa: F[A]) def m … = …` inside a `given … with` body — a typeclass instance whose ops
    // are extension methods. Each method becomes a MEMBER def with the receiver prepended (see memberNodes).
    else if isKw(c, "extension") then parseExtension(c)
    else parseStmt(c)

  // A `trait` (and a plain `class`) KEEPS ITS NAME, PARENTS AND MEMBERS. It used to be consumed
  // whole into `sealedNoop` — parsed, then thrown away — and that made it invisible to every
  // measurement UniML has at once, which is why it survived a sprint spent counting things:
  //
  //   - the `spike.error` count could not see it: nothing failed to parse;
  //   - the silent-drop census could not see it: the frame is a MODELLED node with no subtree
  //     under it to drop;
  //   - the coverage figure could not see it: it counted as `typed`, not as a gap.
  //
  // A construct consumed into a contentless node is invisible to all three, and no amount of
  // counting finds it — the same shape as a coverage metric that rewarded dropping. It took v3's
  // FRONT DIFFERENTIAL, which compares against another implementation rather than against itself.
  // `trait` gates 137 corpus cases for v3.
  //
  // The frame KIND stays `spike.sealed` deliberately: `SpikeProject` matches on it and returns a
  // constant, so the v2 lane sees no change and no kind census moves. Consumers that want the
  // trait read the roles. Same trade as the import path, for the same reason.
  // The CONSTRUCTOR CLAUSE of a plain `class`, captured instead of skipped. `class Box(n: Int)`
  // went through `skipBalancedParens` — consumed, recorded nowhere — so `SpikeAst.TraitDecl` had no
  // parameters to carry and the projection printed `(fields)` where v3's own front printed
  // `(fields (p "n"))` (v3/BUGS.md uniml-traitdecl-drops-class-parameters; three declared
  // front-diff rows diverge by exactly this). Roles follow the `slots` convention `cc.field` /
  // `def.param` use: `td.param` the name, `td.paramType` the type HEAD (generics erased by
  // `skipTypeTail`, as def params were before stage 2b needed theirs — nothing reads a class
  // field's type arguments yet), `td.dflt` a default. A `val`/`var`/`private`/`protected` modifier
  // is consumed role-less, exactly what the old skip did to every token here.
  //
  // On ANY unrecognised shape the remainder is skipped balanced WITHOUT `report`: a clause this
  // capture does not model parsed SILENTLY before, and the error census must not move because the
  // tokens are now looked at. Either exit consumes through the closing `)` — the consumed token
  // set is `skipBalancedParens`'s own.
  private def captureCtorParams(c0: Cur): St[Vector[Node]] =
    val open = c0.advance // `(`
    val kids0 = open.v.map(t => Node.Leaf(t, Some("td.lparen"))).toVector
    def bail(c: Cur, acc: Vector[Node]): St[Vector[Node]] = St(skipToParamListEnd(c), acc)
    def params(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
      def mods(cm: Cur): Cur =
        if isKw(cm, "val") || isKw(cm, "var") || isKw(cm, "private") || isKw(cm, "protected")
        then mods(cm.bump)
        else cm
      val cM = mods(c)
      if !isNameKind(cM.peekKind) then bail(cM, acc)
      else
        val nameP = cM.advance
        val acc1 = acc ++ nameP.v.map(t => Node.Leaf(t, Some("td.param"))).toVector
        if nameP.c.peekKind != "spike.colon" then bail(nameP.c, acc1)
        else
          val colon = nameP.c.advance
          val acc2 = acc1 ++ colon.v.map(t => Node.Leaf(t, Some("td.colon"))).toVector
          val ty: St[Vector[Node]] =
            if colon.c.peekKind == "spike.lparen" then
              // a parenthesised (tuple/function) type — `captureType` opens on exactly this
              val ct = captureType(colon.c, "td.paramType")
              St(ct.c, acc2 :+ ct.v)
            else if isNameKind(colon.c.peekKind) then
              val t = colon.c.advance
              St(skipTypeTail(t.c), acc2 ++ t.v.map(tt => Node.Leaf(tt, Some("td.paramType"))).toVector)
            else St(colon.c, acc2)
          val dflt: St[Vector[Node]] =
            if ty.c.peekKind == "spike.eq" then
              val e = parseExpr(ty.c.bump, 1)
              St(e.c, ty.v ++ e.v.map(_.withRole("td.dflt")).toVector)
            else ty
          if dflt.c.peekKind == "spike.comma" then
            val comma = dflt.c.advance
            params(comma.c, dflt.v ++ comma.v.map(t => Node.Leaf(t, Some("td.comma"))).toVector)
          else if dflt.c.peekKind == "spike.rparen" then St(dflt.c, dflt.v)
          else bail(dflt.c, dflt.v)
    val inner: St[Vector[Node]] =
      if open.c.peekKind == "spike.rparen" then St(open.c, kids0)
      else params(open.c, kids0)
    if inner.c.peekKind == "spike.rparen" then
      val close = inner.c.advance
      St(close.c, inner.v ++ close.v.map(t => Node.Leaf(t, Some("td.rparen"))).toVector)
    else St(inner.c, inner.v) // bail already consumed through `)`

  private def parseTraitOrClassNoop(c0: Cur): St[Node] =
    val declCol = c0.peekCol // before the keyword is consumed — the offside line for members
    val kw = c0.advance // `trait` / `class`
    val kids0 = kw.v.map(t => Node.Leaf(t, Some("td.kw"))).toVector
    val name: St[Vector[Node]] =
      if isNameKind(kw.c.peekKind) then
        val n = kw.c.advance
        St(n.c, kids0 ++ n.v.map(t => Node.Leaf(t, Some("td.name"))).toVector)
      else St(kw.c, kids0)
    val c1 = skipTypeParams(name.c)
    // the class constructor clause, captured — see captureCtorParams just above
    val ctor: St[Vector[Node]] =
      if c1.peekKind == "spike.lparen" then captureCtorParams(c1) else St(c1, Vector.empty)
    val parents = captureExtendsClause(ctor.c)
    val braced = parents.c.peekKind == "spike.lbrace"
    val c3 =
      if braced then parents.c.bump
      else if parents.c.peekKind == "spike.colon" then parents.c.bump
      else parents.c
    if braced || c3.peekKind != "spike.lbrace" then
      val c4 = c3.skipSemis
      val bodyCol = c4.peekCol
      // Same offside rule as `parseObject`, and the same bug: a marker `trait SqliteValue` with no
      // body swallowed every declaration that followed it at column 1.
      val hasBody = braced || bodyCol > declCol
      val members = memberLoop(c4, "obj.member", braced, bodyCol, hasBody, eraseModifiers = true, name.v ++ ctor.v ++ parents.v)
      val closed = if braced && members.c.peekKind == "spike.rbrace" then members.c.bump else members.c
      St(closed, Node.Frame("spike.sealed", None, members.v))
    else St(c3, Node.Frame("spike.sealed", None, name.v ++ ctor.v ++ parents.v))

  // `[a, b, c](path.ssc)` markdown-link import — a parse-only no-op (ssc1-front.ssc0:2474 → Pair("sealed","")).
  // Consume `[ … ]` then the optional `( … )`, matching ssc1-front's non-nested skipTo.
  // KEEP the consumed tokens as leaves: an EMPTY Frame does not survive the Node→UniNode emit (same reason
  // parseListLiteral keeps its `[`/`]`). At top level a vanished no-op import was harmless — it projects to
  // `("sealed", "")`, which the lowerer drops anyway — but inside a STATEMENT LIST it is not: an arm body
  // `case _ => []` is a link-import for ssc1-front's parseOneStmt too (ssc1-front.ssc0:2515), giving
  // `("block", [("sealed","")])` → `(lit unit)`; with the frame gone the block is empty and projects a HOLE.
  // The projection ignores these children (`stmt()` maps any spike.sealed to `Pair("sealed", "")`).
  private def parseLinkImport(c0: Cur): St[Node] =
    def keep(step: St[Option[SourceToken]], acc: Vector[Node]): (Cur, Vector[Node]) =
      (step.c, acc ++ step.v.map(t => Node.Leaf(t, Some("imp.tok"))).toVector)
    def upTo(c: Cur, stop: String, acc: Vector[Node]): (Cur, Vector[Node]) =
      if c.peekKind != stop && !c.eof then
        val (c1, a1) = keep(c.advance, acc)
        upTo(c1, stop, a1)
      else (c, acc)
    val (c1, a1) = keep(c0.advance, Vector.empty) // `[`
    val (c2, a2) = upTo(c1, "spike.rbracket", a1)
    val (c3, a3) = if c2.peekKind == "spike.rbracket" then keep(c2.advance, a2) else (c2, a2)
    if c3.peekKind == "spike.lparen" then
      val (c4, a4) = keep(c3.advance, a3)
      val (c5, a5) = upTo(c4, "spike.rparen", a4)
      val (c6, a6) = if c5.peekKind == "spike.rparen" then keep(c5.advance, a5) else (c5, a5)
      St(c6, Node.Frame("spike.sealed", None, a6))
    else St(c3, Node.Frame("spike.sealed", None, a3))

  // `import a.b.c` / `import a.b.{x, y}` / `import a.b.*` — parse-only no-op (ssc1-front.ssc0:2485 → sealed).
  // Consume exactly the dotted path (+ optional `{…}` group / `.*` wildcard), like ssc1-front's skipPath.
  // The PATH IS ATTACHED, and it did not used to be. Every token here was consumed and thrown
  // away, so the frame carried one token and `import a.b.c` was indistinguishable from
  // `import x.y` — correct for the v2 front, which resolves imports elsewhere and reads this as
  // `Pair("sealed", "")`, and useless for the role UniML is being readied for: a v3 front on this
  // tree has to build a module graph and there was nothing to build it from. Found by the typed
  // projection asserting the path was there and failing.
  //
  // The KIND stays `spike.sealed` deliberately. `SpikeProject` matches on it and returns a
  // constant, so extra children change nothing for the v2 lane; a new kind would have needed a
  // change there and in every kind census. Consumers that want the path read the roles.
  private def parseImportStmt(c0: Cur): St[Node] =
    val kw = c0.advance // `import`
    val kids0 = kw.v.map(t => Node.Leaf(t, Some("imp.kw"))).toVector
    def walk(c: Cur, acc: Vector[Node]): St[Vector[Node]] =
      if c.peekKind == "spike.id" || c.peekKind == "spike.uid" then
        val seg = c.advance
        val a1 = acc ++ seg.v.map(t => Node.Leaf(t, Some("imp.seg"))).toVector
        if seg.c.peekKind == "spike.dot" then
          val dot = seg.c.advance
          walk(dot.c, a1 ++ dot.v.map(t => Node.Leaf(t, Some("imp.dot"))).toVector)
        else St(seg.c, a1)
      else if c.peekKind == "spike.lbrace" then
        val open = c.advance // `{`
        def sels(cc: Cur, a: Vector[Node]): St[Vector[Node]] =
          if cc.peekKind != "spike.rbrace" && !cc.eof then
            // a selector group `{x, y}` — the names are segments, the commas are punctuation
            val role = if cc.peekKind == "spike.id" || cc.peekKind == "spike.uid" then "imp.sel" else "imp.tok"
            val t = cc.advance
            sels(t.c, a ++ t.v.map(tok => Node.Leaf(tok, Some(role))).toVector)
          else St(cc, a)
        val group = sels(open.c, acc ++ open.v.map(t => Node.Leaf(t, Some("imp.tok"))).toVector)
        if group.c.peekKind == "spike.rbrace" then
          val close = group.c.advance
          St(close.c, group.v ++ close.v.map(t => Node.Leaf(t, Some("imp.tok"))).toVector)
        else St(group.c, group.v)
      else if c.peekLexeme == "*" then
        val wc = c.advance
        St(wc.c, acc ++ wc.v.map(t => Node.Leaf(t, Some("imp.wildcard"))).toVector)
      else St(c, acc)
    val walked = walk(kw.c, kids0)
    St(walked.c, Node.Frame("spike.sealed", None, walked.v))

  def parseProgram(toks: Vector[SourceToken]): Parsed =
    def dispatch(c: Cur): St[Node] =
      if isDefStart(c) then parseDef(c)
      // `case object`. The `case` used to be advanced past and dropped, so the object frame had
      // no way to know, and `case object O` projected identically to `object O`.
      else if isKw(c, "case") && c.peek2Lexeme == "object" then
        val caseKw = c.advance
        parseObject(caseKw.c, caseKw.v)
      else if isKw(c, "case") then parseCaseClass(c)
      else if isKw(c, "given") then parseGiven(c)
      else if isKw(c, "enum") then parseEnum(c)
      else if isKw(c, "extension") then parseExtension(c)
      else if isWord(c, "object") then parseObject(c)
      else if isWord(c, "effect") && (c.peek2Kind == "spike.uid" || c.peek2Kind == "spike.id") then parseEffectDecl(c, false)
      else if isWord(c, "multi") && c.peek2Lexeme == "effect" then parseEffectDecl(c, true) // `multi effect`
      else if isWord(c, "extern") then parseExtern(c)
      else if isWord(c, "type") && isNameKind(c.peek2Kind) then parseTypeAlias(c) // `type X = Y` erased
      // `opaque type X = Y` — erased exactly like the plain alias above, and read the same way
      // `multi effect` is read two lines up: a two-word head, matched on the second lexeme. Step
      // over `opaque` and the existing parser handles the rest, since it consumes the alias LINE.
      //
      // THE TWIN, and why it is here. v3's own parser had the same hole and fixing it alone was not
      // enough: `ssc3 run` takes THIS front whenever uniml is registered, so the fixture went green
      // in a worktree without uniml and RED with it, at a different position and message
      // (`unknown name 'opaque'` here, `expected an expression, found =` there). A construct this
      // tier erases has to be erased by both fronts or the feature is invisible on the default path.
      else if isWord(c, "opaque") && c.peek2Lexeme == "type" then
        parseTypeAlias(c.bump) // `opaque`; `parseTypeAlias` starts at `type` and eats the rest of the line
      else if isWord(c, "trait") || isKw(c, "class") then parseTraitOrClassNoop(c)
      // a top-level STATEMENT — script-style `println(…)`, top-level `val`/`var`/expr. ssc1-front keeps these
      // in source order and lowerProg collects them into `(entry (seq …))` (and `val`/`var` → a global cell).
      // Before this they collapsed the whole program to Nil (newfront Phase 0's #1 gap).
      else parseStmt(c, topLevel = true)
    // A residual `}` at TOP LEVEL is SKIPPED and yields NO statement — ssc1-front's parseStmts does exactly
    // `else if kindIs("}", ts2) then go(advance(ts2), acc)` (ssc1-front.ssc0:2828). These are real: its
    // layout pass emits a VIRTUAL `}` to close each open layout frame and then keeps the original `}` token
    // too (ssc1-front.ssc0:3101-3106), and a `{` swallowed by the char lexer (`'{ ` in `'{ $x + 1 }` lexes
    // as a 3-char CHAR literal!) leaves its `}` unmatched. Without this skip the token became a bogus
    // top-level `_err` statement that the oracle never has.
    def skipResiduals(c: Cur): Cur =
      val c1 = c.skipSemis
      if c1.peekKind == "spike.rbrace" then skipResiduals(c1.bump) else c1
    // annotation: a SAME-LINE `@ann <decl>` is a decl annotation (erased); an OWN-LINE `@ann` (the next
    // token sits on a LATER line — ssc1-front's layout inserts a `;` there) is a standalone statement →
    // `_err`, because ssc1-front's `@` handler runs `parseOneStmt(skipAnn(…))` which then hits that `;`
    // (ssc1-front.ssc0:2499). Emit the `_err` and let the decl parse on the next loop iteration.
    // AN ANNOTATION ON ITS OWN LINE IS SKIPPED, and the declaration below it is parsed.
    //
    // This used to emit an `_err` node in that case, faithfully — the comment here cited
    // `ssc1-front.ssc0:2499`, where skipping `@Name(args)` left the layout `;` and `parseOneStmt`
    // met a separator where a declaration should be. **The reference has since FIXED that**: the
    // call reads `parseOneStmt(skipSemis(skipAnn(toks)))` now, and its own comment files the old
    // behaviour as `ssc1-front-annotation-before-declaration`, noting "F has no such gap".
    //
    // So this was mirroring a BUG, from a version that no longer exists. Eight files across
    // `examples/` and the standard library were refused by v3 for it — `@graphLabel`, `@rdfClass`,
    // `@tailrec` — while the reference parses them. Fidelity to an oracle means fidelity to what
    // it does, which is a thing to re-read rather than remember.
    def skipAnns(c: Cur): Cur =
      if isAnnotationStart(c) then skipAnns(skipAnnotation(c).skipSemis) else c
    def loop(c0: Cur, defs: Vector[Node]): St[Vector[Node]] =
      if c0.eof then St(c0, defs)
      else
        val c1 = skipDeclModifiers(skipAnns(skipResiduals(c0)))         // `sealed`/`final`/`abstract`/… — erased
        if c1.eof then St(c1, defs) // trailing annotation(s)/modifier(s) with nothing after
        else
          val d = dispatch(c1)
          val progressed = if d.c.p == c1.p then d.c.bump else d.c // guarantee progress even if nothing was consumed
          loop(progressed, defs :+ d.v)
    val done = loop(Cur(toks, 0, Vector.empty, 0, Nil), Vector.empty)
    Parsed(Node.Frame("spike.program", None, done.v), done.c.diags)

// ── serialise the Node tree → VmTokens (open on first token, closeAfter on last) ──
object SpikeEmit:
  private final case class Ev(tok: SourceToken, opens: Vector[FrameSpec], closes: Vector[String], role: Option[String])

  private def walk(n: Node): Vector[Ev] = n match
    case Node.Leaf(t, role) => Vector(Ev(t, Vector.empty, Vector.empty, role))
    case Node.Frame(kind, role, kids) =>
      val evs = kids.flatMap(walk)
      if evs.isEmpty then Vector.empty
      else
        val head = evs.head
        val opened = FrameSpec(kind, role)
        val withOpen = evs.updated(0, head.copy(opens = opened +: head.opens))
        val li = withOpen.length - 1
        withOpen.updated(li, withOpen(li).copy(closes = withOpen(li).closes :+ kind))

  /** Emits over the FULL lexed stream, not just the parsed tree.
    *
    * The parser consumes `spike.ws` through `skipTrivia` and never puts it in its
    * tree, so emitting the tree alone dropped every space and newline: `def
    * add(a: Int, b: Int)` came back as `defadd(a:Int,b:Int)` on input the parser
    * understood completely. Losslessness is the whole reason to put UniML in a
    * front end, so it must not depend on the parser choosing to keep trivia.
    *
    * Tokens carry a stable lexer-assigned `id`, so the tree's frame transitions
    * are re-attached to the tokens that earned them and every other lexed token
    * is emitted in its source position. No parse rule changes.
    *
    * THIS REQUIRES EACH TOKEN TO APPEAR AT MOST ONCE IN THE TREE. It does now;
    * it did not before, and an id-keyed map silently kept one of the two copies,
    * losing the other's frame transitions and collapsing the C_min projection.
    *
    * A token the parser DROPPED rather than skipped — error recovery leaving a
    * hole — comes back too, labelled `unparsed` rather than `trivia`, so the
    * recovery path is lossless as well. That is when a lossless tree is worth
    * the most. Both roles are filtered out by the projection's `kids`. */
  def emit(root: Node, lexed: Vector[SourceToken]): Vector[VmToken] =
    val evs = walk(root)
    if lexed.isEmpty then Vector.empty
    else
      val byId = evs.iterator.map(ev => ev.tok.id -> ev).toMap
      // (token, opens, closes, role) per LEXED token, tree events where there are any
      val rows0 = lexed.map { tok =>
        byId.get(tok.id) match
          case Some(ev) => (tok, ev.opens, ev.closes, ev.role)
          case None =>
            val role = if tok.channel == TokenChannel.Trivia then "trivia" else "unparsed"
            (tok, Vector.empty[FrameSpec], Vector.empty[String], Some(role))
      }
      // THE OUTERMOST FRAME MUST SPAN THE WHOLE SOURCE. It opens on the first token the
      // PARSER used, which is no longer the first token EMITTED — leading whitespace now
      // precedes it. Left alone, that trivia sits outside the frame as an extra ROOT, and
      // every consumer takes `roots.head`, so `\ndef f…` projected to Nil while `def f…`
      // projected fine. Move the outermost open to the first emitted token and the
      // outermost close to the last; inner frames keep the tokens that earned them.
      val openAt = evs.headOption.flatMap(h => rows0.indexWhere(_._1.id == h.tok.id) match
        case -1 => None
        case i  => Some(i))
      val withOpen = openAt match
        case Some(i) if i > 0 && rows0(i)._2.nonEmpty =>
          val spec = rows0(i)._2.head
          val trimmed = rows0.updated(i, rows0(i).copy(_2 = rows0(i)._2.drop(1)))
          trimmed.updated(0, trimmed(0).copy(_2 = spec +: trimmed(0)._2))
        case _ => rows0
      val closeAt = evs.lastOption.flatMap(l => withOpen.lastIndexWhere(_._1.id == l.tok.id) match
        case -1 => None
        case i  => Some(i))
      val rows = closeAt match
        case Some(i) if i < withOpen.length - 1 && withOpen(i)._3.nonEmpty =>
          val last = withOpen.length - 1
          val kind = withOpen(i)._3.last
          val trimmed = withOpen.updated(i, withOpen(i).copy(_3 = withOpen(i)._3.dropRight(1)))
          trimmed.updated(last, trimmed(last).copy(_3 = trimmed(last)._3 :+ kind))
        case _ => withOpen
      rows.map { (tok, opens, closes, role) =>
        val instr =
          if opens.isEmpty && closes.isEmpty then VmInstruction.Emit(role)
          else VmInstruction.Reframe(open = opens, closeAfter = closes, role = role)
        VmToken(tok, instr)
      }

// ── the dialect ───────────────────────────────────────────────────────────────
object SpikeDialect extends DialectAdapter:
  def id: String = "scalascript.spike"

  override val aliases: Set[String] = Set("scalascript", "scala", "ssc")

  def instructions(source: SourceInput): Processor[String, SourceChunk, VmToken] =
    new Processor[String, SourceChunk, VmToken]:
      def start: String = ""
      def step(state: String, input: SourceChunk): Stepped[String, VmToken] =
        Stepped(state + input.text, ProcessBatch.empty)
      def stop(state: String): ProcessBatch[VmToken] =
        val toks = SpikeLex.scan(source.source, state)
        val parsed = SpikeParse.parseProgram(toks)
        ProcessBatch(SpikeEmit.emit(parsed.tree, toks), parsed.diagnostics)

// ── projection: UniML CST → ssc-v2 `Pair(tag,data)` AST as ssc0 source text ────
// TOTAL: any error / missing subtree becomes a `__notImplemented__` hole.
object SpikeProject:
  private val hole = """Pair("prim", Pair("__notImplemented__", Nil))"""

  private def esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

  /** escape a decoded string VALUE back into an ssc0 string literal that round-trips to it
    * (ssc0 buildStr decodes `\n`/`\t`; `\`/`"` are escaped by `esc`). */
  private def escStr(s: String): String = esc(s).replace("\n", "\\n").replace("\t", "\\t")

  // ── string interpolation (mirrors ssc1-front interpParts / partsToExpr, KC12) ──────────────────
  // Same rule as the lexer's `isSpikeIdPart`, which lives in another object — stated from the
  // shared primitives rather than reached across, so the two cannot drift apart silently.
  private def isAlphaNum(c: Char): Boolean =
    UniAlphabet.isAsciiAlnum(c) || c == '_' || UniAlphabet.isNonAscii(c)

  /** skip a nested `"…"` inside a `${…}` body; returns the index just after the closing quote. */
  private def scanNestedStr(s: String, i0: Int): Int =
    val n = s.length
    def scan(i: Int): Int =
      if i >= n then i
      else if s.charAt(i) == '"' then i + 1
      else if s.charAt(i) == '\\' then scan(i + 2)
      else scan(i + 1)
    scan(i0)

  /** index just after the `}` that matches a `${` opened at depth `depth0`; balances nested
    * braces and string literals; malformed input returns EOF. */
  private def scanInterpEnd(s: String, i0: Int, depth0: Int): Int =
    val n = s.length
    def scan(i: Int, depth: Int): Int =
      if i >= n then i
      else s.charAt(i) match
        case '"' => scan(scanNestedStr(s, i + 1), depth)
        case '{' => scan(i + 1, depth + 1)
        case '}' => if depth == 1 then i + 1 else scan(i + 1, depth - 1)
        case _   => scan(i + 1, depth)
    scan(i0, depth0)

  /** split a decoded interpolated string into ("str",lit) | ("var",name) | ("expr",src) parts. */
  private def interpParts(raw: String): Vector[(String, String)] =
    val n = raw.length
    def flush(out: Vector[(String, String)], litStart: Int, end: Int): Vector[(String, String)] =
      if end > litStart then out :+ (("str", raw.substring(litStart, end))) else out
    def walk(i: Int, litStart: Int, out: Vector[(String, String)]): Vector[(String, String)] =
      if i >= n then flush(out, litStart, n)
      else if raw.charAt(i) == '$' && i + 1 < n then
        val c2 = raw.charAt(i + 1)
        if c2 == '{' then
          val iAfter  = scanInterpEnd(raw, i + 2, 1)
          val exprEnd = if iAfter > i + 2 && iAfter <= n && raw.charAt(iAfter - 1) == '}' then iAfter - 1 else n
          walk(iAfter, iAfter, flush(out, litStart, i) :+ (("expr", raw.substring(i + 2, exprEnd))))
        else if UniAlphabet.isAsciiLetter(c2) || UniAlphabet.isNonAscii(c2) then // ssc1-front's isAlpha is LETTER-only: `$_foo` is NOT interpolated (a literal `$`)
          def idEnd(k: Int): Int = if k < n && isAlphaNum(raw.charAt(k)) then idEnd(k + 1) else k
          val endId = idEnd(i + 1)
          walk(endId, endId, flush(out, litStart, i) :+ (("var", raw.substring(i + 1, endId))))
        else walk(i + 1, litStart, out) // a literal `$` (including `$_…` / `$1…`, which ssc1-front leaves as text)
      else walk(i + 1, litStart, out)
    walk(0, 0, Vector.empty)

  /** fold parts into the right-associative `++` concatenation partsToExpr builds. */
  private def partsToExpr(parts: Vector[(String, String)]): String =
    if parts.isEmpty then """mkStr("")"""
    else
      val (tag, v) = parts.head
      val pe = tag match
        case "str"  => s"""mkStr("${escStr(v)}")"""
        case "expr" => exprOfSource(v)
        case _      => s"""mkVar("${esc(v)}")"""
      if parts.tail.isEmpty then pe else s"""mkInf("++", $pe, ${partsToExpr(parts.tail)})"""

  private def sInterp(raw: String): String = partsToExpr(interpParts(raw))

  // ── f-interpolation: printf specs → __fInterpolate__ (mirrors buildFInterp/goFArgs) ────────────
  private def isFmtFlag(c: Char): Boolean = "-#+ 0,(<".indexOf(c.toInt) >= 0
  private def isDigitC(c: Char): Boolean = UniAlphabet.isDigit(c)

  /** peel a leading printf spec `%[flags][width][.prec]<letter>` off `part`; default `"%s"`. */
  private def splitFFormatPrefix(part: String): (String, String) =
    val len = part.length
    if len == 0 || part.charAt(0) != '%' then ("%s", part)
    else
      def flagsEnd(i: Int): Int = if i < len && isFmtFlag(part.charAt(i)) then flagsEnd(i + 1) else i
      def digitsEnd(i: Int): Int = if i < len && isDigitC(part.charAt(i)) then digitsEnd(i + 1) else i
      val afterWidth = digitsEnd(flagsEnd(1))
      val i = if afterWidth < len && part.charAt(afterWidth) == '.' then digitsEnd(afterWidth + 1) else afterWidth
      if i < len && UniAlphabet.isAsciiLetter(part.charAt(i)) then (part.substring(0, i + 1), part.substring(i + 1, len))
      else ("%s", part)

  private def fArgExpr(part: (String, String)): String =
    if part._1 == "expr" then exprOfSource(part._2) else s"""mkVar("${esc(part._2)}")"""

  /** interleave [spec, arg, restLiteral] triples across the arg parts (goFArgs). */
  private def goFArgs(parts: Vector[(String, String)]): Vector[String] =
    if parts.isEmpty then Vector.empty
    else
      val ae = fArgExpr(parts.head)
      val rest = parts.tail
      if rest.isEmpty then Vector(s"""mkStr("%s")""", ae, """mkStr("")""")
      else if rest.head._1 == "str" then
        val (spec, r) = splitFFormatPrefix(rest.head._2)
        Vector(s"""mkStr("${escStr(spec)}")""", ae, s"""mkStr("${escStr(r)}")""") ++ goFArgs(rest.tail)
      else Vector(s"""mkStr("%s")""", ae, """mkStr("")""") ++ goFArgs(rest)

  private def fInterp(raw: String): String =
    val parts = interpParts(raw)
    if parts.isEmpty then """mkStr("")"""
    else
      val p0 = parts.head
      val args =
        if p0._1 == "str" then Vector(s"""mkStr("${escStr(p0._2)}")""") ++ goFArgs(parts.tail)
        else """mkStr("")""" +: goFArgs(parts)
      s"""mkApp(mkVar("__fInterpolate__"), ${consList(args)})"""

  /** re-parse an inner `${…}` expression with the spike's own front and project it. Wrapping it
    * as a parameterless def yields a program the dialect parses; then lift the def body. */
  private def exprOfSource(src: String): String =
    val pr = UniML.parse(SourceInput.fromString(SourceId("interp:expr"), s"def __e__ = $src"), SpikeDialect)
    val body = for
      prog <- pr.roots.headOption
      defn <- kids(prog).collectFirst { case (_, c) if kindOf(c) == "spike.def" => c }
      b    <- kids(defn).collectFirst { case (Some("def.body"), c) => c }
    yield expr(b)
    body.getOrElse(hole)

  private def lexeme(n: UniNode): String = n match
    case UniNode.Token(t) => t.lexeme
    case _                => ""
  private def kindOf(n: UniNode): String = n match
    case b: UniNode.Branch => b.kind
    case UniNode.Token(_)  => "token"

  /** The children the PROJECTION sees, which is not the set the CST holds.
    *
    * Since the emitter became lossless the tree also carries tokens the parse did
    * not use. Filtering by ROLE rather than by kind is what makes that safe: an
    * `unparsed` token has an ordinary syntactic kind like `spike.id`, so a
    * kind-based filter lets it through and the projection reads it as real
    * structure. */
  private def kids(n: UniNode): Vector[(Option[String], UniNode)] = n match
    case b: UniNode.Branch =>
      b.edges.collect {
        case UniEdge(role, c)
            if !role.contains("trivia") && !role.contains("unparsed") &&
              !(c.isInstanceOf[UniNode.Token] && c.asInstanceOf[UniNode.Token].value.kind == "spike.ws") =>
          (role, c)
      }
    case _ => Vector.empty

  def program(root: UniNode): String =
    consList(kids(root).flatMap {
      case (_, c) if kindOf(c) == "spike.def"       => defNodes(c)
      case (_, c) if kindOf(c) == "spike.casecls"   => caseClsNodes(c)
      case (_, c) if kindOf(c) == "spike.given"     => Vector(givenNode(c))
      case (_, c) if kindOf(c) == "spike.givenobj"  => Vector(givenObjNode(c))
      case (_, c) if kindOf(c) == "spike.givenval"  => Vector(givenValNode(c))
      case (_, c) if kindOf(c) == "spike.enum"      => enumNodes(c)
      case (_, c) if kindOf(c) == "spike.extension" => extensionNodes(c)
      case (_, c) if kindOf(c) == "spike.object"    => Vector(objectNode(c))
      case (_, c) if kindOf(c) == "spike.effectdecl" => Vector(effectDeclNode(c))
      // top-level STATEMENTS in source order — `stmt()` projects them to mkVal/Pair("var")/Pair("assign")/
      // Pair("while")/mkSExpr, which lowerProg collects into `(entry (seq …))` (and val/var → a global cell).
      case (_, c) if isTopStmt(kindOf(c))           => Vector(stmt(c))
      case _                                        => Vector.empty[String]
    }.toVector)

  // `effect L: ops` → Pair("effect_decl", Pair(name, Pair(false, [op-defs]))) — lowerer builds E_op closures.
  private def effectDeclNode(n: UniNode): String =
    val name  = kids(n).collectFirst { case (Some("eff.name"), c) => lexeme(c) }.getOrElse("_")
    val multi = kids(n).exists { case (Some("eff.multi"), _) => true; case _ => false } // `multi effect` = multi-shot
    val ops   = kids(n).collect { case (Some("eff.op"), c) => memberNode(c) }
    s"""Pair("effect_decl", Pair("${esc(name)}", Pair($multi, ${consList(ops.toVector)})))"""

  // `object X { members }` → Pair("object", Pair(name, [member-stmts])); the lowerer emits X_member globals.
  private def objectNode(n: UniNode): String =
    val name = kids(n).collectFirst { case (Some("obj.name"), c) => lexeme(c) }.getOrElse("_")
    val members = kids(n).collect { case (Some("obj.member"), c) => memberNode(c) }
    s"""Pair("object", Pair("${esc(name)}", ${consList(members.toVector)}))"""
  private def memberNode(c: UniNode): String = kindOf(c) match
    case "spike.def"     => defNode(c)
    case "spike.casecls" => caseClsNode(c)
    case _               => stmt(c) // val / var / exprStmt / …

  private def isTopStmt(k: String): Boolean =
    k == "spike.val" || k == "spike.var" || k == "spike.assign" || k == "spike.while" ||
    k == "spike.exprStmt" || k == "spike.sealed" || k == "spike.tuppatval" || k == "spike.compoundassign" || k == "spike.idxassign"

  // an extension group → three statements: extension_start, the method def (receiver prepended
  // to its params), extension_end. lowerProg's collectExtensionMethods registers it for `.m`.
  private def extensionNodes(n: UniNode): Vector[String] =
    val recv = kids(n).collectFirst { case (Some("ext.recv"), c) => lexeme(c) }.getOrElse("_")
    val defs = kids(n).collect { case (_, c) if kindOf(c) == "spike.def" => defNode(c, Vector("\"" + esc(recv) + "\"")) }
    (Vector("""Pair("extension_start", "")""") ++ defs ++ Vector("""Pair("extension_end", "")""")).toVector

  // Pair("enum", Pair(name, [Pair(caseName, [fieldNames])…])) — lowerProg makes each case a ctor.
  private def enumNode(n: UniNode): String =
    val ks = kids(n)
    val name = ks.collectFirst { case (Some("enum.name"), c) => lexeme(c) }.getOrElse("_")
    val cases = ks.collect { case (_, c) if kindOf(c) == "spike.enumcase" => enumCase(c.asInstanceOf[UniNode.Branch]) }
    s"""Pair("enum", Pair("${esc(name)}", ${consList(cases)}))"""

  /** Positional (name, default) pairing: each `fieldRole` leaf owns the LAST `dfltRole` that
    * follows it before the next boundary role — the shape the three index walks it replaced all
    * shared (a default sits right after its field, before the next field/section). Roles compare
    * by EQUALITY (`Option.contains`), exactly as the walks did. */
  private def fieldDefaultPairs(
      ks: Vector[(Option[String], UniNode)],
      fieldRole: String, dfltRole: String, boundary: Option[String] => Boolean,
  ): Vector[(String, Option[String])] =
    def dfltAfter(j: Int, found: Option[String]): Option[String] =
      if j >= ks.length || boundary(ks(j)._1) then found
      else if ks(j)._1.contains(dfltRole) then dfltAfter(j + 1, Some(wrapArg(ks(j)._2)))
      else dfltAfter(j + 1, found)
    ks.zipWithIndex.collect {
      case ((role, node), i) if role.contains(fieldRole) =>
        ("\"" + esc(lexeme(node)) + "\"", dfltAfter(i + 1, None))
    }

  private val noDflt = """Pair("__nodflt__", "")"""

  // the enum decl plus, per parametrized case with field defaults, a companion `("funcdefaults", …)` node
  // (variant-A: lowerProg's collectFuncDefaultsNodes unions it into funcDefaultsCell) so `Circle()` →
  // `Circle(1)` synthesises like a case class with defaults (`case Square(side: Int = 2)`).
  private def enumNodes(n: UniNode): Vector[String] =
    val fdNodes = kids(n).collect { case (_, c) if kindOf(c) == "spike.enumcase" => c }.toVector.flatMap { ec =>
      val eks = kids(ec)
      val cname = eks.collectFirst { case (Some("ec.name"), c) => lexeme(c) }.getOrElse("_")
      val pairs = fieldDefaultPairs(eks, "ec.field", "ec.dflt", r => r.contains("ec.field"))
      if pairs.exists(_._2.isDefined) then
        Vector(s"""Pair("funcdefaults", Pair("${esc(cname)}", Pair(${consList(pairs.map(_._1))}, ${consList(pairs.map(_._2.getOrElse(noDflt)))})))""")
      else Vector.empty[String]
    }
    Vector(enumNode(n)) ++ fdNodes

  private def enumCase(b: UniNode.Branch): String =
    val cname = kids(b).collectFirst { case (Some("ec.name"), c) => lexeme(c) }.getOrElse("_")
    val fields = kids(b).collect { case (Some("ec.field"), c) => "\"" + esc(lexeme(c)) + "\"" }.toVector
    s"""Pair("${esc(cname)}", ${consList(fields)})"""

  // Pair("given", Pair(name, Pair(typeStr, body))) — lowerProg builds the given table + IrDef.
  private def givenNode(n: UniNode): String =
    val ks = kids(n)
    val name = ks.collectFirst { case (Some("given.name"), c) => lexeme(c) }.getOrElse("_")
    val ty   = ks.collectFirst { case (Some("given.type"), c) => concatType(c) }.getOrElse("_")
    val body = ks.collectFirst { case (Some("given.body"), c) => expr(c) }.getOrElse(hole)
    s"""Pair("given", Pair("${esc(name)}", Pair("${esc(ty)}", $body)))"""

  // `given name: T with { members }` → Pair("given_obj", Pair(name, Pair(typeStr, [member-stmts])))
  private def givenObjNode(n: UniNode): String =
    val ks = kids(n)
    val name = ks.collectFirst { case (Some("given.name"), c) => lexeme(c) }.getOrElse("_")
    val ty   = ks.collectFirst { case (Some("given.type"), c) => concatType(c) }.getOrElse("_")
    val members = ks.collect { case (Some("obj.member"), c) => c }.toVector.flatMap(memberNodes)
    s"""Pair("given_obj", Pair("${esc(name)}", Pair("${esc(ty)}", ${consList(members)})))"""

  // a given-body member → its projected node(s). An `extension [A](recv: T) def m … = …` group inside a
  // `given … with` body projects EXACTLY like a top-level one: extension_start / def-with-receiver-prepended
  // / extension_end. The markers are load-bearing, not decoration — ssc1-lower's collectExtensionMethods
  // descends into a given_obj's members with active=FALSE (ssc1-lower.ssc0:573-576), so a bare def there
  // never registers as an extension method; only defs BETWEEN the markers do. Registering them is what makes
  // the lowerer emit the tag-testing dispatcher (`fmap` → listFunctor_fmap / optionFunctor_fmap) on top of
  // the per-instance `<given>_<m>` defs and the instance method object.
  private def memberNodes(c: UniNode): Vector[String] = kindOf(c) match
    case "spike.extension" => extensionNodes(c)
    case _                 => Vector(memberNode(c))

  private def givenValNode(n: UniNode): String =
    val ks = kids(n)
    val name = ks.collectFirst { case (Some("given.name"), c) => lexeme(c) }.getOrElse("_")
    val body = ks.collectFirst { case (Some("given.body"), c) => expr(c) }.getOrElse(hole)
    s"""mkVal("${esc(name)}", $body)"""

  // Pair("casecls", Pair(name, Pair(fieldNames, Pair(fieldTypes, derives)))) via mkCaseCls;
  // lowerProg generates the ctor def, Mirror, `_sel_<field>` accessors and `__regfields__`.
  private def caseClsNode(n: UniNode): String =
    val ks = kids(n)
    val name  = ks.collectFirst { case (Some("cc.name"), c) => lexeme(c) }.getOrElse("_")
    // an unsupported annotated field (cc.synthfield marker) contributed a synthetic ("_","Any") field,
    // appended after any real fields parsed before it — exactly ssc1-front's parseCaseParam fallback.
    val synth = ks.exists { case (Some("cc.synthfield"), _) => true; case _ => false }
    val names = ks.collect { case (Some("cc.field"), c) => "\"" + esc(lexeme(c)) + "\"" }.toVector ++ (if synth then Vector("\"_\"") else Vector.empty)
    val types = ks.collect { case (Some("cc.fieldType"), c) => "\"" + esc(concatType(c)) + "\"" }.toVector ++ (if synth then Vector("\"Any\"") else Vector.empty)
    val derives = ks.collect { case (Some("cc.derive"), c) => "\"" + esc(lexeme(c)) + "\"" }.toVector
    s"""mkCaseCls("${esc(name)}", ${consList(names)}, ${consList(types)}, ${consList(derives)})"""

  // a top-level def projects to its mkDef plus, when applicable, companion variant-A nodes that lowerProg
  // unions into the parser cells it can no longer populate from the spike's projection:
  //   usingsig   → usingSigCell   for call-site given auto-injection (injectGivens)
  //   funcdefaults → funcDefaultsCell for call-site default synthesis (`f(a)` → `f(a, dflt…)`)
  private def defNodes(n: UniNode): Vector[String] =
    val base = defNode(n)
    val ks   = kids(n)
    val name = ks.collectFirst { case (Some("def.name"), c) => lexeme(c) }.getOrElse("_")
    // positional param names + their defaults (a def.dflt follows its def.param, before the next def.param)
    val pairs = fieldDefaultPairs(ks, "def.param", "def.dflt", r => r.contains("def.param"))
    val paramNames = pairs.map(_._1)
    val usingTypes = ks.collect { case (Some("def.usingtype"), c) => "\"" + esc(lexeme(c)) + "\"" }.toVector
    val usingNode = if usingTypes.isEmpty then Vector.empty[String]
      else Vector(s"""Pair("usingsig", Pair("${esc(name)}", Pair(${consList(usingTypes)}, ${paramNames.length})))""")
    val fdNode = if !pairs.exists(_._2.isDefined) then Vector.empty[String]
      else Vector(s"""Pair("funcdefaults", Pair("${esc(name)}", Pair(${consList(paramNames)}, ${consList(pairs.map(_._2.getOrElse(noDflt)))})))""")
    Vector(base) ++ usingNode ++ fdNode

  // a case class projects to its mkCaseCls plus, if the body has METHODS, a companion casemethods node
  // (Pair("casemethods", Pair(name, Pair(fieldNames, [method-defs]))) — the shape lowerProg's caseMethodsCell
  // expects), which collectCaseMethodsNodes unions in to generate the `Name_method` globals + dispatch regs.
  private def caseClsNodes(n: UniNode): Vector[String] =
    val base    = caseClsNode(n)
    val ks      = kids(n)
    val name    = ks.collectFirst { case (Some("cc.name"), c) => lexeme(c) }.getOrElse("_")
    val methods = ks.collect { case (Some("cc.method"), c) => memberNode(c) }.toVector
    // positional field names + defaults (a cc.dflt follows its cc.field, before the next cc.field / cc.method)
    val pairs = fieldDefaultPairs(ks, "cc.field", "cc.dflt", r => r.contains("cc.field") || r.contains("cc.method"))
    val fields = pairs.map(_._1)
    val cmNode = if methods.isEmpty then Vector.empty[String]
      else Vector(s"""Pair("casemethods", Pair("${esc(name)}", Pair(${consList(fields)}, ${consList(methods)})))""")
    val fdNode = if !pairs.exists(_._2.isDefined) then Vector.empty[String]
      else Vector(s"""Pair("funcdefaults", Pair("${esc(name)}", Pair(${consList(fields)}, ${consList(pairs.map(_._2.getOrElse(noDflt)))})))""")
    // `case class Circle(…) extends Shape` → ("subtype", (Shape, Circle)) so `case _: Shape` expands to child tags
    val stNode = ks.collectFirst { case (Some("cc.parent"), c) => lexeme(c) } match
      case Some(p) => Vector(s"""Pair("subtype", Pair("${esc(p)}", "${esc(name)}"))""")
      case None    => Vector.empty[String]
    Vector(base) ++ cmNode ++ fdNode ++ stNode

  // concatenate a spike.cctype frame's token lexemes (no spaces) → the full field type string
  private def concatType(n: UniNode): String = n match
    case b: UniNode.Branch => kids(b).map((_, c) => lexeme(c)).mkString
    case UniNode.Token(t)  => t.lexeme

  private def consList(xs: Vector[String]): String =
    xs.foldRight("Nil")((h, acc) => s"Cons($h, $acc)")

  private def defNode(n: UniNode, prefixParams: Vector[String] = Vector.empty): String =
    val ks = kids(n)
    val name = ks.collectFirst { case (Some("def.name"), c) => lexeme(c) }.getOrElse("main")
    val params = prefixParams ++ ks.collect { case (Some("def.param"), c) => "\"" + esc(lexeme(c)) + "\"" }.toVector
    // an abstract def (no `=`, hence no def.eq leaf) is a trait method / effect op — the lowering ignores its
    // body, so give it a harmless unit placeholder rather than __notImplemented__ (which mis-flags the program).
    val hasEq   = ks.exists { case (Some("def.eq"), _) => true; case _ => false }
    val bodyRaw = if !hasEq then "mkTup(Nil)"
                  else ks.collectFirst { case (role, c) if role.contains("def.body") => expr(c) }.getOrElse(hole)
    // `def x: T = e` (no param clause) is parameterless — a bare `x` auto-applies. `def x(): T = e` (empty
    // parens) is a method — a bare `x` is the closure. ssc1-front marks the former with mkParameterlessBody.
    val hasParamClause = ks.exists { case (Some("def.lparen"), _) => true; case _ => false }
    val body = if hasParamClause || prefixParams.nonEmpty then bodyRaw else s"mkParameterlessBody($bodyRaw)"
    s"""mkDef("${esc(name)}", ${consList(params)}, $body)"""

  private def expr(n: UniNode): String = n match
    case UniNode.Token(t) if t.kind == "spike.int"   => s"""mkInt("${esc(SpikeNum.decode(t.lexeme))}")"""
    case UniNode.Token(t) if t.kind == "spike.float" => s"""mkFloat("${esc(SpikeNum.decode(t.lexeme))}")"""
    case UniNode.Token(t) if t.kind == "spike.str"   => s"""mkStr("${escStr(SpikeStr.decode(t.lexeme))}")"""
    // `true`/`false` are literal booleans, not variables (ssc1-front does the same)
    case UniNode.Token(t) if t.kind == "spike.id" && (t.lexeme == "true" || t.lexeme == "false") => s"""mkBool("${t.lexeme}")"""
    case UniNode.Token(t) if t.kind == "spike.id" && t.lexeme == "null" => """mkUVar("None")""" // K62.18: null → None
    case UniNode.Token(t) if t.lexeme == "???"       => hole // Predef.??? → prim __notImplemented__
    case UniNode.Token(t) if t.kind == "spike.id"    => s"""mkVar("${esc(t.lexeme)}")"""
    case UniNode.Token(t) if t.kind == "spike.uid"   => s"""mkUVar("${esc(t.lexeme)}")"""
    // an unhandled KEYWORD in atom position is a var NAMED AFTER ITSELF, not an error — ssc1-front's
    // parseAtom falls through to `pr(mkVar(v), advance(toks))` (ssc1-front.ssc0:1121). Reached via the
    // statement-level recovery (e.g. a stray `else` whose `if … then` branch a custom interpolator cut
    // short). Without this case the token hits `case _ => hole` and poisons the program with a parse hole.
    case UniNode.Token(t) if t.kind == "spike.kw"    => s"""mkVar("${esc(t.lexeme)}")"""
    case b: UniNode.Branch => b.kind match
      case "spike.infix" => infix(b)
      case "spike.paren" => kids(b).collectFirst { case (Some("group.elem"), c) => expr(c) }.getOrElse(hole)
      case "spike.tuple" => s"""mkTup(${consList(kids(b).collect { case (Some("group.elem"), c) => expr(c) }.toVector)})"""
      case "spike.call"  => call(b)
      case "spike.if"    => ifExpr(b)
      case "spike.block" => block(b)
      case "spike.match" => matchExpr(b)
      case "spike.sel"   => sel(b)
      case "spike.throw" =>
        val e = kids(b).collectFirst { case (Some("throw.expr"), c) => expr(c) }.getOrElse(hole)
        s"""Pair("prim", Pair("__throw__", Cons($e, Nil)))"""
      case "spike.assign" => // an assignment used as an expression (e.g. a for-do body) → same Pair("assign",…)
        val name = kids(b).collectFirst { case (Some("assign.name"), c) => lexeme(c) }.getOrElse("_")
        val rhs  = kids(b).collectFirst { case (Some("assign.rhs"), c) => expr(c) }.getOrElse(hole)
        s"""Pair("assign", Pair("${esc(name)}", $rhs))"""
      case "spike.for" =>
        val gens    = kids(b).collect { case (Some("for.gen"), g) => g }
        val body    = kids(b).collectFirst { case (Some("for.body"), c) => expr(c) }.getOrElse(hole)
        val isYield = kids(b).exists { case (Some("for.yield"), _) => true; case _ => false }
        // mkBinderLam: a single binder → `x => inner`; a tuple `(a,b,…)` → `__fp => { val a = __fp._1; … }`.
        def binderLam(g: UniNode, inner: String): String =
          val binders = kids(g).collect { case (Some("gen.binder"), c) => esc(lexeme(c)) }.toVector
          if binders.length > 1 then // a tuple binder `(a, b, …)`
            val binds = binders.zipWithIndex.map((nm, i) => s"""mkVal("$nm", mkSel(mkVar("__fp"), "_${i + 1}"))""")
            s"""mkLam(Cons("__fp", Nil), Pair("block", ${consList(binds :+ s"mkSExpr($inner)")}))"""
          else s"""mkLam(Cons("${binders.headOption.getOrElse("_")}", Nil), $inner)"""
        // gen[.filter(binderLam(guard))].method(binderLam(inner))
        def genExpr(g: UniNode, method: String, inner: String): String =
          val gen0 = kids(g).collectFirst { case (Some("gen.gen"), c) => expr(c) }.getOrElse(hole)
          val gen  = kids(g).collectFirst { case (Some("gen.guard"), c) => expr(c) } match
            case Some(guard) => s"""mkApp(mkSel($gen0, "filter"), Cons(${binderLam(g, guard)}, Nil))"""
            case None        => gen0
          s"""mkApp(mkSel($gen, "$method"), Cons(${binderLam(g, inner)}, Nil))"""
        // flatMap for every generator but the last; map (yield) / foreach (do) for the last.
        val n = gens.length
        gens.zipWithIndex.foldRight(body) { case ((g, i), inner) =>
          genExpr(g, if i == n - 1 then (if isYield then "map" else "foreach") else "flatMap", inner)
        }
      // `case A =>` with NO body at all — ssc1-front's parseArmBody returns `Pair("uid", "Unit")` for an
      // empty statement list (ssc1-front.ssc0:1987). The frame carries the `=>` token only so it survives
      // the Node→UniNode emit; the token itself is not projected.
      case "spike.summon" => // payload = the whole type application, joined with NO separator (joinStrs)
        s"""Pair("summon", "${esc(kids(b).collect { case (Some("summon.tok"), c) => lexeme(c) }.mkString)}")"""
      case "spike.pre" =>
        val op  = kids(b).collectFirst { case (Some("pre.op"), c) => lexeme(c) }.getOrElse("-")
        val sub = kids(b).collectFirst { case (Some("pre.sub"), c) => expr(c) }.getOrElse(hole)
        s"""mkPre("${esc(op)}", $sub)"""
      case "spike.lambda" =>
        val ps   = kids(b).collect { case (Some("lam.param"), c) => "\"" + esc(lexeme(c)) + "\"" }.toVector
        val body = kids(b).collectFirst { case (Some("lam.body"), c) => expr(c) }.getOrElse(hole)
        s"""mkLam(${consList(ps)}, $body)"""
      case "spike.listlit" => // [e1, …, en] → List(e1, …, en)
        val els = kids(b).collect { case (Some("list.el"), c) => expr(c) }
        s"""mkApp(mkUVar("List"), ${consList(els.toVector)})"""
      case "spike.blockapp" => // e { body } → mkApp(e, [blockArg]) (ssc1-front consumeBlockArg)
        val fn  = kids(b).collectFirst { case (Some("blkapp.fn"), c) => expr(c) }.getOrElse(hole)
        val arg = kids(b).collectFirst { case (Some("blkapp.arg"), c) => expr(c) }.getOrElse(hole)
        s"""mkApp($fn, ${consList(Vector(arg))})"""
      case "spike.pfblock" => // { case … } → __pf => __pf match { … } (partial-function literal)
        val arms = kids(b).collect { case (_, c) if kindOf(c) == "spike.arm" => arm(c.asInstanceOf[UniNode.Branch]) }
        s"""mkLam(Cons("__pf", Nil), mkMatch(mkVar("__pf"), ${consList(arms.toVector)}))"""
      case "spike.try" => // try B [catch H] [finally F] → __tryCatch__ / __tryCatchFinally__ / __tryFinally__
        val body = kids(b).collectFirst { case (Some("try.body"), c) => expr(c) }.getOrElse(hole)
        val catchH = kids(b).collectFirst { case (Some("try.catch"), c) => expr(c) }
        val finH = kids(b).collectFirst { case (Some("try.finally"), c) => expr(c) }
        val bodyThunk = s"""mkLam(Nil, $body)"""
        (catchH, finH) match
          case (Some(ch), Some(fh)) =>
            s"""Pair("prim", Pair("__tryCatchFinally__", ${consList(Vector(bodyThunk, ch, s"mkLam(Nil, $fh)"))}))"""
          case (Some(ch), None) =>
            s"""Pair("prim", Pair("__tryCatch__", ${consList(Vector(bodyThunk, ch))}))"""
          case (None, Some(fh)) =>
            s"""Pair("prim", Pair("__tryFinally__", ${consList(Vector(bodyThunk, s"mkLam(Nil, $fh)"))}))"""
          case (None, None) => body // `try B` with no handler is just B (ssc1-front returns the raw body)
      case "spike.interp" =>
        val pfx = kids(b).collectFirst { case (Some("interp.prefix"), c) => lexeme(c) }.getOrElse("s")
        val raw = kids(b).collectFirst { case (Some("interp.raw"), c) => SpikeStr.decode(lexeme(c)) }.getOrElse("")
        pfx match
          case "md" => s"""Pair("prim", Pair("__mdStrip__", Cons(${sInterp(raw)}, Nil)))"""
          case "f"  => fInterp(raw) // printf format specifiers → __fInterpolate__
          case _    => sInterp(raw) // s / raw
      case "spike.rangeop" =>
        val word = kids(b).collectFirst { case (Some("range.op"), c) => lexeme(c) }.getOrElse("to")
        val lhs  = kids(b).collectFirst { case (Some("range.lhs"), c) => expr(c) }.getOrElse(hole)
        val rhs  = kids(b).collectFirst { case (Some("range.rhs"), c) => expr(c) }.getOrElse(hole)
        s"""mkApp(mkSel($lhs, "${esc(word)}"), ${consList(Vector(rhs))})"""
      case "spike.unitlit" => "mkTup(Nil)" // abstract-def placeholder body (ignored by effect/trait lowering)
      case "spike.direct" => // direct[F] { block } → Pair("direct", Pair(typeArgs, block)) — lowerer → flatMap
        val ty  = kids(b).collect { case (Some("ta.tok"), c) => lexeme(c) }.mkString
        val blk = kids(b).collectFirst { case (Some("direct.block"), c) => expr(c) }.getOrElse(hole)
        s"""Pair("direct", Pair("${esc(ty)}", $blk))"""
      case "spike.focusmarker" => """Pair("focus_marker", "")""" // Focus[T](_.a.b) — type args unused for focus
      case "spike.prism" => // Prism[Super, Case](…) — the variant name (after the last comma) drives the lowering
        val ty = kids(b).collect { case (Some("ta.tok"), c) => lexeme(c) }.mkString
        s"""Pair("prism", "${esc(ty)}")"""
      case "spike.error" => """mkVar("_err")""" // error-recovery for a stray/unparseable token (ssc1-front _err)
      case _             => hole
    case _ => hole

  private def sel(b: UniNode.Branch): String =
    val obj = kids(b).collectFirst { case (Some("sel.obj"), c) => expr(c) }.getOrElse(hole)
    val field = kids(b).collectFirst { case (Some("sel.field"), c) => lexeme(c) }.getOrElse("_")
    s"""mkSel($obj, "${esc(field)}")"""

  // Pair("match", Pair(scrut, [arm…])); arm = Pair(pattern, body); guard → Pair("gpat", …).
  private def matchExpr(b: UniNode.Branch): String =
    val scrut = kids(b).collectFirst { case (Some("match.scrut"), c) => expr(c) }.getOrElse(hole)
    val arms  = kids(b).collect { case (_, c) if kindOf(c) == "spike.arm" => arm(c.asInstanceOf[UniNode.Branch]) }
    s"""mkMatch($scrut, ${consList(arms)})"""

  private def arm(b: UniNode.Branch): String =
    val pat  = kids(b).collectFirst { case (Some("case.pat"), c) => patProj(c) }.getOrElse("""Pair("wpat", "")""")
    // no `case.body` child means `case A =>` with an empty body, i.e. Unit — the arm
    // used to carry a duplicated `=>` token to say this
    val body = kids(b).collectFirst { case (Some("case.body"), c) => expr(c) }.getOrElse("""mkUVar("Unit")""")
    val guarded = kids(b).collectFirst { case (Some("case.guard"), c) => expr(c) } match
      case Some(g) => s"""Pair("gpat", Pair($pat, $g))"""
      case None    => pat
    s"""Pair($guarded, $body)"""

  private def patProj(n: UniNode): String = n match
    case UniNode.Token(t) if t.kind == "spike.int"                    => s"""Pair("lpat", Pair("int", "${esc(t.lexeme)}"))"""
    case UniNode.Token(t) if t.kind == "spike.str"                    => s"""Pair("lpat", Pair("str", "${escStr(SpikeStr.decode(t.lexeme))}"))"""
    case UniNode.Token(t) if t.kind == "spike.float"                  => s"""Pair("lpat", Pair("float", "${esc(t.lexeme)}"))"""
    case UniNode.Token(t) if t.kind == "spike.id" && t.lexeme == "_"  => """Pair("wpat", "")"""
    case UniNode.Token(t) if t.kind == "spike.id" && (t.lexeme == "true" || t.lexeme == "false") =>
      s"""Pair("lpat", Pair("bool", "${t.lexeme}"))""" // `case true/false =>` (true/false are ids, not kws)
    case UniNode.Token(t) if t.kind == "spike.id"                     => s"""Pair("vpat", "${esc(t.lexeme)}")"""
    case b: UniNode.Branch if b.kind == "spike.cpat"                  => cpatProj(b)
    case b: UniNode.Branch if b.kind == "spike.conspat"               =>
      s"""Pair("cpat", Pair("Cons", ${consList(kids(b).collect { case (Some("conspat.arg"), c) => patProj(c) }.toVector)}))"""
    case b: UniNode.Branch if b.kind == "spike.tuppat"                => tuppatProj(b)
    case b: UniNode.Branch if b.kind == "spike.apat"                  =>
      s"""Pair("apat", ${consList(kids(b).collect { case (Some("apat.alt"), c) => patProj(c) }.toVector)})"""
    case b: UniNode.Branch if b.kind == "spike.bpat"                  =>
      val alias = kids(b).collectFirst { case (Some("bpat.alias"), c) => lexeme(c) }.getOrElse("_")
      val inner = kids(b).collectFirst { case (Some("bpat.inner"), c) => patProj(c) }.getOrElse("""Pair("wpat", "")""")
      s"""Pair("bpat", Pair("${esc(alias)}", $inner))"""
    case b: UniNode.Branch if b.kind == "spike.tpat"                  =>
      val pat = kids(b).collectFirst { case (Some("tpat.pat"), c) => patProj(c) }.getOrElse("""Pair("wpat", "")""")
      val ty  = kids(b).collectFirst { case (Some("tpat.type"), c) => lexeme(c) }.getOrElse("_")
      s"""Pair("tpat", Pair($pat, "${esc(ty)}"))"""
    case _ => """Pair("wpat", "")"""

  // ctor pattern → Pair("cpat", Pair(name, [subpats])); mirrors ssc1-front finishCtorPat.
  private def cpatProj(b: UniNode.Branch): String =
    val name = kids(b).collectFirst { case (Some("cpat.name"), c) => lexeme(c) }.getOrElse("_")
    val subs = kids(b).collect { case (Some("cpat.arg"), c) => patProj(c) }.toVector
    s"""Pair("cpat", Pair("${esc(name)}", ${consList(subs)}))"""

  // tuple pattern → ssc1-front lowers it to cpat "Pair" (2) / "TupleN" (≥3); 1 collapses.
  private def tuppatProj(b: UniNode.Branch): String =
    val subs = kids(b).collect { case (Some("tup.arg"), c) => patProj(c) }.toVector
    subs.length match
      case 0 => """Pair("wpat", "")"""
      case 1 => subs.head
      case 2 => s"""Pair("cpat", Pair("Pair", ${consList(subs)}))"""
      case n => s"""Pair("cpat", Pair("Tuple$n", ${consList(subs)}))"""

  // Pair("block", [stmt…]) — mirrors ssc1-front; lowerBlock folds vals into nested lets.
  private def block(b: UniNode.Branch): String =
    s"""Pair("block", ${consList(kids(b).map((_, c) => stmt(c)))})"""

  private def stmt(n: UniNode): String = n match
    case b: UniNode.Branch if b.kind == "spike.sealed" => """Pair("sealed", "")""" // import — parse-only no-op
    case b: UniNode.Branch if b.kind == "spike.tuppatval" => // val (a, b) = e → Pair("tuppat", Pair([names], e))
      val names = kids(b).collect { case (Some("tup.name"), c) => "\"" + esc(lexeme(c)) + "\"" }.toVector
      val rhs   = kids(b).collectFirst { case (Some("val.rhs"), c) => expr(c) }.getOrElse(hole)
      s"""Pair("tuppat", Pair(${consList(names)}, $rhs))"""
    case b: UniNode.Branch if b.kind == "spike.val" =>
      val name = kids(b).collectFirst { case (Some("val.name"), c) => lexeme(c) }.getOrElse("_")
      val rhs  = kids(b).collectFirst { case (Some("val.rhs"), c) => expr(c) }.getOrElse(hole)
      s"""mkVal("${esc(name)}", $rhs)"""
    case b: UniNode.Branch if b.kind == "spike.exprStmt" =>
      s"""mkSExpr(${kids(b).collectFirst { case (Some("stmt.expr"), c) => expr(c) }.getOrElse(hole)})"""
    case b: UniNode.Branch if b.kind == "spike.var" =>
      val name = kids(b).collectFirst { case (Some("var.name"), c) => lexeme(c) }.getOrElse("_")
      val rhs  = kids(b).collectFirst { case (Some("var.rhs"), c) => expr(c) }.getOrElse(hole)
      s"""Pair("var", Pair("${esc(name)}", $rhs))"""
    case b: UniNode.Branch if b.kind == "spike.assign" =>
      val name = kids(b).collectFirst { case (Some("assign.name"), c) => lexeme(c) }.getOrElse("_")
      val rhs  = kids(b).collectFirst { case (Some("assign.rhs"), c) => expr(c) }.getOrElse(hole)
      s"""Pair("assign", Pair("${esc(name)}", $rhs))"""
    case b: UniNode.Branch if b.kind == "spike.idxassign" => // `a(idx) = rhs` → ssc1-lower arr.set(a, idx, rhs)
      val lhs = kids(b).collectFirst { case (Some("idxassign.lhs"), c) => expr(c) }.getOrElse(hole)
      val rhs = kids(b).collectFirst { case (Some("idxassign.rhs"), c) => expr(c) }.getOrElse(hole)
      s"""Pair("idx_assign", Pair($lhs, $rhs))"""
    case b: UniNode.Branch if b.kind == "spike.compoundassign" =>
      // `x += e` parses through the EXPRESSION path (compoundBaseOp), so it is an `expr` statement wrapping an
      // assign — in a block lowerBlock let-binds an `expr` but seq's a bare `assign`, so the wrap matters.
      val name = kids(b).collectFirst { case (Some("ca.name"), c) => lexeme(c) }.getOrElse("_")
      val op   = kids(b).collectFirst { case (Some("ca.op"), c) => lexeme(c) }.getOrElse("+=").dropRight(1)
      val rhs  = kids(b).collectFirst { case (Some("ca.rhs"), c) => expr(c) }.getOrElse(hole)
      s"""mkSExpr(Pair("assign", Pair("${esc(name)}", mkInf("${esc(op)}", mkVar("${esc(name)}"), $rhs))))"""
    case b: UniNode.Branch if b.kind == "spike.while" =>
      val cond = kids(b).collectFirst { case (Some("while.cond"), c) => expr(c) }.getOrElse(hole)
      val body = kids(b).collectFirst { case (Some("while.body"), c) => expr(c) }.getOrElse(hole)
      s"""Pair("while", Pair($cond, $body))"""
    case b: UniNode.Branch if b.kind == "spike.def" => defNode(b)
    case n => s"""mkSExpr(${expr(n)})""" // unhandled stmt (e.g. an error-recovery node → `_err`) as a bare expr

  private def infix(b: UniNode.Branch): String =
    val op = kids(b).collectFirst { case (Some("bin.op"), c) => SpikeOp.meaning(lexeme(c)) }.getOrElse("+")
    val l  = kids(b).collectFirst { case (Some("bin.left"), c) => expr(c) }.getOrElse(hole)
    val r  = kids(b).collectFirst { case (Some("bin.right"), c) => expr(c) }.getOrElse(hole)
    s"""mkInf("${esc(op)}", $l, $r)"""

  private def call(b: UniNode.Branch): String =
    val fn = kids(b).collectFirst { case (Some("call.fn"), c) => expr(c) }.getOrElse(hole)
    val args = kids(b).collect { case (Some("call.arg"), c) => wrapArg(c) }
    s"""mkApp($fn, ${consList(args.toVector)})"""

  // ── underscore-placeholder lifting (mirrors ssc1-front wrapPhArg) ─────────────────────────────────────
  // A `_` in a call ARGUMENT — reached through inf/pre/sel/app/paren but NOT a nested lambda — lifts the
  // whole argument to an N-ary lambda: `_ + 1` → `x => x + 1`, `_ + _` → `(a, b) => a + b` (each `_` is a
  // DISTINCT param left-to-right, __u0/__u1/…). A bare `_` argument is left unwrapped (ssc1-front returns it).
  private def isBarePh(n: UniNode): Boolean = n match
    case UniNode.Token(t) => t.kind == "spike.id" && t.lexeme == "_"
    case _ => false
  private def phDescend(b: UniNode.Branch): Boolean =
    b.kind == "spike.infix" || b.kind == "spike.pre" || b.kind == "spike.sel" ||
    b.kind == "spike.call" || b.kind == "spike.paren"
  private def countPh(n: UniNode): Int = n match
    case UniNode.Token(t) if t.kind == "spike.id" && t.lexeme == "_" => 1
    // A `_` in a nested call's ARGUMENT belongs to that inner call (it gets its own lambda when the inner
    // call is projected), so it does NOT lift into THIS argument — only the fn position (e.g. `_.foo(a)`)
    // joins this lift. ssc1-front achieves the same by wrapping innermost-first at parse time.
    case b: UniNode.Branch if b.kind == "spike.call" =>
      kids(b).collect { case (Some("call.fn"), c) => countPh(c) }.sum
    case b: UniNode.Branch if phDescend(b) => kids(b).map((_, c) => countPh(c)).sum
    case _ => 0
  /** Projects with the running placeholder index THREADED (was a mutable one-cell array):
    * returns the projected source and the index after this subtree's placeholders. */
  private def projectPh(n: UniNode, k0: Int): (String, Int) = n match
    case UniNode.Token(t) if t.kind == "spike.id" && t.lexeme == "_" =>
      (s"""mkVar("__u$k0")""", k0 + 1)
    case b: UniNode.Branch if b.kind == "spike.infix" =>
      val op = kids(b).collectFirst { case (Some("bin.op"), c) => SpikeOp.meaning(lexeme(c)) }.getOrElse("+")
      val (l, k1) = kids(b).collectFirst { case (Some("bin.left"), c) => projectPh(c, k0) }.getOrElse((hole, k0))
      val (r, k2) = kids(b).collectFirst { case (Some("bin.right"), c) => projectPh(c, k1) }.getOrElse((hole, k1))
      (s"""mkInf("${esc(op)}", $l, $r)""", k2)
    case b: UniNode.Branch if b.kind == "spike.pre" =>
      val op  = kids(b).collectFirst { case (Some("pre.op"), c) => lexeme(c) }.getOrElse("-")
      val (sub, k1) = kids(b).collectFirst { case (Some("pre.sub"), c) => projectPh(c, k0) }.getOrElse((hole, k0))
      (s"""mkPre("${esc(op)}", $sub)""", k1)
    case b: UniNode.Branch if b.kind == "spike.sel" =>
      val (obj, k1) = kids(b).collectFirst { case (Some("sel.obj"), c) => projectPh(c, k0) }.getOrElse((hole, k0))
      val field = kids(b).collectFirst { case (Some("sel.field"), c) => lexeme(c) }.getOrElse("_")
      (s"""mkSel($obj, "${esc(field)}")""", k1)
    case b: UniNode.Branch if b.kind == "spike.call" =>
      // fn position joins THIS lift (threaded index); a nested call's args get their OWN placeholder
      // scope via wrapArg (independent lambda), so they must NOT consume this lambda's params.
      val (fn, k1) = kids(b).collectFirst { case (Some("call.fn"), c) => projectPh(c, k0) }.getOrElse((hole, k0))
      val args = kids(b).collect { case (Some("call.arg"), c) => wrapArg(c) }
      (s"""mkApp($fn, ${consList(args.toVector)})""", k1)
    case b: UniNode.Branch if b.kind == "spike.paren" =>
      kids(b).collectFirst { case (Some("group.elem"), c) => projectPh(c, k0) }.getOrElse((hole, k0))
    case _ => (expr(n), k0) // literals, vars, lambdas, blocks, ifs, matches: projected as-is (no placeholder descent)
  private def wrapArg(n: UniNode): String = n match
    case b: UniNode.Branch if b.kind == "spike.narg" => // label = value → Pair("narg", Pair(label, value))
      val name = kids(b).collectFirst { case (Some("narg.name"), c) => lexeme(c) }.getOrElse("_")
      val v    = kids(b).collectFirst { case (Some("narg.val"), c) => wrapArg(c) }.getOrElse(hole)
      s"""Pair("narg", Pair("${esc(name)}", $v))"""
    case _ =>
      if isBarePh(n) then expr(n)
      else if countPh(n) > 0 then
        val (body, cnt) = projectPh(n, 0)
        s"""mkLam(${consList((0 until cnt).map(i => s""""__u$i"""").toVector)}, $body)"""
      else expr(n)

  private def ifExpr(b: UniNode.Branch): String =
    val cnd = kids(b).collectFirst { case (Some("if.cond"), c) => expr(c) }.getOrElse(hole)
    val thn = kids(b).collectFirst { case (Some("if.thenE"), c) => expr(c) }.getOrElse(hole)
    // a missing `else` defaults to Unit (`mkTup(Nil)`), exactly like ssc1-front's parseIfExpr.
    val els = kids(b).collectFirst { case (Some("if.elseE"), c) => expr(c) }.getOrElse("mkTup(Nil)")
    s"""mkIf($cnd, $thn, $els)"""
