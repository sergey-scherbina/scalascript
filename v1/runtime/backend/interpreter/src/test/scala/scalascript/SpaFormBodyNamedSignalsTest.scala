package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JsRuntimeSignals

import java.nio.charset.StandardCharsets

/** Regression for the SPA reactive bridge (2026-07-07): `formBody([("k","sigName")])`
 *  references field signals by their user-facing NAME, but `_ssc_ui_signal(name, init)`
 *  DISCARDED the name and the bridge store `_sv` is keyed by numeric id — so every
 *  formBody POST assembled `""` for every field (found live: the rozum UCC
 *  session-launch form posted `{"agent":"","model":"","workdir":""}` → 400 while the
 *  page visibly showed all three values).
 *
 *  The fix: `_ssc_ui_signal`/`_ssc_ui_seedSignal` register name → Signal in
 *  `_signalsByName`; the render walk resolves each formBody field ref to its numeric
 *  bridge id via `_ssc_ui_resolveFormFields` and collects the signals so their `_sv`
 *  entries exist and stay fresh.
 *
 *  Runs the REAL `JsRuntimeSignals` runtime headless (same harness as
 *  SpaComputedBodyBridgeTest): named signals + a fields-body fetch button, one value
 *  set before mount and one typed after, click, assert the captured POST body. */
class SpaFormBodyNamedSignalsTest extends AnyFunSuite:

  private def hasNode: Boolean = ProcTestUtil.commandOk("node")

  private def runNode(js: String): String =
    assume(hasNode, "node not available")
    val tmp = java.io.File.createTempFile("ssc-spa-formbody-", ".cjs")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, js.getBytes(StandardCharsets.UTF_8))
    val _r  = ProcTestUtil.runCaptured(Seq("node", tmp.getAbsolutePath))
    val out = _r.out
    val err = _r.err
    val ok  = _r.exit
    assert(ok == 0, s"node run failed ($ok):\n$err")
    out.trim

  private val driver = """
// ── minimal DOM + fetch shims so the real _ssc_ui_mount runs headless ─────────
var _captured = null;
function _el(attrs) {
  return { _attrs: attrs, _on: {}, value: "",
    getAttribute: function(k){ return this._attrs[k] != null ? this._attrs[k] : null; },
    addEventListener: function(ev, fn){ this._on[ev] = fn; },
    fire: function(ev){ if (this._on[ev]) this._on[ev](); } };
}
// named signals, as `signal("seAgent", "claude")` creates them from .ssc
var agent = _ssc_ui_signal("fAgent", "claude");
var model = _ssc_ui_signal("fModel", "");
// the walk-side resolution the render performs for a formBody descriptor
var rf = _ssc_ui_resolveFormFields([["agent", "fAgent"], ["model", "fModel"]]);
var input  = _el({ "data-ssc-change": String(model.id) });
var button = _el({ "data-ssc-fetch-url": "/echo", "data-ssc-fetch-method": "POST",
                   "data-ssc-fetch-body-fields": JSON.stringify(rf.fields) });
global.window = { addEventListener: function(){}, location: { hash: "" } };
global.document = { querySelectorAll: function(sel){
  if (sel === '[data-ssc-change]')    return [input];
  if (sel === '[data-ssc-fetch-url]') return [button];
  return [];
} };
global.fetch = function(url, opts){ _captured = opts.body;
  return Promise.resolve({ ok: true, text: function(){ return Promise.resolve(""); } }); };

// mimic collectSig over the resolved field signals (what the render walk now does)
var sigs = new Map();
rf.signals.forEach(function(s){ sigs.set(s.id, s.get()); });
_ssc_ui_mount(sigs);

// type the model value, then submit
input.value = "mlx-community:Qwen3-4B-4bit";
input.fire('input');
button.fire('click');
console.log(_captured);   // expect both fields populated; bug → {"agent":"","model":""}
"""

  test("formBody fields referenced by signal NAME resolve to live values at submit") {
    val out = runNode(JsRuntimeSignals + "\n" + driver)
    assert(out == """{"agent":"claude","model":"mlx-community:Qwen3-4B-4bit"}""",
      s"formBody by-name fields did not resolve: got [$out]")
  }
