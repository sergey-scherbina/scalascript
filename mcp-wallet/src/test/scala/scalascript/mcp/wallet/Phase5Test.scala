package scalascript.mcp.wallet

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.blockchain.evm.EvmChainAdapter
import scalascript.blockchain.spi.*
import scalascript.crypto.Curve
import scalascript.mcp.{McpServerBuilder, ToolHandlerResult}
import scalascript.wallet.spi.*
import scalascript.wallet.strategy.eoa.{EoaStrategy, RawPrivateKeyVault}

/** Phase 5 tests: `wallet.sendTransaction` end-to-end (build →
 *  sign → broadcast against a stubbed RPC) and
 *  `ConfirmationMode.ElicitationCached(ttl)` semantics. */
class Phase5Test extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  private val privKey  = "0x" + "55" * 32
  private val vault    = RawPrivateKeyVault.fromHex("p5-wallet", privKey, Curve.Secp256k1)
  private val signer   = Await.result(vault.getSigner(Curve.Secp256k1, "raw"), 2.seconds)
  private val strategy = new EoaStrategy(signer)
  private val baseAdpt = new EvmChainAdapter(ChainId.Base)
  private val addr     = baseAdpt.addressFromPublicKey(signer.publicKey)

  private val mgr: AccountManager = new AccountManager:
    def chains: Set[ChainId] = Set(ChainId.Base)
    def strategyFor(c: ChainId): Option[AccountStrategy] = if c == ChainId.Base then Some(strategy) else None
    def adapterFor(c: ChainId): Option[ChainAdapter]     = if c == ChainId.Base then Some(baseAdpt) else None
    def request(req: DappRequest): Future[DappResponse]  = Future.successful(DappResponse.Ok(ujson.Null))

  /** RPC stub that the EvmChainAdapter pipeline pokes during
   *  buildTransaction / broadcast. */
  private val canned = scala.collection.mutable.Map.empty[String, ujson.Value]
  private val rpcCalls = scala.collection.mutable.ArrayBuffer.empty[(String, Seq[ujson.Value])]
  private val ctxFor: ChainId => ChainContext = _ => new ChainContext:
    def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] =
      rpcCalls += ((method, params.toSeq))
      canned.get(method) match
        case Some(v) => Future.successful(v)
        case None    => Future.failed(new RuntimeException(s"unmocked: $method"))
    def nowSeconds: Long = 1700000000L

  private def primeRpc(broadcastHash: String = "0xfeedface"): Unit =
    canned.clear()
    rpcCalls.clear()
    canned("eth_getTransactionCount")  = ujson.Str("0x7")
    canned("eth_estimateGas")          = ujson.Str("0x5208")
    canned("eth_maxPriorityFeePerGas") = ujson.Str("0x77359400")
    canned("eth_getBlockByNumber")     = ujson.Obj("baseFeePerGas" -> ujson.Str("0x77359400"))
    canned("eth_sendRawTransaction")   = ujson.Str(broadcastHash)

  private class RecordingHandler(approve: Boolean) extends ElicitationHandler:
    val requests = scala.collection.mutable.ArrayBuffer.empty[ElicitationRequest]
    def confirm(req: ElicitationRequest): Future[Boolean] =
      requests += req
      Future.successful(approve)

  private def newServer(
    p:      Policy,
    elicit: ElicitationHandler,
  ): (McpWalletServer, McpServerBuilder) =
    val srv = new McpWalletServer(vault, mgr, p, ctxFor, Some(elicit))
    val b   = new McpServerBuilder
    srv.installOn(b)
    (srv, b)

  private def callTool(b: McpServerBuilder, name: String, args: Map[String, Any]): ToolHandlerResult =
    b.tools(name).handler(args)

  // ── wallet.sendTransaction ────────────────────────────────────────────

  test("wallet.sendTransaction signs + broadcasts via the chain adapter") {
    primeRpc(broadcastHash = "0xabc123")
    val rec    = new RecordingHandler(approve = true)
    val (s, b) = newServer(Policy(confirmation = ConfirmationMode.ElicitationPerCall), rec)
    val r = callTool(b, "wallet.sendTransaction", Map(
      "chainId" -> "eip155:8453",
      "to"      -> "0x1111111111111111111111111111111111111111",
      "value"   -> "1000",
      "data"    -> "0xdeadbeef",
    ))
    assert(!r.isError, r.content)
    val resp = r.content.head.obj
    assert(resp("txHash").str == "0xabc123")
    assert(resp("from").str.equalsIgnoreCase(addr))
    // The adapter must have queried nonce / gas / fee + sent the tx
    assert(rpcCalls.exists(_._1 == "eth_getTransactionCount"))
    assert(rpcCalls.exists(_._1 == "eth_sendRawTransaction"))
    val rawTx = rpcCalls.find(_._1 == "eth_sendRawTransaction").get._2.head.str
    assert(rawTx.startsWith("0x02"), "expected EIP-1559 envelope byte 0x02")
    // Audit
    assert(s.auditLog.snapshot.head.tool == "wallet.sendTransaction")
    assert(s.auditLog.snapshot.head.decision == "approved")
  }

  test("wallet.sendTransaction rejected → no broadcast + audit rejection") {
    primeRpc()
    val rec    = new RecordingHandler(approve = false)
    val (s, b) = newServer(Policy(confirmation = ConfirmationMode.ElicitationPerCall), rec)
    val r = callTool(b, "wallet.sendTransaction", Map(
      "chainId" -> "eip155:8453",
      "to"      -> "0x1111111111111111111111111111111111111111",
    ))
    assert(r.isError)
    assert(r.content.head.obj("error").str.contains("rejected"))
    assert(!rpcCalls.exists(_._1 == "eth_sendRawTransaction"))
    assert(s.auditLog.snapshot.head.decision == "rejected")
  }

  test("wallet.sendTransaction enforces policy.maxPerCall on `value`") {
    primeRpc()
    val rec    = new RecordingHandler(approve = true)
    val p      = Policy(
      confirmation = ConfirmationMode.ElicitationPerCall,
      maxPerCall   = Map(ChainId.Base -> BigInt(500)),
    )
    val (_, b) = newServer(p, rec)
    val r = callTool(b, "wallet.sendTransaction", Map(
      "chainId" -> "eip155:8453",
      "to"      -> "0x1111111111111111111111111111111111111111",
      "value"   -> "1000",
    ))
    assert(r.isError)
    assert(r.content.head.obj("error").str.contains("exceeds policy cap"))
    assert(rec.requests.isEmpty)  // never asked the user
  }

  // ── ConfirmationMode.ElicitationCached ────────────────────────────────

  test("ElicitationCached(ttl) caches approval per (tool, chainId) within TTL") {
    val rec    = new RecordingHandler(approve = true)
    val p      = Policy(confirmation = ConfirmationMode.ElicitationCached(ttlSeconds = 60))
    val (s, b) = newServer(p, rec)
    // First call: handler invoked
    val r1 = callTool(b, "wallet.signMessage", Map(
      "chainId" -> "eip155:8453", "message" -> "0x01",
    ))
    assert(!r1.isError)
    assert(rec.requests.size == 1)
    // Second call within TTL: handler should NOT be invoked
    val r2 = callTool(b, "wallet.signMessage", Map(
      "chainId" -> "eip155:8453", "message" -> "0x02",
    ))
    assert(!r2.isError)
    assert(rec.requests.size == 1, "second call must hit the cache")
    // Audit: both approved, second one labeled "approved" too
    assert(s.auditLog.snapshot.size == 2)
    assert(s.auditLog.snapshot.forall(_.decision == "approved"))
  }

  test("ElicitationCached: rejection is NOT cached — re-asks on next call") {
    val rec    = new RecordingHandler(approve = false)
    val p      = Policy(confirmation = ConfirmationMode.ElicitationCached(ttlSeconds = 60))
    val (_, b) = newServer(p, rec)
    callTool(b, "wallet.signMessage", Map("chainId" -> "eip155:8453", "message" -> "0x01"))
    callTool(b, "wallet.signMessage", Map("chainId" -> "eip155:8453", "message" -> "0x02"))
    assert(rec.requests.size == 2, "rejections should not be cached; handler asked twice")
  }

  test("ElicitationCached: separate (tool, chainId) keys cache independently") {
    val rec    = new RecordingHandler(approve = true)
    val p      = Policy(confirmation = ConfirmationMode.ElicitationCached(ttlSeconds = 60))
    val (_, b) = newServer(p, rec)
    callTool(b, "wallet.signMessage",   Map("chainId" -> "eip155:8453", "message" -> "0x01"))
    callTool(b, "wallet.signMessage",   Map("chainId" -> "eip155:8453", "message" -> "0x02"))  // cached
    callTool(b, "wallet.signTypedData", Map("chainId" -> "eip155:8453", "typedData" -> Map(
      "domain" -> Map("name" -> "T", "version" -> "1", "chainId" -> 1, "verifyingContract" -> "0x0000000000000000000000000000000000000001"),
      "types"  -> Map(
        "EIP712Domain" -> Seq(
          Map("name" -> "name", "type" -> "string"),
          Map("name" -> "version", "type" -> "string"),
          Map("name" -> "chainId", "type" -> "uint256"),
          Map("name" -> "verifyingContract", "type" -> "address"),
        ),
        "M" -> Seq(Map("name" -> "v", "type" -> "uint256")),
      ),
      "primaryType" -> "M",
      "message"     -> Map("v" -> "0"),
    )))
    // 2 distinct tools → 2 cache misses → handler asked twice
    assert(rec.requests.size == 2)
  }
