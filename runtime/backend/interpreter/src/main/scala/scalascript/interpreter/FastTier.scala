package scalascript.interpreter

import scala.meta.Term
import Computation.PureUnit
import java.lang.Double.{doubleToRawLongBits, longBitsToDouble}
import scalascript.interpreter.vm.jit.{ObjToDouble, ObjToLong}

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

  // ── double-acc slot (avoids per-outer-iter DoubleV writeback) ────────────────
  // When EvalRuntime wraps an outer mixed-body while that contains a
  // double-acc foreach (detected by `peekDoubleAccName`), it pre-extracts
  // the acc bits into a raw-long slot and calls `withAccSlot`. Inside the loop
  // `tryDoubleAccumForeach`/`Set` read/write the slot instead of globals,
  // reducing per-outer-iter DoubleV allocation from O(iters) to O(1).
  //
  // slot layout: slot(0) = raw double bits; slot(1) = 1L if active, 0L if bailed.
  // Two separate ThreadLocals avoid wrapper-object allocation on each outer call.
  private val accSlotTls: ThreadLocal[Array[Long] | Null] = new ThreadLocal[Array[Long] | Null]()
  private val accNameTls: ThreadLocal[String | Null]      = new ThreadLocal[String | Null]()

  def withAccSlot[A](name: String, slot: Array[Long])(thunk: => A): A =
    accSlotTls.set(slot)
    accNameTls.set(name)
    try thunk finally { accSlotTls.set(null); accNameTls.set(null) }

  // Pre-resolved guard results for the foreach fast path.
  // Created ONCE at while-loop setup; reused every outer iteration to skip
  // the ~12 guard checks that `tryDoubleAccumForeach` runs per call.
  //
  // `jitDouble`/`jitLong` — optional JIT-compiled direct interface for the
  // inner `fn`. When non-null, `runDoubleAccumForeachFast` uses it instead of
  // `compiled.runValueDouble`, skipping the ctorTags scan, closure dispatch,
  // and DExpr tree-walk entirely. Only populated when `slotFreeNames.isEmpty`
  // so the generated Java code never needs `BytecodeJit.interpTls`; callers
  // can therefore invoke the interface directly without `withInterp` overhead.
  final class ResolvedDoubleAccum(
    val compiled:  PatternRuntime.CompiledMatch,
    val fnEnv:     Env,
    val accName:   String,
    val jitDouble: ObjToDouble | Null = null
  )
  final class ResolvedLongAccum(
    val compiled: PatternRuntime.CompiledMatch,
    val fnEnv:    Env,
    val accName:  String,
    val jitLong:  ObjToLong | Null = null
  )

  /** Peek at a leading foreach `apply` term to detect
   *  `xs.foreach(s => { acc = acc + fn(s) })` where `acc` is DoubleV in
   *  globals. Returns the accumulator name or null. AST-only — no eval. */
  def peekDoubleAccName(apply: Term, interp: Interpreter): String | Null =
    peek1ArgAccName(apply, interp, classOf[Value.DoubleV])

  /** Long-typed parallel of `peekDoubleAccName`. Returns the accumulator name
   *  if `acc` resolves to `IntV` in globals; null otherwise. Used to gate the
   *  Long-acc branch of the foreach-hoist in `EvalRuntime.tryFastWhileAssign`
   *  (targets the `patternMatchWide`-style List+Long shape). */
  def peekLongAccName(apply: Term, interp: Interpreter): String | Null =
    peek1ArgAccName(apply, interp, classOf[Value.IntV])

  private def peek1ArgAccName(apply: Term, interp: Interpreter, accCls: Class[?]): String | Null =
    apply match
      case ta: Term.Apply =>
        ta.fun match
          case Term.Select(_, Term.Name("foreach")) =>
            ta.argClause.values match
              case List(fn: Term.Function) =>
                val pc = fn.paramClause
                if pc.values.lengthCompare(1) != 0 then null
                else
                  val pName = pc.values.head.name.value
                  if pName.isEmpty then null
                  else
                    val shape = analyzeClosure(fn.body, pName)
                    if shape == null then null
                    else
                      val v = interp.globals.getOrElse(shape.accName, null)
                      if v != null && accCls.isInstance(v) then shape.accName else null
              case _ => null
          case _ => null
      case _ => null

  /** 2-arg parallel of `peekDoubleAccName`/`peekLongAccName` for the
   *  `m.foreach((k, v) => acc = acc + paramRef)` shape. Returns
   *  `(accName, isDouble)` or null when the apply doesn't match the shape OR
   *  the acc isn't `DoubleV` / `IntV` in globals. */
  def peekMapAccName(apply: Term, interp: Interpreter): (String, Boolean) | Null =
    apply match
      case ta: Term.Apply =>
        ta.fun match
          case Term.Select(_, Term.Name("foreach")) =>
            ta.argClause.values match
              case List(fn: Term.Function) =>
                val pc = fn.paramClause
                if pc.values.lengthCompare(2) != 0 then null
                else
                  val p1 = pc.values.head.name.value
                  val p2 = pc.values(1).name.value
                  if p1.isEmpty || p2.isEmpty then null
                  else
                    val shape = analyzeMapAccum(fn.body, p1, p2)
                    if shape == null then null
                    else
                      interp.globals.getOrElse(shape.accName, null) match
                        case _: Value.DoubleV => (shape.accName, true)
                        case _: Value.IntV    => (shape.accName, false)
                        case _                => null
              case _ => null
          case _ => null
      case _ => null

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
    // Use raw-long slot if EvalRuntime pre-extracted the double-acc for this name.
    val slot    = accSlotTls.get()
    val useSlot = slot != null && slot(1) != 0L && accNameTls.get() == shape.accName
    var acc =
      if useSlot then longBitsToDouble(slot(0))
      else accV.asInstanceOf[Value.DoubleV].v

    val fnEnv: Env = if fn.name.nonEmpty then interp.closureWithSelfFor(fn) else fn.closure

    try
      var rem = list
      while rem.nonEmpty do
        acc = acc + compiled.runValueDouble(rem.head, fnEnv)
        rem = rem.tail
      if useSlot then slot(0) = doubleToRawLongBits(acc)
      else interp.globals(shape.accName) = Value.doubleV(acc)
      PureUnit
    catch
      case _: scala.util.control.ControlThrowable =>
        // NotDouble thrown — bail to standard foreach. Mark slot inactive so
        // subsequent outer iterations also use the globals path (which the
        // standard foreach has already updated correctly).
        if useSlot then slot(1) = 0L
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
    // Use raw-long slot if EvalRuntime pre-extracted the long-acc for this name.
    // The slot stores `acc` directly (no bit-encoding needed since Long is raw).
    val slot    = accSlotTls.get()
    val useSlot = slot != null && slot(1) != 0L && accNameTls.get() == shape.accName
    var acc =
      if useSlot then slot(0)
      else accV.asInstanceOf[Value.IntV].v

    val fnEnv: Env = if fn.name.nonEmpty then interp.closureWithSelfFor(fn) else fn.closure

    try
      var rem = list
      while rem.nonEmpty do
        acc = acc + compiled.runValueLong(rem.head, fnEnv)
        rem = rem.tail
      if useSlot then slot(0) = acc
      else interp.globals(shape.accName) = Value.intV(acc)
      PureUnit
    catch
      case _: scala.util.control.ControlThrowable =>
        if useSlot then slot(1) = 0L
        null

  /** Set-receiver variant of `tryDoubleAccumForeach`. Mirrors the List path
   *  but iterates via `set.iterator` so the dispatch site can hand the
   *  receiver `Set[Value]` directly instead of pre-allocating
   *  `set.toList` (the path the generic `dispatchList(s.toList, …)`
   *  detour pays). Saves O(N) `::`-cons allocations per outer iter for
   *  benches like `patternMatchSet`. */
  def tryDoubleAccumForeachSet(
    set:     scala.collection.immutable.Set[Value],
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
    if !compiled.doubleCapable then return null
    if !compiled.allSlot then return null
    val freeNames = compiled.slotFreeNames
    if freeNames == null || freeNames.contains(fnParam) then return null
    val accV = interp.globals.getOrElse(shape.accName, null)
    if (accV eq null) || !accV.isInstanceOf[Value.DoubleV] then return null
    val slot    = accSlotTls.get()
    val useSlot = slot != null && slot(1) != 0L && accNameTls.get() == shape.accName
    var acc =
      if useSlot then longBitsToDouble(slot(0))
      else accV.asInstanceOf[Value.DoubleV].v
    val fnEnv: Env = if fn.name.nonEmpty then interp.closureWithSelfFor(fn) else fn.closure
    try
      val it = set.iterator
      while it.hasNext do
        acc = acc + compiled.runValueDouble(it.next(), fnEnv)
      if useSlot then slot(0) = doubleToRawLongBits(acc)
      else interp.globals(shape.accName) = Value.doubleV(acc)
      PureUnit
    catch
      case _: scala.util.control.ControlThrowable =>
        if useSlot then slot(1) = 0L
        null

  /** Long-typed parallel of `tryDoubleAccumForeachSet`. */
  def tryLongAccumForeachSet(
    set:     scala.collection.immutable.Set[Value],
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
    val slot    = accSlotTls.get()
    val useSlot = slot != null && slot(1) != 0L && accNameTls.get() == shape.accName
    var acc =
      if useSlot then slot(0)
      else accV.asInstanceOf[Value.IntV].v
    val fnEnv: Env = if fn.name.nonEmpty then interp.closureWithSelfFor(fn) else fn.closure
    try
      val it = set.iterator
      while it.hasNext do
        acc = acc + compiled.runValueLong(it.next(), fnEnv)
      if useSlot then slot(0) = acc
      else interp.globals(shape.accName) = Value.intV(acc)
      PureUnit
    catch
      case _: scala.util.control.ControlThrowable =>
        if useSlot then slot(1) = 0L
        null

  // ── Pre-resolve: run guards ONCE at while-loop setup ─────────────────────────

  /** Run all guard checks for the double-acc foreach path, returning a
   *  `ResolvedDoubleAccum` on success or null on miss.  The caller stores it and
   *  passes it to `runDoubleAccumForeachFast` on every outer iteration, bypassing
   *  the ~12 per-call guard checks in `tryDoubleAccumForeach`. */
  def tryResolveDoubleAccum(
    closure: Value.FunV,
    interp:  Interpreter
  ): ResolvedDoubleAccum | Null =
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
    if !compiled.doubleCapable || !compiled.allSlot then return null
    val freeNames = compiled.slotFreeNames
    if freeNames == null || freeNames.contains(fnParam) then return null
    val fnEnv: Env = if fn.name.nonEmpty then interp.closureWithSelfFor(fn) else fn.closure
    // Try to JIT-compile `fn` as an `ObjToDouble`. Only when there are no free
    // names (slotFreeNames.isEmpty) can the caller skip `withInterp` — the
    // generated Java code then never calls `readGlobalDouble`.
    val jitFn: ObjToDouble | Null =
      if freeNames != null && freeNames.isEmpty then
        scalascript.interpreter.vm.JitRuntime.tryGetObjToDouble(fn, interp)
      else null
    new ResolvedDoubleAccum(compiled, fnEnv, shape.accName, jitFn)

  /** Long-typed parallel of `tryResolveDoubleAccum`. */
  def tryResolveLongAccum(
    closure: Value.FunV,
    interp:  Interpreter
  ): ResolvedLongAccum | Null =
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
    if !compiled.longCapable || !compiled.allSlot then return null
    val freeNames = compiled.slotFreeNames
    if freeNames == null || freeNames.contains(fnParam) then return null
    val fnEnv: Env = if fn.name.nonEmpty then interp.closureWithSelfFor(fn) else fn.closure
    val jitFn: ObjToLong | Null =
      if freeNames != null && freeNames.isEmpty then
        scalascript.interpreter.vm.JitRuntime.tryGetObjToLong(fn, interp)
      else null
    new ResolvedLongAccum(compiled, fnEnv, shape.accName, jitFn)

  // ── Fast inner loops: guards already resolved, only slot + iterate ────────────

  /** Inner loop for double-acc List foreach with pre-resolved guards.
   *  Reads the acc from TLS slot (when active) or globals; writes back once.
   *  When `resolved.jitDouble` is non-null (bytecode-JIT compiled, no free
   *  names), the inner `fn` is invoked via the direct unboxed interface,
   *  bypassing ctorTags scan + closure dispatch + DExpr arithmetic entirely. */
  def runDoubleAccumForeachFast(
    list:     List[Value],
    resolved: ResolvedDoubleAccum,
    interp:   Interpreter,
    cachedSlot: Array[Long] | Null = null
  ): Computation | Null =
    val slot    = if cachedSlot ne null then cachedSlot else accSlotTls.get()
    val useSlot = slot != null && slot(1) != 0L
    var acc =
      if useSlot then longBitsToDouble(slot(0))
      else
        val v = interp.globals.getOrElse(resolved.accName, null)
        if (v eq null) || !v.isInstanceOf[Value.DoubleV] then return null
        v.asInstanceOf[Value.DoubleV].v
    val jitFn = resolved.jitDouble
    if jitFn != null then
      // JIT path: no free names → no withInterp TLS needed; call direct interface.
      try
        var rem = list
        while rem.nonEmpty do
          acc = acc + jitFn.apply(rem.head.asInstanceOf[AnyRef])
          rem = rem.tail
        if useSlot then slot(0) = doubleToRawLongBits(acc)
        else interp.globals(resolved.accName) = Value.doubleV(acc)
        PureUnit
      catch
        case _: scala.util.control.ControlThrowable =>
          if useSlot then slot(1) = 0L
          null
        case _: Throwable =>
          // Unexpected JIT error (e.g. wrong instance type); return null so
          // the caller falls back to the standard foreach path for correctness.
          null
    else
      try
        var rem = list
        while rem.nonEmpty do
          acc = acc + resolved.compiled.runValueDouble(rem.head, resolved.fnEnv)
          rem = rem.tail
        if useSlot then slot(0) = doubleToRawLongBits(acc)
        else interp.globals(resolved.accName) = Value.doubleV(acc)
        PureUnit
      catch
        case _: scala.util.control.ControlThrowable =>
          if useSlot then slot(1) = 0L
          null

  /** Long-typed parallel of `runDoubleAccumForeachFast`. */
  def runLongAccumForeachFast(
    list:     List[Value],
    resolved: ResolvedLongAccum,
    interp:   Interpreter,
    cachedSlot: Array[Long] | Null = null
  ): Computation | Null =
    val slot    = if cachedSlot ne null then cachedSlot else accSlotTls.get()
    val useSlot = slot != null && slot(1) != 0L
    var acc =
      if useSlot then slot(0)
      else
        val v = interp.globals.getOrElse(resolved.accName, null)
        if (v eq null) || !v.isInstanceOf[Value.IntV] then return null
        v.asInstanceOf[Value.IntV].v
    val jitFn = resolved.jitLong
    if jitFn != null then
      try
        var rem = list
        while rem.nonEmpty do
          acc = acc + jitFn.apply(rem.head.asInstanceOf[AnyRef])
          rem = rem.tail
        if useSlot then slot(0) = acc
        else interp.globals(resolved.accName) = Value.intV(acc)
        PureUnit
      catch
        case _: scala.util.control.ControlThrowable =>
          if useSlot then slot(1) = 0L
          null
        case _: Throwable =>
          null
    else
      try
        var rem = list
        while rem.nonEmpty do
          acc = acc + resolved.compiled.runValueLong(rem.head, resolved.fnEnv)
          rem = rem.tail
        if useSlot then slot(0) = acc
        else interp.globals(resolved.accName) = Value.intV(acc)
        PureUnit
      catch
        case _: scala.util.control.ControlThrowable =>
          if useSlot then slot(1) = 0L
          null

  /** Set-receiver fast variant of `runDoubleAccumForeachFast`. */
  def runDoubleAccumForeachSetFast(
    set:      scala.collection.immutable.Set[Value],
    resolved: ResolvedDoubleAccum,
    interp:   Interpreter,
    cachedSlot: Array[Long] | Null = null
  ): Computation | Null =
    val slot    = if cachedSlot ne null then cachedSlot else accSlotTls.get()
    val useSlot = slot != null && slot(1) != 0L
    var acc =
      if useSlot then longBitsToDouble(slot(0))
      else
        val v = interp.globals.getOrElse(resolved.accName, null)
        if (v eq null) || !v.isInstanceOf[Value.DoubleV] then return null
        v.asInstanceOf[Value.DoubleV].v
    val jitFn = resolved.jitDouble
    if jitFn != null then
      try
        val it = set.iterator
        while it.hasNext do
          acc = acc + jitFn.apply(it.next().asInstanceOf[AnyRef])
        if useSlot then slot(0) = doubleToRawLongBits(acc)
        else interp.globals(resolved.accName) = Value.doubleV(acc)
        PureUnit
      catch
        case _: scala.util.control.ControlThrowable =>
          if useSlot then slot(1) = 0L
          null
        case _: Throwable => null
    else
      try
        val it = set.iterator
        while it.hasNext do
          acc = acc + resolved.compiled.runValueDouble(it.next(), resolved.fnEnv)
        if useSlot then slot(0) = doubleToRawLongBits(acc)
        else interp.globals(resolved.accName) = Value.doubleV(acc)
        PureUnit
      catch
        case _: scala.util.control.ControlThrowable =>
          if useSlot then slot(1) = 0L
          null

  /** Set-receiver fast variant of `runLongAccumForeachFast`. */
  def runLongAccumForeachSetFast(
    set:      scala.collection.immutable.Set[Value],
    resolved: ResolvedLongAccum,
    interp:   Interpreter,
    cachedSlot: Array[Long] | Null = null
  ): Computation | Null =
    val slot    = if cachedSlot ne null then cachedSlot else accSlotTls.get()
    val useSlot = slot != null && slot(1) != 0L
    var acc =
      if useSlot then slot(0)
      else
        val v = interp.globals.getOrElse(resolved.accName, null)
        if (v eq null) || !v.isInstanceOf[Value.IntV] then return null
        v.asInstanceOf[Value.IntV].v
    val jitFn = resolved.jitLong
    if jitFn != null then
      try
        val it = set.iterator
        while it.hasNext do
          acc = acc + jitFn.apply(it.next().asInstanceOf[AnyRef])
        if useSlot then slot(0) = acc
        else interp.globals(resolved.accName) = Value.intV(acc)
        PureUnit
      catch
        case _: scala.util.control.ControlThrowable =>
          if useSlot then slot(1) = 0L
          null
        case _: Throwable => null
    else
      try
        val it = set.iterator
        while it.hasNext do
          acc = acc + resolved.compiled.runValueLong(it.next(), resolved.fnEnv)
        if useSlot then slot(0) = acc
        else interp.globals(resolved.accName) = Value.intV(acc)
        PureUnit
      catch
        case _: scala.util.control.ControlThrowable =>
          if useSlot then slot(1) = 0L
          null

  /** 2-arg closure shape: `(p1, p2) => { accName = accName + paramRef }` where
   *  `paramRef` is a `Term.Name` reference to either `p1` or `p2`. Used for the
   *  `Map.foreach((k, v) => acc = acc + v)` family (see `tryLongAccumForeachMap`,
   *  `tryDoubleAccumForeachMap`). `useFirst` selects the first or second
   *  closure param as the accumulator source. */
  private final class MapAccumShape(val accName: String, val useFirst: Boolean)
  private val MAP_MISS: MapAccumShape = new MapAccumShape("", false)
  private val mapShapeCache: java.util.IdentityHashMap[Term, MapAccumShape] =
    new java.util.IdentityHashMap[Term, MapAccumShape]()

  private def analyzeMapAccum(body: Term, p1: String, p2: String): MapAccumShape | Null =
    val cached = mapShapeCache.get(body)
    if cached != null then return if cached eq MAP_MISS then null else cached
    val r = analyzeMapAccumRaw(body, p1, p2)
    mapShapeCache.put(body, if r == null then MAP_MISS else r)
    r

  private def analyzeMapAccumRaw(body: Term, p1: String, p2: String): MapAccumShape | Null =
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
            if accName == p1 || accName == p2 then null
            else
              a.rhs match
                case Term.ApplyInfix.After_4_6_0(lhs: Term.Name, op, _, argClause)
                    if op.value == "+" && lhs.value == accName && argClause.values.lengthCompare(1) == 0 =>
                  argClause.values.head match
                    case ref: Term.Name =>
                      if ref.value == p1 then new MapAccumShape(accName, true)
                      else if ref.value == p2 then new MapAccumShape(accName, false)
                      else null
                    case _ => null
                case _ => null
          case _ => null
      case _ => null

  /** Fast-tier handler for `m.foreach((k, v) => acc = acc + paramRef)` where
   *  `acc` is `Int`-typed in globals and the picked entry component (`k` or
   *  `v`) is `IntV` per iteration. Simpler than the list/set 1-arg paths
   *  because there's no inner `fn` to resolve — the closure body adds one
   *  component of the entry directly to the accumulator.
   *
   *  Bails on the first non-`IntV` source value with `globals` untouched
   *  (safe — we accumulate locally and write back once at end of loop). */
  def tryLongAccumForeachMap(
    m:       scala.collection.immutable.Map[Value, Value],
    closure: Value.FunV,
    interp:  Interpreter
  ): Computation | Null =
    if !enabled then return null
    if closure.params.length != 2 then return null
    if closure.usingParams.nonEmpty then return null
    val p1    = closure.params(0)
    val p2    = closure.params(1)
    val shape = analyzeMapAccum(closure.body, p1, p2)
    if shape == null then return null
    val accV = interp.globals.getOrElse(shape.accName, null)
    if (accV eq null) || !accV.isInstanceOf[Value.IntV] then return null
    val slot    = accSlotTls.get()
    val useSlot = slot != null && slot(1) != 0L && accNameTls.get() == shape.accName
    var acc =
      if useSlot then slot(0)
      else accV.asInstanceOf[Value.IntV].v
    val it = m.iterator
    while it.hasNext do
      val kv  = it.next()
      val src = if shape.useFirst then kv._1 else kv._2
      src match
        case Value.IntV(x) => acc = acc + x
        case _             =>
          if useSlot then slot(1) = 0L
          return null
    if useSlot then slot(0) = acc
    else interp.globals(shape.accName) = Value.intV(acc)
    PureUnit

  /** Double-typed parallel of `tryLongAccumForeachMap`. Accepts both `DoubleV`
   *  and `IntV` (the latter widens to `double`) at the source position —
   *  mirrors Scala's numeric promotion in `total = total + v` where `total` is
   *  Double. Writes back once via `Value.doubleV`. */
  def tryDoubleAccumForeachMap(
    m:       scala.collection.immutable.Map[Value, Value],
    closure: Value.FunV,
    interp:  Interpreter
  ): Computation | Null =
    if !enabled then return null
    if closure.params.length != 2 then return null
    if closure.usingParams.nonEmpty then return null
    val p1    = closure.params(0)
    val p2    = closure.params(1)
    val shape = analyzeMapAccum(closure.body, p1, p2)
    if shape == null then return null
    val accV = interp.globals.getOrElse(shape.accName, null)
    if (accV eq null) || !accV.isInstanceOf[Value.DoubleV] then return null
    val slot    = accSlotTls.get()
    val useSlot = slot != null && slot(1) != 0L && accNameTls.get() == shape.accName
    var acc =
      if useSlot then longBitsToDouble(slot(0))
      else accV.asInstanceOf[Value.DoubleV].v
    val it = m.iterator
    while it.hasNext do
      val kv  = it.next()
      val src = if shape.useFirst then kv._1 else kv._2
      src match
        case Value.DoubleV(x) => acc = acc + x
        case Value.IntV(x)    => acc = acc + x.toDouble
        case _                =>
          if useSlot then slot(1) = 0L
          return null
    if useSlot then slot(0) = doubleToRawLongBits(acc)
    else interp.globals(shape.accName) = Value.doubleV(acc)
    PureUnit
