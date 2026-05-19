package scalascript.micropayment.threshold

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.micropayment.spi.*
import scalascript.blockchain.spi.*
import scalascript.wallet.spi.AccountStrategy
import scalascript.crypto.{Curve, PublicKey}
import scalascript.x402.{Facilitators, NonceStore}
import java.time.Instant

class ThresholdIntegrationTest extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  private val PayerAddress = "0xPAYER0000000000000000000000000000000000"
  private val PayeeAddress = "0xPAYEE0000000000000000000000000000000000"
  private val TestAsset    = Asset(ChainId.Base, "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913", "USDC", 6)

  private def makeConfig(policy: SettlementPolicy = SettlementPolicy.onClose): ChannelConfig =
    ChannelConfig(
      chain            = ChainId.Base,
      asset            = TestAsset,
      payee            = PayeeAddress,
      initialDeposit   = BigInt(10_000_000),
      settlementPolicy = policy,
      timeout          = 1.hour,
    )

  private def makeChannel(policy: SettlementPolicy = SettlementPolicy.onClose): (ThresholdChannel, ThresholdChannel) =
    val receiptStore = ReceiptStore.inMemory()
    val nonceStore   = NonceStore.inMemory()
    val facilitator  = Facilitators.testnet()
    val expiry       = Instant.now().toEpochMilli / 1000 + 3600L

    def mkCh(id: String) = new ThresholdChannel(
      channelId        = id,
      payerAddress     = PayerAddress,
      payeeAddress     = PayeeAddress,
      assetInfo        = TestAsset,
      openedAt         = Instant.now(),
      expiry           = expiry,
      chain            = StubChain,
      strategy         = StubStrategy,
      facilitator      = facilitator,
      nonceStore       = nonceStore,
      receiptStore     = receiptStore,
      settlementPolicy = policy,
    )
    val id = "test-channel-1"
    (mkCh(id), mkCh(id))  // client-side and server-side share same id + store

  // ── Basic lifecycle ────────────────────────────────────────────────────────

  test("pay() increments sequence and returns receipt") {
    val (client, _) = makeChannel()
    val r = Await.result(client.pay(100_000), 5.seconds)
    assert(r.sequence    == 1)
    assert(r.amount      == BigInt(100_000))
    assert(r.cumulative  == BigInt(100_000))
    assert(r.channelId   == "test-channel-1")
  }

  test("pay() twice: sequence and cumulative increment correctly") {
    val (client, _) = makeChannel()
    Await.result(client.pay(100_000), 5.seconds)
    val r2 = Await.result(client.pay(200_000), 5.seconds)
    assert(r2.sequence   == 2)
    assert(r2.amount     == BigInt(200_000))
    assert(r2.cumulative == BigInt(300_000))
  }

  test("receive(): valid receipt updates server state") {
    val (client, server) = makeChannel()
    val r = Await.result(client.pay(100_000), 5.seconds)
    Await.result(server.receive(r), 5.seconds)
    assert(server.state.sequence     == 1)
    assert(server.state.offChainPaid == BigInt(100_000))
  }

  test("receive(): replayed receipt is rejected") {
    val (client, server) = makeChannel()
    val r = Await.result(client.pay(100_000), 5.seconds)
    Await.result(server.receive(r), 5.seconds)
    val ex = intercept[Exception] { Await.result(server.receive(r), 5.seconds) }
    assert(ex.getMessage.contains("Replay"))
  }

  test("receive(): wrong signer is rejected") {
    val (client, server) = makeChannel()
    val r = Await.result(client.pay(100_000), 5.seconds)
    val tampered = r.copy(payerSig = "WRONGSIG".getBytes("UTF-8"))
    val ex = intercept[Exception] { Await.result(server.receive(tampered), 5.seconds) }
    assert(ex.getMessage.nonEmpty)
  }

  test("full cycle: open → pay × 3 → settle → state reset") {
    val (client, server) = makeChannel()
    val rs = Seq(100_000, 200_000, 150_000).map { amt =>
      val r = Await.result(client.pay(amt), 5.seconds)
      Await.result(server.receive(r), 5.seconds)
      r
    }
    val lastCum = rs.last.cumulative
    assert(lastCum == BigInt(450_000))
    assert(server.state.offChainPaid == BigInt(450_000))

    val result = Await.result(server.settle(), 5.seconds)
    assert(result.isInstanceOf[SettlementResult.Ok])
    assert(server.state.offChainPaid == BigInt(0))
    assert(server.state.onChainPaid  == BigInt(450_000))
  }

  test("threshold policy: auto-settle when threshold crossed") {
    val policy = SettlementPolicy.threshold(BigInt(300_000))
    val (client, server) = makeChannel(policy)

    val r1 = Await.result(client.pay(100_000), 5.seconds)
    Await.result(server.receive(r1), 5.seconds)
    assert(server.state.offChainPaid == BigInt(100_000))  // no settle yet

    val r2 = Await.result(client.pay(200_000), 5.seconds)
    Await.result(server.receive(r2), 5.seconds)
    // 300_000 >= threshold → auto-settled
    assert(server.state.offChainPaid == BigInt(0))
    assert(server.state.onChainPaid  == BigInt(300_000))
  }

  test("close(): settles and returns Ok") {
    val (client, server) = makeChannel()
    val r = Await.result(client.pay(500_000), 5.seconds)
    Await.result(server.receive(r), 5.seconds)
    val result = Await.result(server.close(), 5.seconds)
    assert(result.isInstanceOf[SettlementResult.Ok])
    assert(server.state.offChainPaid == BigInt(0))
  }

  test("ThresholdBatchingProvider.open() uses strategy address") {
    val store    = ReceiptStore.inMemory()
    val provider = ThresholdBatchingProvider(StubChain, StubStrategy, Facilitators.testnet(),
                                              receiptStore = store)
    val channel  = Await.result(provider.open(makeConfig()), 5.seconds)
    val tc       = channel.asInstanceOf[ThresholdChannel]
    assert(tc.payerAddress == PayerAddress)
    assert(tc.payeeAddress == PayeeAddress)
  }

  // ── Stub chain + strategy ────────────────────────────────────────────────

  private object StubChain extends ChainAdapter:
    type Tx       = Unit
    type SignedTx = Unit
    def chainId                                                              = ChainId.Base
    def supportedCurves                                                      = Seq(Curve.Secp256k1)
    def defaultDerivationPath                                                = "m/44'/60'/0'/0/0"
    def addressFromPublicKey(pk: PublicKey)                                  = "0x" + "0" * 40
    def isValidAddress(s: String)                                            = true
    def normalizeAddress(s: String)                                          = s.toLowerCase

    def typedDataDigest(data: TypedData): Array[Byte] =
      Array.empty[Byte]

    def recoverAddress(digest: Array[Byte], sig: Array[Byte]): Option[String] =
      // sig = "STUB_SIG:<address>" encoded in UTF-8
      val s = new String(sig, "UTF-8")
      if s.startsWith("STUB_SIG:") then Some(s.drop(9)) else None

    def buildTransaction(i: TxIntent, ctx: ChainContext)                     = Future.successful(())
    def prepareSigningPayload(tx: Unit, pk: PublicKey)                       = SigningPayload(Array.empty, scalascript.crypto.HashAlgo.Keccak256)
    def assembleSignedTransaction(tx: Unit, sig: Array[Byte], pk: PublicKey) = ()
    def broadcast(tx: Unit, ctx: ChainContext)                               = Future.successful(TxHash("0x0"))
    def describe(tx: Unit)                                                   = TxDescription("stub", Map.empty)
    def nativeBalance(a: String, c: ChainContext)                            = Future.successful(BigInt(0))
    def tokenBalance(a: Asset, h: String, c: ChainContext)                   = Future.successful(BigInt(0))
    def nonceOf(a: String, c: ChainContext)                                  = Future.successful(BigInt(0))
    def getReceipt(h: TxHash, c: ChainContext)                               = Future.successful(None)
    def waitForReceipt(h: TxHash, c: ChainContext, t: Long)                  = Future.failed(NotImplementedError())
    def call(target: String, cd: Array[Byte], c: ChainContext)               = Future.successful(Array.empty)
    def predictDeployAddress(d: TxIntent.Deploy, dep: String, c: ChainContext) = Future.successful("0x0")

  private object StubStrategy extends AccountStrategy:
    def kind                                          = "stub"
    def getAddress(chain: ChainAdapter): Future[String] = Future.successful(PayerAddress)

    def signTransaction(chain: ChainAdapter)(tx: chain.Tx): Future[chain.SignedTx] =
      Future.failed(NotImplementedError())

    def signMessage(chain: ChainAdapter, msg: Array[Byte]): Future[Array[Byte]] =
      Future.successful(msg)

    def signTypedData(chain: ChainAdapter, typed: TypedData): Future[Array[Byte]] =
      Future.successful(s"STUB_SIG:$PayerAddress".getBytes("UTF-8"))
