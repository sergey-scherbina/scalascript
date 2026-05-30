package scalascript.wallet.vault.mpc.zengo

import java.net.URI
import java.net.http.HttpRequest
import java.security.MessageDigest
import javax.crypto.Mac
import java.time.Duration
import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scalascript.crypto.{Curve, HashAlgo}
import scalascript.wallet.vault.mpc.*

/** ZenGo X Enterprise MPC adapter options. */
case class ZenGoOptions(
  pollIntervalMs:  Long   = 500L,
  pollMaxAttempts: Int    = 60,
  timeoutMs:       Long   = 15_000L,
  userAgent:       String = "scalascript-wallet-vault-mpc-zengo/0.1",
)

object ZenGoOptions:
  val Default: ZenGoOptions = ZenGoOptions()

/** Signing client for ZenGo X Enterprise MPC service.
 *
 *  Auth: HMAC-SHA256 per-request signature.
 *  Each outgoing request carries three headers:
 *    `X-ZENGO-KEY`       — the opaque API key ID
 *    `X-ZENGO-TIMESTAMP` — Unix timestamp in seconds
 *    `X-ZENGO-SIGNATURE` — `base64(HMAC-SHA256(timestamp|METHOD|path|sha256hex(body), secretKey))`
 *
 *  Endpoints:
 *  - `GET  /v1/health`              → health check
 *  - `GET  /v1/accounts`            → list MPC accounts (wallets)
 *  - `POST /v1/signing/requests`    → create signing request (async)
 *  - `GET  /v1/signing/requests/{id}` → poll for completion
 */
class ZenGoRemoteSigningClient(
  baseUrl:   String,
  apiKey:    String,
  secretKey: String,
  options:   ZenGoOptions = ZenGoOptions.Default,
)(using ec: ExecutionContext)
    extends HttpRemoteSigningClient(
      baseUrl,
      initialToken = "",
      accountId    = "",
      HttpRemoteSigningOptions(
        pollIntervalMs  = options.pollIntervalMs,
        pollMaxAttempts = options.pollMaxAttempts,
        timeoutMs       = options.timeoutMs,
        userAgent       = options.userAgent,
      ),
    ):

  override def health(): Future[Boolean] =
    send(buildGet("/v1/health"))
      .map(r => r.statusCode >= 200 && r.statusCode < 300)
      .recover { case _ => false }

  override def listAccounts(): Future[Seq[McpAccount]] =
    send(buildGet("/v1/accounts")).flatMap { resp =>
      val sc = resp.statusCode
      if sc >= 200 && sc < 300 then
        Future.successful(ZenGoWire.parseAccounts(ujson.read(resp.body)))
      else
        Future.failed(RuntimeException(s"ZenGo listAccounts failed: HTTP $sc - ${truncated(resp.body)}"))
    }

  override def sign(
    accountId:      String,
    curve:          Curve,
    derivationPath: String,
    payload:        Array[Byte],
    hashAlgo:       HashAlgo,
  ): Future[Array[Byte]] =
    val body = ZenGoWire.signingRequestBody(accountId, curve, derivationPath, payload, hashAlgo)
    send(buildPost("/v1/signing/requests", body)).flatMap { resp =>
      val sc = resp.statusCode
      if sc >= 200 && sc < 300 then
        val id = ZenGoWire.parseSigningRequestId(ujson.read(resp.body))
        pollSigningRequest(id)
      else
        Future.failed(RuntimeException(s"ZenGo sign failed: HTTP $sc - ${truncated(resp.body)}"))
    }

  private def pollSigningRequest(reqId: String): Future[Array[Byte]] =
    val path = s"/v1/signing/requests/$reqId"
    def attempt(remaining: Int): Future[Array[Byte]] =
      if remaining <= 0 then
        Future.failed(RuntimeException(
          s"ZenGo signing request $reqId did not complete within ${options.pollMaxAttempts} polls"
        ))
      else
        send(buildGet(path)).flatMap { resp =>
          val sc = resp.statusCode
          if sc >= 200 && sc < 300 then
            ZenGoWire.parseSigningStatus(ujson.read(resp.body)) match
              case Right(Some(sig)) => Future.successful(sig)
              case Right(None)      => sleep(options.pollIntervalMs).flatMap(_ => attempt(remaining - 1))
              case Left(reason)     => Future.failed(RuntimeException(s"ZenGo signing request $reqId failed: $reason"))
          else
            Future.failed(RuntimeException(s"ZenGo poll failed: HTTP $sc - ${truncated(resp.body)}"))
        }
    attempt(options.pollMaxAttempts)

  private def buildGet(path: String): HttpRequest =
    build("GET", path, "")

  private def buildPost(path: String, body: ujson.Value): HttpRequest =
    build("POST", path, ujson.write(body))

  private def build(method: String, path: String, body: String): HttpRequest =
    val ts  = System.currentTimeMillis() / 1000L
    val sig = ZenGoAuth.sign(secretKey, ts, method, path, body)
    val uri = URI.create(s"$trimmedBase$path")
    val b   = HttpRequest.newBuilder(uri)
      .header("Accept",            "application/json")
      .header("User-Agent",        options.userAgent)
      .header("X-ZENGO-KEY",       apiKey)
      .header("X-ZENGO-TIMESTAMP", ts.toString)
      .header("X-ZENGO-SIGNATURE", sig)
      .timeout(Duration.ofMillis(options.timeoutMs))
    if method == "POST" then
      b.header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    else b.GET().build()

object ZenGoAuth:
  /** HMAC-SHA256 message: `timestamp|METHOD|path|sha256hex(body)` */
  def sign(secretKey: String, timestamp: Long, method: String, path: String, body: String): String =
    val bodyHash = sha256hex(body.getBytes("UTF-8"))
    val message  = s"$timestamp|${method.toUpperCase}|$path|$bodyHash"
    val mac      = Mac.getInstance("HmacSHA256")
    mac.init(javax.crypto.spec.SecretKeySpec(secretKey.getBytes("UTF-8"), "HmacSHA256"))
    Base64.getEncoder.encodeToString(mac.doFinal(message.getBytes("UTF-8")))

  private def sha256hex(data: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(data).map(b => f"${b & 0xff}%02x").mkString

object ZenGoWire:
  def signingRequestBody(
    accountId:      String,
    curve:          Curve,
    derivationPath: String,
    payload:        Array[Byte],
    hashAlgo:       HashAlgo,
  ): ujson.Obj =
    ujson.Obj(
      "account_id"      -> accountId,
      "algorithm"       -> zenGoAlgorithm(curve),
      "derivation_path" -> derivationPath,
      "payload"         -> hex(payload),
      "hash_algorithm"  -> MpcSerialization.hashName(hashAlgo),
    )

  def parseSigningRequestId(value: ujson.Value): String =
    val obj = value.obj
    obj.get("request_id").orElse(obj.get("id")).map(_.str).getOrElse {
      throw IllegalStateException("ZenGo create signing request response missing id")
    }

  def parseSigningStatus(value: ujson.Value): Either[String, Option[Array[Byte]]] =
    val obj    = value.obj
    val status = obj.get("status").map(_.str).getOrElse("PENDING").toUpperCase
    status match
      case "SIGNED" | "COMPLETED" | "SUCCESS" =>
        obj.get("signature").map(v => Right(Some(unhex(v.str))))
          .getOrElse(Left("completed signing request missing signature"))
      case "FAILED" | "REJECTED" | "CANCELLED" | "ERROR" =>
        Left(obj.get("reason").orElse(obj.get("error")).map(_.str).getOrElse(status))
      case _ =>
        Right(None)

  def parseAccounts(value: ujson.Value): Seq[McpAccount] =
    val arr = value.obj.get("accounts").map(_.arr).getOrElse(ujson.Arr(value).arr)
    arr.toSeq.map { a =>
      val obj = a.obj
      McpAccount(
        id         = obj.get("id").map(_.str).getOrElse("unknown"),
        label      = obj.get("name").orElse(obj.get("label")).map(_.str)
                       .getOrElse(obj.get("id").map(_.str).getOrElse("unknown")),
        publicKeys = Map.empty,
      )
    }

  def zenGoAlgorithm(curve: Curve): String = curve match
    case Curve.Secp256k1 => "ECDSA_SECP256K1"
    case Curve.Ed25519   => "EDDSA_ED25519"
    case Curve.P256      => "ECDSA_P256"
    case other           => throw UnsupportedOperationException(s"ZenGo X does not support $other")

  export MpcSerialization.{hex, unhex}
