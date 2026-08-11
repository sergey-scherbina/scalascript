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
  /** A call to a HOST primitive, by name. The kernel's only door out (invariant I-1).
    *
    * No surface syntax builds one directly — `extern def readFile(path: String): String` declares
    * it, and the declaration's body IS this node. Distinct from `Call` on purpose: `Call` names a
    * function the module defines and an unknown one is a typo, while this names something the LANE
    * provides and an unknown one is a capability the lane lacks. Collapsing them would have turned
    * every misspelled function into a silent prim. */
  case Prim(name: String, args: List[Expr], pos: Pos)
  /** A performed effect operation, with its op id already RESOLVED.
    *
    * Produced by a rewrite in `Lower.programOf`, where the `effect` declarations are in scope, so
    * the lowering itself needs no extra parameter threaded through every case — the alternative was
    * a fifth argument on `lower`, and a list that one consumer forgets to pass is how the front ends
    * up with two notions of the same program. */
  case Perform(op: Int, args: List[Expr], pos: Pos)
  /** `handle(body) { case E.op(args…, k) => … }`, with op ids resolved and binder NAMES kept —
    * registers are allocated by the lowering, which is the only place that knows the frame. */
  case Handle(body: Expr, arms: List[HandleArm], pos: Pos)
  /** `k(v)` inside a handler arm. A distinct node because `k` is not a function: it names the
    * continuation register the protocol puts it in (`specs/10-ssc-ir.md` §3). */
  case Resume(k: String, value: Expr, pos: Pos)
  /** `recv.name` and `recv.name(args)` alike — a getter is a call with no arguments, which is what
    * it is on every lane already. Keeping them one node means the lowering has one case, not two
    * that must agree. */
  case MethodCall(recv: Expr, name: String, args: List[Expr], pos: Pos)

  /** `recv.name` with NO argument list written — a selection, not yet a call.
    *
    * WHY A SEPARATE CASE rather than a flag on `MethodCall`. The distinction is only between
    * `obj.m` and `obj.m()`, and both used to arrive as `MethodCall(_, _, Nil, _)`; a program that
    * passes a method as a value was therefore indistinguishable from one that calls it with no
    * arguments, so `xs.foldLeft(z)(m.combine)` lowered to a call taking none and the verifier
    * refused it (`v3-method-as-a-value`). A `Boolean` on `MethodCall` would have said the same
    * thing and broken all THIRTY of its pattern sites, every one of which would have gained a
    * wildcard for a fact it does not use.
    *
    * IT DOES NOT SURVIVE THE LOWERING. `Lower.resolveMethodRefs` turns each one into either a
    * `MethodCall` with no arguments — a field read, or a method that genuinely takes none — or a
    * lambda that calls it with the arguments it declares. So nothing downstream of that pre-pass
    * ever sees this node, which is why adding it costs one case here and one there rather than a
    * sweep. */
  case MethodRef(recv: Expr, name: String, pos: Pos)
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
  /** `f(a)(b)` — applying the RESULT of an expression. `Call` names a function; this one has an
    * expression in that slot, which is what a curried call and `foldLeft(z)(f)` need. */
  case Apply(fn: Expr, args: List[Expr], pos: Pos)
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
  /** `case s: String =>`, `case c: Circle =>`, `case _: Throwable =>` — a TYPE ASCRIPTION pattern.
    *
    * The test is NOMINAL and flat: the value's tag against the name, with no subtype graph. That is
    * what the reference front does (`ssc1-lower.ssc0:3559`, `__isTag__`), and matching it exactly is
    * the point — the frozen conformance goldens encode the reference's answers, including its
    * deliberate permissiveness about exception supertypes and its aliases (`List`/`Seq`/`Iterable`
    * for a cons cell, `Option` for `Some`/`None`).
    *
    * `inner` is the pattern the value is bound by once the test passes, which is a binder or a
    * wildcard in every case the surface syntax can produce. */
  case PType(name: String, inner: Pat, pos: Pos)

object Pat:
  def posOf(p: Pat): Pos = p match
    case PWild(x)       => x
    case PType(_, _, x) => x
    case PBind(_, x)    => x
    case PLit(_, x)     => x
    case PCtor(_, _, x) => x
    case PAlt(_, x)     => x

  /** The names a pattern BINDS. `Lower.patNames` is a one-line alias; it is here because
    * `Expr.boundNames` needs it and `Expr` cannot reach into the lowering. */
  def names(p: Pat): List[String] = p match
    case PBind(n, _)       => List(n)
    case PCtor(_, args, _) => args.flatMap(a => names(a))
    // `case s: String =>` binds `s`. Missing this arm would have left the binder out of the arm's
    // scope while the LOWERING bound it anyway — the name would resolve to whatever else was in
    // scope, or to nothing, which is a wrong answer rather than a compile error.
    case PType(_, inner, _) => names(inner)
    // An alternative binds nothing by construction — see `Pat.PAlt`.
    case _                 => Nil

/** `guard` is the optional `if cond` between the pattern and the `=>`. It is part of the ARM, not
  * of the pattern, because it is an ordinary expression evaluated with the pattern's bindings in
  * scope — putting it in `Pat` would have made every pattern position able to carry code. */
final case class MatchArm(pat: Pat, guard: Option[Expr], body: Expr)

enum Stmt:
  case Val(name: String, value: Expr, mutable: Boolean, pos: Pos)
  case Exp(e: Expr)
  /** A `def` inside a block — a LOCAL function. It stays a `Def` rather than becoming a `val` of a
    * lambda because it may be RECURSIVE, and a `val`-bound lambda has no name to call itself by. */
  case LocalDef(d: Def)

/** `default` is the `= expr` after the type: `def f(x: Int = 5)` and `case class C(x: Int = 5)`.
  * Held as the unevaluated EXPRESSION, so a call site that omits the argument gets the expression
  * substituted and evaluated there — which is what Scala does and what makes `= Nil` cheap. */
/** A parameter. `byName` marks `x: => A`: the argument is not evaluated at the call site.
  *
  * Defaulted so the eight other places that build a `Param` — lambdas, case-class fields, for-comp
  * desugaring — compile unchanged, because none of them can be by-name.
  *
  * v3 implements by-name as a FRONT transformation, not a runtime feature: the call site wraps the
  * argument in a zero-argument lambda and each use in the body calls it. That is how Scala does it,
  * and it means the IR, the executor and the bridge need to know nothing about it. */
final case class HandleArm(op: Int, params: List[String], k: String, body: Expr, pos: Pos)

/** `tpe` is the parameter's declared type AS WRITTEN — `Show[A]`, `Int`, `List[A]` — and `given_`
  * marks a parameter that came from a `(using …)` clause or a context bound.
  *
  * THE SECOND PLACE THIS FRONT CARRIES A TYPE, after `ObjectDef.givenOf`, and for the same reason:
  * `display(42)` has to find the instance that `Show[A]` selects when `A` is `Int`, and neither the
  * parameter's type nor the instance's can be reconstructed once discarded. It is TEXT, not a
  * parsed type — Tier 0 has nothing to parse it into, and text is exactly enough to substitute a
  * type argument into and compare (SPRINT §52, G2 stage 2a).
  *
  * Both are defaulted, so the eight other places that build a `Param` — lambdas, case-class fields,
  * for-comprehension desugaring — compile unchanged. */
final case class Param(name: String, pos: Pos, default: Option[Expr] = None,
                       byName: Boolean = false, tpe: Option[String] = None,
                       given_ : Boolean = false)
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
/** `tparams` are the names in `[A]`, `[A, B]`, `[A: Monoid]` — the type VARIABLES, so a call site
  * can tell `A` (something to solve for) from `Int` (something to match). Their bounds are not kept:
  * `[A: Monoid]` becomes a `given_` parameter of type `Monoid[A]` in `params`, which is what the
  * bound MEANS and is the form the resolution already understands. */
/** `givenParams` are the parameters a CONTEXT BOUND stands for — `[A: Monoid]` means
  * `(using Monoid[A])` — kept OUT of `params` on purpose.
  *
  * They belong to `params` by the time anything lowers them, and `Lower` puts them there. Putting
  * them there in the PARSER made v3's printed AST differ from UniML's on every program with a
  * context bound, because UniML drops the bound entirely: `front-diff.sh` reported
  * `std-semigroup-monoid` and `tagless-context-bounds` as disagreements, both of them
  * `(params (p "xs") (p "__given0"))` against `(params (p "xs"))`. The two fronts really do differ
  * here — that is `BUGS.md v3-uniml-def-has-no-type-parameters`, declared in the capability gate —
  * but the difference belongs in ONE place, not in every tree the differential compares. */
final case class Def(name: String, params: List[Param], body: Expr, pos: Pos,
                     tparams: List[String] = Nil, givenParams: List[Param] = Nil)
/** A `.ssc` file is a SCRIPT: `def`s are declarations and everything else is the program body,
  * executed in order. That is the project's model, not a v3 invention — measured on the corpus,
  * top-level statements were the single largest reason v3 could not read a case. */
/** An `object`'s members, flattened. The object itself carries no runtime value at Tier 0 — it is
  * a NAMESPACE — so `object Foo: def bar(…)` becomes a top-level `Foo.bar`, which is also how the
  * other lanes lower it. */
/** `vals` are the object's `val`/`var` members. They become module GLOBALS named `Object.member`,
  * because a `var` needs somewhere to live and a namespace is not a value in this language. */
/** `givenOf` is the TRAIT HEAD a `given name: T with` declares — `Monoid` for `Monoid[Int]` — and
  * `None` for an ordinary `object`.
  *
  * THE FIRST TYPE THIS FRONT CARRIES, and it is one word rather than a type. `summon[Monoid[A]]`
  * asks which instance a type selects, so something has to remember what each instance IS; the
  * head is the least that answers it and the most that Tier 0 can check, since the ARGUMENT (`Int`
  * vs `A`) is exactly what is erased. Recording more would be an unenforced notion of types in the
  * front, which invariant I-2 is about; recording less makes `given` indistinguishable from
  * `object` and `summon` unanswerable.
  *
  * Defaulted so every existing construction keeps compiling — and that is a hazard this repository
  * has paid for once already (`Loader.merge` silently dropped a defaulted `Program.effects`, and
  * the symptom appeared three layers away). Nothing rebuilds an `ObjectDef` field by field today;
  * anything that starts to must carry this. */
final case class ObjectDef(name: String, defs: List[Def], vals: List[Stmt.Val], pos: Pos,
                           givenOf: Option[String] = None)

final case class Program(defs: List[Def], topLevel: List[Stmt], classes: List[ClassDef],
                         objects: List[ObjectDef], traits: List[TraitDef] = Nil,
                         /** `effect E:` declarations. `TraitDef` is reused rather than copied: an
                           * effect declaration IS a name plus a list of operation signatures, which
                           * is what a trait body already parses to. Kept in its OWN field because
                           * the two mean different things downstream — a trait's methods are
                           * dispatched, an effect's are PERFORMED. */
                         effects: List[TraitDef] = Nil,
                         /** Declaration name → the path of the UNIT it came from, filled by
                           * `Loader.merge` and EMPTY for a single-file program. `merge`
                           * concatenates every unit's declarations, so this is the only surviving
                           * record of which file a declaration was written in — without it a
                           * diagnostic can only name the file the user typed on the command line,
                           * and prints an imported unit's line against it. */
                         origin: Map[String, String] = Map.empty):
  /** ONE PREDICATE FOR "THIS PROGRAM HAS NO CODE", because there were two and they had to agree.
    *
    * `Lower` refuses an empty program; `Loader` skips the prelude for one, since a prelude puts
    * names in scope FOR CODE and there is none. Same question, and it was written out twice in two
    * files — P-3 of `v3/PRELUDE-CORRECTNESS.md`, and mine from the day the prelude landed. The
    * duplication was survivable only because `prelude-gate.sh` fails when they diverge, which is
    * detection, not correctness.
    *
    * It lives on `Program` rather than in either caller: both of them are asking about a program. */
  def hasCode: Boolean = defs.nonEmpty || topLevel.nonEmpty

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
    case Prim(_, _, p)    => p
    case Perform(_, _, p)  => p
    case Handle(_, _, p)   => p
    case Resume(_, _, p)   => p
    case MethodCall(_, _, _, p) => p
    case MethodRef(_, _, p)     => p
    case If(_, _, _, p)   => p
    case While(_, _, p)   => p
    case Block(_, _, p)   => p
    case Assign(_, _, p)  => p
    case Update(_, _, _, p) => p
    case NamedArg(_, _, p) => p
    case Apply(_, _, p)   => p
    case Match(_, _, p)   => p
    case Lambda(_, _, p)  => p
    case Try(_, _, _, p)  => p

  /** THE DEEP EXPRESSION WALKER — children rebuilt first, then `f` applied to the result.
    *
    * IT LIVES HERE, ON THE AST, AND NOT IN THE LOWERING, and that placement was decided rather
    * than inherited. It was `Lower.mapDeep`, private, until `Loader.merge` needed to rewrite calls
    * inside a unit (P-6 of `v3/PRELUDE-CORRECTNESS.md`). The obvious move was to copy the traversal
    * into `Loader`, because the loader must NOT depend on the lowering — a module graph that knows
    * how programs are compiled is backwards. But look at what the two comments below record: both
    * `MethodRef` and `NamedArg` are cases this walker was MISSING, each for long enough to produce
    * a defect two passes away from the omission, and one of them cost 116 corpus cases. A copy
    * inherits today's cases and none of tomorrow's. A traversal over the AST is a property of the
    * AST; putting it here gives Loader the walker without giving it the lowering.
    *
    * `Lower` still calls it through a private one-line alias, so its twenty-odd call sites are
    * unchanged and there is exactly one traversal in the tree. */
  // EXPR-WALKER
  def mapDeep(e: Expr, f: Expr => Expr): Expr =
    def go(x: Expr): Expr = mapDeep(x, f)
    val rebuilt = e match
      case Expr.Call(fn, as, p)         => Expr.Call(fn, as.map(go), p)
      case Expr.MethodCall(r, n, as, p) => Expr.MethodCall(go(r), n, as.map(go), p)
      // The RECEIVER is traversed. Without this line `summon[Monoid[A]].combine` kept its
      // `__summon__` unresolved — the selection was rebuilt as itself and nothing looked inside —
      // and the failure surfaced two passes later as `call to unknown function '__summon__'`.
      case Expr.MethodRef(r, n, p)      => Expr.MethodRef(go(r), n, p)
      // A NAMED ARGUMENT'S VALUE, and this line is a fix for a hole that predates the node above.
      // `mapDeep` had no case for `NamedArg`, so it fell to the identity arm and nothing inside
      // `f(x = expr)` was ever rewritten — meaning `rewriteByName` and `resolveSummons` have both
      // been silently skipping named arguments. It surfaced because a `MethodRef` left unresolved
      // is REFUSED rather than merely unrewritten: 116 corpus cases stopped at
      // `std/scljet/btree.ssc:178`, whose `cursor.copy(stack = ReadonlyCursorFrame(read.page, 0))`
      // hides a selection two levels inside a named argument.
      case Expr.NamedArg(n, v, p)       => Expr.NamedArg(n, go(v), p)
      case Expr.Bin(o, l, r, p)         => Expr.Bin(o, go(l), go(r), p)
      case Expr.Neg(x, p)               => Expr.Neg(go(x), p)
      case Expr.Not(x, p)               => Expr.Not(go(x), p)
      case Expr.If(c, t, el, p)         => Expr.If(go(c), go(t), el.map(go), p)
      case Expr.While(c, b, p)          => Expr.While(go(c), go(b), p)
      case Expr.Assign(n, v, p)         => Expr.Assign(n, go(v), p)
      case Expr.Update(a, i, v, p)      => Expr.Update(go(a), go(i), go(v), p)
      case Expr.Apply(f2, as, p)        => Expr.Apply(go(f2), as.map(go), p)
      case Expr.Prim(n, as, p)          => Expr.Prim(n, as.map(go), p)
      case Expr.Perform(o, as, p)       => Expr.Perform(o, as.map(go), p)
      case Expr.Handle(b, arms, p)      => Expr.Handle(go(b), arms.map(a => a.copy(body = go(a.body))), p)
      case Expr.Resume(k, v, p)         => Expr.Resume(k, go(v), p)
      case Expr.Lambda(ps, b, p)        => Expr.Lambda(ps, go(b), p)
      case Expr.Try(b, x, h, p)         => Expr.Try(go(b), x, go(h), p)
      case Expr.Interp(parts, xs, p)    => Expr.Interp(parts, xs.map(go), p)
      case Expr.Match(sc, arms, p) =>
        Expr.Match(go(sc), arms.map(a => MatchArm(a.pat, a.guard.map(go), go(a.body))), p)
      case Expr.Block(sts, res, p) =>
        Expr.Block(sts.map { st => st match
          case Stmt.Val(n, v, mu, q) => Stmt.Val(n, go(v), mu, q)
          case Stmt.Exp(x)           => Stmt.Exp(go(x))
          case Stmt.LocalDef(d)      => Stmt.LocalDef(d.copy(body = go(d.body)))
        }, res.map(go), p)
      case other => other
    f(rebuilt)

  /** Every name BOUND somewhere inside this expression — lambda parameters, a `try`'s exception
    * binder, local `val`s, local `def`s and their parameters, and pattern binders.
    *
    * COLLECTED OVER THE WHOLE EXPRESSION, not per scope, and that is deliberate in both of its
    * readers. `Lower.checkArity` uses it to decide a name is NOT a global function, so an
    * over-large set makes it skip a check it could have made and can never make it invent an error
    * — which is the defect it was written for (P-1). `Loader.merge` uses it to decide a call is NOT
    * to a module-level `def`, so an over-large set leaves a call un-renamed, which is today's
    * behaviour and never a wrong redirection. Both want the same direction of error, which is why
    * one set serves both. */
  def boundNames(e: Expr): Set[String] =
    var out = Set.empty[String]
    mapDeep(e, x => { x match
      case Expr.Lambda(ps, _, _) => out = out ++ ps.map(_.name)
      case Expr.Try(_, n, _, _)  => out = out + n
      // PATTERN BINDERS, which this set did not carry when it served `checkArity` alone. `case
      // rows => …` binds `rows`, and a module-level `def rows` of the same name is a different
      // thing; leaving them out let both readers mistake the binder for the global.
      case Expr.Match(_, arms, _) => arms.foreach(a => out = out ++ Pat.names(a.pat))
      case Expr.Block(sts, _, _) => sts.foreach {
        case Stmt.Val(n, _, _, _) => out = out + n
        case Stmt.LocalDef(d)     => out = out + d.name ++ d.params.map(_.name)
        case _                    => ()
      }
      case _ => ()
    ; x })
    out
