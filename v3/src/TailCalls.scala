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
    val withTails = m.funcs.zipWithIndex.map((f, i) => f.copy(body = tailify(f.body)))
    m.copy(funcs = withTails.zipWithIndex.map((f, i) => selfLoop(f, i)))

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
