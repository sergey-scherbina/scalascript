package scalascript.wallet.strategy.erc4337

import org.scalatest.BeforeAndAfterAll
import scalascript.crypto.CryptoBackend
import scalascript.crypto.noble.NobleCryptoBackend

/** Scala.js-side concrete: explicitly installs the Noble.js
 *  `CryptoBackend`. */
class WebAuthnAssertionTest extends WebAuthnAssertionTestBase with BeforeAndAfterAll:
  override def beforeAll(): Unit =
    super.beforeAll()
    CryptoBackend.register(new NobleCryptoBackend)
