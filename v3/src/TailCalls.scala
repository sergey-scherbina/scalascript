package ssc3

// A pass over SSC IR: turn calls in TAIL POSITION into `TailCall`.
//
// This is the first real optimisation pass, and it is the argument for the IR's shape made concrete
// — it is a rewrite of DATA. Nothing here parses, nothing here knows about `.ssc`, and the input and
// the output are the same type, so the pass can be applied, skipped, or applied twice without any
// other stage caring.
//
// It matters because `TailCall` is the one thing only v3's own executor honours: the trampoline
// reuses the frame, so `isEven(100000)` runs in constant stack instead of overflowing.

object TailCalls:

  def apply(m: Module): Module =
    val withTails = m.copy(funcs = m.funcs.map(f => f.copy(body = tailify(f.body))))
    val merged = mergeGroups(withTails)
    merged.copy(funcs = merged.funcs.zipWithIndex.map((f, i) => selfLoop(f, i)))

  // ── mutual tail recursion ───────────────────────────────────────────────────
  //
  // A group of functions that tail-call EACH OTHER is folded into one function with a SELECTOR
  // parameter: the loop dispatches on it, and an internal tail call becomes "set the selector, set
  // the arguments, branch to the top". It is the self-call rewrite with a group instead of one
  // function — one mechanism, generalised, rather than a second one beside it.
  //
  // The alternative was a TRAMPOLINE: every `TailCall` returns a marker and every call site loops
  // while it sees one. That fixes mutual recursion by taxing EVERY call in the program, including
  // the ones that are never recursive, and it needs a marker value that must never leak into user
  // data. This transformation costs nothing at a call that is not in a group.
  //
  // It is in the IR, so both lanes get it: a backend cannot opt out of a loop.

  private def tailEdges(m: Module, i: Int): List[Int] =
    def go(body: List[Instr]): List[Int] = body.flatMap { x => x match
      case Instr.TailCall(g, _) => List(g)
      case other                => go(Instr.children(other))
    }
    go(m.funcs(i).body).distinct

  /** Mutually reachable via tail calls, and bigger than one. Computed by closure rather than by a
    * named algorithm because the graph is one node per function — clarity is worth more here than
    * asymptotics. */
  private def groups(m: Module): List[List[Int]] =
    val n = m.funcs.length
    val edges = (0 until n).toList.map(i => tailEdges(m, i))
    def reaches(a: Int, b: Int): Boolean =
      var seen: List[Int] = Nil
      var todo = List(a)
      var found = false
      while todo.nonEmpty && !found do
        val h = todo.head
        todo = todo.tail
        if !seen.contains(h) then
          seen = h :: seen
          if edges(h).contains(b) then found = true else todo = todo ++ edges(h)
      found
    var out: List[List[Int]] = Nil
    var taken: List[Int] = Nil
    (0 until n).toList.foreach { i =>
      if !taken.contains(i) then
        val g = (0 until n).toList.filter(j => j != i && reaches(i, j) && reaches(j, i))
        if g.nonEmpty then
          val whole = (i :: g).sorted
          out = out :+ whole
          taken = taken ++ whole
    }
    out

  /** Shift every REGISTER reference by `d`, and nothing else.
    *
    * The risk this comment exists for: an instruction's other integer fields are indices into the
    * constant pool, the type table, the globals or the function table, and shifting one of those
    * would be silent — a wrong constant or a wrong callee, with no verifier complaint because the
    * index is still in range. Each case below is written out so the distinction is visible.
    */
  private def shift(body: List[Instr], d: Int): List[Instr] = body.map { i => i match
    case Instr.Const(a, k)         => Instr.Const(a + d, k)
    case Instr.Move(a, b)          => Instr.Move(a + d, b + d)
    case Instr.Un(o, k, a, b)      => Instr.Un(o, k, a + d, b + d)
    case Instr.Bin(o, k, a, b, c)  => Instr.Bin(o, k, a + d, b + d, c + d)
    case Instr.Block(b)            => Instr.Block(shift(b, d))
    case Instr.Loop(b)             => Instr.Loop(shift(b, d))
    case Instr.If(c, t, e)         => Instr.If(c + d, shift(t, d), shift(e, d))
    case Instr.Br(k)               => Instr.Br(k)
    case Instr.BrIf(c, k)          => Instr.BrIf(c + d, k)
    case Instr.Call(a, f, as)      => Instr.Call(a + d, f, as.map(_ + d))
    case Instr.CallV(a, c, as)     => Instr.CallV(a + d, c + d, as.map(_ + d))
    case Instr.MkClos(a, f, cs)    => Instr.MkClos(a + d, f, cs.map(_ + d))
    case Instr.TailCall(f, as)     => Instr.TailCall(f, as.map(_ + d))
    case Instr.Ret(a)              => Instr.Ret(a + d)
    case Instr.MkData(a, t, as)    => Instr.MkData(a + d, t, as.map(_ + d))
    case Instr.Field(a, b, t, ix)  => Instr.Field(a + d, b + d, t, ix)
    case Instr.Tag(a, b)           => Instr.Tag(a + d, b + d)
    case Instr.Switch(s, arms, df) =>
      Instr.Switch(s + d, arms.map(a => SwitchArm(a.tag, shift(a.body, d))), shift(df, d))
    case Instr.NewArr(a, b)        => Instr.NewArr(a + d, b + d)
    case Instr.ArrGet(a, b, c)     => Instr.ArrGet(a + d, b + d, c + d)
    case Instr.ArrSet(a, b, c)     => Instr.ArrSet(a + d, b + d, c + d)
    case Instr.ArrLen(a, b)        => Instr.ArrLen(a + d, b + d)
    case Instr.GlobGet(a, g)       => Instr.GlobGet(a + d, g)
    case Instr.GlobSet(g, a)       => Instr.GlobSet(g, a + d)
    case Instr.Perform(a, o, as)   => Instr.Perform(a + d, o, as.map(_ + d))
    case Instr.Handle(a, b, arms)  =>
      Instr.Handle(a + d, shift(b, d), arms.map(x => HandlerArm(x.op, shift(x.body, d))))
    case Instr.Resume(a, b, c)     => Instr.Resume(a + d, b + d, c + d)
    case Instr.Invoke(a, nm, r, as) => Instr.Invoke(a + d, nm, r + d, as.map(_ + d))
    case Instr.Prim(a, p, as)      => Instr.Prim(a + d, p, as.map(_ + d))
    case Instr.Try(a, b, x, h)     => Instr.Try(a + d, shift(b, d), x + d, shift(h, d))
  }

  private def mergeGroups(m0: Module): Module =
    val gs = groups(m0)
    if gs.isEmpty then m0
    else
      var m = m0
      gs.foreach { g => m = mergeOne(m, g) }
      m

  private def constFor(consts: List[Lit], v: Long): (Int, List[Lit]) =
    val l = Lit.LInt(v)
    val i = consts.indexOf(l)
    if i >= 0 then (i, consts) else (consts.length, consts :+ l)

  private def mergeOne(m: Module, g: List[Int]): Module =
    val members = g.map(i => m.funcs(i))
    val maxArity = members.map(f => f.nparams).max
    // Layout: r0 = selector, r1..rMax = the shared arguments, then each member's own frame at its
    // own base, then the temporaries a re-entry writes through.
    val argBase = 1
    var bases: List[(Int, Int)] = Nil
    var next = argBase + maxArity
    g.zip(members).foreach { (idx, f) =>
      bases = bases :+ (idx, next)
      next = next + f.nregs
    }
    val tempBase = next
    val total = tempBase + maxArity
    val mergedIdx = m.funcs.length
    def baseOf(i: Int): Int = bases.find((k, _) => k == i).map((_, v) => v).get

    // Dispatch is an IF-CHAIN on the selector, NOT a `Switch`. `Switch` matches a CONSTRUCTOR TAG
    // and takes the default for anything that is not `Data` — so an integer selector fell straight
    // through and the function returned the selector itself (0/1/0 instead of true/false/true).
    // The instruction was the wrong one for the job, and it failed quietly.
    // Selector constants for EVERY member, created before the chain so an internal tail call can
    // set the selector as well as the arguments.
    var consts0 = m.consts
    var selConst: List[(Int, Int)] = Nil
    g.foreach { idx =>
      val (k, c) = constFor(consts0, idx.toLong)
      consts0 = c
      selConst = selConst :+ (idx, k)
    }
    var chain: List[Instr] = List(Instr.Ret(0))
    // Each arm sits at its OWN depth: the chain nests, so the p-th member is inside p+1 `If`s plus
    // the `Loop`, and `Br(d)` targets the (d+1)-th enclosing region — so reaching the loop from
    // there is `Br(p+1)`. Both the per-arm part and the off-by-one were caught by the VERIFIER
    // rather than by a wrong answer, which is what a mandatory structural check is for: a `br` past
    // its region is a jump to nowhere that testing finds only by luck.
    val positions = g.zip(members).zipWithIndex.map { case ((idx, f), p) => (idx, f, p) }
    positions.reverse.foreach { (idx, f, pos) =>
      val b = baseOf(idx)
      // The member's parameters are copied in from the shared slots, then its own body runs in its
      // own register range — which is why every register is shifted rather than renumbered by hand:
      // one arithmetic rule instead of a mapping to keep in step.
      val prologue = (0 until f.nparams).toList.map(k => Instr.Move(b + k, argBase + k))
      val armBody = prologue ++ reenter(shift(f.body, b), g, argBase, tempBase, pos + 1, selConst, total)
      val k = selConst.find((a, _) => a == idx).map((_, v) => v).get
      val cReg = total
      val tReg = total + 1
      chain = List(Instr.Const(cReg, k), Instr.Bin(BinOp.Eq, NumKind.Dyn, tReg, 0, cReg),
                   Instr.If(tReg, armBody, chain))
    }
    // The selector IS the member's original function index, so a stub passes the same number the
    // dispatch compares — there is no second numbering to keep in step with the first.
    val merged = Func("__group_" + members.map(f => f.name).mkString("_"), 1 + maxArity, total + 2,
                      List(Instr.Loop(chain), Instr.Ret(0)))

    // Each original becomes a STUB that enters the group. External callers are untouched: they call
    // the name they always called, and the group is an implementation detail below it.
    var consts = consts0
    var funcs = m.funcs
    g.zip(members).foreach { (idx, f) =>
      val (kSel, c1) = constFor(consts, idx.toLong)
      val (kPad, c2) = constFor(c1, 0L)
      consts = c2
      val selReg = f.nparams
      val padReg = f.nparams + 1
      val resReg = f.nparams + 2
      val args = (0 until f.nparams).toList ++ List.fill(maxArity - f.nparams)(padReg)
      funcs = funcs.updated(idx, Func(f.name, f.nparams, f.nparams + 3, List(
        Instr.Const(selReg, kSel),
        Instr.Const(padReg, kPad),
        Instr.Call(resReg, mergedIdx, selReg :: args),
        Instr.Ret(resReg))))
    }
    m.copy(consts = consts, funcs = funcs :+ merged)

  /** An internal tail call re-enters the loop; an external one stays a call. */
  private def reenter(body: List[Instr], g: List[Int], argBase: Int, tempBase: Int, depth: Int,
                      selConst: List[(Int, Int)], selTmp: Int): List[Instr] =
    body.flatMap { i => i match
      case Instr.TailCall(h, as) if g.contains(h) =>
        // THE SELECTOR MUST MOVE TOO. Setting only the arguments re-enters the SAME arm, so
        // `isOdd(1)` looped in isOdd instead of handing off to isEven and answered `false`. My
        // three-line probe passed by luck — the parity happened to come out right — and the corpus
        // case caught it because it calls `isOdd(1)`, the one input where the two differ.
        val k = selConst.find((a, _) => a == h).map((_, v) => v).get
        val toTemp = as.zipWithIndex.map((a, j) => Instr.Move(tempBase + j, a))
        val toArg = as.indices.toList.map(j => Instr.Move(argBase + j, tempBase + j))
        (toTemp ++ toArg :+ Instr.Const(selTmp, k)) ++ List(Instr.Move(0, selTmp), Instr.Br(depth))
      case Instr.Block(b) => List(Instr.Block(reenter(b, g, argBase, tempBase, depth + 1, selConst, selTmp)))
      case Instr.Loop(b)  => List(Instr.Loop(reenter(b, g, argBase, tempBase, depth + 1, selConst, selTmp)))
      case Instr.If(c, t, e) =>
        List(Instr.If(c, reenter(t, g, argBase, tempBase, depth + 1, selConst, selTmp),
                         reenter(e, g, argBase, tempBase, depth + 1, selConst, selTmp)))
      case Instr.Switch(s, arms, d) =>
        List(Instr.Switch(s, arms.map(a => SwitchArm(a.tag, reenter(a.body, g, argBase, tempBase, depth + 1, selConst, selTmp))),
                          reenter(d, g, argBase, tempBase, depth + 1, selConst, selTmp)))
      case Instr.Try(d0, b, x, h) =>
        List(Instr.Try(d0, reenter(b, g, argBase, tempBase, depth + 1, selConst, selTmp), x,
                       reenter(h, g, argBase, tempBase, depth + 1, selConst, selTmp)))
      case other => List(other)
    }

  /** A SELF tail call becomes a LOOP.
    *
    * `TailCall` is honoured by v3's own executor and not by the v2 bridge, because v2 has no TCO.
    * Turning self-recursion into a loop fixes it in the IR instead, so BOTH lanes get constant
    * stack — a backend cannot opt out of a loop. Mutual recursion still needs a trampoline and
    * still only works on the executor; that limit is real and stays named.
    *
    * The arguments are evaluated into TEMPORARIES before the parameters are overwritten. Assigning
    * them in place is correct only until a call swaps them — `go(b, a)` would set `a` from the new
    * `a`, and the bug would appear exactly once, in whichever program first swapped arguments.
    */
  private def selfLoop(f: Func, self: Int): Func =
    if !hasSelfTail(f.body, self) then f
    else
      val temps = (0 until f.nparams).toList.map(i => f.nregs + i)
      val body = rewrite(f.body, self, f.nparams, temps, 0)
      // The loop can never fall through — every path out of it is a `Ret` — but rule 5 is a
      // STRUCTURAL check and cannot know that, so the body ends with an unreachable terminator
      // rather than with the verifier weakened to accept a body that merely looks fine.
      Func(f.name, f.nparams, f.nregs + f.nparams, List(Instr.Loop(body), Instr.Ret(0)))

  private def hasSelfTail(body: List[Instr], self: Int): Boolean =
    body.exists { i => i match
      case Instr.TailCall(g, _) => g == self
      case other                => hasSelfTail(Instr.children(other), self)
    }

  /** `depth` counts the regions between here and the loop, which is what a `Br` has to clear. */
  private def rewrite(body: List[Instr], self: Int, nparams: Int, temps: List[Int], depth: Int): List[Instr] =
    body.flatMap { i => i match
      case Instr.TailCall(g, args) if g == self =>
        val toTemp = args.zipWithIndex.map((a, k) => Instr.Move(temps(k), a))
        val toParam = (0 until nparams).toList.map(k => Instr.Move(k, temps(k)))
        toTemp ++ toParam :+ Instr.Br(depth)
      case Instr.Block(b) => List(Instr.Block(rewrite(b, self, nparams, temps, depth + 1)))
      case Instr.Loop(b)  => List(Instr.Loop(rewrite(b, self, nparams, temps, depth + 1)))
      case Instr.If(c, t, e) =>
        List(Instr.If(c, rewrite(t, self, nparams, temps, depth + 1),
                         rewrite(e, self, nparams, temps, depth + 1)))
      case Instr.Switch(s, arms, d) =>
        List(Instr.Switch(s, arms.map(a => SwitchArm(a.tag, rewrite(a.body, self, nparams, temps, depth + 1))),
                          rewrite(d, self, nparams, temps, depth + 1)))
      case Instr.Try(d, b, x, h) =>
        List(Instr.Try(d, rewrite(b, self, nparams, temps, depth + 1), x,
                       rewrite(h, self, nparams, temps, depth + 1)))
      case other => List(other)
    }

  /** A body ending in `Ret(r)` is in tail position; the question is only what produces `r`.
    *
    * Two shapes are rewritten, and the second is what makes this worth having:
    *
    *   … Call(r, f, args); Ret(r)          ->  … TailCall(f, args)
    *   … If(c, t, e); Ret(r)               ->  … If(c, tailify(t :+ Ret r), tailify(e :+ Ret r))
    *
    * Pushing the `Ret` INTO the arms is what reaches `if n == 0 then true else isOdd(n - 1)`, which
    * is the shape mutual recursion actually takes. Without it only a bare trailing call qualifies,
    * and almost nothing does.
    */
  def tailify(body: List[Instr]): List[Instr] =
    if body.isEmpty then body
    else
      body.last match
        case Instr.Ret(r) =>
          val init = body.dropRight(1)
          if init.isEmpty then body
          else
            init.last match
              case Instr.Call(d, f, args) if d == r =>
                init.dropRight(1) :+ Instr.TailCall(f, args)
              // `Call(d, …); Move(r, d); Ret(r)` — the shape the lowering ACTUALLY emits, because a
              // branch's result register is allocated separately from the call's. Matching only
              // `Call(r, …); Ret(r)` looked right and fired on nothing: the IR is what decides
              // which pattern a pass needs, not the source it came from.
              case Instr.Move(dst, src) if dst == r && init.length >= 2 =>
                init(init.length - 2) match
                  case Instr.Call(d, f, args) if d == src =>
                    init.dropRight(2) :+ Instr.TailCall(f, args)
                  case _ => body
              // Only when BOTH arms already leave their value in `r`. The lowering ends each arm
              // with `Move(r, …)`, so this is a check that the shape is the one we think it is
              // rather than an assumption about how the arms were built — a pass that guesses its
              // input's provenance is a pass that breaks the day another front emits into it.
              case Instr.If(c, t, e) if endsWithMoveTo(t, r) && endsWithMoveTo(e, r) =>
                init.dropRight(1) :+
                  Instr.If(c, tailify(t :+ Instr.Ret(r)), tailify(e :+ Instr.Ret(r)))
              case _ => body
        case _ => body

  private def endsWithMoveTo(body: List[Instr], r: Int): Boolean =
    body.nonEmpty && (body.last match
      case Instr.Move(d, _) => d == r
      case _                => false)
