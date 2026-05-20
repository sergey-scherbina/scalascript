package scalascript.wallet.strategy.erc4337

import java.math.BigInteger
import scala.concurrent.{ExecutionContext, Future}
import scalascript.blockchain.evm.abi.{AbiEncoder, AbiType, AbiValue}
import scalascript.crypto.{Curve, HashAlgo, PublicKey}
import scalascript.wallet.spi.RawSigner

/** WebAuthn / Passkey signer for ERC-4337 smart accounts whose owner
 *  is a P-256 credential on the user's authenticator (TouchID, Face
 *  ID, YubiKey, Android Strongbox, …).
 *
 *  Architecture: this class is curve = P-256 RawSigner. It does NOT
 *  itself talk to the browser; it delegates that to an
 *  `assertChallenge` callback whose signature is intentionally
 *  platform-agnostic. On JS, the callback wraps
 *  `navigator.credentials.get(...)`. On JVM (this slice), tests
 *  inject a deterministic implementation that uses
 *  [[scalascript.crypto.CryptoBackend]] to produce a real DER P-256
 *  signature over the WebAuthn-style digest.
 *
 *  Output: the bytes returned from `sign` are the **signature blob**
 *  the on-chain PasskeyAccount expects in `UserOperation.signature`,
 *  not the raw (r, s) pair. The blob is decoded by the account's
 *  `_validateSignature` and re-checked against the credential's
 *  stored public key.
 *
 *  Pack format (chosen): ABI-encoded
 *
 *    abi.encode(
 *      bytes  authenticatorData,
 *      bytes  clientDataJSON,
 *      bytes32 r,
 *      bytes32 s,
 *      uint256 challengeIndex,
 *      uint256 typeIndex,
 *    )
 *
 *  matching the layout shipped by the Coinbase Smart Wallet
 *  `WebAuthn.sol` library (which is also the reference implementation
 *  cited by ERC-7836 and used by Daimo / Alchemy / Pimlico passkey
 *  accounts). `challengeIndex` and `typeIndex` are byte offsets into
 *  clientDataJSON, so the verifier can confirm the embedded
 *  `"type":"webauthn.get"` and `"challenge":"..."` substrings without
 *  parsing JSON in Solidity.
 *
 *  References:
 *    - Coinbase Smart Wallet, https://github.com/coinbase/smart-wallet
 *      `src/utils/WebAuthn.sol` `WebAuthnAuth` struct + `verify`
 *    - ERC-7836 "Smart Account: keystore standardisation"
 *    - W3C WebAuthn Level 2 §6.5 (authenticatorData),
 *      §5.1.3 (clientDataJSON)
 */
class PasskeySigner(
  val publicKey:      PublicKey,
  val assertChallenge: Array[Byte] => Future[WebAuthnAssertion],
)(using ec: ExecutionContext) extends RawSigner:

  require(publicKey.curve == Curve.P256, s"PasskeySigner is P-256 only; got ${publicKey.curve}")

  def curve: Curve = Curve.P256

  /** Sign by delegating to the authenticator.
   *
   *  Contract:
   *    - `hash` must be [[HashAlgo.None]]. The on-chain PasskeyAccount
   *      reconstructs the WebAuthn digest itself; passing a hash algo
   *      here would imply an extra digest stage the contract doesn't
   *      apply.
   *    - `msg` is the 32-byte challenge (for ERC-4337 the userOpHash).
   *    - The authenticator's clientDataJSON.challenge MUST equal the
   *      base64url encoding of `msg`. We verify this on return as a
   *      safety net against a misbehaving delegate. */
  def sign(msg: Array[Byte], hash: HashAlgo = HashAlgo.None): Future[Array[Byte]] =
    require(hash == HashAlgo.None,
      s"PasskeySigner.sign requires HashAlgo.None (got $hash); the smart contract " +
      "applies the WebAuthn digest itself.")
    assertChallenge(msg).map { assertion =>
      // Defence in depth: the challenge embedded in clientDataJSON
      // must match the challenge we asked for. If a JS facade or a
      // mocked authenticator returns the wrong assertion, we refuse
      // to package it.
      val embedded = PasskeyAssertion.clientDataChallenge(assertion.clientDataJson)
      if !java.util.Arrays.equals(embedded, msg) then
        throw new IllegalStateException(
          s"WebAuthn assertion's challenge (${embedded.length}B) does not match the " +
          s"requested challenge (${msg.length}B)")
      packAssertion(assertion)
    }

  /** Convert the assertion into the ABI-encoded blob a PasskeyAccount
   *  validates against.
   *
   *  Steps:
   *    1. DER → raw (r, s) decoding.
   *    2. Low-s normalisation: if s > n/2 set s = n - s. The on-chain
   *       verifier rejects high-s to prevent malleability.
   *    3. ABI-encode the tuple. */
  def packAssertion(assertion: WebAuthnAssertion): Array[Byte] =
    val (r, s) = derToRawLowS(assertion.signatureDer)
    val cdJson = assertion.clientDataJson
    val chIdx  = PasskeyAssertion.fieldByteOffset(cdJson, "challenge")
    val tyIdx  = PasskeyAssertion.fieldByteOffset(cdJson, "type")
    if chIdx < 0 then throw new IllegalArgumentException("clientDataJSON missing `challenge` field")
    if tyIdx < 0 then throw new IllegalArgumentException("clientDataJSON missing `type` field")
    AbiEncoder.encodeTuple(
      Seq(
        AbiType.Bytes,
        AbiType.Bytes,
        AbiType.FixedBytes(32),
        AbiType.FixedBytes(32),
        AbiType.UInt(256),
        AbiType.UInt(256),
      ),
      Seq(
        AbiValue.Bytes(assertion.authenticatorData),
        AbiValue.Bytes(cdJson),
        AbiValue.FixedBytes(32, r),
        AbiValue.FixedBytes(32, s),
        AbiValue.UInt(256, BigInt(chIdx)),
        AbiValue.UInt(256, BigInt(tyIdx)),
      ),
    )

  // ── DER → raw (r, s) with low-s normalisation ─────────────────────────

  /** secp256r1 group order, copied from SEC2. Hardcoded so this file
   *  doesn't pull in BouncyCastle types (the wallet-strategy-erc4337
   *  module already depends on CryptoBackend abstractly). */
  private val P256N: BigInteger =
    BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
  private val P256HalfN: BigInteger = P256N.shiftRight(1)

  /** Parse an ECDSA DER signature
   *      30 || L || 02 || rLen || r || 02 || sLen || s
   *  Returns `(r, s)` as 32-byte big-endian unsigned arrays with
   *  low-s normalisation applied. */
  def derToRawLowS(der: Array[Byte]): (Array[Byte], Array[Byte]) =
    if der.length < 8 || (der(0) & 0xff) != 0x30 then
      throw new IllegalArgumentException(s"Not a DER ECDSA signature (first byte=${der(0) & 0xff})")

    // Outer SEQUENCE length — either 1-byte short form or 1-byte long-form
    // prefix (0x81/0x82) + length bytes. P-256 sigs fit in short form.
    var off = 1
    val outerLen =
      val b0 = der(off) & 0xff
      if (b0 & 0x80) == 0 then
        off += 1
        b0
      else
        val n = b0 & 0x7f
        if n < 1 || n > 2 then
          throw new IllegalArgumentException(s"Unsupported DER length encoding: 0x${b0.toHexString}")
        off += 1
        var v = 0
        for _ <- 0 until n do
          v = (v << 8) | (der(off) & 0xff)
          off += 1
        v
    if off + outerLen != der.length then
      throw new IllegalArgumentException(
        s"DER outer length mismatch: expected ${off + outerLen}, got ${der.length}")

    def readInt(): BigInteger =
      if (der(off) & 0xff) != 0x02 then
        throw new IllegalArgumentException(s"Expected INTEGER tag (0x02) at offset $off")
      off += 1
      val len = der(off) & 0xff
      if (len & 0x80) != 0 then
        throw new IllegalArgumentException("Long-form INTEGER length in ECDSA signature — never legitimate for P-256")
      off += 1
      val end = off + len
      if end > der.length then
        throw new IllegalArgumentException(s"INTEGER overrun: $end > ${der.length}")
      val bytes = java.util.Arrays.copyOfRange(der, off, end)
      off = end
      BigInteger(bytes)  // signed; DER uses leading 0x00 to keep positive

    val r = readInt()
    val s0 = readInt()
    if r.signum <= 0 || s0.signum <= 0 then
      throw new IllegalArgumentException("ECDSA signature components must be positive")

    // Low-s normalisation. P-256 has no recovery byte so there's
    // nothing else to flip.
    val s = if s0.compareTo(P256HalfN) > 0 then P256N.subtract(s0) else s0

    (toUnsigned32(r), toUnsigned32(s))

  private def toUnsigned32(x: BigInteger): Array[Byte] =
    val raw = x.toByteArray
    if raw.length == 32 then raw
    else if raw.length == 33 && raw(0) == 0 then java.util.Arrays.copyOfRange(raw, 1, 33)
    else if raw.length < 32 then
      val padded = new Array[Byte](32)
      System.arraycopy(raw, 0, padded, 32 - raw.length, raw.length)
      padded
    else
      throw new IllegalArgumentException(
        s"BigInteger too large for 32-byte unsigned encoding (${raw.length} bytes)")
