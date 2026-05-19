package scalascript.x402.facilitator

import scalascript.x402.*
import scalascript.coinbase.*
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import ExecutionContext.Implicits.global

class CoinbaseFacilitatorTest extends AnyFunSuite:

  private def makePayload(nonce: String = "0x" + "ab" * 32) =
    PaymentPayload(
      scheme        = PaymentScheme.Exact(1_000_000L),
      network       = Network.Base,
      authorization = TransferAuthorization(
        from        = "0xfrom",
        to          = "0xpayTo",
        value       = BigInt(1_000_000),
        validAfter  = BigInt(0),
        validBefore = BigInt(9_999_999_999L),
        nonce       = nonce,
      ),
      signature     = "0x" + "cc" * 65,
    )

  private val testReq = PaymentRequirements(
    scheme      = PaymentScheme.Exact(1_000_000L),
    network     = Network.Base,
    asset       = Assets.USDC_BASE,
    payTo       = "0xpayTo",
    resource    = "/api/premium",
    description = "Test",
  )

  private def mockCoinbaseClient(verifyResponse: ujson.Value, settleResponse: ujson.Value): CoinbaseClient =
    new CoinbaseClient:
      def trade: CoinbaseTrade = ???
      def cdp:   CoinbaseCdp   = ???
      val x402: CoinbaseFacilitator = new CoinbaseFacilitator:
        def verify(payload: ujson.Value): Future[ujson.Value] = Future.successful(verifyResponse)
        def settle(payload: ujson.Value): Future[ujson.Value] = Future.successful(settleResponse)

  test("verify: isValid=true → VerifyResult.Ok") {
    val client = mockCoinbaseClient(
      ujson.Obj("isValid" -> true),
      ujson.Obj("txHash"  -> "0xtx"),
    )
    val fac    = CoinbaseFacilitator(client)
    val result = Await.result(fac.verify(makePayload(), testReq), 5.seconds)
    assert(result == VerifyResult.Ok)
  }

  test("verify: isValid=false → VerifyResult.Fail") {
    val client = mockCoinbaseClient(
      ujson.Obj("isValid" -> false, "error" -> "bad sig"),
      ujson.Obj(),
    )
    val fac    = CoinbaseFacilitator(client)
    val result = Await.result(fac.verify(makePayload(), testReq), 5.seconds)
    result match
      case VerifyResult.Fail(reason) => assert(reason.contains("bad sig"))
      case _                         => fail("expected Fail")
  }

  test("verify: API exception → VerifyResult.Fail") {
    val failing = new CoinbaseClient:
      def trade: CoinbaseTrade = ???
      def cdp:   CoinbaseCdp   = ???
      val x402: CoinbaseFacilitator = new CoinbaseFacilitator:
        def verify(payload: ujson.Value): Future[ujson.Value] =
          Future.failed(RuntimeException("network error"))
        def settle(payload: ujson.Value): Future[ujson.Value] = ???
    val fac    = CoinbaseFacilitator(failing)
    val result = Await.result(fac.verify(makePayload(), testReq), 5.seconds)
    result match
      case VerifyResult.Fail(reason) => assert(reason.contains("network error"))
      case _                         => fail("expected Fail")
  }

  test("settle: txHash present → SettleResult.Ok") {
    val client = mockCoinbaseClient(
      ujson.Obj("isValid" -> true),
      ujson.Obj("txHash"  -> "0xdeadbeef"),
    )
    val fac    = CoinbaseFacilitator(client)
    val result = Await.result(fac.settle(makePayload(), testReq), 5.seconds)
    result match
      case SettleResult.Ok(hash) => assert(hash == "0xdeadbeef")
      case _                     => fail("expected Ok")
  }

  test("settle: success=true without txHash → SettleResult.Ok with zeroed hash") {
    val client = mockCoinbaseClient(
      ujson.Obj("isValid" -> true),
      ujson.Obj("success" -> true),
    )
    val fac    = CoinbaseFacilitator(client)
    val result = Await.result(fac.settle(makePayload(), testReq), 5.seconds)
    result match
      case SettleResult.Ok(hash) => assert(hash.startsWith("0x"))
      case _                     => fail("expected Ok")
  }

  test("settle: API exception → SettleResult.Fail") {
    val failing = new CoinbaseClient:
      def trade: CoinbaseTrade = ???
      def cdp:   CoinbaseCdp   = ???
      val x402: CoinbaseFacilitator = new CoinbaseFacilitator:
        def verify(payload: ujson.Value): Future[ujson.Value] = ???
        def settle(payload: ujson.Value): Future[ujson.Value] =
          Future.failed(RuntimeException("timeout"))
    val fac    = CoinbaseFacilitator(failing)
    val result = Await.result(fac.settle(makePayload(), testReq), 5.seconds)
    result match
      case SettleResult.Fail(reason) => assert(reason.contains("timeout"))
      case _                         => fail("expected Fail")
  }
