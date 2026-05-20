package scalascript.frontend.custom

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*
import scala.sys.process.*
import scala.util.Try

/** A2e E2E — todo-list-style demo through the emitted JS.
 *  Two buttons: push appends a literal, clear empties the list.
 *  The wrapper span's children stay in lock-step with the list. */
class ForSignalE2ETest extends AnyFunSuite:

  private val nodeAvailable: Boolean =
    Try("node --version".!!.trim).toOption.exists(_.nonEmpty)

  test("ForSignal — push + clear update the rendered children via emitted JS") {
    assume(nodeAvailable, "node not available")
    val backend = new CustomFrameworkBackend
    val todos = new ReactiveSignalList[String]("todos", Seq("first"))
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty,
      Seq(
        View.Element("button",
          Map("id" -> AttrValue.Str("add")),
          Map("click" -> EventHandler.PushSignalLiteral(todos, "new")),
          Seq(View.TextNode(() => "add"))
        ),
        View.Element("button",
          Map("id" -> AttrValue.Str("clr")),
          Map("click" -> EventHandler.ClearSignalList(todos)),
          Seq(View.TextNode(() => "clear"))
        ),
        View.Element("ul",
          Map("id" -> AttrValue.Str("list")),
          Map.empty,
          Seq(View.ForSignal(todos))
        )
      )
    ))
    val emitted = backend.emit(FrontendModule(List(app), "App", "/"))

    val tmp = Files.createTempDirectory("ssc-forsignal-e2e-")
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
        |  const list = dom.window.document.getElementById('list');
        |  const add  = dom.window.document.getElementById('add');
        |  const clr  = dom.window.document.getElementById('clr');
        |  const items = () => Array.from(list.querySelectorAll('li')).map(li => li.textContent);
        |  const initial = items();
        |  if (JSON.stringify(initial) !== JSON.stringify(['first']))
        |    throw new Error('initial: ' + JSON.stringify(initial));
        |  add.click(); add.click();
        |  const after = items();
        |  if (JSON.stringify(after) !== JSON.stringify(['first', 'new', 'new']))
        |    throw new Error('after 2 add: ' + JSON.stringify(after));
        |  clr.click();
        |  const cleared = items();
        |  if (cleared.length !== 0)
        |    throw new Error('after clear: ' + JSON.stringify(cleared));
        |  add.click();
        |  const refilled = items();
        |  if (JSON.stringify(refilled) !== JSON.stringify(['new']))
        |    throw new Error('after refill: ' + JSON.stringify(refilled));
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
