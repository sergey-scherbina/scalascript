package scalascript.interpreter.vm

import scalascript.interpreter.{Value, Computation, Interpreter}

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
   *  update merely delays compilation by one call, never corrupts state. */
  final class Entry:
    @volatile var compiled: SscVm.CompiledFn | Null = null
    @volatile var disabled: Boolean = false
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
          VmCompiler.compile(f, resolverFor(interp)) match
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

  // ── thread-local growable frame stack ────────────────────────────
  private val tlStack = new ThreadLocal[Array[Long]]:
    override def initialValue(): Array[Long] = new Array[Long](64 * 1024)

  /** Run `cf` with `args` on the thread-local stack, growing on overflow.
   *  Returns the Long result; propagates non-overflow throwables to the caller
   *  (which treats them as "fall back to tree-walk"). */
  private def runVm(cf: SscVm.CompiledFn, args: Array[Long]): Long =
    var stack = tlStack.get()
    while true do
      val need = cf.numRegs.max(1) + cf.arity + 16
      if stack.length < need then
        stack = new Array[Long](need * 2)
        tlStack.set(stack)
      var i = 0
      while i < args.length do { stack(i) = args(i); i += 1 }
      try return SscVm.exec(cf, stack, 0)
      catch case fo: SscVm.FrameOverflow =>
        stack = new Array[Long](fo.need * 2 + 16)
        tlStack.set(stack)
    0L // unreachable

  /** 1-arg entry. Returns a Pure(IntV) computation if JITted, else null. */
  def tryRun1(f: Value.FunV, arg: Value, interp: Interpreter, eager: Boolean = false): Computation | Null =
    if !enabled || f.name.isEmpty || f.params.length != 1 then null
    else arg match
      case Value.IntV(x) =>
        val cf = hotCompiled(f, interp, eager)
        if cf == null then null
        else
          try Computation.pureIntV(runVm(cf, Array(x)))
          catch case _: Throwable => null
      case _ => null

  /** 2-arg entry. Returns a Pure(IntV) computation if JITted, else null. */
  def tryRun2(f: Value.FunV, a: Value, b: Value, interp: Interpreter, eager: Boolean = false): Computation | Null =
    if !enabled || f.name.isEmpty || f.params.length != 2 then null
    else (a, b) match
      case (Value.IntV(x), Value.IntV(y)) =>
        val cf = hotCompiled(f, interp, eager)
        if cf == null then null
        else
          try Computation.pureIntV(runVm(cf, Array(x, y)))
          catch case _: Throwable => null
      case _ => null

  /** List-arg entry used by the general `callFun` path. Handles any arity the
   *  compiler supports ([[VmCompiler.MaxArity]]), provided every argument is an
   *  `IntV`. `eager` is set for self-tail-recursive callers (see
   *  [[hotCompiled]]). */
  def tryRunList(f: Value.FunV, args: List[Value], interp: Interpreter, eager: Boolean = false): Computation | Null =
    if !enabled || f.name.isEmpty then null
    else
      val n = args.length
      if n < 1 || n > VmCompiler.MaxArity || f.params.length != n then null
      else
        val xs = new Array[Long](n)
        var rest = args
        var i = 0
        var ok = true
        while ok && (rest ne Nil) do
          rest.head match
            case Value.IntV(x) => xs(i) = x; i += 1; rest = rest.tail
            case _             => ok = false
        if !ok then null
        else
          val cf = hotCompiled(f, interp, eager)
          if cf == null then null
          else
            try Computation.pureIntV(runVm(cf, xs))
            catch case _: Throwable => null
