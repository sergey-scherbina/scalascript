package scalascript.mcp.x402

import scala.concurrent.{ExecutionContext, Future}
import scalascript.x402.PaymentRequirements

/** `PaymentSigner` adapter that routes signing to a remote (or
 *  in-process) `mcp-wallet-server`'s `wallet.payX402` tool.
 *
 *  This is the natural production wiring for the embedded-wallet
 *  pattern: the agent talks to two MCP endpoints — a local stdio
 *  wallet (signing) and a remote priced server (paid tools) —
 *  without any shared library code between them. The autopay
 *  middleware sees a `PaymentSigner`; how the wallet is actually
 *  reached is the host's call.
 *
 *  The host provides one function: given a (toolName, arguments)
 *  pair, perform a `tools/call` on the wallet connection and return
 *  the JSON-RPC `result` field's first content entry. In-process
 *  composition wires it to `builder.tools(name).handler(args)`;
 *  cross-process composition wires it to an `McpClient.callTool(...)`. */
class McpWalletPaymentSigner(
  callTool: (String, ujson.Value) => Future[ujson.Value],
  /** Chain hint for `wallet.payX402`. Per the wallet's tool schema
   *  the chainId is required; we derive it from `requirements.network`. */
  chainIdFor: PaymentRequirements => String = req => s"eip155:${req.network.chainId}",
)(using ec: ExecutionContext) extends PaymentSigner:

  def signRequirements(req: PaymentRequirements): Future[Option[String]] =
    val amount = req.scheme match
      case scalascript.x402.PaymentScheme.Exact(a)              => a
      case scalascript.x402.PaymentScheme.Stream(_, _, _, maxA) => maxA
      case scalascript.x402.PaymentScheme.CardanoExact(love, _) => love
    val args = ujson.Obj(
      "chainId" -> ujson.Str(chainIdFor(req)),
      "requirements" -> ujson.Obj(
        "asset" -> ujson.Obj(
          "address"  -> ujson.Str(req.asset.address),
          "symbol"   -> ujson.Str(req.asset.symbol),
          "decimals" -> ujson.Num(req.asset.decimals.toDouble),
        ),
        "payTo"             -> ujson.Str(req.payTo),
        "amount"            -> ujson.Str(amount.toString),
        "maxTimeoutSeconds" -> ujson.Num(req.maxTimeoutSeconds.toDouble),
      ),
    )
    callTool("wallet.payX402", args).map { result =>
      // `wallet.payX402` returns the headerValue as a string field.
      result.obj.get("headerValue").map(_.str)
    }.recover { case _ => None }
