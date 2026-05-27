package scalascript.x402.facilitator.plutus

import scalascript.blockfrost.BlockfrostClient
import scalascript.x402.Network

import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script
import com.bloxbean.cardano.client.spec.{Era, NetworkId}
import com.bloxbean.cardano.client.transaction.spec.*
import com.bloxbean.cardano.client.crypto.SecretKey

import java.math.BigInteger
import java.util.Collections
import scala.concurrent.{ExecutionContext, Future}

/** One-time operator helper that posts the compiled x402 Plutus V3 escrow
 *  script to the chain as a **reference-script output** (CIP-33).
 *
 *  After calling `deploy` once, store the returned `"<txHash>#<index>"` in
 *  `ScalusSettlerConfig.referenceScriptRef` so claim transactions can refer
 *  to the on-chain copy instead of embedding the ~6 KB validator in every
 *  submission.
 *
 *  The output carries no datum and a minUTxO-sized ADA value so the node
 *  accepts it.  The tx is signed by the relayer key passed in `signingKeyHex`
 *  and submitted via `blockfrost`. */
object ReferenceScriptDeployer:

  /** Minimum lovelace to satisfy the node's minUTxO constraint for a
   *  reference-script output.  Conservative; well above the 4310-per-byte
   *  cost for the ~6 KB script. */
  val minUtxoLovelace: BigInt = BigInt(30_000_000)

  /** Deploy the compiled x402 escrow validator as a reference-script UTxO.
   *
   *  @param blockfrost     Blockfrost client targeting the correct network.
   *  @param network        `CardanoMainnet`, `CardanoPreprod`, or
   *                        `CardanoPreview`.
   *  @param signingKeyHex  Raw Ed25519 signing key (32 bytes hex) of the
   *                        address that funds the output.
   *  @param feeLovelace    Transaction fee.  Default is a conservative
   *                        200 000 lovelace; override for precise balancing.
   *  @return               `(txHash, outputIndex)` of the reference-script
   *                        output.  Store as
   *                        `ScalusSettlerConfig.referenceScriptRef`. */
  def deploy(
    blockfrost:    BlockfrostClient,
    network:       Network,
    signingKeyHex: String,
    feeLovelace:   BigInt = BigInt(200_000),
  )(using ExecutionContext): Future[(String, Int)] =
    val txBytes = buildDeployTx(network, signingKeyHex, feeLovelace)
    blockfrost.submitTx(txBytes).map { txHash =>
      (txHash, 0)
    }

  /** Build the reference-script deploy transaction.
   *
   *  The output at index 0 carries:
   *  - Address: the escrow script address itself (canonical choice; any
   *    address could hold the reference, but posting it at the script
   *    address makes discovery easy).
   *  - Value: `minUtxoLovelace` ADA (covers minUTxO for the script size).
   *  - Script: the compiled `PlutusV3Script` bytes.
   *  - Datum: none (reference-script outputs don't need a datum). */
  private[plutus] def buildDeployTx(
    network:       Network,
    signingKeyHex: String,
    feeLovelace:   BigInt,
  ): Array[Byte] =
    val scriptAddress = EscrowScript.address(network)
    val networkId     = if network == Network.CardanoMainnet then NetworkId.MAINNET else NetworkId.TESTNET

    val script: PlutusV3Script = PlutusV3Script.builder()
      .cborHex(X402EscrowCompiled.doubleCborHex)
      .build()
      .asInstanceOf[PlutusV3Script]

    val output = TransactionOutput.builder()
      .address(scriptAddress)
      .value(Value.fromCoin(new BigInteger(minUtxoLovelace.toString)))
      .scriptRef(script)
      .build()

    val body = TransactionBody.builder()
      .outputs(Collections.singletonList(output))
      .fee(new BigInteger(feeLovelace.toString))
      .networkId(networkId)
      .build()

    val witnesses = TransactionWitnessSet.builder()
      .build()

    val tx = Transaction.builder()
      .era(Era.Conway)
      .body(body)
      .witnessSet(witnesses)
      .isValid(true)
      .build()

    val signed = com.bloxbean.cardano.client.transaction.TransactionSigner.INSTANCE.sign(
      tx,
      SecretKey.create(EscrowRedeemerCodec.hexToBytes(signingKeyHex)),
    )
    signed.serialize()
