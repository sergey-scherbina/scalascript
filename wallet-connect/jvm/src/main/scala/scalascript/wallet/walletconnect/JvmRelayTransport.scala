package scalascript.wallet.walletconnect

import scala.concurrent.ExecutionContext

/** JVM WalletConnect v2 relay transport.
 *
 *  Composes [[RelayTransportBase]] (which owns all the WC protocol
 *  logic — JSON-RPC framing, envelope demux, X25519 Type-1 approve)
 *  with a JVM-side [[WsChannel]].  Construct with:
 *  {{{
 *  val transport = new JvmRelayTransport(
 *    channel      = new JdkWsChannel,
 *    sessionStore = new WcSessionStore,
 *    jwtSeed      = walletEd25519Priv,        // 32 B
 *    // relayUrl defaults to wss://relay.walletconnect.com
 *  )
 *  }}}
 *
 *  Then wire it into the `WalletConnectConnector`.
 *
 *  For tests, inject a `MockWsChannel`.  Everything else lives in
 *  [[RelayTransportBase]] so the matching `js/JsRelayTransport` reuses
 *  the same implementation. */
class JvmRelayTransport(
  channel:      WsChannel,
  sessionStore: WcSessionStore,
  jwtSeed:      Array[Byte],
  relayUrl:     String = "wss://relay.walletconnect.com",
)(using ec: ExecutionContext)
  extends RelayTransportBase(channel, sessionStore, jwtSeed, relayUrl)
