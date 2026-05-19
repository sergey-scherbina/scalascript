package scalascript.wallet.connector.eip1193

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.blockchain.evm.EvmChainAdapter
import scalascript.blockchain.spi.*
import scalascript.crypto.Curve
import scalascript.wallet.spi.*
import scalascript.wallet.strategy.eoa.{EoaStrategy, RawPrivateKeyVault}

/** EIP-1193 protocol translator tests. We exercise the request handler
 *  with a real EoaStrategy + EvmChainAdapter so the signature outputs
 *  are real bytes; only the chain RPC is mocked. */
class Eip1193ProviderTest extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  // Real signer over a known key — its address is the only "account"
  // the provider knows about.
  private val privKey  = "0x" + "11" * 32
  private val vault    = RawPrivateKeyVault.fromHex("test", privKey, Curve.Secp256k1)
  private val signer   = Await.result(vault.getSigner(Curve.Secp256k1, "raw"), 2.seconds)
  private val strategy = new EoaStrategy(signer)
  private val adapter  = new EvmChainAdapter(ChainId.Base)
  private val addr     = adapter.addressFromPublicKey(signer.publicKey)

  /** Minimal AccountManager bound to one chain + one strategy. */
  private val mgr: AccountManager = new AccountManager:
    def chains: Set[ChainId] = Set(ChainId.Base, ChainId.BaseSepolia)
    def strategyFor(c: ChainId): Option[AccountStrategy] =
      if c == ChainId.Base || c == ChainId.BaseSepolia then Some(strategy) else None
    def adapterFor(c: ChainId): Option[ChainAdapter] =
      if c == ChainId.Base then Some(adapter)
      else if c == ChainId.BaseSepolia then Some(new EvmChainAdapter(ChainId.BaseSepolia))
      else None
    def request(req: DappRequest): Future[DappResponse] =
      Future.successful(DappResponse.Ok(ujson.Null))   // unused by this test

  /** ChainContext used for both passthrough RPC and tx broadcast. */
  private val mockReplies = scala.collection.mutable.Map.empty[String, ujson.Value]
  private val ctxFor: ChainId => ChainContext = _ => new ChainContext:
    def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] =
      mockReplies.get(method) match
        case Some(v) => Future.successful(v)
        case None    => Future.failed(new RuntimeException(s"unmocked: $method"))
    def nowSeconds: Long = 1700000000L

  private def newProvider(active: ChainId = ChainId.Base) =
    val p = new Eip1193Provider(mgr, ctxFor, active)
    p.attach(mgr)
    p

  // ── discovery ─────────────────────────────────────────────────────────

  test("eth_accounts returns the bound address before attach() → disconnected") {
    val p = new Eip1193Provider(mgr, ctxFor, ChainId.Base)
    val ex = intercept[Eip1193Errors.ProviderError] {
      Await.result(p.request("eth_accounts"), 2.seconds)
    }
    assert(ex.code == 4900)
  }

  test("eth_accounts after attach returns the bound address") {
    val p = newProvider()
    val result = Await.result(p.request("eth_accounts"), 2.seconds)
    assert(result.arr.size == 1)
    assert(result.arr.head.str.equalsIgnoreCase(addr))
  }

  test("eth_chainId returns the active chain in 0x hex") {
    val p = newProvider(ChainId.Base)
    val cid = Await.result(p.request("eth_chainId"), 2.seconds)
    assert(cid.str == "0x2105")     // 8453 in hex
  }

  test("wallet_switchEthereumChain updates active chain") {
    val p = newProvider(ChainId.Base)
    Await.result(
      p.request("wallet_switchEthereumChain", ujson.Arr(ujson.Obj("chainId" -> ujson.Str("0x14a34")))),
      2.seconds,
    )
    val cid = Await.result(p.request("eth_chainId"), 2.seconds)
    assert(cid.str == "0x14a34")     // 84532 = BaseSepolia
  }

  test("wallet_switchEthereumChain rejects unknown chain") {
    val p = newProvider(ChainId.Base)
    val ex = intercept[Eip1193Errors.ProviderError] {
      Await.result(
        p.request("wallet_switchEthereumChain", ujson.Arr(ujson.Obj("chainId" -> ujson.Str("0x1")))),
        2.seconds,
      )
    }
    assert(ex.code == 4901)
  }

  // ── signing ───────────────────────────────────────────────────────────

  test("personal_sign returns a 65-byte hex signature") {
    val p = newProvider()
    val params = ujson.Arr(
      ujson.Str("0x" + "deadbeef" * 4),
      ujson.Str(addr),
    )
    val sig = Await.result(p.request("personal_sign", params), 5.seconds).str
    assert(sig.startsWith("0x"))
    assert(sig.length == 2 + 130)   // 0x + 65 bytes hex
  }

  test("personal_sign accepts reversed [address, data] argument order") {
    val p = newProvider()
    val sigA = Await.result(
      p.request("personal_sign", ujson.Arr(ujson.Str("0x" + "ab" * 32), ujson.Str(addr))),
      5.seconds,
    ).str
    val sigB = Await.result(
      p.request("personal_sign", ujson.Arr(ujson.Str(addr), ujson.Str("0x" + "ab" * 32))),
      5.seconds,
    ).str
    // RFC-6979 deterministic → identical signatures
    assert(sigA == sigB)
  }

  test("eth_signTypedData_v4 returns a 65-byte hex signature") {
    val p = newProvider()
    val typedData = ujson.Obj(
      "domain" -> ujson.Obj(
        "name"              -> ujson.Str("USD Coin"),
        "version"           -> ujson.Str("2"),
        "chainId"           -> ujson.Str("8453"),
        "verifyingContract" -> ujson.Str("0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"),
      ),
      "types" -> ujson.Obj(
        "EIP712Domain" -> ujson.Arr(
          ujson.Obj("name" -> ujson.Str("name"),              "type" -> ujson.Str("string")),
          ujson.Obj("name" -> ujson.Str("version"),           "type" -> ujson.Str("string")),
          ujson.Obj("name" -> ujson.Str("chainId"),           "type" -> ujson.Str("uint256")),
          ujson.Obj("name" -> ujson.Str("verifyingContract"), "type" -> ujson.Str("address")),
        ),
        "TransferWithAuthorization" -> ujson.Arr(
          ujson.Obj("name" -> ujson.Str("from"),        "type" -> ujson.Str("address")),
          ujson.Obj("name" -> ujson.Str("to"),          "type" -> ujson.Str("address")),
          ujson.Obj("name" -> ujson.Str("value"),       "type" -> ujson.Str("uint256")),
          ujson.Obj("name" -> ujson.Str("validAfter"),  "type" -> ujson.Str("uint256")),
          ujson.Obj("name" -> ujson.Str("validBefore"), "type" -> ujson.Str("uint256")),
          ujson.Obj("name" -> ujson.Str("nonce"),       "type" -> ujson.Str("bytes32")),
        ),
      ),
      "primaryType" -> ujson.Str("TransferWithAuthorization"),
      "message" -> ujson.Obj(
        "from"        -> ujson.Str(addr),
        "to"          -> ujson.Str("0x1111111111111111111111111111111111111111"),
        "value"       -> ujson.Str("1000000"),
        "validAfter"  -> ujson.Str("0"),
        "validBefore" -> ujson.Str("9999999999"),
        "nonce"       -> ujson.Str("0x" + "ab" * 32),
      ),
    )
    val sig = Await.result(
      p.request("eth_signTypedData_v4", ujson.Arr(ujson.Str(addr), typedData)),
      5.seconds,
    ).str
    assert(sig.startsWith("0x"))
    assert(sig.length == 2 + 130)
  }

  test("eth_signTypedData_v4 also accepts a stringified JSON payload") {
    val p = newProvider()
    val tdString = """{
      "domain": {"name":"Mail","version":"1","chainId":"1","verifyingContract":"0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC"},
      "types": {
        "EIP712Domain":[{"name":"name","type":"string"},{"name":"version","type":"string"},{"name":"chainId","type":"uint256"},{"name":"verifyingContract","type":"address"}],
        "Greeting":[{"name":"text","type":"string"}]
      },
      "primaryType": "Greeting",
      "message": {"text":"hello"}
    }"""
    val sig = Await.result(
      p.request("eth_signTypedData_v4", ujson.Arr(ujson.Str(addr), ujson.Str(tdString))),
      5.seconds,
    ).str
    assert(sig.startsWith("0x"))
    assert(sig.length == 2 + 130)
  }

  // ── unsupported / errors ─────────────────────────────────────────────

  test("unknown method returns 4200") {
    val p = newProvider()
    val ex = intercept[Eip1193Errors.ProviderError] {
      Await.result(p.request("foo_bar"), 2.seconds)
    }
    assert(ex.code == 4200)
    assert(ex.message.contains("foo_bar"))
  }

  test("personal_sign with invalid params surfaces -32602") {
    val p = newProvider()
    val ex = intercept[Eip1193Errors.ProviderError] {
      Await.result(p.request("personal_sign", ujson.Arr(ujson.Str("0xdeadbeef"))), 2.seconds)
    }
    assert(ex.code == -32602)
  }

  // ── RPC passthrough ──────────────────────────────────────────────────

  test("eth_blockNumber passes through to chain RPC") {
    mockReplies.clear()
    mockReplies("eth_blockNumber") = ujson.Str("0x1234")
    val p = newProvider()
    val result = Await.result(p.request("eth_blockNumber"), 2.seconds)
    assert(result.str == "0x1234")
  }

  test("detach() puts provider into disconnected state") {
    val p = newProvider()
    p.detach()
    val ex = intercept[Eip1193Errors.ProviderError] {
      Await.result(p.request("eth_accounts"), 2.seconds)
    }
    assert(ex.code == 4900)
  }
