package scalascript.wallet.walletconnect

import scala.concurrent.Future

/** Transport abstraction for the WalletConnect v2 relay channel.
 *
 *  Real production transports plug in:
 *
 *  - JVM:   a JDK `java.net.http.WebSocket` connecting to
 *           `wss://relay.walletconnect.com?projectId=…`, with
 *           chacha20-poly1305 envelope encryption per the spec.
 *
 *  - JS:    a thin facade over `@walletconnect/sign-client` /
 *           `@walletconnect/web3wallet` running inside the PWA.
 *
 *  Tests inject a mock `WcRelayTransport` and drive inbound events
 *  via `simulateInbound(...)`. */
trait WcRelayTransport:

  /** Open the relay connection. Idempotent. */
  def connect(projectId: String): Future[Unit]

  /** Subscribe to a topic — invoked whenever a new pairing or session
   *  is established. */
  def subscribe(topic: String): Future[Unit]

  /** Unsubscribe from a topic — called on session delete / disconnect. */
  def unsubscribe(topic: String): Future[Unit]

  /** Emit an outbound message. Encryption + relay framing are the
   *  transport's responsibility. */
  def send(message: WcOutbound): Future[Unit]

  /** Register the wallet's inbound-event handler. The transport invokes
   *  this for every decrypted message it receives on subscribed topics. */
  def onInbound(handler: WcInbound => Unit): Unit

  /** Close the connection. */
  def close(): Future[Unit]
