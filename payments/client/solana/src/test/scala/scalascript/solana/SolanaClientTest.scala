package scalascript.solana

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.blockchain.spi.*
import scalascript.blockchain.solana.{SolanaChainAdapter, Base58}
import scalascript.crypto.{Curve, Ed25519, PublicKey}

/** clientSolana: config shape parity with clientEvm, the turnkey `ChainContext`, and a build→sign→broadcast
 *  example driven through a MOCK Solana JSON-RPC (the real HTTP path is exercised by the devnet-gated test). */
class SolanaClientTest extends AnyFunSuite with Matchers:

  given ExecutionContext = ExecutionContext.global
  private def await[A](f: Future[A]): A = Await.result(f, 10.seconds)

  // ── config / networks ─────────────────────────────────────────────────────────

  test("SolanaNetworks expose the well-known clusters") {
    SolanaNetworks.MainnetBeta.rpcUrl shouldBe "https://api.mainnet-beta.solana.com"
    SolanaNetworks.Devnet.rpcUrl      shouldBe "https://api.devnet.solana.com"
    SolanaNetworks.Testnet.rpcUrl     shouldBe "https://api.testnet.solana.com"
    SolanaNetworks.Devnet.commitment  shouldBe "confirmed"
  }

  // ── mock JSON-RPC client ───────────────────────────────────────────────────────

  /** Records calls and returns canned envelopes matching the Solana JSON-RPC shapes. */
  class MockSolanaClient(blockhash: String) extends SolanaClient:
    var calls: List[String]        = Nil
    var sentTx: Option[String]     = None
    def rpc(method: String, params: ujson.Value*): Future[ujson.Value] =
      calls = calls :+ method
      method match
        case "getLatestBlockhash" =>
          Future.successful(ujson.Obj("value" -> ujson.Obj("blockhash" -> ujson.Str(blockhash))))
        case "sendTransaction" =>
          sentTx = Some(params.head.str)
          Future.successful(ujson.Str("MockSig11111111111111111111111111111111111"))
        case "getBalance" =>
          Future.successful(ujson.Obj("value" -> ujson.Num(424242)))
        case other => Future.failed(new RuntimeException(s"unexpected RPC: $other"))
    def getBalance(address: String): Future[BigInt]  = rpc("getBalance", ujson.Str(address)).map(v => BigInt(v.obj("value").num.toLong))
    def getLatestBlockhash(): Future[String]         = Future.successful(blockhash)
    def getTokenAccountsByOwner(owner: String, mint: Option[String]) = Future.successful(ujson.Obj("value" -> ujson.Arr()))
    def getTransaction(signature: String)            = Future.successful(None)
    def sendTransaction(base64Tx: String)            = rpc("sendTransaction", ujson.Str(base64Tx)).map(_.str)
    def getAccountInfo(address: String)              = Future.successful(None)

  // ── the deliverable: turnkey ChainContext drives build→sign→broadcast ──────────

  test("Solana.chainContext(mock) drives SolanaChainAdapter build→sign→broadcast") {
    val adapter = new SolanaChainAdapter(SolanaChainAdapter.Mainnet)
    val priv    = Array.tabulate[Byte](32)(i => (i + 1).toByte)
    val pk      = PublicKey(Curve.Ed25519, Ed25519.derivePublicKey(priv))
    val sender  = adapter.addressFromPublicKey(pk)
    val to      = "FdGYQdiRky8NtRmh3LvPqtxLE9Sq6daDek4WfEsmFrEx"

    val mock = new MockSolanaClient(Base58.encode(Array.fill[Byte](32)(7.toByte)))
    val ctx  = Solana.chainContext(mock)

    val tx      = await(adapter.buildTransaction(TxIntent.NativeTransfer(to, BigInt(1_000_000)), sender, ctx))
    val payload = adapter.prepareSigningPayload(tx, pk)
    val sig     = Ed25519.sign(priv, payload.bytes)
    val signed  = adapter.assembleSignedTransaction(tx, sig, pk)
    val hash    = await(adapter.broadcast(signed, ctx))

    hash.value shouldBe "MockSig11111111111111111111111111111111111"
    mock.calls should contain("getLatestBlockhash")   // recent blockhash fetched for the message
    mock.calls should contain("sendTransaction")       // broadcast went through the turnkey context
    mock.sentTx.isDefined shouldBe true                // a base64 tx was submitted
    java.util.Base64.getDecoder.decode(mock.sentTx.get).length should be > 64  // sig(64) + message
  }

  test("ChainContext.rpcCall returns the raw result envelope (adapter unwraps value)") {
    val mock = new MockSolanaClient("x")
    val ctx  = Solana.chainContext(mock)
    val v    = await(ctx.rpcCall("getBalance", ujson.Str("addr")))
    v.obj("value").num.toLong shouldBe 424242L
    ctx.nowSeconds should be > 0L
  }

  test("typed getBalance unwraps lamports from the value envelope") {
    val mock = new MockSolanaClient("x")
    await(mock.getBalance("addr")) shouldBe BigInt(424242)
  }

  // ── devnet integration (the runnable example) — skipped when offline ───────────

  private lazy val devnetReachable: Boolean =
    try await(Solana.connect(SolanaNetworks.Devnet).getLatestBlockhash()).nonEmpty
    catch case _: Throwable => false

  test("Solana devnet getLatestBlockhash + getBalance (skipped if unreachable)") {
    if !devnetReachable then cancel("Solana devnet not reachable")
    val client = Solana.connect(SolanaNetworks.Devnet)
    await(client.getLatestBlockhash()).length should be > 32
    await(client.getBalance("11111111111111111111111111111111")) should be >= BigInt(0)
  }
