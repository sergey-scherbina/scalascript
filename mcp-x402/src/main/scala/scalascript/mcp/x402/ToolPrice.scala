package scalascript.mcp.x402

import scalascript.x402.{Asset, Network, PaymentRequirements, PaymentScheme}

/** Per-call price attached to a priced MCP tool. */
case class ToolPrice(
  amount: BigInt,
  asset:  Asset,
  payTo:  String,
):
  /** Render this price into an x402 `PaymentRequirements` for a
   *  given resource — what gets included in the -32402 error data. */
  def toRequirements(resource: String, description: String, maxTimeoutSeconds: Int = 300): PaymentRequirements =
    PaymentRequirements(
      scheme            = PaymentScheme.Exact(amount),
      network           = asset.network,
      asset             = asset,
      payTo             = payTo,
      resource          = resource,
      description       = description,
      maxTimeoutSeconds = maxTimeoutSeconds,
    )

/** Whether a payment authorises a single call or a (short-lived)
 *  session of subsequent calls. */
case class PaymentScope(
  oneShot: Boolean = true,
  ttlSec:  Int     = 300,
)

object PaymentScope:
  val oneShot: PaymentScope = PaymentScope(oneShot = true, ttlSec = 0)
  def session(ttlSec: Int): PaymentScope = PaymentScope(oneShot = false, ttlSec = ttlSec)

/** Configuration for one priced MCP tool. The full set of fields the
 *  enclosing host needs in order to register it onto its
 *  `McpServerBuilder` and run the verify/dispatch dance via
 *  `Mcp402Dispatcher`. */
case class PricedToolConfig(
  name:        String,
  description: String,
  price:       ToolPrice,
  scope:       PaymentScope = PaymentScope.oneShot,
)

/** A successfully-verified payment, returned to the host so it can
 *  log it / emit `_meta.x402.settled` in the eventual response. */
case class VerifiedPayment(
  rawHeader: String,       // base64 form the client sent
  network:   Network,
  amount:    BigInt,
  from:      String,
  to:        String,
  txHash:    Option[String] = None,  // populated after settle
)
