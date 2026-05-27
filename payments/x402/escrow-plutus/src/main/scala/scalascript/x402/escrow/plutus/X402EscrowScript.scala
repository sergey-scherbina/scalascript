package scalascript.x402.escrow.plutus

import scalus.*
import scalus.Compile
import scalus.uplc.builtin.{Builtins, ByteString, Data, FromData, ToData}
import scalus.cardano.onchain.plutus.prelude.*
import scalus.cardano.onchain.plutus.v1.PubKeyHash
import scalus.cardano.onchain.plutus.v3.{ScriptContext, ScriptInfo}
import scalus.uplc.PlutusV3

// ── Datum + Redeemer types ────────────────────────────────────────────────
//
// Top-level so Scalus 0.15.1's @Compile macro can derive FromData /
// ToData. Nested-in-object case classes don't pick up derivation.

case class EscrowDatum(
  payerKeyHash:     PubKeyHash,
  claimMessageHash: ByteString,
  receiverHash:     PubKeyHash,
  amount:           BigInt,
  validBefore:      BigInt,
  refundAfter:      BigInt,
) derives FromData, ToData

enum EscrowRedeemer derives FromData, ToData:
  case Claim(coseSign1: ByteString, coseKey: ByteString)
  case Refund

/** Plutus V3 escrow validator for x402 Cardano payments.
 *
 *  Single-purpose validator written as a plain `@Compile object` with
 *  `inline def validate(scData: Data): Unit` rather than extending
 *  Scalus's `Validator` trait — that trait exposes six deferred-inline
 *  purpose methods (mint/spend/withdraw/certify/vote/propose) that all
 *  need to be supplied for compilation, even when only `spend` is
 *  meaningful. Dispatching on `ScriptInfo.SpendingScript` directly is
 *  simpler and avoids the unused-purpose boilerplate.
 *
 *  Claim validates the canonical CIP-8 shape emitted by our
 *  `Cip8Signer`:
 *    - COSE_Key must carry a 32-byte Ed25519 public key at key `-2`
 *      in the exact canonical map shape `{1:1, 3:-8, -1:6, -2:x}`.
 *    - COSE_Sign1 must be `[protected_bstr, {}, payload_bstr, sig_bstr]`
 *      with protected header `{1:-8}`.
 *    - `blake2b_224(pubKey) == datum.payerKeyHash`.
 *    - `blake2b_256(payload) == datum.claimMessageHash`.
 *    - Ed25519 verifies over
 *      `["Signature1", protected_bstr, h"", payload_bstr]`.
 *
 *  This is intentionally not a general CBOR/COSE parser; the off-chain
 *  client and facilitator already own the canonical wire format.
 *
 *  See [`docs/x402-cardano-scalus.md`](../../../../../../../docs/x402-cardano-scalus.md). */
@Compile
object X402EscrowScript:

  inline def validate(scData: Data): Unit =
    val ctx = scData.to[ScriptContext]
    ctx.scriptInfo match
      case ScriptInfo.SpendingScript(_, datumOpt) =>
        val d = datumOpt.getOrFail("Missing escrow datum").to[EscrowDatum]
        val r = ctx.redeemer.to[EscrowRedeemer]
        r match
          case EscrowRedeemer.Claim(coseSign1, coseKey) =>
            require(verifyCanonicalCip8(d, coseSign1, coseKey), "Invalid CIP-8 claim proof")
            require(
              ctx.txInfo.signatories.contains(d.receiverHash),
              "Claim must be signed by the receiver (proxy: receiverHash)",
            )
          case EscrowRedeemer.Refund =>
            require(
              ctx.txInfo.signatories.contains(d.payerKeyHash),
              "Refund must be signed by the payer",
            )
      case _ =>
        require(false, "X402EscrowScript only supports the spending purpose")

  inline private def verifyCanonicalCip8(
    datum:     EscrowDatum,
    coseSign1: ByteString,
    coseKey:   ByteString,
  ): Boolean =
    val pubKey = canonicalCoseKeyPubKey(coseKey)
    val payload = canonicalCosePayload(coseSign1)
    val sig = canonicalCoseSignature(coseSign1)
    val sigStructure = cip8SigStructure(payload)
    Builtins.blake2b_224(pubKey) == datum.payerKeyHash.hash &&
      Builtins.blake2b_256(payload) == datum.claimMessageHash &&
      Builtins.verifyEd25519Signature(pubKey, sigStructure, sig)

  inline private def canonicalCoseKeyPubKey(coseKey: ByteString): ByteString =
    require(Builtins.lengthOfByteString(coseKey) == BigInt(42), "COSE_Key length")
    require(byteAt(coseKey, BigInt(0)) == BigInt(0xa4), "COSE_Key map")
    require(byteAt(coseKey, BigInt(1)) == BigInt(0x01), "COSE_Key kty key")
    require(byteAt(coseKey, BigInt(2)) == BigInt(0x01), "COSE_Key kty OKP")
    require(byteAt(coseKey, BigInt(3)) == BigInt(0x03), "COSE_Key alg key")
    require(byteAt(coseKey, BigInt(4)) == BigInt(0x27), "COSE_Key alg EdDSA")
    require(byteAt(coseKey, BigInt(5)) == BigInt(0x20), "COSE_Key crv key")
    require(byteAt(coseKey, BigInt(6)) == BigInt(0x06), "COSE_Key crv Ed25519")
    require(byteAt(coseKey, BigInt(7)) == BigInt(0x21), "COSE_Key x key")
    require(byteAt(coseKey, BigInt(8)) == BigInt(0x58), "COSE_Key x bytes")
    require(byteAt(coseKey, BigInt(9)) == BigInt(0x20), "COSE_Key x length")
    Builtins.sliceByteString(BigInt(10), BigInt(32), coseKey)

  inline private def canonicalCosePayload(coseSign1: ByteString): ByteString =
    require(cosePrefixOk(coseSign1), "COSE_Sign1 prefix")
    val payloadLen = byteAt(coseSign1, BigInt(7))
    require(payloadLen > BigInt(0), "COSE_Sign1 payload length")
    val sigHeader = BigInt(8) + payloadLen
    require(byteAt(coseSign1, sigHeader) == BigInt(0x58), "COSE_Sign1 signature bytes")
    require(byteAt(coseSign1, sigHeader + BigInt(1)) == BigInt(0x40), "COSE_Sign1 signature length")
    require(Builtins.lengthOfByteString(coseSign1) == sigHeader + BigInt(66), "COSE_Sign1 total length")
    Builtins.sliceByteString(BigInt(8), payloadLen, coseSign1)

  inline private def canonicalCoseSignature(coseSign1: ByteString): ByteString =
    val payloadLen = byteAt(coseSign1, BigInt(7))
    Builtins.sliceByteString(BigInt(10) + payloadLen, BigInt(64), coseSign1)

  inline private def cosePrefixOk(coseSign1: ByteString): Boolean =
    Builtins.lengthOfByteString(coseSign1) >= BigInt(74) &&
      byteAt(coseSign1, BigInt(0)) == BigInt(0x84) &&
      byteAt(coseSign1, BigInt(1)) == BigInt(0x43) &&
      byteAt(coseSign1, BigInt(2)) == BigInt(0xa1) &&
      byteAt(coseSign1, BigInt(3)) == BigInt(0x01) &&
      byteAt(coseSign1, BigInt(4)) == BigInt(0x27) &&
      byteAt(coseSign1, BigInt(5)) == BigInt(0xa0) &&
      byteAt(coseSign1, BigInt(6)) == BigInt(0x58)

  inline private def cip8SigStructure(payload: ByteString): ByteString =
    append(
      ByteString.fromHex("846a5369676e61747572653143a1012740"),
      append(byteStringHeader(Builtins.lengthOfByteString(payload)), payload),
    )

  inline private def byteStringHeader(length: BigInt): ByteString =
    require(length > BigInt(0) && length < BigInt(256), "CIP-8 payload length must fit one-byte CBOR bytes header")
    Builtins.consByteString(BigInt(0x58), Builtins.consByteString(length, ByteString.empty))

  inline private def append(a: ByteString, b: ByteString): ByteString =
    Builtins.appendByteString(a, b)

  inline private def byteAt(bytes: ByteString, index: BigInt): BigInt =
    Builtins.indexByteString(bytes, index)

/** Off-chain handle. The compiled UPLC program's double-CBOR hex is
 *  the single artefact this sub-build produces. */
object X402EscrowCompiled:
  private given scalus.compiler.Options = scalus.compiler.Options.default
  lazy val compiled               = PlutusV3.compile(X402EscrowScript.validate)
  lazy val doubleCborHex: String  = compiled.program.doubleCborHex
