package ssc.bridge

import ssc.{Term as T, Arm, Def as CDef, Program}

/** Op-argument lifting for the bridged v1 pipeline (BUGS.md `v2-op-arg-lifting`).
 *
 *  v1 semantics: user code never observes a raw effect value — every position an
 *  unresolved `Op` can flow into must defer the enclosing computation into the
 *  Op's continuation. The v2 kernel already threads Ops at `Let` bindings and
 *  `Seq` statements (Runtime's letThreadOp/seqThreadOp, unconditional), at
 *  method receivers (methodOp), arith operands (arithOp) and function position
 *  (applyFallback). The remaining leak is ARGUMENT position: `formatMoney(x)`,
 *  `println(x)`, `Ctor(x)`, `f(x) match …`, `if f(x) then …` where `x`/`f(x)`
 *  evaluates to an Op executed the consumer immediately on the raw Op
 *  (busi ledger: `formatMoney(accountBalance(...))` printed `Op(Journal.read,…)`).
 *
 *  The lift CANNOT live in the shared kernel: the Mira/hm lane passes Op values
 *  to functions legitimately (`runState(k(r), s)` must receive the Op and match
 *  on it — deferring there would forward the op past its own handler). So this
 *  pass rewrites only the BRIDGE-emitted CoreIR: any App/Ctor/Prim argument (or
 *  Match scrutinee / If condition) that may evaluate to an Op is bound through a
 *  `Let`, and the kernel's existing Let-threading performs the deferral.
 *
 *  `mayOp` is conservative but shape-aware: only terms that can transitively
 *  reach an `App` or a dynamic method/effect dispatch (the Op sources in
 *  bridged code) are wrapped. Pure-arith call arguments (`loop(n - 1)`) stay untouched,
 *  preserving the compiler's syntactic TCO / while-fusion fast paths. `handle`
 *  applications need no special case: their arguments are Lams (thunk + handler),
 *  which are values and never wrapped. `While` is left alone entirely (its
 *  condition re-evaluates per iteration — a Let outside would evaluate once).
 *
 *  CoreIR is de Bruijn (`Local(0)` = innermost binder), so inserting a Let
 *  shifts the free locals of the sibling terms that move under the new binders —
 *  `shift` below is the standard cutoff-based shifter. Let bindings evaluate
 *  with PROGRESSIVE extension (rhs_i sees rhs_0..i-1), matching the kernel.
 */
object OpAnf:

  def lift(p: Program): Program =
    Program(p.defs.map(d => CDef(d.name, tx(d.body))), tx(p.entry))

  /** May evaluating this term return a raw `DataV("Op", …)`?
   *  Op sources in bridged code: any function call (App), ordinary/extension
   *  dynamic dispatch, and reusable/one-shot effect dispatch. Wrappers
   *  that thread Ops (Let/Seq/If/Match) may RETURN a rewrapped Op when any
   *  constituent may. Lam/Ctor/Lit/Local/Global are values (a Ctor FIELD may
   *  hold an Op, but the Ctor itself is not one — fields are wrapped at their
   *  own position by `tx`). */
  private def mayOp(t: T): Boolean = t match
    case T.App(_, _) => true
    case T.Prim(op, as) =>
      ssc.Compiler.primitiveMayProduceAutoThreadOp(op) || as.exists(mayOp)
    case T.Let(rhs, b)           => rhs.exists(mayOp) || mayOp(b)
    case T.LetRec(_, b)          => mayOp(b)
    case T.If(c, x, y)           => mayOp(c) || mayOp(x) || mayOp(y)
    case T.Match(s, arms, d)     => mayOp(s) || arms.exists(a => mayOp(a.body)) || d.exists(mayOp)
    case T.Seq(ts)               => ts.exists(mayOp)
    case _                       => false // Lit, Local, Global, Lam, Ctor, While

  /** `handle(expr)(handler)` paren form: the body EXPRESSION's Op must reach
   *  `handle` RAW — letifying it would thread the op past its own handler
   *  (the block form is immune: its body arrives as a Lam thunk). */
  private def isHandleStage(f: T, args: List[T]): Boolean =
    f == T.Global("handle") && args.length == 1

  private def isEffectPrim(op: String): Boolean =
    op == "effect.handle" || op == "effect.perform" ||
      op == "effect.perform.oneshot" || op == "effect.pure"

  private def tx(t: T): T = t match
    case T.App(f, as) =>
      val f2 = tx(f); val as2 = as.map(tx)
      val positions = f2 :: as2
      if !isHandleStage(f2, as2) && positions.exists(mayOp) then
        letify(positions, lifted => T.App(lifted.head, lifted.tail))
      else T.App(f2, as2)
    case T.Prim(op, as) =>
      val as2 = as.map(tx)
      if !isEffectPrim(op) && as2.exists(mayOp) then letify(as2, ls => T.Prim(op, ls))
      else T.Prim(op, as2)
    case T.Ctor(tag, fs) =>
      val fs2 = fs.map(tx)
      if fs2.exists(mayOp) then letify(fs2, ls => T.Ctor(tag, ls))
      else T.Ctor(tag, fs2)
    case T.Match(s, arms, d) =>
      val s2 = tx(s)
      val arms2 = arms.map(a => Arm(a.tag, a.arity, tx(a.body)))
      val d2 = d.map(tx)
      if mayOp(s2) then
        T.Let(List(s2), T.Match(T.Local(0),
          arms2.map(a => Arm(a.tag, a.arity, shift(a.body, 1, a.arity))),
          d2.map(shift(_, 1, 0))))
      else T.Match(s2, arms2, d2)
    case T.If(c, x, y) =>
      val c2 = tx(c); val x2 = tx(x); val y2 = tx(y)
      if mayOp(c2) then T.Let(List(c2), T.If(T.Local(0), shift(x2, 1, 0), shift(y2, 1, 0)))
      else T.If(c2, x2, y2)
    case T.Lam(ar, b)      => T.Lam(ar, tx(b))
    case T.Let(rhs, b)     => T.Let(rhs.map(tx), tx(b))
    case T.LetRec(lams, b) => T.LetRec(lams.map(tx), tx(b))
    case T.Seq(ts)         => T.Seq(ts.map(tx))
    case T.While(c, b) =>
      val c2 = tx(c); val b2 = tx(b)
      if mayOp(c2) || mayOp(b2) then effectAwareWhile(c2, b2)
      else T.While(c2, b2)
    case _                 => t // Lit, Local, Global

  private def effectAwareWhile(cond: T, body: T): T =
    val condInLoop = shift(cond, 1, 0)
    val bodyInCondition = shift(body, 2, 0)
    val recur = T.App(T.Local(1), Nil)
    val loop = T.Lam(0, T.Let(
      List(condInLoop),
      T.If(
        T.Local(0),
        T.Seq(List(bodyInCondition, recur)),
        T.Lit(ssc.Const.CUnit),
      ),
    ))
    T.LetRec(List(loop), T.App(T.Local(0), Nil))

  /** Values whose evaluation is effect-free and position-independent: they
   *  stay IN PLACE (shifted under the new binders) instead of being Let-bound.
   *  Binding literals was not just noise — a bound `Lit("+")` turned
   *  `__arith__(Lit(+), …)` into `__arith__(Local, …)`, demoting the compile
   *  from Prims.arithOp (full dispatch: Map + Tuple2, char semantics) to the
   *  weaker resolve-table arith (string-concat fallback) — busi litdoc's
   *  `attrs + (k -> v)` came back as a concatenated STRING. Keeping Lits in
   *  place also preserves the FastCode shapes keyed on literal names. */
  private def isPure(t: T): Boolean = t match
    case T.Lit(_) | T.Global(_) | T.Lam(_, _) | T.Local(_) => true
    case _                                                 => false

  /** Bind the impure args through a Let (progressive extension: rhs_i moves
   *  under i prior binders); pure args stay in place, shifted by the total
   *  binder count. After binding b0..b(n-1), Local(0) is the LAST binding, so
   *  bind-index i maps to Local(n-1-i). */
  private def letify(args: List[T], rebuild: List[T] => T): T =
    val bindIdx = args.zipWithIndex.collect { case (a, i) if !isPure(a) => i }
    val n = bindIdx.length
    val rhs = bindIdx.zipWithIndex.map((argI, bindI) => shift(args(argI), bindI, 0))
    val bindPos = bindIdx.zipWithIndex.toMap // arg index -> bind index
    val rebuilt = args.zipWithIndex.map((a, i) =>
      bindPos.get(i) match
        case Some(bi) => T.Local(n - 1 - bi)
        case None     => shift(a, n, 0))
    T.Let(rhs, rebuild(rebuilt))

  /** Standard de Bruijn shift: add `d` to every Local >= cutoff `c`. */
  private def shift(t: T, d: Int, c: Int): T =
    if d == 0 then t
    else t match
      case T.Local(i)        => if i >= c then T.Local(i + d) else t
      case T.Lit(_) | T.Global(_) => t
      case T.Lam(ar, b)      => T.Lam(ar, shift(b, d, c + ar))
      case T.App(f, as)      => T.App(shift(f, d, c), as.map(shift(_, d, c)))
      case T.Let(rhs, b) =>
        T.Let(rhs.zipWithIndex.map((r, i) => shift(r, d, c + i)), shift(b, d, c + rhs.length))
      case T.LetRec(lams, b) =>
        val n = lams.length
        T.LetRec(lams.map(shift(_, d, c + n)), shift(b, d, c + n))
      case T.If(a, x, y)     => T.If(shift(a, d, c), shift(x, d, c), shift(y, d, c))
      case T.Ctor(tag, fs)   => T.Ctor(tag, fs.map(shift(_, d, c)))
      case T.Match(s, arms, df) =>
        T.Match(shift(s, d, c),
          arms.map(a => Arm(a.tag, a.arity, shift(a.body, d, c + a.arity))),
          df.map(shift(_, d, c)))
      case T.Prim(op, as)    => T.Prim(op, as.map(shift(_, d, c)))
      case T.While(cond, b)  => T.While(shift(cond, d, c), shift(b, d, c))
      case T.Seq(ts)         => T.Seq(ts.map(shift(_, d, c)))
