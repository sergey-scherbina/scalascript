package scalascript.wallet.vault.encrypted

import org.scalatest.BeforeAndAfterAll

import scalascript.crypto.CryptoBackend
import scalascript.crypto.noble.NobleCryptoBackend

/** Scala.js concrete subclass.  Registers `crypto-noble-js` before any
 *  test runs since Scala.js has no `ServiceLoader` — the shared
 *  `CryptoBackend.get()` call inside `Bip39` resolves to the
 *  registered backend. */
class Bip39Test extends Bip39TestBase with BeforeAndAfterAll:

  override def beforeAll(): Unit =
    CryptoBackend.register(new NobleCryptoBackend)
