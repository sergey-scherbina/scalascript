package scalascript.wallet.strategy.erc4337

import scala.concurrent.ExecutionContext
import scalascript.crypto.PublicKey

/** Scala.js convenience constructor — wires the cross-platform
 *  [[PasskeySigner]] to the browser WebAuthn API via
 *  [[WebAuthnFacade.assertChallenge]].
 *
 *  Use this from a Scala.js wallet bootstrap (e.g. an in-page
 *  EIP-1193 connector or a service-worker signer) when the owner of
 *  the ERC-4337 smart account is a Passkey rather than an EOA:
 *
 *  ```scala
 *  val signer = PasskeySignerJs.fromBrowserPasskey(
 *    publicKey        = PublicKey(Curve.P256, pubXY),
 *    rpId             = "wallet.example.com",
 *    allowCredentials = Seq(savedCredentialId),
 *  )
 *  // signer.sign(userOpHash, HashAlgo.None) now prompts the user's
 *  // authenticator and returns the ABI-packed WebAuthnAuth blob.
 *  ```
 *
 *  `assertChallenge` returns a Scala `Future`; the underlying JS
 *  `Promise` from `navigator.credentials.get(...)` is converted via
 *  `scala.scalajs.js.Thenable.Implicits`. */
object PasskeySignerJs:

  /** Build a [[PasskeySigner]] whose `assertChallenge` invokes the
   *  browser's WebAuthn API.
   *
   *  @param publicKey        The P-256 credential's public key (`X || Y`,
   *                          64 bytes).
   *  @param rpId             Relying-party id (the dApp's domain;
   *                          must match the rpId baked into the
   *                          credential at registration).
   *  @param allowCredentials Optional list of acceptable credential
   *                          ids (the WebAuthn `allowCredentials`
   *                          filter).  Empty list ⇒ authenticator
   *                          picks. */
  def fromBrowserPasskey(
    publicKey:        PublicKey,
    rpId:             String,
    allowCredentials: Seq[Array[Byte]] = Nil,
  )(using ec: ExecutionContext): PasskeySigner =
    new PasskeySigner(
      publicKey,
      challenge => WebAuthnFacade.assertChallenge(challenge, rpId, allowCredentials),
    )
