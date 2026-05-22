package scalascript.mcp.x402

import java.util.Base64
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.x402.*

/** Server-side x402-over-MCP dispatcher tests. Drives the dispatcher
 *  directly with mocked `Facilitator` responses — the integration
 *  with mcp-common's tools/call dispatch is a thin wiring layer
 *  that we'll exercise separately. */
class Mcp402DispatcherTest extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  private val price = ToolPrice(
    amount = BigInt(10_000),                  // 0.01 USDC (6 decimals)
    asset  = Assets.USDC_BASE,
    payTo  = "0x2222222222222222222222222222222222222222",
  )

  private val config = PricedToolConfig(
    name        = "premium.search",
    description = "Premium semantic search across our corpus",
    price       = price,
  )

  private class StubFacilitator(verifyResult: VerifyResult) extends Facilitator:
    var verifyCalls = 0
    def verify(payload: PaymentPayload, req: PaymentRequirements): Future[VerifyResult] =
      verifyCalls += 1
      Future.successful(verifyResult)
    def settle(payload: PaymentPayload, req: PaymentRequirements): Future[SettleResult] =
      Future.successful(SettleResult.Ok("0x" + "0" * 64))

  // ── unpaid call → -32402 ──────────────────────────────────────────────

  test("dispatch without _meta.x402.payment returns -32402 with PaymentRequirements") {
    val fac = new StubFacilitator(VerifyResult.Ok)
    val dispatcher = new Mcp402Dispatcher(fac)
    val params = ujson.Obj(
      "name"      -> ujson.Str("premium.search"),
      "arguments" -> ujson.Obj("query" -> ujson.Str("hello")),
    )
    val result = Await.result(dispatcher.dispatch(config, params), 5.seconds)
    result match
      case Left(err) =>
        assert(err("code").num.toInt == Mcp402Protocol.PaymentRequiredCode)
        val data = err("data").obj
        assert(data("x402Version").num.toInt == 1)
        val req = data("requirements").obj
        assert(req("payTo").str == price.payTo)
        assert(req("scheme").obj("amount").str == "10000")
        assert(req("asset").obj("address").str == price.asset.address)
        assert(fac.verifyCalls == 0)
      case Right(_) => fail("expected Left -32402, got Right")
  }

  // ── valid payment → Right ─────────────────────────────────────────────

  test("dispatch with valid payment + facilitator Ok returns Right(VerifiedPayment)") {
    val fac        = new StubFacilitator(VerifyResult.Ok)
    val dispatcher = new Mcp402Dispatcher(fac)
    val header     = buildHeader(amount = 10_000, from = "0xf1", to = price.payTo)
    val params = ujson.Obj(
      "name"      -> ujson.Str("premium.search"),
      "arguments" -> ujson.Obj("query" -> ujson.Str("hi")),
      "_meta"     -> ujson.Obj("x402" -> ujson.Obj("payment" -> ujson.Str(header))),
    )
    val result = Await.result(dispatcher.dispatch(config, params), 5.seconds)
    result match
      case Right(v) =>
        assert(v.amount == BigInt(10_000))
        assert(v.from.equalsIgnoreCase("0xf1"))
        assert(v.to.equalsIgnoreCase(price.payTo))
        assert(v.rawHeader == header)
        assert(fac.verifyCalls == 1)
      case Left(err) => fail(s"expected Right, got Left: $err")
  }

  // ── facilitator Fail → -32402 with reason ────────────────────────────

  test("dispatch with facilitator Fail returns -32402 carrying the reason") {
    val fac        = new StubFacilitator(VerifyResult.Fail("Insufficient balance"))
    val dispatcher = new Mcp402Dispatcher(fac)
    val header     = buildHeader(amount = 10_000)
    val params = ujson.Obj(
      "_meta" -> ujson.Obj("x402" -> ujson.Obj("payment" -> ujson.Str(header))),
    )
    val result = Await.result(dispatcher.dispatch(config, params), 5.seconds)
    result match
      case Left(err) =>
        assert(err("code").num.toInt == Mcp402Protocol.PaymentRequiredCode)
        assert(err("message").str.contains("Insufficient balance"))
        assert(fac.verifyCalls == 1)
      case Right(_) => fail("expected Left")
  }

  // ── malformed base64 / JSON → -32402 ─────────────────────────────────

  test("dispatch with malformed base64 header surfaces a -32402 error") {
    val fac        = new StubFacilitator(VerifyResult.Ok)
    val dispatcher = new Mcp402Dispatcher(fac)
    val params = ujson.Obj(
      "_meta" -> ujson.Obj("x402" -> ujson.Obj("payment" -> ujson.Str("not-base64!!!"))),
    )
    val result = Await.result(dispatcher.dispatch(config, params), 5.seconds)
    result match
      case Left(err) =>
        assert(err("code").num.toInt == Mcp402Protocol.PaymentRequiredCode)
        assert(err("message").str.toLowerCase.contains("malformed"))
        assert(fac.verifyCalls == 0)
      case Right(_) => fail("expected Left")
  }

  // ── PaymentScope renders in requirements ─────────────────────────────

  test("session-scoped tool surfaces oneShot=false + ttlSec in requirements") {
    val sessionConfig = config.copy(scope = PaymentScope.session(ttlSec = 600))
    val fac           = new StubFacilitator(VerifyResult.Ok)
    val dispatcher    = new Mcp402Dispatcher(fac)
    val result = Await.result(dispatcher.dispatch(sessionConfig, ujson.Obj()), 5.seconds)
    result match
      case Left(err) =>
        val scope = err("data").obj("requirements").obj("scope").obj
        assert(!scope("oneShot").bool)
        assert(scope("ttlSec").num.toInt == 600)
      case Right(_) => fail("expected Left")
  }

  // ── util ──────────────────────────────────────────────────────────────

  /** Build the base64-encoded PaymentPayload header an x402 client
   *  would send. Mirrors x402-client.PayloadBuilder.encode. */
  private def buildHeader(
    amount: BigInt,
    from:   String = "0x1111111111111111111111111111111111111111",
    to:     String = "0x2222222222222222222222222222222222222222",
  ): String =
    val payload = ujson.Obj(
      "x402Version"   -> ujson.Num(1),
      "scheme"        -> ujson.Obj("type" -> ujson.Str("exact"), "amount" -> ujson.Str(amount.toString)),
      "network"       -> ujson.Str("Base"),
      "authorization" -> ujson.Obj(
        "from"        -> ujson.Str(from),
        "to"          -> ujson.Str(to),
        "value"       -> ujson.Str(amount.toString),
        "validAfter"  -> ujson.Str("0"),
        "validBefore" -> ujson.Str("9999999999"),
        "nonce"       -> ujson.Str("0x" + "ab" * 32),
      ),
      "signature"     -> ujson.Str("0x" + "cc" * 65),
    )
    Base64.getEncoder.encodeToString(payload.toString.getBytes("UTF-8"))
