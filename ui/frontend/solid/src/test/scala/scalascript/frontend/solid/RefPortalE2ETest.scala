package scalascript.frontend.solid

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*
import scala.sys.process.*
import scala.util.Try

/** A6 E2E — DomRef + Portal driven through the emitted Solid JS in a
 *  jsdom environment. */
class RefPortalE2ETest extends AnyFunSuite:

  private val nodeAvailable: Boolean =
    Try("node --version".!!.trim).toOption.exists(_.nonEmpty)

  test("DomRef + Portal — Solid imperative ref + portal via emitted JS") {
    assume(nodeAvailable, "node not available")
    val backend = new SolidFrameworkBackend
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

    val tmp = Files.createTempDirectory("ssc-refportal-solid-")
    try
      val script = tmp.resolve("check.mjs")
      // Solid output is `import { createSignal, createEffect } from 'solid-js';`
      // — we can't run import statements with a `new Function(src)` parse-only
      // check.  Strip the import for the parse-only fallback.
      val parseOnlyJs = emitted.js
        .replaceFirst("(?m)^import \\{[^}]*\\} from 'solid-js';\\n", "")
      Files.writeString(script, s"""
        |let jsdom;
        |try { jsdom = await import('jsdom'); } catch (_e) { jsdom = null; }
        |let solid;
        |try { solid = await import('solid-js'); } catch (_e) { solid = null; }
        |if (jsdom && solid) {
        |  const dom = new jsdom.JSDOM('<!DOCTYPE html><html><body><div id="app"></div><div id="modal-root"></div></body></html>');
        |  globalThis.document = dom.window.document;
        |  globalThis.window   = dom.window;
        |  // Inline the module by writing it to a temp .mjs and importing it,
        |  // so the `import { createSignal, createEffect } from 'solid-js'` at
        |  // the top of the emitted JS resolves through node's loader.
        |  const fs   = await import('fs');
        |  const path = await import('path');
        |  const url  = await import('url');
        |  const tmpFile = path.join(${"\"" + tmp.toString.replace("\\", "\\\\") + "\""}, 'bundle.mjs');
        |  fs.writeFileSync(tmpFile, ${"\"" + escapeForJs(emitted.js) + "\""});
        |  await import(url.pathToFileURL(tmpFile).href);
        |  // Portal child must be under #modal-root.
        |  const portaled = dom.window.document.getElementById('portaled');
        |  if (!portaled) throw new Error('portal child missing');
        |  if (portaled.parentElement.id !== 'modal-root') throw new Error('portal parent: ' + portaled.parentElement.id);
        |  // Ref points at the input.
        |  const refNode = dom.window.nameInput;
        |  const byId    = dom.window.document.getElementById('name');
        |  if (refNode !== byId) throw new Error('ref does not point at the input');
        |  refNode.focus();
        |  if (dom.window.document.activeElement !== byId) throw new Error('focus via ref failed');
        |  console.log('OK');
        |} else {
        |  // Solid not installed (or jsdom missing) — fall back to parse-only.
        |  const src = ${"\"" + escapeForJs(parseOnlyJs) + "\""};
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
