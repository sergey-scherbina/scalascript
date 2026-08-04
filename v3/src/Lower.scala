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
  private val builtins: List[(String, String)] = List("println" -> "io.println", "__autoOutput__" -> "__autoOutput__", "__throw__" -> "__throw__")

  /** Constructors the language provides. `List(a, b)` is `Cons(a, Cons(b, Nil))` — measured off the
    * oracle, not assumed — so it is ordinary `MkData` over the type table rather than a special
    * form. `Some`/`None` are the same shape and cost nothing extra. */
  private val ctors: List[(String, Int)] =
    List("Cons" -> 2, "Nil" -> 0, "Some" -> 1, "None" -> 0)

  private final case class St(
      next: Int,            // next free register
      max: Int,             // high-water mark → nregs
      env: List[(String, Int)],
      consts: List[Lit],
      prims: List[String],
      types: List[TypeDef],
      lifted: List[Func],
      globals: List[String],
  ):
    def fresh: (Int, St) =
      val r = next
      (r, copy(next = r + 1, max = if r + 1 > max then r + 1 else max))
    def bind(n: String, r: Int): St = copy(env = (n, r) :: env)
    def lookup(n: String): Option[Int] =
      env.find((k, _) => k == n).map((_, v) => v)
    def globalIdx(n: String): Int = globals.indexOf(n)
    def constIdx(l: Lit): (Int, St) =
      val i = consts.indexOf(l)
      if i >= 0 then (i, this) else (consts.length, copy(consts = consts :+ l))
    def primIdx(p: String): (Int, St) =
      val i = prims.indexOf(p)
      if i >= 0 then (i, this) else (prims.length, copy(prims = prims :+ p))
    def typeIdx(name: String, fields: Int): (Int, St) =
      val td = TypeDef(name, fields)
      val i = types.indexOf(td)
      if i >= 0 then (i, this) else (types.length, copy(types = types :+ td))

  /** Names a lambda body reads from OUTSIDE itself. Those become the closure's captures, and they
    * are computed rather than guessed: capturing everything in scope would grow every closure with
    * the enclosing frame, and capturing nothing would silently read the wrong register. */
  private def freeVars(e: Expr, bound: List[String]): List[String] = e match
    case Expr.Name(n, _)          => if bound.contains(n) then Nil else List(n)
    case Expr.Bin(_, l, r, _)     => freeVars(l, bound) ++ freeVars(r, bound)
    case Expr.Neg(x, _)           => freeVars(x, bound)
    case Expr.Not(x, _)           => freeVars(x, bound)
    case Expr.Assign(n, v, _)     => (if bound.contains(n) then Nil else List(n)) ++ freeVars(v, bound)
    case Expr.If(c, t, el, _)     => freeVars(c, bound) ++ freeVars(t, bound) ++ el.toList.flatMap(x => freeVars(x, bound))
    case Expr.While(c, b, _)      => freeVars(c, bound) ++ freeVars(b, bound)
    case Expr.Call(_, as, _)      => as.flatMap(a => freeVars(a, bound))
    case Expr.MethodCall(r, _, as, _) => freeVars(r, bound) ++ as.flatMap(a => freeVars(a, bound))
    case Expr.Lambda(ps, b, _)    => freeVars(b, bound ++ ps.map(_.name))
    case Expr.Match(sc, arms, _) =>
      freeVars(sc, bound) ++ arms.flatMap { a =>
        val bs = a.pat match
          case Pat.PBind(n, _)      => List(n)
          case Pat.PCtor(_, args, _) => args.flatMap { case Pat.PBind(n, _) => List(n); case _ => Nil }
          case _                    => Nil
        freeVars(a.body, bound ++ bs)
      }
    case Expr.Block(stmts, res, _) =>
      var b = bound
      var acc: List[String] = Nil
      stmts.foreach { st =>
        st match
          case Stmt.Val(n, v, _, _) => acc = acc ++ freeVars(v, b); b = n :: b
          case Stmt.Exp(x)          => acc = acc ++ freeVars(x, b)
      }
      acc ++ res.toList.flatMap(x => freeVars(x, b))
    case _ => Nil

  private def constExpr(l: Lit, st0: St): (List[Instr], Int, St) =
    val (k, st1) = st0.constIdx(l)
    val (r, st2) = st1.fresh
    (List(Instr.Const(r, k)), r, st2)

  private def lower(e: Expr, fns: List[String], classes: List[ClassDef], st0: St): (List[Instr], Int, St) = e match
    case Expr.IntLit(v, _)  => constExpr(Lit.LInt(v), st0)
    case Expr.DoubleLit(v, _) => constExpr(Lit.LFloat(v), st0)
    case Expr.StrLit(v, _)  => constExpr(Lit.LStr(v), st0)
    // Folded into a `+` chain, which is what interpolation IS. `+` with a string on the left
    // already stringifies its right operand on both lanes, so no `toString` call is synthesised —
    // one less thing that would have to mean the same in two runtimes.
    case Expr.Interp(parts, exprs, p) =>
      val (k0, stA) = st0.constIdx(Lit.LStr(parts.head))
      val (acc0, stB) = stA.fresh
      var instrs: List[Instr] = List(Instr.Const(acc0, k0))
      var acc = acc0
      var st = stB
      var rest = parts.tail
      exprs.foreach { e =>
        val (ei, er, s1) = lower(e, fns, classes, st)
        val (d1, s2) = s1.fresh
        val (kt, s3) = s2.constIdx(Lit.LStr(rest.head))
        val (tr, s4) = s3.fresh
        val (d2, s5) = s4.fresh
        instrs = instrs ++ ei ++ List(
          Instr.Bin(BinOp.Add, NumKind.Dyn, d1, acc, er),
          Instr.Const(tr, kt),
          Instr.Bin(BinOp.Add, NumKind.Dyn, d2, d1, tr))
        acc = d2
        st = s5
        rest = rest.tail
      }
      (instrs, acc, st)
    case Expr.BoolLit(v, _) => constExpr(Lit.LBool(v), st0)
    case Expr.UnitLit(_)    => constExpr(Lit.LUnit, st0)

    case Expr.Name(n, p) =>
      st0.lookup(n) match
        case Some(r) => (Nil, r, st0)
        case None if st0.globalIdx(n) >= 0 =>
          // A TOP-LEVEL `val`/`var` is a module global, not a register of the entry function. That
          // is what makes it visible inside a `def` — measured: 60 corpus files declare a top-level
          // val or var alongside a def, and every one of them was an "unknown name" before this.
          val (d, st1) = st0.fresh
          (List(Instr.GlobGet(d, st0.globalIdx(n))), d, st1)
        case None =>
          // A nullary constructor is spelled as a bare name — `Nil`, `None`, and every `case Red`
          // of an enum — which is why this arm exists rather than the lookup simply failing.
          // Declared classes are checked FIRST so a program's own `case None` wins over the
          // built-in, which is what shadowing means.
          val declaredNullary = classes.find(c => c.name == n && c.fields.isEmpty)
          if declaredNullary.isDefined then
            val (t, st1) = st0.typeIdx(n, 0)
            val (d, st2) = st1.fresh
            (List(Instr.MkData(d, t, Nil)), d, st2)
          else
          ctors.find((cn, ar) => cn == n && ar == 0) match
            case Some((cn, _)) =>
              val (t, st1) = st0.typeIdx(cn, 0)
              val (d, st2) = st1.fresh
              (List(Instr.MkData(d, t, Nil)), d, st2)
            case None => throw LowerFail(p, "unknown name '" + n + "'")

    // `&&` and `||` SHORT-CIRCUIT, so they lower to `If` and never to a `Bin`. The IR spec makes
    // this a rule rather than a preference: a strict binary operator here would silently evaluate
    // the right side, and nothing downstream could tell that it should not have.
    case Expr.Bin("&&", l, r, p) =>
      val (li, lr, st1) = lower(l, fns, classes, st0)
      val (ri, rr, st2) = lower(r, fns, classes, st1)
      val (d, st3) = st2.fresh
      val (fk, st4) = st3.constIdx(Lit.LBool(false))
      (li ++ List(Instr.If(lr, ri :+ Instr.Move(d, rr), List(Instr.Const(d, fk)))), d, st4)
    case Expr.Bin("||", l, r, p) =>
      val (li, lr, st1) = lower(l, fns, classes, st0)
      val (ri, rr, st2) = lower(r, fns, classes, st1)
      val (d, st3) = st2.fresh
      val (tk, st4) = st3.constIdx(Lit.LBool(true))
      (li ++ List(Instr.If(lr, List(Instr.Const(d, tk)), ri :+ Instr.Move(d, rr))), d, st4)

    // `h :: t` is `Cons(h, t)` — the same node the pattern form produces, so the two spellings
    // cannot drift apart.
    case Expr.Bin("::", l, r, _) =>
      val (li, lr, st1) = lower(l, fns, classes, st0)
      val (ri, rr, st2) = lower(r, fns, classes, st1)
      val (t, st3) = st2.typeIdx("Cons", 2)
      val (d, st4) = st3.fresh
      (li ++ ri :+ Instr.MkData(d, t, List(lr, rr)), d, st4)

    case Expr.Bin(op, l, r, p) =>
      val (li, lr, st1) = lower(l, fns, classes, st0)
      val (ri, rr, st2) = lower(r, fns, classes, st1)
      val (d, st3) = st2.fresh
      (li ++ ri :+ Instr.Bin(binOp(op, p), NumKind.Dyn, d, lr, rr), d, st3)

    case Expr.Neg(x, _) =>
      val (xi, xr, st1) = lower(x, fns, classes, st0)
      val (d, st2) = st1.fresh
      (xi :+ Instr.Un(UnOp.Neg, NumKind.Dyn, d, xr), d, st2)
    case Expr.Not(x, _) =>
      val (xi, xr, st1) = lower(x, fns, classes, st0)
      val (d, st2) = st1.fresh
      (xi :+ Instr.Un(UnOp.Not, NumKind.Dyn, d, xr), d, st2)

    case Expr.Assign(n, v, p) if st0.lookup(n).isEmpty && st0.globalIdx(n) >= 0 =>
      val (vi, vr, st1) = lower(v, fns, classes, st0)
      (vi :+ Instr.GlobSet(st0.globalIdx(n), vr), vr, st1)

    case Expr.Assign(n, v, p) =>
      st0.lookup(n) match
        case None => throw LowerFail(p, "assignment to unknown name '" + n + "'")
        case Some(target) =>
          val (vi, vr, st1) = lower(v, fns, classes, st0)
          (vi :+ Instr.Move(target, vr), target, st1)

    case Expr.If(c, t, elseOpt, _) =>
      val (ci, cr, st1) = lower(c, fns, classes, st0)
      val (ti, tr, st2) = lower(t, fns, classes, st1)
      val (d, st3) = st2.fresh
      elseOpt match
        case Some(el) =>
          val (ei, er, st4) = lower(el, fns, classes, st3)
          (ci ++ List(Instr.If(cr, ti :+ Instr.Move(d, tr), ei :+ Instr.Move(d, er))), d, st4)
        case None =>
          val (uk, st4) = st3.constIdx(Lit.LUnit)
          (ci ++ List(Instr.If(cr, ti :+ Instr.Move(d, tr), List(Instr.Const(d, uk)))), d, st4)

    // The WASM while idiom, and the reason the IR has `Block` at all: `brif` out of the block is
    // the exit test, `br 0` is the back edge. There is no loop-with-condition instruction because
    // one would be a special case of exactly this.
    case Expr.While(c, body, _) =>
      val (ci, cr, st1) = lower(c, fns, classes, st0)
      val (nr, st2) = st1.fresh
      val (bi, _, st3) = lower(body, fns, classes, st2)
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
            val (vi, vr, st1) = lower(v, fns, classes, st)
            // Always a LOCAL here. Whether a `val` initialises a module global is decided in
            // `program`, where the top level is actually known — deciding it here meant a local
            // `val` that happened to share a name with a top-level one wrote the GLOBAL instead of
            // shadowing it, and nothing in the program said so.
            val (slot, st2) = st1.fresh
            acc = acc ++ vi :+ Instr.Move(slot, vr)
            st = st2.bind(n, slot)
          case Stmt.Exp(ex) =>
            val (xi, _, st1) = lower(ex, fns, classes, st)
            acc = acc ++ xi
            st = st1
      }
      result match
        case Some(r) =>
          val (ri, rr, st1) = lower(r, fns, classes, st)
          // The block's own bindings leave scope with it; `env` is restored so a name defined
          // inside cannot be seen outside. The register numbers are NOT reused — see the header.
          (acc ++ ri, rr, st1.copy(env = st0.env))
        case None =>
          val (uk, st1) = st.constIdx(Lit.LUnit)
          val (d, st2) = st1.fresh
          (acc :+ Instr.Const(d, uk), d, st2.copy(env = st0.env))

    // A no-argument call whose name is a FIELD of exactly one declared class is a field read, and
    // must lower to `Field` rather than `Invoke`. Measured: v2's `__method__` does not resolve a
    // case-class field — it returns the `Stub` SENTINEL, printed, at exit 0. That is this project's
    // documented worst failure shape, and an output check is the only thing that sees it.
    //
    // Exactly ONE class, deliberately. Ambiguity is refused rather than guessed: picking a type
    // here without a checker would be a silent wrong-field read, which is the bug family the IR's
    // single type table exists to remove.
    // `Foo.bar(…)` where `Foo` is a declared object is a DIRECT call, not dynamic dispatch. An
    // object is a namespace at Tier 0, so resolving it here is the whole of its semantics.
    case Expr.MethodCall(Expr.Name(obj, _), nm, argEs, p) if fns.contains(obj + "." + nm) =>
      var acc: List[Instr] = Nil
      var regs: List[Int] = Nil
      var st = st0
      argEs.foreach { a =>
        val (ai, ar, stN) = lower(a, fns, classes, st)
        acc = acc ++ ai; regs = ar :: regs; st = stN
      }
      val (d, st1) = st.fresh
      (acc :+ Instr.Call(d, fns.indexOf(obj + "." + nm), regs.reverse), d, st1)

    // A no-argument call whose name is a FIELD of some declared class lowers to a `Switch` with an
    // arm per declaring class and a DEFAULT that dispatches dynamically. That shape is the oracle's
    // (`_sel_head` in v2's own output), and it is the only thing that resolves `head-field-shadow`:
    // a program may read `r.head` on a record and `xs.head` on a list, and no syntactic rule can
    // tell them apart without a type checker.
    //
    // It also RETIRES the ambiguity refusal this arm used to carry. Two classes with the same field
    // name are simply two arms — the receiver decides, at run time, which is what it always was.
    case Expr.MethodCall(recv, nm, Nil, p) if classes.exists(c => c.fields.exists(f => f.name == nm)) =>
      val owners = classes.filter(c => c.fields.exists(f => f.name == nm))
      val (ri, rr, st1) = lower(recv, fns, classes, st0)
      val (d, st2) = st1.fresh
      var st = st2
      var arms: List[SwitchArm] = Nil
      owners.foreach { o =>
        val (t, sN) = st.typeIdx(o.name, o.fields.length)
        val (fr, sN2) = sN.fresh
        val idx = o.fields.indexWhere(f => f.name == nm)
        arms = arms :+ SwitchArm(t, List(Instr.Field(fr, rr, t, idx), Instr.Move(d, fr)))
        st = sN2
      }
      val (nk, st3) = st.constIdx(Lit.LStr(nm))
      val (ir2, st4) = st3.fresh
      (ri :+ Instr.Switch(rr, arms, List(Instr.Invoke(ir2, nk, rr, Nil), Instr.Move(d, ir2))), d, st4)

    case Expr.MethodCall(recv, nm, argEs, _) =>
      val (ri, rr, st1) = lower(recv, fns, classes, st0)
      var acc = ri
      var regs: List[Int] = Nil
      var st = st1
      argEs.foreach { a =>
        val (ai, ar, stN) = lower(a, fns, classes, st)
        acc = acc ++ ai; regs = ar :: regs; st = stN
      }
      val (nk, st2) = st.constIdx(Lit.LStr(nm))
      val (d, st3) = st2.fresh
      (acc :+ Instr.Invoke(d, nk, rr, regs.reverse), d, st3)

    // A `match` becomes ONE if-chain: `Tag` for constructor arms, `Eq` for literal ones. Two code
    // paths — `Switch` for pure-constructor matches, a chain otherwise — would be faster and would
    // be two things that must agree about arm order, binding and fall-through. One path cannot
    // disagree with itself. `Switch` stays in the IR for a later pass to recognise.
    case Expr.Match(scrut, arms, p) =>
      if arms.isEmpty then throw LowerFail(p, "a `match` with no arms")
      val (si, sr, st1) = lower(scrut, fns, classes, st0)
      val needsTag = arms.exists(a => a.pat match { case Pat.PCtor(_, _, _) => true; case _ => false })
      val (tr, st2) = if needsTag then st1.fresh else (0, st1)
      val tagInstr = if needsTag then List(Instr.Tag(tr, sr)) else Nil
      val (rd, st3) = st2.fresh
      // A non-exhaustive match must FAIL, loudly, at the point of failure. Falling through to unit
      // would be a wrong answer with a clean exit — the shape `p.x` returning `Stub` already cost
      // this module one debugging round.
      val (mk, st4) = st3.constIdx(Lit.LStr("match: no arm matched"))
      val (thr, st5) = st4.primIdx("__throw__")
      val (msgR, st6) = st5.fresh
      val fallback: List[Instr] = List(Instr.Const(msgR, mk), Instr.Prim(rd, thr, List(msgR)))
      val (chain, stF) = armChain(arms, sr, tr, rd, fns, classes, st6, fallback)
      (si ++ tagInstr ++ chain, rd, stF)

    // LAMBDA LIFTING. The body becomes a top-level function whose FIRST parameters are the captured
    // values and whose remaining ones are the lambda's own — which is exactly the shape `MkClos`
    // describes, so the instruction needed no change to support closures.
    case Expr.Lambda(ps, body, p) =>
      val pnames = ps.map(_.name)
      val free = freeVars(body, pnames).distinct.filter(n => st0.lookup(n).isDefined)
      val capRegs = free.map(n => st0.lookup(n).get)
      val idx = fns.length + st0.lifted.length
      // The lifted function gets a FRESH register space: it is a separate frame at run time, and
      // sharing the numbering with the enclosing function would be a silent aliasing bug.
      val inner0 = St(free.length + ps.length, free.length + ps.length,
                      (free.zipWithIndex.map((n, i) => (n, i)) ++
                       pnames.zipWithIndex.map((n, i) => (n, free.length + i))).reverse,
                      st0.consts, st0.prims, st0.types, st0.lifted, st0.globals)
      val (bi, br, inner) = lower(body, fns, classes, inner0)
      val f = Func("__lam" + idx, free.length + ps.length,
                   if inner.max > 0 then inner.max else 1, bi :+ Instr.Ret(br))
      val st1 = st0.copy(consts = inner.consts, prims = inner.prims, types = inner.types,
                         lifted = inner.lifted :+ f)
      val (d, st2) = st1.fresh
      (List(Instr.MkClos(d, idx, capRegs)), d, st2)

    case Expr.Try(body, exn, handler, _) =>
      val (d, st1) = st0.fresh
      val (xr, st2) = st1.fresh
      val (bi, br, st3) = lower(body, fns, classes, st2)
      val (hi, hr, st4) = lower(handler, fns, classes, st3.bind(exn, xr))
      (List(Instr.Try(d, bi :+ Instr.Move(d, br), xr, hi :+ Instr.Move(d, hr))), d,
       st4.copy(env = st0.env))

    case Expr.Call(fn, argEs, p) =>
      var acc: List[Instr] = Nil
      var regs: List[Int] = Nil
      var st = st0
      argEs.foreach { a =>
        val (ai, ar, st1) = lower(a, fns, classes, st)
        acc = acc ++ ai
        regs = ar :: regs
        st = st1
      }
      val args = regs.reverse
      // A flat chain, not nested `match`es. The first attempt nested them and Scala 3's significant
      // indentation quietly closed the inner match one level early, so the List branch computed a
      // value that was DISCARDED and every call fell through to "unknown function". It compiled.
      val declared = classes.find(c => c.name == fn)
      if fn == "List" || fn == "Seq" then listOf(args, acc, st)
      else if declared.isDefined then
        val c = declared.get
        if args.length != c.fields.length then
          throw LowerFail(p, fn + " takes " + c.fields.length + " field(s), given " + args.length)
        val (t, st1) = st.typeIdx(fn, c.fields.length)
        val (d, st2) = st1.fresh
        (acc :+ Instr.MkData(d, t, args), d, st2)
      else if ctors.exists((n, _) => n == fn) then
        val arity = ctors.find((n, _) => n == fn).map((_, a) => a).getOrElse(0)
        if args.length != arity then
          throw LowerFail(p, fn + " takes " + arity + " argument(s), given " + args.length)
        val (t, st1) = st.typeIdx(fn, arity)
        val (d, st2) = st1.fresh
        (acc :+ Instr.MkData(d, t, args), d, st2)
      else if builtins.exists((n, _) => n == fn) then
        val primName = builtins.find((n, _) => n == fn).map((_, v) => v).getOrElse(fn)
        val (pi, st1) = st.primIdx(primName)
        val (d, st2) = st1.fresh
        (acc :+ Instr.Prim(d, pi, args), d, st2)
      else if st.lookup(fn).isEmpty && st.globalIdx(fn) >= 0 then
        // A top-level `val f = (x) => …` is a GLOBAL holding a closure, so calling it is a read
        // followed by an indirect call. Without this arm it reported `f` as an unknown function
        // while the closure sat in a cell — which is what the gate caught the moment `val` moved
        // out of the entry's registers.
        val (fr, st1) = st.fresh
        val (d, st2) = st1.fresh
        (acc ++ List(Instr.GlobGet(fr, st.globalIdx(fn)), Instr.CallV(d, fr, args)), d, st2)
      else if st.lookup(fn).isDefined then
        // A name bound in scope that is being CALLED is a closure value, not a top-level function.
        // `val f = (x) => x + 1; f(2)` is ordinary, and without this arm it reported the name as an
        // unknown function while it was sitting in a register.
        val (d, st1) = st.fresh
        (acc :+ Instr.CallV(d, st.lookup(fn).get, args), d, st1)
      else
        val idx = fns.indexOf(fn)
        if idx < 0 then throw LowerFail(p, "call to unknown function '" + fn + "'")
        val (d, st1) = st.fresh
        (acc :+ Instr.Call(d, idx, args), d, st1)

  /** `List(a, b)` is `Cons(a, Cons(b, Nil))` — measured off the oracle, not assumed — so it is
    * ordinary `MkData` over the type table rather than a special form anywhere downstream. */
  private def listOf(args: List[Int], acc0: List[Instr], st0: St): (List[Instr], Int, St) =
    val (nilT, st1) = st0.typeIdx("Nil", 0)
    val (nilR, st2) = st1.fresh
    var cur = nilR
    var instrs = acc0 :+ Instr.MkData(nilR, nilT, Nil)
    var st = st2
    args.reverse.foreach { a =>
      val (consT, s1) = st.typeIdx("Cons", 2)
      val (r, s2) = s1.fresh
      instrs = instrs :+ Instr.MkData(r, consT, List(a, cur))
      cur = r
      st = s2
    }
    (instrs, cur, st)

  /** Arms, in source order, as nested `If`s. Built back to front so each arm's `else` is the rest
    * of the chain — which is what makes source order the matching order, exactly as written. */
  private def armChain(arms: List[MatchArm], scrut: Int, tagReg: Int, dst: Int,
                       fns: List[String], classes: List[ClassDef], st0: St,
                       fallback: List[Instr]): (List[Instr], St) =
    if arms.isEmpty then (fallback, st0)
    else
      val a = arms.head
      val (rest, st1) = armChain(arms.tail, scrut, tagReg, dst, fns, classes, st0, fallback)
      a.pat match
        case Pat.PWild(_) =>
          val (bi, br, st2) = lower(a.body, fns, classes, st1)
          // A wildcard always matches, so everything after it is DEAD. Emitting the body directly
          // rather than an `if true` keeps that visible in the IR instead of hiding it in a branch.
          (bi :+ Instr.Move(dst, br), st2)
        case Pat.PBind(n, _) =>
          val (slot, st2) = st1.fresh
          val (bi, br, st3) = lower(a.body, fns, classes, st2.bind(n, slot))
          ((Instr.Move(slot, scrut) :: bi) :+ Instr.Move(dst, br), st3.copy(env = st1.env))
        case Pat.PLit(v, _) =>
          val (vi, vr, st2) = lower(v, fns, classes, st1)
          val (cr, st3) = st2.fresh
          val (bi, br, st4) = lower(a.body, fns, classes, st3)
          (vi ++ List(Instr.Bin(BinOp.Eq, NumKind.Dyn, cr, scrut, vr),
                      Instr.If(cr, bi :+ Instr.Move(dst, br), rest)), st4)
        case Pat.PCtor(cname, cargs, cp) =>
          val arity = classes.find(c => c.name == cname).map(c => c.fields.length)
            .orElse(ctors.find((n, _) => n == cname).map((_, ar) => ar))
            .getOrElse(throw LowerFail(cp, "unknown constructor '" + cname + "' in a pattern"))
          if cargs.nonEmpty && cargs.length != arity then
            throw LowerFail(cp, cname + " has " + arity + " field(s), the pattern binds " + cargs.length)
          val (t, st2) = st1.typeIdx(cname, arity)
          val (tagK, st3) = st2.constIdx(Lit.LInt(t.toLong))
          val (tagV, st4) = st3.fresh
          val (cr, st5) = st4.fresh
          // Bind each field the pattern names. A wildcard argument binds nothing, so it costs no
          // register and no read.
          var binds: List[Instr] = Nil
          var stb = st5
          var envb = st5.env
          cargs.zipWithIndex.foreach { (ap, i) =>
            ap match
              case Pat.PBind(bn, _) =>
                val (r, sN) = stb.fresh
                binds = binds :+ Instr.Field(r, scrut, t, i)
                stb = sN
                envb = (bn, r) :: envb
              case _ => ()
          }
          val (bi, br, st6) = lower(a.body, fns, classes, stb.copy(env = envb))
          (List(Instr.Const(tagV, tagK), Instr.Bin(BinOp.Eq, NumKind.Dyn, cr, tagReg, tagV),
                Instr.If(cr, binds ++ bi :+ Instr.Move(dst, br), rest)), st6.copy(env = st1.env))

  private def markAutoOutput(stmts: List[Stmt], blockEnds: List[Int]): List[Stmt] =
    if blockEnds.isEmpty then stmts
    else
      // For each block, the last STATEMENT that falls inside it. A `val` tail prints nothing, which
      // is why only `Stmt.Exp` is wrapped.
      var chosen: List[Int] = Nil
      blockEnds.foreach { e =>
        var best = -1
        var i = 0
        stmts.foreach { st =>
          val ln = st match
            case Stmt.Exp(x)        => Expr.posOf(x).line
            case Stmt.Val(_, _, _, q) => q.line
          if ln <= e && ln > 0 then best = i
          i = i + 1
        }
        if best >= 0 then chosen = best :: chosen
      }
      var i = 0
      stmts.map { st =>
        val out = st match
          case Stmt.Exp(x) if chosen.contains(i) => Stmt.Exp(Expr.Call("__autoOutput__", List(x), Expr.posOf(x)))
          case other                              => other
        i = i + 1
        out
      }

  private def binOp(op: String, p: Pos): BinOp = op match
    case "+" => BinOp.Add; case "-" => BinOp.Sub; case "*" => BinOp.Mul
    case "/" => BinOp.Div; case "%" => BinOp.Rem
    case "<" => BinOp.Lt; case "<=" => BinOp.Le; case ">" => BinOp.Gt; case ">=" => BinOp.Ge
    case "==" => BinOp.Eq; case "!=" => BinOp.Ne
    case other => throw LowerFail(p, "operator '" + other + "' is outside SSC3 core Tier 0")

  /** The synthetic entry. Top-level statements run in order, then `main()` if the file defines one —
    * which is how `.ssc` behaves on the other lanes: a script with no `main` still prints, and a
    * file with only a `main` still runs it. Both shapes appear in the corpus. */
  private val entryName = "__ssc3_entry__"

  def program(p: Program): Module = programOf(p, Nil)

  def programOf(p: Program, blockEnds: List[Int]): Module =
    if p.defs.isEmpty && p.topLevel.isEmpty then throw LowerFail(Pos.none, "empty program")
    val userMain = p.defs.find(d => d.name == "main" && d.params.isEmpty)
    // Auto-output: the LAST top-level expression of each block becomes `__autoOutput__(v)`, which
    // prints only a non-Unit value — the runtime decides, so the front does not need a type checker
    // to know whether a `println(…)` tail should print again.
    // A top-level `val n = v` IS an assignment to the module cell. Rewriting it here rather than
    // special-casing it in the lowering keeps "am I at the top level?" a question with one answer,
    // asked in the one place that can answer it.
    // MARK FIRST, HOIST SECOND. The other order turns a top-level `val` into an assignment
    // EXPRESSION and auto-output then prints it — a block whose tail is a `val` started emitting a
    // line nobody wrote. A `val` tail prints nothing; that is the rule, and it only survives if the
    // marking sees the statement as the author wrote it.
    val marked = markAutoOutput(p.topLevel, blockEnds)
    val hoisted = marked.map { st => st match
      case Stmt.Val(n, v, _, q) => Stmt.Exp(Expr.Assign(n, v, q))
      case other                => other
    }
    val entryBody =
      Expr.Block(hoisted, userMain.map(_ => Expr.Call("main", Nil, Pos.none)), Pos.none)
    val entryDef = Def(entryName, Nil, entryBody, Pos.none)
    // Object members are flattened into `Object.member` top-level functions before anything else
    // looks at the name list, so a qualified call resolves by ordinary lookup.
    val objectDefs = p.objects.flatMap(o => o.defs.map(d => d.copy(name = o.name + "." + d.name)))
    val allDefs = (p.defs ++ objectDefs) :+ entryDef
    val names = allDefs.map(d => d.name)
    val entry = names.indexOf(entryName)

    var consts: List[Lit] = Nil
    var prims: List[String] = Nil
    var types: List[TypeDef] = Nil
    var lifted: List[Func] = Nil
    // Collected BEFORE anything is lowered: a `def` may reference a top-level `val` declared
    // further down the file, and the other lanes allow that.
    val globalNames = p.topLevel.flatMap { st => st match
      case Stmt.Val(n, _, _, _) => List(n)
      case _                    => Nil
    }.distinct
    var funcs: List[Func] = Nil
    allDefs.foreach { d =>
      val params = d.params.zipWithIndex.map((pa, i) => (pa.name, i))
      val st0 = St(d.params.length, d.params.length, params.reverse, consts, prims, types, lifted, globalNames)
      val (body, r, st) = lower(d.body, names, p.classes, st0)
      consts = st.consts
      prims = st.prims
      types = st.types
      lifted = st.lifted
      funcs = Func(d.name, d.params.length, if st.max > 0 then st.max else 1, body :+ Instr.Ret(r)) :: funcs
    }
    // Lifted lambdas are APPENDED, which is what makes `fns.length + lifted.length` the right
    // index at the point MkClos is emitted.
    // The tail-call pass runs HERE rather than inside the lowering: it is a rewrite of finished IR,
    // and keeping it separate is what lets it be tested, skipped or reordered without touching the
    // front. v3/src/TailCalls.scala.
    TailCalls(Module(consts, types, globalNames.map(n => GlobalDef(n)), prims, funcs.reverse ++ lifted, entry))
