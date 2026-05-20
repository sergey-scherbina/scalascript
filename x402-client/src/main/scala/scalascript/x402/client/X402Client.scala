package scalascript.x402.client

import scalascript.x402.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import java.util.Base64
import java.security.SecureRandom
import ujson.*

import scalascript.blockchain.spi.{ChainId, TypedData}
import scalascript.blockchain.evm.EvmChainAdapter
import scalascript.blockchain.cardano.CardanoAddress
import scalascript.crypto.{Curve, HashAlgo}
import scalascript.wallet.spi.RawSigner
import scalascript.wallet.strategy.eoa.{EoaStrategy, RawPrivateKeyVault}

// ── EIP-712 domain (used by EVM wallets for typed signing) ────────────────────

case class Eip712Domain(
  name:              String,
  version:           String,
  chainId:           Int,
  verifyingContract: String,
)

// ── Wallet SPI ────────────────────────────────────────────────────────────────

trait Wallet:
  def address: String
  def network: Network

  /** EIP-712 typed-data signing. EVM wallets implement; Cardano wallets fail. */
  def signEip712(
    domain: Eip712Domain,
    types:  Map[String, Seq[(String, String)]],
    value:  Map[String, Any],
  ): Future[String]

  /** CIP-8 (COSE_Sign1) signing for Cardano. Cardano wallets implement; EVM wallets fail. */
  def signCip8(message: Array[Byte]): Future[CardanoPaymentProof]

// ── Private-key wallet (JVM / Node.js automation) ─────────────────────────────
//
// Thin adapter over wallet-spi + blockchain-evm. The public API
// (`Wallet`, `Wallets.privateKey`, `Wallets.envKey`) stays stable; the
// implementation produces real secp256k1 ECDSA signatures over real
// EIP-712 digests instead of the SHA-256 stub that lived here pre-
// Phase-1 of the wallet-spi milestone (see docs/wallet-spi.md §9).

private class PrivateKeyWallet(privateKeyHex: String, val network: Network)
    (using ec: ExecutionContext) extends Wallet:

  private val vault    = RawPrivateKeyVault.fromHex("x402-pkwallet", privateKeyHex, Curve.Secp256k1)
  private val signer: RawSigner = Await.result(
    vault.getSigner(Curve.Secp256k1, "raw"),
    Duration.Inf,
  )
  private val strategy = new EoaStrategy(signer)
  private val adapter  = new EvmChainAdapter(ChainId(s"eip155:${network.chainId}"))

  val address: String = adapter.addressFromPublicKey(signer.publicKey)

  def signEip712(
    domain: Eip712Domain,
    types:  Map[String, Seq[(String, String)]],
    value:  Map[String, Any],
  ): Future[String] =
    val typedData = PrivateKeyWallet.buildTypedData(domain, types, value)
    strategy.signTypedData(adapter, typedData).map(PrivateKeyWallet.encodeEthereumSignature)

  def signCip8(message: Array[Byte]): Future[CardanoPaymentProof] =
    Future.failed(UnsupportedOperationException(
      s"EVM wallet ($network) does not support CIP-8 signing (message length=${message.length})"
    ))

private object PrivateKeyWallet:

  private val EthDomainFields: Seq[(String, String)] = Seq(
    "string"  -> "name",
    "string"  -> "version",
    "uint256" -> "chainId",
    "address" -> "verifyingContract",
  )

  def buildTypedData(
    domain:    Eip712Domain,
    userTypes: Map[String, Seq[(String, String)]],
    userValue: Map[String, Any],
  ): TypedData.Eip712 =
    val primaryType = (userTypes.keys.toSet - "EIP712Domain").headOption.getOrElse(
      throw new IllegalArgumentException("signEip712: types map must include at least one non-EIP712Domain entry"),
    )
    val allTypes  = userTypes + ("EIP712Domain" -> EthDomainFields)
    val domainMap = Map[String, ujson.Value](
      "name"              -> ujson.Str(domain.name),
      "version"           -> ujson.Str(domain.version),
      "chainId"           -> ujson.Str(domain.chainId.toString),
      "verifyingContract" -> ujson.Str(domain.verifyingContract),
    )
    val valueMap = userValue.map { case (k, v) =>
      k -> (v match
        case s: String  => ujson.Str(s)
        case bi: BigInt => ujson.Str(bi.toString)
        case i: Int     => ujson.Str(i.toString)
        case l: Long    => ujson.Str(l.toString)
        case other      => ujson.Str(other.toString))
    }
    TypedData.Eip712(
      domain      = domainMap,
      types       = allTypes,
      value       = valueMap,
      primaryType = primaryType,
    )

  /** Wallet-spi `EoaStrategy.signTypedData` returns the 65-byte raw
   *  `r||s||recId` produced by `crypto-bouncycastle`. Ethereum / x402
   *  callers want `r||s||v` with `v = recId + 27`. */
  def encodeEthereumSignature(raw: Array[Byte]): String =
    require(raw.length == 65, s"Expected 65-byte signature, got ${raw.length}")
    val out = raw.clone()
    out(64) = (raw(64) + 27).toByte
    "0x" + out.map(b => f"${b & 0xff}%02x").mkString

// ── Cardano private-key wallet (CIP-8 / Ed25519) ──────────────────────────────
//
// Signs CIP-8 (COSE_Sign1) authorizations for x402 Cardano payments. The
// payer address is either derived from the key via CIP-19 (enterprise
// address) by `Wallets.cardano(hex, network)`, or supplied explicitly
// via `Wallets.cardano(hex, address, network)` when callers need a
// base/stake-augmented address instead of the bare enterprise form.

private class CardanoPrivateKeyWallet(
  privateKeyHex: String,
  val address:   String,
  val network:   Network,
)(using ec: ExecutionContext) extends Wallet:

  require(network.isCardano, s"CardanoPrivateKeyWallet requires a Cardano network, got $network")

  private val vault  = RawPrivateKeyVault.fromHex("x402-cardano-pkwallet", privateKeyHex, Curve.Ed25519)
  private val signer: RawSigner = Await.result(
    vault.getSigner(Curve.Ed25519, "raw"),
    Duration.Inf,
  )

  def signEip712(
    domain: Eip712Domain,
    types:  Map[String, Seq[(String, String)]],
    value:  Map[String, Any],
  ): Future[String] =
    Future.failed(UnsupportedOperationException(
      s"Cardano wallet ($network) does not support EIP-712 signing " +
        s"(${types.size} types, ${value.size} value fields, domain=${domain.name})"
    ))

  def signCip8(message: Array[Byte]): Future[CardanoPaymentProof] =
    signer.sign(Cip8Signer.sigStructure(message), HashAlgo.None).map { sig =>
      Cip8Signer.buildProof(
        message   = message,
        signature = sig,
        publicKey = signer.publicKey.bytes,
        address   = address,
      )
    }

object Wallets:
  def privateKey(hex: String, network: Network)(using ExecutionContext): Wallet =
    new PrivateKeyWallet(hex, network)

  def envKey(envVar: String, network: Network)(using ExecutionContext): Wallet =
    val hex = sys.env.getOrElse(envVar, throw RuntimeException(s"Env var $envVar not set"))
    new PrivateKeyWallet(hex, network)

  /** Cardano CIP-8 wallet backed by a raw Ed25519 private key.
   *  The bech32 payment address is derived from the key via CIP-19
   *  (enterprise address: `header || Blake2b-224(pubkey)`). */
  def cardano(hex: String, network: Network)(using ExecutionContext): Wallet =
    new CardanoPrivateKeyWallet(hex, deriveCardanoEnterpriseAddress(hex, network), network)

  /** Cardano CIP-8 wallet with an explicit address (use when supplying a
   *  pointer-, script-, or otherwise hand-crafted bech32 address). */
  def cardano(hex: String, address: String, network: Network)(using ExecutionContext): Wallet =
    new CardanoPrivateKeyWallet(hex, address, network)

  /** Cardano CIP-8 wallet with a stake-aware **base** address derived
   *  from both keys (CIP-19 type 0/1): `header || Blake2b-224(payment) ||
   *  Blake2b-224(stake)`. Signing uses only the payment key — the stake
   *  key participates in the address but never signs payments. */
  def cardanoBase(paymentHex: String, stakeHex: String, network: Network)(using ExecutionContext): Wallet =
    new CardanoPrivateKeyWallet(
      paymentHex,
      deriveCardanoBaseAddress(paymentHex, stakeHex, network),
      network,
    )

  /** Env-var convenience: reads the hex private key from `envVar` and
   *  derives the enterprise bech32 address from it. */
  def cardanoEnvKey(envVar: String, network: Network)(using ExecutionContext): Wallet =
    val hex = sys.env.getOrElse(envVar, throw RuntimeException(s"Env var $envVar not set"))
    new CardanoPrivateKeyWallet(hex, deriveCardanoEnterpriseAddress(hex, network), network)

  /** Env-var convenience with an explicit address override. */
  def cardanoEnvKey(envVar: String, address: String, network: Network)(using ExecutionContext): Wallet =
    val hex = sys.env.getOrElse(envVar, throw RuntimeException(s"Env var $envVar not set"))
    new CardanoPrivateKeyWallet(hex, address, network)

  /** Env-var convenience for stake-aware base addresses. Reads both keys
   *  from env vars and derives the CIP-19 type-0 base bech32 address. */
  def cardanoBaseEnvKey(paymentEnvVar: String, stakeEnvVar: String, network: Network)(using ExecutionContext): Wallet =
    val payment = sys.env.getOrElse(paymentEnvVar, throw RuntimeException(s"Env var $paymentEnvVar not set"))
    val stake   = sys.env.getOrElse(stakeEnvVar,   throw RuntimeException(s"Env var $stakeEnvVar not set"))
    new CardanoPrivateKeyWallet(
      payment,
      deriveCardanoBaseAddress(payment, stake, network),
      network,
    )

  /** Derive a CIP-19 enterprise bech32 address from a raw Ed25519 private
   *  key. Network selects mainnet (`addr1...`) vs testnet (`addr_test1...`). */
  private def deriveCardanoEnterpriseAddress(hex: String, network: Network)(using ExecutionContext): String =
    require(network.isCardano, s"deriveCardanoEnterpriseAddress requires a Cardano network, got $network")
    val signer  = cardanoSigner(hex, label = "x402-cardano-addr-derive")
    val testnet = network != Network.CardanoMainnet
    CardanoAddress.fromPublicKey(signer.publicKey, testnet = testnet)

  /** Derive a CIP-19 type-0 base bech32 address from raw Ed25519
   *  payment + stake private keys. */
  private def deriveCardanoBaseAddress(paymentHex: String, stakeHex: String, network: Network)(using ExecutionContext): String =
    require(network.isCardano, s"deriveCardanoBaseAddress requires a Cardano network, got $network")
    val payment = cardanoSigner(paymentHex, label = "x402-cardano-base-payment")
    val stake   = cardanoSigner(stakeHex,   label = "x402-cardano-base-stake")
    val testnet = network != Network.CardanoMainnet
    CardanoAddress.fromPublicKeys(payment.publicKey, stake.publicKey, testnet = testnet)

  private def cardanoSigner(hex: String, label: String)(using ExecutionContext): RawSigner =
    val vault = RawPrivateKeyVault.fromHex(label, hex, Curve.Ed25519)
    Await.result(vault.getSigner(Curve.Ed25519, "raw"), Duration.Inf)

// ── Payment payload builder ───────────────────────────────────────────────────

private object PayloadBuilder:
  private val rng = SecureRandom()

  def build(wallet: Wallet, req: PaymentRequirements)(using ec: ExecutionContext): Future[PaymentPayload] =
    req.scheme match
      case _: PaymentScheme.CardanoExact => buildCardano(wallet, req)
      case _                             => buildEvm(wallet, req)

  private def buildEvm(wallet: Wallet, req: PaymentRequirements)(using ec: ExecutionContext): Future[PaymentPayload] =
    // For Stream: authorize ratePerUnit (one unit per request)
    val amount      = req.scheme match
      case PaymentScheme.Exact(amt)                    => amt
      case PaymentScheme.Stream(ratePerUnit, _, _, _)  => ratePerUnit
      case _: PaymentScheme.CardanoExact               =>
        throw IllegalStateException("buildEvm called with CardanoExact scheme")
    val nonce       = new Array[Byte](32)
    rng.nextBytes(nonce)
    val nonceHex    = "0x" + nonce.map(b => f"${b & 0xff}%02x").mkString
    val now         = System.currentTimeMillis() / 1000
    val validBefore = BigInt(now + req.maxTimeoutSeconds)
    val domain = Eip712Domain(
      name              = "USD Coin",
      version           = "2",
      chainId           = req.network.chainId,
      verifyingContract = req.asset.address,
    )
    val types = Map(
      "TransferWithAuthorization" -> Seq(
        "address" -> "from",
        "address" -> "to",
        "uint256" -> "value",
        "uint256" -> "validAfter",
        "uint256" -> "validBefore",
        "bytes32" -> "nonce",
      )
    )
    val auth  = TransferAuthorization(
      from        = wallet.address,
      to          = req.payTo,
      value       = amount,
      validAfter  = BigInt(0),
      validBefore = validBefore,
      nonce       = nonceHex,
    )
    val value = Map[String, Any](
      "from"        -> auth.from,
      "to"          -> auth.to,
      "value"       -> auth.value.toString,
      "validAfter"  -> auth.validAfter.toString,
      "validBefore" -> auth.validBefore.toString,
      "nonce"       -> auth.nonce,
    )
    wallet.signEip712(domain, types, value).map { sig =>
      PaymentPayload(
        scheme        = req.scheme,
        network       = req.network,
        authorization = auth,
        signature     = sig,
      )
    }

  /** Cardano CIP-8 payload. The message signed is `req.description` (UTF-8),
   *  matching `CardanoFacilitator.Cip8Verifier.requirementsMessage`. The
   *  `authorization` field is filled with payer/payee/amount metadata for
   *  observability — the Cardano facilitator only reads `cardanoProof`. */
  private def buildCardano(wallet: Wallet, req: PaymentRequirements)(using ec: ExecutionContext): Future[PaymentPayload] =
    val lovelace = req.scheme match
      case PaymentScheme.CardanoExact(l, _) => l
      case other =>
        throw IllegalStateException(s"buildCardano called with non-Cardano scheme: $other")
    val now         = System.currentTimeMillis() / 1000
    val validBefore = BigInt(now + req.maxTimeoutSeconds)
    val auth = TransferAuthorization(
      from        = wallet.address,
      to          = req.payTo,
      value       = lovelace,
      validAfter  = BigInt(0),
      validBefore = validBefore,
      nonce       = "",   // Cardano anti-replay rides on the signed description bytes
    )
    val message = req.description.getBytes("UTF-8")
    wallet.signCip8(message).map { proof =>
      PaymentPayload(
        scheme        = req.scheme,
        network       = req.network,
        authorization = auth,
        signature     = "",
        cardanoProof  = Some(proof),
      )
    }

  def encode(payload: PaymentPayload): String =
    val auth = payload.authorization
    val json = ujson.Obj(
      "x402Version"   -> payload.x402Version,
      "scheme"        -> schemeJson(payload.scheme),
      "network"       -> payload.network.toString,
      "authorization" -> ujson.Obj(
        "from"        -> auth.from,
        "to"          -> auth.to,
        "value"       -> auth.value.toString,
        "validAfter"  -> auth.validAfter.toString,
        "validBefore" -> auth.validBefore.toString,
        "nonce"       -> auth.nonce,
      ),
      "signature"     -> payload.signature,
    )
    payload.cardanoProof.foreach { p =>
      json("cardanoProof") = ujson.Obj(
        "address"   -> p.address,
        "signature" -> p.signature,
        "key"       -> p.key,
      )
    }
    Base64.getEncoder.encodeToString(json.toString.getBytes("UTF-8"))

  private def schemeJson(scheme: PaymentScheme): ujson.Value = scheme match
    case PaymentScheme.Exact(amount) =>
      ujson.Obj("type" -> "exact", "amount" -> amount.toString)
    case PaymentScheme.Stream(rate, unit, maxU, maxA) =>
      ujson.Obj("type" -> "stream", "ratePerUnit" -> rate.toString,
        "unitName" -> unit, "maxUnits" -> maxU, "maxAmount" -> maxA.toString)
    case PaymentScheme.CardanoExact(lovelace, asset) =>
      val o = ujson.Obj("type" -> "cardanoExact", "lovelace" -> lovelace.toString)
      asset.foreach(a => o("asset") = ujson.Obj(
        "policyId" -> a.policyId, "assetName" -> a.assetName, "symbol" -> a.symbol))
      o

// ── Simple HTTP response type ─────────────────────────────────────────────────

case class HttpResponse(status: Int, headers: Map[String, String], body: String)

// ── X402-aware HTTP client ────────────────────────────────────────────────────

class X402HttpClient(
  wallet:    Wallet,
  maxAmount: BigInt,
  backend:   (String, String, Map[String, String], String) => Future[HttpResponse],
)(using ec: ExecutionContext):

  private var sessionSpent: BigInt = BigInt(0)

  def get(url: String, headers: Map[String, String] = Map.empty): Future[HttpResponse] =
    doRequest("GET", url, headers, "")

  def post(url: String, body: String, headers: Map[String, String] = Map.empty): Future[HttpResponse] =
    doRequest("POST", url, headers, body)

  private def doRequest(
    method:  String,
    url:     String,
    headers: Map[String, String],
    body:    String,
  ): Future[HttpResponse] =
    backend(method, url, headers, body).flatMap { resp =>
      if resp.status != 402 then
        Future.successful(resp)
      else
        handle402(resp, method, url, headers, body)
    }

  private def handle402(
    resp402: HttpResponse,
    method:  String,
    url:     String,
    headers: Map[String, String],
    body:    String,
  ): Future[HttpResponse] =
    val j = try ujson.read(resp402.body) catch case _ => return Future.successful(resp402)
    val req = parseRequirements(j("requirements"))
    // For Stream: charge ratePerUnit per request; for others: the scheme amount
    val chargePerRequest = req.scheme match
      case PaymentScheme.Exact(amt)                   => amt
      case PaymentScheme.Stream(ratePerUnit, _, _, _) => ratePerUnit
      case PaymentScheme.CardanoExact(love, _)        => love
    if chargePerRequest > maxAmount then
      Future.successful(resp402)   // single charge exceeds per-request cap
    else if sessionSpent + chargePerRequest > maxAmount then
      Future.successful(resp402)   // session budget exhausted
    else
      PayloadBuilder.build(wallet, req).flatMap { payload =>
        val encoded    = PayloadBuilder.encode(payload)
        val newHeaders = headers + ("X-Payment" -> encoded)
        backend(method, url, newHeaders, body).map { resp =>
          if resp.status != 402 then sessionSpent += chargePerRequest
          resp
        }
      }

  private def parseRequirements(j: ujson.Value): PaymentRequirements =
    val network = j("network").str match
      case "BaseSepolia"     => Network.BaseSepolia
      case "Base"            => Network.Base
      case "EthereumMainnet" => Network.EthereumMainnet
      case "Polygon"         => Network.Polygon
      case "Arbitrum"        => Network.Arbitrum
      case "Optimism"        => Network.Optimism
      case "CardanoMainnet"  => Network.CardanoMainnet
      case "CardanoPreprod"  => Network.CardanoPreprod
      case "CardanoPreview"  => Network.CardanoPreview
      case other             => throw RuntimeException(s"Unknown network: $other")
    val asset = Asset(
      address  = j("asset")("address").str,
      symbol   = j("asset")("symbol").str,
      decimals = j("asset")("decimals").num.toInt,
      network  = network,
    )
    val scheme = j("scheme")("type").str match
      case "exact" => PaymentScheme.Exact(BigInt(j("scheme")("amount").str))
      case "stream" => PaymentScheme.Stream(
        BigInt(j("scheme")("ratePerUnit").str),
        j("scheme")("unitName").str,
        j("scheme")("maxUnits").num.toInt,
        BigInt(j("scheme")("maxAmount").str),
      )
      case "cardanoExact" => PaymentScheme.CardanoExact(
        BigInt(j("scheme")("lovelace").str),
        None,
      )
      case other => throw RuntimeException(s"Unknown scheme: $other")
    PaymentRequirements(
      scheme      = scheme,
      network     = network,
      asset       = asset,
      payTo       = j("payTo").str,
      resource    = j.obj.get("resource").map(_.str).getOrElse(""),
      description = j.obj.get("description").map(_.str).getOrElse(""),
      maxTimeoutSeconds = j.obj.get("maxTimeoutSeconds").map(_.num.toInt).getOrElse(300),
    )

object X402Client:
  def apply(
    wallet:    Wallet,
    maxAmount: BigInt,
    backend:   (String, String, Map[String, String], String) => Future[HttpResponse],
  )(using ExecutionContext): X402HttpClient =
    new X402HttpClient(wallet, maxAmount, backend)
