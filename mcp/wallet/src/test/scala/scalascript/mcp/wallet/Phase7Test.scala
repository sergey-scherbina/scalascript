package scalascript.mcp.wallet

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.blockchain.evm.EvmChainAdapter
import scalascript.blockchain.spi.*
import scalascript.crypto.Curve
import scalascript.mcp.{McpServerBuilder, ToolHandlerResult}
import scalascript.oauth.OAuth
import scalascript.wallet.spi.*
import scalascript.wallet.strategy.eoa.{EoaStrategy, RawPrivateKeyVault}

/** Phase 7: PolicyProvider.FromAuth resolves the effective policy
 *  per-request from OAuth.AuthClaims bound via McpWalletAuth. Tests
 *  exercise the read-only-vs-signing-vs-deny split by scope without
 *  actually wiring an HTTP transport. */
class Phase7Test extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  private val privKey  = "0x" + "88" * 32
  private val vault    = RawPrivateKeyVault.fromHex("p7-wallet", privKey, Curve.Secp256k1)
  private val signer   = Await.result(vault.getSigner(Curve.Secp256k1, "raw"), 2.seconds)
  private val strategy = new EoaStrategy(signer)
  private val baseAdpt = new EvmChainAdapter(ChainId.Base)
  private val addr     = baseAdpt.addressFromPublicKey(signer.publicKey)

  private val mgr: AccountManager = new AccountManager:
    def chains: Set[ChainId] = Set(ChainId.Base)
    def strategyFor(c: ChainId): Option[AccountStrategy] = if c == ChainId.Base then Some(strategy) else None
    def adapterFor(c: ChainId): Option[ChainAdapter]     = if c == ChainId.Base then Some(baseAdpt) else None
    def request(req: DappRequest): Future[DappResponse]  = Future.successful(DappResponse.Ok(ujson.Null))

  private val ctxFor: ChainId => ChainContext = _ => new ChainContext:
    def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] = Future.failed(new RuntimeException(s"no rpc: $method"))
    def nowSeconds: Long = 1700000000L

  private class AutoApprove extends ElicitationHandler:
    def confirm(req: ElicitationRequest): Future[Boolean] = Future.successful(true)

  /** Per-scope policy resolver:
   *  - `wallet:read`  → read-only policy (no signing tools).
   *  - `wallet:sign`  → full policy with Implicit confirmation.
   *  - anything else  → fallback (read-only).
   */
  private val resolver: OAuth.AuthClaims => Policy = c =>
    if c.hasScope("wallet:sign") then
      Policy(
        allowedTools = Set(
          "wallet.listAccounts", "wallet.getAddress", "wallet.getBalance",
          "wallet.signMessage", "wallet.signTypedData", "wallet.payX402",
          "wallet.sendTransaction",
        ),
        confirmation = ConfirmationMode.Implicit,
      )
    else if c.hasScope("wallet:read") then
      Policy(
        allowedTools = Set("wallet.listAccounts", "wallet.getAddress", "wallet.getBalance"),
        confirmation = ConfirmationMode.Implicit,
      )
    else
      Policy.readOnly

  /** Static max policy: exposes every tool. The resolver further
   *  narrows per scope. */
  private val maxPolicy = Policy(confirmation = ConfirmationMode.Implicit)

  private def newServer(): (McpWalletServer, McpServerBuilder) =
    val srv = new McpWalletServer(
      vault                  = vault,
      manager                = mgr,
      policy                 = maxPolicy,
      ctxFor                 = ctxFor,
      elicitation            = Some(new AutoApprove),
      policyProviderOverride = Some(PolicyProvider.FromAuth(resolver)),
    )
    val b = new McpServerBuilder
    srv.installOn(b)
    (srv, b)

  private def callTool(b: McpServerBuilder, name: String, args: Map[String, Any]): ToolHandlerResult =
    b.tools(name).handler(args)

  private def claimsWith(scopes: String*): OAuth.AuthClaims =
    OAuth.AuthClaims(subject = "agent-42", scopes = scopes.toSet)

  // ── effective policy is the resolver's output ────────────────────────

  test("FromAuth: wallet:read scope can call wallet.getAddress") {
    val (_, b) = newServer()
    val r = McpWalletAuth.withClaims(claimsWith("wallet:read")):
      callTool(b, "wallet.getAddress", Map("chainId" -> "eip155:8453"))
    assert(!r.isError, r.content)
    assert(r.content.head.obj("address").str.equalsIgnoreCase(addr))
  }

  test("FromAuth: wallet:read scope is BLOCKED from wallet.signMessage at runtime") {
    val (_, b) = newServer()
    val r = McpWalletAuth.withClaims(claimsWith("wallet:read")):
      callTool(b, "wallet.signMessage", Map("chainId" -> "eip155:8453", "message" -> "0x01"))
    assert(r.isError)
    assert(r.content.head.obj("error").str.contains("not exposed"))
  }

  test("FromAuth: wallet:sign scope CAN call wallet.signMessage") {
    val (_, b) = newServer()
    val r = McpWalletAuth.withClaims(claimsWith("wallet:sign")):
      callTool(b, "wallet.signMessage", Map("chainId" -> "eip155:8453", "message" -> "0x01"))
    assert(!r.isError, r.content)
    val sig = r.content.head.obj("signature").str
    assert(sig.startsWith("0x") && sig.length == 2 + 130)
  }

  test("FromAuth: missing claims → fallback policy applies (read-only)") {
    val (_, b) = newServer()
    // No McpWalletAuth.withClaims wrapping — claims = None.
    val read  = callTool(b, "wallet.listAccounts", Map.empty)
    val write = callTool(b, "wallet.signMessage", Map("chainId" -> "eip155:8453", "message" -> "0x01"))
    assert(!read.isError, "read-only fallback should permit listAccounts")
    assert(write.isError)
    assert(write.content.head.obj("error").str.contains("not exposed"))
  }

  test("FromAuth: unrecognised scope → fallback policy (deny signing)") {
    val (_, b) = newServer()
    val r = McpWalletAuth.withClaims(claimsWith("orgs:read")):
      callTool(b, "wallet.signMessage", Map("chainId" -> "eip155:8453", "message" -> "0x01"))
    assert(r.isError)
  }

  // ── thread-local cleanup ──────────────────────────────────────────────

  test("McpWalletAuth.withClaims clears the thread-local on exit") {
    McpWalletAuth.withClaims(claimsWith("wallet:sign")):
      assert(McpWalletAuth.currentClaims().exists(_.hasScope("wallet:sign")))
    assert(McpWalletAuth.currentClaims().isEmpty)
  }

  test("McpWalletAuth.withClaims clears the thread-local even when body throws") {
    intercept[RuntimeException] {
      McpWalletAuth.withClaims(claimsWith("wallet:sign")):
        throw new RuntimeException("boom")
    }
    assert(McpWalletAuth.currentClaims().isEmpty)
  }

  // ── PolicyProvider.Static is the default (backwards compat) ──────────

  test("Static policy provider is the default when no override is given") {
    val srv = new McpWalletServer(vault, mgr, maxPolicy, ctxFor, elicitation = Some(new AutoApprove))
    val b   = new McpServerBuilder
    srv.installOn(b)
    // Auth context is irrelevant under Static — same policy regardless
    val r1 = McpWalletAuth.withClaims(claimsWith("wallet:read")):
      callTool(b, "wallet.signMessage", Map("chainId" -> "eip155:8453", "message" -> "0x01"))
    val r2 = callTool(b, "wallet.signMessage", Map("chainId" -> "eip155:8453", "message" -> "0x01"))
    assert(!r1.isError)
    assert(!r2.isError)
  }

  // ── per-call policy.maxPerCall narrowing via FromAuth ────────────────

  test("FromAuth: scope-specific maxPerCall caps signing budgets") {
    val cappedResolver: OAuth.AuthClaims => Policy = c =>
      if c.hasScope("wallet:sign:small") then
        Policy(
          allowedTools = Set("wallet.payX402"),
          maxPerCall   = Map(ChainId.Base -> BigInt(500)),
          confirmation = ConfirmationMode.Implicit,
        )
      else
        Policy.readOnly
    val srv = new McpWalletServer(
      vault, mgr, maxPolicy, ctxFor,
      elicitation = Some(new AutoApprove),
      policyProviderOverride = Some(PolicyProvider.FromAuth(cappedResolver)),
    )
    val b = new McpServerBuilder
    srv.installOn(b)
    val r = McpWalletAuth.withClaims(claimsWith("wallet:sign:small")):
      callTool(b, "wallet.payX402", Map(
        "chainId"      -> "eip155:8453",
        "requirements" -> Map(
          "asset" -> Map("address" -> "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913",
                         "symbol"   -> "USDC", "decimals" -> 6),
          "payTo"  -> "0x1111111111111111111111111111111111111111",
          "amount" -> "1000",
        ),
      ))
    assert(r.isError)
    assert(r.content.head.obj("error").str.contains("exceeds policy cap"))
  }
