package ssc

/**
 * §10.2 saveable-region reification (`specs/portable-save-region.md`): closure-convert a
 * compiler-declared saveable region into a **closed** CoreIR resume `Program`, so a Portable
 * capsule (`v2/src/Capsule.scala`) can be produced from ordinary code rather than a
 * hand-authored resume lambda.
 *
 * The runtime continuation at an `effect.perform` is a live `ClosV`
 * (`v2/src/Runtime.scala:237, 334-362`), which control-interoperability §10.2 forbids
 * serializing (`control-interoperability.md:737`). So this pass operates on a SYNTACTIC
 * region — the resume segment expressed as a lambda — and only closes it over its captured
 * frame; it does not capture the dynamic continuation. See §1 of the spec for the rationale.
 *
 * First-order slice: the frame is a flat tuple of scalar slots and the region lambda is closed
 * over its own `(slot0, ..., slot(n-1), input)` parameters. Auto-liveness (finding the slots
 * from free outer variables), global closure, and effectful regions are later staged slices.
 */
object SaveRegion:
  /**
   * Reify a saveable region into `(frameValue, resume)`:
   *   - `frameSlots` — the captured frame values (evaluated at save time), one per slot.
   *   - `resumeLam`  — the region resume segment, a CLOSED `Lam(n+1, body)` =
   *                    `(slot0, ..., slot(n-1), input) => body`.
   *
   * Returns the frame value term `Ctor("frame", slots)` and a closed resume `Program` whose
   * entry is `Lam(2, (frame, input) => ...)`: it pattern-matches the frame tuple to bind the n
   * slots, then applies the (already closed) region lambda to those fields plus the input. The
   * result has no free variables, so `Reader.validate` admits it and `run-capsule` runs it with
   * no machine held.
   */
  def reify(frameSlots: List[Const], resumeLam: Term): (Term, Program) =
    val n = frameSlots.length
    resumeLam match
      case Term.Lam(arity, _) if arity == n + 1 => ()
      case Term.Lam(arity, _) =>
        sys.error(s"save-region: resume arity $arity does not match $n slots + 1 input")
      case _ => sys.error("save-region: resume segment must be a lambda")

    val frameValue = Term.Ctor("frame", frameSlots.map(Term.Lit(_)))

    // Closed resume entry `Lam(2, Match(frame, Arm("frame", n, App(resumeLam, args))))`:
    //   at the Lam(2) body, frame = Local(1), input = Local(0);
    //   the arm binds the n fields (first field = Local(n-1), last field = Local(0), per the
    //   CoreIR de-Bruijn convention where the last binder is index 0) and shifts input to
    //   Local(n). We apply the CLOSED region lambda to (field0, ..., field(n-1), input); being
    //   closed it needs no shifting wherever it is embedded.
    val args =
      (0 until n).map(i => Term.Local(n - 1 - i)).toList :+ Term.Local(n)
    val entry =
      Term.Lam(
        2,
        Term.Match(
          Term.Local(1),
          List(Arm("frame", n, Term.App(resumeLam, args))),
          None
        )
      )
    (frameValue, Program(Nil, entry))

  // Demo region for `ssc freeze-region`: the frame captures two slots (a = 3, b = 4); the
  // resume segment is `(a, b, input) => a * input + b`. In de-Bruijn `Lam(3, ...)`:
  // input = Local(0), b = Local(1), a = Local(2).
  val demoRegionSlots: List[Const] = List(Const.CInt(3), Const.CInt(4))
  val demoRegionResume: Term =
    Term.Lam(
      3,
      Term.Prim(
        "i.add",
        List(
          Term.Prim("i.mul", List(Term.Local(2), Term.Local(0))),
          Term.Local(1)
        )
      )
    )
