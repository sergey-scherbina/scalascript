package scalascript.ir

import upickle.default.ReadWriter

// ---------------------------------------------------------------------------
// Backend SPI v0.1 — IR (docs/backend-spi.md §5)
//
// Stage 2.1: structural types that round-trip losslessly through
// JSON / MsgPack.  Mirrors the AST closely for "near-no-op" Normalize;
// scalameta trees are NOT carried (backends re-parse from source).
//
// Stage 2.2: `derives ReadWriter` on every data type — upickle handles
// both JSON and MsgPack wire formats from the same derivation.
//
// Expression-level placeholders (Perform / Handle / Resume / ExternCall
// / TailCall / MatchTree) are reserved here so Stage 3 (effect lowering)
// and Stage 5 (intrinsic extraction) can populate them without an SPI
// version bump.
// ---------------------------------------------------------------------------

// ─── Positions ─────────────────────────────────────────────────────────────

case class Position(line: Int, column: Int, offset: Int) derives ReadWriter
case class Span(start: Position, end: Position)            derives ReadWriter

// ─── Symbol references ─────────────────────────────────────────────────────

case class QualifiedName(value: String) derives ReadWriter:
  override def toString: String = value

case class SymbolRef(qualifiedName: QualifiedName, span: Option[Span] = None) derives ReadWriter

// ─── Manifest (front-matter, structured fields only) ───────────────────────

case class RouteDecl(method: String, path: String, handler: String, span: Option[Span] = None) derives ReadWriter

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
) derives ReadWriter

// ─── Top-level module structure ────────────────────────────────────────────

case class NormalizedModule(
  manifest: Option[Manifest],
  sections: List[Section],
  span:     Option[Span] = None
) derives ReadWriter

case class Section(
  heading:     Heading,
  content:     List[Content],
  subsections: List[Section],
  span:        Option[Span] = None
) derives ReadWriter

case class Heading(level: Int, text: String, span: Option[Span] = None) derives ReadWriter

// ─── Content (per-section payload) ─────────────────────────────────────────

enum Content derives ReadWriter:
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

case class ImportBinding(name: String, alias: Option[String], span: Option[Span] = None) derives ReadWriter
case class ListItem(content: String, nested: List[ListItem], span: Option[Span] = None)  derives ReadWriter

// ─── Expression-level IR ────────────────────────────────────────────────

/** Sealed sum of IR expressions.  Stage 5+/A.1 concretises the core
 *  ordinary-expression cases (Lit, VarRef, Call) so intrinsic
 *  dispatch + `Normalize` can produce real IR.  Stage 3+ effect
 *  primitives (Perform / Handle / Resume), Stage 5+/A.1 extern call
 *  sites (ExternCall), Stage 3 match desugaring (MatchTree), and
 *  Stage 3 tail-call annotations all coexist as IrExpr variants. */
sealed trait IrExpr derives ReadWriter

// ── Ordinary expressions ───────────────────────────────────────────────

/** A primitive literal. */
case class Lit(value: LitValue) extends IrExpr derives ReadWriter

/** A variable reference by lexical name.  Resolved against the
 *  module scope or enclosing frame. */
case class VarRef(name: String) extends IrExpr derives ReadWriter

/** Ordinary function / method call site, target resolved to an
 *  absolute symbol reference. */
case class Call(target: SymbolRef, args: List[IrExpr]) extends IrExpr derives ReadWriter

/** A primitive literal payload — concrete variant of `Lit`.
 *  Numeric kinds split so round-trip preserves type. */
enum LitValue derives ReadWriter:
  case IntL(value:    Long)
  case DoubleL(value: Double)
  case StringL(value: String)
  case BoolL(value:   Boolean)
  case UnitL

// ── Effect primitives (Stage 3+) ──────────────────────────────────────

case class Perform(effect: QualifiedName, op: String, args: List[IrExpr]) extends IrExpr derives ReadWriter
case class Handle(body: IrExpr, cases: List[HandleCase], ret: HandleReturn) extends IrExpr derives ReadWriter
case class HandleCase(effect: QualifiedName, op: String, params: List[String], body: IrExpr) derives ReadWriter
case class HandleReturn(param: String, body: IrExpr) derives ReadWriter
case class Resume(k: SymbolRef, value: IrExpr) extends IrExpr derives ReadWriter

case class TailCall(target: SymbolRef, args: List[IrExpr]) extends IrExpr derives ReadWriter

/** `extern def` call site — the call lowers to this when its target
 *  is a symbol declared `extern` (spec §8).  Each backend's
 *  `Backend.intrinsics` map decides how the call materialises:
 *  inline target source, runtime helper, or out-of-process callback. */
case class ExternCall(name: QualifiedName, args: List[IrExpr], span: Option[Span] = None) extends IrExpr derives ReadWriter

/** Compiled pattern-match decision tree.  Stage 3's match desugaring
 *  produces this so each backend doesn't re-implement decision-tree
 *  compilation. */
case class MatchTree(scrutinee: IrExpr, root: DecisionNode) extends IrExpr derives ReadWriter

sealed trait DecisionNode derives ReadWriter
case class Switch(cases: List[(Pattern, DecisionNode)], default: Option[DecisionNode]) extends DecisionNode derives ReadWriter
case class Leaf(action: IrExpr) extends DecisionNode derives ReadWriter

sealed trait Pattern derives ReadWriter
case class PatLit(value: PatternLiteral) extends Pattern derives ReadWriter
case class PatVar(name: String) extends Pattern          derives ReadWriter
case class PatCtor(ctor: QualifiedName, fields: List[Pattern]) extends Pattern derives ReadWriter
case object PatWildcard extends Pattern

/** A primitive literal usable in a pattern. */
enum PatternLiteral derives ReadWriter:
  case IntLit(value: Long)
  case StrLit(value: String)
  case BoolLit(value: Boolean)
  case NullLit

// ─── Context types passed to backend intrinsics ────────────────────────────
//
// EmitContext / TargetCode / Value carry runtime references that are not
// part of the serialised IR — they're used only at intrinsic call sites
// (in-process) and don't cross the wire.  No ReadWriter required.

trait EmitContext

/** Opaque target-source string returned by `IntrinsicImpl.InlineCode.emit`. */
opaque type TargetCode = String
object TargetCode:
  def apply(s: String): TargetCode = s
  extension (t: TargetCode) def value: String = t

/** Runtime value handed to / returned from
 *  `Session.invokeHandler`.  Stage 5 settles the concrete shape; for now
 *  any opaque payload travels through the wire as JSON. */
trait Value
