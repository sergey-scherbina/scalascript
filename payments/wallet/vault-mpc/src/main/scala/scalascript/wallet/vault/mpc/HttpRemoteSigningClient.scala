package scalascript.wallet.vault.mpc

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}
import scalascript.crypto.{Curve, HashAlgo}

/** Options governing the HTTP behaviour of an `HttpRemoteSigningClient`.
 *
 *  - `pollIntervalMs` — sleep between polls when a sign call comes back
 *    202 with an `operationId` (async / threshold-quorum signing).
 *  - `pollMaxAttempts` — give up after this many polls and fail the
 *    Future. The total wall-clock timeout is bounded by
 *    `pollIntervalMs * pollMaxAttempts`.
 *  - `timeoutMs` — per-request HTTP timeout (connect + response).
 *  - `userAgent` — sent as the `User-Agent` header. */
case class HttpRemoteSigningOptions(
  pollIntervalMs:   Long   = 500L,
  pollMaxAttempts:  Int    = 60,    // default ≈ 30 s end-to-end
  timeoutMs:        Long   = 10_000L,
  userAgent:        String = "scalascript-wallet-vault-mpc/0.1",
)

object HttpRemoteSigningOptions:
  val Default: HttpRemoteSigningOptions = HttpRemoteSigningOptions()

/** Reference `RemoteSigningClient` over plain JSON-over-HTTPS, modelled
 *  on the "Fireblocks-like" REST surface common to MPC custody
 *  providers:
 *
 *  - `GET    {base}/v1/accounts`                         → list accounts
 *  - `POST   {base}/v1/accounts/{id}/sign`               → sign
 *  - `GET    {base}/v1/operations/{operationId}`         → poll async op
 *  - `GET    {base}/health`                              → liveness
 *
 *  Authentication: bearer token in the `Authorization` header. Real
 *  providers typically additionally sign requests with an API key;
 *  that's a per-provider concern best implemented by a subclass that
 *  overrides the `decorateRequest` hook below.
 *
 *  Sync vs async signing is decided by the server's response:
 *  - `200 OK` + `{"status":"completed", "signature":...}` → return now.
 *  - `202 Accepted` + `{"operationId":"..."}` → start polling
 *    `/v1/operations/{id}` until status is `completed` / `failed` or
 *    `pollMaxAttempts` is exceeded.
 *
 *  The bearer token is held in a `@volatile var` so `forgetToken()`
 *  can blank it out — this is what `McpVault.lock()` ultimately
 *  triggers when a host wants to drop the cached credential. */
class HttpRemoteSigningClient(
  baseUrl:        String,
  initialToken:   String,
  val accountId:  String,
  options:        HttpRemoteSigningOptions = HttpRemoteSigningOptions.Default,
)(using ec: ExecutionContext) extends RemoteSigningClient:

  @volatile private var token: String = initialToken

  protected val http: HttpClient =
    HttpClient.newBuilder()
      .connectTimeout(Duration.ofMillis(options.timeoutMs))
      .build()

  protected val trimmedBase: String =
    if baseUrl.endsWith("/") then baseUrl.dropRight(1) else baseUrl

  /** Drop the cached bearer token. After this, all subsequent calls
   *  will go out unauthenticated and 401 / 403; intended use is wiring
   *  through `McpVault.lock()`. */
  def forgetToken(): Unit =
    token = ""

  /** Subclass hook for provider-specific request decoration (HMAC
   *  signature, idempotency keys, …). Default just adds the bearer
   *  token + JSON content-type + User-Agent. */
  protected def decorateRequest(b: HttpRequest.Builder): HttpRequest.Builder =
    val withCommon = b
      .header("Accept",       "application/json")
      .header("User-Agent",   options.userAgent)
      .timeout(Duration.ofMillis(options.timeoutMs))
    if token.nonEmpty then withCommon.header("Authorization", s"Bearer $token")
    else withCommon

  private def buildGet(path: String): HttpRequest =
    decorateRequest(HttpRequest.newBuilder(URI.create(s"$trimmedBase$path")).GET()).build()

  private def buildPost(path: String, body: ujson.Value): HttpRequest =
    decorateRequest(
      HttpRequest.newBuilder(URI.create(s"$trimmedBase$path"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(ujson.write(body)))
    ).build()

  /** Send `req` async via the JDK client. Wraps the resulting
   *  `CompletableFuture[HttpResponse[String]]` in a Scala `Future`.
   *  `protected` so provider subclasses reuse this transport instead of
   *  re-deriving their own JDK-client plumbing. */
  protected def send(req: HttpRequest): Future[HttpResponse[String]] =
    val p = Promise[HttpResponse[String]]()
    val cf = http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
    cf.whenComplete { (resp, err) =>
      if err != null then p.failure(err)
      else p.success(resp)
    }
    p.future

  // ─── trait impl ────────────────────────────────────────────────────

  def listAccounts(): Future[Seq[McpAccount]] =
    send(buildGet("/v1/accounts")).flatMap { resp =>
      val sc = resp.statusCode
      if sc >= 200 && sc < 300 then
        Future.fromTry(Try(MpcSerialization.parseAccountList(ujson.read(resp.body))))
      else
        Future.failed(new RuntimeException(
          s"listAccounts failed: HTTP $sc — ${truncated(resp.body)}"
        ))
    }

  def health(): Future[Boolean] =
    send(buildGet("/health"))
      .map(r => r.statusCode >= 200 && r.statusCode < 300)
      .recover { case _ => false }

  def sign(
    accountId:      String,
    curve:          Curve,
    derivationPath: String,
    payload:        Array[Byte],
    hashAlgo:       HashAlgo,
  ): Future[Array[Byte]] =
    val body = MpcSerialization.signRequest(curve, payload, derivationPath, hashAlgo)
    val req  = buildPost(s"/v1/accounts/$accountId/sign", body)
    send(req).flatMap { resp =>
      val sc = resp.statusCode
      sc match
        case 200 =>
          Future.fromTry(Try(MpcSerialization.parseSignCompleted(ujson.read(resp.body))))
        case 202 =>
          Future.fromTry(Try(MpcSerialization.parseOperationId(ujson.read(resp.body))))
            .flatMap(pollOperation)
        case other =>
          Future.failed(new RuntimeException(
            s"sign failed: HTTP $other — ${truncated(resp.body)}"
          ))
    }

  // ─── async polling ────────────────────────────────────────────────

  private def pollOperation(operationId: String): Future[Array[Byte]] =
    val req = buildGet(s"/v1/operations/$operationId")

    def attempt(remaining: Int): Future[Array[Byte]] =
      if remaining <= 0 then
        Future.failed(new RuntimeException(
          s"sign operation $operationId did not complete within ${options.pollMaxAttempts} polls"
        ))
      else
        send(req).flatMap { resp =>
          val sc = resp.statusCode
          if sc >= 200 && sc < 300 then
            Try(MpcSerialization.pollStatus(ujson.read(resp.body))) match
              case Success(Right(Some(sig))) => Future.successful(sig)
              case Success(Right(None))      => sleep(options.pollIntervalMs).flatMap(_ => attempt(remaining - 1))
              case Success(Left(err))        => Future.failed(new RuntimeException(s"operation $operationId failed: $err"))
              case Failure(t)                => Future.failed(t)
          else
            Future.failed(new RuntimeException(
              s"poll $operationId failed: HTTP $sc — ${truncated(resp.body)}"
            ))
        }

    attempt(options.pollMaxAttempts)

  // ─── helpers ──────────────────────────────────────────────────────

  // Promise-based sleep, runs the resume on the global scheduler then
  // continues on `ec`. Tiny single-thread executor avoids spinning a
  // dedicated thread per poll.
  protected def sleep(ms: Long): Future[Unit] =
    val p = Promise[Unit]()
    HttpRemoteSigningClient.scheduler.schedule(
      new Runnable { def run(): Unit = p.success(()) },
      ms,
      java.util.concurrent.TimeUnit.MILLISECONDS,
    )
    p.future

  protected def truncated(s: String): String =
    if s.length <= 200 then s else s.substring(0, 200) + "…"

object HttpRemoteSigningClient:
  /** Shared scheduler for poll-interval sleeps. Daemon thread so it
   *  doesn't block JVM shutdown. */
  private val scheduler: java.util.concurrent.ScheduledExecutorService =
    val tf = new java.util.concurrent.ThreadFactory:
      def newThread(r: Runnable): Thread =
        val t = new Thread(r, "mpc-http-poll-scheduler")
        t.setDaemon(true)
        t
    java.util.concurrent.Executors.newSingleThreadScheduledExecutor(tf)
