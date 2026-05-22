package scalascript.frontend.vue

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*
import scala.sys.process.*
import scala.util.Try

/** A2e E2E — todo-list-style demo via the emitted Vue JS. */
class ForSignalE2ETest extends AnyFunSuite:

  private val nodeAvailable: Boolean =
    Try("node --version".!!.trim).toOption.exists(_.nonEmpty)

  test("ForSignal — push + clear update the rendered <li>s via emitted Vue JS") {
    assume(nodeAvailable, "node not available")
    val backend = new VueFrameworkBackend
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

    val tmp = Files.createTempDirectory("ssc-forsignal-vue-")
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
        |  const add  = dom.window.document.getElementById('add');
        |  const clr  = dom.window.document.getElementById('clr');
        |  const items = () => Array.from(list.querySelectorAll('li')).map(li => li.textContent);
        |  if (JSON.stringify(items()) !== JSON.stringify(['first']))
        |    throw new Error('initial: ' + JSON.stringify(items()));
        |  add.click(); add.click();
        |  await nextTick();
        |  if (JSON.stringify(items()) !== JSON.stringify(['first', 'new', 'new']))
        |    throw new Error('after 2 add: ' + JSON.stringify(items()));
        |  clr.click();
        |  await nextTick();
        |  if (items().length !== 0) throw new Error('after clear: ' + JSON.stringify(items()));
        |  add.click();
        |  await nextTick();
        |  if (JSON.stringify(items()) !== JSON.stringify(['new']))
        |    throw new Error('after refill: ' + JSON.stringify(items()));
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
