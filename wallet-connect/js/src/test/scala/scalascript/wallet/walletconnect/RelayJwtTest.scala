package scalascript.wallet.walletconnect

import org.scalatest.BeforeAndAfterAll

import scalascript.crypto.CryptoBackend
import scalascript.crypto.noble.NobleCryptoBackend

/** Scala.js concrete subclass — registers `crypto-noble-js`. */
class RelayJwtTest extends RelayJwtTestBase with BeforeAndAfterAll:

  override def beforeAll(): Unit =
    CryptoBackend.register(new NobleCryptoBackend)
