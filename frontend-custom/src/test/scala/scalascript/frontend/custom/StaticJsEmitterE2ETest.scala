package scalascript.frontend.custom

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*
import scala.sys.process.*
import scala.util.Try

/** End-to-end smoke: run the emitted JS through Node + jsdom and
 *  assert the resulting DOM matches what we'd expect.  Skipped if
 *  `node` isn't on PATH (CI without Node, contributor first-clone),
 *  so the suite is still green on minimal setups. */
class StaticJsEmitterE2ETest extends AnyFunSuite:

  private val nodeAvailable: Boolean = Try("node --version".!! .trim).toOption.exists(_.nonEmpty)

  test("emit — full bundle runs under jsdom and produces the expected DOM") {
    assume(nodeAvailable, "node not available — skipping E2E test")
    // Best-effort jsdom check: works if jsdom is installed somewhere
    // node can resolve.  If not, fall back to the pure-syntax check
    // (parse-only via Function constructor) so the test still
    // exercises that the JS at least parses.
    val backend = new CustomFrameworkBackend
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div",
      attrs    = Map("class" -> AttrValue.Str("greeting")),
      events   = Map.empty,
      children = Seq(
        View.Element("h1", Map.empty, Map.empty, Seq(View.TextNode(() => "Hello"))),
        View.TextNode(() => ", world!")
      )
    ))
    val emitted = backend.emit(FrontendModule(List(app), "App", "/"))

    val tmp = Files.createTempDirectory("ssc-frontend-custom-")
    try
      val script = tmp.resolve("check.mjs")
      // Try jsdom; on resolution failure fall back to syntax-only.
      Files.writeString(script, s"""
        |let jsdom;
        |try { jsdom = await import('jsdom'); } catch (_e) { jsdom = null; }
        |if (jsdom) {
        |  const dom = new jsdom.JSDOM('<!DOCTYPE html><html><body><div id="app"></div></body></html>');
        |  globalThis.document = dom.window.document;
        |  globalThis.window   = dom.window;
        |  ${emitted.js}
        |  const root = dom.window.document.getElementById('app');
        |  const div  = root.firstElementChild;
        |  if (!div || div.tagName !== 'DIV') throw new Error('expected <div>, got ' + (div && div.tagName));
        |  if (div.getAttribute('class') !== 'greeting') throw new Error('wrong class: ' + div.getAttribute('class'));
        |  const h1   = div.firstElementChild;
        |  if (!h1 || h1.tagName !== 'H1') throw new Error('expected <h1>, got ' + (h1 && h1.tagName));
        |  if (h1.textContent !== 'Hello') throw new Error('wrong h1 text: ' + h1.textContent);
        |  if (!div.textContent.includes(', world!')) throw new Error('missing trailing text');
        |  console.log('OK');
        |} else {
        |  // Syntax-only fallback: wrapping in `new Function(...)` validates
        |  // the JS parses, even though `document` isn't bound here.
        |  const src = ${"\"" + escapeForJs(emitted.js) + "\""};
        |  new Function(src);
        |  console.log('PARSE_OK');
        |}
        |""".stripMargin)
      val output = Try(s"node $script".!!).getOrElse("")
      assert(output.trim == "OK" || output.trim == "PARSE_OK",
        s"node run did not return OK/PARSE_OK; got: '$output'")
    finally
      // Best-effort cleanup; not catastrophic if it fails.
      Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
  }

  /** Escape JS for embedding inside a double-quoted JS string. */
  private def escapeForJs(s: String): String =
    s.replace("\\", "\\\\")
     .replace("\"", "\\\"")
     .replace("\n", "\\n")
     .replace("\r", "\\r")
