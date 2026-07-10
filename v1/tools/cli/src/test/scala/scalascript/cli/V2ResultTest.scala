package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

class V2ResultTest extends AnyFunSuite:
  test("bridged portable Op fails with a bounded program-boundary diagnostic"):
    val op = _root_.ssc.PortableEffects.perform(
      "Journal.read", List(_root_.ssc.Value.StrV("acme")))

    val error = intercept[RuntimeException](V2Result.report(op))
    assert(error.getMessage == "unhandled runtime effect: Journal.read")
