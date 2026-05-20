package scalascript.wallet.walletconnect

import scala.concurrent.ExecutionContext

/** Scala.js WalletConnect v2 relay transport.
 *
 *  Composes [[RelayTransportBase]] (all WC protocol logic) with a
 *  browser-side [[BrowserWsChannel]].  Same construction shape as
 *  [[JvmRelayTransport]] — the only difference is the channel
 *  implementation, which is the entire reason WC v2 composes through
 *  `RelayTransportBase`.
 *
 *  In the browser this is wired up as:
 *  {{{
 *  given ec: ExecutionContext = ExecutionContext.global  // JS-side macrotask EC
 *  val transport = new JsRelayTransport(
 *    channel      = new BrowserWsChannel(),
 *    sessionStore = new WcSessionStore,
 *    jwtSeed      = wallet.ed25519Priv,
 *  )
 *  }}}
 *
 *  Tests inject a mock `WsChannel` exactly like the JVM-side test
 *  suite. */
class JsRelayTransport(
  channel:      WsChannel,
  sessionStore: WcSessionStore,
  jwtSeed:      Array[Byte],
  relayUrl:     String = "wss://relay.walletconnect.com",
)(using ec: ExecutionContext)
  extends RelayTransportBase(channel, sessionStore, jwtSeed, relayUrl)
