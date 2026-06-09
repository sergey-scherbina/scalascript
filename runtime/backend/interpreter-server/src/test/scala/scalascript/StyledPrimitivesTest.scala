package scalascript

import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** ui-styled-p2-nodes + p3-lower (busi UI proposals P2).  Runs the styled
 *  primitives example through the real driver, emits the lowered tree to HTML
 *  under default + dark themes, and asserts the CSS is **token-resolved** (no
 *  hardcoded values), per-axis padding works, box sizing works, and the same
 *  tree re-themes under darkTheme — the whole point of token-aware styling. */
class StyledPrimitivesTest extends AnyFunSuite:

  private def runExample(): Unit =
    val src = os.read(TestPaths.repoRoot / "examples" / "frontend" / "std-ui" / "styled-primitives.ssc")
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(out = ps, headless = true,
                baseDir = Some(TestPaths.repoRoot)).run(Parser.parse(src))
    ps.flush()
    assert(buf.toString.contains("styled:ok"), s"example did not complete:\n${buf.toString}")

  // emit writes a shell index.html + an app.js that builds the View; the inline
  // style strings live in app.js, so read both.
  private def html(dir: String): String =
    Files.readString(Path.of(dir, "index.html")) + "\n" + Files.readString(Path.of(dir, "app.js"))

  // emit renders the View's inline styles as a JS style object (camelCase keys,
  // quoted values): the CSS `background:#16a34a;` becomes `background: '#16a34a'`.
  test("styled primitives lower to token-resolved styles (default theme)"):
    runExample()
    val out = html("/tmp/ssc-styled-default")
    // badge success → defaultTheme.colors.success, caption font (12px)
    assert(out.contains("background: '#16a34a'"), s"badge success colour not themed:\n$out")
    assert(out.contains("fontSize: '12px'"), "caption font (badge/kpi label) not 12px")
    // styled(): bg surface, radius md=8, paddingY smd=12, paddingX 16
    assert(out.contains("background: '#f9fafb'"), "styled bg=surface not resolved")
    assert(out.contains("borderRadius: '8px'"), "styled radius=md not resolved")
    assert(out.contains("paddingTop: '12px'") && out.contains("paddingBottom: '12px'"), "paddingY smd wrong")
    assert(out.contains("paddingLeft: '16px'") && out.contains("paddingRight: '16px'"), "paddingX 16 wrong")
    // tag warning, pill danger
    assert(out.contains("background: '#d97706'"), "tag warning not themed")
    assert(out.contains("background: '#dc2626'"), "pill danger not themed")
    // box maxWidth (px, breakpoint)
    assert(out.contains("maxWidth: '960px'"), "box maxWidth not applied")
    // tabBar renders both tabs
    assert(out.contains("Home") && out.contains("Tx"), "tabBar tabs missing")

  test("same tree re-themes under darkTheme (token re-resolution)"):
    runExample()
    val out = html("/tmp/ssc-styled-dark")
    // badge success now darkTheme.colors.success; styled bg now dark surface
    assert(out.contains("background: '#22c55e'"), s"badge success not re-themed for dark:\n$out")
    assert(out.contains("background: '#1f2937'"), "styled bg=surface not re-themed for dark")
    // and NOT the light values — proves tokens, not baked hex
    assert(!out.contains("background: '#16a34a'"), "light success colour leaked into dark theme")
