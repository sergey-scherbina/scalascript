package ssc3

// The typed AST — SSC3 Tier 0 (v3/specs/20-core-language.md §2).
//
// This is the "typed projection" half of v3/specs/40-front-on-uniml.md: a SEALED ADT, so lowering
// gets exhaustivity from the compiler instead of re-deriving shape from strings. The lossless
// UniML CST is the storage underneath and stays canonical; this is what the rest of the compiler
// consumes.
//
// It is v3's OWN type, which is why it can exist before UniML's projection does. When that lands,
// it produces these nodes and only the parsing half of the interim front is replaced.

/** Source position, kept so a diagnostic can point at what the user wrote. `line`/`col` are
  * 1-based. Carried on every expression rather than a few, because the one you did not carry it on
  * is always the one an error lands in. */
final case class Pos(line: Int, col: Int):
  def show: String = line.toString + ":" + col.toString

object Pos:
  /** `0:0` means "the file as a whole" — for a diagnostic that has no single line to point at,
    * such as a missing `main`. It is a real convention rather than a missing value, and the
    * message names the file, so it is actionable. */
  val none: Pos = Pos(0, 0)

enum Expr:
  case IntLit(v: Long, pos: Pos)
  case DoubleLit(v: Double, pos: Pos)
  case StrLit(v: String, pos: Pos)
  /** `parts` is always one longer than `exprs`: text, hole, text, hole, …, text. */
  case Interp(parts: List[String], exprs: List[Expr], pos: Pos)
  case BoolLit(v: Boolean, pos: Pos)
  case UnitLit(pos: Pos)
  /** The CODE POINT. A char is an integer that prints as a character — that is the reference
    * lane's model (`CharV extends IntV`), not a simplification made here. */
  case CharLit(code: Int, pos: Pos)
  case Name(n: String, pos: Pos)
  case Bin(op: String, l: Expr, r: Expr, pos: Pos)
  case Neg(e: Expr, pos: Pos)
  case Not(e: Expr, pos: Pos)
  case Call(fn: String, args: List[Expr], pos: Pos)
  /** `recv.name` and `recv.name(args)` alike — a getter is a call with no arguments, which is what
    * it is on every lane already. Keeping them one node means the lowering has one case, not two
    * that must agree. */
  case MethodCall(recv: Expr, name: String, args: List[Expr], pos: Pos)
  case If(c: Expr, t: Expr, e: Option[Expr], pos: Pos)
  case While(c: Expr, body: Expr, pos: Pos)
  case Block(stmts: List[Stmt], result: Option[Expr], pos: Pos)
  case Assign(name: String, value: Expr, pos: Pos)
  /** `a(i) = v`. Separate from `Assign` because the target is an INDEX, not a name — and separate
    * from a method call because the IR has `ArrSet`, which both lanes already implement. */
  case Update(arr: Expr, index: Expr, value: Expr, pos: Pos)
  /** `f(width = 3)`. Only ever appears INSIDE an argument list, and is resolved to a position
    * before lowering — the IR has no notion of an argument's name. */
  case NamedArg(name: String, value: Expr, pos: Pos)
  case Match(scrut: Expr, arms: List[MatchArm], pos: Pos)
  case Lambda(params: List[Param], body: Expr, pos: Pos)
  case Try(body: Expr, exn: String, handler: Expr, pos: Pos)

/** Patterns, Tier 0. Constructor arguments are a binder or a wildcard — NESTED patterns are
  * refused by name rather than half-supported, because a half-supported pattern silently matches
  * the wrong thing. */
enum Pat:
  case PWild(pos: Pos)
  case PBind(name: String, pos: Pos)
  case PLit(value: Expr, pos: Pos)
  case PCtor(name: String, args: List[Pat], pos: Pos)
  /** `case A | B =>`. The alternatives BIND NOTHING — Scala requires every alternative to bind the
    * same names, and the only way to satisfy that without analysis is to bind none. So this is a
    * pure disjunction of tests and needs no per-alternative environment. */
  case PAlt(alts: List[Pat], pos: Pos)

object Pat:
  def posOf(p: Pat): Pos = p match
    case PWild(x)       => x
    case PBind(_, x)    => x
    case PLit(_, x)     => x
    case PCtor(_, _, x) => x
    case PAlt(_, x)     => x

/** `guard` is the optional `if cond` between the pattern and the `=>`. It is part of the ARM, not
  * of the pattern, because it is an ordinary expression evaluated with the pattern's bindings in
  * scope — putting it in `Pat` would have made every pattern position able to carry code. */
final case class MatchArm(pat: Pat, guard: Option[Expr], body: Expr)

enum Stmt:
  case Val(name: String, value: Expr, mutable: Boolean, pos: Pos)
  case Exp(e: Expr)

/** `default` is the `= expr` after the type: `def f(x: Int = 5)` and `case class C(x: Int = 5)`.
  * Held as the unevaluated EXPRESSION, so a call site that omits the argument gets the expression
  * substituted and evaluated there — which is what Scala does and what makes `= Nil` cheap. */
final case class Param(name: String, pos: Pos, default: Option[Expr] = None)
/** A `case class` declaration. Only the constructor SHAPE is kept: the field names and their
  * order, which is exactly what the IR's type table needs and all a Tier 0 program can use. */
/** `parents` are the traits/classes named after `extends`/`with`. They are kept, not discarded,
  * for ONE purpose: a class inherits its parents' concrete methods. No type checking is done with
  * them — dispatch is by the receiver's tag at run time, which is what makes traits possible at
  * Tier 0 at all. */
final case class ClassDef(name: String, fields: List[Param], methods: List[Def],
                          parents: List[String], pos: Pos)

/** A `trait`: a name, the methods it declares (bodies optional) and its own parents. Abstract
  * members carry no body and exist only so a call to them is not an unknown name; concrete ones are
  * inherited by every class that extends it. */
final case class TraitDef(name: String, methods: List[Def], parents: List[String], pos: Pos)
final case class Def(name: String, params: List[Param], body: Expr, pos: Pos)
/** A `.ssc` file is a SCRIPT: `def`s are declarations and everything else is the program body,
  * executed in order. That is the project's model, not a v3 invention — measured on the corpus,
  * top-level statements were the single largest reason v3 could not read a case. */
/** An `object`'s members, flattened. The object itself carries no runtime value at Tier 0 — it is
  * a NAMESPACE — so `object Foo: def bar(…)` becomes a top-level `Foo.bar`, which is also how the
  * other lanes lower it. */
final case class ObjectDef(name: String, defs: List[Def], pos: Pos)

final case class Program(defs: List[Def], topLevel: List[Stmt], classes: List[ClassDef],
                         objects: List[ObjectDef], traits: List[TraitDef] = Nil)

object Expr:
  def posOf(e: Expr): Pos = e match
    case IntLit(_, p)     => p
    case DoubleLit(_, p)  => p
    case StrLit(_, p)     => p
    case Interp(_, _, p)  => p
    case BoolLit(_, p)    => p
    case UnitLit(p)       => p
    case CharLit(_, p)    => p
    case Name(_, p)       => p
    case Bin(_, _, _, p)  => p
    case Neg(_, p)        => p
    case Not(_, p)        => p
    case Call(_, _, p)    => p
    case MethodCall(_, _, _, p) => p
    case If(_, _, _, p)   => p
    case While(_, _, p)   => p
    case Block(_, _, p)   => p
    case Assign(_, _, p)  => p
    case Update(_, _, _, p) => p
    case NamedArg(_, _, p) => p
    case Match(_, _, p)   => p
    case Lambda(_, _, p)  => p
    case Try(_, _, _, p)  => p
