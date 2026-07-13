package scalascript.uniml.dialect.markdown.bridge

import scalascript.ast.{ContentBlock, ContentInline, ContentValue, DocumentContent, EmbeddedKind, SectionContent}
import scalascript.uniml.{Diagnostic, Severity}
import scalascript.uniml.dialect.markdown.*
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/** Result of bridging a [[MarkdownDocument]] into the compiler's
  * [[DocumentContent]] model: the projected document plus a diagnostic for every
  * construct the target model cannot preserve. */
final case class MarkdownBridgeResult(document: DocumentContent, diagnostics: Vector[Diagnostic])

/** Optional, JVM-only bridge from the lossless UniML Markdown projection into the
  * existing ScalaScript `DocumentContent` compiler model.
  *
  * The bridge reuses the compiler model without making it the canonical Markdown
  * representation: it maps only the constructs `DocumentContent` can express and
  * reports explicit model loss for everything else (block quotes, thematic
  * breaks, raw HTML, standalone definitions, hard/soft-break distinction, task
  * state, inline images and strikethrough). Representable content is mapped
  * exactly as `Parser.buildDocumentContent` maps it, so the two agree. */
object MarkdownDocContent:

  def bridge(doc: MarkdownDocument): MarkdownBridgeResult =
    val b = Builder()
    doc.blocks.foreach(b.top)
    b.finish(doc)

  private final class Frame(
      val level: Int,
      val id: String,
      val title: String,
      val blocks: ListBuffer[ContentBlock],
      val children: ListBuffer[SectionContent],
  )

  private final class Builder:
    private val roots = ListBuffer.empty[SectionContent]
    private val topBlocks = ListBuffer.empty[ContentBlock]
    private val stack = mutable.Stack.empty[Frame]
    private val usedIds = mutable.Map.empty[String, Int]
    private val diagnostics = ListBuffer.empty[Diagnostic]
    private var breakLossReported = false

    // ── section stack (mirrors Parser.buildDocumentContent) ──────────────

    private def flush(toLevel: Int): Unit =
      while stack.nonEmpty && stack.top.level >= toLevel do
        val f = stack.pop()
        val section = SectionContent(f.id, f.level, f.title, Map.empty, f.blocks.toList, f.children.toList)
        if stack.nonEmpty then stack.top.children += section else roots += section

    private def addBlocks(bs: List[ContentBlock]): Unit =
      if bs.nonEmpty then
        if stack.nonEmpty then stack.top.blocks ++= bs else topBlocks ++= bs

    private def nextId(title: String): String =
      val base0 = slugify(title)
      val base = if base0.nonEmpty then base0 else "section"
      val count = usedIds.getOrElse(base, 0) + 1
      usedIds(base) = count
      if count == 1 then base else s"$base-$count"

    /** Top-level dispatch: headings open sections, everything else is a block. */
    def top(block: MarkdownBlock): Unit = block match
      case MarkdownBlock.Heading(level, inlines, _) =>
        flush(level)
        val title = plainText(mapInlines(inlines))
        stack.push(Frame(level, nextId(title), title, ListBuffer.empty, ListBuffer.empty))
      case other => addBlocks(blockToContent(other))

    def finish(doc: MarkdownDocument): MarkdownBridgeResult =
      flush(0)
      if doc.references.nonEmpty then
        loss("uniml.markdown.bridge.link-definition",
          s"${doc.references.size} link reference definition(s) produce no DocumentContent block", Severity.Info)
      val document = DocumentContent(
        manifest = ContentValue.MapV(Map.empty),
        title = roots.headOption.map(_.title),
        description = None,
        attrs = Map.empty,
        sections = roots.toList,
        blocks = topBlocks.toList,
      )
      MarkdownBridgeResult(document, diagnostics.toVector)

    // ── block mapping (no section handling — used for lists/quotes too) ──

    private def blockToContent(block: MarkdownBlock): List[ContentBlock] = block match
      case MarkdownBlock.Paragraph(inlines) =>
        inlines match
          case Vector(MarkdownInline.Image(alt, dest, title)) =>
            List(ContentBlock.Image(dest, plainText(mapInlines(alt)), title))
          case _ =>
            val mapped = mapInlines(inlines)
            if mapped.isEmpty then Nil else List(ContentBlock.Paragraph(mapped))

      case MarkdownBlock.Heading(level, inlines, _) =>
        // a heading nested where only blocks are allowed (e.g. inside a quote);
        // preserve its text as an emphasized paragraph and report the loss
        loss("uniml.markdown.bridge.nested-heading",
          "heading in a block-only context projected as an emphasized paragraph", Severity.Warning)
        List(ContentBlock.Paragraph(List(ContentInline.Strong(mapInlines(inlines)))))

      case MarkdownBlock.ThematicBreak =>
        loss("uniml.markdown.bridge.thematic-break", "thematic break has no DocumentContent block", Severity.Warning)
        Nil

      case MarkdownBlock.BlockQuote(blocks) =>
        loss("uniml.markdown.bridge.block-quote", "block quote flattened; DocumentContent has no quote block", Severity.Warning)
        blocks.toList.flatMap(blockToContent)

      case MarkdownBlock.ListBlock(ordered, start, _, items) =>
        val itemBlocks = items.toList.map { item =>
          item.task.foreach { _ =>
            loss("uniml.markdown.bridge.task-item", "task-list checked state has no DocumentContent representation", Severity.Info)
          }
          item.blocks.toList.flatMap(blockToContent)
        }
        if ordered then List(ContentBlock.OrderedList(itemBlocks, start.map(_.toInt).getOrElse(1)))
        else List(ContentBlock.BulletList(itemBlocks))

      case MarkdownBlock.CodeBlock(info, literal, _) =>
        val lang = info.getOrElse("").takeWhile(!_.isWhitespace).toLowerCase
        List(ContentBlock.Embedded(lang, literal, embeddedKind(lang)))

      case MarkdownBlock.HtmlBlock(_) =>
        loss("uniml.markdown.bridge.html-block", "raw HTML block has no DocumentContent representation", Severity.Warning)
        Nil

      case MarkdownBlock.LinkDefinition(_, _, _) =>
        Nil // collected as references; never a rendered block (matches CommonMark)

      case MarkdownBlock.Table(header, alignments, rows) =>
        List(ContentBlock.Table(
          headers = header.toList.map(cell => mapInlines(cell.inlines)),
          rows = rows.toList.map(_.toList.map(cell => mapInlines(cell.inlines))),
          alignments = alignments.toList.map(alignString),
        ))

    // ── inline mapping (matches Parser.contentInlines for representable kinds) ──

    private def mapInlines(inlines: Vector[MarkdownInline]): List[ContentInline] =
      val out = ListBuffer.empty[ContentInline]
      inlines.foreach(i => mapInline(i).foreach(appendMerging(out, _)))
      out.toList

    private def appendMerging(out: ListBuffer[ContentInline], inline: ContentInline): Unit =
      (out.lastOption, inline) match
        case (Some(ContentInline.Text(a)), ContentInline.Text(b)) => out(out.size - 1) = ContentInline.Text(a + b)
        case _                                                    => out += inline

    private def mapInline(inline: MarkdownInline): Option[ContentInline] = inline match
      case MarkdownInline.Text(v)           => Some(ContentInline.Text(v))
      case MarkdownInline.Code(v)           => Some(ContentInline.Code(v))
      case MarkdownInline.Emphasis(cs)      => Some(ContentInline.Emphasis(mapInlines(cs)))
      case MarkdownInline.Strong(cs)        => Some(ContentInline.Strong(mapInlines(cs)))
      case MarkdownInline.Link(label, d, t) => Some(ContentInline.Link(mapInlines(label), d, t))
      case MarkdownInline.Expression(src)   => Some(ContentInline.Expr(src))
      case MarkdownInline.Autolink(dest, l) => Some(ContentInline.Link(List(ContentInline.Text(l)), dest, None))
      case MarkdownInline.Image(alt, _, _) =>
        loss("uniml.markdown.bridge.inline-image", "inline image projected as its alt text", Severity.Info)
        Some(ContentInline.Text(plainText(mapInlines(alt))))
      case MarkdownInline.RawHtml(raw) =>
        loss("uniml.markdown.bridge.raw-html", "raw inline HTML projected as literal text", Severity.Warning)
        Some(ContentInline.Text(raw))
      case MarkdownInline.Strikethrough(cs) =>
        loss("uniml.markdown.bridge.strikethrough", "strikethrough has no DocumentContent representation; children preserved", Severity.Warning)
        // unwrap children into the surrounding run
        val mapped = mapInlines(cs)
        mapped match
          case Nil          => None
          case single :: Nil => Some(single)
          case many         => Some(ContentInline.Emphasis(many)) // keep grouping without losing children
      case MarkdownInline.SoftBreak =>
        reportBreakLoss(); Some(ContentInline.Text(" "))
      case MarkdownInline.HardBreak =>
        reportBreakLoss(); Some(ContentInline.Text("\n"))

    private def reportBreakLoss(): Unit =
      if !breakLossReported then
        breakLossReported = true
        loss("uniml.markdown.bridge.line-break", "soft/hard break distinction is not preserved in DocumentContent", Severity.Info)

    // ── helpers ────────────────────────────────────────────────────────

    private def loss(code: String, message: String, severity: Severity): Unit =
      diagnostics += Diagnostic(code, message, severity, span = None, dialect = Some("markdown.bridge"))

    private def embeddedKind(lang: String): EmbeddedKind =
      if Set("yaml", "yml", "json", "toml").contains(lang) then EmbeddedKind.StructuredData
      else EmbeddedKind.Opaque

    private def alignString(a: ColumnAlignment): String = a match
      case ColumnAlignment.Left    => "left"
      case ColumnAlignment.Center  => "center"
      case ColumnAlignment.Right   => "right"
      case ColumnAlignment.Default => "default"

    private def plainText(inlines: List[ContentInline]): String =
      val sb = StringBuilder()
      def walk(i: ContentInline): Unit = i match
        case ContentInline.Text(v)       => sb.append(v)
        case ContentInline.Code(v)       => sb.append(v)
        case ContentInline.Emphasis(cs)  => cs.foreach(walk)
        case ContentInline.Strong(cs)    => cs.foreach(walk)
        case ContentInline.Link(l, _, _) => l.foreach(walk)
        case ContentInline.Expr(_)       => ()
      inlines.foreach(walk)
      sb.result()

  private def slugify(text: String): String =
    text.toLowerCase.replaceAll("[^a-z0-9]+", "-").stripPrefix("-").stripSuffix("-")
