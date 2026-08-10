package ssc3

// SSC3-J1 — the specializer. Design: `specs/ssc3-jit.md` §3.
//
// `Ir.scala` says what the `kind` field on `Un`/`Bin` is for, in as many words: "the front emits
// `Dyn` unless it can prove the operand type, and the specializer rewrites the field in place.
// Optimization is then a rewrite of data, not a change of representation."
//
// Measured 2026-08-09, before this file existed: `Lower.scala` emits `NumKind.Dyn` at ALL NINE of
// its `Bin`/`Un` sites and `I64` at none, and `Exec.step` matches the field as `_`. The lever the
// IR was designed around was emitted blank and read nowhere. This is the half that writes it.
//
// Written in the Scala 3 ∩ ScalaScript 2 subset (`v3/specs/30-portable-subset.md`): `enum`, `List`,
// tuples, local `var`/`while`, no mutable collections, and no `val`/`var` members in the object
// body. `List` rather than `Array` for the register state because a dataflow state is JOINED and
// compared, and an immutable value cannot be aliased into a predecessor's state by accident.

/** What the pass proved about one register at one program point.
  *
  * Deliberately four cases and not seven. `KTop` covers booleans, strings, data, closures, unit and
  * "two branches disagreed" alike, because the only question this pass answers is *may I write a
  * numeric kind here* — and every one of those answers no. A richer lattice would be more precise
  * about facts nothing downstream can use.
  */
enum Kd:
  /** No information yet: an unreachable point, or the identity element of a join. NOT the state of
    * a register at function entry — see `Specialize.entryState`. */
  case KBot
  case KInt
  case KFloat
  case KTop

object Specialize:

  // ── WHY THERE IS NO `Big` HERE, AND WHY THAT IS A CORRECTNESS RULE ─────────────────────────────
  //
  // `NumKind` offers `Big`, and this pass never emits it. `Exec.constOf` reads
  // `case Lit.LBig(d) => Value.VStr(d)` — a big literal becomes a STRING at run time, v3 has no
  // big-integer value yet, and `Exec.binOp` matches its string arms BEFORE any numeric arm. So two
  // "Big" operands added are a string concatenation, and an instruction marked `Big` would be
  // describing a representation the executor does not have.
  //
  // This is exactly the class of mistake the field being unread makes invisible: nothing would have
  // failed today, and the day `Exec` learns to trust `kind` it would have been a wrong answer with
  // no commit to blame. `Dyn` is always correct, so the absence costs nothing but precision.

  /** The whole point: rewrite the `kind` field where the operand types are proved, leave everything
    * else exactly as it was.
    *
    * Runs AFTER `Verify.module` (invariant I-4) and produces a `Module` that must still verify —
    * it changes one enum field per instruction and no register index, so it cannot invalidate any
    * of the five rules, and `v3/jit-gate.sh` re-verifies the output rather than assuming it.
    */
  def module(m: Module): Module =
    m.copy(funcs = m.funcs.map(f => func(m, f)))

  def func(m: Module, f: Func): Func =
    val (body, _, _) = runList(m, f.body, entryState(f.nregs))
    f.copy(body = body)

  /** Every register is `KTop` on entry, and both halves of that are deliberate.
    *
    * `Exec.callFunc` fills the frame with `Value.VUnit` and then writes the arguments over the
    * first `nparams` slots. A parameter's kind is the caller's business and this pass is
    * intraprocedural, so it is unknown; a non-parameter register holds `unit`, which is not a
    * number. Both are `KTop`, so the entry state is uniform — and starting OPTIMISTIC instead
    * (assuming `KInt` until refuted) would prove `r = r + 1` numeric in a function where `r` is
    * never initialised, which is a wrong answer reachable from a cycle with no ground truth in it.
    */
  private def entryState(n: Int): List[Kd] =
    var out: List[Kd] = Nil
    var i = 0
    while i < n do
      out = Kd.KTop :: out
      i = i + 1
    out

  private def botState(n: Int): List[Kd] =
    var out: List[Kd] = Nil
    var i = 0
    while i < n do
      out = Kd.KBot :: out
      i = i + 1
    out

  private def join(a: Kd, b: Kd): Kd =
    if a == Kd.KBot then b
    else if b == Kd.KBot then a
    else if a == b then a
    else Kd.KTop

  private def joinSt(a: List[Kd], b: List[Kd]): List[Kd] =
    var out: List[Kd] = Nil
    var xs = a
    var ys = b
    while xs.nonEmpty && ys.nonEmpty do
      out = join(xs.head, ys.head) :: out
      xs = xs.tail
      ys = ys.tail
    out.reverse

  private def at(st: List[Kd], i: Int): Kd =
    if i < 0 || i >= st.length then Kd.KTop else st(i)

  /** `zipWithIndex`/`updated` would both read better and neither is in the subset's §1 list, so
    * this is spelled with `head`/`tail`/`::` — the operations the subset does name. */
  private def setAt(st: List[Kd], i: Int, k: Kd): List[Kd] =
    var out: List[Kd] = Nil
    var rest = st
    var j = 0
    while rest.nonEmpty do
      out = (if j == i then k else rest.head) :: out
      rest = rest.tail
      j = j + 1
    out.reverse

  private def isNum(k: Kd): Boolean = k == Kd.KInt || k == Kd.KFloat

  private def kindOfLit(l: Lit): Kd = l match
    case Lit.LInt(_)   => Kd.KInt
    case Lit.LFloat(_) => Kd.KFloat
    // `LBig` is a string at run time (see the note at the top), and unit/bool/str/bytes are not
    // numbers. All `KTop`.
    case _ => Kd.KTop

  private def numKindOf(k: Kd): NumKind =
    if k == Kd.KInt then NumKind.I64 else NumKind.F64

  private def isCompare(op: BinOp): Boolean = op match
    case BinOp.Lt | BinOp.Le | BinOp.Gt | BinOp.Ge | BinOp.Eq | BinOp.Ne => true
    case _ => false

  /** The register an instruction writes, or `-1`. Used to build the conservative input state for a
    * region that can be entered at a point this pass cannot see — a `Try` handler, a `Handle` arm. */
  private def dstOf(i: Instr): Int = i match
    case Instr.Const(d, _)         => d
    case Instr.Move(d, _)          => d
    case Instr.Un(_, _, d, _)      => d
    case Instr.Bin(_, _, d, _, _)  => d
    case Instr.Call(d, _, _)       => d
    case Instr.CallV(d, _, _)      => d
    case Instr.MkClos(d, _, _)     => d
    case Instr.MkData(d, _, _)     => d
    case Instr.Field(d, _, _, _)   => d
    case Instr.Tag(d, _)           => d
    case Instr.NewArr(d, _)        => d
    case Instr.ArrGet(d, _, _)     => d
    case Instr.ArrLen(d, _)        => d
    case Instr.GlobGet(d, _)       => d
    case Instr.Try(d, _, _, _)     => d
    case Instr.Perform(d, _, _)    => d
    case Instr.Handle(d, _, _)     => d
    case Instr.Resume(d, _, _)     => d
    case Instr.Invoke(d, _, _, _)  => d
    case Instr.Prim(d, _, _)       => d
    case _ => -1

  /** `st` with every register written ANYWHERE inside `body` — nested regions included — set to
    * `KTop`, plus the arms' binders.
    *
    * This is what makes a `Try` handler and a `Handle` arm sound without modelling where control
    * actually re-enters them. Whatever the real entry state is, it is below this one.
    */
  private def topAllWritten(st: List[Kd], body: List[Instr]): List[Kd] =
    var out = st
    var rest = Instr.flatten(body)
    while rest.nonEmpty do
      val i = rest.head
      val d = dstOf(i)
      if d >= 0 then out = setAt(out, d, Kd.KTop)
      i match
        case Instr.Handle(_, _, arms) =>
          var as = arms
          while as.nonEmpty do
            var ps = as.head.params
            while ps.nonEmpty do
              out = setAt(out, ps.head, Kd.KTop)
              ps = ps.tail
            out = setAt(out, as.head.k, Kd.KTop)
            as = as.tail
        case _ => ()
      rest = rest.tail
    out

  private def joinAllAtDepth(brs: List[(Int, List[Kd])], depth: Int, n: Int): List[Kd] =
    var acc = botState(n)
    var rest = brs
    while rest.nonEmpty do
      val (d, s) = rest.head
      if d == depth then acc = joinSt(acc, s)
      rest = rest.tail
    acc

  private def deeper(brs: List[(Int, List[Kd])]): List[(Int, List[Kd])] =
    var out: List[(Int, List[Kd])] = Nil
    var rest = brs
    while rest.nonEmpty do
      val (d, s) = rest.head
      if d > 0 then out = (d - 1, s) :: out
      rest = rest.tail
    out.reverse

  /** Analyse and rewrite one instruction list.
    *
    * Returns the rewritten list, the state where control falls off the end, and the states that
    * BRANCHED OUT, each tagged with how many more regions it still has to leave. Depths are
    * relative to this list's own innermost enclosing region, which is why every region case below
    * consumes depth 0 and re-emits the rest one lower — the same arithmetic `Exec` does with
    * `Signal.Branch(d - 1)`.
    */
  private def runList(m: Module, body: List[Instr], in: List[Kd])
      : (List[Instr], List[Kd], List[(Int, List[Kd])]) =
    val n = in.length
    var cur = in
    var outInstrs: List[Instr] = Nil
    var brs: List[(Int, List[Kd])] = Nil
    var rest = body
    while rest.nonEmpty do
      val (i2, cur2, brs2) = step(m, rest.head, cur, n)
      outInstrs = i2 :: outInstrs
      brs = brs ++ brs2
      cur = cur2
      rest = rest.tail
    (outInstrs.reverse, cur, brs)

  private def step(m: Module, i: Instr, cur: List[Kd], n: Int)
      : (Instr, List[Kd], List[(Int, List[Kd])]) = i match

    case Instr.Const(d, k) => (i, setAt(cur, d, kindOfLit(m.consts(k))), Nil)
    case Instr.Move(d, a)  => (i, setAt(cur, d, at(cur, a)), Nil)

    case Instr.Un(op, kind, d, a) =>
      val ka = at(cur, a)
      op match
        // `Not` is BOOLEAN negation — `Exec` runs it through `truthy` and never looks at `kind`.
        // Writing a numeric kind on it would be a claim about an operand nothing treats as a
        // number, so it is left exactly as the front emitted it.
        case UnOp.Not  => (i, setAt(cur, d, Kd.KTop), Nil)
        case UnOp.Neg  =>
          val k2 = if kind == NumKind.Dyn && isNum(ka) then numKindOf(ka) else kind
          (Instr.Un(op, k2, d, a), setAt(cur, d, if isNum(ka) then ka else Kd.KTop), Nil)
        case UnOp.BNot =>
          val k2 = if kind == NumKind.Dyn && ka == Kd.KInt then NumKind.I64 else kind
          (Instr.Un(op, k2, d, a), setAt(cur, d, if ka == Kd.KInt then Kd.KInt else Kd.KTop), Nil)

    case Instr.Bin(op, kind, d, a, b) =>
      val ka = at(cur, a)
      val kb = at(cur, b)
      // BOTH sides, and the same kind. `Exec.binOp` matches a `VStr` on EITHER side before any
      // numeric arm (`1 + "x"` is the string `"1x"` on both lanes), and coerces a `VChar` to an
      // integer before them too — so "one side is an int" proves nothing about which arm fires.
      // Two proved numbers of the same kind cannot reach a string arm, which is what makes the
      // rewrite safe rather than merely likely.
      val proved = isNum(ka) && ka == kb
      val k2 = if kind == NumKind.Dyn && proved then numKindOf(ka) else kind
      // A comparison yields a boolean whatever it compared; arithmetic yields the operand kind.
      val res = if isCompare(op) then Kd.KTop else if proved then ka else Kd.KTop
      (Instr.Bin(op, k2, d, a, b), setAt(cur, d, res), Nil)

    // ── regions ───────────────────────────────────────────────────────────────────────────────
    //
    // `Br 0` inside a `Block` or an `If` leaves it; inside a `Loop` it goes back to the TOP. That
    // asymmetry is WebAssembly's rule, it is what `Exec` implements, and getting it backwards would
    // make this pass unsound rather than imprecise — hence each case names which way it read it.

    case Instr.Block(b) =>
      val (nb, ob, bs) = runList(m, b, cur)
      // Depth 0 exits the block and joins the fallthrough.
      val out = joinSt(ob, joinAllAtDepth(bs, 0, n))
      (Instr.Block(nb), out, deeper(bs))

    case Instr.If(c, t, e) =>
      val (nt, ot, bt) = runList(m, t, cur)
      val (ne, oe, be) = runList(m, e, cur)
      val bs = bt ++ be
      val out = joinSt(joinSt(ot, oe), joinAllAtDepth(bs, 0, n))
      (Instr.If(c, nt, ne), out, deeper(bs))

    case Instr.Loop(b) =>
      // Depth 0 is the BACK EDGE, so it feeds the loop's entry rather than its exit, and the entry
      // state has to be a fixpoint. The lattice is two high (KBot below a numeric kind below KTop),
      // so a register can change at most twice and this converges in a couple of rounds; the guard
      // exists so that "converges quickly" is not load-bearing. Falling off the end EXITS — a loop
      // that repeats does so through a `Br`, never by running out of instructions.
      var entry = cur
      var settled = false
      var rounds = 0
      var lastBody: List[Instr] = b
      var lastOut = cur
      var lastBrs: List[(Int, List[Kd])] = Nil
      while !settled do
        val (nb, ob, bs) = runList(m, b, entry)
        lastBody = nb
        lastOut = ob
        lastBrs = bs
        val back = joinAllAtDepth(bs, 0, n)
        val next = joinSt(entry, back)
        rounds = rounds + 1
        if next == entry then settled = true
        else if rounds > n + 3 then
          // Unreachable with a lattice this shape; if it ever happens, the safe answer is "nothing
          // is proved" rather than a loop that does not terminate.
          entry = entryState(n)
          val (nb2, ob2, bs2) = runList(m, b, entry)
          lastBody = nb2
          lastOut = ob2
          lastBrs = bs2
          settled = true
        else entry = next
      (Instr.Loop(lastBody), lastOut, deeper(lastBrs))

    case Instr.Switch(scrut, arms, default) =>
      var newArms: List[SwitchArm] = Nil
      var outs = botState(n)
      var bs: List[(Int, List[Kd])] = Nil
      var rest = arms
      while rest.nonEmpty do
        val a = rest.head
        val (nb, ob, ab) = runList(m, a.body, cur)
        newArms = SwitchArm(a.tag, nb) :: newArms
        outs = joinSt(outs, ob)
        bs = bs ++ ab
        rest = rest.tail
      val (nd, od, db) = runList(m, default, cur)
      bs = bs ++ db
      outs = joinSt(outs, od)
      val out = joinSt(outs, joinAllAtDepth(bs, 0, n))
      (Instr.Switch(scrut, newArms.reverse, nd), out, deeper(bs))

    // A branch LEAVES: nothing after it in this list is reachable, so the state that continues is
    // bottom and the state that branched is recorded against its depth.
    case Instr.Br(d)      => (i, botState(n), List((d, cur)))
    case Instr.BrIf(c, d) => (i, cur, List((d, cur)))

    case Instr.Ret(_)       => (i, botState(n), Nil)
    case Instr.TailCall(_, _) => (i, botState(n), Nil)

    // ── the two regions that can be entered where this pass cannot see ────────────────────────

    case Instr.Try(d, b, exn, h) =>
      val (nb, ob, bbs) = runList(m, b, cur)
      // An exception can arrive at ANY instruction of the body, so the handler's real input is some
      // state part-way through it. `topAllWritten` is above every one of those by construction.
      val hin = setAt(topAllWritten(cur, b), exn, Kd.KTop)
      val (nh, oh, hbs) = runList(m, h, hin)
      val bs = bbs ++ hbs
      val out = joinSt(joinSt(ob, oh), joinAllAtDepth(bs, 0, n))
      (Instr.Try(d, nb, exn, nh), out, deeper(bs))

    case Instr.Handle(d, b, arms) =>
      // An arm runs when a `Perform` anywhere under the body reaches it, and its binders are
      // written by the runtime rather than by an instruction. Same conservative input as `Try`,
      // widened over the arms' own bodies too.
      var inner = b
      var ra = arms
      while ra.nonEmpty do
        inner = inner ++ ra.head.body
        ra = ra.tail
      val ain = topAllWritten(cur, inner)
      val (nb, ob, bbs) = runList(m, b, ain)
      var newArms: List[HandlerArm] = Nil
      var outs = ob
      var bs = bbs
      var rest = arms
      while rest.nonEmpty do
        val a = rest.head
        var armIn = ain
        var ps = a.params
        while ps.nonEmpty do
          armIn = setAt(armIn, ps.head, Kd.KTop)
          ps = ps.tail
        armIn = setAt(armIn, a.k, Kd.KTop)
        val (nab, oab, abs) = runList(m, a.body, armIn)
        newArms = HandlerArm(a.op, a.params, a.k, nab) :: newArms
        outs = joinSt(outs, oab)
        bs = bs ++ abs
        rest = rest.tail
      val out = joinSt(setAt(outs, d, Kd.KTop), joinAllAtDepth(bs, 0, n))
      (Instr.Handle(d, nb, newArms.reverse), out, deeper(bs))

    // ── everything else writes a value this pass cannot describe ──────────────────────────────
    //
    // `Tag` and `ArrLen` are the two exceptions worth naming: both produce an integer by
    // construction, and both feed arithmetic in real programs (a tag compared against a constant,
    // a length used as a loop bound).
    case Instr.Tag(d, _)    => (i, setAt(cur, d, Kd.KInt), Nil)
    case Instr.ArrLen(d, _) => (i, setAt(cur, d, Kd.KInt), Nil)

    case other =>
      val d = dstOf(other)
      (other, if d >= 0 then setAt(cur, d, Kd.KTop) else cur, Nil)

  // ── SSC3-J1c — which registers can live in a `long` bank ───────────────────────────────────────
  //
  // The specializer proves a KIND per instruction; this turns that into a per-REGISTER decision, so
  // the executor can keep a value unboxed for its whole life instead of boxing every result.
  // `specs/ssc3-jit.md` §9 has the measurement that named it: `arith-loop` allocates one frame and
  // still reclaims ~4 GB, all of it `Value.VInt`.
  //
  // MONOTONE DISQUALIFICATION, not inference. Every register starts a candidate and is removed the
  // moment anything writes something that is not a proved integer. The fixpoint therefore only ever
  // shrinks the set, which is what makes "sound" checkable by reading one direction: no rule here
  // can ADD a register, so no register can be marked long-bank because a rule was too clever.
  //
  // A COMPARISON WRITES A BOOLEAN. `Bin(Lt, I64, d, …)` carries `I64` and that names its OPERANDS,
  // not its result — `d` holds `VBool`. Missing this would put a boolean in a long slot and read it
  // back as a number, and it is the one arm of this analysis where the field's meaning inverts.

  /** Registers whose every writer stores a proved `I64`, so they never need to be boxed.
    *
    * Parameters are excluded: they arrive as a `List[Value]` from the caller, so unboxing them is a
    * calling-convention change rather than a frame change. Named here because it is why a
    * call-heavy program is expected not to move.
    */
  def longBanks(m: Module, f: Func): Array[Boolean] =
    val ok = new Array[Boolean](f.nregs)
    val written = new Array[Boolean](f.nregs)
    var i = f.nparams
    while i < f.nregs do
      ok(i) = true
      i = i + 1
    // Repeat until nothing changes: `Move` copies a register's status, so one pass could accept a
    // register whose source is disqualified later in the list.
    var changed = true
    var rounds = 0
    while changed && rounds <= f.nregs + 2 do
      changed = false
      rounds = rounds + 1
      var is = Instr.flatten(f.body)
      while is.nonEmpty do
        val ins = is.head
        val d = writesReg(ins)
        if d >= 0 && d < f.nregs then
          written(d) = true
          if ok(d) && !writesInt(m, ins, ok) then
            ok(d) = false
            changed = true
        // A handler's binders are written by the runtime, not by an instruction, so they are
        // disqualified explicitly rather than by falling through `writesInt`.
        // ── THE REGIONS THE EXECUTOR DELEGATES ────────────────────────────────────────────────
        //
        // `Exec.stepBanked` answers `Block`, `Loop` and `If` itself and carries the banks into
        // them. `Switch`, `Try` and `Handle` it hands to the shared interpreter, which knows
        // nothing about banks and writes `regs(d)` — so a long-bank register written INSIDE one of
        // those would leave its `long` slot stale and the next read would get an old value.
        //
        // Found by reading rather than by a failing test, and the test would not have found it:
        // registers written inside a `Try` are already never proved, because the specializer treats
        // a handler's entry state conservatively, and a corpus `Switch` writing an integer local
        // simply did not exist among the fixtures. **A hazard that only one of three regions
        // happens to avoid, for an unrelated reason, is not covered.** Disqualifying all three here
        // makes it a property of this analysis instead of a coincidence of another one.
        ins match
          case Instr.Switch(_, _, _) | Instr.Try(_, _, _, _) | Instr.Handle(_, _, _) =>
            var inner = Instr.flatten(Instr.children(ins))
            while inner.nonEmpty do
              val w = dstOf(inner.head)
              if w >= 0 && w < f.nregs && ok(w) then { ok(w) = false; changed = true }
              inner = inner.tail
          case _ => ()
        ins match
          case Instr.Handle(_, _, arms) =>
            var as = arms
            while as.nonEmpty do
              var ps = as.head.params
              while ps.nonEmpty do
                if ps.head >= 0 && ps.head < f.nregs && ok(ps.head) then { ok(ps.head) = false; changed = true }
                ps = ps.tail
              if as.head.k >= 0 && as.head.k < f.nregs && ok(as.head.k) then { ok(as.head.k) = false; changed = true }
              as = as.tail
          case Instr.Try(_, _, exn, _) =>
            if exn >= 0 && exn < f.nregs && ok(exn) then { ok(exn) = false; changed = true }
          case _ => ()
        is = is.tail
    // A register nothing writes holds `unit` for the whole function; it is not an integer and must
    // not get a slot that reads back as 0.
    i = 0
    while i < f.nregs do
      if !written(i) then ok(i) = false
      i = i + 1

    // ── GROUNDING, and it closes the hole the optimistic start opens ──────────────────────────
    //
    // The loop above starts every register a candidate and only ever removes, which is what makes
    // it easy to read as sound — but it lets a CYCLE justify itself. `r = r + 1` with no
    // initialiser has one writer, that writer is `Bin(I64)` over `r` and a constant, and `ok(r)`
    // was assumed true to begin with, so it survives. The register is never actually assigned, so
    // it holds `unit` at entry while the long bank holds 0, and the two lanes disagree.
    //
    // This is the same trap `entryState` names and refuses for the flow-sensitive analysis, walked
    // into one function later. The fix is a SECOND fixpoint in the other direction: a register is
    // GROUNDED when some writer traces back to an integer constant, and a candidate that is not
    // grounded is dropped. Least fixpoint, so a cycle with no constant in it never starts.
    val grounded = new Array[Boolean](f.nregs)
    var growing = true
    while growing do
      growing = false
      var gs = Instr.flatten(f.body)
      while gs.nonEmpty do
        val ins = gs.head
        val d = dstOf(ins)
        if d >= 0 && d < f.nregs && !grounded(d) then
          val g = ins match
            case Instr.Const(_, k) => kindOfLit(m.consts(k)) == Kd.KInt
            case Instr.Move(_, a)  => a >= 0 && a < f.nregs && grounded(a)
            case Instr.Bin(op, kind, _, a, b) =>
              kind == NumKind.I64 && !isCompare(op) &&
                a >= 0 && a < f.nregs && grounded(a) && b >= 0 && b < f.nregs && grounded(b)
            case _ => false
          if g then { grounded(d) = true; growing = true }
        gs = gs.tail
    i = 0
    while i < f.nregs do
      if !grounded(i) then ok(i) = false
      i = i + 1
    ok

  private def writesReg(i: Instr): Int = dstOf(i)

  /** Does this instruction store a proved integer into its destination?
    *
    * DELIBERATELY NARROWER THAN WHAT IS PROVABLE. `Un(Neg, I64, …)`, `Tag` and `ArrLen` all store
    * integers too and are all excluded, because the executor's invariant is stronger than "this
    * register holds an integer": it is **only `Const`, `Move` and `Bin` may write a long-bank
    * register**, and those three are exactly the opcodes `Exec.stepBanked` answers itself. Every
    * other opcode reaches the shared interpreter, which knows nothing about banks.
    *
    * That turns a property that would have to be argued across forty instruction arms into one a
    * reader checks by looking at this list and at the three cases of `stepBanked`. The cost is
    * precision on `Un`/`Tag`/`ArrLen`; the alternative is an invariant nobody can verify. */
  private def writesInt(m: Module, i: Instr, ok: Array[Boolean]): Boolean = i match
    case Instr.Const(_, k) => kindOfLit(m.consts(k)) == Kd.KInt
    // A copy is an integer exactly when its source is one, which is what makes this a fixpoint.
    case Instr.Move(_, a)  => a >= 0 && a < ok.length && ok(a)
    // BOTH OPERANDS MUST THEMSELVES BE LONG-BANK, not merely `kind == I64`, and this is a
    // correctness rule that a gate had to teach me.
    //
    // `kind` is a CLAIM the IR makes, and `v3/tests/jit/wrong-kind.ssir` is a module where the
    // claim is a lie: two STRINGS added under an `i64` annotation. Trusting the field alone made
    // `d` long-bank, the executor's fallback correctly produced `VStr("ab")` and stored it in the
    // Value slot, the `long` slot stayed unwritten — and the next sync overwrote the string with
    // `VInt(0)`. The program printed `0` where every other lane prints `ab`.
    //
    // Requiring the operands closes the set under itself: a long-bank register is written only by
    // an integer constant, by a copy of a long-bank register, or by arithmetic on two of them. It
    // is grounded in `LInt` constants and therefore CANNOT hold a non-integer, whatever the `kind`
    // field says. The executor's invariant stops depending on the IR telling the truth.
    case Instr.Bin(op, kind, _, a, b) =>
      kind == NumKind.I64 && !isCompare(op) &&
        a >= 0 && a < ok.length && ok(a) && b >= 0 && b < ok.length && ok(b)
    case _ => false

  /** How many registers across the module the executor could keep unboxed, and out of how many. The
    * number `v3/jit-gate.sh --banks` reports: it is what says whether a change to this analysis
    * proved MORE or merely proved differently. */
  def bankCensus(m: Module): (Int, Int) =
    var total = 0
    var longs = 0
    var fs = m.funcs
    while fs.nonEmpty do
      val f = fs.head
      val b = longBanks(m, f)
      var i = 0
      while i < b.length do
        total = total + 1
        if b(i) then longs = longs + 1
        i = i + 1
      fs = fs.tail
    (longs, total)

  /** How many `Bin`/`Un` instructions carry each kind. The number `v3/jit-gate.sh` reports, and the
    * number that says whether a change to this pass proved MORE or merely proved DIFFERENTLY. */
  def census(m: Module): (Int, Int, Int) =
    var dyn = 0
    var i64 = 0
    var f64 = 0
    var fs = m.funcs
    while fs.nonEmpty do
      var is = Instr.flatten(fs.head.body)
      while is.nonEmpty do
        is.head match
          case Instr.Bin(_, k, _, _, _) =>
            if k == NumKind.Dyn then dyn = dyn + 1
            else if k == NumKind.I64 then i64 = i64 + 1
            else if k == NumKind.F64 then f64 = f64 + 1
          case Instr.Un(op, k, _, _) =>
            // `Not` is boolean and its kind is never written by this pass; counting it as an
            // unproved `Dyn` would make the census say there is work left where there is none.
            if op != UnOp.Not then
              if k == NumKind.Dyn then dyn = dyn + 1
              else if k == NumKind.I64 then i64 = i64 + 1
              else if k == NumKind.F64 then f64 = f64 + 1
          case _ => ()
        is = is.tail
      fs = fs.tail
    (dyn, i64, f64)

/** A driver for the pass, so `v3/jit-gate.sh` can run it.
  *
  * It is HERE rather than a subcommand of `v3/src/Main.scala` for a coordination reason and not a
  * design one: that file is held by another claim (`ssc3-cps-split`). It moves to `Main.scala` as
  * `ssc3 ir --specialize` when the file frees up; until then the gate invokes this class directly
  * on the same class directory the driver built.
  *
  *   java -cp <classes> ssc3.SpecializeMain [--census] <file.ssc|file.ssir>
  */
object SpecializeMain:

  def main(args: Array[String]): Unit =
    var census = false
    var path = ""
    var i = 0
    while i < args.length do
      if args(i) == "--census" then census = true else path = args(i)
      i = i + 1
    if path == "" then
      Console.err.println("usage: ssc3.SpecializeMain [--census] <file.ssc|file.ssir>")
      System.exit(2)
    val src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), "UTF-8")
    val m0 =
      if path.endsWith(".ssir") then Text.read(src)
      else Lower.programOf(Loader.merge(Loader.closure(path)), Source.blockEnds(src))
    // Invariant I-4 applies to this pass too: it does not get to run on IR nobody checked, and the
    // output is verified as well — a rewrite that broke a validation rule would otherwise only
    // surface as a wrong answer much later.
    Verify.module(m0) match
      case Some(e) => Console.err.println("ssc3: refusing to specialize invalid IR: " + e.render); System.exit(1)
      case None    => ()
    val m1 = Specialize.module(m0)
    Verify.module(m1) match
      case Some(e) => Console.err.println("ssc3: THE SPECIALIZER PRODUCED INVALID IR: " + e.render); System.exit(1)
      case None    => ()
    if census then
      val (d0, i0, f0) = Specialize.census(m0)
      val (d1, i1, f1) = Specialize.census(m1)
      println("before: dyn " + d0 + "  i64 " + i0 + "  f64 " + f0)
      println("after:  dyn " + d1 + "  i64 " + i1 + "  f64 " + f1)
      // SSC3-J1c. Reported off the SPECIALIZED module, because a register can only be proved
      // integer once the kinds are filled in — on `m0` this is 0 by construction and would read as
      // "the analysis found nothing" rather than "it was asked too early".
      val (lb, tot) = Specialize.bankCensus(m1)
      println("banks:  long " + lb + " of " + tot + " register(s)")
    else print(Text.write(m1))
