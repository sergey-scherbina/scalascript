package scalascript.ir

// ---------------------------------------------------------------------------
// Backend SPI v0.1 — IR (docs/backend-spi.md §5)
//
// Stage 2.1: structural types that round-trip losslessly through
// JSON / MsgPack.  Mirrors the AST closely for "near-no-op" Normalize;
// scalameta trees are NOT carried (backends re-parse from source).
//
// Expression-level placeholders (Perform / Handle / Resume / ExternCall
// / TailCall / MatchTree) are reserved here so Stage 3 (effect lowering)
// and Stage 5 (intrinsic extraction) can populate them without an SPI
// version bump.
// ---------------------------------------------------------------------------

// ─── Positions ─────────────────────────────────────────────────────────────

case class Position(line: Int, column: Int, offset: Int)
case class Span(start: Position, end: Position)

// ─── Symbol references ─────────────────────────────────────────────────────

case class QualifiedName(value: String):
  override def toString: String = value

case class SymbolRef(qualifiedName: QualifiedName, span: Option[Span] = None)

// ─── Manifest (front-matter, structured fields only) ───────────────────────

case class RouteDecl(method: String, path: String, handler: String, span: Option[Span] = None)

case class Manifest(
  name:         Option[String],
  version:      Option[String],
  description:  Option[String],
  dependencies: Map[String, String],
  exports:      List[String],
  targets:      List[String],
  routes:       List[RouteDecl],
  pkg:          Option[List[String]],
  span:         Option[Span] = None
)

// ─── Top-level module structure ────────────────────────────────────────────

case class NormalizedModule(
  manifest: Option[Manifest],
  sections: List[Section],
  span:     Option[Span] = None
)

case class Section(
  heading:     Heading,
  content:     List[Content],
  subsections: List[Section],
  span:        Option[Span] = None
)

case class Heading(level: Int, text: String, span: Option[Span] = None)

// ─── Content (per-section payload) ─────────────────────────────────────────

enum Content:
  /** Raw prose extracted from a Markdown paragraph. */
  case Prose(text: String, span: Option[Span] = None)
  /** A `scalascript` / `ssc` fence — host embedded language.  Stored as
   *  source; backends re-parse via the scala-source plugin (Stage 9).
   *  Carries the parsed/normalised body once Stage 3 lowering lands. */
  case CodeBlock(source: String, body: List[IrExpr] = Nil, span: Option[Span] = None)
  /** A foreign-language fence (`html`, `css`, `scala`, future `wat`, …).
   *  Compiled by a SourceLanguage plugin (Stage 9). */
  case EmbeddedBlock(language: String, source: String, span: Option[Span] = None)
  /** Markdown link that acts as a module import: `[Name, …](path)`. */
  case Import(path: String, bindings: List[ImportBinding], span: Option[Span] = None)
  /** Ordered or unordered list. */
  case DataList(items: List[ListItem], ordered: Boolean, span: Option[Span] = None)

/** Alias used by `Session.feed` in the SPI — feeding "one block" to an
 *  interactive backend means handing it one `Content` node. */
type NormalizedBlock = Content

case class ImportBinding(name: String, alias: Option[String], span: Option[Span] = None)
case class ListItem(content: String, nested: List[ListItem], span: Option[Span] = None)

// ─── Expression-level IR (placeholders for Stage 3+) ───────────────────────

/** Sealed sum of IR expressions.  Stage 2.1 only declares the
 *  placeholders; Stage 3 (effect lowering) fills bodies. */
sealed trait IrExpr

case class Perform(effect: QualifiedName, op: String, args: List[IrExpr]) extends IrExpr
case class Handle(body: IrExpr, cases: List[HandleCase], ret: HandleReturn) extends IrExpr
case class HandleCase(effect: QualifiedName, op: String, params: List[String], body: IrExpr)
case class HandleReturn(param: String, body: IrExpr)
case class Resume(k: SymbolRef, value: IrExpr) extends IrExpr

case class TailCall(target: SymbolRef, args: List[IrExpr]) extends IrExpr

/** `extern def` call site.  Stage 5 produces these when lowering calls
 *  to symbols declared with the `extern` modifier (spec §8). */
case class ExternCall(name: QualifiedName, args: List[IrExpr], span: Option[Span] = None) extends IrExpr

/** Compiled pattern-match decision tree.  Stage 3's match desugaring
 *  produces this so each backend doesn't re-implement decision-tree
 *  compilation. */
case class MatchTree(scrutinee: IrExpr, root: DecisionNode) extends IrExpr

sealed trait DecisionNode
case class Switch(cases: List[(Pattern, DecisionNode)], default: Option[DecisionNode]) extends DecisionNode
case class Leaf(action: IrExpr) extends DecisionNode

sealed trait Pattern
case class PatLit(value: PatternLiteral) extends Pattern
case class PatVar(name: String) extends Pattern
case class PatCtor(ctor: QualifiedName, fields: List[Pattern]) extends Pattern
case object PatWildcard extends Pattern

/** A primitive literal usable in a pattern.  Round-trippable via
 *  upickle's standard derivation. */
enum PatternLiteral:
  case IntLit(value: Long)
  case StrLit(value: String)
  case BoolLit(value: Boolean)
  case NullLit

// ─── Context types passed to backend intrinsics ────────────────────────────

trait EmitContext

/** Opaque target-source string returned by `IntrinsicImpl.InlineCode.emit`. */
opaque type TargetCode = String
object TargetCode:
  def apply(s: String): TargetCode = s
  extension (t: TargetCode) def value: String = t

// ─── Value type for interactive backends ───────────────────────────────────

/** Runtime value handed to / returned from
 *  `Session.invokeHandler`.  Stage 5 settles the concrete shape; for now
 *  any opaque payload travels through the wire as JSON. */
trait Value
