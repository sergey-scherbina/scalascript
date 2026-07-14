package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser
import scalascript.frontend.{View, AttrValue}
import scalascript.interpreter.Value

/** std-ui-button-size (specs/std-ui-button-size.md): `View` is opaque to `.ssc`
 *  (see specs/std-ui-select.md "Why no new extern def"), so `tests/conformance/
 *  tkv2-button-size.ssc` can only assert the `size` field threads through the
 *  constructor and that `lower()` doesn't throw — it cannot inspect the rendered CSS
 *  string. This test closes that gap at the Scala level: it runs the interpreter,
 *  extracts the real `frontend.core.View.Element`'s "style" attribute, and asserts the
 *  font-size and padding literals genuinely differ per size (not just that the code
 *  path executes without error). */
class ButtonSizeTest extends AnyFunSuite:

  def styleOf(size: String): String =
    val src =
      s"""# button-size-probe
        |
        |[actionButton](std/ui/input.ssc)
        |[lower](std/ui/lower.ssc)
        |[defaultTheme](std/ui/theme.ssc)
        |[setSignal, signal](std/ui/primitives.ssc)
        |
        |```scalascript
        |val sig = signal("probeSig", "")
        |val h = setSignal(sig, "x")
        |lower(actionButton(h, "L", false, "primary", "$size"), defaultTheme)
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

  test("actionButton size resolves to a genuinely different font-size + padding per size") {
    val sm    = styleOf("sm")
    val md    = styleOf("md")
    val lg    = styleOf("lg")
    val bogus = styleOf("some-typo")

    assert(sm.contains("padding:4px 8px"))
    assert(sm.contains("font-size:12px"))
    assert(md.contains("padding:8px 16px"))
    assert(md.contains("font-size:16px"))
    assert(lg.contains("padding:16px 24px"))
    assert(lg.contains("font-size:24px"))
    assert(bogus == md, "unrecognized size must fall back to md, byte for byte")

    val all = Set(sm, md, lg)
    assert(all.size == 3, "all three sizes must produce distinct style strings")
  }
