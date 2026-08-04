package scalascript.uniml.dialect.scalascript

import scalascript.uniml.*

/** The TYPED view of a ScalaScript CST — the AST a compiler would consume.
  *
  * This is the second half of the split the ScalaScript 3 decision rests on
  * (`specs/uniml-ssc3-frontend.md` §3): the lossless CST is the STORAGE, and a
  * typed projection over it is the INTERFACE. Pattern-matching a type checker on
  * `Branch("spike.def", …)` gives no exhaustivity and no shape guarantees; this
  * gives both, while the CST underneath still reconstructs the source byte for
  * byte.
  *
  * Every node carries the SPAN of the CST node it came from, so a diagnostic can
  * point at source without the AST having to carry text.
  *
  * `Unsupported` is deliberate and load-bearing. A projection that silently
  * dropped what it did not model would report a clean AST for a file it half
  * understood — the same failure as a parser that swallows input. Coverage is
  * therefore MEASURABLE: count the Unsupported nodes over a corpus. */
object SpikeAst:

  sealed trait Node:
    def span: SourceSpan

  final case class Module(decls: Vector[Decl], span: SourceSpan) extends Node

  sealed trait Decl extends Node
  final case class Def(name: String, params: Vector[Param], body: Expr, span: SourceSpan) extends Decl
  final case class CaseClass(name: String, fields: Vector[String], parent: Option[String], span: SourceSpan) extends Decl
  final case class EnumDecl(name: String, cases: Vector[String], span: SourceSpan) extends Decl
  final case class Given(name: Option[String], span: SourceSpan) extends Decl
  final case class Extension(defs: Vector[Def], span: SourceSpan) extends Decl
  /** A top-level expression statement — `.ssc` allows them, and they are 96% of
    * the corpus's declaration slots, so modelling them is not an edge case. */
  final case class TopExpr(expr: Expr, span: SourceSpan) extends Decl
  final case class ObjectDecl(name: String, members: Vector[Decl], span: SourceSpan) extends Decl
  final case class UnsupportedDecl(kind: String, span: SourceSpan) extends Decl

  final case class Param(name: String, tpe: Option[String], using_ : Boolean, span: SourceSpan) extends Node

  sealed trait Expr extends Node
  final case class IntLit(value: String, span: SourceSpan) extends Expr
  final case class FloatLit(value: String, span: SourceSpan) extends Expr
  final case class StrLit(value: String, span: SourceSpan) extends Expr
  final case class Ident(name: String, span: SourceSpan) extends Expr
  final case class Infix(op: String, left: Expr, right: Expr, span: SourceSpan) extends Expr
  final case class Prefix(op: String, operand: Expr, span: SourceSpan) extends Expr
  final case class Apply(fn: Expr, args: Vector[Expr], span: SourceSpan) extends Expr
  final case class Select(receiver: Expr, member: String, span: SourceSpan) extends Expr
  final case class If(cond: Expr, thenE: Expr, elseE: Option[Expr], span: SourceSpan) extends Expr
  final case class Block(stmts: Vector[Expr], span: SourceSpan) extends Expr
  final case class Match(scrutinee: Expr, arms: Vector[Arm], span: SourceSpan) extends Expr
  final case class Lambda(params: Vector[String], body: Expr, span: SourceSpan) extends Expr
  final case class ValDef(name: String, rhs: Expr, span: SourceSpan) extends Expr
  final case class Assign(name: String, rhs: Expr, span: SourceSpan) extends Expr
  final case class While(cond: Expr, body: Expr, span: SourceSpan) extends Expr
  final case class Tuple(elems: Vector[Expr], span: SourceSpan) extends Expr
  final case class UnitLit(span: SourceSpan) extends Expr
  /** A CST shape this projection does not model yet. Never silently dropped. */
  final case class Unsupported(kind: String, span: SourceSpan) extends Expr

  final case class Arm(pattern: String, guard: Option[Expr], body: Expr, span: SourceSpan) extends Node

  /** Every node in the tree, for coverage counting and for tooling that walks
    * uniformly rather than by case. */
  def walk(n: Node): Vector[Node] = n +: (n match
    case Module(ds, _)            => ds.flatMap(walk)
    case TopExpr(e, _)            => walk(e)
    case ObjectDecl(_, ms, _)     => ms.flatMap(walk)
    case While(c, b, _)           => walk(c) ++ walk(b)
    case Tuple(es, _)             => es.flatMap(walk)
    case Def(_, ps, b, _)         => ps.flatMap(walk) ++ walk(b)
    case Extension(ds, _)         => ds.flatMap(walk)
    case Infix(_, l, r, _)        => walk(l) ++ walk(r)
    case Prefix(_, e, _)          => walk(e)
    case Apply(f, as, _)          => walk(f) ++ as.flatMap(walk)
    case Select(r, _, _)          => walk(r)
    case If(c, t, e, _)           => walk(c) ++ walk(t) ++ e.toVector.flatMap(walk)
    case Block(ss, _)             => ss.flatMap(walk)
    case Match(s, arms, _)        => walk(s) ++ arms.flatMap(walk)
    case Arm(_, g, b, _)          => g.toVector.flatMap(walk) ++ walk(b)
    case Lambda(_, b, _)          => walk(b)
    case ValDef(_, r, _)          => walk(r)
    case Assign(_, r, _)          => walk(r)
    case _                        => Vector.empty)

  def unsupported(n: Node): Vector[Node] = walk(n).filter {
    case _: Unsupported     => true
    case _: UnsupportedDecl => true
    case _                  => false
  }
