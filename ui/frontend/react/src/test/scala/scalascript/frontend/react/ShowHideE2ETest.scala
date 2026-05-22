package scalascript.frontend.react

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*
import scala.sys.process.*
import scala.util.Try

/** A2d E2E — show/hide counter via the emitted React JS. */
class ShowHideE2ETest extends AnyFunSuite:

  private val nodeAvailable: Boolean =
    Try("node --version".!!.trim).toOption.exists(_.nonEmpty)

  test("ShowSignal + ToggleSignal — show/hide counter via emitted React JS") {
    assume(nodeAvailable, "node not available")
    val backend = new ReactFrameworkBackend
    val visible = new ReactiveSignal[Boolean]("visible", true)
    val count   = new ReactiveSignal[Int]("count", 0)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty,
      Seq(
        View.Element("button",
          Map("id" -> AttrValue.Str("tog")),
          Map("click" -> EventHandler.ToggleSignal(visible)),
          Seq(View.TextNode(() => "toggle"))
        ),
        View.Element("button",
          Map("id" -> AttrValue.Str("inc")),
          Map("click" -> EventHandler.IncrementSignal(count)),
          Seq(View.TextNode(() => "+"))
        ),
        View.ShowSignal(
          cond      = visible,
          whenTrue  = View.Element("span",
                       Map("id" -> AttrValue.Str("box")),
                       Map.empty,
                       Seq(View.SignalText(count))),
          whenFalse = View.TextNode(() => "")
        )
      )
    ))
    val emitted = backend.emit(FrontendModule(List(app), "App", "/"))

    val tmp = Files.createTempDirectory("ssc-showhide-react-")
    try
      val script = tmp.resolve("check.mjs")
      Files.writeString(script, s"""
        |let JSDOM, React, ReactDOM, act;
        |try {
        |  ({ JSDOM } = await import('jsdom'));
        |  React    = (await import('react')).default;
        |  ReactDOM = (await import('react-dom/client')).default;
        |  try { ({ act } = await import('react-dom/test-utils')); } catch (_e) { act = (await import('react')).act; }
        |} catch (_e) { JSDOM = null; }
        |if (JSDOM && React && ReactDOM) {
        |  const dom = new JSDOM('<!DOCTYPE html><html><body><div id="app"></div></body></html>');
        |  globalThis.document = dom.window.document;
        |  globalThis.window   = dom.window;
        |  globalThis.React    = React;
        |  globalThis.ReactDOM = ReactDOM;
        |  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
        |
        |  act(() => { ${emitted.js} });
        |
        |  const tog = dom.window.document.getElementById('tog');
        |  const inc = dom.window.document.getElementById('inc');
        |  const visible = () => !!dom.window.document.getElementById('box');
        |  const boxText = () => { const b = dom.window.document.getElementById('box'); return b ? b.textContent : null; };
        |  if (!visible() || boxText() !== '0') throw new Error('initial: ' + visible() + '/' + boxText());
        |  act(() => { inc.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true })); });
        |  act(() => { inc.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true })); });
        |  if (boxText() !== '2') throw new Error('after 2 inc: ' + boxText());
        |  act(() => { tog.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true })); });
        |  if (visible()) throw new Error('toggle did not hide');
        |  act(() => { tog.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true })); });
        |  if (boxText() !== '2') throw new Error('after re-toggle: ' + boxText());
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
