package scalascript.wallet.walletconnect

import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.util.concurrent.CompletionStage
import scala.concurrent.{Future, Promise}
import scala.jdk.FutureConverters.*

/** Minimal WebSocket abstraction the WC relay transport relies on.
 *
 *  Splitting this out keeps `JvmRelayTransport` testable: tests
 *  inject a `MockWsChannel` that records `send` payloads and lets
 *  the test inject inbound text frames synchronously, without any
 *  real network. Production wiring uses `JdkWsChannel` which wraps
 *  `java.net.http.WebSocket`. */
trait WsChannel:

  /** Open the underlying transport. Idempotent — opening an already-
   *  open channel returns a successful Future. */
  def connect(url: String, headers: Map[String, String] = Map.empty): Future[Unit]

  /** Send a text frame. */
  def send(text: String): Future[Unit]

  /** Register a handler for inbound text frames. The handler may be
   *  invoked from arbitrary threads; implementations must ensure
   *  partial WebSocket frames are accumulated into complete messages
   *  before invoking it. */
  def onText(handler: String => Unit): Unit

  /** Close the channel. Idempotent. */
  def close(): Future[Unit]


/** Production `WsChannel` over JDK 11+ `java.net.http.WebSocket`.
 *
 *  Partial text frames (`onText(..., last = false)`) are accumulated
 *  into a single `StringBuilder` and dispatched to the handler only
 *  when `last` is true — `WebSocket.Listener` is allowed to deliver
 *  one logical message as multiple invocations. */
final class JdkWsChannel(httpClient: HttpClient = HttpClient.newHttpClient()) extends WsChannel:

  @volatile private var socket:  WebSocket = null
  @volatile private var handler: String => Unit = _ => ()

  private final class Listener extends WebSocket.Listener:
    private val acc = new StringBuilder

    override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
      acc.append(data)
      if last then
        val message = acc.toString
        acc.setLength(0)
        try handler(message) catch case _: Throwable => ()
      ws.request(1)
      null

    override def onError(ws: WebSocket, error: Throwable): Unit =
      // We swallow listener errors so the wrapper Future surface drives
      // failure semantics. Production callers wire structured logging.
      ()

  def connect(url: String, headers: Map[String, String] = Map.empty): Future[Unit] =
    if socket != null then Future.successful(())
    else
      val builder0 = httpClient.newWebSocketBuilder()
      val builder  = headers.foldLeft(builder0) { case (b, (k, v)) => b.header(k, v) }
      val stage    = builder.buildAsync(URI.create(url), new Listener)
      val p        = Promise[Unit]()
      stage.whenComplete { (ws, err) =>
        if err != null then p.tryFailure(err)
        else
          socket = ws
          p.trySuccess(())
      }
      p.future

  def send(text: String): Future[Unit] =
    val ws = socket
    if ws == null then Future.failed(new IllegalStateException("WsChannel.send before connect"))
    else ws.sendText(text, true).asScala.map(_ => ())(using scala.concurrent.ExecutionContext.parasitic)

  def onText(h: String => Unit): Unit =
    handler = h

  def close(): Future[Unit] =
    val ws = socket
    if ws == null then Future.successful(())
    else
      socket = null
      ws.sendClose(WebSocket.NORMAL_CLOSURE, "client close")
        .asScala
        .map(_ => ())(using scala.concurrent.ExecutionContext.parasitic)
