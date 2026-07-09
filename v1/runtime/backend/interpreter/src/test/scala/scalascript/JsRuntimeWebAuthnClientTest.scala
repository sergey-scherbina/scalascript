package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JsGen

import java.nio.charset.StandardCharsets

class JsRuntimeWebAuthnClientTest extends AnyFunSuite:

  private def hasNode: Boolean = ProcTestUtil.commandOk("node")

  private def runNode(js: String): String =
    assume(hasNode, "node not available")
    val tmp = java.io.File.createTempFile("ssc-webauthn-client-", ".cjs")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, js.getBytes(StandardCharsets.UTF_8))
    val r = ProcTestUtil.runCaptured(Seq("node", tmp.getAbsolutePath))
    assert(r.exit == 0, s"node run failed (${r.exit}):\n${r.err}")
    r.out.trim

  test("browser WebAuthn actions build navigator options and POST verifier-shaped payloads"):
    val runtime = JsGen.generateRuntime(Set(JsGen.Capability.Signals))
    val script =
      runtime + "\n" +
      """
        |(async function() {
        |  function assertRuntime(c, m) { if (!c) throw new Error(m); }
        |  function bytes(v) { return Array.from(_ssc_wa_bytes(v)).join(','); }
        |  function response(status, body) {
        |    return { ok: status >= 200 && status < 300, status, text: async () => body };
        |  }
        |
        |  const calls = [];
        |  globalThis.fetch = async function(url, opts) {
        |    calls.push({ url, opts: opts || {} });
        |    if (url === '/begin-register') {
        |      return response(200, JSON.stringify({ challenge: 'AQID', userId: 'owner', displayName: 'Owner' }));
        |    }
        |    if (url === '/complete-register') {
        |      const body = JSON.parse(opts.body);
        |      assertRuntime(body.clientDataJSON === 'BAU', 'registration clientDataJSON payload');
        |      assertRuntime(body.attestationObject === 'Bg', 'registration attestationObject payload');
        |      assertRuntime(opts.headers['X-Test'] === 'yes', 'registration custom header');
        |      assertRuntime(opts.headers['Content-Type'] === 'application/json', 'registration content type');
        |      return response(200, 'registered');
        |    }
        |    if (url === '/begin-assert') {
        |      return response(200, JSON.stringify({ challenge: 'Cgs', allowCredentials: JSON.stringify(['CQ']) }));
        |    }
        |    if (url === '/complete-assert') {
        |      const body = JSON.parse(opts.body);
        |      assertRuntime(body.clientDataJSON === 'DA', 'assertion clientDataJSON payload');
        |      assertRuntime(body.authenticatorData === 'DQ4', 'assertion authenticatorData payload');
        |      assertRuntime(body.signature === 'Dw', 'assertion signature payload');
        |      assertRuntime(body.credentialId === 'CQ', 'assertion credentialId payload');
        |      return response(200, 'asserted');
        |    }
        |    return response(404, 'missing');
        |  };
        |
        |  let createOptions = null;
        |  let getOptions = null;
        |  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: {
        |    credentials: {
        |      create: async function(opts) {
        |        createOptions = opts.publicKey;
        |        return {
        |          response: {
        |            clientDataJSON: new Uint8Array([4, 5]).buffer,
        |            attestationObject: new Uint8Array([6]).buffer,
        |          }
        |        };
        |      },
        |      get: async function(opts) {
        |        getOptions = opts.publicKey;
        |        return {
        |          rawId: new Uint8Array([9]).buffer,
        |          response: {
        |            clientDataJSON: new Uint8Array([12]).buffer,
        |            authenticatorData: new Uint8Array([13, 14]).buffer,
        |            signature: new Uint8Array([15]).buffer,
        |          }
        |        };
        |      }
        |    }
        |  }});
        |
        |  const headers = Signal('{"X-Test":"yes"}');
        |  const regResult = Signal('');
        |  const regError = Signal('');
        |  await _ssc_ui_runWebAuthnAction(
        |    _ssc_ui_webauthnRegister('/begin-register', '/complete-register', 'Demo RP',
        |      regResult, regError, headers, 1234, 'required'));
        |  assertRuntime(regResult.get() === 'registered', 'registration result signal');
        |  assertRuntime(regError.get() === '', 'registration error signal');
        |  assertRuntime(bytes(createOptions.challenge) === '1,2,3', 'registration challenge decoded');
        |  assertRuntime(createOptions.rp.name === 'Demo RP', 'registration rpName');
        |  assertRuntime(bytes(createOptions.user.id) === '111,119,110,101,114', 'registration user id bytes');
        |  assertRuntime(createOptions.user.displayName === 'Owner', 'registration displayName');
        |  assertRuntime(createOptions.timeout === 1234, 'registration timeout');
        |  assertRuntime(createOptions.authenticatorSelection.userVerification === 'required', 'registration uv');
        |
        |  const assertResult = Signal('');
        |  const assertError = Signal('');
        |  await _ssc_ui_runWebAuthnAction(
        |    _ssc_ui_webauthnAssert('/begin-assert', '/complete-assert',
        |      assertResult, assertError, headers, 2222, 'preferred'));
        |  assertRuntime(assertResult.get() === 'asserted', 'assertion result signal');
        |  assertRuntime(assertError.get() === '', 'assertion error signal');
        |  assertRuntime(bytes(getOptions.challenge) === '10,11', 'assertion challenge decoded');
        |  assertRuntime(bytes(getOptions.allowCredentials[0].id) === '9', 'assertion credential id decoded');
        |  assertRuntime(getOptions.timeout === 2222, 'assertion timeout');
        |
        |  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { credentials: {} } });
        |  const unsupportedError = Signal('');
        |  await _ssc_ui_runWebAuthnAction(
        |    _ssc_ui_webauthnAssert('/begin-assert', '/complete-assert',
        |      Signal(''), unsupportedError, headers));
        |  assertRuntime(unsupportedError.get().indexOf('WebAuthn is only available in a browser') >= 0,
        |    'unsupported browser error');
        |
        |  console.log('webauthn-client-ok');
        |})().catch(function(e) { console.error(e && e.stack ? e.stack : e); process.exit(1); });
        |""".stripMargin

    assert(runNode(script) == "webauthn-client-ok")
