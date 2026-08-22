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
//     left alone, because a region's remainder is not a suffix of an instruction list;
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
// STEP 3 IS ANSWERED, AND NOT HERE — which is the same correction this header needed once before,
// so it is written the same way. The clause above still describes what THIS PASS does: a `Perform`
// inside a region is not split by it, and a reader who wants to know why gets the reason. What is
// no longer true is that nothing answers it. Both lanes do, and they do it differently on purpose,
// because the difference is not stylistic — `v3/specs/10-ssc-ir.md` §3 states the one invariant and
// names the two realisations:
//
//   * `Exec` hands the perform the rest of its own instruction list as a `PendingFrame`, the same
//     shape it already records on the way into a region. There a region does not open a frame, so
//     both remainders share one register array and there is nothing to thread.
//   * `BridgeV2` REBUILDS the remainder into one function (`cutAt`, `splitRegionPerforms`), because
//     `MkClos` captures registers by value and a continuation split across two closures would lose
//     every write the first one made.
//
// LOWERING COULD STILL TAKE IT and the spec's own route says how — a `Loop` containing a `Perform`
// becomes a recursive function — which would replace both of those with one statement. Nobody has,
// and the argument for doing it is exactly that: one mechanism instead of two.
object Cps:

  /** The suffix name for a split function. Positional, like everything else in a module: ids are
    * only ever compared inside one module. */
  private def contName(base: String, n: Int): String = base + "$k" + n

  /** Split every top-level `Perform` in every function. Returns the module unchanged when there is
    * nothing to split, so applying it to a program without effects is free and provably a no-op. */
  /** THE OPERATIONS THAT NEED NO CONTINUATION, and therefore must NOT be split.
    *
    * An arm whose last act is a single `resume` folds "run the arm and take its value" and "capture
    * the rest, hand it over, resume it" into the same computation. Splitting anyway is not a wasted
    * closure — it is WRONG TWICE, and both were measured before this was written:
    *
    *   * IT COSTS THE CONSTANT STACK. A split perform takes the CPS path, where every resume runs
    *     inside the previous arm's activation on the HOST stack. The same loop, 200 000 iterations:
    *     unsplit answers, split dies with a StackOverflow at 1000. The executor's whole claim is
    *     constant stack, and this is where it lost it.
    *   * IT CAN ANSWER WRONGLY. The split makes the performing function return the ARM's value, and
    *     `BridgeV2` — correctly, because a tail-resumptive arm needs no caller remainder — then
    *     declines to carry the caller's. The two decisions disagree and the rest of the program runs
    *     on a value the split had already finished with: `1001` where the answer is `1100`.
    *
    * CONSERVATIVE IN THE ONLY DIRECTION THAT IS SAFE. Every arm for the operation, in the whole
    * module, must be tail-resumptive; one that is not, or a return clause the tail path would then
    * have to lift, and the split stands. An operation with no arm at all is not reachable — lowering
    * refuses a `perform` no `handle` answers — so the empty case cannot arise here.
    */
  private def needsNoContinuation(m: Module): Set[Int] =
    val arms: List[HandlerArm] = m.funcs.flatMap(fn =>
      Instr.flatten(fn.body).collect { case Instr.Handle(_, _, as) => as }.flatten)
    val ops: Set[Int] = arms.filter(_.op >= 0).map(_.op).toSet
    ops.filter(op => arms.filter(_.op == op).forall(_.tailResumptive))

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
      val skip = needsNoContinuation(m)
      var queue = funcs.indices.toList
      while queue.nonEmpty do
        val idx = queue.head
        queue = queue.tail
        topLevelPerform(funcs(idx).body, skip).foreach { i =>
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
  private def topLevelPerform(body: List[Instr], skip: Set[Int] = Set.empty): Option[Int] =
    val i = body.indexWhere {
      case Instr.Perform(_, op, _) => !skip.contains(op)
      case _                       => false
    }
    if i < 0 then None else Some(i)
