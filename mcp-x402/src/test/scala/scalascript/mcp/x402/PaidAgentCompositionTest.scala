package scalascript.mcp.x402

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.blockchain.evm.EvmChainAdapter
import scalascript.blockchain.spi.*
import scalascript.crypto.Curve
import scalascript.mcp.McpServerBuilder
import scalascript.mcp.wallet.*
import scalascript.wallet.spi.*
import scalascript.wallet.strategy.eoa.{EoaStrategy, RawPrivateKeyVault}
import scalascript.x402.*

/** End-to-end composition test for the MCP × x402 × Wallet stack.
 *  Wires the four phases together in one in-process flow:
 *
 *    1. McpWalletServer (Phase 1+2+5): a wallet under stdio /
 *       AccountManager / Policy / ElicitationHandler.
 *
 *    2. Mcp402Dispatcher (Phase 3): a server-side payment gate
 *       backed by a stub Facilitator.
 *
 *    3. McpWalletPaymentSigner: adapts the wallet's wallet.payX402
 *       tool into the PaymentSigner trait.
 *
 *    4. X402AutoPay (Phase 4): the client middleware that
 *       intercepts -32402 and signs via the wallet.
 *
 *  The "agent" in this test calls a priced tool through the
 *  autopay wrapper. We assert it eventually gets the tool result,
 *  having paid via the wallet, with elicitation having been asked
 *  once for the payment. */
class PaidAgentCompositionTest extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  // ── Agent's wallet ────────────────────────────────────────────────────

  private val privKey  = "0x" + "77" * 32
  private val vault    = RawPrivateKeyVault.fromHex("agent-wallet", privKey, Curve.Secp256k1)
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
    def rpcCall(method: String, params: ujson.Value*): Future[ujson.Value] =
      Future.failed(new RuntimeException(s"no RPC needed in this demo: $method"))
    def nowSeconds: Long = 1700000000L

  // ── Priced server (in-process) ────────────────────────────────────────

  private val price = ToolPrice(
    amount = BigInt(10_000),                 // 0.01 USDC
    asset  = Assets.USDC_BASE,
    payTo  = "0x2222222222222222222222222222222222222222",
  )
  private val pricedToolConfig = PricedToolConfig(
    name        = "premium.search",
    description = "Premium semantic search over our corpus",
    price       = price,
  )

  private class StubFacilitator extends Facilitator:
    def verify(payload: PaymentPayload, req: PaymentRequirements): Future[VerifyResult] =
      Future.successful(VerifyResult.Ok)
    def settle(payload: PaymentPayload, req: PaymentRequirements): Future[SettleResult] =
      Future.successful(SettleResult.Ok("0xstub-settle-hash"))

  private val dispatcher = new Mcp402Dispatcher(new StubFacilitator)

  /** Priced server's tools/call endpoint. The agent's autopay
   *  middleware calls this. Returns the tool's result on a paid
   *  call; throws PaymentRequiredException on an unpaid call. */
  private def pricedServer: (String, ujson.Value) => Future[ujson.Value] =
    (method, params) => method match
      case "tools/call" =>
        dispatcher.dispatch(pricedToolConfig, params).flatMap {
          case Left(err) =>
            PaymentRequiredException.tryParse(err) match
              case Some(ex) => Future.failed(ex)
              case None     => Future.failed(new RuntimeException(s"unparseable error: $err"))
          case Right(payment) =>
            // The tool "runs": echo back the query + record the payment.
            Future.successful(ujson.Obj(
              "result" -> ujson.Obj(
                "tool"    -> ujson.Str(pricedToolConfig.name),
                "query"   -> params.obj.get("arguments").map(_.obj.getOrElse("query", ujson.Null)).getOrElse(ujson.Null),
                "paidBy"  -> ujson.Str(payment.from),
                "paidTo"  -> ujson.Str(payment.to),
                "amount"  -> ujson.Str(payment.amount.toString),
              ),
              "_meta" -> ujson.Obj(
                Mcp402Protocol.MetaX402Key -> ujson.Obj(
                  Mcp402Protocol.MetaSettledField -> ujson.Str("0xstub-settle-hash"),
                ),
              ),
            ))
        }
      case other =>
        Future.failed(new RuntimeException(s"unsupported: $other"))

  // ── Composition test ──────────────────────────────────────────────────

  test("agent → autopay → wallet → priced server: end-to-end paid call") {
    // 1) Stand up the wallet server with ElicitationPerCall + a
    //    recording handler that auto-approves but captures the
    //    requests for assertion.
    class RecordingElicitation extends ElicitationHandler:
      val elicitations = scala.collection.mutable.ArrayBuffer.empty[ElicitationRequest]
      def confirm(req: ElicitationRequest): Future[Boolean] =
        elicitations += req
        Future.successful(true)
    val elicit = new RecordingElicitation
    val walletServer = new McpWalletServer(
      vault   = vault,
      manager = mgr,
      policy  = Policy(confirmation = ConfirmationMode.ElicitationPerCall),
      ctxFor  = ctxFor,
      elicitation = Some(elicit),
    )
    val walletBuilder = new McpServerBuilder
    walletServer.installOn(walletBuilder)

    // 2) Build a PaymentSigner that adapts the wallet's tool surface.
    //    `callTool(name, args)`: in-process — just invoke the
    //    registered handler. Cross-process production wiring would
    //    use an actual mcp-common client.
    val callWalletTool: (String, ujson.Value) => Future[ujson.Value] =
      (toolName, args) =>
        val argsMap = args.obj.toMap.map { case (k, v) => k -> ujsonToAny(v) }
        Future(walletBuilder.tools(toolName).handler(argsMap)).map { res =>
          if res.isError then
            throw new RuntimeException(s"wallet tool $toolName failed: ${res.content}")
          else
            res.content.head
        }

    val paymentSigner = new McpWalletPaymentSigner(callWalletTool)

    // 3) Wrap the priced server with X402AutoPay.
    val charges = scala.collection.mutable.ArrayBuffer.empty[(PaymentRequirements, String)]
    val autopay = new X402AutoPay(
      signer    = paymentSigner,
      maxAmount = BigInt(1_000_000),
      onCharge  = (r, h) => charges += ((r, h)),
    )
    val agentSend = autopay.wrap(pricedServer)

    // 4) The agent makes its call.
    val params = ujson.Obj(
      "name"      -> ujson.Str(pricedToolConfig.name),
      "arguments" -> ujson.Obj("query" -> ujson.Str("vector databases")),
    )
    val result = Await.result(agentSend("tools/call", params), 10.seconds)

    // ── assertions ────────────────────────────────────────────────────
    val tool = result.obj("result").obj
    assert(tool("tool").str   == "premium.search")
    assert(tool("query").str  == "vector databases")
    assert(tool("amount").str == "10000")
    assert(tool("paidBy").str.equalsIgnoreCase(addr))
    assert(tool("paidTo").str == price.payTo)

    // Settled-hash hint surfaces in _meta
    val meta = result.obj("_meta").obj(Mcp402Protocol.MetaX402Key).obj
    assert(meta(Mcp402Protocol.MetaSettledField).str == "0xstub-settle-hash")

    // The autopay middleware fired its onCharge hook exactly once
    assert(charges.size == 1)
    assert(charges.head._1.payTo == price.payTo)

    // The wallet asked for elicitation exactly once (the payX402 call)
    assert(elicit.elicitations.size == 1)
    assert(elicit.elicitations.head.tool == "wallet.payX402")
    assert(elicit.elicitations.head.details("amount") == "10000")

    // The wallet's audit log records the approval
    val audit = walletServer.auditLog.snapshot
    assert(audit.exists(e => e.tool == "wallet.payX402" && e.decision == "approved"))
  }

  // ── util ──────────────────────────────────────────────────────────────

  /** Recursive ujson.Value → Any so we can pump a JSON `arguments`
   *  blob through the mcp-common tool handler shape. */
  private def ujsonToAny(v: ujson.Value): Any = v match
    case ujson.Null     => null
    case ujson.True     => true
    case ujson.False    => false
    case ujson.Num(n)   => n
    case ujson.Str(s)   => s
    case ujson.Arr(xs)  => xs.toSeq.map(ujsonToAny)
    case ujson.Obj(o)   => o.toMap.map { case (k, vv) => k -> ujsonToAny(vv) }
