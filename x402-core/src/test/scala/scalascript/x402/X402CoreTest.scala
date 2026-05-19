package scalascript.x402

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import ExecutionContext.Implicits.global

class X402CoreTest extends AnyFunSuite:

  // ── Network ──────────────────────────────────────────────────────────────────

  test("Network.chainId") {
    assert(Network.Base.chainId            == 8453)
    assert(Network.BaseSepolia.chainId     == 84532)
    assert(Network.EthereumMainnet.chainId == 1)
    assert(Network.Polygon.chainId         == 137)
    assert(Network.Arbitrum.chainId        == 42161)
    assert(Network.Optimism.chainId        == 10)
  }

  test("Assets.usdc returns correct asset per network") {
    assert(Assets.usdc(Network.Base).symbol         == "USDC")
    assert(Assets.usdc(Network.Base).decimals       == 6)
    assert(Assets.usdc(Network.BaseSepolia).network == Network.BaseSepolia)
    assert(Assets.usdc(Network.EthereumMainnet).address.startsWith("0x"))
  }

  // ── PaymentScheme ────────────────────────────────────────────────────────────

  test("PaymentScheme.Exact") {
    val s = PaymentScheme.Exact(1_000_000L)
    s match
      case PaymentScheme.Exact(amt) => assert(amt == BigInt(1_000_000))
      case _                        => fail("wrong variant")
  }

  test("PaymentScheme.Stream") {
    val s = PaymentScheme.Stream(100L, "request", 100, 10_000L)
    s match
      case PaymentScheme.Stream(rate, unit, max, maxAmt) =>
        assert(rate == 100)
        assert(unit == "request")
        assert(max == 100)
        assert(maxAmt == 10_000)
      case _ => fail("wrong variant")
  }

  test("PaymentScheme.CardanoExact ADA") {
    val s = PaymentScheme.CardanoExact(2_000_000L, None)
    s match
      case PaymentScheme.CardanoExact(lovelace, asset) =>
        assert(lovelace == BigInt(2_000_000))
        assert(asset.isEmpty)
      case _ => fail("wrong variant")
  }

  // ── PaymentRequirements ──────────────────────────────────────────────────────

  test("PaymentRequirements defaults") {
    val req = PaymentRequirements(
      scheme      = PaymentScheme.Exact(1_000_000L),
      network     = Network.Base,
      asset       = Assets.USDC_BASE,
      payTo       = "0xabc",
      resource    = "/api/premium",
      description = "Premium access",
    )
    assert(req.maxTimeoutSeconds == 300)
    assert(req.network           == Network.Base)
  }

  // ── TransferAuthorization ────────────────────────────────────────────────────

  test("TransferAuthorization fields") {
    val auth = TransferAuthorization(
      from        = "0xfrom",
      to          = "0xto",
      value       = BigInt(1_000_000),
      validAfter  = BigInt(0),
      validBefore = BigInt(9999999999L),
      nonce       = "0x" + "ab" * 32,
    )
    assert(auth.value == BigInt(1_000_000))
  }

  // ── PaymentPayload ───────────────────────────────────────────────────────────

  test("PaymentPayload default version") {
    val auth = TransferAuthorization("0xf", "0xt", BigInt(1), BigInt(0), BigInt(1), "0x00")
    val p    = PaymentPayload(
      scheme        = PaymentScheme.Exact(1L),
      network       = Network.Base,
      authorization = auth,
      signature     = "0xsig",
    )
    assert(p.x402Version == 1)
  }

  // ── Cardano types ────────────────────────────────────────────────────────────

  test("CardanoAssets") {
    assert(CardanoAssets.ADA.symbol  == "ADA")
    assert(CardanoAssets.DJED.symbol == "DJED")
    assert(CardanoAssets.USDA.symbol == "USDA")
  }

  test("CardanoPaymentProof fields") {
    val proof = CardanoPaymentProof("addr1q...", "sig_hex", "key_hex")
    assert(proof.address   == "addr1q...")
    assert(proof.signature == "sig_hex")
    assert(proof.key       == "key_hex")
  }

  // ── In-memory NonceStore ─────────────────────────────────────────────────────

  test("NonceStore.inMemory: first claim returns true") {
    val store = NonceStore.inMemory()
    val ok    = Await.result(store.claim("0xabc", BigInt(9999999999L)), 5.seconds)
    assert(ok)
  }

  test("NonceStore.inMemory: second claim of same nonce returns false") {
    val store = NonceStore.inMemory()
    val nonce = "0x" + "ff" * 32
    Await.result(store.claim(nonce, BigInt(9999999999L)), 5.seconds)
    val replay = Await.result(store.claim(nonce, BigInt(9999999999L)), 5.seconds)
    assert(!replay)
  }

  test("NonceStore.inMemory: different nonces are independent") {
    val store = NonceStore.inMemory()
    val n1 = Await.result(store.claim("0x01", BigInt(9999999999L)), 5.seconds)
    val n2 = Await.result(store.claim("0x02", BigInt(9999999999L)), 5.seconds)
    assert(n1 && n2)
  }

  test("NonceStore.inMemory: cleanup completes") {
    val store = NonceStore.inMemory()
    Await.result(store.claim("0xclean", BigInt(1)), 5.seconds)
    Await.result(store.cleanup(), 5.seconds)
  }

  // ── In-memory SettlementQueue ────────────────────────────────────────────────

  test("SettlementQueue.inMemory: enqueue then process") {
    val queue = SettlementQueue.inMemory()
    val req   = PaymentRequirements(
      scheme      = PaymentScheme.Exact(1_000_000L),
      network     = Network.Base,
      asset       = Assets.USDC_BASE,
      payTo       = "0xabc",
      resource    = "/api",
      description = "test",
    )
    val auth    = TransferAuthorization("0xf", "0xt", BigInt(1), BigInt(0), BigInt(1), "0x00")
    val payload = PaymentPayload(
      scheme        = req.scheme,
      network       = req.network,
      authorization = auth,
      signature     = "0xsig",
    )
    Await.result(queue.enqueue(payload, req), 5.seconds)

    var settled = 0
    val fac = new Facilitator:
      def verify(p: PaymentPayload, r: PaymentRequirements) = Future.successful(VerifyResult.Ok)
      def settle(p: PaymentPayload, r: PaymentRequirements) =
        settled += 1
        Future.successful(SettleResult.Ok("0x" + "0" * 64))

    Await.result(queue.process(fac), 5.seconds)
    assert(settled == 1)
  }

  test("SettlementQueue.inMemory: process on empty queue is a no-op") {
    val queue = SettlementQueue.inMemory()
    val fac   = Facilitators.testnet()
    Await.result(queue.process(fac), 5.seconds)   // should not throw
  }

  // ── Testnet facilitator ──────────────────────────────────────────────────────

  test("Facilitators.testnet: verify always Ok") {
    val auth  = TransferAuthorization("0xf", "0xt", BigInt(1), BigInt(0), BigInt(1), "0x00")
    val req   = PaymentRequirements(PaymentScheme.Exact(1L), Network.Base, Assets.USDC_BASE, "0xpay", "/", "test")
    val payload = PaymentPayload(scheme = PaymentScheme.Exact(1L), network = Network.Base, authorization = auth, signature = "0xsig")
    val result  = Await.result(Facilitators.testnet().verify(payload, req), 5.seconds)
    assert(result == VerifyResult.Ok)
  }

  test("Facilitators.testnet: settle always Ok") {
    val auth  = TransferAuthorization("0xf", "0xt", BigInt(1), BigInt(0), BigInt(1), "0x00")
    val req   = PaymentRequirements(PaymentScheme.Exact(1L), Network.Base, Assets.USDC_BASE, "0xpay", "/", "test")
    val payload = PaymentPayload(scheme = PaymentScheme.Exact(1L), network = Network.Base, authorization = auth, signature = "0xsig")
    val result  = Await.result(Facilitators.testnet().settle(payload, req), 5.seconds)
    result match
      case SettleResult.Ok(hash) => assert(hash.startsWith("0x"))
      case _                     => fail("expected Ok")
  }

  // ── WithFallback facilitator ─────────────────────────────────────────────────

  test("Facilitators.withFallback: uses primary when Ok") {
    var primaryCalled  = 0
    var fallbackCalled = 0
    val primary = new Facilitator:
      def verify(p: PaymentPayload, r: PaymentRequirements) =
        primaryCalled += 1; Future.successful(VerifyResult.Ok)
      def settle(p: PaymentPayload, r: PaymentRequirements) =
        primaryCalled += 1; Future.successful(SettleResult.Ok("0xtx"))
    val fallback = new Facilitator:
      def verify(p: PaymentPayload, r: PaymentRequirements) =
        fallbackCalled += 1; Future.successful(VerifyResult.Ok)
      def settle(p: PaymentPayload, r: PaymentRequirements) =
        fallbackCalled += 1; Future.successful(SettleResult.Ok("0xtx2"))
    val auth    = TransferAuthorization("0xf", "0xt", BigInt(1), BigInt(0), BigInt(1), "0x00")
    val req     = PaymentRequirements(PaymentScheme.Exact(1L), Network.Base, Assets.USDC_BASE, "0xpay", "/", "test")
    val payload = PaymentPayload(scheme = PaymentScheme.Exact(1L), network = Network.Base, authorization = auth, signature = "0xsig")
    val fac     = Facilitators.withFallback(primary, fallback)
    Await.result(fac.verify(payload, req), 5.seconds)
    assert(primaryCalled  == 1)
    assert(fallbackCalled == 0)
  }

  test("Facilitators.withFallback: falls back on Fail") {
    val failing = new Facilitator:
      def verify(p: PaymentPayload, r: PaymentRequirements) =
        Future.successful(VerifyResult.Fail("primary down"))
      def settle(p: PaymentPayload, r: PaymentRequirements) =
        Future.successful(SettleResult.Fail("primary down"))
    val auth    = TransferAuthorization("0xf", "0xt", BigInt(1), BigInt(0), BigInt(1), "0x00")
    val req     = PaymentRequirements(PaymentScheme.Exact(1L), Network.Base, Assets.USDC_BASE, "0xpay", "/", "test")
    val payload = PaymentPayload(scheme = PaymentScheme.Exact(1L), network = Network.Base, authorization = auth, signature = "0xsig")
    val fac     = Facilitators.withFallback(failing, Facilitators.testnet())
    val result  = Await.result(fac.verify(payload, req), 5.seconds)
    assert(result == VerifyResult.Ok)
  }

  // ── SettlementMode ───────────────────────────────────────────────────────────

  test("SettlementMode.Synchronous is a singleton") {
    assert(SettlementMode.Synchronous == SettlementMode.Synchronous)
  }

  test("SettlementMode.Async wraps a queue") {
    val q = SettlementQueue.inMemory()
    val m = SettlementMode.Async(q)
    m match
      case SettlementMode.Async(queue) => assert(queue eq q)
      case _                           => fail("wrong mode")
  }
