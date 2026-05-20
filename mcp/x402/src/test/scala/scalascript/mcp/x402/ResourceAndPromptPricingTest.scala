package scalascript.mcp.x402

import java.util.Base64
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.x402.*

/** Phase 6: dispatcher applies the same -32402 / payment flow to
 *  `resources/read` and `prompts/get` as it does to `tools/call`,
 *  with kind-discriminating `resource:` / `prompt:` labels in the
 *  emitted requirements. */
class ResourceAndPromptPricingTest extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  private val price = ToolPrice(
    amount = BigInt(5_000),
    asset  = Assets.USDC_BASE,
    payTo  = "0x3333333333333333333333333333333333333333",
  )

  private class StubFac(result: VerifyResult) extends Facilitator:
    def verify(p: PaymentPayload, r: PaymentRequirements): Future[VerifyResult] = Future.successful(result)
    def settle(p: PaymentPayload, r: PaymentRequirements): Future[SettleResult] = Future.successful(SettleResult.Ok("0x0"))

  private val dispatcher = new Mcp402Dispatcher(new StubFac(VerifyResult.Ok))

  /** Synthesise a valid base64-encoded PaymentPayload header for a
   *  given amount (the stub facilitator accepts any payload). */
  private def buildHeader(amount: BigInt): String =
    val payload = ujson.Obj(
      "x402Version"   -> ujson.Num(1),
      "scheme"        -> ujson.Obj("type" -> ujson.Str("exact"), "amount" -> ujson.Str(amount.toString)),
      "network"       -> ujson.Str("Base"),
      "authorization" -> ujson.Obj(
        "from"        -> ujson.Str("0x1111111111111111111111111111111111111111"),
        "to"          -> ujson.Str(price.payTo),
        "value"       -> ujson.Str(amount.toString),
        "validAfter"  -> ujson.Str("0"),
        "validBefore" -> ujson.Str("9999999999"),
        "nonce"       -> ujson.Str("0x" + "ab" * 32),
      ),
      "signature"     -> ujson.Str("0x" + "cc" * 65),
    )
    Base64.getEncoder.encodeToString(payload.toString.getBytes("UTF-8"))

  // ── resources/read ────────────────────────────────────────────────────

  test("dispatchResource without payment → -32402 with resource: label + kind=resource") {
    val config = PricedResourceConfig(
      uri         = "data://premium/feed",
      description = "Curated premium news feed",
      price       = price,
      mimeType    = Some("application/json"),
    )
    val params = ujson.Obj("uri" -> ujson.Str(config.uri))
    val r      = Await.result(dispatcher.dispatchResource(config, params), 5.seconds)
    r match
      case Left(err) =>
        assert(err("code").num.toInt == Mcp402Protocol.PaymentRequiredCode)
        val req = err("data").obj("requirements").obj
        assert(req("resource").str == "resource:data://premium/feed")
        assert(req("kind").str     == "resource")
        assert(req("mimeType").str == "application/json")
      case Right(_) => fail("expected Left")
  }

  test("dispatchResource with valid payment → Right(VerifiedPayment)") {
    val config = PricedResourceConfig(
      uri         = "data://premium/feed",
      description = "feed",
      price       = price,
    )
    val params = ujson.Obj(
      "uri"   -> ujson.Str(config.uri),
      "_meta" -> ujson.Obj("x402" -> ujson.Obj("payment" -> ujson.Str(buildHeader(5_000)))),
    )
    val r = Await.result(dispatcher.dispatchResource(config, params), 5.seconds)
    r match
      case Right(v) =>
        assert(v.amount == BigInt(5_000))
        assert(v.to     == price.payTo)
      case Left(err) => fail(s"expected Right, got Left: $err")
  }

  // ── prompts/get ───────────────────────────────────────────────────────

  test("dispatchPrompt without payment → -32402 with prompt: label + kind=prompt") {
    val config = PricedPromptConfig(
      name        = "premium.summary",
      description = "Premium summarisation prompt template",
      price       = price,
    )
    val params = ujson.Obj("name" -> ujson.Str(config.name))
    val r      = Await.result(dispatcher.dispatchPrompt(config, params), 5.seconds)
    r match
      case Left(err) =>
        assert(err("code").num.toInt == Mcp402Protocol.PaymentRequiredCode)
        val req = err("data").obj("requirements").obj
        assert(req("resource").str == "prompt:premium.summary")
        assert(req("kind").str     == "prompt")
      case Right(_) => fail("expected Left")
  }

  test("dispatchPrompt with valid payment → Right(VerifiedPayment)") {
    val config = PricedPromptConfig(
      name        = "premium.summary",
      description = "summary",
      price       = price,
    )
    val params = ujson.Obj(
      "name"  -> ujson.Str(config.name),
      "_meta" -> ujson.Obj("x402" -> ujson.Obj("payment" -> ujson.Str(buildHeader(5_000)))),
    )
    val r = Await.result(dispatcher.dispatchPrompt(config, params), 5.seconds)
    r match
      case Right(v) =>
        assert(v.amount == BigInt(5_000))
      case Left(err) => fail(s"expected Right, got Left: $err")
  }

  // ── tools/call still works + `kind=tool` is emitted ──────────────────

  test("dispatchTool emits kind=tool in requirements") {
    val config = PricedToolConfig("premium.search", "search", price)
    val r      = Await.result(dispatcher.dispatchTool(config, ujson.Obj()), 5.seconds)
    r match
      case Left(err) =>
        val req = err("data").obj("requirements").obj
        assert(req("resource").str == "tool:premium.search")
        assert(req("kind").str     == "tool")
      case Right(_) => fail("expected Left")
  }

  test("legacy `dispatch(toolConfig, ...)` is still an alias for dispatchTool") {
    val config = PricedToolConfig("legacy.tool", "desc", price)
    val r      = Await.result(dispatcher.dispatch(config, ujson.Obj()), 5.seconds)
    assert(r.isLeft)
    r match
      case Left(err) => assert(err("data").obj("requirements").obj("kind").str == "tool")
      case _         => ()
  }

  // ── X402AutoPay treats all three kinds uniformly ─────────────────────

  test("X402AutoPay round-trips a paid resources/read end-to-end") {
    val config = PricedResourceConfig(
      uri         = "data://premium/feed",
      description = "feed",
      price       = price,
    )
    val server: (String, ujson.Value) => Future[ujson.Value] =
      (method, params) => method match
        case "resources/read" =>
          dispatcher.dispatchResource(config, params).flatMap {
            case Left(err) =>
              PaymentRequiredException.tryParse(err) match
                case Some(ex) => Future.failed(ex)
                case None     => Future.failed(new RuntimeException(s"unparseable: $err"))
            case Right(_) =>
              Future.successful(ujson.Obj("contents" -> ujson.Arr(ujson.Str("premium data"))))
          }
        case other => Future.failed(new RuntimeException(s"unsupported: $other"))

    val signer: PaymentSigner = (req) =>
      Future.successful(Some(buildHeader(req.scheme match {
        case PaymentScheme.Exact(a)              => a
        case PaymentScheme.Stream(_, _, _, maxA) => maxA
        case PaymentScheme.CardanoExact(love, _) => love
      })))
    val autopay = new X402AutoPay(signer, maxAmount = BigInt(100_000))
    val wrap    = autopay.wrap(server)

    val params = ujson.Obj("uri" -> ujson.Str(config.uri))
    val result = Await.result(wrap("resources/read", params), 5.seconds)
    assert(result.obj("contents").arr.head.str == "premium data")
  }
