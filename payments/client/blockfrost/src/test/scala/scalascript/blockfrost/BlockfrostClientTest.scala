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

  test("BlockfrostProtocolParams parses latest-epoch parameters") {
    val params = BlockfrostProtocolParams.fromJson(ujson.read(
      """{
        |  "min_fee_a": 44,
        |  "min_fee_b": "155381",
        |  "max_tx_size": 16384,
        |  "price_mem": 0.0577,
        |  "price_step": "0.0000721",
        |  "coins_per_utxo_size": "4310",
        |  "collateral_percent": 150,
        |  "max_collateral_inputs": 3,
        |  "cost_models": {
        |    "PlutusV3": [100788, "420"]
        |  }
        |}""".stripMargin
    ))

    assert(params.minFeeA == BigInt(44))
    assert(params.minFeeB == BigInt(155381))
    assert(params.maxTxSize == BigInt(16384))
    assert(params.priceMem == BigDecimal("0.0577"))
    assert(params.priceStep == BigDecimal("0.0000721"))
    assert(params.coinsPerUtxoSize == BigInt(4310))
    assert(params.collateralPercent == 150)
    assert(params.maxCollateralInputs == 3)
    assert(params.costModels("PlutusV3") == Seq(100788L, 420L))
  }

  test("BlockfrostEvaluationResult parses Ogmios-style array response") {
    val results = BlockfrostEvaluationResult.parseAll(ujson.read(
      """[
        |  {"validator":"spend:0","budget":{"memory":1700,"cpu":476468}},
        |  {"validator":"mint:2","budget":{"memory":"42","steps":"99"}}
        |]""".stripMargin
    ))

    assert(results == Seq(
      BlockfrostEvaluationResult("spend:0", BigInt(1700), BigInt(476468)),
      BlockfrostEvaluationResult("mint:2", BigInt(42), BigInt(99)),
    ))
  }

  test("BlockfrostEvaluationResult parses map response") {
    val results = BlockfrostEvaluationResult.parseAll(ujson.read(
      """{"spend:0":{"memory":1700,"steps":476468}}"""
    ))

    assert(results == Seq(BlockfrostEvaluationResult("spend:0", BigInt(1700), BigInt(476468))))
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
