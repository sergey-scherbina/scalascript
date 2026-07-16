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
  /** Splits source into lines preserving CR / LF / CRLF spellings distinctly. A
    * trailing newline yields no synthetic empty final line; a missing trailing
    * newline yields a final line with `ending == ""`. */
  def split(text: String): Vector[MdLine] =
    var lines: Vector[MdLine] = Vector.empty
    var content: Vector[String] = Vector.empty
    var index = 0
    while index < text.length do
      val char = text.charAt(index)
      char match
        case '\n' =>
          lines = lines :+ MdLine(content.mkString, "\n")
          content = Vector.empty
          index += 1
        case '\r' =>
          if index + 1 < text.length && text.charAt(index + 1) == '\n' then
            lines = lines :+ MdLine(content.mkString, "\r\n")
            index += 2
          else
            lines = lines :+ MdLine(content.mkString, "\r")
            index += 1
          content = Vector.empty
        case _ =>
          // v2 has no Char box — slice the source rather than stringifying the
          // matched char (which would render the code point's decimal digits).
          content = content :+ text.substring(index, index + 1)
          index += 1
    if content.nonEmpty then lines = lines :+ MdLine(content.mkString, "")
    lines

/** Shared character classification following CommonMark 0.31.2 §2.1. */
private[markdown] object MdChars:
  private val VerticalTab = '\u000B'
  private val FormFeed = '\u000C'

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
    var col = 0
    var i = 0
    var done = false
    while i < content.length && !done do
      content.charAt(i) match
        case ' '  => col += 1; i += 1
        case '\t' => col += 4 - (col % 4); i += 1
        case _    => done = true
    col

  /** Length in chars of the leading whitespace prefix of `content`. */
  def indentPrefixLength(content: String): Int =
    var i = 0
    while i < content.length && (content.charAt(i) == ' ' || content.charAt(i) == '\t') do i += 1
    i
