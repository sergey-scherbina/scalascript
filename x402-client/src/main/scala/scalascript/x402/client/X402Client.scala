package scalascript.x402.client

import scalascript.x402.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import java.util.Base64
import java.security.SecureRandom
import ujson.*

import scalascript.blockchain.spi.{ChainId, TypedData}
import scalascript.blockchain.evm.EvmChainAdapter
import scalascript.crypto.Curve
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
  def signEip712(
    domain: Eip712Domain,
    types:  Map[String, Seq[(String, String)]],
    value:  Map[String, Any],
  ): Future[String]

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

object Wallets:
  def privateKey(hex: String, network: Network)(using ExecutionContext): Wallet =
    new PrivateKeyWallet(hex, network)

  def envKey(envVar: String, network: Network)(using ExecutionContext): Wallet =
    val hex = sys.env.getOrElse(envVar, throw RuntimeException(s"Env var $envVar not set"))
    new PrivateKeyWallet(hex, network)

// ── Payment payload builder ───────────────────────────────────────────────────

private object PayloadBuilder:
  private val rng = SecureRandom()

  def build(wallet: Wallet, req: PaymentRequirements)(using ec: ExecutionContext): Future[PaymentPayload] =
    // For Stream: authorize ratePerUnit (one unit per request)
    val amount      = req.scheme match
      case PaymentScheme.Exact(amt)                    => amt
      case PaymentScheme.Stream(ratePerUnit, _, _, _)  => ratePerUnit
      case PaymentScheme.CardanoExact(love, _)         => love
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
    ).toString
    Base64.getEncoder.encodeToString(json.getBytes("UTF-8"))

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
