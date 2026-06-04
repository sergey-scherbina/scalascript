package scalascript.mcp.x402

/** Protocol constants and key conventions for x402-over-MCP.
 *  See docs/specs/mcp-x402-wallet.md §6 for the full design. */
object Mcp402Protocol:

  /** JSON-RPC error code for "Payment Required". -32402 sits inside
   *  the application-defined range and rhymes with HTTP 402; if MCP
   *  upstream standardises a different code in the future we'll
   *  align without breaking semantics. */
  val PaymentRequiredCode: Int = -32402

  /** Conventional `_meta` namespace and field names. MCP `_meta`
   *  carries server-/protocol-defined metadata that piggy-backs on
   *  the standard JSON-RPC params/result envelope. Domain-qualified
   *  keys keep us forward-compatible with other extensions. */
  val MetaX402Key: String           = "x402"
  val MetaPaymentField: String      = "payment"      // base64 PaymentPayload (Phase 3)
  val MetaSettledField: String      = "settled"      // optional response hint (Phase 5)
  val MetaStreamChargeField: String = "streamCharge" // Phase 8 — actual usage

  /** Build the `error.data` payload that a -32402 response carries. */
  def paymentRequiredErrorData(
    x402Version:  Int,
    requirements: ujson.Value,
  ): ujson.Value =
    ujson.Obj(
      "x402Version"  -> ujson.Num(x402Version.toDouble),
      "requirements" -> requirements,
    )

  /** Convenience: produce the full JSON-RPC `error` object for a
   *  -32402 result. */
  def paymentRequiredError(
    message:      String,
    requirements: ujson.Value,
    x402Version:  Int = 1,
  ): ujson.Value =
    ujson.Obj(
      "code"    -> ujson.Num(PaymentRequiredCode.toDouble),
      "message" -> ujson.Str(message),
      "data"    -> paymentRequiredErrorData(x402Version, requirements),
    )

  /** Render an `_meta.x402.streamCharge` payload for a stream-priced
   *  tool's response. The server emits this to tell the client how
   *  many units were actually consumed out of the pre-authorised
   *  budget — clients track running totals via
   *  X402AutoPay.onStreamCharge. */
  def streamChargeMeta(usedUnits: Long, unitName: String, totalAmount: BigInt): ujson.Value =
    ujson.Obj(
      MetaX402Key -> ujson.Obj(
        MetaStreamChargeField -> ujson.Obj(
          "usedUnits"   -> ujson.Num(usedUnits.toDouble),
          "unitName"    -> ujson.Str(unitName),
          "totalAmount" -> ujson.Str(totalAmount.toString),
        ),
      ),
    )

  /** Parse a server-emitted `_meta.x402.streamCharge` payload from
   *  the `_meta` object of a response. Returns None when the
   *  response carries no streamCharge field. */
  def parseStreamCharge(meta: ujson.Value): Option[StreamCharge] =
    try
      val o = meta.obj(MetaX402Key).obj(MetaStreamChargeField).obj
      Some(StreamCharge(
        usedUnits   = o("usedUnits").num.toLong,
        unitName    = o("unitName").str,
        totalAmount = BigInt(o("totalAmount").str),
      ))
    catch case _: Throwable => None

/** Actual usage info a stream-priced tool reports on its response.
 *  Clients use this for accounting and budget-tracking; combined
 *  with the pre-authorised cap, the user always knows what they
 *  actually paid vs what they authorised. */
case class StreamCharge(
  usedUnits:   Long,
  unitName:    String,
  totalAmount: BigInt,
)
