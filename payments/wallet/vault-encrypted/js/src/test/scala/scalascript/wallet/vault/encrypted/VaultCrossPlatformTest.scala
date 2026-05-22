package scalascript.wallet.vault.encrypted

import org.scalatest.BeforeAndAfterAll

import scalascript.crypto.CryptoBackend
import scalascript.crypto.noble.NobleCryptoBackend

/** Scala.js concrete subclass.  Registers `crypto-noble-js` once
 *  before tests since Scala.js has no `ServiceLoader`. */
class VaultCrossPlatformTest extends VaultCrossPlatformTestBase with BeforeAndAfterAll:
  override def beforeAll(): Unit =
    CryptoBackend.register(new NobleCryptoBackend)

class VaultCrossPlatformAsyncTest extends VaultCrossPlatformAsyncTestBase with BeforeAndAfterAll:
  override def beforeAll(): Unit =
    CryptoBackend.register(new NobleCryptoBackend)
