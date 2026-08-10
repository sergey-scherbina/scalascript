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
  /** `tparams` are the definition's own type-parameter NAMES and `bounds` the context bounds on
    * them, as `(name, boundHead)` — `[A: Monoid]` is `("A", "Monoid")`, meaning a `using Monoid[A]`
    * parameter. Kept since 2026-08-09: without them a front cannot tell a type VARIABLE from a
    * type, which is the whole of instance resolution, and the v3 projection had to refuse `using`
    * outright (BUGS.md v3-uniml-def-has-no-type-parameters).
    *
    * Defaulted, so every other construction of a `Def` in this file and its tests compiles
    * unchanged. */
  final case class Def(name: String, params: Vector[Param], ret: Option[TypeRef], body: Expr, span: SourceSpan,
                       tparams: Vector[String] = Vector.empty,
                       bounds: Vector[(String, String)] = Vector.empty) extends Decl
  final case class CaseClass(name: String, fields: Vector[Param], parent: Option[String], methods: Vector[Def], span: SourceSpan) extends Decl
  final case class EnumDecl(name: String, cases: Vector[EnumCase], span: SourceSpan) extends Decl
  /** One `case` of an enum. Its fields are constructor parameters, so they are `Param`s — the same
    * node a `def`'s and a case class's are, because the CST gives all three the same shape. */
  final case class EnumCase(name: String, fields: Vector[Param], span: SourceSpan) extends Node
  final case class Given(name: Option[String], tpe: Option[TypeRef], body: Option[Expr], span: SourceSpan) extends Decl
  /** `extension (r: T) def m … `. The RECEIVER was dropped for the whole corpus — an extension
    * method without the thing it extends is not a smaller AST, it is a wrong one. Found by the
    * token census only after that census learned to judge tokens, since `ext.recv` is a token. */
  final case class Extension(recv: Option[Param], defs: Vector[Def], span: SourceSpan) extends Decl
  /** A top-level expression statement — `.ssc` allows them, and they are 96% of
    * the corpus's declaration slots, so modelling them is not an edge case. */
  final case class TopExpr(expr: Expr, span: SourceSpan) extends Decl
  /** `object O` and `case object O`. `isCase` distinguishes them; without it a `case object` and
    * an empty `object` project identically, which is a wrong answer wherever the tag matters.
    *
    * `parents` is the `extends` clause. It used to be ERASED in the CST, which is right for the v2
    * lane and wrong for a front: `case object SqlNull extends SqliteValue` is a nullary constructor
    * OF that trait, and a constructor with no hierarchy is one no `match` on the trait reaches. */
  final case class ObjectDecl(name: String, parents: Vector[String], members: Vector[Decl],
                             isCase: Boolean, span: SourceSpan) extends Decl
  /** `given n: T with { defs }` — a typeclass instance. Distinct from `Given` because it has
    * MEMBERS rather than a right-hand side, which is the whole difference at the use site. */
  final case class GivenObject(name: Option[String], tpe: Option[TypeRef], members: Vector[Decl], span: SourceSpan)
      extends Decl
  /** `effect E { ops }` / `multi effect E { ops }` — multi-shot when `multi` is present. */
  final case class EffectDecl(name: String, multi: Boolean, ops: Vector[Decl], span: SourceSpan) extends Decl
  /** An anonymous `given` — parsed, and genuinely carrying nothing. It shares the `spike.sealed`
    * kind with imports, so telling the two apart is what the roles are for.
    *
    * Carrying nothing is a fact about the construct here, not a shortcut. It used to be a fact
    * about IMPORTS too, and that was a defect rather than a property: see `ImportDecl`. */
  final case class NoOpDecl(span: SourceSpan) extends Decl

  /** `import a.b.c`, `import a.b.{x, y}`, `import a.b.*`, and the Markdown link-import
    * `[name](path)`. `wildcard` is the `.*` form; `selectors` the `{x, y}` names.
    *
    * The path was NOT always reachable — see `NoOpDecl` above for what it looked like before, and
    * why a count could never have found it. */
  final case class ImportDecl(path: String, selectors: Vector[String], wildcard: Boolean, span: SourceSpan)
      extends Decl
  /** `trait T extends A with B: members` — and a plain `class`, which the dialect parses the same
    * way. `keyword` is which of the two was written, since v3 has a separate node for each.
    *
    * It used to VANISH into `NoOpDecl`, and that is the most instructive defect this projection has
    * had: a construct consumed into a contentless node is invisible to the parse-error count, to
    * the silent-drop census AND to the coverage figure, all at once. Nothing UniML measures about
    * itself could see it. v3's front differential found it in one run, because comparing against a
    * second implementation asks a question that self-measurement cannot. */
  final case class TraitDecl(keyword: String, name: String, parents: Vector[String],
                             members: Vector[Decl], span: SourceSpan) extends Decl
  /** `val id: String` with no `=` — an ABSTRACT member, legal inside a trait or class body. It has
    * no right-hand side, so it is a declaration rather than a `ValDef`, and modelling it as one
    * would need an initialiser that was never written. It only became reachable once traits stopped
    * vanishing: nothing else in the corpus puts one where the projection could see it. */
  final case class AbstractVal(name: String, span: SourceSpan) extends Decl
  final case class UnsupportedDecl(kind: String, span: SourceSpan) extends Decl

  /** `byName` marks `x: => A`. Defaulted so every existing construction site compiles untouched —
    * only a `def`'s parameter list can carry the arrow. */
  final case class Param(name: String, tpe: Option[TypeRef], default: Option[Expr], using_ : Boolean, span: SourceSpan,
                         byName: Boolean = false)
      extends Node

  sealed trait Expr extends Node
  final case class IntLit(value: String, span: SourceSpan) extends Expr
  /** `'x'`. The dialect lexes a char as `spike.int` whose LEXEME keeps the quotes, so the code and
    * the spelling are both recoverable — but only if the projection looks. It did not, and `'x'`
    * arrived as `IntLit("120")`, indistinguishable from the integer 120. `println('x')` prints `x`
    * and `println(120)` prints `120`; the language's Char IS an integer that prints differently,
    * which is exactly why the distinction has to survive the projection rather than be recovered
    * downstream. `code` is the decoded code point, matching `IntLit`'s convention of holding the
    * decoded text. */
  final case class CharLit(code: String, span: SourceSpan) extends Expr
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
  /** `val x = e` and `var x = e`. `isVar` is not decoration: without it the two project
    * IDENTICALLY, and a `var` read as a `val` makes every later assignment to it a refusal. Found
    * 2026-08-06 by v3's front differential — UniML printed `(val "counter" …)` where v3's own front
    * printed `(var "counter" …)` for the same source. A wrong answer, not a smaller tree. */
  final case class ValDef(name: String, rhs: Expr, isVar: Boolean, span: SourceSpan) extends Expr
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
  final case class Throw(value: Expr, span: SourceSpan) extends Expr
  /** `try b catch h finally f`. The handler is an expression, not a list of arms: the dialect
    * accepts a partial-function literal, a braceless `case` run, or any `PartialFunction` VALUE
    * (`ScalaSpike.parseTry`), and flattening the first two into arms would misrepresent the
    * third as something it is not. */
  final case class Try(body: Expr, handler: Option[Expr], finalizer: Option[Expr], span: SourceSpan) extends Expr
  final case class For(gens: Vector[ForGen], body: Expr, isYield: Boolean, span: SourceSpan) extends Expr
  /** One `x <- xs if p` of a for-comprehension. Several binders mean a tuple binder — the dialect
    * records them flat and lets the count say so (`ScalaSpike.parseForGen`). */
  final case class ForGen(binders: Vector[String], source: Expr, guard: Option[Expr], span: SourceSpan) extends Node
  /** `a to b` / `a until b` — `to` and `until` are identifiers, not operators, so they are their
    * own node rather than an `Infix`. */
  final case class RangeOp(op: String, from: Expr, to: Expr, span: SourceSpan) extends Expr
  /** `summon[T]`. The payload is the WHOLE type application as one string, joined without
    * separators, because that is what the dialect captures and what resolution matches on —
    * keeping only the head (`Show` of `Show[Int]`) never matches an instance. */
  final case class Summon(tpe: String, span: SourceSpan) extends Expr
  /** `{ case … }` — a partial-function literal. */
  final case class PartialFn(arms: Vector[Arm], span: SourceSpan) extends Expr
  final case class Quote(body: Expr, span: SourceSpan) extends Expr
  final case class Splice(body: Expr, span: SourceSpan) extends Expr
  final case class QuotedName(name: String, span: SourceSpan) extends Expr
  /** A `def` in statement position — a local function. `Block` holds expressions, so this is the
    * wrapper that lets one hold a declaration without `Block` becoming a list of `Node`. */
  final case class LocalDef(decl: Def, span: SourceSpan) extends Expr
  /** `???` — `Predef.???`, which the dialect gives its own leaf because it lowers to a prim. */
  final case class NotImplemented(span: SourceSpan) extends Expr
  /** `xs(i) = v` — an index assignment, distinct from `Assign` because the target is a call. */
  final case class IndexAssign(target: Expr, value: Expr, span: SourceSpan) extends Expr
  /** `x += 1`. The written operator is kept rather than desugared to `x = x + 1`: the CST says
    * `+=`, and desugaring is a lowering decision. */
  final case class CompoundAssign(name: String, op: String, value: Expr, span: SourceSpan) extends Expr
  /** `val (a, b) = e` — a destructuring val. */
  final case class TupleVal(names: Vector[String], rhs: Expr, span: SourceSpan) extends Expr
  /** `direct[F] { … }` and the optics markers `Focus`/`Prism`. They erase to their contents for
    * this dialect — it parses the language, it does not run the DSL — but they are what was
    * written, so they are kept rather than unwrapped. */
  final case class Marker(name: String, inner: Option[Expr], typeArgs: Vector[String], span: SourceSpan) extends Expr
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
  /** `case 1 =>`, `case '\n' =>`, `case "NULL" =>`, `case true =>`.
    *
    * `value` is an `Expr` — the SAME node the expression path builds for the same token — and it
    * used to be a `String`. Stringly-typed, it had lost the literal's KIND, and every kind decodes
    * differently: the integer arm handed over the raw lexeme, the string arm handed over decoded
    * CONTENT with the quotes gone, and a consumer holding `NULL` could not tell it from a name.
    * A char pattern came through as the raw `'\n'` and read as the backslash — `case '\n'` matched
    * character 92, which is a WRONG MATCH rather than a failure to compile. Carrying the node makes
    * the two paths incapable of disagreeing, because there is only one of them. */
  final case class PatLit(value: Expr, span: SourceSpan) extends Pattern
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
    case ObjectDecl(_, _, ms, _, _) => ms.flatMap(walk)
    case While(c, b, _)           => walk(c) ++ walk(b)
    case Tuple(es, _)             => es.flatMap(walk)
    case Def(_, ps, rt, b, _, _, _)     => ps.flatMap(walk) ++ rt.toVector.flatMap(walk) ++ walk(b)
    case Param(_, t, d, _, _, _)  => t.toVector.flatMap(walk) ++ d.toVector.flatMap(walk)
    case CaseClass(_, fs, _, ms, _) => fs.flatMap(walk) ++ ms.flatMap(walk)
    case EnumDecl(_, cs, _)       => cs.flatMap(walk)
    case TraitDecl(_, _, _, ms, _) => ms.flatMap(walk)
    case EnumCase(_, fs, _)       => fs.flatMap(walk)
    case Given(_, t, b, _)        => t.toVector.flatMap(walk) ++ b.toVector.flatMap(walk)
    case Extension(r, ds, _)      => r.toVector.flatMap(walk) ++ ds.flatMap(walk)
    case Infix(_, l, r, _)        => walk(l) ++ walk(r)
    case Prefix(_, e, _)          => walk(e)
    case Apply(f, as, _)          => walk(f) ++ as.flatMap(walk)
    case NamedArg(_, v, _)        => walk(v)
    case ListLit(es, _)           => es.flatMap(walk)
    case BlockApply(f, a, _)      => walk(f) ++ walk(a)
    case Throw(v, _)              => walk(v)
    case Try(b, h, f, _)          => walk(b) ++ h.toVector.flatMap(walk) ++ f.toVector.flatMap(walk)
    case For(gs, b, _, _)         => gs.flatMap(walk) ++ walk(b)
    case ForGen(_, s, g, _)       => walk(s) ++ g.toVector.flatMap(walk)
    case RangeOp(_, f, t, _)      => walk(f) ++ walk(t)
    case PartialFn(as, _)         => as.flatMap(walk)
    case Quote(b, _)              => walk(b)
    case Splice(b, _)             => walk(b)
    case LocalDef(d, _)           => walk(d)
    case IndexAssign(t, v, _)     => walk(t) ++ walk(v)
    case CompoundAssign(_, _, v, _) => walk(v)
    case TupleVal(_, r, _)        => walk(r)
    case Marker(_, i, _, _)       => i.toVector.flatMap(walk)
    case GivenObject(_, t, ms, _) => t.toVector.flatMap(walk) ++ ms.flatMap(walk)
    case EffectDecl(_, _, ops, _) => ops.flatMap(walk)
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
    case ValDef(_, r, _, _)       => walk(r)
    case Assign(_, r, _)          => walk(r)
    case _                        => Vector.empty)

  def unsupported(n: Node): Vector[Node] = walk(n).filter {
    case _: Unsupported     => true
    case _: UnsupportedDecl => true
    case _: PatUnsupported  => true
    case _                  => false
  }
