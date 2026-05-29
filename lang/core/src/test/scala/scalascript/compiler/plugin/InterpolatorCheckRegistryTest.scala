package scalascript.compiler.plugin

import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.{Diagnostic, InterpolatorCheck}
import scalascript.parser.Parser
import scalascript.transform.MarkupInterpolatorCheck

class InterpolatorCheckRegistryTest extends AnyFunSuite:

  test("built-in xml check is registered"):
    assert(InterpolatorCheckRegistry.checksFor("xml").nonEmpty)

  test("custom check runs through MarkupInterpolatorCheck traversal"):
    object DemoCheck extends InterpolatorCheck:
      def interpolatorName: String = "demo_check_registry"
      def check(parts: List[String]): List[Diagnostic] =
        List(Diagnostic.Generic(s"demo:${parts.mkString("|")}"))

    InterpolatorCheckRegistry.register(DemoCheck)
    val module = Parser.parse(
      """# Test
        |
        |```scalascript
        |val x = demo_check_registry"hello ${name}"
        |```
        |""".stripMargin
    )
    val diags = MarkupInterpolatorCheck.check(module)
    assert(diags.exists {
      case Diagnostic.Generic("demo:hello |", _) => true
      case _                                     => false
    })
