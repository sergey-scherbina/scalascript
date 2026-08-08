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
  case TInterp(raw: String, pos: Pos)
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
  /** Unicode uppercase, as SORTED RANGE PAIRS — the same table `UniAlphabet.upperRanges` carries,
    * and `v3/toolchain-gate.sh` asserts the two agree on every code point in the BMP.
    *
    * A COPY, deliberately, and the alternative was worse in both directions. Reaching into UniML
    * would break invariant I-1 — the kernel builds and runs with UniML absent, and every gate
    * depends on that. Calling `Character.isUpperCase` is what this file did for one day and it is
    * exactly what `20-core-language.md` §3 bans: route an alphabet through the host and the same
    * source lexes differently on the JVM, on JS and on the v2 VM, so the language's syntax becomes
    * host-dependent. The measurement that justified the host call — "they agree on every BMP code
    * point" — was taken ON THIS JVM, which is the guarantee the rule says not to rely on.
    *
    * A one-comparison rule in the style of `isIdStart` — "`A`–`Z` or anything ≥ U+0080" — was the
    * other candidate and is rejected on a cost this project's author pays directly: it makes
    * `case имя =>` a CONSTRUCTOR, so Cyrillic and Greek binders stop working in patterns. The table
    * is 606 ranges of data; that is cheaper than a language that cannot bind a Russian name. */
  private val upperRanges: Array[Int] = Array(
    192, 214, 216, 222, 256, 256, 258, 258, 260, 260, 262, 262, 264, 264, 266, 266,
    268, 268, 270, 270, 272, 272, 274, 274, 276, 276, 278, 278, 280, 280, 282, 282,
    284, 284, 286, 286, 288, 288, 290, 290, 292, 292, 294, 294, 296, 296, 298, 298,
    300, 300, 302, 302, 304, 304, 306, 306, 308, 308, 310, 310, 313, 313, 315, 315,
    317, 317, 319, 319, 321, 321, 323, 323, 325, 325, 327, 327, 330, 330, 332, 332,
    334, 334, 336, 336, 338, 338, 340, 340, 342, 342, 344, 344, 346, 346, 348, 348,
    350, 350, 352, 352, 354, 354, 356, 356, 358, 358, 360, 360, 362, 362, 364, 364,
    366, 366, 368, 368, 370, 370, 372, 372, 374, 374, 376, 377, 379, 379, 381, 381,
    385, 386, 388, 388, 390, 391, 393, 395, 398, 401, 403, 404, 406, 408, 412, 413,
    415, 416, 418, 418, 420, 420, 422, 423, 425, 425, 428, 428, 430, 431, 433, 435,
    437, 437, 439, 440, 444, 444, 452, 452, 455, 455, 458, 458, 461, 461, 463, 463,
    465, 465, 467, 467, 469, 469, 471, 471, 473, 473, 475, 475, 478, 478, 480, 480,
    482, 482, 484, 484, 486, 486, 488, 488, 490, 490, 492, 492, 494, 494, 497, 497,
    500, 500, 502, 504, 506, 506, 508, 508, 510, 510, 512, 512, 514, 514, 516, 516,
    518, 518, 520, 520, 522, 522, 524, 524, 526, 526, 528, 528, 530, 530, 532, 532,
    534, 534, 536, 536, 538, 538, 540, 540, 542, 542, 544, 544, 546, 546, 548, 548,
    550, 550, 552, 552, 554, 554, 556, 556, 558, 558, 560, 560, 562, 562, 570, 571,
    573, 574, 577, 577, 579, 582, 584, 584, 586, 586, 588, 588, 590, 590, 880, 880,
    882, 882, 886, 886, 895, 895, 902, 902, 904, 906, 908, 908, 910, 911, 913, 929,
    931, 939, 975, 975, 978, 980, 984, 984, 986, 986, 988, 988, 990, 990, 992, 992,
    994, 994, 996, 996, 998, 998, 1000, 1000, 1002, 1002, 1004, 1004, 1006, 1006, 1012, 1012,
    1015, 1015, 1017, 1018, 1021, 1071, 1120, 1120, 1122, 1122, 1124, 1124, 1126, 1126, 1128, 1128,
    1130, 1130, 1132, 1132, 1134, 1134, 1136, 1136, 1138, 1138, 1140, 1140, 1142, 1142, 1144, 1144,
    1146, 1146, 1148, 1148, 1150, 1150, 1152, 1152, 1162, 1162, 1164, 1164, 1166, 1166, 1168, 1168,
    1170, 1170, 1172, 1172, 1174, 1174, 1176, 1176, 1178, 1178, 1180, 1180, 1182, 1182, 1184, 1184,
    1186, 1186, 1188, 1188, 1190, 1190, 1192, 1192, 1194, 1194, 1196, 1196, 1198, 1198, 1200, 1200,
    1202, 1202, 1204, 1204, 1206, 1206, 1208, 1208, 1210, 1210, 1212, 1212, 1214, 1214, 1216, 1217,
    1219, 1219, 1221, 1221, 1223, 1223, 1225, 1225, 1227, 1227, 1229, 1229, 1232, 1232, 1234, 1234,
    1236, 1236, 1238, 1238, 1240, 1240, 1242, 1242, 1244, 1244, 1246, 1246, 1248, 1248, 1250, 1250,
    1252, 1252, 1254, 1254, 1256, 1256, 1258, 1258, 1260, 1260, 1262, 1262, 1264, 1264, 1266, 1266,
    1268, 1268, 1270, 1270, 1272, 1272, 1274, 1274, 1276, 1276, 1278, 1278, 1280, 1280, 1282, 1282,
    1284, 1284, 1286, 1286, 1288, 1288, 1290, 1290, 1292, 1292, 1294, 1294, 1296, 1296, 1298, 1298,
    1300, 1300, 1302, 1302, 1304, 1304, 1306, 1306, 1308, 1308, 1310, 1310, 1312, 1312, 1314, 1314,
    1316, 1316, 1318, 1318, 1320, 1320, 1322, 1322, 1324, 1324, 1326, 1326, 1329, 1366, 4256, 4293,
    4295, 4295, 4301, 4301, 5024, 5109, 7312, 7354, 7357, 7359, 7680, 7680, 7682, 7682, 7684, 7684,
    7686, 7686, 7688, 7688, 7690, 7690, 7692, 7692, 7694, 7694, 7696, 7696, 7698, 7698, 7700, 7700,
    7702, 7702, 7704, 7704, 7706, 7706, 7708, 7708, 7710, 7710, 7712, 7712, 7714, 7714, 7716, 7716,
    7718, 7718, 7720, 7720, 7722, 7722, 7724, 7724, 7726, 7726, 7728, 7728, 7730, 7730, 7732, 7732,
    7734, 7734, 7736, 7736, 7738, 7738, 7740, 7740, 7742, 7742, 7744, 7744, 7746, 7746, 7748, 7748,
    7750, 7750, 7752, 7752, 7754, 7754, 7756, 7756, 7758, 7758, 7760, 7760, 7762, 7762, 7764, 7764,
    7766, 7766, 7768, 7768, 7770, 7770, 7772, 7772, 7774, 7774, 7776, 7776, 7778, 7778, 7780, 7780,
    7782, 7782, 7784, 7784, 7786, 7786, 7788, 7788, 7790, 7790, 7792, 7792, 7794, 7794, 7796, 7796,
    7798, 7798, 7800, 7800, 7802, 7802, 7804, 7804, 7806, 7806, 7808, 7808, 7810, 7810, 7812, 7812,
    7814, 7814, 7816, 7816, 7818, 7818, 7820, 7820, 7822, 7822, 7824, 7824, 7826, 7826, 7828, 7828,
    7838, 7838, 7840, 7840, 7842, 7842, 7844, 7844, 7846, 7846, 7848, 7848, 7850, 7850, 7852, 7852,
    7854, 7854, 7856, 7856, 7858, 7858, 7860, 7860, 7862, 7862, 7864, 7864, 7866, 7866, 7868, 7868,
    7870, 7870, 7872, 7872, 7874, 7874, 7876, 7876, 7878, 7878, 7880, 7880, 7882, 7882, 7884, 7884,
    7886, 7886, 7888, 7888, 7890, 7890, 7892, 7892, 7894, 7894, 7896, 7896, 7898, 7898, 7900, 7900,
    7902, 7902, 7904, 7904, 7906, 7906, 7908, 7908, 7910, 7910, 7912, 7912, 7914, 7914, 7916, 7916,
    7918, 7918, 7920, 7920, 7922, 7922, 7924, 7924, 7926, 7926, 7928, 7928, 7930, 7930, 7932, 7932,
    7934, 7934, 7944, 7951, 7960, 7965, 7976, 7983, 7992, 7999, 8008, 8013, 8025, 8025, 8027, 8027,
    8029, 8029, 8031, 8031, 8040, 8047, 8120, 8123, 8136, 8139, 8152, 8155, 8168, 8172, 8184, 8187,
    8450, 8450, 8455, 8455, 8459, 8461, 8464, 8466, 8469, 8469, 8473, 8477, 8484, 8484, 8486, 8486,
    8488, 8488, 8490, 8493, 8496, 8499, 8510, 8511, 8517, 8517, 8544, 8559, 8579, 8579, 9398, 9423,
    11264, 11311, 11360, 11360, 11362, 11364, 11367, 11367, 11369, 11369, 11371, 11371, 11373, 11376, 11378, 11378,
    11381, 11381, 11390, 11392, 11394, 11394, 11396, 11396, 11398, 11398, 11400, 11400, 11402, 11402, 11404, 11404,
    11406, 11406, 11408, 11408, 11410, 11410, 11412, 11412, 11414, 11414, 11416, 11416, 11418, 11418, 11420, 11420,
    11422, 11422, 11424, 11424, 11426, 11426, 11428, 11428, 11430, 11430, 11432, 11432, 11434, 11434, 11436, 11436,
    11438, 11438, 11440, 11440, 11442, 11442, 11444, 11444, 11446, 11446, 11448, 11448, 11450, 11450, 11452, 11452,
    11454, 11454, 11456, 11456, 11458, 11458, 11460, 11460, 11462, 11462, 11464, 11464, 11466, 11466, 11468, 11468,
    11470, 11470, 11472, 11472, 11474, 11474, 11476, 11476, 11478, 11478, 11480, 11480, 11482, 11482, 11484, 11484,
    11486, 11486, 11488, 11488, 11490, 11490, 11499, 11499, 11501, 11501, 11506, 11506, 42560, 42560, 42562, 42562,
    42564, 42564, 42566, 42566, 42568, 42568, 42570, 42570, 42572, 42572, 42574, 42574, 42576, 42576, 42578, 42578,
    42580, 42580, 42582, 42582, 42584, 42584, 42586, 42586, 42588, 42588, 42590, 42590, 42592, 42592, 42594, 42594,
    42596, 42596, 42598, 42598, 42600, 42600, 42602, 42602, 42604, 42604, 42624, 42624, 42626, 42626, 42628, 42628,
    42630, 42630, 42632, 42632, 42634, 42634, 42636, 42636, 42638, 42638, 42640, 42640, 42642, 42642, 42644, 42644,
    42646, 42646, 42648, 42648, 42650, 42650, 42786, 42786, 42788, 42788, 42790, 42790, 42792, 42792, 42794, 42794,
    42796, 42796, 42798, 42798, 42802, 42802, 42804, 42804, 42806, 42806, 42808, 42808, 42810, 42810, 42812, 42812,
    42814, 42814, 42816, 42816, 42818, 42818, 42820, 42820, 42822, 42822, 42824, 42824, 42826, 42826, 42828, 42828,
    42830, 42830, 42832, 42832, 42834, 42834, 42836, 42836, 42838, 42838, 42840, 42840, 42842, 42842, 42844, 42844,
    42846, 42846, 42848, 42848, 42850, 42850, 42852, 42852, 42854, 42854, 42856, 42856, 42858, 42858, 42860, 42860,
    42862, 42862, 42873, 42873, 42875, 42875, 42877, 42878, 42880, 42880, 42882, 42882, 42884, 42884, 42886, 42886,
    42891, 42891, 42893, 42893, 42896, 42896, 42898, 42898, 42902, 42902, 42904, 42904, 42906, 42906, 42908, 42908,
    42910, 42910, 42912, 42912, 42914, 42914, 42916, 42916, 42918, 42918, 42920, 42920, 42922, 42926, 42928, 42932,
    42934, 42934, 42936, 42936, 42938, 42938, 42940, 42940, 42942, 42942, 42944, 42944, 42946, 42946, 42948, 42951,
    42953, 42953, 42960, 42960, 42966, 42966, 42968, 42968, 42997, 42997, 65313, 65338
  )

  /** Does this name start a CONSTRUCTOR rather than a binder?
    *
    * Scala's rule is capitalisation, and the parser used to spell it `>= 'A' && <= 'Z'` — ASCII
    * only. Every non-ASCII name therefore read as lowercase, so `case Éric =>` was a BINDER that
    * matched everything and printed `BOUND 1` where Scala 3 says `not found: value Éric` and
    * UniML's front refuses. A pattern that silently matches everything is the worst answer of the
    * three this shape produces across lanes (`BUGS.md`,
    * `an-undefined-name-in-a-pattern-means-three-different-things`), and it was v3's.
    *
    * A RANGE TABLE, not the host's. The first version called `Character.isUpperCase` and justified
    * it by measuring agreement with UniML's table over the BMP — a measurement taken on this JVM,
    * which is precisely the guarantee `20-core-language.md` §3 says not to rely on. The two fronts
    * are kept in step by `toolchain-gate.sh` sweeping the BMP instead, which is a check rather
    * than a hope. */
  def isUpperStart(c: Char): Boolean =
    (c >= 'A' && c <= 'Z') || (c >= 128 && inUpperRanges(c.toInt))

  private def inUpperRanges(cp: Int): Boolean =
    var lo = 0
    var hi = upperRanges.length / 2 - 1
    var found = false
    while lo <= hi && !found do
      val mid = (lo + hi) / 2
      if cp < upperRanges(mid * 2) then hi = mid - 1
      else if cp > upperRanges(mid * 2 + 1) then lo = mid + 1
      else found = true
    found
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
      // `s"…"` — an identifier immediately followed by a quote is an interpolator. `f` and `raw`
      // lex the same way and are refused later BY NAME, rather than being silently treated as `s`.
      if !done(s) && at(s) == '"' && (text == "s" || text == "f" || text == "raw") then
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
        if text != "s" then throw LexError(p, "the `" + text + "` interpolator is outside SSC3 core Tier 0; `s` is supported")
        (Tok.TInterp(raw, p), t)
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
    else if c == '(' || c == ')' || c == ',' || c == ':' || c == '.' || c == ';' ||
            c == '{' || c == '}' || c == '[' || c == ']' then
      (Tok.TPunct(c.toString, p), adv(s0))
    else throw LexError(p, "unexpected character '" + c + "'")

  def show(t: Tok): String = t match
    case Tok.TInt(t, _)   => t
    case Tok.TFloat(t, _) => t
    case Tok.TStr(v, _)   => "\"" + v + "\""
    case Tok.TChar(c, _)  => "'" + c.toChar + "'"
    case Tok.TInterp(v, _) => "s\"" + v + "\""
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
    case Tok.TInterp(_, p) => p
    case Tok.TId(_, p)    => p
    case Tok.TOp(_, p)    => p
    case Tok.TPunct(_, p) => p
    case Tok.TNewline(p)  => p
    case Tok.TIndent(p)   => p
    case Tok.TDedent(p)   => p
    case Tok.TEof(p)      => p
