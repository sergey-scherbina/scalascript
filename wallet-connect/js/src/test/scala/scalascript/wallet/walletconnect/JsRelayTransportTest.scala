package scalascript.wallet.walletconnect

import scala.concurrent.ExecutionContext
import org.scalatest.BeforeAndAfterAll

import scalascript.crypto.CryptoBackend
import scalascript.crypto.noble.NobleCryptoBackend

/** Scala.js concrete subclass — wires `JsRelayTransport` into the
 *  shared [[RelayTransportTestBase]] specs and registers
 *  `crypto-noble-js` so `CryptoBackend.get()` resolves. */
class JsRelayTransportTest extends RelayTransportTestBase with BeforeAndAfterAll:

  override def beforeAll(): Unit =
    CryptoBackend.register(new NobleCryptoBackend)

  override protected def mkTransport(
    ws:       WsChannel,
    store:    WcSessionStore,
    jwtSeed:  Array[Byte],
    relayUrl: String,
  )(using ec: ExecutionContext): RelayTransportBase =
    new JsRelayTransport(ws, store, jwtSeed, relayUrl)
