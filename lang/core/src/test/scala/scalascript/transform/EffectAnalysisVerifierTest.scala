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

  test("verify: no warning when function declares effects but body is pure (sub-effecting valid)") {
    // A function may declare an effect it doesn't actually use — that's valid (sub-effecting).
    // Only the reverse (effectful body with no declared row) is an error.
    val result   = EffectAnalysis.Result(Set("Logger.log"), Set.empty)
    val declared = Map("foo" -> Set("Logger"))
    val warnings = EffectAnalysis.verify(declared, result)
    assert(warnings.isEmpty, s"expected no warnings, got: $warnings")
  }

  test("verify: no warnings when declared effectful and analysis agrees") {
    val result   = EffectAnalysis.Result(Set("Logger.log"), Set("foo"))
    // foo declares Logger and analysis says foo is effectful
    val declared = Map("foo" -> Set("Logger"))
    // analysis says foo IS effectful — no divergence
    assert(EffectAnalysis.verify(declared, result).isEmpty)
  }
