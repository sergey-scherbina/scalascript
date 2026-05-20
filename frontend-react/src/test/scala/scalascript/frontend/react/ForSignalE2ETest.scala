package scalascript.frontend.react

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*
import scala.sys.process.*
import scala.util.Try

/** A2e E2E — todo-list-style demo via the emitted React JS, run
 *  through jsdom + the real react / react-dom (or parse-only when
 *  the libs aren't installed). */
class ForSignalE2ETest extends AnyFunSuite:

  private val nodeAvailable: Boolean =
    Try("node --version".!!.trim).toOption.exists(_.nonEmpty)

  test("ForSignal — push + clear update the rendered <li>s via emitted React JS") {
    assume(nodeAvailable, "node not available")
    val backend = new ReactFrameworkBackend
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

    val tmp = Files.createTempDirectory("ssc-forsignal-react-")
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
        |  const list = dom.window.document.getElementById('list');
        |  const add  = dom.window.document.getElementById('add');
        |  const clr  = dom.window.document.getElementById('clr');
        |  const items = () => Array.from(list.querySelectorAll('li')).map(li => li.textContent);
        |  if (JSON.stringify(items()) !== JSON.stringify(['first']))
        |    throw new Error('initial: ' + JSON.stringify(items()));
        |  act(() => { add.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true })); });
        |  act(() => { add.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true })); });
        |  if (JSON.stringify(items()) !== JSON.stringify(['first', 'new', 'new']))
        |    throw new Error('after 2 add: ' + JSON.stringify(items()));
        |  act(() => { clr.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true })); });
        |  if (items().length !== 0) throw new Error('after clear: ' + JSON.stringify(items()));
        |  act(() => { add.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true })); });
        |  if (JSON.stringify(items()) !== JSON.stringify(['new']))
        |    throw new Error('after refill: ' + JSON.stringify(items()));
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
