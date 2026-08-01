package ssc3

// Lowering — the typed AST to SSC IR. v3/specs/20-core-language.md §2 (Tier 0).
//
// This is the half of the front that SURVIVES: v3/specs/40-front-on-uniml.md replaces the parser
// with UniML's projection, and this file keeps consuming the same typed AST.
//
// Register allocation is a bump allocator per function with a high-water mark. A register is never
// reused across an expression, which is wasteful and is the right trade for V-0: the bridge stores
// the whole frame in one array, so frame size costs allocation once per call and nothing per step.

final case class LowerFail(pos: Pos, message: String)
    extends RuntimeException(pos.show + ": " + message)

object Lower:

  /** Names the language provides, mapped to the v2 prim spelling the bridge emits. Deliberately a
    * TABLE and not a fallthrough: an unknown name is a `LowerFail` naming it, so a typo becomes a
    * diagnostic at the source position rather than an unbound global three layers down. */
  private val builtins: List[(String, String)] = List("println" -> "io.println")

  private final case class St(
      next: Int,            // next free register
      max: Int,             // high-water mark → nregs
      env: List[(String, Int)],
      consts: List[Lit],
      prims: List[String],
  ):
    def fresh: (Int, St) =
      val r = next
      (r, copy(next = r + 1, max = if r + 1 > max then r + 1 else max))
    def bind(n: String, r: Int): St = copy(env = (n, r) :: env)
    def lookup(n: String): Option[Int] =
      env.find((k, _) => k == n).map((_, v) => v)
    def constIdx(l: Lit): (Int, St) =
      val i = consts.indexOf(l)
      if i >= 0 then (i, this) else (consts.length, copy(consts = consts :+ l))
    def primIdx(p: String): (Int, St) =
      val i = prims.indexOf(p)
      if i >= 0 then (i, this) else (prims.length, copy(prims = prims :+ p))

  private def constExpr(l: Lit, st0: St): (List[Instr], Int, St) =
    val (k, st1) = st0.constIdx(l)
    val (r, st2) = st1.fresh
    (List(Instr.Const(r, k)), r, st2)

  private def lower(e: Expr, fns: List[String], st0: St): (List[Instr], Int, St) = e match
    case Expr.IntLit(v, _)  => constExpr(Lit.LInt(v), st0)
    case Expr.StrLit(v, _)  => constExpr(Lit.LStr(v), st0)
    case Expr.BoolLit(v, _) => constExpr(Lit.LBool(v), st0)
    case Expr.UnitLit(_)    => constExpr(Lit.LUnit, st0)

    case Expr.Name(n, p) =>
      st0.lookup(n) match
        case Some(r) => (Nil, r, st0)
        case None    => throw LowerFail(p, "unknown name '" + n + "'")

    // `&&` and `||` SHORT-CIRCUIT, so they lower to `If` and never to a `Bin`. The IR spec makes
    // this a rule rather than a preference: a strict binary operator here would silently evaluate
    // the right side, and nothing downstream could tell that it should not have.
    case Expr.Bin("&&", l, r, p) =>
      val (li, lr, st1) = lower(l, fns, st0)
      val (ri, rr, st2) = lower(r, fns, st1)
      val (d, st3) = st2.fresh
      val (fk, st4) = st3.constIdx(Lit.LBool(false))
      (li ++ List(Instr.If(lr, ri :+ Instr.Move(d, rr), List(Instr.Const(d, fk)))), d, st4)
    case Expr.Bin("||", l, r, p) =>
      val (li, lr, st1) = lower(l, fns, st0)
      val (ri, rr, st2) = lower(r, fns, st1)
      val (d, st3) = st2.fresh
      val (tk, st4) = st3.constIdx(Lit.LBool(true))
      (li ++ List(Instr.If(lr, List(Instr.Const(d, tk)), ri :+ Instr.Move(d, rr))), d, st4)

    case Expr.Bin(op, l, r, p) =>
      val (li, lr, st1) = lower(l, fns, st0)
      val (ri, rr, st2) = lower(r, fns, st1)
      val (d, st3) = st2.fresh
      (li ++ ri :+ Instr.Bin(binOp(op, p), NumKind.Dyn, d, lr, rr), d, st3)

    case Expr.Neg(x, _) =>
      val (xi, xr, st1) = lower(x, fns, st0)
      val (d, st2) = st1.fresh
      (xi :+ Instr.Un(UnOp.Neg, NumKind.Dyn, d, xr), d, st2)
    case Expr.Not(x, _) =>
      val (xi, xr, st1) = lower(x, fns, st0)
      val (d, st2) = st1.fresh
      (xi :+ Instr.Un(UnOp.Not, NumKind.Dyn, d, xr), d, st2)

    case Expr.Assign(n, v, p) =>
      st0.lookup(n) match
        case None => throw LowerFail(p, "assignment to unknown name '" + n + "'")
        case Some(target) =>
          val (vi, vr, st1) = lower(v, fns, st0)
          (vi :+ Instr.Move(target, vr), target, st1)

    case Expr.If(c, t, elseOpt, _) =>
      val (ci, cr, st1) = lower(c, fns, st0)
      val (ti, tr, st2) = lower(t, fns, st1)
      val (d, st3) = st2.fresh
      elseOpt match
        case Some(el) =>
          val (ei, er, st4) = lower(el, fns, st3)
          (ci ++ List(Instr.If(cr, ti :+ Instr.Move(d, tr), ei :+ Instr.Move(d, er))), d, st4)
        case None =>
          val (uk, st4) = st3.constIdx(Lit.LUnit)
          (ci ++ List(Instr.If(cr, ti :+ Instr.Move(d, tr), List(Instr.Const(d, uk)))), d, st4)

    // The WASM while idiom, and the reason the IR has `Block` at all: `brif` out of the block is
    // the exit test, `br 0` is the back edge. There is no loop-with-condition instruction because
    // one would be a special case of exactly this.
    case Expr.While(c, body, _) =>
      val (ci, cr, st1) = lower(c, fns, st0)
      val (nr, st2) = st1.fresh
      val (bi, _, st3) = lower(body, fns, st2)
      val (uk, st4) = st3.constIdx(Lit.LUnit)
      val (d, st5) = st4.fresh
      val loop = Instr.Block(List(Instr.Loop(
        ci ++ List(Instr.Un(UnOp.Not, NumKind.Dyn, nr, cr), Instr.BrIf(nr, 1)) ++ bi :+ Instr.Br(0)
      )))
      (List(loop, Instr.Const(d, uk)), d, st5)

    case Expr.Block(stmts, result, p) =>
      var acc: List[Instr] = Nil
      var st = st0
      stmts.foreach { s =>
        s match
          case Stmt.Val(n, v, _, _) =>
            val (vi, vr, st1) = lower(v, fns, st)
            // A binding gets its OWN register rather than aliasing the value's, so a later
            // assignment to it cannot write through to a temporary something else still holds.
            val (slot, st2) = st1.fresh
            acc = acc ++ vi :+ Instr.Move(slot, vr)
            st = st2.bind(n, slot)
          case Stmt.Exp(ex) =>
            val (xi, _, st1) = lower(ex, fns, st)
            acc = acc ++ xi
            st = st1
      }
      result match
        case Some(r) =>
          val (ri, rr, st1) = lower(r, fns, st)
          // The block's own bindings leave scope with it; `env` is restored so a name defined
          // inside cannot be seen outside. The register numbers are NOT reused — see the header.
          (acc ++ ri, rr, st1.copy(env = st0.env))
        case None =>
          val (uk, st1) = st.constIdx(Lit.LUnit)
          val (d, st2) = st1.fresh
          (acc :+ Instr.Const(d, uk), d, st2.copy(env = st0.env))

    case Expr.Call(fn, argEs, p) =>
      var acc: List[Instr] = Nil
      var regs: List[Int] = Nil
      var st = st0
      argEs.foreach { a =>
        val (ai, ar, st1) = lower(a, fns, st)
        acc = acc ++ ai
        regs = ar :: regs
        st = st1
      }
      val args = regs.reverse
      builtins.find((n, _) => n == fn) match
        case Some((_, primName)) =>
          val (pi, st1) = st.primIdx(primName)
          val (d, st2) = st1.fresh
          (acc :+ Instr.Prim(d, pi, args), d, st2)
        case None =>
          val idx = fns.indexOf(fn)
          if idx < 0 then throw LowerFail(p, "call to unknown function '" + fn + "'")
          val (d, st1) = st.fresh
          (acc :+ Instr.Call(d, idx, args), d, st1)

  private def binOp(op: String, p: Pos): BinOp = op match
    case "+" => BinOp.Add; case "-" => BinOp.Sub; case "*" => BinOp.Mul
    case "/" => BinOp.Div; case "%" => BinOp.Rem
    case "<" => BinOp.Lt; case "<=" => BinOp.Le; case ">" => BinOp.Gt; case ">=" => BinOp.Ge
    case "==" => BinOp.Eq; case "!=" => BinOp.Ne
    case other => throw LowerFail(p, "operator '" + other + "' is outside SSC3 core Tier 0")

  def program(p: Program): Module =
    if p.defs.isEmpty then throw LowerFail(Pos.none, "no definitions")
    val names = p.defs.map(d => d.name)
    val entry = names.indexOf("main")
    if entry < 0 then throw LowerFail(Pos.none, "no `def main` — that is the entry point `ssc3 run` calls")

    var consts: List[Lit] = Nil
    var prims: List[String] = Nil
    var funcs: List[Func] = Nil
    p.defs.foreach { d =>
      val params = d.params.zipWithIndex.map((pa, i) => (pa.name, i))
      val st0 = St(d.params.length, d.params.length, params.reverse, consts, prims)
      val (body, r, st) = lower(d.body, names, st0)
      consts = st.consts
      prims = st.prims
      funcs = Func(d.name, d.params.length, if st.max > 0 then st.max else 1, body :+ Instr.Ret(r)) :: funcs
    }
    Module(consts, Nil, Nil, prims, funcs.reverse, entry)
