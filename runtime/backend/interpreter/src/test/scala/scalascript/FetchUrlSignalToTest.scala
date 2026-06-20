package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JsRuntimeSignals

import java.nio.charset.StandardCharsets

/** Regression for `fetchUrlSignalTo` (2026-06-15): the read-side counterpart of
 *  `fetchActionTo` — a `fetchUrlSignal` whose URL is a `Signal[String]` resolved at
 *  fetch time, RE-FETCHING whenever it changes (a filter/branch switch). A plain
 *  `fetchUrlSignal` bakes its URL at render time, so a dependent-signal change never
 *  re-fetches.
 *
 *  Runs the REAL `JsRuntimeSignals` runtime headless (document/fetch shims): a field
 *  drives a computed URL signal; the GET fetch must fire on mount with the initial
 *  URL AND re-fire with the new URL after the field changes — proving the reactive
 *  re-fetch (relies on the computed→`_sv` bridge + the `_sub(urlSig, doGet)` wire). */
class FetchUrlSignalToTest extends AnyFunSuite:

  private def hasNode: Boolean = ProcTestUtil.commandOk("node")

  private def runNode(js: String): String =
    assume(hasNode, "node not available")
    val tmp = java.io.File.createTempFile("ssc-fetchurlto-", ".cjs")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, js.getBytes(StandardCharsets.UTF_8))
    val _r  = ProcTestUtil.runCaptured(Seq("node", tmp.getAbsolutePath))
    val out = _r.out
    val err = _r.err
    val ok  = _r.exit
    assert(ok == 0, s"node run failed ($ok):\n$err")
    out.trim

  private val driver = """
var _urls = [];
function _el(attrs) {
  return { _attrs: attrs, _on: {}, value: "",
    getAttribute: function(k){ return this._attrs[k] != null ? this._attrs[k] : null; },
    addEventListener: function(ev, fn){ this._on[ev] = fn; },
    fire: function(ev){ if (this._on[ev]) this._on[ev](); } };
}
var branch = Signal("");                                              // the filter/branch field
var urlSig = computed(function(){ return "/commits?branch=" + branch.get(); });
var rows   = _ssc_ui_fetchUrlSignalTo("commits", urlSig, null, null);  // _fetchGet.urlSig
var input  = _el({ "data-ssc-change": String(branch.id) });
global.window = { addEventListener: function(){}, location: { hash: "" } };
global.document = { querySelectorAll: function(sel){
  if (sel === '[data-ssc-change]') return [input];
  return [];
} };
global.fetch = function(url, opts){ _urls.push(url);
  return Promise.resolve({ ok: true, text: function(){ return Promise.resolve("[]"); } }); };

var sigs = new Map();
sigs.set(branch.id, branch.get());
sigs.set(urlSig.id, urlSig.get());
sigs.set(rows.id, rows.get());     // collected → forEach mounts its _fetchGet (urlSig)
_ssc_ui_mount(sigs);

input.value = "work";
input.fire('input');               // → branch changes → urlSig recomputes → re-fetch
console.log(JSON.stringify(_urls));
"""

  test("fetchUrlSignalTo fetches on mount and re-fetches the new URL when its url signal changes") {
    val out = runNode(JsRuntimeSignals + "\n" + driver)
    // mount fetch (initial empty branch) + a re-fetch with the typed branch.
    assert(out.contains("/commits?branch="),
      s"no initial GET fired: $out")
    assert(out.contains("/commits?branch=work"),
      s"fetchUrlSignalTo did not re-fetch the new URL after the field changed: $out")
  }
