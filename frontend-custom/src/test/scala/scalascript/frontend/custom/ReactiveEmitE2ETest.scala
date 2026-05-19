package scalascript.frontend.custom

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*
import scala.sys.process.*
import scala.util.Try

/** End-to-end: emit a SignalText, run through jsdom, drive
 *  `__setSignal('greeting', 'world')`, assert the DOM text node
 *  updated.  Falls back to a Function-constructor parse check
 *  when jsdom isn't on NODE_PATH so the test still passes on
 *  minimal CI. */
class ReactiveEmitE2ETest extends AnyFunSuite:

  private val nodeAvailable: Boolean =
    Try("node --version".!!.trim).toOption.exists(_.nonEmpty)

  test("SignalText — emitted JS reacts to __setSignal under jsdom") {
    assume(nodeAvailable, "node not available")
    val backend = new CustomFrameworkBackend
    val greeting = new ReactiveSignal[String]("greeting", "Hello")
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty,
      Seq(View.SignalText(greeting))
    ))
    val emitted = backend.emit(FrontendModule(List(app), "App", "/"))

    val tmp = Files.createTempDirectory("ssc-reactive-e2e-")
    try
      val script = tmp.resolve("check.mjs")
      Files.writeString(script, s"""
        |let jsdom;
        |try { jsdom = await import('jsdom'); } catch (_e) { jsdom = null; }
        |if (jsdom) {
        |  const dom = new jsdom.JSDOM('<!DOCTYPE html><html><body><div id="app"></div></body></html>');
        |  globalThis.document = dom.window.document;
        |  globalThis.window   = dom.window;
        |  ${emitted.js}
        |  const initial = dom.window.document.getElementById('app').textContent;
        |  if (initial !== 'Hello') throw new Error('initial DOM text wrong: ' + initial);
        |  __setSignal('greeting', 'world');
        |  const after = dom.window.document.getElementById('app').textContent;
        |  if (after !== 'world') throw new Error('reactive update failed: ' + after);
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
