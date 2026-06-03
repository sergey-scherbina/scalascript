package scalascript.interpreter.vm.jit

import scalascript.interpreter.{Interpreter, Value}

/** Per-thread interpreter handle for the free-name globals read path.
 *  `JitRuntime` sets this on each MH invocation; the generated Java code
 *  calls back into `readGlobalLong(name)` to resolve a free `Int` global
 *  by name. The lookup adds one HashMap miss + a type cast per read, but
 *  the rest of the function body still benefits from the bytecode-JIT'd
 *  hot path. */
object JitGlobals:

  private val interpTls: ThreadLocal[Interpreter] = new ThreadLocal[Interpreter]()

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

  /** Called by generated Java code: read a top-level `Int` global by name and
   *  return its `Long` value. Throws `RuntimeException` if the name is
   *  missing or not an `IntV` — caller's MH invocation catches and falls
   *  back to the SscVm.exec / tree-walk path. */
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
