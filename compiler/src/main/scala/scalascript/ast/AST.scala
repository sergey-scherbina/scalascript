package scalascript.ast

/** Source position for error reporting */
case class Position(line: Int, column: Int, offset: Int):
  override def toString: String = s"$line:$column"

case class Span(start: Position, end: Position):
  override def toString: String = s"$start-$end"

// ─── Document structure ───────────────────────────────────────────

case class Module(
  manifest: Option[Manifest],
  sections: List[Section],
  span: Option[Span] = None
)

case class Manifest(
  name: Option[String],
  version: Option[String],
  description: Option[String],
  dependencies: Map[String, String],
  exports: List[String],
  targets: List[String],
  raw: Map[String, Any],
  span: Option[Span] = None
)

case class Section(
  heading: Heading,
  content: List[Content],
  subsections: List[Section],
  span: Option[Span] = None
)

case class Heading(level: Int, text: String, span: Option[Span] = None)

// ─── Content ─────────────────────────────────────────────────────

enum Content:
  /** Raw prose text extracted from a Markdown paragraph. */
  case Prose(text: String, span: Option[Span] = None)
  /** Fenced code block; `tree` is Some when scalameta parsed it successfully. */
  case CodeBlock(lang: String, source: String, tree: Option[ScalaNode], span: Option[Span] = None)
  /** Markdown link that acts as a module import: `[Name, …](path)`. */
  case Import(path: String, bindings: List[ImportBinding], span: Option[Span] = None)
  /** Ordered or unordered list. */
  case DataList(items: List[ListItem], ordered: Boolean, span: Option[Span] = None)

case class ImportBinding(name: String, alias: Option[String], span: Option[Span] = None)
case class ListItem(content: String, nested: List[ListItem], span: Option[Span] = None)
