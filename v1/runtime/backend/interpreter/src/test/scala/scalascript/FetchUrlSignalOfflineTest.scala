package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JsRuntimeSignals

import java.nio.charset.StandardCharsets

/** Runs the real custom-SPA signals runtime under Node. A managed GET must
 *  consume transport/body-read rejection, preserve last-good data, and keep
 *  its tick subscription alive for a later successful refresh. */
class FetchUrlSignalOfflineTest extends AnyFunSuite:

  private def hasNode: Boolean = ProcTestUtil.commandOk("node")

  private def runNode(js: String): String =
    assume(hasNode, "node not available")
    val tmp = java.io.File.createTempFile("ssc-fetchurl-offline-", ".cjs")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, js.getBytes(StandardCharsets.UTF_8))
    val result = ProcTestUtil.runCaptured(Seq("node", tmp.getAbsolutePath))
    assert(result.exit == 0, s"node run failed (${result.exit}):\n${result.err}")
    result.out.trim

  private val driver = """
var unhandled = [];
process.on('unhandledRejection', function(err) { unhandled.push(String(err)); });

var calls = 0;
var incButton = {
  attrs: {}, listeners: {},
  getAttribute: function(k){ return this.attrs[k] == null ? null : this.attrs[k]; },
  setAttribute: function(k, v){ this.attrs[k] = String(v); },
  addEventListener: function(name, fn){ this.listeners[name] = fn; },
  fire: function(name){ if (this.listeners[name]) this.listeners[name](); }
};
global.window = { addEventListener: function(){}, location: { hash: "" } };
global.document = { querySelectorAll: function(selector){
  return selector === '[data-ssc-inc]' ? [incButton] : [];
} };
global.fetch = function() {
  calls += 1;
  if (calls === 1) return Promise.reject(new Error("offline transport"));
  if (calls === 2) return Promise.resolve({
    ok: true,
    text: function(){ return Promise.reject(new Error("offline body")); }
  });
  return Promise.resolve({
    ok: false,
    status: 503,
    text: function(){ return Promise.resolve("fresh after reconnect"); }
  });
};

var tick = _ssc_ui_signal("offlineTick", 0);
incButton.attrs['data-ssc-inc'] = String(tick.id);
var rows = _ssc_ui_fetchUrlSignal("offlineRows", "/api/rows", tick, null);
var sigs = new Map();
sigs.set(tick.id, tick.get());
sigs.set(rows.id, "last good");
_ssc_ui_mount(sigs);

setImmediate(function() {
  var afterTransportReject = rows.get();
  incButton.fire('click');
  setImmediate(function() {
    var afterBodyReject = rows.get();
    incButton.fire('click');
    setImmediate(function() {
      setImmediate(function() {
        console.log(JSON.stringify({
          calls: calls,
          afterTransportReject: afterTransportReject,
          afterBodyReject: afterBodyReject,
          finalValue: rows.get(),
          unhandled: unhandled
        }));
      });
    });
  });
});
"""

  test("managed GET retains last-good data and recovers without unhandled rejection"):
    val out = runNode(JsRuntimeSignals + "\n" + driver)
    assert(out.contains("\"calls\":3"), s"managed GET did not retry on later ticks: $out")
    assert(out.contains("\"afterTransportReject\":\"last good\""),
      s"transport rejection replaced last-good data: $out")
    assert(out.contains("\"afterBodyReject\":\"last good\""),
      s"response-body rejection replaced last-good data: $out")
    assert(out.contains("\"finalValue\":\"fresh after reconnect\""),
      s"successful reconnect did not update the managed signal: $out")
    assert(out.contains("\"unhandled\":[]"), s"managed GET leaked an unhandled rejection: $out")
