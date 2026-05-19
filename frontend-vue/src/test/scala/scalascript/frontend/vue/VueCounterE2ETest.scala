package scalascript.frontend.vue

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*
import scala.sys.process.*
import scala.util.Try

/** End-to-end: drive the actually-emitted Vue JS through jsdom +
 *  real vue@3.4, click the counter button, await `nextTick` for
 *  Vue's scheduled update, assert 0 → 1 → 3.
 *
 *  Falls back to a Function-constructor parse check when jsdom
 *  / vue isn't on NODE_PATH so CI without them still passes. */
class VueCounterE2ETest extends AnyFunSuite:

  private val nodeAvailable: Boolean =
    Try("node --version".!!.trim).toOption.exists(_.nonEmpty)

  test("counter — emitted Vue JS runs, click bumps state via proxy assignment") {
    assume(nodeAvailable, "node not available")
    val backend = new VueFrameworkBackend
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

    val tmp = Files.createTempDirectory("ssc-vue-e2e-")
    try
      val script = tmp.resolve("counter.mjs")
      // The bundle does `import ... from 'vue'` + a top-level
      // `createApp(App).mount(...)`.  For the test we inline the
      // imports manually and run the rest.  Replace 'vue' import
      // with our dynamic import path.
      val bundleBody = emitted.js
        .linesIterator
        .filterNot(_.startsWith("import "))
        .mkString("\n")
      Files.writeString(script, s"""
        |let JSDOM, ref, h, Fragment, createApp, nextTick;
        |try {
        |  ({ JSDOM } = await import('jsdom'));
        |  ({ ref, h, Fragment, createApp, nextTick } = await import('vue'));
        |} catch (_e) { JSDOM = null; }
        |if (JSDOM && createApp) {
        |  const dom = new JSDOM('<!DOCTYPE html><html><body><div id="app"></div></body></html>');
        |  globalThis.document     = dom.window.document;
        |  globalThis.window       = dom.window;
        |  globalThis.HTMLElement  = dom.window.HTMLElement;
        |  globalThis.Element      = dom.window.Element;
        |  globalThis.Node         = dom.window.Node;
        |  globalThis.SVGElement   = dom.window.SVGElement;
        |  globalThis.MouseEvent   = dom.window.MouseEvent;
        |
        |  $bundleBody
        |  await nextTick();
        |
        |  const display = dom.window.document.getElementById('display');
        |  const button  = dom.window.document.getElementById('inc');
        |  if (display.textContent !== '0') throw new Error('initial: ' + display.textContent);
        |  button.click();
        |  await nextTick();
        |  if (display.textContent !== '1') throw new Error('after 1: ' + display.textContent);
        |  button.click(); button.click();
        |  await nextTick();
        |  if (display.textContent !== '3') throw new Error('after 3: ' + display.textContent);
        |  console.log('OK');
        |} else {
        |  const src = ${"\"" + escapeForJs(emitted.js) + "\""};
        |  const noImports = src.split('\\n').filter(l => !l.startsWith('import ')).join('\\n');
        |  new Function(noImports);
        |  console.log('PARSE_OK');
        |}
        |""".stripMargin)
      val output = Try(s"node $script".!!).getOrElse("")
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
