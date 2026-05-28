package scalascript.wallet.vault.trezor

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.mutable
import upickle.default.*

/** HTTP client for the Trezor Bridge local daemon (trezord-go). */
trait TrezorBridge:
  /** Bridge daemon version string. */
  def version(): Future[String]

  /** List connected Trezor devices. */
  def enumerate(): Future[Seq[TrezorDeviceInfo]]

  /** Acquire a session on the device at `path`. Pass `previousSession`
   *  to take over an existing one; `None` for a fresh acquisition. */
  def acquire(path: String, previousSession: Option[String]): Future[String]

  /** Release a previously acquired session. */
  def release(session: String): Future[Unit]

  /** Send a Trezor Bridge typed message and receive a response. */
  def call(
    session:     String,
    messageType: String,
    message:     ujson.Value = ujson.Obj(),
  ): Future[TrezorResponse]

// ── HTTP implementation ───────────────────────────────────────────────────────

class HttpTrezorBridge(
  baseUrl:   String = "http://127.0.0.1:21325",
  timeoutMs: Long   = 10_000L,
)(using ec: ExecutionContext) extends TrezorBridge:

  private val http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofMillis(timeoutMs))
    .build()

  private def get(path: String): Future[String] = Future {
    val req = HttpRequest.newBuilder(URI.create(s"$baseUrl$path"))
      .GET()
      .header("Origin", "https://bridge.trezor.io")
      .timeout(Duration.ofMillis(timeoutMs))
      .build()
    val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
    if resp.statusCode() >= 400 then
      throw RuntimeException(s"Trezor Bridge GET $path → ${resp.statusCode()}: ${resp.body()}")
    resp.body()
  }

  private def post(path: String, body: String = ""): Future[String] = Future {
    val req = HttpRequest.newBuilder(URI.create(s"$baseUrl$path"))
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .header("Content-Type", "application/json")
      .header("Origin", "https://bridge.trezor.io")
      .timeout(Duration.ofMillis(timeoutMs))
      .build()
    val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
    if resp.statusCode() >= 400 then
      throw RuntimeException(s"Trezor Bridge POST $path → ${resp.statusCode()}: ${resp.body()}")
    resp.body()
  }

  def version(): Future[String] =
    get("/").map { body =>
      ujson.read(body).obj.get("version").map(_.str).getOrElse("unknown")
    }

  def enumerate(): Future[Seq[TrezorDeviceInfo]] =
    post("/enumerate").map { body =>
      read[Seq[TrezorDeviceInfo]](body)
    }

  def acquire(path: String, previousSession: Option[String]): Future[String] =
    val prev = previousSession.getOrElse("null")
    post(s"/acquire/$path/$prev").map { body =>
      ujson.read(body).obj("session").str
    }

  def release(session: String): Future[Unit] =
    post(s"/release/$session").map(_ => ())

  def call(
    session:     String,
    messageType: String,
    message:     ujson.Value = ujson.Obj(),
  ): Future[TrezorResponse] =
    val body = ujson.Obj("type" -> ujson.Str(messageType), "message" -> message).render()
    post(s"/call/$session", body).map { responseBody =>
      val json  = ujson.read(responseBody)
      val mType = json.obj("type").str
      val msg   = json.obj("message")
      TrezorResponse(mType, msg)
    }

// ── Mock implementation ───────────────────────────────────────────────────────

/** Injectable mock for tests. Push responses with `enqueueResponse`; all
 *  calls are recorded in `calls` for assertion. */
class MockTrezorBridge extends TrezorBridge:

  case class RecordedCall(messageType: String, message: ujson.Value)

  private val responseQueues = mutable.Map.empty[String, mutable.Queue[TrezorResponse]]
  val calls = mutable.ArrayBuffer.empty[RecordedCall]
  private var sessionCounter = 0

  def enqueueResponse(messageType: String, responseType: String, message: ujson.Value): Unit =
    val q = responseQueues.getOrElseUpdate(messageType, mutable.Queue.empty)
    q.enqueue(TrezorResponse(responseType, message))

  def enqueueFeatures(initialized: Boolean = true, pinCached: Boolean = true): Unit =
    enqueueResponse(TrezorMessageType.Initialize, TrezorMessageType.Features, ujson.Obj(
      "initialized"     -> ujson.Bool(initialized),
      "pin_protection"  -> ujson.Bool(false),
      "pin_cached"      -> ujson.Bool(pinCached),
      "firmware_present"-> ujson.Bool(true),
      "vendor"          -> ujson.Str("trezor.io"),
    ))

  def enqueuePublicKey(xpub: String, publicKeyHex: String = "02" + "aa" * 32): Unit =
    enqueueResponse(TrezorMessageType.GetPublicKey, TrezorMessageType.PublicKey, ujson.Obj(
      "xpub"    -> ujson.Str(xpub),
      "node"    -> ujson.Obj(
        "public_key"  -> ujson.Str(publicKeyHex),
        "chain_code"  -> ujson.Str("00" * 32),
        "depth"       -> ujson.Num(5),
        "fingerprint" -> ujson.Num(0),
        "child_num"   -> ujson.Num(0),
      ),
    ))

  def enqueueEthSignature(address: String, signature: String): Unit =
    enqueueResponse(
      TrezorMessageType.EthereumSignMessage,
      TrezorMessageType.EthereumMessageSig,
      ujson.Obj("address" -> ujson.Str(address), "signature" -> ujson.Str(signature)),
    )

  def enqueueFailure(code: Int = 2, message: String = "Action cancelled"): Unit =
    val q = responseQueues.getOrElseUpdate(TrezorMessageType.Initialize, mutable.Queue.empty)
    q.enqueue(TrezorResponse(TrezorMessageType.Failure, ujson.Obj(
      "code" -> ujson.Num(code), "message" -> ujson.Str(message),
    )))

  def version(): Future[String] = Future.successful("mock-2.0.0")

  def enumerate(): Future[Seq[TrezorDeviceInfo]] =
    Future.successful(Seq(TrezorDeviceInfo("mock-path-0", None, "trezor.io", "Trezor Mock")))

  def acquire(path: String, previousSession: Option[String]): Future[String] =
    sessionCounter += 1
    Future.successful(s"mock-session-$sessionCounter")

  def release(session: String): Future[Unit] = Future.successful(())

  def call(
    session:     String,
    messageType: String,
    message:     ujson.Value = ujson.Obj(),
  ): Future[TrezorResponse] =
    calls += RecordedCall(messageType, message)
    val q = responseQueues.getOrElse(messageType, mutable.Queue.empty)
    if q.nonEmpty then Future.successful(q.dequeue())
    else Future.failed(RuntimeException(
      s"MockTrezorBridge: no response queued for messageType=$messageType"
    ))
