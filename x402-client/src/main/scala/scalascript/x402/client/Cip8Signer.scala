package scalascript.x402.client

import scalascript.x402.{CardanoPaymentProof, MiniCbor}

/** Builds a CIP-8 / COSE_Sign1 payment proof from a precomputed Ed25519
 *  signature over the canonical Sig_Structure.
 *
 *  CIP-8 ([Cardano Improvement Proposal 8](https://cips.cardano.org/cips/cip8/))
 *  uses the COSE_Sign1 message format from RFC 8152:
 *  - Protected header carries the algorithm (`alg = EdDSA`, integer `-8`)
 *  - Payload is the raw message bytes (here: `req.description` UTF-8)
 *  - Signature is over the Sig_Structure `["Signature1", protected, h"", payload]`
 *  - The verification key travels alongside as a COSE_Key (CBOR map)
 *
 *  Mirrors `CardanoFacilitator.Cip8Verifier` on the server side. */
object Cip8Signer:

  // alg = EdDSA = -8 ; encoded as NInt(7) since -(7+1) = -8
  private val protectedHeader: Array[Byte] = MiniCbor.encode(MiniCbor.Map(IndexedSeq(
    MiniCbor.UInt(1) -> MiniCbor.NInt(7),
  )))

  /** Build the Sig_Structure bytes that the client must sign. */
  def sigStructure(message: Array[Byte]): Array[Byte] =
    MiniCbor.encode(MiniCbor.Arr(IndexedSeq(
      MiniCbor.Text("Signature1"),
      MiniCbor.Bytes(protectedHeader),
      MiniCbor.Bytes(Array.empty),
      MiniCbor.Bytes(message),
    )))

  /** Assemble a `CardanoPaymentProof` given a 64-byte Ed25519 signature over
   *  `sigStructure(message)`, the 32-byte raw public key, and the bech32
   *  address the payment is debited from. */
  def buildProof(
    message:   Array[Byte],
    signature: Array[Byte],
    publicKey: Array[Byte],
    address:   String,
  ): CardanoPaymentProof =
    require(signature.length == 64, s"Ed25519 signature must be 64 bytes, got ${signature.length}")
    require(publicKey.length == 32, s"Ed25519 public key must be 32 bytes, got ${publicKey.length}")

    // COSE_Sign1 = [protected_bstr, {}, payload_bstr, sig_bstr]
    val coseSign1 = MiniCbor.encode(MiniCbor.Arr(IndexedSeq(
      MiniCbor.Bytes(protectedHeader),
      MiniCbor.Map(IndexedSeq.empty),
      MiniCbor.Bytes(message),
      MiniCbor.Bytes(signature),
    )))

    // COSE_Key = {1: 1 (kty=OKP), 3: -8 (alg=EdDSA), -1: 6 (crv=Ed25519), -2: pubkey}
    val coseKey = MiniCbor.encode(MiniCbor.Map(IndexedSeq(
      MiniCbor.UInt(1) -> MiniCbor.UInt(1),
      MiniCbor.UInt(3) -> MiniCbor.NInt(7),
      MiniCbor.NInt(0) -> MiniCbor.UInt(6),
      MiniCbor.NInt(1) -> MiniCbor.Bytes(publicKey),
    )))

    CardanoPaymentProof(
      address   = address,
      signature = bytesToHex(coseSign1),
      key       = bytesToHex(coseKey),
    )

  private def bytesToHex(b: Array[Byte]): String =
    b.map(x => f"${x & 0xFF}%02x").mkString
