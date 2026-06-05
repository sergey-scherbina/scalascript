package scalascript.interpreter.vm

import scalascript.interpreter.{Value, Computation, Interpreter}
import scalascript.interpreter.vm.jit.{JitBackend, JitResult, JitGlobals, LongFn0, DoubleFn0, LongFn1, DoubleFn1, ObjToLong, ObjToDouble, ObjToObject, LongToObject, LongFn2, DoubleFn2, LongObjToLong, LongObjToDouble, ObjLongToLong, ObjLongToDouble, ObjObjToLong, ObjObjToDouble, LongFn3, DoubleFn3, ObjLongLongToLong, LongObjLongToLong, LongLongObjToLong, ObjObjLongToLong, ObjLongObjToLong, LongObjObjToLong, ObjObjObjToLong, ObjLongLongToDouble, LongObjLongToDouble, LongLongObjToDouble, ObjObjLongToDouble, ObjLongObjToDouble, LongObjObjToDouble, ObjObjObjToDouble}
import java.lang as jl

/** Run-time JIT bridge between the tree-walking interpreter and [[SscVm]].
 *
 *  A named function that is called repeatedly (>= [[threshold]] times) and
 *  whose body lies in the compilable integer subset (see [[VmCompiler]]) is
 *  compiled once to register bytecode and thereafter executed on the VM.
 *
 *  Safety contract — this layer can NEVER change observable semantics:
 *    - Only the pure integer subset compiles; anything touching effects,
 *      native calls, or non-Int values makes [[VmCompiler]] bail (returns
 *      None) and we mark the entry permanently `disabled`.
 *    - Every VM execution is wrapped: a [[SscVm.FrameOverflow]] grows the
 *      thread-local stack and retries; ANY other Throwable falls back to the
 *      tree-walk path, which recomputes the same value (the compiled subset
 *      is side-effect-free, so re-running is observationally identical).
 *    - The cache is keyed by FunV *identity* (IdentityHashMap), not structural
 *      equality — FunV is an AST-bearing enum case whose structural equality
 *      would be expensive and collision-prone.
 *    - The VM stack is thread-local, so concurrent actor calls cannot corrupt
 *      one another's frames.
 *
 *  Disable entirely with `SSC_JIT=off`; tune the warm-up count with
 *  `SSC_JIT_THRESHOLD=<n>`.
 */
object JitRuntime:

  val enabled: Boolean =
    !sys.env.get("SSC_JIT").map(_.toLowerCase).contains("off")

  val threshold: Int =
    sys.env.get("SSC_JIT_THRESHOLD").flatMap(_.toIntOption).filter(_ > 0).getOrElse(8)

  /** Per-closure JIT state. Mutated only under the cache monitor (for the
   *  compile transition) plus a benign racy `calls` increment — `calls` is a
   *  monotone counter whose only effect is crossing `threshold`, and a lost
   *  update merely delays compilation by one call, never corrupts state.
   *
   *  `bytecodeMh` is the Phase C bytecode-jit method handle (Java source →
   *  `javax.tools.JavaCompiler` → in-memory class → `MethodHandle`). Populated
   *  on demand when `SSC_JIT_BYTECODE=on` AND the body is in `BytecodeJit`'s
   *  pure-int subset. Tried before `SscVm.exec` so the dispatch loop overhead
   *  is skipped entirely when present. */
  final class Entry:
    @volatile var compiled: SscVm.CompiledFn | Null = null
    @volatile var disabled: Boolean = false
    @volatile var bytecode: JitResult | Null = null
    @volatile var bytecodeTried: Boolean = false
    var calls: Int = 0

  /** Identity-keyed cache. Synchronized: insertions are rare (once per
   *  distinct closure) and reads after warm-up hit the volatile `compiled`
   *  field directly without entering this map. */
  private val cache = new java.util.IdentityHashMap[Value.FunV, Entry]()

  private def entryFor(f: Value.FunV): Entry =
    cache.synchronized:
      var e = cache.get(f)
      if e == null then { e = new Entry; cache.put(f, e) }
      e

  /** Return the compiled function for `f`, or null if it is not (yet, or ever)
   *  eligible. Increments the call counter and triggers compilation once the
   *  warm-up threshold is reached. When `eager`, compile on the first call —
   *  used for self-tail-recursive functions, whose single `callFun` entry
   *  already represents the whole hot loop (the VM compiles TCO to a loop). */
  private def hotCompiled(f: Value.FunV, interp: Interpreter, eager: Boolean): SscVm.CompiledFn | Null =
    if interp.debugHooks.nonEmpty then return null
    val e = entryFor(f)
    val c = e.compiled
    if c != null then return c
    if e.disabled then return null
    e.calls += 1
    if !eager && e.calls < threshold then null
    else
      cache.synchronized:
        if e.compiled != null then e.compiled
        else if e.disabled then null
        else
          VmCompiler.compile(f, resolverFor(interp), metaFor(interp)) match
            case Some(cf) => e.compiled = cf; cf
            case None     => e.disabled = true; null

  /** Build a [[VmCompiler.Resolve]] that maps a free name in `owner`'s body to
   *  the [[Value.FunV]] it refers to: first the lexical closure, then the
   *  interpreter globals. Returns null for anything that is not a function
   *  (the compiler then bails on that call site and falls back to tree-walk). */
  private def resolverFor(interp: Interpreter): VmCompiler.Resolve =
    (owner: Value.FunV, name: String) =>
      owner.closure.get(name) match
        case Some(fv: Value.FunV) => fv
        case _ =>
          interp.globals.get(name) match
            case Some(fv: Value.FunV) => fv
            case _                    => null

  // Built-in type meta for String: length/isEmpty/nonEmpty → Int/Boolean/Boolean.
  // These are zero-arg Scala val-like accessors; the VM emits GETFI for them.
  private val StringMeta: (List[String], List[String]) =
    (List("length", "isEmpty", "nonEmpty"), List("Int", "Boolean", "Boolean"))

  /** Build an ADT-constructor metadata lookup from the interpreter's recorded
   *  field order + field types, for `match` field extraction in [[VmCompiler]]. */
  private def metaFor(interp: Interpreter): VmCompiler.Meta =
    (ctor: String) =>
      if ctor == "String" then StringMeta
      else interp.typeFieldOrder.get(ctor) match
        case Some(names) =>
          val types = interp.typeFieldTypes.getOrElse(ctor, names.map(_ => "String"))
          (names, types)
        case None => null

  // ── thread-local growable frame stack ────────────────────────────
  // Dual-bank: a `Long` stack for numeric registers and a parallel `AnyRef`
  // stack for ref registers (VM 2a). Both banks are kept the same length so the
  // VM's single bounds check (on the Long stack) covers both.
  private val tlStack = new ThreadLocal[Array[Long]]:
    override def initialValue(): Array[Long] = new Array[Long](64 * 1024)
  private val tlRefStack = new ThreadLocal[Array[AnyRef]]:
    override def initialValue(): Array[AnyRef] = new Array[AnyRef](64 * 1024)

  private val NoArgs = new Array[Long](0)
  private val NoRefs = new Array[AnyRef](0)

  /** Run `cf` with numeric `args` (placed in the Long bank) and `refArgs`
   *  (placed in the ref bank) on the thread-local stacks, growing on overflow.
   *  Returns the Long result; propagates non-overflow throwables to the caller
   *  (which treats them as "fall back to tree-walk"). */
  private def runVm(cf: SscVm.CompiledFn, args: Array[Long], refArgs: Array[AnyRef]): Long =
    while true do
      var stack = tlStack.get()
      var refs  = tlRefStack.get()
      val need  = cf.numRegs.max(1) + cf.arity + 16
      if stack.length < need then { stack = new Array[Long](need * 2); tlStack.set(stack) }
      if refs.length < stack.length then { refs = new Array[AnyRef](stack.length); tlRefStack.set(refs) }
      var i = 0
      while i < args.length do { stack(i) = args(i); i += 1 }
      i = 0
      while i < refArgs.length do { refs(i) = refArgs(i); i += 1 }
      try return SscVm.exec(cf, stack, refs, 0)
      catch case fo: SscVm.FrameOverflow =>
        val sz = fo.need * 2 + 16
        tlStack.set(new Array[Long](sz)); tlRefStack.set(new Array[AnyRef](sz))
    0L // unreachable

  private def isNumeric(v: Value): Boolean = v match
    case _: Value.IntV | _: Value.DoubleV => true
    case _                                => false

  /** Marshal one Value into the raw `Long` the VM register expects, given whether
   *  that param is double-typed. Returns null on a type the VM can't represent in
   *  that domain (e.g. a Double passed to an int param). */
  private def marshal(v: Value, wantDouble: Boolean): jl.Long | Null = v match
    case Value.IntV(x)    => if wantDouble then jl.Double.doubleToRawLongBits(x.toDouble) else x
    case Value.DoubleV(d) => if wantDouble then jl.Double.doubleToRawLongBits(d) else null
    case _                => null

  /** Wrap a raw VM result in the right Value, per the compiled return domain. */
  private def wrap(cf: SscVm.CompiledFn, raw: Long): Computation =
    if cf.retIsDouble then Computation.Pure(Value.doubleV(jl.Double.longBitsToDouble(raw)))
    else if cf.retIsBool then Computation.pureBool(raw != 0L)
    else Computation.pureIntV(raw)

  private def isRefParam(cf: SscVm.CompiledFn, i: Int): Boolean =
    i < cf.paramIsRef.length && cf.paramIsRef(i)

  /** Phase C bytecode JIT lookup: resolves `Entry.bytecode` lazily (compile is
   *  tried at most once per FunV; the underlying `BytecodeJit.tryCompile`
   *  caches by body AST so a rebuilt FunV with the same body skips compile).
   *  Returns the `Result` (handle + paramIsRef) or null. */
  private def bytecodeFor(f: Value.FunV, interp: Interpreter): JitResult | Null =
    if !JitBackend.default.enabled then return null
    if interp.debugHooks.nonEmpty then return null
    val e = entryFor(f)
    val r = e.bytecode
    if r != null then return r
    if e.bytecodeTried then return null
    cache.synchronized:
      if !e.bytecodeTried then
        e.bytecode     = JitBackend.default.tryCompile(f, interp)
        e.bytecodeTried = true
    e.bytecode

  /** Wrap a FunV as a `LongFn1` or `LongFn2` adapter for HOF params.
   *  The adapter calls `interp.invoke` (which tries JIT first), so a
   *  compilable HOF callback gets the fast path on subsequent calls. */
  private def wrapFunVAsLong(fn: Value.FunV, interp: Interpreter): AnyRef | Null =
    fn.params.length match
      case 1 =>
        new LongFn1:
          def apply(n: Long): Long =
            interp.invoke(fn, List(Value.intV(n))) match
              case Value.IntV(x)  => x
              case Value.BoolV(b) => if b then 1L else 0L
              case _ => throw new RuntimeException("HOF returned non-Int (expected LongFn1)")
      case 2 =>
        new LongFn2:
          def apply(a: Long, b: Long): Long =
            interp.invoke(fn, List(Value.intV(a), Value.intV(b))) match
              case Value.IntV(x)  => x
              case Value.BoolV(b2) => if b2 then 1L else 0L
              case _ => throw new RuntimeException("HOF returned non-Int (expected LongFn2)")
      case _ => null  // arity 0 or 3+ not yet supported in HOF dispatch

  /** Marshal one Value into the Java arg the bytecode method expects.
   *  Ref param → the value as `Object` (must be `InstanceV` or `FunV`; bails
   *  otherwise). Numeric param: when `resultIsDouble` is true the slots are
   *  `double` and accept `IntV` (widened) or `DoubleV`; when false they
   *  accept only `IntV`. */
  private def marshalBytecode(v: Value, isRef: Boolean, isDouble: Boolean, interp: Interpreter): AnyRef | Null =
    if isRef then
      v match
        case _: Value.InstanceV | _: Value.StringV | _: Value.MapV => v.asInstanceOf[AnyRef]
        case fn: Value.FunV if interp != null => wrapFunVAsLong(fn, interp)
        case _                  => null
    else if isDouble then
      v match
        case Value.DoubleV(x) => jl.Double.valueOf(x)
        case Value.IntV(x)    => jl.Double.valueOf(x.toDouble)
        case _                => null
    else
      v match
        case Value.IntV(x) => jl.Long.valueOf(x)
        case _             => null

  private def wrapLong(r: JitResult, raw: Long): Computation =
    if r.resultIsBool then Computation.pureBool(raw != 0L)
    else Computation.pureIntV(raw)

  private def wrapBytecodeResult(r: JitResult, out: AnyRef): Computation =
    if r.resultIsRef then Computation.Pure(out.asInstanceOf[Value])
    else if r.resultIsDouble then Computation.Pure(Value.doubleV(out.asInstanceOf[jl.Double].doubleValue))
    else if r.resultIsBool then Computation.pureBool(out.asInstanceOf[jl.Long].longValue != 0L)
    else Computation.pureIntV(out.asInstanceOf[jl.Long].longValue)

  /** 0-arg bytecode dispatch — for compiled `def workload(): Long` thunks. */
  private def invokeBytecode0(r: JitResult, interp: Interpreter): Computation | Null =
    val d = r.direct
    if d != null then
      if r.resultIsRef then return null
      try
        if r.resultIsDouble then
          val result = JitGlobals.withInterp(interp) { d.asInstanceOf[DoubleFn0].apply() }
          Computation.Pure(Value.doubleV(result))
        else
          val result = JitGlobals.withInterp(interp) { d.asInstanceOf[LongFn0].apply() }
          wrapLong(r, result)
      catch case _: Throwable => null
    else
      val out =
        try JitGlobals.withInterp(interp) { r.mh.invoke().asInstanceOf[AnyRef] }
        catch case _: Throwable => return null
      wrapBytecodeResult(r, out)

  private def invokeBytecode1(r: JitResult, arg: Value, interp: Interpreter): Computation | Null =
    val d = r.direct
    if d != null then
      try
        if !r.paramIsRef(0) then
          if r.resultIsRef || d.isInstanceOf[LongToObject] then
            val n = arg match
              case Value.IntV(x) => x
              case _             => return null
            val result = JitGlobals.withInterp(interp) { d.asInstanceOf[LongToObject].apply(n) }
            Computation.Pure(result.asInstanceOf[Value])
          else if r.resultIsDouble then
            val n = arg match
              case Value.IntV(x)    => x.toDouble
              case Value.DoubleV(x) => x
              case _                => return null
            val result = JitGlobals.withInterp(interp) { d.asInstanceOf[DoubleFn1].apply(n) }
            Computation.Pure(Value.doubleV(result))
          else
            val n = arg match
              case Value.IntV(x) => x
              case _             => return null
            val result = JitGlobals.withInterp(interp) { d.asInstanceOf[LongFn1].apply(n) }
            wrapLong(r, result)
        else
          val argRef: AnyRef | Null = arg match
            case _: Value.InstanceV | _: Value.StringV | _: Value.MapV => arg.asInstanceOf[AnyRef]
            case fn: Value.FunV => wrapFunVAsLong(fn, interp)
            case _              => null
          if argRef == null then null
          else if r.resultIsRef || d.isInstanceOf[ObjToObject] then
            val result = JitGlobals.withInterp(interp) { d.asInstanceOf[ObjToObject].apply(argRef) }
            Computation.Pure(result.asInstanceOf[Value])
          else if r.resultIsDouble then
            val result = JitGlobals.withInterp(interp) { d.asInstanceOf[ObjToDouble].apply(argRef) }
            Computation.Pure(Value.doubleV(result))
          else
            val result = JitGlobals.withInterp(interp) { d.asInstanceOf[ObjToLong].apply(argRef) }
            wrapLong(r, result)
      catch case _: Throwable => null
    else
      val a0 = marshalBytecode(arg, r.paramIsRef(0), r.resultIsDouble, interp)
      if a0 == null then return null
      val out =
        try JitGlobals.withInterp(interp) { r.mh.invoke(a0).asInstanceOf[AnyRef] }
        catch case _: Throwable => return null
      wrapBytecodeResult(r, out)

  private def invokeBytecode2(r: JitResult, a: Value, b: Value, interp: Interpreter): Computation | Null =
    val d = r.direct
    if d != null then
      if r.resultIsRef then return null
      // Unboxed dispatch for every (paramIsRef, paramIsRef) × resultIsDouble
      // combination JavacJitBackend.determineInterface emits. The mixed
      // Long+Ref interfaces (added 2026-06-03) unblock the
      // `recursiveEvalMixed` shape `gEval(scale: Int, e: Expr): Int` which
      // previously fell through to the MethodHandle path and boxed both args.
      try
        val isDoubleResult = r.resultIsDouble
        val pr0 = r.paramIsRef(0); val pr1 = r.paramIsRef(1)
        (pr0, pr1) match
          case (false, false) =>
            if isDoubleResult then
              val x = a match { case Value.IntV(v) => v.toDouble; case Value.DoubleV(v) => v; case _ => return null }
              val y = b match { case Value.IntV(v) => v.toDouble; case Value.DoubleV(v) => v; case _ => return null }
              val result = JitGlobals.withInterp(interp) { d.asInstanceOf[DoubleFn2].apply(x, y) }
              Computation.Pure(Value.doubleV(result))
            else
              val x = a match { case Value.IntV(v) => v; case _ => return null }
              val y = b match { case Value.IntV(v) => v; case _ => return null }
              val result = JitGlobals.withInterp(interp) { d.asInstanceOf[LongFn2].apply(x, y) }
              wrapLong(r, result)
          case (false, true) =>
            val x = a match { case Value.IntV(v) => v; case _ => return null }
            val yRef: AnyRef | Null = b match
              case _: Value.InstanceV | _: Value.StringV | _: Value.MapV => b.asInstanceOf[AnyRef]
              case fn: Value.FunV => wrapFunVAsLong(fn, interp)
              case _              => null
            if yRef == null then return null
            if isDoubleResult then
              val result = JitGlobals.withInterp(interp) { d.asInstanceOf[LongObjToDouble].apply(x, yRef) }
              Computation.Pure(Value.doubleV(result))
            else
              val result = JitGlobals.withInterp(interp) { d.asInstanceOf[LongObjToLong].apply(x, yRef) }
              wrapLong(r, result)
          case (true, false) =>
            val xRef: AnyRef | Null = a match
              case _: Value.InstanceV | _: Value.StringV | _: Value.MapV => a.asInstanceOf[AnyRef]
              case fn: Value.FunV => wrapFunVAsLong(fn, interp)
              case _              => null
            if xRef == null then return null
            val y = b match { case Value.IntV(v) => v; case _ => return null }
            if isDoubleResult then
              val result = JitGlobals.withInterp(interp) { d.asInstanceOf[ObjLongToDouble].apply(xRef, y) }
              Computation.Pure(Value.doubleV(result))
            else
              val result = JitGlobals.withInterp(interp) { d.asInstanceOf[ObjLongToLong].apply(xRef, y) }
              wrapLong(r, result)
          case (true, true) =>
            val xRef: AnyRef | Null = a match
              case _: Value.InstanceV | _: Value.StringV | _: Value.MapV => a.asInstanceOf[AnyRef]
              case fn: Value.FunV => wrapFunVAsLong(fn, interp)
              case _              => null
            if xRef == null then return null
            val yRef: AnyRef | Null = b match
              case _: Value.InstanceV | _: Value.StringV | _: Value.MapV => b.asInstanceOf[AnyRef]
              case fn: Value.FunV => wrapFunVAsLong(fn, interp)
              case _              => null
            if yRef == null then return null
            if isDoubleResult then
              val result = JitGlobals.withInterp(interp) { d.asInstanceOf[ObjObjToDouble].apply(xRef, yRef) }
              Computation.Pure(Value.doubleV(result))
            else
              val result = JitGlobals.withInterp(interp) { d.asInstanceOf[ObjObjToLong].apply(xRef, yRef) }
              wrapLong(r, result)
      catch case _: Throwable => null
    else
      val a0 = marshalBytecode(a, r.paramIsRef(0), r.resultIsDouble, interp)
      if a0 == null then return null
      val b0 = marshalBytecode(b, r.paramIsRef(1), r.resultIsDouble, interp)
      if b0 == null then return null
      val out =
        try JitGlobals.withInterp(interp) { r.mh.invoke(a0, b0).asInstanceOf[AnyRef] }
        catch case _: Throwable => return null
      wrapBytecodeResult(r, out)

  private def invokeBytecode3(r: JitResult, a: Value, b: Value, c: Value, interp: Interpreter): Computation | Null =
    val d = r.direct
    if d != null then
      if r.resultIsRef then return null
      try
        val isDbl = r.resultIsDouble
        val pr0 = r.paramIsRef(0); val pr1 = r.paramIsRef(1); val pr2 = r.paramIsRef(2)
        def refOf(v: Value): AnyRef | Null = v match
          case _: Value.InstanceV | _: Value.StringV | _: Value.MapV => v.asInstanceOf[AnyRef]
          case fn: Value.FunV => wrapFunVAsLong(fn, interp)
          case _ => null
        (pr0, pr1, pr2) match
          case (false, false, false) =>
            val x = a match { case Value.IntV(v) => v; case _ => return null }
            val y = b match { case Value.IntV(v) => v; case _ => return null }
            val z = c match { case Value.IntV(v) => v; case _ => return null }
            if isDbl then Computation.Pure(Value.doubleV(JitGlobals.withInterp(interp){ d.asInstanceOf[DoubleFn3].apply(x.toDouble, y.toDouble, z.toDouble) }))
            else wrapLong(r, JitGlobals.withInterp(interp){ d.asInstanceOf[LongFn3].apply(x, y, z) })
          case (true, false, false) =>
            val ax = refOf(a); if ax == null then return null
            val y = b match { case Value.IntV(v) => v; case _ => return null }
            val z = c match { case Value.IntV(v) => v; case _ => return null }
            if isDbl then Computation.Pure(Value.doubleV(JitGlobals.withInterp(interp){ d.asInstanceOf[ObjLongLongToDouble].apply(ax, y, z) }))
            else wrapLong(r, JitGlobals.withInterp(interp){ d.asInstanceOf[ObjLongLongToLong].apply(ax, y, z) })
          case (false, true, false) =>
            val x = a match { case Value.IntV(v) => v; case _ => return null }
            val bx = refOf(b); if bx == null then return null
            val z = c match { case Value.IntV(v) => v; case _ => return null }
            if isDbl then Computation.Pure(Value.doubleV(JitGlobals.withInterp(interp){ d.asInstanceOf[LongObjLongToDouble].apply(x, bx, z) }))
            else wrapLong(r, JitGlobals.withInterp(interp){ d.asInstanceOf[LongObjLongToLong].apply(x, bx, z) })
          case (false, false, true) =>
            val x = a match { case Value.IntV(v) => v; case _ => return null }
            val y = b match { case Value.IntV(v) => v; case _ => return null }
            val cx = refOf(c); if cx == null then return null
            if isDbl then Computation.Pure(Value.doubleV(JitGlobals.withInterp(interp){ d.asInstanceOf[LongLongObjToDouble].apply(x, y, cx) }))
            else wrapLong(r, JitGlobals.withInterp(interp){ d.asInstanceOf[LongLongObjToLong].apply(x, y, cx) })
          case (true, true, false) =>
            val ax = refOf(a); if ax == null then return null
            val bx = refOf(b); if bx == null then return null
            val z = c match { case Value.IntV(v) => v; case _ => return null }
            if isDbl then Computation.Pure(Value.doubleV(JitGlobals.withInterp(interp){ d.asInstanceOf[ObjObjLongToDouble].apply(ax, bx, z) }))
            else wrapLong(r, JitGlobals.withInterp(interp){ d.asInstanceOf[ObjObjLongToLong].apply(ax, bx, z) })
          case (true, false, true) =>
            val ax = refOf(a); if ax == null then return null
            val y = b match { case Value.IntV(v) => v; case _ => return null }
            val cx = refOf(c); if cx == null then return null
            if isDbl then Computation.Pure(Value.doubleV(JitGlobals.withInterp(interp){ d.asInstanceOf[ObjLongObjToDouble].apply(ax, y, cx) }))
            else wrapLong(r, JitGlobals.withInterp(interp){ d.asInstanceOf[ObjLongObjToLong].apply(ax, y, cx) })
          case (false, true, true) =>
            val x = a match { case Value.IntV(v) => v; case _ => return null }
            val bx = refOf(b); if bx == null then return null
            val cx = refOf(c); if cx == null then return null
            if isDbl then Computation.Pure(Value.doubleV(JitGlobals.withInterp(interp){ d.asInstanceOf[LongObjObjToDouble].apply(x, bx, cx) }))
            else wrapLong(r, JitGlobals.withInterp(interp){ d.asInstanceOf[LongObjObjToLong].apply(x, bx, cx) })
          case (true, true, true) =>
            val ax = refOf(a); if ax == null then return null
            val bx = refOf(b); if bx == null then return null
            val cx = refOf(c); if cx == null then return null
            if isDbl then Computation.Pure(Value.doubleV(JitGlobals.withInterp(interp){ d.asInstanceOf[ObjObjObjToDouble].apply(ax, bx, cx) }))
            else wrapLong(r, JitGlobals.withInterp(interp){ d.asInstanceOf[ObjObjObjToLong].apply(ax, bx, cx) })
      catch case _: Throwable => null
    else
      val a0 = marshalBytecode(a, r.paramIsRef(0), r.resultIsDouble, interp)
      if a0 == null then return null
      val b0 = marshalBytecode(b, r.paramIsRef(1), r.resultIsDouble, interp)
      if b0 == null then return null
      val c0 = marshalBytecode(c, r.paramIsRef(2), r.resultIsDouble, interp)
      if c0 == null then return null
      val out =
        try JitGlobals.withInterp(interp) { r.mh.invoke(a0, b0, c0).asInstanceOf[AnyRef] }
        catch case _: Throwable => return null
      wrapBytecodeResult(r, out)

  /** For FastTier: try to get an `ObjToDouble` direct interface for a
   *  1-param ref → double JIT-compiled function. Returns null when JIT is
   *  disabled, compilation fails, or the shape doesn't match. No `withInterp`
   *  wrapper required when called — that's the caller's responsibility (FastTier
   *  omits it entirely when `slotFreeNames.isEmpty`, since generated code then
   *  never calls `readGlobalDouble`). */
  def tryGetObjToDouble(f: Value.FunV, interp: Interpreter): ObjToDouble | Null =
    if !JitBackend.default.enabled then return null
    val r = bytecodeFor(f, interp)
    if r == null then return null
    if !r.resultIsDouble then return null
    if r.paramIsRef.length != 1 || !r.paramIsRef(0) then return null
    r.direct match
      case d: ObjToDouble => d
      case _              => null

  /** For FastTier: try to get an `ObjToLong` direct interface for a
   *  1-param ref → long JIT-compiled function. Parallel to `tryGetObjToDouble`. */
  def tryGetObjToLong(f: Value.FunV, interp: Interpreter): ObjToLong | Null =
    if !JitBackend.default.enabled then return null
    val r = bytecodeFor(f, interp)
    if r == null then return null
    if r.resultIsDouble then return null
    if r.paramIsRef.length != 1 || !r.paramIsRef(0) then return null
    r.direct match
      case d: ObjToLong => d
      case _            => null

  /** Bytecode-JIT entry called from `CallRuntime.callFun` BEFORE the
   *  `TcoRuntime.tcoTrampoline` dispatch. Handles the same 1- and 2-param
   *  FunV shapes the existing `tryRun1`/`tryRun2` cover, with the trampoline's
   *  list-shaped arg convention. Returns null on any miss → caller continues
   *  with the trampoline. */
  def tryBytecodeList(f: Value.FunV, args: List[Value], interp: Interpreter): Computation | Null =
    if !JitBackend.default.enabled || f.name.isEmpty then return null
    val n = f.params.length
    if n < 1 || n > 3 || args.length != n then return null
    val bc = bytecodeFor(f, interp)
    if bc == null then return null
    if n == 1 then invokeBytecode1(bc, args.head, interp)
    else if n == 2 then invokeBytecode2(bc, args.head, args(1), interp)
    else invokeBytecode3(bc, args.head, args(1), args(2), interp)

  /** 0-arg entry. Returns a Pure(IntV/DoubleV) computation if the 0-param thunk
   *  `f` is bytecode-JIT compilable, else null. Only the bytecode-JIT path
   *  (Javac/ASM) is wired for 0-arg; the register VM is not used here. */
  def tryRun0(f: Value.FunV, interp: Interpreter): Computation | Null =
    if !enabled || f.name.isEmpty || f.params.nonEmpty || f.returnsThrows || !JitBackend.default.enabled then null
    else
      val bc = bytecodeFor(f, interp)
      if bc == null then null
      else invokeBytecode0(bc, interp)

  /** 1-arg entry. Returns a Pure(IntV/DoubleV) computation if JITted, else null.
   *  Accepts either a numeric arg (numeric param) or an InstanceV (ref param,
   *  VM 2a) — the param domain is decided by the compiled function. */
  def tryRun1(f: Value.FunV, arg: Value, interp: Interpreter, eager: Boolean = false): Computation | Null =
    if !enabled || f.name.isEmpty || f.params.length != 1 || f.returnsThrows then null
    else
      // Phase C: try the bytecode-jit MH before the register-VM path. Skips
      // `SscVm.exec`'s opcode dispatch loop entirely when the body is in the
      // pure-int subset BytecodeJit supports.
      val bcMh = bytecodeFor(f, interp)
      if bcMh != null then
        val r = invokeBytecode1(bcMh, arg, interp)
        if r != null then return r
      val cf = hotCompiled(f, interp, eager)
      if cf == null then null
      else if isRefParam(cf, 0) then
        arg match
          case inst: Value.InstanceV =>
            try wrap(cf, runVm(cf, NoArgs, Array[AnyRef](inst)))
            catch case _: Throwable => null
          case fn: Value.FunV =>
            try wrap(cf, runVm(cf, NoArgs, Array[AnyRef](fn)))
            catch case _: Throwable => null
          case _ => null
      else if !isNumeric(arg) then null
      else
        val a0 = marshal(arg, cf.paramIsDouble.length > 0 && cf.paramIsDouble(0))
        if a0 == null then null
        else
          try wrap(cf, runVm(cf, Array(a0.longValue), NoRefs))
          catch case _: Throwable => null

  /** 2-arg entry. Returns a Pure(IntV/DoubleV) computation if JITted, else null. */
  def tryRun2(f: Value.FunV, a: Value, b: Value, interp: Interpreter, eager: Boolean = false): Computation | Null =
    if !enabled || f.name.isEmpty || f.params.length != 2 || f.returnsThrows then null
    else
      val bcMh = bytecodeFor(f, interp)
      if bcMh != null then
        val r = invokeBytecode2(bcMh, a, b, interp)
        if r != null then return r
      val cf = hotCompiled(f, interp, eager)
      if cf == null then null
      else marshalAndRun(cf, List(a, b))

  /** List-arg entry used by the general `callFun` path. Handles any arity the
   *  compiler supports ([[VmCompiler.MaxArity]]). Each argument is marshalled
   *  per the compiled param domain: numeric → Long bank, InstanceV → ref bank.
   *  `eager` is set for self-tail-recursive callers (see [[hotCompiled]]). */
  def tryRunList(f: Value.FunV, args: List[Value], interp: Interpreter, eager: Boolean = false): Computation | Null =
    if !enabled || f.name.isEmpty || f.returnsThrows then null
    else
      val n = args.length
      if n < 1 || n > VmCompiler.MaxArity || f.params.length != n then null
      else
        // Bytecode JIT for 1-, 2-, and 3-param funcs; bypasses the
        // SscVm.exec dispatch loop when the body fits its subset
        // (incl. the TCO `while`-loop pattern from `tryTcoBody`).
        if (n >= 1 && n <= 3) && JitBackend.default.enabled then
          val bc = bytecodeFor(f, interp)
          if bc != null then
            val r =
              if n == 1 then invokeBytecode1(bc, args.head, interp)
              else if n == 2 then invokeBytecode2(bc, args.head, args(1), interp)
              else invokeBytecode3(bc, args.head, args(1), args(2), interp)
            if r != null then return r
        val cf = hotCompiled(f, interp, eager)
        if cf == null then null else marshalAndRun(cf, args)

  /** Marshal `args` into the numeric + ref banks per the compiled param domains,
   *  run, and wrap. Returns null if any arg cannot be represented in its param's
   *  domain (→ caller falls back to tree-walk). */
  private def marshalAndRun(cf: SscVm.CompiledFn, args: List[Value]): Computation | Null =
    val n  = args.length
    val xs = new Array[Long](n)
    val rs = new Array[AnyRef](n)
    var anyRef = false
    var ok   = true
    var i    = 0
    var rest = args
    while ok && (rest ne Nil) do
      val v = rest.head
      if isRefParam(cf, i) then
        v match
          case inst: Value.InstanceV => rs(i) = inst; anyRef = true
          case fn: Value.FunV        => rs(i) = fn;   anyRef = true
          case _                     => ok = false
      else
        val m = marshal(v, i < cf.paramIsDouble.length && cf.paramIsDouble(i))
        if m == null then ok = false else xs(i) = m.longValue
      i += 1; rest = rest.tail
    if !ok then null
    else
      try wrap(cf, runVm(cf, xs, if anyRef then rs else NoRefs))
      catch case _: Throwable => null
