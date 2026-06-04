package scalascript.interpreter.vm.jit

import scala.meta.Term
import scalascript.interpreter.{Interpreter, Value}

/** SPI boundary for bytecode-JIT backends.
 *
 *  Today there are two implementations:
 *  - `JavacJitBackend` (Java source → `javax.tools.JavaCompiler` → bytecode)
 *  - `AsmJitBackend` (AST → JVM bytecode directly via ASM 9.7)
 *
 *  The trait provides a clean extension point for future backends and lets
 *  `JitLint` target any backend explicitly via the `backend` parameter. */
trait JitBackend:
  def id: String
  def enabled: Boolean
  def tryCompile(f: Value.FunV, interp: Interpreter): JitResult | Null
  def tryCompileWhileLong(
    cond:   Term,
    names:  Array[String],
    rhs:    Array[Term],
    interp: Interpreter | Null,
    locals: Map[String, Value] = Map.empty
  ): WhileJitEntry | Null

  /** Fused outer-while + inner-foreach JIT compilation.
   *
   *  Targets the `{ xs.foreach(s => acc = acc + fn(s)); i = i + 1 }` body
   *  shape. Generates a single Java `static void run(long[] slots)` that
   *  iterates `xs` inline — eliminating the per-outer-iteration virtual
   *  dispatch into `PreResolvedForeach.run` and the TLS slot reads/writes.
   *  Enables JVM devirtualization of the monomorphic `fn.apply(item)` call.
   *
   *  `names`/`rhs` are the trailing Int-assign expressions (e.g. `i = i+1`).
   *  The accumulator occupies `slots[names.length]`: raw double bits when
   *  `accIsDouble`, raw long otherwise.  The list receiver is passed at call
   *  time via `JitGlobals.getRefs()[0]`.  The compiled function is stored in
   *  `WhileJitEntry.refDoubleFns[0]` (Double acc) or `refFns[0]` (Long acc).
   *
   *  Returns null if the pattern is not recognised or compilation fails;
   *  callers fall back to `tryMixedLongWhile`. */
  /** JIT-compile a `while cond do { Stream.emit(emitArgs...); assigns... }` body
   *  to a `WhileLongEmitRunFn` that pushes raw Long values into a caller-owned
   *  `Array[Long]` buffer — no `Value.intV` wrapping, no virtual dispatch.
   *  `allSlots` is the full slot-name array (slot index → name); `assignNames`
   *  and `rhs` are the trailing assign targets and their RHS expressions.
   *  Returns null if the pattern is not recognised or compilation fails. */
  def tryCompileWhileLongEmit(
    cond:        Term,
    emitArgs:    Array[Term],
    allSlots:    Array[String],
    assignNames: Array[String],
    rhs:         Array[Term],
    interp:      Interpreter | Null
  ): WhileLongEmitRunFn | Null = null

  def tryCompileWhileMixed(
    cond:         Term,
    names:        Array[String],
    rhs:          Array[Term],
    foreachApply: Term,
    accName:      String,
    accIsDouble:  Boolean,
    interp:       Interpreter
  ): WhileJitEntry | Null = null

  /** Static structural analysis: which `JitBailReason`s explain why
   *  `tryCompile` would return null for this function.
   *
   *  The default implementation delegates to `JitPredicates.classifyBailReasons`
   *  which covers the structural cliffs shared by both Javac and ASM backends
   *  (param count, `using` params, varargs, effect return, bool body,
   *  try/catch, non-extract patterns, non-ADT scrutinees).  Backends may
   *  override to add or suppress reasons specific to their implementation.
   *
   *  `JitLint.lintFun` calls this only when `tryCompile` has already returned
   *  null, so it does not need to re-run any compilation.  Returns Nil when
   *  no structural cliff is detected — `JitLint` then reports `UnknownShape`. */
  def classifyBailReasons(fn: Value.FunV): List[JitBailReason] =
    JitPredicates.classifyBailReasons(fn)

object JitBackend:
  /** Active backend. `SSC_JIT_BACKEND=asm` selects `AsmJitBackend`;
   *  `SSC_JIT_BACKEND=javac` (or unset) selects `JavacJitBackend`. */
  val default: JitBackend = sys.env.get("SSC_JIT_BACKEND") match
    case Some("asm")   => AsmJitBackend
    case Some("javac") => JavacJitBackend
    case _             => JavacJitBackend
