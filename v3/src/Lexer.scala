package ssc3

// The SSC3 lexer.
//
// Character classification is OURS — v3/specs/20-core-language.md §3. Every class is a range
// comparison and there is no table on any host, which is what makes the portable subset's ban on
// `isLetter`/`isDigit`/`isWhitespace` cost nothing rather than cost a Unicode database.
//
// Indentation is significant, so the token stream carries INDENT/DEDENT. Doing it here rather than
// in the parser is the usual split and the reason the parser can stay a plain recursive descent.

enum Tok:
  /** The DIGITS, not the value. `-9223372036854775808` is Long.MinValue and its digit string is
    * 2^63, which overflows on its own — the minus is a separate token and belongs to the parser.
    * Converting here crashed the front on two corpus cases with a raw NumberFormatException. */
  case TInt(text: String, pos: Pos)
  case TFloat(text: String, pos: Pos)
  case TStr(v: String, pos: Pos)
  case TId(s: String, pos: Pos)
  case TOp(s: String, pos: Pos)
  case TPunct(s: String, pos: Pos)
  case TNewline(pos: Pos)
  case TIndent(pos: Pos)
  case TDedent(pos: Pos)
  case TEof(pos: Pos)

final case class LexError(pos: Pos, message: String)
    extends RuntimeException(pos.show + ": " + message)

object Chars:
  // The five whitespace characters SSC3 recognises, and no others.
  def isSpace(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f'
  def isDigit(c: Char): Boolean = c >= '0' && c <= '9'
  /** `>= U+0080` is deliberate: one comparison where a Unicode letter test is a table, and it
    * accepts identifiers in any script. More permissive than Scala, which is the SAFE direction for
    * a compatibility lane — every valid Scala identifier is still one here. */
  def isIdStart(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$' || c >= 128
  def isIdPart(c: Char): Boolean = isIdStart(c) || isDigit(c)
  def isOpChar(c: Char): Boolean =
    c == '+' || c == '-' || c == '*' || c == '/' || c == '%' || c == '<' || c == '>' ||
      c == '=' || c == '!' || c == '&' || c == '|' || c == '^' || c == '~'

object Lexer:

  private final case class St(src: String, pos: Int, line: Int, col: Int)

  private def at(s: St): Char = s.src.charAt(s.pos)
  private def done(s: St): Boolean = s.pos >= s.src.length
  private def here(s: St): Pos = Pos(s.line, s.col)
  private def adv(s: St): St =
    if done(s) then s
    else if at(s) == '\n' then St(s.src, s.pos + 1, s.line + 1, 1)
    else St(s.src, s.pos + 1, s.line, s.col + 1)

  def lex(src: String): List[Tok] =
    var s = St(src, 0, 1, 1)
    var out: List[Tok] = Nil
    var indents: List[Int] = List(0)
    var atLineStart = true
    var emittedOnLine = false
    // Depth of open `(` and `[`. Inside them a line break is NOT a statement boundary — a call's
    // arguments and a collection literal routinely span lines — so layout tokens are suppressed.
    // `{` is deliberately NOT counted: a brace block's newlines DO separate statements.
    var round = 0

    while !done(s) do
      if atLineStart && round > 0 then
        // Continuation line inside brackets: no INDENT, no DEDENT, no NEWLINE. Skipping the
        // indentation entirely is the point — its width means nothing here.
        var t = s
        while !done(t) && (at(t) == ' ' || at(t) == '\t') do t = adv(t)
        s = t
        atLineStart = false
      else if atLineStart then
        // Measure the indentation, then decide. A blank or comment-only line has no indentation to
        // speak of and must not close a block — that would make an empty line a DEDENT, which is
        // the classic way indentation lexers turn formatting into syntax.
        var width = 0
        var t = s
        while !done(t) && (at(t) == ' ' || at(t) == '\t') do
          width = width + (if at(t) == '\t' then 8 - (width % 8) else 1)
          t = adv(t)
        val blank = done(t) || at(t) == '\n' || at(t) == '\r' || isCommentStart(t)
        if blank then
          // CONSUME the newline as well. `skipToLineEnd` stops AT it, so assigning its result here
          // left the position unmoved and the outer loop spun forever on a blank line between two
          // definitions. It presented as an empty result at exit 0 — the timeout, not a refusal —
          // which is why the front gate below runs a program with a blank line in it.
          s = adv(skipToLineEnd(t))
        else
          s = t
          val cur = indents.head
          if width > cur then
            indents = width :: indents
            out = Tok.TIndent(here(s)) :: out
          else if width < cur then
            while indents.nonEmpty && width < indents.head do
              indents = indents.tail
              out = Tok.TDedent(here(s)) :: out
            if indents.isEmpty || indents.head != width then
              throw LexError(here(s), "dedent to column " + width + " matches no enclosing block")
          atLineStart = false
          emittedOnLine = false
      else if done(s) then ()
      else
        val c = at(s)
        if c == '\n' then
          if emittedOnLine && round == 0 then out = Tok.TNewline(here(s)) :: out
          s = adv(s)
          atLineStart = true
        else if c == ' ' || c == '\t' || c == '\r' then s = adv(s)
        else if isCommentStart(s) then s = skipToLineEnd(s)
        else
          val (tok, s2) = one(s)
          tok match
            case Tok.TPunct("(", _) => round = round + 1
            case Tok.TPunct("[", _) => round = round + 1
            case Tok.TPunct(")", _) => if round > 0 then round = round - 1
            case Tok.TPunct("]", _) => if round > 0 then round = round - 1
            case _                  => ()
          out = tok :: out
          s = s2
          emittedOnLine = true

    if emittedOnLine then out = Tok.TNewline(here(s)) :: out
    while indents.nonEmpty && indents.head > 0 do
      indents = indents.tail
      out = Tok.TDedent(here(s)) :: out
    (Tok.TEof(here(s)) :: out).reverse

  private def isCommentStart(s: St): Boolean =
    !done(s) && at(s) == '/' && s.pos + 1 < s.src.length && s.src.charAt(s.pos + 1) == '/'

  private def skipToLineEnd(s0: St): St =
    var s = s0
    while !done(s) && at(s) != '\n' do s = adv(s)
    s

  private def one(s0: St): (Tok, St) =
    val p = here(s0)
    val c = at(s0)
    if Chars.isDigit(c) then
      var s = s0
      var text = ""
      while !done(s) && Chars.isDigit(at(s)) do
        text = text + at(s); s = adv(s)
      // A FRACTION, only when a digit follows the dot. `1.5` is a number; `1.toString` is a method
      // call on a number, and the difference is exactly the character after the `.`.
      var isFloat = false
      if !done(s) && at(s) == '.' && s.pos + 1 < s.src.length && Chars.isDigit(s.src.charAt(s.pos + 1)) then
        isFloat = true
        text = text + "."
        s = adv(s)
        while !done(s) && Chars.isDigit(at(s)) do
          text = text + at(s); s = adv(s)
      // Width suffixes. `Int` is already 64-bit here, so `L` carries no information and is simply
      // consumed — but it must be consumed, or it lexes as an identifier and every `123L` in the
      // corpus becomes a syntax error. It was 87 of them.
      if !done(s) && (at(s) == 'L' || at(s) == 'l') then s = adv(s)
      else if !done(s) && (at(s) == 'd' || at(s) == 'D' || at(s) == 'f' || at(s) == 'F') then
        isFloat = true
        s = adv(s)
      if isFloat then (Tok.TFloat(text, p), s) else (Tok.TInt(text, p), s)
    else if Chars.isIdStart(c) then
      var s = s0
      var text = ""
      while !done(s) && Chars.isIdPart(at(s)) do
        text = text + at(s); s = adv(s)
      (Tok.TId(text, p), s)
    else if c == '"' then
      var s = adv(s0)
      var text = ""
      var closed = false
      while !closed do
        if done(s) || at(s) == '\n' then throw LexError(p, "unterminated string literal")
        else if at(s) == '"' then
          s = adv(s); closed = true
        else if at(s) == '\\' then
          val e = adv(s)
          if done(e) then throw LexError(p, "dangling escape in string literal")
          val ch = at(e)
          text = text + (if ch == 'n' then "\n" else if ch == 't' then "\t"
                         else if ch == 'r' then "\r" else ch.toString)
          s = adv(e)
        else
          text = text + at(s); s = adv(s)
      (Tok.TStr(text, p), s)
    else if Chars.isOpChar(c) then
      // Longest-match on operator characters, so `<=` never lexes as `<` then `=`. Doing it by
      // maximal munch rather than by a table of known pairs means a new operator needs no edit here.
      var s = s0
      var text = ""
      while !done(s) && Chars.isOpChar(at(s)) do
        text = text + at(s); s = adv(s)
      (Tok.TOp(text, p), s)
    else if c == '(' || c == ')' || c == ',' || c == ':' || c == '.' || c == ';' ||
            c == '{' || c == '}' || c == '[' || c == ']' then
      (Tok.TPunct(c.toString, p), adv(s0))
    else throw LexError(p, "unexpected character '" + c + "'")

  def show(t: Tok): String = t match
    case Tok.TInt(t, _)   => t
    case Tok.TFloat(t, _) => t
    case Tok.TStr(v, _)   => "\"" + v + "\""
    case Tok.TId(s, _)    => s
    case Tok.TOp(s, _)    => s
    case Tok.TPunct(s, _) => s
    case Tok.TNewline(_)  => "<newline>"
    case Tok.TIndent(_)   => "<indent>"
    case Tok.TDedent(_)   => "<dedent>"
    case Tok.TEof(_)      => "<end of input>"

  def posOf(t: Tok): Pos = t match
    case Tok.TInt(_, p)   => p
    case Tok.TFloat(_, p) => p
    case Tok.TStr(_, p)   => p
    case Tok.TId(_, p)    => p
    case Tok.TOp(_, p)    => p
    case Tok.TPunct(_, p) => p
    case Tok.TNewline(p)  => p
    case Tok.TIndent(p)   => p
    case Tok.TDedent(p)   => p
    case Tok.TEof(p)      => p
