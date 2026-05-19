package scalascript.x402.client

import scalascript.x402.*
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import ExecutionContext.Implicits.global
import java.util.Base64

class X402ClientTest extends AnyFunSuite:

  private val testWallet = Wallets.privateKey("0x" + "ab" * 32, Network.Base)

  private val testReq = PaymentRequirements(
    scheme      = PaymentScheme.Exact(1_000_000L),
    network     = Network.Base,
    asset       = Assets.USDC_BASE,
    payTo       = "0xpayTo",
    resource    = "/api/premium",
    description = "Test access",
  )

  private def req402Body: String =
    ujson.Obj(
      "error" -> "Payment Required",
      "requirements" -> ujson.Obj(
        "x402Version"       -> 1,
        "scheme"            -> ujson.Obj("type" -> "exact", "amount" -> "1000000"),
        "network"           -> "Base",
        "chainId"           -> 8453,
        "asset"             -> ujson.Obj("address" -> Assets.USDC_BASE.address, "symbol" -> "USDC", "decimals" -> 6),
        "payTo"             -> "0xpayTo",
        "resource"          -> "/api/premium",
        "description"       -> "Test access",
        "maxTimeoutSeconds" -> 300,
      ),
    ).toString

  // ── Wallet ───────────────────────────────────────────────────────────────────

  test("PrivateKeyWallet address is deterministic") {
    val w1 = Wallets.privateKey("0x" + "aa" * 32, Network.Base)
    val w2 = Wallets.privateKey("0x" + "aa" * 32, Network.Base)
    assert(w1.address == w2.address)
  }

  test("PrivateKeyWallet address starts with 0x") {
    assert(testWallet.address.startsWith("0x"))
  }

  test("PrivateKeyWallet.network matches construction arg") {
    assert(testWallet.network == Network.Base)
  }

  test("signEip712 returns a hex string") {
    val domain = Eip712Domain("USD Coin", "2", 8453, "0xcontract")
    val sig    = Await.result(testWallet.signEip712(domain, Map.empty, Map.empty), 5.seconds)
    assert(sig.startsWith("0x"))
  }

  // ── Wallets.envKey ───────────────────────────────────────────────────────────

  test("Wallets.envKey throws when env var missing") {
    intercept[RuntimeException] {
      Wallets.envKey("__NO_SUCH_ENV_VAR__X402__", Network.Base)
    }
  }

  // ── PayloadBuilder ───────────────────────────────────────────────────────────

  test("PayloadBuilder.build produces valid payload") {
    val payload = Await.result(PayloadBuilder.build(testWallet, testReq), 5.seconds)
    assert(payload.network       == Network.Base)
    assert(payload.authorization.to == "0xpayTo")
    assert(payload.authorization.value == BigInt(1_000_000))
    assert(payload.signature.startsWith("0x"))
  }

  test("PayloadBuilder.build generates unique nonces") {
    val p1 = Await.result(PayloadBuilder.build(testWallet, testReq), 5.seconds)
    val p2 = Await.result(PayloadBuilder.build(testWallet, testReq), 5.seconds)
    assert(p1.authorization.nonce != p2.authorization.nonce)
  }

  test("PayloadBuilder.encode produces base64 JSON") {
    val auth = TransferAuthorization("0xf", "0xt", BigInt(1), BigInt(0), BigInt(1), "0x00")
    val p    = PaymentPayload(scheme = PaymentScheme.Exact(1L), network = Network.Base,
                              authorization = auth, signature = "0xsig")
    val enc  = PayloadBuilder.encode(p)
    val json = String(Base64.getDecoder.decode(enc), "UTF-8")
    val j    = ujson.read(json)
    assert(j("network").str == "Base")
    assert(j("signature").str == "0xsig")
  }

  // ── X402HttpClient: no-402 passthrough ──────────────────────────────────────

  test("X402HttpClient: 200 response passes through without intercepting") {
    var callCount = 0
    val backend: (String, String, Map[String, String], String) => Future[HttpResponse] =
      (_, _, _, _) =>
        callCount += 1
        Future.successful(HttpResponse(200, Map.empty, "data"))
    val client = X402Client(testWallet, BigInt(5_000_000), backend)
    val resp   = Await.result(client.get("http://example.com/api"), 5.seconds)
    assert(resp.status    == 200)
    assert(resp.body      == "data")
    assert(callCount      == 1)
  }

  // ── X402HttpClient: 402 → sign → retry ──────────────────────────────────────

  test("X402HttpClient: 402 triggers payment and retry → 200") {
    var callCount = 0
    val backend: (String, String, Map[String, String], String) => Future[HttpResponse] =
      (_, _, headers, _) =>
        callCount += 1
        if headers.contains("X-Payment") then Future.successful(HttpResponse(200, Map.empty, "paid data"))
        else Future.successful(HttpResponse(402, Map("Content-Type" -> "application/json"), req402Body))
    val client = X402Client(testWallet, BigInt(5_000_000), backend)
    val resp   = Await.result(client.get("http://example.com/api"), 5.seconds)
    assert(resp.status == 200)
    assert(resp.body   == "paid data")
    assert(callCount   == 2)
  }

  test("X402HttpClient: retry request includes X-Payment header") {
    var paymentHeader: Option[String] = None
    val backend: (String, String, Map[String, String], String) => Future[HttpResponse] =
      (_, _, headers, _) =>
        paymentHeader = headers.get("X-Payment")
        if headers.contains("X-Payment") then Future.successful(HttpResponse(200, Map.empty, "ok"))
        else Future.successful(HttpResponse(402, Map.empty, req402Body))
    val client = X402Client(testWallet, BigInt(5_000_000), backend)
    Await.result(client.get("http://example.com/api"), 5.seconds)
    assert(paymentHeader.isDefined)
    // must be valid base64
    val decoded = String(Base64.getDecoder.decode(paymentHeader.get), "UTF-8")
    val j       = ujson.read(decoded)
    assert(j("network").str == "Base")
  }

  test("X402HttpClient: refuses to pay more than maxAmount") {
    val backend: (String, String, Map[String, String], String) => Future[HttpResponse] =
      (_, _, headers, _) =>
        if headers.contains("X-Payment") then Future.successful(HttpResponse(200, Map.empty, "paid"))
        else Future.successful(HttpResponse(402, Map.empty, req402Body))
    val client = X402Client(testWallet, BigInt(500_000), backend)  // max 0.5 USDC < 1 USDC
    val resp   = Await.result(client.get("http://example.com/api"), 5.seconds)
    assert(resp.status == 402)   // refused — too expensive
  }

  test("X402HttpClient: POST with body works through 402 → retry") {
    var callCount = 0
    var lastBody  = ""
    val backend: (String, String, Map[String, String], String) => Future[HttpResponse] =
      (_, _, headers, body) =>
        callCount += 1
        lastBody = body
        if headers.contains("X-Payment") then Future.successful(HttpResponse(200, Map.empty, "ok"))
        else Future.successful(HttpResponse(402, Map.empty, req402Body))
    val client = X402Client(testWallet, BigInt(5_000_000), backend)
    val resp   = Await.result(client.post("http://example.com/api", """{"data":"test"}"""), 5.seconds)
    assert(resp.status == 200)
    assert(callCount   == 2)
    assert(lastBody    == """{"data":"test"}""")
  }
