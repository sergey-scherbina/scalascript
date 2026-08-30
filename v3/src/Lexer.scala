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
  /** The CODE POINT, not a Char, because that is what it is on the reference lane: v2 stores a
    * char as `CharV extends IntV` — an integer that prints differently. `'x' + 1` is 121 there. */
  case TChar(code: Int, pos: Pos)
  /** `s"…"` — the RAW content, unescaped-but-unsplit. Splitting it needs the expression parser,
    * which the lexer does not have, so it hands the whole thing over. */
  /** An interpolated string AND ITS PREFIX. The prefix used to be discarded and every one but `s`
    * refused here, which is what made an interpolator a kernel feature: a program could not define
    * one. It is carried now so the parser can turn `pfx"…"` into an ordinary CALL — see
    * `Parser.interpCallFor`. */
  case TInterp(prefix: String, raw: String, pos: Pos)
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

  /** Does this name start a CONSTRUCTOR rather than a binder?
    *
    * Scala's rule is capitalisation, and the parser used to spell it `>= 'A' && <= 'Z'` — ASCII
    * only. Every non-ASCII name therefore read as lowercase, so `case Éric =>` was a BINDER that
    * matched everything and printed `BOUND 1` where Scala 3 says `not found: value Éric` and
    * UniML's front refuses. A pattern that silently matches everything is the worst answer of the
    * three this shape produces across lanes (`BUGS.md`,
    * `an-undefined-name-in-a-pattern-means-three-different-things`), and it was v3's.
    *
    * DELEGATED to `alphabet/src/Alphabet.scala` — ONE copy, compiled into this kernel and into
    * UniML, so the two fronts cannot disagree about it rather than being watched for disagreeing.
    * It is a source directory `v3/ssc3` adds to the kernel's compile, not a library: invariant I-1
    * holds, and self-hosting still means "compile these repo files".
    *
    * Two wrong turns before that, both recorded because the shape recurs. First
    * `Character.isUpperCase`, justified by measuring agreement with UniML over the BMP — on one
    * JVM, which is exactly the guarantee `20-core-language.md` §3 says not to rely on. Then a
    * hand-copied table with a gate sweeping for drift, which is worth having only while sharing is
    * impossible. */
  def isUpperStart(c: Char): Boolean = scalascript.alphabet.Alphabet.isUpperStart(c)

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
        val blank = done(t) || at(t) == '\n' || at(t) == '\r' || isCommentStart(t) ||
                    isBlockCommentStart(t)
        if blank then
          // CONSUME the newline as well. `skipToLineEnd` stops AT it, so assigning its result here
          // left the position unmoved and the outer loop spun forever on a blank line between two
          // definitions. It presented as an empty result at exit 0 — the timeout, not a refusal —
          // which is why the front gate below runs a program with a blank line in it.
          // A BLOCK comment is skipped to its end, which may be many lines down; a line comment
          // and a blank line end at the newline. Both then consume that newline, because
          // `skipToLineEnd` stops AT it and leaving it made the outer loop spin.
          s = if isBlockCommentStart(t) then adv(skipToLineEnd(skipBlockComment(t)))
              else adv(skipToLineEnd(t))
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
        else if isBlockCommentStart(s) then s = skipBlockComment(s)
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

  private def isBlockCommentStart(s: St): Boolean =
    !done(s) && at(s) == '/' && s.pos + 1 < s.src.length && s.src.charAt(s.pos + 1) == '*'

  // Block comments, NESTED, because Scala nests them and a non-nesting scanner ends the outer
  // comment at the first inner close and then lexes prose as code.
  //
  // Their absence was the largest single refusal in the corpus at 116 cases - every one of them
  // one doc comment in one imported module, and the diagnostic blamed the APOSTROPHE in the
  // English word "journal's", which the lexer read as the start of a character literal. A missing
  // comment form does not announce itself; it announces whatever it stumbles into first.
  //
  // Written with line comments on purpose: a doc comment describing this cannot quote the closing
  // delimiter without ending itself, which is how the first attempt failed to compile.
  private def skipBlockComment(s0: St): St =
    var s = adv(adv(s0))
    var depth = 1
    while depth > 0 && !done(s) do
      if at(s) == '/' && s.pos + 1 < s.src.length && s.src.charAt(s.pos + 1) == '*' then
        depth = depth + 1; s = adv(adv(s))
      else if at(s) == '*' && s.pos + 1 < s.src.length && s.src.charAt(s.pos + 1) == '/' then
        depth = depth - 1; s = adv(adv(s))
      else s = adv(s)
    s

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
      // AN UNDERSCORE IS A DIGIT GROUPER AND IS DROPPED. `30_000` is 30000 — Scala's rule, and the
      // corpus writes it: `std/actors.ssc:382` declares `overlapMs: Int = 30_000`. Without this the
      // lexer stopped at the `_`, the parser reported `expected ')', found _000`, and two
      // conformance cases were refused by v3's front while the UniML front took them — a divergence
      // the capability gate reports as NEW, which is how this was found.
      //
      // Only BETWEEN digits: a trailing `_` would take the following identifier's first character
      // with it, and `1_` is not a literal in Scala either.
      while !done(s) && (Chars.isDigit(at(s)) ||
                         (at(s) == '_' && s.pos + 1 < s.src.length && Chars.isDigit(s.src.charAt(s.pos + 1)))) do
        if at(s) != '_' then text = text + at(s)
        s = adv(s)
      // A FRACTION, only when a digit follows the dot. `1.5` is a number; `1.toString` is a method
      // call on a number, and the difference is exactly the character after the `.`.
      var isFloat = false
      if !done(s) && at(s) == '.' && s.pos + 1 < s.src.length && Chars.isDigit(s.src.charAt(s.pos + 1)) then
        isFloat = true
        text = text + "."
        s = adv(s)
        while !done(s) && Chars.isDigit(at(s)) do
          text = text + at(s); s = adv(s)
      // An EXPONENT: `1.0e10`, `2.5E3`, `1e-3`. It makes the literal a FLOAT even without a dot,
      // which is why the flag is set here rather than only by the fraction above — `1e-3` has no
      // `.` and is 0.001. Measured on v1, which accepts all three forms.
      //
      // The digit after `e`/`E` (or after its sign) is REQUIRED, so `1.toEngine` still lexes as a
      // number followed by a method call: the character after the `e` is what tells them apart, the
      // same rule the fraction uses for the dot.
      if !done(s) && (at(s) == 'e' || at(s) == 'E') then
        val signAt = s.pos + 1
        val hasSign = signAt < s.src.length &&
                      (s.src.charAt(signAt) == '+' || s.src.charAt(signAt) == '-')
        val digitAt = if hasSign then signAt + 1 else signAt
        if digitAt < s.src.length && Chars.isDigit(s.src.charAt(digitAt)) then
          isFloat = true
          text = text + at(s); s = adv(s)
          if hasSign then
            text = text + at(s); s = adv(s)
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
      // ANY identifier immediately followed by a quote is an interpolator — which is what the rule
      // always said, while the code allowed three names. The list was harmless when every prefix but
      // `s` was refused anyway; once a prefix became an ordinary CALL it turned into a divergence,
      // because uniml accepts any word here and v3's own front lexed `tag"…"` as an identifier and a
      // separate string. `front-diff` caught it on the first run, on the fixture added with this
      // change and on no corpus case.
      //
      // ADJACENCY IS THE WHOLE TEST, and it is enough: a name touching a quote with nothing between
      // them has no other meaning in this language. A line break between them is not adjacency —
      // that is the same-line rule the uniml lexer needed on 2026-08-16.
      // A TRIPLE-QUOTED INTERPOLATOR — `html"""<li>${it}</li>"""`, and `md"""` spanning lines. Raw
      // like the plain triple-quoted string below, with no escapes, and `${…}` still applies: the
      // holes are found by the same parser that reads a single-quoted one, so the two spellings
      // cannot drift. Missing this arm was not a hypothetical — broadening the prefix rule without
      // it made v3's own front stop reading `content` and both `std-ui-native-html-lambda` cases,
      // which `front-diff` counted as three files moving to uniml-only.
      if !done(s) && at(s) == '"' && !done(adv(s)) && at(adv(s)) == '"'
         && !done(adv(adv(s))) && at(adv(adv(s))) == '"' then
        var t = adv(adv(adv(s)))
        var raw = ""
        var closed = false
        while !closed do
          if done(t) then throw LexError(p, "unterminated triple-quoted interpolated string")
          else if at(t) == '"' && !done(adv(t)) && at(adv(t)) == '"'
                  && !done(adv(adv(t))) && at(adv(adv(t))) == '"' then
            t = adv(adv(adv(t))); closed = true
          else
            raw = raw + at(t); t = adv(t)
        (Tok.TInterp(text, raw, p), t)
      else if !done(s) && at(s) == '"' then
        var t = adv(s)
        var raw = ""
        var closed = false
        // Depth of open `${`. A quote only CLOSES the string at depth 0 — inside a hole it belongs
        // to a nested string literal, as in `s"x ${if c then "a" else "b"} y"`. Stopping at the
        // first quote regardless is the obvious implementation and truncates every such string.
        var hole = 0
        while !closed do
          if done(t) || at(t) == '\n' then throw LexError(p, "unterminated interpolated string")
          else if at(t) == '$' && !done(adv(t)) && at(adv(t)) == '{' then
            hole = hole + 1
            raw = raw + "${"
            t = adv(adv(t))
          else if at(t) == '}' && hole > 0 then
            hole = hole - 1
            raw = raw + "}"
            t = adv(t)
          else if at(t) == '"' && hole == 0 then
            t = adv(t); closed = true
          else if at(t) == '\\' then
            val e = adv(t)
            if done(e) then throw LexError(p, "dangling escape in an interpolated string")
            val ch = at(e)
            raw = raw + (if ch == 'n' then "\n" else if ch == 't' then "\t"
                         else if ch == 'r' then "\r" else ch.toString)
            t = adv(e)
          else
            raw = raw + at(t); t = adv(t)
        // NO REFUSAL BY PREFIX. `s` stays the built-in one; any other prefix becomes a call to a
        // function of that name, so `html"…"` is defined in a library rather than in the kernel.
        (Tok.TInterp(text, raw, p), t)
      else (Tok.TId(text, p), s)
    // `'x'` — a CHARACTER literal. Told apart from nothing: `'` has no other use in the language,
    // so there is no ambiguity to resolve (Scala's `'sym` and type-parameter ticks are not Tier 0).
    else if c == '\'' then
      var s = adv(s0)
      if done(s) then throw LexError(p, "unterminated character literal")
      var code = 0
      if at(s) == '\\' then
        val e = adv(s)
        if done(e) then throw LexError(p, "dangling escape in character literal")
        val ch = at(e)
        code = (if ch == 'n' then '\n' else if ch == 't' then '\t' else if ch == 'r' then '\r'
                else if ch == '0' then 0.toChar else ch).toInt
        s = adv(e)
      else
        code = at(s).toInt
        s = adv(s)
      if done(s) || at(s) != '\'' then throw LexError(p, "unterminated character literal")
      (Tok.TChar(code, p), adv(s))
    // A TRIPLE-QUOTED string: raw, may span lines, no escapes. Without it `"""{"user":…}"""` lexed
    // as the empty string `""`, then `{`, then a run of tokens from the JSON inside, and one `val`
    // became twenty-one statements — a well-formed program saying something the source did not.
    // `json-value` and `json-lookup` came out that way; the UniML front, which has the form, is
    // what made the difference visible.
    else if c == '"' && !done(adv(s0)) && at(adv(s0)) == '"'
            && !done(adv(adv(s0))) && at(adv(adv(s0))) == '"' then
      var s = adv(adv(adv(s0)))
      var text = ""
      var closed = false
      while !closed do
        if done(s) then throw LexError(p, "unterminated triple-quoted string literal")
        else if at(s) == '"' && !done(adv(s)) && at(adv(s)) == '"'
                && !done(adv(adv(s))) && at(adv(adv(s))) == '"' then
          s = adv(adv(adv(s))); closed = true
        else
          text = text + at(s); s = adv(s)
      (Tok.TStr(text, p), s)
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
      // `:` is admitted INSIDE the munch but never starts one here — that is what separates `+:`
      // (one operator) from `f(x)` followed by `: Int` (an operator, then a type ascription). The
      // ascription colon always follows an identifier, a literal or a bracket, never an operator.
      while !done(s) && (Chars.isOpChar(at(s)) || at(s) == ':') do
        text = text + at(s); s = adv(s)
      (Tok.TOp(text, p), s)
    // A `:` that STARTS an operator (`::`, `:+`, `:=`) versus a bare `:`, which is punctuation
    // introducing a type. Making `:` an ordinary operator character would have been simpler and
    // would turn every `x: Int` into an infix application, so the two are told apart by LOOKAHEAD:
    // a `:` followed by another operator character begins an operator, and is then lexed by the
    // same maximal munch as everything else rather than by a table of known pairs.
    //
    // `::` used to be special-cased alone. Measured 2026-08-04: `:+` was 116 of the 126 cases in
    // the corpus's largest refusal bucket — all of them one line of one heavily-imported std
    // module. The bucket read as `expected an expression, found :`, which named the symptom.
    else if c == ':' && s0.pos + 1 < s0.src.length &&
            (Chars.isOpChar(s0.src.charAt(s0.pos + 1)) || s0.src.charAt(s0.pos + 1) == ':') then
      var s = s0
      var text = ""
      while !done(s) && (Chars.isOpChar(at(s)) || at(s) == ':') do
        text = text + at(s); s = adv(s)
      (Tok.TOp(text, p), s)
    // `@` is PUNCTUATION and not an operator character, so `@tailrec` lexes as two tokens and an
    // annotation never fuses with a neighbouring symbol by maximal munch. Nothing here decides what
    // it MEANS — the parser skips whole annotations before it dispatches on a declaration keyword.
    else if c == '(' || c == ')' || c == ',' || c == ':' || c == '.' || c == ';' ||
            c == '{' || c == '}' || c == '[' || c == ']' || c == '@' then
      (Tok.TPunct(c.toString, p), adv(s0))
    else throw LexError(p, "unexpected character '" + c + "'")

  def show(t: Tok): String = t match
    case Tok.TInt(t, _)   => t
    case Tok.TFloat(t, _) => t
    case Tok.TStr(v, _)   => "\"" + v + "\""
    case Tok.TChar(c, _)  => "'" + c.toChar + "'"
    case Tok.TInterp(pfx, v, _) => pfx + "\"" + v + "\""
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
    case Tok.TChar(_, p)  => p
    case Tok.TInterp(_, _, p) => p
    case Tok.TId(_, p)    => p
    case Tok.TOp(_, p)    => p
    case Tok.TPunct(_, p) => p
    case Tok.TNewline(p)  => p
    case Tok.TIndent(p)   => p
    case Tok.TDedent(p)   => p
    case Tok.TEof(p)      => p
