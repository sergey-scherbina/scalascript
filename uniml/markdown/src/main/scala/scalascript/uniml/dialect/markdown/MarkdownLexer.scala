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
    val lines = Vector.newBuilder[MdLine]
    val content = StringBuilder()
    var index = 0
    while index < text.length do
      val char = text.charAt(index)
      char match
        case '\n' =>
          lines += MdLine(content.result(), "\n")
          content.clear()
          index += 1
        case '\r' =>
          if index + 1 < text.length && text.charAt(index + 1) == '\n' then
            lines += MdLine(content.result(), "\r\n")
            index += 2
          else
            lines += MdLine(content.result(), "\r")
            index += 1
          content.clear()
        case other =>
          content.append(other)
          index += 1
    if content.nonEmpty then lines += MdLine(content.result(), "")
    lines.result()

/** Shared character classification following CommonMark 0.31.2 §2.1. */
private[markdown] object MdChars:
  private val VerticalTab = '\u000B'
  private val FormFeed = '\u000C'

  def isAsciiWhitespace(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == VerticalTab || c == FormFeed || c == '\r'

  def isUnicodeWhitespace(c: Char): Boolean =
    isAsciiWhitespace(c) || Character.isSpaceChar(c)

  def isAsciiPunctuation(c: Char): Boolean =
    (c >= '!' && c <= '/') || (c >= ':' && c <= '@') || (c >= '[' && c <= '`') || (c >= '{' && c <= '~')

  /** CommonMark "punctuation": ASCII punctuation or a Unicode punctuation/symbol char. */
  def isPunctuation(c: Char): Boolean =
    isAsciiPunctuation(c) || {
      val t = Character.getType(c)
      t == Character.CONNECTOR_PUNCTUATION || t == Character.DASH_PUNCTUATION ||
      t == Character.START_PUNCTUATION || t == Character.END_PUNCTUATION ||
      t == Character.INITIAL_QUOTE_PUNCTUATION || t == Character.FINAL_QUOTE_PUNCTUATION ||
      t == Character.OTHER_PUNCTUATION || t == Character.MATH_SYMBOL ||
      t == Character.CURRENCY_SYMBOL || t == Character.MODIFIER_SYMBOL || t == Character.OTHER_SYMBOL
    }

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
