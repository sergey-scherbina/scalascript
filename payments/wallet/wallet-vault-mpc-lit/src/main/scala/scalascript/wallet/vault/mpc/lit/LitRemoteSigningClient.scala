package scalascript.wallet.vault.mpc.lit

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scalascript.crypto.{Curve, HashAlgo}
import scalascript.wallet.vault.mpc.*

/** Lit Protocol PKP signing configuration.
 *
 *  @param pkpPublicKey  hex-encoded compressed public key of the PKP
 *  @param authSig       pre-computed SIWE-style AuthSig JSON string
 *  @param sigName       label for this signature slot (arbitrary, echoed back)
 *  @param litNodeUrl    base URL of the Lit node (e.g. `https://rpc.litprotocol.com`)
 */
case class LitOptions(
  pkpPublicKey:    String,
  authSig:         String,
  sigName:         String         = "sig1",
  pollIntervalMs:  Long           = 500L,
  pollMaxAttempts: Int            = 60,
  timeoutMs:       Long           = 15_000L,
  userAgent:       String         = "scalascript-wallet-vault-mpc-lit/0.1",
)

object LitOptions:
  val Default: LitOptions = LitOptions(pkpPublicKey = "", authSig = "{}")

/** Signing client that delegates to a Lit Protocol node over HTTP.
 *
 *  Auth: the caller supplies a pre-computed `authSig` (a SIWE AuthSig serialised
 *  as JSON).  The client passes it as the `authSig` field in every signing
 *  request body; no request-signing or JWT generation is performed by this class.
 *
 *  Endpoints:
 *  - `GET  /health`            → health check
 *  - `GET  /web3/pkp/list`     → account discovery (returns PKP list)
 *  - `POST /web3/pkp/sign`     → threshold ECDSA/EdDSA signing
 *
 *  The `/web3/pkp/sign` response contains the assembled threshold signature;
 *  no multi-node aggregation is performed by this adapter — the single configured
 *  node URL is expected to either be a Lit relay/aggregator or a dev-mode node.
 */
class LitRemoteSigningClient(
  baseUrl: String,
  options: LitOptions,
)(using ec: ExecutionContext)
    extends HttpRemoteSigningClient(
      baseUrl,
      initialToken = "",
      accountId    = options.pkpPublicKey,
      HttpRemoteSigningOptions(
        pollIntervalMs  = options.pollIntervalMs,
        pollMaxAttempts = options.pollMaxAttempts,
        timeoutMs       = options.timeoutMs,
        userAgent       = options.userAgent,
      ),
    ):

  private val http =
    HttpClient.newBuilder()
      .connectTimeout(Duration.ofMillis(options.timeoutMs))
      .build()

  private val trimmedBase =
    if baseUrl.endsWith("/") then baseUrl.dropRight(1) else baseUrl

  override def health(): Future[Boolean] =
    send(buildGet("/health"))
      .map(r => r.statusCode >= 200 && r.statusCode < 300)
      .recover { case _ => false }

  override def listAccounts(): Future[Seq[McpAccount]] =
    val authEncoded = java.net.URLEncoder.encode(options.authSig, "UTF-8")
    send(buildGet(s"/web3/pkp/list?authSig=$authEncoded")).flatMap { resp =>
      val sc = resp.statusCode
      if sc >= 200 && sc < 300 then
        Future.successful(LitWire.parsePkpList(ujson.read(resp.body), options.pkpPublicKey))
      else
        Future.failed(RuntimeException(s"Lit listAccounts failed: HTTP $sc - ${truncated(resp.body)}"))
    }

  override def sign(
    accountId:      String,
    curve:          Curve,
    derivationPath: String,
    payload:        Array[Byte],
    hashAlgo:       HashAlgo,
  ): Future[Array[Byte]] =
    val body = LitWire.pkpSignBody(
      pkpPublicKey = accountId,
      toSign       = payload,
      authSig      = options.authSig,
      sigName      = options.sigName,
      curve        = curve,
    )
    send(buildPost("/web3/pkp/sign", body)).flatMap { resp =>
      val sc = resp.statusCode
      if sc >= 200 && sc < 300 then
        Future.fromTry(scala.util.Try(LitWire.parseSignature(ujson.read(resp.body), options.sigName)))
      else
        Future.failed(RuntimeException(s"Lit sign failed: HTTP $sc - ${truncated(resp.body)}"))
    }

  private def buildGet(path: String): HttpRequest =
    HttpRequest.newBuilder(URI.create(s"$trimmedBase$path"))
      .header("Accept",     "application/json")
      .header("User-Agent", options.userAgent)
      .timeout(Duration.ofMillis(options.timeoutMs))
      .GET()
      .build()

  private def buildPost(path: String, body: ujson.Value): HttpRequest =
    val bodyStr = ujson.write(body)
    HttpRequest.newBuilder(URI.create(s"$trimmedBase$path"))
      .header("Accept",       "application/json")
      .header("Content-Type", "application/json")
      .header("User-Agent",   options.userAgent)
      .timeout(Duration.ofMillis(options.timeoutMs))
      .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
      .build()

  private def send(req: HttpRequest): Future[HttpResponse[String]] =
    val p = Promise[HttpResponse[String]]()
    http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete { (resp, err) =>
      if err != null then p.failure(err) else p.success(resp)
    }
    p.future

  private def truncated(s: String): String =
    if s.length <= 200 then s else s.take(200) + "..."

object LitWire:
  def pkpSignBody(
    pkpPublicKey: String,
    toSign:       Array[Byte],
    authSig:      String,
    sigName:      String,
    curve:        Curve,
  ): ujson.Obj =
    val authSigValue = try ujson.read(authSig) catch case _ => ujson.Str(authSig)
    ujson.Obj(
      "pkpPublicKey" -> pkpPublicKey,
      "toSign"       -> ujson.Arr(toSign.map(b => ujson.Num((b & 0xff).toDouble))*),
      "authSig"      -> authSigValue,
      "sigName"      -> sigName,
      "curve"        -> litCurve(curve),
    )

  def parseSignature(value: ujson.Value, sigName: String): Array[Byte] =
    val root = value.obj
    val sigNode =
      root.get("signatures").flatMap(_.obj.get(sigName))
        .orElse(root.get("signature"))
        .orElse(root.get(sigName))
        .getOrElse(throw IllegalStateException(s"Lit sign response missing signature '$sigName'"))
    val sigObj = sigNode.obj
    sigObj.get("signature")
      .orElse(sigObj.get("sig"))
      .map(v => unhex(v.str))
      .getOrElse {
        val r = sigObj("r").str
        val s = sigObj("s").str
        unhex(r) ++ unhex(s)
      }

  def parsePkpList(value: ujson.Value, fallbackKey: String): Seq[McpAccount] =
    val arr = value.obj.get("pkps")
      .map(_.arr)
      .orElse(value.arrOpt.map(_.toIndexedSeq))
      .getOrElse(ujson.Arr(value).arr)
    if arr.isEmpty && fallbackKey.nonEmpty then
      Seq(McpAccount(id = fallbackKey, label = "default", publicKeys = Map.empty))
    else
      arr.toSeq.map { entry =>
        val obj  = entry.obj
        val key  = obj.get("publicKey").orElse(obj.get("pkpPublicKey")).map(_.str).getOrElse(fallbackKey)
        val name = obj.get("name").orElse(obj.get("id")).map(_.str).getOrElse(key)
        McpAccount(id = key, label = name, publicKeys = Map.empty)
      }

  def litCurve(curve: Curve): String = curve match
    case Curve.Secp256k1 => "K256"
    case Curve.Ed25519   => "ed25519"
    case Curve.P256      => "P256"
    case other           => throw UnsupportedOperationException(s"Lit Protocol does not support $other")

  def unhex(s: String): Array[Byte] =
    val clean = if s.startsWith("0x") then s.drop(2) else s
    require(clean.length % 2 == 0, s"Invalid hex length: ${clean.length}")
    clean.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
