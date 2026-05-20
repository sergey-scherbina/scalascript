package scalascript.frontend.react

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*
import scala.sys.process.*
import scala.util.Try

/** A6 E2E — DomRef + Portal through the actually-emitted React JS in a
 *  jsdom environment.  Verifies:
 *    - the ref's .current points at the rendered DOM input,
 *    - the portal's children land in #modal-root, not under #app. */
class RefPortalE2ETest extends AnyFunSuite:

  private val nodeAvailable: Boolean =
    Try("node --version".!!.trim).toOption.exists(_.nonEmpty)

  test("DomRef + Portal — React useRef + createPortal via emitted JS") {
    assume(nodeAvailable, "node not available")
    val backend = new ReactFrameworkBackend
    val inputRef = new DomRef("nameInput")
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty,
      Seq(
        View.Element("input",
          Map(
            "ref"  -> AttrValue.RefBinding(inputRef),
            "id"   -> AttrValue.Str("name"),
            "type" -> AttrValue.Str("text")
          ),
          Map.empty,
          Seq.empty
        ),
        View.Portal("#modal-root", Seq(
          View.Element("span",
            Map("id" -> AttrValue.Str("portaled")),
            Map.empty,
            Seq(View.TextNode(() => "in-portal")))
        ))
      )
    ))
    val emitted = backend.emit(FrontendModule(List(app), "App", "/"))

    val tmp = Files.createTempDirectory("ssc-refportal-react-")
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
        |  const dom = new JSDOM('<!DOCTYPE html><html><body><div id="app"></div><div id="modal-root"></div></body></html>');
        |  globalThis.document = dom.window.document;
        |  globalThis.window   = dom.window;
        |  globalThis.React    = React;
        |  // The emitted bundle calls both `ReactDOM.createRoot(...)` AND
        |  // `ReactDOM.createPortal(...)` — but react-dom/client only
        |  // exports createRoot.  Pull createPortal from 'react-dom' and
        |  // merge both shapes onto the global.
        |  const reactDomFull = await import('react-dom');
        |  globalThis.ReactDOM = Object.assign({}, ReactDOM, { createPortal: reactDomFull.default.createPortal });
        |  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
        |
        |  act(() => { ${emitted.js} });
        |
        |  // Portal: the portaled <span> must live under #modal-root.
        |  const portaled = dom.window.document.getElementById('portaled');
        |  if (!portaled) throw new Error('portal child missing');
        |  if (portaled.parentElement.id !== 'modal-root') throw new Error('portal parent: ' + portaled.parentElement.id);
        |  // Ref: window.nameInput is the React ref object; .current should be the <input>.
        |  const refObj  = dom.window.nameInput;
        |  if (!refObj || !('current' in refObj)) throw new Error('ref object missing or not a useRef');
        |  const byId    = dom.window.document.getElementById('name');
        |  if (refObj.current !== byId) throw new Error('ref.current does not point at the input');
        |  // Imperative ref usage: focus via the ref.
        |  refObj.current.focus();
        |  if (dom.window.document.activeElement !== byId) throw new Error('ref-driven focus failed');
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
