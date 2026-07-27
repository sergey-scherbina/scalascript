package scalascript.cli

import java.util.concurrent.atomic.AtomicReference
import org.scalatest.funsuite.AnyFunSuite
import _root_.ssc.{Const, Def, Emit, IrToData, Prims, Program, Runtime, Term, Value}
import _root_.ssc.bytecode.JvmBytecodeAdmission

class FNestedBytecodeEvalTest extends AnyFunSuite:

  private def evalCoreIr(program: Program): Value =
    Prims.resolve("coreir.eval")(List(IrToData.program(program)))

  test("string-chunking admission uses modified UTF8 bytes across the whole program"):
    val small = Program(Nil, Term.Lit(Const.CStr("small")))
    val nulHeavy = "\u0000" * 33000
    val largeInDefinition = Program(
      List(Def("payload", Term.Lit(Const.CStr(nulHeavy)))),
      Term.Lit(Const.CUnit),
    )

    assert(!JvmBytecodeAdmission.requiresStringChunking(small))
    assert(nulHeavy.length < 65535)
    assert(JvmBytecodeAdmission.requiresStringChunking(largeInDefinition))

  test("coreir evaluator scope delegates, nests, restores, and stays thread-local"):
    val program = Program(Nil, Term.Lit(Const.CInt(7)))

    val scoped =
      Runtime.withCoreIrEvaluator(_ => Some(Value.StrV("outer"))) {
        val otherThreadValue = new AtomicReference[Value]()
        val thread = new Thread(() => otherThreadValue.set(evalCoreIr(program)))
        thread.start()
        thread.join()

        val before = evalCoreIr(program)
        val inner = Runtime.withCoreIrEvaluator(_ => Some(Value.StrV("inner"))) {
          evalCoreIr(program)
        }
        val after = evalCoreIr(program)
        (before, inner, after, otherThreadValue.get())
      }

    assert(scoped == (
      Value.StrV("outer"),
      Value.StrV("inner"),
      Value.StrV("outer"),
      Value.IntV(7),
    ))
    assert(evalCoreIr(program) == Value.IntV(7))

    val delegated = Runtime.withCoreIrEvaluator(_ => None) {
      evalCoreIr(program)
    }
    assert(delegated == Value.IntV(7))

  test("F evaluator consumes the first nested eval even when it stays on VM"):
    val small = Program(Nil, Term.Lit(Const.CStr("small")))
    val large = Program(Nil, Term.Lit(Const.CStr("x" * 70000)))
    val evaluator = RunNativeV2.fNestedBytecodeEvaluator(trace = false)

    assert(evaluator(small).isEmpty)
    assert(evaluator(large).isEmpty)

  test("F evaluator runs one large nested program exactly and restores bytecode globals"):
    val payload = "\u0000\u07ff\u0800\ud83d\ude42" * 10000
    val large = Program(Nil, Term.Lit(Const.CStr(payload)))
    val original = Emit.globalsRef
    val sentinel: scala.collection.Map[String, Value] =
      Map("sentinel" -> Value.IntV(1))
    Emit.globalsRef = sentinel

    try
      val evaluator = RunNativeV2.fNestedBytecodeEvaluator(trace = false)
      assert(evaluator(large).contains(Value.StrV(payload)))
      assert(Emit.globalsRef.asInstanceOf[AnyRef] eq sentinel.asInstanceOf[AnyRef])
      assert(evaluator(large).isEmpty)
    finally Emit.globalsRef = original

  test("F evaluator propagates a failure after bytecode execution starts"):
    val failing = Program(
      List(Def("payload", Term.Lit(Const.CStr("x" * 70000)))),
      Term.Prim("__arith__", List(
        Term.Lit(Const.CStr("/")),
        Term.Lit(Const.CInt(1)),
        Term.Lit(Const.CInt(0)),
      )),
    )
    val evaluator = RunNativeV2.fNestedBytecodeEvaluator(trace = false)

    val failure =
      intercept[RunNativeV2.FNestedBytecodeFailure](evaluator(failing))
    assert(failure.original.isInstanceOf[ArithmeticException])
