package ssc.js

import ssc.{Term as T, Arm, Def as CDef, Program}

/** Op-ARGUMENT lifting for the v2-JS lane — a self-contained port of
 *  `ssc.bytecode.OpAnfNative` (kept standalone so the JS backend has no
 *  dependency on the ASM/bytecode module). The JS runtime threads a raw effect
 *  `Op` at Let bindings and Seq statements (see `$letThread`/`$seqThread` in
 *  JsBackend), but NOT at App/Ctor/Prim ARGUMENT, Match scrutinee or If
 *  condition position: `Cons(msg, resume(()))` would otherwise trap the resume
 *  Op inside the constructor. This pass binds any such sub-term that `mayOp`
 *  through a `Let`, so the Let-threading then defers the enclosing computation
 *  into the Op's continuation.
 *
 *  Applied ONLY when the program uses effect primitives (JsGen gates on this),
 *  so non-effect programs generate byte-identical JS to before.
 *
 *  DELIBERATELY EXCLUDED (mirrors OpAnfNative): `effect.handle` (the computation
 *  must reach the handler RAW), `effect.perform`/`perform.oneshot`/`pure` (Op
 *  sources / pure lift — their args are already values), and the legacy curried
 *  `handle(computation)` first stage. */
object OpAnf:

  def lift(p: Program): Program =
    Program(p.defs.map(d => CDef(d.name, tx(d.body))), tx(p.entry))

  /** Builtins that can produce an auto-thread Op even with ordinary-value args
   *  (they invoke user/plugin code, run nested CoreIR, read a stored Value, or
   *  are effect sources). Inlined from Runtime.operationProducingBuiltinNames so
   *  the JS backend stays standalone (the fast-loop compiles only CoreIR.scala).
   *  Over-lifting is correctness-safe (an extra Let that $letThread fast-paths);
   *  under-lifting would trap an Op in arg position, so keep this in sync. */
  private val opProducingBuiltins: Set[String] = Set(
    "__method__", "__method0__", "__effect__", "__methodOrExt__", "__effect_oneshot__",
    "__arithExt__", "__with_return__", "__tryCatch__", "__tryCatchFinally__", "__tryFinally__",
    "__lazyForce__", "coreir.eval", "fieldAt", "arr.get", "arr.pop", "cell.get", "cell.getOr",
    "effect.perform", "effect.perform.oneshot", "effect.handle",
  )

  /** May evaluating this term return a raw `Op`? Op sources: any App and the
   *  method-dispatch / effect prims. */
  private def mayOp(t: T): Boolean = t match
    case T.App(_, _) => true
    case T.Prim(op, as) =>
      opProducingBuiltins.contains(op) || as.exists(mayOp)
    case T.Let(rhs, b)       => rhs.exists(mayOp) || mayOp(b)
    case T.LetRec(_, b)      => mayOp(b)
    case T.If(c, x, y)       => mayOp(c) || mayOp(x) || mayOp(y)
    case T.Match(s, arms, d) => mayOp(s) || arms.exists(a => mayOp(a.body)) || d.exists(mayOp)
    case T.Seq(ts)           => ts.exists(mayOp)
    case _                   => false // Lit, Local, Global, Lam, Ctor, While

  private def isEffectPrim(op: String): Boolean =
    op == "effect.handle" || op == "effect.perform" ||
      op == "effect.perform.oneshot" || op == "effect.pure"

  private def isHandleStage(f: T, args: List[T]): Boolean =
    f == T.Global("handle") && args.length == 1

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
    case T.While(c, b)     => T.While(tx(c), tx(b))
    case _                 => t // Lit, Local, Global

  private def isPure(t: T): Boolean = t match
    case T.Lit(_) | T.Global(_) | T.Lam(_, _) | T.Local(_) => true
    case _                                                 => false

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

  private def shift(t: T, d: Int, c: Int): T =
    if d == 0 then t
    else t match
      case T.Local(i)             => if i >= c then T.Local(i + d) else t
      case T.Lit(_) | T.Global(_) => t
      case T.Lam(ar, b)           => T.Lam(ar, shift(b, d, c + ar))
      case T.App(f, as)           => T.App(shift(f, d, c), as.map(shift(_, d, c)))
      case T.Let(rhs, b) =>
        T.Let(rhs.zipWithIndex.map((r, i) => shift(r, d, c + i)), shift(b, d, c + rhs.length))
      case T.LetRec(lams, b) =>
        val n = lams.length
        T.LetRec(lams.map(shift(_, d, c + n)), shift(b, d, c + n))
      case T.If(a, x, y)   => T.If(shift(a, d, c), shift(x, d, c), shift(y, d, c))
      case T.Ctor(tag, fs) => T.Ctor(tag, fs.map(shift(_, d, c)))
      case T.Match(s, arms, df) =>
        T.Match(shift(s, d, c),
          arms.map(a => Arm(a.tag, a.arity, shift(a.body, d, c + a.arity))),
          df.map(shift(_, d, c)))
      case T.Prim(op, as)   => T.Prim(op, as.map(shift(_, d, c)))
      case T.While(cond, b) => T.While(shift(cond, d, c), shift(b, d, c))
      case T.Seq(ts)        => T.Seq(ts.map(shift(_, d, c)))

  /** Does the program use any effect primitive? Gates the whole effect path so
   *  non-effect programs stay on the original (byte-identical) codegen. */
  def usesEffects(p: Program): Boolean =
    def go(t: T): Boolean = t match
      case T.Prim(op, as) =>
        (op == "effect.perform" || op == "effect.perform.oneshot" ||
          op == "effect.handle" || op == "effect.pure") || as.exists(go)
      case T.App(f, as)        => go(f) || as.exists(go)
      case T.Lam(_, b)         => go(b)
      case T.Let(rhs, b)       => rhs.exists(go) || go(b)
      case T.LetRec(lams, b)   => lams.exists(go) || go(b)
      case T.If(c, x, y)       => go(c) || go(x) || go(y)
      case T.Ctor(_, fs)       => fs.exists(go)
      case T.Match(s, arms, d) => go(s) || arms.exists(a => go(a.body)) || d.exists(go)
      case T.While(c, b)       => go(c) || go(b)
      case T.Seq(ts)           => ts.exists(go)
      case _                   => false
    p.defs.exists(d => go(d.body)) || go(p.entry)
