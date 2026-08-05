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

  /** A type as WRITTEN. The dialect captures a type as a run of tokens
    * (`ScalaSpike.captureType`/`expectType`) rather than a parsed structure, so this keeps the
    * source text. Inventing structure the CST does not have would be a second representation of
    * one fact — the thing the CST/projection split exists to avoid.
    *
    * It is a NODE rather than a `String` on purpose: it carries a span, so a later type error can
    * point at the annotation, and so the drop census can see that it was consumed at all. */
  final case class TypeRef(text: String, span: SourceSpan) extends Node

  sealed trait Decl extends Node
  final case class Def(name: String, params: Vector[Param], ret: Option[TypeRef], body: Expr, span: SourceSpan) extends Decl
  final case class CaseClass(name: String, fields: Vector[Param], parent: Option[String], methods: Vector[Def], span: SourceSpan) extends Decl
  final case class EnumDecl(name: String, cases: Vector[EnumCase], span: SourceSpan) extends Decl
  /** One `case` of an enum. Its fields are constructor parameters, so they are `Param`s — the same
    * node a `def`'s and a case class's are, because the CST gives all three the same shape. */
  final case class EnumCase(name: String, fields: Vector[Param], span: SourceSpan) extends Node
  final case class Given(name: Option[String], tpe: Option[TypeRef], body: Option[Expr], span: SourceSpan) extends Decl
  final case class Extension(defs: Vector[Def], span: SourceSpan) extends Decl
  /** A top-level expression statement — `.ssc` allows them, and they are 96% of
    * the corpus's declaration slots, so modelling them is not an edge case. */
  final case class TopExpr(expr: Expr, span: SourceSpan) extends Decl
  final case class ObjectDecl(name: String, members: Vector[Decl], span: SourceSpan) extends Decl
  /** A declaration the dialect parses and deliberately keeps NOTHING of: `import a.b.c`, the
    * Markdown link-import `[name](path)`, and an anonymous `given`. All three frame as
    * `spike.sealed`, a parse-only no-op for the v2 front, which resolves imports by other means.
    *
    * It carries no payload, and that is a statement about the CST rather than a shortcut here.
    * `ScalaSpike.parseImportStmt` CONSUMES the dotted path without attaching it, then calls
    * `sealedNoop`, which builds the frame from a single carrier token. So the path is not reachable
    * from this node — not dropped by the projection, absent from the frame.
    *
    * This was found by asserting the path was there and watching it fail, and it MATTERS BEYOND
    * this projection: a v3 front on UniML has to resolve imports, and the CST as it stands cannot
    * tell it what is imported. Filed in `uniml/BACKLOG.md` as a dialect-side gap. */
  final case class NoOpDecl(span: SourceSpan) extends Decl
  final case class UnsupportedDecl(kind: String, span: SourceSpan) extends Decl

  final case class Param(name: String, tpe: Option[TypeRef], default: Option[Expr], using_ : Boolean, span: SourceSpan)
      extends Node

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
  /** `f(label = value)`. A named argument is not an `Assign`: it names a PARAMETER, and a lowering
    * has to reorder it against the callee's declared order. Keeping it distinct is what lets it. */
  final case class NamedArg(name: String, value: Expr, span: SourceSpan) extends Expr
  final case class ListLit(elems: Vector[Expr], span: SourceSpan) extends Expr
  /** `f { … }` and its fewer-braces form `f: …`. Kept apart from `Apply` because the CST keeps
    * them apart; collapsing here would decide, at projection time, a question that belongs to the
    * lowering — and the projection's job is to say what was written. */
  final case class BlockApply(fn: Expr, arg: Expr, span: SourceSpan) extends Expr
  /** `s"a $x b"`. The dialect does NOT decompose an interpolation: `spike.interp` holds exactly two
    * tokens, the prefix and the raw string (`ScalaSpike.scala:1904`), with the embedded expressions
    * still inside the string as text. So keeping the raw text here loses nothing the CST had — the
    * parts are not subtrees that could be dropped. Splitting them is a re-lex, and it belongs
    * wherever the interpolation is given meaning, not here. */
  final case class Interp(prefix: String, raw: String, span: SourceSpan) extends Expr
  /** A CST shape this projection does not model yet. Never silently dropped. */
  final case class Unsupported(kind: String, span: SourceSpan) extends Expr

  /** Patterns are a TREE, and modelling them as a `String` was the projection's largest single
    * loss: `Arm.pattern` read its child with a helper that returns `""` for a branch, so every
    * structured pattern in the corpus — 4,579 of them, the biggest entry in the drop census —
    * arrived as the empty string. `case Some(x) =>` and `case _ =>` were indistinguishable, and
    * nothing said so, because an empty string is a perfectly well-formed `String`. */
  sealed trait Pattern extends Node
  final case class PatVar(name: String, span: SourceSpan) extends Pattern
  final case class PatWild(span: SourceSpan) extends Pattern
  final case class PatLit(value: String, span: SourceSpan) extends Pattern
  final case class PatCtor(name: String, args: Vector[Pattern], span: SourceSpan) extends Pattern
  final case class PatTuple(elems: Vector[Pattern], span: SourceSpan) extends Pattern
  final case class PatCons(head: Pattern, tail: Pattern, span: SourceSpan) extends Pattern
  final case class PatTyped(inner: Pattern, tpe: Option[TypeRef], span: SourceSpan) extends Pattern
  final case class PatAlt(alts: Vector[Pattern], span: SourceSpan) extends Pattern
  final case class PatBind(alias: String, inner: Pattern, span: SourceSpan) extends Pattern
  final case class PatUnsupported(kind: String, span: SourceSpan) extends Pattern

  final case class Arm(pattern: Pattern, guard: Option[Expr], body: Expr, span: SourceSpan) extends Node

  /** Every node in the tree, for coverage counting and for tooling that walks
    * uniformly rather than by case. */
  def walk(n: Node): Vector[Node] = n +: (n match
    case Module(ds, _)            => ds.flatMap(walk)
    case TopExpr(e, _)            => walk(e)
    case ObjectDecl(_, ms, _)     => ms.flatMap(walk)
    case While(c, b, _)           => walk(c) ++ walk(b)
    case Tuple(es, _)             => es.flatMap(walk)
    case Def(_, ps, rt, b, _)     => ps.flatMap(walk) ++ rt.toVector.flatMap(walk) ++ walk(b)
    case Param(_, t, d, _, _)     => t.toVector.flatMap(walk) ++ d.toVector.flatMap(walk)
    case CaseClass(_, fs, _, ms, _) => fs.flatMap(walk) ++ ms.flatMap(walk)
    case EnumDecl(_, cs, _)       => cs.flatMap(walk)
    case EnumCase(_, fs, _)       => fs.flatMap(walk)
    case Given(_, t, b, _)        => t.toVector.flatMap(walk) ++ b.toVector.flatMap(walk)
    case Extension(ds, _)         => ds.flatMap(walk)
    case Infix(_, l, r, _)        => walk(l) ++ walk(r)
    case Prefix(_, e, _)          => walk(e)
    case Apply(f, as, _)          => walk(f) ++ as.flatMap(walk)
    case NamedArg(_, v, _)        => walk(v)
    case ListLit(es, _)           => es.flatMap(walk)
    case BlockApply(f, a, _)      => walk(f) ++ walk(a)
    case Select(r, _, _)          => walk(r)
    case If(c, t, e, _)           => walk(c) ++ walk(t) ++ e.toVector.flatMap(walk)
    case Block(ss, _)             => ss.flatMap(walk)
    case Match(s, arms, _)        => walk(s) ++ arms.flatMap(walk)
    case Arm(p, g, b, _)          => walk(p) ++ g.toVector.flatMap(walk) ++ walk(b)
    case PatCtor(_, as, _)        => as.flatMap(walk)
    case PatTuple(es, _)          => es.flatMap(walk)
    case PatCons(h, t, _)         => walk(h) ++ walk(t)
    case PatTyped(i, t, _)        => walk(i) ++ t.toVector.flatMap(walk)
    case PatAlt(as, _)            => as.flatMap(walk)
    case PatBind(_, i, _)         => walk(i)
    case Lambda(_, b, _)          => walk(b)
    case ValDef(_, r, _)          => walk(r)
    case Assign(_, r, _)          => walk(r)
    case _                        => Vector.empty)

  def unsupported(n: Node): Vector[Node] = walk(n).filter {
    case _: Unsupported     => true
    case _: UnsupportedDecl => true
    case _: PatUnsupported  => true
    case _                  => false
  }
