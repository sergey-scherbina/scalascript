package scalascript.interpreter.vm

import scalascript.interpreter.{Value, Computation, Interpreter}
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
    @volatile var bytecode: BytecodeJit.Result | Null = null
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

  /** Build an ADT-constructor metadata lookup from the interpreter's recorded
   *  field order + field types, for `match` field extraction in [[VmCompiler]]. */
  private def metaFor(interp: Interpreter): VmCompiler.Meta =
    (ctor: String) =>
      interp.typeFieldOrder.get(ctor) match
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
    else Computation.pureIntV(raw)

  private def isRefParam(cf: SscVm.CompiledFn, i: Int): Boolean =
    i < cf.paramIsRef.length && cf.paramIsRef(i)

  /** Phase C bytecode JIT lookup: resolves `Entry.bytecode` lazily (compile is
   *  tried at most once per FunV; the underlying `BytecodeJit.tryCompile`
   *  caches by body AST so a rebuilt FunV with the same body skips compile).
   *  Returns the `Result` (handle + paramIsRef) or null. */
  private def bytecodeFor(f: Value.FunV, interp: Interpreter): BytecodeJit.Result | Null =
    if !BytecodeJit.enabled then return null
    val e = entryFor(f)
    val r = e.bytecode
    if r != null then return r
    if e.bytecodeTried then return null
    cache.synchronized:
      if !e.bytecodeTried then
        e.bytecode     = BytecodeJit.tryCompile(f, interp)
        e.bytecodeTried = true
    e.bytecode

  /** Marshal one Value into the Java arg the bytecode method expects.
   *  Ref param → the value as `Object` (must be `InstanceV`; bails
   *  otherwise). Numeric param: when `resultIsDouble` is true the slots are
   *  `double` and accept `IntV` (widened) or `DoubleV`; when false they
   *  accept only `IntV`. */
  private def marshalBytecode(v: Value, isRef: Boolean, isDouble: Boolean): AnyRef | Null =
    if isRef then
      v match
        case _: Value.InstanceV => v.asInstanceOf[AnyRef]
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

  private def wrapBytecodeResult(r: BytecodeJit.Result, out: AnyRef): Computation =
    if r.resultIsDouble then Computation.Pure(Value.doubleV(out.asInstanceOf[jl.Double].doubleValue))
    else Computation.pureIntV(out.asInstanceOf[jl.Long].longValue)

  private def invokeBytecode1(r: BytecodeJit.Result, arg: Value, interp: Interpreter): Computation | Null =
    val d = r.direct
    if d != null then
      try
        if !r.paramIsRef(0) then
          if r.resultIsDouble then
            val n = arg match
              case Value.IntV(x)    => x.toDouble
              case Value.DoubleV(x) => x
              case _                => return null
            val result = BytecodeJit.withInterp(interp) { d.asInstanceOf[DoubleFn1].apply(n) }
            Computation.Pure(Value.doubleV(result))
          else
            val n = arg match
              case Value.IntV(x) => x
              case _             => return null
            val result = BytecodeJit.withInterp(interp) { d.asInstanceOf[LongFn1].apply(n) }
            Computation.pureIntV(result)
        else
          arg match
            case _: Value.InstanceV =>
              if r.resultIsDouble then
                val result = BytecodeJit.withInterp(interp) { d.asInstanceOf[ObjToDouble].apply(arg.asInstanceOf[AnyRef]) }
                Computation.Pure(Value.doubleV(result))
              else
                val result = BytecodeJit.withInterp(interp) { d.asInstanceOf[ObjToLong].apply(arg.asInstanceOf[AnyRef]) }
                Computation.pureIntV(result)
            case _ => null
      catch case _: Throwable => null
    else
      val a0 = marshalBytecode(arg, r.paramIsRef(0), r.resultIsDouble)
      if a0 == null then return null
      val out =
        try BytecodeJit.withInterp(interp) { r.mh.invoke(a0).asInstanceOf[AnyRef] }
        catch case _: Throwable => return null
      wrapBytecodeResult(r, out)

  private def invokeBytecode2(r: BytecodeJit.Result, a: Value, b: Value, interp: Interpreter): Computation | Null =
    val d = r.direct
    if d != null then
      // unboxed path: only pure-long or pure-double 2-param (no mixed ref+numeric)
      try
        if r.resultIsDouble then
          val x = a match { case Value.IntV(v) => v.toDouble; case Value.DoubleV(v) => v; case _ => return null }
          val y = b match { case Value.IntV(v) => v.toDouble; case Value.DoubleV(v) => v; case _ => return null }
          val result = BytecodeJit.withInterp(interp) { d.asInstanceOf[DoubleFn2].apply(x, y) }
          Computation.Pure(Value.doubleV(result))
        else
          val x = a match { case Value.IntV(v) => v; case _ => return null }
          val y = b match { case Value.IntV(v) => v; case _ => return null }
          val result = BytecodeJit.withInterp(interp) { d.asInstanceOf[LongFn2].apply(x, y) }
          Computation.pureIntV(result)
      catch case _: Throwable => null
    else
      val a0 = marshalBytecode(a, r.paramIsRef(0), r.resultIsDouble)
      if a0 == null then return null
      val b0 = marshalBytecode(b, r.paramIsRef(1), r.resultIsDouble)
      if b0 == null then return null
      val out =
        try BytecodeJit.withInterp(interp) { r.mh.invoke(a0, b0).asInstanceOf[AnyRef] }
        catch case _: Throwable => return null
      wrapBytecodeResult(r, out)

  /** Bytecode-JIT entry called from `CallRuntime.callFun` BEFORE the
   *  `TcoRuntime.tcoTrampoline` dispatch. Handles the same 1- and 2-param
   *  FunV shapes the existing `tryRun1`/`tryRun2` cover, with the trampoline's
   *  list-shaped arg convention. Returns null on any miss → caller continues
   *  with the trampoline. */
  def tryBytecodeList(f: Value.FunV, args: List[Value], interp: Interpreter): Computation | Null =
    if !BytecodeJit.enabled || f.name.isEmpty then return null
    val n = f.params.length
    if n < 1 || n > 2 || args.length != n then return null
    val bc = bytecodeFor(f, interp)
    if bc == null then return null
    if n == 1 then invokeBytecode1(bc, args.head, interp)
    else invokeBytecode2(bc, args.head, args(1), interp)

  /** 1-arg entry. Returns a Pure(IntV/DoubleV) computation if JITted, else null.
   *  Accepts either a numeric arg (numeric param) or an InstanceV (ref param,
   *  VM 2a) — the param domain is decided by the compiled function. */
  def tryRun1(f: Value.FunV, arg: Value, interp: Interpreter, eager: Boolean = false): Computation | Null =
    if !enabled || f.name.isEmpty || f.params.length != 1 then null
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
    if !enabled || f.name.isEmpty || f.params.length != 2 then null
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
    if !enabled || f.name.isEmpty then null
    else
      val n = args.length
      if n < 1 || n > VmCompiler.MaxArity || f.params.length != n then null
      else
        // Phase C bytecode JIT for 1- or 2-param funcs; bypasses the
        // SscVm.exec dispatch loop when the body fits its subset
        // (incl. the TCO `while`-loop pattern from `tryTcoBody`).
        if (n == 1 || n == 2) && BytecodeJit.enabled then
          val bc = bytecodeFor(f, interp)
          if bc != null then
            val r = if n == 1 then invokeBytecode1(bc, args.head, interp)
                    else            invokeBytecode2(bc, args.head, args(1), interp)
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
          case _                     => ok = false
      else
        val m = marshal(v, i < cf.paramIsDouble.length && cf.paramIsDouble(i))
        if m == null then ok = false else xs(i) = m.longValue
      i += 1; rest = rest.tail
    if !ok then null
    else
      try wrap(cf, runVm(cf, xs, if anyRef then rs else NoRefs))
      catch case _: Throwable => null
