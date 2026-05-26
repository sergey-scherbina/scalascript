package scalascript.transform

import org.scalatest.funsuite.AnyFunSuite

class EffectAnalysisVerifierTest extends AnyFunSuite:

  test("verify: no warnings when declared and inferred agree (both empty)") {
    val result   = EffectAnalysis.Result(Set.empty, Set.empty)
    val declared = Map("foo" -> Set.empty[String])
    assert(EffectAnalysis.verify(declared, result).isEmpty)
  }

  test("verify: warns when function is effectful but declares no row") {
    val result   = EffectAnalysis.Result(Set("Logger.log"), Set("foo"))
    val declared = Map("foo" -> Set.empty[String])
    val warnings = EffectAnalysis.verify(declared, result)
    assert(warnings.exists(_.contains("foo")))
    assert(warnings.exists(_.contains("declares no effect row")))
  }

  test("verify: warns when function declares effects but analysis sees none") {
    val result   = EffectAnalysis.Result(Set("Logger.log"), Set.empty)
    val declared = Map("foo" -> Set("Logger"))
    val warnings = EffectAnalysis.verify(declared, result)
    assert(warnings.exists(_.contains("foo")))
    assert(warnings.exists(_.contains("Logger")))
  }

  test("verify: no warnings when declared effectful and analysis agrees") {
    val result   = EffectAnalysis.Result(Set("Logger.log"), Set("foo"))
    // foo declares Logger and analysis says foo is effectful
    val declared = Map("foo" -> Set("Logger"))
    // analysis says foo IS effectful — no divergence
    assert(EffectAnalysis.verify(declared, result).isEmpty)
  }
