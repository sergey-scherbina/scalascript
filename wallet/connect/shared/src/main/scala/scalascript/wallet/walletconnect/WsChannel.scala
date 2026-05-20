package scalascript.wallet.walletconnect

import scala.concurrent.Future

/** Minimal WebSocket abstraction the WC relay transport relies on.
 *
 *  Splitting this out keeps the relay transports testable: tests
 *  inject a `MockWsChannel` that records `send` payloads and lets
 *  the test inject inbound text frames synchronously, without any
 *  real network. Production wiring uses platform-specific impls:
 *
 *  - **JVM**: `JdkWsChannel` (`wallet-connect/jvm/`) wraps
 *    `java.net.http.WebSocket`.
 *  - **Scala.js**: `BrowserWsChannel` (`wallet-connect/js/`) wraps
 *    the browser's native `WebSocket` global. */
trait WsChannel:

  /** Open the underlying transport. Idempotent — opening an already-
   *  open channel returns a successful Future. */
  def connect(url: String, headers: Map[String, String] = Map.empty): Future[Unit]

  /** Send a text frame. */
  def send(text: String): Future[Unit]

  /** Register a handler for inbound text frames. The handler may be
   *  invoked from arbitrary threads (JVM) or the JS event loop (Scala.js);
   *  implementations must ensure partial WebSocket frames are accumulated
   *  into complete messages before invoking it. */
  def onText(handler: String => Unit): Unit

  /** Close the channel. Idempotent. */
  def close(): Future[Unit]
