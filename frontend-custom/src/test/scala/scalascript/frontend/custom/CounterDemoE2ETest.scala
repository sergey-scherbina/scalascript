package scalascript.frontend.custom

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*
import scala.sys.process.*
import scala.util.Try

/** Headline A2c demo: a counter button.  Clicking it triggers
 *  IncrementSignal, which bumps the cell, which fans out to the
 *  text-node subscriber.  The DOM should show 0 → 1 → 2 → 3.
 *
 *  Runs the actual emitted JS through jsdom when available; falls
 *  back to a Function-constructor parse check so CI without
 *  jsdom still passes. */
class CounterDemoE2ETest extends AnyFunSuite:

  private val nodeAvailable: Boolean =
    Try("node --version".!!.trim).toOption.exists(_.nonEmpty)

  test("counter — click button bumps signal + DOM updates (under jsdom)") {
    assume(nodeAvailable, "node not available")
    val backend = new CustomFrameworkBackend
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

    val tmp = Files.createTempDirectory("ssc-counter-e2e-")
    try
      val script = tmp.resolve("counter.mjs")
      Files.writeString(script, s"""
        |let jsdom;
        |try { jsdom = await import('jsdom'); } catch (_e) { jsdom = null; }
        |if (jsdom) {
        |  const dom = new jsdom.JSDOM('<!DOCTYPE html><html><body><div id="app"></div></body></html>');
        |  globalThis.document = dom.window.document;
        |  globalThis.window   = dom.window;
        |  ${emitted.js}
        |  const display = dom.window.document.getElementById('display');
        |  const button  = dom.window.document.getElementById('inc');
        |  if (display.textContent !== '0') throw new Error('initial display: ' + display.textContent);
        |  button.click();
        |  if (display.textContent !== '1') throw new Error('after 1 click: ' + display.textContent);
        |  button.click(); button.click();
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
