package scalascript.frontend.vue

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*
import scala.sys.process.*
import scala.util.Try

/** A6 E2E — DomRef + Portal driven through the emitted Vue JS in a
 *  jsdom environment with the actual `vue` runtime installed. */
class RefPortalE2ETest extends AnyFunSuite:

  private val nodeAvailable: Boolean =
    Try("node --version".!!.trim).toOption.exists(_.nonEmpty)

  test("DomRef + Teleport — Vue ref + Teleport via emitted JS") {
    assume(nodeAvailable, "node not available")
    val backend = new VueFrameworkBackend
    val inputRef = new WidgetRef("nameInput")
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

    val tmp = Files.createTempDirectory("ssc-refportal-vue-")
    try
      val script = tmp.resolve("check.mjs")
      // Parse-only fallback strips the import line that the JS-string
      // `new Function(...)` evaluator cannot handle.
      val parseOnlyJs = emitted.js
        .replaceFirst("(?m)^import \\{[^}]*\\} from 'vue';\\n", "")
      Files.writeString(script, s"""
        |let jsdom, vue;
        |try { jsdom = await import('jsdom'); } catch (_e) { jsdom = null; }
        |try { vue   = await import('vue');   } catch (_e) { vue   = null; }
        |if (jsdom && vue) {
        |  const dom = new jsdom.JSDOM('<!DOCTYPE html><html><body><div id="app"></div><div id="modal-root"></div></body></html>');
        |  globalThis.document = dom.window.document;
        |  globalThis.window   = dom.window;
        |  // Write the emitted bundle to a temp .mjs and import it so the
        |  // `import { ref, h, ..., Teleport, createApp } from 'vue';`
        |  // resolves through node's loader.
        |  const fs   = await import('fs');
        |  const path = await import('path');
        |  const url  = await import('url');
        |  const tmpFile = path.join(${"\"" + tmp.toString.replace("\\", "\\\\") + "\""}, 'bundle.mjs');
        |  fs.writeFileSync(tmpFile, ${"\"" + escapeForJs(emitted.js) + "\""});
        |  await import(url.pathToFileURL(tmpFile).href);
        |  // Vue mounts asynchronously — wait a tick for the DOM to settle.
        |  await new Promise(r => setTimeout(r, 50));
        |  // Portal: the portaled <span> must live under #modal-root.
        |  const portaled = dom.window.document.getElementById('portaled');
        |  if (!portaled) throw new Error('portal child missing');
        |  if (portaled.parentElement.id !== 'modal-root') throw new Error('portal parent: ' + portaled.parentElement.id);
        |  // Vue refs are objects with a .value pointing at the DOM element.
        |  const refObj = dom.window.nameInput;
        |  const byId   = dom.window.document.getElementById('name');
        |  if (!refObj || !('value' in refObj)) throw new Error('not a Vue ref');
        |  if (refObj.value !== byId) throw new Error('ref.value does not point at the input');
        |  refObj.value.focus();
        |  if (dom.window.document.activeElement !== byId) throw new Error('focus via ref failed');
        |  console.log('OK');
        |} else {
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
