package scalascript.uniml

/** The lexical alphabet: character classification without host tables.
  *
  * Decided in `v3/specs/20-core-language.md` §3 and required of UniML by
  * `v3/specs/40-front-on-uniml.md` §4 — "the lexer may not use host `Char` classification".
  *
  * **Why this is a compiler question rather than tidiness.** `Character.isLetter` answers from
  * Unicode tables. Route an alphabet through the host and the same source lexes differently on the
  * JVM, on JS and on the v2 VM, which makes the language's syntax host-dependent. Every predicate
  * here is a range comparison, so there is no table on any host and no way for the hosts to
  * disagree.
  *
  * **What belongs here and what deliberately does not.** Whitespace, digits and the identifier
  * classes are one thing across every dialect, so they live here. An OPERATOR set is not: it is a
  * property of the language being modelled, and this repository currently has three different ones
  * (§3's table, `v3/src/Lexer.scala`'s `Chars.isOpChar`, and the ScalaScript dialect's). [[opChar]]
  * is §3's, named for the language it belongs to; a dialect modelling a different language defines
  * its own and says so. Centralising things that merely look alike is how a shared helper acquires
  * two callers that need it to behave differently.
  */
object UniAlphabet:

  /** Space, tab, CR, LF, FF — and nothing else. */
  def isWhitespace(char: Char): Boolean =
    char == ' ' || char == '\t' || char == '\n' || char == '\r' || char == '\f'

  def isDigit(char: Char): Boolean = char >= '0' && char <= '9'

  def isOctDigit(char: Char): Boolean = char >= '0' && char <= '7'

  def isHexDigit(char: Char): Boolean =
    isDigit(char) || (char >= 'a' && char <= 'f') || (char >= 'A' && char <= 'F')

  def isAsciiUpper(char: Char): Boolean = char >= 'A' && char <= 'Z'

  def isAsciiLower(char: Char): Boolean = char >= 'a' && char <= 'z'

  def isAsciiLetter(char: Char): Boolean = isAsciiLower(char) || isAsciiUpper(char)

  def isAsciiAlnum(char: Char): Boolean = isAsciiLetter(char) || isDigit(char)

  /** At or above U+0080. The one comparison that stands in for a Unicode letter table.
    *
    * Exposed as a primitive because dialects compose their own identifier rules from it — the
    * ScalaScript dialect's excludes `$` so that interpolation can see it — and the alternative is
    * every dialect writing `>= 128` with its own idea of the boundary.
    */
  def isNonAscii(char: Char): Boolean = char >= 128

  /** `a`-`z`, `A`-`Z`, `_`, `$`, or any code point >= U+0080.
    *
    * The last clause is one comparison where a Unicode letter test is a table, and it accepts
    * identifiers in any script. It is MORE permissive than Scala, which is the safe direction for a
    * compatibility lane: every valid Scala identifier is still an identifier here, so no existing
    * program changes meaning — only programs Scala rejects are additionally accepted.
    * `UniAlphabetSweepSpec` proves that direction over the whole `Char` range rather than asserting
    * it in prose.
    */
  def isIdStart(char: Char): Boolean =
    isAsciiLetter(char) || char == '_' || char == '$' || char >= 128

  def isIdPart(char: Char): Boolean = isIdStart(char) || isDigit(char)

  /** SSC3's operator characters, from §3's table. Read the class comment before reusing this for a
    * dialect that models some other language: it is 17 characters and the two neighbouring
    * implementations in this repository use 13 and 15.
    */
  def isOpChar(char: Char): Boolean =
    char == '+' || char == '-' || char == '*' || char == '/' || char == '%' ||
      char == '<' || char == '>' || char == '=' || char == '!' || char == '&' ||
      char == '|' || char == '^' || char == '~' || char == ':' || char == '#' ||
      char == '@' || char == '?'

  /** Case has NO tableless definition beyond ASCII, and §3's table does not give it one.
    *
    * This matters because a Scala-family dialect decides "type or term" on the initial letter's
    * case. Over ASCII this agrees with the host exactly; at or above U+0080 it answers `false` for
    * every character, so `Число` begins with a non-upper character here and with an upper one in
    * Scala. **Unlike [[isIdStart]] that diverges in the UNSAFE direction** — a valid Scala program
    * changes meaning rather than an invalid one becoming valid — so it is a language decision, not
    * an implementation detail. `UniAlphabetSweepSpec` counts the affected code points; the count is
    * in the test rather than this comment so it cannot go stale.
    */
  def isTypeNameStart(char: Char): Boolean = isAsciiUpper(char)
