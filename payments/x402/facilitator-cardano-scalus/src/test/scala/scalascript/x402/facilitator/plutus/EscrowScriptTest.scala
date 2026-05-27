package scalascript.x402.facilitator.plutus

import org.scalatest.funsuite.AnyFunSuite
import scalascript.blockchain.cardano.Bech32
import scalascript.x402.Network

class EscrowScriptTest extends AnyFunSuite:

  test("scriptHash: derives a 28-byte script credential from the compiled validator") {
    assert(EscrowScript.scriptHash.length == 28)
    assert(EscrowScript.scriptHash.exists(_ != 0.toByte))
  }

  test("address: derives mainnet enterprise script address") {
    val address = EscrowScript.address(Network.CardanoMainnet)
    assert(address == "addr1wxj0t77w5k08xqpsslzw4rljksp7ev9stduxrzqgyg7w35qm75nhg")

    val bytes = Bech32.decode(address).getOrElse(fail("mainnet address must decode"))
    assert(bytes.length == 29)
    assert((bytes(0) & 0xff) == 0x71)
    assert(bytes.tail.toSeq == EscrowScript.scriptHash.toSeq)
  }

  test("address: derives preprod and preview testnet enterprise script address") {
    val preprod = EscrowScript.address(Network.CardanoPreprod)
    val preview = EscrowScript.address(Network.CardanoPreview)

    assert(preprod == "addr_test1wzj0t77w5k08xqpsslzw4rljksp7ev9stduxrzqgyg7w35qqkq0cd")
    assert(preprod == preview)

    val bytes = Bech32.decode(preprod).getOrElse(fail("testnet address must decode"))
    assert(bytes.length == 29)
    assert((bytes(0) & 0xff) == 0x70)
    assert(bytes.tail.toSeq == EscrowScript.scriptHash.toSeq)
  }

  test("address: rejects non-Cardano networks") {
    val ex = intercept[IllegalArgumentException](EscrowScript.address(Network.Base))
    assert(ex.getMessage.contains("Cardano network"))
  }
