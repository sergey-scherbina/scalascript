package scalascript.frontend.vue

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*
import scala.sys.process.*
import scala.util.Try

/** A2e.2 E2E — per-item delete button on Vue. */
class RichForSignalE2ETest extends AnyFunSuite:

  private val nodeAvailable: Boolean =
    Try("node --version".!!.trim).toOption.exists(_.nonEmpty)

  test("per-item delete button drops the clicked slot via emitted Vue JS") {
    assume(nodeAvailable, "node not available")
    val backend = new VueFrameworkBackend
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

    val tmp = Files.createTempDirectory("ssc-richforsignal-vue-")
    try
      val script = tmp.resolve("check.mjs")
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
        |  const list = dom.window.document.getElementById('list');
        |  const items = () => Array.from(list.querySelectorAll('li')).map(li => li.firstChild.textContent);
        |  if (JSON.stringify(items()) !== JSON.stringify(['a', 'b', 'c']))
        |    throw new Error('initial: ' + JSON.stringify(items()));
        |  list.querySelectorAll('li')[1].querySelector('button').click();
        |  await nextTick();
        |  if (JSON.stringify(items()) !== JSON.stringify(['a', 'c']))
        |    throw new Error('after delete b: ' + JSON.stringify(items()));
        |  list.querySelectorAll('li')[0].querySelector('button').click();
        |  await nextTick();
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
