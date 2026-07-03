package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JsRuntimeSignals

import java.nio.charset.StandardCharsets

/** Regression for `fetchActionTo` (2026-06-15): a `fetchAction` variant whose URL is
 *  a `Signal[String]` resolved at click time — for path-id endpoints like
 *  `/documents/<selectedId>/submit` where a plain `fetchAction` would bake the URL
 *  (with the empty initial selection) at render time.
 *
 *  Runs the REAL `JsRuntimeSignals` runtime headless (document/fetch shims): a field
 *  drives a computed URL signal; after typing + clicking, the captured fetch URL must
 *  reflect the typed value (not the load-time default). Relies on the computed→`_sv`
 *  bridge (a urlSig read only at click is `_sub`'d at mount). */
class FetchActionToUrlTest extends AnyFunSuite:

  private def hasNode: Boolean = ProcTestUtil.commandOk("node")

  private def runNode(js: String): String =
    assume(hasNode, "node not available")
    val tmp = java.io.File.createTempFile("ssc-fetchto-", ".cjs")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, js.getBytes(StandardCharsets.UTF_8))
    val _r  = ProcTestUtil.runCaptured(Seq("node", tmp.getAbsolutePath))
    val out = _r.out
    val err = _r.err
    val ok  = _r.exit
    assert(ok == 0, s"node run failed ($ok):\n$err")
    out.trim

  private val driver = """
var _capturedUrl = null;
function _el(attrs) {
  return { _attrs: attrs, _on: {}, value: "",
    getAttribute: function(k){ return this._attrs[k] != null ? this._attrs[k] : null; },
    addEventListener: function(ev, fn){ this._on[ev] = fn; },
    fire: function(ev){ if (this._on[ev]) this._on[ev](); } };
}
var s    = Signal("");                                   // selected id
var urlSig = computed(function(){ return "/documents/" + s.get() + "/submit"; });
var body = Signal("{}");
var input  = _el({ "data-ssc-change": String(s.id) });
// fetchActionTo descriptor carries urlSig (no static url)
var btnH   = _ssc_ui_fetchActionTo("POST", urlSig, body, null, null);
var button = _el({
  "data-ssc-fetch-url": "",
  "data-ssc-fetch-url-sig": String(urlSig.id),
  "data-ssc-fetch-method": "POST",
  "data-ssc-fetch-body": String(body.id)
});
global.window = { addEventListener: function(){}, location: { hash: "" } };
global.document = { querySelectorAll: function(sel){
  if (sel === '[data-ssc-change]')    return [input];
  if (sel === '[data-ssc-fetch-url]') return [button];
  return [];
} };
global.fetch = function(url, opts){ _capturedUrl = url;
  return Promise.resolve({ ok: true, text: function(){ return Promise.resolve(""); } }); };

var sigs = new Map();
sigs.set(s.id, s.get());
sigs.set(urlSig.id, urlSig.get());
sigs.set(body.id, body.get());
_ssc_ui_mount(sigs);

input.value = "42";
input.fire('input');
button.fire('click');
console.log(_capturedUrl);   // expect /documents/42/submit ; bug → /documents//submit
"""

  test("fetchActionTo resolves its URL signal at click (reflects typed selection)") {
    val out = runNode(JsRuntimeSignals + "\n" + driver)
    assert(out == "/documents/42/submit",
      s"fetchActionTo did not resolve the reactive URL from the typed value: got [$out]")
  }
