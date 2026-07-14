package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser
import scalascript.frontend.{View, AttrValue}
import scalascript.interpreter.Value

/** std-ui-hstack-wrap (specs/std-ui-hstack-wrap.md): `View` is opaque to `.ssc`
 *  (see specs/std-ui-select.md "Why no new extern def"), so `tests/conformance/
 *  tkv2-hstack-wrap.ssc` can only assert the `wrap` field threads through the
 *  constructor and that `lower()` doesn't throw — it cannot inspect the rendered CSS
 *  string. This test closes that gap at the Scala level: it runs the interpreter,
 *  extracts the real `frontend.core.View.Element`'s "style" attribute, and asserts
 *  `flex-wrap:wrap` appears only when `wrap = true`, and that the `wrap = false` /
 *  no-argument-default style string is byte-identical to the pre-slice output. */
class HStackWrapTest extends AnyFunSuite:

  def styleOf(hstackCall: String): String =
    val src =
      s"""# hstack-wrap-probe
        |
        |[hstack](std/ui/layout.ssc)
        |[text](std/ui/typography.ssc)
        |[lower](std/ui/lower.ssc)
        |[defaultTheme](std/ui/theme.ssc)
        |
        |```scalascript
        |lower($hstackCall(text("a"), text("b")), defaultTheme)
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

  test("hstack wrap=true adds flex-wrap:wrap; wrap=false / default omit it, byte-identical") {
    val noArg    = styleOf("hstack(gap = 8)")
    val explFalse = styleOf("hstack(gap = 8, wrap = false)")
    val wrapTrue = styleOf("hstack(gap = 8, wrap = true)")

    // Pre-slice literal output for gap=8, no wrap — pinned so a regression that
    // changes the default-case string (not just adds flex-wrap) is caught.
    val preSliceStyle = "display:flex; flex-direction:row; align-items:center; gap:8px; box-sizing:border-box"

    assert(noArg == preSliceStyle, "no-`wrap`-argument call must be byte-identical to pre-slice output")
    assert(explFalse == preSliceStyle, "explicit wrap=false must be byte-identical to pre-slice output")
    assert(!noArg.contains("flex-wrap"), "default output must not mention flex-wrap at all")

    assert(wrapTrue.contains("flex-wrap:wrap"), "wrap=true must set flex-wrap:wrap")
    assert(wrapTrue == preSliceStyle + "; flex-wrap:wrap",
           "wrap=true must be exactly the pre-slice string plus the new fragment, nothing else changed")
  }
