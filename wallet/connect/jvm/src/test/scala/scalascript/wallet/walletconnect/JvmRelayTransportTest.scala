package scalascript.wallet.walletconnect

import scala.concurrent.ExecutionContext

/** JVM concrete subclass — wires `JvmRelayTransport` into the shared
 *  [[RelayTransportTestBase]] specs.  ServiceLoader auto-registers
 *  BouncyCastle so the shared crypto-driven assertions resolve to the
 *  JVM backend. */
class JvmRelayTransportTest extends RelayTransportTestBase:

  override protected def mkTransport(
    ws:       WsChannel,
    store:    WcSessionStore,
    jwtSeed:  Array[Byte],
    relayUrl: String,
  )(using ec: ExecutionContext): RelayTransportBase =
    new JvmRelayTransport(ws, store, jwtSeed, relayUrl)
