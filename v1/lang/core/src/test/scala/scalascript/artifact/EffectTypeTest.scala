package scalascript.artifact

import org.scalatest.funsuite.AnyFunSuite
import scalascript.typer.{SType, EffectOp, Unifier, Constraint, UnifyResult}

class EffectTypeTest extends AnyFunSuite:

  private def parseSType(s: String): SType = InterfaceScope.parseSType(s)

  test("SType.Function with no effects shows without ! clause") {
    val t = SType.Function(List(SType.String), SType.Unit)
    assert(t.show == "String => Unit")
  }

  test("SType.Function with single effect shows '! Effect'") {
    val t = SType.Function(List(SType.String), SType.Unit,
              SType.EffectRow(-1, Set(EffectOp("Logger"))))
    assert(t.show == "String => Unit ! Logger")
  }

  test("SType.Function with two effects shows '! (E1, E2)'") {
    val t = SType.Function(List(SType.String), SType.Unit,
              SType.EffectRow(-1, Set(EffectOp("Logger"), EffectOp("Random"))))
    assert(t.show.startsWith("String => Unit ! ("))
    assert(t.show.contains("Logger"))
    assert(t.show.contains("Random"))
  }

  test("parseSType round-trips single effect") {
    val orig = SType.Function(List(SType.String), SType.Unit,
                 SType.EffectRow(-1, Set(EffectOp("Logger"))))
    val parsed = parseSType(orig.show)
    assert(parsed == orig)
  }

  test("parseSType round-trips two effects") {
    val orig = SType.Function(List(SType.String), SType.Unit,
                 SType.EffectRow(-1, Set(EffectOp("Logger"), EffectOp("Random"))))
    val parsed = parseSType(orig.show)
    assert(parsed == orig)
  }

  test("parseSType: 'String => Unit' parses as Function with empty row") {
    val t = parseSType("String => Unit")
    assert(t == SType.Function(List(SType.String), SType.Unit))
  }

  test("parseSType: 'String => Unit ! Logger' parses correctly") {
    val t = parseSType("String => Unit ! Logger")
    assert(t == SType.Function(List(SType.String), SType.Unit,
                 SType.EffectRow(-1, Set(EffectOp("Logger")))))
  }

  test("parseSType: '(Int, String) => Boolean ! (Logger, Random)' parses correctly") {
    val t = parseSType("(Int, String) => Boolean ! (Logger, Random)")
    assert(t == SType.Function(List(SType.Int, SType.String), SType.Boolean,
                 SType.EffectRow(-1, Set(EffectOp("Logger"), EffectOp("Random")))))
  }

  test("EffectRow unification: same closed rows unify") {
    val f1 = SType.Function(List(SType.String), SType.Unit, SType.EffectRow(-1, Set(EffectOp("Logger"))))
    val f2 = SType.Function(List(SType.String), SType.Unit, SType.EffectRow(-1, Set(EffectOp("Logger"))))
    val result = Unifier.unify(List(Constraint.Equal(f1, f2)))
    assert(result.isInstanceOf[UnifyResult.Success])
  }

  test("EffectRow unification: different closed rows fail") {
    val f1 = SType.Function(List(SType.String), SType.Unit, SType.EffectRow(-1, Set(EffectOp("Logger"))))
    val f2 = SType.Function(List(SType.String), SType.Unit, SType.EffectRow(-1, Set(EffectOp("Random"))))
    val result = Unifier.unify(List(Constraint.Equal(f1, f2)))
    assert(result.isInstanceOf[UnifyResult.Failure])
  }
