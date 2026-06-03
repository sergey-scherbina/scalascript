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
  ): java.lang.reflect.Method | Null

object JitBackend:
  /** Active backend. Hard-wired to `JavacJitBackend` today.
   *  When a second impl lands, replace with an env-var + ServiceLoader selector. */
  val default: JitBackend = JavacJitBackend
