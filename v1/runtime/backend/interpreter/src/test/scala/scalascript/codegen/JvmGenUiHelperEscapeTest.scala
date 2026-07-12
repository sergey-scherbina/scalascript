package scalascript.codegen

import org.scalatest.funsuite.AnyFunSuite

final class JvmGenUiHelperEscapeTest extends AnyFunSuite:
  test("SwiftUI helper emits compilable dotted-row regex literals"):
    val source = new JvmGen().uiHelperFunctions(
      frontendName = "swiftui",
      appIcon = None,
      bundleId = None,
      displayName = None,
      version = None,
      models = Nil,
    )
    val expected = "split(\"\\\\.\", -1)"
    assert(source.sliding(expected.length).count(_ == expected) == 2, source)
    assert(!source.contains("split(\"\\.\", -1)"), source)
