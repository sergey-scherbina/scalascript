package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JsRuntimeSignals

import java.nio.charset.StandardCharsets

/** The `onOpenJson(urlTemplate, field)` onSuccess effect (2026-07-07): on a 2xx the
 *  runtime parses the RESPONSE body as JSON and navigates to the templated URL with
 *  `:value` replaced by the named field. First consumer: the rozum UCC launch button
 *  opening `/terminal.html?id=<created session id>` straight from the launch response.
 *
 *  Runs the REAL `_ssc_ui_runOnSuccess` headless (same harness family as
 *  SpaComputedBodyBridgeTest): a location shim captures the navigation; a non-2xx and
 *  a missing field must both stay put. */
class SpaOpenJsonEffectTest extends AnyFunSuite:

  private def hasNode: Boolean = ProcTestUtil.commandOk("node")

  private def runNode(js: String): String =
    assume(hasNode, "node not available")
    val tmp = java.io.File.createTempFile("ssc-spa-openjson-", ".cjs")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, js.getBytes(StandardCharsets.UTF_8))
    val _r = ProcTestUtil.runCaptured(Seq("node", tmp.getAbsolutePath))
    assert(_r.exit == 0, s"node run failed (${_r.exit}):\n${_r.err}")
    _r.out.trim

  private val driver = """
global.window = { location: { href: "about:start", hash: "" } };
var effects = JSON.stringify([
  { eff: 'bumpTick', id: 't1' },
  { eff: 'openJson', urlTemplate: '/terminal.html?id=:value', field: 'id' }
]);
var sv = { t1: 0 };
function setFn(id, v) { sv[id] = v; }

// happy path: 2xx + field present → navigate + tick bumped
_ssc_ui_runOnSuccess(effects, true, setFn, sv, '{"ok":true,"id":"claude-123"}');
console.log(window.location.href);
console.log(sv.t1);

// non-2xx: nothing runs
window.location.href = "about:reset";
_ssc_ui_runOnSuccess(effects, false, setFn, sv, '{"ok":false,"id":"nope"}');
console.log(window.location.href);

// 2xx but the field is absent: tick still bumps, no navigation
_ssc_ui_runOnSuccess(effects, true, setFn, sv, '{"ok":true}');
console.log(window.location.href);
console.log(sv.t1);
"""

  test("onOpenJson navigates from the response body on 2xx only") {
    val out = runNode(JsRuntimeSignals + "\n" + driver)
    assert(out == Seq(
      "/terminal.html?id=claude-123",
      "1",
      "about:reset",
      "about:reset",
      "2",
    ).mkString("\n"), s"unexpected effect behaviour:\n$out")
  }
