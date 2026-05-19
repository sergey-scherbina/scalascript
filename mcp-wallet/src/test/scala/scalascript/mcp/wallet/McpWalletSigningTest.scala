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

/** Phase 2 signing-tool tests. The elicitation handler is a stub
 *  driven by the test, so we observe both approval and rejection
 *  paths without spinning up a UI. */
class McpWalletSigningTest extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  private val privKey  = "0x" + "33" * 32
  private val vault    = RawPrivateKeyVault.fromHex("test-wallet", privKey, Curve.Secp256k1)
  private val signer   = Await.result(vault.getSigner(Curve.Secp256k1, "raw"), 2.seconds)
  private val strategy = new EoaStrategy(signer)
  private val baseAdpt = new EvmChainAdapter(ChainId.Base)
  private val addr     = baseAdpt.addressFromPublicKey(signer.publicKey)

  private val mgr: AccountManager = new AccountManager:
    def chains: Set[ChainId] = Set(ChainId.Base)
    def strategyFor(c: ChainId): Option[AccountStrategy] =
      if c == ChainId.Base then Some(strategy) else None
    def adapterFor(c: ChainId): Option[ChainAdapter] =
      if c == ChainId.Base then Some(baseAdpt) else None
    def request(req: DappRequest): Future[DappResponse] = Future.successful(DappResponse.Ok(ujson.Null))

  private val ctxFor: ChainId => ChainContext = _ => new ChainContext:
    def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] =
      Future.failed(new RuntimeException(s"no rpc in signing tests: $method"))
    def nowSeconds: Long = 1700000000L

  /** Records every confirm request — lets us assert details + drive
   *  approve/reject deterministically. */
  private class RecordingHandler(approve: Boolean) extends ElicitationHandler:
    val requests = scala.collection.mutable.ArrayBuffer.empty[ElicitationRequest]
    def confirm(req: ElicitationRequest): Future[Boolean] =
      requests += req
      Future.successful(approve)

  private def newServer(
    p: Policy = Policy(confirmation = ConfirmationMode.ElicitationPerCall),
    elicit: ElicitationHandler,
  ): (McpWalletServer, McpServerBuilder) =
    val srv = new McpWalletServer(vault, mgr, p, ctxFor, Some(elicit))
    val builder = new McpServerBuilder
    srv.installOn(builder)
    (srv, builder)

  private def callTool(builder: McpServerBuilder, name: String, args: Map[String, Any]): ToolHandlerResult =
    builder.tools(name).handler(args)

  // ── signMessage ───────────────────────────────────────────────────────

  test("wallet.signMessage approved → 65-byte hex signature") {
    val rec      = new RecordingHandler(approve = true)
    val (s, b)   = newServer(elicit = rec)
    val r        = callTool(b, "wallet.signMessage", Map(
      "chainId" -> "eip155:8453",
      "message" -> "0xdeadbeef",
    ))
    assert(!r.isError, r.content)
    val sig = r.content.head.obj("signature").str
    assert(sig.startsWith("0x") && sig.length == 2 + 130)
    assert(rec.requests.size == 1)
    assert(rec.requests.head.tool == "wallet.signMessage")
    assert(s.auditLog.size == 1)
    assert(s.auditLog.snapshot.head.decision == "approved")
  }

  test("wallet.signMessage rejected → tool error + audit rejection") {
    val rec = new RecordingHandler(approve = false)
    val (s, b) = newServer(elicit = rec)
    val r = callTool(b, "wallet.signMessage", Map(
      "chainId" -> "eip155:8453",
      "message" -> "hello",
    ))
    assert(r.isError)
    assert(r.content.head.obj("error").str.contains("rejected"))
    assert(s.auditLog.snapshot.head.decision == "rejected")
  }

  test("wallet.signMessage without elicitation handler under ElicitationPerCall is fail-closed") {
    val srv = new McpWalletServer(vault, mgr, Policy(confirmation = ConfirmationMode.ElicitationPerCall), ctxFor, elicitation = None)
    val b   = new McpServerBuilder
    srv.installOn(b)
    val r = callTool(b, "wallet.signMessage", Map(
      "chainId" -> "eip155:8453",
      "message" -> "hi",
    ))
    assert(r.isError, "should fail-closed without an elicitation handler")
  }

  test("Implicit policy auto-approves without an elicitation handler") {
    val srv = new McpWalletServer(vault, mgr, Policy(confirmation = ConfirmationMode.Implicit), ctxFor, elicitation = None)
    val b = new McpServerBuilder
    srv.installOn(b)
    val r = callTool(b, "wallet.signMessage", Map(
      "chainId" -> "eip155:8453",
      "message" -> "0x01",
    ))
    assert(!r.isError, r.content)
    assert(srv.auditLog.snapshot.head.decision == "auto")
  }

  // ── payX402 ───────────────────────────────────────────────────────────

  test("wallet.payX402 builds a base64-encoded PaymentPayload + headerValue") {
    val rec      = new RecordingHandler(approve = true)
    val (_, b)   = newServer(elicit = rec)
    val r        = callTool(b, "wallet.payX402", Map(
      "chainId"      -> "eip155:8453",
      "requirements" -> Map(
        "asset"  -> Map(
          "address"  -> "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913",
          "symbol"   -> "USDC",
          "decimals" -> 6,
        ),
        "payTo"             -> "0x1111111111111111111111111111111111111111",
        "amount"            -> "1000000",
        "maxTimeoutSeconds" -> 300,
      ),
    ))
    assert(!r.isError, r.content)
    val o = r.content.head.obj
    assert(o("from").str.equalsIgnoreCase(addr))
    val headerValue = o("headerValue").str
    val decoded     = new String(java.util.Base64.getDecoder.decode(headerValue), "UTF-8")
    val payload     = ujson.read(decoded).obj
    assert(payload("network").str == "eip155:8453")
    assert(payload("authorization").obj("from").str.equalsIgnoreCase(addr))
    assert(payload("authorization").obj("to").str.equalsIgnoreCase("0x1111111111111111111111111111111111111111"))
    assert(payload("authorization").obj("value").str == "1000000")
    val sig = payload("signature").str
    assert(sig.length == 2 + 130, s"signature length: ${sig.length}")
    // last byte v should be 27 or 28 (Ethereum convention)
    val vByte = Integer.parseInt(sig.takeRight(2), 16)
    assert(vByte == 27 || vByte == 28)
    // elicitation must have shown amount + payTo details
    assert(rec.requests.head.details.get("amount").contains("1000000"))
    assert(rec.requests.head.details.get("payTo").exists(_.toLowerCase.startsWith("0x1111")))
  }

  test("wallet.payX402 enforces policy.maxPerCall") {
    val rec = new RecordingHandler(approve = true)
    val p   = Policy(
      confirmation = ConfirmationMode.ElicitationPerCall,
      maxPerCall   = Map(ChainId.Base -> BigInt(500_000)),
    )
    val (_, b) = newServer(p, rec)
    val r = callTool(b, "wallet.payX402", Map(
      "chainId"      -> "eip155:8453",
      "requirements" -> Map(
        "asset" -> Map("address" -> "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913",
                       "symbol"   -> "USDC", "decimals" -> 6),
        "payTo"  -> "0x1111111111111111111111111111111111111111",
        "amount" -> "1000000",
      ),
    ))
    assert(r.isError)
    assert(r.content.head.obj("error").str.contains("exceeds policy cap"))
    // elicitation should NOT have been asked
    assert(rec.requests.isEmpty)
  }

  // ── audit resource ────────────────────────────────────────────────────

  test("wallet://audit resource reflects appended entries") {
    val rec = new RecordingHandler(approve = true)
    val (_, b) = newServer(elicit = rec)
    callTool(b, "wallet.signMessage", Map("chainId" -> "eip155:8453", "message" -> "0x01"))
    callTool(b, "wallet.signMessage", Map("chainId" -> "eip155:8453", "message" -> "0x02"))
    val resHandler = b.resources("wallet://audit").handler
    val resBody    = resHandler("wallet://audit").contents.head.obj
    val entries    = resBody("entries").arr
    assert(entries.size == 2)
    assert(entries.forall(_.obj("tool").str == "wallet.signMessage"))
    assert(entries.forall(_.obj("decision").str == "approved"))
    // No raw signature in the resource — only the digest
    assert(entries.head.obj("resultDigest").str.length == 16)
  }
