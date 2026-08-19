package ssc3

// SSC3-7b step 2 — SPLIT a function at a `Perform`, so the rest of it becomes a FUNCTION and the
// continuation can be an ordinary closure.
//
// The design is `specs/10-ssc-ir.md` §3 ("Capturing a continuation" and "Who PRODUCES the
// continuation"), decided before any of this was written: `k` is a `VClos` built by the compiler,
// not a machine the executor reifies, because the executor is 1363 lines of structured host
// recursion and making its stack copyable is a rewrite of the kernel's largest file. v3 has had
// `MkClos`/`CallV`/`VClos` since the beginning, and a continuation that is a closure is multi-shot
// for free — a closure is not consumed by being called.
//
// THE SPLIT. A `Perform` at position i of a function body divides it:
//
//   before                       stays in `f`
//   MkClos k, f$k, <captures>    built just before the perform
//   Perform d, op, args ++ [k]   the continuation is the LAST argument, per §3
//   Ret d                        `f` ends here: what the handler returns IS the answer
//   after                        becomes `f$k`
//
// EVERY REGISTER IS CAPTURED, not a computed live set. That is deliberate for a first cut: with all
// of them captured, `f$k`'s parameters are `0 .. nregs-1` in order and the instructions in `after`
// need NO renaming — the mapping is the identity. A live-range analysis would capture fewer and
// would have to renumber, which is a second thing to get wrong while the first is unproven. It
// costs closure size, never correctness, and the comment is here so the next person does not read
// laziness into it.
//
// The resumed value arrives as the LAST parameter — `VClos(f, cap)` is called as
// `callFunc(f, cap ++ args)` (Exec.CallV), so captures come first — and is moved into the register
// the `Perform` was writing to, which is what makes `after` read it exactly where it expected to.
//
// WHAT THIS PASS DOES NOT DO, so nobody reads more into it than is here:
//   * only a `Perform` at the TOP LEVEL of a body is split. One inside a `Loop`/`If`/`Switch` is
//     left alone, because a region's remainder is not a suffix of an instruction list — that is
//     step 3, and it is a step rather than a detail for exactly this reason;
//   * it does not change what `Perform` MEANS. The executor's tail-resumptive path still runs
//     unconverted functions unchanged (`Exec.scala` relies on it for every arm that is not CPS
//     encoded).
//
// WIRED IN SINCE `addd2d89c`, 2026-08-09 — this header said "wiring this in is step 4" for ten days
// after it stopped being true, and it is the first thing anyone reads to understand the effects
// pipeline. `Lower.scala` now ends with `TailCalls(Cps(Module(…)))`, which is also why a plugin
// rewrite pass needs no coordination with this one: it runs strictly BEFORE, so whatever a marker
// expands into arrives here as ordinary `handle`/`perform`.
//
// THE STEP 3 CLAUSE ABOVE IS STILL TRUE, and the distinction matters because half of this header
// was stale and half was not. A CALL to a performing function inside a region is crossed now — by
// the executor's frame chain and by the bridge's caller split, neither of which is this pass. A
// `Perform` standing directly inside a region is still not split, and that is what step 3 means.
object Cps:

  /** The suffix name for a split function. Positional, like everything else in a module: ids are
    * only ever compared inside one module. */
  private def contName(base: String, n: Int): String = base + "$k" + n

  /** Split every top-level `Perform` in every function. Returns the module unchanged when there is
    * nothing to split, so applying it to a program without effects is free and provably a no-op. */
  def apply(m: Module): Module =
    if !m.funcs.exists(f => topLevelPerform(f.body).isDefined) then m
    else
      var funcs = m.funcs
      // A WORKLIST, not a scan-until-stable loop.
      //
      // The first version re-scanned every function until none had a top-level `Perform`, and did
      // not terminate: a SPLIT function still contains one — that is the whole point of the split —
      // so it was found and split again forever. The right statement is that a split FINISHES the
      // function it touched and produces one new function that may still need work.
      //
      // Terminates because the continuation's body is strictly shorter than the body it came from,
      // and nothing else is ever added to the queue.
      var queue = funcs.indices.toList
      while queue.nonEmpty do
        val idx = queue.head
        queue = queue.tail
        topLevelPerform(funcs(idx).body).foreach { i =>
          val cur = funcs(idx)
          val (before, rest) = cur.body.splitAt(i)
          val Instr.Perform(d, op, args) = rest.head: @unchecked
          val after = rest.tail
          val nregs = cur.nregs
          val kReg = nregs
          val contIdx = funcs.length
          val caps = (0 until nregs).toList
          val cont = Func(contName(cur.name, i), nregs + 1, nregs + 1,
                          Instr.Move(d, nregs) :: after)
          val split = cur.copy(
            nregs = nregs + 1,
            body  = before
                    ++ List(Instr.MkClos(kReg, contIdx, caps),
                            Instr.Perform(d, op, args :+ kReg),
                            Instr.Ret(d)))
          funcs = funcs.updated(idx, split) :+ cont
          // Only the CONTINUATION goes back on the queue. A program whose function performs twice —
          // `val a = E.op(); val b = E.op()` — keeps its second perform in the continuation the
          // first split just made, and without this it stays there unsplit and unresumable.
          queue = queue :+ contIdx
        }
      m.copy(funcs = funcs)

  /** The index of the first `Perform` at the TOP LEVEL of this body, if any. Deliberately not
    * recursive: a perform nested inside a region is step 3's problem, and finding it here would
    * produce a split that cannot be expressed. */
  private def topLevelPerform(body: List[Instr]): Option[Int] =
    val i = body.indexWhere {
      case Instr.Perform(_, _, _) => true
      case _                      => false
    }
    if i < 0 then None else Some(i)
