package scalascript.frontend.solid

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*
import scala.sys.process.*
import scala.util.Try

/** A2e.2 E2E — per-item delete button on Solid.  Same scenario as
 *  Custom / React but driven through createEffect's wipe-and-rebuild
 *  pass.  Requires `--conditions=browser` for Solid's reactive
 *  runtime. */
class RichForSignalE2ETest extends AnyFunSuite:

  private val nodeAvailable: Boolean =
    Try("node --version".!!.trim).toOption.exists(_.nonEmpty)

  test("per-item delete button drops the clicked slot via emitted Solid JS") {
    assume(nodeAvailable, "node not available")
    val backend = new SolidFrameworkBackend
    val todos = new ReactiveSignalList[String]("todos", Seq("a", "b", "c"))
    val template = View.Element("li", Map.empty, Map.empty, Seq(
      View.ItemText,
      View.Element("button",
        Map.empty,
        Map("click" -> EventHandler.RemoveSelfFromList(todos)),
        Seq(View.TextNode(() => "x"))
      )
    ))
    val app = ComponentDef("App", Nil, _ => View.Element(
      "ul", Map("id" -> AttrValue.Str("list")), Map.empty,
      Seq(View.ForSignal(todos, itemTemplate = Some(template)))
    ))
    val emitted = backend.emit(FrontendModule(List(app), "App", "/"))

    val tmp = Files.createTempDirectory("ssc-richforsignal-solid-")
    try
      val script = tmp.resolve("check.mjs")
      val bundleBody = emitted.js
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
        |    $bundleBody
        |    App(dom.window.document.getElementById('app'));
        |  });
        |
        |  const list = dom.window.document.getElementById('list');
        |  const items = () => Array.from(list.querySelectorAll('li')).map(li => li.firstChild.textContent);
        |  if (JSON.stringify(items()) !== JSON.stringify(['a', 'b', 'c']))
        |    throw new Error('initial: ' + JSON.stringify(items()));
        |  list.querySelectorAll('li')[1].querySelector('button').click();
        |  if (JSON.stringify(items()) !== JSON.stringify(['a', 'c']))
        |    throw new Error('after delete b: ' + JSON.stringify(items()));
        |  list.querySelectorAll('li')[0].querySelector('button').click();
        |  if (JSON.stringify(items()) !== JSON.stringify(['c']))
        |    throw new Error('after delete a: ' + JSON.stringify(items()));
        |  console.log('OK');
        |} else {
        |  const src = ${"\"" + escapeForJs(emitted.js) + "\""};
        |  const noImports = src.split('\\n').filter(l => !l.startsWith('import ')).join('\\n');
        |  new Function(noImports);
        |  console.log('PARSE_OK');
        |}
        |""".stripMargin)
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
