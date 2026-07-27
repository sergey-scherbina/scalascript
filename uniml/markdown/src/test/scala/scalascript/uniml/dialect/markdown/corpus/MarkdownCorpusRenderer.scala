package scalascript.uniml.dialect.markdown.corpus

import scalascript.uniml.dialect.markdown.*

/** Deterministic, test-only HTML renderer for the public Markdown projection.
  *
  * It intentionally has no JVM APIs and no dependency on an external Markdown
  * implementation, so JVM and Scala.js compare the same semantic observable.
  * The upstream HTML remains the oracle; this renderer only makes UniML's
  * projected semantics observable in that format.
  */
private[corpus] object MarkdownCorpusRenderer:
  def render(document: MarkdownDocument): String =
    document.blocks.iterator.map(renderBlock).mkString

  private def renderBlock(block: MarkdownBlock): String = block match
    case MarkdownBlock.Paragraph(inlines) =>
      s"<p>${renderInlines(inlines)}</p>\n"
    case MarkdownBlock.Heading(level, inlines, _) =>
      s"<h$level>${renderInlines(inlines)}</h$level>\n"
    case MarkdownBlock.ThematicBreak =>
      "<hr />\n"
    case MarkdownBlock.BlockQuote(blocks) =>
      "<blockquote>\n" + blocks.iterator.map(renderBlock).mkString + "</blockquote>\n"
    case MarkdownBlock.ListBlock(ordered, start, tight, items) =>
      renderList(ordered, start, tight, items)
    case MarkdownBlock.CodeBlock(info, literal, _) =>
      val language = info.flatMap(_.split("\\s+").iterator.find(_.nonEmpty))
      val classAttribute =
        language.map { value =>
          val cssClass = if value.startsWith("language-") then value else "language-" + value
          s""" class="${escapeAttribute(cssClass)}""""
        }.getOrElse("")
      s"<pre><code$classAttribute>${escapeText(literal)}</code></pre>\n"
    case MarkdownBlock.HtmlBlock(raw) =>
      if raw.isEmpty || raw.endsWith("\n") then raw else raw + "\n"
    case _: MarkdownBlock.LinkDefinition =>
      ""
    case MarkdownBlock.Table(header, alignments, rows) =>
      renderTable(header, alignments, rows)

  private def renderList(
      ordered: Boolean,
      start: Option[Long],
      tight: Boolean,
      items: Vector[ListItem],
  ): String =
    val tag = if ordered then "ol" else "ul"
    val startAttribute =
      if ordered then start.filter(_ != 1L).map(value => s""" start="$value"""").getOrElse("")
      else ""
    val output = new StringBuilder
    output.append('<').append(tag).append(startAttribute).append(">\n")
    items.foreach { item =>
      output.append("<li>")
      if item.blocks.nonEmpty then
        if tight then renderTightItem(item, output)
        else renderLooseItem(item, output)
      output.append("</li>\n")
    }
    output.append("</").append(tag).append(">\n")
    output.result()

  private def renderTightItem(item: ListItem, output: StringBuilder): Unit =
    val checkbox = item.task.map(renderCheckbox).getOrElse("")
    if item.blocks.isEmpty then output.append(checkbox)
    else
      item.blocks.zipWithIndex.foreach { (block, index) =>
        block match
          case MarkdownBlock.Paragraph(inlines) =>
            val rendered = renderInlines(inlines)
            if index == 0 then output.append(renderTaskParagraph(checkbox, rendered))
            else output.append(rendered)
            if index < item.blocks.size - 1 then output.append('\n')
          case other =>
            if index == 0 then
              output.append(checkbox)
              output.append('\n')
            output.append(renderBlock(other))
      }

  private def renderLooseItem(item: ListItem, output: StringBuilder): Unit =
    val checkbox = item.task.map(renderCheckbox).getOrElse("")
    output.append('\n')
    if item.blocks.isEmpty then
      if checkbox.nonEmpty then output.append(checkbox).append('\n')
    else
      item.blocks.zipWithIndex.foreach { (block, index) =>
        block match
          case MarkdownBlock.Paragraph(inlines) =>
            val rendered = renderInlines(inlines)
            val body = if index == 0 then renderTaskParagraph(checkbox, rendered) else rendered
            output.append("<p>").append(body).append("</p>\n")
          case other =>
            if index == 0 && checkbox.nonEmpty then output.append(checkbox).append('\n')
            output.append(renderBlock(other))
      }

  private def renderCheckbox(checked: Boolean): String =
    if checked then """<input checked="" disabled="" type="checkbox"> """
    else """<input disabled="" type="checkbox"> """

  /** The projection retains the one source space following `[ ]`/`[x]`.
    * cmark-gfm replaces the marker (including that separator) with an input
    * whose rendered form already ends in one space, so consume exactly that
    * retained separator and preserve any additional source whitespace. */
  private def renderTaskParagraph(checkbox: String, rendered: String): String =
    if checkbox.isEmpty then rendered
    else checkbox + (if rendered.startsWith(" ") then rendered.substring(1) else rendered)

  private def renderTable(
      header: Vector[TableCell],
      alignments: Vector[ColumnAlignment],
      rows: Vector[Vector[TableCell]],
  ): String =
    val output = new StringBuilder("<table>\n<thead>\n<tr>\n")
    header.zipWithIndex.foreach { (cell, index) =>
      output.append("<th")
      output.append(alignmentAttribute(alignments.lift(index)))
      output.append('>').append(renderInlines(cell.inlines)).append("</th>\n")
    }
    output.append("</tr>\n</thead>\n")
    if rows.nonEmpty then
      output.append("<tbody>\n")
      rows.foreach { row =>
        output.append("<tr>\n")
        header.indices.foreach { index =>
          val cell = row.lift(index).getOrElse(TableCell(Vector.empty))
          output.append("<td")
          output.append(alignmentAttribute(alignments.lift(index)))
          output.append('>').append(renderInlines(cell.inlines)).append("</td>\n")
        }
        output.append("</tr>\n")
      }
      output.append("</tbody>\n")
    output.append("</table>\n").result()

  private def alignmentAttribute(alignment: Option[ColumnAlignment]): String = alignment match
    case Some(ColumnAlignment.Left)   => """ align="left""""
    case Some(ColumnAlignment.Center) => """ align="center""""
    case Some(ColumnAlignment.Right)  => """ align="right""""
    case _                            => ""

  private def renderInlines(inlines: Vector[MarkdownInline]): String =
    inlines.iterator.map(renderInline).mkString

  private def renderInline(inline: MarkdownInline): String = inline match
    case MarkdownInline.Text(value) =>
      escapeText(value)
    case MarkdownInline.Emphasis(children) =>
      "<em>" + renderInlines(children) + "</em>"
    case MarkdownInline.Strong(children) =>
      "<strong>" + renderInlines(children) + "</strong>"
    case MarkdownInline.Strikethrough(children) =>
      "<del>" + renderInlines(children) + "</del>"
    case MarkdownInline.Code(value) =>
      "<code>" + escapeText(value) + "</code>"
    case MarkdownInline.Link(label, destination, title) =>
      val titleAttribute =
        title.map(value => s""" title="${escapeAttribute(value)}"""").getOrElse("")
      s"""<a href="${escapeHref(destination)}"$titleAttribute>${renderInlines(label)}</a>"""
    case MarkdownInline.Image(alt, destination, title) =>
      val titleAttribute =
        title.map(value => s""" title="${escapeAttribute(value)}"""").getOrElse("")
      val renderedAlt = escapeAttribute(plainText(alt))
      s"""<img src="${escapeHref(destination)}" alt="$renderedAlt"$titleAttribute />"""
    case MarkdownInline.Autolink(destination, label) =>
      val href =
        if destination.contains('@') && !destination.contains(':') then "mailto:" + destination
        else destination
      s"""<a href="${escapeHref(href)}">${escapeText(label)}</a>"""
    case MarkdownInline.RawHtml(raw) =>
      raw
    case MarkdownInline.SoftBreak =>
      "\n"
    case MarkdownInline.HardBreak =>
      "<br />\n"
    case MarkdownInline.Expression(source) =>
      escapeText("${" + source + "}")

  private def plainText(inlines: Vector[MarkdownInline]): String =
    val result = new StringBuilder
    def append(inline: MarkdownInline): Unit = inline match
      case MarkdownInline.Text(value)           => result.append(value)
      case MarkdownInline.Emphasis(children)    => children.foreach(append)
      case MarkdownInline.Strong(children)      => children.foreach(append)
      case MarkdownInline.Strikethrough(values) => values.foreach(append)
      case MarkdownInline.Code(value)           => result.append(value)
      case MarkdownInline.Link(label, _, _)     => label.foreach(append)
      case MarkdownInline.Image(alt, _, _)       => alt.foreach(append)
      case MarkdownInline.Autolink(_, label)     => result.append(label)
      case MarkdownInline.RawHtml(raw)           => result.append(raw)
      case MarkdownInline.SoftBreak              => result.append(' ')
      case MarkdownInline.HardBreak              => result.append(' ')
      case MarkdownInline.Expression(source)     => result.append("${").append(source).append('}')
    inlines.foreach(append)
    result.result()

  private def escapeText(value: String): String =
    val output = new StringBuilder
    value.foreach {
      case '&' => output.append("&amp;")
      case '<' => output.append("&lt;")
      case '>' => output.append("&gt;")
      case '"' => output.append("&quot;")
      case c   => output.append(c)
    }
    output.result()

  private def escapeAttribute(value: String): String =
    val output = new StringBuilder
    value.foreach {
      case '&'  => output.append("&amp;")
      case '<'  => output.append("&lt;")
      case '>'  => output.append("&gt;")
      case '"'  => output.append("&quot;")
      case c    => output.append(c)
    }
    output.result()

  /** URI escaping used by the CommonMark HTML observable. Existing `%xx`
    * escapes and URI delimiter characters remain intact; non-ASCII and unsafe
    * characters are UTF-8 percent encoded. */
  private def escapeHref(value: String): String =
    val output = new StringBuilder
    var index = 0
    while index < value.length do
      val first = value.charAt(index)
      val codePoint =
        if first >= '\uD800' && first <= '\uDBFF' && index + 1 < value.length then
          val second = value.charAt(index + 1)
          if second >= '\uDC00' && second <= '\uDFFF' then
            index += 1
            0x10000 + ((first.toInt - 0xD800) << 10) + (second.toInt - 0xDC00)
          else 0xFFFD
        else first.toInt
      if codePoint == '&'.toInt then output.append("&amp;")
      else if codePoint == '\''.toInt then output.append("&#x27;")
      else if codePoint < 128 && isUriSafe(codePoint.toChar) then output.append(codePoint.toChar)
      else appendPercentEncoded(output, codePoint)
      index += 1
    output.result()

  private def isUriSafe(value: Char): Boolean =
    value.isLetterOrDigit ||
      "-_.+!*(),%#@?=;:/,$~".indexOf(value) >= 0

  private def appendPercentEncoded(output: StringBuilder, codePoint: Int): Unit =
    val bytes =
      if codePoint <= 0x7f then Vector(codePoint)
      else if codePoint <= 0x7ff then
        Vector(0xc0 | (codePoint >>> 6), 0x80 | (codePoint & 0x3f))
      else if codePoint <= 0xffff then
        Vector(
          0xe0 | (codePoint >>> 12),
          0x80 | ((codePoint >>> 6) & 0x3f),
          0x80 | (codePoint & 0x3f),
        )
      else
        Vector(
          0xf0 | (codePoint >>> 18),
          0x80 | ((codePoint >>> 12) & 0x3f),
          0x80 | ((codePoint >>> 6) & 0x3f),
          0x80 | (codePoint & 0x3f),
        )
    val hex = "0123456789ABCDEF"
    bytes.foreach { byte =>
      output.append('%')
      output.append(hex.charAt((byte >>> 4) & 0xf))
      output.append(hex.charAt(byte & 0xf))
    }
