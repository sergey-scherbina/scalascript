package scalascript.wallet.vault.mpc.coinbase

import java.net.URI
import java.net.http.HttpRequest
import java.security.{KeyFactory, Signature}
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scalascript.crypto.{Curve, HashAlgo}
import scalascript.wallet.vault.mpc.*

case class CoinbaseOptions(
  portfolioId:     String = "default",
  assetId:         String = "ETH",
  networkId:       String = "ethereum-mainnet",
  pollIntervalMs:  Long   = 500L,
  pollMaxAttempts: Int    = 60,
  timeoutMs:       Long   = 10_000L,
  userAgent:       String = "scalascript-wallet-vault-mpc-coinbase/0.1",
)

object CoinbaseOptions:
  val Default: CoinbaseOptions = CoinbaseOptions()

class CoinbaseRemoteSigningClient(
  baseUrl:       String,
  apiKey:        String,
  privateKeyPem: String,
  options:       CoinbaseOptions = CoinbaseOptions.Default,
)(using ec: ExecutionContext)
    extends HttpRemoteSigningClient(
      baseUrl,
      "",
      options.portfolioId,
      HttpRemoteSigningOptions(
        pollIntervalMs  = options.pollIntervalMs,
        pollMaxAttempts = options.pollMaxAttempts,
        timeoutMs       = options.timeoutMs,
        userAgent       = options.userAgent,
      ),
    ):

  private val privateKey = CoinbaseAuth.privateKeyFromPem(privateKeyPem)

  override def health(): Future[Boolean] =
    send(buildGet(s"/v1/portfolios/${options.portfolioId}"))
      .map(r => r.statusCode >= 200 && r.statusCode < 300)
      .recover { case _ => false }

  override def listAccounts(): Future[Seq[McpAccount]] =
    send(buildGet(s"/v1/portfolios/${options.portfolioId}/wallets")).flatMap { resp =>
      val sc = resp.statusCode
      if sc >= 200 && sc < 300 then
        Future.successful(CoinbaseWire.parseWallets(ujson.read(resp.body)))
      else
        Future.failed(RuntimeException(s"Coinbase listAccounts failed: HTTP $sc - ${truncated(resp.body)}"))
    }

  override def sign(
    accountId:      String,
    curve:          Curve,
    derivationPath: String,
    payload:        Array[Byte],
    hashAlgo:       HashAlgo,
  ): Future[Array[Byte]] =
    val body = CoinbaseWire.signingRequestBody(
      walletId       = accountId,
      assetId        = options.assetId,
      networkId      = options.networkId,
      curve          = curve,
      derivationPath = derivationPath,
      payload        = payload,
      hashAlgo       = hashAlgo,
    )
    val path = s"/v1/portfolios/${options.portfolioId}/signing_requests"
    send(buildPost(path, body)).flatMap { resp =>
      resp.statusCode match
        case sc if sc >= 200 && sc < 300 =>
          val id = CoinbaseWire.parseSigningRequestId(ujson.read(resp.body))
          pollSigningRequest(id)
        case sc =>
          Future.failed(RuntimeException(s"Coinbase sign failed: HTTP $sc - ${truncated(resp.body)}"))
    }

  private def pollSigningRequest(reqId: String): Future[Array[Byte]] =
    val path = s"/v1/portfolios/${options.portfolioId}/signing_requests/$reqId"
    def attempt(remaining: Int): Future[Array[Byte]] =
      if remaining <= 0 then
        Future.failed(RuntimeException(
          s"Coinbase signing request $reqId did not complete within ${options.pollMaxAttempts} polls"
        ))
      else
        send(buildGet(path)).flatMap { resp =>
          val sc = resp.statusCode
          if sc >= 200 && sc < 300 then
            CoinbaseWire.parseSigningRequestStatus(ujson.read(resp.body)) match
              case Right(Some(sig)) => Future.successful(sig)
              case Right(None)      => sleep(options.pollIntervalMs).flatMap(_ => attempt(remaining - 1))
              case Left(reason)     => Future.failed(RuntimeException(s"Coinbase signing request $reqId failed: $reason"))
          else
            Future.failed(RuntimeException(s"Coinbase poll failed: HTTP $sc - ${truncated(resp.body)}"))
        }
    attempt(options.pollMaxAttempts)

  private def buildGet(path: String): HttpRequest =
    build("GET", path, "")

  private def buildPost(path: String, body: ujson.Value): HttpRequest =
    build("POST", path, ujson.write(body))

  private def build(method: String, path: String, body: String): HttpRequest =
    val uri = URI.create(s"$trimmedBase$path")
    val ts  = System.currentTimeMillis() / 1000L
    val sig = CoinbaseAuth.sign(privateKey, ts, method, path, body)
    val b = HttpRequest.newBuilder(uri)
      .header("Accept",                "application/json")
      .header("User-Agent",            options.userAgent)
      .header("X-CB-ACCESS-KEY",       apiKey)
      .header("X-CB-ACCESS-TIMESTAMP", ts.toString)
      .header("X-CB-ACCESS-SIGNATURE", sig)
      .timeout(Duration.ofMillis(options.timeoutMs))
    if method == "POST" then
      b.header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    else b.GET().build()

object CoinbaseAuth:
  def sign(
    key:       java.security.PrivateKey,
    timestamp: Long,
    method:    String,
    path:      String,
    body:      String,
  ): String =
    val message = s"$timestamp${method.toUpperCase}$path$body"
    val sig = Signature.getInstance("SHA256withECDSA")
    sig.initSign(key)
    sig.update(message.getBytes("UTF-8"))
    Base64.getEncoder.encodeToString(sig.sign())

  def privateKeyFromPem(pem: String): java.security.PrivateKey =
    val clean = pem
      .replace("-----BEGIN EC PRIVATE KEY-----", "")
      .replace("-----END EC PRIVATE KEY-----", "")
      .replace("-----BEGIN PRIVATE KEY-----", "")
      .replace("-----END PRIVATE KEY-----", "")
      .replaceAll("\\s", "")
    val bytes = Base64.getDecoder.decode(clean)
    KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))

object CoinbaseWire:
  def signingRequestBody(
    walletId:       String,
    assetId:        String,
    networkId:      String,
    curve:          Curve,
    derivationPath: String,
    payload:        Array[Byte],
    hashAlgo:       HashAlgo,
  ): ujson.Obj =
    ujson.Obj(
      "wallet_id"      -> walletId,
      "asset_id"       -> assetId,
      "network_id"     -> networkId,
      "signing_target" -> ujson.Obj(
        "payload"         -> hex(payload),
        "derivation_path" -> derivationPath,
        "algorithm"       -> coinbaseAlgorithm(curve),
        "hash_algorithm"  -> MpcSerialization.hashName(hashAlgo),
      ),
    )

  def parseSigningRequestId(value: ujson.Value): String =
    val obj = value.obj
    obj.get("signing_request_id").orElse(obj.get("id")).map(_.str).getOrElse {
      throw IllegalStateException("Coinbase create signing request response missing id")
    }

  def parseSigningRequestStatus(value: ujson.Value): Either[String, Option[Array[Byte]]] =
    val obj    = value.obj
    val status = obj.get("status").map(_.str).getOrElse("PENDING").toUpperCase
    status match
      case "SIGNED" | "COMPLETED" =>
        signatureFrom(obj).map(sig => Right(Some(sig))).getOrElse(Left("completed signing request missing signature"))
      case "FAILED" | "REJECTED" | "CANCELLED" =>
        Left(obj.get("reason").orElse(obj.get("error")).map(_.str).getOrElse(status))
      case _ =>
        Right(None)

  def parseWallets(value: ujson.Value): Seq[McpAccount] =
    val arr = value.obj.get("wallets").map(_.arr).getOrElse(ujson.Arr(value).arr)
    arr.toSeq.map { w =>
      val obj = w.obj
      McpAccount(
        id         = obj.get("id").map(_.str).getOrElse("unknown"),
        label      = obj.get("name").map(_.str).getOrElse(obj.get("id").map(_.str).getOrElse("unknown")),
        publicKeys = Map.empty,
      )
    }

  private def signatureFrom(obj: upickle.core.LinkedHashMap[String, ujson.Value]): Option[Array[Byte]] =
    for
      sigObj <- obj.get("signature")
      hex    <- sigObj.obj.get("value")
                  .orElse(sigObj.obj.get("hex"))
                  .orElse(sigObj.obj.get("r_s_signature"))
    yield unhex(hex.str)

  def coinbaseAlgorithm(curve: Curve): String = curve match
    case Curve.Secp256k1 => "SECP256K1"
    case Curve.Ed25519   => "ED25519"
    case Curve.P256      => "P256"
    case other           => throw UnsupportedOperationException(s"Coinbase MPC does not support $other")

  export MpcSerialization.{hex, unhex}
