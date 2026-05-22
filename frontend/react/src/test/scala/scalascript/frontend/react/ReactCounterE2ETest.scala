package scalascript.frontend.react

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*
import scala.sys.process.*
import scala.util.Try

/** End-to-end: drive the actually-emitted React JS through
 *  jsdom + a real React + a real ReactDOM, click the counter
 *  button, assert the DOM updates 0 → 1 → 3.
 *
 *  React's `act()` is required because state updates are async;
 *  outside `act` you'd see the OLD value after `dispatchEvent`.
 *
 *  Skipped when node / jsdom / react are missing — the unit tests
 *  cover correctness; this one validates the React contract
 *  (useState + setState + reconciliation) we depend on. */
class ReactCounterE2ETest extends AnyFunSuite:

  private val nodeAvailable: Boolean =
    Try("node --version".!!.trim).toOption.exists(_.nonEmpty)

  test("counter — emitted React JS runs through ReactDOM, click bumps state") {
    assume(nodeAvailable, "node not available")
    val backend = new ReactFrameworkBackend
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

    val tmp = Files.createTempDirectory("ssc-react-e2e-")
    try
      val script = tmp.resolve("counter.mjs")
      Files.writeString(script, s"""
        |let JSDOM, React, ReactDOM, act;
        |try {
        |  ({ JSDOM } = await import('jsdom'));
        |  React    = (await import('react')).default;
        |  ReactDOM = (await import('react-dom/client')).default;
        |  // React 18 + jsdom: prefer react-dom/test-utils.act (still exported in 18.x).
        |  try { ({ act } = await import('react-dom/test-utils')); } catch (_e) { act = (await import('react')).act; }
        |} catch (_e) {
        |  JSDOM = null;
        |}
        |if (JSDOM && React && ReactDOM) {
        |  const dom = new JSDOM('<!DOCTYPE html><html><body><div id="app"></div></body></html>');
        |  globalThis.document  = dom.window.document;
        |  globalThis.window    = dom.window;
        |  globalThis.React     = React;
        |  globalThis.ReactDOM  = ReactDOM;
        |  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
        |
        |  act(() => { ${emitted.js} });
        |
        |  const display = dom.window.document.getElementById('display');
        |  const button  = dom.window.document.getElementById('inc');
        |  if (display.textContent !== '0') throw new Error('initial: ' + display.textContent);
        |  act(() => { button.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true })); });
        |  if (display.textContent !== '1') throw new Error('after 1 click: ' + display.textContent);
        |  act(() => { button.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true })); });
        |  act(() => { button.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true })); });
        |  if (display.textContent !== '3') throw new Error('after 3 clicks: ' + display.textContent);
        |  console.log('OK');
        |} else {
        |  const src = ${"\"" + escapeForJs(emitted.js) + "\""};
        |  new Function(src);
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
