package scalascript.micropayment.hydra

import scala.concurrent.{ExecutionContext, Future, Promise}
import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.util.concurrent.CompletionStage

/** Wraps the Hydra node WebSocket connection.
 *
 *  Uses Java 11 `java.net.http.WebSocket` (JVM only; Hydra nodes run server-side).
 *  Message handlers are registered via `subscribe`; `send` is fire-and-forget with
 *  a Future that completes when the WS buffer has accepted the frame.
 *
 *  Pending responses are tracked by txId (for TxValid/TxInvalid correlation) and
 *  by tag (for state transitions like HeadIsOpen / HeadIsClosed). */
trait HydraNodeClient:
  def send(msg: HydraClientMsg)(using ec: ExecutionContext): Future[Unit]
  def subscribe(handler: HydraServerMsg => Unit): Unit
  def disconnect(): Unit

object HydraNodeClient:
  /** Connect to a live Hydra node WebSocket endpoint.
   *  Blocks until the WS handshake completes. */
  def connect(wsUrl: String): Future[HydraNodeClient] =
    val p = Promise[HydraNodeClient]()
    new LiveHydraNodeClient(wsUrl, p): Unit
    p.future

  /** In-process stub for testing; inject messages via `inject(msg)`. */
  def stub(): StubHydraNodeClient = new StubHydraNodeClient

// ── Live (Java 11 WebSocket) ──────────────────────────────────────────────────

private class LiveHydraNodeClient(wsUrl: String, ready: Promise[HydraNodeClient])
    extends HydraNodeClient:

  @volatile private var handlers: List[HydraServerMsg => Unit] = Nil
  private val buf = new java.lang.StringBuilder

  private val httpClient = HttpClient.newHttpClient()

  private val ws: WebSocket = httpClient
    .newWebSocketBuilder()
    .buildAsync(URI.create(wsUrl), new WebSocket.Listener:
      override def onOpen(webSocket: WebSocket): Unit =
        webSocket.request(1)
        ready.trySuccess(LiveHydraNodeClient.this)

      override def onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
        buf.append(data)
        if last then
          val json = buf.toString
          buf.setLength(0)
          HydraServerMsg.parse(json).foreach(msg => handlers.foreach(_(msg)))
        webSocket.request(1)
        null

      override def onError(webSocket: WebSocket, error: Throwable): Unit =
        ready.tryFailure(error)
    ).join()

  def send(msg: HydraClientMsg)(using ec: ExecutionContext): Future[Unit] =
    val p = Promise[Unit]()
    ws.sendText(msg.toJson, true).whenComplete { (_, ex) =>
      if ex != null then p.failure(ex) else p.success(())
    }
    p.future

  def subscribe(handler: HydraServerMsg => Unit): Unit =
    handlers = handler :: handlers

  def disconnect(): Unit = ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye")

// ── Stub (in-process, for tests) ─────────────────────────────────────────────

class StubHydraNodeClient extends HydraNodeClient:
  @volatile private var handlers: List[HydraServerMsg => Unit] = Nil
  val sent: scala.collection.mutable.ArrayBuffer[HydraClientMsg] =
    scala.collection.mutable.ArrayBuffer.empty

  def send(msg: HydraClientMsg)(using ec: ExecutionContext): Future[Unit] =
    sent += msg
    Future.unit

  def subscribe(handler: HydraServerMsg => Unit): Unit =
    handlers = handler :: handlers

  def disconnect(): Unit = ()

  /** Push a server message into all registered handlers (simulates Hydra node response). */
  def inject(msg: HydraServerMsg): Unit = handlers.foreach(_(msg))
