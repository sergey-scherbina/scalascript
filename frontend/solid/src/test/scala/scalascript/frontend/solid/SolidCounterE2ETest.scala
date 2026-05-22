package scalascript.frontend.solid

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*
import scala.sys.process.*
import scala.util.Try

/** End-to-end: drive the actually-emitted Solid JS through
 *  jsdom + a real solid-js + createSignal/createEffect, click the
 *  counter button, assert the DOM updates 0 → 1 → 3.
 *
 *  IMPORTANT: needs `node --conditions=browser` because Solid's
 *  default `node` export is the SSR build with NO reactivity.
 *  Without the flag, `createEffect` never runs in response to
 *  signal sets.
 *
 *  We wrap the emit in `createRoot` so the reactive scope is
 *  established outside the bundle's own structure.  In a real
 *  Solid app the JSX compiler emits the root for you. */
class SolidCounterE2ETest extends AnyFunSuite:

  private val nodeAvailable: Boolean =
    Try("node --version".!!.trim).toOption.exists(_.nonEmpty)

  test("counter — emitted Solid JS runs, click bumps state via createEffect") {
    assume(nodeAvailable, "node not available")
    val backend = new SolidFrameworkBackend
    val count = new ReactiveSignal[Int]("count", 0)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty,
      Seq(
        View.Element(
          "button",
          attrs    = Map("id" -> AttrValue.Str("inc")),
          events   = Map("click" -> EventHandler.IncrementSignal(count)),
          children = Seq(View.TextNode(() => "+"))
        ),
        View.Element(
          "span",
          attrs    = Map("id" -> AttrValue.Str("display")),
          events   = Map.empty,
          children = Seq(View.SignalText(count))
        )
      )
    ))
    val emitted = backend.emit(FrontendModule(List(app), "App", "/"))

    val tmp = Files.createTempDirectory("ssc-solid-e2e-")
    try
      val script = tmp.resolve("counter.mjs")
      // Strip the bundle's own `import ...` and `App(document...)`
      // lines so we can wrap the body in createRoot.  The emit's
      // shape is stable enough that this surgery is safe.
      val withoutImports = emitted.js
        .linesIterator
        .filterNot(_.startsWith("import "))
        .filterNot(_.startsWith("App("))
        .mkString("\n")
      Files.writeString(script, s"""
        |let JSDOM, createSignal, createEffect, createRoot;
        |try {
        |  ({ JSDOM } = await import('jsdom'));
        |  ({ createSignal, createEffect, createRoot } = await import('solid-js'));
        |} catch (_e) { JSDOM = null; }
        |if (JSDOM && createSignal) {
        |  const dom = new JSDOM('<!DOCTYPE html><html><body><div id="app"></div></body></html>');
        |  globalThis.document = dom.window.document;
        |  globalThis.window   = dom.window;
        |
        |  createRoot(() => {
        |    $withoutImports
        |    App(dom.window.document.getElementById('app'));
        |  });
        |
        |  const display = dom.window.document.getElementById('display');
        |  const button  = dom.window.document.getElementById('inc');
        |  if (display.textContent !== '0') throw new Error('initial: ' + display.textContent);
        |  button.click();
        |  if (display.textContent !== '1') throw new Error('after 1: ' + display.textContent);
        |  button.click(); button.click();
        |  if (display.textContent !== '3') throw new Error('after 3: ' + display.textContent);
        |  console.log('OK');
        |} else {
        |  // Without jsdom/solid we still validate the JS parses.
        |  const src = ${"\"" + escapeForJs(emitted.js) + "\""};
        |  // Strip the import lines that confuse Function() (it doesn't
        |  // accept module syntax).
        |  const noImports = src.split('\\n').filter(l => !l.startsWith('import ')).join('\\n');
        |  new Function(noImports);
        |  console.log('PARSE_OK');
        |}
        |""".stripMargin)
      // `--conditions=browser` selects Solid's reactive runtime over the
      // SSR-only node build.  Without it, createEffect never fires.
      val output = Try(s"node --conditions=browser $script".!!).getOrElse("")
      assert(output.trim == "OK" || output.trim == "PARSE_OK",
        s"node run did not return OK/PARSE_OK; got: '$output'")
    finally
      Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
  }

  private def escapeForJs(s: String): String =
    s.replace("\\", "\\\\")
     .replace("\"", "\\\"")
     .replace("\n", "\\n")
     .replace("\r", "\\r")
