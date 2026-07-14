package ssc.bytecode

import ssc.{Term as T, Arm, Def as CDef, Program}

/** Op-ARGUMENT lifting for the self-hosted (native `run --bytecode`) lane.
 *
 *  A faithful port of `ssc.bridge.OpAnf` (which lives in frontend-bridge and is
 *  therefore NOT on the native lane's classpath). The kernel already threads a
 *  raw effect `Op` at Let bindings, Seq statements, Match scrutinees, method
 *  receivers and arith operands — but NOT at function-ARGUMENT position:
 *  `scoredGigs(GigSource.fetch())` passes the raw `Op` into `scoredGigs`
 *  (BUGS.md `v2-read-gigs-handle-leak` / `head-field-effect-shadow`).
 *
 *  This pass binds any App/Ctor/Prim ARGUMENT (or Match scrutinee / If
 *  condition) that `mayOp` through a `Let`, and the kernel's Let-threading then
 *  defers the enclosing computation into the Op's continuation.
 *
 *  DELIBERATELY EXCLUDED (the same reason OpAnf carries `isHandle`):
 *   - `effect.handle(compute, handler)` — the computation must reach the handler
 *     RAW; letifying it threads the Op past its own handler.
 *   - `effect.perform` / `effect.perform.oneshot` / `effect.pure` — the Op
 *     SOURCE / a pure lift; their args are already values (identity + performed
 *     argument) and must not be deferred.
 *
 *  Applied by default on the native lane (RunNativeV2 / NativeJvmArtifact).
 *  Escape hatch `SSC_NO_OPANF=1` skips it; `SSC_DUMP_IR=<defname>` prints that
 *  def's body pre/post lift. */
object OpAnfNative:

  def lift(p: Program): Program =
    if sys.env.contains("SSC_NO_OPANF") then return p
    val dumpTarget = sys.env.get("SSC_DUMP_IR").filter(_.nonEmpty)
    dumpTarget.foreach { name =>
      p.defs.find(_.name == name).foreach { d =>
        System.err.println(s"=== IR[$name] PRE-lift ===\n${show(d.body, 0)}")
      }
    }
    val lifted = Program(p.defs.map(d => CDef(d.name, tx(d.body))), tx(p.entry))
    dumpTarget.foreach { name =>
      lifted.defs.find(_.name == name).foreach { d =>
        System.err.println(s"=== IR[$name] POST-lift ===\n${show(d.body, 0)}")
      }
    }
    lifted

  /** May evaluating this term return a raw `DataV("Op", …)`? Op sources on the
   *  native lane: any App and the method-dispatch prims. */
  private def mayOp(t: T): Boolean = t match
    case T.App(_, _) => true
    case T.Prim(op, as) =>
      op == "__method__" || op == "__effect__" || op == "__methodOrExt__" ||
        op == "__effect_oneshot__" || op == "__spliceUnwrap__" || as.exists(mayOp)
    case T.Let(rhs, b)       => rhs.exists(mayOp) || mayOp(b)
    case T.LetRec(_, b)      => mayOp(b)
    case T.If(c, x, y)       => mayOp(c) || mayOp(x) || mayOp(y)
    case T.Match(s, arms, d) => mayOp(s) || arms.exists(a => mayOp(a.body)) || d.exists(mayOp)
    case T.Seq(ts)           => ts.exists(mayOp)
    case _                   => false // Lit, Local, Global, Lam, Ctor, While

  /** Prims whose arguments must NOT be lifted (see class doc). */
  private def isEffectPrim(op: String): Boolean =
    op == "effect.handle" || op == "effect.perform" ||
      op == "effect.perform.oneshot" || op == "effect.pure"

  private def tx(t: T): T = t match
    case T.App(f, as) =>
      val f2 = tx(f); val as2 = as.map(tx)
      if as2.exists(mayOp) then
        val binders = as2.count(a => !isPure(a))
        letify(as2, ls => T.App(shift(f2, binders, 0), ls))
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

  /** Compact indented dump for debugging (SSC_DUMP_IR). */
  private def show(t: T, ind: Int): String =
    val pad = "  " * ind
    t match
      case T.Lit(c)     => s"${pad}Lit($c)"
      case T.Local(i)   => s"${pad}Local($i)"
      case T.Global(n)  => s"${pad}Global($n)"
      case T.Lam(a, b)  => s"${pad}Lam($a){\n${show(b, ind + 1)}\n$pad}"
      case T.App(f, as) =>
        s"${pad}App{\n${show(f, ind + 1)}\n${as.map(show(_, ind + 1)).mkString("\n")}\n$pad}"
      case T.Let(r, b) =>
        s"${pad}Let[${r.length}]{\n${r.map(show(_, ind + 1)).mkString("\n")}\n${pad}in\n${show(b, ind + 1)}\n$pad}"
      case T.LetRec(ls, b) =>
        s"${pad}LetRec[${ls.length}]{\n${ls.map(show(_, ind + 1)).mkString("\n")}\n${pad}in\n${show(b, ind + 1)}\n$pad}"
      case T.If(c, x, y) =>
        s"${pad}If{\n${show(c, ind + 1)}\n${show(x, ind + 1)}\n${show(y, ind + 1)}\n$pad}"
      case T.Ctor(tag, fs) =>
        s"${pad}Ctor($tag){\n${fs.map(show(_, ind + 1)).mkString("\n")}\n$pad}"
      case T.Match(s, arms, d) =>
        val armsS = arms.map(a => s"${"  " * (ind + 1)}Arm(${a.tag}/${a.arity}){\n${show(a.body, ind + 2)}\n${"  " * (ind + 1)}}").mkString("\n")
        s"${pad}Match{\n${show(s, ind + 1)}\n$armsS\n${d.map(show(_, ind + 1)).getOrElse("")}\n$pad}"
      case T.Prim(op, as) =>
        s"${pad}Prim($op){\n${as.map(show(_, ind + 1)).mkString("\n")}\n$pad}"
      case T.While(c, b) =>
        s"${pad}While{\n${show(c, ind + 1)}\n${show(b, ind + 1)}\n$pad}"
      case T.Seq(ts) =>
        s"${pad}Seq{\n${ts.map(show(_, ind + 1)).mkString("\n")}\n$pad}"
