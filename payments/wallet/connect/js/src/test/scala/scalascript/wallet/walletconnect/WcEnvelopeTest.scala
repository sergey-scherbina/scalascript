package scalascript.wallet.walletconnect

import org.scalatest.BeforeAndAfterAll

import scalascript.crypto.CryptoBackend
import scalascript.crypto.noble.NobleCryptoBackend

/** Scala.js concrete subclass — registers `crypto-noble-js` before any
 *  WC envelope test runs since Scala.js has no `ServiceLoader`. */
class WcEnvelopeTest extends WcEnvelopeTestBase with BeforeAndAfterAll:

  override def beforeAll(): Unit =
    CryptoBackend.register(new NobleCryptoBackend)
