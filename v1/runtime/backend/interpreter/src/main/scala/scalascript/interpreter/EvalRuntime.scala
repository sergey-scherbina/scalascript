package scalascript.interpreter

import scalascript.transform.DirectTypeUtils
import scala.collection.mutable
import scala.collection.immutable.{Map => IMap}
import scala.meta.*
import Computation.{Pure, FlatMap, Perform}
import java.lang.Double.{doubleToRawLongBits, longBitsToDouble}

/** Public observability for the jit-foldleft-tc typeclass-fold memo, so a
 *  differential test (outside this package) can confirm the memo path is exercised. */
object JitFoldTcStats:
  val hits = new java.util.concurrent.atomic.AtomicLong(0L)

/** Expression evaluator: the core `eval(term, env, interp)` dispatch,
 *  plus the `evalArgs`, `collectApplyArgs`, and `threadValues` helpers.
 */
private[interpreter] object EvalRuntime:

  /** Opt-in fused-range-chain detection.  Disabled by default — the
   *  runtime pattern-check on Term.Apply slightly regressed range-sum
   *  and list-fold while only helping streams-pipeline.  Enable with
   *  `SSC_FUSED_RANGE=1` to experiment, or wire the detection into
   *  BytecodeJIT pre-pass for a real win. */
  val _FusedRangeEnabled: Boolean = sys.env.get("SSC_FUSED_RANGE").contains("1")

  private def cachedLiteralValue(lit: Lit, interp: Interpreter): Value | Null =
    val cached = interp.litCache.get(lit)
    if cached != null then
      cached match
        case Pure(v) => v
        case _       => null
    else
      val c = lit match
        case Lit.Int(v)     => Computation.pureIntV(v.toLong)
        case Lit.Long(v)    => Computation.pureIntV(v)
        case Lit.Double(v)  => Pure(Value.doubleV(v.toString.toDouble))
        case Lit.Float(v)   => Pure(Value.doubleV(v.toString.toDouble))
        case Lit.Boolean(v) => Computation.pureBool(v)
        case _              => null
      if c == null then null
      else
        interp.litCache.put(lit, c)
        c.asInstanceOf[Pure].value

  private def fastValue(term: Term, env: Env, interp: Interpreter): Value | Null =
    term match
      case tn: Term.Name =>
        val ev = env.getOrElse(tn.value, null)
        if ev != null then ev else interp.globals.getOrElse(tn.value, null)
      case lit: Lit =>
        cachedLiteralValue(lit, interp)
      case _ => null

  // Tuple-free: arithmetic/ordering shared with DispatchRuntime.numericFast; the
  // Double/Bool equality and Bool short-circuit cases are kept here because
  // numericFast deliberately leaves them to the general path.
  private def fastPrimitiveInfix(lhs: Value, op: String, rhs: Value): Computation | Null =
    val nf = DispatchRuntime.numericFast(lhs, op, rhs)
    if nf != null then nf
    else lhs match
      case Value.DoubleV(a) => rhs match
        case Value.DoubleV(b) => op match
          case "==" => Computation.pureBool(a == b)
          case "!=" => Computation.pureBool(a != b)
          case _    => null
        case _ => null
      case Value.BoolV(a) => rhs match
        case Value.BoolV(b) => op match
          case "&&" => Computation.pureBool(a && b)
          case "||" => Computation.pureBool(a || b)
          case "==" => Computation.pureBool(a == b)
          case "!=" => Computation.pureBool(a != b)
          case _    => null
        case _ => null
      case _ => null

  private def fastPrimitiveInfixTerm(lhs: Term, op: String, rhs: Term, env: Env, interp: Interpreter): Computation | Null =
    val lv = fastPrimitiveValue(lhs, env, interp)
    if lv == null then null
    else
      val rv = fastPrimitiveValue(rhs, env, interp)
      if rv == null then null else fastPrimitiveInfix(lv, op, rv)

  /** Value-returning twin of `fastPrimitiveInfix`: same coverage, but yields the
   *  raw `Value` (or null) with no `Pure` wrapper. The hot while-assign loop only
   *  needs the Value, so this avoids allocating one throwaway `Pure` per infix
   *  per iteration (≈3 per arithLoop step). Mirrors `fastPrimitiveInfix` exactly:
   *  `numericFastValue` first, then the Double-eq / Bool cases it leaves out. */
  private def fastPrimitiveInfixValue(lhs: Value, op: String, rhs: Value): Value | Null =
    val nf = DispatchRuntime.numericFastValue(lhs, op, rhs)
    if nf != null then nf
    else lhs match
      case Value.DoubleV(a) => rhs match
        case Value.DoubleV(b) => op match
          case "==" => Value.boolV(a == b)
          case "!=" => Value.boolV(a != b)
          case _    => null
        case _ => null
      case Value.BoolV(a) => rhs match
        case Value.BoolV(b) => op match
          case "&&" => Value.boolV(a && b)
          case "||" => Value.boolV(a || b)
          case "==" => Value.boolV(a == b)
          case "!=" => Value.boolV(a != b)
          case _    => null
        case _ => null
      case _ => null

  /** `private[interpreter]` (effect-cps-continuation): `BlockRuntime.step` uses this to bind a
   *  pure `val` rhs to a bare Value without the `evalCore` megamorphic dispatch + `Pure` alloc.
   *  Returns non-null ONLY for a provably side-effect-free expression (arith over names/literals,
   *  or a pure match/slot-bodied user-`FunV` call — effect ops are `NativeFnV` and bail, effectful
   *  fn bodies don't compile and bail), so a caller may treat non-null as "pure value, no effect". */
  private[interpreter] def fastPrimitiveValue(term: Term, env: Env, interp: Interpreter): Value | Null =
    term match
      // Type-test + direct field access (no `After_4_6_0` unapply) — the extractor allocates a
      // `Some` + `Tuple4` per visit, and this is on the per-resume continuation re-eval path
      // (effect-vm-cont-p3b: `a*a`, `b*b+sa`, … re-evaluated across 3125 multi-shot paths).
      case ai: Term.ApplyInfix if ai.argClause.values.lengthCompare(1) == 0 =>
        val lv = fastPrimitiveValue(ai.lhs, env, interp)
        if lv == null then null
        else
          val rv = fastPrimitiveValue(ai.argClause.values.head, env, interp)
          if rv == null then null else fastPrimitiveInfixValue(lv, ai.op.value, rv)
      // Direct-style call of a pure match-bodied function (e.g. `area(s)`):
      // folds the call to a bare Value so an enclosing pure expression such as
      // `total + area(s)` needs no Pure wrapper for the call result and no
      // FlatMap to thread it. Bails (null) for anything outside the pure subset.
      case app: Term.Apply =>
        pureCallValue(app, env, interp)
      case _ => fastValue(term, env, interp)

  /** Evaluate `f(x)`/`f(x, y)` to a bare Value when `f` is a simple, pure,
   *  match-bodied user function and the matched arm folds to a Value — with no
   *  `Computation`/`Pure` allocation and no call-stack push (a pure arm cannot
   *  raise a located error). Returns null to fall back to the monadic call path,
   *  which preserves effects, TCO/JIT, error traces, and Match-failure reporting.
   *  Targets the ADT-pattern-match allocation floor. */
  private def pureCallValue(app: Term.Apply, env: Env, interp: Interpreter): Value | Null =
    if interp.debugHooks.nonEmpty then return null
    val fun = app.fun
    if fun.isInstanceOf[Term.Apply] then return null   // curried call → monadic
    val fv = fastValue(fun, env, interp)
    if !fv.isInstanceOf[Value.FunV] then return null
    val f    = fv.asInstanceOf[Value.FunV]
    // Function-shape guards (mirror callValue1Slow/callValue2Slow): exact arity,
    // no using-params, no throws-wrapping, no defaults, no varargs.
    if f.usingParams.nonEmpty || f.returnsThrows then return null
    if f.defaults.nonEmpty && f.defaults.exists(_.nonEmpty) then return null
    if f.paramTypes.exists(_.endsWith("*")) then return null
    val args     = app.argClause.values
    val nparams  = f.params.length
    if nparams != args.length || nparams < 1 || nparams > 2 then return null
    val body = f.body
    if body.isInstanceOf[Term.Match] then
      pureCallValueMatch(f, body.asInstanceOf[Term.Match], args, env, interp, nparams)
    else
      pureCallValueGeneric(f, body, args, env, interp, nparams)

  /** Direct-style match-bodied path — the original `pureCallValue` core. */
  private def pureCallValueMatch(
    f: Value.FunV, mt: Term.Match, args: List[Term], env: Env, interp: Interpreter, nparams: Int
  ): Value | Null =
    var compiled = interp.matchCache.get(mt)
    if compiled == null then
      compiled = PatternRuntime.compileMatch(mt, interp)
      interp.matchCache.put(mt, compiled)
    if !compiled.valueCapable then return null
    val withSelf: Env = if f.name.nonEmpty then interp.closureWithSelfFor(f) else f.closure
    if nparams == 1 then
      val a = fastPrimitiveValue(args.head, env, interp)
      if a == null then return null
      val paramName = f.params.head
      val freeNames = compiled.slotFreeNames
      if compiled.allSlot && freeNames != null && !freeNames.contains(paramName)
         && (mt.expr match { case Term.Name(nm) => nm == paramName; case _ => false }) then
        compiled.runValue(a, withSelf)
      else
        val callEnv = FrameMap.one(paramName, a, withSelf)
        val scrut   = fastValue(mt.expr, callEnv, interp)
        if scrut == null then return null
        compiled.runValue(scrut, callEnv)
    else
      val a = fastPrimitiveValue(args.head, env, interp)
      if a == null then return null
      val b = fastPrimitiveValue(args(1), env, interp)
      if b == null then return null
      val callEnv = FrameMap.two(f.params.head, a, f.params(1), b, withSelf)
      val scrut   = fastValue(mt.expr, callEnv, interp)
      if scrut == null then return null
      compiled.runValue(scrut, callEnv)

  /** A4 (Tier-2b pure-call) generalization: direct-style runner for any
   *  function whose body is in `PatternRuntime.compileSlotBody`'s supported
   *  subset (literals, name lookups, primitive arith / comparison) — not just
   *  `Term.Match`. Compile-time-cached by AST identity in `interp.pureBodyCache`.
   *  Returns the result as a bare `Value`; the param slot is passed positionally
   *  (`v0`/`v1`) so no per-call `FrameMap.one`/`two` is allocated. Bails to
   *  null for any unsupported body shape — the caller (`fastPrimitiveValue` /
   *  the monadic path) handles those.
   */
  private def pureCallValueGeneric(
    f: Value.FunV, body: Term, args: List[Term], env: Env, interp: Interpreter, nparams: Int
  ): Value | Null =
    val cached = interp.pureBodyCache.get(body)
    val slotBody: PatternRuntime.SlotBody =
      if cached != null then
        if cached eq PatternRuntime.PureBodyMiss then return null
        cached.asInstanceOf[PatternRuntime.SlotBody]
      else
        val n0 = f.params.head
        val n1: String | Null = if nparams >= 2 then f.params(1) else null
        val sb = PatternRuntime.compileSlotBody(body, n0, n1, interp)
        interp.pureBodyCache.put(body, if sb == null then PatternRuntime.PureBodyMiss else sb.asInstanceOf[AnyRef])
        if sb == null then return null else sb
    val withSelf: Env = if f.name.nonEmpty then interp.closureWithSelfFor(f) else f.closure
    if nparams == 1 then
      val a = fastPrimitiveValue(args.head, env, interp)
      if a == null then return null
      slotBody(a, null, withSelf)
    else
      val a = fastPrimitiveValue(args.head, env, interp)
      if a == null then return null
      val b = fastPrimitiveValue(args(1), env, interp)
      if b == null then return null
      slotBody(a, b, withSelf)

  private final class FastAssignBody(val names: Array[String], val rhs: Array[Term])

  /** Sentinel stored in `Interpreter.whileJitCache` to indicate a previous
   *  compile attempt for a while loop failed — avoids re-attempting. */
  private[interpreter] val WhileJitMiss: AnyRef = new AnyRef

  /** Try to run a `FastAssignBody` while loop via JIT-compiled bytecode.
   *
   *  On the first call for a given `Term.While`, attempts to compile the loop
   *  condition and all-int assigns to a Java `static void run(long[] v)` method
   *  via `BytecodeJit.tryCompileWhileLong`. Subsequent calls use the cached
   *  `WhileJitEntry`; a cached `WhileJitMiss` sentinel skips immediately.
   *
   *  When the compiled loop calls JIT-compiled `ObjToLong` functions with
   *  val-bound InstanceV arguments, `entry.refNames` is non-empty.  The
   *  invocation wraps the `method.invoke` in `JitGlobals.withRefs` so the
   *  generated code can read `_rN` and `_fnM` from TLS.
   *
   *  Precondition: the condition was already found to be true (caller checked).
   *  The compiled method runs the FULL remaining loop from current slot values.
   *
   *  Returns `PureUnit` on success, null to fall back to `tryLongWhileAssign`. */
  private def tryWhileJit(
    t:         Term.While,
    body:      FastAssignBody,
    frameView: MutableEnvView,
    interp:    Interpreter
  ): Computation | Null =
    val cached = interp.whileJitCache.get(t)
    val entry: scalascript.interpreter.vm.jit.WhileJitEntry | Null =
      if cached != null then
        if cached eq WhileJitMiss then return null
        else cached.asInstanceOf[scalascript.interpreter.vm.jit.WhileJitEntry]
      else
        val localRefs: Map[String, Value] =
          frameView.underlying.iterator.collect {
            case (k, v: Value.InstanceV) => (k, v: Value)
            // Stage 9 lambda-value-solo: surface FunV locals so the JIT
            // backend can inline val-bound lambda call sites in while bodies.
            case (k, v: Value.FunV)      => (k, v: Value)
          }.toMap
        // A while-loop codegen bug (e.g. a walkLocalSlotCtx recursion →
        // StackOverflowError on some shapes) must bail to the tree-walk loop, never
        // crash the program.
        val e =
          try scalascript.interpreter.vm.jit.JitBackend.default.tryCompileWhileLong(
                t.expr, body.names, body.rhs, interp, localRefs)
          catch case _: Throwable => null
        interp.whileJitCache.put(t, if e == null then WhileJitMiss else e.asInstanceOf[AnyRef])
        e
    if entry == null then return null
    val n = body.names.length
    val args = entry.cachedSlots
    var k = 0
    while k < n do
      (frameView.getOrElse(body.names(k), null) match
        case null => interp.globals.getOrElse(body.names(k), null)
        case v    => v) match
      case Value.IntV(v) => args(k) = v
      case _             => return null
      k += 1
    if entry.refNames.length > 0 || entry.refObjFns.length > 0 then
      // Collect InstanceV values for each ref name.  Names are either simple
      // ("item") or dotted ("item.field") — the dot form does a two-level
      // globals → InstanceV.fields lookup.  Bail if any slot no longer holds.
      val refs = new Array[AnyRef](entry.refNames.length)
      var ri = 0
      while ri < entry.refNames.length do
        val name   = entry.refNames(ri)
        val dotIdx = name.indexOf('.')
        val v: Value | Null =
          if dotIdx < 0 then
            frameView.getOrElse(name, null) match
              case null => interp.globals.getOrElse(name, null)
              case fv   => fv
          else
            val base  = name.substring(0, dotIdx)
            val field = name.substring(dotIdx + 1)
            (frameView.getOrElse(base, null) match
              case null => interp.globals.getOrElse(base, null)
              case fv   => fv) match
              case inst: Value.InstanceV =>
                val arr = inst.fieldsArr
                if arr != null then
                  val fo = interp.typeFieldOrder.getOrElse(inst.typeName, Nil)
                  val idx = fo.indexOf(field)
                  if idx >= 0 && idx < arr.length then arr(idx) else null
                else inst.fields.getOrElse(field, null)
              case _ => null
        v match
          case v: Value.InstanceV => refs(ri) = v.asInstanceOf[AnyRef]
          case _                  => return null
        ri += 1
      try
        scalascript.interpreter.vm.jit.JitGlobals.withInterp(interp) {
          scalascript.interpreter.vm.jit.JitGlobals.withRefs(refs, entry.refFns, entry.refObjFns) {
            entry.runFn.run(args)
          }
        }
      catch
        case _: Throwable => return null
    else
      try
        scalascript.interpreter.vm.jit.JitGlobals.withInterp(interp) {
          entry.runFn.run(args)
        }
      catch
        case _: Throwable => return null
    val local = frameView.underlying
    k = 0
    while k < n do
      val v = Value.intV(args(k))
      local(body.names(k)) = v
      interp.globals(body.names(k)) = v
      k += 1
    Computation.PureUnit

  /** Fused while + foreach JIT path.  Compiles (once) the outer while + inner
   *  list-foreach into a single Java method; invokes it with the list receiver
   *  and JIT-compiled inner function wired via TLS.
   *
   *  `accIsDouble` true → Double accumulator stored as raw bits in slots[n].
   *  `accIsDouble` false → Long (Int) accumulator stored raw in slots[n].
   *  The list receiver is resolved from globals by name at each call.
   *  Returns PureUnit on success; null to fall back to tryMixedLongWhile. */
  private def tryWhileJitMixed(
    t:              scala.meta.Term.While,
    body:           MixedAssignBody,
    foreachApplyIdx: Int,
    accName:        String,
    accIsDouble:    Boolean,
    accInitVal:     Double,
    frameView:      MutableEnvView,
    interp:         Interpreter
  ): Computation | Null =
    val foreachApply = body.leadingApplies(foreachApplyIdx)
    // Per-interpreter cache keyed on the foreach apply Term node.
    val cached = interp.whileMixedJitCache.get(foreachApply)
    val entry: scalascript.interpreter.vm.jit.WhileJitEntry | Null =
      if cached != null then
        if cached eq WhileJitMiss then return null
        else cached.asInstanceOf[scalascript.interpreter.vm.jit.WhileJitEntry]
      else
        val e =
          try scalascript.interpreter.vm.jit.JitBackend.default.tryCompileWhileMixed(
                t.expr, body.names, body.rhs,
                foreachApply, accName, accIsDouble, interp)
          catch case _: Throwable => null
        interp.whileMixedJitCache.put(foreachApply, if e == null then WhileJitMiss else e.asInstanceOf[AnyRef])
        e
    if entry == null then return null

    // Build slots: int assigns + accumulator.
    val n = body.names.length
    val slots = entry.cachedSlots
    var k = 0
    while k < n do
      (frameView.getOrElse(body.names(k), null) match
        case null => interp.globals.getOrElse(body.names(k), null)
        case v    => v) match
      case Value.IntV(v) => slots(k) = v
      case _             => return null
      k += 1
    val accSlotIdx = n
    slots(accSlotIdx) =
      if accIsDouble then java.lang.Double.doubleToRawLongBits(accInitVal)
      else interp.globals.getOrElse(accName, null) match
        case Value.IntV(v) => v
        case _             => return null

    // Resolve the receiver (ListV, SetV, or MapV) from globals at call time (val-bound, stable).
    val receiverVal: Value | Null =
      foreachApply match
        case ta: scala.meta.Term.Apply =>
          ta.fun match
            case scala.meta.Term.Select(qual, scala.meta.Term.Name("foreach")) =>
              qual match
                case scala.meta.Term.Name(n2) =>
                  interp.globals.getOrElse(n2, null) match
                    case lv: Value.ListV => lv
                    case sv: Value.SetV  => sv
                    case mv: Value.MapV  => mv
                    case _               => null
                case _ => null
            case _ => null
        case _ => null
    if receiverVal == null then return null

    // Pre-extract collection items into Object[] to avoid per-iteration allocation / virtual dispatch.
    val refObj: AnyRef = receiverVal match
      case mv: Value.MapV =>
        val arr: Array[Value] =
          if entry.mapIsKeyMode then mv.entries.keysIterator.toArray
          else mv.entries.valuesIterator.toArray
        arr.asInstanceOf[AnyRef]
      case lv: Value.ListV if entry.listPreExtract =>
        lv.items.toArray[AnyRef].asInstanceOf[AnyRef]
      case other => other.asInstanceOf[AnyRef]
    val refs = Array[AnyRef](refObj)
    try
      if accIsDouble then
        scalascript.interpreter.vm.jit.JitGlobals.withInterp(interp) {
          scalascript.interpreter.vm.jit.JitGlobals.withRefs(
            refs, Array.empty, Array.empty, entry.refDoubleFns
          ) {
            entry.runFn.run(slots)
          }
        }
      else
        scalascript.interpreter.vm.jit.JitGlobals.withInterp(interp) {
          scalascript.interpreter.vm.jit.JitGlobals.withRefs(
            refs, entry.refFns, Array.empty
          ) {
            entry.runFn.run(slots)
          }
        }
    catch
      case _: Throwable => return null

    // Write back int assigns.
    val local = frameView.underlying
    k = 0
    while k < n do
      val v = Value.intV(slots(k))
      local(body.names(k)) = v
      interp.globals(body.names(k)) = v
      k += 1
    // Write back accumulator.
    val accVal =
      if accIsDouble then Value.doubleV(java.lang.Double.longBitsToDouble(slots(accSlotIdx)))
      else Value.intV(slots(accSlotIdx))
    local(accName)         = accVal
    interp.globals(accName) = accVal
    Computation.PureUnit

  private final class OverlayEnvView(base: Env, overlay: mutable.HashMap[String, Value])
      extends scala.collection.immutable.AbstractMap[String, Value]:
    override def get(key: String): Option[Value] =
      overlay.get(key).orElse(base.get(key))
    override def getOrElse[V1 >: Value](key: String, default: => V1): V1 =
      val v = overlay.getOrElse(key, null)
      if v != null then v else base.getOrElse(key, default)
    override def contains(key: String): Boolean =
      overlay.contains(key) || base.contains(key)
    override def iterator: Iterator[(String, Value)] =
      overlay.iterator ++ base.iterator.filterNot { case (k, _) => overlay.contains(k) }
    override def updated[V1 >: Value](key: String, value: V1): Map[String, V1] =
      (base ++ overlay).updated(key, value)
    override def removed(key: String): Map[String, Value] =
      (base ++ overlay).removed(key)

  /** Flatten a STATIC tuple expression — `(a, b)` and `(a, b) ++ (c, d)` (any nesting
   *  of literal tuples joined by `++`) — into its component element terms. Returns
   *  null for anything that isn't a compile-time-known tuple shape. */
  private def tupleComponents(e: Term): List[Term] | Null = e match
    case t: Term.Tuple => t.args
    // `(a, b) ++ (c, d)` parses as `ApplyInfix((a,b), ++, ArgClause(c, d))` — the RHS
    // tuple is FLATTENED into the arg clause (Scala's `x op (a, b)` two-arg rule), so the
    // arg-clause values are exactly the right-hand components. Left side may itself be a
    // tuple or a nested `++`. Require ≥2 flattened args: a single arg (`t ++ x`) could be
    // a tuple-VALUED expression whose runtime arity we can't know statically — bail there
    // (keep the materialised tuple) so we never miscount components.
    case ai: Term.ApplyInfix if ai.op.value == "++" && ai.argClause.values.lengthCompare(2) >= 0 =>
      val lc = tupleComponents(ai.lhs)
      if lc == null then null else lc ++ ai.argClause.values
    case _ => null

  /** `"_3"` → 3, anything else → -1. */
  private def tupleIdx(sel: String): Int =
    if sel.length >= 2 && sel(0) == '_' && sel.substring(1).forall(_.isDigit) then sel.substring(1).toInt else -1

  private def containsName(t: Tree, name: String): Boolean =
    var found = false
    t.traverse { case Term.Name(`name`) => found = true }
    found

  /** Free names appearing anywhere in `e` (includes operator names — harmless for the
   *  reassignment check, which only intersects with assignment LHS names). */
  private def freeNames(e: Term): Set[String] =
    val s = scala.collection.mutable.Set.empty[String]
    e.traverse { case Term.Name(n) => s += n }
    s.toSet

  private def isArithOp(op: String): Boolean =
    op == "+" || op == "-" || op == "*" || op == "/" || op == "%"

  private def isCompareOp(op: String): Boolean =
    op == "<" || op == ">" || op == "<=" || op == ">=" || op == "==" || op == "!="

  /** Side-effect-free boolean condition: comparisons of pure-arith operands, `&&`/`||` of
   *  pure conditions, unary `!`, and bool literals/names. Admits a pure `if` into
   *  [[isPureArith]] (a conditional intermediate). */
  private def isPureCond(c: Term): Boolean = c match
    case _: Lit | _: Term.Name => true
    case ai: Term.ApplyInfix if isCompareOp(ai.op.value) =>
      isPureArith(ai.lhs) && ai.argClause.values.forall(isPureArith)
    case ai: Term.ApplyInfix if ai.op.value == "&&" || ai.op.value == "||" =>
      isPureCond(ai.lhs) && ai.argClause.values.forall(isPureCond)
    case Term.ApplyUnary(op, arg) if op.value == "!" => isPureCond(arg)
    case _ => false

  /** True for a side-effect-free numeric expression — `Lit`, `Term.Name`, arithmetic
   *  `ApplyInfix`, unary `+/-`, the total pure numeric conversions `.toInt/.toLong/.toDouble`,
   *  and a pure `if c then a else b` (conditional intermediate). Excludes `Term.Apply` (a call
   *  may have side effects, and inlining would duplicate it) and other selects. Such an expr
   *  may be freely duplicated, so a `val x = <isPureArith>` binding used only as a whole `x`
   *  can be inlined. (Inlining only GATES on this — the substituted body still bails to
   *  tree-walk if the while-JIT can't compile it, so this is purely about correctness.) */
  /** Side-effect-free pattern match: pure scrutinee, every guard pure (or none), every case
   *  body pure-arith. Pattern matching + binding is itself side-effect-free, so such a `match`
   *  may be admitted to [[isPureArith]] (a `val x = p match { … }` intermediate). */
  private def isPureMatch(m: Term.Match): Boolean =
    isPureArith(m.expr) && m.casesBlock.cases.forall { c =>
      c.cond.forall(isPureCond) && isPureArith(c.body)
    }

  private def isPureArith(e: Term): Boolean = e match
    case _: Lit | _: Term.Name    => true
    case ai: Term.ApplyInfix      =>
      isArithOp(ai.op.value) && isPureArith(ai.lhs) && ai.argClause.values.forall(isPureArith)
    case Term.ApplyUnary(op, arg) => (op.value == "-" || op.value == "+") && isPureArith(arg)
    case Term.Select(qual, Term.Name("toInt" | "toLong" | "toDouble")) => isPureArith(qual)
    case ti: Term.If              => isPureCond(ti.cond) && isPureArith(ti.thenp) && isPureArith(ti.elsep)
    case m: Term.Match            => isPureMatch(m)
    case _                        => false

  /** Substitute a leading loop-body `val name = …` away in `rhs`. With `comps` (the val
   *  is a static tuple) every `name._K` accessor is replaced by the K-th component; with
   *  `scalar` (the val is a pure-arith expr) every bare `name` is replaced by that expr.
   *  Returns null when `name` is used in an unsupported way (a bare tuple reference, an
   *  out-of-range `_K`, or an unhandled shape) so the caller keeps the materialised value.
   *  Manual rewrite (scalameta `Tree.transform` isn't available here) modelled on
   *  `scalascript.transform.DirectAnorm`. */
  private def substVal(rhs: Term, name: String, comps: List[Term] | Null, scalar: Term | Null): Term | Null =
    var bad = false
    def go(e: Term): Term =
      if bad then e
      else e match
        case Term.Select(Term.Name(`name`), Term.Name(sel)) if comps != null =>
          val k = tupleIdx(sel)
          if k >= 1 && k <= comps.length then comps(k - 1) else { bad = true; e }
        case Term.Name(`name`) =>
          if scalar != null then scalar else { bad = true; e }   // tuple: bare ref unsupported
        case _: Term.Name | _: Lit => e
        case Term.Select(qual, nm) =>
          val q2 = go(qual); if q2 eq qual then e else Term.Select(q2, nm)
        case Term.Apply.After_4_6_0(fun, ac) =>
          val f2 = go(fun); val a2 = ac.values.map(go)
          if (f2 eq fun) && a2.corresponds(ac.values)(_ eq _) then e
          else Term.Apply.After_4_6_0(f2, Term.ArgClause(a2, ac.mod))
        case Term.ApplyInfix.After_4_6_0(lhs, op, targs, ac) =>
          val l2 = go(lhs); val a2 = ac.values.map(go)
          if (l2 eq lhs) && a2.corresponds(ac.values)(_ eq _) then e
          else Term.ApplyInfix.After_4_6_0(l2, op, targs, Term.ArgClause(a2, ac.mod))
        case Term.ApplyUnary(op, arg) =>
          val a2 = go(arg); if a2 eq arg then e else Term.ApplyUnary(op, a2)
        case Term.Tuple(args) =>
          val a2 = args.map(go); if a2.corresponds(args)(_ eq _) then e else Term.Tuple(a2)
        case ti: Term.If =>
          val c2 = go(ti.cond); val t2 = go(ti.thenp); val el2 = go(ti.elsep)
          if (c2 eq ti.cond) && (t2 eq ti.thenp) && (el2 eq ti.elsep) then e
          else Term.If.After_4_4_0(c2, t2, el2, ti.mods)
        case other =>
          if containsName(other, name) then { bad = true; e } else e
    val out = go(rhs)
    if bad then null else out

  /** A leading loop-body `val` resolved to a pure inlinable form: either a static tuple
   *  (`comps`, substituted at `name._K`) or a pure-arith scalar (`scalar`, substituted at a
   *  bare `name`). `exprVars` are the loop variables it captures (after prior vals are
   *  folded in) — used for the reassignment soundness guard. */
  private final case class ResolvedVal(name: String, comps: List[Term] | Null,
                                       scalar: Term | Null, exprVars: Set[String])

  private def classifyVal(name: String, e: Term): ResolvedVal | Null =
    tupleComponents(e) match
      case comps: List[Term] => ResolvedVal(name, comps, null, comps.foldLeft(Set.empty[String])(_ ++ freeNames(_)))
      case null              => if isPureArith(e) then ResolvedVal(name, null, e, freeNames(e)) else null

  /** Fold the already-resolved (loop-var-only) vals into `e` so chained bindings like
   *  `val b = a * 2` (a a prior val) become loop-var-only too. Null on an unsupported use. */
  private def substPriorVals(e: Term, prior: List[ResolvedVal]): Term | Null =
    var cur: Term | Null = e
    val it = prior.iterator
    while it.hasNext && cur != null do
      val rv = it.next()
      if containsName(cur.asInstanceOf[Term], rv.name) then
        cur = substVal(cur.asInstanceOf[Term], rv.name, rv.comps, rv.scalar)
    cur

  private def collectFastAssignBody(body: Term): FastAssignBody | Null =
    def one(assign: Term.Assign): FastAssignBody | Null =
      if assign.lhs.isInstanceOf[Term.Name] then
        new FastAssignBody(Array(assign.lhs.asInstanceOf[Term.Name].value), Array(assign.rhs))
      else null

    // Peel ALL leading `val` bindings (`val x = …` and destructuring `val (a, b) = …`),
    // resolving each to an inlinable form (chained bindings are folded into later ones via
    // substPriorVals). Returns the resolved vals + the remaining (assignment) stats, or null
    // if any leading val isn't inlinable (→ caller bails to tree-walk, the prior behaviour).
    def peelVals(stats: List[scala.meta.Stat]): (List[ResolvedVal], List[scala.meta.Stat]) | Null =
      val resolved = scala.collection.mutable.ListBuffer.empty[ResolvedVal]
      var rest = stats
      var ok   = true
      while ok && rest.nonEmpty && rest.head.isInstanceOf[Defn.Val] do
        rest.head.asInstanceOf[Defn.Val] match
          case Defn.Val(_, List(Pat.Var(tn)), _, e0) =>
            substPriorVals(e0, resolved.toList) match
              case null      => ok = false
              case e: Term   =>
                classifyVal(tn.value, e) match
                  case null            => ok = false
                  case rv: ResolvedVal => resolved += rv; rest = rest.tail
          // Destructuring `val (a, b, …) = <static tuple>`: bind each pattern var to its
          // component (each then classified independently).
          case Defn.Val(_, List(Pat.Tuple(pats)), _, e0) if pats.forall(_.isInstanceOf[Pat.Var]) =>
            substPriorVals(e0, resolved.toList) match
              case null    => ok = false
              case e: Term =>
                tupleComponents(e) match
                  case comps: List[Term] if comps.length == pats.length =>
                    var j = 0
                    while ok && j < pats.length do
                      classifyVal(pats(j).asInstanceOf[Pat.Var].name.value, comps(j)) match
                        case null            => ok = false
                        case rv: ResolvedVal => resolved += rv
                      j += 1
                    if ok then rest = rest.tail
                  case _ => ok = false
          case _ => ok = false
      if !ok then null else (resolved.toList, rest)

    // Build a FastAssignBody from the assignment stats, inlining each resolved val into the
    // rhs it appears in. SOUNDNESS: a val captures its expr at the binding point, so inlining
    // a use is only valid if no variable of that val's expr was reassigned by an EARLIER
    // assignment (`val t=(i,..); i=i+1; s=s+t._1` would otherwise capture the post-increment i).
    // An unused val is NOT dropped (its evaluation — e.g. a `/`-by-zero — is preserved).
    def fromAssigns(stats: List[scala.meta.Stat], resolved: List[ResolvedVal]): FastAssignBody | Null =
      val n = stats.length
      if n == 0 then return null
      val names    = new Array[String](n)
      val rhsA     = new Array[Term](n)
      val assigned = scala.collection.mutable.Set.empty[String]
      val usedVals = scala.collection.mutable.Set.empty[String]
      var rest     = stats
      var i        = 0
      while rest.nonEmpty do
        rest.head match
          case assign: Term.Assign if assign.lhs.isInstanceOf[Term.Name] =>
            val lhsName = assign.lhs.asInstanceOf[Term.Name].value
            names(i) = lhsName
            var rhs: Term | Null = assign.rhs
            val it = resolved.iterator
            while it.hasNext && rhs != null do
              val rv = it.next()
              if containsName(rhs.asInstanceOf[Term], rv.name) then
                if rv.exprVars.exists(assigned.contains) then return null   // reassigned before use
                usedVals += rv.name
                rhs = substVal(rhs.asInstanceOf[Term], rv.name, rv.comps, rv.scalar)
            if rhs == null then return null
            rhsA(i) = rhs.asInstanceOf[Term]
            assigned += lhsName
          case _ => return null
        i += 1
        rest = rest.tail
      if resolved.exists(rv => !usedVals.contains(rv.name)) then return null   // don't drop an unused val
      new FastAssignBody(names, rhsA)

    body match
      case assign: Term.Assign => one(assign)
      case Term.Block(stats) if stats.nonEmpty =>
        peelVals(stats) match
          case null                  => null
          case (resolved, assigns)   => fromAssigns(assigns, resolved)
      case _ => null

  /** Body shape for a mixed while-loop: one or more leading no-result `Term.Apply`
   *  side-effect statements (e.g. `xs.foreach(...)` — handled cheaply when
   *  `FastTier` recognizes the closure shape) followed by one or more
   *  `Term.Assign(Term.Name, _)` int-typed updates (e.g. `i = i + 1`). Lets
   *  `tryMixedLongWhile` run the assigns in unboxed `Long` slot space while
   *  still threading the side-effect applies through `interp.eval`. */
  private final class MixedAssignBody(
    val leadingApplies: Array[Term],
    val names:          Array[String],
    val rhs:            Array[Term]
  )

  /** Pre-resolved receiver and closure FunV for a
   *  `receiver.foreach(closure)` leading apply. Allows `tryMixedLongWhile` to
   *  bypass the eval→evalApplyGeneral→dispatch path (which allocates
   *  `Pure(listValue)` per outer iteration) and call the matching
   *  `FastTier.try*AccumForeach*` directly. Receiver type + accumulator type
   *  are determined at pre-resolution time; `run` virtual-dispatches to the
   *  cell of the (List/Set/Map × Long/Double) table.
   *  Targets: patternMatchHeavy (List+Double), patternMatchSet (Set+Double),
   *  patternMatchWide (List+Long), mapForeach (Map+Long). */
  private sealed abstract class PreResolvedForeach:
    def applyIdx: Int
    def run(interp: Interpreter): Computation | Null
    /** Set the pre-hoisted accumulator slot (`tryFastWhileAssign` calls this
     *  right after constructing the PreResolved + creating the slot). Only the
     *  `Fast*` subclasses override; the non-Fast paths (which don't use the
     *  slot mechanism at all) leave it as a no-op. */
    def setCachedSlot(slot: Array[Long]): Unit = ()

  private final class PreResolvedListForeach(
    val applyIdx: Int,
    val list:     List[Value],
    val closure:  Value.FunV,
    val isDouble: Boolean
  ) extends PreResolvedForeach:
    def run(interp: Interpreter): Computation | Null =
      if isDouble then FastTier.tryDoubleAccumForeach(list, closure, interp)
      else            FastTier.tryLongAccumForeach(list, closure, interp)

  private final class PreResolvedSetForeach(
    val applyIdx: Int,
    val set:      scala.collection.immutable.Set[Value],
    val closure:  Value.FunV,
    val isDouble: Boolean
  ) extends PreResolvedForeach:
    def run(interp: Interpreter): Computation | Null =
      if isDouble then FastTier.tryDoubleAccumForeachSet(set, closure, interp)
      else            FastTier.tryLongAccumForeachSet(set, closure, interp)

  private final class PreResolvedMapForeach(
    val applyIdx: Int,
    val map:      scala.collection.immutable.Map[Value, Value],
    val closure:  Value.FunV,
    val isDouble: Boolean
  ) extends PreResolvedForeach:
    def run(interp: Interpreter): Computation | Null =
      if isDouble then FastTier.tryDoubleAccumForeachMap(map, closure, interp)
      else            FastTier.tryLongAccumForeachMap(map, closure, interp)

  // Fast variants: guards pre-resolved once at setup; run() calls straight to
  // the inner loop without re-running ~12 guard checks per outer iteration.

  private final class PreResolvedFastDoubleListForeach(
    val applyIdx: Int,
    val list:     List[Value],
    val resolved: FastTier.ResolvedDoubleAccum
  ) extends PreResolvedForeach:
    var cachedSlot: Array[Long] | Null = null
    override def setCachedSlot(slot: Array[Long]): Unit = cachedSlot = slot
    def run(interp: Interpreter): Computation | Null =
      FastTier.runDoubleAccumForeachFast(list, resolved, interp, cachedSlot)

  private final class PreResolvedFastLongListForeach(
    val applyIdx: Int,
    val list:     List[Value],
    val resolved: FastTier.ResolvedLongAccum
  ) extends PreResolvedForeach:
    var cachedSlot: Array[Long] | Null = null
    override def setCachedSlot(slot: Array[Long]): Unit = cachedSlot = slot
    def run(interp: Interpreter): Computation | Null =
      FastTier.runLongAccumForeachFast(list, resolved, interp, cachedSlot)

  private final class PreResolvedFastDoubleSetForeach(
    val applyIdx: Int,
    val set:      scala.collection.immutable.Set[Value],
    val resolved: FastTier.ResolvedDoubleAccum
  ) extends PreResolvedForeach:
    var cachedSlot: Array[Long] | Null = null
    override def setCachedSlot(slot: Array[Long]): Unit = cachedSlot = slot
    def run(interp: Interpreter): Computation | Null =
      FastTier.runDoubleAccumForeachSetFast(set, resolved, interp, cachedSlot)

  private final class PreResolvedFastLongSetForeach(
    val applyIdx: Int,
    val set:      scala.collection.immutable.Set[Value],
    val resolved: FastTier.ResolvedLongAccum
  ) extends PreResolvedForeach:
    var cachedSlot: Array[Long] | Null = null
    override def setCachedSlot(slot: Array[Long]): Unit = cachedSlot = slot
    def run(interp: Interpreter): Computation | Null =
      FastTier.runLongAccumForeachSetFast(set, resolved, interp, cachedSlot)

  private final class PreResolvedFastLongMapForeach(
    val applyIdx: Int,
    val map:      scala.collection.immutable.Map[Value, Value],
    val resolved: FastTier.ResolvedLongMapAccum
  ) extends PreResolvedForeach:
    var cachedSlot: Array[Long] | Null = null
    override def setCachedSlot(slot: Array[Long]): Unit = cachedSlot = slot
    def run(interp: Interpreter): Computation | Null =
      FastTier.runLongAccumForeachMapFast(map, resolved, interp, cachedSlot)

  private final class PreResolvedFastDoubleMapForeach(
    val applyIdx: Int,
    val map:      scala.collection.immutable.Map[Value, Value],
    val resolved: FastTier.ResolvedDoubleMapAccum
  ) extends PreResolvedForeach:
    var cachedSlot: Array[Long] | Null = null
    override def setCachedSlot(slot: Array[Long]): Unit = cachedSlot = slot
    def run(interp: Interpreter): Computation | Null =
      FastTier.runDoubleAccumForeachMapFast(map, resolved, interp, cachedSlot)

  /** Recognize `while cond do { apply1; ...; applyN; assign1; ...; assignM }`
   *  with `N ≥ 1` and `M ≥ 1`. Returns null if the body has no leading applies
   *  (use the plain `collectFastAssignBody` path), no trailing assigns, or any
   *  unsupported leading stmt. */
  private def collectMixedAssignBody(body: Term): MixedAssignBody | Null = body match
    case Term.Block(stats) if stats.nonEmpty =>
      // `stats` is `List[Stat]`; we narrow each entry to `Term.Apply` or
      // `Term.Assign` and cast to `Term` (both are Term subtypes).
      val arr = stats.toArray
      var firstAssign = 0
      while firstAssign < arr.length && !arr(firstAssign).isInstanceOf[Term.Assign] do
        if !arr(firstAssign).isInstanceOf[Term.Apply] then return null
        firstAssign += 1
      if firstAssign == 0 || firstAssign == arr.length then return null
      val applies = new Array[Term](firstAssign)
      var i = 0
      while i < firstAssign do
        applies(i) = arr(i).asInstanceOf[Term.Apply]
        i += 1
      val nAssigns = arr.length - firstAssign
      val names = new Array[String](nAssigns)
      val rhs   = new Array[Term](nAssigns)
      var j = 0
      while j < nAssigns do
        arr(firstAssign + j) match
          case a: Term.Assign if a.lhs.isInstanceOf[Term.Name] =>
            names(j) = a.lhs.asInstanceOf[Term.Name].value
            rhs(j) = a.rhs
          case _ => return null
        j += 1
      new MixedAssignBody(applies, names, rhs)
    case _ => null

  /** Try to pre-resolve the receiver and closure FunV for a
   *  `receiver.foreach(closure)` leading apply, choosing the matching
   *  PreResolvedForeach subclass based on receiver kind (ListV / SetV / MapV)
   *  and accumulator kind (DoubleV / IntV). `isDouble` is passed in by the
   *  caller from the peek result so this function doesn't re-classify.
   *  Returns null if pre-resolution is not possible (receiver isn't a stable
   *  global of a supported kind, is a loop-slot var, or the closure FunV
   *  can't be resolved). */
  private def tryPreResolveForeach(
    apply:           Term,
    applyIdx:        Int,
    isDouble:        Boolean,
    interp:          Interpreter,
    longAssignNames: Array[String],
    frameView:       MutableEnvView
  ): PreResolvedForeach | Null =
    apply match
      case ta: Term.Apply =>
        ta.fun match
          case sel: Term.Select if sel.name.value == "foreach" =>
            sel.qual match
              case recvTerm: Term.Name =>
                val recvStr = recvTerm.value
                // Only pre-resolve if receiver is not a slot-assigned loop var.
                var isAssigned = false
                var k = 0
                while k < longAssignNames.length do
                  if longAssignNames(k) == recvStr then isAssigned = true
                  k += 1
                if isAssigned then return null
                // Resolve the receiver from globals (immutable for this loop)
                // and dispatch to the matching subclass. Anything outside
                // {ListV, SetV, MapV} bails.
                val recvV = interp.globals.getOrElse(recvStr, null)
                if recvV == null then return null
                ta.argClause.values match
                  case List(fn: Term.Function) =>
                    val cached = interp.emptyClosureFunCache.get(fn)
                    val funV: Value.FunV | Null =
                      if cached != null then
                        cached match
                          case Pure(fv: Value.FunV) => fv
                          case _                    => null
                      else
                        interp.eval(fn, frameView) match
                          case Pure(fv: Value.FunV) => fv
                          case _                    => null
                    if funV == null then null
                    else
                      recvV match
                        case lv: Value.ListV =>
                          if isDouble then
                            val r = FastTier.tryResolveDoubleAccum(funV, interp)
                            if r != null then new PreResolvedFastDoubleListForeach(applyIdx, lv.items, r)
                            else new PreResolvedListForeach(applyIdx, lv.items, funV, isDouble)
                          else
                            val r = FastTier.tryResolveLongAccum(funV, interp)
                            if r != null then new PreResolvedFastLongListForeach(applyIdx, lv.items, r)
                            else new PreResolvedListForeach(applyIdx, lv.items, funV, isDouble)
                        case sv: Value.SetV =>
                          if isDouble then
                            val r = FastTier.tryResolveDoubleAccum(funV, interp)
                            if r != null then new PreResolvedFastDoubleSetForeach(applyIdx, sv.items, r)
                            else new PreResolvedSetForeach(applyIdx, sv.items, funV, isDouble)
                          else
                            val r = FastTier.tryResolveLongAccum(funV, interp)
                            if r != null then new PreResolvedFastLongSetForeach(applyIdx, sv.items, r)
                            else new PreResolvedSetForeach(applyIdx, sv.items, funV, isDouble)
                        case mv: Value.MapV =>
                          if isDouble then
                            val r = FastTier.tryResolveDoubleMapAccum(funV, interp)
                            if r != null then new PreResolvedFastDoubleMapForeach(applyIdx, mv.entries, r)
                            else new PreResolvedMapForeach(applyIdx, mv.entries, funV, isDouble)
                          else
                            val r = FastTier.tryResolveLongMapAccum(funV, interp)
                            if r != null then new PreResolvedFastLongMapForeach(applyIdx, mv.entries, r)
                            else new PreResolvedMapForeach(applyIdx, mv.entries, funV, isDouble)
                        case _ => null
                  case _ => null
              case _ => null
          case _ => null
      case _ => null

  /** True if `t` references any `Term.Name` whose value is in `names` (read OR
   *  write — conservative). Used to verify that the leading applies in
   *  `MixedAssignBody` do NOT touch any slot-assigned int var: if they did,
   *  the slot value would go out of sync with the frame between the apply
   *  (which reads through the frame) and the long-slot assign. */
  private def applyAccessesNames(t: scala.meta.Tree, names: Set[String]): Boolean =
    if names.isEmpty then false
    else
      var hit = false
      def visit(tree: scala.meta.Tree): Unit =
        if hit then ()
        else
          tree match
            case tn: Term.Name if names.contains(tn.value) => hit = true
            case _ => tree.children.foreach(visit)
      visit(t)
      hit

  /** True if `t` or any descendant is a `Term.Apply` (function call that can
   *  produce effects). `Term.ApplyInfix` (arithmetic / comparisons) is NOT
   *  matched — only named-call applications. Used by `tryStreamEmitWhileFast`
   *  to verify that counter RHSes / emit args are pure arithmetic and the
   *  fast path is safe to apply. */
  private def containsApply(t: scala.meta.Tree): Boolean = t match
    case _: Term.Apply => true
    case _             => t.children.exists(containsApply)

  /** Fast path for `runStream { [var i = v0; ...] while cond do { Stream.emit(expr); ...; i = i + n } }`.
   *  Compiles the loop to LExpr (unboxed Long slots) — bypasses both the Free Monad trampoline
   *  AND per-iteration `eval` dispatch overhead.  Emit args, counter RHSes, and the condition
   *  must all be pure integer arithmetic (no function calls); non-int emit args bail to the
   *  slow path.
   *
   *  Returns null if:
   *  - no while-loop pattern, or
   *  - any non-emit apply or function call is found in the counter/condition terms, or
   *  - any emit arg is non-integer or any init RHS is effectful.
   *  Caller falls back to `EffectHandlers.streamRun` on null. */
  private def tryStreamEmitWhileFast(
    bodyTerm: Term,
    env:      Env,
    interp:   Interpreter
  ): Computation | Null =
    if interp.debugHooks.nonEmpty then return null

    // Decompose body: optional Defn.Var init stats + terminal Term.While.
    val (initStats, whileTerm) = bodyTerm match
      case t: Term.While => (Nil, t)
      case blk: Term.Block if blk.stats.nonEmpty =>
        blk.stats.last match
          case t: Term.While => (blk.stats.dropRight(1), t)
          case _             => return null
      case _ => return null

    // While body must be a MixedAssignBody: leading Stream.emit applies + trailing assigns.
    val mixedBody = collectMixedAssignBody(whileTerm.body)
    if mixedBody == null || mixedBody.leadingApplies.isEmpty || mixedBody.names.isEmpty
    then return null

    // Collect emit arg terms; all leading applies must be Stream.emit(singleArg) with pure arg.
    val emitArgs = new Array[Term](mixedBody.leadingApplies.length)
    var ei = 0
    while ei < mixedBody.leadingApplies.length do
      mixedBody.leadingApplies(ei) match
        case ta: Term.Apply =>
          ta.fun match
            case Term.Select(q: Term.Name, nm: Term.Name)
                if q.value == "Stream" && nm.value == "emit" && ta.argClause.values.size == 1 =>
              if containsApply(ta.argClause.values.head) then return null
              emitArgs(ei) = ta.argClause.values.head
            case _ => return null
        case _ => return null
      ei += 1

    // Counter RHSes and condition must not contain function calls (pure arithmetic only).
    var ai = 0
    while ai < mixedBody.rhs.length do
      if containsApply(mixedBody.rhs(ai)) then return null
      ai += 1
    if containsApply(whileTerm.expr) then return null

    // Evaluate Defn.Var init stats to build the local frame.
    val local     = scala.collection.mutable.HashMap.empty[String, Value]
    val frameView = new MutableEnvView(local)
    var initRest  = initStats
    while initRest.nonEmpty do
      initRest.head match
        case Defn.Var.After_4_7_2(_, List(Pat.Var(nm)), _, rhs) =>
          val v: Value = eval(rhs, env, interp) match
            case Pure(pv) => pv
            case _        => return null  // effectful init — bail
          local(nm.value) = v
          interp.globals(nm.value) = v
        case _ => return null  // non-var init stat — bail
      initRest = initRest.tail

    // ── LExpr compile ────────────────────────────────────────────────────────
    val slotOfName = new SlotTable()
    def slotOf(name: String): Int =
      val existing = slotOfName.slotIndex(name)
      if existing >= 0 then existing
      else
        frameView.getOrElse(name, null) match
          case Value.IntV(_) => slotOfName.register(name)
          case _             => -1
    def compileExpr(term: Term): LExpr | Null = term match
      case Lit.Int(v)  => new LConst(v.toLong)
      case Lit.Long(v) => new LConst(v)
      case tn: Term.Name =>
        val s = slotOf(tn.value)
        if s < 0 then null else new LVar(s)
      case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
          if argClause.values.lengthCompare(1) == 0 =>
        val code = op.value match
          case "+" => 0
          case "-" => 1
          case "*" => 2
          case "/" => 3
          case "%" => 4
          case _   => -1
        if code < 0 then null
        else
          val l = compileExpr(lhs)
          if l == null then null
          else
            val r = compileExpr(argClause.values.head)
            if r == null then null else new LBin(code, l, r)
      case _ => null
    def compileCond(term: Term): LCond | Null = term match
      case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
          if argClause.values.lengthCompare(1) == 0 =>
        op.value match
          case "&&" =>
            val l = compileCond(lhs)
            if l == null then null
            else
              val r = compileCond(argClause.values.head)
              if r == null then null else new LAnd(l, r)
          case "||" =>
            val l = compileCond(lhs)
            if l == null then null
            else
              val r = compileCond(argClause.values.head)
              if r == null then null else new LOr(l, r)
          case other =>
            val code = other match
              case "<"  => 0
              case "<=" => 1
              case ">"  => 2
              case ">=" => 3
              case "==" => 4
              case "!=" => 5
              case _    => -1
            if code < 0 then null
            else
              val l = compileExpr(lhs)
              if l == null then null
              else
                val r = compileExpr(argClause.values.head)
                if r == null then null else new LCmp(code, l, r)
      case _ => null

    // Register assigned names (must be int-valued vars in the frame).
    var k = 0
    while k < mixedBody.names.length do
      if slotOf(mixedBody.names(k)) < 0 then return null
      k += 1
    // Compile emit arg expressions (bail if any arg is non-integer).
    val emitLExprs = new Array[LExpr](emitArgs.length)
    var ek = 0
    while ek < emitArgs.length do
      val c = compileExpr(emitArgs(ek))
      if c == null then return null
      emitLExprs(ek) = c
      ek += 1
    // Compile assign RHSes.
    val rhsL = new Array[LExpr](mixedBody.rhs.length)
    var j = 0
    while j < mixedBody.rhs.length do
      val c = compileExpr(mixedBody.rhs(j))
      if c == null then return null
      rhsL(j) = c
      j += 1
    // Compile condition.
    val condL = compileCond(whileTerm.expr)
    if condL == null then return null

    // Initial slot values from the local frame.
    val slots = new Array[Long](slotOfName.size)
    var idx = 0
    while idx < slotOfName.size do
      frameView.getOrElse(slotOfName.nameAt(idx), null) match
        case Value.IntV(v) => slots(idx) = v
        case _             => return null
      idx += 1
    val assignSlot = new Array[Int](mixedBody.names.length)
    var as = 0
    while as < mixedBody.names.length do
      assignSlot(as) = slotOfName.slotIndex(mixedBody.names(as))
      as += 1
    val refs: Array[AnyRef] = EmptyRefs

    // ── Try JIT-compiled emit loop (zero virtual dispatch, no Value wrapping) ──
    // allSlotNames maps slot index → name; used as the JIT's slot context.
    val allSlotNames = Array.tabulate(slotOfName.size)(slotOfName.nameAt)

    // Try JIT compile — cache ensures this only compiles once per body identity.
    // A codegen bug must bail to tree-walk, never crash the program.
    val emitRunner =
      try scalascript.interpreter.vm.jit.JitBackend.default
            .tryCompileWhileLongEmit(whileTerm.expr, emitArgs, allSlotNames, mixedBody.names.toArray, mixedBody.rhs.toArray, interp)
      catch case _: Throwable => null

    // Pre-allocate Long buffer. 65536 covers the vast majority of loop counts.
    // The JIT-generated inner loop has no bounds check; AIOOB is the safety
    // net for rare >65536-emit loops. After AIOOB, slots[] is still intact
    // (the generated Java writes back v[i] = _vi only at the end, so a
    // mid-loop exception leaves slots unchanged), so the LExpr fallback
    // starts from the correct initial state.
    val JIT_BUF_CAP = 65536

    var nElems:   Int                                              = 0
    var longBuf:  Array[Long] | Null                              = null
    var valueBuf: scala.collection.mutable.ArrayBuffer[Value] | Null = null

    if emitRunner != null then
      val lbuf  = new Array[Long](JIT_BUF_CAP)
      var jitOk = true
      try nElems = emitRunner.run(slots, lbuf, 0)
      catch case _: ArrayIndexOutOfBoundsException => jitOk = false
      if jitOk then longBuf = lbuf
      else
        // AIOOB: slots still holds original values. Fall back to LExpr loop.
        val vbuf = new scala.collection.mutable.ArrayBuffer[Value](JIT_BUF_CAP + 1024)
        while condL.eval(slots, refs) do
          var emitIdx = 0
          while emitIdx < emitLExprs.length do
            vbuf += Value.intV(emitLExprs(emitIdx).eval(slots, refs))
            emitIdx += 1
          var ri = 0
          while ri < rhsL.length do
            slots(assignSlot(ri)) = rhsL(ri).eval(slots, refs)
            ri += 1
        nElems = vbuf.length; valueBuf = vbuf
    else
      // LExpr fallback: LExpr dispatch + Value.intV wrapping per iteration.
      val vbuf = new scala.collection.mutable.ArrayBuffer[Value](1024)
      while condL.eval(slots, refs) do
        var emitIdx = 0
        while emitIdx < emitLExprs.length do
          vbuf += Value.intV(emitLExprs(emitIdx).eval(slots, refs))
          emitIdx += 1
        var ri = 0
        while ri < rhsL.length do
          slots(assignSlot(ri)) = rhsL(ri).eval(slots, refs)
          ri += 1
      nElems = vbuf.length; valueBuf = vbuf

    // Write back final slot values to the frame + globals.
    var w = 0
    while w < mixedBody.names.length do
      val nm = mixedBody.names(w)
      val v  = Value.intV(slots(assignSlot(w)))
      local(nm) = v
      interp.globals(nm) = v
      w += 1

    // Build result: (source, ())
    val fromFn = interp.globals.getOrElse("Source.from", null)
    val source: Value =
      if fromFn != null then
        // Dstreams plugin loaded — materialise to ListV for Source.from.
        val emitted: Value = if longBuf != null then
          var k = 0; val arr = new Array[Value](nElems)
          while k < nElems do { arr(k) = Value.intV(longBuf(k)); k += 1 }
          Value.ListV(arr.toList)
        else Value.ListV(valueBuf.nn.toList)
        try interp.invoke(fromFn, emitted :: Nil)
        catch case _: Throwable => emitted
      else
        // Dstreams plugin not loaded. Build SrcList with lazy materialisation —
        // runToList().length resolves via NativeFnV (O(1)); actual List[Value]
        // is deferred until user code calls .toList or iterates.
        val srcList = Value.InstanceV("SrcList", Map(
          "length"   -> Value.NativeFnV("SrcList.length",   Computation.pureFn { _ => Value.intV(nElems) }),
          "size"     -> Value.NativeFnV("SrcList.size",     Computation.pureFn { _ => Value.intV(nElems) }),
          "isEmpty"  -> Value.NativeFnV("SrcList.isEmpty",  Computation.pureFn { _ => Value.boolV(nElems == 0) }),
          "nonEmpty" -> Value.NativeFnV("SrcList.nonEmpty", Computation.pureFn { _ => Value.boolV(nElems > 0) }),
          "toList"   -> Value.NativeFnV("SrcList.toList",   Computation.pureFn { _ =>
            if longBuf != null then
              var k = 0; val arr = new Array[Value](nElems)
              while k < nElems do { arr(k) = Value.intV(longBuf(k)); k += 1 }
              Value.ListV(arr.toList)
            else Value.ListV(valueBuf.nn.toList)
          }),
        ))
        Value.InstanceV("Source", Map(
          "runToList" -> Value.NativeFnV("Source.runToList", Computation.pureFn { _ => srcList }),
          "length"    -> Value.intV(nElems)
        ))
    Pure(Value.TupleV(source :: Value.UnitV :: Nil))

  private def previewFastAssignIteration(body: FastAssignBody, cond: Term, env: Env, interp: Interpreter): java.lang.Boolean | Null =
    val overlay = mutable.HashMap.empty[String, Value]
    val preview = new OverlayEnvView(env, overlay)
    try
      var i = 0
      while i < body.names.length do
        val v = fastPrimitiveValue(body.rhs(i), preview, interp)
        if v == null then return null
        overlay(body.names(i)) = v
        i += 1
      fastPrimitiveValue(cond, preview, interp) match
        case Value.BoolV(next) => java.lang.Boolean.valueOf(next)
        case _                 => null
    catch
      case scala.util.control.NonFatal(_) => null

  /** Array-backed name→slot table used by `tryLongWhileAssign` /
   *  `tryMixedLongWhile` compile prologues. Replaces the previous
   *  `mutable.LinkedHashMap[String, Int]` to avoid `Integer` boxing on every
   *  `get` / `update`, which JFR showed as the top hot frame on
   *  `instanceFieldAccess` (`BoxesRunTime.boxToInteger <- slotOf$1`).
   *  Linear scan is faster than a HashMap probe for the small N (≤ 8
   *  typical) seen in real bench shapes, and zero Int allocations. */
  // Slot-kind tags. The single slot index space is shared by the parallel
  // Long bank and Ref bank (see SscVm's exec for the proven pattern); the
  // kind tag says which bank the slot's "real" value lives in. Phase 1
  // Commit 1 introduces the tags but only Long is populated; later commits
  // register Ref-kind slots when compileExpr accepts ref subterms.
  private val SlotKindLong:   Byte = 0
  private val SlotKindRef:    Byte = 1
  @scala.annotation.unused
  private val SlotKindDouble: Byte = 2

  /** Static empty Array[AnyRef] used as the `refs` argument of `LExpr.eval`
   *  when the enclosing loop has registered zero Ref-kind slots. Sharing a
   *  read-only singleton avoids per-loop-entry allocation; per-loop arrays
   *  are sized to `SlotTable.size` only when at least one Ref slot exists.
   *  Safe to share across threads because no LExpr.eval ever writes to
   *  `refs` — writes happen only at LRefExpr-result-store sites in the
   *  enclosing while loop. */
  private val EmptyRefs: Array[AnyRef] = new Array[AnyRef](0)

  private final class SlotTable(initialCapacity: Int = 8):
    private var names: Array[String] = new Array[String](initialCapacity)
    /** Parallel per-slot kind tag (SlotKindLong / Ref / Double). Defaults to
     *  Long; callers that register a ref-typed loop var use
     *  `registerKind(name, SlotKindRef)` so the eval loop knows to allocate
     *  the `refs` bank and store the value there. */
    private var kinds: Array[Byte]   = new Array[Byte](initialCapacity)
    private var _size: Int = 0
    private var _refCount: Int = 0
    def size: Int = _size
    /** Number of slots registered as `SlotKindRef`. Used by the while-loop
     *  entry to decide between `EmptyRefs` (zero) and a sized `Array[AnyRef]`. */
    def refCount: Int = _refCount
    /** Linear scan; -1 if absent. */
    def slotIndex(name: String): Int =
      var i = 0
      while i < _size do
        if names(i) == name then return i
        i += 1
      -1
    /** Register a name as a Long-kind slot; returns its slot index. Existing
     *  callers behave unchanged — Long is the default. */
    def register(name: String): Int = registerKind(name, SlotKindLong)
    /** Register a name with an explicit kind. If the name already exists,
     *  returns the existing index regardless of the requested kind (callers
     *  must check `kindAt` if they care). */
    def registerKind(name: String, kind: Byte): Int =
      val existing = slotIndex(name)
      if existing >= 0 then existing
      else
        if _size == names.length then
          val grown  = new Array[String](names.length * 2)
          val grownK = new Array[Byte](names.length * 2)
          System.arraycopy(names, 0, grown, 0, _size)
          System.arraycopy(kinds, 0, grownK, 0, _size)
          names = grown
          kinds = grownK
        names(_size) = name
        kinds(_size) = kind
        if kind == SlotKindRef then _refCount += 1
        val i = _size
        _size += 1
        i
    def contains(name: String): Boolean = slotIndex(name) >= 0
    /** Name at slot index. */
    def nameAt(idx: Int): String = names(idx)
    /** Kind tag at slot index. */
    def kindAt(idx: Int): Byte = kinds(idx)

  // ── Dual-bank fast path for integer/ref while-assign loops ─────────────
  // A while-loop whose body is `name = <int-arith>` assignments runs entirely
  // in unboxed `long` slots, boxing each var back to a pooled IntV only once
  // on loop exit. The `refs: Array[AnyRef]` parallel bank (added in Phase 1
  // Commit 1 of the dual-bank LExpr roadmap) lets ref-typed subterms — an
  // `InstanceV` argument, a field-extract result, a ref-returning fn call —
  // participate in the same compiled LExpr fold instead of forcing a tree-walk
  // bail. Commit 1 reshapes the eval contract; later commits populate
  // `LRefExpr` subtypes that actually read/write `refs`. Mirrors
  // `SscVm.exec(stack, refStack, base)` proven design.
  private sealed abstract class LExpr:
    def eval(slots: Array[Long], refs: Array[AnyRef]): Long
  /** Ref-returning sibling of `LExpr`. Lets ref subterms (val-bound
   *  InstanceV args, ref slots, field-extract results, ref-returning
   *  fn calls) compose inside the LExpr fold without crossing a
   *  tree-walk boundary. Phase 1 Commit 2: LRefConst + LRefVar.
   *  Commit 3: LRefFieldGet, LApplyR1ToRef, LRefMatch. */
  private sealed abstract class LRefExpr:
    def eval(slots: Array[Long], refs: Array[AnyRef]): AnyRef

  /** Compile-time snapshot of a val-bound ref. `interp.valNames`
   *  (populated by Defn.Val at module load) guarantees the value can't be
   *  reassigned, so the per-iter eval skips the HashMap probe entirely. */
  private final class LRefConst(v: AnyRef) extends LRefExpr:
    def eval(slots: Array[Long], refs: Array[AnyRef]): AnyRef = v

  /** Read a ref slot from the parallel refs bank. Populated when ref-typed
   *  loop vars are registered via `SlotTable.registerKind(_, SlotKindRef)`. */
  @scala.annotation.unused
  private final class LRefVar(idx: Int) extends LRefExpr:
    def eval(slots: Array[Long], refs: Array[AnyRef]): AnyRef = refs(idx)

  /** `instExpr.fieldName` as a ref expression. `fieldIdx` is resolved at
   *  compile time from `interp.typeFieldOrder(typeName)`, so eval is one
   *  ref-deref + one array index — no String hashing, no Map lookup. At
   *  runtime the receiver must be an `InstanceV` with a non-null
   *  `fieldsArr`; both mismatches throw `NotDouble` so the enclosing
   *  while-loop falls back to tree-walk and re-runs correctly. */
  private final class LRefFieldGet(instR: LRefExpr, fieldIdx: Int) extends LRefExpr:
    def eval(slots: Array[Long], refs: Array[AnyRef]): AnyRef =
      instR.eval(slots, refs) match
        case inst: Value.InstanceV =>
          val arr = inst.fieldsArr
          if arr ne null then arr(fieldIdx)
          else throw PatternRuntime.NotDouble
        case _ => throw PatternRuntime.NotDouble

  /** Ref-returning match in LRefExpr position. Scrutinee is any `LRefExpr`
   *  (LRefConst, LRefFieldGet, …). `cm.runValue` must succeed (returns non-null)
   *  or the enclosing while-loop falls back to tree-walk via `NotDouble`.
   *  Only wired when `cm.valueCapable` is true. */
  private final class LRefMatch(scrutR: LRefExpr, cm: PatternRuntime.CompiledMatch) extends LRefExpr:
    def eval(slots: Array[Long], refs: Array[AnyRef]): AnyRef =
      val scrutV = scrutR.eval(slots, refs).asInstanceOf[Value]
      val result = cm.runValue(scrutV, Map.empty)
      if result ne null then result else throw PatternRuntime.NotDouble

  private final class LConst(v: Long) extends LExpr:
    def eval(slots: Array[Long], refs: Array[AnyRef]): Long = v
  private final class LVar(idx: Int) extends LExpr:
    def eval(slots: Array[Long], refs: Array[AnyRef]): Long = slots(idx)
  private final class LBin(op: Int, l: LExpr, r: LExpr) extends LExpr:
    def eval(slots: Array[Long], refs: Array[AnyRef]): Long =
      val a = l.eval(slots, refs); val b = r.eval(slots, refs)
      op match
        case 0 => a + b
        case 1 => a - b
        case 2 => a * b
        case 3 => a / b
        case _ => a % b
  private sealed abstract class LCond:
    def eval(slots: Array[Long], refs: Array[AnyRef]): Boolean
  private final class LCmp(op: Int, l: LExpr, r: LExpr) extends LCond:
    def eval(slots: Array[Long], refs: Array[AnyRef]): Boolean =
      val a = l.eval(slots, refs); val b = r.eval(slots, refs)
      op match
        case 0 => a < b
        case 1 => a <= b
        case 2 => a > b
        case 3 => a >= b
        case 4 => a == b
        case _ => a != b
  private final class LAnd(l: LCond, r: LCond) extends LCond:
    def eval(slots: Array[Long], refs: Array[AnyRef]): Boolean =
      l.eval(slots, refs) && r.eval(slots, refs)
  private final class LOr(l: LCond, r: LCond) extends LCond:
    def eval(slots: Array[Long], refs: Array[AnyRef]): Boolean =
      l.eval(slots, refs) || r.eval(slots, refs)

  /** Primitive-Long-arg function interface used by `LApply` / `LApply2`.
   *  Scala's `Function2[Long, Env, Long]` / `Function3[Long, Long, Env, Long]`
   *  are NOT specialized for `(Long, AnyRef) => Long` shapes, so each call
   *  through them auto-boxes both the Long arg and the Long return via
   *  `BoxesRunTime.boxToLong`. JFR-2026-06-02 showed ~24 MB/op alloc on
   *  `pureCallSum`'s 1M-iter inner loop coming from this boxing. The custom
   *  trait below sidesteps the Function2/3 specialization gap. */
  private[interpreter] trait LongEnvFn1:
    def apply(arg: Long, env: Env): Long
  private[interpreter] trait LongEnvFn2:
    def apply(a0: Long, a1: Long, env: Env): Long

  /** Raw-Long inlined function call. Folds `f(x)` (a 1-param pure-bodied
   *  function whose body is in the `compileSlotD` arith subset) into the
   *  enclosing `tryLongWhileAssign` loop without boxing the arg or result.
   *
   *  At runtime: re-resolves `fnName` from `interp.globals` per call (so a
   *  user re-binding `f` mid-loop is observed correctly) and checks the
   *  current `fn.body eq expectedBody` — if the function was reassigned to a
   *  new body, throws `PatternRuntime.NotDouble` so `tryLongWhileAssign`
   *  catches and bails to tree-walk. */
  private final class LApply(
    argL:         LExpr,
    fnName:       String,
    expectedBody: Term,
    slotFn:       LongEnvFn1,
    interp:       Interpreter
  ) extends LExpr:
    def eval(slots: Array[Long], refs: Array[AnyRef]): Long =
      val arg = argL.eval(slots, refs)
      val fnV = interp.globals.getOrElse(fnName, null)
      fnV match
        case fn: Value.FunV if fn.body eq expectedBody =>
          slotFn(arg, fn.closure)
        case _ =>
          throw PatternRuntime.NotDouble

  /** Generalised ref-arg JIT fast path: `f(<ref-expr>)` where `f` is
   *  bytecode-JIT-compiled to an `ObjToLong` direct interface
   *  (paramIsRef[0] = true, result is Long), and the argument is *any*
   *  compileable LRefExpr (val snapshot, ref slot, field-get, ref-returning
   *  fn call). Replaces the original `LApplyObjRef` which only handled a
   *  bare val-bound `InstanceV` — now that the arg can be any ref subterm,
   *  composite shapes like `f(g(item))` or `f(item.field)` stay inside the
   *  LExpr fold instead of forcing a tree-walk break. Phase 1 Commit 2:
   *  the only LRefExpr compileRefExpr returns is LRefConst (val snapshot);
   *  Commit 3 wires LRefFieldGet + LApplyR1ToRef. */
  private final class LApplyR1(
    argR:  LRefExpr,
    objFn: scalascript.interpreter.vm.jit.ObjToLong
  ) extends LExpr:
    def eval(slots: Array[Long], refs: Array[AnyRef]): Long =
      objFn.apply(argR.eval(slots, refs))

  /** 2-arg ref-mixed fast path — `f(longExpr, refExpr)` where `f` is
   *  bytecode-JIT-compiled to a `LongObjToLong` direct interface (param 0
   *  is numeric Long, param 1 is ref InstanceV, result is Long).
   *  Targets `gEval(scale: Int, e: Expr): Int` in the recursiveEvalMixed
   *  bench shape — previously every outer-loop iteration tree-walked
   *  through evalCore because compileExpr's 2-arg apply path required the
   *  pure-arith `compileSlotLongFn2`, which a match-bodied `gEval` can't
   *  satisfy. */
  private final class LApplyR2LongObj(
    arg0L: LExpr,
    arg1R: LRefExpr,
    objFn: scalascript.interpreter.vm.jit.LongObjToLong
  ) extends LExpr:
    def eval(slots: Array[Long], refs: Array[AnyRef]): Long =
      objFn.apply(arg0L.eval(slots, refs), arg1R.eval(slots, refs))

  /** Symmetric variant — `f(refExpr, longExpr)` where param 0 is ref
   *  and param 1 is Long. Same lift as LApplyR2LongObj. */
  private final class LApplyR2ObjLong(
    arg0R: LRefExpr,
    arg1L: LExpr,
    objFn: scalascript.interpreter.vm.jit.ObjLongToLong
  ) extends LExpr:
    def eval(slots: Array[Long], refs: Array[AnyRef]): Long =
      objFn.apply(arg0R.eval(slots, refs), arg1L.eval(slots, refs))

  /** Ref-returning function call in LRefExpr position: `g(refExpr)` where `g`
   *  is bytecode-JIT-compiled to an `ObjToObject` direct interface (1 ref param,
   *  ref return). Enables `f(g(item))` chains where `g` extracts an ADT sub-field
   *  and `f` takes the result as a ref arg (→ LApplyR1).
   *  Phase 1 Commit 3 (dual-bank-lapply-r1-to-ref). */
  private final class LApplyR1ToRef(
    argR:  LRefExpr,
    objFn: scalascript.interpreter.vm.jit.ObjToObject
  ) extends LRefExpr:
    def eval(slots: Array[Long], refs: Array[AnyRef]): AnyRef =
      objFn.apply(argR.eval(slots, refs))

  /** 2-arg parallel of `LApply`. Folds `g(x, y)` into the enclosing unboxed-
   *  Long loop without boxing either arg or result. */
  private final class LApply2(
    arg0L:        LExpr,
    arg1L:        LExpr,
    fnName:       String,
    expectedBody: Term,
    slotFn:       LongEnvFn2,
    interp:       Interpreter
  ) extends LExpr:
    def eval(slots: Array[Long], refs: Array[AnyRef]): Long =
      val a0 = arg0L.eval(slots, refs)
      val a1 = arg1L.eval(slots, refs)
      val fnV = interp.globals.getOrElse(fnName, null)
      fnV match
        case fn: Value.FunV if fn.body eq expectedBody =>
          slotFn(a0, a1, fn.closure)
        case _ =>
          throw PatternRuntime.NotDouble

  /** Inlines a `p match { case Ctor(a, b) => a + b }` expression into the
   *  enclosing `tryLongWhileAssign` / `tryMixedLongWhile` loop without building
   *  a FrameMap or a Pure wrapper per iteration.  Preconditions (verified at
   *  compile time in `compileExpr`):
   *   - scrutinee is a `Term.Name` whose runtime value lives in `interp.globals`
   *   - the compiled match is `longCapable` (all arms fold to raw Long)
   *   - `cm.slotFreeNames` is non-null and doesn't overlap with any loop slot
   *     name (loop-slot vars live in the Long array, not in globals, so an
   *     overlap would yield stale data from globals — bail instead)
   *  At runtime, throws `PatternRuntime.NotDouble` to bail when the scrutinee
   *  is absent from globals or when `runValueLong` cannot complete (no arm
   *  matches, or a free-name lookup returns non-IntV). */
  private final class LMatch(
    scrutName: String,
    cm:        PatternRuntime.CompiledMatch,
    interp:    Interpreter,
    /** Snapshot of `interp.globals(scrutName)` taken at compile time when
     *  the scrutinee is a `val` (immutable for the loop's lifetime). When
     *  non-null, `eval` skips the per-iter HashMap probe and uses this
     *  value directly. For `var` scrutinees this is null and the original
     *  per-iter lookup runs so reassignments within the loop are seen. */
    cachedScrut: Value | Null = null
  ) extends LExpr:
    def eval(slots: Array[Long], refs: Array[AnyRef]): Long =
      val scrutV =
        if cachedScrut ne null then cachedScrut
        else
          val v = interp.globals.getOrElse(scrutName, null)
          if v == null then throw PatternRuntime.NotDouble
          v
      cm.runValueLong(scrutV, Map.empty)

  /** Pre-resolved 0-arg side-effect call node for `tryMixedLongWhile`'s inner loop.
   *  Compiled once per loop entry; eliminates the per-iter HashMap probe, FunV
   *  dispatch, Computation boxing, and callValue chain on each iteration. */
  private sealed trait LEffect:
    def run(interp: Interpreter): Unit

  /** FunV variant: pre-resolved user-defined 0-arg function.
   *  `closureEnv` = `closureWithSelfFor(fn)` captured at compile time. */
  private final class LApplyEffect(
    val fn:         Value.FunV,
    val closureEnv: Env
  ) extends LEffect:
    def run(interp: Interpreter): Unit =
      val relLine   = if interp.currentSpanLine >= 0 then interp.currentSpanLine + 1 else 0
      val frameName = if fn.name.nonEmpty then fn.name else "<anon>"
      interp.callStackPush(frameName, interp.debugSourceFile, interp.debugBlockDocLine + relLine)
      try
        val result = try TcoRuntime.runUntilSuspension(interp.eval(fn.body, closureEnv))
                     catch case r: ReturnSignal => Pure(r.value)
        result match
          case Pure(_) => ()
          case comp    => Computation.run(comp)
      finally
        if interp.callStackNonEmpty then interp.callStackPop()

  /** NativeFnV variant: direct dispatch, no FunV body / callStack overhead. */
  private final class LApplyEffectNative(val fn: Value.NativeFnV) extends LEffect:
    def run(interp: Interpreter): Unit = Computation.run(fn.f(Nil))

  /** Fold `while ctr OP bound do ctr = ctr + step` to `ctr = finalValue` in O(1).
   *  Only called when exactly one int-slot assignment remains after hoisting.
   *  Returns true and writes the terminal value to frame + globals if the
   *  pattern matched; false to fall through to the JIT / LExpr loop.
   *
   *  Pattern requirements:
   *  - RHS is `ctr + step` where step is a positive `Lit.Int`
   *  - Condition is `ctr < bound` or `ctr <= bound` where bound is `Lit.Int`
   *    or a val reference resolving to `Value.IntV`
   *  - Current counter value is `Value.IntV` (standard Int slot)
   *
   *  Safe because the caller (tryHoistedPureWhile) has already proven the body
   *  is all-assign with no Term.Apply — no side effects can occur mid-loop. */
  private def tryFoldCounterLoop(
    cond:      Term,
    ctrName:   String,
    rhs:       Term,
    frameView: MutableEnvView,
    interp:    Interpreter
  ): Boolean =
    val step: Long = rhs match
      case Term.ApplyInfix.After_4_6_0(Term.Name(`ctrName`), Term.Name("+"), _, ac)
          if ac.values.lengthCompare(1) == 0 =>
        ac.values.head match
          case Lit.Int(s) if s > 0 => s.toLong
          case _ => return false
      case _ => return false
    val (isLE, bound): (Boolean, Long) = cond match
      case Term.ApplyInfix.After_4_6_0(Term.Name(`ctrName`), Term.Name(op), _, ac)
          if ac.values.lengthCompare(1) == 0 && (op == "<" || op == "<=") =>
        val b: Long = ac.values.head match
          case Lit.Int(v) => v.toLong
          case Term.Name(n) if interp.valNames.contains(n) =>
            interp.globals.getOrElse(n, null) match
              case Value.IntV(v) => v
              case _ => return false
          case _ => return false
        (op == "<=", b)
      case _ => return false
    val start: Long = (frameView.getOrElse(ctrName, null) match
      case null => interp.globals.getOrElse(ctrName, null)
      case v    => v) match
    case Value.IntV(v) => v
    case _             => return false
    val exclusiveBound = if isLE then bound + 1L else bound
    val finalVal: Long =
      if start >= exclusiveBound then start
      else
        val iters = (exclusiveBound - start + step - 1L) / step
        start + iters * step
    val v = Value.intV(finalVal)
    val local = frameView.underlying
    local(ctrName) = v
    interp.globals(ctrName) = v
    true

  private final case class CounterFold(iterations: Long, finalValue: Long)

  private def stableRefArg(term: Term, interp: Interpreter): AnyRef | Null = term match
    case n: Term.Name if interp.valNames.contains(n.value) =>
      interp.globals.getOrElse(n.value, null) match
        case inst: Value.InstanceV => inst.asInstanceOf[AnyRef]
        case _                     => null
    case _ => null

  private def stableLongArg(term: Term, interp: Interpreter): java.lang.Long | Null = term match
    case Lit.Int(v)  => java.lang.Long.valueOf(v.toLong)
    case Lit.Long(v) => java.lang.Long.valueOf(v)
    case n: Term.Name if interp.valNames.contains(n.value) =>
      interp.globals.getOrElse(n.value, null) match
        case Value.IntV(v) => java.lang.Long.valueOf(v)
        case _             => null
    case _ => null

  /** Evaluate a loop-invariant, JIT-proven pure numeric call once.
   *
   *  This intentionally accepts only direct bytecode-JIT interfaces with stable
   *  literal / val-bound arguments. A miss returns null and the loop falls back
   *  to the normal while JIT / LExpr path, preserving effects and dynamic calls.
   */
  private def jitInvariantLong(term: Term, interp: Interpreter): java.lang.Long | Null =
    if interp.debugHooks.nonEmpty then return null
    term match
      case ap: Term.Apply =>
        ap.fun match
          case fnName: Term.Name =>
            interp.globals.getOrElse(fnName.value, null) match
              case fn: Value.FunV
                  if fn.usingParams.isEmpty && !fn.returnsThrows &&
                     (fn.defaults.isEmpty || fn.defaults.forall(_.isEmpty)) &&
                     (fn.paramTypes.isEmpty || !fn.paramTypes.exists(_.endsWith("*"))) =>
                val bcRes = scalascript.interpreter.vm.jit.JitBackend.default.tryCompile(fn, interp)
                if bcRes == null || bcRes.direct == null || bcRes.resultIsDouble then return null
                val args = ap.argClause.values
                try
                  if args.lengthCompare(1) == 0 && bcRes.paramIsRef.length == 1 && bcRes.paramIsRef(0) then
                    val ref = stableRefArg(args.head, interp)
                    if ref == null then null
                    else bcRes.direct match
                      case ot: scalascript.interpreter.vm.jit.ObjToLong =>
                        java.lang.Long.valueOf(
                          scalascript.interpreter.vm.jit.JitGlobals.withInterp(interp) {
                            ot.apply(ref)
                          }
                        )
                      case _ => null
                  else if args.lengthCompare(2) == 0 && bcRes.paramIsRef.length == 2 then
                    bcRes.direct match
                      case ot: scalascript.interpreter.vm.jit.LongObjToLong
                          if !bcRes.paramIsRef(0) && bcRes.paramIsRef(1) =>
                        val a0 = stableLongArg(args.head, interp)
                        val a1 = stableRefArg(args(1), interp)
                        if a0 == null || a1 == null then null
                        else java.lang.Long.valueOf(
                          scalascript.interpreter.vm.jit.JitGlobals.withInterp(interp) {
                            ot.apply(a0.longValue, a1)
                          }
                        )
                      case ot: scalascript.interpreter.vm.jit.ObjLongToLong
                          if bcRes.paramIsRef(0) && !bcRes.paramIsRef(1) =>
                        val a0 = stableRefArg(args.head, interp)
                        val a1 = stableLongArg(args(1), interp)
                        if a0 == null || a1 == null then null
                        else java.lang.Long.valueOf(
                          scalascript.interpreter.vm.jit.JitGlobals.withInterp(interp) {
                            ot.apply(a0, a1.longValue)
                          }
                        )
                      case _ => null
                  else null
                catch
                  case scala.util.control.NonFatal(_) => null
              case _ => null
          case _ => null
      case _ => null

  private def counterFoldInfo(
    cond:      Term,
    ctrName:   String,
    rhs:       Term,
    frameView: MutableEnvView,
    interp:    Interpreter
  ): CounterFold | Null =
    val step: Long = rhs match
      case Term.ApplyInfix.After_4_6_0(Term.Name(`ctrName`), Term.Name("+"), _, ac)
          if ac.values.lengthCompare(1) == 0 =>
        ac.values.head match
          case Lit.Int(s) if s > 0 => s.toLong
          case _                   => return null
      case _ => return null
    val (inclusive, bound): (Boolean, Long) = cond match
      case Term.ApplyInfix.After_4_6_0(Term.Name(`ctrName`), Term.Name(op), _, ac)
          if ac.values.lengthCompare(1) == 0 && (op == "<" || op == "<=") =>
        val b: Long = ac.values.head match
          case Lit.Int(v) => v.toLong
          case Term.Name(n) if interp.valNames.contains(n) =>
            interp.globals.getOrElse(n, null) match
              case Value.IntV(v) => v
              case _             => return null
          case _ => return null
        (op == "<=", b)
      case _ => return null
    val start: Long = (frameView.getOrElse(ctrName, null) match
      case null => interp.globals.getOrElse(ctrName, null)
      case v    => v) match
    case Value.IntV(v) => v
    case _             => return null
    val span = BigInt(bound) - BigInt(start) + (if inclusive then BigInt(1) else BigInt(0))
    if span <= 0 then CounterFold(0L, start)
    else
      val stepBig = BigInt(step)
      val iterationsBig = (span + stepBig - 1) / stepBig
      if iterationsBig > BigInt(Long.MaxValue) then return null
      val finalBig = BigInt(start) + iterationsBig * stepBig
      if finalBig < BigInt(Long.MinValue) || finalBig > BigInt(Long.MaxValue) then return null
      CounterFold(iterationsBig.toLong, finalBig.toLong)

  private def invariantAddend(accName: String, rhs: Term): Term | Null = rhs match
    case Term.ApplyInfix.After_4_6_0(lhs, Term.Name("+"), _, ac)
        if ac.values.lengthCompare(1) == 0 =>
      val rhsTerm = ac.values.head
      lhs match
        case Term.Name(`accName`) => rhsTerm
        case _ =>
          rhsTerm match
            case Term.Name(`accName`) => lhs
            case _                    => null
    case _ => null

  /** Fold `while i < N do { acc = acc + f(stableVal); i = i + step }`.
   *
   *  The addend is evaluated once only when it is a bytecode-JIT direct call
   *  with stable literal / val-bound arguments. This targets recursive ADT
   *  evaluators (`eval(tree)`, `gEval(scale, tree)`) without changing the
   *  effect-capable eval path.
   */
  private def tryFoldInvariantAccumLoop(
    t:         Term.While,
    body:      FastAssignBody,
    frameView: MutableEnvView,
    interp:    Interpreter
  ): Computation | Null =
    if body.names.length != 2 then return null
    var counterIdx = -1
    var counter: CounterFold | Null = null
    var idx = 0
    while idx < body.names.length do
      val cf = counterFoldInfo(t.expr, body.names(idx), body.rhs(idx), frameView, interp)
      if cf != null then
        if counterIdx >= 0 then return null
        counterIdx = idx
        counter = cf
      idx += 1
    if counterIdx < 0 || counter == null then return null
    val accIdx  = if counterIdx == 0 then 1 else 0
    val accName = body.names(accIdx)
    if accName == body.names(counterIdx) then return null
    // If the counter assignment precedes the accumulator, the accumulator RHS
    // reads the post-update counter. The fold below models pre-update
    // counter values, so leave order-dependent bodies to the sequential loop.
    if counterIdx < accIdx && freeNames(body.rhs(accIdx)).contains(body.names(counterIdx)) then
      return null
    val accStart = (frameView.getOrElse(accName, null) match
      case null => interp.globals.getOrElse(accName, null)
      case v    => v) match
    case Value.IntV(v) => v
    case _             => return null
    val addTerm = invariantAddend(accName, body.rhs(accIdx))
    if addTerm == null then return null
    val addValue = jitInvariantLong(addTerm, interp)
    if addValue == null then return null
    val finalAccBig = BigInt(accStart) + BigInt(addValue.longValue) * BigInt(counter.iterations)
    if finalAccBig < BigInt(Long.MinValue) || finalAccBig > BigInt(Long.MaxValue) then return null
    val local = frameView.underlying
    val accV = Value.intV(finalAccBig.toLong)
    val ctrV = Value.intV(counter.finalValue)
    local(accName) = accV
    interp.globals(accName) = accV
    local(body.names(counterIdx)) = ctrV
    interp.globals(body.names(counterIdx)) = ctrV
    Computation.PureUnit

  /** Walk `term` as a degree-1 polynomial `a*counter + b`, where `p1` (and
   *  optionally `p2`) are parameter names each bound to the loop counter.
   *  Val-bound integer globals are folded as constants.  Any term outside the
   *  {const, param, +, -, *, unary-, 1-stmt-block} grammar returns null. */
  private def walkLinearPoly(
    term: Term, p1: String, p2: String, interp: Interpreter
  ): (Long, Long) | Null = term match
    case Lit.Int(n)  => (0L, n.toLong)
    case Lit.Long(n) => (0L, n)
    case n: Term.Name if n.value == p1 || (p2.nonEmpty && n.value == p2) => (1L, 0L)
    case n: Term.Name if interp.valNames.contains(n.value) =>
      interp.globals.getOrElse(n.value, null) match
        case Value.IntV(v) => (0L, v)
        case _             => null
    case Term.ApplyInfix.After_4_6_0(lhs, Term.Name("+"), _, ac)
        if ac.values.lengthCompare(1) == 0 =>
      val r1 = walkLinearPoly(lhs, p1, p2, interp); if r1 == null then return null
      val r2 = walkLinearPoly(ac.values.head, p1, p2, interp); if r2 == null then return null
      (r1._1 + r2._1, r1._2 + r2._2)
    case Term.ApplyInfix.After_4_6_0(lhs, Term.Name("-"), _, ac)
        if ac.values.lengthCompare(1) == 0 =>
      val r1 = walkLinearPoly(lhs, p1, p2, interp); if r1 == null then return null
      val r2 = walkLinearPoly(ac.values.head, p1, p2, interp); if r2 == null then return null
      (r1._1 - r2._1, r1._2 - r2._2)
    case Term.ApplyInfix.After_4_6_0(lhs, Term.Name("*"), _, ac)
        if ac.values.lengthCompare(1) == 0 =>
      val r1 = walkLinearPoly(lhs, p1, p2, interp); if r1 == null then return null
      val r2 = walkLinearPoly(ac.values.head, p1, p2, interp); if r2 == null then return null
      val (a1, b1) = r1; val (a2, b2) = r2
      if a1 != 0L && a2 != 0L then return null  // degree-2 product is not affine
      if a2 == 0L then (a1 * b2, b1 * b2) else (a2 * b1, b2 * b1)
    case Term.ApplyUnary(op, arg) if op.value == "-" =>
      val r = walkLinearPoly(arg, p1, p2, interp); if r == null then return null
      (-r._1, -r._2)
    case blk: Term.Block if blk.stats.lengthCompare(1) == 0 =>
      blk.stats.head match
        case inner: Term => walkLinearPoly(inner, p1, p2, interp)
        case _           => null
    case _ => null

  /** Walk `term` as a degree-≤2 polynomial `a2*counter^2 + a1*counter + a0`.
   *  Returns null on non-polynomial shapes, degree > 2, function calls, or if
   *  `acc` (the accumulator variable) appears — that would be unsound. */
  private def walkQuadPoly(
    term: Term, counter: String, acc: String, interp: Interpreter
  ): (Long, Long, Long) | Null = term match
    case Lit.Int(n)  => (0L, 0L, n.toLong)
    case Lit.Long(n) => (0L, 0L, n)
    case n: Term.Name if n.value == counter => (0L, 1L, 0L)
    case n: Term.Name if n.value == acc     => null
    case n: Term.Name if interp.valNames.contains(n.value) =>
      interp.globals.getOrElse(n.value, null) match
        case Value.IntV(v) => (0L, 0L, v)
        case _             => null
    case Term.ApplyInfix.After_4_6_0(lhs, Term.Name("+"), _, ac)
        if ac.values.lengthCompare(1) == 0 =>
      val r1 = walkQuadPoly(lhs, counter, acc, interp); if r1 == null then return null
      val r2 = walkQuadPoly(ac.values.head, counter, acc, interp); if r2 == null then return null
      (r1._1 + r2._1, r1._2 + r2._2, r1._3 + r2._3)
    case Term.ApplyInfix.After_4_6_0(lhs, Term.Name("-"), _, ac)
        if ac.values.lengthCompare(1) == 0 =>
      val r1 = walkQuadPoly(lhs, counter, acc, interp); if r1 == null then return null
      val r2 = walkQuadPoly(ac.values.head, counter, acc, interp); if r2 == null then return null
      (r1._1 - r2._1, r1._2 - r2._2, r1._3 - r2._3)
    case Term.ApplyInfix.After_4_6_0(lhs, Term.Name("*"), _, ac)
        if ac.values.lengthCompare(1) == 0 =>
      val r1 = walkQuadPoly(lhs, counter, acc, interp); if r1 == null then return null
      val r2 = walkQuadPoly(ac.values.head, counter, acc, interp); if r2 == null then return null
      val deg1 = if r1._1 != 0 then 2 else if r1._2 != 0 then 1 else 0
      val deg2 = if r2._1 != 0 then 2 else if r2._2 != 0 then 1 else 0
      if deg1 + deg2 > 2 then return null
      val (a2, a1, a0) = r1; val (b2, b1, b0) = r2
      (a2*b0 + a0*b2 + a1*b1, a1*b0 + a0*b1, a0*b0)
    case Term.ApplyUnary(op, arg) if op.value == "-" =>
      val r = walkQuadPoly(arg, counter, acc, interp); if r == null then return null
      (-r._1, -r._2, -r._3)
    case blk: Term.Block if blk.stats.lengthCompare(1) == 0 =>
      blk.stats.head match
        case inner: Term => walkQuadPoly(inner, counter, acc, interp)
        case _           => null
    case _ => null

  /** Parse `rhs` as `acc + poly(counter)` in left-associative `+` chain.
   *  Returns `(a2, a1, a0)` coefficients of the addend, or null on mismatch. */
  private def tryExtractPolyAddend(
    accName: String, rhs: Term, ctrName: String, interp: Interpreter
  ): (Long, Long, Long) | Null = rhs match
    case Term.Name(`accName`) => (0L, 0L, 0L)
    case Term.ApplyInfix.After_4_6_0(lhs, Term.Name("+"), _, ac)
        if ac.values.lengthCompare(1) == 0 =>
      val rhsTerm = ac.values.head
      lhs match
        case Term.Name(`accName`) =>
          walkQuadPoly(rhsTerm, ctrName, accName, interp)
        case _ =>
          val rest = tryExtractPolyAddend(accName, lhs, ctrName, interp)
          if rest == null then return null
          val rp = walkQuadPoly(rhsTerm, ctrName, accName, interp)
          if rp == null then return null
          (rest._1 + rp._1, rest._2 + rp._2, rest._3 + rp._3)
    case _ => null

  /** Replace `while counter < N do { acc = acc + f(counter); counter += step }`
   *  with the Gauss closed form, bypassing bytecode JIT entirely.
   *
   *  Fires when body is exactly 2 assigns, counter has a literal/val-bound
   *  upper bound, the accumulator's addend is a 1-arg or 2-arg-counter-bound
   *  FunV call, and that function's body is degree-1 in its parameter(s).
   *
   *  Formula (K = iterations, s = counter start, step = counter stride):
   *    Σ_{j=0}^{K-1} (a*(s + j*step) + b)  =  a*step*K*(K-1)/2 + (a*s + b)*K
   *
   *  Returns null with no side effects on any mismatch. */
  private def tryClosedFormPolyLoop(
    t:         Term.While,
    body:      FastAssignBody,
    frameView: MutableEnvView,
    interp:    Interpreter
  ): Computation | Null =
    if body.names.length != 2 then return null
    var counterIdx = -1
    var counter: CounterFold | Null = null
    var idx = 0
    while idx < body.names.length do
      val cf = counterFoldInfo(t.expr, body.names(idx), body.rhs(idx), frameView, interp)
      if cf != null then
        if counterIdx >= 0 then return null
        counterIdx = idx
        counter = cf
      idx += 1
    if counterIdx < 0 || counter == null then return null
    val accIdx  = if counterIdx == 0 then 1 else 0
    val accName = body.names(accIdx)
    val ctrName = body.names(counterIdx)
    if accName == ctrName then return null
    // If the counter assignment precedes the accumulator, the accumulator RHS
    // reads the post-update counter. The fold below models pre-update
    // counter values, so leave order-dependent bodies to the sequential loop.
    if counterIdx < accIdx && freeNames(body.rhs(accIdx)).contains(ctrName) then
      return null
    val accStart: Long = (frameView.getOrElse(accName, null) match
      case null => interp.globals.getOrElse(accName, null)
      case v    => v) match
    case Value.IntV(v) => v
    case _             => return null
    val ctrStart: Long = (frameView.getOrElse(ctrName, null) match
      case null => interp.globals.getOrElse(ctrName, null)
      case v    => v) match
    case Value.IntV(v) => v
    case _             => return null
    val step: Long = body.rhs(counterIdx) match
      case Term.ApplyInfix.After_4_6_0(Term.Name(`ctrName`), Term.Name("+"), _, ac)
          if ac.values.lengthCompare(1) == 0 =>
        ac.values.head match
          case Lit.Int(s) if s > 0 => s.toLong
          case _ => return null
      case _ => return null
    // Fast path: inline degree-≤2 polynomial addend `acc = acc + poly(counter)`.
    // Handles left-assoc chains like `acc + X1 + X2` (e.g. after val-inlining).
    val inlinePoly = tryExtractPolyAddend(accName, body.rhs(accIdx), ctrName, interp)
    if inlinePoly != null then
      val (a2, a1, a0) = inlinePoly
      val K   = BigInt(counter.iterations)
      val S   = BigInt(ctrStart)
      val stp = BigInt(step)
      // Σ_{j=0}^{K-1} [a2*(S+j*stp)^2 + a1*(S+j*stp) + a0]
      val sumQuad = S*S*K + S*stp*K*(K-1) + stp*stp*K*(K-1)*(2*K-1)/6
      val sumLin  = S*K + stp*K*(K-1)/2
      val sumBig  = BigInt(a2)*sumQuad + BigInt(a1)*sumLin + BigInt(a0)*K
      val finalAccBig = BigInt(accStart) + sumBig
      if finalAccBig < BigInt(Long.MinValue) || finalAccBig > BigInt(Long.MaxValue) then
        return null
      val local = frameView.underlying
      val accV  = Value.intV(finalAccBig.toLong)
      val ctrV  = Value.intV(counter.finalValue)
      local(accName) = accV; interp.globals(accName) = accV
      local(ctrName) = ctrV; interp.globals(ctrName) = ctrV
      return Computation.PureUnit
    val addTerm = invariantAddend(accName, body.rhs(accIdx))
    if addTerm == null then return null
    // addTerm must be a plain call f(counter) or f(counter, counter)
    val applyFn: Term.Apply = addTerm match
      case ap: Term.Apply => ap
      case _              => return null
    val fnName: String = applyFn.fun match
      case n: Term.Name => n.value
      case _            => return null
    val fn: Value.FunV = interp.globals.getOrElse(fnName, null) match
      case f: Value.FunV => f
      case _             => return null
    if fn.usingParams.nonEmpty || fn.returnsThrows then return null
    if fn.defaults.exists(_.isDefined) then return null
    val args = applyFn.argClause.values
    val (polyA, polyB): (Long, Long) =
      fn.params match
        case List(p1) if args.lengthCompare(1) == 0 =>
          args.head match
            case n: Term.Name if n.value == ctrName => ()
            case _ => return null
          walkLinearPoly(fn.body, p1, "", interp) match
            case null => return null; case r => r
        case List(p1, p2) if args.lengthCompare(2) == 0 =>
          (args.head, args(1)) match
            case (n1: Term.Name, n2: Term.Name)
                if n1.value == ctrName && n2.value == ctrName => ()
            case _ => return null
          walkLinearPoly(fn.body, p1, p2, interp) match
            case null => return null; case r => r
        case _ => return null
    val K      = BigInt(counter.iterations)
    val a      = BigInt(polyA)
    val b      = BigInt(polyB)
    val s      = BigInt(ctrStart)
    val stpBig = BigInt(step)
    val sumBig = a * stpBig * K * (K - 1) / 2 + (a * s + b) * K
    val finalAccBig = BigInt(accStart) + sumBig
    if finalAccBig < BigInt(Long.MinValue) || finalAccBig > BigInt(Long.MaxValue) then return null
    val local = frameView.underlying
    val accV  = Value.intV(finalAccBig.toLong)
    val ctrV  = Value.intV(counter.finalValue)
    local(accName) = accV; interp.globals(accName) = accV
    local(ctrName) = ctrV; interp.globals(ctrName) = ctrV
    Computation.PureUnit

  /** Pure-literal-RHS hoist for an all-assign while body that has at least one
   *  non-int LHS (the var being assigned is something other than an `IntV`,
   *  e.g. a `TupleV`) whose RHS is a fully-pure-literal expression cached by
   *  `pureConstCache`. Targets the `tupleMonoid` shape
   *  `while i < N do { last = (1,2) ++ (3,4); i = i + 1 }`.
   *
   *  Strategy: a pure-literal RHS evaluates to the same `Value` on every visit,
   *  so a single write to the non-int LHS before the loop is observably
   *  identical to `N` per-iteration writes — the body has only `Term.Assign`
   *  (no `Term.Apply`), so nothing else can re-mutate that name mid-loop.
   *  After the hoisted write, the remaining int-slot assigns are forwarded to
   *  `tryLongWhileAssign` via a synthesised `FastAssignBody` that contains
   *  only those entries; `tryLongWhileAssign`'s hot path is unchanged.
   *
   *  Returns null with no side effects when:
   *  - every name is already int-slot (tryLongWhileAssign handles it), or
   *  - any non-int name has a non-pure RHS (the slow path is needed), or
   *  - no int-slot counter remains (degenerate loop, bail).
   *
   *  Precondition: condition is true on entry (same as `tryLongWhileAssign`). */
  private def tryHoistedPureWhile(
    t: Term.While,
    body: FastAssignBody,
    frameView: MutableEnvView,
    interp: Interpreter
  ): Computation | Null =
    // O(n) pre-scan: if every name is IntV, leave it to tryLongWhileAssign.
    // Otherwise verify the non-int RHSes are pure — bail if any isn't, so the
    // value-space loop sees the original body unchanged.
    var anyNonInt = false
    var k = 0
    while k < body.names.length do
      val lhsName = body.names(k)
      val v = frameView.getOrElse(lhsName, null) match
        case null => interp.globals.getOrElse(lhsName, null)
        case vv   => vv
      if !v.isInstanceOf[Value.IntV] then
        anyNonInt = true
        val rhs = body.rhs(k)
        val hoistable = rhs match
          case n: Term.Name => interp.valNames.contains(n.value) || n.value == lhsName
          case _            => isPureConstExpr(rhs)
        if !hoistable then return null
      k += 1
    if !anyNonInt then return null
    // Split the body. Pure-cached entries are pre-evaluated now (this also
    // populates `pureConstCache` as a side effect, so a later visit sees the
    // value without an extra eval).
    val intNames = new Array[String](body.names.length)
    val intRhs   = new Array[Term](body.names.length)
    var intCount = 0
    val pureNames  = new Array[String](body.names.length)
    val pureValues = new Array[Value](body.names.length)
    var pureCount  = 0
    var i = 0
    while i < body.names.length do
      val nm = body.names(i)
      val v  = frameView.getOrElse(nm, null) match
        case null => interp.globals.getOrElse(nm, null)
        case vv   => vv
      if v.isInstanceOf[Value.IntV] then
        intNames(intCount) = nm
        intRhs(intCount) = body.rhs(i)
        intCount += 1
      else
        eval(body.rhs(i), frameView, interp) match
          case Pure(pv) =>
            pureNames(pureCount) = nm
            pureValues(pureCount) = pv
            pureCount += 1
          case _ => return null
      i += 1
    if intCount == 0 then return null   // no counter slot → degenerate loop
    // Hoist the pure-cached writes once. The body has no Term.Apply (it's an
    // all-assign body per `collectFastAssignBody`), so the values can't be
    // re-mutated mid-loop.
    val local = frameView.underlying
    var p = 0
    while p < pureCount do
      local(pureNames(p)) = pureValues(p)
      interp.globals(pureNames(p)) = pureValues(p)
      p += 1
    // When a single int slot remains and it is a simple +step counter, fold
    // the loop to a single assignment: `ctr = terminalValue`.  This converts
    // `while i < N do { <hoisted>; i = i+1 }` to `i = N` in O(1) without
    // running the JIT loop.  Only safe here because the body is all-assign
    // (no Term.Apply, proven by collectFastAssignBody).
    if intCount == 1 &&
       tryFoldCounterLoop(t.expr, intNames(0), intRhs(0), frameView, interp)
    then return Computation.PureUnit
    val intBody = new FastAssignBody(
      if intCount == intNames.length then intNames else intNames.take(intCount),
      if intCount == intRhs.length   then intRhs   else intRhs.take(intCount)
    )
    // Try the bytecode JIT first — for loops like `while i < N do i = i+1`
    // after hoisting, the JIT produces tighter code than the LExpr loop.
    val jit = tryWhileJit(t, intBody, frameView, interp)
    if jit != null then jit
    else tryLongWhileAssign(t, intBody, frameView, interp)

  /** Tries to run the whole while-assign loop in unboxed `long` space. Returns
   *  `PureUnit` on success (slots boxed back to env+globals on exit), or null to
   *  bail to the Value-space loop (non-int var, unsupported op/term). Precondition:
   *  the caller has already confirmed the loop condition is true on entry. */
  private def tryLongWhileAssign(t: Term.While, body: FastAssignBody, frameView: MutableEnvView, interp: Interpreter): Computation | Null =
    val slotOfName = new SlotTable()
    // Slot index for an int-valued name, or -1 (bail). Registers on first use.
    // Falls back to interp.globals when frameView is empty (Defn.Var initialises
    // both local and globals identically, so the var lands in neither frame).
    def slotOf(name: String): Int =
      val existing = slotOfName.slotIndex(name)
      if existing >= 0 then existing
      else
        (frameView.getOrElse(name, null) match
          case null => interp.globals.getOrElse(name, null)
          case v    => v) match
        case Value.IntV(_) => slotOfName.register(name)
        case _             => -1
    // Compile a ref-typed term into an LRefExpr or null on bail. Phase 1
    // Commit 2: handles only `Term.Name` resolving to a val-bound `InstanceV`
    // (LRefConst snapshot). Commit 3 extends to ref field access
    // (LRefFieldGet) and ref-returning fn calls (LApplyR1ToRef).
    def compileRefExpr(term: Term): LRefExpr | Null = term match
      case n: Term.Name if interp.valNames.contains(n.value) =>
        interp.globals.getOrElse(n.value, null) match
          case inst: Value.InstanceV => new LRefConst(inst)
          case _                     => null
      // `inst.field` — resolves to LRefFieldGet when the receiver's static
      // type can be inferred from a val snapshot. We need typeName at
      // compile time to look up the static field index via
      // `interp.typeFieldOrder`; without it the per-iter type-probe would
      // defeat the win.
      case Term.Select(qual, Term.Name(fieldName)) =>
        val qualType: String | Null = qual match
          case qn: Term.Name if interp.valNames.contains(qn.value) =>
            interp.globals.getOrElse(qn.value, null) match
              case inst: Value.InstanceV => inst.typeName
              case _                     => null
          case _ => null
        if qualType == null then null
        else
          val order = interp.typeFieldOrder.getOrElse(qualType, Nil)
          val fieldIdx = order.indexOf(fieldName)
          if fieldIdx < 0 then null
          else
            val qualR = compileRefExpr(qual)
            if qualR == null then null
            else new LRefFieldGet(qualR, fieldIdx)
      case tm: Term.Match =>
        val scrutR = compileRefExpr(tm.expr)
        if scrutR == null then null
        else
          val cm = PatternRuntime.compileMatch(tm, interp)
          if cm.valueCapable then new LRefMatch(scrutR, cm) else null
      // `g(refExpr)` — resolves to LApplyR1ToRef when g is JIT-compiled to
      // ObjToObject (1 ref param, ref return).  Enables f(g(item)) chains.
      case ap: Term.Apply if ap.argClause.values.lengthCompare(1) == 0 =>
        ap.fun match
          case fnName: Term.Name =>
            val argR = compileRefExpr(ap.argClause.values.head)
            if argR == null then null
            else interp.globals.getOrElse(fnName.value, null) match
              case fn: Value.FunV
                  if fn.params.length == 1 && fn.usingParams.isEmpty && !fn.returnsThrows =>
                val bcRes = scalascript.interpreter.vm.jit.JitBackend.default.tryCompile(fn, interp)
                if bcRes == null then null
                else bcRes.direct match
                  case oo: scalascript.interpreter.vm.jit.ObjToObject => new LApplyR1ToRef(argR, oo)
                  case _ => null
              case _ => null
          case _ => null
      case _ => null
    def compileExpr(term: Term): LExpr | Null = term match
      case Lit.Int(v)  => new LConst(v.toLong)
      case Lit.Long(v) => new LConst(v)
      case tn: Term.Name =>
        val s = slotOf(tn.value)
        if s < 0 then null else new LVar(s)
      case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
          if argClause.values.lengthCompare(1) == 0 =>
        val code = op.value match
          case "+" => 0
          case "-" => 1
          case "*" => 2
          case "/" => 3
          case "%" => 4
          case _   => -1
        if code < 0 then null
        else
          val l = compileExpr(lhs)
          if l == null then null
          else
            val r = compileExpr(argClause.values.head)
            if r == null then null else new LBin(code, l, r)
      // 1-param pure-bodied function call: `f(x)` where `f` is a top-level def
      // whose body is in the `compileSlotD` arith subset (e.g. `def f(x) = x + 1`).
      // Resolve `f` at compile time from `interp.globals`, compile its body to a
      // `(Long, Env) => Long` thunk via `compileSlotLongFn1`, and wrap as `LApply`.
      // At eval time, `LApply` re-resolves `f` to handle reassignment.
      case ap: Term.Apply =>
        ap.fun match
          case fnName: Term.Name if ap.argClause.values.lengthCompare(1) == 0 =>
            val argTerm = ap.argClause.values.head
            // Ref-arg JIT fast path via the dual-bank LExpr lowering. The
            // arg is compiled to an LRefExpr (Commit 2: LRefConst for a
            // val-bound InstanceV; Commit 3: LRefFieldGet for inst.field,
            // LApplyR1ToRef for f(g(item)) chains) and wrapped in
            // LApplyR1, which calls the bytecode-JIT'd ObjToLong direct
            // interface per iter without HashMap probes, Computation
            // boxing, or marshalling.
            val refFast: LExpr | Null =
              val argR = compileRefExpr(argTerm)
              if argR == null then null
              else
                interp.globals.getOrElse(fnName.value, null) match
                  case fn: Value.FunV
                      if fn.params.length == 1 && fn.usingParams.isEmpty
                         && !fn.returnsThrows =>
                    val bcRes = scalascript.interpreter.vm.jit.JitBackend.default.tryCompile(fn, interp)
                    if bcRes == null then null
                    else if !bcRes.paramIsRef(0) || bcRes.resultIsDouble then null
                    else
                      bcRes.direct match
                        case ot: scalascript.interpreter.vm.jit.ObjToLong =>
                          new LApplyR1(argR, ot)
                        case _ => null
                  case _ => null
            if refFast != null then refFast
            else
              val argL = compileExpr(argTerm)
              if argL == null then null
              else
                interp.globals.getOrElse(fnName.value, null) match
                  case fn: Value.FunV
                      if fn.params.length == 1
                         && fn.usingParams.isEmpty
                         && !fn.returnsThrows
                         && (fn.defaults.isEmpty || fn.defaults.head.isEmpty)
                         && (fn.paramTypes.isEmpty || !fn.paramTypes.head.endsWith("*")) =>
                    val slotFn = PatternRuntime.compileSlotLongFn1(fn.body, fn.params.head, interp)
                    if slotFn == null then null
                    else new LApply(argL, fnName.value, fn.body, slotFn, interp)
                  case _ => null
          // 2-arg parallel: `g(x, y)` where `g` is a pure 2-param fn.
          case fnName: Term.Name if ap.argClause.values.lengthCompare(2) == 0 =>
            val a0Term = ap.argClause.values.head
            val a1Term = ap.argClause.values(1)
            // Ref-mixed JIT fast path — `f(longExpr, refExpr)` and the
            // symmetric `f(refExpr, longExpr)`. Detected BEFORE the
            // slot-arg compileExpr probe so a ref-typed arg name doesn't
            // force the whole expression to bail (same lift as the 1-arg
            // refFast path that landed in commit 13af281f / f7fc2b34).
            // Unblocks `gEval(scale, e)` in recursiveEvalMixed.
            val mixedFast: LExpr | Null =
              interp.globals.getOrElse(fnName.value, null) match
                case fn: Value.FunV
                    if fn.params.length == 2 && fn.usingParams.isEmpty
                       && !fn.returnsThrows =>
                  val bcRes = scalascript.interpreter.vm.jit.JitBackend.default.tryCompile(fn, interp)
                  if bcRes == null || bcRes.resultIsDouble then null
                  else
                    bcRes.direct match
                      case ot: scalascript.interpreter.vm.jit.LongObjToLong
                          if !bcRes.paramIsRef(0) && bcRes.paramIsRef(1) =>
                        val a0L = compileExpr(a0Term)
                        val a1R = compileRefExpr(a1Term)
                        if a0L == null || a1R == null then null
                        else new LApplyR2LongObj(a0L, a1R, ot)
                      case ot: scalascript.interpreter.vm.jit.ObjLongToLong
                          if bcRes.paramIsRef(0) && !bcRes.paramIsRef(1) =>
                        val a0R = compileRefExpr(a0Term)
                        val a1L = compileExpr(a1Term)
                        if a0R == null || a1L == null then null
                        else new LApplyR2ObjLong(a0R, a1L, ot)
                      case _ => null
                case _ => null
            if mixedFast != null then mixedFast
            else
              val arg0L = compileExpr(a0Term)
              if arg0L == null then null
              else
                val arg1L = compileExpr(a1Term)
                if arg1L == null then null
                else
                  interp.globals.getOrElse(fnName.value, null) match
                    case fn: Value.FunV
                        if fn.params.length == 2
                           && fn.usingParams.isEmpty
                           && !fn.returnsThrows
                           && (fn.defaults.isEmpty || fn.defaults.forall(_.isEmpty))
                           && (fn.paramTypes.length < 2 || !fn.paramTypes(1).endsWith("*")) =>
                      val slotFn = PatternRuntime.compileSlotLongFn2(fn.body, fn.params.head, fn.params(1), interp)
                      if slotFn == null then null
                      else new LApply2(arg0L, arg1L, fnName.value, fn.body, slotFn, interp)
                    case _ => null
          case _ => null
      // `p match { case Ctor(a, b) => a + b }` inlined as LMatch:
      // compiles the match once (cached), bails if not longCapable or if any
      // arm free-name overlaps a loop-slot (stale data guard).
      case m: Term.Match =>
        m.expr match
          case Term.Name(scrutName) =>
            var cm = interp.matchCache.get(m)
            if cm == null then
              cm = PatternRuntime.compileMatch(m, interp)
              interp.matchCache.put(m, cm)
            if !cm.longCapable then null
            else if cm.slotFreeNames == null then null
            else if cm.slotFreeNames.exists(slotOfName.contains) then null
            else
              // For `val` scrutinees, snapshot the value once at compile
              // time — `LMatch.eval` then skips the per-iter HashMap probe.
              // The valNames registry populated by `StatRuntime.execStat`
              // tracks names that can't be reassigned.
              val cached: Value | Null =
                if interp.valNames.contains(scrutName)
                then interp.globals.getOrElse(scrutName, null)
                else null
              new LMatch(scrutName, cm, interp, cached)
          case _ => null
      case _ => null
    def compileCond(term: Term): LCond | Null = term match
      case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
          if argClause.values.lengthCompare(1) == 0 =>
        op.value match
          case "&&" =>
            val l = compileCond(lhs)
            if l == null then null
            else
              val r = compileCond(argClause.values.head)
              if r == null then null else new LAnd(l, r)
          case "||" =>
            val l = compileCond(lhs)
            if l == null then null
            else
              val r = compileCond(argClause.values.head)
              if r == null then null else new LOr(l, r)
          case other =>
            val code = other match
              case "<"  => 0
              case "<=" => 1
              case ">"  => 2
              case ">=" => 3
              case "==" => 4
              case "!=" => 5
              case _    => -1
            if code < 0 then null
            else
              val l = compileExpr(lhs)
              if l == null then null
              else
                val r = compileExpr(argClause.values.head)
                if r == null then null else new LCmp(code, l, r)
      case _ => null

    // Register assigned names (must already be int-valued vars), then compile.
    var k = 0
    while k < body.names.length do
      if slotOf(body.names(k)) < 0 then return null
      k += 1
    val rhsL = new Array[LExpr](body.rhs.length)
    var j = 0
    while j < body.rhs.length do
      val c = compileExpr(body.rhs(j))
      if c == null then return null
      rhsL(j) = c
      j += 1
    val condL = compileCond(t.expr)
    if condL == null then return null
    // Initial slot values (every registered name was checked int-valued).
    val slots = new Array[Long](slotOfName.size)
    var idx = 0
    while idx < slotOfName.size do
      (frameView.getOrElse(slotOfName.nameAt(idx), null) match
        case null => interp.globals.getOrElse(slotOfName.nameAt(idx), null)
        case v    => v) match
      case Value.IntV(v) => slots(idx) = v
      case _             => return null
      idx += 1
    val assignSlot = new Array[Int](body.names.length)
    var a = 0
    while a < body.names.length do
      assignSlot(a) = slotOfName.slotIndex(body.names(a))
      a += 1
    // Allocate the parallel Ref bank only when at least one slot was
    // registered as `SlotKindRef`; otherwise share the static `EmptyRefs`
    // singleton — Phase 1 Commit 1 has zero callers register Ref slots,
    // so this is always EmptyRefs today.
    val refs: Array[AnyRef] =
      if slotOfName.refCount == 0 then EmptyRefs
      else new Array[AnyRef](slotOfName.size)
    // Run in unboxed long space (condition already true on entry → do-while).
    // The try/catch handles a rare `LApply` runtime bail (a function was
    // reassigned mid-loop so its body no longer matches the compiled `DExpr`):
    // throw `NotDouble` lands here, returns null, caller falls back to the
    // value-space loop. ControlThrowable is stackless so the cost is minimal
    // and pays only on bail, not the steady-state path.
    try
      var running = true
      while running do
        var i = 0
        while i < rhsL.length do
          slots(assignSlot(i)) = rhsL(i).eval(slots, refs)
          i += 1
        running = condL.eval(slots, refs)
      // Box final values of assigned vars back into env + globals (once).
      val local = frameView.underlying
      var w = 0
      while w < body.names.length do
        val nm = body.names(w)
        val v  = Value.intV(slots(assignSlot(w)))
        local(nm) = v
        interp.globals(nm) = v
        w += 1
      Computation.PureUnit
    catch
      case PatternRuntime.NotDouble => null

  /** Mixed apply+assign variant of `tryLongWhileAssign`. The body is
   *  `{ apply1; ...; applyN; assign1; ...; assignM }` (see
   *  `collectMixedAssignBody`): each apply runs via `interp.eval` (typically
   *  routed through `FastTier` so the side effect is near-free), and the
   *  trailing int-typed assigns run in unboxed `Long` slot space, just as in
   *  `tryLongWhileAssign`. Targets the outer-while of `patternMatchHeavy` /
   *  `patternMatchWide` (`{ shapes.foreach(...); i = i + 1 }`), removing the
   *  per-iter `IntV` allocation that the value-path falls back to.
   *
   *  Safety: requires no leading apply to read or write any slot-assigned
   *  name (verified once via `applyAccessesNames`). The applies see the env
   *  through `frameView`, so they observe the entry-state of the slot vars
   *  for the iteration — but since they don't touch them, the in-loop slot
   *  values stay authoritative without any per-iter resync. */
  private def tryMixedLongWhile(
    t: Term.While,
    body: MixedAssignBody,
    frameView: MutableEnvView,
    interp: Interpreter,
    preResolved: PreResolvedForeach | Null = null
  ): Computation | Null =
    val slotOfName = new SlotTable()
    def slotOf(name: String): Int =
      val existing = slotOfName.slotIndex(name)
      if existing >= 0 then existing
      else
        (frameView.getOrElse(name, null) match
          case null => interp.globals.getOrElse(name, null)
          case v    => v) match
        case Value.IntV(_) => slotOfName.register(name)
        case _             => -1
    // Compile a ref-typed term into an LRefExpr or null on bail. Phase 1
    // Commit 2: handles only `Term.Name` resolving to a val-bound `InstanceV`
    // (LRefConst snapshot). Commit 3 extends to ref field access
    // (LRefFieldGet) and ref-returning fn calls (LApplyR1ToRef).
    def compileRefExpr(term: Term): LRefExpr | Null = term match
      case n: Term.Name if interp.valNames.contains(n.value) =>
        interp.globals.getOrElse(n.value, null) match
          case inst: Value.InstanceV => new LRefConst(inst)
          case _                     => null
      // `inst.field` — resolves to LRefFieldGet when the receiver's static
      // type can be inferred from a val snapshot. We need typeName at
      // compile time to look up the static field index via
      // `interp.typeFieldOrder`; without it the per-iter type-probe would
      // defeat the win.
      case Term.Select(qual, Term.Name(fieldName)) =>
        val qualType: String | Null = qual match
          case qn: Term.Name if interp.valNames.contains(qn.value) =>
            interp.globals.getOrElse(qn.value, null) match
              case inst: Value.InstanceV => inst.typeName
              case _                     => null
          case _ => null
        if qualType == null then null
        else
          val order = interp.typeFieldOrder.getOrElse(qualType, Nil)
          val fieldIdx = order.indexOf(fieldName)
          if fieldIdx < 0 then null
          else
            val qualR = compileRefExpr(qual)
            if qualR == null then null
            else new LRefFieldGet(qualR, fieldIdx)
      case tm: Term.Match =>
        val scrutR = compileRefExpr(tm.expr)
        if scrutR == null then null
        else
          val cm = PatternRuntime.compileMatch(tm, interp)
          if cm.valueCapable then new LRefMatch(scrutR, cm) else null
      // `g(refExpr)` — resolves to LApplyR1ToRef when g is JIT-compiled to
      // ObjToObject (1 ref param, ref return).  Enables f(g(item)) chains.
      case ap: Term.Apply if ap.argClause.values.lengthCompare(1) == 0 =>
        ap.fun match
          case fnName: Term.Name =>
            val argR = compileRefExpr(ap.argClause.values.head)
            if argR == null then null
            else interp.globals.getOrElse(fnName.value, null) match
              case fn: Value.FunV
                  if fn.params.length == 1 && fn.usingParams.isEmpty && !fn.returnsThrows =>
                val bcRes = scalascript.interpreter.vm.jit.JitBackend.default.tryCompile(fn, interp)
                if bcRes == null then null
                else bcRes.direct match
                  case oo: scalascript.interpreter.vm.jit.ObjToObject => new LApplyR1ToRef(argR, oo)
                  case _ => null
              case _ => null
          case _ => null
      case _ => null
    def compileExpr(term: Term): LExpr | Null = term match
      case Lit.Int(v)  => new LConst(v.toLong)
      case Lit.Long(v) => new LConst(v)
      case tn: Term.Name =>
        val s = slotOf(tn.value)
        if s < 0 then null else new LVar(s)
      case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
          if argClause.values.lengthCompare(1) == 0 =>
        val code = op.value match
          case "+" => 0
          case "-" => 1
          case "*" => 2
          case "/" => 3
          case "%" => 4
          case _   => -1
        if code < 0 then null
        else
          val l = compileExpr(lhs)
          if l == null then null
          else
            val r = compileExpr(argClause.values.head)
            if r == null then null else new LBin(code, l, r)
      case ap: Term.Apply =>
        ap.fun match
          case fnName: Term.Name if ap.argClause.values.lengthCompare(1) == 0 =>
            val argTerm = ap.argClause.values.head
            // Ref-arg JIT fast path via the dual-bank LExpr lowering. The
            // arg is compiled to an LRefExpr (Commit 2: LRefConst for a
            // val-bound InstanceV; Commit 3: LRefFieldGet for inst.field,
            // LApplyR1ToRef for f(g(item)) chains) and wrapped in
            // LApplyR1, which calls the bytecode-JIT'd ObjToLong direct
            // interface per iter without HashMap probes, Computation
            // boxing, or marshalling.
            val refFast: LExpr | Null =
              val argR = compileRefExpr(argTerm)
              if argR == null then null
              else
                interp.globals.getOrElse(fnName.value, null) match
                  case fn: Value.FunV
                      if fn.params.length == 1 && fn.usingParams.isEmpty
                         && !fn.returnsThrows =>
                    val bcRes = scalascript.interpreter.vm.jit.JitBackend.default.tryCompile(fn, interp)
                    if bcRes == null then null
                    else if !bcRes.paramIsRef(0) || bcRes.resultIsDouble then null
                    else
                      bcRes.direct match
                        case ot: scalascript.interpreter.vm.jit.ObjToLong =>
                          new LApplyR1(argR, ot)
                        case _ => null
                  case _ => null
            if refFast != null then refFast
            else
              val argL = compileExpr(argTerm)
              if argL == null then null
              else
                interp.globals.getOrElse(fnName.value, null) match
                  case fn: Value.FunV
                      if fn.params.length == 1
                         && fn.usingParams.isEmpty
                         && !fn.returnsThrows
                         && (fn.defaults.isEmpty || fn.defaults.head.isEmpty)
                         && (fn.paramTypes.isEmpty || !fn.paramTypes.head.endsWith("*")) =>
                    val slotFn = PatternRuntime.compileSlotLongFn1(fn.body, fn.params.head, interp)
                    if slotFn == null then null
                    else new LApply(argL, fnName.value, fn.body, slotFn, interp)
                  case _ => null
          // 2-arg parallel: `g(x, y)` where `g` is a pure 2-param fn.
          case fnName: Term.Name if ap.argClause.values.lengthCompare(2) == 0 =>
            val a0Term = ap.argClause.values.head
            val a1Term = ap.argClause.values(1)
            // Ref-mixed JIT fast path — `f(longExpr, refExpr)` and the
            // symmetric `f(refExpr, longExpr)`. Detected BEFORE the
            // slot-arg compileExpr probe so a ref-typed arg name doesn't
            // force the whole expression to bail (same lift as the 1-arg
            // refFast path that landed in commit 13af281f / f7fc2b34).
            // Unblocks `gEval(scale, e)` in recursiveEvalMixed.
            val mixedFast: LExpr | Null =
              interp.globals.getOrElse(fnName.value, null) match
                case fn: Value.FunV
                    if fn.params.length == 2 && fn.usingParams.isEmpty
                       && !fn.returnsThrows =>
                  val bcRes = scalascript.interpreter.vm.jit.JitBackend.default.tryCompile(fn, interp)
                  if bcRes == null || bcRes.resultIsDouble then null
                  else
                    bcRes.direct match
                      case ot: scalascript.interpreter.vm.jit.LongObjToLong
                          if !bcRes.paramIsRef(0) && bcRes.paramIsRef(1) =>
                        val a0L = compileExpr(a0Term)
                        val a1R = compileRefExpr(a1Term)
                        if a0L == null || a1R == null then null
                        else new LApplyR2LongObj(a0L, a1R, ot)
                      case ot: scalascript.interpreter.vm.jit.ObjLongToLong
                          if bcRes.paramIsRef(0) && !bcRes.paramIsRef(1) =>
                        val a0R = compileRefExpr(a0Term)
                        val a1L = compileExpr(a1Term)
                        if a0R == null || a1L == null then null
                        else new LApplyR2ObjLong(a0R, a1L, ot)
                      case _ => null
                case _ => null
            if mixedFast != null then mixedFast
            else
              val arg0L = compileExpr(a0Term)
              if arg0L == null then null
              else
                val arg1L = compileExpr(a1Term)
                if arg1L == null then null
                else
                  interp.globals.getOrElse(fnName.value, null) match
                    case fn: Value.FunV
                        if fn.params.length == 2
                           && fn.usingParams.isEmpty
                           && !fn.returnsThrows
                           && (fn.defaults.isEmpty || fn.defaults.forall(_.isEmpty))
                           && (fn.paramTypes.length < 2 || !fn.paramTypes(1).endsWith("*")) =>
                      val slotFn = PatternRuntime.compileSlotLongFn2(fn.body, fn.params.head, fn.params(1), interp)
                      if slotFn == null then null
                      else new LApply2(arg0L, arg1L, fnName.value, fn.body, slotFn, interp)
                    case _ => null
          case _ => null
      // `p match { case Ctor(a, b) => a + b }` inlined as LMatch:
      // compiles the match once (cached), bails if not longCapable or if any
      // arm free-name overlaps a loop-slot (stale data guard).
      case m: Term.Match =>
        m.expr match
          case Term.Name(scrutName) =>
            var cm = interp.matchCache.get(m)
            if cm == null then
              cm = PatternRuntime.compileMatch(m, interp)
              interp.matchCache.put(m, cm)
            if !cm.longCapable then null
            else if cm.slotFreeNames == null then null
            else if cm.slotFreeNames.exists(slotOfName.contains) then null
            else
              // For `val` scrutinees, snapshot the value once at compile
              // time — `LMatch.eval` then skips the per-iter HashMap probe.
              // The valNames registry populated by `StatRuntime.execStat`
              // tracks names that can't be reassigned.
              val cached: Value | Null =
                if interp.valNames.contains(scrutName)
                then interp.globals.getOrElse(scrutName, null)
                else null
              new LMatch(scrutName, cm, interp, cached)
          case _ => null
      case _ => null
    def compileCond(term: Term): LCond | Null = term match
      case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
          if argClause.values.lengthCompare(1) == 0 =>
        op.value match
          case "&&" =>
            val l = compileCond(lhs)
            if l == null then null
            else
              val r = compileCond(argClause.values.head)
              if r == null then null else new LAnd(l, r)
          case "||" =>
            val l = compileCond(lhs)
            if l == null then null
            else
              val r = compileCond(argClause.values.head)
              if r == null then null else new LOr(l, r)
          case other =>
            val code = other match
              case "<"  => 0
              case "<=" => 1
              case ">"  => 2
              case ">=" => 3
              case "==" => 4
              case "!=" => 5
              case _    => -1
            if code < 0 then null
            else
              val l = compileExpr(lhs)
              if l == null then null
              else
                val r = compileExpr(argClause.values.head)
                if r == null then null else new LCmp(code, l, r)
      case _ => null

    // Compile a single leading apply to an LEffect if the target is a known 0-arg
    // FunV/NativeFnV that won't be re-bound during the loop.  Returns null to fall
    // back to per-iter monadic eval.
    def compileLeadingEffect(apply: Term): LEffect | Null = apply match
      case ap: Term.Apply if ap.argClause.values.isEmpty =>
        ap.fun match
          case fnTerm: Term.Name =>
            val fname = fnTerm.value
            var isRebound = false
            var kb = 0
            while kb < body.names.length do
              if body.names(kb) == fname then isRebound = true
              kb += 1
            if isRebound then return null
            interp.globals.getOrElse(fname, null) match
              case fn: Value.FunV
                  if fn.params.isEmpty && fn.usingParams.isEmpty && !fn.returnsThrows &&
                     { val ti = TcoRuntime.tcoInfoFor(fn, interp); !ti.isSelfTailRec && ti.tailTargets.isEmpty } =>
                val env = if fn.name.nonEmpty then interp.closureWithSelfFor(fn) else fn.closure
                new LApplyEffect(fn, env)
              case fn: Value.NativeFnV =>
                new LApplyEffectNative(fn)
              case _ => null
          case _ => null
      case _ => null

    // Register assigned names + compile RHSes / cond first so we know the slot set.
    var k = 0
    while k < body.names.length do
      if slotOf(body.names(k)) < 0 then return null
      k += 1
    val rhsL = new Array[LExpr](body.rhs.length)
    var j = 0
    while j < body.rhs.length do
      val c = compileExpr(body.rhs(j))
      if c == null then return null
      rhsL(j) = c
      j += 1
    val condL = compileCond(t.expr)
    if condL == null then return null

    // Safety: leading applies must NOT reference any slot-assigned name.
    // Build a Set lazily from the SlotTable (small N — direct construction is fine).
    val slotNameSet: Set[String] =
      val b = Set.newBuilder[String]
      var ni = 0
      while ni < slotOfName.size do
        b += slotOfName.nameAt(ni); ni += 1
      b.result()
    var pi = 0
    while pi < body.leadingApplies.length do
      if applyAccessesNames(body.leadingApplies(pi), slotNameSet) then return null
      pi += 1

    // Initial slot values.
    val slots = new Array[Long](slotOfName.size)
    var idx = 0
    while idx < slotOfName.size do
      (frameView.getOrElse(slotOfName.nameAt(idx), null) match
        case null => interp.globals.getOrElse(slotOfName.nameAt(idx), null)
        case v    => v) match
      case Value.IntV(v) => slots(idx) = v
      case _             => return null
      idx += 1
    val assignSlot = new Array[Int](body.names.length)
    var a = 0
    while a < body.names.length do
      assignSlot(a) = slotOfName.slotIndex(body.names(a))
      a += 1
    // Parallel Ref bank — see tryLongWhileAssign for the EmptyRefs rationale.
    val refs: Array[AnyRef] =
      if slotOfName.refCount == 0 then EmptyRefs
      else new Array[AnyRef](slotOfName.size)

    // Pre-compile leading applies to effect nodes (once per loop entry, amortized).
    val effects = new Array[LEffect | Null](body.leadingApplies.length)
    var ei = 0
    while ei < body.leadingApplies.length do
      effects(ei) = compileLeadingEffect(body.leadingApplies(ei))
      ei += 1

    try
      var running = true
      var useFastForeach = preResolved != null
      while running do
        // 1. Run leading side-effect applies. For the pre-resolved foreach apply
        //    (if available), call tryDoubleAccumForeach directly, skipping the
        //    eval→evalApplyGeneral→dispatch overhead (~50% CPU on patternMatchHeavy).
        //    On bail, fall back to standard eval for this and subsequent applies.
        var ap = 0
        while ap < body.leadingApplies.length do
          if useFastForeach && ap == preResolved.applyIdx then
            // Virtual-dispatch to the matching FastTier method for the
            // receiver+accumulator kind cell. The sealed hierarchy
            // (List/Set/Map × Long/Double) absorbs the branching cost off the
            // per-inner-element path.
            if preResolved.run(interp) == null then
              // FastTier bailed (NotDouble/wrong-shape) — fall back.
              useFastForeach = false
              val eff = effects(ap)
              if eff != null then eff.run(interp)
              else Computation.run(interp.eval(body.leadingApplies(ap), frameView))
          else
            val eff = effects(ap)
            if eff != null then eff.run(interp)
            else Computation.run(interp.eval(body.leadingApplies(ap), frameView))
          ap += 1
        // 2. Long-slot assigns.
        var i = 0
        while i < rhsL.length do
          slots(assignSlot(i)) = rhsL(i).eval(slots, refs)
          i += 1
        // 3. Cond.
        running = condL.eval(slots, refs)
      // Write back final slot values.
      val local = frameView.underlying
      var w = 0
      while w < body.names.length do
        val nm = body.names(w)
        val v  = Value.intV(slots(assignSlot(w)))
        local(nm) = v
        interp.globals(nm) = v
        w += 1
      Computation.PureUnit
    catch
      case PatternRuntime.NotDouble => null

  /** True if the loop body performs an effect op (`Eff.op(...)`) that has NO active inline
   *  resolver in scope. Such an op must thread as a `Computation` through the surrounding
   *  handler, but the fast-while path runs leading applies eagerly via `Computation.run`, so
   *  the `Perform` would escape ("Unhandled effect ... no handler in scope"). Bailing to the
   *  monadic trampoline (which threads effects via `FlatMap`) is correct. A resolved op (the
   *  one-shot tail-resume fast path) keeps a live resolver, so this returns false for it and
   *  the fast path is preserved. (interp-returnclause-effect-in-while.) */
  private def whileBodyHasUnresolvedEffect(body: Term, interp: Interpreter): Boolean =
    if interp.effectOpNames.isEmpty then false
    else
      var found = false
      def walk(n: scala.meta.Tree): Unit =
        if !found then
          n match
            case Term.Apply.After_4_6_0(Term.Select(Term.Name(eff), Term.Name(op)), _)
                if interp.effectOpNames.contains(s"$eff.$op") &&
                   EffectsRuntime.lookupResolver(eff, op) == null =>
              found = true
            case _ => n.children.foreach(walk)
      walk(body)
      found

  private def tryFastWhileAssign(t: Term.While, frameView: MutableEnvView, interp: Interpreter): Computation | Null =
    if interp.debugHooks.nonEmpty then null
    else if whileBodyHasUnresolvedEffect(t.body, interp) then null
    else
      val body = collectFastAssignBody(t.body)
      if body == null then
        // Try the mixed apply+assign variant before falling back to monadic.
        val mixedBody = collectMixedAssignBody(t.body)
        if mixedBody == null then null
        else
          fastPrimitiveValue(t.expr, frameView, interp) match
            case Value.BoolV(false) => Computation.PureUnit
            case Value.BoolV(true) =>
              // Try four foreach-hoist peeks in priority order. The first to
              // fire wins; on miss, fall through to plain tryMixedLongWhile.
              //   (a) Double-acc 1-arg (List/Set) — gets the slot-bypass
              //       (`withAccSlot`) that avoids per-outer-iter DoubleV writeback.
              //   (b) Long-acc 1-arg (List/Set) — no slot bypass yet.
              //   (c) Map foreach 2-arg, Double or Long acc — no slot bypass yet.
              // Each peek is AST-only + a globals lookup; cheap to attempt.
              def peekFirst[A <: AnyRef](f: Term => A | Null): (Int, A) | Null =
                var idx = 0
                while idx < mixedBody.leadingApplies.length do
                  val r = f(mixedBody.leadingApplies(idx))
                  if r != null then return (idx, r.asInstanceOf[A])
                  idx += 1
                null
              // Identity-foreach `xs.foreach(s => acc = acc + s)` (no inner fn).
              // Intercept BEFORE the fn-based peeks and route ONLY through the
              // fused-JIT mixed path. On a JIT hit return it; on a miss fall
              // through to the chain below, which (since no fn-peek matches an
              // identity closure) ends at plain tryMixedLongWhile — the
              // interpreter evaluates the foreach, so there is no slot-bypass
              // writeback to get wrong.
              val idLongPeek = peekFirst(t => FastTier.peekLongIdentityAccName(t, interp))
              val idPeek: (Int, String, Boolean) | Null =
                if idLongPeek != null then (idLongPeek._1, idLongPeek._2, false)
                else
                  val idDblPeek = peekFirst(t => FastTier.peekDoubleIdentityAccName(t, interp))
                  if idDblPeek != null then (idDblPeek._1, idDblPeek._2, true) else null
              if idPeek != null then
                val (foreachApplyIdx, idAccName, idIsDouble) = idPeek
                val initV = interp.globals.getOrElse(idAccName, null)
                val initOk =
                  if idIsDouble then initV.isInstanceOf[Value.DoubleV]
                  else                initV.isInstanceOf[Value.IntV]
                if initOk then
                  val initD =
                    if idIsDouble then initV.asInstanceOf[Value.DoubleV].v
                    else               initV.asInstanceOf[Value.IntV].v.toDouble
                  val idJit = tryWhileJitMixed(
                    t, mixedBody, foreachApplyIdx, idAccName, idIsDouble, initD, frameView, interp
                  )
                  if idJit != null then return idJit
              val doublePeek = peekFirst(t => FastTier.peekDoubleAccName(t, interp))
              if doublePeek != null then
                val (foreachApplyIdx, doubleAccName) = doublePeek
                val initV = interp.globals.getOrElse(doubleAccName, null)
                if (initV ne null) && initV.isInstanceOf[Value.DoubleV] then
                  val initDouble = initV.asInstanceOf[Value.DoubleV].v
                  // Fused JIT path: compile outer while + inner foreach into one
                  // Java method, eliminating per-outer-iter TLS round-trips and
                  // enabling JVM devirtualization of the monomorphic fn call.
                  val jitR = tryWhileJitMixed(
                    t, mixedBody, foreachApplyIdx, doubleAccName, true, initDouble, frameView, interp
                  )
                  if jitR != null then jitR
                  else
                    val slot = Array[Long](doubleToRawLongBits(initDouble), 1L)
                    val preResolved = tryPreResolveForeach(
                      mixedBody.leadingApplies(foreachApplyIdx),
                      foreachApplyIdx, isDouble = true,
                      interp, mixedBody.names, frameView
                    )
                    // Hoist the accSlotTls.get out of the inner per-iter path:
                    // when preResolved is a Fast variant it stores the slot in
                    // a field; FastTier.runDoubleAccumForeachFast then reads
                    // the field directly instead of probing TLS each iter.
                    if preResolved ne null then preResolved.setCachedSlot(slot)
                    val r = FastTier.withAccSlot(doubleAccName, slot) {
                      tryMixedLongWhile(t, mixedBody, frameView, interp, preResolved)
                    }
                    if r != null && slot(1) != 0L then
                      interp.globals(doubleAccName) =
                        Value.doubleV(longBitsToDouble(slot(0)))
                    r
                else tryMixedLongWhile(t, mixedBody, frameView, interp)
              else
                val longPeek = peekFirst(t => FastTier.peekLongAccName(t, interp))
                if longPeek != null then
                  val (foreachApplyIdx, longAccName) = longPeek
                  val initV = interp.globals.getOrElse(longAccName, null)
                  if (initV ne null) && initV.isInstanceOf[Value.IntV] then
                    val initLong = initV.asInstanceOf[Value.IntV].v.toDouble
                    // Fused JIT path for Long-acc (patternMatchWide shape).
                    val jitR = tryWhileJitMixed(
                      t, mixedBody, foreachApplyIdx, longAccName, false, initLong, frameView, interp
                    )
                    if jitR != null then jitR
                    else
                      // Fallback: Long-acc slot bypass — mirror of the Double-acc path.
                      // The slot stores raw Long bits (no encoding), so the
                      // FastTier methods read/write `acc` directly.
                      val slot = Array[Long](initV.asInstanceOf[Value.IntV].v, 1L)
                      val preResolved = tryPreResolveForeach(
                        mixedBody.leadingApplies(foreachApplyIdx),
                        foreachApplyIdx, isDouble = false,
                        interp, mixedBody.names, frameView
                      )
                      if preResolved ne null then preResolved.setCachedSlot(slot)
                      val r = FastTier.withAccSlot(longAccName, slot) {
                        tryMixedLongWhile(t, mixedBody, frameView, interp, preResolved)
                      }
                      if r != null && slot(1) != 0L then
                        interp.globals(longAccName) = Value.intV(slot(0))
                      r
                  else tryMixedLongWhile(t, mixedBody, frameView, interp)
                else
                  val mapPeek = peekFirst(t => FastTier.peekMapAccName(t, interp))
                  if mapPeek != null then
                    val (foreachApplyIdx, accKind) = mapPeek
                    val mapAccName = accKind._1
                    val mapIsDouble = accKind._2
                    val initV = interp.globals.getOrElse(mapAccName, null)
                    if (initV ne null) && (
                         (mapIsDouble && initV.isInstanceOf[Value.DoubleV])
                         || (!mapIsDouble && initV.isInstanceOf[Value.IntV])
                       )
                    then
                      // Fused JIT path: compile outer while + inner Map.foreach into one
                      // Java method, emitting an iterator loop over MapV.entries().
                      val initDouble =
                        if mapIsDouble then initV.asInstanceOf[Value.DoubleV].v
                        else initV.asInstanceOf[Value.IntV].v.toDouble
                      val jitR = tryWhileJitMixed(
                        t, mixedBody, foreachApplyIdx, mapAccName, mapIsDouble, initDouble, frameView, interp
                      )
                      if jitR != null then jitR
                      else
                        // Fallback: Map foreach slot bypass — encodes Double as raw bits,
                        // Long directly. Same slot/TLS as the Double List path.
                        val initBits =
                          if mapIsDouble then
                            doubleToRawLongBits(initV.asInstanceOf[Value.DoubleV].v)
                          else
                            initV.asInstanceOf[Value.IntV].v
                        val slot = Array[Long](initBits, 1L)
                        val preResolved = tryPreResolveForeach(
                          mixedBody.leadingApplies(foreachApplyIdx),
                          foreachApplyIdx, isDouble = mapIsDouble,
                          interp, mixedBody.names, frameView
                        )
                        if preResolved ne null then preResolved.setCachedSlot(slot)
                        val r = FastTier.withAccSlot(mapAccName, slot) {
                          tryMixedLongWhile(t, mixedBody, frameView, interp, preResolved)
                        }
                        if r != null && slot(1) != 0L then
                          interp.globals(mapAccName) =
                            if mapIsDouble then Value.doubleV(longBitsToDouble(slot(0)))
                            else                Value.intV(slot(0))
                        r
                    else tryMixedLongWhile(t, mixedBody, frameView, interp)
                  else tryMixedLongWhile(t, mixedBody, frameView, interp)
            case _                  => null
      else
        fastPrimitiveValue(t.expr, frameView, interp) match
          case Value.BoolV(false) => Computation.PureUnit
          case Value.BoolV(true) =>
            // Algebraic loop eliminators (invariant-call memoise + Gauss
            // closed-form). These rewrite a whole counter loop to a constant, so
            // a benchmark of the loop would measure the fold, not iteration.
            // Gate them behind FastTier so `SSC_FASTTIER=off` (scripts/bench off)
            // yields an honest un-folded baseline; default (on) keeps the win.
            if FastTier.enabled then
              val invariantFold = tryFoldInvariantAccumLoop(t, body, frameView, interp)
              if invariantFold != null then return invariantFold
              val closedForm = tryClosedFormPolyLoop(t, body, frameView, interp)
              if closedForm != null then return closedForm
            // Hoist pure-literal assigns to non-int LHS out of the loop, then
            // delegate the int-slot subset to tryLongWhileAssign. Returns null
            // for all-int bodies (the common case) — no overhead added.
            val hoisted = tryHoistedPureWhile(t, body, frameView, interp)
            val longLoop =
              if hoisted != null then hoisted
              else
                val jit = tryWhileJit(t, body, frameView, interp)
                if jit != null then jit
                else tryLongWhileAssign(t, body, frameView, interp)
            if longLoop != null then longLoop
            else if previewFastAssignIteration(body, t.expr, frameView, interp) == null then null
            else
              val local = frameView.underlying
              var running = true
              while running do
                var i = 0
                while i < body.names.length do
                  val v = fastPrimitiveValue(body.rhs(i), frameView, interp)
                  if v == null then interp.located("Fast while assignment left the primitive path")
                  val name = body.names(i)
                  local(name) = v
                  interp.globals(name) = v
                  i += 1
                fastPrimitiveValue(t.expr, frameView, interp) match
                  case Value.BoolV(next) => running = next
                  case _                 => running = false
              Computation.PureUnit
          case _ => null

  // Head names of the special-form `Term.Apply` cases handled below (effect
  // runners, `validate`, `bench`, `receive`, `Focus`, …). A plain application
  // `f(args)` whose head is a `Term.Name` NOT in this set can skip the entire
  // special-form scan and go straight to generic call dispatch — that linear
  // scan was the dominant self-cost of `eval` on hot recursive calls (JFR).
  // private[interpreter] so StatRuntime can recognise a curried block-form extern
  // (e.g. httpClient) and register a placeholder global for import resolution.
  private[interpreter] val reservedApplyHeads: Set[String] = Set(
    "bench", "computed", "effect", "handle", "httpClient", "receive", "restartable",
    "runActors", "runAsync", "runAsyncParallel", "runAuthWith",
    "runEphemeralStorage",
    "runStorage", "runStream",
    "runSideEffect", "runTx", "timeout", "validate", "withFixedUuid", "Focus")

  /** Generic positional/named call dispatch for `f(args...)`, shared by the
   *  plain-application fast path and the `case _` fallthrough of `app.fun`. */
  /** Run a plugin block-form `keyword(config…) { body }`: the interpreter owns the `runWithHandler`
   *  trampoline; the plugin's handler replies over `SpiValue` (it never sees `Computation`). The
   *  leading `configTerms` (empty for `keyword { body }`) are evaluated to values and handed to
   *  `BlockForm.newHandler` as handler-config args (e.g. the seed of `runRandomSeeded(seed){…}`).
   *  Shared by the loaded-plugin special-form cases and the lazy-load fallbacks below. §2d. */
  private def dispatchBlockForm(
      bf: scalascript.backend.spi.BlockForm, configTerms: List[Term], bodyTerm: Term,
      env: Env, interp: Interpreter
  ): Computation =
    val bctx    = new scalascript.backend.spi.BlockContext:
      def out: java.io.PrintStream = interp.out
      // Apply a ScalaScript closure (an op argument, e.g. `State.modify(f)`) by routing back into
      // the interpreter's `callValue` and running it synchronously — parity with the former
      // `interp.callValue1` path. The function + args cross the boundary as `SpiValue`.
      override def applyFn(fn: scalascript.backend.spi.SpiValue,
                           args: List[scalascript.backend.spi.SpiValue]): scalascript.backend.spi.SpiValue =
        val res = Computation.run(interp.callValue(interp.spiToValue(fn), args.map(interp.spiToValue), Map.empty))
        interp.valueToSpi(res)
      // Build a `Value.InstanceV(typeName, {fields})` and hand it back as an opaque SpiValue; the
      // interp unwraps the Opaque to the record when resuming the body. Lets a handler reply with a
      // typed record (e.g. `Http.get` → `Response { status, headers, body }`). §2d core-min.
      override def makeRecord(typeName: String,
                              fields: List[(String, scalascript.backend.spi.SpiValue)]): scalascript.backend.spi.SpiValue =
        val m: Map[String, Value] = fields.iterator.map((kv) => kv._1 -> interp.spiToValue(kv._2)).toMap
        scalascript.backend.spi.SpiValue.Opaque(Value.InstanceV(typeName, m))
      // Read an execution-scoped feature-local value (e.g. the http base URL set by httpClient(...)),
      // so a handler can read host config a runner needs. §2d core-min.
      override def featureLocal(key: String): Option[Any] = interp.nativeFeatureLocalGet(key)
    val cfgArgs = configTerms.map(t => interp.valueToSpi(Computation.run(eval(t, env, interp))))
    val handler = bf.newHandler(bctx, cfgArgs)
    val body    = eval(bodyTerm, env, interp)
    val ran = EffectHandlers.runWithHandler(body, bf.effectName,
      (op, args, resume) => resume(interp.spiToValue(handler.reply(op, args.map(interp.valueToSpi)))))
    ran.flatMap(r => Computation.Pure(interp.spiToValue(bf.result(interp.valueToSpi(r), handler))))

  private def evalPlainApply(app: Term.Apply, env: Env, interp: Interpreter): Computation =
    // Flatten nested Apply nodes so that curried calls like `f(a)(using b)`
    // are collected into a single `interp.callValue(f, [a, b])` invocation.
    val (baseFun, allArgTerms) = collectApplyArgs(app)
    // Lazy plugin block-form (`runLogger { … }`): an unresolved single-arg applied name may be a
    // block-form a ServiceLoader plugin hasn't registered yet. Only an *unresolved* head (absent
    // from env + globals) reaches the load — a resolved call skips this, so a plugin-free script
    // never triggers the ServiceLoader scan here. (polyglot-libraries §2d, lazy-ServiceLoader.)
    baseFun match
      case Term.Name(kw) if allArgTerms.sizeIs == 1 && !env.contains(kw) && !interp.globals.contains(kw) =>
        if !interp.blockForms.contains(kw) && !interp._pluginsLoaded then interp.ensurePluginsLoaded()
        interp.blockForms.get(kw) match
          case Some(bf) => return dispatchBlockForm(bf, Nil, allArgTerms.head, env, interp)
          case None     => ()
      case _ => ()
    val hasNamedArgs = allArgTerms.exists(_.isInstanceOf[Term.Assign])
    // Pure-free fast lane: name/literal head + name/literal args (no named args,
    // no debug hooks). Reads head and args as Values with no Pure wrappers and
    // dispatches directly; any non-fast operand falls through to the monadic
    // path below. callValue/callValue1 are the same calls the slow path makes.
    if !hasNamedArgs && interp.debugHooks.isEmpty then
      val fv = fastValue(baseFun, env, interp)
      if fv != null then
        allArgTerms match
          case one :: Nil =>
            val av = fastValue(one, env, interp)
            if av != null then return interp.callValue1(fv, av, env)
          case Nil => return interp.callValue0(fv, env)
          case a :: b :: Nil =>
            val av1 = fastValue(a, env, interp)
            if av1 != null then
              val av2 = fastValue(b, env, interp)
              if av2 != null then return interp.callValue(fv, av1 :: av2 :: Nil, env)
          case _ => ()
    val funC     = eval(baseFun, env, interp)
    if hasNamedArgs then
      val namedComps = allArgTerms.map {
        case Term.Assign(Term.Name(n), rhs) => (Some(n), eval(rhs, env, interp))
        case other                          => (None,    eval(other, env, interp))
      }
      val comps = namedComps.map(_._2)
      funC match
        case Pure(fv) if comps.forall(_.isInstanceOf[Pure]) =>
          val namedVals = namedComps.map { case (k, Pure(v)) => (k, v); case (k, _) => (k, Value.UnitV) }
          CallRuntime.callValueNamed(fv, namedVals, env, interp)
        case _ =>
          FlatMap(funC, fv =>
            interp.threadValues(comps)(argVals =>
              CallRuntime.callValueNamed(fv, namedComps.map(_._1).zip(argVals), env, interp)))
    else if allArgTerms.lengthCompare(1) == 0 then
      val arg1C = eval(allArgTerms.head, env, interp)
      funC match
        case Pure(fv) =>
          arg1C match
            case Pure(av) => interp.callValue1(fv, av, env)
            case _        => FlatMap(arg1C, av => interp.callValue1(fv, av, env))
        case _ =>
          arg1C match
            case Pure(av) => FlatMap(funC, fv => interp.callValue1(fv, av, env))
            case _        => FlatMap(funC, fv => FlatMap(arg1C, av => interp.callValue1(fv, av, env)))
    else if allArgTerms.lengthCompare(2) == 0 then
      val arg1C = eval(allArgTerms.head, env, interp)
      val arg2C = eval(allArgTerms(1),   env, interp)
      funC match
        case Pure(fv) =>
          arg1C match
            case Pure(av1) =>
              arg2C match
                case Pure(av2) => interp.callValue(fv, av1 :: av2 :: Nil, env)
                case _         => FlatMap(arg2C, av2 => interp.callValue(fv, av1 :: av2 :: Nil, env))
            case _ =>
              arg2C match
                case Pure(av2) => FlatMap(arg1C, av1 => interp.callValue(fv, av1 :: av2 :: Nil, env))
                case _         => FlatMap(arg1C, av1 => FlatMap(arg2C, av2 => interp.callValue(fv, av1 :: av2 :: Nil, env)))
        case _ =>
          arg1C match
            case Pure(av1) if arg2C.isInstanceOf[Pure] =>
              FlatMap(funC, fv => interp.callValue(fv, av1 :: arg2C.asInstanceOf[Pure].value :: Nil, env))
            case _ =>
              FlatMap(funC, fv => FlatMap(arg1C, av1 => FlatMap(arg2C, av2 => interp.callValue(fv, av1 :: av2 :: Nil, env))))
    else
      val argComps = allArgTerms.map(eval(_, env, interp))
      val argVsPos = extractPureValues(argComps)
      if argVsPos != null then
        funC match
          case Pure(fv) => interp.callValue(fv, argVsPos, env)
          case _        => FlatMap(funC, fv => interp.callValue(fv, argVsPos, env))
      else
        FlatMap(funC, fv =>
          interp.threadValues(argComps)(argVals => interp.callValue(fv, argVals, env)))

  def eval(term: Term, env: Env, interp: Interpreter): Computation =
    // Pure-const cache fast path: for `Term.Tuple` and `Term.ApplyInfix` whose
    // subtree is fully literal (e.g. `(1, 2) ++ (3, 4)` in a hot loop), memoise
    // the result by AST identity. First visit computes + classifies + stores;
    // subsequent visits return a single HashMap.get + Pure wrap. Limited to
    // these two kinds because they're the heavyweight allocators that benefit
    // from caching — Lit is already interned via litCache. effect-vm-cont-p3 adds
    // constant immutable collection literals (`List(1,2,3,4)`) — rebuilt per visit
    // in a hot loop / multi-shot continuation; the callee-name gate keeps non-`List`
    // applies (e.g. `fib(n-1)`) off the cache path.
    val isCacheKind = term.isInstanceOf[Term.Tuple] || term.isInstanceOf[Term.ApplyInfix] ||
      (term.isInstanceOf[Term.Apply] && isConstCollName(term.asInstanceOf[Term.Apply].fun))
    if isCacheKind then
      val cv = interp.pureConstCache.get(term)
      if cv != null && (cv ne interp.NotPure) then
        return Computation.purify(cv.asInstanceOf[Value])
    val result = evalCore(term, env, interp)
    if isCacheKind && (interp.pureConstCache.get(term) == null) then
      // Lazily classify + populate. Pure result + literal subtree → cache the
      // value. Otherwise stamp NotPure so the next visit skips the purity walk.
      if isPureConstExpr(term) then
        result match
          case Pure(v) => interp.pureConstCache.put(term, v.asInstanceOf[AnyRef])
          case _       => interp.pureConstCache.put(term, interp.NotPure)
      else
        interp.pureConstCache.put(term, interp.NotPure)
    result

  /** True iff `t`'s subtree is built only from `Lit.*`, `Term.Tuple`, and
   *  `Term.ApplyInfix` on pure ops (`+`, `-`, `*`, `/`, `%`, `++`). Used to
   *  decide whether a Term's evaluation is deterministic + env-independent and
   *  therefore cacheable. Caching is otherwise inserted by `eval` for the
   *  `Term.Tuple`/`Term.ApplyInfix` outer kinds only. */
  /** The callee of a constant **immutable** collection literal — `List(..)` / `Vector(..)` /
   *  `Seq(..)`. Such a literal with all-pure-const args is loop-invariant and its value is
   *  immutable (safe to share), so `pureConstCache` can memoise it (effect-vm-cont-p3: the
   *  `choose(List(1,2,3,4))` arg was rebuilt ~85× across the multi-shot continuation re-runs;
   *  also a general win for any `const-collection` literal in a hot loop). Plain field-access on
   *  the `Term.Name` (no `unapply`) keeps the per-`Apply` cache-gate cheap. */
  private def isConstCollName(t: Term): Boolean =
    t.isInstanceOf[Term.Name] && {
      val n = t.asInstanceOf[Term.Name].value
      n == "List" || n == "Vector" || n == "Seq"
    }

  /** The distinct `Term.Name` strings appearing anywhere in a lambda `body` — a SOUND
   *  over-approximation of its free variables (it also picks up locally-bound names,
   *  method-selection names, and names inside nested lambdas; all harmless for closure
   *  capture — see `lambdaFreeNamesCache`). Used once per lambda AST (cached) so a
   *  closure captures only these names from the env instead of iterating the whole env. */
  private def collectBodyNames(body: Term): Array[String] =
    // `.collect` visits the whole subtree INCLUDING the root, so a body that is itself a
    // bare `Term.Name` (`x => x`) is covered. `.distinct` dedups; done once per lambda.
    body.collect { case n: Term.Name => n.value }.distinct.toArray

  private def isPureConstExpr(t: Term): Boolean = t match
    case _: Lit         => true
    case tu: Term.Tuple =>
      var ok = true
      var xs = tu.args
      while ok && xs.nonEmpty do
        ok = isPureConstExpr(xs.head)
        xs = xs.tail
      ok
    case app: Term.Apply if isConstCollName(app.fun) =>
      var ok = true
      var xs = app.argClause.values
      while ok && xs.nonEmpty do
        ok = isPureConstExpr(xs.head)
        xs = xs.tail
      ok
    case app: Term.ApplyInfix =>
      val s = app.op.value
      val opPure = s == "+" || s == "-" || s == "*" || s == "/" || s == "%" || s == "++"
      if !opPure then false
      else if !isPureConstExpr(app.lhs) then false
      else
        var ok = true
        var xs = app.argClause.values
        while ok && xs.nonEmpty do
          ok = isPureConstExpr(xs.head)
          xs = xs.tail
        ok
    case _ => false

  private def evalCore(term: Term, env: Env, interp: Interpreter): Computation =
    interp.trackPos(term)
    // DAP step/breakpoint hook: called for every term so DebugHooks can decide
    // whether to suspend (breakpoint, stepIn, stepOver, stepOut).
    // currentSpan: block-relative 0-based line; docLine = debugBlockDocLine + blockLine + 1.
    // callStack.length is the current call depth (0 at top level).
    // Pattern match instead of .foreach: .foreach { hooks => ... } allocates a lambda
    // on every eval call even when debugHooks is None. Match desugars to isEmpty+get.
    interp.debugHooks match
      case Some(hooks) if interp.currentSpanLine >= 0 =>
        val blockLine  = interp.currentSpanLine
        val docLine    = interp.debugBlockDocLine + blockLine + 1
        val callFrames = interp.callStackToIndexedSeq.map { case (n, sf, l) =>
          scalascript.interpreter.debug.CallFrameEntry(n, sf, l)
        }
        val frame = scalascript.interpreter.debug.DebugFrame(0, "frame", interp.debugSourceFile, docLine, interp.callStackLength, env, callFrames)
        hooks.onStep(frame)
      case _ =>
    term match
    // Literals — interned by Lit identity so a hot loop reuses the same
    // `Pure(Value)` instance instead of reallocating on every eval. The
    // parser produces a stable AST; each `Lit.Int(1)` etc. is the same
    // Scala object across all visits.
    case lit: Lit =>
      val cached = interp.litCache.get(lit)
      if cached != null then cached
      else
        val c = lit match
          case Lit.Int(v)     => Computation.pureIntV(v.toLong)
          case Lit.Long(v)    => Computation.pureIntV(v)
          case Lit.Double(v)  => Pure(Value.doubleV(v.toString.toDouble))
          case Lit.Float(v)   => Pure(Value.doubleV(v.toString.toDouble))
          case Lit.String(v)  => Pure(Value.StringV(v))
          case Lit.Boolean(v) => Computation.pureBool(v)
          case Lit.Char(v)    => Pure(Value.charV(v))
          case Lit.Unit()     => Computation.PureUnit
          case Lit.Null()     => Computation.PureNull
          case _              => Computation.PureNull
        interp.litCache.put(lit, c)
        c

    // Name lookup: local env first, then interp.globals.
    // Phase 2 lazy loading: on first miss we trigger the plugin ServiceLoader
    // scan (ensurePluginsLoaded) so scripts like `hello.ssc` that never touch
    // a plugin never pay the cost.  After plugins load we re-check globals; if
    // still missing, the name is genuinely undefined.
    // Type-only check avoids Term.Name.unapply which allocates Some(name).
    case tn: Term.Name =>
      val name = tn.value
      // null-default lookups (not by-name getOrElse) to avoid a Function0 thunk per name.
      val ev = env.getOrElse(name, null)
      val v = if ev != null then ev else interp.globals.getOrElse(name, null)
      // `Computation.purify` reuses the cached `Pure` wrapper for pool-cached
      // IntV / BoolV / Unit / Null / None / EmptyList / EmptyStr. Term.Name
      // is the hottest Pure-allocating eval path (JFR-2026-06-02 on
      // `recursiveEval` showed Pure as the top sampled allocator).
      if v != null then Computation.purify(v)
      else if interp._pluginsLoaded then interp.located(s"Undefined: $name")
      else
        interp.ensurePluginsLoaded()
        Computation.purify(interp.globals.getOrElse(name, interp.located(s"Undefined: $name")))

    // Fast path: plain application `f(args)` where the head is a user-level
    // `Term.Name` that is NOT a reserved special form. Skips the ~40
    // special-form `Term.Apply` cases below — a linear scan that dominated
    // eval's self-time on hot recursive calls (e.g. fib). Curried calls
    // (head is itself an Apply) and method calls (head is a Select) fall
    // through to the full match unchanged.
    case app: Term.Apply if (app.fun match
          // A plugin-contributed block-form keyword (e.g. `runTally`) must reach the
          // special-form cases below, not the fast path. `interp.blockForms` is empty until a
          // plugin loads, so a plugin-free script keeps the original fast path unchanged.
          case n: Term.Name => !reservedApplyHeads.contains(n.value) && !interp.blockForms.contains(n.value)
          case _            => false) =>
      evalPlainApply(app, env, interp)
    // Method calls (`recv.m(args)`) have a `Term.Select` head, which no
    // special-form `Term.Apply` case below can match (every one has a
    // `Term.Name` or curried-`Term.Apply` head). Route them straight to the
    // general handler, skipping the ~40 special-form extractor checks — each
    // would otherwise call `Term.Apply.unapply`, allocating a Some+Tuple2 per
    // method call (JFR: ~8% of patternMatch allocations).
    case app: Term.Apply if app.fun.isInstanceOf[Term.Select] =>
      evalApplyGeneral(app, env, interp)

    // Curried method calls (`recv.m(a)(b)…`) have a `Term.Apply` head whose
    // own head is a `Term.Select`. Every curried special-form below
    // (`withFixedUuid(x){body}`, `runState(s0){body}`, `handle(…){body}`, …)
    // instead has a `Term.Name` head — none is a method call — so a curried
    // method call can be routed straight to the general handler. Without this,
    // each such call walks the ~40 curried special-form extractors above, and
    // their inner `Term.Apply.unapply` allocates a Tuple2 every time (JFR on
    // typeclass-fold: `xs.foldLeft(z)(op)` → 740 MB/op, the single dominant
    // allocator). The `inner.fun` type-test is allocation-free.
    case app: Term.Apply if (app.fun match
          case inner: Term.Apply => inner.fun.isInstanceOf[Term.Select]
          case _                 => false) =>
      evalApplyGeneral(app, env, interp)


    // ── Hot non-Apply terms, hoisted above the ~40 special-form `Term.Apply`
    //    cases below.  These types (ApplyInfix, If, Block, Match) are siblings
    //    of Term.Apply under Term, so their relative order with the Apply cases
    //    is irrelevant to semantics — but placing them first means recursive
    //    workloads (fib: If + 4×ApplyInfix per call; arith loops: ApplyInfix
    //    per iteration; pattern matching: Match) no longer pay ~40 failing
    //    instanceof/extractor checks on every visit.  JFR showed eval's own
    //    match body dominating self-time on these workloads.

    // `head #:: tail` — lazy cons. Evaluate the head eagerly but DEFER the tail (Scala's by-name
    // cons), so a self-recursive stream def `def from(n) = n #:: from(n+1)` yields an infinite
    // `LazyList` instead of recursing eagerly. `LazyList.cons`'s second param is by-name, so the
    // braced tail block runs only when the tail is forced. (collection-real-type.)
    case app: Term.ApplyInfix if app.op.value == "#::" && app.argClause.values.lengthCompare(1) == 0 =>
      val tailExpr = app.argClause.values.head
      eval(app.lhs, env, interp).flatMap { headV =>
        Pure(Value.LazyListV(LazyList.cons(headV, {
          Computation.run(eval(tailExpr, env, interp)) match
            case llv: Value.LazyListV => llv.underlying
            case l: Value.ListV       => l.items.to(LazyList)
            case a: Value.ArrayV      => a.items.to(LazyList)
            case Value.SetV(s)        => s.to(LazyList)
            case other                => other #:: LazyList.empty[Value]
        })))
      }

    // Short-circuiting `&&` / `||` — MUST intercept before the general infix
    // case below (which eagerly evaluates the argClause).  Scala/JS semantics:
    // the right operand is evaluated only when the left operand demands it, so
    // a guarded access like `xs.nonEmpty && xs.head > 0` never touches `.head`
    // on an empty list.  Lowered to control flow:
    //   `a && b` ≡ `if a then b else false`
    //   `a || b` ≡ `if a then true else b`
    // (bug: interp-boolean-operators-no-short-circuit).
    case app: Term.ApplyInfix
        if (app.op.value == "&&" || app.op.value == "||")
           && app.argClause.values.lengthCompare(1) == 0 =>
      val isAnd   = app.op.value == "&&"
      val rhsTerm = app.argClause.values.head
      def afterLhs(lv: Value): Computation = lv match
        case Value.BoolV(b) =>
          // (true,&&)→rhs ; (false,||)→rhs ; (false,&&)→false ; (true,||)→true
          if b == isAnd then eval(rhsTerm, env, interp)
          else Computation.pureBool(b)
        case other =>
          // Non-Boolean left operand (a custom/overloaded `&&`/`||`): fall back
          // to the general two-arg dispatch so behaviour is unchanged for it.
          eval(rhsTerm, env, interp) match
            case Pure(rv) => interp.infix2(other, app.op.value, rv, env)
            case rhsC     => FlatMap(rhsC, rv => interp.infix2(other, app.op.value, rv, env))
      eval(app.lhs, env, interp) match
        case Pure(lv) => afterLhs(lv)
        case lhsC     => FlatMap(lhsC, afterLhs)

    // Infix operators — handles both plain binary (a + b) and compound
    // assignment (x += e).  Using `case app: Term.ApplyInfix` avoids calling
    // Term.ApplyInfix.After_4_6_0.unapply, which allocates a Tuple4 even for
    // binary ops where the compound-assignment guard then fails.
    case app: Term.ApplyInfix =>
      val opStr = app.op.value
      app.lhs match
        // Compound assignment: x += e, x -= e, x *= e, x /= e, x %= e
        case lhsName: Term.Name
            if opStr.length > 1 && opStr.last == '=' && !BlockRuntime.isCompareOp(opStr) =>
          val baseOp   = opStr.init
          val argVals  = app.argClause.values
          if argVals.lengthCompare(1) == 0 then
            val rhsC = eval(argVals.head, env, interp)
            eval(lhsName, env, interp).flatMap { lhsV =>
              rhsC.flatMap { rv =>
                interp.infix2(lhsV, baseOp, rv, env).flatMap { newV =>
                  interp.globals(lhsName.value) = newV; Computation.PureUnit } } }
          else
            eval(lhsName, env, interp).flatMap { lhsV =>
              val argComps = argVals.map(eval(_, env, interp))
              interp.threadValues(argComps) { argVs =>
                interp.infix(lhsV, baseOp, argVs, env).flatMap { newV =>
                  interp.globals(lhsName.value) = newV
                  Computation.PureUnit
                }
              }
            }
        // Plain binary infix: a op b
        case lhs =>
          val argVals = app.argClause.values
          if argVals.lengthCompare(1) == 0 then
            val rhsTerm = argVals.head
            val fast =
              if interp.debugHooks.isEmpty then fastPrimitiveInfixTerm(lhs, opStr, rhsTerm, env, interp)
              else null
            if fast != null then fast
            else
              // Pure-free operand reads: a name/literal operand is fetched as a
              // Value with no Pure wrapper; only operands that may be effectful
              // (calls, blocks, …) take the monadic eval path. Names/literals are
              // side-effect-free reads, so evaluation order is preserved.
              val lvFast = fastValue(lhs, env, interp)
              val rvFast = fastValue(rhsTerm, env, interp)
              if lvFast != null && rvFast != null then
                interp.infix2(lvFast, opStr, rvFast, env)
              else if lvFast != null then
                val rhsC = eval(rhsTerm, env, interp)
                rhsC match
                  case Pure(rv) => interp.infix2(lvFast, opStr, rv, env)
                  case _        => FlatMap(rhsC, rv => interp.infix2(lvFast, opStr, rv, env))
              else if rvFast != null then
                val lhsC = eval(lhs, env, interp)
                lhsC match
                  case Pure(lv) => interp.infix2(lv, opStr, rvFast, env)
                  case _        => FlatMap(lhsC, lv => interp.infix2(lv, opStr, rvFast, env))
              else
                val lhsC = eval(lhs, env, interp)
                val rhsC = eval(rhsTerm, env, interp)
                lhsC match
                  case Pure(lv) =>
                    rhsC match
                      case Pure(rv) => interp.infix2(lv, opStr, rv, env)
                      case _        => FlatMap(rhsC, rv => interp.infix2(lv, opStr, rv, env))
                  case _ =>
                    rhsC match
                      case Pure(rv) => FlatMap(lhsC, lv => interp.infix2(lv, opStr, rv, env))
                      case _        => FlatMap(lhsC, lv => FlatMap(rhsC, rv => interp.infix2(lv, opStr, rv, env)))
          else
            val lhsC     = eval(lhs, env, interp)
            val argComps = argVals.map(eval(_, env, interp))
            val argVsInfix = extractPureValues(argComps)
            lhsC match
              case Pure(lhsV) if argVsInfix != null =>
                interp.infix(lhsV, opStr, argVsInfix, env)
              case _ =>
                FlatMap(lhsC, lhsV =>
                  interp.threadValues(argComps)(argVs => interp.infix(lhsV, opStr, argVs, env)))

    // if/then/else
    case t: Term.If =>
      eval(t.cond, env, interp) match
        case Pure(Value.BoolV(true))  => eval(t.thenp, env, interp)
        case Pure(Value.BoolV(false)) => eval(t.elsep, env, interp)
        case Pure(other)              => interp.located(s"if condition must be Boolean, got ${Value.show(other)}")
        case condC                    => FlatMap(condC, {
          case Value.BoolV(true)  => eval(t.thenp, env, interp)
          case Value.BoolV(false) => eval(t.elsep, env, interp)
          case other              => interp.located(s"if condition must be Boolean, got ${Value.show(other)}")
        })

    // Block { stmts; expr }
    // Type test (not Term.Block(stats) extractor) to avoid a per-eval Some alloc.
    case t: Term.Block =>
      BlockRuntime.evalBlock(t.stats, env, interp)

    // Pattern match — compiled-matcher cache keyed by the Term.Match node.
    case t: Term.Match =>
      var compiled = interp.matchCache.get(t)
      if compiled == null then
        compiled = PatternRuntime.compileMatch(t, interp)
        interp.matchCache.put(t, compiled)
      val compiledM = compiled
      // Pure-free scrutinee read: a plain name/literal scrutinee (the common case)
      // is fetched as a Value with no throwaway Pure wrapper.
      val fastScrut = fastValue(t.expr, env, interp)
      if fastScrut != null then compiledM.run(fastScrut, env, interp)
      else eval(t.expr, env, interp) match
        case Pure(scrutV) => compiledM.run(scrutV, env, interp)
        case exprC        => FlatMap(exprC, scrutV => compiledM.run(scrutV, env, interp))

    // Special form: handle(body) { case Eff.op(args, resume) => ... }
    case Term.Apply.After_4_6_0(
      Term.Apply.After_4_6_0(Term.Name("handle"), bodyArgClause),
      pfArgClause
    ) if bodyArgClause.values.size == 1 =>
      pfArgClause.values match
        case List(pf: Term.PartialFunction) =>
          EffectsRuntime.evalHandle(bodyArgClause.values.head.asInstanceOf[Term], pf.cases, env, interp,
            interp.multiShotEffects)
        case _ => interp.located("handle expects a partial function { case Eff.op(args, resume) => ... }")

    // Special form: runAsync(body) — default Async handler.  The body is
    // a by-name expression; we evaluate it lazily so its interp.effects compose
    // into the Computation tree before the driver walks them.
    case Term.Apply.After_4_6_0(Term.Name("runAsync"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      AsyncRuntime.asyncInterp(eval(bodyArgClause.values.head, env, interp), interp)

    // Special form: runAsyncParallel(body) — alternate Async handler
    // that executes thunks passed to `async` / `parallel` on real
    // JVM threads (ExecutorService + CompletableFuture).  `await`
    // blocks the calling thread on the future; `parallel` returns
    // results in declared order regardless of completion order so
    // value-deterministic code still produces byte-identical output.
    case Term.Apply.After_4_6_0(Term.Name("runAsyncParallel"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      AsyncRuntime.asyncParInterp(eval(bodyArgClause.values.head, env, interp), interp)

    // Special forms: runStorage / runEphemeralStorage — Storage-effect
    // handlers.  The former hydrates from / persists to a JSON file
    // (path from the optional second arg or `SSC_STORAGE_PATH` env,
    // defaulting to `./ssc-storage.json`); the latter keeps the map
    // in-memory and discards it at scope exit.
    case Term.Apply.After_4_6_0(Term.Name("runStorage"), bodyArgClause)
        if bodyArgClause.values.size >= 1 =>
      val pathOpt = bodyArgClause.values.lift(1).map { p =>
        Computation.run(eval(p, env, interp)) match
          case Value.StringV(s) => s
          case _                => StorageRuntime.defaultPath
      }
      StorageRuntime.interp(eval(bodyArgClause.values.head, env, interp), Some(pathOpt.getOrElse(StorageRuntime.defaultPath)))
    case Term.Apply.After_4_6_0(Term.Name("runEphemeralStorage"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      StorageRuntime.interp(eval(bodyArgClause.values.head, env, interp), None)

    // ── v1.4 Logger effect handlers ───────────────────────────────────────
    // runLogger / runLoggerJson / runLoggerToList — EXTRACTED to `logger-effect-plugin`
    // (core-minimization). They now resolve via the generic block-form dispatch below + the
    // lazy ServiceLoader path in `evalPlainApply`. (polyglot-libraries §2d.)

    // Generic plugin-contributed block-form `keyword { body }` (polyglot-libraries §2d).
    // Fires only for a keyword a loaded plugin registered (`interp.blockForms` is empty until a
    // plugin loads, so a plugin-free script never reaches here). The interpreter owns the
    // Computation trampoline (`runWithHandler`); the plugin's handler replies over `SpiValue`.
    case Term.Apply.After_4_6_0(Term.Name(kw), bodyArgClause)
        if bodyArgClause.values.size == 1 && interp.blockForms.contains(kw) =>
      dispatchBlockForm(interp.blockForms(kw), Nil, bodyArgClause.values.head, env, interp)

    // ── v1.4 Random effect handlers ───────────────────────────────────────
    // runRandom / runRandomSeeded — EXTRACTED to `random-effect-plugin` (core-minimization).
    // runRandom { body } resolves via the lazy single-clause path; runRandomSeeded(seed) { body }
    // via the generic curried block-form cases below. (polyglot-libraries §2d.)

    // ── v1.4 Clock + Env effect handlers ──────────────────────────────────
    // runClock / runClockAt(t0) — EXTRACTED to `clock-effect-plugin`.
    // runEnv / runEnvWith(map)  — EXTRACTED to `env-effect-plugin`.
    // The plain forms resolve via the lazy single-clause path; the curried
    // config-args forms via the generic curried block-form cases below. §2d.

    // ── v1.65 SideEffect handlers ─────────────────────────────────────────
    // runSideEffect { body }             — identity; just evaluates body
    // withFixedUuid(fixed) { body }      — overrides Uuid.v4/v7 for test determinism
    case Term.Apply.After_4_6_0(Term.Name("runSideEffect"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      eval(bodyArgClause.values.head, env, interp)

    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("withFixedUuid"), fixedClause),
        bodyClause)
        if fixedClause.values.size == 1 && bodyClause.values.size == 1 =>
      val fixed = Computation.run(eval(fixedClause.values.head, env, interp)) match
        case Value.StringV(s) => s
        case _ => throw InterpretError("withFixedUuid(fixed: Uuid) { body }")
      interp.nativeFeatureLocalSet("scalascript.uuid.fixed", fixed)
      try eval(bodyClause.values.head, env, interp)
      finally interp.nativeFeatureLocalRemove("scalascript.uuid.fixed")

    // ── v1.4 Http effect handlers — EXTRACTED to `http-plugin`.blockForms (core-min §2d) ──
    // runHttp { body } (real I/O) + runHttpStub(routes) { body } (stub) now resolve via the lazy
    // ServiceLoader path (single-clause + generic-curried block-form cases); the handler replies
    // with a `Response` record via `BlockContext.makeRecord`, reading config via `featureLocal`.
    // `httpClient(baseUrl) { body }` stays a core form (a feature-local config setter, below).

    // ── v1.51.6 Stream effect handler
    // runStream { body }  — discharges Stream.emit(x) performs; returns Source[A]
    case Term.Apply.After_4_6_0(Term.Name("runStream"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      // Lazily install Stream global so embedded interpreters (e.g. JMH bench
      // harness) that skip initBuiltins can call Stream.emit inside the body.
      if interp.globals.getOrElse("Stream", null) == null then
        StdEffectsRuntime.installStreamGlobal(interp)
      val bodyTerm = bodyArgClause.values.head
      // FastTier: `while … Stream.emit(expr); i = i+n` pattern — bypass the
      // Free Monad trampoline by swapping in a buffer-filling emit, letting
      // the while loop hit its all-pure fast path (zero FlatMap allocations).
      val fast = tryStreamEmitWhileFast(bodyTerm, env, interp)
      if fast != null then fast
      else EffectHandlers.streamRun(eval(bodyTerm, env, interp), interp)

    // ── v1.4 State effect handlers ────────────────────────────────────────
    // runState(s0) { body } — EXTRACTED to `state-effect-plugin` (core-minimization).
    // Resolves via the generic curried block-form cases below; `State.modify(f)` applies the
    // closure through `BlockContext.applyFn`. Returns (finalState, result). §2d.

    // ── v1.4 Auth effect handlers ─────────────────────────────────────────
    // runAuthWith(user) { body }  — injects a fixed user via thread-local;
    // body is run synchronously so the thread-local is set during execution.
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("runAuthWith"), userClause),
        bodyClause)
        if userClause.values.size == 1 && bodyClause.values.size == 1 =>
      val user = Computation.run(eval(userClause.values.head, env, interp))
      val prior = interp._authUser.get()
      interp._authUser.set(Some(user))
      try Pure(Computation.run(eval(bodyClause.values.head, env, interp)))
      finally interp._authUser.set(prior)

    // ── v1.4 Retry / Cache effect handlers ────────────────────────────────
    // EXTRACTED to `retry-effect-plugin` / `cache-effect-plugin` (core-minimization,
    // polyglot-libraries §2d). runRetry/runRetryNoSleep and runCache/runCacheBypass now
    // dispatch through `Backend.blockForms` + the generic block-form case below; the retried/
    // memoized thunk is invoked via `BlockContext.applyFn`. `runWithHandler` stays in core.

    // ── v1.4 Tx effect handlers ───────────────────────────────────────────
    // runTx { body }  — default no-op handler (just runs body directly)
    case Term.Apply.After_4_6_0(Term.Name("runTx"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      eval(bodyArgClause.values.head, env, interp)

    // ── v1.5 httpClient(baseUrl) { block } ───────────────────────────────
    // Double-apply special form: evaluate body directly (not as a thunk) so
    // any statements inside the block run with feature-local HTTP state set,
    // then restore.
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("httpClient"), baseClause),
        bodyClause)
        if baseClause.values.size == 1 && bodyClause.values.size == 1 =>
      val baseComp = eval(baseClause.values.head, env, interp)
      baseComp match
        case Pure(Value.StringV(base)) =>
          val priorBase = interp.nativeFeatureLocalGet(interp.NativeFeatureKeys.HttpBaseUrl)
          val priorT = interp.nativeFeatureLocalGet(interp.NativeFeatureKeys.HttpTimeoutMs)
          val priorR = interp.nativeFeatureLocalGet(interp.NativeFeatureKeys.HttpMaxRetries)
          val priorD = interp.nativeFeatureLocalGet(interp.NativeFeatureKeys.HttpRetryDelayMs)
          interp.nativeFeatureLocalSet(interp.NativeFeatureKeys.HttpBaseUrl, base.stripSuffix("/"))
          try eval(bodyClause.values.head, env, interp)
          finally restoreHttpClientState(interp, priorBase, priorT, priorR, priorD)
        case _ =>
          FlatMap(baseComp, {
            case Value.StringV(base) =>
              val priorBase = interp.nativeFeatureLocalGet(interp.NativeFeatureKeys.HttpBaseUrl)
              val priorT = interp.nativeFeatureLocalGet(interp.NativeFeatureKeys.HttpTimeoutMs)
              val priorR = interp.nativeFeatureLocalGet(interp.NativeFeatureKeys.HttpMaxRetries)
              val priorD = interp.nativeFeatureLocalGet(interp.NativeFeatureKeys.HttpRetryDelayMs)
              interp.nativeFeatureLocalSet(interp.NativeFeatureKeys.HttpBaseUrl, base.stripSuffix("/"))
              try eval(bodyClause.values.head, env, interp)
              finally restoreHttpClientState(interp, priorBase, priorT, priorR, priorD)
            case _ => throw InterpretError("httpClient(baseUrl: String) { body }")
          })

    // ── v1.6 Actors Phase 1 ────────────────────────────────────────────
    // `runActors { body }` installs an actor scheduler, spawns the body
    // as the root actor, and drives until quiescence.  The result is
    // whatever the root actor returned, or UnitV if it never did.
    case Term.Apply.After_4_6_0(Term.Name("runActors"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      interp.actorInterp(eval(bodyArgClause.values.head, env, interp))

    // v1.5 Tier 5 #20 — `validate { body }` runs `body` with an active
    // validation collector.  Returns `Right(bodyResult)` when the body
    // ran without any `require*` complaint, else `Left(Map[field,
    // reason])` capturing every problem in document order.  `require*`
    // inside the block returns a safe default on miss/invalid so the
    // body keeps running and accumulates errors in one pass.
    case Term.Apply.After_4_6_0(Term.Name("validate"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val buf = mutable.LinkedHashMap.empty[String, String]
      interp.validationStack.push(buf)
      val bodyComp = try eval(bodyArgClause.values.head, env, interp)
                     finally ()  // never pop in the try — see below
      bodyComp.flatMap { result =>
        interp.validationStack.pop()
        if buf.nonEmpty then
          val errMap = Value.MapV(
            scala.collection.immutable.ListMap.from(
              buf.map { (k, v) => Value.StringV(k) -> Value.StringV(v) }
            )
          )
          Pure(Value.InstanceV("Left", new IMap.Map1("value", errMap)))
        else
          Pure(Value.InstanceV("Right", new IMap.Map1("value", result)))
      }

    // ── bench(name) { body } / bench(name, warmup, reps) { body } ───────────
    // Timing micro-benchmark: evaluates body warmup+reps times, prints a one-
    // line summary to interp.out, and returns the last result.
    //   bench("label") { expr }
    //   bench("label", 2, 7) { expr }   — explicit warmup / rep counts
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("bench"), nameClause),
        bodyClause)
        if nameClause.values.nonEmpty && bodyClause.values.size == 1 =>
      val nameVal = Computation.run(eval(nameClause.values.head, env, interp))
      val label = nameVal match
        case Value.StringV(s) => s
        case other            => Value.show(other)
      val warmup = nameClause.values.lift(1).map { t =>
        Computation.run(eval(t, env, interp)) match
          case Value.IntV(n) => n.toInt
          case _             => 2
      }.getOrElse(2)
      val reps = nameClause.values.lift(2).map { t =>
        Computation.run(eval(t, env, interp)) match
          case Value.IntV(n) => n.toInt
          case _             => 7
      }.getOrElse(7)
      val bodyTerm = bodyClause.values.head
      var lastResult: Value = Value.UnitV
      for _ <- 1 to warmup do
        lastResult = Computation.run(eval(bodyTerm, env, interp))
      val times = new Array[Long](reps)
      for i <- 0 until reps do
        val t0 = System.nanoTime()
        lastResult = Computation.run(eval(bodyTerm, env, interp))
        times(i) = (System.nanoTime() - t0) / 1_000_000L
      java.util.Arrays.sort(times)
      val p50 = times(reps / 2)
      val min = times(0)
      val max = times(reps - 1)
      interp.out.println(f"[bench] $label%-30s  p50=${p50}ms  min=${min}ms  max=${max}ms  ($reps reps)")
      Pure(lastResult)

    // `receive { case … }` — special form so we can pull interp.out the AST
    // cases at dispatch time.  Stashes (cases, env) and emits a Perform
    // whose payload is the integer token into that side table.
    case Term.Apply.After_4_6_0(Term.Name("receive"), pfArgClause)
        if pfArgClause.values.size == 1 =>
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val id = interp.receiveSpecNext; interp.receiveSpecNext += 1
          interp.receiveSpecs(id) = (pf.cases, env)
          Perform("Actor", "receive", Value.intV(id) :: Nil)
        case _ =>
          interp.located("receive expects a partial function { case msg => ... }")

    // `receive(timeout = N) { case … }` — same dispatch, but the
    // driver also tracks a deadline.  On timeout the receive value
    // is None; on match it's Some(body-value).
    case Term.Apply.After_4_6_0(
            Term.Apply.After_4_6_0(Term.Name("receive"), timeoutClause),
            pfArgClause)
        if pfArgClause.values.size == 1 && timeoutClause.values.size == 1 =>
      val timeoutTerm = timeoutClause.values.head match
        case Term.Assign(Term.Name("timeout"), v) => v
        case other: Term                          => other
      val timeoutMs = Computation.run(eval(timeoutTerm, env, interp)) match
        case Value.IntV(n) => n
        case _ => throw InterpretError("receive timeout must be an Int (milliseconds)")
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val id = interp.receiveSpecNext; interp.receiveSpecNext += 1
          interp.receiveSpecs(id) = (pf.cases, env)
          Perform("Actor", "receive_t", Value.intV(id) :: Value.intV(timeoutMs) :: Nil)
        case _ =>
          interp.located("receive expects a partial function { case msg => ... }")

    // Special forms: `computed { ... }` / `effect { ... }` — by-name
    // bodies wrapped as zero-arg closures so the reactive machinery can
    // re-run them when dependencies change.  Without the special form
    // the block would be evaluated eagerly at call time and the runtime
    // would get a plain value instead of a thunk.
    case Term.Apply.After_4_6_0(Term.Name("computed"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val body  = bodyArgClause.values.head.asInstanceOf[Term]
      val thunk = Value.FunV(Nil, body, env, "")
      Pure(SignalRuntime.makeComputed(interp, thunk))
    case Term.Apply.After_4_6_0(Term.Name("effect"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val body  = bodyArgClause.values.head.asInstanceOf[Term]
      val thunk = Value.FunV(Nil, body, env, "")
      SignalRuntime.makeEffect(interp, thunk)
      Computation.PureUnit

    // ── v1.16 restartable { handlers } { body } ───────────────────────────
    // Common Lisp condition-system style handler: the body runs in a virtual
    // thread; when `throw e` fires inside it, the error is handed to the
    // handler partial function which returns a `Restart` decision:
    //   Restart.resume(v)   — body continues with `v` as the throw-expression result
    //   Restart.useDefault  — body continues with Value.UnitV (null/unit)
    //   Restart.rethrow     — exception propagates normally from interp restartable frame
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("restartable"), pfArgClause),
        bodyArgClause)
        if pfArgClause.values.size == 1 && bodyArgClause.values.size == 1 =>
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          EffectsRuntime.evalRestartable(bodyArgClause.values.head.asInstanceOf[Term], pf.cases, env, interp)
        case _ =>
          interp.located("restartable expects a partial function { case Error(…) => Restart.… }")

    // Generic plugin-contributed block-form WITH config args: `keyword(config…) { body }`
    // (e.g. `runRandomSeeded(seed) { … }`). Placed AFTER all hardcoded curried special-forms
    // (runClockAt / runEnvWith / httpClient / …) so it only catches genuinely-unmatched applies.
    // The leading clause carries handler-config args (→ `BlockForm.newHandler`); the trailing
    // single-element clause is the body. Fires only for an already-registered block-form. §2d.
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name(kw), cfgClause), bodyClause)
        if bodyClause.values.size == 1 && interp.blockForms.contains(kw) =>
      dispatchBlockForm(interp.blockForms(kw), cfgClause.values, bodyClause.values.head, env, interp)
    // Lazy-load mirror for the curried form (the curried shape can't reach the evalPlainApply
    // hook, whose fast path only fires for a `Term.Name` head). An unresolved curried head may be
    // a config-args block-form a ServiceLoader plugin hasn't registered yet; same gate as the
    // single-clause hook (unresolved head + not-yet-loaded → one-time scan, then re-check).
    case t @ Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name(kw), cfgClause), bodyClause)
        if bodyClause.values.size == 1 && !env.contains(kw) && !interp.globals.contains(kw)
           && !interp._pluginsLoaded =>
      interp.ensurePluginsLoaded()
      interp.blockForms.get(kw) match
        case Some(bf) => dispatchBlockForm(bf, cfgClause.values, bodyClause.values.head, env, interp)
        case None     => evalApplyGeneral(t, env, interp)

    // Function application: detect obj.method(args) and dispatch directly.
    // All sub-terms are evaluated eagerly here; the FlatMap chain composes
    // already-built Computations so placeholderIdx and other eval-time state
    // is observed correctly.
    case app: Term.Apply =>
      evalApplyGeneral(app, env, interp)

    // Field / method selection: a.b  (no-arg call). Type-test form, not the
    // `Term.Select(qual, sn)` extractor: `Term.Select.unapply` allocates a
    // Tuple2 + Some on every no-arg field access (JFR on typeclass-fold:
    // `summon[…].empty` / `.combine` hit this twice per `combineAll`). `.name`
    // is already a `Term.Name`, and `.qual` a `Term`, so no extraction needed.
    case sel: Term.Select =>
      val sn = sel.name
      // '.!' outside a direct block (or inside a lambda/block in a direct
      // block) is an error — it is only valid directly inside a direct[M] body.
      if sn.value == "!" then
        interp.located("'.!' can only appear in expression position directly inside a direct[M] block body; not inside lambdas or nested blocks")
      else
        val method = sn.value
        eval(sel.qual, env, interp) match
          case Pure(qualV) => DispatchRuntime.dispatch(qualV, method, Nil, env, interp)
          case qualC       => FlatMap(qualC, qualV => DispatchRuntime.dispatch(qualV, method, Nil, env, interp))

    // Fast path: s"${expr}" or s"prefix${expr}suffix" — 1-arg s-interpolation.
    // Avoids: 2 List allocations (cast map + evalArgs map), threadValues,
    // StringBuilder, for loop. Covers the dominant s"..." pattern.
    case Term.Interpolate(Term.Name("s"), List(p0: Lit.String, p1: Lit.String), List(arg: Term)) =>
      val pre = p0.value; val suf = p1.value
      eval(arg, env, interp) match
        case Pure(v) => Pure(Value.StringV(pre + Value.show(v) + suf))
        case argC    => FlatMap(argC, v => Pure(Value.StringV(pre + Value.show(v) + suf)))

    // Fast path: 2-arg s"${e1}mid${e2}" or similar.
    case Term.Interpolate(Term.Name("s"), List(p0: Lit.String, p1: Lit.String, p2: Lit.String), List(a1: Term, a2: Term)) =>
      val pre = p0.value; val mid = p1.value; val suf = p2.value
      val c1 = eval(a1, env, interp); val c2 = eval(a2, env, interp)
      c1 match
        case Pure(v1) =>
          c2 match
            case Pure(v2) => Pure(Value.StringV(pre + Value.show(v1) + mid + Value.show(v2) + suf))
            case _        => FlatMap(c2, v2 => Pure(Value.StringV(pre + Value.show(v1) + mid + Value.show(v2) + suf)))
        case _ =>
          c2 match
            case Pure(v2) => FlatMap(c1, v1 => Pure(Value.StringV(pre + Value.show(v1) + mid + Value.show(v2) + suf)))
            case _        => FlatMap(c1, v1 => FlatMap(c2, v2 => Pure(Value.StringV(pre + Value.show(v1) + mid + Value.show(v2) + suf))))

    // String interpolation s"..." / f"..." / md"..." / html"..." / css"..."
    case Term.Interpolate(Term.Name(prefix), parts, args)
        if prefix == "s" || prefix == "f" || prefix == "md"
        || prefix == "html" || prefix == "css" =>
      evalArgs(args.map(_.asInstanceOf[Term]), env, interp) { argVs =>
        val sb = StringBuilder()
        // f"..." semantics: the literal part following each ${} can begin
        // with a Java/printf-style format spec applied to the preceding arg.
        //   `f"${pi}%.2f"` →  parts = ["", "%.2f"], spec consumed off parts(1)
        val fmtRe = "^%[-+# 0,(]*\\d*(?:\\.\\d+)?[bBhHsScCdoxXeEfgGaAtT%]".r
        for i <- parts.indices do
          val partStr = parts(i).asInstanceOf[Lit.String].value
          // The first part precedes any interpolation; never consumes a spec.
          val (consumedSpec, partRest) =
            if i > 0 && prefix == "f" then
              fmtRe.findFirstIn(partStr) match
                case Some(spec) => (Some(spec), partStr.substring(spec.length))
                case None       => (None, partStr)
            else (None, partStr)
          // Emit the interpolated value for index (i - 1) before interp part's text.
          if i > 0 then
            val v = argVs(i - 1)
            val rendered = consumedSpec match
              case Some(spec) =>
                val boxed: AnyRef = v match
                  case Value.IntV(n)    => java.lang.Long.valueOf(n)
                  case Value.DoubleV(d) => java.lang.Double.valueOf(d)
                  case Value.BoolV(b)   => java.lang.Boolean.valueOf(b)
                  case Value.CharV(c)   => java.lang.Character.valueOf(c)
                  case Value.StringV(s) => s
                  case other            => Value.show(other)
                try String.format(spec, boxed)
                catch case _: java.util.IllegalFormatException => Value.show(v)
              case None =>
                Value.show(v)
            sb ++= (if prefix == "html" then interp.htmlEscapeUnlessRaw(v, rendered) else rendered)
          sb ++= partRest
        val raw = sb.toString
        Pure(Value.StringV(if prefix == "md" then interp.stripIndent(raw) else raw))
      }

    // User-defined interpolator — build StringContext instance and call prefix fn.
    // Vararg `args: Any*` is packed into a single ListV so the body sees it as
    // an indexed list (args(0), args.length, etc.).
    case Term.Interpolate(Term.Name(prefix), parts, args) =>
      evalArgs(args.map(_.asInstanceOf[Term]), env, interp) { argVs =>
        val partVals = parts.map(p => Value.StringV(p.asInstanceOf[Lit.String].value))
        val sc = Value.InstanceV("StringContext", new IMap.Map1("parts", Value.ListV(partVals)))
        val scExts = interp.extensions.getOrElse("StringContext", null)
        val extFn = if scExts != null then scExts.getOrElse(prefix, null) else null
        val fn: Value = if extFn != null then extFn
          else
            val fv = env.getOrElse(prefix, interp.globals.getOrElse(prefix, null))
            if fv != null then fv else interp.located(s"Unknown interpolator '$prefix': not in scope")
        interp.callValue2(fn, sc, Value.ListV(argVs), env)
      }

    // Anonymous function with _ placeholders: _.field, _ + 1, _ + _, etc.
    case t: Term.AnonymousFunction =>
      Pure(Value.NativeFnV("anon", args => {
        val saved = interp._phIdxTL.get()
        interp._phIdxTL.set(0)
        val phEnv = env ++ args.zipWithIndex.map { (v, i) => s"_$$${i}" -> v }
        try eval(t.body, phEnv, interp)
        finally interp._phIdxTL.set(saved)
      }))

    // _ placeholder — numbered left-to-right via mutable counter
    case _: Term.Placeholder =>
      val i = interp._phIdxTL.get()
      interp._phIdxTL.set(interp._phIdxTL.get() + 1)
      Pure(env.getOrElse(s"_$$${i}", interp.located("Unexpected _")))

    // Lambda  x => body  or  (x, y) => body
    // Type test (not the After_4_6_0 extractor) to avoid a per-eval Some+Tuple2;
    // this case is hit once per foreach/map call in a hot loop.
    case t: Term.Function =>
      // Fast path: a previously-seen lambda node whose closure was empty yields an
      // invariant FunV — return the cached Pure(FunV) without walking the env.
      val cachedFun = interp.emptyClosureFunCache.get(t)
      if cachedFun != null then return cachedFun
      val paramClause = t.paramClause
      val body        = t.body
      // Drop only the keys whose env value still matches the live interp.globals —
      // those are top-level bindings that the lambda should re-read from
      // `interp.globals` at call time (so a `var` reassigned later is visible).
      // Genuine closure captures (outer def params, block-local vals) have
      // a different value in env than in interp.globals and must be kept; otherwise
      // a param like `a` for `def adder(a)` is stripped because interp.globals also
      // hold the `<a>` HTML tag under that name, and the inner `b => a + b`
      // would resolve `a` to the tag instead of the captured Int.
      // Capture ONLY the names the body could reference (free-var-limited closure,
      // effect-cps-continuation env slice), instead of iterating the whole env — a
      // lambda created in a large env (a multi-shot handler's `opt => resume(opt)`
      // re-evaluated per perform, env holding all accumulated continuation vars) used
      // to `foreachEntry` the entire env. `collectBodyNames` is a SOUND over-approx of
      // the free vars (cached by AST identity), so no needed binding is dropped; a name
      // not in the env is skipped, a same-as-globals name is left to re-read live at call
      // time (so a later-reassigned `var` is visible), exactly as before.
      val closure: Map[String, Value] =
        if env eq interp.globals then Map.empty
        else
          var names = interp.lambdaFreeNamesCache.get(t)
          if names == null then
            names = collectBodyNames(body)
            interp.lambdaFreeNamesCache.put(t, names)
          if names.length == 0 then Map.empty
          else
            var b: scala.collection.mutable.HashMap[String, Value] = null
            var i = 0
            while i < names.length do
              val k = names(i)
              val v = env.getOrElse(k, null)
              // Drop k only when it resolves to the *identical* global binding (a true
              // top-level reference, re-read live at call time). Use reference identity
              // (`ne`), NOT value inequality: `Value` has structural equality, so a genuine
              // frame-local whose value merely *equals* a same-named global (e.g. a
              // per-request value coinciding with a global) was misclassified as the global
              // and dropped — then re-read live at call time, leaking a later/other context's
              // value (cross-tenant state leak). A distinct binding object is always a real
              // capture; only the literal global object is re-read live.
              // Capture k when it is NOT the identical live global (`ne` — a genuine
              // frame-local; see the cross-tenant-leak note above), OR when it is a
              // stable top-level `val` (in `valNames`). The "drop eq-global, re-read
              // live at call time" shortcut is only sound for *reassignable vars*
              // (whose later reassignment must stay visible). A `val` never changes,
              // so capturing it is value-identical AND robust when the lambda is
              // invoked under a *different* interpreter than the one that created it
              // — e.g. a `computedSignal(() => … moduleVal …)` thunk in an imported
              // library module, evaluated by the frontend runtime's interpreter,
              // whose globals do not hold that module's vals (they were re-read-live
              // before this and came back Undefined). Vars stay re-read-live.
              if v != null && ((interp.globals.getOrElse(k, null) ne v) || interp.valNames.contains(k)) then
                if b == null then b = new scala.collection.mutable.HashMap[String, Value]
                b(k) = v
              i += 1
            if b == null then Map.empty else b.toMap
      // Extract and cache lambda parameter metadata once per AST node. Typed
      // handler mounting reads paramTypes, with empty string for unannotated params.
      var paramInfo = interp.paramInfoCache.get(paramClause)
      if paramInfo == null then
        paramInfo = (
          paramClause.values.map(_.name.value),
          paramClause.values.map(p => p.decltpe.fold("")(interp.typeToString))
        )
        interp.paramInfoCache.put(paramClause, paramInfo)
      val (paramNames, paramTypes) = paramInfo
      val result = Pure(Value.FunV(paramNames, body, closure, paramTypes = paramTypes))
      if closure.isEmpty then interp.emptyClosureFunCache.put(t, result)
      result

    // Partial function  { case pat => body; ... }  — e.g. xs.map { case (k, v) => ... }
    // Compiled and cached per AST node so matchPat/Option allocations happen once, not per call.
    case t: Term.PartialFunction =>
      var compiled = interp.pfCache.get(t)
      if compiled == null then
        compiled = PatternRuntime.compilePF(t, interp)
        interp.pfCache.put(t, compiled)
      val compiledPF = compiled
      Pure(Value.NativeFnV("partial", args => {
        val arg = args match
          case List(v) => v
          case vs      => Value.TupleV(vs)
        compiledPF.run(arg, env, interp)
      }))

    // Tuple  (a, b, ...)
    // Fast paths for 2- and 3-tuples avoid the intermediate List[Computation]
    // allocation that evalArgs creates for all-Pure cases.
    // Nested match instead of (c1, c2) match to avoid Tuple2/Tuple3 allocation.
    case Term.Tuple(List(e1: Term, e2: Term)) =>
      val c1 = eval(e1, env, interp); val c2 = eval(e2, env, interp)
      c1 match
        case Pure(v1) =>
          c2 match
            case Pure(v2) => Pure(Value.TupleV(v1 :: v2 :: Nil))
            case _        => FlatMap(c2, v2 => Pure(Value.TupleV(v1 :: v2 :: Nil)))
        case _ =>
          c2 match
            case Pure(v2) => FlatMap(c1, v1 => Pure(Value.TupleV(v1 :: v2 :: Nil)))
            case _        => FlatMap(c1, v1 => FlatMap(c2, v2 => Pure(Value.TupleV(v1 :: v2 :: Nil))))
    case Term.Tuple(List(e1: Term, e2: Term, e3: Term)) =>
      val c1 = eval(e1, env, interp); val c2 = eval(e2, env, interp); val c3 = eval(e3, env, interp)
      c1 match
        case Pure(v1) =>
          c2 match
            case Pure(v2) =>
              c3 match
                case Pure(v3) => Pure(Value.TupleV(v1 :: v2 :: v3 :: Nil))
                case _        => FlatMap(c3, v3 => Pure(Value.TupleV(v1 :: v2 :: v3 :: Nil)))
            case _ =>
              c3 match
                case Pure(v3) => FlatMap(c2, v2 => Pure(Value.TupleV(v1 :: v2 :: v3 :: Nil)))
                case _        => FlatMap(c2, v2 => FlatMap(c3, v3 => Pure(Value.TupleV(v1 :: v2 :: v3 :: Nil))))
        case _ =>
          c2 match
            case Pure(v2) =>
              c3 match
                case Pure(v3) => FlatMap(c1, v1 => Pure(Value.TupleV(v1 :: v2 :: v3 :: Nil)))
                case _        => FlatMap(c1, v1 => FlatMap(c3, v3 => Pure(Value.TupleV(v1 :: v2 :: v3 :: Nil))))
            case _ =>
              c3 match
                case Pure(v3) => FlatMap(c1, v1 => FlatMap(c2, v2 => Pure(Value.TupleV(v1 :: v2 :: v3 :: Nil))))
                case _        => FlatMap(c1, v1 => FlatMap(c2, v2 => FlatMap(c3, v3 => Pure(Value.TupleV(v1 :: v2 :: v3 :: Nil)))))
    case Term.Tuple(elems) =>
      evalArgs(elems, env, interp)(vs => Pure(Value.TupleV(vs)))

    // new ClassName(args)
    case Term.New(Init.After_4_6_0(tpe, _, argClauses)) =>
      val typeName = tpe match { case Type.Name(n) => n; case _ => "?" }
      val argTerms = argClauses.toList.flatMap(_.values)
      evalArgs(argTerms, env, interp) { argVals =>
        env.getOrElse(typeName, interp.globals.getOrElse(typeName,
          interp.located(s"Unknown constructor: $typeName"))) match
            case c: Value.NativeFnV => c.f(argVals)
            case f: Value.FunV      => interp.callFun(f, argVals)
            case _ => interp.located(s"$typeName is not a constructor")
      }

    // for x <- xs yield f(x)
    case t: Term.ForYield =>
      PatternRuntime.evalForYield(t.enumsBlock.enums, t.body, env, interp)

    // for x <- xs do body
    //
    // A loop-body `outerVar = …` assignment goes through `Term.Assign` →
    // `interp.globals(outerVar)`, leaving the caller's local frame stale (the same
    // quirk the `Term.While` case below compensates for). evalForDo never synced
    // those globals back, so a for-do that mutates an enclosing `var` inside a
    // function silently returned the pre-loop value. Mirror the while-loop's
    // `syncCallerEnv`: snapshot the caller MutableEnvView's globals at entry, run
    // the loop, then push any changed global back into the local frame.
    case t: Term.For =>
      // `afterIter` reflects body assignments (which land in `interp.globals`) back
      // into the caller's mutable local view AFTER EACH iteration, so the next
      // iteration — and the code after the loop — reads the updated enclosing `var`.
      // Only a global that CHANGED since loop entry is pulled in, so a pre-existing
      // global is never allowed to clobber a same-named shadowing local.
      //
      // The mutable view is found by walking the FrameMap parent chain, not just by
      // matching `env` directly: a *nested* for-do is evaluated with `env` =
      // `FrameMap(loopVar, … → callerMev)`, so the enclosing function's mutable frame
      // sits behind one or more FrameMap links. Without the walk, the inner loop's
      // increments would all read the same stale local (e.g. nested `c = c+1` capped
      // at the inner trip count instead of the product).
      def findMev(e: Map[String, Value]): MutableEnvView | Null = e match
        case mev: MutableEnvView => mev
        case fm: FrameMap        => findMev(fm.parent)
        case _                   => null
      val afterIter: () => Unit = findMev(env) match
        case null => () => ()
        case mev =>
          val keys   = mev.underlying.keysIterator.toArray
          val entryG = keys.map(k => interp.globals.getOrElse(k, null))
          () => {
            var i = 0
            while i < keys.length do
              val gv = interp.globals.getOrElse(keys(i), null)
              if gv != null && (gv.asInstanceOf[AnyRef] ne entryG(i).asInstanceOf[AnyRef]) then
                mev.underlying(keys(i)) = gv
              i += 1
          }
      PatternRuntime.evalForDo(t.enumsBlock.enums, t.body, env, Map.empty, interp, afterIter)
        .flatMap(Computation.discardToUnit)

    // while cond do body  — refresh env from interp.globals each iteration so mutations are visible.
    // Snapshot interp.globals at loop entry: only update a key on subsequent iterations if its
    // interp.globals value CHANGED since entry (i.e., was written by Term.Assign).  This prevents
    // a pre-existing interp.globals entry (e.g. the HTML `<a>` tag) from clobbering a local
    // `var a = 0` that happens to shadow it.
    //
    // Hot path: instead of creating a new Map each iteration, maintain a mutable frame
    // that is updated in-place and exposed via MutableEnvView.  O(K) lookup per iteration
    // where K = keys that changed in globals (typically 0 or 1); no Map allocation.
    //
    // Frame size optimization: only copy env entries that are absent from (or differ from)
    // interp.globals.  These are the locally-declared vars that the loop can mutate.
    // All other entries (builtins, global defs) are already in interp.globals and
    // EvalRuntime.Term.Name's globals fallback makes them visible without copying.
    // This shrinks frame from O(N_globals) to O(N_local_vars) — typically 2-5 entries.
    case t: Term.While =>
      val frame: scala.collection.mutable.HashMap[String, Value] = env match
        case fm: FrameMap =>
          val b = scala.collection.mutable.HashMap.empty[String, Value]
          var cur: Map[String, Value] = fm
          while cur.isInstanceOf[FrameMap] do
            val fm2 = cur.asInstanceOf[FrameMap]
            fm2.appendLocalTo(b, interp.globals)
            cur = fm2.parent
          if cur ne interp.globals then
            cur.foreachEntry { (k, v) =>
              if !b.contains(k) && interp.globals.getOrElse(k, null) != v then b(k) = v
            }
          b
        case _ =>
          val b2 = scala.collection.mutable.HashMap.empty[String, Value]
          env.foreachEntry { (k, v) => if interp.globals.getOrElse(k, null) != v then b2(k) = v }
          b2
      // Top-level loops already run in the interpreter globals map.  If the
      // compacted frame is empty and the incoming env is just a globals view,
      // execute the loop body directly against globals instead of creating a
      // side frame that must be refreshed every iteration.
      val useGlobalsFrame = frame.isEmpty && (env match
        case mev: MutableEnvView => mev.underlying.asInstanceOf[AnyRef] eq interp.globals.asInstanceOf[AnyRef]
        case _                   => false
      )
      // Snapshot the GLOBALS value of each frame key at loop entry.
      // refreshFn fires when a global was reassigned after loop start — so we compare
      // current globals vs. globals-at-entry, not globals vs. local-at-entry.
      val entrySnap: scala.collection.mutable.HashMap[String, Value] = {
        val s = scala.collection.mutable.HashMap.empty[String, Value]
        if !useGlobalsFrame then frame.foreachEntry { (k, _) => s(k) = interp.globals.getOrElse(k, null) }
        s
      }
      // Snapshot globals for every key in the caller's mutable env (BlockRuntime's
      // local map) so we can push updates back after the loop.  Needed because
      // Defn.Var (after the dual-write fix) initialises both local AND globals to
      // the same value — so the var never enters `frame` — but Term.Assign inside
      // the loop only updates globals, leaving local stale.
      // We skip keys that ARE in frame (those are params/closure captures handled by
      // refreshFn) to avoid clobbering a shadowed parameter with a same-named global.
      val callerEnvSnap: scala.collection.mutable.HashMap[String, Value] | Null =
        if useGlobalsFrame then null
        else env match
          case mev: MutableEnvView =>
            val s = new scala.collection.mutable.HashMap[String, Value]
            mev.underlying.foreachEntry { (k, _) =>
              if !frame.contains(k) then s(k) = interp.globals.getOrElse(k, null)
            }
            s
          case _ => null
      def syncCallerEnv(): Unit =
        if callerEnvSnap != null then
          val mev = env.asInstanceOf[MutableEnvView]
          callerEnvSnap.foreachEntry { (k, snap) =>
            val gv = interp.globals.getOrElse(k, null)
            if (snap.asInstanceOf[AnyRef] ne gv.asInstanceOf[AnyRef]) && gv != null then
              mev.underlying(k) = gv
          }
      val frameView = new MutableEnvView(if useGlobalsFrame then interp.globals else frame)
      val fastLoop = tryFastWhileAssign(t, frameView, interp)
      if fastLoop != null then
        syncCallerEnv()
        fastLoop
      else
        // Hoist per-iteration closures: allocated once per while-entry, reused across all iterations.
        val refreshFn: (String, Value) => Unit = (k, _) => {
          val gv = interp.globals.getOrElse(k, null)
          if gv != null && entrySnap.getOrElse(k, null) != gv then frame(k) = gv
        }
        lazy val loopCont: Value => Computation = _ => loop
        lazy val condCont: Value => Computation = {
          case Value.BoolV(true) => FlatMap(eval(t.body, frameView, interp), loopCont)
          case _ =>
            syncCallerEnv()
            Computation.PureUnit
        }
        def loop: Computation =
          // Refresh mutable frame: only overwrite a key if globals changed since entry.
          if !useGlobalsFrame && frame.nonEmpty then frame.foreachEntry(refreshFn)
          // All-pure fast path: if both the condition and body are pure on the first
          // iteration, run subsequent iterations in a plain JVM while loop — zero
          // FlatMap allocations for tight loops (saves 2 allocs × N iterations).
          // Falls back to the trampoline path on the first effectful step.
          eval(t.expr, frameView, interp) match
            case Pure(Value.BoolV(true)) =>
              eval(t.body, frameView, interp) match
                case Pure(_) =>
                  var running = true
                  while running do
                    if !useGlobalsFrame && frame.nonEmpty then frame.foreachEntry(refreshFn)
                    eval(t.expr, frameView, interp) match
                      case Pure(Value.BoolV(true)) =>
                        eval(t.body, frameView, interp) match
                          case Pure(_)  => ()
                          case bodyComp => return FlatMap(bodyComp, loopCont)
                      case Pure(_) => running = false
                      case condComp => return FlatMap(condComp, condCont)
                  syncCallerEnv()
                  Computation.PureUnit
                case bodyComp => FlatMap(bodyComp, loopCont)
            case Pure(_) =>
              syncCallerEnv()
              Computation.PureUnit
            case condComp  => FlatMap(condComp, condCont)
        loop

    // return expr  (non-local via exception)
    case Term.Return(expr) =>
      eval(expr, env, interp).flatMap(v => throw ReturnSignal(v))

    // var/field assignment
    case Term.Assign(Term.Name(name), rhs) =>
      eval(rhs, env, interp) match
        case Pure(v) => interp.globals(name) = v; Computation.PureUnit
        case c       => FlatMap(c, { v => interp.globals(name) = v; Computation.PureUnit })

    // `recv(idx…) = rhs` desugars to `recv.update(idx…, rhs)` (Scala). Mutates a real `ArrayV` in place
    // (and works for any receiver exposing `update`). Eval order: recv, indices (l→r), rhs. (collection-real-type.)
    case Term.Assign(app: Term.Apply, rhs) =>
      eval(app.fun, env, interp).flatMap { recvV =>
        def evalIdx(remaining: List[Term], acc: List[Value]): Computation = remaining match
          case Nil    => eval(rhs, env, interp).flatMap { v =>
            DispatchRuntime.dispatch(recvV, "update", acc.reverse ::: List(v), env, interp)
          }
          case h :: t => eval(h, env, interp).flatMap { iv => evalIdx(t, iv :: acc) }
        evalIdx(app.argClause.values, Nil)
      }

    // summon[TC[T]] — retrieve a given instance from the table
    case t: Term.ApplyType =>
      (t.fun, t.argClause.values) match
        case (Term.Name("summon"), List(typeArg)) =>
          // Derived lookup strings are pure functions of the (immutable) type
          // argument AST, so compute the key + synthetic-param name once per
          // node and cache them; only the env/global lookups below are per-call.
          var derived = interp.summonKeyCache.get(t)
          if derived == null then
            val k     = interp.typeToString(typeArg.asInstanceOf[scala.meta.Type])
            val tcEnd = k.indexOf('[')
            val synth =
              if tcEnd > 0 then
                val tc         = k.substring(0, tcEnd)
                val typeArgStr = k.substring(tcEnd + 1, k.length - 1).trim
                s"${typeArgStr}$$${tc}"
              else null
            derived = Array(k, synth)
            interp.summonKeyCache.put(t, derived)
          val key   = derived(0)
          val synth = derived(1)
          // 1. Direct lookup in env / interp.globals
          val direct = env.getOrElse(key, interp.globals.getOrElse(key, null))
          val found: Value | Null =
            if direct != null then direct
            else
              // 2. For generic keys like "Monoid[A]" with a context-bound A,
              //    `A$Monoid` is in env as a synthetic using param.  Try this
              //    single hash lookup BEFORE the more expensive resolveGiven
              //    (which scans the given table and tries to concretize type
              //    vars).  Hot path for `[A: TC]` workloads: typeclass-fold
              //    fires summon[Monoid[A]] twice per foldLeft step.
              val viaTcSyntheticParam: Value | Null =
                if synth != null then env.getOrElse(synth, null)
                else null
              if viaTcSyntheticParam != null then viaTcSyntheticParam
              else
                // 3. Fallback: resolveGiven (handles cases where the key has
                //    type vars that need concretization from regular arg types,
                //    or anonymous given instances with no synthetic param).
                GivenRuntime.resolveGiven(key, Nil, env, interp).orNull
          if found != null then Pure(found)
          else Pure(interp.located(s"No given instance for '$key'"))

        // Prism[Outer, Variant] — focus on a single sum-type variant.
        case (Term.Name("Prism"), List(_, variantType)) =>
          val variantName = variantType match
            case n: Type.Name => n.value
            case _            => interp.located("Prism[Outer, Variant]: Variant must be a simple type name")
          Pure(OpticsRuntime.buildPrism(variantName, interp))

        // compiletime.constValue[T] — return the compile-time constant value of type T
        case (Term.Select(Term.Name("compiletime"), Term.Name("constValue")), List(typeArg)) =>
          Pure(CallRuntime.constValueOfType(typeArg.asInstanceOf[scala.meta.Type]))

        // compiletime.summonInline[TC[T]] — look up a given instance
        case (Term.Select(Term.Name("compiletime"), Term.Name("summonInline")), List(typeArg)) =>
          val key = interp.typeToString(typeArg.asInstanceOf[scala.meta.Type])
          val fv = env.getOrElse(key, interp.globals.getOrElse(key, null))
          val found: Value | Null =
            if fv != null then fv
            else GivenRuntime.resolveGiven(key, Nil, env, interp).orNull
          if found != null then Pure(found)
          else Pure(interp.located(s"No given instance for '$key' (summonInline)"))

        // Mirror.of[T] — runtime view of the same product/sum metadata used by derives.
        case (Term.Select(Term.Name("Mirror"), Term.Name("of")), List(typeArg)) =>
          val typeName = interp.typeToString(typeArg.asInstanceOf[scala.meta.Type])
          Pure(DerivesRuntime.mirrorForType(typeName, interp))

        case _ => eval(t.fun, env, interp)  // other type applications — erase type args

    // Prefix unary operators: `!x`, `-x`, `+x`, `~x`.
    case t: Term.ApplyUnary =>
      val op = t.op.value
      @inline def applyUnary(v: Value): Computation = (op, v) match
        case ("!", Value.BoolV(b))   => Computation.pureBool(!b)
        case ("!", Value.IntV(n))    => Computation.pureBool(n == 0)
        case ("-", Value.IntV(n))    => Computation.pureIntV(-n)
        case ("-", Value.DoubleV(d)) => Pure(Value.doubleV(-d))
        case ("+", n: Value.IntV)    => Pure(n)
        case ("+", d: Value.DoubleV) => Pure(d)
        case ("~", Value.IntV(n))    => Computation.pureIntV(~n)
        case (_, other)              => interp.located(s"Cannot apply unary $op to ${Value.show(other)}")
      eval(t.arg, env, interp) match
        case Pure(v) => applyUnary(v)
        case c       => FlatMap(c, applyUnary)

    case t: Term.Throw =>
      eval(t.expr, env, interp).flatMap { v =>
        if interp._insideDirectBlock.get() then Pure(Value.InstanceV("Left", new IMap.Map1("value", v)))
        else
          // v1.16: check for an active restartable frame on interp thread.
          // If present, suspend the body and let the handler decide.
          val rsStack = interp.restartableStack()
          val rsHandle = rsStack.peekFirst()
          if rsHandle != null then
            rsHandle.errorQ.put(v)   // send error to handler thread
            rsHandle.resumeQ.take() match  // wait for handler's decision
              case Right(resumeVal) => Pure(resumeVal)   // resume with replacement
              case Left(rethrowVal) =>
                // Throw RestartableRethrow (not ScriptException) so the
                // body thread's catch wrapper knows interp is a terminal rethrow
                // and doesn't route it back through the handler again.
                throw RestartableRethrow(rethrowVal)
          else
            val isNoTrace = v match
              case Value.InstanceV(typeName, _) => interp.noTraceTypes.contains(typeName)
              case _ => false
            if isNoTrace then throw ScriptExceptionNoTrace(v)
            else throw ScriptException(v)
      }

    case t: Term.TryWithHandler =>
      def handleException(thrownVal: Value): Value =
        val handler = Computation.run(eval(t.catchp, env, interp))
        Computation.run(interp.callValue1(handler, thrownVal, env))
      val tryResult: Value =
        try Computation.run(eval(t.expr, env, interp))
        catch
          case rr: RestartableRethrow => throw rr
          case se: ScriptException =>
            handleException(se.value)
          case th: Throwable =>
            val exTypeName = th.getClass.getSimpleName
            val msg = Option(th.getMessage).getOrElse(exTypeName)
            handleException(Value.InstanceV(exTypeName, new IMap.Map1("message", Value.StringV(msg))))
      t.finallyp match
        case Some(f) => Computation.run(eval(f, env, interp))
        case None    =>
      Pure(tryResult)

    case t: Term.Try =>
      @annotation.nowarn("msg=deprecated")
      def tryCatch(thrownVal: Value, cause: Throwable): Value =
        t.catchp.iterator.flatMap { c =>
          val bound = PatternRuntime.matchPat(c.pat, thrownVal, Map.empty, interp)
          if bound == null then Iterator.empty else Iterator.single((c, bound))
        }.nextOption() match
          case Some((matchedCase, bound)) => Computation.run(eval(matchedCase.body, env ++ bound, interp))
          case None                       => throw cause
      val tryResult: Value =
        try Computation.run(eval(t.expr, env, interp))
        catch
          case rr: RestartableRethrow => throw rr  // v1.16: let terminal rethrows pass through
          case se: ScriptException  => tryCatch(se.value, se)
          case th: Throwable =>
            // Convert any JVM exception (NumberFormatException, InterpretError, etc.)
            // into a ScalaScript InstanceV so catch patterns can match it.
            val exTypeName = th.getClass.getSimpleName
            val msg = Option(th.getMessage).getOrElse(exTypeName)
            tryCatch(Value.InstanceV(exTypeName, new IMap.Map1("message", Value.StringV(msg))), th)
      t.finallyp match
        case Some(f) => Computation.run(eval(f, env, interp))
        case None    =>
      Pure(tryResult)

    case t: Term.Ascribe =>
      eval(t.expr, env, interp)

    case _: Term.This =>
      // `this` inside a class / enum-case / trait method body — bound to the
      // receiver instance by `invokeTypeMethod` when the body references it
      // (busi seq-121). Outside a method, there is no receiver.
      env.getOrElse("this", interp.globals.getOrElse("this", null)) match
        case null => interp.located("`this` is only available inside a method body")
        case v    => Pure(v)

    case other => interp.located(s"Cannot eval: ${other.productPrefix}")

  // ─── Lenses / Optics — see OpticsRuntime.scala ─────────────────────

    /** Evaluate a list of argument terms eagerly to a list of Computations, then
   *  thread their values into `k` via FlatMap chain.
   *
   *  Eager evaluation matters because `eval` mutates `placeholderIdx`; deferring
   *  sub-term evaluation into a FlatMap continuation would observe a wrong index
   *  later. After interp call all sub-Computations are fully built; only the final
   *  composition (the FlatMap chain) is interpreted lazily. */

  /** Detect `(lo to hi).map(f).filter(g).foldLeft(z)(h)` (the common
   *  iterator-chain shape) and evaluate it as a single while loop with
   *  no intermediate List/View allocations.  Returns `null` if the
   *  shape doesn't match — caller falls back to the regular path.
   *
   *  Skips bodies whose closure produces non-Int results so we keep
   *  semantics tight; if any rendering choice fails, we bail and let
   *  the generic path take over. */
  private def tryFusedRangeMapFilterFold(
      app:    Term.Apply,
      env:    Env,
      interp: Interpreter
  ): Computation | Null =
    // Cheap pre-filter: peek into the AST shape `Apply(Apply(Select(_,
    // "foldLeft"), 1arg), 1arg)` before doing any work.  This bails on
    // every non-`foldLeft` call without allocating intermediate pattern
    // values — critical because evalApplyGeneral runs on every Apply node.
    if app.argClause.values.lengthCompare(1) != 0 then return null
    val foldOuterFunRaw = app.fun
    if !foldOuterFunRaw.isInstanceOf[Term.Apply] then return null
    val foldOuterFun = foldOuterFunRaw.asInstanceOf[Term.Apply]
    if foldOuterFun.argClause.values.lengthCompare(1) != 0 then return null
    val foldSelRaw = foldOuterFun.fun
    if !foldSelRaw.isInstanceOf[Term.Select] then return null
    val foldSel = foldSelRaw.asInstanceOf[Term.Select]
    if foldSel.name.value != "foldLeft" then return null
    val hExpr = app.argClause.values.head
    val zExpr = foldOuterFun.argClause.values.head
    val filterChain = foldSel.qual
    // .filter
    val (mapChain, gExpr) = filterChain match
      case fa: Term.Apply if fa.argClause.values.lengthCompare(1) == 0 =>
        fa.fun match
          case Term.Select(q, n) if n.value == "filter" => (q, fa.argClause.values.head)
          case _ => return null
      case _ => return null
    // .map
    val (rangeExpr, fExpr) = mapChain match
      case ma: Term.Apply if ma.argClause.values.lengthCompare(1) == 0 =>
        ma.fun match
          case Term.Select(q, n) if n.value == "map" => (q, ma.argClause.values.head)
          case _ => return null
      case _ => return null
    // (lo to hi) or (lo until hi)
    val (loExpr, hiExpr, inclusive) = rangeExpr match
      case Term.ApplyInfix.After_4_6_0(lhs, Term.Name("to"), _, ac) if ac.values.lengthCompare(1) == 0 =>
        (lhs, ac.values.head, true)
      case Term.ApplyInfix.After_4_6_0(lhs, Term.Name("until"), _, ac) if ac.values.lengthCompare(1) == 0 =>
        (lhs, ac.values.head, false)
      case _ => return null

    // Evaluate lo, hi, z, f, g, h.  All must be Pure.
    val loV = eval(loExpr, env, interp)
    if !loV.isInstanceOf[Computation.Pure] then return null
    val hiV = eval(hiExpr, env, interp)
    if !hiV.isInstanceOf[Computation.Pure] then return null
    val zV  = eval(zExpr, env, interp)
    if !zV.isInstanceOf[Computation.Pure] then return null
    val fV  = eval(fExpr, env, interp)
    if !fV.isInstanceOf[Computation.Pure] then return null
    val gV  = eval(gExpr, env, interp)
    if !gV.isInstanceOf[Computation.Pure] then return null
    val hV  = eval(hExpr, env, interp)
    if !hV.isInstanceOf[Computation.Pure] then return null

    val loN = loV.asInstanceOf[Computation.Pure].value match
      case Value.IntV(n) => n
      case _ => return null
    val hiN = hiV.asInstanceOf[Computation.Pure].value match
      case Value.IntV(n) => n
      case _ => return null
    val zN  = zV.asInstanceOf[Computation.Pure].value match
      case Value.IntV(n) => n
      case _ => return null
    val fFun = fV.asInstanceOf[Computation.Pure].value
    val gFun = gV.asInstanceOf[Computation.Pure].value
    val hFun = hV.asInstanceOf[Computation.Pure].value

    // Fused loop.
    var acc = zN
    var i = loN
    val limit = if inclusive then hiN else hiN - 1
    while i <= limit do
      val mc = CallRuntime.callValue1(fFun, Value.IntV(i), env, interp)
      val mV = mc match { case Computation.Pure(v) => v; case _ => return null }
      val gC = CallRuntime.callValue1(gFun, mV, env, interp)
      val passed = gC match
        case Computation.Pure(Value.BoolV(true))  => true
        case Computation.Pure(Value.BoolV(false)) => false
        case _ => return null
      if passed then
        val hC = CallRuntime.callValue2(hFun, Value.IntV(acc), mV, env, interp)
        hC match
          case Computation.Pure(Value.IntV(n)) => acc = n
          case _ => return null
      i += 1L
    Computation.Pure(Value.IntV(acc))

  /** General `Term.Apply` dispatch: `.copy`, Focus/direct/Db.query/remoteStub
   *  intrinsics, method calls (`recv.m(args)` via the `Term.Select` arm), and the
   *  bare function-value fallback. Shared by the catch-all `Term.Apply` case and
   *  the hoisted `Term.Select`-head fast path above. */
  private def evalApplyGeneral(app: Term.Apply, env: Env, interp: Interpreter): Computation =
      // ── Universal fused-loop fast-path for `(lo to hi).map(f).filter(g)
      //    .foldLeft(z)(h)` lives in tryFusedRangeMapFilterFold.  Disabled
      //    by default (controlled by SSC_FUSED_RANGE env var) — empirical
      //    bench shows the pattern-check overhead on the hot Term.Apply
      //    path slightly regressed range-sum / list-fold despite the
      //    fusion win on streams-pipeline itself.  The native .map/.filter/
      //    .foldLeft chain hits BytecodeJIT-compiled paths in the
      //    interpreter that beat a Scala-level fused loop for short
      //    ranges.  Re-enable once the fusion is lifted into the
      //    BytecodeJIT pre-pass (see SPRINT ssc-jit-loop-fusion-universal
      //    Stage 2 — IR-level lowering before JIT, not runtime detection).
      if scalascript.interpreter.EvalRuntime._FusedRangeEnabled then
        val fusedRange = tryFusedRangeMapFilterFold(app, env, interp)
        if fusedRange != null then return fusedRange

      app.fun match
        // ── Bench.opaque(x) — fast-path identity, avoids native-dispatch
        // cost (~2.5µs per call → 2.5s on 1M-iter benches). Just evaluate
        // the argument; the Rust target maps Bench.opaque to std::hint::
        // black_box at codegen time, so the anti-folding effect still
        // applies on AOT. On interp, Bench.opaque is a transparent no-op.
        case Term.Select(Term.Name("Bench"), Term.Name("opaque"))
            if app.argClause.values.size == 1 =>
          eval(app.argClause.values.head, env, interp)
        // ── .copy(field = value, ...) on an InstanceV ────────────────
        // Named args arrive as Term.Assign(Term.Name(field), rhs); we have
        // to intercept BEFORE the generic eval path, otherwise Term.Assign
        // would fall into the var-assignment case and mutate interp.globals.
        case sel: Term.Select if sel.name.value == "copy" =>
          OpticsRuntime.evalCopy(sel.qual, app.argClause.values, env, interp)
        // ── Focus[T](_.a.b) / Focus(_.a.b) — Monocle-style lens ──────
        // Inspect the lambda body at AST level to extract a field-access
        // chain, then synthesise a Lens value with get / set / modify /
        // andThen. Done at AST level because the placeholder lambda is
        // otherwise erased to an opaque NativeFnV.
        case ta: Term.ApplyType if OpticsRuntime.isFocusName(ta.fun) =>
          OpticsRuntime.evalFocus(app.argClause.values, interp)
        case Term.Name("Focus") =>
          OpticsRuntime.evalFocus(app.argClause.values, interp)
        // ── direct[M] { stmts } — v1.8 do-notation sugar ─────────────
        case Term.ApplyType.After_4_6_0(Term.Name("direct"), typeArgClause) =>
          val typeArg = typeArgClause.values.headOption.getOrElse(Type.Name("?"))
          DirectTypeUtils.validateDirectTypeArg(typeArg)
          val tag = BlockRuntime.extractDirectMonadTag(typeArgClause.values)
          app.argClause.values match
            case List(block: Term.Block) => BlockRuntime.evalDirectBlock(block.stats, env, tag, interp)
            case List(single: Term)      => eval(single, env, interp)
            case _                       => interp.located("direct[M] expects a single block argument")
        // Db.query[T](...) — the interpreter intrinsic returns row maps;
        // preserve the erased type argument here and project each row map into
        // the registered case-class runtime shape so `ssc run` mirrors JvmGen.
        case Term.ApplyType.After_4_6_0(
              Term.Select(Term.Name("Db"), Term.Name("query")),
              typeArgClause
            ) if typeArgClause.values.nonEmpty =>
          val typeName = interp.typeToString(typeArgClause.values.head.asInstanceOf[scala.meta.Type])
          val qualC = eval(Term.Name("Db"), env, interp)
          val argComps = app.argClause.values.map {
            case Term.Assign(_, rhs) => eval(rhs, env, interp)
            case other               => eval(other, env, interp)
          }
          val argVsQ = extractPureValues(argComps)
          qualC match
            case Pure(qualV) if argVsQ != null =>
              DispatchRuntime.dispatch(qualV, "query", argVsQ, env, interp).map(projectTypedRows(typeName, _, interp))
            case _ =>
              FlatMap(qualC, qualV =>
                interp.threadValues(argComps)(argVals =>
                  DispatchRuntime.dispatch(qualV, "query", argVals, env, interp).map(projectTypedRows(typeName, _, interp))
                )
              )
        case sel: Term.Select =>
          val method   = sel.name.value
          val qualC    = eval(sel.qual, env, interp)
          val argTerms = app.argClause.values
          // Named args (Term.Assign) must evaluate only the RHS; the full
          // Term.Assign path at line 2338 treats them as var-assignments and
          // returns UnitV, destroying the actual value.
          if argTerms.isEmpty then
            qualC match
              case Pure(qualV) => DispatchRuntime.dispatch(qualV, method, Nil, env, interp)
              case _           => FlatMap(qualC, qualV => DispatchRuntime.dispatch(qualV, method, Nil, env, interp))
          else if argTerms.lengthCompare(1) == 0 then
            val arg1C = argTerms.head match
              case Term.Assign(_, rhs) => eval(rhs, env, interp)
              case other               => eval(other, env, interp)
            qualC match
              case Pure(qv) =>
                arg1C match
                  case Pure(av) => DispatchRuntime.dispatch1(qv, method, av, env, interp)
                  case _        => FlatMap(arg1C, av => DispatchRuntime.dispatch1(qv, method, av, env, interp))
              case _ =>
                arg1C match
                  case Pure(av) => FlatMap(qualC, qv => DispatchRuntime.dispatch1(qv, method, av, env, interp))
                  case _        => FlatMap(qualC, qv => FlatMap(arg1C, av => DispatchRuntime.dispatch1(qv, method, av, env, interp)))
          else if argTerms.lengthCompare(2) == 0 then
            // 2-arg fast path: avoids extractPureValues ArrayBuffer + argComps List allocation.
            val arg1C = argTerms.head match
              case Term.Assign(_, rhs) => eval(rhs, env, interp)
              case other               => eval(other, env, interp)
            val arg2C = argTerms(1) match
              case Term.Assign(_, rhs) => eval(rhs, env, interp)
              case other               => eval(other, env, interp)
            qualC match
              case Pure(qv) =>
                arg1C match
                  case Pure(av1) =>
                    arg2C match
                      case Pure(av2) => DispatchRuntime.dispatch2(qv, method, av1, av2, env, interp)
                      case _         => FlatMap(arg2C, av2 => DispatchRuntime.dispatch2(qv, method, av1, av2, env, interp))
                  case _ =>
                    arg2C match
                      case Pure(av2) => FlatMap(arg1C, av1 => DispatchRuntime.dispatch2(qv, method, av1, av2, env, interp))
                      case _         => FlatMap(arg1C, av1 => FlatMap(arg2C, av2 => DispatchRuntime.dispatch2(qv, method, av1, av2, env, interp)))
              case _ =>
                arg1C match
                  case Pure(av1) if arg2C.isInstanceOf[Pure] =>
                    FlatMap(qualC, qv => DispatchRuntime.dispatch2(qv, method, av1, arg2C.asInstanceOf[Pure].value, env, interp))
                  case _ =>
                    FlatMap(qualC, qv =>
                      FlatMap(arg1C, av1 => FlatMap(arg2C, av2 => DispatchRuntime.dispatch2(qv, method, av1, av2, env, interp))))
          else
            val argComps = argTerms.map {
              case Term.Assign(_, rhs) => eval(rhs, env, interp)
              case other               => eval(other, env, interp)
            }
            val argVsS = extractPureValues(argComps)
            if argVsS != null then
              qualC match
                case Pure(qualV) => DispatchRuntime.dispatch(qualV, method, argVsS, env, interp)
                case _           => FlatMap(qualC, qualV => DispatchRuntime.dispatch(qualV, method, argVsS, env, interp))
            else
              FlatMap(qualC, qualV =>
                interp.threadValues(argComps)(argVals => DispatchRuntime.dispatch(qualV, method, argVals, env, interp)))
        // ── remoteStub[Api](baseUrl) / Remote.stub[Api](baseUrl) ─────────────
        // Pass the erased type-name as a second argument so RemoteIntrinsics
        // can look up the stored abstract method list and build per-method
        // NativeFnV entries that POST to {baseUrl}/{methodName}.
        case Term.ApplyType.After_4_6_0(Term.Name("remoteStub"), typeArgClause)
            if typeArgClause.values.nonEmpty && app.argClause.values.sizeIs == 1 =>
          val typeName = interp.typeToString(typeArgClause.values.head.asInstanceOf[scala.meta.Type])
          val baseUrlC = eval(app.argClause.values.head, env, interp)
          baseUrlC.flatMap { baseUrlV =>
            interp.callValue2(
              interp.globals.getOrElse("remoteStub", interp.located("remoteStub not found")),
              baseUrlV, Value.StringV(typeName), env)
          }
        case Term.ApplyType.After_4_6_0(
              Term.Select(Term.Name("Remote"), Term.Name("stub")), typeArgClause)
            if typeArgClause.values.nonEmpty && app.argClause.values.sizeIs == 1 =>
          val typeName = interp.typeToString(typeArgClause.values.head.asInstanceOf[scala.meta.Type])
          val baseUrlC = eval(app.argClause.values.head, env, interp)
          baseUrlC.flatMap { baseUrlV =>
            interp.callValue2(
              interp.globals.getOrElse("remoteStub", interp.located("remoteStub not found")),
              baseUrlV, Value.StringV(typeName), env)
          }
        // ── obj.method[T](args) — type args erased, dispatch with actual args
        // Mirrors the bare Term.Select(qual, method) path above so that
        // type-parameterised method calls like `Dataset.of[Int]()` reach
        // the dispatcher with the right argument list (otherwise the
        // standalone `Dataset.of` would auto-call as a no-arg NativeFnV
        // and then the outer `()` would fail on the resulting value).
        case Term.ApplyType.After_4_6_0(Term.Select(qual, Term.Name(method)), _) =>
          val qualC    = eval(qual, env, interp)
          val argComps = app.argClause.values.map {
            case Term.Assign(_, rhs) => eval(rhs, env, interp)
            case other               => eval(other, env, interp)
          }
          val argVsT = extractPureValues(argComps)
          if argVsT != null then
            qualC match
              case Pure(qualV) => DispatchRuntime.dispatch(qualV, method, argVsT, env, interp)
              case _           => FlatMap(qualC, qualV => DispatchRuntime.dispatch(qualV, method, argVsT, env, interp))
          else
            FlatMap(qualC, qualV =>
              interp.threadValues(argComps)(argVals => DispatchRuntime.dispatch(qualV, method, argVals, env, interp)))
        // Fused curried `qual.foldLeft(z)(g)` — the typeclass-fold HOF glue.
        // The generic path evals the inner `qual.foldLeft(z)` to a `NativeFnV`
        // (one alloc) via the ~40-case dispatchList name-match, then applies it.
        // For a List receiver + FunV combine this fast-path skips the NativeFnV
        // alloc and goes straight to foldLeftReusing. Only matches the curried
        // `Apply(Apply(Select(_, "foldLeft"), [z]), [g])` shape, so plain/method
        // applies (app.fun is a Name/Select) never reach this branch.
        case inner: Term.Apply
            if app.argClause.values.lengthCompare(1) == 0
            && inner.argClause.values.lengthCompare(1) == 0
            && (inner.fun match
                  case s: Term.Select => s.name.value == "foldLeft"
                  case _              => false) =>
          val sel = inner.fun.asInstanceOf[Term.Select]
          evalFusedFoldLeft(sel.qual, inner.argClause.values.head,
                            app.argClause.values.head, env, interp)
        case _ =>
          evalPlainApply(app, env, interp)

  /** Evaluate a curried `qual.foldLeft(z)(g)`. Evaluates `qual`, `z`, `g` in the
   *  same order the generic path would, then folds. For a `ListV` receiver + a
   *  `FunV` combine it calls `foldLeftReusing` directly (no intermediate
   *  `NativeFnV`); any other receiver/combine shape completes via the generic
   *  `foldLeft` dispatch using the already-evaluated values (no re-evaluation,
   *  so semantics + effect ordering are preserved). */
  // jit-foldleft-tc: memoize the evaluated (empty, combine) of a typeclass fold
  // `xs.foldLeft(summon[M].empty)(summon[M].combine)` per call-site, keyed by the
  // resolved given identity. ON by default — kill-switch -Dssc.jit.foldtc=0 /
  // SSC_JIT_FOLDTC=0. Assumes a lawful (referentially-transparent) monoid `empty`;
  // a side-effecting `empty` (an anti-pattern) should disable it via the switch.
  private def foldTcEnabled: Boolean =
    sys.props.get("ssc.jit.foldtc").orElse(sys.env.get("SSC_JIT_FOLDTC"))
      .forall(v => v != "0" && v != "false")

  /** `summon[M].member` → the Select node, else null. */
  private def asSummonMember(t: Term): Term.Select | Null = t match
    case sel: Term.Select =>
      sel.qual match
        case at: Term.ApplyType =>
          at.fun match
            case Term.Name("summon") => sel
            case _                   => null
        case _ => null
    case _ => null

  private def evalFusedFoldLeft(qualT: Term, zT: Term, gT: Term, env: Env, interp: Interpreter): Computation =
    def finish(qualV: Value, zV: Value, gV: Value): Computation =
      qualV match
        case Value.ListV(ls) =>
          gV match
            case g: Value.FunV => CallRuntime.foldLeftReusing(ls, zV, g, env, interp)
            case _             => DispatchRuntime.dispatch1(qualV, "foldLeft", zV, env, interp)
                                    .flatMap(nf => interp.callValue1(nf, gV, env))
        case _ =>
          DispatchRuntime.dispatch1(qualV, "foldLeft", zV, env, interp)
            .flatMap(nf => interp.callValue1(nf, gV, env))
    // jit-foldleft-tc memo: when z = summon[M].x and g = summon[N].y are both pure
    // given-member accesses, resolve the two givens (cheap) and — if their identities
    // match the per-call-site cache — reuse the already-evaluated (z, g), skipping the
    // member-access sub-expressions. Sound: keyed by BOTH given identities; any change
    // re-evaluates. Bails (falls through) on any non-Pure piece.
    if foldTcEnabled && asSummonMember(zT) != null && asSummonMember(gT) != null then
      val zSel = asSummonMember(zT); val gSel = asSummonMember(gT)
      // Eval qual (xs) first — the normal order. The memo only engages for a
      // NON-EMPTY list: an empty `List[T]()` infers the given from the element
      // type, and resolving `summon[M]` standalone (without the `.member`) fails
      // for it — and an empty fold is trivially cheap anyway, so skip the memo.
      def normal(qualV: Value): Computation =
        eval(zT, env, interp).flatMap(zV => eval(gT, env, interp).flatMap(gV => finish(qualV, zV, gV)))
      return eval(qualT, env, interp).flatMap {
        case qualV @ Value.ListV(elems) if elems.nonEmpty =>
          eval(zSel.qual, env, interp) match
            case Pure(mz) =>
              eval(gSel.qual, env, interp) match
                case Pure(mg) =>
                  val cached = interp.foldTcMemo.get(gT)
                  if cached != null && (cached(0) eq mz) && (cached(1) eq mg) then
                    JitFoldTcStats.hits.incrementAndGet()
                    finish(qualV, cached(2).asInstanceOf[Value], cached(3).asInstanceOf[Value])
                  else
                    eval(zT, env, interp).flatMap { zV =>
                      eval(gT, env, interp).flatMap { gV =>
                        interp.foldTcMemo.put(gT,
                          Array(mz, mg, zV.asInstanceOf[AnyRef], gV.asInstanceOf[AnyRef]))
                        finish(qualV, zV, gV)
                      }
                    }
                case gqC => FlatMap(gqC, (_: Value) => normal(qualV))
            case zqC => FlatMap(zqC, (_: Value) => normal(qualV))
        case qualV => normal(qualV)   // empty list / non-list → normal eval
      }
    eval(qualT, env, interp) match
      case Pure(qualV) =>
        eval(zT, env, interp) match
          case Pure(zV) =>
            eval(gT, env, interp) match
              case Pure(gV) => finish(qualV, zV, gV)
              case gC       => FlatMap(gC, gV => finish(qualV, zV, gV))
          case zC => FlatMap(zC, zV => eval(gT, env, interp).flatMap(gV => finish(qualV, zV, gV)))
      case qualC => FlatMap(qualC, qualV =>
        eval(zT, env, interp).flatMap(zV => eval(gT, env, interp).flatMap(gV => finish(qualV, zV, gV))))

  private def evalArgs(args: List[Term], env: Env, interp: Interpreter)(k: List[Value] => Computation): Computation =
    val argComps = args.map(eval(_, env, interp))
    interp.threadValues(argComps)(k)

  /** Extract values from a list of Pure computations in one pass.
   *  Returns null if any computation is non-Pure (caller falls back to threadValues). */
  private[interpreter] def extractPureValues(comps: List[Computation]): List[Value] | Null =
    // Fast paths for 0–3 args avoid ArrayBuffer + toList entirely.
    // Non-pure small-arity returns null immediately without allocating the buffer.
    comps match
      case Nil                                     => Nil
      case List(Pure(v))                           => v :: Nil
      case List(_)                                 => null
      case List(Pure(v1), Pure(v2))                => v1 :: v2 :: Nil
      case List(_, _)                              => null
      case List(Pure(v1), Pure(v2), Pure(v3))      => v1 :: v2 :: v3 :: Nil
      case List(_, _, _)                           => null
      case _ =>
        val buf  = new scala.collection.mutable.ArrayBuffer[Value](comps.length)
        var rest = comps
        while rest.nonEmpty do
          rest.head match
            case Pure(v) => buf += v; rest = rest.tail
            case _       => return null
        buf.toList

  private def projectTypedRows(typeName: String, value: Value, interp: Interpreter): Value = value match
    case Value.ListV(rows) =>
      Value.ListV(rows.map(projectTypedRow(typeName, _, interp)))
    case other => other

  private def projectTypedRow(typeName: String, row: Value, interp: Interpreter): Value = row match
    case Value.MapV(entries) =>
      val order = interp.typeFieldOrder.getOrElse(typeName, null)
      val fields = if order != null then
        val schemas = interp.typeFieldSchemas.getOrElse(typeName,
          order.map(name => TypeFieldSchema(name, name, Nil, None, key = false)))
        if interp.rejectUnknownTypes.contains(typeName) then
          val known = schemas.iterator.flatMap(_.storageNames).map(_.toLowerCase(java.util.Locale.ROOT)).toSet
          entries.keys.collectFirst {
            case Value.StringV(k) if !known.contains(k.toLowerCase(java.util.Locale.ROOT)) => k
          }.foreach(name => throw InterpretError(s"$$.$name: unknown column '$name'"))
        schemas.map { schema =>
          schema.fieldName -> lookupRowField(entries, schema)
        }.toMap
      else
        entries.collect { case (Value.StringV(k), v) => k -> v }
      Value.InstanceV(typeName, fields)
    case other => other

  private def lookupRowField(entries: Map[Value, Value], schema: TypeFieldSchema): Value =
    val snIt = schema.storageNames.iterator
    while snIt.hasNext do
      val name = snIt.next()
      val exact = entries.getOrElse(Value.StringV(name), null)
      if exact != null then return exact
      val entIt = entries.iterator
      while entIt.hasNext do
        entIt.next() match
          case (Value.StringV(k), v) if k.equalsIgnoreCase(name) => return v
          case _ =>
    schema.default.getOrElse(Value.NullV)

  private def restoreHttpClientState(
      interp: Interpreter,
      priorBase: Option[Any],
      priorTimeout: Option[Any],
      priorRetries: Option[Any],
      priorRetryDelay: Option[Any]
  ): Unit =
    restoreFeatureLocal(interp, interp.NativeFeatureKeys.HttpBaseUrl, priorBase)
    restoreFeatureLocal(interp, interp.NativeFeatureKeys.HttpTimeoutMs, priorTimeout)
    restoreFeatureLocal(interp, interp.NativeFeatureKeys.HttpMaxRetries, priorRetries)
    restoreFeatureLocal(interp, interp.NativeFeatureKeys.HttpRetryDelayMs, priorRetryDelay)

  private def restoreFeatureLocal(interp: Interpreter, key: String, value: Option[Any]): Unit =
    value match
      case Some(v) => interp.nativeFeatureLocalSet(key, v)
      case None    => interp.nativeFeatureLocalRemove(key)

  /** Peel nested `Apply` nodes to collect all argument lists for a curried call.
   *  Only activates when the **outermost** `Apply` has a `using` argument clause
   *  (i.e. `mod = Some(Mod.Using())`).  In that case we collect all arg lists
   *  (regular + using) into a single flat list and return the base callee.
   *
   *  This handles `f(regularArgs)(using usingArgs)` without affecting ordinary
   *  curried calls like `onWebSocket(path) { handler }` which have no `using` mod.
   */
  def collectApplyArgs(app: Term.Apply): (Term, List[Term]) =
    // Only flatten when interp outer Apply carries `using` args
    if app.argClause.mod.isEmpty then (app.fun, app.argClause.values)
    else
      def peel(t: Term, acc: List[Term]): (Term, List[Term]) = t match
        case inner: Term.Apply => inner.fun match
          // Stop at select / type-apply / other complex funs
          case _: Term.Select | _: Term.ApplyType | _: Term.ApplyInfix =>
            (t, acc)
          case _ =>
            peel(inner.fun, inner.argClause.values ++ acc)
        case other => (other, acc)
      peel(app.fun, app.argClause.values)

  /** Thread a list of already-built Computations: bind each in order and feed
   *  the resulting values to `k`. */
  def threadValues(comps: List[Computation])(k: List[Value] => Computation): Computation =
    // Specialised fast paths for the most common small-arity cases (0–3 args).
    // Each pattern match avoids ArrayBuffer + toList + FlatMap chain overhead
    // when all arg evaluations are Pure — the common case for literal arguments.
    comps match
      case Nil => k(Nil)
      case List(Pure(v1)) => k(v1 :: Nil)
      case List(Pure(v1), Pure(v2)) => k(v1 :: v2 :: Nil)
      case List(Pure(v1), Pure(v2), Pure(v3)) => k(v1 :: v2 :: v3 :: Nil)
      case _ =>
        // General case: all-Pure check then FlatMap chain.
        var allPure = true
        var rest    = comps
        val buf     = new scala.collection.mutable.ArrayBuffer[Value](comps.length)
        while allPure && rest.nonEmpty do
          rest.head match
            case Pure(v) => buf += v; rest = rest.tail
            case _       => allPure = false
        if allPure then k(buf.toList)
        else
          def chain(remaining: List[Computation], acc: List[Value]): Computation = remaining match
            case Nil       => k(acc.reverse)
            case c :: rest => FlatMap(c, v => chain(rest, v :: acc))
          chain(comps, Nil)

  // ─── Block eval + direct[M] — see BlockRuntime.scala ─────────────────

  // ─── Call helpers ─────────────────────────────────────────────────
