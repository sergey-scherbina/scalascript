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

  private def nodeAvailable: Boolean =
    try os.proc("node", "--version").call(check = false).exitCode == 0
    catch case _: Throwable => false

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

  // Realistic shape: imports the std/ui primitives (so `serve`/`element`/`textNode`
  // bind to the SPA runtime), like a real UCC app.
  private val viewModuleImported =
    """---
      |frontend: react
      |---
      |
      |# App
      |
      |[signal, serve, element, textNode](std/ui/primitives.ssc)
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
      os.write(f, viewModuleImported)
      withReact {
        val html = renderSpaHtml(f, Some("http://127.0.0.1:8411"))
        assert(html.contains("hello UCC"), s"view not rendered (blank page) for def view():\n${html.takeRight(2000)}")
        assert(html.contains("__sscBackendBaseUrl"), s"--server-url base not injected:\n$html")
      }
    finally os.remove.all(dir)
  }

  // Stronger: actually EXECUTE the client-mode SPA against a DOM stub and assert
  // the #app element receives the rendered body — proves the mount runs (the
  // generic `_ssc_ui_mount` path, NOT React `createRoot`), not just that the
  // view-building code is present in the bundle.
  test("def view() client-mode SPA actually MOUNTS into #app (node execution)") {
    assume(nodeAvailable, "node not on PATH — skipping client-mode mount execution")
    val dir = os.temp.dir(prefix = "ssc-e3x-")
    try
      val f = dir / "app.ssc"
      os.write(f, viewModuleImported)
      val html = withReact { renderSpaHtml(f, Some("http://x:8411")) }
      val script = html.substring(html.indexOf("<script>") + 8, html.lastIndexOf("</script>"))
      val stub =
        """function El(t){this.tag=t;this.style={};this.children=[];this._html='';this.attrs={};
          |this.appendChild=function(c){this.children.push(c);};this.setAttribute=function(k,v){this.attrs[k]=v;};
          |this.getAttribute=function(k){return this.attrs[k]||null;};this.addEventListener=function(){};
          |Object.defineProperty(this,'innerHTML',{get:function(){return this._html;},set:function(v){this._html=v;}});
          |this.textContent='';this.querySelector=function(){return null;};this.querySelectorAll=function(){return [];};
          |this.remove=function(){};this.classList={add:function(){},remove:function(){}};}
          |var _app=new El('div');
          |globalThis.document={createElement:function(t){return new El(t);},getElementById:function(id){return id==='app'?_app:null;},
          |head:new El('head'),body:new El('body'),querySelectorAll:function(){return [];},addEventListener:function(){}};
          |globalThis.window={location:{hash:'',href:'http://x/',pathname:'/'},addEventListener:function(){},history:{},getComputedStyle:function(){return {fontSize:'16px',fontFamily:'s'};}};
          |globalThis.location=window.location;globalThis.getComputedStyle=window.getComputedStyle;
          |""".stripMargin
      val runFile = dir / "run.js"
      os.write(runFile, stub + "\n" + script + "\n;console.log('APPHTML:'+JSON.stringify((_app.children[0]||{}).innerHTML||''));")
      val r = os.proc("node", runFile.toString).call(cwd = dir, check = false)
      assert(r.exitCode == 0, s"client-mode SPA threw on mount:\n${r.err.text()}")
      val mounted = r.out.text().linesIterator.find(_.startsWith("APPHTML:")).getOrElse("")
      assert(mounted.contains("hello UCC"),
        s"#app was NOT populated → blank client-mode page (mount did not run): $mounted")
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
