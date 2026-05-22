package scalascript.blockfrost

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global

class BlockfrostClientTest extends AnyFunSuite:

  test("BlockfrostConfig default baseUrl is mainnet") {
    val cfg = BlockfrostConfig("project_test")
    assert(cfg.baseUrl.contains("cardano-mainnet.blockfrost.io"))
  }

  test("BlockfrostConfig stores projectId") {
    val cfg = BlockfrostConfig("my_project_123")
    assert(cfg.projectId == "my_project_123")
  }

  test("BlockfrostConfig accepts custom baseUrl for preprod") {
    val cfg = BlockfrostConfig("proj", "https://cardano-preprod.blockfrost.io/api/v0")
    assert(cfg.baseUrl.contains("preprod"))
  }

  test("AddressInfo aggregates lovelace balance") {
    val info = AddressInfo("addr1test", BigInt(5_000_000), Map.empty)
    assert(info.lovelaceBalance == BigInt(5_000_000))
  }

  test("AddressInfo stores native asset balances") {
    val assets = Map("policy123assetABC" -> BigInt(100))
    val info   = AddressInfo("addr1test", BigInt(2_000_000), assets)
    assert(info.assets("policy123assetABC") == BigInt(100))
  }

  test("Blockfrost.connect returns a client") {
    val cfg    = BlockfrostConfig("test_key")
    val client = Blockfrost.connect(cfg)
    assert(client != null)
  }

  // Live tests — skipped unless BLOCKFROST_KEY is set

  test("live: getAddressInfo returns lovelace for known address") {
    assume(sys.env.contains("BLOCKFROST_KEY"), "BLOCKFROST_KEY not set")
    val cfg    = BlockfrostConfig(sys.env("BLOCKFROST_KEY"),
      "https://cardano-preprod.blockfrost.io/api/v0")
    val client = Blockfrost.connect(cfg)
    val testAddress = sys.env.getOrElse("TEST_CARDANO_ADDRESS",
      "addr_test1vqeux7xwusdju9dvsj8h7mca9eqwuespty8xec4uq5hkgnqrxazxx")
    val info = Await.result(client.getAddressInfo(testAddress), 10.seconds)
    assert(info.lovelaceBalance >= BigInt(0))
  }
