package scalascript.micropayment.client

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.micropayment.spi.*
import scalascript.blockchain.spi.{ChainId, Asset}
import java.time.Instant
import java.util.Base64

class MicropaymentHttpClientTest extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  private val testConfig = ChannelConfig(
    chain            = ChainId.Base,
    asset            = Asset(ChainId.Base, "0xusdc", "USDC", 6),
    payee            = "0xPayee",
    initialDeposit   = BigInt(5_000_000),
    settlementPolicy = SettlementPolicy.onClose,
    timeout          = 1.hour,
  )

  // ── Stub channel + provider ───────────────────────────────────────────────

  private class StubChannel(val channelId: ChannelId, val seq: Int = 0) extends MicropaymentChannel:
    @volatile var payCount    = 0
    @volatile var lastReceipt: Option[PaymentReceipt] = None
    def state = ChannelState(channelId, payCount, BigInt(payCount * 100_000), BigInt(0), Instant.EPOCH, None)
    def availableBalance = Future.successful(BigInt(5_000_000))
    def pay(amount: BigInt, memo: String): Future[PaymentReceipt] =
      payCount += 1
      val r = PaymentReceipt(channelId, payCount, amount, BigInt(payCount) * amount, Array[Byte](1), System.currentTimeMillis())
      lastReceipt = Some(r)
      Future.successful(r)
    def receive(r: PaymentReceipt): Future[Unit] = Future.unit
    def settle()  = Future.successful(SettlementResult.Ok("0xtx", BigInt(100_000)))
    def close()   = settle()

  private class StubProvider(channelId: String = "test-chan") extends ChannelProvider:
    val channel = new StubChannel(channelId)
    var openCount    = 0
    var restoreCount = 0
    def kind = ChannelKind.ThresholdBatching
    def open(config: ChannelConfig)(using ExecutionContext): Future[MicropaymentChannel] =
      openCount += 1; Future.successful(channel)
    def restore(id: ChannelId)(using ExecutionContext): Future[Option[MicropaymentChannel]] =
      restoreCount += 1; Future.successful(Some(channel))
    def listOpen()(using ExecutionContext): Future[Seq[ChannelState]] = Future.successful(Seq.empty)

  // ── Tests ─────────────────────────────────────────────────────────────────

  test("First get() opens a channel") {
    val provider = StubProvider()
    val backend  = echoBackend(200)
    val client   = MicropaymentHttpClient(provider, testConfig, backend)
    Await.result(client.get("http://example.com/api", BigInt(100_000)), 5.seconds)
    assert(provider.openCount == 1)
    assert(provider.channel.payCount == 1)
  }

  test("Second get() reuses same channel (no re-open)") {
    val provider = StubProvider()
    val backend  = echoBackend(200)
    val client   = MicropaymentHttpClient(provider, testConfig, backend)
    Await.result(client.get("http://example.com/api", BigInt(100_000)), 5.seconds)
    Await.result(client.get("http://example.com/api", BigInt(100_000)), 5.seconds)
    assert(provider.openCount    == 1)
    assert(provider.channel.payCount == 2)
  }

  test("X-Payment-Receipt header is present in request") {
    val provider = StubProvider()
    var capturedHeaders = Map.empty[String, String]
    val backend: HttpBackend = new HttpBackend:
      def execute(method: String, url: String, headers: Map[String, String], body: String) =
        capturedHeaders = headers
        Future.successful(HttpResponse(200, Map.empty, "ok"))
    val client = MicropaymentHttpClient(provider, testConfig, backend)
    Await.result(client.get("http://example.com/api", BigInt(100_000)), 5.seconds)
    assert(capturedHeaders.contains("X-Payment-Receipt"))
    assert(capturedHeaders.contains("X-Channel-Id"))
    // Verify receipt is valid base64 JSON
    val enc  = capturedHeaders("X-Payment-Receipt")
    val json = ujson.read(String(Base64.getDecoder.decode(enc), "UTF-8"))
    assert(json("channelId").str == "test-chan")
  }

  test("post() sends body through") {
    val provider = StubProvider()
    var capturedBody = ""
    val backend: HttpBackend = new HttpBackend:
      def execute(method: String, url: String, headers: Map[String, String], body: String) =
        capturedBody = body
        Future.successful(HttpResponse(200, Map.empty, "ok"))
    val client = MicropaymentHttpClient(provider, testConfig, backend)
    Await.result(client.post("http://example.com/api", """{"x":1}""", BigInt(100_000)), 5.seconds)
    assert(capturedBody == """{"x":1}""")
  }

  test("channelState is None before first request") {
    val client = MicropaymentHttpClient(StubProvider(), testConfig, echoBackend(200))
    assert(client.channelState.isEmpty)
  }

  test("channelState is Some after first request") {
    val client = MicropaymentHttpClient(StubProvider(), testConfig, echoBackend(200))
    Await.result(client.get("http://example.com/api", BigInt(100_000)), 5.seconds)
    assert(client.channelState.isDefined)
  }

  test("closeChannel() returns Ok when channel is open") {
    val client = MicropaymentHttpClient(StubProvider(), testConfig, echoBackend(200))
    Await.result(client.get("http://example.com/api", BigInt(100_000)), 5.seconds)
    val result = Await.result(client.closeChannel(), 5.seconds)
    assert(result.isInstanceOf[SettlementResult.Ok])
  }

  test("closeChannel() returns Fail when no channel opened yet") {
    val client = MicropaymentHttpClient(StubProvider(), testConfig, echoBackend(200))
    val result = Await.result(client.closeChannel(), 5.seconds)
    assert(result.isInstanceOf[SettlementResult.Fail])
  }

  private def echoBackend(status: Int): HttpBackend = new HttpBackend:
    def execute(method: String, url: String, headers: Map[String, String], body: String) =
      Future.successful(HttpResponse(status, Map.empty, "ok"))
