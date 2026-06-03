package scalascript.interpreter.vm.jit

import scala.meta.Term
import scalascript.interpreter.{Interpreter, Value}

/** SPI boundary for bytecode-JIT backends.
 *
 *  Today there is exactly one implementation — `JavacJitBackend` (Java source
 *  → `javax.tools.JavaCompiler` → in-memory class → `MethodHandle`).  The
 *  trait exists to make the architectural intent explicit and to provide a
 *  clean extension point for a future ASM backend without touching any caller
 *  code. */
trait JitBackend:
  def id: String
  def enabled: Boolean
  def tryCompile(f: Value.FunV, interp: Interpreter): JitResult | Null
  def tryCompileWhileLong(
    cond:   Term,
    names:  Array[String],
    rhs:    Array[Term],
    interp: Interpreter | Null
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
  def tryCompileWhileMixed(
    cond:         Term,
    names:        Array[String],
    rhs:          Array[Term],
    foreachApply: Term,
    accName:      String,
    accIsDouble:  Boolean,
    interp:       Interpreter
  ): WhileJitEntry | Null = null

object JitBackend:
  /** Active backend. `SSC_JIT_BACKEND=asm` selects `AsmJitBackend`;
   *  `SSC_JIT_BACKEND=javac` (or unset) selects `JavacJitBackend`. */
  val default: JitBackend = sys.env.get("SSC_JIT_BACKEND") match
    case Some("asm")   => AsmJitBackend
    case Some("javac") => JavacJitBackend
    case _             => JavacJitBackend
