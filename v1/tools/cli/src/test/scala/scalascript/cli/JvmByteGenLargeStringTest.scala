package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import _root_.ssc.{Const, Program, Term, Value}
import _root_.ssc.bytecode.JvmByteGen

class JvmByteGenLargeStringTest extends AnyFunSuite:

  test("direct ASM preserves strings larger than one classfile UTF8 entry"):
    val inputs = List(
      "x" * 70000,
      "\u0000\u07ff\u0800\ud83d\ude42" * 10000,
    )

    inputs.foreach { input =>
      val bytes = JvmByteGen.emitProgram(Program(Nil, Term.Lit(Const.CStr(input))))
      assert(JvmByteGen.runProgram(bytes) == Value.StrV(input))
    }
