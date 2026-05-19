package scalascript.micropayment.server

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.micropayment.spi.*
import scalascript.server.{Request, Response}
import java.time.Instant

class MicropaymentMiddlewareTest extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  private val testReq = Request("GET", "/api", Map.empty, Map.empty, Map.empty, "")

  private def testConfig: ChannelConfig =
    ChannelConfig(
      chain            = scalascript.blockchain.spi.ChainId.Base,
      asset            = scalascript.blockchain.spi.Asset(scalascript.blockchain.spi.ChainId.Base, "0xusdc", "USDC", 6),
      payee            = "0xPayee",
      initialDeposit   = BigInt(10_000_000),
      settlementPolicy = SettlementPolicy.onClose,
      timeout          = 1.hour,
    )

  // ── Helpers ───────────────────────────────────────────────────────────────────

  private def makeReceipt(channelId: String, seq: Long = 1, amount: BigInt = BigInt(100_000)): PaymentReceipt =
    PaymentReceipt(channelId, seq, amount, amount * seq, Array[Byte](1, 2, 3), System.currentTimeMillis())

  private def encodeReceipt(r: PaymentReceipt): String = ReceiptCodec.encode(r)

  private def reqWith(channelId: String, receipt: PaymentReceipt): Request =
    testReq.copy(headers = Map(
      "x-channel-id"      -> channelId,
      "x-payment-receipt" -> encodeReceipt(receipt),
    ))

  private def reqWithChannelOnly(channelId: String): Request =
    testReq.copy(headers = Map("x-channel-id" -> channelId))

  // ── Stub provider ─────────────────────────────────────────────────────────────

  private class StubChannel(id: String) extends MicropaymentChannel:
    @volatile var received: Option[PaymentReceipt] = None
    @volatile var settled   = false
    val channelId: ChannelId = id
    def state = ChannelState(id, 0, BigInt(0), BigInt(0), Instant.EPOCH, None)
    def availableBalance = Future.successful(BigInt(9_000_000))
    def pay(amount: BigInt, memo: String) = Future.failed(NotImplementedError())
    def receive(r: PaymentReceipt): Future[Unit] =
      received = Some(r)
      Future.unit
    def settle() = Future.successful(SettlementResult.Ok("0xtx", BigInt(100_000)))
    def close()  = settle()

  private class StubProvider(channel: StubChannel, existingId: Option[String] = None) extends ChannelProvider:
    def kind = ChannelKind.ThresholdBatching
    def open(config: ChannelConfig)(using ExecutionContext) = Future.successful(channel)
    def restore(id: ChannelId)(using ExecutionContext) =
      Future.successful(if existingId.contains(id) then Some(channel) else None)
    def listOpen()(using ExecutionContext) = Future.successful(Seq.empty)

  // ── Tests ─────────────────────────────────────────────────────────────────────

  test("No X-Channel-Id: opens new channel, echoes X-Channel-Id in response") {
    val ch       = StubChannel("chan-1")
    val provider = StubProvider(ch)
    val handler  = MicropaymentMiddleware.withMicropayment(provider, testConfig) { (_, _) =>
      Future.successful(Response(200, body = "ok"))
    }
    val resp = Await.result(handler(testReq), 5.seconds)
    assert(resp.status == 200)
    assert(resp.headers.contains("X-Channel-Id"))
    assert(resp.headers("X-Channel-Id") == "chan-1")
  }

  test("X-Channel-Id but no X-Payment-Receipt: returns 402") {
    val ch       = StubChannel("chan-2")
    val provider = StubProvider(ch, existingId = Some("chan-2"))
    val handler  = MicropaymentMiddleware.withMicropayment(provider, testConfig) { (_, _) =>
      Future.successful(Response(200, body = "ok"))
    }
    val resp = Await.result(handler(reqWithChannelOnly("chan-2")), 5.seconds)
    assert(resp.status == 402)
  }

  test("Valid receipt: calls handler and echoes X-Channel-Id") {
    val ch       = StubChannel("chan-3")
    val provider = StubProvider(ch, existingId = Some("chan-3"))
    val handler  = MicropaymentMiddleware.withMicropayment(provider, testConfig) { (_, chan) =>
      Future.successful(Response(200, body = s"balance=${chan.state.offChainPaid}"))
    }
    val receipt = makeReceipt("chan-3")
    val resp    = Await.result(handler(reqWith("chan-3", receipt)), 5.seconds)
    assert(resp.status == 200)
    assert(resp.headers("X-Channel-Id") == "chan-3")
    assert(ch.received.isDefined)
  }

  test("X-Channel-Balance header is present after valid payment") {
    val ch       = StubChannel("chan-4")
    val provider = StubProvider(ch, existingId = Some("chan-4"))
    val handler  = MicropaymentMiddleware.withMicropayment(provider, testConfig) { (_, _) =>
      Future.successful(Response(200))
    }
    val resp = Await.result(handler(reqWith("chan-4", makeReceipt("chan-4"))), 5.seconds)
    assert(resp.headers.contains("X-Channel-Balance"))
    assert(resp.headers("X-Channel-Balance") == "9000000")
  }

  test("Unknown channel id: returns 402") {
    val ch       = StubChannel("chan-5")
    val provider = StubProvider(ch, existingId = None)  // restore always returns None
    val handler  = MicropaymentMiddleware.withMicropayment(provider, testConfig) { (_, _) =>
      Future.successful(Response(200))
    }
    val resp = Await.result(handler(reqWith("unknown-chan", makeReceipt("unknown-chan"))), 5.seconds)
    assert(resp.status == 402)
  }

  test("Malformed base64 receipt: returns 400") {
    val ch       = StubChannel("chan-6")
    val provider = StubProvider(ch, existingId = Some("chan-6"))
    val handler  = MicropaymentMiddleware.withMicropayment(provider, testConfig) { (_, _) =>
      Future.successful(Response(200))
    }
    val badReq = testReq.copy(headers = Map(
      "x-channel-id"      -> "chan-6",
      "x-payment-receipt" -> "not-valid-base64!!!",
    ))
    val resp = Await.result(handler(badReq), 5.seconds)
    assert(resp.status == 400)
  }

  test("receive() failure: returns 402 with error message") {
    val failChannel = new StubChannel("chan-7"):
      override def receive(r: PaymentReceipt): Future[Unit] =
        Future.failed(RuntimeException("Replay detected"))
    val provider = StubProvider(failChannel, existingId = Some("chan-7"))
    val handler  = MicropaymentMiddleware.withMicropayment(provider, testConfig) { (_, _) =>
      Future.successful(Response(200))
    }
    val resp = Await.result(handler(reqWith("chan-7", makeReceipt("chan-7"))), 5.seconds)
    assert(resp.status == 402)
  }

  test("ReceiptCodec round-trip") {
    val r1  = makeReceipt("codec-test", seq = 5, amount = BigInt(999_999))
    val enc = ReceiptCodec.encode(r1)
    val r2  = ReceiptCodec.decode(enc)
    assert(r2.isRight)
    val r = r2.toOption.get
    assert(r.channelId  == r1.channelId)
    assert(r.sequence   == r1.sequence)
    assert(r.amount     == r1.amount)
    assert(r.cumulative == r1.cumulative)
    assert(r.timestamp  == r1.timestamp)
    assert(java.util.Arrays.equals(r.payerSig, r1.payerSig))
  }
