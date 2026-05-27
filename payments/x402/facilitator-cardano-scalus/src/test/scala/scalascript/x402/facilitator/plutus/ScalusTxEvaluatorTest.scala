package scalascript.x402.facilitator.plutus

import com.bloxbean.cardano.client.api.impl.StaticTransactionEvaluator
import com.bloxbean.cardano.client.plutus.spec.ExUnits
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import org.scalatest.funsuite.AnyFunSuite
import scalascript.blockfrost.{AddressInfo, BlockfrostClient, BlockfrostEvaluationResult, BlockfrostProtocolParams, BlockfrostUtxo}
import scalascript.x402.{Network, ScalusEscrowRef}

import java.math.BigInteger
import java.net.InetSocketAddress
import java.util.Collections
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

class ScalusTxEvaluatorTest extends AnyFunSuite:

  private val params = BlockfrostProtocolParams(
    minFeeA             = BigInt(44),
    minFeeB             = BigInt(155381),
    maxTxSize           = BigInt(16384),
    priceMem            = BigDecimal("0.0577"),
    priceStep           = BigDecimal("0.0000721"),
    coinsPerUtxoSize    = BigInt(4310),
    collateralPercent   = 150,
    maxCollateralInputs = 3,
    costModels          = Map.empty,
  )

  private val plan = ClaimTxPlan(
    network         = Network.CardanoPreprod,
    escrowRef       = ScalusEscrowRef("a" * 64, 2),
    scriptAddress   = EscrowScript.address(Network.CardanoPreprod),
    receiverAddress = "addr_test1wzj0t77w5k08xqpsslzw4rljksp7ev9stduxrzqgyg7w35qqkq0cd",
    lovelace        = BigInt(2_000_000),
    coseSign1Hex    = "c0ffee",
    coseKeyHex      = "cafe",
    relayerKeyHex   = "11" * 32,
  )

  test("bloxbean evaluator adapts EvaluationResult into ScalusExUnits") {
    val evaluator = ScalusTxEvaluator.bloxbean(new StaticTransactionEvaluator(Collections.singletonList(
      ExUnits.builder().mem(BigInteger.valueOf(1234)).steps(BigInteger.valueOf(5678)).build()
    )))
    val tx = BloxbeanClaimTxDraftBuilder.buildTransaction(plan)
    val results = Await.result(evaluator.evaluate(tx), 5.seconds)

    assert(ScalusTxEvaluator.claimSpendExUnits(results).contains(ScalusExUnits(BigInt(1234), BigInt(5678))))
  }

  test("evaluated balanced draft rebuilds redeemer and fee from evaluator ex-units") {
    val evaluator = ScalusTxEvaluator.bloxbean(new StaticTransactionEvaluator(Collections.singletonList(
      ExUnits.builder().mem(BigInteger.valueOf(1000)).steps(BigInteger.valueOf(2000)).build()
    )))
    val tx = Await.result(BloxbeanClaimTxDraftBuilder.buildEvaluatedBalancedTransaction(plan, params, evaluator), 5.seconds)
    val redeemer = tx.getWitnessSet.getRedeemers.get(0)
    val expectedFee = ScalusFeeBalancer.estimate(params, tx.serialize().length, Seq(BigInt(1000) -> BigInt(2000))).total

    assert(redeemer.getExUnits.getMem == BigInteger.valueOf(1000))
    assert(redeemer.getExUnits.getSteps == BigInteger.valueOf(2000))
    assert(tx.getBody.getFee == expectedFee.bigInteger)
  }

  test("blockfrost endpoint evaluator maps spend:0 into claim ex-units") {
    val client = new BlockfrostClient:
      def getAddressInfo(address: String): Future[AddressInfo] = ???
      def isTxConfirmed(txHash: String): Future[Boolean] = ???
      def getUtxos(address: String): Future[Seq[BlockfrostUtxo]] = ???
      def submitTx(cbor: Array[Byte]): Future[String] = ???
      override def evaluateTx(cbor: Array[Byte]): Future[Seq[BlockfrostEvaluationResult]] =
        Future.successful(Seq(BlockfrostEvaluationResult("spend:0", BigInt(111), BigInt(222))))

    val tx = BloxbeanClaimTxDraftBuilder.buildTransaction(plan)
    val results = Await.result(ScalusTxEvaluator.blockfrost(client).evaluate(tx), 5.seconds)
    assert(ScalusTxEvaluator.claimSpendExUnits(results).contains(ScalusExUnits(BigInt(111), BigInt(222))))
  }

  test("ogmios HTTP evaluator posts evaluateTransaction JSON-RPC") {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    try
      var seenBody = ""
      server.createContext("/", new HttpHandler:
        def handle(exchange: HttpExchange): Unit =
          seenBody = String(exchange.getRequestBody.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
          val response =
            """{"jsonrpc":"2.0","id":"scalascript-evaluate","result":[{"validator":"spend:0","budget":{"memory":333,"cpu":444}}]}"""
          exchange.getResponseHeaders.add("Content-Type", "application/json")
          exchange.sendResponseHeaders(200, response.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
          val out = exchange.getResponseBody
          out.write(response.getBytes(java.nio.charset.StandardCharsets.UTF_8))
          out.close()
      )
      server.start()
      val url = s"http://127.0.0.1:${server.getAddress.getPort}/"
      val tx = BloxbeanClaimTxDraftBuilder.buildTransaction(plan)
      val results = Await.result(ScalusTxEvaluator.ogmiosHttp(url).evaluate(tx), 5.seconds)

      assert(seenBody.contains("evaluateTransaction"))
      assert(seenBody.contains("cbor"))
      assert(ScalusTxEvaluator.claimSpendExUnits(results).contains(ScalusExUnits(BigInt(333), BigInt(444))))
    finally
      server.stop(0)
  }
