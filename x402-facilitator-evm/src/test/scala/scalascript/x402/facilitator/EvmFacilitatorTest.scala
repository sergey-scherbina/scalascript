package scalascript.x402.facilitator

import scalascript.x402.*
import scalascript.evm.*
import scala.concurrent.duration.Duration
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import ExecutionContext.Implicits.global

class EvmFacilitatorTest extends AnyFunSuite:

  private val testReq = PaymentRequirements(
    scheme      = PaymentScheme.Exact(1_000_000L),
    network     = Network.Base,
    asset       = Assets.USDC_BASE,
    payTo       = "0xpayto",
    resource    = "/api/premium",
    description = "Test",
  )

  private def makePayload(
    from:        String  = "0xfrom",
    to:          String  = "0xpayto",
    value:       BigInt  = BigInt(1_000_000),
    validBefore: BigInt  = BigInt(9_999_999_999L),
  ) =
    PaymentPayload(
      scheme        = PaymentScheme.Exact(value),
      network       = Network.Base,
      authorization = TransferAuthorization(
        from        = from,
        to          = to,
        value       = value,
        validAfter  = BigInt(0),
        validBefore = validBefore,
        nonce       = "0x" + "ab" * 32,
      ),
      signature     = "0x" + "cc" * 65,
    )

  private def mockEvm(balance: BigInt): EvmClient = new EvmClient:
    def blockNumber(): Future[BigInt]                                     = ???
    def getBalance(address: String): Future[BigInt]                       = ???
    def getCode(address: String): Future[String]                          = ???
    def erc20Balance(token: String, address: String): Future[BigInt]      = Future.successful(balance)
    def erc20Allowance(t: String, o: String, s: String): Future[BigInt]   = ???
    def erc20Decimals(token: String): Future[Int]                         = ???
    def erc20Symbol(token: String): Future[String]                        = ???
    def getTransaction(hash: String): Future[Option[EvmTransaction]]      = ???
    def getReceipt(hash: String): Future[Option[EvmReceipt]]              = ???
    def waitForReceipt(hash: String, timeout: Duration): Future[EvmReceipt] = ???
    def call(to: String, data: String): Future[String]                    = ???
    def rpc(method: String, params: ujson.Value*): Future[ujson.Value]    = ???

  // ── Expired authorization ─────────────────────────────────────────────────────

  test("verify: expired validBefore → Fail") {
    val payload = makePayload(validBefore = BigInt(1))
    val fac     = EvmFacilitator(mockEvm(BigInt(1_000_000)))
    val result  = Await.result(fac.verify(payload, testReq), 5.seconds)
    result match
      case VerifyResult.Fail(reason) => assert(reason.contains("expired"))
      case _                         => fail("expected Fail")
  }

  // ── Destination mismatch ──────────────────────────────────────────────────────

  test("verify: wrong payTo → Fail") {
    val payload = makePayload(to = "0xwrongaddress")
    val fac     = EvmFacilitator(mockEvm(BigInt(1_000_000)))
    val result  = Await.result(fac.verify(payload, testReq), 5.seconds)
    result match
      case VerifyResult.Fail(reason) => assert(reason.contains("mismatch"))
      case _                         => fail("expected Fail")
  }

  // ── Insufficient balance ──────────────────────────────────────────────────────

  test("verify: balance < required value → Fail") {
    val payload = makePayload(value = BigInt(1_000_000))
    val fac     = EvmFacilitator(mockEvm(BigInt(500_000)))
    val result  = Await.result(fac.verify(payload, testReq), 5.seconds)
    result match
      case VerifyResult.Fail(reason) => assert(reason.contains("Insufficient"))
      case _                         => fail("expected Fail")
  }

  // ── Sufficient balance ────────────────────────────────────────────────────────

  test("verify: balance >= required value → Ok") {
    val payload = makePayload()
    val fac     = EvmFacilitator(mockEvm(BigInt(2_000_000)))
    val result  = Await.result(fac.verify(payload, testReq), 5.seconds)
    assert(result == VerifyResult.Ok)
  }

  test("verify: exact balance match → Ok") {
    val payload = makePayload()
    val fac     = EvmFacilitator(mockEvm(BigInt(1_000_000)))
    val result  = Await.result(fac.verify(payload, testReq), 5.seconds)
    assert(result == VerifyResult.Ok)
  }

  // ── EVM error ─────────────────────────────────────────────────────────────────

  test("verify: EVM RPC error → Fail") {
    val failEvm = new EvmClient:
      def blockNumber(): Future[BigInt]                                     = ???
      def getBalance(address: String): Future[BigInt]                       = ???
      def getCode(address: String): Future[String]                          = ???
      def erc20Balance(token: String, address: String): Future[BigInt]      = Future.failed(RuntimeException("RPC unavailable"))
      def erc20Allowance(t: String, o: String, s: String): Future[BigInt]   = ???
      def erc20Decimals(token: String): Future[Int]                         = ???
      def erc20Symbol(token: String): Future[String]                        = ???
      def getTransaction(hash: String): Future[Option[EvmTransaction]]      = ???
      def getReceipt(hash: String): Future[Option[EvmReceipt]]              = ???
      def waitForReceipt(hash: String, timeout: Duration): Future[EvmReceipt] = ???
      def call(to: String, data: String): Future[String]                    = ???
      def rpc(method: String, params: ujson.Value*): Future[ujson.Value]    = ???
    val payload = makePayload()
    val fac     = EvmFacilitator(failEvm)
    val result  = Await.result(fac.verify(payload, testReq), 5.seconds)
    result match
      case VerifyResult.Fail(reason) => assert(reason.contains("RPC unavailable"))
      case _                         => fail("expected Fail")
  }

  // ── Settle: no settler → Ok with zeroed hash ─────────────────────────────────

  test("settle: no settler configured → Ok(zeroed hash)") {
    val fac    = EvmFacilitator(mockEvm(BigInt(1_000_000)))
    val result = Await.result(fac.settle(makePayload(), testReq), 5.seconds)
    result match
      case SettleResult.Ok(hash) => assert(hash.startsWith("0x") && hash.length > 2)
      case _                     => fail("expected Ok")
  }

  // ── Settle: custom settler ────────────────────────────────────────────────────

  test("settle: custom settler is called") {
    var called = false
    val settler = (_: PaymentPayload, _: PaymentRequirements) =>
      called = true
      Future.successful(SettleResult.Ok("0xmytxhash"))
    val fac    = EvmFacilitator.withSettler(mockEvm(BigInt(1_000_000)), settler)
    val result = Await.result(fac.settle(makePayload(), testReq), 5.seconds)
    assert(called)
    result match
      case SettleResult.Ok(hash) => assert(hash == "0xmytxhash")
      case _                     => fail("expected Ok")
  }

  test("settle: settler failure propagates") {
    val settler = (_: PaymentPayload, _: PaymentRequirements) =>
      Future.successful(SettleResult.Fail("chain down"))
    val fac    = EvmFacilitator.withSettler(mockEvm(BigInt(1_000_000)), settler)
    val result = Await.result(fac.settle(makePayload(), testReq), 5.seconds)
    result match
      case SettleResult.Fail(reason) => assert(reason.contains("chain down"))
      case _                         => fail("expected Fail")
  }
