package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

/** UCC [E3] — `def view()` (no explicit serve) must render on the static codegen
 *  path (client-mode SPA / emit-spa), not just at interpret time. Before the fix
 *  it emitted no mount → a blank client-mode page. */
class AutoViewEntryClientModeTest extends AnyFunSuite:

  private def withReact[A](body: => A): A =
    applyFrontendBackend("react")
    try body finally scalascript.frontend.FrontendFrameworks.setBackend(null)

  private val viewModule =
    """---
      |frontend: react
      |---
      |
      |```scalascript
      |def view(): View =
      |  element("div", Map(), Map(), List(textNode("hello UCC")))
      |```
      |""".stripMargin

  // ── unit: the convention gate ───────────────────────────────────────────

  test("maybeInject appends a serve(view(),…) section when frontend selected + def view() + no UI entry") {
    withReact {
      val m  = Parser.parse(viewModule)
      val m2 = AutoViewEntry.maybeInject(m)
      assert(m2.sections.length == m.sections.length + 1, "expected one synthetic serve section appended")
    }
  }

  test("maybeInject is a no-op when no frontend is selected") {
    scalascript.frontend.FrontendFrameworks.setBackend(null)
    val m  = Parser.parse(viewModule)
    assert(AutoViewEntry.maybeInject(m) eq m)
  }

  test("maybeInject is a no-op when the module already calls a UI entry (explicit serve)") {
    withReact {
      val m = Parser.parse(
        """```scalascript
          |def view(): View = element("div", Map(), Map(), List(textNode("x")))
          |serve(view(), 8080)
          |```
          |""".stripMargin)
      assert(AutoViewEntry.maybeInject(m) eq m)
    }
  }

  // ── integration: the client-mode SPA actually renders the view ──────────

  test("def view() + react renders the view in the client-mode SPA (+ base-url injected)") {
    val dir = os.temp.dir(prefix = "ssc-e3-")
    try
      val f = dir / "app.ssc"
      os.write(f, viewModule)
      withReact {
        val html = renderSpaHtml(f, Some("http://127.0.0.1:8411"))
        assert(html.contains("hello UCC"), s"view not rendered (blank page) for def view():\n${html.takeRight(2000)}")
        assert(html.contains("__sscBackendBaseUrl"), s"--server-url base not injected:\n$html")
      }
    finally os.remove.all(dir)
  }

  test("no frontend → def view() is NOT auto-served (emit-js stays a plain bundle)") {
    val dir = os.temp.dir(prefix = "ssc-e3b-")
    try
      val f = dir / "app.ssc"
      os.write(f,
        """```scalascript
          |def view(): View = element("div", Map(), Map(), List(textNode("hello UCC")))
          |```
          |""".stripMargin)
      scalascript.frontend.FrontendFrameworks.setBackend(null)
      val js = compileJsSegments(f).collect { case s: scalascript.backend.spi.Segment.Code => s.code }.mkString("\n")
      assert(!js.contains("hello UCC"), s"must NOT auto-render the view without a frontend:\n$js")
    finally os.remove.all(dir)
  }
