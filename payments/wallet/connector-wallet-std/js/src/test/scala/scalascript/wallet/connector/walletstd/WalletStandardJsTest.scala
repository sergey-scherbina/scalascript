package scalascript.wallet.connector.walletstd

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scalascript.blockchain.spi.*
import scalascript.crypto.{Curve, HashAlgo, PublicKey}
import scalascript.wallet.spi.*

/** Typed binding to `globalThis` for mutating browser globals in tests.
 *  Same pattern as [[WalletStandardRegisterTest]]. */
@js.native @JSGlobal("globalThis")
private object GlobalThisJs extends js.Object:
  var window:      js.Any = js.native
  var CustomEvent: js.Any = js.native

/** 6 smoke / behaviour tests for [[WalletStandardJs]] and
 *  [[StandardWalletConnectorJs]].
 *
 *  A Node.js `global.window` stub stands in for the real browser `window` so
 *  all tests run under `walletConnectorWalletStdJs/test` (Node.js via Scala.js
 *  fast-link).  No DOM dependency needed. */
class WalletStandardJsTest extends AnyFunSuite:
  implicit val ec: ExecutionContext = ExecutionContext.global

  // ── window stub ──────────────────────────────────────────────────────────

  private def stubWindow(): (js.Dynamic, scala.collection.mutable.ArrayBuffer[js.Dynamic]) =
    val dispatched = scala.collection.mutable.ArrayBuffer.empty[js.Dynamic]
    val handle = js.Dynamic.literal(
      addEventListener    = (_: String, _: js.Function1[js.Dynamic, Any]) => (),
      removeEventListener = (_: String, _: js.Function1[js.Dynamic, Any]) => (),
      dispatchEvent       = (ev: js.Dynamic) => { dispatched += ev; true },
    ).asInstanceOf[js.Dynamic]
    // No legacy `window.standard.wallets` here — tests for the event path only.
    GlobalThisJs.window = handle
    if js.isUndefined(GlobalThisJs.CustomEvent) then
      val ctor: js.Function2[String, js.Dynamic, js.Object] = (name, init) =>
        js.Dynamic.literal(`type` = name, detail = init.detail).asInstanceOf[js.Object]
      GlobalThisJs.CustomEvent = ctor
    (handle, dispatched)

  // ── stub connector ───────────────────────────────────────────────────────

  private val SolanaChain = ChainId("solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc8AS5w")
  private val testAddr    = Base58.encode(Array.fill[Byte](32)('A'))

  private def makeAdapter(cid: ChainId): ChainAdapter = new ChainAdapter:
    type Tx       = Array[Byte]
    type SignedTx = Array[Byte]
    def chainId: ChainId = cid
    def supportedCurves: Seq[Curve] = Seq(Curve.Ed25519)
    def defaultDerivationPath: String = ""
    def addressFromPublicKey(pk: PublicKey): String = testAddr
    def isValidAddress(s: String): Boolean = true
    def normalizeAddress(s: String): String = s
    def typedDataDigest(d: TypedData): Array[Byte] = new Array[Byte](32)
    def recoverAddress(d: Array[Byte], s: Array[Byte]): Option[String] = None
    def nativeBalance(a: String, c: ChainContext): Future[BigInt] = Future.successful(BigInt(0))
    def tokenBalance(a: Asset, h: String, c: ChainContext): Future[BigInt] = Future.successful(BigInt(0))
    def nonceOf(a: String, c: ChainContext): Future[BigInt] = Future.successful(BigInt(0))
    def call(t: String, cd: Array[Byte], c: ChainContext): Future[Array[Byte]] = Future.successful(Array.emptyByteArray)
    def buildTransaction(intent: TxIntent, sender: String, c: ChainContext): Future[Tx] = Future.successful(Array.emptyByteArray)
    def prepareSigningPayload(tx: Tx, signer: PublicKey): SigningPayload = SigningPayload(Array.emptyByteArray, HashAlgo.None)
    def assembleSignedTransaction(tx: Tx, sig: Array[Byte], signer: PublicKey): SignedTx = Array.emptyByteArray
    def broadcast(signed: SignedTx, c: ChainContext): Future[TxHash] = Future.successful(TxHash("sig"))
    def describe(tx: Tx): TxDescription = TxDescription("", Map.empty)
    def getReceipt(h: TxHash, c: ChainContext): Future[Option[TxReceipt]] = Future.successful(None)
    def waitForReceipt(h: TxHash, c: ChainContext, t: Long): Future[TxReceipt] = Future.failed(new RuntimeException("unused"))
    def predictDeployAddress(d: TxIntent.Deploy, deployer: String, c: ChainContext): Future[String] = Future.successful("")

  private def makeStrategy(): AccountStrategy = new AccountStrategy:
    def kind: String = "stub"
    def getAddress(chain: ChainAdapter): Future[String] = Future.successful(testAddr)
    def signTransaction(chain: ChainAdapter)(tx: chain.Tx): Future[chain.SignedTx] =
      Future.failed(new UnsupportedOperationException("stub"))
    def signMessage(chain: ChainAdapter, msg: Array[Byte]): Future[Array[Byte]] =
      Future.successful(Array.fill(64)(0x01.toByte))
    def signTypedData(chain: ChainAdapter, typed: TypedData): Future[Array[Byte]] =
      Future.successful(Array.fill(64)(0x02.toByte))

  private def stubManager(): AccountManager = new AccountManager:
    def chains: Set[ChainId] = Set(SolanaChain)
    def strategyFor(c: ChainId): Option[AccountStrategy] =
      if c == SolanaChain then Some(makeStrategy()) else None
    def adapterFor(c: ChainId): Option[ChainAdapter] =
      if c == SolanaChain then Some(makeAdapter(c)) else None
    def request(req: DappRequest): Future[DappResponse] =
      Future.successful(DappResponse.Ok(ujson.Null))

  private def stubCtx: ChainContext = new ChainContext:
    def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] =
      Future.failed(new RuntimeException(s"unmocked: $method"))
    def nowSeconds: Long = 0L

  private def newConnector(): WalletStandardConnector =
    val mgr = stubManager()
    val c   = new WalletStandardConnector(mgr, _ => stubCtx, SolanaChain)
    c.attach(mgr)
    c

  private def walletInfo(
    name:   String         = "Test Wallet",
    icon:   String         = "data:image/svg+xml;base64,",
    chains: js.Array[String] = js.Array(SolanaChain.toString),
  ): WalletInfo =
    js.Dynamic.literal(
      name     = name,
      icon     = icon,
      chains   = chains,
      features = js.Dictionary.empty[js.Any],
    ).asInstanceOf[WalletInfo]

  // ── test 1 ───────────────────────────────────────────────────────────────

  test("register dispatches a wallet-standard:register-wallet event on window") {
    val (_, dispatched) = stubWindow()
    WalletStandardJs.register(walletInfo(), newConnector())
    val evs = dispatched.toSeq.filter(_.`type`.asInstanceOf[String] == "wallet-standard:register-wallet")
    assert(evs.size == 1, "exactly one wallet-standard:register-wallet event must be dispatched")
  }

  // ── test 2 ───────────────────────────────────────────────────────────────

  test("event detail has a register(callback) function") {
    val (_, dispatched) = stubWindow()
    WalletStandardJs.register(walletInfo(), newConnector())
    val ev = dispatched.find(_.`type`.asInstanceOf[String] == "wallet-standard:register-wallet").get
    val detail = ev.detail
    assert(!js.isUndefined(detail), "event detail must be set")
    assert(js.typeOf(detail) == "function", "detail must be a function (the register callback)")
  }

  // ── test 3 ───────────────────────────────────────────────────────────────

  test("calling register(callback) in event detail invokes callback with the wallet object") {
    val (_, dispatched) = stubWindow()
    WalletStandardJs.register(walletInfo(name = "My Wallet"), newConnector())
    val ev       = dispatched.find(_.`type`.asInstanceOf[String] == "wallet-standard:register-wallet").get
    val register = ev.detail.asInstanceOf[js.Function1[js.Function1[js.Dynamic, Any], Unit]]
    var received: js.Dynamic = js.Dynamic.literal()
    register((w: js.Dynamic) => { received = w; () })
    assert(received.name.asInstanceOf[String] == "My Wallet", "callback must receive wallet with correct name")
  }

  // ── test 4 ───────────────────────────────────────────────────────────────

  test("wallet object features map contains standard:connect key") {
    val (_, dispatched) = stubWindow()
    WalletStandardJs.register(walletInfo(), newConnector())
    val ev       = dispatched.find(_.`type`.asInstanceOf[String] == "wallet-standard:register-wallet").get
    val register = ev.detail.asInstanceOf[js.Function1[js.Function1[js.Dynamic, Any], Unit]]
    var wallet: js.Dynamic = js.Dynamic.literal()
    register((w: js.Dynamic) => { wallet = w; () })
    val features = wallet.features
    assert(
      !js.isUndefined(features.selectDynamic("standard:connect")),
      "features must include standard:connect",
    )
  }

  // ── test 5 ───────────────────────────────────────────────────────────────

  test("wallet object chains includes a Solana chain ID") {
    val (_, dispatched) = stubWindow()
    WalletStandardJs.register(walletInfo(), newConnector())
    val ev       = dispatched.find(_.`type`.asInstanceOf[String] == "wallet-standard:register-wallet").get
    val register = ev.detail.asInstanceOf[js.Function1[js.Function1[js.Dynamic, Any], Unit]]
    var wallet: js.Dynamic = js.Dynamic.literal()
    register((w: js.Dynamic) => { wallet = w; () })
    val chains = wallet.chains.asInstanceOf[js.Array[String]]
    assert(
      chains.exists(_.startsWith("solana:")),
      s"chains must include at least one solana: entry, got: ${chains.toSeq}",
    )
  }

  // ── test 6 ───────────────────────────────────────────────────────────────

  test("solana:signMessage feature has a signMessage function property") {
    val conn = newConnector()
    val jsConn = new StandardWalletConnectorJs(conn)
    val features = jsConn.featuresJs
    val signMsgFeature = features.selectDynamic("solana:signMessage")
    assert(!js.isUndefined(signMsgFeature), "solana:signMessage must be present in features")
    val signMsg = signMsgFeature.signMessage
    assert(js.typeOf(signMsg) == "function", "solana:signMessage.signMessage must be a function")
  }
