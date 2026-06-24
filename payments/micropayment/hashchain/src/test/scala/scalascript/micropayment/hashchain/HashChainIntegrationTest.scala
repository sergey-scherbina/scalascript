package scalascript.micropayment.hashchain

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.micropayment.spi.*
import scalascript.blockchain.spi.*
import scalascript.crypto.{Curve, PublicKey}

/** Lifecycle (open → pay → receive → settle) for the PayWord hash-chain provider, plus the crypto invariants:
 *  the open commitment is signed, each preimage chains to the tip, replays/forgeries are rejected, and the
 *  deepest reveal alone proves the cumulative amount. Parity with the other `ChannelProvider`s. */
class HashChainIntegrationTest extends AnyFunSuite:

  given ExecutionContext = ExecutionContext.global
  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  private val Asset0    = Asset(ChainId.Base, "0xUSDC000000000000000000000000000000000000", "USDC", 6)
  private val PayerSeed = Array.fill[Byte](32)(0x11.toByte)
  private val UnitValue = BigInt(1000)     // 0.001 USDC per chain step
  private val Length    = 100

  private def config   = ChannelConfig(ChainId.Base, Asset0, "0xPAYEE", BigInt(0), SettlementPolicy.onClose, 1.hour)
  private def provider = new HashChainProvider(StubChain, StubContext, UnitValue, Length, PayerSeed)

  test("provider exposes the HashChain kind"):
    assert(provider.kind == ChannelKind.HashChain)

  test("the open commitment is signed by the payer and verifies; tampering fails"):
    val (_, _, commitment, sig) = provider.openPair("ch1", config)
    assert(HashChainCommitment.verify(commitment, sig, provider.payerPublicKey), "open signature must verify")
    assert(!HashChainCommitment.verify(commitment.copy(unitValue = UnitValue * 2), sig, provider.payerPublicKey),
      "a tampered commitment must not verify")

  test("open → pay → receive → settle lifecycle"):
    val (payer, payee, _, _) = provider.openPair("ch2", config)
    val r1 = await(payer.pay(UnitValue * 5)); await(payee.receive(r1))
    val r2 = await(payer.pay(UnitValue * 3)); await(payee.receive(r2))
    val r3 = await(payer.pay(UnitValue * 2)); await(payee.receive(r3))
    assert(payee.state.offChainPaid == UnitValue * 10, "10 units paid off-chain")
    await(payee.settle()) match
      case SettlementResult.Ok(_, settled) => assert(settled == UnitValue * 10)
      case other                           => fail(s"expected Ok, got $other")
    assert(payee.state.onChainPaid == UnitValue * 10)
    await(payee.settle()) match
      case SettlementResult.Fail(_) => succeed     // nothing left to settle
      case other                    => fail(s"expected Fail, got $other")

  test("the deepest reveal alone proves the cumulative amount (payee may skip intermediates)"):
    val (payer, payee, _, _) = provider.openPair("ch3", config)
    await(payer.pay(UnitValue * 4))               // payer advances; this receipt is NOT shown to the payee
    val latest = await(payer.pay(UnitValue * 6))  // cumulative = 10 units
    await(payee.receive(latest))                  // payee verifies the latest directly against the committed tip
    assert(payee.state.offChainPaid == UnitValue * 10)

  test("a forged preimage is rejected"):
    val (payer, payee, _, _) = provider.openPair("ch4", config)
    val r      = await(payer.pay(UnitValue * 3))
    val forged = r.payerSig.clone(); forged(0) = (forged(0) ^ 0xFF).toByte
    val ex = intercept[Exception](await(payee.receive(r.copy(payerSig = forged))))
    assert(ex.getMessage.contains("Invalid preimage"))

  test("a replayed / stale receipt is rejected"):
    val (payer, payee, _, _) = provider.openPair("ch5", config)
    val r1 = await(payer.pay(UnitValue * 2)); await(payee.receive(r1))
    val r2 = await(payer.pay(UnitValue * 2)); await(payee.receive(r2))
    assert(intercept[Exception](await(payee.receive(r1))).getMessage.contains("Replay"))

  test("a payee-side channel cannot pay; non-multiples and over-capacity are rejected"):
    val (payer, payee, _, _) = provider.openPair("ch6", config)
    assert(intercept[Exception](await(payee.pay(UnitValue))).getMessage.contains("cannot pay"))
    assert(intercept[Exception](await(payer.pay(UnitValue + 1))).getMessage.contains("multiple"))
    assert(intercept[Exception](await(payer.pay(UnitValue * (Length + 1)))).getMessage.contains("exhausted"))

  // ── stub chain (off-chain test; balance + no real broadcast) ─────────────────
  private object StubChain extends ChainAdapter:
    type Tx = Unit; type SignedTx = Unit
    def chainId = ChainId.Base
    def supportedCurves = Seq(Curve.Secp256k1)
    def defaultDerivationPath = "m/44'/60'/0'/0/0"
    def addressFromPublicKey(pk: PublicKey) = "0x" + "0" * 40
    def isValidAddress(s: String) = true
    def normalizeAddress(s: String) = s.toLowerCase
    def typedDataDigest(data: TypedData): Array[Byte] = Array.empty
    def recoverAddress(digest: Array[Byte], sig: Array[Byte]): Option[String] = None
    def buildTransaction(i: TxIntent, sender: String, ctx: ChainContext) = Future.successful(())
    def prepareSigningPayload(tx: Unit, pk: PublicKey) = SigningPayload(Array.empty, scalascript.crypto.HashAlgo.Keccak256)
    def assembleSignedTransaction(tx: Unit, sig: Array[Byte], pk: PublicKey) = ()
    def broadcast(tx: Unit, ctx: ChainContext) = Future.successful(TxHash("0x0"))
    def describe(tx: Unit) = TxDescription("stub", Map.empty)
    def nativeBalance(a: String, c: ChainContext) = Future.successful(BigInt(0))
    def tokenBalance(a: Asset, h: String, c: ChainContext) = Future.successful(BigInt(10_000_000))
    def nonceOf(a: String, c: ChainContext) = Future.successful(BigInt(0))
    def getReceipt(h: TxHash, c: ChainContext) = Future.successful(None)
    def waitForReceipt(h: TxHash, c: ChainContext, t: Long) = Future.failed(NotImplementedError())
    def call(target: String, cd: Array[Byte], c: ChainContext) = Future.successful(Array.empty[Byte])
    def predictDeployAddress(d: TxIntent.Deploy, dep: String, c: ChainContext) = Future.successful("0x0")

  private object StubContext extends ChainContext:
    def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] = Future.successful(ujson.Null)
    def nowSeconds: Long = System.currentTimeMillis() / 1000
