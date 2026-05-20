package scalascript.wallet.strategy.erc4337

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.typedarray.{ArrayBuffer, Uint8Array}
import org.scalajs.dom

/** Scala.js facade over the WebAuthn `navigator.credentials.get(...)`
 *  API.  Implements the `assertChallenge` callback shape expected by
 *  the cross-compiled [[PasskeySigner]].
 *
 *  Usage:
 *
 *  ```scala
 *  val signer = PasskeySignerJs.fromBrowserPasskey(
 *    publicKey       = PublicKey(Curve.P256, pubXY),
 *    rpId            = "example.com",
 *    allowCredentials = Seq(credId),
 *  )
 *  val sig: Array[Byte] = await(signer.sign(userOpHash, HashAlgo.None))
 *  ```
 *
 *  The facade does no business logic — it only translates between the
 *  WebAuthn JS shape and the Scala [[WebAuthnAssertion]] value class.
 *  Signature normalisation, ABI packing, and challenge cross-checks
 *  live in [[PasskeySigner]] and run cross-platform. */
object WebAuthnFacade:

  /** Trigger a WebAuthn assertion ceremony.
   *
   *  Builds a `PublicKeyCredentialRequestOptions` dictionary matching
   *  the W3C WebAuthn Level 2 spec, calls
   *  `navigator.credentials.get(options)`, and unwraps the returned
   *  `PublicKeyCredential` + `AuthenticatorAssertionResponse` into a
   *  Scala [[WebAuthnAssertion]] (`authenticatorData`,
   *  `clientDataJSON`, `signature` as `Array[Byte]`).
   *
   *  @param challenge        Raw challenge bytes (32 B for ERC-4337 —
   *                          the userOpHash).  Embedded as
   *                          `Uint8Array` into the options dict.
   *  @param rpId             Relying-party identifier (the dApp's
   *                          domain).
   *  @param allowCredentials Optional list of credential ids the user
   *                          is allowed to assert with.  Empty list
   *                          ⇒ the authenticator picks (and the dApp
   *                          relies on `userVerification`). */
  def assertChallenge(
    challenge:        Array[Byte],
    rpId:             String,
    allowCredentials: Seq[Array[Byte]] = Nil,
  )(using ec: ExecutionContext): Future[WebAuthnAssertion] =
    val options = js.Dynamic.literal(
      challenge        = toUint8Array(challenge),
      rpId             = rpId,
      userVerification = "required",
    )
    if allowCredentials.nonEmpty then
      val allowed = js.Array[js.Any](
        allowCredentials.map { id =>
          js.Dynamic.literal(
            `type` = "public-key",
            id     = toUint8Array(id),
          ).asInstanceOf[js.Any]
        }*,
      )
      options.updateDynamic("allowCredentials")(allowed)
    val credsReq = js.Dynamic.literal(publicKey = options)
    val credentialsApi = dom.window.navigator.asInstanceOf[js.Dynamic].credentials
    val promise = credentialsApi.get(credsReq).asInstanceOf[js.Promise[js.Dynamic]]
    promise.toFuture.map { cred =>
      val response = cred.response
      val authData = arrayBufferToArray(response.authenticatorData.asInstanceOf[ArrayBuffer])
      val clientDataJSON = arrayBufferToArray(response.clientDataJSON.asInstanceOf[ArrayBuffer])
      val signature = arrayBufferToArray(response.signature.asInstanceOf[ArrayBuffer])
      WebAuthnAssertion(authData, clientDataJSON, signature)
    }

  /** Allocate a `Uint8Array` view onto a freshly-allocated
   *  `ArrayBuffer` and copy `bytes` into it.  We do not return a view
   *  into the caller's array because the WebAuthn API copies the
   *  challenge into its internal state and we don't want surprises if
   *  the caller mutates the source array afterwards. */
  private[erc4337] def toUint8Array(bytes: Array[Byte]): Uint8Array =
    val out = new Uint8Array(bytes.length)
    var i = 0
    while i < bytes.length do
      out(i) = (bytes(i) & 0xff).toShort
      i += 1
    out

  /** Copy an ArrayBuffer's bytes into a fresh `Array[Byte]`. */
  private[erc4337] def arrayBufferToArray(buf: ArrayBuffer): Array[Byte] =
    val view = new Uint8Array(buf)
    val out  = new Array[Byte](view.length)
    var i = 0
    while i < out.length do
      out(i) = view(i).toByte
      i += 1
    out
