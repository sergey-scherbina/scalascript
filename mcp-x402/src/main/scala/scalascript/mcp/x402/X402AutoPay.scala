package scalascript.mcp.x402

import scala.concurrent.{ExecutionContext, Future}
import scalascript.x402.*

/** Client-side middleware that handles MCP `-32402 Payment Required`
 *  responses transparently. Wraps a raw request function; on a
 *  `-32402` it parses `error.data.requirements`, asks the configured
 *  `PaymentSigner` for a header, attaches `_meta.x402.payment` to
 *  the original params, and retries once.
 *
 *  The agent calling the wrapped function sees only the eventual
 *  success (or a non-payment error). The 402 round-trip is invisible.
 *
 *  Caps:
 *
 *    - `maxAmount`: ceiling on a single payment. Independent of any
 *      cap the wallet enforces. Defence-in-depth — neither layer
 *      alone is trusted to bound spend.
 *    - `maxRetries`: how many -32402 round-trips per call. Defaults
 *      to 1 — re-presenting a request with payment shouldn't bounce
 *      back as -32402 unless something's wrong. */
class X402AutoPay(
  val signer:        PaymentSigner,
  val maxAmount:     BigInt,
  val onCharge:      (PaymentRequirements, String /* base64 header */) => Unit = (_, _) => (),
  val onStreamCharge: (StreamCharge) => Unit = _ => (),
  val maxRetries:    Int = 1,
)(using ec: ExecutionContext):

  /** Wrap a JSON-RPC `request(method, params)` function with auto-pay
   *  behaviour. The wrapped function calls the underlying send; on a
   *  `-32402` it signs + retries; final result is bubbled up.
   *
   *  The send fn must throw / fail the Future on JSON-RPC errors
   *  rather than returning an "error" envelope — that's how the
   *  middleware detects -32402. The most natural integration is
   *  `mcp-common`'s client core, which already raises on errors.
   *
   *  On a successful response, the middleware additionally inspects
   *  `_meta.x402.streamCharge` (Phase 8 stream-priced tools) and
   *  fires `onStreamCharge` with the parsed metrics. The caller
   *  receives the response unchanged. */
  def wrap(send: (String, ujson.Value) => Future[ujson.Value]): (String, ujson.Value) => Future[ujson.Value] =
    (method, params) => callWithRetries(send, method, params, retriesLeft = maxRetries)

  private def callWithRetries(
    send:        (String, ujson.Value) => Future[ujson.Value],
    method:      String,
    params:      ujson.Value,
    retriesLeft: Int,
  ): Future[ujson.Value] =
    send(method, params).map(result =>
      // On any success, look for a streamCharge metadata block and
      // report it to the budget tracker. The result is returned
      // unchanged.
      result.obj.get("_meta").foreach { meta =>
        Mcp402Protocol.parseStreamCharge(meta).foreach(onStreamCharge)
      }
      result,
    ).recoverWith {
      case ex: PaymentRequiredException if retriesLeft > 0 =>
        handle402(ex.requirements, method, params).flatMap {
          case None =>
            // Refusal / over-budget: surface as the original 402 error.
            Future.failed(ex)
          case Some(paidParams) =>
            callWithRetries(send, method, paidParams, retriesLeft - 1)
        }
    }

  private def handle402(req: PaymentRequirements, @annotation.unused method: String, params: ujson.Value): Future[Option[ujson.Value]] =
    val amount = req.scheme match
      case PaymentScheme.Exact(a)              => a
      case PaymentScheme.Stream(_, _, _, maxA) => maxA
      case PaymentScheme.CardanoExact(love, _) => love
    if amount > maxAmount then
      Future.successful(None)
    else
      signer.signRequirements(req).map {
        case None =>
          None
        case Some(header) =>
          onCharge(req, header)
          Some(attachPaymentMeta(params, header))
      }

  private def attachPaymentMeta(params: ujson.Value, header: String): ujson.Value =
    val base = params match
      case obj: ujson.Obj => ujson.Obj.from(obj.value)
      case _              => ujson.Obj()
    val meta = base.obj.get("_meta") match
      case Some(m: ujson.Obj) => ujson.Obj.from(m.value)
      case _                  => ujson.Obj()
    val x402 = meta.obj.get("x402") match
      case Some(m: ujson.Obj) => ujson.Obj.from(m.value)
      case _                  => ujson.Obj()
    x402("payment") = ujson.Str(header)
    meta("x402")    = x402
    base("_meta")   = meta
    base

/** Raised by `X402AutoPay` callers when the underlying send fn
 *  receives a `-32402` JSON-RPC error. The caller's send fn parses
 *  the error envelope and throws this — the middleware catches and
 *  handles it.
 *
 *  Provided as a separate exception type so the middleware can match
 *  precisely without inspecting error-code numbers itself. */
case class PaymentRequiredException(
  message:      String,
  requirements: PaymentRequirements,
  raw:          ujson.Value,
) extends Exception(message)

object PaymentRequiredException:
  /** Try to parse a generic JSON-RPC error object as a -32402 with
   *  embedded requirements. Returns None if the error is something
   *  else. */
  def tryParse(error: ujson.Value): Option[PaymentRequiredException] =
    try
      val code = error("code").num.toInt
      if code != Mcp402Protocol.PaymentRequiredCode then None
      else
        val data         = error("data").obj
        val message      = error("message").str
        val requirements = parseRequirements(data("requirements"))
        Some(PaymentRequiredException(message, requirements, error))
    catch case _: Throwable => None

  private def parseRequirements(j: ujson.Value): PaymentRequirements =
    val o       = j.obj
    val network = o("network").str match
      case "Base"            | "eip155:8453"  => Network.Base
      case "BaseSepolia"     | "eip155:84532" => Network.BaseSepolia
      case "EthereumMainnet" | "eip155:1"     => Network.EthereumMainnet
      case "Polygon"         | "eip155:137"   => Network.Polygon
      case "Arbitrum"        | "eip155:42161" => Network.Arbitrum
      case "Optimism"        | "eip155:10"    => Network.Optimism
      case other                              => throw new IllegalArgumentException(s"network: $other")
    val asset = Asset(
      address  = o("asset").obj("address").str,
      symbol   = o("asset").obj("symbol").str,
      decimals = o("asset").obj("decimals").num.toInt,
      network  = network,
    )
    val scheme = o("scheme").obj("type").str match
      case "exact"  => PaymentScheme.Exact(BigInt(o("scheme").obj("amount").str))
      case "stream" =>
        val s = o("scheme").obj
        PaymentScheme.Stream(
          ratePerUnit = BigInt(s("ratePerUnit").str),
          unitName    = s("unitName").str,
          maxUnits    = s("maxUnits").num.toInt,
          maxAmount   = BigInt(s("maxAmount").str),
        )
      case other => throw new IllegalArgumentException(s"unsupported scheme: $other")
    PaymentRequirements(
      scheme            = scheme,
      network           = network,
      asset             = asset,
      payTo             = o("payTo").str,
      resource          = o.get("resource").map(_.str).getOrElse(""),
      description       = o.get("description").map(_.str).getOrElse(""),
      maxTimeoutSeconds = o.get("maxTimeoutSeconds").map(_.num.toInt).getOrElse(300),
    )
