package scalascript.mcp.x402

import java.util.Base64
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.x402.*

/** Phase 8: stream-priced MCP tools — server advertises
 *  PaymentScheme.Stream in requirements, client pre-authorises
 *  maxAmount, server reports actual usage via _meta.x402.streamCharge,
 *  client middleware tracks the running total via onStreamCharge. */
class StreamPricingTest extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  private val streamPrice = Pricing.Stream(
    ratePerUnit = BigInt(10),
    unitName    = "token",
    maxUnits    = 10_000,
    maxAmount   = BigInt(100_000),
    asset       = Assets.USDC_BASE,
    payTo       = "0x4444444444444444444444444444444444444444",
  )

  private val streamToolConfig = PricedToolConfig(
    name        = "premium.llm",
    description = "LLM proxy priced per output token",
    price       = streamPrice,
  )

  private class StubFac(verifyResult: VerifyResult = VerifyResult.Ok) extends Facilitator:
    def verify(p: PaymentPayload, r: PaymentRequirements): Future[VerifyResult] = Future.successful(verifyResult)
    def settle(p: PaymentPayload, r: PaymentRequirements): Future[SettleResult] = Future.successful(SettleResult.Ok("0x0"))

  /** Synthesise a base64-encoded PaymentPayload with a Stream
   *  scheme — the maxAmount cap is what the client pre-authorises. */
  private def buildStreamHeader(maxAmount: BigInt): String =
    val payload = ujson.Obj(
      "x402Version"   -> ujson.Num(1),
      "scheme"        -> ujson.Obj(
        "type"        -> ujson.Str("stream"),
        "ratePerUnit" -> ujson.Str(streamPrice.ratePerUnit.toString),
        "unitName"    -> ujson.Str(streamPrice.unitName),
        "maxUnits"    -> ujson.Num(streamPrice.maxUnits.toDouble),
        "maxAmount"   -> ujson.Str(maxAmount.toString),
      ),
      "network"       -> ujson.Str("Base"),
      "authorization" -> ujson.Obj(
        "from"        -> ujson.Str("0x1111111111111111111111111111111111111111"),
        "to"          -> ujson.Str(streamPrice.payTo),
        "value"       -> ujson.Str(maxAmount.toString),
        "validAfter"  -> ujson.Str("0"),
        "validBefore" -> ujson.Str("9999999999"),
        "nonce"       -> ujson.Str("0x" + "ab" * 32),
      ),
      "signature"     -> ujson.Str("0x" + "cc" * 65),
    )
    Base64.getEncoder.encodeToString(payload.toString.getBytes("UTF-8"))

  // ── server emits Stream scheme in requirements ────────────────────────

  test("dispatchTool with Stream pricing emits scheme=stream in -32402 requirements") {
    val dispatcher = new Mcp402Dispatcher(new StubFac)
    val r          = Await.result(dispatcher.dispatchTool(streamToolConfig, ujson.Obj()), 5.seconds)
    r match
      case Left(err) =>
        val req    = err("data").obj("requirements").obj
        val scheme = req("scheme").obj
        assert(scheme("type").str        == "stream")
        assert(scheme("ratePerUnit").str == "10")
        assert(scheme("unitName").str    == "token")
        assert(scheme("maxUnits").num    == 10_000)
        assert(scheme("maxAmount").str   == "100000")
      case Right(_) => fail("expected Left")
  }

  test("dispatchTool accepts a Stream-scheme payment") {
    val dispatcher = new Mcp402Dispatcher(new StubFac)
    val params = ujson.Obj(
      "_meta" -> ujson.Obj("x402" -> ujson.Obj("payment" -> ujson.Str(buildStreamHeader(100_000)))),
    )
    val r = Await.result(dispatcher.dispatchTool(streamToolConfig, params), 5.seconds)
    r match
      case Right(v) =>
        assert(v.amount == BigInt(100_000))  // the pre-authorised cap
        assert(v.to     == streamPrice.payTo)
      case Left(err) => fail(s"expected Right, got Left: $err")
  }

  // ── _meta.x402.streamCharge round-trip ────────────────────────────────

  test("Mcp402Protocol.streamChargeMeta + parseStreamCharge round-trip") {
    val meta = Mcp402Protocol.streamChargeMeta(usedUnits = 4567, unitName = "token", totalAmount = BigInt(45_670))
    val parsed = Mcp402Protocol.parseStreamCharge(meta)
    assert(parsed.contains(StreamCharge(usedUnits = 4567, unitName = "token", totalAmount = BigInt(45_670))))
  }

  test("parseStreamCharge returns None when streamCharge absent") {
    assert(Mcp402Protocol.parseStreamCharge(ujson.Obj()).isEmpty)
    assert(Mcp402Protocol.parseStreamCharge(ujson.Obj("x402" -> ujson.Obj())).isEmpty)
  }

  // ── X402AutoPay tracks running totals via onStreamCharge ──────────────

  test("X402AutoPay reports streamCharge on response via onStreamCharge hook") {
    val dispatcher = new Mcp402Dispatcher(new StubFac)
    val server: (String, ujson.Value) => Future[ujson.Value] =
      (method, params) => method match
        case "tools/call" =>
          dispatcher.dispatchTool(streamToolConfig, params).flatMap {
            case Left(err) =>
              PaymentRequiredException.tryParse(err) match
                case Some(ex) => Future.failed(ex)
                case None     => Future.failed(new RuntimeException(s"unparseable: $err"))
            case Right(_) =>
              // Simulate a tool that consumed 1234 tokens.
              val resp = ujson.Obj(
                "result" -> ujson.Obj("tokens" -> ujson.Arr(ujson.Str("foo"), ujson.Str("bar"))),
                "_meta"  -> Mcp402Protocol.streamChargeMeta(
                  usedUnits   = 1234,
                  unitName    = "token",
                  totalAmount = BigInt(12_340),
                ),
              )
              Future.successful(resp)
          }
        case other => Future.failed(new RuntimeException(s"unsupported: $other"))

    val signer: PaymentSigner = _ => Future.successful(Some(buildStreamHeader(100_000)))
    val tallied = scala.collection.mutable.ArrayBuffer.empty[StreamCharge]
    val autopay = new X402AutoPay(
      signer         = signer,
      maxAmount      = BigInt(1_000_000),
      onStreamCharge = ch => tallied += ch,
    )
    val result = Await.result(autopay.wrap(server)("tools/call",
      ujson.Obj(
        "name"      -> ujson.Str("premium.llm"),
        "arguments" -> ujson.Obj("prompt" -> ujson.Str("hi")),
      ),
    ), 5.seconds)
    assert(result.obj("result").obj("tokens").arr.size == 2)
    assert(tallied.size == 1)
    assert(tallied.head.usedUnits   == 1234)
    assert(tallied.head.unitName    == "token")
    assert(tallied.head.totalAmount == BigInt(12_340))
  }

  // ── budget tracker example via onStreamCharge ─────────────────────────

  test("onStreamCharge can accumulate running totals across multiple calls") {
    val dispatcher = new Mcp402Dispatcher(new StubFac)
    var tokensUsed: Long       = 0L
    var amountSpent: BigInt    = BigInt(0)
    val autopay = new X402AutoPay(
      signer         = _ => Future.successful(Some(buildStreamHeader(100_000))),
      maxAmount      = BigInt(1_000_000),
      onStreamCharge = ch =>
        tokensUsed  += ch.usedUnits
        amountSpent += ch.totalAmount,
    )
    val server: (String, ujson.Value) => Future[ujson.Value] = (_, params) =>
      dispatcher.dispatchTool(streamToolConfig, params).flatMap {
        case Left(err) => Future.failed(PaymentRequiredException.tryParse(err).get)
        case Right(_)  =>
          Future.successful(ujson.Obj(
            "result" -> ujson.Obj(),
            "_meta"  -> Mcp402Protocol.streamChargeMeta(500, "token", BigInt(5_000)),
          ))
      }
    val wrap = autopay.wrap(server)
    Await.result(wrap("tools/call", ujson.Obj()), 5.seconds)
    Await.result(wrap("tools/call", ujson.Obj()), 5.seconds)
    Await.result(wrap("tools/call", ujson.Obj()), 5.seconds)
    assert(tokensUsed  == 1500L)
    assert(amountSpent == BigInt(15_000))
  }

  // ── ToolPrice backwards-compatibility ─────────────────────────────────

  test("ToolPrice(amount, asset, payTo) still works (alias for Pricing.Exact)") {
    val p: ToolPrice = ToolPrice(BigInt(123), Assets.USDC_BASE, "0xabc")
    assert(p.amount    == BigInt(123))
    assert(p.maxAmount == BigInt(123))
    // It IS a Pricing.Exact at the type level
    val asPricing: Pricing = p
    assert(asPricing.isInstanceOf[Pricing.Exact])
  }
