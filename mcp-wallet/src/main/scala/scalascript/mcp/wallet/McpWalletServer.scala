package scalascript.mcp.wallet

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scalascript.blockchain.spi.{Asset, ChainContext, ChainId}
import scalascript.mcp.{McpServerBuilder, ToolHandlerResult}
import scalascript.wallet.spi.{AccountManager, Vault}

/** Read-only MCP tool surface over the wallet-spi `Vault` /
 *  `AccountManager`. Registers tools onto a caller-provided
 *  `McpServerBuilder` so the host owns the transport (stdio /
 *  HTTP+SSE) and the lifecycle.
 *
 *  Phase 1 exposes only read-only tools — listing accounts, address
 *  derivation, balance lookup. Signing tools (`wallet.signMessage`,
 *  `wallet.signTypedData`, `wallet.payX402`, `wallet.sendTransaction`)
 *  land in Phase 2 along with the elicitation-based confirmation
 *  flow.
 *
 *  See docs/mcp-x402-wallet.md §5. */
class McpWalletServer(
  val vault:    Vault,
  val manager:  AccountManager,
  val policy:   Policy,
  ctxFor:       ChainId => ChainContext,
)(using ec: ExecutionContext):

  /** Mount this wallet onto an `McpServerBuilder`. Idempotent only
   *  per-builder — calling twice on the same builder doubles the
   *  registrations. */
  def installOn(builder: McpServerBuilder): Unit =
    if policy.exposes("wallet.listAccounts") then registerListAccounts(builder)
    if policy.exposes("wallet.getAddress")   then registerGetAddress(builder)
    if policy.exposes("wallet.getBalance")   then registerGetBalance(builder)

  // ── tool registrations ────────────────────────────────────────────────

  private def registerListAccounts(builder: McpServerBuilder): Unit =
    builder.tool(
      name        = "wallet.listAccounts",
      description = Some("List wallet accounts: id, label, curves, derivation path."),
      inputSchema = ujson.Obj(
        "type"       -> ujson.Str("object"),
        "properties" -> ujson.Obj(),
      ),
      handler     = _ => awaitTool(handleListAccounts()),
    )

  private def handleListAccounts(): Future[ToolHandlerResult] =
    vault.listAccounts().map { accs =>
      val items = accs.map { a =>
        ujson.Obj(
          "id"             -> ujson.Str(a.id),
          "label"          -> ujson.Str(a.label),
          "derivationPath" -> ujson.Str(a.derivationPath),
          "curves"         -> ujson.Arr(a.publicKeys.keys.map(c => ujson.Str(c.toString)).toSeq*),
        )
      }
      ToolHandlerResult(
        content = List(ujson.Obj("accounts" -> ujson.Arr(items*))),
        isError = false,
      )
    }

  private def registerGetAddress(builder: McpServerBuilder): Unit =
    builder.tool(
      name        = "wallet.getAddress",
      description = Some("Address of the wallet on a specific chain (CAIP-2)."),
      inputSchema = ujson.Obj(
        "type"       -> ujson.Str("object"),
        "properties" -> ujson.Obj(
          "chainId" -> ujson.Obj(
            "type"        -> ujson.Str("string"),
            "description" -> ujson.Str("CAIP-2 chain id, e.g. \"eip155:8453\""),
          ),
        ),
        "required" -> ujson.Arr(ujson.Str("chainId")),
      ),
      handler = args => awaitTool(handleGetAddress(args)),
    )

  private def handleGetAddress(args: Map[String, Any]): Future[ToolHandlerResult] =
    args.get("chainId").map(_.toString) match
      case None =>
        Future.successful(errorResult("chainId is required"))
      case Some(chainStr) =>
        val chain = ChainId(chainStr)
        if !policy.visibleChains(manager.chains).contains(chain) then
          Future.successful(errorResult(s"chain $chain not exposed by policy"))
        else
          (manager.strategyFor(chain), manager.adapterFor(chain)) match
            case (Some(strategy), Some(adapter)) =>
              strategy.getAddress(adapter).map { addr =>
                ToolHandlerResult(
                  content = List(ujson.Obj(
                    "chainId" -> ujson.Str(chain.caip2),
                    "address" -> ujson.Str(addr),
                  )),
                  isError = false,
                )
              }
            case _ =>
              Future.successful(errorResult(s"no strategy/adapter registered for $chain"))

  private def registerGetBalance(builder: McpServerBuilder): Unit =
    builder.tool(
      name        = "wallet.getBalance",
      description = Some("Native or token balance of the wallet on a chain."),
      inputSchema = ujson.Obj(
        "type"       -> ujson.Str("object"),
        "properties" -> ujson.Obj(
          "chainId" -> ujson.Obj("type" -> ujson.Str("string")),
          "asset"   -> ujson.Obj(
            "type"       -> ujson.Str("object"),
            "description"-> ujson.Str("Asset {address,symbol,decimals}. Absent = native coin."),
            "properties" -> ujson.Obj(
              "address"  -> ujson.Obj("type" -> ujson.Str("string")),
              "symbol"   -> ujson.Obj("type" -> ujson.Str("string")),
              "decimals" -> ujson.Obj("type" -> ujson.Str("integer")),
            ),
          ),
        ),
        "required" -> ujson.Arr(ujson.Str("chainId")),
      ),
      handler = args => awaitTool(handleGetBalance(args)),
    )

  private def handleGetBalance(args: Map[String, Any]): Future[ToolHandlerResult] =
    args.get("chainId").map(_.toString) match
      case None =>
        Future.successful(errorResult("chainId is required"))
      case Some(chainStr) =>
        val chain = ChainId(chainStr)
        if !policy.visibleChains(manager.chains).contains(chain) then
          Future.successful(errorResult(s"chain $chain not exposed by policy"))
        else
          (manager.strategyFor(chain), manager.adapterFor(chain)) match
            case (Some(strategy), Some(adapter)) =>
              val ctx = ctxFor(chain)
              for
                addr    <- strategy.getAddress(adapter)
                assetOpt = parseAsset(args.get("asset"), chain)
                balance <- assetOpt match
                  case None    => adapter.nativeBalance(addr, ctx)
                  case Some(a) => adapter.tokenBalance(a, addr, ctx)
              yield
                ToolHandlerResult(
                  content = List(ujson.Obj(
                    "chainId"  -> ujson.Str(chain.caip2),
                    "address"  -> ujson.Str(addr),
                    "asset"    -> assetOpt.map(a => ujson.Str(a.address)).getOrElse(ujson.Str("native")),
                    "value"    -> ujson.Str(balance.toString),
                    "decimals" -> assetOpt.map(a => ujson.Num(a.decimals.toDouble)).getOrElse(ujson.Num(18)),
                    "symbol"   -> assetOpt.map(a => ujson.Str(a.symbol)).getOrElse(ujson.Str("native")),
                  )),
                  isError = false,
                )
            case _ =>
              Future.successful(errorResult(s"no strategy/adapter registered for $chain"))

  // ── helpers ────────────────────────────────────────────────────────────

  private def parseAsset(raw: Option[Any], chain: ChainId): Option[Asset] =
    raw.flatMap {
      case m: Map[_, _] =>
        val mm = m.asInstanceOf[Map[String, Any]]
        for
          addr     <- mm.get("address").map(_.toString)
          symbol    = mm.get("symbol").map(_.toString).getOrElse("?")
          decimals  = mm.get("decimals").map(d => d.toString.toDouble.toInt).getOrElse(18)
        yield Asset(chain, addr, symbol, decimals)
      case _ => None
    }

  private def errorResult(message: String): ToolHandlerResult =
    ToolHandlerResult(
      content = List(ujson.Obj("error" -> ujson.Str(message))),
      isError = true,
    )

  /** Tool handlers in mcp-common are synchronous. Our wallet ops are
   *  Future-shaped; this blocks for the result. Acceptable for stdio
   *  servers (one request at a time) and for the read-only ops here
   *  (all complete in milliseconds). When signing tools land we'll
   *  revisit this for the elicitation round-trip. */
  private def awaitTool(f: Future[ToolHandlerResult]): ToolHandlerResult =
    try Await.result(f, 30.seconds)
    catch case ex: Throwable => errorResult(s"tool failed: ${ex.getMessage}")
