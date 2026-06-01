package scalascript.interpreter

import scala.meta.Term
import Computation.PureUnit

/** Gated fast-tier (binary-strolling-river plan A3/A4).
 *
 *  Current scope (intentionally narrow): the
 *  `xs.foreach(s => acc = acc + fn(s))` shape with a `Double`-typed accumulator
 *  and a pure-double slot-compilable match-bodied `fn` (e.g. the `patternMatch`
 *  benchmark's `var total = 0.0; shapes.foreach(s => total = total + area(s))`).
 *  This is the dominant un-JIT-able allocator (~85% of `patternMatchHeavy`'s
 *  bytes: 70% `DoubleV` + 15% `Computation.Pure`).
 *
 *  Strategy: detect the closure shape once per AST-identical body, resolve the
 *  inner `fn` from the captured closure, look up its cached `CompiledMatch`,
 *  read the accumulator's initial value as a primitive `double` from
 *  `interp.globals` (Term.Assign writes always go to globals — see
 *  `EvalRuntime.Term.Assign`), iterate the list reading the raw double result
 *  of each `fn(item)` via `CompiledMatch.runValueDouble`, and write the boxed
 *  accumulator back to globals exactly once at loop exit.
 *
 *  Gate: default **ON** since both heavy-double and wide-long sub-targets
 *  shipped same-session A/B-proven 2026-06-01 (Heavy −50.9%, Wide −78.1%).
 *  Opt out via `SSC_FASTTIER=off` or `-Dssc.fasttier=off` if a regression
 *  needs A/B isolation. */
private[interpreter] object FastTier:

  /** Default **ON**. Opt out via env `SSC_FASTTIER=off` (interactive) or the
   *  parallel system property `-Dssc.fasttier=off` (JMH forks / sbt — env
   *  vars do not always propagate through forks). Off-switch retained so a
   *  regression can be A/B-isolated without code churn, and so the original
   *  test-with-and-without-gate methodology stays available. */
  val enabled: Boolean =
    !sys.env.get("SSC_FASTTIER").contains("off") &&
      !sys.props.get("ssc.fasttier").contains("off")

  /** Closure shape: `paramName => { accName = accName + fnName(paramName) }`.
   *  `paramName` is implicit (matches the closure's single param). */
  private final class ClosureShape(val accName: String, val fnName: String)

  /** Sentinel for "analyzed and didn't match" — keeps the IdentityHashMap miss
   *  path branch-free (one lookup, one reference compare). */
  private val MISS: ClosureShape = new ClosureShape("", "")

  /** Per-AST cached shape analysis. The closure's `body: Term` is a stable AST
   *  node across rebuilds of the surrounding `FunV` (the AST is immutable; a
   *  loop-resident lambda points at the same Term every iteration). */
  private val shapeCache: java.util.IdentityHashMap[Term, ClosureShape] =
    new java.util.IdentityHashMap[Term, ClosureShape]()

  private def analyzeClosure(body: Term, paramName: String): ClosureShape | Null =
    val cached = shapeCache.get(body)
    if cached != null then return if cached eq MISS then null else cached
    val r = analyzeRaw(body, paramName)
    shapeCache.put(body, if r == null then MISS else r)
    r

  private def analyzeRaw(body: Term, paramName: String): ClosureShape | Null =
    val core = body match
      case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
        b.stats.head match
          case t: Term => t
          case _       => return null
      case t => t
    core match
      case a: Term.Assign =>
        a.lhs match
          case lhsName: Term.Name =>
            val accName = lhsName.value
            if accName == paramName then null
            else
              a.rhs match
                case Term.ApplyInfix.After_4_6_0(lhs: Term.Name, op, _, argClause)
                    if op.value == "+" && lhs.value == accName && argClause.values.lengthCompare(1) == 0 =>
                  argClause.values.head match
                    case ap: Term.Apply =>
                      ap.fun match
                        case fn: Term.Name if ap.argClause.values.lengthCompare(1) == 0 =>
                          ap.argClause.values.head match
                            case arg: Term.Name
                                if arg.value == paramName && fn.value != accName && fn.value != paramName =>
                              new ClosureShape(accName, fn.value)
                            case _ => null
                        case _ => null
                    case _ => null
                case _ => null
          case _ => null
      case _ => null

  /** Fast-tier handler for `xs.foreach(s => acc = acc + fn(s))` where `acc` is
   *  Double-typed and `fn`'s body is a slot-double-compilable match.
   *
   *  Returns `PureUnit` on success (acc written back to globals once); null on
   *  any precondition miss (caller falls through to the standard foreach path).
   *  On a runtime `NotDouble` mid-loop (a free name in `fn`'s body resolves to
   *  a non-numeric value), returns null with `globals` untouched — safe because
   *  the slot-double path is side-effect-free up to that point. */
  def tryDoubleAccumForeach(
    list:    List[Value],
    closure: Value.FunV,
    interp:  Interpreter
  ): Computation | Null =
    if !enabled then return null
    if closure.params.length != 1 then return null
    val paramName = closure.params.head
    val shape = analyzeClosure(closure.body, paramName)
    if shape == null then return null

    // Resolve `fn` (the inner pure function). Top-level `def` is registered in
    // `interp.globals`; the empty-closure optimization (project memory:
    // `project_interp_fasttier` A1) elides capturing global names into
    // `closure.closure`, so the closure body's free `fn` reference resolves
    // via env→globals at call time. Mirror that fallback here.
    val fnVal = {
      val c = closure.closure.getOrElse(shape.fnName, null)
      if c != null then c else interp.globals.getOrElse(shape.fnName, null)
    }
    if (fnVal eq null) || !fnVal.isInstanceOf[Value.FunV] then return null
    val fn = fnVal.asInstanceOf[Value.FunV]
    if fn.params.length != 1 || fn.usingParams.nonEmpty || fn.returnsThrows then return null
    if fn.defaults.nonEmpty && fn.defaults.exists(_.nonEmpty) then return null
    if fn.paramTypes.nonEmpty && fn.paramTypes.exists(_.endsWith("*")) then return null
    if !fn.body.isInstanceOf[Term.Match] then return null
    val mt = fn.body.asInstanceOf[Term.Match]
    val fnParam = fn.params.head
    val scrutMatchesParam = mt.expr match
      case n: Term.Name => n.value == fnParam
      case _            => false
    if !scrutMatchesParam then return null

    // Look up or build the CompiledMatch (cached in interp.matchCache by AST id).
    var compiled = interp.matchCache.get(mt)
    if compiled == null then
      compiled = PatternRuntime.compileMatch(mt, interp)
      interp.matchCache.put(mt, compiled)
    if !compiled.doubleCapable then return null
    if !compiled.allSlot then return null
    val freeNames = compiled.slotFreeNames
    // The fn's param must NOT appear as a free name in any arm body — otherwise
    // skipping the param FrameMap would resolve it from `fn.closure` (a
    // shadowed outer binding) instead of the actual argument.
    if freeNames == null || freeNames.contains(fnParam) then return null

    // Read accumulator initial value. Term.Assign always writes to globals
    // (EvalRuntime line ~1403), so a top-level `var total = 0.0` lives there.
    val accV = interp.globals.getOrElse(shape.accName, null)
    if (accV eq null) || !accV.isInstanceOf[Value.DoubleV] then return null
    var acc = accV.asInstanceOf[Value.DoubleV].v

    val fnEnv: Env = if fn.name.nonEmpty then interp.closureWithSelfFor(fn) else fn.closure

    try
      var rem = list
      while rem.nonEmpty do
        acc = acc + compiled.runValueDouble(rem.head, fnEnv)
        rem = rem.tail
      // Write back ONCE. doubleV picks pre-cached DoubleZero/DoubleOne if applicable.
      interp.globals(shape.accName) = Value.doubleV(acc)
      PureUnit
    catch
      case _: scala.util.control.ControlThrowable =>
        // NotDouble thrown — a free name resolved to a non-numeric value at
        // runtime (or no arm matched, the canonical NaNMiss path). `globals`
        // has not been written yet (we only write after the full loop). Return
        // null so the caller falls back to the standard foreach evaluator;
        // semantics preserved because the slot-double path is read-only.
        null

  /** Long-typed parallel of `tryDoubleAccumForeach`, for an `Int`-typed
   *  accumulator and a slot-long-compilable `fn` (e.g. the `patternMatchWide`
   *  benchmark's `var total = 0; ops.foreach(o => total = total + eval(o))`).
   *
   *  Same shape detection and lookup path as the double variant. Accumulator
   *  must be `IntV` in `interp.globals`; bails to null otherwise. Writes back
   *  exactly once via `Value.intV(acc)`, which hits the cached `_intVPool`
   *  for in-range results — saving the wrapper allocation on the boundary too. */
  def tryLongAccumForeach(
    list:    List[Value],
    closure: Value.FunV,
    interp:  Interpreter
  ): Computation | Null =
    if !enabled then return null
    if closure.params.length != 1 then return null
    val paramName = closure.params.head
    val shape = analyzeClosure(closure.body, paramName)
    if shape == null then return null

    val fnVal = {
      val c = closure.closure.getOrElse(shape.fnName, null)
      if c != null then c else interp.globals.getOrElse(shape.fnName, null)
    }
    if (fnVal eq null) || !fnVal.isInstanceOf[Value.FunV] then return null
    val fn = fnVal.asInstanceOf[Value.FunV]
    if fn.params.length != 1 || fn.usingParams.nonEmpty || fn.returnsThrows then return null
    if fn.defaults.nonEmpty && fn.defaults.exists(_.nonEmpty) then return null
    if fn.paramTypes.nonEmpty && fn.paramTypes.exists(_.endsWith("*")) then return null
    if !fn.body.isInstanceOf[Term.Match] then return null
    val mt = fn.body.asInstanceOf[Term.Match]
    val fnParam = fn.params.head
    val scrutMatchesParam = mt.expr match
      case n: Term.Name => n.value == fnParam
      case _            => false
    if !scrutMatchesParam then return null

    var compiled = interp.matchCache.get(mt)
    if compiled == null then
      compiled = PatternRuntime.compileMatch(mt, interp)
      interp.matchCache.put(mt, compiled)
    if !compiled.longCapable then return null
    if !compiled.allSlot then return null
    val freeNames = compiled.slotFreeNames
    if freeNames == null || freeNames.contains(fnParam) then return null

    val accV = interp.globals.getOrElse(shape.accName, null)
    if (accV eq null) || !accV.isInstanceOf[Value.IntV] then return null
    var acc = accV.asInstanceOf[Value.IntV].v

    val fnEnv: Env = if fn.name.nonEmpty then interp.closureWithSelfFor(fn) else fn.closure

    try
      var rem = list
      while rem.nonEmpty do
        acc = acc + compiled.runValueLong(rem.head, fnEnv)
        rem = rem.tail
      interp.globals(shape.accName) = Value.intV(acc)
      PureUnit
    catch
      case _: scala.util.control.ControlThrowable => null
