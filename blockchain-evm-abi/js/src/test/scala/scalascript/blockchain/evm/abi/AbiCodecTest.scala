package scalascript.blockchain.evm.abi

import org.scalatest.BeforeAndAfterAll
import scalascript.crypto.CryptoBackend
import scalascript.crypto.noble.NobleCryptoBackend

/** Scala.js-side concrete instantiation of [[AbiCodecTestBase]].
 *
 *  Scala.js has no `ServiceLoader`, so the noble backend is registered
 *  explicitly in `beforeAll` (idempotent — the registry's `register`
 *  overwrites on duplicate id).  This is the same pattern the other
 *  Stage-2/3 JS test suites use when they need keccak256. */
class AbiCodecTest extends AbiCodecTestBase with BeforeAndAfterAll:

  override def beforeAll(): Unit =
    super.beforeAll()
    CryptoBackend.register(new NobleCryptoBackend)
