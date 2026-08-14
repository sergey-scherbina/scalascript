package ssc3

// Lowering — the typed AST to SSC IR. v3/specs/20-core-language.md §2 (Tier 0).
//
// This is the half of the front that SURVIVES: v3/specs/40-front-on-uniml.md replaces the parser
// with UniML's projection, and this file keeps consuming the same typed AST.
//
// Register allocation is a bump allocator per function with a high-water mark. A register is never
// reused across an expression, which is wasteful and is the right trade for V-0: the bridge stores
// the whole frame in one array, so frame size costs allocation once per call and nothing per step.

// `origin` is the path of the UNIT the failing declaration came from, when that is not the file the
// user named. `Loader.merge` concatenates every unit's declarations into one `Program` and the unit
// boundary is gone by the time lowering runs, so a `LowerFail` used to be printed against the ROOT
// path with an IMPORTED unit's line — `tests/conformance/tkv2-busi-home.ssc:119` on a file 71 lines
// long, for a site that is really `std/ui/form.ssc:119`. A position past the end of the file it
// names costs the reader three commands to disbelieve (BUGS.md
// `v3-lowerfail-reports-the-root-path-with-an-imported-unit's-line`).
//
// Defaulted, so every existing `throw LowerFail(pos, msg)` is untouched: the path is attached ONCE,
// where the per-def loop knows which declaration it is lowering.
final case class LowerFail(pos: Pos, message: String, origin: Option[String] = None)
    extends RuntimeException(pos.show + ": " + message):
  def at(path: Option[String]): LowerFail =
    if origin.nonEmpty || path.isEmpty then this else copy(origin = path)

object Lower:

  /** Names the language provides, mapped to the v2 prim spelling the bridge emits. Deliberately a
    * TABLE and not a fallthrough: an unknown name is a `LowerFail` naming it, so a typo becomes a
    * diagnostic at the source position rather than an unbound global three layers down. */
  // `nanoTime()` is the language's name for the clock and `io.nanoTime` is the prim's, which is
  // v2's own name for it — the bridge emits prim names verbatim, so choosing a different one would
  // be a program that runs on the executor and is refused on the bridge (invariant I-3).
  private val builtins: List[(String, String)] = List("println" -> "io.println", "__autoOutput__" -> "__autoOutput__", "__throw__" -> "__throw__", "nanoTime" -> "io.nanoTime")

  /** Constructors the language provides. `List(a, b)` is `Cons(a, Cons(b, Nil))` — measured off the
    * oracle, not assumed — so it is ordinary `MkData` over the type table rather than a special
    * form. `Some`/`None` are the same shape and cost nothing extra. */
  private val ctors: List[(String, Int)] =
    List("Cons" -> 2, "Nil" -> 0, "Some" -> 1, "None" -> 0,
         // `Either` — `Right`/`Left`. Measured 2026-08-07: FIFTY-THREE corpus cases were refused
         // with `call to unknown function 'Right'`, more than any other single name and by a
         // factor of four. It is a language-provided constructor exactly as `Some` is, and it was
         // missing for the same reason `Some` once was — nothing in the kernel names it, so
         // nothing put it in the table.
         "Right" -> 1, "Left" -> 1)

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
    } :+ ClassDef("Pair", List(Param("_1", Pos.none), Param("_2", Pos.none)), Nil, Nil, Pos.none)

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
    /** Interning, and FLOATS ARE INTERNED BY BIT PATTERN.
      *
      * `indexOf` compares with `==`, which on a `Double` field says `-0.0 == 0.0` is TRUE and
      * `NaN == NaN` is FALSE — both wrong for a pool whose whole job is identity. The first cost a
      * real answer: `println(-0.0)` put `LFloat(-0.0)` in the pool, `1.0 / 0.0` then looked up
      * `LFloat(0.0)`, FOUND that slot, and divided by NEGATIVE zero — so the program printed `-inf`
      * where it should print `inf`, and `-1.0 / 0.0` printed `inf`. The two lines came out swapped.
      * The second is only waste: every NaN got its own slot.
      *
      * `doubleToLongBits` gives both properties at once — it separates the zeros and canonicalises
      * NaN, so all NaNs share one slot and behave identically anyway.
      *
      * IT TOOK A SECOND FRONT TO SURFACE THIS, and it nearly did not. v3's own parser reads `-0.0`
      * as `Neg(DoubleLit(0.0))` — a runtime negation of a POSITIVE zero — so the poisoned constant
      * was never created and the pool looked correct for two months. UniML folds the sign into the
      * literal, which is equally right and hit the bug immediately. The front differential itself
      * was blind: `AstText` deliberately folds `Neg(float)` into a negative literal, so the two
      * trees printed identically while executing differently. What caught it was a gate comparing
      * output against a recorded expectation — not the two fronts against each other. */
    def constIdx(l: Lit): (Int, St) =
      def same(a: Lit, b: Lit): Boolean = (a, b) match
        case (Lit.LFloat(x), Lit.LFloat(y)) =>
          java.lang.Double.doubleToLongBits(x) == java.lang.Double.doubleToLongBits(y)
        case _ => a == b
      val i = consts.indexWhere(c => same(c, l))
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
  // EXPR-WALKER
  private def freeVars(e: Expr, bound: List[String]): List[String] = e match
    case Expr.Name(n, _)          => if bound.contains(n) then Nil else List(n)
    case Expr.Bin(_, l, r, _)     => freeVars(l, bound) ++ freeVars(r, bound)
    case Expr.Neg(x, _)           => freeVars(x, bound)
    case Expr.Not(x, _)           => freeVars(x, bound)
    case Expr.Assign(n, v, _)     => (if bound.contains(n) then Nil else List(n)) ++ freeVars(v, bound)
    case Expr.Update(a, i, v, _)  => freeVars(a, bound) ++ freeVars(i, bound) ++ freeVars(v, bound)
    case Expr.Apply(f, as, _)     => freeVars(f, bound) ++ as.flatMap(a => freeVars(a, bound))
    case Expr.Prim(_, as, _)      => as.flatMap(a => freeVars(a, bound))
    case Expr.Perform(_, as, _)   => as.flatMap(a => freeVars(a, bound))
    case Expr.Handle(b, arms, _)  =>
      freeVars(b, bound) ++ arms.flatMap(a => freeVars(a.body, bound ++ a.params ++ List(a.k)))
    // THE CONTINUATION IS A USE OF `k`. Without naming it here the lambda lifter does not see it,
    // so `opts.flatMap(opt => resume(opt))` — a resume inside a nested lambda, which is what a
    // multi-shot handler is made of — lifted a function that never captured `resume` and failed at
    // lowering with "`resume` is not a continuation in scope". A node that reads a variable has to
    // say so, whatever else it is.
    case Expr.Resume(k, v, _)     =>
      (if bound.contains(k) then Nil else List(k)) ++ freeVars(v, bound)
    case Expr.If(c, t, el, _)     => freeVars(c, bound) ++ freeVars(t, bound) ++ el.toList.flatMap(x => freeVars(x, bound))
    case Expr.While(c, b, _)      => freeVars(c, bound) ++ freeVars(b, bound)
    // THE CALLEE IS A NAME TOO. This read `case Expr.Call(_, as, …)` and scanned only the ARGUMENTS,
    // so a lambda calling a function it captured never recorded it as free — the capture was not
    // passed, the lowering fell through to the top-level function table, and
    // `def comp(f, g) = x => f(g(x))` failed with `call to unknown function 'g'` while `def ap(f, x)
    // = f(x)` worked, because there `f` is a live register rather than a capture.
    //
    // Naming it here is safe because BOTH consumers filter: the lambda-lifting site keeps only names
    // `st0.lookup` resolves, and the other keeps only names that are not top-level. So `println(x)`
    // contributes nothing, and a function-valued parameter contributes itself.
    case Expr.Call(fn, as, _)     => (if bound.contains(fn) then Nil else List(fn)) ++ as.flatMap(a => freeVars(a, bound))
    case Expr.MethodCall(r, _, as, _) => freeVars(r, bound) ++ as.flatMap(a => freeVars(a, bound))
    // Reached because `liftLocals` runs BEFORE `resolveMethodRefs` — the receiver of a selection
    // is an ordinary expression and its free names must be captured like any other.
    case Expr.MethodRef(r, _, _)      => freeVars(r, bound)
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
          // A local `def` binds its own name for everything after it — and for ITSELF, since it
          // may recurse.
          case Stmt.LocalDef(d) =>
            b = d.name :: b
            acc = acc ++ freeVars(d.body, b ++ d.params.map(_.name))
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
    // `1 to 3` / `0 until n` — a LIST of ints, which is what the reference lane builds
    // (`v2/src/Runtime.scala:2978`, a `Cons` chain, not a lazy range). Routed through `__arith__`
    // because that is the prim v2 already answers for these two names, so the bridge lane costs
    // nothing and the executor implements the identical vocabulary rather than a parallel one.
    //
    // Desugared HERE and not in a front: both fronts parse it as the binary operator it looks
    // like, and a desugaring written twice is two things that will disagree.
    case Expr.Bin(o, l, r, p) if o == "to" || o == "until" =>
      lower(Expr.Prim("__arith__", List(Expr.StrLit(o, p), l, r), p), fns, classes, zeroArity, st0)

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
    // `k -> v` is a PAIR, which in this language is a `Tuple2` — the same node a `(k, v)` literal
    // builds. Making it a tuple rather than its own thing is what lets `Map(a -> 1, b -> 2)` be an
    // ordinary constructor call over ordinary values, and it is what v2 expects: its Map factory
    // reads `DataV("Tuple2", [k, v])` arguments.
    case Expr.Bin("->", l, r, p) =>
      lower(Expr.Call("Pair", List(l, r), p), fns, classes, zeroArity, st0)

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

    // An ALPHANUMERIC infix operator is a method call — `a to b` IS `a.to(b)` in Scala, and routing
    // it to `Invoke` is that identity rather than a new mechanism. It also means the receiver's
    // runtime type decides, so `to` on an Int and a `to` someone later defines on their own type
    // both work without the lowering knowing either. Same shape `++` above already uses.
    case Expr.Bin(op, l, r, _) if op.nonEmpty && (op.charAt(0).isLetter || op.charAt(0) == '_') =>
      val (li, lr, st1) = lower(l, fns, classes, zeroArity, st0)
      val (ri, rr, st2) = lower(r, fns, classes, zeroArity, st1)
      val (k, st3) = st2.constIdx(Lit.LStr(op))
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
          // `liftLocals` runs before any lowering and removes every one of these. Reaching here
          // means the pass missed one, and a silent skip would drop a whole function — so it is an
          // internal error with a name rather than a no-op.
          case Stmt.LocalDef(d) =>
            throw LowerFail(d.pos, "internal: the local `def` '" + d.name + "' was not lifted")
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
    // `LazyList.from(n)` — the one LazyList CONSTRUCTOR the corpus uses. Lowered as an invoke whose
    // RECEIVER is the starting Int, so there is no `LazyList` name to resolve at run time and the
    // executor needs no notion of a module object. Everything after it (`map`, `take`, `sum`) is an
    // ordinary method call on the value this produces.
    // `Bench.opaque(x)` — the corpus anti-fold barrier — is the IDENTITY here, and that is the
    // honest implementation rather than a shortcut. Its whole job (`v1/runtime/std/bench.ssc`,
    // `docs/bench/corpus-antifold.md`) is to stop an OPTIMISER proving the surrounding expression
    // constant: on Rust it becomes `std::hint::black_box`, on JVM/JS/interp it is already an
    // ordinary identity call. v3 walks IR and folds nothing, so there is nothing to defeat — the
    // same reasoning that makes `asInstanceOf` the identity in the executor.
    //
    // Used without an import in the corpus, so there is no `std/bench.ssc` to resolve; the name is
    // matched here for the same reason `Set`/`Map`/`Array` are.
    //
    // ONE CONSEQUENCE, stated: the v2 BRIDGE inherits this erasure, so a program measured through
    // the bridge loses the barrier. Nothing measures that lane today — `bench/run.sc` has no
    // v3-bridge column — but a future one would need the barrier emitted rather than erased.
    case Expr.MethodCall(Expr.Name("Bench", _), "opaque", List(argE), _)
        if !classes.exists(c => c.name == "Bench") =>
      lower(argE, fns, classes, zeroArity, st0)

    // `System.nanoTime()` is Scala's spelling of the clock, and the SHARED bench wrapper has to use
    // it: the js backend maps only that spelling — to `Math.round(performance.now() * 1e6)` — and
    // emits a bare `nanoTime()` verbatim as an undefined JS function. So the wrapper cannot move to
    // v3's spelling; v3 resolves Scala's, and the two lanes measure the same text. Without this the
    // emitted wrapper died here with `unknown name 'System'` and the v3 column had to be timed by a
    // second, driver-side path (§55 B1).
    //
    // A SPELLING, NOT A KEYWORD, and the guards are what keep it one: a program that defines its own
    // `System` class or its own `System.nanoTime` function keeps them, exactly as `Bench.opaque`
    // above yields to a user `Bench`. An unconditional match here would break `System` as an
    // ordinary name, which is how an earlier `extension` refusal broke `extension = extension + 1`.
    case Expr.MethodCall(Expr.Name("System", _), "nanoTime", Nil, p)
        if !classes.exists(c => c.name == "System") && !fns.contains("System.nanoTime") =>
      lower(Expr.Call("nanoTime", Nil, p), fns, classes, zeroArity, st0)

    case Expr.MethodCall(Expr.Name("LazyList", _), "from", List(argE), _)
        if !classes.exists(c => c.name == "LazyList") =>
      val (ai, ar, st1) = lower(argE, fns, classes, zeroArity, st0)
      val (k, st2) = st1.constIdx(Lit.LStr("__lazyFrom__"))
      val (d, st3) = st2.fresh
      (ai :+ Instr.Invoke(d, k, ar, Nil), d, st3)

    // AN INVARIANT, STATED WHERE IT WOULD BREAK. `resolveMethodRefs` runs over every def before
    // anything is lowered and leaves no `MethodRef` behind, so reaching this case means a new
    // producer of the node was added without going through that pass — which would otherwise show
    // up as a missing method call much later, in a program that merely does the wrong thing.
    case Expr.MethodRef(_, nm, p) =>
      throw LowerFail(p, "internal: a selection `" + nm + "` reached the lowering unresolved — " +
        "every `MethodRef` is meant to be rewritten by `resolveMethodRefs`")
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

    // AN OBJECT WITHOUT THAT MEMBER, said as itself instead of as a puzzle.
    //
    // `Dataset.fromFile(tmp)` has no `Dataset.fromFile`, so it fell past the arm above and the
    // lowering tried to evaluate the RECEIVER as a value. An object is a namespace and not a value,
    // so it arrived at the bare-name arm and came out as `unknown name 'Dataset'` — pointing at a
    // name that is perfectly well known, while the member that is missing goes unmentioned.
    //
    // IT WAS MY OWN NEW CLAUSE THAT MADE THIS UNREADABLE, which is how it was found:
    // `Driver.preludeNote` resolved `Dataset` against the prelude and produced
    //
    //     unknown name 'Dataset' — 'Dataset' comes from the standard prelude
    //
    // a message that contradicts itself in one line. Both halves were accurate; together they were
    // nonsense, because neither was about the member.
    //
    // A NAME IS AN OBJECT HERE IF THE PROGRAM HAS ANY `obj.something` — that is exactly what
    // flattening an `object` produces, so this asks the same table the arm above does and invents
    // no new notion. `Dataset` in `tests/conformance/dataset-shape` is the live case: the library
    // is a Tier 0 list library and `fromFile` needs host IO, which option 3 of the DATASET decision
    // exists to avoid. That is an honest refusal and it should read like one.
    // ONLY A NULLARY class is excluded, not every class. A bare `Expr.Name` can be a constructor —
    // `Nil`, `None`, `case Red` — but only when the class takes NO fields; one that takes fields
    // cannot be spelled as a name and its companion is what a `Name` receiver means. `Dataset` is
    // exactly that pair (a `case class Dataset(items)` beside an `object Dataset`), and excluding
    // every class left the very case this arm was written for still reading `unknown name`.
    // `globalIdx(obj + "." + nm)`, NOT just `globalIdx(obj)`, and the corpus is what said so.
    // An object's `val`/`var` members are module GLOBALS named `Owner.member` — they are not in
    // `fns`, which holds functions — so `Counter.n` on an `object Counter: var n = 0` looked to
    // this arm exactly like a missing member. `object-var-member-scope` went from PASS to a
    // refusal, N 206 -> 205, caught before the push by the measurement and by nothing else: every
    // gate stayed green, because no gate has an object with a `var` in it.
    case Expr.MethodCall(Expr.Name(obj, _), nm, _, p)
        if st0.lookup(obj).isEmpty && st0.globalIdx(obj) < 0 &&
           st0.globalIdx(obj + "." + nm) < 0 &&
           !classes.exists(c => c.name == obj && c.fields.isEmpty) &&
           !ctors.exists((cn, ar) => cn == obj && ar == 0) &&
           fns.exists(f => f.startsWith(obj + ".")) =>
      val members = fns.filter(f => f.startsWith(obj + ".")).map(f => f.substring(obj.length + 1)).sorted
      throw LowerFail(p, "'" + obj + "' has no member '" + nm + "' — it declares " +
        (if members.length <= 8 then members.mkString(", ")
         else members.take(8).mkString(", ") + " and " + (members.length - 8).toString + " more"))

    // `p.copy(y = 20)` — a new value of the SAME class with some fields replaced. Dispatched by
    // the receiver's tag like every other method, and each arm builds its own constructor: the
    // fields the call names come from the arguments, the rest are read back off the receiver.
    //
    // Not an AST rewrite, because which class it is is not known until run time — the same reason
    // field-by-name access is a `Switch`.
    case Expr.MethodCall(recv, "copy", argEs, p)
        if classes.exists(c => c.fields.nonEmpty && copyFits(c, argEs)) =>
      val owners = classes.filter(c => c.fields.nonEmpty && copyFits(c, argEs))
      val (ri, rr, st1) = lower(recv, fns, classes, zeroArity, st0)
      var acc = ri
      var st = st1
      var named: List[(String, Int)] = Nil
      argEs.foreach { a => a match
        case Expr.NamedArg(n, v, _) =>
          val (vi, vr, stN) = lower(v, fns, classes, zeroArity, st)
          acc = acc ++ vi; named = named :+ ((n, vr)); st = stN
        case other => throw LowerFail(p, "`copy` takes named arguments at Tier 0")
      }
      val (d, stD) = st.fresh
      st = stD
      var arms: List[SwitchArm] = Nil
      owners.foreach { o =>
        val (t, sN) = st.typeIdx(o.name, o.fields.length)
        st = sN
        var body: List[Instr] = Nil
        var regs: List[Int] = Nil
        o.fields.zipWithIndex.foreach { (f, i) =>
          named.find((n, _) => n == f.name) match
            case Some((_, vr)) => regs = regs :+ vr
            case None =>
              val (fr, s2) = st.fresh
              st = s2
              body = body :+ Instr.Field(fr, rr, t, i)
              regs = regs :+ fr
        }
        val (cr, s3) = st.fresh
        st = s3
        arms = arms :+ SwitchArm(t, body ++ List(Instr.MkData(cr, t, regs), Instr.Move(d, cr)))
      }
      val (nk, stK) = st.constIdx(Lit.LStr("copy"))
      val (ir2, stF) = stK.fresh
      (acc :+ Instr.Switch(rr, arms, List(Instr.Invoke(ir2, nk, rr, Nil), Instr.Move(d, ir2))),
       d, stF)

    // `Cfg.name` where `name` is an object MEMBER, not a method. Rewritten to the dotted global it
    // is, so the ordinary name path resolves it — no second lookup rule for qualified reads.
    case Expr.MethodCall(Expr.Name(obj, _), nm, Nil, p) if st0.globalIdx(obj + "." + nm) >= 0 =>
      lower(Expr.Name(obj + "." + nm, p), fns, classes, zeroArity, st0)

    // `C.Red` — an enum case reached through its enum's name. v3 flattens an enum into one class
    // per case, so the qualifier carries no information by the time we are here; it is dropped
    // rather than resolved, and the case is the nullary constructor it already was.
    case Expr.MethodCall(Expr.Name(_, _), nm, Nil, p)
        if classes.exists(c => c.name == nm && c.fields.isEmpty) =>
      lower(Expr.Name(nm, p), fns, classes, zeroArity, st0)

    // `v.step(1, 2)` where `step` is a FIELD holding a function — a field READ followed by an
    // application, not a method call. Rewritten into the two nodes that already do exactly that, so
    // neither mechanism needs to learn about the other.
    //
    // It reached the corpus as a `Stub` — v2's silent sentinel at exit 0 — the moment `foldLeft`
    // became parseable and the case stopped being UNSUPPORTED. The defect was always there; making
    // one construct work is what let the program get far enough to show it, which is the argument
    // for re-running the whole corpus after every front change rather than the affected bucket.
    case Expr.MethodCall(recv, nm, argEs, p)
        if argEs.nonEmpty && classes.exists(c => c.fields.exists(f => f.name == nm)) &&
           !classes.exists(c => c.methods.exists(mm => mm.name == nm)) =>
      lower(Expr.Apply(Expr.MethodCall(recv, nm, Nil, p), argEs, p), fns, classes, zeroArity, st0)

    // A call whose name is a METHOD of some declared class. Same shape as the field read below —
    // a `Switch` with an arm per declaring class and a DEFAULT that dispatches dynamically — and
    // for the same reason: without a type checker, only the receiver knows which class it is.
    //
    // This is what makes `trait` work at Tier 0. A trait contributes a NAME and, if its member has
    // a body, an implementation that its subclasses inherit; the call site never needs to know the
    // static type, because the arm is chosen by the tag at run time. `given`/`using` is the part
    // that genuinely needs types, and it stays refused.
    //
    // THE ARITY IS PART OF THE MATCH, and leaving it out generated INVALID IR. `open.lock` is a
    // FIELD read on a `MemoryHandleState`; some unrelated class has a `lock` METHOD taking one
    // argument; this arm claimed the call because a method of that NAME exists somewhere, emitted
    // `JvmSqliteFile.lock(receiver)` — one argument where the flattened method takes two — and the
    // verifier refused the module. **113 corpus cases** came through that one line, all importing
    // `scljet/jvm-vfs.ssc`. A name is not a signature, and this file resolves methods by name
    // across every declared class precisely because there is no type checker; the arity is the only
    // part of the signature available, so it has to be used.
    case Expr.MethodCall(recv, nm, argEs, p)
        if classes.exists(c => c.methods.exists(mm => mm.name == nm && mm.params.length == argEs.length)) =>
      val owners = classes.filter(c => c.methods.exists(mm => mm.name == nm && mm.params.length == argEs.length))
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
      // FIELD OWNERS GO IN THE SAME SWITCH. A name can be a method on one class and a field on
      // another — `name` and `sectorSize` are both, across `scljet/` — and whichever arm claimed
      // the call left the other's classes with no arm at all, so their receivers fell to the
      // dynamic `Invoke` default and the executor answered "method 'name' … is not implemented".
      // 76 corpus cases, and every one of them a WRONG READING rather than a missing feature: the
      // field is right there on the receiver. One switch, arms from both sources, the tag decides —
      // which is what this dispatch was always for.
      if argEs.isEmpty then
        classes.filter(c => c.fields.exists(f => f.name == nm) && !owners.exists(o => o.name == c.name))
          .foreach { o =>
            val (t, sN) = st.typeIdx(o.name, o.fields.length)
            val (fr, sN2) = sN.fresh
            st = sN2
            arms = arms :+ SwitchArm(t, List(Instr.Field(fr, rr, t, o.fields.indexWhere(f => f.name == nm)),
                                             Instr.Move(d, fr)))
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
    // The FIELD-ONLY case: no class has a method of this name at this arity, so the arm above did
    // not claim it. Same shape, and it stays separate because the common case is a plain record
    // read and paying the method lookup for it would be noise.
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

    // A named argument that survived the resolution pass means the callee's signature was not
    // known — a call through a value, or a method this front cannot see. A positioned refusal, not
    // a MatchError: the compiler's own exhaustiveness warning is what found this.
    // Applying a VALUE: evaluate the callee, then `CallV`. The same instruction a call through a
    // local closure already uses — a curried call is not a new mechanism, only a new spelling.
    // A HOST PRIMITIVE. The arguments are ordinary expressions; the name is not looked up in the
    // function table, because a prim is not a function this module defines — it is what the LANE
    // provides. The executor answers with `unknown primitive '…'` when it has none, and the bridge
    // hands the name to v2, which has its whole plugin fleet. Two lanes, two honest answers.
    // A performed operation. The op id was resolved by the rewrite in `programOf`; here it is an
    // ordinary instruction whose arguments are lowered like anyone else's.
    // `handle(body) { … }`. The body is lowered INSIDE the Handle block — that is what makes the
    // handler installed before it runs, and it is why `handle` cannot be an ordinary function: an
    // ordinary call evaluates its argument first, and a perform in there would find no handler.
    //
    // Each arm's `params` and `k` get registers of THIS function's frame, which is what
    // `specs/10-ssc-ir.md` §3 specifies, and they are bound by name for the arm's body only.
    case Expr.Handle(bodyE, arms, _) =>
      val (d, stD) = st0.fresh
      var st = stD
      var loweredArms: List[HandlerArm] = Nil
      arms.foreach { a =>
        var paramRegs: List[Int] = Nil
        var armSt = st
        a.params.foreach { pn =>
          val (r, s2) = armSt.fresh
          paramRegs = paramRegs :+ r
          armSt = s2.bind(pn, r)
        }
        val (kr, s3) = armSt.fresh
        armSt = s3.bind(a.k, kr)
        val (ab, ar, s4) = lower(a.body, fns, classes, zeroArity, armSt)
        // The arm's value IS the resume: the executor reads what `Resume` produced, and the arm
        // must END in one for the tail-resumptive check to accept it.
        // The arm ENDS WITH A RET, so its value has one place to come from. Without it the
        // executor would have to guess which register held the answer — and in CPS mode the answer
        // is whatever the arm computed, which may be `k(1) + k(10)` rather than a single resume.
        // Harmless on the tail-resumptive path, which reads the resumed value it already recorded.
        loweredArms = loweredArms :+ HandlerArm(a.op, paramRegs, kr, ab :+ Instr.Ret(ar))
        st = s4
      }
      val (bi, br, stB) = lower(bodyE, fns, classes, zeroArity, st)
      (List(Instr.Handle(d, bi :+ Instr.Move(d, br), loweredArms)), d, stB)

    case Expr.Resume(k, vE, p) =>
      val (vi, vr, st1) = lower(vE, fns, classes, zeroArity, st0)
      st1.lookup(k) match
        case None => throw LowerFail(p, "`" + k + "` is not a continuation in scope")
        case Some(kr) =>
          val (d, st2) = st1.fresh
          (vi :+ Instr.Resume(d, kr, vr), d, st2)

    case Expr.Perform(op, argEs, _) =>
      var acc: List[Instr] = Nil
      var regs: List[Int] = Nil
      var st = st0
      argEs.foreach { a =>
        val (ai, ar, stN) = lower(a, fns, classes, zeroArity, st)
        acc = acc ++ ai; regs = regs :+ ar; st = stN
      }
      val (d, st1) = st.fresh
      (acc :+ Instr.Perform(d, op, regs), d, st1)

    case Expr.Prim(name, argEs, _) =>
      var acc: List[Instr] = Nil
      var regs: List[Int] = Nil
      var st = st0
      argEs.foreach { a =>
        val (ai, ar, stN) = lower(a, fns, classes, zeroArity, st)
        acc = acc ++ ai; regs = regs :+ ar; st = stN
      }
      val (pi, st2) = st.primIdx(name)
      val (d, stF) = st2.fresh
      (acc :+ Instr.Prim(d, pi, regs), d, stF)

    case Expr.Apply(fnE, argEs, _) =>
      val (fi, fr, st1) = lower(fnE, fns, classes, zeroArity, st0)
      var acc = fi
      var regs: List[Int] = Nil
      var st = st1
      argEs.foreach { a =>
        val (ai, ar, stN) = lower(a, fns, classes, zeroArity, st)
        acc = acc ++ ai; regs = regs :+ ar; st = stN
      }
      val (d, stF) = st.fresh
      (acc :+ Instr.CallV(d, fr, regs), d, stF)

    case Expr.NamedArg(n, _, p) =>
      throw LowerFail(p, "a named argument '" + n + "' in a call whose signature is not known")

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
    // `Map(k -> v, …)` — allocate, then put. Built from the prims v2 ALREADY has (`map.new`,
    // `map.put`) rather than from a new IR form, for the same reason `Array` was: the instruction
    // set is not where a library type belongs. Each argument is a `Pair`, so its halves are read
    // with the field accessor the type table already knows.
    // `Set(…)` — one prim, because v2 exposes exactly one (`set.of`) and it takes every element.
    // Simpler than `Map`, which needs a put per pair.
    case Expr.Call("Set", argEs, p) if !classes.exists(c => c.name == "Set") =>
      var acc: List[Instr] = Nil
      var regs: List[Int] = Nil
      var st = st0
      argEs.foreach { a =>
        val (ai, ar, stN) = lower(a, fns, classes, zeroArity, st)
        acc = acc ++ ai; regs = regs :+ ar; st = stN
      }
      val (pi, st1) = st.primIdx("set.of")
      val (d, st2) = st1.fresh
      (acc :+ Instr.Prim(d, pi, regs), d, st2)

    case Expr.Call("Map", argEs, p) if !classes.exists(c => c.name == "Map") =>
      var acc: List[Instr] = Nil
      var st = st0
      val (newP, stN) = st.primIdx("map.new")
      st = stN
      val (d, stD) = st.fresh
      st = stD
      acc = acc :+ Instr.Prim(d, newP, Nil)
      val (pairT, stT) = st.typeIdx("Pair", 2)
      st = stT
      val (putP, stP) = st.primIdx("map.put")
      st = stP
      argEs.foreach { a =>
        val (ai, ar, s1) = lower(a, fns, classes, zeroArity, st)
        val (kr, s2) = s1.fresh
        val (vr, s3) = s2.fresh
        val (ig, s4) = s3.fresh
        st = s4
        acc = acc ++ ai ++ List(Instr.Field(kr, ar, pairT, 0), Instr.Field(vr, ar, pairT, 1),
                                Instr.Prim(ig, putP, List(d, kr, vr)))
      }
      (acc, d, st)

    // `Vector` shares `Array`'s representation, and the choice is not cosmetic. Vector is an
    // INDEXED sequence; lowering it to a list — the obvious alternative, since `Seq` already goes
    // there — would make `v(i)` a traversal, and `bench/corpus/vector-index.ssc` exists precisely to
    // measure indexed access. v3's column would then report list-walking under the name "vector",
    // which is the same class of error as measuring the wrong lane.
    //
    // WHAT THIS GIVES UP, stated rather than discovered later: a Scala `Vector` is immutable and
    // `VArr` is not, so `v(i) = x` is accepted here and rejected by Scala. At Tier 0 types are
    // erased and `asInstanceOf` is already the identity, so this is the tier's existing bargain
    // rather than a new one — but it IS a difference, and a type checker will have to take it back.
    case Expr.Call("Array" | "Vector", argEs, p)
        if !classes.exists(c => c.name == "Array" || c.name == "Vector") =>
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
  // EXPR-WALKER
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
          case Stmt.LocalDef(d)      => Stmt.LocalDef(d.copy(body = go(d.body)))
        }, res.map(go), p)
      case other => other

  /** Bottom-up rewrite over an expression tree. `selfCalls` deliberately does NOT use this: it has
    * to shrink its name set inside a lambda whose parameter shadows a method, and a plain walker
    * carries no scope. Everything scope-INDEPENDENT goes through here rather than growing a third
    * copy of the same twenty cases. */

  /** By-name parameters, as a FRONT transformation.
    *
    * `def f(x: => A)` is implemented the way Scala implements it, and the way that costs the IR, the
    * executor and the bridge nothing: the CALL SITE wraps the argument in a zero-argument lambda,
    * and each USE of the parameter in the body calls it. v3 already had both halves —
    * `val f = () => 41; f()` has always worked — so this is a rewrite, not a new capability.
    *
    * WHAT IT COVERS AND WHAT IT CANNOT. A call site is rewritten only when the callee is a
    * statically known `def`, because that is all the front has here. A call THROUGH A VALUE cannot
    * be: knowing whether that value's parameter is by-name needs a type, and Tier 0 has none. Scala
    * decides it with types; v3 decides it by name, and where it cannot decide it leaves the call
    * eager. A real limit, written down rather than assumed away.
    */
  /** `summon[Monoid[A]]` becomes the ONE `given` that declares `Monoid`, or a refusal naming what
    * it found. SSC3-G2 stage 1 (v3/SPRINT.md §52).
    *
    * WHY THIS IS SOUND AT TIER 0, and where it stops. The parser carries the HEAD of the summoned
    * type — `Monoid`, never `Monoid[Int]` — because the argument is what erasure removes. When a
    * module declares exactly one instance of that trait there is nothing to choose between, so no
    * type is needed to choose it: the answer is the same whatever `A` turns out to be. The moment
    * there are two, the choice IS the type, and this refuses instead of picking. That is the whole
    * of the difference between a stage and a shortcut, and `tagless-resolution` — `Show[Int]` and
    * `Show[String]` — is the case that would otherwise print a plausible wrong answer.
    *
    * It runs over the MERGED module, which is why it is here and not in the parser:
    * `tagless-multi-file` declares its instance in one file and summons it in another.
    *
    * What stage 2 (the checker proper) adds is exactly the ambiguous case — and when it lands,
    * this function is where the single-instance fast path can stay, since a checker will agree
    * with it whenever it applies. */
  /** The head of a declared type: `Show` for `Show[Int]`, `Show` for `Show`. */
  private def typeHead(t: String): String = t.takeWhile(c => c != '[').trim

  private def resolveSummons(defs: List[Def], objects: List[ObjectDef]): List[Def] =
    val instances: List[(String, String)] =           // (declared type, instance name)
      objects.flatMap(o => o.givenOf.map(t => (t, o.name)))
    // AN IN-SCOPE `using` PARAMETER WINS OVER A GLOBAL INSTANCE, and that is what makes a generic
    // function work at all. Inside `def combineAndPretty[A: Pretty]`, `summon[Pretty[A]]` must be
    // the parameter the call site filled in — the instance that matches THIS call's `A` — and not
    // one of the module's `Pretty` instances picked by counting. A global is consulted only when
    // no parameter offers the trait, which is the non-generic case G1 already handled.
    def fixIn(d: Def): Def =
      val fromParams: Map[String, String] =
        d.params.filter(_.given_).flatMap(pm => pm.tpe.map(t => (typeHead(t), pm.name))).toMap
      def fix(e: Expr): Expr = mapDeep(e, x => x match
        case Expr.Call("__summon__", List(Expr.StrLit(head, _)), p) =>
          fromParams.get(head) match
            case Some(pn) => Expr.Name(pn, p)
            case None =>
              instances.filter((t, _) => typeHead(t) == head).map((_, n) => n) match
                case one :: Nil => Expr.Name(one, p)
                case Nil =>
                  throw LowerFail(p, "`summon[" + head + "[…]]` finds no `given` declaring `" +
                    head + "` in this module or anything it imports, and no `using` parameter of " +
                    "this function offers one")
                case many =>
                  throw LowerFail(p, "`summon[" + head + "[…]]` has " + many.length +
                    " instances to choose from — " + many.sorted.mkString(", ") +
                    " — and this function has no `using` parameter of that trait to say which. " +
                    "Add one — `def f[A: " + head + "]` — or name the instance you mean")
        case other => other)
      d.copy(body = fix(d.body))
    defs.map(fixIn)

  /** A call to a function with `using` parameters gets them FILLED IN, by matching each one's
    * declared type against the instances in scope — SSC3-G2 stage 2a (v3/SPRINT.md §52).
    *
    * `def display[A](a: A)(using s: Show[A])` called as `display(42)`: the explicit argument `42`
    * is an `Int`, the parameter it fills is declared `A`, so `A` is `Int`; the using parameter's
    * `Show[A]` becomes `Show[Int]`; and exactly one `given` declares that type. The call becomes
    * `display(42, showInt)`.
    *
    * WHAT IT INFERS IS DELIBERATELY SMALL: a literal's type, and nothing else. That covers the
    * shape typeclass code actually takes at a call site — a value flows in and its type picks the
    * instance — and it means the answer is never a guess. When it cannot infer, or when the
    * substituted type matches no instance or several, the call is LEFT ALONE: the arity check then
    * refuses it by name, with a position. Filling in "the only instance of that trait" instead
    * would be the spelling-based shortcut §52 rejected, and `Show[Int]` beside `Show[String]` is
    * exactly where it would answer wrongly.
    *
    * An EXPLICIT `(using showInt)` at the call site needs nothing from this pass: the parser
    * flattens it into an ordinary argument, so the arity already matches and the call is skipped. */
  private def resolveGivenArgs(defs: List[Def], objects: List[ObjectDef]): List[Def] =
    val instances: List[(String, String)] = objects.flatMap(o => o.givenOf.map(t => (t, o.name)))
    val byName: Map[String, Def] = defs.map(d => (d.name, d)).toMap
    var extra: List[Def] = Nil
    var made: Set[String] = Set.empty

    /** A type's head and its arguments: `List[Int]` is `("List", ["Int"])`, `Int` is `("Int", [])`.
      * Splitting on the TOP-LEVEL commas only, so `Map[String,List[Int]]` gives two. */
    def split(t: String): (String, List[String]) =
      val i = t.indexOf('[')
      if i < 0 || !t.endsWith("]") then (t, Nil)
      else
        val inner = t.substring(i + 1, t.length - 1)
        var depth = 0; var cur = new StringBuilder; var out: List[String] = Nil
        inner.foreach { c =>
          if c == '[' then { depth += 1; cur += c }
          else if c == ']' then { depth -= 1; cur += c }
          else if c == ',' && depth == 0 then { out = cur.toString :: out; cur = new StringBuilder }
          else cur += c
        }
        (t.substring(0, i), (cur.toString :: out).reverse.filter(_.nonEmpty))

    /** Solve a parameter's declared type against an argument's actual one: `List[A]` against
      * `List[Int]` binds `A` to `Int`. Structural and total — no subtyping, no variance, nothing
      * that Tier 0 could not check by comparing two strings.
      *
      * Returns None when they cannot match, and the CALLER treats that as "do not specialise",
      * never as "pick something": a call this cannot solve is left for the arity check to refuse
      * by name. */
    def unify(pat: String, con: String, tvars: List[String]): Option[Map[String, String]] =
      if tvars.contains(pat) then Some(Map(pat -> con))
      else if pat == con then Some(Map.empty)
      else
        val (ph, pa) = split(pat); val (ch, ca) = split(con)
        if ph != ch || pa.length != ca.length || pa.isEmpty then None
        else pa.zip(ca).foldLeft(Option(Map.empty[String, String])) { (acc, pc) =>
          acc.flatMap(m => unify(pc._1, pc._2, tvars).map(m ++ _))
        }

    /** What an argument's type IS, as far as this pass can tell.
      *
      * A literal; a `List(…)`/`Vector(…)` of literals, which is what a call site actually writes;
      * or a NAME whose type the enclosing specialisation already fixed — that last one is what
      * carries `A = Int` from `combineAndPretty(List(10, 20, 30))` into the `combineAll(xs)` its
      * body makes, and without it a generic function calling another generic function can never be
      * resolved. Anything else is None, and None means "leave the call alone". */
    def argType(e: Expr, env: Map[String, String]): Option[String] = e match
      case Expr.IntLit(_, _)  => Some("Int")
      case Expr.StrLit(_, _)  => Some("String")
      case Expr.BoolLit(_, _) => Some("Boolean")
      case Expr.Name(n, _)    => env.get(n)
      case Expr.Call(ctor, as, _) if (ctor == "List" || ctor == "Vector") && as.nonEmpty =>
        val ts = as.map(a => argType(a, env))
        if ts.forall(_.isDefined) && ts.map(_.get).distinct.length == 1 then
          Some(ctor + "[" + ts.head.get + "]")
        else None
      case _ => None

    def substitute(t: String, binds: Map[String, String]): String =
      binds.foldLeft(t) { (acc, kv) =>
        // Whole-word replacement: `A` inside `Show[A]` but not the `A` of `Array`.
        val (v, c) = kv
        acc.split("(?=[\\[\\],])|(?<=[\\[\\],])").map(seg => if seg == v then c else seg).mkString
      }

    val instanceNames: Set[String] = instances.map((_, n) => n).toSet

    /** `val m = intMonoid` — an instance BOUND TO A NAME, replaced by the instance everywhere and
      * the binding dropped.
      *
      * An `object` is a namespace with no runtime value, so the `val` cannot stand: after the
      * specialisation renames `summon[Monoid[A]]` to `intMonoid`, `describeMonoid`'s
      * `val m = summon[Monoid[A]]` left a bare object name and the program failed with
      * `unknown name 'intMonoid'` — a refusal where the code is correct Scala. Aliasing it away is
      * what makes `m.combine(a, m.empty)` the qualified call that works. */
    def dropInstanceAliases(e: Expr): Expr = mapDeep(e, x => x match
      case Expr.Block(sts, res, bp) =>
        val aliases = sts.collect {
          case Stmt.Val(n, Expr.Name(inst, _), _, _) if instanceNames.contains(inst) => (n, inst)
        }.toMap
        if aliases.isEmpty then x
        else
          val kept = sts.filter {
            case Stmt.Val(n, Expr.Name(inst, _), _, _) => !(aliases.get(n).contains(inst))
            case _                                     => true
          }
          def sub(y: Expr): Expr = mapDeep(y, z => z match
            case Expr.Name(n, np) if aliases.contains(n) => Expr.Name(aliases(n), np)
            case other                                   => other)
          Expr.Block(kept.map {
            case Stmt.Exp(v)             => Stmt.Exp(sub(v))
            case Stmt.Val(n, v, iv, vp)  => Stmt.Val(n, sub(v), iv, vp)
            case other                   => other
          }, res.map(sub), bp)
      case other => other)

    // `f(a)(using inst)` arrives as an `Apply` over a `Call`, because the pass that flattens
    // curried argument lists runs per-def much later than this whole-program one. Normalising it
    // here — only when the trailing list is instance names — keeps that ordering untouched.
    def flatten(e: Expr): Expr = e match
      case Expr.Apply(Expr.Call(fn, as1, cp), as2, _)
        if as2.nonEmpty && as2.forall { case Expr.Name(n, _) => instanceNames.contains(n)
                                        case _ => false } =>
        Expr.Call(fn, as1 ++ as2, cp)
      case other => other

    // FLATTENED IN A PASS OF ITS OWN, before any specialisation. `mapDeep` rebuilds children first,
    // so doing it inline let the inner `display(99)` be specialised from the literal BEFORE the
    // outer `(using showInt)` was ever looked at — leaving the instance dangling as an argument to
    // a function that no longer took one.
    def flattenAll(e: Expr): Expr = mapDeep(e, flatten)

    /** Build the specialised copy, once per name, with the bindings its call site solved.
      *
      * `childEnv` is what makes a generic body resolvable: each explicit parameter's declared type
      * with the call's bindings substituted in, so inside the copy `xs` is known to be `List[Int]`
      * and the `combineAll(xs)` it contains can be solved in turn. */
    def specialise(d: Def, nm: String, names: List[String], binds: Map[String, String]): Unit =
      if !made.contains(nm) then
        made = made + nm
        val explicit = d.params.filterNot(_.given_)
        val subst: Map[String, String] = d.params.filter(_.given_).map(_.name).zip(names).toMap
        def rename(e: Expr): Expr = mapDeep(e, y => y match
          case Expr.Name(n, np) if subst.contains(n) => Expr.Name(subst(n), np)
          case other                                 => other)
        val childEnv = explicit.flatMap(pm => pm.tpe.map(t => (pm.name, substitute(t, binds)))).toMap
        extra = d.copy(name = nm, params = explicit,
                       body = dropInstanceAliases(rename(fix(d.body, childEnv)))) :: extra

    /** Specialise for instances NAMED AT THE CALL SITE, wherever they sit in the argument list.
      *
      * `greet("Alice", consoleOption, optionMonad)` passes two instances as ORDINARY arguments —
      * no `using`, no `summon`, and `tagless-program` is written that way throughout. At Tier 0 an
      * instance is an `object`, a namespace with no runtime value, so the call failed with
      * `unknown name 'consoleOption'`; the same specialisation answers it, with the parameters at
      * those POSITIONS renamed rather than the trailing `using` ones.
      *
      * This subsumes the explicit `(using inst)` case, which is the same thing with the instances
      * at the end, so there is one code path rather than two that must agree.
      *
      * WHAT MADE THIS SMALLER THAN PLANNED: §54 filed it as "higher-kinded — selects on a type
      * CONSTRUCTOR, `Monad[Option]` beside `Monad[List]`", expecting kind-aware matching. Reading
      * the file first showed there is nothing to infer: the program NAMES both instances at every
      * call. Kinds would be needed to work them out, and nobody asked for them to be worked out. */
    def specialiseAt(d: Def, nm: String, picks: List[(Int, String)]): Unit =
      if !made.contains(nm) then
        made = made + nm
        val subst: Map[String, String] =
          picks.map((i, inst) => (d.params(i).name, inst)).toMap
        val kept = d.params.zipWithIndex.filterNot((_, i) => picks.exists((j, _) => j == i))
                           .map((pm, _) => pm)
        def rename(e: Expr): Expr = mapDeep(e, y => y match
          case Expr.Name(n, np) if subst.contains(n) => Expr.Name(subst(n), np)
          case other                                 => other)
        extra = d.copy(name = nm, params = kept,
                       body = dropInstanceAliases(rename(fix(d.body, Map.empty)))) :: extra

    def fix(e: Expr, env: Map[String, String]): Expr = mapDeep(e, x =>
      x match
      // AN EXPLICIT `(using showInt)` — the program names the instance itself. Same specialisation,
      // and it needs one for the same reason: the argument it wrote is an `object`, which has no
      // value to pass. Handled before the inferring case so that writing the instance out always
      // beats working it out, which is what `display(99)(using showInt)` is asking for.
      case Expr.Call(fn, as, p)
        if byName.get(fn).exists(d => as.length == d.params.length) &&
           as.exists { case Expr.Name(n, _) => instanceNames.contains(n); case _ => false } =>
        val d = byName(fn)
        val picks = as.zipWithIndex.collect {
          case (Expr.Name(n, _), i) if instanceNames.contains(n) => (i, n)
        }
        val nm = fn + "$" + picks.map((_, n) => n).mkString("$")
        specialiseAt(d, nm, picks)
        Expr.Call(nm, as.zipWithIndex.filterNot((_, i) => picks.exists((j, _) => j == i))
                       .map((a2, _) => a2), p)
      case Expr.Call(fn, as, p) =>
        byName.get(fn) match
          case Some(d) if d.params.exists(_.given_) && as.length == d.params.count(!_.given_) =>
            val explicit = d.params.filterNot(_.given_)
            val binds = explicit.zip(as).foldLeft(Option(Map.empty[String, String])) { (acc, pa) =>
              acc.flatMap { m =>
                val (pm, a) = pa
                (pm.tpe, argType(a, env)) match
                  case (Some(pt), Some(at)) => unify(pt, at, d.tparams).map(m ++ _).orElse(Some(m))
                  case _                    => Some(m)
              }
            }.getOrElse(Map.empty)
            val gs = d.params.filter(_.given_)
            val chosen = gs.map { g =>
              g.tpe.map(t => substitute(t, binds)).flatMap { concrete =>
                instances.filter((it, _) => it == concrete).map((_, n) => n) match
                  case one :: Nil => Some(one)
                  case _          => None
              }
            }
            if !chosen.forall(_.isDefined) then x
            else
              // MONOMORPHISATION, not a dictionary. An instance is an `object` — a NAMESPACE at
              // Tier 0, with no runtime value — so `display(42, showInt)` cannot work: there is
              // nothing named `showInt` to pass. What CAN be done is specialise the callee, so
              // `s.show(a)` becomes `showInt.show(a)` — the qualified call that already works.
              //
              // One copy per (function, instances) pair, so two call sites with the same instances
              // share it and a program with `Show[Int]` and `Show[String]` gets one of each.
              val names = chosen.flatten
              val nm = fn + "$" + names.mkString("$")
              specialise(d, nm, names, binds)
              Expr.Call(nm, as, p)
          case _ => x
      case other => other)
    // TOP-LEVEL VALUES SEED THE ENVIRONMENT, and leaving them out was a regression.
    //
    // Stage 2b turned a context bound into a parameter, and a call whose argument is a top-level
    // `val` could no longer be solved: `val xs: List[Int] = List(1, …, 10)` then
    // `combineAll(xs)` inside `workload()` saw an EMPTY env, inferred nothing, left the call
    // unspecialised, and the arity check refused it. `bench/corpus/typeclass-fold.ssc` computed
    // VInt(16500) before that stage and stopped after it.
    //
    // It was invisible to `corpus-report.sh`, which reads `tests/conformance` — the bench corpus
    // is a SEPARATE set, and this row lives only there. The type comes from the initialiser
    // rather than the annotation because `Stmt.Val` does not carry one.
    val topEnv: Map[String, String] =
      defs.filter(_.name == entryName).flatMap(_.body match
        // A top-level `val` is a GLOBAL by the time this runs, and its initialiser arrives as an
        // ASSIGNMENT in the entry function — not as a `Stmt.Val`, which is what the first version
        // matched and why the environment came out empty. Both shapes are read, since a `val`
        // inside the entry's own block is still a `Stmt.Val`.
        case Expr.Block(sts, _, _) => sts.collect {
          case Stmt.Val(n, v, _, _)             => argType(v, Map.empty).map(t => (n, t))
          case Stmt.Exp(Expr.Assign(n, v, _))   => argType(v, Map.empty).map(t => (n, t))
        }.flatten
        case _ => Nil).toMap

    val fixed = defs.map(d => d.copy(body = fix(flattenAll(d.body), topEnv)))
    // THE GENERIC DEFINITION IS A TEMPLATE, and it is dropped once nothing calls it.
    //
    // Monomorphisation leaves the original behind: `combineAll[A: Monoid](xs: List[A])` still has
    // its `using` parameter and a body full of calls that only a specialisation can resolve. Kept,
    // it is lowered and ARITY-CHECKED like any other function, so `combineAndPretty`'s
    // `combineAll(xs)` — which is resolvable only with the enclosing `A` — refused the whole
    // program at `31:28` while the copies that actually run were correct.
    //
    // Dropped only when no call to it SURVIVES. A call this pass could not solve is still there,
    // and keeping the template for it means the reader gets `passes 1 argument(s), it takes 2` —
    // which names the problem — instead of `unknown function`, which names the fix I made.
    val all = fixed ++ extra
    val templates = fixed.filter(_.params.exists(_.given_)).map(_.name).toSet
    if templates.isEmpty then all
    else
      var called = Set.empty[String]
      all.foreach(d => mapDeep(d.body, x => { x match
        case Expr.Call(fn, _, _) if templates.contains(fn) => called = called + fn
        case _ => ()
      ; x }))
      all.filterNot(d => templates.contains(d.name) && !called.contains(d.name))

  /** Every `Expr.MethodRef` becomes either an ordinary no-argument `MethodCall` or a LAMBDA that
    * calls the method with the arguments it declares. Nothing downstream sees a `MethodRef`.
    *
    * `xs.foldLeft(z)(intSum.combine)` is the case: `combine` is not being called there, it is being
    * PASSED, and v3 used to lower it to a call with no arguments — so the verifier reported
    * `intSum.combine passes 0 arguments, it takes 2` and refused a correct program
    * (`v3-method-as-a-value`).
    *
    * ETA-EXPANSION IS RESTRICTED TO A RECEIVER THAT NAMES ITS TARGET, and the restriction is the
    * honest part. `Obj.m` resolves to the single definition `Obj.m`, so its arity is known here.
    * A method on a VALUE — `x.m` where `x` is some list at run time — does not: which `m` runs is
    * decided by the receiver's tag, and two classes may declare `m` with different arities. Those
    * keep lowering exactly as before, so this pass can only turn a program that was REFUSED into
    * one that runs, never change one that already ran.
    *
    * The distinction it depends on — `obj.m` versus `obj.m()` — exists only because the parser and
    * the projection now keep it (`Ast.MethodRef`). Measured before building any of this: both
    * spellings used to print `(send (name "M") "add")`, so a rule based on "no arguments" alone
    * would have turned the genuine arity error `M.add()` into a lambda, and a program that should
    * be refused would have started printing something. */
  private def resolveMethodRefs(defs: List[Def], sigs: List[(String, List[Param])]): List[Def] =
    val arity: Map[String, List[Param]] = sigs.toMap
    def fix(e: Expr): Expr = mapDeep(e, x => x match
      case Expr.MethodRef(recv, m, p) =>
        val target = recv match
          case Expr.Name(o, _) => arity.get(o + "." + m)
          case _               => None
        target match
          case Some(ps) if ps.nonEmpty =>
            val names = ps.indices.map(i => "__eta" + i).toList
            Expr.Lambda(names.map(n => Param(n, p)),
                        Expr.MethodCall(recv, m, names.map(n => Expr.Name(n, p)), p), p)
          case _ => Expr.MethodCall(recv, m, Nil, p)
      case other => other)
    defs.map(d => d.copy(body = fix(d.body)))


  // ── SSC3 extensions: `v.m(args)` becomes `m(v, args)`, under three conditions ─────────────────
  //
  // Design and its justification: `specs/ssc3-extensions.md`. §51 of `v3/SPRINT.md` records the
  // version of this that was REVERTED — `N 188 -> 130, CRASH 0 -> 131`, because an extension named
  // `map` rewrote every `.map(…)` in the program, lists included — and its conclusion, that which
  // method a receiver has is a fact about its runtime value rather than about syntax.
  //
  // That conclusion rules out choosing BETWEEN candidates. It does not rule out observing that there
  // are NONE BUT ONE: if a name is not a built-in method and no class in the merged program declares
  // it, no receiver value can answer to it, and the top-level def of that name is the only thing the
  // call can mean. The rewrite is then not a guess about a type — it is the absence of alternatives.
  //
  // Every condition can only PREVENT a rewrite, so this cannot make a call that works today stop
  // working. It can decline one that would have been fine, and that shows up as a program still
  // refused rather than as a wrong answer.
  //
  // BUILTIN-VOCABULARY-BEGIN
  // Kept in sync with `Exec.invoke` by `v3/extension-gate.sh`, which DERIVES the list from that file
  // and requires this one to cover it. A hand-written copy of another file's table goes stale in the
  // direction nobody notices, and here that direction is exact: a built-in `Exec` gains and this
  // list lacks would be SHADOWED by an extension of the same name. The gate turns red on the commit
  // that adds the built-in instead.
  private def builtinMethods: List[String] = List(
    "++", "+:", ":+", "A", "Array", "Bool", "Boolean", "Char", "Cons", "Double", "Error",
    "Exception", "Float", "Int", "Iterable", "Left", "List", "Long", "Map", "Nil", "None", "Option",
    "Right", "Set", "Some", "String", "Unit", "Vector", "abs", "append", "apply", "asInstanceOf",
    "charAt", "collect", "contains", "containsKey", "copy", "count", "distinct", "drop", "dropRight",
    "dropWhile", "endsWith", "exists", "filter", "filterNot", "find", "flatMap", "flatten", "fold",
    "foldLeft", "foldRight", "forall", "foreach", "from", "get", "getOrElse", "groupBy", "head",
    "headOption", "indexOf", "init", "isDefined", "isEmpty", "isInstanceOf", "isRight", "iterator",
    "keys", "last", "lastIndexOf", "lastOption", "length", "map", "max", "maxBy", "min", "minBy",
    "mkString", "nonEmpty", "padTo", "partition", "product", "put", "reduce", "reduceLeft",
    "remove", "removed", "replace", "reverse", "size", "slice", "sortBy", "sortWith", "sorted",
    "span", "split", "startsWith", "strip", "stripMargin", "sum", "tail", "take", "takeRight",
    "takeWhile", "toDouble", "toChar", "toInt", "toList", "toLong", "toLowerCase", "toMap", "toSeq",
    "toSet", "toString", "toUpperCase", "toVector", "trim", "updated", "values", "withFilter",
    "zip", "zipWithIndex",
    // Added by `v3/extension-gate.sh` on its first run against a hand-written list — it found 28
    // names this had missed, which is the whole reason the list is derived and not trusted. Some are
    // methods (`substring`, `matches`, `union`, `subsetOf`, `scanLeft`), some are type names, some
    // are prims, and a few (`ab`, `alice`, `carol`, `cd`, `x`) are strings from examples inside
    // `Exec` that the extractor sweeps up. All of them stay: an extra name can only PREVENT a
    // rewrite, and pruning by hand is how the list would start drifting again.
    "RuntimeException", "Seq", "Throwable", "Tuple", "__arith__", "__autoOutput__", "__isTag__",
    "__lazyFrom__", "__throw__", "ab", "alice", "any", "carol", "cd", "char", "diff", "intersect",
    "isLeft", "matches", "scanLeft", "subsetOf", "substring", "throw", "to", "toOption", "union",
    "until", "x",
    // `blen`/`bget` are PRIMS rather than methods — the byte loop `Lower.bytesToList` synthesises
    // calls them — but the gate derives this list from every name `Exec` answers to, and it is
    // right not to split hairs: the list must be a SUPERSET, and an extension named `bget` shadowing
    // a name the runtime knows would be a wrong answer either way.
    "bget", "blen")
  // BUILTIN-VOCABULARY-END

  private def rewriteExtensionCalls(defs: List[Def], classes: List[ClassDef]): List[Def] =
    val topLevel = defs.map(_.name).toSet
    val classMembers = classes.flatMap(c => c.methods.map(_.name)).toSet ++ classes.flatMap(_.fields.map(_.name)).toSet
    val builtins = builtinMethods.toSet
    def eligible(m: String): Boolean =
      topLevel.contains(m) && !classMembers.contains(m) && !builtins.contains(m)
    if !defs.exists(d => eligible(d.name)) then defs
    else
      def fix(e: Expr): Expr = mapDeep(e, x => x match
        case Expr.MethodCall(recv, m, args, p) if eligible(m) => Expr.Call(m, recv :: args, p)
        // AN INFIX USE ARRIVES AS `Bin`, not as a `MethodCall`. `3 ~ 4` parses as a binary operator
        // — that is what `prec` is for — so an extension named `~` is reached here and nowhere
        // else. Missing this arm left the def parsing, the use parsing, and the LOWERING refusing
        // with `operator '~' is outside…`, which reads like a parser gap and is not one.
        //
        // The same three conditions, so a built-in operator can never be captured: `+` and `<` are
        // not top-level def names, so `eligible` is false for them by construction rather than by a
        // list this arm would have to keep.
        case Expr.Bin(op, l, r, p) if eligible(op) => Expr.Call(op, List(l, r), p)
        case other => other)
      defs.map(d => d.copy(body = fix(d.body)))

  /** `xs.map(f)` on a `given … with` INSTANCE — the call-site half of a typeclass extension.
    *
    * WHY THE PARSER HALF ALONE WAS A REGRESSION. An `extension` inside a `trait` or a `given`
    * parses into `Def`s with the receiver lifted first, which makes them look like ordinary
    * members — and ordinary members are dispatched by the receiver's runtime TAG. A list's tag is
    * `Cons`, `Cons` has no `fmap`, and six corpus cases went from an honest refusal to
    * `Cons.fmap was called but does not exist`. Parsing a construct without lowering it is worse
    * than refusing it, which is why that state was measured and reverted rather than landed.
    *
    * HOW THE INSTANCE IS CHOSEN — the owner's decision, `v3/specs/20-core-language.md` §4a1, and
    * an APPROXIMATION taken deliberately with its edges written down there:
    *
    *   1. the receiver's CONSTRUCTOR. A tag names exactly one type head — `Cons`/`Nil` belong only
    *      to `List`, `Some`/`None` only to `Option` — so this is a function, not a guess, and the
    *      rewrite still happens at LOWERING time rather than at run time.
    *   2. otherwise a parameter's DECLARED type, which the front keeps as text in `Param.tpe`.
    *
    * `Stmt.Val` records no type at all, so a receiver bound by a `val` is resolved by its
    * constructor or not at all. That is the debt, not an oversight.
    *
    * WHEN SEVERAL INSTANCES PROVIDE THE METHOD, THE SUBTRAIT WINS. Measured before the rule was
    * chosen: 10 of 48 (type, method) pairs in `std` are declared by two traits — `List.foldLeft` by
    * both `Foldable` and `Traversable`, `List.pure` by both `Monad` and `Selective` — and in every
    * one of them the pair is a trait and a trait that EXTENDS it. Unrelated traits are left alone,
    * so the call falls through to the existing refusal with a position rather than picking one. */
  private def rewriteGivenExtensionCalls(defs: List[Def], objects: List[ObjectDef],
                                         traits: List[TraitDef], classes: List[ClassDef]): List[Def] =
    /** `Monad[List]` -> ("Monad", "List"). The whole declared type is kept by the parser, not just
      * its head — the stale comment above `givenOf`'s assignment says otherwise and the code below
      * it corrects itself two lines later. */
    def headAndArg(t: String): Option[(String, String)] =
      val i = t.indexOf('[')
      if i < 0 || !t.endsWith("]") then None
      else
        val arg = t.substring(i + 1, t.length - 1).trim
        val j = arg.indexOf('[')
        Some((t.substring(0, i).trim, (if j < 0 then arg else arg.substring(0, j)).trim))

    // (type head, method) -> (instance object name, trait head)
    var table: Map[(String, String), List[(String, String)]] = Map.empty
    objects.foreach { o =>
      o.givenOf.flatMap(headAndArg).foreach { (traitHead, typeArg) =>
        o.defs.foreach { d =>
          val k = (typeArg, d.name)
          table = table.updated(k, (o.name, traitHead) :: table.getOrElse(k, Nil))
        }
      }
    }
    if table.isEmpty then defs
    else
      /** Does `sub` extend `sup`, directly or through another trait? The rule is "the more specific
        * one wins", and specificity here IS the extends graph. */
      def extendsFrom(sub: String, sup: String): Boolean =
        var seen = Set.empty[String]
        var todo = List(sub)
        var found = false
        while todo.nonEmpty && !found do
          val h = todo.head; todo = todo.tail
          if !seen.contains(h) then
            seen = seen + h
            val ps = traits.find(_.name == h).map(_.parents).getOrElse(Nil)
            if ps.contains(sup) then found = true else todo = todo ++ ps
        found

      // A CONSTRUCTOR NAMES ITS TYPE. The built-in five are the only ones where the two differ; a
      // `case class C` is its own type, which is why the map is this short rather than a census.
      val ctorType: Map[String, String] =
        Map("Cons" -> "List", "Nil" -> "List", "Some" -> "Option", "None" -> "Option",
            "Right" -> "Either", "Left" -> "Either")
      /** `(Int, String)` -> `Tuple2`. A TUPLE TYPE HAS A HEAD and nothing here knew it.
        *
        * `headAndArg` splits on `[`, which is right for `List[Int]` and blind to a tuple: a
        * parenthesised type has no bracket, so its "head" came out as the whole text
        * `(Int,String)` and matched no instance. `Bifunctor[Tuple2]` was therefore unreachable
        * however the receiver's type was known — measured on all three shapes before this was
        * written, and it is what killed the obvious explanation. `std-bifunctor` refuses
        * `t.bimap(…)` on a `val t: (Int, String)`, which reads exactly like §4a1's "`Stmt.Val`
        * records NO declared type"; but a PARAMETER declared `(Int, String)` was refused too, and
        * so was the literal `(10, "ok")`, while `Either[String, Int]` through the same parameter
        * path answered. The missing val type is a real debt and was not this bug.
        *
        * Arity by TOP-LEVEL commas, so `(Int, (A, B))` is `Tuple2` and not `Tuple3`. Depth counts
        * brackets as well as parens because `(Map[K, V], Int)` is two elements, and counting only
        * parens would read the type argument's comma as a separator. */
      def tupleHead(t: String): Option[String] =
        val s = t.trim
        if !(s.startsWith("(") && s.endsWith(")")) then None
        else
          var depth = 0
          var n = 1
          var i = 1
          var ok = true
          while i < s.length - 1 do
            val c = s.charAt(i)
            if c == '(' || c == '[' then depth += 1
            else if c == ')' || c == ']' then { depth -= 1; if depth < 0 then ok = false }
            else if c == ',' && depth == 0 then n += 1
            i += 1
          // `(Int)` is a parenthesised type, not a 1-tuple, and `()` is Unit — neither has a
          // `TupleN` instance to find, so neither gets a head.
          if ok && depth == 0 && n >= 2 then Some("Tuple" + n) else None

      def typeOfCtor(n: String): Option[String] =
        // A TUPLE CONSTRUCTOR NAMES ITS OWN TYPE, which the five-entry map above could not say.
        // A tuple literal lowers to `Expr.Call("Tuple2", …)` — checked with `ssc3 ast`, not
        // assumed — and `Tuple2` is neither a built-in constructor nor a declared class, so the
        // constructor source produced nothing for the one receiver shape a Bifunctor is for.
        if n.startsWith("Tuple") && n.drop(5).forall(_.isDigit) && n.length > 5 then Some(n)
        else ctorType.get(n).orElse(classes.find(_.name == n).map(_.name))

      def receiverType(e: Expr, params: Map[String, String]): Option[String] = e match
        case Expr.Call(fn, _, _) if fn == "List" || fn == "Seq" => Some("List")
        case Expr.Call(fn, _, _) => typeOfCtor(fn)
        case Expr.Name(n, _)     => typeOfCtor(n).orElse(params.get(n))
        case _                   => None

      def pick(ty: String, m: String): Option[String] =
        table.get((ty, m)) match
          case None | Some(Nil) => None
          case Some(List((obj, _))) => Some(obj)
          case Some(cands) =>
            // The subtrait wins: keep the candidate no OTHER candidate is a supertrait of.
            cands.filter((_, t) => !cands.exists((_, u) => u != t && extendsFrom(u, t))) match
              case List((obj, _)) => Some(obj)
              case _              => None   // unrelated traits — left for the existing refusal

      // AN INSTANCE'S OWN BODY IS NOT REWRITTEN, and this is the arm that stops an infinite loop.
      //
      // `given listMonad: Monad[List] with … def map(fa) = fa.map(f)` means the CONCRETE list's
      // `map` in its own body — that is the only reading under which it terminates, and it is what
      // Scala resolves too, by preferring the receiver's own member over an extension. Rewriting it
      // turned the method into a call to itself: `std-functor-applicative-monad` compiled and then
      // SPUN, seventeen minutes before I looked, on a corpus run that had been six minutes the day
      // before.
      //
      // MY PROBE MISSED IT BY ONE CHARACTER. I named the method `fmap`, so `fa.map` inside it
      // resolved to the built-in and everything passed. `std` names it `map`, and that is the whole
      // difference between a green probe and a hang. The corpus caught what three probes could not.
      // Names NOTHING but a `given` instance provides. A class method or a built-in of the same
      // name means the call has another owner and must be left alone.
      val classMembers = classes.flatMap(c => c.methods.map(_.name)).toSet
      val instanceOnly: Set[String] =
        table.keySet.map((_, m) => m) -- classMembers -- builtinMethods.toSet -- defs.map(_.name).toSet
      val instanceDefs: Set[String] =
        objects.filter(_.givenOf.nonEmpty).flatMap(o => o.defs.map(d => o.name + "." + d.name)).toSet
      defs.map { d =>
        if instanceDefs.contains(d.name) then d
        else
          // `tupleHead` FIRST, because `headAndArg` would hand back the whole `(Int,String)` as a
          // head and that is the shape this fix exists for.
          val params = d.params.flatMap(q => q.tpe.map(t =>
            (q.name, tupleHead(t).orElse(headAndArg(t).map(_._1)).getOrElse(t)))).toMap
          val hasGiven = d.params.exists(_.given_) || d.givenParams.nonEmpty
          d.copy(body = mapDeep(d.body, x => x match
            case Expr.MethodCall(recv, m, args, p) =>
              receiverType(recv, params).flatMap(ty => pick(ty, m)) match
                case Some(obj) => Expr.Call(obj + "." + m, recv :: args, p)
                // UNRESOLVED IS REFUSED, not left to fall through, and that is the difference
                // between an honest refusal and a wrong answer. A name declared ONLY by `given`
                // instances is not a method of anything: leaving it alone sends it to dispatch by
                // the receiver's runtime tag, which cannot possibly find it, and the program dies
                // with `Tuple2.bimap was called but does not exist` — a message about a tag, from
                // inside v2, with no position. Measured: three corpus cases went from an honest
                // refusal to exactly that. Refusing HERE says which name, where, and why.
                //
                // Only when nothing else could own the name: a class method or a built-in of the
                // same name is a different thing entirely and is left to the paths that handle it.
                // NOT INSIDE A DEF THAT CARRIES A `given` PARAMETER. There the receiver's type
                // comes from a context bound and `resolveGivenArgs`/`resolveSummons` resolve it
                // further down the pipeline — refusing here pre-empts a mechanism that works.
                // Measured: the first version refused nine cases, six of them the `tagless-*`
                // family (`combine`, `empty`, `pure`, `eqv`), which had been fine.
                case None if instanceOnly.contains(m) && !hasGiven =>
                  throw LowerFail(p, "'" + m + "' is provided by a `given` instance, and the type " +
                    "of the receiver is not known here — it is a `val` with no declared type, or " +
                    "the instances that provide it belong to unrelated traits")
                case None => x
            case other => other))
      }

  private def rewriteByName(defs: List[Def]): List[Def] =
    val byNameParams: Map[String, Set[Int]] =
      defs.map(d => (d.name, d.params.zipWithIndex.filter((pm, _) => pm.byName).map(_._2).toSet))
          .filter((_, ixs) => ixs.nonEmpty).toMap
    if byNameParams.isEmpty then defs
    else
      def thunkArgs(e: Expr): Expr = mapDeep(e, x => x match
        case Expr.Call(fn, as, cp) if byNameParams.contains(fn) =>
          val ixs = byNameParams(fn)
          Expr.Call(fn, as.zipWithIndex.map { (a, i) =>
            // ALREADY A THUNK — do not wrap it twice. `f { 21 }` where `f` takes `x: => Int`
            // arrives from the UniML front as `f((() => 21))`, because that front makes a block
            // argument a zero-argument lambda; wrapping again gave `x()` a LAMBDA instead of 21 and
            // `x + x` reported `Add on function <closure 2> and function <closure 2>`. Broken on
            // the DEFAULT front only, which is why neither differential caught it: front-diff saw a
            // text difference it could not interpret, and the capability gate saw both fronts
            // accept.
            //
            // Safe at Tier 0 because passing an actual `() => A` to a by-name parameter is a type
            // error in Scala, so there is no program this guard silently changes.
            a match
              case Expr.Lambda(Nil, _, _)       => a
              case _ if ixs.contains(i)         => Expr.Lambda(Nil, a, Expr.posOf(a))
              case _                            => a
          }, cp)
        case other => other)
      // Each USE of a by-name parameter becomes a call of the thunk the call site now passes.
      //
      // Shadowing is respected by REFUSING it. A nested binder reusing the name refers to its own
      // binding, so rewriting inside it would read a value from the wrong place — and that is a
      // wrong answer, not a missing feature. Tracking scopes here would be the better fix and is
      // more than this needs; the refusal names the def and says what to do.
      def forceUses(d: Def): Def =
        val names = d.params.filter(_.byName).map(_.name).toSet
        if names.isEmpty then d
        else
          mapDeep(d.body, x => {
            x match
              case Expr.Lambda(ps, _, lp) if ps.exists(pm => names.contains(pm.name)) =>
                throw LowerFail(lp,
                  "a by-name parameter of '" + d.name + "' is shadowed by a lambda parameter of " +
                  "the same name; rename one — the front rewrites uses by name and cannot tell " +
                  "them apart")
              case _ => ()
            x
          })
          d.copy(body = mapDeep(d.body, x => x match
            case Expr.Name(n, np) if names.contains(n) => Expr.Apply(Expr.Name(n, np), Nil, np)
            case other                                 => other))
      defs.map(forceUses).map(d => d.copy(body = thunkArgs(d.body)))

  // Deliberately carries no walker marker: this is an alias, not a walk. The traversal is
  // `Expr.mapDeep`, on the AST, since `Loader` needs the same one and must not depend on this file,
  // and `v3/walker-gate.sh` reads it THERE — the marker went with the code. This alias exists only
  // so the twenty-odd call sites below stay unchanged. (Marking it here would register a walker
  // that handles nothing, which is what the gate reported the moment the code moved.)
  private def mapDeep(e: Expr, f: Expr => Expr): Expr = Expr.mapDeep(e, f)

  /** Substitute DEFAULT arguments at the call site. Scala's semantics: the default is an
    * expression evaluated where the call is, not a value computed once at the declaration — which
    * is why the parser keeps it unevaluated and this pastes it in.
    *
    * Trailing only. A call that omits a MIDDLE argument needs the parameter's name, which is what
    * named arguments are for; without them, filling from the left is the only unambiguous reading
    * and a short call to a function whose gap is not at the end stays an honest arity error. */
  /** Locals that a LAMBDA ASSIGNS TO from the outside — the ones that must be boxed.
    *
    * v3's lambda lifting passes captures as leading PARAMETERS, so a captured `var` arrives as a
    * copy and an assignment inside the lambda mutates the copy. Measured 2026-08-08:
    * `List(1,2,3).foreach { i => n = n + i }` left `n` at 0 where the reference answers 6, on BOTH
    * v3 lanes — so neither the parity gate nor the front differential could see it, and only the
    * reference caught it (`BUGS.md`, `v3-loses-a-mutation-to-a-captured-var`).
    *
    * Only names that are BOTH assigned inside a lambda AND free there. Boxing every capture would
    * cost every closure an indirection for a mutation that never happens; boxing by "is a `var`"
    * would box loop counters that no lambda ever sees. The binding rules mirror `freeVars` exactly,
    * because a name shadowed by an inner binder is a DIFFERENT name and boxing the outer one on its
    * account would be a wrong answer of its own. */
  // EXPR-WALKER
  private def assignedFree(e: Expr, bound: List[String]): List[String] = e match
    case Expr.Assign(n, v, _)     => (if bound.contains(n) then Nil else List(n)) ++ assignedFree(v, bound)
    case Expr.Name(_, _)          => Nil
    case Expr.Bin(_, l, r, _)     => assignedFree(l, bound) ++ assignedFree(r, bound)
    case Expr.Neg(x, _)           => assignedFree(x, bound)
    case Expr.Not(x, _)           => assignedFree(x, bound)
    case Expr.Update(a, i, v, _)  => assignedFree(a, bound) ++ assignedFree(i, bound) ++ assignedFree(v, bound)
    case Expr.Apply(f, as, _)     => assignedFree(f, bound) ++ as.flatMap(a => assignedFree(a, bound))
    case Expr.Prim(_, as, _)      => as.flatMap(a => assignedFree(a, bound))
    case Expr.If(c, t, el, _)     => assignedFree(c, bound) ++ assignedFree(t, bound) ++ el.toList.flatMap(x => assignedFree(x, bound))
    case Expr.While(c, b, _)      => assignedFree(c, bound) ++ assignedFree(b, bound)
    case Expr.Call(_, as, _)      => as.flatMap(a => assignedFree(a, bound))
    case Expr.MethodCall(r, _, as, _) => assignedFree(r, bound) ++ as.flatMap(a => assignedFree(a, bound))
    case Expr.NamedArg(_, v, _)   => assignedFree(v, bound)
    case Expr.Interp(_, xs, _)    => xs.flatMap(x => assignedFree(x, bound))
    case Expr.Try(b, x, h, _)     => assignedFree(b, bound) ++ assignedFree(h, x :: bound)
    case Expr.Lambda(ps, b, _)    => assignedFree(b, bound ++ ps.map(_.name))
    case Expr.Match(sc, arms, _) =>
      assignedFree(sc, bound) ++ arms.flatMap { a =>
        val inner = bound ++ patNames(a.pat)
        a.guard.map(g => assignedFree(g, inner)).getOrElse(Nil) ++ assignedFree(a.body, inner)
      }
    case Expr.Block(stmts, res, _) =>
      var b = bound
      var acc: List[String] = Nil
      stmts.foreach { st =>
        st match
          case Stmt.Val(n, v, _, _) => acc = acc ++ assignedFree(v, b); b = n :: b
          case Stmt.Exp(x)          => acc = acc ++ assignedFree(x, b)
          case Stmt.LocalDef(d) =>
            b = d.name :: b
            acc = acc ++ assignedFree(d.body, b ++ d.params.map(_.name))
      }
      acc ++ res.toList.flatMap(r => assignedFree(r, b))
    case _ => Nil

  /** Names DECLARED by a `val`/`var` anywhere in this expression. */
  private def declaredLocals(e: Expr): List[String] =
    var out: List[String] = Nil
    mapDeep(e, x => {
      x match
        case Expr.Block(sts, _, _) =>
          sts.foreach { st => st match { case Stmt.Val(n, _, _, _) => out = n :: out; case _ => () } }
        case _ => ()
      x
    })
    out.distinct

  /** Every name some lambda in this expression assigns to from the outside AND that is declared as
    * a local here.
    *
    * THE SECOND HALF IS NOT A REFINEMENT, it is the difference between working and broken. A
    * top-level `var` is a module GLOBAL in v3 — already a cell, already mutable by reference, and
    * already correct through a closure. Boxing one rewrote its reads and writes while its
    * DECLARATION stayed a global assignment, so the box was never created and `v3/tests/front/
    * arrays.ssc` died with `array write on ()`. Caught by the front gate on the first run after
    * the boxing landed. */
  private def boxedNames(e: Expr): List[String] =
    var out: List[String] = Nil
    mapDeep(e, x => {
      x match
        case Expr.Lambda(ps, b, _) => out = out ++ assignedFree(b, ps.map(_.name))
        case _ => ()
      x
    })
    val local = declaredLocals(e)
    out.distinct.filter(n => local.contains(n))

  /** A boxed local becomes a ONE-ELEMENT ARRAY, and its reads and writes become element access.
    *
    * `Array(v)`, `n(0)` and `n(0) = v` are all shapes both lanes already have — `NewArr`, an
    * application, `ArrSet` — so this needs no new instruction, no new prim, and nothing added to
    * the vocabulary the bridge shares with v2. The alternative was a `cell` prim family, which
    * would have been a second way to say the same thing. */
  // EXPR-WALKER
  private def boxLocals(e: Expr, boxed: List[String]): Expr =
    if boxed.isEmpty then e
    else
      def zero(p: Pos): Expr = Expr.IntLit(0L, p)
      def go(x: Expr): Expr = x match
        // The TARGET's name is not a read — rewriting it would index the box to find the box.
        case Expr.Assign(n, v, p) if boxed.contains(n) =>
          Expr.Update(Expr.Name(n, p), zero(p), go(v), p)
        case Expr.Name(n, p) if boxed.contains(n) => Expr.Apply(Expr.Name(n, p), List(zero(p)), p)
        case Expr.Bin(o, l, r, p)         => Expr.Bin(o, go(l), go(r), p)
        case Expr.Neg(y, p)               => Expr.Neg(go(y), p)
        case Expr.Not(y, p)               => Expr.Not(go(y), p)
        case Expr.Assign(n, v, p)         => Expr.Assign(n, go(v), p)
        case Expr.Update(a, i, v, p)      => Expr.Update(go(a), go(i), go(v), p)
        case Expr.Apply(f, as, p)         => Expr.Apply(go(f), as.map(go), p)
        case Expr.Prim(n, as, p)          => Expr.Prim(n, as.map(go), p)
        case Expr.If(c, t, el, p)         => Expr.If(go(c), go(t), el.map(go), p)
        case Expr.While(c, b, p)          => Expr.While(go(c), go(b), p)
        case Expr.Call(f, as, p)          => Expr.Call(f, as.map(go), p)
        case Expr.MethodCall(r, n, as, p) => Expr.MethodCall(go(r), n, as.map(go), p)
        case Expr.MethodRef(r, n, p)      => Expr.MethodRef(go(r), n, p)
        case Expr.NamedArg(n, v, p)       => Expr.NamedArg(n, go(v), p)
        case Expr.Interp(parts, xs, p)    => Expr.Interp(parts, xs.map(go), p)
        case Expr.Try(b, n, h, p)         => Expr.Try(go(b), n, go(h), p)
        case Expr.Lambda(ps, b, p)        => Expr.Lambda(ps, go(b), p)
        case Expr.Match(sc, arms, p)      =>
          Expr.Match(go(sc), arms.map(a => MatchArm(a.pat, a.guard.map(go), go(a.body))), p)
        case Expr.Block(sts, res, p) =>
          Expr.Block(sts.map { st => st match
            // The DECLARATION creates the box. It becomes immutable — the box is what changes.
            case Stmt.Val(n, v, _, q) if boxed.contains(n) =>
              Stmt.Val(n, Expr.Call("Array", List(go(v)), q), false, q)
            case Stmt.Val(n, v, mu, q) => Stmt.Val(n, go(v), mu, q)
            case Stmt.Exp(x2)          => Stmt.Exp(go(x2))
            case Stmt.LocalDef(d)      => Stmt.LocalDef(d.copy(body = go(d.body)))
          }, res.map(go), p)
        case other => other
      go(e)

  /** `xs.map(_ * 2)` — the PLACEHOLDER LAMBDA.
    *
    * The rule is the reference front's, verbatim (`ssc1-front.ssc0:984`, `wrapPhArg`): an ARGUMENT
    * of a call that CONTAINS a `_` but is not a bare `_` becomes a lambda over that argument. A
    * bare `f(_)` is left alone — it is eta-expansion, a different thing, and rarer.
    *
    * EACH `_` IS A DISTINCT PARAMETER, left to right, so `_ + _` is `(a, b) => a + b` with arity 2
    * rather than one parameter used twice. The reference records that as a fix (K62.29) and names
    * what it broke: `foldLeft`/`reduce` failed with "arity: 1 expected, 2 given". Copying the rule
    * rather than re-deriving it is what keeps the bridge lane and the frozen goldens in agreement.
    *
    * THE SCOPE IS ONE ARGUMENT, and the descent below says exactly which shapes are searched:
    * binary, prefix, call, and the receiver of a method call. Not a block, not an `if`, not a
    * lambda body — a `_` there belongs to something else, and the reference does not look either.
    * Written as ONE pass over the AST rather than in each front: two fronts implementing the same
    * desugaring is two implementations that will disagree, which is the failure this project keeps
    * arranging its apparatus to catch.
    */
  private def hasPh(e: Expr): Boolean = e match
    case Expr.Name("_", _)            => true
    case Expr.Bin(_, l, r, _)         => hasPh(l) || hasPh(r)
    case Expr.Neg(x, _)               => hasPh(x)
    case Expr.Not(x, _)               => hasPh(x)
    case Expr.Call(_, as, _)          => as.exists(hasPh)
    case Expr.MethodCall(r, _, as, _) => hasPh(r) || as.exists(hasPh)
    case Expr.Apply(f, as, _)         => hasPh(f) || as.exists(hasPh)
    case _                            => false

  /** Replace each `_` with `__u<i>`, left to right, returning the next free index. */
  private def replacePh(e: Expr, i0: Int): (Expr, Int) = e match
    case Expr.Name("_", p) => (Expr.Name("__u" + i0, p), i0 + 1)
    case Expr.Bin(o, l, r, p) =>
      val (l1, i1) = replacePh(l, i0)
      val (r1, i2) = replacePh(r, i1)
      (Expr.Bin(o, l1, r1, p), i2)
    case Expr.Neg(x, p) => val (x1, i1) = replacePh(x, i0); (Expr.Neg(x1, p), i1)
    case Expr.Not(x, p) => val (x1, i1) = replacePh(x, i0); (Expr.Not(x1, p), i1)
    case Expr.Call(fn, as, p) =>
      val (as1, i1) = replacePhAll(as, i0)
      (Expr.Call(fn, as1, p), i1)
    case Expr.MethodCall(r, n, as, p) =>
      val (r1, i1) = replacePh(r, i0)
      val (as1, i2) = replacePhAll(as, i1)
      (Expr.MethodCall(r1, n, as1, p), i2)
    case Expr.Apply(f, as, p) =>
      val (f1, i1) = replacePh(f, i0)
      val (as1, i2) = replacePhAll(as, i1)
      (Expr.Apply(f1, as1, p), i2)
    case other => (other, i0)

  private def replacePhAll(es: List[Expr], i0: Int): (List[Expr], Int) =
    var i = i0
    var out: List[Expr] = Nil
    es.foreach { e => val (e1, i1) = replacePh(e, i); out = out :+ e1; i = i1 }
    (out, i)

  private def wrapPhArg(e: Expr): Expr = e match
    // A bare `_` is eta-expansion, not a placeholder lambda. Left exactly as the reference leaves it.
    case Expr.Name("_", _) => e
    case _ if hasPh(e) =>
      val p = Expr.posOf(e)
      val (body, n) = replacePh(e, 0)
      Expr.Lambda((0 until n).toList.map(k => Param("__u" + k, p)), body, p)
    case _ => e

  private def expandPlaceholders(e: Expr): Expr =
    mapDeep(e, x => x match
      case Expr.Call(fn, as, p)         => Expr.Call(fn, as.map(wrapPhArg), p)
      case Expr.MethodCall(r, n, as, p) => Expr.MethodCall(r, n, as.map(wrapPhArg), p)
      case Expr.Apply(f, as, p)         => Expr.Apply(f, as.map(wrapPhArg), p)
      case other                        => other)

  /** `ap(3)(f)` where `def ap(n: Int)(f: Int => Int)` — a CURRIED APPLICATION of a plain function.
    *
    * Multiple parameter clauses flatten into one list at the definition, so the call has to flatten
    * too. Left alone it lowered to a one-argument `Call` whose result was applied to the second
    * list, and the verifier refused the module: "call to ap passes 1 argument(s), it takes 2". Two
    * corpus cases came out that way — and only once UniML became the front, because v3's own parser
    * cannot read a second parameter clause at all (`expected ')', found :`).
    *
    * IT RUNS BEFORE `fillDefaults` and it is GUARDED ON THE ARITY, which is the whole content of
    * it. `mk()(3)`, where `mk` returns a closure, must stay an application OF that closure —
    * flattening it would call `mk` with an argument it does not take. So the two lists are joined
    * only when together they match the callee's declared arity exactly. A returned closure cannot
    * fake that: `mk` has arity 0 and `0 != 0 + 1`.
    *
    * Named arguments are excluded because `resolveArgs` reorders against the parameter list, and
    * which clause a name belongs to is information this flattening has just destroyed. */
  private def flattenCurried(e: Expr, sigs: List[(String, List[Param])]): Expr =
    mapDeep(e, x => x match
      case Expr.Apply(Expr.Call(fn, as1, cp), as2, _)
        if !as1.exists(_.isInstanceOf[Expr.NamedArg]) && !as2.exists(_.isInstanceOf[Expr.NamedArg]) &&
           sigs.exists((n, ps) => n == fn && ps.length == as1.length + as2.length) =>
        Expr.Call(fn, as1 ++ as2, cp)
      // A curried METHOD — `file.lock(a)(b)` where `def lock(a: Int)(b: Int)`. The plain-call arm
      // above missed it entirely, and the verifier said so: "call to JvmSqliteFile.lock passes 1
      // argument(s), it takes 2". A method's flattened signature carries the RECEIVER as its first
      // parameter, so the arity to match is 1 + both argument lists.
      case Expr.Apply(Expr.MethodCall(recv, nm, as1, cp), as2, _)
        if !as1.exists(_.isInstanceOf[Expr.NamedArg]) && !as2.exists(_.isInstanceOf[Expr.NamedArg]) &&
           curriedOwner(recv, nm, sigs, as1.length + as2.length) =>
        Expr.MethodCall(recv, nm, as1 ++ as2, cp)
      case other => other)

  /** Does a method called `nm` ON THIS RECEIVER take exactly `want` parameters?
    *
    * THE LAST RECEIVER-BLIND RESOLUTION IN THIS FILE, and the mildest of the three, which is why it
    * outlived them: it checks the ARITY, so a wrong match needs a same-named method that also
    * happens to take the right number. The two already fixed — `e51b86956` for an object's members
    * and `8dda5011c` for a class's — both named this one as the remainder.
    *
    * What it decides is whether `f(a)(b)` is ONE call or a call whose RESULT is applied, and
    * getting that wrong is not a diagnostic: the two lower to different programs. So a same-named
    * method on a class the receiver has nothing to do with could flatten a genuine
    * apply-the-result, or leave a genuine curried call unflattened for the verifier to refuse.
    *
    * SAME RULE AS `fillDefaults`, deliberately, because it is the same question: when the receiver
    * is a NAME the answer is an exact `obj + "." + nm`; when it is an expression the method belongs
    * to a class chosen by the tag at run time, no name exists here, and the suffix search is used
    * only when it leaves ONE answer. Several candidates of the SAME arity agree about the only
    * thing this predicate returns, so they are not an ambiguity.
    *
    * `want` IS THE COUNT OF ARGUMENTS THE USER WROTE, and that spelling is a bug fix, not a taste.
    * The two kinds of callable count differently: a class method's flattened signature carries the
    * RECEIVER as its first parameter and an object's member does not. The first version of this
    * compared `1 + as1 + as2` against the raw list and broke `NS.at(3)(5)` on an `object NS` —
    * while the suffix search it replaced had been getting that case RIGHT BY ACCIDENT, matching
    * `Box.at`'s three parameters (`this`, a, b) for a call to an object that has two. Dropping a
    * leading `this` before comparing makes both kinds answer the same question. */
  private def curriedOwner(recv: Expr, nm: String, sigs: List[(String, List[Param])], want: Int): Boolean =
    def visible(ps: List[Param]): Int =
      if ps.nonEmpty && ps.head.name == "this" then ps.length - 1 else ps.length
    // THE ANONYMOUS CASE IS UNDECIDABLE HERE AND IS LEFT AS IT WAS. I first required every
    // candidate to AGREE on the arity, reasoning that an ambiguous flattening is worse than a
    // refusal. The probe refuted it immediately: `Box(1).at(2)(3)` on a `def at(a)(b)` stopped
    // flattening because an unrelated `Other.at(a)` exists, and a legitimate curried call became
    // `method 'at' on #8(1)' is not implemented`. Where a receiver is an EXPRESSION its class is
    // chosen by the tag at run time, so there is no information at this point to be strict WITH —
    // being strict only refuses programs that work. What would decide it is the receiver's type,
    // and Tier 0 has none (invariant I-2). So: any candidate of the right arity flattens, exactly
    // as before, and the fix above is confined to the case where a name IS available.
    def anon: Boolean =
      sigs.exists((n, ps) => n.endsWith("." + nm) && visible(ps) == want)
    recv match
      case Expr.Name(obj, _) =>
        sigs.find((n, _) => n == obj + "." + nm) match
          case Some((_, ps)) => visible(ps) == want
          // Not an object member: `recv` names a local, or a value whose class is chosen at run
          // time, so fall through to the receiver-less question.
          case None => anon
      case _ => anon

  /** An arity mismatch, caught HERE with a position rather than by the verifier without one.
    *
    * `extern def pathJoin(parts: String*)` is a VARARG host function; v3 has no varargs, so the
    * declaration says one parameter and every real call passes three or four. The call lowered
    * anyway and the verifier refused the whole module — `call to pathJoin passes 4 argument(s), it
    * takes 1` — which `corpus-report.sh` classifies as CRASH, rightly, because it names no place.
    *
    * Emitting a call the verifier will reject is a defect in this file whatever the cause: the
    * lowering knows both numbers AND the source position, and the verifier knows neither. Runs
    * after `fillDefaults`, since that is what makes a short argument list legitimate. */
  // Shared with `Loader.merge`, which asks the same question for the same reason — see
  // `Expr.boundNames`, where the direction both readers need is written out.
  private def boundNames(e: Expr): Set[String] = Expr.boundNames(e)

  /** THE NAMES BOUND INSIDE THIS DEF ARE NOT GLOBAL FUNCTIONS, and forgetting that REFUSED CORRECT
    * PROGRAMS. `sigs` is the whole program's table, so a call to a name that is really a PARAMETER
    * was checked against a top-level function of the same name:
    *
    *     def f(a: Int, b: Int) = a + b        // a top-level name
    *     def g(f: Any => Any)  = f(1)         // and also a parameter
    *     -> call to 'f' passes 1 argument(s), it takes 2
    *
    * Three lines, no prelude. Found BY the prelude — the first v3 code that lives beside an
    * arbitrary user program — when three front fixtures went red at once for defining `f`, and the
    * corpus carries the same message on three conformance cases independently.
    *
    * CONSERVATIVE ON PURPOSE: the bound set is collected over the WHOLE def instead of tracked
    * per-scope, so a global function shadowed anywhere inside a def goes unchecked everywhere
    * inside it. That direction is the safe one — it can MISS an error and cannot invent one, and
    * inventing one is exactly this defect. Per-scope tracking is P-1's follow-up in
    * `v3/PRELUDE-CORRECTNESS.md`. */
  private def checkArity(e: Expr, sigs: List[(String, List[Param])], params: Set[String]): Expr =
    val bound = params ++ boundNames(e)
    mapDeep(e, x => x match
      case Expr.Call(fn, as, p) if !bound.contains(fn) =>
        sigs.find((n, _) => n == fn) match
          // A ZERO-ARITY def APPLIED to arguments is legitimate and has its own lowering arm:
          // `def mkAdd = (a) => a + 1` then `mkAdd(3)` calls `mkAdd` with nothing and applies the
          // closure it returns. Refusing that broke `parenless-def-value`, which had been passing —
          // the check was right about the shape and wrong about this one case, and the corpus said
          // so in the same run that showed the crashes go to zero.
          case Some((_, ps)) if ps.isEmpty && as.nonEmpty => x
          case Some((_, ps)) if ps.length != as.length =>
            throw LowerFail(p, "call to '" + fn + "' passes " + as.length +
                               " argument(s), it takes " + ps.length)
          case _ => x
      // AN OBJECT'S METHOD, where the target is named and its arity is therefore known here.
      // `M.add()` — an argument list written and none supplied — is an arity error, and without
      // this arm it reached the verifier, which reports it with no position at all and so is
      // classified CRASH. That matters more than usual now: `M.add` WITHOUT the list is
      // eta-expansion and runs, so the two spellings differ, and the one that fails has to say
      // where.
      //
      // Keyed on `obj.nm` resolving in `sigs`, which happens only for an OBJECT — a namespace,
      // whose flattened `def`s carry no receiver parameter. A class method is `C.m` with the
      // receiver first and is not reachable through a name here, so this cannot misjudge it: the
      // lookup simply misses and the expression is left alone.
      case Expr.MethodCall(Expr.Name(obj, _), nm, as, p) if !bound.contains(obj) =>
        sigs.find((n, _) => n == obj + "." + nm) match
          case Some((_, ps)) if ps.isEmpty && as.nonEmpty => x
          case Some((_, ps)) if ps.length != as.length =>
            throw LowerFail(p, "call to '" + obj + "." + nm + "' passes " + as.length +
                               " argument(s), it takes " + ps.length)
          case _ => x
      case other => other)

  private def fillDefaults(e: Expr, sigs: List[(String, List[Param])]): Expr =
    mapDeep(e, x => x match
      // `copy` is resolved in the LOWERING, not here — it needs field reads and a constructor, and
      // which class it is depends on the receiver at run time.
      case Expr.MethodCall(_, "copy", _, _) => x
      case Expr.Call(fn, as, p) =>
        sigs.find((n, _) => n == fn) match
          case Some((_, ps)) => Expr.Call(fn, resolveArgs(as, ps, fn, p), p)
          case _             => x
      case Expr.MethodCall(r, nm, as, p) =>
        // THE RECEIVER IS CONSULTED FIRST, and not consulting it was a defect that declaration
        // ORDER decided:
        //
        //     object A: def of(a: Int, b: Int = 2, c: Int = 3) = …
        //     object B: def of(x: Int) = x * 10
        //     B.of(5)   ->   A first: "call to 'B.of' passes 3 argument(s), it takes 1"
        //                    B first: 50
        //
        // The suffix match below takes the FIRST signature in the whole program ending in `.of`, so
        // `A`'s two defaults were pasted into a call to `B` — and `checkArity`, which resolves the
        // receiver correctly by exact `obj + "." + nm`, then refused the call this pass handed it.
        // Two passes over one name, one of them ignoring the owner written right there.
        //
        // FOUND THROUGH THE PRELUDE and not caused by it: `object Dataset: def of(…)` carries
        // SIXTEEN defaulted parameters and the prelude is first in `sigs`, so it captured every
        // `.of` in every program — `companion-case-class-order` was refused with `passes 16
        // argument(s)`. That case passes today only because the prelude loads lazily now; the
        // defect was untouched and fires for any two objects. (`BUGS.md
        // an-objects-defaults-are-taken-from-another-objects-member-of-the-same-name`)
        //
        // NARROW ON PURPOSE. An exact `obj + "." + nm` is only available when the receiver is a
        // NAME — an object, a namespace. When it is an expression the method belongs to a class
        // chosen by the receiver's tag at run time, no name is available here, and the suffix match
        // is what there is; two classes with a same-named method at different arities are the same
        // defect one level down and need more than a lookup. Left as it was, and recorded, rather
        // than guessed at in the same change.
        val exact = r match
          case Expr.Name(obj, _) => sigs.find((n, _) => n == obj + "." + nm)
          case _                 => None
        // A method's first parameter is the receiver, added when it was flattened.
        def own(ps0: List[Param]): List[Param] =
          if ps0.nonEmpty && ps0.head.name == "this" then ps0.tail else ps0
        // WHEN NO NAME IS AVAILABLE, PICK BY WHAT THE CALL CAN ACTUALLY FILL.
        //
        // The receiver is an expression, so the method belongs to a class chosen by the tag at run
        // time and there is no name to look up. Taking the FIRST `.nm` in the program is what the
        // object case above was refused for, one level down: a second class with a same-named
        // method at a different arity supplies its defaults to this call, and `resolveArgs` can
        // even THROW `'nm' has no parameter named 'x'` about a parameter list that was never the
        // target's.
        //
        // So candidates are filtered by whether this call could be completed against them at all,
        // and the result is used ONLY when that leaves one answer. Ambiguity leaves the call
        // untouched — which is exactly what `resolveArgs` already does when it cannot fill, so an
        // unfilled call stays an honest arity error instead of a confident wrong one.
        def fits(ps: List[Param]): Boolean =
          val positional = as.takeWhile(a => !a.isInstanceOf[Expr.NamedArg])
          val named = as.drop(positional.length).collect { case Expr.NamedArg(n, _, _) => n }
          // A VARARG SIGNATURE HAS NO UPPER BOUND. `positional.length <= ps.length` is the right
          // test for every other shape and the wrong one here: `def of[T](items: T*)` takes one
          // parameter and `Dataset.of(1, 2, 3)` supplies three, so a vararg candidate was filtered
          // out and the call was left alone — which reads as "ambiguous" and is really "the only
          // candidate was rejected for the one thing that makes it match".
          (if isVararg(ps) then positional.length >= ps.length - 1
           else positional.length <= ps.length) &&
          named.forall(n => ps.exists(q => q.name == n)) &&
          ps.drop(positional.length).forall(q => q.default.isDefined || named.contains(q.name))
        val chosen = exact.map((_, ps0) => own(ps0)).orElse {
          val cands = sigs.filter((n, _) => n.endsWith("." + nm)).map((_, ps0) => own(ps0))
          val fitting = cands.filter(fits)
          if fitting.length == 1 then Some(fitting.head)
          // Several fit and they agree on ARITY: whatever is filled is filled to the same length by
          // any of them, so the choice cannot change the shape of the call. Different lengths mean
          // the answer depends on which class, and this pass does not know — leave it alone.
          else if fitting.length > 1 && fitting.map(_.length).distinct.length == 1 then Some(fitting.head)
          else None
        }
        chosen match
          case Some(ps) => Expr.MethodCall(r, nm, resolveArgs(as, ps, nm, p), p)
          case None     => x
      case other => other)

  /** Does this signature end in a VARARG parameter — `def of[T](items: T*)`?
    *
    * Read off the declared type's TEXT, which is where both fronts put the star: v3's own parser
    * reassembles it from the tokens `skipType` consumes, and the uniml dialect appends it in
    * `SpikeTyped.slots` from the `def.vararg` leaf. One spelling, so one test. */
  private def isVararg(ps: List[Param]): Boolean =
    ps.nonEmpty && ps.last.tpe.exists(_.endsWith("*"))

  /** The tail of a vararg call, COLLECTED INTO A LIST. `Dataset.of(1, 2, 3)` becomes
    * `Dataset.of(1 :: 2 :: 3 :: Nil)`.
    *
    * `T*` IS `List[T]` AT TIER 0 — that is read off the corpus, not chosen: `std/ui/data.ssc:43` is
    * `def tableRow(cells: TkNode*): List[TkNode] = cells.toList`, so the body already treats the
    * parameter as an ordinary list. Nothing about the callee changes; only the call site does.
    *
    * IT RUNS INSIDE `resolveArgs` and therefore BEFORE `checkArity`, and the order is the whole
    * reason this is here rather than in a pass of its own. The arity check is RIGHT to refuse
    * `passes 3, it takes 1` until the tail has been gathered, so gathering has to happen first —
    * and `resolveArgs` is the one place that already has both the arguments and the RESOLVED
    * signature, receiver and all. A separate pass would have had to redo the receiver lookup, which
    * is the duplicate-decision-site shape this file has paid for twice.
    *
    * EMPTY IS A LIST TOO: `tableRow()` gives `Nil`, not a missing argument.
    *
    * LEFT ALONE when the call is too short to have a tail at all, so a genuine arity mistake is
    * still reported as one; and when NAMED arguments are present, because a named argument for the
    * vararg parameter itself means something this tier has not decided and quietly wrapping it
    * would be a guess. */
  private def collectVarargs(as: List[Expr], ps: List[Param], p: Pos): List[Expr] =
    if !isVararg(ps) || as.exists(_.isInstanceOf[Expr.NamedArg]) || as.length < ps.length - 1 then as
    else
      val fixed = as.take(ps.length - 1)
      fixed :+ as.drop(ps.length - 1)
                 .foldRight(Expr.Name("Nil", p))((h, t) => Expr.Bin("::", h, t, p))

  /** Positional arguments, then NAMED ones placed by their parameter's name, then DEFAULTS for
    * whatever is still missing. Returns the arguments UNCHANGED when the call cannot be completed,
    * so an arity mistake is still reported as an arity mistake rather than being papered over with
    * a confusing substitution. */
  private def resolveArgs(as0: List[Expr], ps: List[Param], what: String, p: Pos): List[Expr] =
    val as = collectVarargs(as0, ps, p)
    val positional = as.takeWhile(a => !a.isInstanceOf[Expr.NamedArg])
    val rest = as.drop(positional.length)
    if rest.isEmpty && as.length >= ps.length then as
    else if rest.exists(a => !a.isInstanceOf[Expr.NamedArg]) then
      throw LowerFail(p, "a positional argument after a named one, in a call to '" + what + "'")
    else
      val named = rest.map { a => a match
        case Expr.NamedArg(n, v, _) => (n, v)
        case other                  => ("", other)
      }
      named.foreach { (n, _) =>
        if !ps.exists(q => q.name == n) then
          throw LowerFail(p, "'" + what + "' has no parameter named '" + n + "'")
      }
      var out: List[Expr] = positional
      var ok = true
      ps.drop(positional.length).foreach { q =>
        named.find((n, _) => n == q.name) match
          case Some((_, v)) => out = out :+ v
          case None =>
            q.default match
              case Some(d) => out = out :+ withEarlierParams(d, ps, out, what, p)
              case None    => ok = false
      }
      if ok then out else as

  /** A later parameter's default that references an EARLIER PARAMETER — `def shift(x: Int, by: Int
    * = x + 1)`, which is legal Scala and which the corpus uses. The default is pasted in AT THE CALL
    * SITE, where `x` does not exist, so it read `unknown name 'x'` while the reference front answers
    * 21. The argument already placed for `x` goes in its place.
    *
    * SUBSTITUTING AN EXPRESSION DUPLICATES ITS EVALUATION, and Scala does not: there the default is
    * a function receiving the preceding parameters, so `shift(next(), )` advances the counter once.
    * `resolveArgs` returns an argument LIST and has nowhere to bind a temporary, so the duplication
    * is only taken where it CANNOT BE OBSERVED — a name or a literal. Anything else is refused by
    * name rather than quietly evaluated twice.
    *
    * A default containing a binder is refused for the same reason: the rewrite is by name, a lambda
    * or block inside the default may bind the parameter's name, and this cannot tell the two apart.
    * Both refusals replace one error message with a clearer one — never a working call. */
  private def withEarlierParams(d: Expr, ps: List[Param], filled: List[Expr],
                                what: String, p: Pos): Expr =
    val earlier = ps.take(filled.length).map(_.name).zip(filled).toMap
    if earlier.isEmpty then d
    else
      var used: List[String] = Nil
      mapDeep(d, x => { x match
        case Expr.Name(n, _) if earlier.contains(n) => used = n :: used
        case _                                      => ()
        x })
      if used.isEmpty then d
      else
        val binder = hasBinder(d)
        used.distinct.foreach { n =>
          if binder then
            throw LowerFail(p, "the default for a parameter of '" + what + "' mentions the earlier " +
              "parameter '" + n + "' inside a lambda, block or match, where a binder of the same " +
              "name cannot be told apart from it — pass the argument explicitly")
          if !isDuplicable(earlier(n)) then
            throw LowerFail(p, "the default for a parameter of '" + what + "' mentions the earlier " +
              "parameter '" + n + "', whose argument here is not a name or a literal — substituting " +
              "it would evaluate that argument twice; pass the argument explicitly")
        }
        mapDeep(d, x => x match
          case Expr.Name(n, np) => earlier.get(n).map(a => reposition(a, np)).getOrElse(x)
          case other            => other)

  /** Can this expression be evaluated twice with the same effect and the same answer? Only a name
    * or a literal — deliberately not "has no call in it", because a name read is the only
    * non-literal this needs and widening it is how a duplicated side effect gets in. */
  private def isDuplicable(e: Expr): Boolean = e match
    case Expr.Name(_, _) | Expr.IntLit(_, _) | Expr.DoubleLit(_, _) | Expr.StrLit(_, _) |
         Expr.BoolLit(_, _) | Expr.CharLit(_, _) | Expr.UnitLit(_) => true
    case _ => false

  private def hasBinder(e: Expr): Boolean =
    var found = false
    mapDeep(e, x => { x match
      case Expr.Lambda(_, _, _) | Expr.Block(_, _, _) | Expr.Match(_, _, _) |
           Expr.Try(_, _, _, _) | Expr.Handle(_, _, _) => found = true
      case _ => ()
      x })
    found

  /** The substituted argument reports the DEFAULT's position, so a failure inside it points at the
    * call the reader is looking at rather than at the declaration three files away. */
  private def reposition(e: Expr, at: Pos): Expr = e match
    case Expr.Name(n, _)      => Expr.Name(n, at)
    case Expr.IntLit(v, _)    => Expr.IntLit(v, at)
    case Expr.DoubleLit(v, _) => Expr.DoubleLit(v, at)
    case Expr.StrLit(v, _)    => Expr.StrLit(v, at)
    case Expr.BoolLit(v, _)   => Expr.BoolLit(v, at)
    case Expr.CharLit(v, _)   => Expr.CharLit(v, at)
    case Expr.UnitLit(_)      => Expr.UnitLit(at)
    case other                => other

  /** Does every named argument of a `copy` name a field of this class? A class that does not have
    * all of them cannot be the receiver, so it contributes no arm. */
  /** One alternative's test, as a boolean in a register. Only shapes that bind NOTHING are
    * allowed — a wildcard, a literal, or a nullary constructor — because an alternative that bound
    * a name would need every other alternative to bind the same one, and deciding that needs the
    * analysis Tier 0 does not have. Refused by name rather than half-supported. */
  private def altTest(p0: Pat, vr: Int, classes: List[ClassDef], fns: List[String],
                      zeroArity: List[String], st0: St, ap: Pos): (List[Instr], Int, St) =
    p0 match
      case Pat.PWild(_) =>
        val (k, st1) = st0.constIdx(Lit.LBool(true))
        val (r, st2) = st1.fresh
        (List(Instr.Const(r, k)), r, st2)
      case Pat.PLit(v, _) =>
        val (vi, lr, st1) = lower(v, fns, classes, zeroArity, st0)
        val (cr, st2) = st1.fresh
        (vi :+ Instr.Bin(BinOp.Eq, NumKind.Dyn, cr, vr, lr), cr, st2)
      case Pat.PCtor(cname, Nil, cp) =>
        val arity = classes.find(c => c.name == cname).map(c => c.fields.length)
          .orElse(ctors.find((n, _) => n == cname).map((_, ar) => ar))
          .getOrElse(throw LowerFail(cp, "unknown constructor '" + cname + "' in a pattern"))
        if arity != 0 then
          throw LowerFail(cp, "'" + cname + "' takes " + arity +
            " field(s); an alternative pattern may not bind at Tier 0")
        val (t, st1) = st0.typeIdx(cname, 0)
        val (tagK, st2) = st1.constIdx(Lit.LInt(t.toLong))
        val (tagV, st3) = st2.fresh
        val (tagR, st4) = st3.fresh
        val (cr, st5) = st4.fresh
        (List(Instr.Tag(tagR, vr), Instr.Const(tagV, tagK),
              Instr.Bin(BinOp.Eq, NumKind.Dyn, cr, tagR, tagV)), cr, st5)
      case other =>
        throw LowerFail(Pat.posOf(other), "an alternative pattern may not bind a name at Tier 0")

  private def copyFits(c: ClassDef, args: List[Expr]): Boolean =
    args.forall { a => a match
      case Expr.NamedArg(n, _, _) => c.fields.exists(f => f.name == n)
      case _                      => false
    }

  /** Local `def`s become TOP-LEVEL functions, with whatever they capture as LEADING parameters.
    *
    * A local function may RECURSE, which is why it is not rewritten into a `val` holding a lambda —
    * a `val`-bound lambda has no name to call itself by. Lifting keeps the name, so the recursive
    * call is an ordinary call to the lifted function.
    *
    * Captures become parameters rather than a closure because that is the cheaper of the two and
    * the call sites are all visible: a local def is called from the body that declares it and from
    * itself, both of which this pass rewrites in the same motion.
    *
    * Iterated to a fixed point, so a local def inside a local def lifts too — the lifted function
    * goes back on the queue and its own locals are found on the next pass. */
  private def liftLocals(defs: List[Def], topNames: List[String]): List[Def] =
    var out: List[Def] = Nil
    var queue = defs
    var guard = 0
    while queue.nonEmpty && guard < 4096 do
      guard = guard + 1
      val d = queue.head
      queue = queue.tail
      val locals = collectLocals(d.body)
      if locals.isEmpty then out = out :+ d
      else
        var body = d.body
        var lifted: List[Def] = Nil
        locals.foreach { ld =>
          val bound = ld.params.map(_.name) ++ List(ld.name) ++ topNames
          val captured = freeVars(ld.body, bound).distinct.filter(n => !topNames.contains(n))
          val mangled = d.name + "$" + ld.name
          val ps = captured.map(c => Param(c, ld.pos)) ++ ld.params
          lifted = lifted :+ Def(mangled, ps, callsTo(ld.body, ld.name, mangled, captured), ld.pos)
          body = callsTo(body, ld.name, mangled, captured)
        }
        queue = (Def(d.name, d.params, dropLocals(body), d.pos) :: lifted) ++ queue
    if queue.nonEmpty then throw LowerFail(Pos.none, "local `def` lifting did not settle")
    out

  private def collectLocals(e: Expr): List[Def] =
    var found: List[Def] = Nil
    mapDeep(e, x =>
      x match
        case Expr.Block(sts, _, _) =>
          sts.foreach { st => st match
            case Stmt.LocalDef(d) => found = found :+ d
            case _                => ()
          }
          x
        case other => other)
    found

  private def dropLocals(e: Expr): Expr =
    mapDeep(e, x =>
      x match
        case Expr.Block(sts, res, p) =>
          Expr.Block(sts.filter { st => st match
            case Stmt.LocalDef(_) => false
            case _                => true
          }, res, p)
        case other => other)

  /** Point every call to a lifted local at its new name, passing the captures first. A BARE name
    * counts: a parameterless local def is referenced without parentheses. */
  private def callsTo(e: Expr, from: String, to: String, captured: List[String]): Expr =
    mapDeep(e, x =>
      x match
        case Expr.Call(fn, as, p) if fn == from =>
          Expr.Call(to, captured.map(c => Expr.Name(c, p)) ++ as, p)
        case Expr.Name(n, p) if n == from =>
          Expr.Call(to, captured.map(c => Expr.Name(c, p)), p)
        case other => other)

  /** Rewrite an object method's references to its OWN members into the dotted globals they are.
    * A parameter or local of the same name shadows, so the rewrite skips a lambda whose parameter
    * takes the name — the same scope care `selfCalls` needs, and the reason neither can be a plain
    * `mapDeep`. */
  // EXPR-WALKER
  private def qualifyMembers(e: Expr, obj: String, own: List[String]): Expr =
    def go(x: Expr): Expr = qualifyMembers(x, obj, own)
    e match
      case Expr.Name(n, p) if own.contains(n)     => Expr.Name(obj + "." + n, p)
      case Expr.Assign(n, v, p) if own.contains(n) => Expr.Assign(obj + "." + n, go(v), p)
      case Expr.Lambda(ps, b, p) =>
        Expr.Lambda(ps, qualifyMembers(b, obj, own.filter(n => !ps.exists(q => q.name == n))), p)
      case Expr.Call(fn, as, p)         => Expr.Call(fn, as.map(go), p)
      case Expr.MethodCall(r, n, as, p) => Expr.MethodCall(go(r), n, as.map(go), p)
      // THE THIRD HAND-WRITTEN WALKER over `Expr` in this file, and the third that had to learn
      // this node. Without it `def names() = entries.reverse` inside `object Registry` kept its
      // receiver unqualified — `entries` instead of `Registry.entries` — and the case failed with
      // `unknown name 'entries'`. It cost exactly one corpus row (188 → 187), which is what the
      // A/B against `origin/main` on the SAME front was run to find.
      case Expr.MethodRef(r, n, p)      => Expr.MethodRef(go(r), n, p)
      // A NAMED ARGUMENT'S VALUE. Found by `walker-gate.sh` on the day it was written, with a
      // two-line reproduction: `Box(v = secret, tag = "x")` inside `object Store` reported
      // `unknown name 'secret'`, because the object's own `val` was never qualified inside the
      // argument. The same hole `mapDeep` had, in a different walker — which is the entry's whole
      // point (BUGS.md lower-has-six-hand-written-Expr-walkers…).
      case Expr.NamedArg(n, v, p)       => Expr.NamedArg(n, go(v), p)
      case Expr.Apply(f, as, p)         => Expr.Apply(go(f), as.map(go), p)
      case Expr.Prim(n, as, p)          => Expr.Prim(n, as.map(go), p)
      case Expr.Perform(o, as, p)       => Expr.Perform(o, as.map(go), p)
      case Expr.Handle(b, arms, p)      => Expr.Handle(go(b), arms.map(a => a.copy(body = go(a.body))), p)
      case Expr.Resume(k, v, p)         => Expr.Resume(k, go(v), p)
      case Expr.Bin(o, l, r, p)         => Expr.Bin(o, go(l), go(r), p)
      case Expr.Neg(x, p)               => Expr.Neg(go(x), p)
      case Expr.Not(x, p)               => Expr.Not(go(x), p)
      case Expr.If(c, t, el, p)         => Expr.If(go(c), go(t), el.map(go), p)
      case Expr.While(c, b, p)          => Expr.While(go(c), go(b), p)
      case Expr.Assign(n, v, p)         => Expr.Assign(n, go(v), p)
      case Expr.Update(a, i, v, p)      => Expr.Update(go(a), go(i), go(v), p)
      case Expr.Try(b, x, h, p)         => Expr.Try(go(b), x, go(h), p)
      case Expr.Interp(parts, xs, p)    => Expr.Interp(parts, xs.map(go), p)
      case Expr.Match(sc, arms, p) =>
        Expr.Match(go(sc), arms.map(a => MatchArm(a.pat, a.guard.map(go), go(a.body))), p)
      case Expr.Block(sts, res, p) =>
        var live = own
        val out = sts.map { st => st match
          case Stmt.Val(n, v, mu, q) =>
            val r = Stmt.Val(n, qualifyMembers(v, obj, live), mu, q)
            live = live.filter(x => x != n)
            r
          case Stmt.Exp(x)      => Stmt.Exp(qualifyMembers(x, obj, live))
          case Stmt.LocalDef(d) => Stmt.LocalDef(d.copy(body = qualifyMembers(d.body, obj, live)))
        }
        Expr.Block(out, res.map(x => qualifyMembers(x, obj, live)), p)
      case other => other

  /** HOST FUNCTIONS BOTH LANES CAN DO — the extern's name to the prim that performs it.
    *
    * `extern def readFile(path: String): String` in `std/fs.ssc` declares a host function and says
    * nothing about how it is performed. Until now v3 answered every one of them the same way: a
    * `__throw__` naming the function, which the corpus counts 28 times. That refusal is honest and
    * stays for everything not listed here.
    *
    * WHY A TABLE, AND WHY THIS TABLE. The bridge lane runs on the v2 VM, which implements exactly
    * ten prims — `io.args env eprint exists exit nanoTime print println readFile writeFile` — and
    * the entries below are the ones `v3/src/Exec.scala` also implements. **That intersection is not
    * a compromise, it is the invariant**: I-3 says the two lanes agree about every program, so a
    * host function on one lane and not the other would be a program that runs here and dies there.
    * The table is the shape that makes the invariant unbreakable rather than merely tested — a name
    * is added only when BOTH sides have it.
    *
    * WHAT IS DELIBERATELY ABSENT, and the list is short because it was MEASURED rather than
    * assumed. My first version mapped `readFile` and `writeFile` too, on the strength of the names
    * matching. They do not match in SHAPE: `v2/src/Runtime.scala:1943` reads a file to **BytesV**
    * and writes from bytes, while `std/fs.ssc` declares `readFile(path): String`. The executor
    * accepted the program and the bridge died with `expected Bytes, got "hello from ScalaScript"` —
    * the two lanes disagreeing, which is precisely what this table exists to prevent, produced by
    * the table itself because I matched on a name instead of a signature.
    *
    * Expressing them is possible and is NOT guessed at here: v2 has `utf8->str` and `str->utf8`, so
    * the body would be `utf8->str(io.readFile(p))` — but the IR is SHARED, so `Exec` would then
    * have to model v2's `BytesV`, and v3 has no byte value at all. That is a real piece of work
    * with its own measurement, and it is where `readBytes`/`writeBytes` belong too, since those two
    * DO match v2's shape exactly. Written down rather than half-built.
    *
    * `deleteFile` — what `tests/conformance/dataset-shape` still needs — is absent from v2's VM
    * entirely. A v3-only `io.deleteFile` would break I-3 the same way. It keeps the honest refusal.
    *
    * The extern's own PARAMETERS become the prim's arguments, in order, so the signature the user
    * wrote is what is passed — no separate argument list to drift out of step with the declaration. */
  /** An `extern def` given the body this lane performs it with — or a positioned refusal.
    *
    * ONE function because there are TWO callers and there always were: a top-level `extern def`,
    * and an `extern def` that is a member of an `object`. The second went unresolved until `math`
    * needed it, so it kept the `__abstract__` placeholder and failed at run time with
    * `an implementation is missing (???)` — unpositioned, which `corpus-report.sh` classifies CRASH.
    * Two call sites and one body is the point; a second copy here is how the two would drift.
    *
    * The refusal CARRIES ITS POSITION, which is the whole reason the first attempt at host
    * functions was rejected: an unpositioned run-time failure is a CRASH and rightly so. */
  private def resolveExtern(d: Def): Def =
    hostPrims.get(d.name) match
      case Some(build) =>
        Def(d.name, d.params, build(d.params.map(q => Expr.Name(q.name, d.pos)))(d.pos),
            d.pos, d.tparams, d.givenParams)
      case None =>
        val at = d.pos.line.toString + ":" + d.pos.col.toString
        d.copy(body = Expr.Prim("__throw__",
          List(Expr.StrLit(at + ": the host function '" + d.name +
                           "' is not implemented on this lane", d.pos)), d.pos))

  private val hostPrims: Map[String, List[Expr] => Pos => Expr] = Map(
    "exists"    -> (as => p => Expr.Prim("io.exists", as, p)),
    // NOT a rename: v2 reads a file to BYTES and `std/fs.ssc` declares a String, so the body is a
    // COMPOSITION. The extern's body is an expression, which is what makes this possible without
    // either lane growing a special case — and `utf8->str`/`str->utf8` are v2's own spellings, so
    // the decoding is v2's UTF-8 on both lanes rather than a choice made twice.
    "readFile"  -> (as => p => Expr.Prim("utf8->str", List(Expr.Prim("io.readFile", as, p)), p)),
    "writeFile" -> (as => p => Expr.Prim("io.writeFile",
                                         List(as.head, Expr.Prim("str->utf8", as.tail, p)), p)),
    "readBytes"  -> (as => p => bytesToList(Expr.Prim("io.readFile", as, p), p)),
    "writeBytes" -> (as => p => Expr.Prim("io.writeFile",
                                          List(as.head, listToBytes(as.tail.head, p)), p)),
    // `math`. TOP-LEVEL NAMES, and the `__` prefix is not decoration: neither front carries
    // `extern` into an `object` (v3's parser refuses it, uniml drops the keyword and leaves the
    // member with a `???` body), so the prelude cannot declare `object math: extern def sqrt(…)`
    // and writes these four beside it, with `object math` delegating. The prefix keeps four host
    // hooks out of the way of a program that defines its own `sqrt` — the prelude loads for
    // EVERY program, so a bare `sqrt` here would shadow one.
    //
    // FOUR, AND NOT `pow`. The split is decided by what v2's VM already has, exactly as the IO
    // entries above were: `f.sqrt`, `f.floor`, `f.ceil` and `f.round` are in
    // `v2/src/Runtime.scala:1434-1437`, and `f.pow` is NOT. A v3-only prim would run on the
    // executor and be refused by the bridge, which is the divergence this table exists to prevent
    // — so `pow` is written in ScalaScript in the prelude, where one implementation serves both.
    "__mathPow"   -> (as => p => Expr.Prim("f.pow", as, p)),
    "__mathSqrt"  -> (as => p => Expr.Prim("f.sqrt", as, p)),
    "__mathFloor" -> (as => p => Expr.Prim("f.floor", as, p)),
    "__mathCeil"  -> (as => p => Expr.Prim("f.ceil", as, p)),
    "__mathRound" -> (as => p => Expr.Prim("f.round", as, p)))

  /** `Bytes -> List[Int]`, SYNTHESISED AS A LOOP rather than asked of a prim.
    *
    * v2 has no bytes-to-list prim, and adding one is a change to another subsystem. What it does
    * have is `blen` and `bget` — so the conversion is expressible in the IR both lanes already run,
    * and building it here costs no v2 change and no user-visible type. The `Bytes` value never
    * escapes: it is bound to a synthesised local, indexed, and dropped.
    *
    * Built BACKWARDS, from the last index down, so each step is a `::` onto the front and the
    * result comes out in order without a reverse. */
  private def bytesToList(bytes: Expr, p: Pos): Expr =
    val b = Expr.Name("__bytes", p)
    val i = Expr.Name("__i", p)
    val acc = Expr.Name("__acc", p)
    Expr.Block(List(
      Stmt.Val("__bytes", bytes, false, p),
      Stmt.Val("__i", Expr.Bin("-", Expr.Prim("blen", List(b), p), Expr.IntLit(1, p), p), true, p),
      Stmt.Val("__acc", Expr.Name("Nil", p), true, p),
      Stmt.Exp(Expr.While(Expr.Bin(">=", i, Expr.IntLit(0, p), p),
        Expr.Block(List(
          Stmt.Exp(Expr.Assign("__acc", Expr.Bin("::", Expr.Prim("bget", List(b, i), p), acc, p), p)),
          Stmt.Exp(Expr.Assign("__i", Expr.Bin("-", i, Expr.IntLit(1, p), p), p))), None, p), p))),
      Some(acc), p)

  /** `List[Int] -> Bytes`, THROUGH HEX, because that is the only constructor both lanes share.
    *
    * v2 builds bytes from a string (`str->utf8`), from hex (`hex->bytes`), from a file, or from
    * other bytes (`bslice`/`bconcat`) — and nothing else. `str->utf8` is not a route: it encodes,
    * so any value above 127 would come out as TWO bytes. Hex is exact for every byte, which is why
    * the round-trip fixture uses 200 — a UTF-8 shortcut passes on ASCII and fails there.
    *
    * The two digits are `/ 16` and `% 16` rather than shifts and masks, so this needs no bitwise
    * prim and reads the same on every lane. */
  private def listToBytes(list: Expr, p: Pos): Expr =
    val xs = Expr.Name("__xs", p)
    val hx = Expr.Name("__hex", p)
    val s = Expr.Name("__s", p)
    val head = Expr.MethodCall(xs, "head", Nil, p)
    def digit(e: Expr): Expr = Expr.MethodCall(hx, "charAt", List(e), p)
    Expr.Block(List(
      Stmt.Val("__hex", Expr.StrLit("0123456789abcdef", p), false, p),
      Stmt.Val("__xs", list, true, p),
      Stmt.Val("__s", Expr.StrLit("", p), true, p),
      Stmt.Exp(Expr.While(Expr.MethodCall(xs, "nonEmpty", Nil, p),
        Expr.Block(List(
          Stmt.Exp(Expr.Assign("__s", Expr.Bin("+", Expr.Bin("+", s,
            digit(Expr.Bin("%", Expr.Bin("/", head, Expr.IntLit(16, p), p), Expr.IntLit(16, p), p)), p),
            digit(Expr.Bin("%", head, Expr.IntLit(16, p), p)), p), p)),
          Stmt.Exp(Expr.Assign("__xs", Expr.MethodCall(xs, "tail", Nil, p), p))), None, p), p))),
      // `.get` — `hex->bytes` answers an OPTION on both lanes. Safe by construction here: every
      // digit is taken from a 16-character table, so the string is always well-formed hex.
      Some(Expr.MethodCall(Expr.Prim("hex->bytes", List(s), p), "get", Nil, p)), p)

  private def isAbstract(d: Def): Boolean = d.body match
    case Expr.Name("__abstract__", _) => true
    case _                            => false

  private def patNames(p: Pat): List[String] = Pat.names(p)

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
        // `case s: String =>` — a TYPE ASCRIPTION. One `__isTag__` call, then the inner pattern
        // (a binder or a wildcard) on the SAME register, because a type test narrows nothing at
        // run time: the value is the value, the test only decides whether this arm runs.
        //
        // `__isTag__(value, name, -1)` is the reference front's own shape (`ssc1-lower.ssc0:3559`)
        // and v2 implements it, so the BRIDGE lane gets this for nothing and the executor
        // implements the identical vocabulary rather than a parallel one. The `-1` is "any arity":
        // a type ascription carries no field patterns, while `case Cons(h, t)` still goes through
        // the constructor path with its real arity.
        case Pat.PType(tname, inner, tp) =>
          val (nameK, st1) = st0.constIdx(Lit.LStr(tname))
          val (anyArity, st2) = st1.constIdx(Lit.LInt(-1L))
          val (nameR, st3) = st2.fresh
          val (arityR, st4) = st3.fresh
          val (isR, st5) = st4.fresh
          val (pi, st6) = st5.primIdx("__isTag__")
          val (innerI, st7) =
            testPat((inner, vr) :: more, guard, body, dst, rest, fns, classes, zeroArity, env, st6)
          (List(Instr.Const(nameR, nameK), Instr.Const(arityR, anyArity),
                Instr.Prim(isR, pi, List(vr, nameR, arityR)),
                Instr.If(isR, innerI, rest)), st7)
        // `case A | B =>` — a DISJUNCTION of tests, evaluated into one boolean and then branched
        // on once. Alternatives bind nothing, so there is no environment to reconcile between
        // them; that restriction is Scala's, not a simplification made here, and an alternative
        // that would bind is refused by name rather than silently binding from whichever matched.
        case Pat.PAlt(alts, ap) =>
          // Nested `If`s that set ONE boolean, not a chain of `BOr`: `||` is lowered to `If`
          // everywhere else in this file because it short-circuits, and a bitwise or on two
          // booleans is not defined on either lane. Built back to front so each alternative's
          // else-branch is the next test — and so the BODY appears once, not once per alternative.
          val (res, stR) = st0.fresh
          val (fk, st1) = stR.constIdx(Lit.LBool(false))
          val (tk, st2) = st1.constIdx(Lit.LBool(true))
          var chain: List[Instr] = Nil
          var st = st2
          alts.reverse.foreach { alt =>
            val (ti, tr, stN) = altTest(alt, vr, classes, fns, zeroArity, st, ap)
            st = stN
            chain = ti :+ Instr.If(tr, List(Instr.Const(res, tk)), chain)
          }
          val (inner, stF) = testPat(more, guard, body, dst, rest,
                                     fns, classes, zeroArity, env, st)
          ((Instr.Const(res, fk) :: chain) :+ Instr.If(res, inner, rest), stF)

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
            case Stmt.Exp(x)          => Expr.posOf(x).line
            case Stmt.Val(_, _, _, q) => q.line
            case Stmt.LocalDef(d)     => d.pos.line
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
    if !p.hasCode then throw LowerFail(Pos.none, "empty program")
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
    // Object members initialise FIRST. A top-level statement may read one, and a namespace's
    // members are conceptually there before the script starts — which is also what v1 does.
    val objectInit: List[Stmt] = p.objects.flatMap(o =>
      o.vals.map(v => Stmt.Exp(Expr.Assign(o.name + "." + v.name, v.value, v.pos))))
    val entryBody =
      Expr.Block(objectInit ++ hoisted, userMain.map(_ => Expr.Call("main", Nil, Pos.none)), Pos.none)
    val entryDef = Def(entryName, Nil, entryBody, Pos.none)
    // Object members are flattened into `Object.member` top-level functions before anything else
    // looks at the name list, so a qualified call resolves by ordinary lookup.
    // An object's methods see its members UNQUALIFIED — `def bump(): Unit = n = n + 1` refers to the
    // object's own `n`. The members are stored as dotted globals, so the body is rewritten to name
    // them that way; without it the method reported `unknown name 'n'` while the global sat beside
    // it under another name.
    // AN OBJECT'S MEMBER IS NEVER AN `extern` HERE, and that is a FRONT limitation rather than a
    // decision this pass gets to make. Probed 2026-08-14 while wiring `math`: neither front carries
    // the keyword into an object. v3's own parser REFUSES (`only \`def\` members are supported in a
    // object at Tier 0, found extern`), and uniml silently gives the member the same `???` body it
    // gives ANY bodyless member — the keyword changes nothing there. So resolving externs over this
    // list would be code no front can reach, and the prelude declares its host functions at top
    // level instead. Filed as `v3-extern-member-in-an-object-has-no-meaning`.
    val objectDefs = p.objects.flatMap { o =>
      val own = o.vals.map(_.name)
      o.defs.map(d => d.copy(name = o.name + "." + d.name, body = qualifyMembers(d.body, o.name, own)))
    }
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
    // `extern def readFile(path: String): String` — a HOST function, declared with no body. v3
    // implements it or it does not exist; there is no third state. So a declaration it cannot back
    // is dropped rather than defined, and a call to it is refused AT THE CALL SITE by the ordinary
    // unknown-name path — with a position, which is what makes a refusal actionable and what
    // `corpus-report.sh` needs to count it as UNSUPPORTED rather than CRASH.
    //
    // DROPPING IT IS THE WHOLE POINT. The standard library declares host functions in blocks — 20
    // in `fs.ssc`, 15 in `os.ssc` — and refusing the DECLARATION made every program importing such
    // a module fail even when it called none of them. That was 144 corpus cases, the top blocker.
    // Two intermediate designs were measured and rejected: handing the extern's name straight to
    // the lane as a prim let v2's plugins answer on the bridge and nothing answer on the executor,
    // so the two v3 lanes disagreed on 11 cases and 5 programs RAN AND PRINTED THE WRONG THING;
    // throwing v3's own error kept the lanes together but arrives without a position.
    // ONE MARKER, and the SITE says what it means. `isAbstract` is "no body was written"; in a
    // trait or a class that means dispatch to a subclass, and HERE, at top level, it means an
    // `extern` — a host function. The distinction is structural (`p.defs` versus a member list),
    // not a second spelling kept in step by hand.
    //
    // AND IT STAYS A FUNCTION, throwing when CALLED. Dropping it from the table made a call refuse
    // at the call site, which is right for a program that reaches one and wrong for the far more
    // common case: `v1/runtime/std/scljet/jvm-vfs.ssc` calls `jvmVfsShmRead` on line 136, and
    // **113 corpus cases import that module without ever going near it**. Lowering refuses every
    // def in the module, so all 113 were refused for a host function they never reach.
    //
    // The message CARRIES ITS POSITION so the failure stays actionable — see `Diag.at`. That was
    // the whole reason the first attempt at this was rejected: an unpositioned run-time failure is
    // classified CRASH, and rightly.
    val externDefs = p.defs.filter(isAbstract).map(resolveExtern)
    val allDefs0 = (p.defs.filterNot(isAbstract) ++ externDefs ++ objectDefs ++ methodDefs) :+ entryDef
    // Every callable's signature, so a call site that omits a defaulted argument can be completed
    // before anything is lowered. Constructors are in here too: `case class C(x: Int, y: Int = 0)`
    // is called as `C(1)`, and its 116 corpus cases are the reason this exists.
    // A CONTEXT BOUND BECOMES A PARAMETER BEFORE `sigs` IS BUILT, not after.
    //
    // It used to be applied further down, so `sigs` said `combineAll` took one parameter while the
    // def it lowered took two — and `checkArity`, which reads `sigs`, let a one-argument call
    // through for the IR verifier to refuse WITHOUT a position. `std-semigroup-monoid` went from a
    // refusal to a CRASH that way, which the corpus report counts as worse than a wrong answer.
    // One table, one arity.
    val bounded = allDefs0.map(d =>
      if d.givenParams.isEmpty then d else d.copy(params = d.params ++ d.givenParams))
    val sigs: List[(String, List[Param])] =
      bounded.map(d => (d.name, d.params)) ++ resolved.map(c => (c.name, c.fields))
    val allDefsEager = liftLocals(bounded, bounded.map(_.name) ++ resolved.map(_.name))
    // BY-NAME is applied HERE, at the one place `allDefs` is bound, so every consumer downstream —
    // the gap check, `zeroArityNames`, the lowering itself — sees the rewritten program and none of
    // them needs to know this feature exists. Applying it later meant threading a second list, and a
    // second list is how one consumer ends up reading the un-rewritten version.
    // `resolveGivenArgs` runs BEFORE `resolveSummons`: it fills a call's `using` arguments, and the
    // body it fills them for may itself `summon` one of those parameters. Both run before
    // `resolveMethodRefs`, whose eta-expansion needs the final arity.
    // `resolveSummons` runs FIRST, so a `summon` in a generic body becomes the `using` PARAMETER's
    // name; `resolveGivenArgs` then specialises that body per instance by renaming exactly those
    // parameters. The other order leaves a `summon` inside the specialised copy with no parameter
    // left to resolve against.
    // A CONTEXT BOUND BECOMES A PARAMETER HERE, not in the parser: the two fronts print the same
    // tree that way, and only this file has to know that `[A: Monoid]` means `(using Monoid[A])`.
    // `rewriteGivenExtensionCalls` runs AFTER `rewriteExtensionCalls` and before nothing else: a
    // top-level extension is the more specific reading of `v.m(x)` — it names one function, while
    // an instance method has to be chosen — so the unambiguous rewrite goes first and this one only
    // sees what it left.
    val allDefs = rewriteGivenExtensionCalls(
      rewriteExtensionCalls(resolveMethodRefs(
        resolveGivenArgs(resolveSummons(rewriteByName(allDefsEager), p.objects), p.objects), sigs),
        p.classes),
      p.objects, p.traits, p.classes)

    // ── effect operations ──────────────────────────────────────────────────────────────────────
    //
    // `effect Bump:` declares operations; `Bump.tick(a)` performs one. Op ids are assigned HERE,
    // where the declarations are in scope, and the rewrite puts the resolved id into the tree — so
    // the lowering below never needs the effect table, and there is no fifth parameter to thread
    // through every case and forget in one of them.
    //
    // Ids are positional within the module: effects in declaration order, operations within each.
    // That is enough because `Perform` and `Handle` only ever compare ids inside one module — and
    // it is written down because a positional id is exactly the kind of thing a second front would
    // number differently.
    val effectOps: Map[String, Int] =
      p.effects.flatMap(e => e.methods.map(mm => e.name + "." + mm.name)).zipWithIndex.toMap
    def resolvePerforms(d: Def): Def =
      if effectOps.isEmpty then d
      else d.copy(body = mapDeep(d.body, x => x match
        case Expr.MethodCall(Expr.Name(obj, _), nm, as, xp) if effectOps.contains(obj + "." + nm) =>
          Expr.Perform(effectOps(obj + "." + nm), as, xp)
        case other => other))
    val allDefsE = allDefs.map(resolvePerforms)
    // `handle(body) { case E.op(a…, k) => … }` as the parser leaves it:
    //   Apply(Call("handle", [body]), [Lambda(_, Match(_, arms))])
    // because a braced block of `case` arms is parsed as a one-argument lambda that matches on it.
    //
    // The op is resolved BY OPERATION NAME, not by `E.op`: the pattern parser deliberately drops a
    // qualifier, since `C.Red` and `Red` name the same constructor once an enum is split into one
    // class per case. Rather than fight that, ambiguity is REFUSED — two effects declaring the same
    // operation name is a program this front cannot read, and saying so is better than picking one.
    val opsByPlainName: Map[String, List[Int]] =
      p.effects.flatMap(e => e.methods.map(mm => (mm.name, effectOps(e.name + "." + mm.name))))
       .groupBy(_._1).map((k, v) => (k, v.map(_._2)))
    def resolveHandles(d: Def): Def =
      if effectOps.isEmpty then d
      else d.copy(body = mapDeep(d.body, x => x match
        case Expr.Apply(Expr.Call("handle", List(hb0), _), List(Expr.Lambda(_, Expr.Match(_, arms, _), _)), hp) =>
          // `handle { body } { case … }` — the FIRST brace group is parsed as a lambda too, exactly
          // as the second one is, so `hb0` arrives as an unapplied `Lambda` and using it as the
          // handled body handed the closure straight back: `handle { prog() } { case ask(k) => k(5) }`
          // printed `<closure 2>` where v1 prints `6`. Not a refusal — a wrong answer at exit 0.
          //
          // The paren form `handle(expr) { … }` was never affected, which is why this survived: the
          // corpus writes both and only one of them was tested.
          // (BUGS `v3-handle-block-body-returns-the-closure`.)
          val hb = hb0 match
            case Expr.Lambda(ps, lb, _) if ps.isEmpty => lb
            case other                                => other
          val hs = arms.map { arm =>
            arm.pat match
              // `case Return(x) => …` / `case Return(_) => …` — THE RETURN CLAUSE AS THE CORPUS
              // WRITES IT, and it must be tested before the operation branch below or `Return`
              // resolves as an undeclared operation and the program is refused.
              //
              // This spelling is the language's, not a leaked runtime marker: fifteen programs use
              // it, `tests/interop-conformance/probes/08-return-clause-transform.ssc` exists to pin
              // it, and `examples/algebraic-effects.ssc` documents it in prose — "the
              // `case Return(_) => List()` RETURN CLAUSE seeds the base case". v1, v2, JsGen and
              // JvmGen all implement it (`EffectsRuntime.scala:156`, `PortableEffects.scala:189`).
              // v3 accepted only the bare `case x =>` below, and refused FOURTEEN programs over the
              // difference: the whole interop-conformance effect probe suite, three conformance
              // libraries, a v21-native fixture and the documented example.
              // (BUGS `v1-handle-return-clause-binds-the-Return-wrapper`.)
              //
              // Lowered to exactly what the bare binder lowers to — `op = -1`, the marker no
              // operation index can take — so the two spellings are ONE arm downstream and the
              // executor needs no second notion of a return clause.
              case Pat.PCtor("Return", List(Pat.PBind(bn, _)), pp) =>
                HandleArm(-1, List(bn), "__return__", arm.body, pp)
              // `case Return(_)` binds nothing; the arm still needs a name for the value it is
              // handed, so one is invented. `__` cannot collide with a user binder — the lexer does
              // not produce it as an identifier.
              case Pat.PCtor("Return", List(Pat.PWild(_)), pp) =>
                HandleArm(-1, List("__ret_unused"), "__return__", arm.body, pp)
              // Any other shape of `Return` is refused BY NAME rather than falling through to the
              // operation branch, where the message would say `Return` is not a declared operation
              // and send the author looking for a missing `effect` declaration.
              case Pat.PCtor("Return", other, pp) =>
                throw LowerFail(pp, "a `Return` clause takes exactly one binder — `case Return(x)` " +
                                    "or `case Return(_)` — and this one has " + other.length)
              case Pat.PCtor(nm, binders, pp) =>
                opsByPlainName.get(nm) match
                  case None => throw LowerFail(pp, "'" + nm + "' is not a declared effect operation")
                  case Some(ids) if ids.length > 1 =>
                    throw LowerFail(pp,
                      "'" + nm + "' is declared by more than one effect; this front resolves a " +
                      "handler arm by the operation name alone and cannot tell them apart")
                  case Some(ids) =>
                    val names = binders.map {
                      case Pat.PBind(bn, _) => bn
                      case other           => throw LowerFail(Pat.posOf(other),
                        "a handler arm binds plain names only — the last is the continuation")
                    }
                    if names.isEmpty then
                      throw LowerFail(pp, "a handler arm needs a continuation binder, as in `case " +
                                          nm + "(resume) => resume(v)`")
                    // The LAST binder is the continuation; the ones before it are the operation's
                    // arguments. That order is the one the corpus writes and the one Scala's effect
                    // proposals use, and it is stated here because nothing else can state it.
                    val k = names.last
                    HandleArm(ids.head, names.init, k,
                              mapDeep(arm.body, y => y match
                                case Expr.Call(cn, List(cv), cp) if cn == k => Expr.Resume(k, cv, cp)
                                case other2 => other2), pp)
              // `case x => …` — THE RETURN CLAUSE. Not an operation: a plain binder, bound to
              // the value the handled body produced, and its result is what `handle` gives back.
              // Marked `op = -1`, which no operation index can be, so the executor can find it
              // without a second field on every arm.
              //
              // No resume rewrite in its body, because it has no continuation to rewrite: the
              // body has already finished by the time this runs.
              case Pat.PBind(bn, bp) =>
                HandleArm(-1, List(bn), "__return__", arm.body, bp)
              case other => throw LowerFail(Pat.posOf(other),
                "a handler arm must match an effect operation, as in `case tick(resume) => …`")
          }
          Expr.Handle(hb, hs, hp)
        case other => other))
    val allDefsH = allDefsE.map(resolveHandles)
      .map { d =>
        // THE ORIGIN IS ATTACHED HERE TOO, and leaving it off is P-2 of
        // `v3/PRELUDE-CORRECTNESS.md`. `LowerFail` has carried an `origin` since before the
        // prelude, and it is set at ONE place — around the lowering of a def's BODY, further down.
        // These passes run EARLIER, so every message they produce arrived with no origin and
        // `Main` fell back to the path the user named. A three-line fixture was told its error was
        // at line 38, which is a line in the PRELUDE: positions are already counted inside their
        // own file, it is the FILE that was lost.
        val e0 =
          try checkArity(fillDefaults(flattenCurried(expandPlaceholders(d.body), sigs), sigs), sigs, d.params.map(_.name).toSet)
          catch case e: LowerFail => throw e.at(p.origin.get(d.name))
        // Boxing runs LAST, on the tree every other pass has finished with: `expandPlaceholders`
        // creates lambdas, and a lambda created after the analysis would capture a var the analysis
        // never saw.
        d.copy(body = boxLocals(e0, boxedNames(e0)))
      }
    // Names that may be referenced WITHOUT parentheses. Collected once, before any lowering, so a
    // def declared later in the file is still callable from one declared earlier.
    // A HOST FUNCTION THAT IS ACTUALLY REACHED is refused HERE, with a position, rather than left
    // to fail at run time.
    //
    // Both halves of that sentence were paid for. Refusing the DECLARATION cost 113 corpus cases
    // that merely import `scljet/jvm-vfs.ssc` and never go near its `jvmVfsShmRead`. Letting the
    // call fail at RUN time cost 5 the other way: the executor prints a clean positioned refusal
    // and the bridge lets v2 throw, so one lane reads UNSUPPORTED and the other reads a wrong
    // ANSWER, and the two v3 lanes stopped agreeing (invariant I-3).
    //
    // Reachability from the entry separates them. It is deliberately UNDER-approximated — direct
    // calls only, no dynamic method dispatch — because over-approximating would mark a host
    // function reachable through any same-named method and refuse the 113 all over again. What an
    // under-approximation can miss is a gap reached only through dynamic dispatch, which then fails
    // at run time exactly as it did before; nothing gets worse, some things get better.
    // `hostPrims` IS SUBTRACTED, because a host function this lane performs is not a gap. Without
    // this line the table above changes nothing: an `extern` reached from the entry is refused
    // HERE, at the call, before its body is ever consulted — so `writeFile` kept its refusal while
    // its declaration had already been lowered to `(prim io.writeFile …)`. The two places that
    // decide what an `extern` means have to read the same table.
    val gapNames = p.defs.filter(isAbstract).map(d => d.name).filterNot(hostPrims.contains)
    if gapNames.nonEmpty then
      val byName = allDefsH.map(d => (d.name, d)).toMap
      def callees(e: Expr): List[String] =
        var out: List[String] = Nil
        mapDeep(e, x => { x match { case Expr.Call(fn, _, _) => out = fn :: out; case _ => () }; x })
        out
      var seen: List[String] = List(entryName)
      var queue: List[String] = List(entryName)
      while queue.nonEmpty do
        val n = queue.head
        queue = queue.tail
        byName.get(n).foreach { d =>
          callees(d.body).foreach { c => if !seen.contains(c) then { seen = c :: seen; queue = c :: queue } }
        }
      seen.foreach { n =>
        byName.get(n).foreach { d =>
          var bad: Option[(String, Pos)] = None
          mapDeep(d.body, x => {
            x match
              case Expr.Call(fn, _, cp) if bad.isEmpty && gapNames.contains(fn) => bad = Some((fn, cp))
              case _ => ()
            x
          })
          bad.foreach { (fn, cp) =>
            throw LowerFail(cp, "the host function '" + fn + "' is not implemented on this lane")
          }
        }
      }

    val zeroArityNames = allDefsH.filter(d => d.params.isEmpty).map(d => d.name)
    val names = allDefsH.map(d => d.name)
    val entry = names.indexOf(entryName)

    var consts: List[Lit] = Nil
    var prims: List[String] = Nil
    // The types the RUNTIME can produce, declared whether or not the source mentions them.
    //
    // `xs.find(…)` returns a `Some`, `xs.zip(ys)` returns tuples — and a module that never writes
    // `Some` or `(a, b)` had no entry for them, so the executor could not build the value it was
    // asked for. The bridge never noticed because v2 has its own constructors; that asymmetry is
    // exactly the lane divergence this pre-registration removes.
    var types: List[TypeDef] =
      List(TypeDef("Cons", 2), TypeDef("Nil", 0), TypeDef("Some", 1), TypeDef("None", 0),
           TypeDef("Tuple2", 2), TypeDef("Pair", 2), TypeDef("Right", 1), TypeDef("Left", 1))
    var lifted: List[Func] = Nil
    // Collected BEFORE anything is lowered: a `def` may reference a top-level `val` declared
    // further down the file, and the other lanes allow that.
    // An object's `val`/`var` members are module GLOBALS named `Object.member`. A namespace is not
    // a value in this language, so there is nowhere else for a `var` to live — and naming them with
    // the dot keeps them in the SAME namespace the qualified read already looks in.
    val objectGlobals = p.objects.flatMap(o => o.vals.map(v => o.name + "." + v.name))
    val globalNames = (p.topLevel.flatMap { st => st match
      case Stmt.Val(n, _, _, _) => List(n)
      case _                    => Nil
    } ++ objectGlobals).distinct
    var funcs: List[Func] = Nil
    allDefsH.foreach { d =>
      val params = d.params.zipWithIndex.map((pa, i) => (pa.name, i))
      val st0 = St(d.params.length, d.params.length, params.reverse, consts, prims, types, lifted, globalNames)
      // ONE place, because every message this lowering can produce passes through here. Attaching
      // the origin at each `throw` site instead would be forty edits and would drift the first time
      // somebody adds a forty-first.
      val (body, r, st) =
        try lower(d.body, names, resolved ++ tupleClasses, zeroArityNames, st0)
        catch case e: LowerFail => throw e.at(p.origin.get(d.name))
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
    // `Cps` BEFORE `TailCalls`: splitting introduces a `Ret` and a whole new function, and
    // tail-call detection should see the FINAL shape rather than the one about to change.
    // Both are Module->Module, so this order is a choice recorded here rather than an accident
    // of where the call happened to be written.
    TailCalls(Cps(Module(consts, types, globalNames.map(n => GlobalDef(n)), prims, funcs.reverse ++ lifted, entry)))
