package scalascript.interpreter

import scala.meta.*
import scala.collection.immutable.{Map => IMap}
import Computation.{Pure, FlatMap, PureUnit}

/** Call helpers: callValue, callFun (with TCO + factory stubs + defaults),
 *  callValueNamed (named-arg reordering), typeToString, isThrowsType,
 *  constValueOfType, applyDefaults, throwsAutoWrap.
 */
private[interpreter] object CallRuntime:

  private def paramTypeAt(paramTypes: List[String], idx: Int): String | Null =
    if idx < 0 then null
    else
      var rest = paramTypes
      var i = idx
      while i > 0 && rest.nonEmpty do
        rest = rest.tail
        i -= 1
      if i == 0 && rest.nonEmpty then rest.head else null

  private def isVarargParam(paramTypes: List[String], idx: Int): Boolean =
    val t = paramTypeAt(paramTypes, idx)
    t != null && t.endsWith("*")

  def callValue(fn: Value, args: List[Value], env: Env, interp: Interpreter): Computation = fn match
    case f: Value.FunV if f.params.isEmpty && args.nonEmpty =>
      // def f: A => B = a => body — zero-param def returning a function.
      // Evaluate f() to get the function value, then apply args to it.
      FlatMap(callFun(f, Nil, interp), result => callValue(result, args, env, interp))
    case f: Value.FunV      => callFun(f, args, interp)
    case f: Value.NativeFnV => f.f(args)
    case Value.InstanceV("Map", fields) =>
      DispatchRuntime.dispatch(Value.MapV(fields.map { case (k, v) => (Value.StringV(k), v) }), "apply", args, env, interp)
    case Value.InstanceV(_, fields) =>
      val applyFn = fields.getOrElse("apply", null)
      if applyFn != null then callValue(applyFn, args, env, interp)
      else interp.located(s"Instance is not callable")
    // `Value.SetV` belongs here for the same reason as the rest: `s(x)` is Scala's membership
    // call and `dispatchSet` has answered `"contains" | "apply"` all along. It was the one
    // container missing from this alternation, so `Set(1, 2)(2)` failed the callable check with
    // "Not callable" — while the comment fifteen lines below already said "matching
    // ListV/MapV/SetV apply". BUGS `int-set-apply-is-not-membership`.
    case _: Value.ListV | _: Value.MapV | _: Value.SetV | _: Value.VectorV | _: Value.ArrayV | _: Value.LazyListV =>
      DispatchRuntime.dispatch(fn, "apply", args, env, interp)
    // `sig()` — read a reactive Signal's current value (Scala `Signal.apply(): T`).
    // The JS/JVM emitters wire this to the reactive runtime; on the interpreter it
    // returns the signal's current (initial) value, so a `computedSignal(() => … sig() …)`
    // thunk and other signal reads can be evaluated for server-side/initial render
    // instead of failing the callable check with a misleading "Not callable". A frontend
    // library type referenced via a string-tagged Foreign, mirroring the `.bind` bridge.
    case Value.Foreign("ReactiveSignal", sig: scalascript.frontend.Signal[?]) if args.isEmpty =>
      Pure(interp.wrapAnyAsValue(sig.apply()))
    // `s(i)` — String index-apply returns the i-th char (Scala `s.apply(i): Char`),
    // matching ListV/MapV/SetV apply.  Without this, `s(i)` fails the callable check
    // with a misleading "Not callable" (specs/string-index-apply.md).
    case Value.StringV(s) => args match
      case List(Value.IntV(i)) =>
        if i >= 0 && i < s.length then Pure(Value.CharV(s.charAt(i.toInt)))
        else interp.located(s"String index out of range: $i (length ${s.length})")
      case _ => interp.located(s"Not callable: ${Value.show(fn)}")
    case _ => interp.located(s"Not callable: ${Value.show(fn)}")

  /** Zero-argument fast path: skips List()/env allocation for 0-param FunV calls.
   *  Handles the common `workload()` / `thunk()` pattern without going through
   *  the full callFun → tcoInfoFor → closureWithSelfFor machinery on every call. */
  def callValue0(fn: Value, env: Env, interp: Interpreter): Computation =
    val jit = fn match
      case f: Value.FunV => scalascript.interpreter.vm.JitRuntime.tryRun0(f, interp)
      case _             => null
    if jit != null then jit else callValue0Slow(fn, env, interp)

  private def callValue0Slow(fn: Value, env: Env, interp: Interpreter): Computation = fn match
    case f: Value.FunV if
        f.params.isEmpty &&
        f.usingParams.isEmpty &&
        !f.returnsThrows &&
        (f.name.isEmpty || { val i = TcoRuntime.tcoInfoFor(f, interp); !i.isSelfTailRec && i.tailTargets.isEmpty }) =>
      val callEnv: Env = if f.name.nonEmpty then interp.closureWithSelfFor(f) else f.closure
      val frameName = if f.name.nonEmpty then f.name else "<anon>"
      val relLine   = if interp.currentSpanLine >= 0 then interp.currentSpanLine + 1 else 0
      interp.callStackPush(frameName, interp.debugSourceFile, interp.debugBlockDocLine + relLine)
      val profiling = Profiler.enabled
      val t0 = if profiling && f.name.nonEmpty then System.nanoTime() else 0L
      val result =
        try TcoRuntime.runUntilSuspension(interp.eval(f.body, callEnv))
        catch case r: ReturnSignal => Pure(r.value)
      if profiling && f.name.nonEmpty then Profiler.record(f.name, System.nanoTime() - t0)
      if interp.callStackNonEmpty then interp.callStackPop()
      result
    case f: Value.NativeFnV => f.f(Nil)
    case _ => callValue(fn, Nil, env, interp)

  /** Single-argument fast path: avoids allocating List(arg) on every map/filter/forEach call.
   *  For simple 1-param FunV (no varargs, no using, no defaults, no TCO) builds
   *  the call env directly.  Falls back to callValue(fn, List(arg), env, interp) otherwise. */
  def callValue1(fn: Value, arg: Value, env: Env, interp: Interpreter): Computation =
    val jit = fn match
      case f: Value.FunV => scalascript.interpreter.vm.JitRuntime.tryRun1(f, arg, interp)
      case _             => null
    if jit != null then jit else callValue1Slow(fn, arg, env, interp)

  private def callValue1Slow(fn: Value, arg: Value, env: Env, interp: Interpreter): Computation = fn match
    case f: Value.FunV if
        f.params.length == 1 &&
        f.usingParams.isEmpty &&
        !f.returnsThrows &&
        (f.defaults.isEmpty || f.defaults.head.isEmpty) &&
        (f.paramTypes.isEmpty || !f.paramTypes.head.endsWith("*")) &&
        // Skip fast path for named self-tail-recursive functions — they need tcoTrampoline.
        // Anonymous lambdas (f.name.isEmpty) are never self-recursive so always safe here.
        (f.name.isEmpty || { val i = TcoRuntime.tcoInfoFor(f, interp); !i.isSelfTailRec && i.tailTargets.isEmpty }) =>
      val withSelf: Env = if f.name.nonEmpty then interp.closureWithSelfFor(f) else f.closure
      val callEnv:  Env = FrameMap.one(f.params.head, arg, withSelf)
      runBody1(f, callEnv, interp)
    case f: Value.NativeFnV => f.f(arg :: Nil)
    case _ => callValue(fn, arg :: Nil, env, interp)

  /** Evaluate a simple 1-param FunV body against an already-prepared call env.
   *  Shared by `callValue1Slow` (which builds a fresh `FrameMap.one`) and the
   *  reused-frame iteration fast path (`foreachReusing`). Pushes/pops the call
   *  stack and converts a non-local `return` into `Pure(value)`, mirroring the
   *  prior inline logic exactly. */
  private[interpreter] def runBody1(f: Value.FunV, callEnv: Env, interp: Interpreter): Computation =
    val frameName = if f.name.nonEmpty then f.name else "<anon>"
    val relLine   = if interp.currentSpanLine >= 0 then interp.currentSpanLine + 1 else 0
    interp.callStackPush(frameName, interp.debugSourceFile, interp.debugBlockDocLine + relLine)
    val profiling = Profiler.enabled
    val t0 = if profiling && f.name.nonEmpty then System.nanoTime() else 0L
    val result =
      try TcoRuntime.runUntilSuspension(interp.eval(f.body, callEnv))
      catch case r: ReturnSignal => Pure(r.value)
    if profiling && f.name.nonEmpty then Profiler.record(f.name, System.nanoTime() - t0)
    if interp.callStackNonEmpty then interp.callStackPop()
    result

  /** True when `f` is a simple 1-param FunV that `callValue1Slow` handles on its
   *  non-allocating fast path — so its body can run against a `ReusableFrame1`. */
  private[interpreter] def isSimple1ParamFun(f: Value.FunV, interp: Interpreter): Boolean =
    f.params.length == 1 &&
    f.usingParams.isEmpty &&
    !f.returnsThrows &&
    (f.defaults.isEmpty || f.defaults.head.isEmpty) &&
    (f.paramTypes.isEmpty || !f.paramTypes.head.endsWith("*")) &&
    (f.name.isEmpty || { val i = TcoRuntime.tcoInfoFor(f, interp); !i.isSelfTailRec && i.tailTargets.isEmpty })

  /** `xs.foreach(f)` with a single reused frame instead of a `FrameMap1` per
   *  element. For a simple 1-param `FunV` whose body returns `Pure` on every
   *  element, one `ReusableFrame1` is mutated across the whole sequence — safe
   *  because a `Pure` result can retain the env only via a by-value closure
   *  snapshot (see `ReusableFrame1`). The first non-`Pure` result (a deferred
   *  effect that may close over the frame) bails: the frame is left untouched and
   *  the remaining elements run through the allocating `foreachSequence` path. */
  def foreachReusing(ls: List[Value], f: Value.FunV, env: Env, interp: Interpreter): Computation =
    if !isSimple1ParamFun(f, interp) then
      return Computation.foreachSequence(ls, item => callValue1(f, item, env, interp))
    // Tier-2b fast path: `xs.foreach(s => acc = acc + fn(s))` with a Double
    // accumulator and a slot-double-compilable `fn`. `SSC_FASTTIER` gates the
    // whole path; the analyzer caches per closure-body AST identity, so a
    // miss is one IdentityHashMap lookup + a reference compare.
    val ftD = FastTier.tryDoubleAccumForeach(ls, f, interp)
    if ftD != null then return ftD
    // Long-typed parallel — same shape, `Int` accumulator (e.g. wide bench).
    val ftL = FastTier.tryLongAccumForeach(ls, f, interp)
    if ftL != null then return ftL
    val withSelf: Env = if f.name.nonEmpty then interp.closureWithSelfFor(f) else f.closure
    val frame = new ReusableFrame1(f.params.head, withSelf)
    var rem = ls
    while rem.nonEmpty do
      val item = rem.head
      // JITted bodies run compiled code and never touch the frame; keep that path.
      val jit = scalascript.interpreter.vm.JitRuntime.tryRun1(f, item, interp)
      val r =
        if jit != null then jit
        else { frame.v1 = item; runBody1(f, frame, interp) }
      r match
        case Pure(_) => rem = rem.tail
        case comp =>
          val tail = rem.tail
          return FlatMap(comp, _ => Computation.foreachSequence(tail, it => callValue1(f, it, env, interp)))
    PureUnit

  /** `xs.map(f)` with a single reused frame instead of a `FrameMap1` per element.
   *  Map analogue of `foreachReusing`: collects each `Pure` result into a buffer
   *  while mutating one `ReusableFrame1` across the whole sequence. The first
   *  non-`Pure` result (a deferred effect that may close over the frame) bails to
   *  the allocating `mapSequence`/`callValue1` path for the remaining elements. */
  def mapReusing(ls: List[Value], f: Value.FunV, env: Env, interp: Interpreter): Computation =
    if !isSimple1ParamFun(f, interp) then
      return Computation.mapSequence(ls, item => callValue1(f, item, env, interp))
    if ls.isEmpty then return Computation.PureEmptyList
    val withSelf: Env = if f.name.nonEmpty then interp.closureWithSelfFor(f) else f.closure
    val frame = new ReusableFrame1(f.params.head, withSelf)
    val buf = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
    var rem = ls
    while rem.nonEmpty do
      val item = rem.head
      val jit = scalascript.interpreter.vm.JitRuntime.tryRun1(f, item, interp)
      val r =
        if jit != null then jit
        else { frame.v1 = item; runBody1(f, frame, interp) }
      r match
        case Pure(v) => buf += v; rem = rem.tail
        case comp =>
          val tail = rem.tail
          def loopRest(remaining: List[Value]): Computation = remaining match
            case Nil    => Pure(Value.ListV(buf.toList))
            case h :: t => FlatMap(callValue1(f, h, env, interp), { rv => buf += rv; loopRest(t) })
          return FlatMap(comp, { rv => buf += rv; loopRest(tail) })
    Pure(Value.ListV(buf.toList))

  /** `xs.foldLeft(z)(f)` with a single reused two-slot frame instead of a
   *  `FrameMap2` per element. Fold analogue of `foreachReusing`/`mapReusing`:
   *  runs `f(acc, item)` against one mutated `ReusableFrame2` while every result
   *  is `Pure`. A non-`Pure` body bails to the allocating `callValue2` path. */
  def foldLeftReusing(ls: List[Value], init: Value, f: Value.FunV, env: Env, interp: Interpreter): Computation =
    if !isSimple2ParamFun(f, interp) then
      return Computation.foldLeftSequence(ls, init, (acc, h) => callValue2(f, acc, h, env, interp))
    val withSelf: Env = if f.name.nonEmpty then interp.closureWithSelfFor(f) else f.closure
    val frame = new ReusableFrame2(f.params.head, f.params(1), withSelf)
    var rem = ls
    var acc = init
    while rem.nonEmpty do
      val item = rem.head
      val jit = scalascript.interpreter.vm.JitRuntime.tryRun2(f, acc, item, interp)
      val r =
        if jit != null then jit
        else { frame.v1 = acc; frame.v2 = item; runBody1(f, frame, interp) }
      r match
        case Pure(v) => acc = v; rem = rem.tail
        case comp =>
          val tail = rem.tail
          def loopRest(remaining: List[Value], curAcc: Value): Computation = remaining match
            case Nil       => Pure(curAcc)
            case h :: rest => FlatMap(callValue2(f, curAcc, h, env, interp), v => loopRest(rest, v))
          return FlatMap(comp, v => loopRest(tail, v))
    Pure(acc)

  /** True when `f` is a simple 2-param FunV that `callValue2Slow` handles on its
   *  non-allocating fast path — so its body can run against a `ReusableFrame2`. */
  private[interpreter] def isSimple2ParamFun(f: Value.FunV, interp: Interpreter): Boolean =
    f.params.length == 2 &&
    f.usingParams.isEmpty &&
    !f.returnsThrows &&
    (f.defaults.isEmpty || f.defaults.head.isEmpty) &&
    (f.paramTypes.lengthCompare(2) < 2 || !f.paramTypes(1).endsWith("*")) &&
    (f.name.isEmpty || { val i = TcoRuntime.tcoInfoFor(f, interp); !i.isSelfTailRec && i.tailTargets.isEmpty })

  /** Two-argument fast path: avoids allocating List(a, b) on foldLeft/foldRight/reduceLeft calls.
   *  For simple 2-param FunV (no varargs, no using, no defaults, no TCO) builds the call env
   *  with FrameMap.two directly.  Falls back to callValue(fn, List(a, b), env, interp) otherwise. */
  def callValue2(fn: Value, a: Value, b: Value, env: Env, interp: Interpreter): Computation =
    val jit = fn match
      case f: Value.FunV => scalascript.interpreter.vm.JitRuntime.tryRun2(f, a, b, interp)
      case _             => null
    if jit != null then jit else callValue2Slow(fn, a, b, env, interp)

  private def callValue2Slow(fn: Value, a: Value, b: Value, env: Env, interp: Interpreter): Computation = fn match
    case f: Value.FunV if
        f.params.length == 2 &&
        f.usingParams.isEmpty &&
        !f.returnsThrows &&
        (f.defaults.isEmpty || f.defaults.head.isEmpty) &&
        (f.paramTypes.lengthCompare(2) < 2 || !f.paramTypes(1).endsWith("*")) &&
        (f.name.isEmpty || { val i = TcoRuntime.tcoInfoFor(f, interp); !i.isSelfTailRec && i.tailTargets.isEmpty }) =>
      val withSelf: Env = if f.name.nonEmpty then interp.closureWithSelfFor(f) else f.closure
      val callEnv:  Env = FrameMap.two(f.params.head, a, f.params(1), b, withSelf)
      val frameName = if f.name.nonEmpty then f.name else "<anon>"
      val relLine   = if interp.currentSpanLine >= 0 then interp.currentSpanLine + 1 else 0
      interp.callStackPush(frameName, interp.debugSourceFile, interp.debugBlockDocLine + relLine)
      val profiling = Profiler.enabled
      val t0 = if profiling && f.name.nonEmpty then System.nanoTime() else 0L
      val result =
        try TcoRuntime.runUntilSuspension(interp.eval(f.body, callEnv))
        catch case r: ReturnSignal => Pure(r.value)
      if profiling && f.name.nonEmpty then Profiler.record(f.name, System.nanoTime() - t0)
      if interp.callStackNonEmpty then interp.callStackPop()
      result
    case f: Value.NativeFnV => f.f(a :: b :: Nil)
    case _ => callValue(fn, a :: b :: Nil, env, interp)

  /** Fast path for map-iteration callbacks receiving a (key, value) entry.
   *  If `fn` is a 2-param FunV (e.g. `{ (k, v) => ... }`), calls callValue2 — no TupleV.
   *  Otherwise creates Value.TupleV(k :: v :: Nil) and calls callValue1. */
  inline def callEntry(fn: Value, k: Value, v: Value, env: Env, interp: Interpreter): Computation = fn match
    case f: Value.FunV if
        f.params.length == 2 &&
        f.usingParams.isEmpty &&
        !f.returnsThrows &&
        (f.defaults.isEmpty || f.defaults.head.isEmpty) &&
        (f.paramTypes.lengthCompare(2) < 2 || !f.paramTypes(1).endsWith("*")) =>
      callValue2(fn, k, v, env, interp)
    case _ =>
      callValue1(fn, Value.TupleV(k :: v :: Nil), env, interp)

  /** Fast path for `fn(recv, args*)`: avoids allocating `recv :: args` list.
   *  Dispatches to callValue1/callValue2 for the 0/1-arg cases; falls back otherwise. */
  inline def callValuePrepend(fn: Value, recv: Value, args: List[Value], env: Env, interp: Interpreter): Computation =
    args match
      case Nil      => callValue1(fn, recv, env, interp)
      case a :: Nil => callValue2(fn, recv, a, env, interp)
      case _        => callValue(fn, recv :: args, env, interp)

  /** The user-level `FunV` a `recv.name(...)` call will end up invoking, or `null`.
   *
   *  Mirrors the two lookups `DispatchRuntime.dispatchInstanceFallback` makes, in the same order:
   *  an object's members are FIELDS of its `InstanceV` (`StatRuntime`'s `Defn.Object` case), while
   *  a class's methods live in `interp.typeMethods`. Used to recover the PARAMETER NAMES that the
   *  positional `dispatch*` signature cannot carry. */
  def methodFunFor(recv: Value, name: String, interp: Interpreter): Value.FunV =
    recv match
      case inst: Value.InstanceV =>
        inst.effectiveFields.getOrElse(name, null) match
          case f: Value.FunV => f
          case _             => interp.typeMethods.get(inst.typeName).flatMap(_.get(name)).orNull
      case _ => null

  /** `recv.m()` with an EXPLICIT empty argument list, where every parameter of `m` has a default —
   *  a complete call, not a function value. Returns null when that does not apply.
   *
   *  Why this cannot live in `DispatchRuntime`: by the time a member reaches it, `V.one` and
   *  `V.one()` are indistinguishable — both arrive with `args = Nil` — and the parenless form is a
   *  function VALUE on every lane (measured 2026-07-30: INT `<function(1)>`, native `<closure>`, the
   *  JVM a lambda; all three agree). So the empty arg list has to be acted on where it is still
   *  visible, which is the caller in `EvalRuntime`. Object members are fields of the receiver's
   *  `InstanceV`, and a field holding a `FunV` was only ever called when it had NO parameters, so
   *  `V.one()` on `def one(a: Int = 3)` returned the closure instead of `3`.
   *  BUGS `v1-interp-zero-arg-call-to-all-defaulted-object-method-returns-a-closure`. */
  def zeroArgAllDefaults(recv: Value, name: String, interp: Interpreter): Value.FunV =
    val f = methodFunFor(recv, name, interp)
    if f == null || f.params.isEmpty || f.usingParams.nonEmpty then null
    else if f.defaults.length < f.params.length then null
    else if f.defaults.take(f.params.length).exists(_.isEmpty) then null
    else f

  /** Reorder `namedArgs` into a COMPLETE positional list for `f`, filling omitted params from
   *  their defaults — or `null` when that cannot be done faithfully.
   *
   *  Why reorder instead of calling `f` directly: the receiver still has to go through
   *  `DispatchRuntime`, which is what binds an instance's fields inside a class-method body. This
   *  fixes the ORDER and leaves the invocation exactly where it was.
   *
   *  Returns `null` — caller keeps its existing positional behaviour rather than guessing — for
   *  `using` params, varargs, an unknown argument name, more positionals than free slots, or a
   *  required param left unfilled.
   *
   *  Defaults are evaluated in `closureWithSelfFor(f)` PLUS `recvFields` plus the params already
   *  bound. The receiver's fields matter: a class method may default a param to a field
   *  (`def shift(dx: Int = x)`), and that name only exists because `DispatchRuntime` binds the
   *  instance's fields around the body. Without them this both crashed on a shape that merely
   *  returned a wrong number before, and could not produce the right answer for it. Any failure to
   *  evaluate a default falls back to `null` rather than raising here — a genuinely broken default
   *  still raises from the ordinary positional path, so nothing is swallowed. */
  def positionalizeNamed(
      f: Value.FunV,
      namedArgs: List[(Option[String], Value)],
      recvFields: Map[String, Value],
      interp: Interpreter
  ): List[Value] =
    val n = f.params.length
    if f.usingParams.nonEmpty || namedArgs.length > n || n == 0 then return null
    if isVarargParam(f.paramTypes, n - 1) then return null
    if namedArgs.exists { case (Some(nm), _) => !f.params.contains(nm); case _ => false } then return null
    val slots = Array.ofDim[Value](n)
    namedArgs.foreach {
      case (Some(nm), v) =>
        val i = f.params.indexOf(nm)
        if i >= 0 then slots(i) = v
      case _ => ()
    }
    val posIter = namedArgs.iterator.collect { case (None, v) => v }
    var i = 0
    while i < n do
      if slots(i) == null && posIter.hasNext then slots(i) = posIter.next()
      i += 1
    if posIter.hasNext then return null
    var env2: Map[String, Value] =
      if recvFields.isEmpty then interp.closureWithSelfFor(f)
      else interp.closureWithSelfFor(f) ++ recvFields
    val out = Array.ofDim[Value](n)
    i = 0
    while i < n do
      val sv = slots(i)
      if sv != null then out(i) = sv
      else
        (if i < f.defaults.length then f.defaults(i) else None) match
          case Some(defaultTerm) =>
            try out(i) = Computation.run(interp.eval(defaultTerm, env2))
            catch case _: Throwable => return null
          case None => return null
      env2 = FrameMap.one(f.params(i), out(i), env2)
      i += 1
    out.toList

  def callValueNamed(fn: Value, namedArgs: List[(Option[String], Value)], env: Env, interp: Interpreter): Computation =
    fn match
      case f: Value.FunV =>
        val paramSet = f.params.toSet
        namedArgs.foreach {
          case (Some(n), _) if !paramSet.contains(n) =>
            interp.located(s"Unknown argument name '$n' for function '${if f.name.nonEmpty then f.name else "<anon>"}' (parameters: ${f.params.mkString(", ")})")
          case _ => ()
        }
        val slots = Array.ofDim[Value](f.params.length)   // null = unfilled
        namedArgs.foreach {
          case (Some(n), v) =>
            val idx = f.params.indexOf(n)
            if idx >= 0 then slots(idx) = v
          case _ => ()
        }
        val positionals = namedArgs.collect { case (None, v) => v }
        val posIter = positionals.iterator
        // Index of the last regular (non-using) param; that's where vararg would live.
        val lastRegularIdx = f.params.length - 1 - f.usingParams.length
        val lastRegularIsVararg = isVarargParam(f.paramTypes, lastRegularIdx)
        for i <- slots.indices do
          if slots(i) == null && posIter.hasNext then
            if lastRegularIsVararg && i == lastRegularIdx then
              // Collect all remaining positionals into a ListV for the vararg param.
              val varargVals = (Iterator.single(posIter.next()) ++ posIter).toList
              slots(i) = Value.ListV(varargVals)
            else
              slots(i) = posIter.next()
        var baseEnv2  = interp.closureWithSelfFor(f)
        val orderedArr = Array.fill[Value](f.params.length)(Value.UnitV)
        var partialFrom = -1  // first index with no value and no default
        for i <- f.params.indices do
          val sv = slots(i)
          if sv != null then
            orderedArr(i) = sv
            baseEnv2 = FrameMap.one(f.params(i), sv, baseEnv2)
          else
            val defaultOpt = if i < f.defaults.length then f.defaults(i) else None
            defaultOpt match
              case Some(defaultTerm) =>
                val v = Computation.run(interp.eval(defaultTerm, baseEnv2))
                orderedArr(i) = v
                baseEnv2 = FrameMap.one(f.params(i), v, baseEnv2)
              case None =>
                if partialFrom < 0 then partialFrom = i
        if partialFrom >= 0 && namedArgs.nonEmpty then
          // Some required params are unsatisfied — return a partial closure so that
          // curried call sites like `f(a)(b, c)` work when `f` is stored flattened.
          val partialEnv = f.closure ++ Map.from(f.params.take(partialFrom).lazyZip(orderedArr.take(partialFrom)))
          Pure(Value.FunV(
            f.params.drop(partialFrom),
            f.body,
            partialEnv,
            f.name,
            f.defaults.drop(partialFrom),
            f.paramTypes.drop(partialFrom),
            f.usingParams,
            f.returnsThrows
          ))
        else
          // If the vararg slot was never filled, truncate the args so callFun sees
          // only the filled positions and appends an empty ListV for the vararg.
          val filledCount =
            if lastRegularIsVararg && slots(lastRegularIdx) == null
            then lastRegularIdx
            else f.params.length
          callFun(f, orderedArr.take(filledCount).toList, interp)
      case f: Value.NativeFnV =>
        // A NativeFnV drops argument names, so a case-class constructor called with
        // partial named args (`S(a = .., c = ..)`) would bind values positionally and
        // mis-place them.  When the target is a known type constructor, reorder named
        // args into a full positional list by field order, filling omitted fields with
        // their declared defaults, then hand the complete list to the ctor.
        val fieldOrderOpt = if namedArgs.exists(_._1.isDefined) then interp.typeFieldOrder.get(f.name) else None
        fieldOrderOpt match
          case Some(fieldOrder) =>
            val slots = Array.ofDim[Value](fieldOrder.length)   // null = unfilled
            namedArgs.foreach {
              case (Some(n), v) =>
                val i = fieldOrder.indexOf(n)
                if i >= 0 then slots(i) = v
                else interp.located(s"Unknown argument name '$n' for '${f.name}' (fields: ${fieldOrder.mkString(", ")})")
              case _ => ()
            }
            val posIter = namedArgs.collect { case (None, v) => v }.iterator
            for i <- slots.indices do
              if slots(i) == null && posIter.hasNext then slots(i) = posIter.next()
            val (defaults, ctorEnv) = interp.typeFieldDefaults.getOrElse(f.name, (Nil, env))
            var fillEnv = ctorEnv
            for i <- slots.indices do
              if slots(i) != null then
                fillEnv = FrameMap.one(fieldOrder(i), slots(i), fillEnv)
              else
                val d = if i < defaults.length then defaults(i) else None
                d match
                  case Some(term) =>
                    val v = Computation.run(interp.eval(term, fillEnv))
                    slots(i) = v
                    fillEnv = FrameMap.one(fieldOrder(i), v, fillEnv)
                  case None =>
                    interp.located(s"missing argument for field '${fieldOrder(i)}' of '${f.name}'")
            f.f(slots.toList)
          case None => f.f(namedArgs.map(_._2))
      case Value.InstanceV(_, fields) =>
        val applyFn = fields.getOrElse("apply", null)
        if applyFn != null then callValueNamed(applyFn, namedArgs, env, interp)
        else interp.located(s"Instance is not callable")
      case _: Value.ListV | _: Value.MapV => DispatchRuntime.dispatch(fn, "apply", namedArgs.map(_._2), env, interp)
      case _ => interp.located(s"Not callable: ${Value.show(fn)}")

  def callFun(f: Value.FunV, args: List[Value], interp: Interpreter): Computation =
    val info = TcoRuntime.tcoInfoFor(f, interp)
    val jit = scalascript.interpreter.vm.JitRuntime.tryRunList(
      f, args, interp, eager = info.isSelfTailRec || info.tailTargets.nonEmpty)
    if jit != null then return jit
    val tupledArgs = args match
      case List(Value.TupleV(elems))
        if f.params.length > 1 && elems.length == f.params.length =>
        elems
      case _ => args
    // True when the last regular parameter is varargs (type ends with "*").
    val lastIsVararg = isVarargParam(f.paramTypes, f.params.length - 1 - f.usingParams.length)
    def packVarargs(rawArgs: List[Value]): List[Value] =
      if !lastIsVararg || rawArgs.length < f.params.length - 1 then rawArgs
      else
        // Pack surplus args into a ListV for the vararg slot (empty list when zero extras).
        val regularCount = f.params.length - 1
        rawArgs.take(regularCount) :+ Value.ListV(rawArgs.drop(regularCount))
    def resolveFactoryStubs(args: List[Value]): List[Value] =
      if f.usingParams.isEmpty || interp.givenFactories.isEmpty then args
      else
        val regularCount = f.params.length - f.usingParams.length
        val regularVals  = args.take(regularCount)
        args.zipWithIndex.map { case (v, i) =>
          if i >= regularCount then
            v match
              case Value.InstanceV(_, fs) if fs.contains("__factory__") =>
                val usingIdx = i - regularCount
                val typeKey =
                  if usingIdx >= 0 && usingIdx < f.usingParams.length then f.usingParams(usingIdx)._2
                  else ""
                if typeKey.nonEmpty then
                  GivenRuntime.resolveGiven(typeKey, regularVals, f.closure, interp).getOrElse(v)
                else v
              case _ => v
          else v
        }
    // If the call provides fewer positional args than the function has params, and some of the
    // missing params are required (no default), return a partial closure so that curried calls
    // like `f(a)(b, c)` work when the def is stored with all param lists flattened.
    if tupledArgs.nonEmpty && tupledArgs.length < f.params.length && f.usingParams.isEmpty then
      // Fill as many defaults as we can; stop at the first required (no-default) param.
      var env2: Map[String, Value] = interp.closureWithSelfFor(f)
      val pIter = f.params.iterator; val aIter = tupledArgs.iterator
      while pIter.hasNext && aIter.hasNext do env2 = FrameMap.one(pIter.next(), aIter.next(), env2)
      var partialStart = -1
      val filled = scala.collection.mutable.ListBuffer.empty[Value]
      for i <- (tupledArgs.length until f.params.length) do
        if partialStart < 0 then
          (if i < f.defaults.length then f.defaults(i) else None) match
            case Some(defaultTerm) =>
              val v = Computation.run(interp.eval(defaultTerm, env2))
              filled += v
              env2 = FrameMap.one(f.params(i), v, env2)
            case None =>
              partialStart = i
      if partialStart >= 0 then
        return Pure(Value.FunV(
          f.params.drop(partialStart),
          f.body,
          env2,
          f.name,
          f.defaults.drop(partialStart),
          f.paramTypes.drop(partialStart),
          f.usingParams,
          f.returnsThrows
        ))
    val effArgs =
      if lastIsVararg && tupledArgs.length == f.params.length - 1 then
        resolveFactoryStubs(packVarargs(tupledArgs))
      else if tupledArgs.length >= f.params.length then resolveFactoryStubs(packVarargs(tupledArgs))
      else if f.usingParams.nonEmpty then
        val regularCount = f.params.length - f.usingParams.length
        val withUsing =
          if tupledArgs.length >= regularCount then
            val regularArgVals    = tupledArgs.take(regularCount)
            val regularParamTypes = f.paramTypes.take(regularCount)
            // Monomorphic cache: resolution is keyed on f's static info + the
            // regular args' runtime types (this path resolves against f.closure).
            val resolved = GivenRuntime.resolveUsingAllCached(f, regularParamTypes, regularArgVals, interp)
            tupledArgs ++ resolved
          else
            tupledArgs
        if withUsing.length >= f.params.length then withUsing
        else applyDefaults(f.params, f.defaults, withUsing, interp.closureWithSelfFor(f), interp)
      else
        applyDefaults(f.params, f.defaults, tupledArgs, interp.closureWithSelfFor(f), interp)
    val hasMutualTail = info.tailTargets.nonEmpty && info.tailTargets.exists { n =>
      val gv = interp.globals.getOrElse(n, null)
      val v  = if gv != null then gv else f.closure.getOrElse(n, null)
      v != null && v.isInstanceOf[Value.FunV]
    }
    if info.noNonTailSelf && (info.isSelfTailRec || hasMutualTail) then
      // Phase C bytecode JIT: try the BytecodeJit path before the trampoline.
      // For TCO functions BytecodeJit emits a Java `while` loop (see
      // BytecodeJit.tryTcoBody), eliminating both the trampoline cost AND the
      // SscVm.exec dispatch loop. Null falls through to the trampoline.
      val bcResult = scalascript.interpreter.vm.JitRuntime.tryBytecodeList(f, effArgs, interp)
      if bcResult != null then return bcResult
      if Profiler.enabled && f.name.nonEmpty then
        Profiler.record(f.name, 0L)
      TcoRuntime.tcoTrampoline(f, effArgs, null, interp)
    else
      val withSelf = interp.closureWithSelfFor(f)
      val callEnv: Env = f.params match
        case Nil               => withSelf
        case p :: Nil          => FrameMap.one(p, effArgs.head, withSelf)
        case p1 :: p2 :: Nil   => FrameMap.two(p1, effArgs.head, p2, effArgs(1), withSelf)
        case ps                =>
          var names = interp.paramsArrayCache.get(f.body)
          if names == null then { names = ps.toArray; interp.paramsArrayCache.put(f.body, names) }
          val arr = effArgs.iterator.take(names.length).toArray
          FrameMap.of(names, arr, withSelf)
      val frameName  = if f.name.nonEmpty then f.name else "<anon>"
      val relLine    = if interp.currentSpanLine >= 0 then interp.currentSpanLine + 1 else 0
      val absDocLine = interp.debugBlockDocLine + relLine
      interp.callStackPush(frameName, interp.debugSourceFile, absDocLine)
      val profiling = Profiler.enabled
      val t0 = if profiling && f.name.nonEmpty then System.nanoTime() else 0L
      val result =
        try TcoRuntime.runUntilSuspension(interp.eval(f.body, callEnv))
        catch case r: ReturnSignal => Pure(r.value)
      if profiling && f.name.nonEmpty then
        Profiler.record(f.name, System.nanoTime() - t0)
      if interp.callStackNonEmpty then interp.callStackPop()
      if f.returnsThrows then result.map(throwsAutoWrap)
      else result

  /** Optimised entry point for the typeMethods dispatch path.
   *
   *  The standard path does `fn.copy(closure = fn.closure ++ fields)` which:
   *    1. Merges two HashMaps (O(n) allocation)
   *    2. Creates a new FunV — a fresh identity causes tcoCache and
   *       closureWithSelfCache to miss on every call and re-traverse the body AST.
   *
   *  Here we build the base env with FrameMap.fromMap (one FrameMapN allocation,
   *  no merge), pass the ORIGINAL fn so tcoInfoFor gets a body-keyed cache hit,
   *  and fall back to the full path only for the rare TCO case.
   */
  def callTypeMethod(
    fn: Value.FunV, fields: Map[String, Value], args: List[Value], interp: Interpreter
  ): Computation =
    // Layer instance fields over fn.closure without allocating a merged Map.
    // For named methods the self-ref is a NativeFnV that re-dispatches through
    // callTypeMethod so that non-tail-recursive calls still see instance fields.
    // This avoids fn.closure.updated(fn.name, ...) which for MutableEnvView
    // would copy the entire scope (O(n) allocation).
    // Compute tcoInfo first (cached by body) so ordinary non-recursive methods
    // can skip the NativeFnV self-ref allocation entirely.
    val info = TcoRuntime.tcoInfoFor(fn, interp)
    val base: Map[String, Value] =
      if fn.name.isEmpty || !info.hasSelfNameRef then FrameMap.fromMap(fields, fn.closure)
      else
        val selfRef = Value.NativeFnV(fn.name, recArgs => callTypeMethod(fn, fields, recArgs, interp))
        FrameMap.fromMapWithSelf(fields, fn.name, selfRef, fn.closure)
    val hasMutualTail = info.tailTargets.nonEmpty && info.tailTargets.exists { n =>
      val gv = interp.globals.getOrElse(n, null)
      val v  = if gv != null then gv else base.getOrElse(n, null)
      v != null && v.isInstanceOf[Value.FunV]
    }
    if info.noNonTailSelf && (info.isSelfTailRec || hasMutualTail) then
      // TCO path: tcoTrampoline reads curFun.closure directly, so we still need
      // a copy here. We use `base` (already a FrameMapN) rather than `fn.closure ++ fields`
      // to avoid the HashMap merge; tcoInfoFor now hits the cache via body identity.
      if Profiler.enabled && fn.name.nonEmpty then Profiler.record(fn.name, 0L)
      TcoRuntime.tcoTrampoline(fn.copy(closure = base), args, null, interp)
    else
      // Normal path: build call env directly on top of `base`.
      // vararg / usingParam logic is the same as callFun.
      val lastIsVararg = isVarargParam(fn.paramTypes, fn.params.length - 1 - fn.usingParams.length)
      def packVarargs(rawArgs: List[Value]): List[Value] =
        if !lastIsVararg || rawArgs.length < fn.params.length - 1 then rawArgs
        else
          val regularCount = fn.params.length - 1
          rawArgs.take(regularCount) :+ Value.ListV(rawArgs.drop(regularCount))
      // CURRIED METHODS. `def h(a: Int)(b: Int)` is stored with its clauses FLATTENED — params
      // `[a, b]` — exactly as a top-level `def h(a)(b)` is, so `c.h(3)` arrives here one argument
      // short and used to reach `applyDefaults`, which refuses: `missing argument for parameter
      // 'b'`. The flattening was never the defect; `callFun` flattens identically and `h(3)(4)`
      // works at top level, because it returns a PARTIAL CLOSURE for exactly this case. This is
      // that branch, on the method path, which never had one
      // (interp-curried-class-method-cannot-be-applied).
      //
      // The closure captures `base`, not `fn.closure`: instance fields are layered into `base`, and
      // a partial application that dropped them would fail on the SECOND call rather than here —
      // the worse failure, because by then the method looks like it started working.
      //
      // ONE GUARD MORE THAN `callFun` HAS, and deliberately: a vararg method called with its
      // varargs omitted (`def f(a, xs: Int*)` as `f(1)`) is a COMPLETE call that packs an empty
      // list, not a partial one. `callFun` does not exclude it; adding the same hole here to be
      // symmetrical would be copying a bug for the sake of matching.
      if args.nonEmpty && args.length < fn.params.length && fn.usingParams.isEmpty
         && !(lastIsVararg && args.length == fn.params.length - 1) then
        var env2: Map[String, Value] = base
        val pIter = fn.params.iterator; val aIter = args.iterator
        while pIter.hasNext && aIter.hasNext do env2 = FrameMap.one(pIter.next(), aIter.next(), env2)
        var partialStart = -1
        for i <- (args.length until fn.params.length) do
          if partialStart < 0 then
            (if i < fn.defaults.length then fn.defaults(i) else None) match
              case Some(defaultTerm) =>
                val v = Computation.run(interp.eval(defaultTerm, env2))
                env2 = FrameMap.one(fn.params(i), v, env2)
              case None =>
                partialStart = i
        if partialStart >= 0 then
          return Pure(Value.FunV(
            fn.params.drop(partialStart),
            fn.body,
            env2,
            fn.name,
            fn.defaults.drop(partialStart),
            fn.paramTypes.drop(partialStart),
            fn.usingParams,
            fn.returnsThrows
          ))
      val effArgs =
        if lastIsVararg && args.length == fn.params.length - 1 then packVarargs(args)
        else if args.length >= fn.params.length then packVarargs(args)
        else if fn.usingParams.nonEmpty then
          val regularCount = fn.params.length - fn.usingParams.length
          val withUsing =
            if args.length >= regularCount then
              val regularArgVals    = args.take(regularCount)
              val regularParamTypes = fn.paramTypes.take(regularCount)
              val resolved = fn.usingParams.map { (pname, typeKey) =>
                GivenRuntime.resolveUsing(typeKey, regularParamTypes, regularArgVals, base, interp)
                  .getOrElse(interp.located(s"No given instance found for '$typeKey' (using parameter '$pname')"))
              }
              args ++ resolved
            else args
          if withUsing.length >= fn.params.length then withUsing
          else applyDefaults(fn.params, fn.defaults, withUsing, base, interp)
        else applyDefaults(fn.params, fn.defaults, args, base, interp)
      val callEnv: Env = fn.params match
        case Nil               => base
        case p :: Nil          => FrameMap.one(p, effArgs.head, base)
        case p1 :: p2 :: Nil   => FrameMap.two(p1, effArgs.head, p2, effArgs(1), base)
        case ps                =>
          var names = interp.paramsArrayCache.get(fn.body)
          if names == null then { names = ps.toArray; interp.paramsArrayCache.put(fn.body, names) }
          val arr = effArgs.iterator.take(names.length).toArray
          FrameMap.of(names, arr, base)
      val frameName  = if fn.name.nonEmpty then fn.name else "<anon>"
      val relLine    = if interp.currentSpanLine >= 0 then interp.currentSpanLine + 1 else 0
      val absDocLine = interp.debugBlockDocLine + relLine
      interp.callStackPush(frameName, interp.debugSourceFile, absDocLine)
      val profiling = Profiler.enabled
      val t0 = if profiling && fn.name.nonEmpty then System.nanoTime() else 0L
      val result =
        try TcoRuntime.runUntilSuspension(interp.eval(fn.body, callEnv))
        catch case r: ReturnSignal => Pure(r.value)
      if profiling && fn.name.nonEmpty then Profiler.record(fn.name, System.nanoTime() - t0)
      if interp.callStackNonEmpty then interp.callStackPop()
      if fn.returnsThrows then result.map(throwsAutoWrap)
      else result

  /** 1-arg fast path for callTypeMethod: avoids `arg :: Nil` allocation when the method
   *  is a simple non-TCO, 1-param, no-defaults, no-using function. */
  def callTypeMethod1(fn: Value.FunV, fields: Map[String, Value], arg: Value, interp: Interpreter): Computation =
    // Bail to full path for rare cases: TCO, defaults, using params, vararg, throws
    val simple = fn.params.lengthCompare(1) == 0 &&
      fn.usingParams.isEmpty &&
      !fn.returnsThrows &&
      (fn.defaults.isEmpty || fn.defaults.head.isEmpty) &&
      (fn.paramTypes.isEmpty || !fn.paramTypes.head.endsWith("*"))
    if !simple then return callTypeMethod(fn, fields, arg :: Nil, interp)

    val info = TcoRuntime.tcoInfoFor(fn, interp)
    val base: Map[String, Value] =
      if fn.name.isEmpty || !info.hasSelfNameRef then FrameMap.fromMap(fields, fn.closure)
      else
        val selfRef = Value.NativeFnV(fn.name, recArgs => callTypeMethod(fn, fields, recArgs, interp))
        FrameMap.fromMapWithSelf(fields, fn.name, selfRef, fn.closure)
    val hasMutualTail = info.tailTargets.nonEmpty && info.tailTargets.exists { n =>
      val gv = interp.globals.getOrElse(n, null)
      val v  = if gv != null then gv else base.getOrElse(n, null)
      v != null && v.isInstanceOf[Value.FunV]
    }
    if info.noNonTailSelf && (info.isSelfTailRec || hasMutualTail) then
      if Profiler.enabled && fn.name.nonEmpty then Profiler.record(fn.name, 0L)
      TcoRuntime.tcoTrampoline(fn.copy(closure = base), arg :: Nil, null, interp)
    else
      val callEnv   = FrameMap.one(fn.params.head, arg, base)
      val frameName = if fn.name.nonEmpty then fn.name else "<anon>"
      val relLine   = if interp.currentSpanLine >= 0 then interp.currentSpanLine + 1 else 0
      interp.callStackPush(frameName, interp.debugSourceFile, interp.debugBlockDocLine + relLine)
      val profiling = Profiler.enabled
      val t0 = if profiling && fn.name.nonEmpty then System.nanoTime() else 0L
      val result =
        try TcoRuntime.runUntilSuspension(interp.eval(fn.body, callEnv))
        catch case r: ReturnSignal => Pure(r.value)
      if profiling && fn.name.nonEmpty then Profiler.record(fn.name, System.nanoTime() - t0)
      if interp.callStackNonEmpty then interp.callStackPop()
      result

  private def throwsAutoWrap(v: Value): Value = v match
    case Value.InstanceV("Left",  _) => v
    case Value.InstanceV("Right", _) => v
    case other => Value.InstanceV("Right", new IMap.Map1("value", other))

  def typeToString(t: scala.meta.Type): String = t match
    case scala.meta.Type.Name(n)         => n
    case scala.meta.Type.Select(qual, name) =>
      def refToString(r: scala.meta.Term.Ref): String = r match
        case scala.meta.Term.Name(n) => n
        case scala.meta.Term.Select(q: scala.meta.Term.Ref, n) => s"${refToString(q)}.${n.value}"
        case scala.meta.Term.Select(q, n) => s"${q.syntax}.${n.value}"
        case _ => r.syntax
      s"${refToString(qual)}.${name.value}"
    case ta: scala.meta.Type.Apply       => s"${typeToString(ta.tpe)}[${ta.argClause.values.map(typeToString).mkString(", ")}]"
    case scala.meta.Type.Function.After_4_6_0(params, r) => s"(${params.values.map(typeToString).mkString(", ")}) => ${typeToString(r)}"
    case scala.meta.Type.Tuple(ts)       => ts.map(typeToString).mkString("(", ", ", ")")
    case ti: scala.meta.Type.ApplyInfix  => s"${typeToString(ti.lhs)} ${ti.op.value} ${typeToString(ti.rhs)}"
    case tr: scala.meta.Type.Repeated    => s"${typeToString(tr.tpe)}*"
    case _                               => "_"

  def constValueOfType(tp: scala.meta.Type): Value = tp match
    case scala.meta.Lit.Int(n)      => Value.intV(n.toLong)
    case scala.meta.Lit.String(s)   => Value.StringV(s)
    case scala.meta.Lit.Boolean(b)  => Value.boolV(b)
    case scala.meta.Lit.Double(d)   => Value.doubleV(d.toDouble)
    case scala.meta.Lit.Long(l)     => Value.intV(l)
    case scala.meta.Type.Name(n)    => Value.StringV(n)
    case _                          => Value.StringV(tp.syntax)

  def isThrowsType(t: scala.meta.Type): Boolean = t match
    case ti: scala.meta.Type.ApplyInfix => ti.op.value == "throws"
    case _                              => false

  def applyDefaults(
    params:   List[String],
    defaults: List[Option[Term]],
    args:     List[Value],
    baseEnv:  Env,
    interp:   Interpreter
  ): List[Value] =
    if args.length >= params.length then args
    else
      val provided = args
      var env: Map[String, Value] = baseEnv
      val paramIter    = params.iterator
      val argIter      = provided.iterator
      val defaultIter  = defaults.iterator
      // Advance past provided args, building env as we go
      while paramIter.hasNext && argIter.hasNext do
        val p = paramIter.next(); val a = argIter.next()
        env = FrameMap.one(p, a, env)
        if defaultIter.hasNext then defaultIter.next() // skip defaults for provided params
      // Fill remaining params from defaults
      val filled = scala.collection.mutable.ArrayBuffer.empty[Value]
      while paramIter.hasNext do
        val pname      = paramIter.next()
        val defaultOpt = if defaultIter.hasNext then defaultIter.next() else None
        defaultOpt match
          case Some(defaultTerm) =>
            val v = Computation.run(interp.eval(defaultTerm, env))
            env = FrameMap.one(pname, v, env)
            filled += v
          case None =>
            interp.located(s"missing argument for parameter '$pname'")
      provided ++ filled.toList
