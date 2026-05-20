package scalascript.wallet.connector.eip1193

import org.scalatest.funsuite.AsyncFunSuite
import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scalascript.blockchain.spi.*
import scalascript.crypto.{Curve, HashAlgo, PublicKey}
import scalascript.wallet.spi.*
import scala.concurrent.Future

/** Direct binding to the host's `globalThis` so the test can mutate
 *  globals (`window`, `CustomEvent`) without tripping Scala.js'
 *  "loading the global scope as a value" rule. */
@js.native @JSGlobal("globalThis")
private object GlobalThis extends js.Object:
  var window:      js.Any = js.native
  var CustomEvent: js.Any = js.native

/** Node-side smoke + behaviour tests for the EIP-1193 / EIP-6963 JS
 *  facade.  The browser globals (`window`, `CustomEvent`) are stubbed
 *  via plain JS objects assigned to `globalThis` before the test
 *  constructs `WindowEthereumProvider` — scalajs-dom looks up
 *  `window` lazily through `org.scalajs.dom`, which delegates to
 *  `globalThis.window`. */
class WindowEthereumProviderTest extends AsyncFunSuite:
  implicit override def executionContext: ExecutionContext = ExecutionContext.global

  // ── window stub ────────────────────────────────────────────────────────

  private def stubWindow(): TestWindow =
    val w = new TestWindow
    GlobalThis.window = w.handle
    if js.isUndefined(GlobalThis.CustomEvent) then
      // Minimal CustomEvent constructor — captures (name, {detail}).
      val ctor: js.Function2[String, js.Dynamic, js.Object] = (name, init) =>
        js.Dynamic.literal(`type` = name, detail = init.detail).asInstanceOf[js.Object]
      GlobalThis.CustomEvent = ctor
    w

  /** Mock window with `addEventListener`/`removeEventListener`/`dispatchEvent`
   *  recording.  Listeners are invoked synchronously on dispatch. */
  private class TestWindow:
    val listeners = scala.collection.mutable.Map.empty[String, scala.collection.mutable.ArrayBuffer[js.Function1[js.Dynamic, Any]]]
    val dispatched = scala.collection.mutable.ArrayBuffer.empty[js.Dynamic]
    var ethereum: js.UndefOr[js.Dynamic] = js.undefined

    val handle: js.Dynamic = js.Dynamic.literal(
      addEventListener = (kind: String, cb: js.Function1[js.Dynamic, Any]) =>
        listeners.getOrElseUpdate(kind, scala.collection.mutable.ArrayBuffer.empty) += cb,
      removeEventListener = (kind: String, cb: js.Function1[js.Dynamic, Any]) =>
        listeners.get(kind).foreach(_ -= cb),
      dispatchEvent = (ev: js.Dynamic) => {
        dispatched += ev
        val kind = ev.`type`.asInstanceOf[String]
        listeners.get(kind).foreach(_.foreach(_(ev)))
        true
      },
    ).asInstanceOf[js.Dynamic]
    // mirror the ethereum slot so `dom.window.ethereum = …` reads back
    handle.updateDynamic("ethereum")(js.undefined)

  // ── minimal AccountManager / ChainAdapter stubs ────────────────────────

  // A toy ChainAdapter that knows how to surface a single address.
  // It does not exercise real signing — just enough for
  // `eth_chainId` / `eth_accounts` / unsupported-method tests.
  private def stubManager(addr: String, chainId: ChainId): AccountManager =
    new AccountManager:
      def chains: Set[ChainId] = Set(chainId)
      def strategyFor(c: ChainId): Option[AccountStrategy] =
        if c == chainId then Some(stubStrategy(addr)) else None
      def adapterFor(c: ChainId): Option[ChainAdapter] =
        if c == chainId then Some(stubAdapter(chainId, addr)) else None
      def request(req: DappRequest): Future[DappResponse] =
        Future.successful(DappResponse.Ok(ujson.Null))

  private def stubStrategy(addr: String): AccountStrategy = new AccountStrategy:
    def kind: String = "stub"
    def getAddress(chain: ChainAdapter): Future[String] = Future.successful(addr)
    def signTransaction(chain: ChainAdapter)(tx: chain.Tx): Future[chain.SignedTx] =
      Future.failed(new UnsupportedOperationException("stub-signer"))
    def signMessage(chain: ChainAdapter, msg: Array[Byte]): Future[Array[Byte]] =
      Future.successful(Array.fill(65)(0x42.toByte))
    def signTypedData(chain: ChainAdapter, typed: TypedData): Future[Array[Byte]] =
      Future.successful(Array.fill(65)(0x42.toByte))

  private def stubAdapter(cid: ChainId, addr: String): ChainAdapter = new ChainAdapter:
    type Tx       = Array[Byte]
    type SignedTx = Array[Byte]
    def chainId: ChainId = cid
    def supportedCurves: Seq[Curve] = Seq(Curve.Secp256k1)
    def defaultDerivationPath: String = "m/44'/60'/0'/0/0"
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

  private def stubCtxFor: ChainId => ChainContext = _ => new ChainContext:
    def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] =
      Future.failed(new RuntimeException(s"unmocked: $method"))
    def nowSeconds: Long = 0L

  private def newWindow(chainId: ChainId): (TestWindow, WindowEthereumProvider) =
    val w   = stubWindow()
    val mgr = stubManager("0x1111111111111111111111111111111111111111", chainId)
    val p   = new Eip1193Provider(mgr, stubCtxFor, chainId)
    p.attach(mgr)
    val win = new WindowEthereumProvider(
      p,
      Eip6963ProviderInfo(
        uuid = "test-uuid",
        name = "ScalaScript Test Wallet",
        icon = "data:image/svg+xml;base64,",
        rdns = "io.scalascript.test",
      ),
    )
    (w, win)

  // ── tests ──────────────────────────────────────────────────────────────

  test("request({method:\"eth_chainId\"}) returns the active chain id hex") {
    val (_, win) = newWindow(ChainId.Base)
    val req = js.Dynamic.literal(method = "eth_chainId", params = js.Array())
    win.requestJs(req).toFuture.map { v =>
      assert(v.asInstanceOf[String] == "0x2105")
    }
  }

  test("install() emits eip6963:announceProvider on window") {
    val (w, win) = newWindow(ChainId.Base)
    win.install()
    val announces = w.dispatched.toSeq.filter(_.`type`.asInstanceOf[String] == "eip6963:announceProvider")
    assert(announces.nonEmpty, "install() must dispatch eip6963:announceProvider")
    val detail = announces.head.detail
    assert(detail.info.uuid.asInstanceOf[String] == "test-uuid")
    assert(detail.info.name.asInstanceOf[String] == "ScalaScript Test Wallet")
    assert(detail.info.rdns.asInstanceOf[String] == "io.scalascript.test")
    assert(!js.isUndefined(detail.provider), "announcement carries provider handle")
  }

  test("eip6963:requestProvider triggers a fresh announce") {
    val (w, win) = newWindow(ChainId.Base)
    win.install()
    val beforeAnnounces = w.dispatched.count(_.`type`.asInstanceOf[String] == "eip6963:announceProvider")
    val ev = js.Dynamic.newInstance(js.Dynamic.global.CustomEvent)(
      "eip6963:requestProvider", js.Dynamic.literal(detail = js.Dynamic.literal()),
    ).asInstanceOf[js.Dynamic]
    w.handle.dispatchEvent(ev)
    val afterAnnounces = w.dispatched.count(_.`type`.asInstanceOf[String] == "eip6963:announceProvider")
    assert(afterAnnounces == beforeAnnounces + 1, "request must trigger one more announce")
  }

  test("requestJs on an unsupported method rejects with EIP-1193 code 4200") {
    val (_, win) = newWindow(ChainId.Base)
    val req = js.Dynamic.literal(method = "foo_bar", params = js.Array())
    win.requestJs(req).toFuture.transform {
      case scala.util.Failure(ex: Eip1193Errors.ProviderError) =>
        scala.util.Success(assert(ex.code == 4200))
      case scala.util.Failure(other) =>
        scala.util.Success(fail(s"expected ProviderError(4200), got $other"))
      case scala.util.Success(v) =>
        scala.util.Success(fail(s"expected rejection, got resolved $v"))
    }
  }

  test("install() binds window.ethereum") {
    val (w, win) = newWindow(ChainId.Base)
    win.install()
    val eth = w.handle.ethereum
    assert(!js.isUndefined(eth), "window.ethereum must be bound after install()")
    assert(js.typeOf(eth.request) == "function")
  }
