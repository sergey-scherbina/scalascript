package scalascript.mcp.x402

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scalascript.x402.*

/** Verification / dispatch logic for x402-over-MCP priced tools.
 *
 *  Server-side recipe for a priced tool:
 *
 *  1. Receive a `tools/call` JSON-RPC request.
 *  2. Look up the tool's `PricedToolConfig` (carries the price).
 *  3. Call `dispatch(config, params)` (`params` is the full
 *     `tools/call` params object — args + optional `_meta`).
 *  4. On `Left(error)`: serialise the error as the JSON-RPC response
 *     directly (the code is -32402; the host's tool dispatch loop
 *     bypasses the tool handler).
 *  5. On `Right(payment)`: call the underlying tool handler;
 *     optionally include `_meta.x402.settled` in the response.
 *
 *  The mcp-common integration that hooks this into
 *  `McpServerBuilder.tool` is a thin wrapper — see Mcp402Integration. */
class Mcp402Dispatcher(
  val facilitator: Facilitator,
)(using ec: ExecutionContext):

  /** Returns Right(VerifiedPayment) when the request carries a
   *  payment header that the facilitator accepted; Left(errorJson)
   *  with code -32402 otherwise. The Left error is in the JSON-RPC
   *  shape so the host can attach it as-is to its response. */
  /** Dispatch a priced `tools/call`. Returns Right(VerifiedPayment)
   *  when the request carries a payment the facilitator accepts;
   *  Left(errorJson) with code -32402 otherwise. The Left error is
   *  in JSON-RPC error shape so the host attaches it as-is. */
  def dispatchTool(config: PricedToolConfig, params: ujson.Value): Future[Either[ujson.Value, VerifiedPayment]] =
    dispatchAny(config, params)

  /** Dispatch a priced `resources/read`. Same -32402 / payment flow
   *  as tools — the only difference is the `resource` label in the
   *  emitted requirements (`resource:<uri>` vs `tool:<name>`). */
  def dispatchResource(config: PricedResourceConfig, params: ujson.Value): Future[Either[ujson.Value, VerifiedPayment]] =
    dispatchAny(config, params)

  /** Dispatch a priced `prompts/get`. */
  def dispatchPrompt(config: PricedPromptConfig, params: ujson.Value): Future[Either[ujson.Value, VerifiedPayment]] =
    dispatchAny(config, params)

  /** Backwards-compat alias for the original `dispatch(toolConfig,
   *  params)` shape from Phase 3 — old callers keep working. */
  def dispatch(config: PricedToolConfig, params: ujson.Value): Future[Either[ujson.Value, VerifiedPayment]] =
    dispatchTool(config, params)

  /** Type-agnostic core. The operation kind is encoded in
   *  `config.resourceLabel`; everything else is identical across
   *  tools / resources / prompts. */
  private def dispatchAny(config: PricedOperationConfig, params: ujson.Value): Future[Either[ujson.Value, VerifiedPayment]] =
    extractPaymentHeader(params) match
      case None =>
        Future.successful(Left(
          Mcp402Protocol.paymentRequiredError(
            message      = s"payment required for ${config.resourceLabel}",
            requirements = renderRequirements(config),
          )
        ))
      case Some(headerB64) =>
        parsePayload(headerB64) match
          case Left(reason) =>
            Future.successful(Left(
              Mcp402Protocol.paymentRequiredError(
                message      = s"malformed _meta.x402.payment: $reason",
                requirements = renderRequirements(config),
              )
            ))
          case Right((payload, requirements)) =>
            facilitator.verify(payload, requirements).map {
              case VerifyResult.Ok =>
                Right(VerifiedPayment(
                  rawHeader = headerB64,
                  network   = payload.network,
                  amount    = payload.authorization.value,
                  from      = payload.authorization.from,
                  to        = payload.authorization.to,
                ))
              case VerifyResult.Fail(reason) =>
                Left(Mcp402Protocol.paymentRequiredError(
                  message      = s"payment verification failed: $reason",
                  requirements = renderRequirements(config),
                ))
            }.recover { case ex =>
              Left(Mcp402Protocol.paymentRequiredError(
                message      = s"verify error: ${ex.getMessage}",
                requirements = renderRequirements(config),
              ))
            }

  // ── extraction helpers ───────────────────────────────────────────────

  /** Pull `_meta.x402.payment` (base64 string) out of a `tools/call`
   *  params object. Returns None if any expected key is missing. */
  private def extractPaymentHeader(params: ujson.Value): Option[String] =
    try
      val meta    = params.obj.get("_meta").flatMap(v => v.objOpt)
      val x402    = meta.flatMap(_.get(Mcp402Protocol.MetaX402Key)).flatMap(_.objOpt)
      val payment = x402.flatMap(_.get(Mcp402Protocol.MetaPaymentField)).flatMap {
        case ujson.Str(s) => Some(s)
        case _            => None
      }
      payment
    catch case _: Throwable => None

  /** Decode the base64 header and parse the embedded
   *  PaymentPayload JSON. The shape mirrors what
   *  `x402-client.PayloadBuilder.encode` produces. */
  private def parsePayload(headerB64: String): Either[String, (PaymentPayload, PaymentRequirements)] =
    try
      val jsonBytes = Base64.getDecoder.decode(headerB64)
      val json      = ujson.read(new String(jsonBytes, "UTF-8")).obj
      val network   = parseNetwork(json("network").str)
      val auth      = json("authorization").obj
      val payload = PaymentPayload(
        x402Version   = json.get("x402Version").map(_.num.toInt).getOrElse(1),
        scheme        = parseScheme(json("scheme")),
        network       = network,
        authorization = TransferAuthorization(
          from        = auth("from").str,
          to          = auth("to").str,
          value       = BigInt(auth("value").str),
          validAfter  = BigInt(auth("validAfter").str),
          validBefore = BigInt(auth("validBefore").str),
          nonce       = auth("nonce").str,
        ),
        signature     = json("signature").str,
      )
      // Reconstruct PaymentRequirements from the embedded info — the
      // facilitator's verify needs a (payload, requirements) pair.
      // We synthesise it from the payload + an Asset entry derived
      // from the standard registry (or fall back to the embedded
      // values if Asset.address matches).
      val req = PaymentRequirements(
        scheme            = payload.scheme,
        network           = network,
        asset             = Assets.usdc(network),
        payTo             = payload.authorization.to,
        resource          = json.obj.get("resource").map(_.str).getOrElse(""),
        description       = json.obj.get("description").map(_.str).getOrElse(""),
        maxTimeoutSeconds = 300,
      )
      Right((payload, req))
    catch case ex: Throwable => Left(ex.getMessage)

  private def parseNetwork(s: String): Network = s match
    case "Base"            | "eip155:8453"  => Network.Base
    case "BaseSepolia"     | "eip155:84532" => Network.BaseSepolia
    case "EthereumMainnet" | "eip155:1"     => Network.EthereumMainnet
    case "Polygon"         | "eip155:137"   => Network.Polygon
    case "Arbitrum"        | "eip155:42161" => Network.Arbitrum
    case "Optimism"        | "eip155:10"    => Network.Optimism
    case other                              => throw new IllegalArgumentException(s"Unknown network: $other")

  private def parseScheme(j: ujson.Value): PaymentScheme =
    j("type").str match
      case "exact" =>
        PaymentScheme.Exact(BigInt(j("amount").str))
      case "stream" =>
        PaymentScheme.Stream(
          ratePerUnit = BigInt(j("ratePerUnit").str),
          unitName    = j("unitName").str,
          maxUnits    = j("maxUnits").num.toInt,
          maxAmount   = BigInt(j("maxAmount").str),
        )
      case other =>
        throw new IllegalArgumentException(s"Unsupported scheme in payment: $other")

  private def renderRequirements(config: PricedOperationConfig): ujson.Value =
    val req = config.price.toRequirements(config.resourceLabel, config.description)
    val base = ujson.Obj(
      "x402Version" -> ujson.Num(1),
      "scheme"      -> ujson.Obj("type" -> ujson.Str("exact"), "amount" -> ujson.Str(config.price.amount.toString)),
      "network"     -> ujson.Str(req.network.toString),
      "chainId"     -> ujson.Num(req.network.chainId.toDouble),
      "asset"       -> ujson.Obj(
        "address"  -> ujson.Str(req.asset.address),
        "symbol"   -> ujson.Str(req.asset.symbol),
        "decimals" -> ujson.Num(req.asset.decimals.toDouble),
      ),
      "payTo"             -> ujson.Str(req.payTo),
      "resource"          -> ujson.Str(req.resource),
      "description"       -> ujson.Str(req.description),
      "maxTimeoutSeconds" -> ujson.Num(req.maxTimeoutSeconds.toDouble),
      "scope" -> ujson.Obj(
        "oneShot" -> ujson.Bool(config.scope.oneShot),
        "ttlSec"  -> ujson.Num(config.scope.ttlSec.toDouble),
      ),
    )
    // Resource-kind hint so clients can tell tool-priced from
    // resource-priced from prompt-priced calls without re-parsing
    // the `resource` field's prefix.
    config match
      case _: PricedToolConfig     => base("kind") = ujson.Str("tool")
      case r: PricedResourceConfig =>
        base("kind") = ujson.Str("resource")
        r.mimeType.foreach(mt => base("mimeType") = ujson.Str(mt))
      case _: PricedPromptConfig   => base("kind") = ujson.Str("prompt")
    base
