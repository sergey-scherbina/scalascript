package scalascript.micropayment.evm

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.micropayment.spi.*
import scalascript.blockchain.spi.*
import scalascript.wallet.spi.AccountStrategy
import scalascript.crypto.{Curve, PublicKey}
import java.time.Instant

class StateChannelTest extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  private val PayerAddress    = "0xA100000000000000000000000000000000000001"
  private val PayeeAddress    = "0xB200000000000000000000000000000000000002"
  private val ContractAddress = "0xC300000000000000000000000000000000000003"
  private val TestAsset       = Asset(ChainId.Base, "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913", "USDC", 6)

  private def makeConfig(policy: SettlementPolicy = SettlementPolicy.onClose): ChannelConfig =
    ChannelConfig(
      chain            = ChainId.Base,
      asset            = TestAsset,
      payee            = PayeeAddress,
      initialDeposit   = BigInt(10_000_000),
      settlementPolicy = policy,
      timeout          = 1.hour,
    )

  private def makeChannel(
    policy: SettlementPolicy     = SettlementPolicy.onClose,
    store:  StateReceiptStore    = StateReceiptStore.inMemory(),
  ): (StateChannel, StateChannel) =
    def mkCh(id: String) = new StateChannel(
      channelId         = id,
      contractAddress   = ContractAddress,
      payerAddress      = PayerAddress,
      payeeAddress      = PayeeAddress,
      assetInfo         = TestAsset,
      openedAt          = Instant.now(),
      disputeWindowSecs = 3600L,
      chain             = StubChain,
      strategy          = StubStrategy,
      ctx               = StubContext,
      receiptStore      = store,
      settlementPolicy  = policy,
    )
    val id = "test-state-channel-1"
    (mkCh(id), mkCh(id))

  // ── Payer side ─────────────────────────────────────────────────────────────

  test("pay() increments sequence and cumulative") {
    val (client, _) = makeChannel()
    val r1 = Await.result(client.pay(100_000), 5.seconds)
    val r2 = Await.result(client.pay(200_000), 5.seconds)
    assert(r1.sequence   == 1)
    assert(r1.cumulative == BigInt(100_000))
    assert(r2.sequence   == 2)
    assert(r2.cumulative == BigInt(300_000))
  }

  test("pay() puts 65-byte personal_sign sig in payerSig") {
    val (client, _) = makeChannel()
    val r = Await.result(client.pay(100_000), 5.seconds)
    assert(r.payerSig.length == 65)
  }

  // ── Payee side ─────────────────────────────────────────────────────────────

  test("receive(): valid receipt updates server state") {
    val (client, server) = makeChannel()
    val r = Await.result(client.pay(100_000), 5.seconds)
    Await.result(server.receive(r), 5.seconds)
    assert(server.state.sequence     == 1)
    assert(server.state.offChainPaid == BigInt(100_000))
  }

  test("receive(): replay is rejected") {
    val (client, server) = makeChannel()
    val r = Await.result(client.pay(100_000), 5.seconds)
    Await.result(server.receive(r), 5.seconds)
    val ex = intercept[Exception] { Await.result(server.receive(r), 5.seconds) }
    assert(ex.getMessage.contains("Replay"))
  }

  test("receive(): wrong signer is rejected") {
    val (client, server) = makeChannel()
    val r = Await.result(client.pay(100_000), 5.seconds)
    val tampered = r.copy(payerSig = Array.fill(65)(0xaa.toByte))
    val ex = intercept[Exception] { Await.result(server.receive(tampered), 5.seconds) }
    assert(ex.getMessage.nonEmpty)
  }

  // ── Full lifecycle ──────────────────────────────────────────────────────────

  test("open → pay × 3 → settle: state cleared, on-chain updated") {
    val (client, server) = makeChannel()
    Seq(100_000, 200_000, 150_000).foreach { amt =>
      val r = Await.result(client.pay(amt), 5.seconds)
      Await.result(server.receive(r), 5.seconds)
    }
    assert(server.state.offChainPaid == BigInt(450_000))

    val result = Await.result(server.settle(), 5.seconds)
    assert(result.isInstanceOf[SettlementResult.Ok])
    assert(server.state.offChainPaid == BigInt(0))
    assert(server.state.onChainPaid  == BigInt(450_000))
    val ok = result.asInstanceOf[SettlementResult.Ok]
    assert(ok.txHash == "0xSETTLED")
  }

  test("settle() fails when no receipt") {
    val (_, server) = makeChannel()
    val r = Await.result(server.settle(), 5.seconds)
    assert(r.isInstanceOf[SettlementResult.Fail])
  }

  test("close() calls settle()") {
    val (client, server) = makeChannel()
    val r = Await.result(client.pay(500_000), 5.seconds)
    Await.result(server.receive(r), 5.seconds)
    val result = Await.result(server.close(), 5.seconds)
    assert(result.isInstanceOf[SettlementResult.Ok])
  }

  test("threshold policy: auto-settle when threshold crossed") {
    val policy = SettlementPolicy.threshold(BigInt(300_000))
    val (client, server) = makeChannel(policy)

    val r1 = Await.result(client.pay(200_000), 5.seconds)
    Await.result(server.receive(r1), 5.seconds)
    assert(server.state.offChainPaid == BigInt(200_000))  // threshold not yet crossed

    val r2 = Await.result(client.pay(200_000), 5.seconds)
    Await.result(server.receive(r2), 5.seconds)
    // 400_000 >= 300_000 → auto-settled
    assert(server.state.offChainPaid == BigInt(0))
    assert(server.state.onChainPaid  == BigInt(400_000))
  }

  // ── Cooperative close ────────────────────────────────────────────────────

  test("cooperativeClose(): both parties sign and release funds") {
    val (client, server) = makeChannel()
    Await.result(client.pay(300_000), 5.seconds)
    val cum = BigInt(300_000)
    val payerSig = Await.result(client.signCoopClose(cum), 5.seconds)
    val payeeSig = Await.result(server.signCoopClose(cum), 5.seconds)
    val result   = Await.result(server.cooperativeClose(cum, payerSig, payeeSig), 5.seconds)
    assert(result.isInstanceOf[SettlementResult.Ok])
    val ok = result.asInstanceOf[SettlementResult.Ok]
    assert(ok.settled == BigInt(300_000))
  }

  // ── Dispute path ─────────────────────────────────────────────────────────

  test("challenge(): payer submits higher-sequence receipt on-chain") {
    val store            = StateReceiptStore.inMemory()
    val (client, server) = makeChannel(store = store)
    val r1 = Await.result(client.pay(100_000), 5.seconds)
    val r2 = Await.result(client.pay(200_000), 5.seconds)
    Await.result(server.receive(r1), 5.seconds)
    // Server (maliciously) tries to submit r1; payer counters with r2
    val result = Await.result(client.challenge(r2), 5.seconds)
    assert(result.isInstanceOf[SettlementResult.Ok])
  }

  // ── ABI helpers ───────────────────────────────────────────────────────────

  test("stateHash is 32 bytes and varies by sequence") {
    val h1 = PaymentChannelAbi.stateHash(ContractAddress, 1L, BigInt(100_000))
    val h2 = PaymentChannelAbi.stateHash(ContractAddress, 2L, BigInt(100_000))
    assert(h1.length == 32)
    assert(h1.toSeq != h2.toSeq)
  }

  test("submitFinalStateCalldata starts with correct selector") {
    val cd = PaymentChannelAbi.submitFinalStateCalldata(1L, BigInt(100_000), Array.fill(65)(0.toByte))
    assert(cd.length > 4)
    val sel = scalascript.crypto.CryptoBackend.get().hash(
      scalascript.crypto.HashAlgo.Keccak256,
      "submitFinalState(uint256,uint256,bytes)".getBytes("UTF-8"),
    ).take(4)
    assert(cd.take(4).toSeq == sel.toSeq)
  }

  test("finaliseCalldata is 4 bytes (selector only)") {
    val cd = PaymentChannelAbi.finaliseCalldata()
    assert(cd.length == 4)
  }

  // ── Provider ──────────────────────────────────────────────────────────────

  test("StateChannelProvider.open() with pre-deployed address skips deploy") {
    val provider = new StateChannelProvider(
      chain           = StubChain,
      strategy        = StubStrategy,
      ctx             = StubContext,
      disputeWindow   = 1.hour,
      contractAddress = Some(ContractAddress),
    )
    val ch = Await.result(provider.open(makeConfig()), 5.seconds)
    assert(ch.isInstanceOf[StateChannel])
    assert(provider.kind == ChannelKind.StateChannel)
    assert(ch.asInstanceOf[StateChannel].contractAddress == ContractAddress)
  }

  test("StateChannelProvider.open() without address deploys contract") {
    val provider = new StateChannelProvider(
      chain         = StubChain,
      strategy      = StubStrategy,
      ctx           = StubContext,
      disputeWindow = 1.hour,
    )
    val ch = Await.result(provider.open(makeConfig()), 5.seconds)
    assert(ch.isInstanceOf[StateChannel])
    assert(ch.asInstanceOf[StateChannel].contractAddress == "0xDD00000000000000000000000000000000000DD0")
  }

  // ── Stubs ──────────────────────────────────────────────────────────────────

  private object StubChain extends ChainAdapter:
    type Tx       = Unit
    type SignedTx = Unit
    def chainId               = ChainId.Base
    def supportedCurves       = Seq(Curve.Secp256k1)
    def defaultDerivationPath = "m/44'/60'/0'/0/0"
    def addressFromPublicKey(pk: PublicKey)                                  = "0x" + "0" * 40
    def isValidAddress(s: String)                                            = true
    def normalizeAddress(s: String)                                          = s.toLowerCase

    def typedDataDigest(data: TypedData): Array[Byte] = data match
      case TypedData.Raw(bytes) => bytes   // echo back for stub: digest == message
      case _                   => Array.empty

    // sig = "STUBSIG:<address>|<hash>" — enough to verify in recoverAddress
    def recoverAddress(digest: Array[Byte], sig: Array[Byte]): Option[String] =
      val s = new String(sig, "UTF-8")
      if s.startsWith("STUBSIG:") then Some(s.drop(8).takeWhile(_ != '|')) else None

    def buildTransaction(i: TxIntent, sender: String, ctx: ChainContext)       = Future.successful(())
    def prepareSigningPayload(tx: Unit, pk: PublicKey)                         = SigningPayload(Array.empty, scalascript.crypto.HashAlgo.Keccak256)
    def assembleSignedTransaction(tx: Unit, sig: Array[Byte], pk: PublicKey)   = ()
    def broadcast(tx: Unit, ctx: ChainContext)                                 = Future.successful(TxHash("0xSETTLED"))
    def describe(tx: Unit)                                                     = TxDescription("stub", Map.empty)
    def nativeBalance(a: String, c: ChainContext)                              = Future.successful(BigInt(0))
    def tokenBalance(a: Asset, h: String, c: ChainContext)                     = Future.successful(BigInt(10_000_000))
    def nonceOf(a: String, c: ChainContext)                                    = Future.successful(BigInt(0))
    def getReceipt(h: TxHash, c: ChainContext)                                 = Future.successful(None)
    def waitForReceipt(h: TxHash, c: ChainContext, t: Long)                    = Future.failed(NotImplementedError())
    def call(target: String, cd: Array[Byte], c: ChainContext)                 = Future.successful(Array.empty)
    def predictDeployAddress(d: TxIntent.Deploy, dep: String, c: ChainContext) = Future.successful("0xDD00000000000000000000000000000000000DD0")

  private object StubStrategy extends AccountStrategy:
    def kind                                             = "stub"
    def getAddress(chain: ChainAdapter): Future[String] = Future.successful(PayerAddress)

    def signTransaction(chain: ChainAdapter)(tx: chain.Tx): Future[chain.SignedTx] =
      Future.successful(tx.asInstanceOf[chain.SignedTx])

    def signMessage(chain: ChainAdapter, msg: Array[Byte]): Future[Array[Byte]] =
      // encode as "STUBSIG:<PayerAddress>|<msg_hex>" so recoverAddress can extract the address
      val msgHex = msg.map(b => f"${b & 0xff}%02x").mkString
      Future.successful(s"STUBSIG:$PayerAddress|$msgHex".getBytes("UTF-8").take(65).padTo(65, 0.toByte))

    def signTypedData(chain: ChainAdapter, typed: TypedData): Future[Array[Byte]] =
      signMessage(chain, Array.empty)

  private object StubContext extends ChainContext:
    def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] = Future.successful(ujson.Null)
    def nowSeconds: Long = System.currentTimeMillis() / 1000
