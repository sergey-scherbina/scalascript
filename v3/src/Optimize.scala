package ssc3

// SSC3-J1d — fewer instructions, not a cheaper dispatch. Design: `specs/ssc3-jit.md` §10.
//
// THE EVIDENCE THIS PASS EXISTS ON. Three changes in this ladder moved exactly what they targeted
// and lost on the clock — closure compilation replaced the dispatch, the long bank routed it through
// a second loop, frame pooling was refuted before it was written — while the one change that clearly
// won, J0c, did nothing but make the EXISTING dispatch inlinable. The executor is dispatch-bound and
// its dispatch is already good, so the remaining lever is the number of dispatches.
//
// `bench/corpus/arith-loop.ssc` is ten instructions per iteration and FOUR of them are artefacts of
// the lowering: two `Const` reloading loop-invariant literals, and two `Move` copying a fresh result
// register into the variable's register because the front allocates a new register per expression
// and never coalesces.
//
//     (const 4 1)  (bin lt 5 1 4)  (un not 6 5)  (brif 6 1)
//     (bin add 7 3 1)  (move 3 7)  (const 8 2)  (bin add 9 1 8)  (move 1 9)  (br 0)
//
// A rewrite of DATA, which is what `10-ssc-ir.md` says optimization is here — the instruction set,
// the verifier and the text form are untouched, and the output is an ordinary `Module` that the
// verifier re-checks.
//
// Written in the Scala 3 ∩ ScalaScript 2 subset.

object Optimize:

  /** Every pass, in order. Runs AFTER `Verify` and its result is verified again — invariant I-4
    * applies to a pass as much as to a program. */
  def module(m: Module): Module = module(m, true)

  /** `hoistConsts = false` is the OFF arm of this pass's own measurement (`ssc3 exec --no-hoist`),
    * the same shape `--no-specialize` and `--no-optimize` already have. A pass whose effect cannot
    * be switched off in ONE binary has to be measured across two builds, and this repository has
    * paid for that: two class directories of the same tree is how J1c was measured, and its control
    * could not see the load that moved between them. */
  // COPY PROPAGATION RUNS FIRST, and the order is a measurement rather than a preference. The front
  // lowers `k = 10` inside a loop as `Const -> rTmp` then `Move(rK, rTmp)`, so on the RAW body the
  // constant's own register is written once and looks liftable — and lifting it separates the pair
  // copy propagation would have collapsed into a single `Const -> rK`, trading a fused instruction
  // for a hoisted one and gaining nothing. Folded first, the same code reads `Const -> rK` with `rK`
  // written twice, which the guard below correctly refuses. Same two passes, opposite outcome, and
  // `v3/tests/front/hoist-guard.ssc` is the program that tells them apart: it prints 30 in this
  // order and 297 in the other.
  def module(m: Module, hoistConsts: Boolean): Module = module(m, hoistConsts, true)

  def module(m: Module, hoistConsts: Boolean, invertCompares: Boolean): Module =
    m.copy(funcs = m.funcs.map { f =>
      val folded = f.copy(body = copyProp(f))
      val turned =
        if invertCompares then
          val (reads, writes) = census(folded)
          folded.copy(body = invert(folded, folded.body, reads, writes))
        else folded
      if hoistConsts then turned.copy(body = hoist(turned, turned.body, writeCounts(turned)))
      else turned
    })

  // ── compare, negate, branch ────────────────────────────────────────────────────────────────────
  //
  // SSC3-J4b. Every counted loop in the corpus ends its test the same way — three dispatches for one
  // decision:
  //
  //     (bin lt i64 5 1 4)  (un not dyn 6 5)  (brif 6 1)
  //
  // Inverting the comparison removes the `Not`: `not (a < b)` is `a >= b`. That identity is the
  // whole pass and it is NOT universally true —
  //
  //   * on FLOATS it is false. A NaN compares false to everything, so `!(nan < 1.0)` is TRUE while
  //     `nan >= 1.0` is FALSE. The rewrite is therefore refused unless `Specialize` proved the
  //     comparison's kind `I64` or `Big`. `v3/tests/front/cmp-invert-nan.ssc` is the program that
  //     tells the two apart: `true true true` with the guard, `false false true` without it.
  //   * `Eq`/`Ne` need no guard at all, on any kind: IEEE says `NaN == x` is false and `NaN != x` is
  //     true, so `not (a == b)` is `a != b` even where the ordering identity fails.
  //
  // The dataflow conditions are the same shape as copy propagation's, and for the same reason — the
  // cheap version is the one that is obviously correct:
  //
  //   * the three are ADJACENT in one region's list, so nothing runs between them;
  //   * the comparison's destination is written once and read once IN THE WHOLE FUNCTION, so the
  //     `Not` is its only reader and dropping it cannot orphan another use;
  //   * the `Not`'s destination is written once, so writing the comparison straight into it cannot
  //     clobber a value some other path produced.

  private def census(f: Func): (Array[Int], Array[Int]) =
    val reads = new Array[Int](f.nregs)
    val writes = new Array[Int](f.nregs)
    var all = Instr.flatten(f.body)
    while all.nonEmpty do
      val i = all.head
      val d = dstOf(i)
      if d >= 0 && d < f.nregs then writes(d) = writes(d) + 1
      var rs = readsOf(i)
      while rs.nonEmpty do
        val r = rs.head
        if r >= 0 && r < f.nregs then reads(r) = reads(r) + 1
        rs = rs.tail
      all = all.tail
    (reads, writes)

  /** `private[ssc3]`, not `private`, and the reason is a rule that must have ONE definition.
    *
    * `BridgeV2` needs the same pair: the structured `while` it emits negates the loop condition, and
    * negating a comparison by inverting it is ILLEGAL on floats — a NaN compares false both ways, so
    * `!(a >= b)` is not `a < b` there. That is the whole content of `invertible`, and a second copy
    * of it in the emitter would be a copy of a rule that is wrong in a way no fixture notices.
    * Nothing else about these two functions is shared; only the rule is. */
  private[ssc3] def inverseOf(op: BinOp): BinOp = op match
    case BinOp.Lt => BinOp.Ge
    case BinOp.Le => BinOp.Gt
    case BinOp.Gt => BinOp.Le
    case BinOp.Ge => BinOp.Lt
    case BinOp.Eq => BinOp.Ne
    case BinOp.Ne => BinOp.Eq
    case other    => other

  /** `Eq`/`Ne` invert on any kind; the ordering four need a kind with no NaN in it. */
  private[ssc3] def invertible(op: BinOp, kind: NumKind): Boolean = op match
    case BinOp.Eq | BinOp.Ne => true
    case BinOp.Lt | BinOp.Le | BinOp.Gt | BinOp.Ge =>
      kind == NumKind.I64 || kind == NumKind.Big
    case _ => false

  private def invert(f: Func, body: List[Instr], reads: Array[Int], writes: Array[Int]): List[Instr] =
    var out: List[Instr] = Nil
    var rest = body
    while rest.nonEmpty do
      val head = descendInvert(f, rest.head, reads, writes)
      var turned = false
      (head, rest.tail) match
        case (Instr.Bin(op, kind, d, a, b), Instr.Un(UnOp.Not, _, e, nd) :: (br @ Instr.BrIf(c, _)) :: after)
            if invertible(op, kind) && nd == d && c == e
               && d >= f.nparams && d < f.nregs && e >= f.nparams && e < f.nregs
               && writes(d) == 1 && reads(d) == 1 && writes(e) == 1 =>
          out = br :: Instr.Bin(inverseOf(op), kind, e, a, b) :: out
          rest = after
          turned = true
        case _ => ()
      if !turned then
        out = head :: out
        rest = rest.tail
    out.reverse

  private def descendInvert(f: Func, i: Instr, reads: Array[Int], writes: Array[Int]): Instr = i match
    case Instr.Block(b)    => Instr.Block(invert(f, b, reads, writes))
    case Instr.Loop(b)     => Instr.Loop(invert(f, b, reads, writes))
    case Instr.If(c, t, e) => Instr.If(c, invert(f, t, reads, writes), invert(f, e, reads, writes))
    case Instr.Switch(s, arms, df) =>
      Instr.Switch(s, arms.map(a => SwitchArm(a.tag, invert(f, a.body, reads, writes))),
                   invert(f, df, reads, writes))
    case Instr.Try(d, b, exn, h)  => Instr.Try(d, invert(f, b, reads, writes), exn, invert(f, h, reads, writes))
    case Instr.Handle(d, b, arms) =>
      Instr.Handle(d, invert(f, b, reads, writes),
                   arms.map(a => HandlerArm(a.op, a.params, a.k, invert(f, a.body, reads, writes))))
    case other => other

  // ── loop-invariant constants ───────────────────────────────────────────────────────────────────
  //
  // SSC3-J4a. `arith-loop`'s inner loop reloads two literals on EVERY iteration:
  //
  //     (loop (const 4 1000000) (bin lt 5 1 4) … (const 8 1) (bin add 9 1 8) (br 0))
  //
  // A `Const` is pure and its value cannot depend on anything, so the only question a hoist raises
  // is whether the register it writes can hold something else at any point the loop can observe.
  // The condition here is the strongest available and the cheapest to check: the register is written
  // EXACTLY ONCE IN THE WHOLE FUNCTION, counted over the flattened body, and it is not a parameter.
  // Nothing else can assign it, so every read anywhere sees this constant whether the write happens
  // once before the loop or once per iteration.
  //
  // TWO THINGS THIS DELIBERATELY DOES NOT DO, because both are only correct under a rule the
  // verifier would have to state and does not:
  //
  //   * it lifts only from the loop's TOP-LEVEL list, never out of an `If` arm inside it — lifting a
  //     conditional write would make it unconditional, and "nobody reads it on the other path" is an
  //     argument about a program the verifier does not check;
  //   * it lifts a `Const` and nothing else. `Move`, `Bin` and the rest read registers, so their
  //     invariance is a dataflow claim rather than a syntactic one, and this pass makes no dataflow
  //     claims.
  //
  // A loop that runs zero times now executes the `Const` it would have skipped. That is a pure
  // write to a register only this instruction can write, so no program can observe it.
  //
  // Nested loops fall out of the recursion order: the inner `Loop` is rewritten FIRST, so a constant
  // lifted out of it becomes a top-level instruction of the outer loop's body and is lifted again on
  // the same walk. `nested-loop` moves five constants out of two levels for that reason.

  private def writeCounts(f: Func): Array[Int] =
    val writes = new Array[Int](f.nregs)
    var all = Instr.flatten(f.body)
    while all.nonEmpty do
      val d = dstOf(all.head)
      if d >= 0 && d < f.nregs then writes(d) = writes(d) + 1
      all = all.tail
    writes

  private def hoist(f: Func, body: List[Instr], writes: Array[Int]): List[Instr] =
    var out: List[Instr] = Nil
    var rest = body
    while rest.nonEmpty do
      rest.head match
        case Instr.Loop(b) =>
          val inner = hoist(f, b, writes)
          val lifted = inner.filter(i => liftable(f, i, writes))
          val kept   = inner.filter(i => !liftable(f, i, writes))
          out = Instr.Loop(kept) :: (lifted.reverse ::: out)
        case other =>
          out = descendHoist(f, other, writes) :: out
      rest = rest.tail
    out.reverse

  private def liftable(f: Func, i: Instr, writes: Array[Int]): Boolean = i match
    case Instr.Const(d, _) => d >= f.nparams && d < f.nregs && writes(d) == 1
    case _                 => false

  /** Regions other than `Loop` are walked so a loop nested inside one is still reached. */
  private def descendHoist(f: Func, i: Instr, writes: Array[Int]): Instr = i match
    case Instr.Block(b)    => Instr.Block(hoist(f, b, writes))
    case Instr.Loop(b)     => Instr.Loop(hoist(f, b, writes))
    case Instr.If(c, t, e) => Instr.If(c, hoist(f, t, writes), hoist(f, e, writes))
    case Instr.Switch(s, arms, df) =>
      Instr.Switch(s, arms.map(a => SwitchArm(a.tag, hoist(f, a.body, writes))), hoist(f, df, writes))
    case Instr.Try(d, b, exn, h)   => Instr.Try(d, hoist(f, b, writes), exn, hoist(f, h, writes))
    case Instr.Handle(d, b, arms)  =>
      Instr.Handle(d, hoist(f, b, writes),
                   arms.map(a => HandlerArm(a.op, a.params, a.k, hoist(f, a.body, writes))))
    case other => other

  // ── copy propagation ───────────────────────────────────────────────────────────────────────────
  //
  // `<something> -> r` immediately followed by `Move(d, r)`, where `r` is used NOWHERE else, becomes
  // the same something writing straight to `d`. Two instructions become one and a register dies.
  //
  // The conditions are deliberately narrow, because the cheap version of this is the one that is
  // obviously correct:
  //
  //   * ADJACENT, so nothing can read `d` in between and no control flow can enter between them;
  //   * `r` written exactly once and read exactly once IN THE WHOLE FUNCTION — counted over the
  //     flattened body, so a use inside any nested region counts. A register the front reuses is
  //     therefore never touched;
  //   * `r` is not a parameter, and neither register is out of range.
  //
  // `d` may be read by the instruction being rewritten (`Bin(add, 7, 3, 1); Move(3, 7)` becomes
  // `Bin(add, 3, 3, 1)`) and that is safe on this executor because every instruction reads its
  // operands before assigning its destination — `regs(d) = f(regs(a), regs(b))`. Stated because it
  // is the one aliasing question this rewrite raises.

  private def copyProp(f: Func): List[Instr] =
    val reads = new Array[Int](f.nregs)
    val writes = new Array[Int](f.nregs)
    var all = Instr.flatten(f.body)
    while all.nonEmpty do
      val i = all.head
      val d = dstOf(i)
      if d >= 0 && d < f.nregs then writes(d) = writes(d) + 1
      var rs = readsOf(i)
      while rs.nonEmpty do
        val r = rs.head
        if r >= 0 && r < f.nregs then reads(r) = reads(r) + 1
        rs = rs.tail
      all = all.tail
    rewrite(f, f.body, reads, writes)

  /** Regions are rewritten too, and each region's body is its own adjacency scope: a pair may not
    * straddle the end of a `Block` or an arm of an `If`, because the instruction after the region is
    * not the instruction after the pair. */
  private def rewrite(f: Func, body: List[Instr], reads: Array[Int], writes: Array[Int]): List[Instr] =
    var out: List[Instr] = Nil
    var rest = body
    while rest.nonEmpty do
      val head = descend(f, rest.head, reads, writes)
      val tail = rest.tail
      var fused = false
      if tail.nonEmpty then
        tail.head match
          case Instr.Move(d, s) =>
            val w = dstOf(head)
            if w >= 0 && w == s && s >= f.nparams && s < f.nregs && d >= 0 && d < f.nregs && d != s
               && writes(s) == 1 && reads(s) == 1 then
              out = retarget(head, d) :: out
              rest = tail.tail
              fused = true
          case _ => ()
      if !fused then
        out = head :: out
        rest = tail
    out.reverse

  private def descend(f: Func, i: Instr, reads: Array[Int], writes: Array[Int]): Instr = i match
    case Instr.Block(b) => Instr.Block(rewrite(f, b, reads, writes))
    case Instr.Loop(b)  => Instr.Loop(rewrite(f, b, reads, writes))
    case Instr.If(c, t, e) => Instr.If(c, rewrite(f, t, reads, writes), rewrite(f, e, reads, writes))
    case Instr.Switch(s, arms, df) =>
      Instr.Switch(s, arms.map(a => SwitchArm(a.tag, rewrite(f, a.body, reads, writes))),
                   rewrite(f, df, reads, writes))
    case Instr.Try(d, b, exn, h) =>
      Instr.Try(d, rewrite(f, b, reads, writes), exn, rewrite(f, h, reads, writes))
    case Instr.Handle(d, b, arms) =>
      Instr.Handle(d, rewrite(f, b, reads, writes),
                   arms.map(a => HandlerArm(a.op, a.params, a.k, rewrite(f, a.body, reads, writes))))
    case other => other

  /** The same instruction writing somewhere else. Exhaustive over the writing opcodes, with a
    * `-1`-returning `dstOf` guarding the call site, so an opcode this does not know is never fused
    * rather than silently retargeted to the wrong place. */
  private def retarget(i: Instr, d: Int): Instr = i match
    case Instr.Const(_, k)          => Instr.Const(d, k)
    case Instr.Move(_, a)           => Instr.Move(d, a)
    case Instr.Un(op, k, _, a)      => Instr.Un(op, k, d, a)
    case Instr.Bin(op, k, _, a, b)  => Instr.Bin(op, k, d, a, b)
    case Instr.Call(_, fn, as)      => Instr.Call(d, fn, as)
    case Instr.CallV(_, c, as)      => Instr.CallV(d, c, as)
    case Instr.MkClos(_, fn, cs)    => Instr.MkClos(d, fn, cs)
    case Instr.MkData(_, t, as)     => Instr.MkData(d, t, as)
    case Instr.Field(_, a, t, ix)   => Instr.Field(d, a, t, ix)
    case Instr.Tag(_, a)            => Instr.Tag(d, a)
    case Instr.NewArr(_, n)         => Instr.NewArr(d, n)
    case Instr.ArrGet(_, a, ix)     => Instr.ArrGet(d, a, ix)
    case Instr.ArrLen(_, a)         => Instr.ArrLen(d, a)
    case Instr.GlobGet(_, g)        => Instr.GlobGet(d, g)
    case Instr.Invoke(_, nm, r, as) => Instr.Invoke(d, nm, r, as)
    case Instr.Prim(_, p, as)       => Instr.Prim(d, p, as)
    case other                      => other

  /** The register an instruction writes, or `-1`.
    *
    * `Try`, `Handle`, `Perform` and `Resume` write a destination and are DELIBERATELY absent: they
    * carry regions or suspend, so "the instruction immediately after" is not a thing this rewrite
    * may reason about. Returning `-1` for them means they are never fused. */
  private def dstOf(i: Instr): Int = i match
    case Instr.Const(d, _)        => d
    case Instr.Move(d, _)         => d
    case Instr.Un(_, _, d, _)     => d
    case Instr.Bin(_, _, d, _, _) => d
    case Instr.Call(d, _, _)      => d
    case Instr.CallV(d, _, _)     => d
    case Instr.MkClos(d, _, _)    => d
    case Instr.MkData(d, _, _)    => d
    case Instr.Field(d, _, _, _)  => d
    case Instr.Tag(d, _)          => d
    case Instr.NewArr(d, _)       => d
    case Instr.ArrGet(d, _, _)    => d
    case Instr.ArrLen(d, _)       => d
    case Instr.GlobGet(d, _)      => d
    case Instr.Invoke(d, _, _, _) => d
    case Instr.Prim(d, _, _)      => d
    case _ => -1

  /** Every register an instruction READS. Over-listing is safe here (it only prevents a fusion);
    * UNDER-listing is not, so a region form lists nothing and is excluded by `dstOf` instead. */
  private def readsOf(i: Instr): List[Int] = i match
    case Instr.Move(_, a)          => List(a)
    case Instr.Un(_, _, _, a)      => List(a)
    case Instr.Bin(_, _, _, a, b)  => List(a, b)
    case Instr.If(c, _, _)         => List(c)
    case Instr.BrIf(c, _)          => List(c)
    case Instr.Call(_, _, as)      => as
    case Instr.CallV(_, c, as)     => c :: as
    case Instr.MkClos(_, _, cs)    => cs
    case Instr.TailCall(_, as)     => as
    case Instr.Ret(a)              => List(a)
    case Instr.MkData(_, _, as)    => as
    case Instr.Field(_, a, _, _)   => List(a)
    case Instr.Tag(_, a)           => List(a)
    case Instr.Switch(s, _, _)     => List(s)
    case Instr.NewArr(_, n)        => List(n)
    case Instr.ArrGet(_, a, ix)    => List(a, ix)
    case Instr.ArrSet(a, ix, v)    => List(a, ix, v)
    case Instr.ArrLen(_, a)        => List(a)
    case Instr.GlobSet(_, a)       => List(a)
    case Instr.Perform(_, _, as)   => as
    case Instr.Resume(_, k, v)     => List(k, v)
    case Instr.Invoke(_, _, r, as) => r :: as
    case Instr.Prim(_, _, as)      => as
    case _ => Nil

  /** How many instructions a module contains, counting inside every region. The number this pass is
    * judged on, and it does not depend on host load. */
  def instrCount(m: Module): Int =
    var n = 0
    var fs = m.funcs
    while fs.nonEmpty do
      n = n + Instr.flatten(fs.head.body).length
      fs = fs.tail
    n
