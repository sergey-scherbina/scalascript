package scalascript.typer

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

/** Tests for v1.12.1 effect-type features in the Typer:
 *   - `!` in `def` return-type annotations builds correct `SType.Function` with effects
 *   - `handle[Eff](thunk)` discharges the named effect from the thunk's type
 *   - `EffectAnalysis.verify` is wired and warns when declared/inferred diverge
 */
class EffectTyperTest extends AnyFunSuite:

  private def typeModule(src: String): TypedModule =
    val module = Parser.parse(src)
    Typer.typeCheck(module)

  private def scopeTypes(src: String): Map[String, SType] =
    val tm = typeModule(src)
    tm.sections.flatMap(_.definitions).flatMap {
      case TypedDef.CodeBlock(_, _, defs) => defs.map(d => d.name -> d.tpe)
      case _                              => Nil
    }.toMap

  // ── §3.2 : effect row in Defn.Def annotation ──────────────────────────

  test("def with single ! effect stores EffectRow in function type") {
    val types = scopeTypes("""
      |```scala
      |def greet(name: String): Unit ! Logger = Logger.log(s"hello")
      |```
      |""".stripMargin)
    val greetType = types("greet")
    greetType match
      case SType.Function(List(SType.String), SType.Unit, SType.EffectRow(_, ops)) =>
        assert(ops == Set(EffectOp("Logger")), s"expected Set(EffectOp(Logger)) but got $ops")
      case other => fail(s"unexpected type: ${other.show}")
  }

  test("def with multiple effects stores all ops in EffectRow") {
    val types = scopeTypes("""
      |```scala
      |def auditedShuffle[A](xs: List[A]): List[A] ! (Logger, Random) = xs
      |```
      |""".stripMargin)
    val t = types("auditedShuffle")
    t match
      case SType.Function(_, _, SType.EffectRow(_, ops)) =>
        assert(ops == Set(EffectOp("Logger"), EffectOp("Random")), s"expected {Logger, Random} but got $ops")
      case other => fail(s"unexpected type: ${other.show}")
  }

  test("def without ! clause stores empty EffectRow (total function)") {
    val types = scopeTypes("""
      |```scala
      |def pure(x: Int): Int = x + 1
      |```
      |""".stripMargin)
    types("pure") match
      case SType.Function(_, _, SType.EffectRow(_, ops)) =>
        assert(ops.isEmpty, s"expected no effects but got $ops")
      case other => fail(s"unexpected type: ${other.show}")
  }

  // ── §5.1 : handle[Eff] discharge ──────────────────────────────────────

  test("handle[Eff] on explicit thunk discharges the named effect") {
    // The body is an explicit thunk `() => expr`: SType.Function(Nil, Unit, EffectRow(Logger))
    // handle[Logger](thunk) should yield Unit (Logger discharged, no remaining effects)
    val tm = typeModule("""
      |```scala
      |def thunk(): Unit ! Logger = Logger.log("hi")
      |def result = handle[Logger](() => thunk())
      |```
      |""".stripMargin)
    // No type errors expected from the handle expression
    val handleErrors = tm.errors.filter(_.msg.contains("handle"))
    assert(handleErrors.isEmpty, s"unexpected handle errors: $handleErrors")
  }

  test("handle[Eff] does not propagate remaining effects when all discharged") {
    val tm = typeModule("""
      |```scala
      |def result = handle[Logger](() => Logger.log("x"))
      |```
      |""".stripMargin)
    assert(!tm.hasErrors, s"unexpected errors: ${tm.errors.map(_.show)}")
  }

  // ── §9 / EffectAnalysis verifier wiring ───────────────────────────────

  test("EffectAnalysis verifier warns when function is effectful but declares no row") {
    val tm = typeModule("""
      |```scala
      |effect Logger:
      |  def log(msg: String): Unit
      |
      |def foo(x: Int): Int =
      |  Logger.log(s"$x")
      |  x + 1
      |```
      |""".stripMargin)
    // verifier should emit a [effect-verifier] warning for foo
    val effectWarnings = tm.errors.filter(_.msg.contains("[effect-verifier]"))
    assert(effectWarnings.exists(_.msg.contains("foo")),
      s"expected effect-verifier warning for foo, got: ${tm.errors.map(_.show)}")
  }

  test("no verifier warning when declared effects agree with analysis") {
    val tm = typeModule("""
      |```scala
      |effect Logger:
      |  def log(msg: String): Unit
      |
      |def greet(name: String): Unit ! Logger =
      |  Logger.log(s"hello $name")
      |```
      |""".stripMargin)
    val effectWarnings = tm.errors.filter(_.msg.contains("[effect-verifier]"))
    assert(effectWarnings.isEmpty,
      s"unexpected effect-verifier warnings: ${effectWarnings.map(_.show)}")
  }

  // ── handle-discharge: a def that fully handles its own effect leaks nothing ──
  // Regression for the durable-save-run.ssc CI Conformance red: `capture()` performs
  // `Suspend.point()` INSIDE a `handle { … } { case Suspend.point(k) => k }` that
  // discharges `Suspend`, so it is genuinely pure (`Int => Int`) and correctly
  // declares no effect row.  The coarse name-reachability set would mis-flag it.

  test("no verifier warning when a def fully discharges its own effect via handle") {
    val tm = typeModule("""
      |```scala
      |multi effect Suspend:
      |  def point(): Int
      |
      |def capture(): Int => Int = handle {
      |  val input = Suspend.point()
      |  input * 10
      |} {
      |  case Suspend.point(saved) => saved
      |}
      |```
      |""".stripMargin)
    val effectWarnings = tm.errors.filter(_.msg.contains("[effect-verifier]"))
    assert(effectWarnings.isEmpty,
      s"unexpected effect-verifier warnings: ${effectWarnings.map(_.show)}")
  }

  test("verifier still flags an effect performed OUTSIDE the discharging handle") {
    // Discharge is lexically scoped, NOT a blanket suppression: an op performed
    // before/outside the handle for that effect still leaks even when a handle for
    // it appears elsewhere in the same body.
    val tm = typeModule("""
      |```scala
      |multi effect Suspend:
      |  def point(): Int
      |
      |def leaks(): Int =
      |  val early = Suspend.point()
      |  handle {
      |    Suspend.point()
      |  } {
      |    case Suspend.point(saved) => saved
      |  }
      |```
      |""".stripMargin)
    val effectWarnings = tm.errors.filter(_.msg.contains("[effect-verifier]"))
    assert(effectWarnings.exists(_.msg.contains("leaks")),
      s"expected effect-verifier warning for leaks, got: ${tm.errors.map(_.show)}")
  }
