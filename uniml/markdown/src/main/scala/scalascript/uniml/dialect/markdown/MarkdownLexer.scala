package scalascript.uniml.dialect.markdown

import scalascript.uniml.*

/** Stable token kinds. All use the `markdown.` prefix so downstream tooling can
  * classify without parsing lexemes. */
private[markdown] object MdKind:
  // line / container
  val Indent = "markdown.indent"
  val LineBreak = "markdown.line-break"
  val Blank = "markdown.blank"
  val BlockquoteMarker = "markdown.blockquote-marker"
  val ListMarker = "markdown.list-marker"
  // block
  val AtxMarker = "markdown.atx-marker"
  val AtxClose = "markdown.atx-close"
  val SetextUnderline = "markdown.setext-underline"
  val ThematicMarker = "markdown.thematic-marker"
  val FenceOpen = "markdown.fence-open"
  val FenceClose = "markdown.fence-close"
  val Info = "markdown.info"
  val CodeContent = "markdown.code-content"
  val Html = "markdown.html"
  val FrontMatterFence = "markdown.front-matter-fence"
  // inline
  val Text = "markdown.text"
  val Escape = "markdown.escape"
  val Entity = "markdown.entity"
  val DelimiterRun = "markdown.delimiter-run"
  val BacktickRun = "markdown.backtick-run"
  val LinkOpen = "markdown.link-open"
  val LinkClose = "markdown.link-close"
  val DestOpen = "markdown.dest-open"
  val Destination = "markdown.destination"
  val Title = "markdown.title"
  val DestClose = "markdown.dest-close"
  val ReferenceLabel = "markdown.reference-label"
  val Colon = "markdown.colon"
  val Autolink = "markdown.autolink"
  // embedded
  val ExpressionOpen = "markdown.expression-open"
  val ExpressionContent = "markdown.expression-content"
  val ExpressionClose = "markdown.expression-close"
  // gfm
  val TablePipe = "markdown.table-pipe"
  val TableRow = "markdown.table-row"
  val TableDelim = "markdown.table-delim"
  val TaskMarker = "markdown.task-marker"
  val StrikethroughRun = "markdown.strikethrough-run"
  // breaks
  val SoftBreak = "markdown.soft-break"
  val HardBreak = "markdown.hard-break"

/** Branch (CST node) kinds. */
private[markdown] object MdBranch:
  val Heading = "markdown.heading"
  val Paragraph = "markdown.paragraph"
  val Blockquote = "markdown.blockquote"
  val List = "markdown.list"
  val ListItem = "markdown.list-item"
  val CodeBlock = "markdown.code-block"
  val HtmlBlock = "markdown.html-block"
  val Definition = "markdown.definition"
  val Table = "markdown.table"
  val TableRow = "markdown.table-row"
  val ThematicBreak = "markdown.thematic-break"
  val FrontMatter = "markdown.front-matter"
  val Emphasis = "markdown.emphasis"
  val Strong = "markdown.strong"
  val Strikethrough = "markdown.strikethrough"
  val CodeSpan = "markdown.code-span"
  val Link = "markdown.link"
  val Image = "markdown.image"
  val Expression = "markdown.expression"

/** One physical source line: exact content plus its exact ending spelling
  * (`""` only for a final line with no trailing newline). */
private[markdown] final case class MdLine(content: String, ending: String):
  def raw: String = content + ending
  def isBlank: Boolean = content.forall(c => c == ' ' || c == '\t')

private[markdown] object MdLine:
  /** Longest run of a delimiter character (`*`, `_`, `` ` ``, `~`) in `s` — the quantity
    * `maxDelimiterRun` bounds. Counted per line because a run cannot cross a line ending. */
  def longestRun(s: String): Int =
    def runEnd(j: Int, c: Char): Int =
      if j < s.length && s.charAt(j) == c then runEnd(j + 1, c) else j
    def scan(i: Int, best: Int): Int =
      if i >= s.length then best
      else
        val c = s.charAt(i)
        if c == '*' || c == '_' || c == '`' || c == '~' then
          val j = runEnd(i + 1, c)
          scan(j, if j - i > best then j - i else best)
        else scan(i + 1, best)
    scan(0, 0)

  /** Splits source into lines preserving CR / LF / CRLF spellings distinctly. A
    * trailing newline yields no synthetic empty final line; a missing trailing
    * newline yields a final line with `ending == ""`. */
  def split(text: String): Vector[MdLine] =
    // INDEX THE CODE UNITS, NOT THE STRING, and it is a complexity fix rather than a style
    // preference. `charAt`/`substring` are O(1) on the JVM but NOT on every backend: the
    // ScalaScript Rust backend stores a String as UTF-8 and emulates JVM code-unit indexing
    // over it, which costs O(i) per index — so this scan, written the ordinary way, was
    // O(n²) there and made a 505 KB document take hours (rozum's `rag-uniml-parser-quadratic`;
    // profiling put 90% of a whole markdown parse inside this one function). `text.toVector`
    // pays that conversion ONCE, and every index after it is a vector index. Identical
    // semantics on both lanes: `Vector[Char]` is code UNITS, exactly what `charAt` yields, so
    // surrogate pairs still arrive as two elements and `chars.length == text.length`.
    // annotated: captured by the lifted `split` (Rust lane)
    val chars: Vector[Char] = text.toVector
    // Tail recursion over (index, lineStart, lines) — the same walk, and CRITICALLY the same
    // complexity: every access is still a Vector index (O(1)) and every slice per LINE, so the
    // O(n²) this comment block describes cannot come back through this conversion. Tail-position
    // self-calls compile to the same loop on scalac and Scala.js.
    def split(index: Int, lineStart: Int, lines: Vector[MdLine]): (Vector[MdLine], Int) =
      if index >= chars.length then (lines, lineStart)
      else chars(index) match
        case '\n' =>
          split(index + 1, index + 1, lines :+ MdLine(chars.slice(lineStart, index).mkString(""), "\n"))
        case '\r' =>
          if index + 1 < chars.length && chars(index + 1) == '\n' then
            split(index + 2, index + 2, lines :+ MdLine(chars.slice(lineStart, index).mkString(""), "\r\n"))
          else
            split(index + 1, index + 1, lines :+ MdLine(chars.slice(lineStart, index).mkString(""), "\r"))
        case _ => split(index + 1, lineStart, lines)
    val splitResult = split(0, 0, Vector.empty)
    val lines = splitResult._1
    val lineStart = splitResult._2
    // SLICE THE CODE-UNIT VECTOR, NOT THE DOCUMENT — and that distinction is a second complexity
    // fix on top of the first. `text.substring(lineStart, index)` is one slice per LINE, which
    // already beat the per-character version below; but `substring` counts from the START of the
    // string, so on a backend where that mapping is a walk it costs O(index) — growing with the
    // position in the file, i.e. O(n²) again, just with a smaller constant. Measured on this
    // parser with every helper instrumented: `substring` calls grew ×4.00 with the input while
    // its walk distance grew ×15.20, and the longest string handed to it was the WHOLE document;
    // every other string helper was ×4.00 on both. `chars` is already the code-unit vector this
    // loop indexes, so slicing THAT is O(line length) and needs no mapping at all.
    //
    // ONE slice per LINE, not one per character. The previous shape accumulated
    // `content :+ text.substring(index, index + 1)` — a fresh single-character String per
    // character — because "v2 has no Char box" and stringifying the matched char would render
    // the code point's decimal digits. Slicing from the line's start keeps that property (the
    // text still comes from the source, never from a Char) while calling `substring` once per
    // line, which is what takes the total from O(n²) to O(n) on a backend whose `substring` is
    // not O(1). It also drops the per-line `mkString` and the `Vector[String]` accumulator.
    if lineStart < chars.length then lines :+ MdLine(chars.drop(lineStart).mkString(""), "") else lines

/** Shared character classification following CommonMark 0.31.2 §2.1. */
private[markdown] object MdChars:
  private val VerticalTab = '\u000B'
  private val FormFeed = '\u000C'

  /** ASCII lowercase — the fold CommonMark actually asks for, and locale-independent.
    *
    * `String.toLowerCase()` takes no argument and uses the JVM's DEFAULT LOCALE, so its result is
    * not a property of the string. In Turkish `"I".toLowerCase` is `"\u0131"` — a dotless i — so
    * every HTML tag name containing an `I` was mis-folded: `<LI>`, `<TITLE>`, `<IFRAME>`,
    * `<DIALOG>`, `<FIGCAPTION>`. The document did not change; the environment variable did.
    *
    * That is the same defect UNIML-SSC3-ALPHABET removed from `isLetter` and worse in one respect:
    * `isLetter` needed a different RUNTIME to diverge, this needs only `-Duser.language=tr` on the
    * same one.
    *
    * Every site that used the host fold here decides an ASCII question — an HTML tag name, a
    * scheme, `www.`, a link label — so ASCII folding is not an approximation, it is the rule.
    * Anything above `z` is left exactly as it was rather than guessed at. */
  def asciiLower(s: String): String =
    // The no-uppercase fast path RETURNS THE INPUT UNALLOCATED, exactly as before — an `exists`
    // over the chars is the same early-exit scan the while spelled out.
    // A WHILE, not `(0 until n).exists { … }`: `.exists` on a Range does not lower on the
    // Rust lane (E0599), same early-exit scan.
    var needs = false
    var ni = 0
    while ni < s.length && !needs do
      val c = s.charAt(ni)
      if c >= 'A' && c <= 'Z' then needs = true
      ni += 1
    if !needs then s
    else
      // `Vector[String]` + `.mkString("")`, not `StringBuilder`: v2 has no StringBuilder, and this is
      // the accumulation shape `specs/uniml-portable-gapmap.md` settled on and `JsonLexer` already
      // ships. `Char.toString` is the portable char→String step — `String.valueOf(c)` is NOT: a
      // capitalized receiver lowers to an effect operation on v2 and yields
      // `Op("String.valueOf", B, <closure>)` instead of a string, silently.
      (0 until s.length).foldLeft(Vector.empty[String]) { (out, k) =>
        val c = s.charAt(k)
        out :+ (if c >= 'A' && c <= 'Z' then (c + 32).toChar else c).toString
      }.mkString("")

  /** CommonMark's link-label fold: Unicode, but NOT locale-dependent.
    *
    * A label matches its definition after a UNICODE case fold — `[ΑΒΓ]` finds `[αβγ]` — so ASCII
    * folding is wrong here and the official corpus says so: folding this site as ASCII took the
    * suite from 607 passing cases to 606.
    *
    * The distinction that makes both properties reachable at once: `String.toLowerCase()` is
    * LOCALE-SENSITIVE and `Character.toLowerCase(char)` is not — the second is defined by the
    * Unicode data alone. So ASCII is folded by range (which also side-steps the Turkish dotless
    * i for the one letter where the two disagree) and everything else goes through the
    * locale-independent character fold. */
  def foldCase(s: String): String =
    // Same portable accumulator as `asciiLower` above. `Character.toLowerCase` stays — it is the
    // locale-independent fold this doc comment argues for, and it runs on v2 since 2026-08-16
    // (`v2/src/Runtime.scala`, `characterFold`); before that it silently produced
    // `Op("Character.toLowerCase", …)` rather than a char.
    (0 until s.length).foldLeft(Vector.empty[String]) { (out, i) =>
      val c = s.charAt(i)
      out :+ (
        if c >= 'A' && c <= 'Z' then (c + 32).toChar
        else if c < 128 then c
        else Character.toLowerCase(c)).toString
    }.mkString("")

  def isAsciiWhitespace(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == VerticalTab || c == FormFeed || c == '\r'

  def isUnicodeWhitespace(c: Char): Boolean =
    isAsciiWhitespace(c) || {
      // Unicode space separators (Zs, Zl, Zp) — the exact non-ASCII set
      val v = c.toInt
      v == 0x00A0 || v == 0x1680 || (v >= 0x2000 && v <= 0x200A) ||
      v == 0x2028 || v == 0x2029 || v == 0x202F || v == 0x205F || v == 0x3000
    }

  def isAsciiPunctuation(c: Char): Boolean =
    (c >= '!' && c <= '/') || (c >= ':' && c <= '@') || (c >= '[' && c <= '`') || (c >= '{' && c <= '~')

  /** CommonMark "punctuation": ASCII punctuation or a Unicode punctuation/symbol
    * char (categories Pc Pd Pe Pf Pi Po Ps + Sc Sk Sm So). The BMP ranges in
    * `punctRanges` are generated from `java.lang.Character.getType` (equivalence
    * proven by `MdCharsParitySpec`), so this is portable — no `Character` at
    * runtime, only a binary search over a sorted range table. */
  def isPunctuation(c: Char): Boolean =
    isAsciiPunctuation(c) || (c.toInt >= 0x80 && bmpPunct(c.toInt))

  private def bmpPunct(cp: Int): Boolean =
    // Binary search as a WHILE loop, not a tail-recursive local def: the Rust lane lifts a
    // local def to a top-level `fn`, and a `fn` item cannot capture `punctRanges` (a module
    // val) — `error[E0434]`. Identical halving, same O(log n).
    var lo = 0
    var hi = punctRanges.length / 2 - 1
    var found = false
    while lo <= hi && !found do
      val mid = (lo + hi) / 2
      val start = punctRanges(mid * 2)
      val end = punctRanges(mid * 2 + 1)
      if cp < start then hi = mid - 1
      else if cp > end then lo = mid + 1
      else found = true
    found

  // 199 BMP ranges (Unicode Pc Pd Pe Pf Pi Po Ps + Sc Sk Sm So), sorted, as
  // [start0, end0, start1, end1, …]. Generated from java.lang.Character.getType.
  private val punctRanges: Vector[Int] = Vector(
    161, 169, 171, 172, 174, 177, 180, 180, 182, 184, 187, 187, 191, 191, 215, 215,
    247, 247, 706, 709, 722, 735, 741, 747, 749, 749, 751, 767, 885, 885, 894, 894,
    900, 901, 903, 903, 1014, 1014, 1154, 1154, 1370, 1375, 1417, 1418, 1421, 1423, 1470, 1470,
    1472, 1472, 1475, 1475, 1478, 1478, 1523, 1524, 1542, 1551, 1563, 1563, 1565, 1567, 1642, 1645,
    1748, 1748, 1758, 1758, 1769, 1769, 1789, 1790, 1792, 1805, 2038, 2041, 2046, 2047, 2096, 2110,
    2142, 2142, 2184, 2184, 2404, 2405, 2416, 2416, 2546, 2547, 2554, 2555, 2557, 2557, 2678, 2678,
    2800, 2801, 2928, 2928, 3059, 3066, 3191, 3191, 3199, 3199, 3204, 3204, 3407, 3407, 3449, 3449,
    3572, 3572, 3647, 3647, 3663, 3663, 3674, 3675, 3841, 3863, 3866, 3871, 3892, 3892, 3894, 3894,
    3896, 3896, 3898, 3901, 3973, 3973, 4030, 4037, 4039, 4044, 4046, 4058, 4170, 4175, 4254, 4255,
    4347, 4347, 4960, 4968, 5008, 5017, 5120, 5120, 5741, 5742, 5787, 5788, 5867, 5869, 5941, 5942,
    6100, 6102, 6104, 6107, 6144, 6154, 6464, 6464, 6468, 6469, 6622, 6655, 6686, 6687, 6816, 6822,
    6824, 6829, 7002, 7018, 7028, 7038, 7164, 7167, 7227, 7231, 7294, 7295, 7360, 7367, 7379, 7379,
    8125, 8125, 8127, 8129, 8141, 8143, 8157, 8159, 8173, 8175, 8189, 8190, 8208, 8231, 8240, 8286,
    8314, 8318, 8330, 8334, 8352, 8384, 8448, 8449, 8451, 8454, 8456, 8457, 8468, 8468, 8470, 8472,
    8478, 8483, 8485, 8485, 8487, 8487, 8489, 8489, 8494, 8494, 8506, 8507, 8512, 8516, 8522, 8525,
    8527, 8527, 8586, 8587, 8592, 9254, 9280, 9290, 9372, 9449, 9472, 10101, 10132, 11123, 11126, 11157,
    11159, 11263, 11493, 11498, 11513, 11516, 11518, 11519, 11632, 11632, 11776, 11822, 11824, 11869, 11904, 11929,
    11931, 12019, 12032, 12245, 12272, 12283, 12289, 12292, 12296, 12320, 12336, 12336, 12342, 12343, 12349, 12351,
    12443, 12444, 12448, 12448, 12539, 12539, 12688, 12689, 12694, 12703, 12736, 12771, 12800, 12830, 12842, 12871,
    12880, 12880, 12896, 12927, 12938, 12976, 12992, 13311, 19904, 19967, 42128, 42182, 42238, 42239, 42509, 42511,
    42611, 42611, 42622, 42622, 42738, 42743, 42752, 42774, 42784, 42785, 42889, 42890, 43048, 43051, 43062, 43065,
    43124, 43127, 43214, 43215, 43256, 43258, 43260, 43260, 43310, 43311, 43359, 43359, 43457, 43469, 43486, 43487,
    43612, 43615, 43639, 43641, 43742, 43743, 43760, 43761, 43867, 43867, 43882, 43883, 44011, 44011, 64297, 64297,
    64434, 64450, 64830, 64847, 64975, 64975, 65020, 65023, 65040, 65049, 65072, 65106, 65108, 65126, 65128, 65131,
    65281, 65295, 65306, 65312, 65339, 65344, 65371, 65381, 65504, 65510, 65512, 65518, 65532, 65533,
  )

  def isAsciiDigit(c: Char): Boolean = c >= '0' && c <= '9'

  def isAsciiLetter(c: Char): Boolean = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')

  def isAsciiAlnum(c: Char): Boolean = isAsciiDigit(c) || isAsciiLetter(c)

  /** Count of leading spaces (tab expands to next multiple of 4). Used only for
    * indentation decisions; the exact bytes are always preserved as tokens. */
  def indentWidth(content: String): Int =
    def walk(i: Int, col: Int): Int =
      if i >= content.length then col
      else content.charAt(i) match
        case ' '  => walk(i + 1, col + 1)
        case '\t' => walk(i + 1, col + (4 - (col % 4)))
        case _    => col
    walk(0, 0)

  /** Char index at which exactly `columns` columns of leading indentation have
    * been consumed, or -1 when the run is too short or a TAB straddles that
    * boundary. A straddling tab would have to be split into spaces to be cut,
    * which no lossless token can represent, so callers must handle -1 rather
    * than approximate it. */
  def indentCut(content: String, columns: Int): Int =
    def walk(i: Int, col: Int): Int =
      if i >= content.length || col >= columns then (if col == columns then i else -1)
      else content.charAt(i) match
        case ' '  => walk(i + 1, col + 1)
        case '\t' => walk(i + 1, col + (4 - (col % 4)))
        case _    => if col == columns then i else -1
    walk(0, 0)

  /** Length in chars of the leading whitespace prefix of `content`. */
  def indentPrefixLength(content: String): Int =
    def walk(i: Int): Int =
      if i < content.length && (content.charAt(i) == ' ' || content.charAt(i) == '\t') then walk(i + 1) else i
    walk(0)
