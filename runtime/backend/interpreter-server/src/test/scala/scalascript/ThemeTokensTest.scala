package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** ui-styled-p0-theme — `SpacingScale.smd` (12) and `TypographyScale.caption`
 *  added to `theme.ssc` for the styled-TkNode work (busi UI proposals P2).
 *  Confirms the additive fields construct correctly across the three built-in
 *  themes and carry the expected (density-scaled) values. */
class ThemeTokensTest extends AnyFunSuite:

  private def run(body: String): String =
    val src =
      s"""# T
         |
         |[defaultTheme, mobileTheme, darkTheme](std/ui/theme.ssc)
         |
         |```scalascript
         |$body
         |```
         |""".stripMargin
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(out = ps, headless = true,
                baseDir = Some(TestPaths.repoRoot)).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  test("SpacingScale.smd = 12 (default/dark), 24 (mobile density)"):
    assert(run("""println(defaultTheme.spacing.smd)""") == "12")
    assert(run("""println(darkTheme.spacing.smd)""")    == "12")
    assert(run("""println(mobileTheme.spacing.smd)""")  == "24")
    // additive — md unchanged
    assert(run("""println(defaultTheme.spacing.md)""")  == "16")

  test("TypographyScale.caption is small text, scaled per theme"):
    assert(run("""println(defaultTheme.typography.caption.fontSize)""") == "12")
    assert(run("""println(mobileTheme.typography.caption.fontSize)""")  == "14")
    // body/heading unchanged
    assert(run("""println(defaultTheme.typography.body.fontSize)""")    == "16")
