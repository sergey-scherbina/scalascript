package scalascript.wallet.connector.walletstd

import org.scalatest.funsuite.AsyncFunSuite
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scalascript.blockchain.spi.*
import scalascript.crypto.{Curve, HashAlgo, PublicKey}
import scalascript.wallet.spi.*

/** Typed binding to the host's `globalThis` so the test can mutate
 *  globals without tripping the "loading the global scope as a value"
 *  rule.  See `WindowEthereumProviderTest` for the same pattern. */
@js.native @JSGlobal("globalThis")
private object GlobalThis extends js.Object:
  var window:      js.Any = js.native
  var CustomEvent: js.Any = js.native

/** Node-side smoke + behaviour tests for the Wallet Standard JS
 *  registration glue.  Plain JS object stubs stand in for the real
 *  browser globals — Wallet Standard registration is purely a DOM
 *  event + a `standard.wallets.registerWallet` call. */
class WalletStandardRegisterTest extends AsyncFunSuite:
  implicit override def executionContext: ExecutionContext = ExecutionContext.global

  // ── window stub ────────────────────────────────────────────────────────

  private class TestWindow:
    val dispatched = scala.collection.mutable.ArrayBuffer.empty[js.Dynamic]
    val registered = scala.collection.mutable.ArrayBuffer.empty[js.Dynamic]

    val handle: js.Dynamic = js.Dynamic.literal(
      addEventListener    = (_: String, _: js.Function1[js.Dynamic, Any]) => (),
      removeEventListener = (_: String, _: js.Function1[js.Dynamic, Any]) => (),
      dispatchEvent       = (ev: js.Dynamic) => { dispatched += ev; true },
    ).asInstanceOf[js.Dynamic]
    // Expose the legacy push-style registry slot.
    val standardWallets: js.Dynamic = js.Dynamic.literal(
      registerWallet = (w: js.Dynamic) => { registered += w; () },
    ).asInstanceOf[js.Dynamic]
    handle.updateDynamic("standard")(js.Dynamic.literal(wallets = standardWallets))

  private def stubWindow(): TestWindow =
    val w  = new TestWindow
    GlobalThis.window = w.handle
    if js.isUndefined(GlobalThis.CustomEvent) then
      val ctor: js.Function2[String, js.Dynamic, js.Object] = (name, init) =>
        js.Dynamic.literal(`type` = name, detail = init.detail).asInstanceOf[js.Object]
      GlobalThis.CustomEvent = ctor
    w

  // ── stub connector ─────────────────────────────────────────────────────

  private def stubManager(address: String, cid: ChainId): AccountManager = new AccountManager:
    def chains: Set[ChainId] = Set(cid)
    def strategyFor(c: ChainId): Option[AccountStrategy] =
      if c == cid then Some(makeStrategy(address)) else None
    def adapterFor(c: ChainId): Option[ChainAdapter] =
      if c == cid then Some(makeAdapter(cid, address)) else None
    def request(req: DappRequest): Future[DappResponse] =
      Future.successful(DappResponse.Ok(ujson.Null))

  private def makeStrategy(addr: String): AccountStrategy = new AccountStrategy:
    def kind: String = "stub"
    def getAddress(chain: ChainAdapter): Future[String] = Future.successful(addr)
    def signTransaction(chain: ChainAdapter)(tx: chain.Tx): Future[chain.SignedTx] =
      Future.failed(new UnsupportedOperationException("stub-signer"))
    def signMessage(chain: ChainAdapter, msg: Array[Byte]): Future[Array[Byte]] =
      // Deterministic stub signature so tests can assert byte equality.
      Future.successful(Array.fill(64)(0x01.toByte))
    def signTypedData(chain: ChainAdapter, typed: TypedData): Future[Array[Byte]] =
      Future.successful(Array.fill(64)(0x02.toByte))

  private def makeAdapter(cid: ChainId, addr: String): ChainAdapter = new ChainAdapter:
    type Tx       = Array[Byte]
    type SignedTx = Array[Byte]
    def chainId: ChainId = cid
    def supportedCurves: Seq[Curve] = Seq(Curve.Ed25519)
    def defaultDerivationPath: String = ""
    def addressFromPublicKey(pk: PublicKey): String = addr
    def isValidAddress(s: String): Boolean = true
    def normalizeAddress(s: String): String = s
    def typedDataDigest(d: TypedData): Array[Byte] = new Array[Byte](32)
    def recoverAddress(d: Array[Byte], s: Array[Byte]): Option[String] = None
    def nativeBalance(a: String, c: ChainContext): Future[BigInt] = Future.successful(BigInt(0))
    def tokenBalance(a: Asset, h: String, c: ChainContext): Future[BigInt] = Future.successful(BigInt(0))
    def nonceOf(a: String, c: ChainContext): Future[BigInt] = Future.successful(BigInt(0))
    def call(target: String, calldata: Array[Byte], c: ChainContext): Future[Array[Byte]] =
      Future.successful(Array.emptyByteArray)
    def buildTransaction(intent: TxIntent, sender: String, c: ChainContext): Future[Tx] =
      Future.successful(Array.emptyByteArray)
    def prepareSigningPayload(tx: Tx, signer: PublicKey): SigningPayload =
      SigningPayload(Array.emptyByteArray, HashAlgo.None)
    def assembleSignedTransaction(tx: Tx, signature: Array[Byte], signer: PublicKey): SignedTx =
      Array.emptyByteArray
    def broadcast(signed: SignedTx, c: ChainContext): Future[TxHash] = Future.successful(TxHash("0x"))
    def describe(tx: Tx): TxDescription = TxDescription("", Map.empty)
    def getReceipt(h: TxHash, c: ChainContext): Future[Option[TxReceipt]] = Future.successful(None)
    def waitForReceipt(h: TxHash, c: ChainContext, t: Long): Future[TxReceipt] =
      Future.failed(new RuntimeException("not used"))
    def predictDeployAddress(deploy: TxIntent.Deploy, deployer: String, c: ChainContext): Future[String] =
      Future.successful("")

  private val SolanaChain = ChainId("solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc8AS5w")

  // ed25519 public key for a known test wallet — we don't care that the
  // base58 isn't "real" here; we only use it as an opaque identifier.
  // 32 'A' bytes ⇒ base58 "16VuFW9unEsTzNTakL68B57Dp7gFGSMqL"
  private val testAddr =
    Base58.encode(Array.fill[Byte](32)('A'))

  private def newConn(): WalletStandardConnectorBase =
    val mgr = stubManager(testAddr, SolanaChain)
    val c   = new WalletStandardConnector(mgr, _ => stubCtx, SolanaChain)
    c.attach(mgr)
    c

  private def stubCtx: ChainContext = new ChainContext:
    def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] =
      Future.failed(new RuntimeException(s"unmocked: $method"))
    def nowSeconds: Long = 0L

  // ── tests ──────────────────────────────────────────────────────────────

  test("register() dispatches wallet-standard:register-wallet event with a callback") {
    val w   = stubWindow()
    val reg = new WalletStandardRegister(
      newConn(),
      WalletStandardInfo(
        name = "ScalaScript Test Wallet",
        icon = "data:image/svg+xml;base64,",
        chains = Seq(SolanaChain.toString),
      ),
    )
    reg.register()
    val evs = w.dispatched.toSeq.filter(_.`type`.asInstanceOf[String] == "wallet-standard:register-wallet")
    assert(evs.size == 1, "exactly one register event must be dispatched")
    val cb = evs.head.detail.asInstanceOf[js.Function1[js.Function1[js.Dynamic, Any], Unit]]
    // The callback (invoked by the dApp) must hand back the wallet object.
    var captured: js.Dynamic = js.Dynamic.literal()
    val attach: js.Function1[js.Dynamic, Any] = (w: js.Dynamic) => { captured = w; () }
    cb(attach)
    assert(captured.name.asInstanceOf[String] == "ScalaScript Test Wallet")
    assert(captured.version.asInstanceOf[String] == "1.0.0")
    assert(!js.isUndefined(captured.features.selectDynamic("solana:signMessage")))
    succeed
  }

  test("register() also invokes window.standard.wallets.registerWallet for legacy dApps") {
    val w   = stubWindow()
    val reg = new WalletStandardRegister(
      newConn(),
      WalletStandardInfo(
        name = "ScalaScript Test Wallet",
        icon = "data:image/svg+xml;base64,",
        chains = Seq(SolanaChain.toString),
      ),
    )
    reg.register()
    assert(w.registered.size == 1, "legacy registerWallet must be called once")
    val wallet = w.registered.head
    assert(wallet.name.asInstanceOf[String] == "ScalaScript Test Wallet")
    val features = wallet.features
    assert(!js.isUndefined(features.selectDynamic("standard:connect")))
    assert(!js.isUndefined(features.selectDynamic("solana:signTransaction")))
    succeed
  }

  test("feature `solana:signMessage` round-trips into the underlying connector") {
    val _   = stubWindow()
    val reg = new WalletStandardRegister(
      newConn(),
      WalletStandardInfo(
        name = "ScalaScript Test Wallet",
        icon = "data:image/svg+xml;base64,",
        chains = Seq(SolanaChain.toString),
      ),
    )
    val wallet  = reg.walletJs
    val signMsg = wallet.features.selectDynamic("solana:signMessage").signMessage
    assert(js.typeOf(signMsg) == "function", "solana:signMessage must be a callable")
    val input = js.Dynamic.literal(
      account = js.Dynamic.literal(address = testAddr),
      message = "aGVsbG8=", // base64("hello")
    )
    val promise = signMsg(input).asInstanceOf[js.Promise[js.Any]]
    promise.toFuture.map { out =>
      val asDyn = out.asInstanceOf[js.Dynamic]
      assert(asDyn.signature.asInstanceOf[String].nonEmpty)
      assert(asDyn.signedMessage.asInstanceOf[String] == "aGVsbG8=")
    }
  }

  test("feature `standard:connect` returns the wallet's accounts list") {
    val _   = stubWindow()
    val reg = new WalletStandardRegister(
      newConn(),
      WalletStandardInfo(
        name = "ScalaScript Test Wallet",
        icon = "data:image/svg+xml;base64,",
        chains = Seq(SolanaChain.toString),
      ),
    )
    val wallet  = reg.walletJs
    val connect = wallet.features.selectDynamic("standard:connect").connect
    val promise = connect(js.Dynamic.literal()).asInstanceOf[js.Promise[js.Any]]
    promise.toFuture.map { out =>
      val accounts = out.asInstanceOf[js.Dynamic].accounts.asInstanceOf[js.Array[js.Dynamic]]
      assert(accounts.length == 1, "connect must return one account")
      assert(accounts(0).address.asInstanceOf[String] == testAddr)
    }
  }
