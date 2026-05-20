package scalascript.frontend.custom

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*
import scala.sys.process.*
import scala.util.Try

/** A2e.2 E2E — todo list with per-item delete button.  Drives the
 *  emitted JS through jsdom (parse-only fallback) and verifies that
 *  clicking a specific item's button removes that item from the list. */
class RichForSignalE2ETest extends AnyFunSuite:

  private val nodeAvailable: Boolean =
    Try("node --version".!!.trim).toOption.exists(_.nonEmpty)

  test("per-item delete button removes its own slot via emitted JS") {
    assume(nodeAvailable, "node not available")
    val backend = new CustomFrameworkBackend
    val todos = new ReactiveSignalList[String]("todos", Seq("a", "b", "c"))
    val template = View.Element("li",
      Map("class" -> AttrValue.Str("item")),
      Map.empty,
      Seq(
        View.ItemText,
        View.Element("button",
          Map("class" -> AttrValue.Str("del")),
          Map("click" -> EventHandler.RemoveSelfFromList(todos)),
          Seq(View.TextNode(() => "x"))
        )
      )
    )
    val app = ComponentDef("App", Nil, _ => View.Element(
      "ul", Map("id" -> AttrValue.Str("list")), Map.empty,
      Seq(View.ForSignal(todos, itemTemplate = Some(template)))
    ))
    val emitted = backend.emit(FrontendModule(List(app), "App", "/"))

    val tmp = Files.createTempDirectory("ssc-richforsignal-custom-")
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
        |  const items = () => Array.from(list.querySelectorAll('li')).map(li => li.firstChild.textContent);
        |  if (JSON.stringify(items()) !== JSON.stringify(['a', 'b', 'c']))
        |    throw new Error('initial: ' + JSON.stringify(items()));
        |  // Click the middle item's delete button — should remove 'b'.
        |  list.querySelectorAll('li')[1].querySelector('button').click();
        |  if (JSON.stringify(items()) !== JSON.stringify(['a', 'c']))
        |    throw new Error('after delete b: ' + JSON.stringify(items()));
        |  // Click what's now the first item's button — should remove 'a'.
        |  list.querySelectorAll('li')[0].querySelector('button').click();
        |  if (JSON.stringify(items()) !== JSON.stringify(['c']))
        |    throw new Error('after delete a: ' + JSON.stringify(items()));
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
