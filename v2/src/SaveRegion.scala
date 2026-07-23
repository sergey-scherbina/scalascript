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

  // ── Slice 2: automatic liveness ────────────────────────────────────────────
  // Instead of explicit `frameSlots`, derive the frame from the FREE OUTER variables of a
  // region `Lam(1, body)` = `(input) => body`, then close `body` over a frame tuple. The
  // frame slots become the outer values live in `body`, ordered by their (nearest-first)
  // outer index; §10.2's "liveness over the transitive value graph" for the first-order case.

  /**
   * The free **outer** de-Bruijn indices of `body`, seen `d` binders deep. A `Local(k)` with
   * `k > d` reaches above the region's own binders; its outer index (0 = nearest enclosing
   * binder) is `k - d - 1`. `Local(k)` with `k <= d` is the input or a body-local binder.
   */
  private def freeOuterIndices(t: Term, d: Int): Set[Int] = t match
    case Term.Local(k)          => if k > d then Set(k - d - 1) else Set.empty
    case Term.Lit(_)            => Set.empty
    case Term.Global(_)         => Set.empty
    case Term.Lam(arity, b)     => freeOuterIndices(b, d + arity)
    case Term.App(fn, args)     => freeOuterIndices(fn, d) ++ args.flatMap(freeOuterIndices(_, d))
    case Term.Let(rhs, b)       => rhs.flatMap(freeOuterIndices(_, d)).toSet ++ freeOuterIndices(b, d + rhs.length)
    case Term.LetRec(lams, b)   => lams.flatMap(freeOuterIndices(_, d + lams.length)).toSet ++ freeOuterIndices(b, d + lams.length)
    case Term.If(c, th, e)      => freeOuterIndices(c, d) ++ freeOuterIndices(th, d) ++ freeOuterIndices(e, d)
    case Term.Ctor(_, fields)   => fields.flatMap(freeOuterIndices(_, d)).toSet
    case Term.Match(s, arms, e) => freeOuterIndices(s, d) ++ arms.flatMap(a => freeOuterIndices(a.body, d + a.arity)) ++ e.toSet.flatMap(freeOuterIndices(_, d))
    case Term.Prim(_, args)     => args.flatMap(freeOuterIndices(_, d)).toSet
    case Term.While(c, b)       => freeOuterIndices(c, d) ++ freeOuterIndices(b, d)
    case Term.Seq(terms)        => terms.flatMap(freeOuterIndices(_, d)).toSet

  /**
   * Rewrite `body` (seen `d` body-local binders deep) into the target context inside
   * `Lam(2, Match(frame, Arm("frame", n, _)))`. A `Local(k)`:
   *   - `k < d` — a body-local binder → unchanged;
   *   - `k == d` — the region input → `Local(d + n)` (shifted past the n frame fields);
   *   - `k > d` — a free outer var, outer index `j = k-d-1`, at frame position `p = jIndex(j)`
   *     → the field read `Local(d + (n-1-p))` (frame field `x_p`, last field innermost).
   */
  private def rewrite(t: Term, d: Int, jIndex: Map[Int, Int], n: Int): Term = t match
    case Term.Local(k) =>
      if k < d then Term.Local(k)
      else if k == d then Term.Local(d + n)
      else Term.Local(d + (n - 1 - jIndex(k - d - 1)))
    case Term.Lit(_)            => t
    case Term.Global(_)         => t
    case Term.Lam(arity, b)     => Term.Lam(arity, rewrite(b, d + arity, jIndex, n))
    case Term.App(fn, args)     => Term.App(rewrite(fn, d, jIndex, n), args.map(rewrite(_, d, jIndex, n)))
    case Term.Let(rhs, b)       => Term.Let(rhs.map(rewrite(_, d, jIndex, n)), rewrite(b, d + rhs.length, jIndex, n))
    case Term.LetRec(lams, b)   => Term.LetRec(lams.map(rewrite(_, d + lams.length, jIndex, n)), rewrite(b, d + lams.length, jIndex, n))
    case Term.If(c, th, e)      => Term.If(rewrite(c, d, jIndex, n), rewrite(th, d, jIndex, n), rewrite(e, d, jIndex, n))
    case Term.Ctor(tag, fields) => Term.Ctor(tag, fields.map(rewrite(_, d, jIndex, n)))
    case Term.Match(s, arms, e) => Term.Match(rewrite(s, d, jIndex, n), arms.map(a => Arm(a.tag, a.arity, rewrite(a.body, d + a.arity, jIndex, n))), e.map(rewrite(_, d, jIndex, n)))
    case Term.Prim(op, args)    => Term.Prim(op, args.map(rewrite(_, d, jIndex, n)))
    case Term.While(c, b)       => Term.While(rewrite(c, d, jIndex, n), rewrite(b, d, jIndex, n))
    case Term.Seq(terms)        => Term.Seq(terms.map(rewrite(_, d, jIndex, n)))

  /**
   * Reify a region `Lam(1, body)` by auto-liveness: compute the sorted free outer indices `J`
   * of `body`, close `body` over a frame tuple of those slots, and return `(J, resume)`. The
   * save site builds the frame `Ctor("frame", J.map(Local(_)))` from its own environment; the
   * returned resume is a closed `Lam(2, (frame, input) => body')`.
   */
  def reifyAuto(region: Term): (List[Int], Program) =
    region match
      case Term.Lam(1, body) =>
        val liveIndices = freeOuterIndices(body, 0).toList.sorted
        val jIndex = liveIndices.zipWithIndex.toMap
        val n = liveIndices.length
        val entry =
          Term.Lam(
            2,
            Term.Match(
              Term.Local(1),
              List(Arm("frame", n, rewrite(body, 0, jIndex, n))),
              None
            )
          )
        (liveIndices, Program(Nil, entry))
      case _ => sys.error("save-region: auto region must be a 1-arg lambda (input) => body")

  // Demo auto-region: `(input) => a + input * b`, expressed with a NESTED lambda to exercise
  // depth-aware rewriting: `(input) => (\u. a + u)(input * b)`. Free outer vars are b (nearest,
  // outer index 0) and a (outer index 1). demoAutoEnv gives the save-site values by outer index.
  //   Lam(1, App(Lam(1, i.add(Local(3)=a, Local(0)=u)), [i.mul(Local(0)=input, Local(1)=b)]))
  val demoAutoRegion: Term =
    Term.Lam(
      1,
      Term.App(
        Term.Lam(1, Term.Prim("i.add", List(Term.Local(3), Term.Local(0)))),
        List(Term.Prim("i.mul", List(Term.Local(0), Term.Local(1))))
      )
    )
  val demoAutoEnv: List[Const] = List(Const.CInt(4), Const.CInt(3)) // outer 0 = b, outer 1 = a

  /** Build the save-site frame value for a reified auto-region from its live-index list. */
  def frameOf(liveIndices: List[Int], env: List[Const]): Term =
    Term.Ctor("frame", liveIndices.map(idx => Term.Lit(env(idx))))
