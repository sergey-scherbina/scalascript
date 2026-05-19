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

/** Tests for the read-only mcp-wallet tool surface. We register
 *  tools onto an `McpServerBuilder` and invoke their handlers
 *  directly — the actual MCP serve loop is exercised by mcp-common's
 *  own tests; here we focus on the wallet-side semantics. */
class McpWalletServerTest extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  // Real wallet stack: private key → vault → signer → EoaStrategy →
  // EvmChainAdapter. Address comes out as real EIP-55-checksummed.
  private val privKey  = "0x" + "22" * 32
  private val vault    = RawPrivateKeyVault.fromHex("test-wallet", privKey, Curve.Secp256k1)
  private val signer   = Await.result(vault.getSigner(Curve.Secp256k1, "raw"), 2.seconds)
  private val strategy = new EoaStrategy(signer)
  private val baseAdpt = new EvmChainAdapter(ChainId.Base)
  private val addr     = baseAdpt.addressFromPublicKey(signer.publicKey)

  private val mgr: AccountManager = new AccountManager:
    def chains: Set[ChainId] = Set(ChainId.Base, ChainId.BaseSepolia)
    def strategyFor(c: ChainId): Option[AccountStrategy] =
      if chains.contains(c) then Some(strategy) else None
    def adapterFor(c: ChainId): Option[ChainAdapter] =
      if c == ChainId.Base then Some(baseAdpt) else None
    def request(req: DappRequest): Future[DappResponse] = Future.successful(DappResponse.Ok(ujson.Null))

  /** Stub ChainContext: returns canned values for balance / token-
   *  balance RPC. */
  private val canned = scala.collection.mutable.Map.empty[String, ujson.Value]
  private val ctxFor: ChainId => ChainContext = _ => new ChainContext:
    def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] =
      canned.get(method) match
        case Some(v) => Future.successful(v)
        case None    => Future.failed(new RuntimeException(s"unmocked: $method"))
    def nowSeconds: Long = 1700000000L

  private def newServer(p: Policy = Policy.readOnly): (McpWalletServer, McpServerBuilder) =
    val srv     = new McpWalletServer(vault, mgr, p, ctxFor)
    val builder = new McpServerBuilder
    srv.installOn(builder)
    (srv, builder)

  /** Convenience: call a registered tool by name with `args`. */
  private def callTool(builder: McpServerBuilder, name: String, args: Map[String, Any]): ToolHandlerResult =
    builder.tools(name).handler(args)

  // ── tool registration ────────────────────────────────────────────────

  test("installOn registers read-only + signing + tx tools by default") {
    val (_, builder) = newServer()
    val names = builder.tools.keys.toSet
    assert(names == Set(
      "wallet.listAccounts", "wallet.getAddress", "wallet.getBalance",
      "wallet.signMessage", "wallet.signTypedData", "wallet.payX402",
      "wallet.sendTransaction",
    ))
  }

  test("policy.allowedTools = {wallet.getAddress} exposes only that tool") {
    val p = Policy.readOnly.copy(allowedTools = Set("wallet.getAddress"))
    val (_, builder) = newServer(p)
    assert(builder.tools.keys.toSet == Set("wallet.getAddress"))
  }

  // ── listAccounts ──────────────────────────────────────────────────────

  test("wallet.listAccounts returns the bound account") {
    val (_, b) = newServer()
    val r = callTool(b, "wallet.listAccounts", Map.empty)
    assert(!r.isError)
    val accounts = r.content.head.obj("accounts").arr
    assert(accounts.size == 1)
    val a = accounts.head
    assert(a.obj("id").str.contains("test-wallet"))
    assert(a.obj("curves").arr.exists(_.str == "Secp256k1"))
  }

  // ── getAddress ────────────────────────────────────────────────────────

  test("wallet.getAddress returns EIP-55-checksummed address for known chain") {
    val (_, b) = newServer()
    val r = callTool(b, "wallet.getAddress", Map("chainId" -> "eip155:8453"))
    assert(!r.isError, s"got error: ${r.content}")
    val resp = r.content.head.obj
    assert(resp("chainId").str == "eip155:8453")
    assert(resp("address").str.equalsIgnoreCase(addr))
  }

  test("wallet.getAddress missing chainId surfaces a tool error") {
    val (_, b) = newServer()
    val r = callTool(b, "wallet.getAddress", Map.empty)
    assert(r.isError)
    assert(r.content.head.obj("error").str.contains("chainId"))
  }

  test("wallet.getAddress for an unsupported chain is policy-rejected") {
    val p = Policy.readOnly.copy(allowedChains = Set(ChainId.BaseSepolia))
    val (_, b) = newServer(p)
    val r = callTool(b, "wallet.getAddress", Map("chainId" -> "eip155:8453"))
    assert(r.isError)
    assert(r.content.head.obj("error").str.contains("not exposed by policy"))
  }

  // ── getBalance ────────────────────────────────────────────────────────

  test("wallet.getBalance returns native balance via eth_getBalance") {
    canned.clear()
    canned("eth_getBalance") = ujson.Str("0x16345785d8a0000")   // 1e17 wei = 0.1 ETH
    val (_, b) = newServer()
    val r = callTool(b, "wallet.getBalance", Map("chainId" -> "eip155:8453"))
    assert(!r.isError, s"got error: ${r.content}")
    val resp = r.content.head.obj
    assert(resp("chainId").str == "eip155:8453")
    assert(resp("address").str.equalsIgnoreCase(addr))
    assert(resp("value").str == "100000000000000000")           // 1e17 in decimal
    assert(resp("symbol").str == "native")
  }

  test("wallet.getBalance for an ERC-20 token uses eth_call with balanceOf calldata") {
    canned.clear()
    // Encoded 1_500_000 (USDC has 6 decimals)
    canned("eth_call") = ujson.Str("0x" + "00" * 29 + "16e360")
    val (_, b) = newServer()
    val r = callTool(b, "wallet.getBalance", Map(
      "chainId" -> "eip155:8453",
      "asset"   -> Map(
        "address"  -> "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913",
        "symbol"   -> "USDC",
        "decimals" -> 6,
      ),
    ))
    assert(!r.isError, s"got error: ${r.content}")
    val resp = r.content.head.obj
    assert(resp("value").str == "1500000")
    assert(resp("symbol").str == "USDC")
    assert(resp("decimals").num.toInt == 6)
  }
