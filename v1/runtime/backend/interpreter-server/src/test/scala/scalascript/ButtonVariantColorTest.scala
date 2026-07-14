package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser
import scalascript.frontend.{View, AttrValue}
import scalascript.interpreter.Value

/** std-ui-button-variant (specs/std-ui-button-variant.md): `View` is opaque to `.ssc`
 *  (see specs/std-ui-select.md "Why no new extern def"), so `tests/conformance/
 *  tkv2-button-variant.ssc` can only assert the `variant` field threads through the
 *  constructor and that `lower()` doesn't throw — it cannot inspect the rendered CSS
 *  string. This test closes that gap at the Scala level: it runs the interpreter,
 *  extracts the real `frontend.core.View.Element`'s "style" attribute, and asserts the
 *  background colour literal genuinely differs per variant (not just that the code
 *  path executes without error). */
class ButtonVariantColorTest extends AnyFunSuite:

  def bgOf(variant: String): String =
    val src =
      s"""# button-variant-color-probe
        |
        |[actionButton](std/ui/input.ssc)
        |[lower](std/ui/lower.ssc)
        |[defaultTheme](std/ui/theme.ssc)
        |[setSignal, signal](std/ui/primitives.ssc)
        |
        |```scalascript
        |val sig = signal("probeSig", "")
        |val h = setSignal(sig, "x")
        |lower(actionButton(h, "L", false, "$variant"), defaultTheme)
        |```
        |""".stripMargin
    val interp = Interpreter(out = java.io.PrintStream(java.io.ByteArrayOutputStream(), true),
                              headless = true, baseDir = Some(TestPaths.repoRoot))
    interp.run(Parser.parse(src))
    interp.lastResult match
      case Value.Foreign("View", v: View[?]) =>
        v match
          case View.Element(tag, attrs, _, _) =>
            attrs("style") match
              case AttrValue.Str(s) => s
              case other            => fail(s"style attr not a Str: $other")
          case other => fail(s"not a View.Element: $other")
      case other => fail(s"lastResult not a View Foreign: $other")

  test("actionButton variant resolves to a genuinely different background colour per variant") {
    val primary   = bgOf("primary")
    val secondary = bgOf("secondary")
    val danger    = bgOf("danger")
    val success   = bgOf("success")
    val warning   = bgOf("warning")
    val bogus     = bgOf("some-typo")

    assert(primary.contains("background:#2563eb"))
    assert(secondary.contains("background:#7c3aed"))
    assert(danger.contains("background:#dc2626"))
    assert(success.contains("background:#16a34a"))
    assert(warning.contains("background:#d97706"))
    assert(bogus == primary, "unrecognized variant must fall back to primary, byte for byte")

    val all = Set(primary, secondary, danger, success, warning)
    assert(all.size == 5, "all five variants must produce distinct style strings")
  }
