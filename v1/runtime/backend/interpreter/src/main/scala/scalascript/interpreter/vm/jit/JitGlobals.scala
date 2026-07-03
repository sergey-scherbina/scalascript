package scalascript.interpreter.vm.jit

import scalascript.interpreter.{Interpreter, Value}

/** Per-thread interpreter handle for the free-name globals read path.
 *  `JitRuntime` sets this on each MH invocation; the generated Java code
 *  calls back into `readGlobalLong(name)` to resolve a free `Int` global
 *  by name. The lookup adds one HashMap miss + a type cast per read, but
 *  the rest of the function body still benefits from the bytecode-JIT'd
 *  hot path. */
object JitGlobals:

  private val interpTls:       ThreadLocal[Interpreter]        = new ThreadLocal[Interpreter]()
  private val refsTls:         ThreadLocal[Array[AnyRef]]      = new ThreadLocal[Array[AnyRef]]()
  private val refFnsTls:       ThreadLocal[Array[ObjToLong]]   = new ThreadLocal[Array[ObjToLong]]()
  private val refObjFnsTls:    ThreadLocal[Array[ObjToObject]] = new ThreadLocal[Array[ObjToObject]]()
  private val refDoubleFnsTls: ThreadLocal[Array[ObjToDouble]] = new ThreadLocal[Array[ObjToDouble]]()

  def withInterp[A](interp: Interpreter)(thunk: => A): A =
    val prev = interpTls.get()
    interpTls.set(interp)
    // Restore via `set(prev)` rather than `remove()` even when `prev == null`:
    // `remove()` deletes the per-thread ThreadLocalMap.Entry, and the next
    // outer call's `set(interp)` then re-allocates it. JFR profiling on
    // `recursiveEval` showed ~10 MB/op of `ThreadLocalMap$Entry` allocations
    // on this exact path — one Entry per outer bytecode-JIT invocation, of
    // which there are millions per script. Setting the slot to `null` leaves
    // the Entry intact with a null value; `readGlobalLong/Double` already
    // check for `interp == null`, so semantics are preserved. Per-thread
    // memory cost: ~32 bytes that never shrink — negligible.
    try thunk
    finally interpTls.set(prev)

  /** Set per-invocation ref arrays for a `WhileJitEntry` that uses InstanceV
   *  arguments.  The generated `run(long[])` method calls `getRefs()` /
   *  `getRefFns()` / `getRefObjFns()` to read the Object, ObjToLong, and
   *  ObjToObject slots; the caller wraps `method.invoke` in this block.
   *  Uses the same `set(prev)` restore pattern as `withInterp` to avoid
   *  ThreadLocalMap.Entry churn.
   *
   *  The 4-arg overload also sets `refDoubleFns` for `tryCompileWhileMixed`
   *  (fused while + foreach with a Double accumulator). */
  def withRefs[A](refs: Array[AnyRef], fns: Array[ObjToLong], objFns: Array[ObjToObject])(thunk: => A): A =
    val prevR  = refsTls.get()
    val prevF  = refFnsTls.get()
    val prevOF = refObjFnsTls.get()
    refsTls.set(refs)
    refFnsTls.set(fns)
    refObjFnsTls.set(objFns)
    try thunk
    finally
      refsTls.set(prevR)
      refFnsTls.set(prevF)
      refObjFnsTls.set(prevOF)

  def withRefs[A](refs: Array[AnyRef], fns: Array[ObjToLong], objFns: Array[ObjToObject],
                  doubleFns: Array[ObjToDouble])(thunk: => A): A =
    val prevR  = refsTls.get()
    val prevF  = refFnsTls.get()
    val prevOF = refObjFnsTls.get()
    val prevDF = refDoubleFnsTls.get()
    refsTls.set(refs)
    refFnsTls.set(fns)
    refObjFnsTls.set(objFns)
    refDoubleFnsTls.set(doubleFns)
    try thunk
    finally
      refsTls.set(prevR)
      refFnsTls.set(prevF)
      refObjFnsTls.set(prevOF)
      refDoubleFnsTls.set(prevDF)

  def getInterp(): Interpreter              = interpTls.get()
  def getRefs(): Array[AnyRef]              = refsTls.get()
  def getRefFns(): Array[ObjToLong]         = refFnsTls.get()
  def getRefObjFns(): Array[ObjToObject]    = refObjFnsTls.get()
  def getRefDoubleFns(): Array[ObjToDouble] = refDoubleFnsTls.get()

  /** Called by generated Java code: read a top-level `Int` global by name and
   *  return its `Long` value. Throws `RuntimeException` if the name is
   *  missing or not an `IntV` — caller's MH invocation catches and falls
   *  back to the SscVm.exec / tree-walk path. */
  /** Runtime subtype test for JIT-compiled `case _: T` arms: true if `typeName`
   *  is `target` or has it as an ancestor in the current interpreter's
   *  `parentTypes` chain. Lets a JIT'd match narrow by a (possibly imported)
   *  supertype without expanding subtype arms at compile time (busi seq-124). */
  def isSubtype(typeName: String, target: String): Boolean =
    if typeName == target then true
    else
      val interp = interpTls.get()
      if interp == null then false
      else
        var p = interp.parentTypes.getOrElse(typeName, null)
        var found = false
        while p != null && !found do
          if p == target then found = true
          else p = interp.parentTypes.getOrElse(p, null)
        found

  def readGlobalLong(name: String): Long =
    val interp = interpTls.get()
    if interp == null then throw new RuntimeException("JitGlobals.readGlobalLong: no interp in TLS")
    val v = interp.globals.getOrElse(name, null)
    v match
      case Value.IntV(x) => x
      case _             => throw new RuntimeException(s"JitGlobals.readGlobalLong: '$name' not an Int")

  /** Double-globals parallel of `readGlobalLong`. Resolves to `DoubleV` only;
   *  an `IntV` value at runtime throws and the wrapping MH invocation falls
   *  back to the SscVm.exec / tree-walk path. The compile-time gate in
   *  `walkDouble` only emits this call when the current `interp.globals`
   *  resolves the name to `DoubleV`, so the runtime mismatch only occurs if
   *  the global is reassigned to a non-Double value between compile time and
   *  call time — a rare shape; safer to bail than to silently widen IntV. */
  def readGlobalDouble(name: String): Double =
    val interp = interpTls.get()
    if interp == null then throw new RuntimeException("JitGlobals.readGlobalDouble: no interp in TLS")
    val v = interp.globals.getOrElse(name, null)
    v match
      case Value.DoubleV(x) => x
      case _                => throw new RuntimeException(s"JitGlobals.readGlobalDouble: '$name' not a Double")

  /** Read a top-level ref-valued global by name. Used by generated HOF-chain
   *  code for immutable List/Option/Either receivers such as `val xs = List(...)`.
   *  Unsupported or mutated shapes throw so the wrapping JIT invocation can
   *  fall back to the interpreter. */
  def readGlobalRef(name: String): AnyRef =
    val interp = interpTls.get()
    if interp == null then throw new RuntimeException("JitGlobals.readGlobalRef: no interp in TLS")
    val v = interp.globals.getOrElse(name, null)
    if v == null then throw new RuntimeException(s"JitGlobals.readGlobalRef: '$name' is missing")
    v.asInstanceOf[AnyRef]

  /** Call a 1-param Long-returning global function by name.
   *  Used by generated bytecode when a callee can't be co-emitted into the
   *  caller's class.  Calls through `Interpreter.invoke`, which tries JIT
   *  first then the tree-walker.  Only supports pure (non-effectful) callees
   *  that return `IntV`; a type mismatch throws, causing the outer JIT frame
   *  to fall back to the tree-walker. */
  def callGlobalLong1(name: String, a1: Long): Long =
    val interp = interpTls.get()
    if interp == null then throw new RuntimeException("JitGlobals.callGlobalLong1: no interp in TLS")
    interp.globals.getOrElse(name, null) match
      case fn: Value.FunV =>
        interp.invoke(fn, List(Value.intV(a1))) match
          case Value.IntV(v) => v
          case other         => throw new RuntimeException(s"JitGlobals.callGlobalLong1: '$name' returned ${other.getClass.getSimpleName}")
      case _ => throw new RuntimeException(s"JitGlobals.callGlobalLong1: '$name' is not a FunV")

  /** 2-param variant of [[callGlobalLong1]]. */
  def callGlobalLong2(name: String, a1: Long, a2: Long): Long =
    val interp = interpTls.get()
    if interp == null then throw new RuntimeException("JitGlobals.callGlobalLong2: no interp in TLS")
    interp.globals.getOrElse(name, null) match
      case fn: Value.FunV =>
        interp.invoke(fn, List(Value.intV(a1), Value.intV(a2))) match
          case Value.IntV(v) => v
          case other         => throw new RuntimeException(s"JitGlobals.callGlobalLong2: '$name' returned ${other.getClass.getSimpleName}")
      case _ => throw new RuntimeException(s"JitGlobals.callGlobalLong2: '$name' is not a FunV")

  def callGlobalLong3(name: String, a1: Long, a2: Long, a3: Long): Long =
    val interp = interpTls.get()
    if interp == null then throw new RuntimeException("JitGlobals.callGlobalLong3: no interp in TLS")
    interp.globals.getOrElse(name, null) match
      case fn: Value.FunV =>
        interp.invoke(fn, List(Value.intV(a1), Value.intV(a2), Value.intV(a3))) match
          case Value.IntV(v) => v
          case other         => throw new RuntimeException(s"JitGlobals.callGlobalLong3: '$name' returned ${other.getClass.getSimpleName}")
      case _ => throw new RuntimeException(s"JitGlobals.callGlobalLong3: '$name' is not a FunV")

  /** effect-vm-continuations (spec): a compiled effectful loop body calls this for a 0-arg
   *  effect op `Eff.op()` that a one-shot tail-resume handler resolves. Reads the innermost
   *  resolver (installed by `EffectsRuntime.evalHandle`) and returns its value as a Long.
   *  Throws if no resolver is in scope — the `tryWhileJit` run is wrapped in try/catch and
   *  writes its slots back ONLY on success, so the loop bails cleanly to tree-walk with no
   *  partial side effect. The op stays handled through the handler each iteration. */
  def resolveEffectLong(eff: String, op: String): Long =
    resolveEffectValue(eff, op, Nil)

  /** 1-/2-arg numeric variants (effect-vm-continuations P2b): an arg-carrying effect op
   *  `Eff.op(a)` / `Eff.op(a, b)` whose args are numeric, resolved by a one-shot tail-resume
   *  handler. The args are passed to the resolver (which binds the op-arg patterns). */
  def resolveEffectLong1(eff: String, op: String, a1: Long): Long =
    resolveEffectValue(eff, op, List(Value.intV(a1)))

  def resolveEffectLong2(eff: String, op: String, a1: Long, a2: Long): Long =
    resolveEffectValue(eff, op, List(Value.intV(a1), Value.intV(a2)))

  private def resolveEffectValue(eff: String, op: String, args: List[Value]): Long =
    scalascript.interpreter.EffectsRuntime.lookupResolver(eff, op) match
      case null => throw new RuntimeException(s"resolveEffectLong: no resolver for $eff.$op")
      case r =>
        r(args) match
          case Value.IntV(v)  => v
          case Value.BoolV(b) => if b then 1L else 0L
          case other          => throw new RuntimeException(s"resolveEffectLong: $eff.$op resolved to ${other.getClass.getSimpleName}")

  /** Stage 8: ref-typed arg, Long return — used for callees with `using` params
   *  (typeclass dispatch) or any ref param shape. `interp.invoke` handles given
   *  resolution and other tree-walker semantics automatically. */
  def callGlobalLong1Ref(name: String, a1: Object): Long =
    val interp = interpTls.get()
    if interp == null then throw new RuntimeException("JitGlobals.callGlobalLong1Ref: no interp in TLS")
    interp.globals.getOrElse(name, null) match
      case fn: Value.FunV =>
        interp.invoke(fn, List(a1.asInstanceOf[Value])) match
          case Value.IntV(v) => v
          case other         => throw new RuntimeException(s"JitGlobals.callGlobalLong1Ref: '$name' returned ${other.getClass.getSimpleName}")
      case _ => throw new RuntimeException(s"JitGlobals.callGlobalLong1Ref: '$name' is not a FunV")

  /** Stage 8: 1-ref-arg, ref-returning global call. */
  def callGlobalRef1Ref(name: String, a1: Object): Object =
    val interp = interpTls.get()
    if interp == null then throw new RuntimeException("JitGlobals.callGlobalRef1Ref: no interp in TLS")
    interp.globals.getOrElse(name, null) match
      case fn: Value.FunV => interp.invoke(fn, List(a1.asInstanceOf[Value])).asInstanceOf[Object]
      case _ => throw new RuntimeException(s"JitGlobals.callGlobalRef1Ref: '$name' is not a FunV")

  /** Stage 8: generic global call — accepts an Object[] of args (each wrapped
   *  Value for ref params, or boxed Long for numeric). Returns Long (unwrapped
   *  from IntV). Used for arity 2/3 with mixed ref/long params or with
   *  `using` clauses. */
  def callGlobalLongAny(name: String, args: Array[Object]): Long =
    val interp = interpTls.get()
    if interp == null then throw new RuntimeException("JitGlobals.callGlobalLongAny: no interp in TLS")
    interp.globals.getOrElse(name, null) match
      case fn: Value.FunV =>
        val argList = args.iterator.map { o =>
          o match
            case v: Value => v
            case l: java.lang.Long => Value.intV(l.longValue)
            case _ => o.asInstanceOf[Value]
        }.toList
        interp.invoke(fn, argList) match
          case Value.IntV(v) => v
          case other         => throw new RuntimeException(s"JitGlobals.callGlobalLongAny: '$name' returned ${other.getClass.getSimpleName}")
      case _ => throw new RuntimeException(s"JitGlobals.callGlobalLongAny: '$name' is not a FunV")

  /** Stage 8: generic global call returning ref. */
  def callGlobalRefAny(name: String, args: Array[Object]): Object =
    val interp = interpTls.get()
    if interp == null then throw new RuntimeException("JitGlobals.callGlobalRefAny: no interp in TLS")
    interp.globals.getOrElse(name, null) match
      case fn: Value.FunV =>
        val argList = args.iterator.map { o =>
          o match
            case v: Value => v
            case l: java.lang.Long => Value.intV(l.longValue)
            case _ => o.asInstanceOf[Value]
        }.toList
        interp.invoke(fn, argList).asInstanceOf[Object]
      case _ => throw new RuntimeException(s"JitGlobals.callGlobalRefAny: '$name' is not a FunV")
