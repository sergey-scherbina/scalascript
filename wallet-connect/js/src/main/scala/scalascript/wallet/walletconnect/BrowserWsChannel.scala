package scalascript.wallet.walletconnect

import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

/** Scala.js [[WsChannel]] over the browser's native `WebSocket`
 *  global.
 *
 *  Construction is **lazy**: the underlying socket isn't created until
 *  the first `connect(url)` call so tests can install a mock
 *  `globalThis.WebSocket` (or override the constructor through the
 *  `wsConstructor` parameter) before any actual networking happens.
 *
 *  Browser WebSockets don't fragment text messages at the JS API
 *  level — `MessageEvent.data` is always one complete logical message —
 *  so there's no accumulator like the JVM side.  Binary frames are
 *  ignored (WC v2 carries everything as text). */
final class BrowserWsChannel(
  wsConstructor: js.Function1[String, BrowserWsChannel.BrowserWebSocket] =
    (url: String) => BrowserWsChannel.defaultConstructor(url),
) extends WsChannel:

  @volatile private var socket:  BrowserWsChannel.BrowserWebSocket = null
  @volatile private var handler: String => Unit = _ => ()

  def connect(url: String, headers: Map[String, String] = Map.empty): Future[Unit] =
    if socket != null then Future.successful(())
    else
      val ws = wsConstructor(url)
      val p  = Promise[Unit]()
      ws.onopen = (_: js.Any) => { p.trySuccess(()); () }
      ws.onerror = (e: js.Any) => {
        p.tryFailure(new RuntimeException(s"ws error: $e"))
        ()
      }
      ws.onmessage = (event: BrowserWsChannel.MessageEvent) =>
        // `MessageEvent.data` is `string` for text frames and one of
        // `ArrayBuffer` / `Blob` / `ArrayBufferView` for binary.  We
        // only handle text frames; binary frames are silently ignored.
        if js.typeOf(event.data) == "string" then
          try handler(event.data.asInstanceOf[String])
          catch case _: Throwable => ()
        else ()
      ws.onclose = (_: js.Any) => ()
      socket = ws
      p.future

  def send(text: String): Future[Unit] =
    val ws = socket
    if ws == null then Future.failed(new IllegalStateException("WsChannel.send before connect"))
    else
      ws.send(text)
      Future.successful(())

  def onText(h: String => Unit): Unit =
    handler = h

  def close(): Future[Unit] =
    val ws = socket
    if ws == null then Future.successful(())
    else
      socket = null
      try ws.close() catch case _: Throwable => ()
      Future.successful(())

object BrowserWsChannel:

  /** Minimal facade over the browser's `WebSocket` constructor + the
   *  handful of `onopen / onmessage / onclose / onerror / send / close`
   *  members the channel uses.  Modelled as a non-native trait so tests
   *  can extend it directly — the production constructor builds the
   *  native one. */
  trait BrowserWebSocket extends js.Object:
    var onopen:    js.Function1[js.Any, Unit]
    var onmessage: js.Function1[MessageEvent, Unit]
    var onclose:   js.Function1[js.Any, Unit]
    var onerror:   js.Function1[js.Any, Unit]
    def send(data: String): Unit
    def close(): Unit

  trait MessageEvent extends js.Object:
    val data: js.Any

  @js.native
  @JSGlobal("WebSocket")
  private class NativeWebSocket(@annotation.unused url: String) extends js.Object:
    var onopen:    js.Function1[js.Any, Unit]            = js.native
    var onmessage: js.Function1[MessageEvent, Unit]      = js.native
    var onclose:   js.Function1[js.Any, Unit]            = js.native
    var onerror:   js.Function1[js.Any, Unit]            = js.native
    def send(data: String): Unit                         = js.native
    def close(): Unit                                    = js.native

  /** Default constructor — wraps `new WebSocket(url)`.  Tests pass a
   *  custom constructor through the channel's primary `wsConstructor`
   *  parameter so the global never needs to be stubbed. */
  def defaultConstructor(url: String): BrowserWebSocket =
    new NativeWebSocket(url).asInstanceOf[BrowserWebSocket]
