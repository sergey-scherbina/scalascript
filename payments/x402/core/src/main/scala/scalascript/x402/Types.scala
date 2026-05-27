package scalascript.x402

import scala.concurrent.Future

// ── Primitives ────────────────────────────────────────────────────────────────

type Bytes32 = String   // hex-encoded 32-byte value

// ── Networks ──────────────────────────────────────────────────────────────────

enum Network:
  case BaseSepolia, Base, EthereumMainnet, Polygon, Arbitrum, Optimism
  case CardanoMainnet, CardanoPreprod, CardanoPreview

  /** EVM EIP-155 chain ID. Throws for non-EVM networks. */
  def chainId: Int = this match
    case BaseSepolia     => 84532
    case Base            => 8453
    case EthereumMainnet => 1
    case Polygon         => 137
    case Arbitrum        => 42161
    case Optimism        => 10
    case CardanoMainnet | CardanoPreprod | CardanoPreview =>
      throw UnsupportedOperationException(s"$this is not an EVM network and has no chainId")

  def isCardano: Boolean = this match
    case CardanoMainnet | CardanoPreprod | CardanoPreview => true
    case _                                                => false

// ── Assets ────────────────────────────────────────────────────────────────────

case class Asset(address: String, symbol: String, decimals: Int, network: Network)

object Assets:
  val USDC_BASE         = Asset("0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913", "USDC", 6, Network.Base)
  val USDC_BASE_SEPOLIA = Asset("0x036CbD53842c5426634e7929541eC2318f3dCF7e", "USDC", 6, Network.BaseSepolia)
  val USDC_ETHEREUM     = Asset("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", "USDC", 6, Network.EthereumMainnet)
  val USDC_POLYGON      = Asset("0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174", "USDC", 6, Network.Polygon)
  val USDC_ARBITRUM     = Asset("0xFF970A61A04b1cA14834A43f5dE4533eBDDB5CC8", "USDC", 6, Network.Arbitrum)
  val USDC_OPTIMISM     = Asset("0x7F5c764cBc14f9669B88837ca1490cCa17c31607", "USDC", 6, Network.Optimism)

  def usdc(network: Network): Asset = network match
    case Network.Base            => USDC_BASE
    case Network.BaseSepolia     => USDC_BASE_SEPOLIA
    case Network.EthereumMainnet => USDC_ETHEREUM
    case Network.Polygon         => USDC_POLYGON
    case Network.Arbitrum        => USDC_ARBITRUM
    case Network.Optimism        => USDC_OPTIMISM
    case Network.CardanoMainnet | Network.CardanoPreprod | Network.CardanoPreview =>
      throw UnsupportedOperationException(s"USDC ERC-20 not deployed on $network; use CardanoAssets.USDA for Cardano")

// ── Cardano native assets ─────────────────────────────────────────────────────

case class CardanoAsset(policyId: String, assetName: String, symbol: String)

object CardanoAssets:
  val ADA  = CardanoAsset("", "", "ADA")
  val DJED = CardanoAsset("8db269c3ec630e06ae29f74bc39edd1f87c819f1056206e879a1cd61", "446a6564", "DJED")
  val USDA = CardanoAsset("f66d78b4a3cb3d37afa0ec36461e51ecbbd428f681267fc5ded45adc", "55534441", "USDA")

// ── Cardano Scalus claim-message codec ───────────────────────────────────────

object ScalusClaimMessageCodec:
  val domain: String = "x402-scalus/v1"

  def encode(receiverBytes: Array[Byte], lovelace: BigInt, validBefore: BigInt): Array[Byte] =
    domain.getBytes("UTF-8") ++ receiverBytes ++ uint64(lovelace, "lovelace") ++
      uint64(validBefore, "validBefore")

  def uint64(value: BigInt, field: String): Array[Byte] =
    require(value >= 0, s"$field must be non-negative, got $value")
    require(value < (BigInt(1) << 64), s"$field is too large for uint64-compatible encoding: $value")
    val out = new Array[Byte](8)
    var v   = value
    var i   = 7
    while i >= 0 do
      out(i) = (v & 0xff).toByte
      v = v >> 8
      i -= 1
    out

case class ScalusEscrowRef(txHash: String, outputIndex: Int):
  override def toString: String = s"$txHash#$outputIndex"

object ScalusEscrowRef:
  private val TxHash = "(?i)[0-9a-f]{64}".r

  def parse(value: String): Either[String, ScalusEscrowRef] =
    value.split("#", -1).toList match
      case txHash :: index :: Nil =>
        if TxHash.pattern.matcher(txHash).matches then
          index.toIntOption match
            case Some(i) if i >= 0 => Right(ScalusEscrowRef(txHash.toLowerCase, i))
            case _                 => Left(s"Invalid Cardano escrow output index: '$index'")
        else Left(s"Invalid Cardano escrow tx hash: '$txHash'")
      case _ =>
        Left("Invalid Cardano escrowRef, expected '<64-hex-txhash>#<output-index>'")

  def require(value: String): ScalusEscrowRef =
    parse(value).fold(msg => throw IllegalArgumentException(msg), identity)

// ── Payment schemes ───────────────────────────────────────────────────────────

enum PaymentScheme:
  case Exact(amount: BigInt)
  case Stream(
    ratePerUnit: BigInt,
    unitName:    String,
    maxUnits:    Int,
    maxAmount:   BigInt,
  )
  case CardanoExact(
    lovelace: BigInt,
    asset:    Option[CardanoAsset],
  )

// ── Payment requirements ──────────────────────────────────────────────────────

case class PaymentRequirements(
  scheme:            PaymentScheme,
  network:           Network,
  asset:             Asset,
  payTo:             String,
  resource:          String,
  description:       String,
  maxTimeoutSeconds: Int = 300,
  scalusEscrowRef:   Option[String] = None,
)

// ── EVM authorization + payload ───────────────────────────────────────────────

case class TransferAuthorization(
  from:        String,
  to:          String,
  value:       BigInt,
  validAfter:  BigInt,
  validBefore: BigInt,
  nonce:       Bytes32,
)

// ── Cardano payment proof ─────────────────────────────────────────────────────

case class CardanoPaymentProof(
  address:   String,
  signature: String,   // CIP-8 COSE_Sign1 hex
  key:       String,   // CIP-8 COSE_Key hex
)

case class PaymentPayload(
  x402Version:   Int = 1,
  scheme:        PaymentScheme,
  network:       Network,
  authorization: TransferAuthorization,
  signature:     String,
  cardanoProof:  Option[CardanoPaymentProof] = None,
)

// ── Facilitator SPI ───────────────────────────────────────────────────────────

enum VerifyResult:
  case Ok
  case Fail(reason: String)

enum SettleResult:
  case Ok(txHash: String)
  case Fail(reason: String)

trait Facilitator:
  def verify(payload: PaymentPayload, req: PaymentRequirements): Future[VerifyResult]
  def settle(payload: PaymentPayload, req: PaymentRequirements): Future[SettleResult]

// ── Nonce store SPI ───────────────────────────────────────────────────────────

trait NonceStore:
  def claim(nonce: Bytes32, validBefore: BigInt): Future[Boolean]
  def cleanup(): Future[Unit]

object NonceStore:
  def inMemory(): NonceStore = new InMemoryNonceStore

// ── Settlement queue + mode ───────────────────────────────────────────────────

trait SettlementQueue:
  def enqueue(payload: PaymentPayload, req: PaymentRequirements): Future[Unit]
  def process(facilitator: Facilitator): Future[Unit]

object SettlementQueue:
  def inMemory()(using scala.concurrent.ExecutionContext): SettlementQueue = new InMemorySettlementQueue

enum SettlementMode:
  case Synchronous
  case Async(queue: SettlementQueue)
