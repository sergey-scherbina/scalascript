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
    m.copy(funcs = m.funcs.map(f => f.copy(body = tailify(f.body))))

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
