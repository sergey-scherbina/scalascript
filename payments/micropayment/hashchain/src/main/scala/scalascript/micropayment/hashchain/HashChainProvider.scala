package scalascript.micropayment.hashchain

import scalascript.micropayment.spi.*
import scalascript.blockchain.spi.{ChainAdapter, ChainContext}
import scalascript.crypto.Ed25519
import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant
import java.util.UUID
import java.security.SecureRandom

/** A [[ChannelProvider]] for PayWord hash-chain micropayments — a self-contained off-chain settlement scheme:
 *  one signature at channel open (the payer authorizes the chain tip), then signature-free per-payment preimage
 *  reveals. Parity with the other providers (probabilistic / state-channel / Hydra / threshold-batching) behind
 *  the same SPI.
 *
 *  `unitValue` is the amount one chain step is worth; `length` bounds the channel's total capacity at
 *  `length * unitValue`. `payerSeed` is the payer's 32-byte Ed25519 seed used to sign the open commitment. */
class HashChainProvider(
    chain:     ChainAdapter,
    ctx:       ChainContext,
    unitValue: BigInt,
    length:    Int,
    payerSeed: Array[Byte],
) extends ChannelProvider:

  require(unitValue > 0, "unitValue must be > 0")
  require(length >= 1, "chain length must be >= 1")
  require(payerSeed.length == 32, "payerSeed must be a 32-byte Ed25519 seed")

  /** The payer's Ed25519 public key — what a payee / settlement contract verifies the open signature against. */
  val payerPublicKey: Array[Byte] = Ed25519.derivePublicKey(payerSeed)

  def kind: ChannelKind = ChannelKind.HashChain

  def open(config: ChannelConfig)(using ec: ExecutionContext): Future[MicropaymentChannel] =
    Future.successful(openPair(UUID.randomUUID().toString, config)._1)

  def restore(channelId: ChannelId)(using ec: ExecutionContext): Future[Option[MicropaymentChannel]] =
    Future.successful(None)

  def listOpen()(using ec: ExecutionContext): Future[Seq[ChannelState]] =
    Future.successful(Seq.empty)

  /** Open a channel, returning both sides + the signed commitment: the payer-side channel (holds the secret
   *  seed), the payee-side channel (holds only the tip), the [[HashChainCommitment]], and the payer's signature
   *  over it. The payee must verify the signature with [[payerPublicKey]] before accepting payments. */
  def openPair(channelId: ChannelId, config: ChannelConfig)(using ec: ExecutionContext)
      : (HashChainChannel, HashChainChannel, HashChainCommitment, Array[Byte]) =
    val seed       = HashChainProvider.randomSeed()
    val tip        = HashChain.tip(seed, length)
    val commitment = HashChainCommitment(channelId, config.payee, tip, length, unitValue)
    val sig        = HashChainCommitment.sign(commitment, payerSeed)
    (mkChannel(channelId, config, tip, Some(seed)), mkChannel(channelId, config, tip, None), commitment, sig)

  private def mkChannel(id: ChannelId, config: ChannelConfig, tip: Array[Byte], seed: Option[Array[Byte]])(
      using ec: ExecutionContext): HashChainChannel =
    new HashChainChannel(
      channelId        = id,
      payeeAddress     = config.payee,
      assetInfo        = config.asset,
      openedAt         = Instant.now(),
      chain            = chain,
      ctx              = ctx,
      unitValue        = unitValue,
      length           = length,
      tip              = tip,
      settlementPolicy = config.settlementPolicy,
      seed             = seed,
    )

object HashChainProvider:
  def randomSeed(): Array[Byte] =
    val s = new Array[Byte](32)
    new SecureRandom().nextBytes(s)
    s
