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

  /** Tuples as SYNTHETIC case classes — `Tuple2(_1, _2)` and so on.
    *
    * Nothing else in the compiler learns what a tuple is. Construction is the `MkData` a `case
    * class` already uses, `t._1` resolves through the same field-by-name `Switch`, `case (a, b)` is
    * a constructor pattern, and printing is one arm in `showV`. The representation is not invented
    * either: v2 stores a tuple as `DataV("Tuple2", …)`, so `(ctor Tuple2 a b)` on the bridge builds
    * v2's own tuple rather than a v3 lookalike that would print and match differently.
    *
    * 2..8 because beyond that Scala itself stops being idiomatic, and an unbounded family would be
    * generated for arities nobody writes. A 9-tuple gets an ordinary "unknown constructor". */
  val tupleClasses: List[ClassDef] =
    (2 to 8).toList.map { n =>
      ClassDef("Tuple" + n, (1 to n).toList.map(i => Param("_" + i, Pos.none)), Nil, Nil, Pos.none)
    }

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
    case Expr.Update(a, i, v, _)  => freeVars(a, bound) ++ freeVars(i, bound) ++ freeVars(v, bound)
    case Expr.If(c, t, el, _)     => freeVars(c, bound) ++ freeVars(t, bound) ++ el.toList.flatMap(x => freeVars(x, bound))
    case Expr.While(c, b, _)      => freeVars(c, bound) ++ freeVars(b, bound)
    case Expr.Call(_, as, _)      => as.flatMap(a => freeVars(a, bound))
    case Expr.MethodCall(r, _, as, _) => freeVars(r, bound) ++ as.flatMap(a => freeVars(a, bound))
    case Expr.Lambda(ps, b, _)    => freeVars(b, bound ++ ps.map(_.name))
    case Expr.Match(sc, arms, _) =>
      freeVars(sc, bound) ++ arms.flatMap { a =>
        val inner = bound ++ patNames(a.pat)
        a.guard.map(g => freeVars(g, inner)).getOrElse(Nil) ++ freeVars(a.body, inner)
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

  // `zeroArity` is threaded EXPLICITLY, not as a `given`. A context parameter would have been
  // shorter and is Scala-3-only — `given`/`using` are Tier 2 in this project's own portable subset,
  // and the v3 kernel has to compile on ScalaScript 2 as well.
  private def lower(e: Expr, fns: List[String], classes: List[ClassDef], zeroArity: List[String], st0: St): (List[Instr], Int, St) = e match
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
        val (ei, er, s1) = lower(e, fns, classes, zeroArity, st)
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

    // A char is its CODE POINT passed through the `char` primitive, which both lanes have: v2's
    // `CharV extends IntV` is an integer that prints as a character, so `'x' + 1` is 121 and
    // `println('x')` is `x`. No new IR — a new `Lit` would have needed a codec, a verifier rule and
    // two backend arms to express something the existing prim already does.
    case Expr.CharLit(code, _) =>
      val (k, st1) = st0.constIdx(Lit.LInt(code.toLong))
      val (r, st2) = st1.fresh
      val (pi, st3) = st2.primIdx("char")
      val (d, st4) = st3.fresh
      (List(Instr.Const(r, k), Instr.Prim(d, pi, List(r))), d, st4)

    case Expr.Name(n, p) =>
      st0.lookup(n) match
        case Some(r) => (Nil, r, st0)
        // A bare name that is a ZERO-ARITY def is a CALL. `def empty: List[A] = Nil` is referenced
        // as `empty`, not `empty()` — that is what makes it read like a constant, and it is the
        // half of parameterless-def support that lives here rather than in the parser.
        case None if fns.contains(n) && zeroArity.contains(n) =>
          val (d, st1) = st0.fresh
          (List(Instr.Call(d, fns.indexOf(n), Nil)), d, st1)
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
      val (li, lr, st1) = lower(l, fns, classes, zeroArity, st0)
      val (ri, rr, st2) = lower(r, fns, classes, zeroArity, st1)
      val (d, st3) = st2.fresh
      val (fk, st4) = st3.constIdx(Lit.LBool(false))
      (li ++ List(Instr.If(lr, ri :+ Instr.Move(d, rr), List(Instr.Const(d, fk)))), d, st4)
    case Expr.Bin("||", l, r, p) =>
      val (li, lr, st1) = lower(l, fns, classes, zeroArity, st0)
      val (ri, rr, st2) = lower(r, fns, classes, zeroArity, st1)
      val (d, st3) = st2.fresh
      val (tk, st4) = st3.constIdx(Lit.LBool(true))
      (li ++ List(Instr.If(lr, List(Instr.Const(d, tk)), ri :+ Instr.Move(d, rr))), d, st4)

    // `h :: t` is `Cons(h, t)` — the same node the pattern form produces, so the two spellings
    // cannot drift apart.
    case Expr.Bin("::", l, r, _) =>
      val (li, lr, st1) = lower(l, fns, classes, zeroArity, st0)
      val (ri, rr, st2) = lower(r, fns, classes, zeroArity, st1)
      val (t, st3) = st2.typeIdx("Cons", 2)
      val (d, st4) = st3.fresh
      (li ++ ri :+ Instr.MkData(d, t, List(lr, rr)), d, st4)

    // `++` is DYNAMIC DISPATCH, not a lowering-time decision: it concatenates lists, strings and
    // sets alike, and which one it is depends on the receiver at run time. Picking a representation
    // here would need a type checker; `Invoke` asks the value.
    // `:+` appends and `+:` prepends. Same argument as `++`: which collection it is, is a property
    // of the receiver at run time, and deciding it here would need a type checker.
    case Expr.Bin(":+", l, r, _) =>
      val (li, lr, st1) = lower(l, fns, classes, zeroArity, st0)
      val (ri, rr, st2) = lower(r, fns, classes, zeroArity, st1)
      val (k, st3) = st2.constIdx(Lit.LStr(":+"))
      val (d, st4) = st3.fresh
      (li ++ ri :+ Instr.Invoke(d, k, lr, List(rr)), d, st4)

    // `a +: xs` is a method on the RIGHT operand — Scala's rule for an operator ending in `:` — so
    // the receiver and the argument swap here. Getting this backwards would prepend the list to the
    // element and fail with a message about the wrong operand.
    case Expr.Bin("+:", l, r, _) =>
      val (li, lr, st1) = lower(l, fns, classes, zeroArity, st0)
      val (ri, rr, st2) = lower(r, fns, classes, zeroArity, st1)
      val (k, st3) = st2.constIdx(Lit.LStr("+:"))
      val (d, st4) = st3.fresh
      (li ++ ri :+ Instr.Invoke(d, k, rr, List(lr)), d, st4)

    case Expr.Bin("++", l, r, _) =>
      val (li, lr, st1) = lower(l, fns, classes, zeroArity, st0)
      val (ri, rr, st2) = lower(r, fns, classes, zeroArity, st1)
      val (k, st3) = st2.constIdx(Lit.LStr("++"))
      val (d, st4) = st3.fresh
      (li ++ ri :+ Instr.Invoke(d, k, lr, List(rr)), d, st4)

    case Expr.Bin(op, l, r, p) =>
      val (li, lr, st1) = lower(l, fns, classes, zeroArity, st0)
      val (ri, rr, st2) = lower(r, fns, classes, zeroArity, st1)
      val (d, st3) = st2.fresh
      (li ++ ri :+ Instr.Bin(binOp(op, p), NumKind.Dyn, d, lr, rr), d, st3)

    case Expr.Neg(x, _) =>
      val (xi, xr, st1) = lower(x, fns, classes, zeroArity, st0)
      val (d, st2) = st1.fresh
      (xi :+ Instr.Un(UnOp.Neg, NumKind.Dyn, d, xr), d, st2)
    case Expr.Not(x, _) =>
      val (xi, xr, st1) = lower(x, fns, classes, zeroArity, st0)
      val (d, st2) = st1.fresh
      (xi :+ Instr.Un(UnOp.Not, NumKind.Dyn, d, xr), d, st2)

    case Expr.Update(arrE, idxE, valE, _) =>
      val (ai, ar, st1) = lower(arrE, fns, classes, zeroArity, st0)
      val (ii, ir, st2) = lower(idxE, fns, classes, zeroArity, st1)
      val (vi, vr, st3) = lower(valE, fns, classes, zeroArity, st2)
      val (d, st4) = st3.fresh
      val (uk, st5) = st4.constIdx(Lit.LUnit)
      (ai ++ ii ++ vi ++ List(Instr.ArrSet(ar, ir, vr), Instr.Const(d, uk)), d, st5)

    case Expr.Assign(n, v, p) if st0.lookup(n).isEmpty && st0.globalIdx(n) >= 0 =>
      val (vi, vr, st1) = lower(v, fns, classes, zeroArity, st0)
      (vi :+ Instr.GlobSet(st0.globalIdx(n), vr), vr, st1)

    case Expr.Assign(n, v, p) =>
      st0.lookup(n) match
        case None => throw LowerFail(p, "assignment to unknown name '" + n + "'")
        case Some(target) =>
          val (vi, vr, st1) = lower(v, fns, classes, zeroArity, st0)
          (vi :+ Instr.Move(target, vr), target, st1)

    case Expr.If(c, t, elseOpt, _) =>
      val (ci, cr, st1) = lower(c, fns, classes, zeroArity, st0)
      val (ti, tr, st2) = lower(t, fns, classes, zeroArity, st1)
      val (d, st3) = st2.fresh
      elseOpt match
        case Some(el) =>
          val (ei, er, st4) = lower(el, fns, classes, zeroArity, st3)
          (ci ++ List(Instr.If(cr, ti :+ Instr.Move(d, tr), ei :+ Instr.Move(d, er))), d, st4)
        case None =>
          val (uk, st4) = st3.constIdx(Lit.LUnit)
          (ci ++ List(Instr.If(cr, ti :+ Instr.Move(d, tr), List(Instr.Const(d, uk)))), d, st4)

    // The WASM while idiom, and the reason the IR has `Block` at all: `brif` out of the block is
    // the exit test, `br 0` is the back edge. There is no loop-with-condition instruction because
    // one would be a special case of exactly this.
    case Expr.While(c, body, _) =>
      val (ci, cr, st1) = lower(c, fns, classes, zeroArity, st0)
      val (nr, st2) = st1.fresh
      val (bi, _, st3) = lower(body, fns, classes, zeroArity, st2)
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
            val (vi, vr, st1) = lower(v, fns, classes, zeroArity, st)
            // Always a LOCAL here. Whether a `val` initialises a module global is decided in
            // `program`, where the top level is actually known — deciding it here meant a local
            // `val` that happened to share a name with a top-level one wrote the GLOBAL instead of
            // shadowing it, and nothing in the program said so.
            val (slot, st2) = st1.fresh
            acc = acc ++ vi :+ Instr.Move(slot, vr)
            st = st2.bind(n, slot)
          case Stmt.Exp(ex) =>
            val (xi, _, st1) = lower(ex, fns, classes, zeroArity, st)
            acc = acc ++ xi
            st = st1
      }
      result match
        case Some(r) =>
          val (ri, rr, st1) = lower(r, fns, classes, zeroArity, st)
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
        val (ai, ar, stN) = lower(a, fns, classes, zeroArity, st)
        acc = acc ++ ai; regs = ar :: regs; st = stN
      }
      val (d, st1) = st.fresh
      (acc :+ Instr.Call(d, fns.indexOf(obj + "." + nm), regs.reverse), d, st1)

    // A call whose name is a METHOD of some declared class. Same shape as the field read below —
    // a `Switch` with an arm per declaring class and a DEFAULT that dispatches dynamically — and
    // for the same reason: without a type checker, only the receiver knows which class it is.
    //
    // This is what makes `trait` work at Tier 0. A trait contributes a NAME and, if its member has
    // a body, an implementation that its subclasses inherit; the call site never needs to know the
    // static type, because the arm is chosen by the tag at run time. `given`/`using` is the part
    // that genuinely needs types, and it stays refused.
    case Expr.MethodCall(recv, nm, argEs, p)
        if classes.exists(c => c.methods.exists(mm => mm.name == nm)) =>
      val owners = classes.filter(c => c.methods.exists(mm => mm.name == nm))
      val (ri, rr, st1) = lower(recv, fns, classes, zeroArity, st0)
      var acc = ri
      var argRegs: List[Int] = Nil
      var st = st1
      argEs.foreach { a =>
        val (ai, ar, stN) = lower(a, fns, classes, zeroArity, st)
        acc = acc ++ ai; argRegs = argRegs :+ ar; st = stN
      }
      val (d, stD) = st.fresh
      st = stD
      var arms: List[SwitchArm] = Nil
      owners.foreach { o =>
        val (t, sN) = st.typeIdx(o.name, o.fields.length)
        st = sN
        val fi = fns.indexOf(o.name + "." + nm)
        if fi < 0 then throw LowerFail(p, "method '" + nm + "' of " + o.name + " was not lifted")
        val (cr, sN2) = st.fresh
        st = sN2
        arms = arms :+ SwitchArm(t, List(Instr.Call(cr, fi, rr :: argRegs), Instr.Move(d, cr)))
      }
      val (nk, stK) = st.constIdx(Lit.LStr(nm))
      val (ir2, stF) = stK.fresh
      (acc :+ Instr.Switch(rr, arms, List(Instr.Invoke(ir2, nk, rr, argRegs), Instr.Move(d, ir2))),
       d, stF)

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
      val (ri, rr, st1) = lower(recv, fns, classes, zeroArity, st0)
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
      val (ri, rr, st1) = lower(recv, fns, classes, zeroArity, st0)
      var acc = ri
      var regs: List[Int] = Nil
      var st = st1
      argEs.foreach { a =>
        val (ai, ar, stN) = lower(a, fns, classes, zeroArity, st)
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
      val (si, sr, st1) = lower(scrut, fns, classes, zeroArity, st0)
      val (rd, st3) = st1.fresh
      // A non-exhaustive match must FAIL, loudly, at the point of failure. Falling through to unit
      // would be a wrong answer with a clean exit — the shape `p.x` returning `Stub` already cost
      // this module one debugging round.
      val (mk, st4) = st3.constIdx(Lit.LStr("match: no arm matched"))
      val (thr, st5) = st4.primIdx("__throw__")
      val (msgR, st6) = st5.fresh
      val fallback: List[Instr] = List(Instr.Const(msgR, mk), Instr.Prim(rd, thr, List(msgR)))
      val (chain, stF) = armChain(arms, sr, rd, fns, classes, zeroArity, st6, fallback)
      (si ++ chain, rd, stF)

    // LAMBDA LIFTING. The body becomes a top-level function whose FIRST parameters are the captured
    // values and whose remaining ones are the lambda's own — which is exactly the shape `MkClos`
    // describes, so the instruction needed no change to support closures.
    case Expr.Lambda(ps, body, p) =>
      val pnames = ps.map(_.name)
      val free = freeVars(body, pnames).distinct.filter(n => st0.lookup(n).isDefined)
      val capRegs = free.map(n => st0.lookup(n).get)
      // The lifted function gets a FRESH register space: it is a separate frame at run time, and
      // sharing the numbering with the enclosing function would be a silent aliasing bug.
      val inner0 = St(free.length + ps.length, free.length + ps.length,
                      (free.zipWithIndex.map((n, i) => (n, i)) ++
                       pnames.zipWithIndex.map((n, i) => (n, free.length + i))).reverse,
                      st0.consts, st0.prims, st0.types, st0.lifted, st0.globals)
      val (bi, br, inner) = lower(body, fns, classes, zeroArity, inner0)
      // The index is taken AFTER the body is lowered, because the body may lift lambdas of its own
      // and each one appends to the same list. Taking it before gave an inner lambda and its
      // enclosing one THE SAME index: the inner appended first and won, so the outer's `MkClos`
      // pointed at the inner function. Calling it then passed the outer's argument count to the
      // inner's arity — `__lam2 takes 2 argument(s), given 1` — on BOTH lanes, identically, which
      // is why the differential gate could not see it. Any lambda nested inside a lambda that
      // captures was affected, including every `for` with two generators.
      val idx = fns.length + inner.lifted.length
      val f = Func("__lam" + idx, free.length + ps.length,
                   if inner.max > 0 then inner.max else 1, bi :+ Instr.Ret(br))
      val st1 = st0.copy(consts = inner.consts, prims = inner.prims, types = inner.types,
                         lifted = inner.lifted :+ f)
      val (d, st2) = st1.fresh
      (List(Instr.MkClos(d, idx, capRegs)), d, st2)

    case Expr.Try(body, exn, handler, _) =>
      val (d, st1) = st0.fresh
      val (xr, st2) = st1.fresh
      val (bi, br, st3) = lower(body, fns, classes, zeroArity, st2)
      val (hi, hr, st4) = lower(handler, fns, classes, zeroArity, st3.bind(exn, xr))
      (List(Instr.Try(d, bi :+ Instr.Move(d, br), xr, hi :+ Instr.Move(d, hr))), d,
       st4.copy(env = st0.env))

    // `Array(a, b, c)` — allocate, then fill. The IR has had arrays from the start (the frame the
    // bridge builds IS one); only the front had no syntax for them, which is why this is six lines
    // rather than a feature.
    case Expr.Call("Array", argEs, p) if !classes.exists(c => c.name == "Array") =>
      var acc: List[Instr] = Nil
      var regs: List[Int] = Nil
      var st = st0
      argEs.foreach { a =>
        val (ai, ar, stN) = lower(a, fns, classes, zeroArity, st)
        acc = acc ++ ai; regs = regs :+ ar; st = stN
      }
      val (nk, st1) = st.constIdx(Lit.LInt(argEs.length.toLong))
      val (nr, st2) = st1.fresh
      val (d, st3) = st2.fresh
      var fill: List[Instr] = Nil
      var stf = st3
      regs.zipWithIndex.foreach { (r, i) =>
        val (ik, sN) = stf.constIdx(Lit.LInt(i.toLong))
        val (ir, sN2) = sN.fresh
        fill = fill ++ List(Instr.Const(ir, ik), Instr.ArrSet(d, ir, r))
        stf = sN2
      }
      (acc ++ List(Instr.Const(nr, nk), Instr.NewArr(d, nr)) ++ fill, d, stf)

    // `mkAdd(1)` where `mkAdd` is a PARENLESS def returning a function. Two steps, not one: call
    // `mkAdd` with no arguments to get the function, then APPLY it. Lowering it as a single call
    // passed one argument to a zero-parameter function and produced invalid IR — caught by the
    // verifier rather than by a wrong answer, which is what invariant I-4 exists for.
    case Expr.Call(fn, argEs, p)
        if argEs.nonEmpty && fns.contains(fn) && zeroArity.contains(fn) =>
      val (fr, st1) = st0.fresh
      var acc: List[Instr] = List(Instr.Call(fr, fns.indexOf(fn), Nil))
      var regs: List[Int] = Nil
      var st = st1
      argEs.foreach { a =>
        val (ai, ar, stN) = lower(a, fns, classes, zeroArity, st)
        acc = acc ++ ai; regs = regs :+ ar; st = stN
      }
      val (d, stF) = st.fresh
      (acc :+ Instr.CallV(d, fr, regs), d, stF)

    case Expr.Call(fn, argEs, p) =>
      var acc: List[Instr] = Nil
      var regs: List[Int] = Nil
      var st = st0
      argEs.foreach { a =>
        val (ai, ar, st1) = lower(a, fns, classes, zeroArity, st)
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

  /** Every name a pattern binds, at ANY depth. One level was enough while patterns could not nest;
    * `case Right(ByteRead(v, _))` binds `v` two levels down, and a closure that missed it would
    * capture nothing and read a stale slot. */
  /** An ABSTRACT member: declared, no body. The parser gives it a placeholder body it can be told
    * apart by, because a trait's abstract members exist to make the NAME known — a call to one is
    * dispatched by the receiver's tag to whichever class implements it. */
  /** Inside a method, an unqualified call to a SIBLING method means `this.that(…)`.
    *
    * Rewritten on the AST rather than resolved in the lowering, because the lowering does not know
    * which class a function came from — by then a method is an ordinary top-level `C.m`. Doing it
    * here keeps that flattening total.
    *
    * Field names are NOT rewritten: they are already bound as locals at the top of the method body,
    * so an unqualified `x` resolves by ordinary scoping and a local `val x` shadows it, exactly as
    * it would anywhere else. */
  private def selfCalls(e: Expr, ms: List[String]): Expr =
    def go(x: Expr): Expr = selfCalls(x, ms)
    e match
      case Expr.Call(fn, as, p) if ms.contains(fn) =>
        Expr.MethodCall(Expr.Name("this", p), fn, as.map(go), p)
      case Expr.Name(n, p) if ms.contains(n) => Expr.MethodCall(Expr.Name("this", p), n, Nil, p)
      case Expr.Call(fn, as, p)              => Expr.Call(fn, as.map(go), p)
      case Expr.MethodCall(r, n, as, p)      => Expr.MethodCall(go(r), n, as.map(go), p)
      case Expr.Bin(o, l, r, p)              => Expr.Bin(o, go(l), go(r), p)
      case Expr.Neg(x, p)                    => Expr.Neg(go(x), p)
      case Expr.Not(x, p)                    => Expr.Not(go(x), p)
      case Expr.If(c, t, el, p)              => Expr.If(go(c), go(t), el.map(go), p)
      case Expr.While(c, b, p)               => Expr.While(go(c), go(b), p)
      case Expr.Assign(n, v, p)              => Expr.Assign(n, go(v), p)
      case Expr.Update(a, i, v, p)           => Expr.Update(go(a), go(i), go(v), p)
      case Expr.Lambda(ps, b, p)             => Expr.Lambda(ps, selfCalls(b, ms.filter(m => !ps.exists(q => q.name == m))), p)
      case Expr.Try(b, x, h, p)              => Expr.Try(go(b), x, go(h), p)
      case Expr.Interp(parts, xs, p)         => Expr.Interp(parts, xs.map(go), p)
      case Expr.Match(sc, arms, p) =>
        Expr.Match(go(sc), arms.map(a => MatchArm(a.pat, a.guard.map(go), go(a.body))), p)
      case Expr.Block(sts, res, p) =>
        Expr.Block(sts.map { st => st match
          case Stmt.Val(n, v, mu, q) => Stmt.Val(n, go(v), mu, q)
          case Stmt.Exp(x)           => Stmt.Exp(go(x))
        }, res.map(go), p)
      case other => other

  private def isAbstract(d: Def): Boolean = d.body match
    case Expr.Name("__abstract__", _) => true
    case _                            => false

  private def patNames(p: Pat): List[String] = p match
    case Pat.PBind(n, _)       => List(n)
    case Pat.PCtor(_, args, _) => args.flatMap(a => patNames(a))
    case _                     => Nil

  /** Arms, in source order, as nested `If`s. Built back to front so each arm's `else` is the rest
    * of the chain — which is what makes source order the matching order, exactly as written. */
  private def armChain(arms: List[MatchArm], scrut: Int, dst: Int,
                       fns: List[String], classes: List[ClassDef], zeroArity: List[String], st0: St,
                       fallback: List[Instr]): (List[Instr], St) =
    if arms.isEmpty then (fallback, st0)
    else
      val a = arms.head
      val (rest, st1) = armChain(arms.tail, scrut, dst, fns, classes, zeroArity, st0, fallback)
      // EVERY arm kind goes through the SAME recursion: a wildcard is a pattern with no test, a
      // binding is a pattern with no test and one name. The four hand-written shapes this replaces
      // were four things that had to agree about arm order, binding and fall-through.
      val (armI, st2) = testPat(List((a.pat, scrut)), a.guard, a.body, dst, rest,
                                fns, classes, zeroArity, st1.env, st1)
      (armI, st2.copy(env = st1.env))

  /** Test a WORKLIST of (pattern, value register) pairs, then lower the body once all of them pass.
    * A failure at any depth branches to `rest` — the next arm.
    *
    * A worklist rather than a tree walk, because a nested pattern's arguments are just more work at
    * a deeper register: `Right(ByteRead(v, _))` pushes `(ByteRead(v, _), fieldReg)` and the function
    * never needs to know how deep it is. Field reads are emitted INSIDE the tag test, so a field of
    * the wrong constructor is never read.
    *
    * The body is lowered at the BASE CASE with the environment every level accumulated, which is why
    * the environment is threaded down rather than returned up. */
  private def testPat(work: List[(Pat, Int)], guard: Option[Expr], body: Expr, dst: Int,
                      rest: List[Instr],
                      fns: List[String], classes: List[ClassDef], zeroArity: List[String],
                      env: List[(String, Int)], st0: St): (List[Instr], St) =
    if work.isEmpty then
      // The guard is the LAST test, and it is evaluated with the pattern's bindings in scope — that
      // is the whole point of `case Some(n) if n > 0`. A failing guard falls to `rest`, exactly like
      // a failing pattern, so `case n if n > 0` followed by `case n` behaves as written.
      guard match
        case None =>
          val (bi, br, st1) = lower(body, fns, classes, zeroArity, st0.copy(env = env))
          (bi :+ Instr.Move(dst, br), st1)
        case Some(g) =>
          val (gi, gr, st1) = lower(g, fns, classes, zeroArity, st0.copy(env = env))
          val (bi, br, st2) = lower(body, fns, classes, zeroArity, st1.copy(env = env))
          (gi :+ Instr.If(gr, bi :+ Instr.Move(dst, br), rest), st2)
    else
      val (p, vr) = work.head
      val more = work.tail
      p match
        // No test and no binding: a wildcard is simply work that is already done.
        case Pat.PWild(_) => testPat(more, guard, body, dst, rest, fns, classes, zeroArity, env, st0)
        case Pat.PBind(n, _) =>
          testPat(more, guard, body, dst, rest, fns, classes, zeroArity, (n, vr) :: env, st0)
        case Pat.PLit(v, _) =>
          val (vi, lr, st1) = lower(v, fns, classes, zeroArity, st0)
          val (cr, st2) = st1.fresh
          val (inner, st3) = testPat(more, guard, body, dst, rest, fns, classes, zeroArity, env, st2)
          (vi ++ List(Instr.Bin(BinOp.Eq, NumKind.Dyn, cr, vr, lr), Instr.If(cr, inner, rest)), st3)
        case Pat.PCtor(cname, cargs, cp) =>
          val arity = classes.find(c => c.name == cname).map(c => c.fields.length)
            .orElse(ctors.find((n, _) => n == cname).map((_, ar) => ar))
            .getOrElse(throw LowerFail(cp, "unknown constructor '" + cname + "' in a pattern"))
          if cargs.nonEmpty && cargs.length != arity then
            throw LowerFail(cp, cname + " has " + arity + " field(s), the pattern binds " + cargs.length)
          val (t, st1) = st0.typeIdx(cname, arity)
          val (tagK, st2) = st1.constIdx(Lit.LInt(t.toLong))
          val (tagV, st3) = st2.fresh
          val (tagR, st4) = st3.fresh
          val (cr, st5) = st4.fresh
          // A wildcard argument costs no register and no read. Anything else becomes deeper work.
          var reads: List[Instr] = Nil
          var deeper: List[(Pat, Int)] = Nil
          var stb = st5
          cargs.zipWithIndex.foreach { (ap, i) =>
            ap match
              case Pat.PWild(_) => ()
              case _ =>
                val (fr, sN) = stb.fresh
                reads = reads :+ Instr.Field(fr, vr, t, i)
                deeper = deeper :+ ((ap, fr))
                stb = sN
          }
          val (inner, stF) = testPat(deeper ++ more, guard, body, dst, rest,
                                     fns, classes, zeroArity, env, stb)
          // `Tag` is TOTAL on both lanes (-1 for anything that is not Data), so testing the tag of a
          // field that turned out not to be a constructor is a clean non-match, not a crash.
          (List(Instr.Tag(tagR, vr), Instr.Const(tagV, tagK),
                Instr.Bin(BinOp.Eq, NumKind.Dyn, cr, tagR, tagV),
                Instr.If(cr, reads ++ inner, rest)), stF)

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
    case "&" => BinOp.BAnd; case "|" => BinOp.BOr; case "^" => BinOp.BXor
    case "<<" => BinOp.Shl; case ">>" => BinOp.Shr; case ">>>" => BinOp.UShr
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
    // Every class's FULL method set: its own, plus the concrete members of the traits it extends,
    // transitively. A member the subclass defines itself WINS — that is what overriding means, and
    // it falls out of putting the class's own methods first and de-duplicating by name.
    val resolved = p.classes.map { c =>
      var seen = c.methods.map(_.name)
      var inherited: List[Def] = Nil
      var queue = c.parents
      var guard = 0
      while queue.nonEmpty && guard < 64 do
        guard = guard + 1
        val head = queue.head
        queue = queue.tail
        p.traits.find(t => t.name == head).foreach { t =>
          queue = queue ++ t.parents
          t.methods.foreach { mm =>
            if !seen.contains(mm.name) && !isAbstract(mm) then
              seen = mm.name :: seen
              inherited = inherited :+ mm
          }
        }
      c.copy(methods = c.methods.filter(mm => !isAbstract(mm)) ++ inherited)
    }
    // Methods become ORDINARY top-level functions taking the receiver first — the same flattening
    // `object` members get. The body is prefixed with a `val` per field so that an unqualified `x`
    // inside a method means `this.x`, and a local `val x` later shadows it exactly as it would in
    // any other block.
    val methodDefs = resolved.flatMap { c =>
      c.methods.map { mm =>
        val binds = c.fields.map(f =>
          Stmt.Val(f.name, Expr.MethodCall(Expr.Name("this", mm.pos), f.name, Nil, mm.pos),
                   false, mm.pos))
        val inner = selfCalls(mm.body, c.methods.map(_.name))
        val body = if binds.isEmpty then inner else Expr.Block(binds, Some(inner), mm.pos)
        Def(c.name + "." + mm.name, Param("this", mm.pos) :: mm.params, body, mm.pos)
      }
    }
    val allDefs = (p.defs ++ objectDefs ++ methodDefs) :+ entryDef
    // Names that may be referenced WITHOUT parentheses. Collected once, before any lowering, so a
    // def declared later in the file is still callable from one declared earlier.
    val zeroArityNames = allDefs.filter(d => d.params.isEmpty).map(d => d.name)
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
      val (body, r, st) = lower(d.body, names, resolved ++ tupleClasses, zeroArityNames, st0)
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
