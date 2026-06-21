package scalascript.interpreter.vm.jit

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.meta.*
import scalascript.interpreter.{EffectsRuntime, Interpreter, Value}

class AsmEffectJitTest extends AnyFunSuite with Matchers:

  private def term(src: String): Term = src.parse[Term].get

  test("ASM walkLong lowers one-shot effect resolver calls"):
    val interp = Interpreter(java.io.PrintStream(java.io.OutputStream.nullOutputStream()))
    val body   = term("acc + Bump.tick().toLong")
    val fn     = Value.FunV(
      params     = List("acc"),
      body       = body,
      closure    = Map.empty,
      name       = "addTick",
      paramTypes = List("Long")
    )
    val resolver: EffectsRuntime.Resolver = _ => Value.intV(1)

    EffectsRuntime.withResolvers(Map(("Bump", "tick") -> resolver)) {
      val jit = AsmJitBackend.tryCompile(fn, interp)
      jit should not be null
      jit.direct shouldBe a [LongFn1]
      jit.direct.asInstanceOf[LongFn1].apply(41L) shouldBe 42L
    }
