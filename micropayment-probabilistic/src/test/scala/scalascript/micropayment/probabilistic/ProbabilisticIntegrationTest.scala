package scalascript.micropayment.probabilistic

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.micropayment.spi.*
import scalascript.blockchain.spi.*
import scalascript.crypto.{Curve, PublicKey}
import java.time.Instant

class ProbabilisticIntegrationTest extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  private val TestAsset    = Asset(ChainId.Base, "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913", "USDC", 6)
  private val PayerAddress = "0xPAYER0000000000000000000000000000000000"
  private val PayeeAddress = "0xPAYEE0000000000000000000000000000000000"
  private val MaxPayout    = BigInt(1_000_000)   // 1 USDC in micro-units
  private val ServerKey    = Array.fill(32)(0x42.toByte)

  private def makeConfig(policy: SettlementPolicy = SettlementPolicy.onClose): ChannelConfig =
    ChannelConfig(
      chain            = ChainId.Base,
      asset            = TestAsset,
      payee            = PayeeAddress,
      initialDeposit   = BigInt(0),
      settlementPolicy = policy,
      timeout          = 1.hour,
    )

  private def makeChannel(
    policy:   SettlementPolicy = SettlementPolicy.onClose,
    winStore: WinningTicketStore = WinningTicketStore.inMemory(),
  ): (ProbabilisticChannel, ProbabilisticChannel) =
    def mkCh(id: String) = new ProbabilisticChannel(
      channelId        = id,
      payerAddress     = PayerAddress,
      payeeAddress     = PayeeAddress,
      assetInfo        = TestAsset,
      openedAt         = Instant.now(),
      expiryMillis     = Instant.now().toEpochMilli + 3_600_000L,
      chain            = StubChain,
      ctx              = StubContext,
      maxPayout        = MaxPayout,
      redeemBatchSize  = 50,
      serverKey        = ServerKey,
      winStore         = winStore,
      settlementPolicy = policy,
    )
    val id = "test-probabilistic-1"
    (mkCh(id), mkCh(id))

  // ── Payer side ─────────────────────────────────────────────────────────────

  test("pay() generates a receipt with 32-byte preimage in payerSig") {
    val (client, _) = makeChannel()
    val r = Await.result(client.pay(100_000), 5.seconds)
    assert(r.sequence == 1)
    assert(r.amount   == BigInt(100_000))
    assert(r.payerSig.length == 32)
  }

  test("pay() increments sequence on each call") {
    val (client, _) = makeChannel()
    val r1 = Await.result(client.pay(100_000), 5.seconds)
    val r2 = Await.result(client.pay(200_000), 5.seconds)
    assert(r1.sequence == 1)
    assert(r2.sequence == 2)
  }

  test("pay() rejects amount > maxPayout") {
    val (client, _) = makeChannel()
    intercept[Exception] { Await.result(client.pay(MaxPayout + 1), 5.seconds) }
  }

  // ── Payee side ─────────────────────────────────────────────────────────────

  test("receive() rejects replayed sequence") {
    val (client, server) = makeChannel()
    val r = Await.result(client.pay(100_000), 5.seconds)
    Await.result(server.receive(r), 5.seconds)
    val ex = intercept[Exception] { Await.result(server.receive(r), 5.seconds) }
    assert(ex.getMessage.contains("Replay"))
  }

  test("receive() rejects payerSig != 32 bytes") {
    val (_, server) = makeChannel()
    val bad = PaymentReceipt("ch", 1, BigInt(100_000), BigInt(0), Array.fill(16)(0.toByte), 0L)
    val ex  = intercept[Exception] { Await.result(server.receive(bad), 5.seconds) }
    assert(ex.getMessage.contains("32 bytes"))
  }

  // ── Lottery convergence ────────────────────────────────────────────────────

  test("many tickets yield winning tickets roughly at expected rate") {
    val winStore = WinningTicketStore.inMemory()
    val (client, server) = makeChannel(SettlementPolicy.onClose, winStore)
    val claimed = BigInt(100_000)   // 10 % win rate (100k / 1M)
    val n       = 500
    for _ <- 1 to n do
      val r = Await.result(client.pay(claimed), 5.seconds)
      Await.result(server.receive(r), 5.seconds)
    val wins = Await.result(winStore.pendingCount, 5.seconds)
    val rate = wins.toDouble / n
    val expected = claimed.toDouble / MaxPayout.toDouble
    assert(math.abs(rate - expected) < 0.05,
      s"Win rate $rate too far from expected $expected (wins=$wins, n=$n)")
  }

  // ── Settlement ─────────────────────────────────────────────────────────────

  test("settle() returns Fail when no winning tickets") {
    val (_, server) = makeChannel()
    val result = Await.result(server.settle(), 5.seconds)
    assert(result.isInstanceOf[SettlementResult.Fail])
  }

  test("settle() returns Ok and clears winPending when there are wins") {
    // Use a known preimage that wins at 99% rate so settle gets something
    val winStore     = WinningTicketStore.inMemory()
    val (_, server)  = makeChannel(SettlementPolicy.onClose, winStore)
    // Manually inject a winning entry
    val fakeReceipt  = PaymentReceipt("test-probabilistic-1", 1, BigInt(990_000), BigInt(0),
                                      Array.fill(32)(0.toByte), System.currentTimeMillis())
    Await.result(winStore.add(WinEntry(fakeReceipt, Array.fill(32)(0.toByte))), 5.seconds)

    // Patch winPending manually via server channel internal state:
    // (we can't directly set it, so go through receive with a winning preimage)
    // Instead: test settle() directly on the store
    val result = Await.result(server.settle(), 5.seconds)
    assert(result.isInstanceOf[SettlementResult.Ok])
    val ok = result.asInstanceOf[SettlementResult.Ok]
    assert(ok.settled == BigInt(990_000))
    val pending = Await.result(winStore.pendingCount, 5.seconds)
    assert(pending == 0)
  }

  test("threshold policy: auto-settle when winPending crosses threshold") {
    // Use 99% win rate to guarantee wins quickly
    val winStore        = WinningTicketStore.inMemory()
    val policy          = SettlementPolicy.threshold(BigInt(500_000))
    val (_, server) = makeChannel(policy, winStore)

    // Force wins by using a preimage that's known to win at 99%:
    // preimage = 0x00…00, salt = HMAC(serverKey, SHA256(0x00…00))
    // We confirmed in LotteryMathTest that zeros win at 50%.
    // Send enough tickets with known-winning preimage pattern until threshold is crossed.
    // Since the preimage is random in pay(), we use a separate known-win receipt.
    val winningPreimage = Array.fill(32)(0.toByte)  // known to win at 50%+
    val salt            = LotteryMath.serverSalt(ServerKey, LotteryMath.commitment(winningPreimage))
    // Verify it actually wins at our claimed amount (990k/1M = 99% → should win)
    val claimedAmt = BigInt(990_000)
    assume(LotteryMath.isWinner(winningPreimage, salt, claimedAmt, MaxPayout),
      "preimage must win for this test to make sense")

    // Submit 2 winning receipts (2 × 990k = 1980k > 500k threshold)
    for seq <- 1 to 2 do
      val r = PaymentReceipt("test-probabilistic-1", seq, claimedAmt, BigInt(0),
                             winningPreimage, System.currentTimeMillis())
      Await.result(server.receive(r), 5.seconds)

    // Policy should have triggered settle() → winPending = 0
    assert(server.state.offChainPaid == BigInt(0),
      s"Expected settle to have cleared winPending, got ${server.state.offChainPaid}")
    assert(server.state.onChainPaid > BigInt(0))
  }

  // ── Provider ───────────────────────────────────────────────────────────────

  test("ProbabilisticProvider.open() returns a ProbabilisticChannel") {
    val provider = ProbabilisticProvider(StubChain, StubContext, maxPayout = MaxPayout)
    val ch       = Await.result(provider.open(makeConfig()), 5.seconds)
    assert(ch.isInstanceOf[ProbabilisticChannel])
    assert(provider.kind == ChannelKind.Probabilistic)
  }

  // ── Stub chain + context ──────────────────────────────────────────────────

  private object StubChain extends ChainAdapter:
    type Tx       = Unit
    type SignedTx = Unit
    def chainId                                                              = ChainId.Base
    def supportedCurves                                                      = Seq(Curve.Secp256k1)
    def defaultDerivationPath                                                = "m/44'/60'/0'/0/0"
    def addressFromPublicKey(pk: PublicKey)                                  = "0x" + "0" * 40
    def isValidAddress(s: String)                                            = true
    def normalizeAddress(s: String)                                          = s.toLowerCase
    def typedDataDigest(data: TypedData): Array[Byte]                        = Array.empty
    def recoverAddress(digest: Array[Byte], sig: Array[Byte]): Option[String] = None
    def buildTransaction(i: TxIntent, sender: String, ctx: ChainContext)     = Future.successful(())
    def prepareSigningPayload(tx: Unit, pk: PublicKey)                       = SigningPayload(Array.empty, scalascript.crypto.HashAlgo.Keccak256)
    def assembleSignedTransaction(tx: Unit, sig: Array[Byte], pk: PublicKey) = ()
    def broadcast(tx: Unit, ctx: ChainContext)                               = Future.successful(TxHash("0x0"))
    def describe(tx: Unit)                                                   = TxDescription("stub", Map.empty)
    def nativeBalance(a: String, c: ChainContext)                            = Future.successful(BigInt(0))
    def tokenBalance(a: Asset, h: String, c: ChainContext)                   = Future.successful(BigInt(10_000_000))
    def nonceOf(a: String, c: ChainContext)                                  = Future.successful(BigInt(0))
    def getReceipt(h: TxHash, c: ChainContext)                               = Future.successful(None)
    def waitForReceipt(h: TxHash, c: ChainContext, t: Long)                  = Future.failed(NotImplementedError())
    def call(target: String, cd: Array[Byte], c: ChainContext)               = Future.successful(Array.empty)
    def predictDeployAddress(d: TxIntent.Deploy, dep: String, c: ChainContext) = Future.successful("0x0")

  private object StubContext extends ChainContext:
    def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] = Future.successful(ujson.Null)
    def nowSeconds: Long = System.currentTimeMillis() / 1000
