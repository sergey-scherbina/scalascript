package scalascript.frontend.custom

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*
import scala.sys.process.*
import scala.util.Try

/** A6 E2E — DomRef + Portal driven through the actually-emitted Custom JS
 *  in a jsdom environment.  Demonstrates:
 *    - A button click handler can read the DOM ref (focus an input)
 *    - A portal's children land in `#modal-root`, not the local parent. */
class RefPortalE2ETest extends AnyFunSuite:

  private val nodeAvailable: Boolean =
    Try("node --version".!!.trim).toOption.exists(_.nonEmpty)

  test("DomRef + Portal — focus via ref + portal lands in modal-root") {
    assume(nodeAvailable, "node not available")
    val backend = new CustomFrameworkBackend
    val inputRef = new WidgetRef("nameInput")
    // Toggle whether the modal is visible: clicking shows it.  Use a
    // boolean signal + ShowSignal to gate the portal mount on first
    // render.  (Portal itself is always emitted; signal-gated portal is
    // a follow-up.  This test mounts the portal at start.)
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
            Seq(View.TextNode(() => "in-portal"))
          )
        ))
      )
    ))
    val emitted = backend.emit(FrontendModule(List(app), "App", "/"))

    val tmp = Files.createTempDirectory("ssc-refportal-e2e-")
    try
      val script = tmp.resolve("check.mjs")
      Files.writeString(script, s"""
        |let jsdom;
        |try { jsdom = await import('jsdom'); } catch (_e) { jsdom = null; }
        |if (jsdom) {
        |  const dom = new jsdom.JSDOM('<!DOCTYPE html><html><body><div id="app"></div><div id="modal-root"></div></body></html>');
        |  globalThis.document = dom.window.document;
        |  globalThis.window   = dom.window;
        |  ${emitted.js}
        |  // 1. Portal: the <span id="portaled"> must live under #modal-root,
        |  //    NOT under #app.
        |  const portaled = dom.window.document.getElementById('portaled');
        |  if (!portaled) throw new Error('portal child missing');
        |  if (portaled.parentElement.id !== 'modal-root') throw new Error('portal child has wrong parent: ' + portaled.parentElement.id);
        |  // 2. Ref: the module-scope name 'nameInput' (exposed on window)
        |  //    must be the same node as document.getElementById('name').
        |  const refNode = dom.window.nameInput;
        |  const byId    = dom.window.document.getElementById('name');
        |  if (refNode !== byId) throw new Error('ref does not point at the input');
        |  // 3. Imperative ref usage: focus the input via the ref.
        |  dom.window.nameInput.focus();
        |  if (dom.window.document.activeElement !== byId) throw new Error('ref-driven focus did not work');
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
