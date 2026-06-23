package scalascript.blockchain.cardano

import scalascript.crypto.Blake2b

// ── Transaction types ─────────────────────────────────────────────────────────
//
// These are the portable, backend-agnostic Cardano transaction data types and
// their CBOR serialization. They depend only on the shared `CardanoCbor` codec
// and the portable `Blake2b` reference, so they cross-compile to Scala.js — the
// Blockfrost-backed `CardanoChainAdapter` (JVM-only) builds and signs them, but a
// browser wallet can construct and hash the same bytes without any JVM I/O.

case class CardanoUtxo(txHash: String, index: Int, lovelace: BigInt, assets: Map[String, BigInt])

case class CardanoTx(
  senderAddress:  String,
  outputAddress:  String,
  lovelace:       BigInt,
  assetTransfer:  Option[(String, BigInt)],
  fee:            BigInt,
  change:         BigInt,
  inputs:         Seq[CardanoUtxo],
):
  lazy val txBodyCbor: Array[Byte] = CardanoTxBody.encode(this)
  // BLAKE2b-256 transaction-body hash via the portable reference (no `org.bouncycastle`).
  lazy val txBodyHash: Array[Byte] = Blake2b.hash256(txBodyCbor)

case class CardanoSignedTx(
  tx:          CardanoTx,
  signature:   Array[Byte],
  pubKeyBytes: Array[Byte],
)

/** Encode a `CardanoTx` body as CBOR for signing and submission. */
private object CardanoTxBody:
  def encode(tx: CardanoTx): Array[Byte] =
    import CardanoCbor.*
    val inputs = Arr(tx.inputs.map(u =>
      Arr(Seq(Bytes(hexToBytes(u.txHash)), UInt(u.index.toLong)))
    ))
    val changeOutput = Arr(Seq(
      Text(tx.senderAddress),
      UInt(tx.change.toLong),
    ))
    val mainOutput = tx.assetTransfer match
      case None =>
        Arr(Seq(Text(tx.outputAddress), UInt(tx.lovelace.toLong)))
      case Some((unit, qty)) =>
        val policyId = unit.take(56)
        val assetName = hexToBytes(unit.drop(56))
        val multiAsset = Map(Seq(
          Bytes(hexToBytes(policyId)) -> Map(Seq(Bytes(assetName) -> UInt(qty.toLong)))
        ))
        Arr(Seq(Text(tx.outputAddress), Arr(Seq(UInt(tx.lovelace.toLong), multiAsset))))
    val body = Map(Seq(
      UInt(0) -> inputs,
      UInt(1) -> Arr(Seq(mainOutput, changeOutput)),
      UInt(2) -> UInt(tx.fee.toLong),
    ))
    CardanoCbor.encode(body)

  private def hexToBytes(hex: String): Array[Byte] =
    val h = if hex.startsWith("0x") then hex.drop(2) else hex
    h.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
