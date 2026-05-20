package scalascript.wallet.strategy.erc4337

import java.util.Base64
import scalascript.crypto.{CryptoBackend, HashAlgo}

/** A `navigator.credentials.get(...)` assertion, in the raw bytes the
 *  WebAuthn spec produces.
 *
 *    authenticatorData : opaque bytes, ≥ 37 B
 *                        rpIdHash(32) || flags(1) || signCount(4) || ext?
 *    clientDataJson    : UTF-8 JSON; required keys are
 *                        `"type":"webauthn.get"`, `"challenge":"<b64url>"`,
 *                        `"origin":"<https://...>"`, ...
 *    signatureDer      : DER-encoded ECDSA(P-256) over
 *                        authenticatorData ++ sha256(clientDataJson)
 *
 *  On-chain, a PasskeyAccount smart contract recomputes that signed
 *  digest and verifies the (r, s) raw signature against the credential's
 *  stored P-256 public key. The challenge embedded in clientDataJson is
 *  the base64url-encoded data the dApp asked the wallet to sign — for
 *  ERC-4337 that's the 32-byte userOpHash.
 *
 *  See:
 *    - W3C WebAuthn Level 2, §6.5 "Authenticator data"
 *    - Coinbase Smart Wallet `WebAuthn.sol` reference implementation
 *    - ERC-7836 "Smart Account: keystore standardisation"
 */
final case class WebAuthnAssertion(
  authenticatorData: Array[Byte],
  clientDataJson:    Array[Byte],
  signatureDer:      Array[Byte],
)

object PasskeyAssertion:

  /** Extract and base64url-decode the `challenge` field from a
   *  clientDataJSON blob. WebAuthn challenges are base64url **without
   *  padding** per §5.1.3 of the spec (clientDataJSON.challenge is
   *  defined as `base64url(rawChallengeBytes)`).
   *
   *  Returns the raw bytes. For ERC-4337 this should be the 32-byte
   *  userOpHash the dApp passed to `navigator.credentials.get`. */
  def clientDataChallenge(json: Array[Byte]): Array[Byte] =
    val s        = String(json, "UTF-8")
    val raw      = stringField(s, "challenge").getOrElse(
      throw new IllegalArgumentException("clientDataJSON has no `challenge` field"))
    base64UrlDecode(raw)

  /** The digest the WebAuthn signature is computed over:
   *      sha256( authenticatorData || sha256(clientDataJson) )
   *
   *  Useful for off-chain assertion verification and for tests that
   *  feed a hand-rolled signature into the signer.
   *
   *  Note: this is the digest the *signature* binds to. The on-chain
   *  PasskeyAccount contract recomputes the same value before calling
   *  the P-256 verifier. */
  def digestForVerification(authData: Array[Byte], clientDataJson: Array[Byte]): Array[Byte] =
    val be    = CryptoBackend.get()
    val cdH   = be.hash(HashAlgo.Sha256, clientDataJson)
    val concat = new Array[Byte](authData.length + cdH.length)
    System.arraycopy(authData,       0, concat, 0,                authData.length)
    System.arraycopy(cdH,            0, concat, authData.length,  cdH.length)
    be.hash(HashAlgo.Sha256, concat)

  /** Locate `"<key>"` inside a flat JSON object and return its string
   *  value. We avoid pulling in upickle here — clientDataJSON is a
   *  predictable shape (no nested objects in the keys we read) and
   *  WebAuthn implementations are strict about it. */
  private def stringField(json: String, key: String): Option[String] =
    val needle = "\"" + key + "\""
    val ki     = json.indexOf(needle)
    if ki < 0 then None
    else
      var i = ki + needle.length
      while i < json.length && json.charAt(i).isWhitespace do i += 1
      if i >= json.length || json.charAt(i) != ':' then None
      else
        i += 1
        while i < json.length && json.charAt(i).isWhitespace do i += 1
        if i >= json.length || json.charAt(i) != '"' then None
        else
          i += 1
          val sb = StringBuilder()
          var done = false
          while !done && i < json.length do
            val c = json.charAt(i)
            if c == '"' then done = true
            else if c == '\\' && i + 1 < json.length then
              json.charAt(i + 1) match
                case '"'  => sb.append('"');  i += 2
                case '\\' => sb.append('\\'); i += 2
                case '/'  => sb.append('/');  i += 2
                case 'n'  => sb.append('\n'); i += 2
                case 'r'  => sb.append('\r'); i += 2
                case 't'  => sb.append('\t'); i += 2
                case _    => sb.append(c); i += 1
            else
              sb.append(c); i += 1
          if done then Some(sb.toString) else None

  /** Locate the **byte offset** of the substring `"<key>":` (after any
   *  whitespace before the colon) in a JSON byte buffer. The on-chain
   *  PasskeyAccount packs the index of `"challenge":` so it can verify
   *  the challenge content cheaply without parsing JSON in Solidity.
   *
   *  Returns `-1` if the field isn't found. */
  def fieldByteOffset(json: Array[Byte], key: String): Int =
    val s      = String(json, "UTF-8")
    val needle = "\"" + key + "\""
    val ki     = s.indexOf(needle)
    if ki < 0 then -1
    else
      // Convert char offset → byte offset. clientDataJSON is always
      // valid UTF-8 with mostly ASCII content; for the field keys we
      // care about (`type`, `challenge`) the prefix is pure ASCII and
      // char offset == byte offset.
      ki

  // ── base64url-without-padding ────────────────────────────────────────

  private val urlDecoder = Base64.getUrlDecoder

  /** Decode base64url, accepting both padded and unpadded forms (per
   *  RFC 4648 §5). WebAuthn uses unpadded; some clients add padding. */
  def base64UrlDecode(s: String): Array[Byte] =
    // The JDK decoder rejects unpadded input on some old versions; add
    // pad bytes defensively so we accept either shape.
    val pad = (4 - s.length % 4) % 4
    val padded = if pad == 0 then s else s + ("=" * pad)
    urlDecoder.decode(padded)
