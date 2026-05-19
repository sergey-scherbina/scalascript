package scalascript.x402.client

import scalascript.x402.*
import scala.concurrent.{ExecutionContext, Future}
import java.util.Base64
import java.security.{MessageDigest, SecureRandom}
import ujson.*

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
  def signEip712(domain: Eip712Domain, types: Map[String, Seq[(String, String)]], value: Map[String, Any]): Future[String]

// ── Private-key wallet (JVM / Node.js automation) ─────────────────────────────

private class PrivateKeyWallet(privateKeyHex: String, val network: Network)
    extends Wallet:

  private val keyBytes: Array[Byte] =
    privateKeyHex.stripPrefix("0x").grouped(2).map(h => Integer.parseInt(h, 16).toByte).toArray

  val address: String =
    val md   = MessageDigest.getInstance("SHA-256")
    val hash = md.digest(keyBytes)
    "0x" + hash.take(20).map(b => f"${b & 0xff}%02x").mkString

  def signEip712(domain: Eip712Domain, types: Map[String, Seq[(String, String)]], value: Map[String, Any]): Future[String] =
    Future.successful {
      // Deterministic stub signature: HMAC-like over domain + value for testability
      val input = s"${domain.chainId}:${domain.verifyingContract}:${value.toSeq.sortBy(_._1).mkString}"
      val md    = MessageDigest.getInstance("SHA-256")
      val hash  = md.digest(input.getBytes("UTF-8"))
      "0x" + hash.map(b => f"${b & 0xff}%02x").mkString + "00"
    }

object Wallets:
  def privateKey(hex: String, network: Network): Wallet =
    new PrivateKeyWallet(hex, network)

  def envKey(envVar: String, network: Network): Wallet =
    val hex = sys.env.getOrElse(envVar, throw RuntimeException(s"Env var $envVar not set"))
    new PrivateKeyWallet(hex, network)

// ── Payment payload builder ───────────────────────────────────────────────────

private object PayloadBuilder:
  private val rng = SecureRandom()

  def build(wallet: Wallet, req: PaymentRequirements)(using ec: ExecutionContext): Future[PaymentPayload] =
    val amount      = req.scheme match
      case PaymentScheme.Exact(amt)              => amt
      case PaymentScheme.Stream(_, _, _, maxAmt) => maxAmt
      case PaymentScheme.CardanoExact(love, _)   => love
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
    val amount = req.scheme match
      case PaymentScheme.Exact(amt)              => amt
      case PaymentScheme.Stream(_, _, _, maxAmt) => maxAmt
      case PaymentScheme.CardanoExact(love, _)   => love
    if amount > maxAmount then
      Future.successful(resp402)   // refuse to pay more than configured max
    else
      PayloadBuilder.build(wallet, req).flatMap { payload =>
        val encoded     = PayloadBuilder.encode(payload)
        val newHeaders  = headers + ("X-Payment" -> encoded)
        backend(method, url, newHeaders, body)
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
