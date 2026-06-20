package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JsRuntimeSignals

import java.nio.charset.StandardCharsets

/** Regression for the SPA reactive bridge (2026-06-15): a `computedSignal` read
 *  ONLY at event time — e.g. a `fetchAction` body that interpolates field signals
 *  (`computedSignal(() => "{...:" + fieldSig() + "...}")`) — POSTed its load-time
 *  value, ignoring typed input.
 *
 *  The click handler reads `_sv[bodyId]` (the hydration/bridge store), not the live
 *  reactive `_signals` store. Field inputs update `_sv` via `_set`; a computed's
 *  `_sv` entry is only refreshed by `_syncBridgeSignals` when it is `_sub`'d. A
 *  computed displayed by `showSignal`/`signalText` gets `_sub`'d and stays fresh,
 *  but one read solely by a `fetchAction` body never was — so its `_sv` value froze
 *  at the seed. The fix subscribes every collected computed at mount.
 *
 *  This runs the REAL `JsRuntimeSignals` runtime headless with minimal document/
 *  fetch shims, mounts a field + a computed-body fetch button, types into the field,
 *  clicks, and asserts the captured POST body reflects the typed value. */
class SpaComputedBodyBridgeTest extends AnyFunSuite:

  private def hasNode: Boolean = ProcTestUtil.commandOk("node")

  private def runNode(js: String): String =
    assume(hasNode, "node not available")
    val tmp = java.io.File.createTempFile("ssc-spa-bridge-", ".cjs")
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
var s    = Signal("");
var body = computed(function(){ return "{\"v\":\"" + s.get() + "\"}"; });
var input  = _el({ "data-ssc-change": String(s.id) });
var button = _el({ "data-ssc-fetch-url": "/echo", "data-ssc-fetch-method": "POST",
                   "data-ssc-fetch-body": String(body.id) });
global.window = { addEventListener: function(){}, location: { hash: "" } };
global.document = { querySelectorAll: function(sel){
  if (sel === '[data-ssc-change]')    return [input];
  if (sel === '[data-ssc-fetch-url]') return [button];
  return [];
} };
global.fetch = function(url, opts){ _captured = opts.body;
  return Promise.resolve({ ok: true, text: function(){ return Promise.resolve(""); } }); };

// mimic collectSig: seed the field signal AND the computed body into the store
var sigs = new Map();
sigs.set(s.id, s.get());
sigs.set(body.id, body.get());
_ssc_ui_mount(sigs);

// type into the field, then click the button (reads the computed body)
input.value = "HELLO";
input.fire('input');
button.fire('click');
console.log(_captured);   // expect {"v":"HELLO"} ; bug → {"v":""}
"""

  test("a computedSignal fetchAction body reflects typed input (computed→_sv bridge)") {
    val out = runNode(JsRuntimeSignals + "\n" + driver)
    assert(out == """{"v":"HELLO"}""",
      s"computed fetchAction body did not reflect the typed field value: got [$out]")
  }
