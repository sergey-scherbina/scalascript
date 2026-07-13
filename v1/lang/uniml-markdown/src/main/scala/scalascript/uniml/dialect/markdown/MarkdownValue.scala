package scalascript.uniml.dialect.markdown

import scalascript.uniml.{Diagnostic, Limits}

/** Selectable Markdown dialect profile. CommonMark is the baseline; GFM and
  * ScalaScript are explicit opt-ins, never silent reinterpretations. */
enum MarkdownProfile:
  case CommonMark, Gfm, ScalaScript

/** Finite bounds guarding every buffer, stack and delegated region so malformed
  * or hostile input fails with structured diagnostics before unbounded memory. */
final case class MarkdownLimits(
    core: Limits = Limits.default,
    maxSourceCodePoints: Long = 64L * 1024 * 1024,
    maxLineCodePoints: Int = 1024 * 1024,
    maxDelimiterRun: Int = 1024 * 1024,
    maxFenceCodePoints: Int = 16 * 1024 * 1024,
    maxReferences: Int = 1_000_000,
    maxBlocks: Int = 10_000_000,
)

object MarkdownLimits:
  val default: MarkdownLimits = MarkdownLimits()

/** Column alignment carried by a GFM table delimiter row. */
enum ColumnAlignment:
  case Default, Left, Center, Right

/** One cell of a GFM table row. */
final case class TableCell(inlines: Vector[MarkdownInline])

/** One item of a bullet or ordered list. `task` is `Some(checked)` for GFM
  * task-list items and `None` otherwise. */
final case class ListItem(blocks: Vector[MarkdownBlock], task: Option[Boolean] = None)

/** Semantic block model. The lossless CST remains the canonical representation;
  * this projection is a separate, bounded view. */
enum MarkdownBlock:
  case Paragraph(inlines: Vector[MarkdownInline])
  case Heading(level: Int, inlines: Vector[MarkdownInline], setext: Boolean)
  case ThematicBreak
  case BlockQuote(blocks: Vector[MarkdownBlock])
  case ListBlock(ordered: Boolean, start: Option[Long], tight: Boolean, items: Vector[ListItem])
  case CodeBlock(info: Option[String], literal: String, fenced: Boolean)
  case HtmlBlock(raw: String)
  case LinkDefinition(label: String, destination: String, title: Option[String])
  case Table(
      header: Vector[TableCell],
      alignments: Vector[ColumnAlignment],
      rows: Vector[Vector[TableCell]],
  )

/** Semantic inline model. Raw HTML, link destinations and `${expr}` text are
  * inert data — never rendered, fetched, or evaluated by the projector. */
enum MarkdownInline:
  case Text(value: String)
  case Emphasis(children: Vector[MarkdownInline])
  case Strong(children: Vector[MarkdownInline])
  case Strikethrough(children: Vector[MarkdownInline])
  case Code(value: String)
  case Link(label: Vector[MarkdownInline], destination: String, title: Option[String])
  case Image(alt: Vector[MarkdownInline], destination: String, title: Option[String])
  case Autolink(destination: String, label: String)
  case RawHtml(raw: String)
  case SoftBreak
  case HardBreak
  case Expression(source: String)

/** A projected Markdown document: ordered blocks plus the collected reference
  * definitions (retained in source order; first definition wins semantically). */
final case class MarkdownDocument(
    blocks: Vector[MarkdownBlock],
    references: Vector[MarkdownBlock.LinkDefinition],
)

/** Result of projecting a parsed CST into the semantic model. */
final case class MarkdownProjectionResult(
    document: Option[MarkdownDocument],
    diagnostics: Vector[Diagnostic],
)
