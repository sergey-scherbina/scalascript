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

/** Common surface for priced MCP operations — tools, resources,
 *  prompts. Each carries a price + scope; `resourceLabel` is what
 *  goes into the `requirements.resource` field of the -32402 error,
 *  letting clients distinguish tool / resource / prompt charges.
 *
 *  Concrete subtypes:
 *    - PricedToolConfig     — `tool:<name>`
 *    - PricedResourceConfig — `resource:<uri>`
 *    - PricedPromptConfig   — `prompt:<name>` */
sealed trait PricedOperationConfig:
  def resourceLabel: String
  def description:   String
  def price:         ToolPrice
  def scope:         PaymentScope

/** Configuration for one priced MCP tool. */
case class PricedToolConfig(
  name:        String,
  description: String,
  price:       ToolPrice,
  scope:       PaymentScope = PaymentScope.oneShot,
) extends PricedOperationConfig:
  def resourceLabel: String = s"tool:$name"

/** Configuration for a priced MCP resource (`resources/read`).
 *  `uri` is the MCP resource URI (e.g. `data://premium/feed`).
 *  `mimeType` is exposed in the -32402 error data so the client
 *  knows what it's buying. */
case class PricedResourceConfig(
  uri:         String,
  description: String,
  price:       ToolPrice,
  scope:       PaymentScope   = PaymentScope.oneShot,
  mimeType:    Option[String] = None,
) extends PricedOperationConfig:
  def resourceLabel: String = s"resource:$uri"

/** Configuration for a priced MCP prompt (`prompts/get`). */
case class PricedPromptConfig(
  name:        String,
  description: String,
  price:       ToolPrice,
  scope:       PaymentScope = PaymentScope.oneShot,
) extends PricedOperationConfig:
  def resourceLabel: String = s"prompt:$name"

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
