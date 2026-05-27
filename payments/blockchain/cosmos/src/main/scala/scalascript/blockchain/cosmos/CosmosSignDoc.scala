package scalascript.blockchain.cosmos

import java.util.Base64

/** Cosmos SDK StdSignDoc / Amino signing.
 *
 *  `StdSignDoc` is the canonical JSON structure that wallets sign.
 *  Field order is mandated by the Amino spec (alphabetical by key):
 *  `account_number`, `chain_id`, `fee`, `memo`, `msgs`, `sequence`.
 *
 *  `aminoEncode` serializes to canonical JSON bytes (UTF-8).
 *  For simple Cosmos messages Amino encoding is exactly canonical JSON —
 *  no binary framing required for the signing payload.
 *
 *  `signStdTx` hashes the Amino bytes with SHA256 and signs with
 *  either secp256k1 (DER, then base64) or ed25519 (64-byte raw, then base64). */
object CosmosSignDoc:

  // ── Types ──────────────────────────────────────────────────────────────────

  /** A single coin amount, e.g. `{"amount":"1000","denom":"uatom"}`. */
  case class Coin(amount: String, denom: String)

  /** The fee field inside StdSignDoc. */
  case class Fee(amount: Seq[Coin], gas: String)

  /** The canonical Cosmos StdSignDoc. */
  case class StdSignDoc(
    chain_id:       String,
    account_number: String,
    sequence:       String,
    fee:            Fee,
    msgs:           Seq[ujson.Value],
    memo:           String,
  )

  enum Curve:
    case Secp256k1, Ed25519

  // ── Amino / JSON serialisation ─────────────────────────────────────────────

  /** Serialise a StdSignDoc to canonical Amino JSON bytes (UTF-8).
   *
   *  Field order follows the Amino spec (alphabetical):
   *  account_number → chain_id → fee → memo → msgs → sequence. */
  def aminoEncode(doc: StdSignDoc): Array[Byte] =
    toJson(doc).getBytes("UTF-8")

  /** Render StdSignDoc as a canonical JSON string. */
  def toJson(doc: StdSignDoc): String =
    val feeCoins = doc.fee.amount.map { c =>
      // Coin fields: amount, denom (alphabetical)
      s"""{"amount":"${c.amount}","denom":"${c.denom}"}"""
    }.mkString("[", ",", "]")
    val feeObj = s"""{"amount":$feeCoins,"gas":"${doc.fee.gas}"}"""
    val msgsArr = doc.msgs.map(ujson.write(_)).mkString("[", ",", "]")
    // Amino field order (alphabetical): account_number, chain_id, fee, memo, msgs, sequence
    s"""{"account_number":"${doc.account_number}","chain_id":"${doc.chain_id}","fee":$feeObj,"memo":"${doc.memo}","msgs":$msgsArr,"sequence":"${doc.sequence}"}"""

  // ── Sign ───────────────────────────────────────────────────────────────────

  /** Sign a StdSignDoc.
   *
   *  Process:
   *  1. Amino-encode (canonical JSON UTF-8)
   *  2. SHA256 hash
   *  3. Sign with secp256k1 (DER) or ed25519 (raw 64 bytes)
   *  4. Base64-encode signature
   *
   *  Returns `(signatureBase64, pubKeyBase64)`. */
  def signStdTx(
    privateKey: Array[Byte],
    doc:        StdSignDoc,
    curve:      Curve,
  ): (String, String) =
    val msgBytes = aminoEncode(doc)
    curve match
      case Curve.Secp256k1 =>
        val hash     = CosmosCrypto.sha256(msgBytes)
        val sig      = CosmosCrypto.sign(privateKey, hash)
        val pubBytes = CosmosCrypto.deriveCompressedPublicKey(privateKey)
        (base64(sig), base64(pubBytes))
      case Curve.Ed25519 =>
        val sig      = CosmosCrypto.signEd25519(privateKey, msgBytes)
        val priv     = new org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(privateKey.take(32))
        val pubBytes = priv.generatePublicKey().getEncoded
        (base64(sig), base64(pubBytes))

  private def base64(bytes: Array[Byte]): String =
    Base64.getEncoder.encodeToString(bytes)
