package ssc.bridge

import java.lang.reflect.InvocationTargetException
import org.scalatest.funsuite.AnyFunSuite
import ssc.*
import ssc.bytecode.JvmByteGen

final class PortableEffectsResidualForwardingTest extends AnyFunSuite:
  private val lanes = List("vm", "asm")

  private def run(program: Program, lane: String): Value = lane match
    case "vm" => Runtime.run(Compiler.compile(program), Runtime.emptyEnv)
    case "asm" => Emit.synchronized {
      val (_, globals) = Compiler.compileWithGlobals(program)
      Emit.globalsRef = globals
      try JvmByteGen.runProgram(JvmByteGen.emitProgram(program))
      catch case error: InvocationTargetException =>
        throw Option(error.getCause).getOrElse(error)
    }

  private def runSource(source: String, lane: String): Value =
    run(FrontendBridge.convertSource(source), lane)

  private def eachLane(program: => Program)(assertion: (String, Value) => Unit): Unit =
    lanes.foreach { lane => assertion(lane, run(program, lane)) }

  private def lit(value: Long): Term = Term.Lit(Const.CInt(value))
  private def str(value: String): Term = Term.Lit(Const.CStr(value))
  private val unit: Term = Term.Lit(Const.CUnit)
  private def add(left: Term, right: Term): Term =
    Term.Prim("i.add", List(left, right))
  private def app(function: Term, argument: Term): Term =
    Term.App(function, List(argument))
  private def op(label: String, argument: Term, suffix: Term): Term =
    Term.Ctor("Op", List(str(label), argument, Term.Lam(1, suffix)))
  private def handler(arms: List[Arm], default: Option[Term] = None): Term =
    Term.Lam(1, Term.Match(Term.Local(0), arms, default))
  private def handle(computation: Term, withHandler: Term): Term =
    Term.Prim("effect.handle", List(computation, withHandler))

  private def continuation(value: Value): Value.ClosV = value match
    case Value.DataV("Op", IndexedSeq(_, _, resume: Value.ClosV)) => resume
    case other => fail(s"expected Op continuation, got ${Show.show(other)}")

  test("residual forwarding reinstalls every skipped handler and both Return clauses") {
    // Wr(7); Rd(); Wr(5); rdReply + 7. The second Wr proves that the outer
    // handler is reinstalled after the first forwarded resume. Inner +1 and
    // outer +2 Return transforms each apply exactly once: 7 + 5 + (35+7+1+2).
    val computation = op("Wr.wr", lit(7),
      op("Rd.rd", unit,
        op("Wr.wr", lit(5), add(Term.Local(1), lit(7)))))
    val inner = handler(List(
      Arm("rd", 1, app(Term.Local(0), lit(35))),
      Arm("Return", 1, add(Term.Local(0), lit(1)))))
    val outer = handler(List(
      Arm("wr", 2, add(Term.Local(1), app(Term.Local(0), unit))),
      Arm("Return", 1, add(Term.Local(0), lit(2)))))
    val program = Program(Nil, handle(handle(computation, inner), outer))

    eachLane(program) { (lane, result) =>
      assert(result == Value.IntV(57), lane)
    }
  }

  test("forwarding preserves one-shot and reusable base continuations") {
    val returnOnly = handler(List(Arm("Return", 1, Term.Local(0))))

    lanes.foreach { lane =>
      val oneShot = Program(Nil, handle(
        Term.Prim("effect.perform.oneshot", List(str("One"), str("op"))),
        returnOnly))
      val guarded = continuation(run(oneShot, lane))
      assert(Prims.runClos1(guarded, Value.IntV(1)) == Value.IntV(1), lane)
      val rejection = intercept[ControlRunFailure] {
        Prims.runClos1(guarded, Value.IntV(2))
      }
      assert(rejection.rejection ==
        ResumeRejected.AlreadyResumed(OperationId(EffectId("One"), "op")), lane)

      val reusable = Program(Nil, handle(
        Term.Prim("effect.perform", List(str("Many.op"))),
        returnOnly))
      val raw = continuation(run(reusable, lane))
      assert(Prims.runClos1(raw, Value.IntV(3)) == Value.IntV(3), lane)
      assert(Prims.runClos1(raw, Value.IntV(4)) == Value.IntV(4), lane)
    }
  }

  test("Return fallback, explicit default, and operation tag Op are unambiguous") {
    val noReturn = handler(List(Arm("unused", 0, lit(-1))))
    val defaultHandler = handler(Nil, Some(lit(99)))
    val opTagHandler = handler(List(
      // Three fields deliberately resemble the runtime Op(label,arg,k) shape.
      Arm("Op", 3, app(Term.Local(0), lit(9)))))

    val programs = List(
      Program(Nil, handle(lit(7), noReturn)) -> Value.IntV(7),
      Program(Nil, handle(
        Term.Prim("effect.perform", List(str("Missing.op"))),
        defaultHandler)) -> Value.IntV(99),
      Program(Nil, handle(
        Term.Prim("effect.perform", List(
          str("Collision.Op"), str("looks.like.label"), lit(2))),
        opTagHandler)) -> Value.IntV(9))

    programs.foreach { (program, expected) =>
      eachLane(program) { (lane, result) => assert(result == expected, lane) }
    }
  }

  test("ordinary and non-canonical closures do not gain recoverable misses") {
    val canonical = handler(List(Arm("Return", 1, Term.Local(0))))
    val ordinaryCall = Program(Nil,
      Term.App(canonical, List(Term.Ctor("Other", Nil))))

    // The extra Let makes this deliberately non-canonical; no frontend-private
    // terminal marker is present, so effect.handle must call it as a total fn.
    val nonCanonical = Term.Lam(1,
      Term.Let(List(Term.Local(0)),
        Term.Match(Term.Local(0),
          List(Arm("Return", 1, Term.Local(0))), None)))
    val nonCanonicalHandle = Program(Nil, handle(
      Term.Prim("effect.perform", List(str("Missing.op"))),
      nonCanonical))

    lanes.foreach { lane =>
      val ordinary = intercept[RuntimeException](run(ordinaryCall, lane))
      assert(ordinary.getMessage.contains("match: no arm for Other/0"), lane)
      val malformed = intercept[RuntimeException](run(nonCanonicalHandle, lane))
      assert(malformed.getMessage.contains("match: no arm for op/1"), lane)
    }
  }

  test("a selected handler body keeps unrelated nested match failures fatal") {
    val nestedFailure = Term.Match(
      Term.Ctor("Some", List(lit(1))),
      List(Arm("None", 0, lit(0))),
      None)
    val selected = handler(List(Arm("op", 1, nestedFailure)))
    val program = Program(Nil, handle(
      Term.Prim("effect.perform", List(str("Boom.op"))), selected))

    lanes.foreach { lane =>
      val failure = intercept[RuntimeException](run(program, lane))
      assert(failure.getMessage.contains("match: no arm for Some/1"), lane)
    }
  }

  test("guarded duplicate cases and nested Return patterns retain source order") {
    val laterSameTag =
      """effect Pick:
        |  def choose(value: Int): Int
        |
        |val inner = handle(Pick.choose(1)) {
        |  case Pick.choose(value, resume) if value < 0 => resume(90)
        |  case Pick.choose(value, resume) if value == 1 => resume(20)
        |  case Return(value) => value
        |}
        |val result = handle(inner) {
        |  case Pick.choose(value, resume) => 999
        |  case Return(value) => value
        |}
        |result
        |""".stripMargin
    val nestedReturn =
      """val result = handle(Some(7)) {
        |  case Return(Some(value)) => value + 1
        |  case Return(None) => 0
        |}
        |result
        |""".stripMargin

    lanes.foreach { lane =>
      assert(runSource(laterSameTag, lane) == Value.IntV(20), lane)
      assert(runSource(nestedReturn, lane) == Value.IntV(8), lane)
    }
  }

  test("effectful multi-shot guards resume independent pending decisions") {
    val source =
      """multi effect Target:
        |  def op(value: Int): Int
        |
        |multi effect Gate:
        |  def allow(): Boolean
        |
        |val inner = handle(Target.op(1)) {
        |  case Target.op(value, resume) if Gate.allow() => resume(value + 100)
        |  case Return(value) => value
        |}
        |val result = handle(inner) {
        |  case Gate.allow(resume) => resume(false) + resume(true)
        |  case Target.op(value, resume) => resume(value + 10)
        |  case Return(value) => value
        |}
        |result
        |""".stripMargin

    lanes.foreach { lane =>
      assert(runSource(source, lane) == Value.IntV(112), lane)
    }
  }

  test("ordinary frontend partial-function invocation still fails on a miss") {
    val source =
      """val partial = { case Some(value) => value }
        |partial(None)
        |""".stripMargin

    lanes.foreach { lane =>
      val failure = intercept[RuntimeException](runSource(source, lane))
      assert(failure.getMessage.contains("match: no arm for None/0"), lane)
    }
  }

  test("persisted three-argument Emit.letrec descriptor remains linkable") {
    val method = Class.forName("ssc.Emit").getMethod(
      "letrec",
      classOf[Array[Int]],
      classOf[Array[Emit.LamFn]],
      classOf[Array[Value]])
    val result = method.invoke(
      null,
      Array.emptyIntArray,
      Array.empty[Emit.LamFn],
      Array.empty[Value]).asInstanceOf[Array[Value]]
    assert(result.isEmpty)
  }
