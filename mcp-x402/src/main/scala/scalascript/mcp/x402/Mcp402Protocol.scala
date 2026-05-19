package scalascript.mcp.x402

/** Protocol constants and key conventions for x402-over-MCP.
 *  See docs/mcp-x402-wallet.md §6 for the full design. */
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
  val MetaX402Key: String        = "x402"
  val MetaPaymentField: String   = "payment"      // base64 PaymentPayload
  val MetaSettledField: String   = "settled"      // optional response hint

  /** Build the `error.data` payload that a -32402 response carries.
   *  Mirrors the HTTP 402 response body shape — clients receive
   *  identical fields whether they're talking to an HTTP server or
   *  an MCP server, so an `X402AutoPay` middleware reuses the same
   *  parser on both channels. */
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
