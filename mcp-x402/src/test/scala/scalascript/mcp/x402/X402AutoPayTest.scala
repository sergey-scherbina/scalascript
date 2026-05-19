package scalascript.mcp.x402

import java.util.Base64
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.x402.*

/** Client-side `X402AutoPay` middleware tests. We pair the
 *  middleware with `Mcp402Dispatcher` directly (skipping the actual
 *  mcp-common transport) so the round-trip exercises real protocol
 *  semantics from both ends. */
class X402AutoPayTest extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  private val price = ToolPrice(
    amount = BigInt(10_000),
    asset  = Assets.USDC_BASE,
    payTo  = "0x2222222222222222222222222222222222222222",
  )
  private val config = PricedToolConfig(
    name        = "premium.search",
    description = "Premium semantic search",
    price       = price,
  )

  private class StubFacilitator(verify: VerifyResult) extends Facilitator:
    def verify(payload: PaymentPayload, req: PaymentRequirements): Future[VerifyResult] =
      Future.successful(verify)
    def settle(payload: PaymentPayload, req: PaymentRequirements): Future[SettleResult] =
      Future.successful(SettleResult.Ok("0xstubhash"))

  private val dispatcher = new Mcp402Dispatcher(new StubFacilitator(VerifyResult.Ok))

  /** Simulates a remote MCP server: priced tool guarded by the
   *  dispatcher. Returns the tool's result on a paid call; throws
   *  PaymentRequiredException on an unpaid call. */
  private def serverSend(toolName: String): (String, ujson.Value) => Future[ujson.Value] =
    (method, params) => method match
      case "tools/call" =>
        dispatcher.dispatch(config, params).flatMap {
          case Left(err) =>
            // Translate the JSON-RPC error into the typed exception
            // that the middleware expects.
            PaymentRequiredException.tryParse(err) match
              case Some(ex) => Future.failed(ex)
              case None     => Future.failed(new RuntimeException(s"unparseable error: $err"))
          case Right(_) =>
            // "Run" the tool — just echo the query.
            Future.successful(ujson.Obj(
              "result" -> ujson.Obj(
                "tool"  -> ujson.Str(toolName),
                "echo"  -> params.obj.get("arguments").map(_.obj.getOrElse("query", ujson.Null)).getOrElse(ujson.Null),
              ),
            ))
        }
      case other => Future.failed(new RuntimeException(s"unsupported: $other"))

  /** Tiny `PaymentSigner` that fakes a signed PaymentPayload header
   *  by base64-encoding a known-good shape — the dispatcher accepts
   *  it because we paired with a stub facilitator that always says
   *  Ok. Real signing is exercised by the wallet-side tests. */
  private class StubSigner(amount: BigInt) extends PaymentSigner:
    var calls = 0
    def signRequirements(req: PaymentRequirements): Future[Option[String]] =
      calls += 1
      val payload = ujson.Obj(
        "x402Version"   -> ujson.Num(1),
        "scheme"        -> ujson.Obj("type" -> ujson.Str("exact"), "amount" -> ujson.Str(amount.toString)),
        "network"       -> ujson.Str("Base"),
        "authorization" -> ujson.Obj(
          "from"        -> ujson.Str("0x1111111111111111111111111111111111111111"),
          "to"          -> ujson.Str(req.payTo),
          "value"       -> ujson.Str(amount.toString),
          "validAfter"  -> ujson.Str("0"),
          "validBefore" -> ujson.Str("9999999999"),
          "nonce"       -> ujson.Str("0x" + "ab" * 32),
        ),
        "signature"     -> ujson.Str("0x" + "cc" * 65),
      )
      Future.successful(Some(Base64.getEncoder.encodeToString(payload.toString.getBytes("UTF-8"))))

  // ── happy path ─────────────────────────────────────────────────────

  test("first call gets -32402 → middleware signs + retries → success") {
    val signer = new StubSigner(amount = 10_000)
    val pay    = new X402AutoPay(signer, maxAmount = BigInt(50_000))
    val wrap   = pay.wrap(serverSend("premium.search"))
    val params = ujson.Obj(
      "name"      -> ujson.Str("premium.search"),
      "arguments" -> ujson.Obj("query" -> ujson.Str("hello")),
    )
    val result = Await.result(wrap("tools/call", params), 5.seconds)
    val tool   = result.obj("result").obj
    assert(tool("tool").str == "premium.search")
    assert(tool("echo").str == "hello")
    assert(signer.calls == 1, "signer should have been invoked exactly once")
  }

  // ── over-budget refusal ────────────────────────────────────────────

  test("middleware refuses to sign when amount > maxAmount") {
    val signer = new StubSigner(amount = 10_000)
    val pay    = new X402AutoPay(signer, maxAmount = BigInt(5_000))   // < 10k
    val wrap   = pay.wrap(serverSend("premium.search"))
    val params = ujson.Obj(
      "name"      -> ujson.Str("premium.search"),
      "arguments" -> ujson.Obj("query" -> ujson.Str("hi")),
    )
    val ex = intercept[PaymentRequiredException] {
      Await.result(wrap("tools/call", params), 5.seconds)
    }
    assert(ex.requirements.payTo == price.payTo)
    assert(signer.calls == 0, "signer must NOT be invoked when over budget")
  }

  // ── onCharge hook is fired ─────────────────────────────────────────

  test("onCharge hook fires with (requirements, header) on a successful charge") {
    var charged: Option[(PaymentRequirements, String)] = None
    val signer = new StubSigner(amount = 10_000)
    val pay = new X402AutoPay(
      signer,
      maxAmount = BigInt(50_000),
      onCharge  = (r, h) => charged = Some((r, h)),
    )
    val wrap = pay.wrap(serverSend("premium.search"))
    Await.result(wrap("tools/call", ujson.Obj(
      "name"      -> ujson.Str("premium.search"),
      "arguments" -> ujson.Obj(),
    )), 5.seconds)
    assert(charged.isDefined)
    assert(charged.get._1.payTo == price.payTo)
    assert(charged.get._2.nonEmpty)
  }

  // ── signer refusal ─────────────────────────────────────────────────

  test("signer returning None surfaces the original 402 to the caller") {
    val refusing: PaymentSigner = new PaymentSigner:
      def signRequirements(req: PaymentRequirements): Future[Option[String]] = Future.successful(None)
    val pay = new X402AutoPay(refusing, maxAmount = BigInt(1_000_000))
    val ex = intercept[PaymentRequiredException] {
      Await.result(pay.wrap(serverSend("premium.search"))("tools/call",
        ujson.Obj("arguments" -> ujson.Obj())), 5.seconds)
    }
    assert(ex.requirements.payTo == price.payTo)
  }

  // ── non-payment error passes through ───────────────────────────────

  test("non-payment errors pass through untouched") {
    val signer = new StubSigner(amount = 10_000)
    val pay    = new X402AutoPay(signer, maxAmount = BigInt(50_000))
    val rawSend: (String, ujson.Value) => Future[ujson.Value] = (_, _) =>
      Future.failed(new RuntimeException("upstream timeout"))
    val ex = intercept[RuntimeException] {
      Await.result(pay.wrap(rawSend)("tools/call", ujson.Obj()), 5.seconds)
    }
    assert(ex.getMessage.contains("upstream timeout"))
    assert(signer.calls == 0)
  }

  // ── PaymentRequiredException.tryParse parses real dispatcher output ──

  test("PaymentRequiredException.tryParse round-trips a dispatcher-emitted 402") {
    val raw = Await.result(dispatcher.dispatch(config, ujson.Obj()), 5.seconds) match
      case Left(err) => err
      case _         => fail("expected Left")
    val parsed = PaymentRequiredException.tryParse(raw)
    assert(parsed.isDefined)
    assert(parsed.get.requirements.payTo == price.payTo)
    assert(parsed.get.requirements.scheme == PaymentScheme.Exact(BigInt(10_000)))
  }

  test("PaymentRequiredException.tryParse returns None for non-402 errors") {
    val other = ujson.Obj("code" -> ujson.Num(-32601), "message" -> ujson.Str("method not found"))
    assert(PaymentRequiredException.tryParse(other).isEmpty)
  }
