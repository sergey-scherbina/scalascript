package scalascript.wallet.vault.mpc.fireblocks

import java.net.URI
import java.net.http.HttpRequest
import java.security.{KeyFactory, MessageDigest, PrivateKey, SecureRandom, Signature}
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scalascript.crypto.{Curve, HashAlgo}
import scalascript.wallet.vault.mpc.*

case class FireblocksOptions(
  vaultAccountId:  String = "default",
  assetId:         String = "ETH",
  pollIntervalMs:  Long   = 500L,
  pollMaxAttempts: Int    = 60,
  timeoutMs:       Long   = 10_000L,
  userAgent:       String = "scalascript-wallet-vault-mpc-fireblocks/0.1",
)

object FireblocksOptions:
  val Default: FireblocksOptions = FireblocksOptions()

class FireblocksRemoteSigningClient(
  baseUrl:       String,
  apiKey:        String,
  privateKeyPem: String,
  options:       FireblocksOptions = FireblocksOptions.Default,
)(using ec: ExecutionContext)
    extends HttpRemoteSigningClient(
      baseUrl,
      "",
      options.vaultAccountId,
      HttpRemoteSigningOptions(
        pollIntervalMs  = options.pollIntervalMs,
        pollMaxAttempts = options.pollMaxAttempts,
        timeoutMs       = options.timeoutMs,
        userAgent       = options.userAgent,
      ),
    ):

  private val privateKey = FireblocksJwt.privateKeyFromPem(privateKeyPem)

  override def health(): Future[Boolean] =
    send(buildGet("/v1/vault/accounts_paged?limit=1"))
      .map(r => r.statusCode >= 200 && r.statusCode < 300)
      .recover { case _ => false }

  override def listAccounts(): Future[Seq[McpAccount]] =
    send(buildGet(s"/v1/vault/accounts/${options.vaultAccountId}")).flatMap { resp =>
      val sc = resp.statusCode
      if sc >= 200 && sc < 300 then
        Future.successful(parseAccount(ujson.read(resp.body)))
      else
        Future.failed(RuntimeException(s"Fireblocks listAccounts failed: HTTP $sc - ${truncated(resp.body)}"))
    }

  override def sign(
    accountId:      String,
    curve:          Curve,
    derivationPath: String,
    payload:        Array[Byte],
    hashAlgo:       HashAlgo,
  ): Future[Array[Byte]] =
    val body = FireblocksWire.signTransactionRequest(
      vaultAccountId = accountId,
      assetId        = options.assetId,
      curve          = curve,
      derivationPath = derivationPath,
      payload        = payload,
      hashAlgo       = hashAlgo,
    )
    send(buildPost("/v1/transactions", body)).flatMap { resp =>
      resp.statusCode match
        case sc if sc >= 200 && sc < 300 =>
          val id = FireblocksWire.parseTransactionId(ujson.read(resp.body))
          pollTransaction(id)
        case sc =>
          Future.failed(RuntimeException(s"Fireblocks sign failed: HTTP $sc - ${truncated(resp.body)}"))
    }

  private def pollTransaction(txId: String): Future[Array[Byte]] =
    def attempt(remaining: Int): Future[Array[Byte]] =
      if remaining <= 0 then
        Future.failed(RuntimeException(
          s"Fireblocks transaction $txId did not complete within ${options.pollMaxAttempts} polls"
        ))
      else
        send(buildGet(s"/v1/transactions/$txId")).flatMap { resp =>
          val sc = resp.statusCode
          if sc >= 200 && sc < 300 then
            FireblocksWire.parseTransactionStatus(ujson.read(resp.body)) match
              case Right(Some(sig)) => Future.successful(sig)
              case Right(None)      => sleep(options.pollIntervalMs).flatMap(_ => attempt(remaining - 1))
              case Left(reason)     => Future.failed(RuntimeException(s"Fireblocks transaction $txId failed: $reason"))
          else
            Future.failed(RuntimeException(s"Fireblocks poll failed: HTTP $sc - ${truncated(resp.body)}"))
        }

    attempt(options.pollMaxAttempts)

  private def parseAccount(value: ujson.Value): Seq[McpAccount] =
    val obj = value.obj
    val keys = obj.get("publicKeys") match
      case Some(v) =>
        v.obj.iterator.toSeq.flatMap { case (name, encoded) =>
          MpcSerialization.parseCurve(name).map(_ -> MpcSerialization.b64decode(encoded.str))
        }.toMap
      case None =>
        Map.empty[Curve, Array[Byte]]
    Seq(McpAccount(
      id         = obj.get("id").map(_.str).getOrElse(options.vaultAccountId),
      label      = obj.get("name").orElse(obj.get("label")).map(_.str).getOrElse(options.vaultAccountId),
      publicKeys = keys,
    ))

  private def buildGet(path: String): HttpRequest =
    build("GET", path, "")

  private def buildPost(path: String, body: ujson.Value): HttpRequest =
    build("POST", path, ujson.write(body))

  private def build(method: String, path: String, body: String): HttpRequest =
    val uri = URI.create(s"$trimmedBase$path")
    val b = HttpRequest.newBuilder(uri)
      .header("Accept", "application/json")
      .header("User-Agent", options.userAgent)
      .header("X-API-Key", apiKey)
      .header("Authorization", s"Bearer ${FireblocksJwt.sign(apiKey, privateKey, path, body)}")
      .timeout(Duration.ofMillis(options.timeoutMs))
    if method == "POST" then
      b.header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    else b.GET().build()

object FireblocksWire:
  def signTransactionRequest(
    vaultAccountId: String,
    assetId:        String,
    curve:          Curve,
    derivationPath: String,
    payload:        Array[Byte],
    hashAlgo:       HashAlgo,
  ): ujson.Obj =
    ujson.Obj(
      "operation" -> "RAW",
      "assetId"   -> assetId,
      "source"    -> ujson.Obj("type" -> "VAULT_ACCOUNT", "id" -> vaultAccountId),
      "extraParameters" -> ujson.Obj(
        "rawMessageData" -> ujson.Obj(
          "messages" -> ujson.Arr(ujson.Obj(
            "content"        -> hex(payload),
            "derivationPath" -> derivationPath,
            "algorithm"      -> fireblocksAlgorithm(curve),
            "hashAlgorithm"  -> MpcSerialization.hashName(hashAlgo),
          ))
        )
      ),
    )

  def parseTransactionId(value: ujson.Value): String =
    val obj = value.obj
    obj.get("id").orElse(obj.get("txId")).map(_.str).getOrElse {
      throw IllegalStateException("Fireblocks create transaction response missing id")
    }

  def parseTransactionStatus(value: ujson.Value): Either[String, Option[Array[Byte]]] =
    val obj = value.obj
    val status = obj.get("status").map(_.str).getOrElse("PENDING").toUpperCase
    status match
      case "COMPLETED" =>
        signatureFrom(obj).map(sig => Right(Some(sig))).getOrElse(Left("completed transaction missing signature"))
      case "FAILED" | "REJECTED" | "CANCELLED" | "BLOCKED" =>
        Left(obj.get("subStatus").orElse(obj.get("error")).map(_.str).getOrElse(status))
      case _ =>
        Right(None)

  private def signatureFrom(obj: upickle.core.LinkedHashMap[String, ujson.Value]): Option[Array[Byte]] =
    for
      arr <- obj.get("signedMessages").map(_.arr)
      first <- arr.headOption
      sigObj <- first.obj.get("signature")
      sig <- sigObj.obj.get("fullSig").orElse(sigObj.obj.get("signature")).orElse(sigObj.obj.get("hex"))
    yield unhex(sig.str)

  def fireblocksAlgorithm(curve: Curve): String = curve match
    case Curve.Secp256k1 => "MPC_ECDSA_SECP256K1"
    case Curve.Ed25519   => "MPC_EDDSA_ED25519"
    case other           => throw UnsupportedOperationException(s"Fireblocks MPC does not support $other")

  export MpcSerialization.{hex, unhex}

object FireblocksJwt:
  private val rng = SecureRandom()
  private val b64Url = Base64.getUrlEncoder.withoutPadding()

  def sign(apiKey: String, key: PrivateKey, uri: String, body: String): String =
    val now = System.currentTimeMillis() / 1000L
    val header = ujson.Obj("alg" -> "RS256", "typ" -> "JWT")
    val payload = ujson.Obj(
      "sub"      -> apiKey,
      "iat"      -> now,
      "exp"      -> (now + 55L),
      "nonce"    -> nonce(),
      "uri"      -> uri,
      "bodyHash" -> sha256Hex(body.getBytes("UTF-8")),
    )
    val signingInput = s"${enc(ujson.write(header).getBytes("UTF-8"))}.${enc(ujson.write(payload).getBytes("UTF-8"))}"
    val sig = Signature.getInstance("SHA256withRSA")
    sig.initSign(key)
    sig.update(signingInput.getBytes("US-ASCII"))
    s"$signingInput.${enc(sig.sign())}"

  def privateKeyFromPem(pem: String): PrivateKey =
    val clean = pem
      .replace("-----BEGIN PRIVATE KEY-----", "")
      .replace("-----END PRIVATE KEY-----", "")
      .replaceAll("\\s", "")
    val bytes = Base64.getDecoder.decode(clean)
    KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))

  def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  def decodePayload(jwt: String): ujson.Value =
    val part = jwt.split("\\.")(1)
    ujson.read(String(Base64.getUrlDecoder.decode(part), "UTF-8"))

  private def enc(bytes: Array[Byte]): String = b64Url.encodeToString(bytes)

  private def nonce(): String =
    val bytes = new Array[Byte](16)
    rng.nextBytes(bytes)
    enc(bytes)
