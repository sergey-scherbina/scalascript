package scalascript.ast

/** Source position for error reporting */
case class Position(line: Int, column: Int, offset: Int):
  override def toString: String = s"$line:$column"

case class Span(start: Position, end: Position):
  override def toString: String = s"$start-$end"

// ============================================
// Top-Level Document Structure
// ============================================

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

case class Heading(
  level: Int,
  text: String,
  span: Option[Span] = None
)

// ============================================
// Content Types
// ============================================

enum Content:
  case Prose(text: String, interpolations: List[Interpolation], span: Option[Span] = None)
  case CodeBlock(lang: String, code: String, statements: List[Statement], span: Option[Span] = None)
  case Import(path: String, bindings: List[ImportBinding], span: Option[Span] = None)
  case DataList(items: List[ListItem], ordered: Boolean, span: Option[Span] = None)

case class Interpolation(expr: Expr, span: Option[Span] = None)

case class ImportBinding(
  name: String,
  alias: Option[String],
  span: Option[Span] = None
)

case class ListItem(
  content: String,
  nested: List[ListItem],
  span: Option[Span] = None
)

// ============================================
// Expressions
// ============================================

enum Expr:
  case Literal(value: LiteralValue, span: Option[Span] = None)
  case Ident(name: String, span: Option[Span] = None)
  case Select(expr: Expr, name: String, span: Option[Span] = None)
  case Apply(fn: Expr, args: List[Expr], span: Option[Span] = None)
  case TypeApply(fn: Expr, targs: List[Type], span: Option[Span] = None)
  case Lambda(params: List[Param], body: Expr, span: Option[Span] = None)
  case If(cond: Expr, thenp: Expr, elsep: Option[Expr], span: Option[Span] = None)
  case Match(scrutinee: Expr, cases: List[CaseClause], span: Option[Span] = None)
  case Block(stats: List[Statement], expr: Expr, span: Option[Span] = None)
  case Tuple(elems: List[Expr], span: Option[Span] = None)
  case New(tpe: Type, args: List[Expr], span: Option[Span] = None)
  case Infix(lhs: Expr, op: String, rhs: Expr, span: Option[Span] = None)
  case Prefix(op: String, expr: Expr, span: Option[Span] = None)
  case Ascription(expr: Expr, tpe: Type, span: Option[Span] = None)
  case Interpolated(parts: List[String], args: List[Expr], span: Option[Span] = None)

enum LiteralValue:
  case IntLit(value: Long)
  case DoubleLit(value: Double)
  case StringLit(value: String)
  case CharLit(value: Char)
  case BoolLit(value: Boolean)
  case UnitLit
  case NullLit

// ============================================
// Statements / Definitions
// ============================================

enum Statement:
  case ValDef(name: String, tpe: Option[Type], rhs: Expr, span: Option[Span] = None)
  case VarDef(name: String, tpe: Option[Type], rhs: Expr, span: Option[Span] = None)
  case DefDef(name: String, tparams: List[TypeParam], params: List[List[Param]], retTpe: Option[Type], body: Expr, span: Option[Span] = None)
  case TypeAlias(name: String, tparams: List[TypeParam], rhs: Type, span: Option[Span] = None)
  case ClassDef(name: String, tparams: List[TypeParam], params: List[Param], parents: List[Type], body: List[Statement], isCase: Boolean, span: Option[Span] = None)
  case ObjectDef(name: String, parents: List[Type], body: List[Statement], isCase: Boolean, span: Option[Span] = None)
  case TraitDef(name: String, tparams: List[TypeParam], parents: List[Type], body: List[Statement], span: Option[Span] = None)
  case EnumDef(name: String, tparams: List[TypeParam], parents: List[Type], cases: List[EnumCase], span: Option[Span] = None)
  case ExprStmt(expr: Expr, span: Option[Span] = None)

case class EnumCase(
  name: String,
  params: List[Param],
  span: Option[Span] = None
)

case class CaseClause(
  pattern: Pattern,
  guard: Option[Expr],
  body: Expr,
  span: Option[Span] = None
)

case class Param(
  name: String,
  tpe: Option[Type],
  default: Option[Expr],
  span: Option[Span] = None
)

case class TypeParam(
  name: String,
  variance: Variance,
  bounds: TypeBounds,
  span: Option[Span] = None
)

enum Variance:
  case Invariant, Covariant, Contravariant

case class TypeBounds(
  lower: Option[Type],
  upper: Option[Type]
)

// ============================================
// Patterns
// ============================================

enum Pattern:
  case Wildcard(span: Option[Span] = None)
  case Binding(name: String, pattern: Option[Pattern], span: Option[Span] = None)
  case Literal(value: LiteralValue, span: Option[Span] = None)
  case Constructor(name: String, args: List[Pattern], span: Option[Span] = None)
  case Tuple(elems: List[Pattern], span: Option[Span] = None)
  case Typed(pattern: Pattern, tpe: Type, span: Option[Span] = None)
  case Alternative(patterns: List[Pattern], span: Option[Span] = None)

// ============================================
// Types
// ============================================

enum Type:
  case Named(name: String, args: List[Type], span: Option[Span] = None)
  case Function(params: List[Type], result: Type, span: Option[Span] = None)
  case Tuple(elems: List[Type], span: Option[Span] = None)
  case Union(types: List[Type], span: Option[Span] = None)
  case Intersection(types: List[Type], span: Option[Span] = None)
  case Wildcard(bounds: TypeBounds, span: Option[Span] = None)

object Type:
  val Unit: Type = Named("Unit", Nil)
  val Boolean: Type = Named("Boolean", Nil)
  val Int: Type = Named("Int", Nil)
  val Long: Type = Named("Long", Nil)
  val Double: Type = Named("Double", Nil)
  val String: Type = Named("String", Nil)
  val Char: Type = Named("Char", Nil)
  val Any: Type = Named("Any", Nil)
  val Nothing: Type = Named("Nothing", Nil)
  val Null: Type = Named("Null", Nil)
