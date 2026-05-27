package scalascript.x402.facilitator.plutus

import org.bouncycastle.crypto.digests.Blake2bDigest
import scalascript.blockchain.cardano.CardanoAddress
import scalascript.x402.{PaymentRequirements, ScalusClaimMessageCodec}

import com.bloxbean.cardano.client.crypto.SecretKey
import com.bloxbean.cardano.client.plutus.spec.*
import com.bloxbean.cardano.client.spec.{Era, NetworkId}
import com.bloxbean.cardano.client.transaction.TransactionSigner
import com.bloxbean.cardano.client.transaction.spec.*

import java.math.BigInteger
import java.util.Collections
import scala.concurrent.Future

/** Off-chain datum for the x402 escrow script.  Field layout mirrors the
 *  on-chain `EscrowDatum` in `x402-escrow-plutus` exactly; types are JVM
 *  primitives suitable for bloxbean `PlutusData` serialisation.
 *
 *  @param payerKeyHash       Blake2b-224 of the payer's raw Ed25519 vk (28 bytes).
 *  @param claimMessageHash   Blake2b-256 of the CIP-8 claim message bytes (32 bytes).
 *  @param receiverHash       Payment-credential keyhash of the receiver address (28 bytes).
 *  @param amount             Exact lovelace amount the escrow holds.
 *  @param validBefore        Upper POSIX-time bound for a valid claim.
 *  @param refundAfter        Lower POSIX-time bound after which the payer may refund. */
case class EscrowDatumOffChain(
  payerKeyHash:     Array[Byte],
  claimMessageHash: Array[Byte],
  receiverHash:     Array[Byte],
  amount:           BigInt,
  validBefore:      BigInt,
  refundAfter:      BigInt,
):
  /** Encode as a bloxbean `ConstrPlutusData` (constructor 0).
   *  Field order matches the Scalus `EscrowDatum` case-class declaration. */
  def toPlutusData: PlutusData =
    ConstrPlutusData.of(
      0L,
      BytesPlutusData.of(payerKeyHash),
      BytesPlutusData.of(claimMessageHash),
      BytesPlutusData.of(receiverHash),
      BigIntPlutusData.of(new BigInteger(amount.toString)),
      BigIntPlutusData.of(new BigInteger(validBefore.toString)),
      BigIntPlutusData.of(new BigInteger(refundAfter.toString)),
    )

/** Deposit-side helper: builds a Cardano transaction that locks lovelace at
 *  the x402 escrow script address with the appropriate datum.
 *
 *  The payer calls `EscrowDeposit.build` before the HTTP request, deposits the
 *  funds on-chain, and passes the resulting UTxO ref as `escrowRef` in the
 *  `PaymentRequirements.scalusEscrowRef` field. */
object EscrowDeposit:

  /** Build a deposit transaction plan without submitting it.
   *
   *  @param payerPublicKeyHex  Raw 32-byte Ed25519 public key of the payer
   *                            (hex-encoded).  Used to derive `payerKeyHash`
   *                            via Blake2b-224.
   *  @param req                Payment requirements: provides `payTo` (receiver)
   *                            and the lovelace `amount`.
   *  @param validBeforeSlot    Upper validity slot for the claim (maps to
   *                            `datum.validBefore` in POSIX seconds).
   *  @param refundAfterSlot    Lower slot for the payer's refund window (maps
   *                            to `datum.refundAfter` in POSIX seconds).
   *  @param cfg                Settler config: provides network, receiver hash,
   *                            and signing key for tx construction.
   *  @return                   `DepositTxPlan` with the serialised unsigned
   *                            deposit CBOR and the datum. */
  def build(
    payerPublicKeyHex: String,
    req:               PaymentRequirements,
    validBeforeSlot:   Long,
    refundAfterSlot:   Long,
    cfg:               ScalusSettlerConfig,
  ): Future[DepositTxPlan] =
    val lovelace = req.scheme match
      case scalascript.x402.PaymentScheme.CardanoExact(l, None) => l
      case scalascript.x402.PaymentScheme.CardanoExact(_, Some(asset)) =>
        return Future.failed(UnsupportedOperationException(
          s"EscrowDeposit currently supports lovelace-only payments, got native asset ${asset.symbol}"
        ))
      case other =>
        return Future.failed(UnsupportedOperationException(
          s"EscrowDeposit only supports CardanoExact, got $other"
        ))

    // Derive payerKeyHash: Blake2b-224 of raw Ed25519 public key bytes
    val payerPubKeyBytes = EscrowRedeemerCodec.hexToBytes(payerPublicKeyHex)
    val payerKeyHash     = blake2b224(payerPubKeyBytes)

    // Extract receiver hash: payment credential bytes from bech32 address
    // CIP-19: header byte at index 0, then 28-byte payment keyhash
    val receiverAddrBytes = CardanoAddress.toBytes(req.payTo)
    val receiverHash      =
      if receiverAddrBytes.length >= 29 then receiverAddrBytes.slice(1, 29)
      else return Future.failed(IllegalArgumentException(
        s"Receiver address payload too short (${receiverAddrBytes.length} bytes): ${req.payTo}"
      ))

    // Build claim message bytes (same as what the payer will sign in Scalus mode)
    val claimMessage = ScalusClaimMessageCodec.encode(
      receiverAddrBytes, lovelace, BigInt(validBeforeSlot)
    )
    val claimMessageHash = blake2b256(claimMessage)

    val datum = EscrowDatumOffChain(
      payerKeyHash     = payerKeyHash,
      claimMessageHash = claimMessageHash,
      receiverHash     = receiverHash,
      amount           = lovelace,
      validBefore      = BigInt(validBeforeSlot),
      refundAfter      = BigInt(refundAfterSlot),
    )

    val scriptAddress = EscrowScript.address(cfg.network)
    val networkId     = if cfg.network == scalascript.x402.Network.CardanoMainnet
      then NetworkId.MAINNET else NetworkId.TESTNET

    val output = TransactionOutput.builder()
      .address(scriptAddress)
      .value(Value.fromCoin(new BigInteger(lovelace.toString)))
      .inlineDatum(datum.toPlutusData)
      .build()

    val body = TransactionBody.builder()
      .outputs(Collections.singletonList(output))
      .fee(new BigInteger(cfg.feeLovelace.toString))
      .networkId(networkId)
      .build()

    val witnesses = TransactionWitnessSet.builder().build()

    val tx = Transaction.builder()
      .era(Era.Conway)
      .body(body)
      .witnessSet(witnesses)
      .isValid(true)
      .build()

    val signed = TransactionSigner.INSTANCE.sign(
      tx,
      SecretKey.create(EscrowRedeemerCodec.hexToBytes(cfg.relayerSigningKeyHex)),
    )

    Future.successful(DepositTxPlan(
      depositTxCbor   = signed.serialize(),
      datum           = datum,
      scriptAddress   = scriptAddress,
      lovelace        = lovelace,
    ))

  private def blake2b224(bytes: Array[Byte]): Array[Byte] =
    val digest = Blake2bDigest(224)
    digest.update(bytes, 0, bytes.length)
    val out = new Array[Byte](28)
    digest.doFinal(out, 0)
    out

  private def blake2b256(bytes: Array[Byte]): Array[Byte] =
    val digest = Blake2bDigest(256)
    digest.update(bytes, 0, bytes.length)
    val out = new Array[Byte](32)
    digest.doFinal(out, 0)
    out

/** Result of `EscrowDeposit.build`. */
case class DepositTxPlan(
  depositTxCbor: Array[Byte],
  datum:         EscrowDatumOffChain,
  scriptAddress: String,
  lovelace:      BigInt,
)
