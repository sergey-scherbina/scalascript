package scalascript.wallet.strategy.erc4337

import org.scalatest.funsuite.AsyncFunSuite
import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.scalajs.js.typedarray.{ArrayBuffer, Uint8Array}

/** Direct binding to `globalThis` — Scala.js refuses to load
 *  `js.Dynamic.global` as a value (only as the LHS of a `.`-selection),
 *  so we use this typed handle whenever a function needs the global
 *  object as an argument (e.g. `Object.defineProperty(globalThis, ...)`).
 *  The `@JSGlobal("globalThis")` annotation tells the Scala.js linker
 *  this is the global root, satisfying the safety rule. */
@js.native @JSGlobal("globalThis")
private object GlobalThis extends js.Object

/** Node-side behaviour tests for the WebAuthn JS facade.  Stubs the
 *  global `navigator.credentials.get` so the test can:
 *
 *    - Inspect the options dict the facade passes in (challenge byte
 *      identity, rpId, allowCredentials transformation).
 *    - Return a fake `PublicKeyCredential` shape and verify that the
 *      facade decodes its `authenticatorData` / `clientDataJSON` /
 *      `signature` ArrayBuffers into byte arrays correctly. */
class WebAuthnFacadeTest extends AsyncFunSuite:
  implicit override def executionContext: ExecutionContext = ExecutionContext.global

  /** Capture of the options dict + a function to control the resolved
   *  credential.  Returned by [[stubNavigator]] so each test asserts
   *  on what the facade passed in and what value it gets out. */
  private class Captured:
    var lastOptions: js.Dynamic = js.Dynamic.literal()
    var responder: js.Dynamic => js.Dynamic = _ =>
      // default response: empty ArrayBuffers
      mkCredential(new Uint8Array(0), new Uint8Array(0), new Uint8Array(0))

  /** Install a `navigator.credentials.get` stub on `globalThis` so
   *  [[WebAuthnFacade]] sees it via scalajs-dom's `dom.window.navigator`.
   *
   *  In Node 26+, `globalThis.navigator` is a read-only getter; plain
   *  assignment throws.  We use `Object.defineProperty` with
   *  `configurable: true` to overwrite it (and also bind it onto
   *  `globalThis.window` so `dom.window.navigator` reaches the same
   *  stub).  Idempotent across tests — each call replaces the
   *  previous stub. */
  private def stubNavigator(): Captured =
    val cap = new Captured
    val getFn: js.Function1[js.Dynamic, js.Promise[js.Dynamic]] = (req: js.Dynamic) => {
      cap.lastOptions = req.publicKey
      js.Promise.resolve[js.Dynamic](cap.responder(req)).asInstanceOf[js.Promise[js.Dynamic]]
    }
    val navigator = js.Dynamic.literal(
      credentials = js.Dynamic.literal(get = getFn),
    )
    val desc = js.Dynamic.literal(
      value        = navigator,
      writable     = true,
      configurable = true,
      enumerable   = true,
    )
    js.Dynamic.global.Object.defineProperty(GlobalThis, "navigator", desc)
    // scalajs-dom's `dom.window.navigator` resolves to
    // `globalThis.window?.navigator || globalThis.navigator`, so also
    // expose `window.navigator` for completeness.
    js.Dynamic.global.Object.defineProperty(GlobalThis, "window", js.Dynamic.literal(
      value        = js.Dynamic.literal(navigator = navigator),
      writable     = true,
      configurable = true,
      enumerable   = true,
    ))
    cap

  private def mkCredential(
    authData:       Uint8Array,
    clientDataJson: Uint8Array,
    signature:      Uint8Array,
  ): js.Dynamic =
    js.Dynamic.literal(
      response = js.Dynamic.literal(
        authenticatorData = authData.buffer,
        clientDataJSON    = clientDataJson.buffer,
        signature         = signature.buffer,
      ),
    )

  private def uint8(bytes: Array[Byte]): Uint8Array =
    val out = new Uint8Array(bytes.length)
    var i = 0
    while i < bytes.length do
      out(i) = (bytes(i) & 0xff).toShort
      i += 1
    out

  private def fromUint8(u: Uint8Array): Array[Byte] =
    val out = new Array[Byte](u.length)
    var i = 0
    while i < out.length do
      out(i) = u(i).toByte
      i += 1
    out

  // ── tests ────────────────────────────────────────────────────────────

  test("assertChallenge passes the challenge bytes through as a Uint8Array") {
    val cap = stubNavigator()
    val challenge = Array.tabulate[Byte](32)(i => (i * 3 + 1).toByte)
    WebAuthnFacade.assertChallenge(challenge, rpId = "example.com").map { _ =>
      val sent = cap.lastOptions.challenge.asInstanceOf[Uint8Array]
      assert(sent.length == challenge.length,
        s"challenge byte length must match (got ${sent.length}, expected ${challenge.length})")
      val sentBytes = fromUint8(sent)
      assert(sentBytes.toSeq == challenge.toSeq,
        "challenge bytes must be byte-identical to the input")
    }
  }

  test("assertChallenge sets rpId, userVerification, and omits allowCredentials when empty") {
    val cap = stubNavigator()
    WebAuthnFacade.assertChallenge(Array.fill[Byte](32)(0x42), rpId = "wallet.example.com").map { _ =>
      assert(cap.lastOptions.rpId.asInstanceOf[String] == "wallet.example.com")
      assert(cap.lastOptions.userVerification.asInstanceOf[String] == "required")
      // No allowCredentials key when caller passes Nil — authenticator
      // is allowed to pick any registered credential for the rpId.
      assert(js.isUndefined(cap.lastOptions.allowCredentials),
        "allowCredentials must be omitted when the caller provides none")
    }
  }

  test("assertChallenge encodes allowCredentials as [{type:public-key, id:Uint8Array}, …]") {
    val cap = stubNavigator()
    val credA = Array.tabulate[Byte](16)(_.toByte)
    val credB = Array.tabulate[Byte](16)(i => (0xff - i).toByte)
    WebAuthnFacade.assertChallenge(
      Array.fill[Byte](32)(0x01),
      rpId             = "example.com",
      allowCredentials = Seq(credA, credB),
    ).map { _ =>
      val arr = cap.lastOptions.allowCredentials.asInstanceOf[js.Array[js.Dynamic]]
      assert(arr.length == 2)
      val first  = arr(0)
      val second = arr(1)
      assert(first.`type`.asInstanceOf[String]  == "public-key")
      assert(second.`type`.asInstanceOf[String] == "public-key")
      assert(fromUint8(first.id.asInstanceOf[Uint8Array]).toSeq  == credA.toSeq)
      assert(fromUint8(second.id.asInstanceOf[Uint8Array]).toSeq == credB.toSeq)
    }
  }

  test("assertChallenge unpacks the response's three ArrayBuffers into Array[Byte]") {
    val cap = stubNavigator()
    val mockAuth   = Array.tabulate[Byte](37)(i => (i + 0x10).toByte)
    val mockCdJson = """{"type":"webauthn.get","challenge":"AAA","origin":"https://example.com"}""".getBytes("UTF-8")
    val mockSig    = Array.tabulate[Byte](70)(i => (i + 0x80).toByte)
    cap.responder = _ => mkCredential(uint8(mockAuth), uint8(mockCdJson), uint8(mockSig))

    WebAuthnFacade.assertChallenge(Array.fill[Byte](32)(0x01), rpId = "example.com").map { a =>
      assert(a.authenticatorData.toSeq == mockAuth.toSeq,
        "authenticatorData must round-trip ArrayBuffer → Array[Byte]")
      assert(a.clientDataJson.toSeq == mockCdJson.toSeq,
        "clientDataJSON must round-trip ArrayBuffer → Array[Byte]")
      assert(a.signatureDer.toSeq == mockSig.toSeq,
        "signature must round-trip ArrayBuffer → Array[Byte]")
    }
  }

  test("toUint8Array preserves byte values exactly across the sign-extension boundary") {
    // Bytes ≥ 0x80 are negative in `Byte`; verify the helper writes
    // them as the unsigned uint8 (0x80-0xff) the browser API expects.
    val bytes = Array[Byte](0x00, 0x7f, 0x80.toByte, 0xff.toByte, 0x42)
    val u = WebAuthnFacade.toUint8Array(bytes)
    assert(u.length == bytes.length)
    assert(u(0).toInt == 0x00)
    assert(u(1).toInt == 0x7f)
    assert(u(2).toInt == 0x80)
    assert(u(3).toInt == 0xff)
    assert(u(4).toInt == 0x42)
  }

  test("arrayBufferToArray copies the bytes faithfully") {
    val src = uint8(Array[Byte](0x12, 0x34, 0x56, 0x78.toByte, 0x9a.toByte, 0xbc.toByte, 0xde.toByte, 0xf0.toByte))
    val out = WebAuthnFacade.arrayBufferToArray(src.buffer.asInstanceOf[ArrayBuffer])
    assert(out.length == src.length)
    assert(out.toSeq == Seq[Byte](0x12, 0x34, 0x56, 0x78.toByte, 0x9a.toByte, 0xbc.toByte, 0xde.toByte, 0xf0.toByte))
  }
