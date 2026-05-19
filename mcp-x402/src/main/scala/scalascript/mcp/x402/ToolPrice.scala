package scalascript.mcp.x402

import scalascript.x402.{Asset, Network, PaymentRequirements, PaymentScheme}

/** Pricing for an MCP operation. Two variants:
 *
 *  - `Pricing.Exact(amount, asset, payTo)` — fixed price per call.
 *    Backwards-compatible with the Phase 3-6 `ToolPrice(amount,
 *    asset, payTo)` constructor, which is now a type alias for
 *    `Pricing.Exact`.
 *
 *  - `Pricing.Stream(ratePerUnit, unitName, maxUnits, maxAmount,
 *    asset, payTo)` — metered pricing per unit (token, second,
 *    byte, request). Phase 8.
 *
 *  Common interface: `asset`, `payTo`, and `maxAmount` — the cap
 *  the client must pre-authorise via x402.
 */
sealed trait Pricing:
  def asset:     Asset
  def payTo:     String
  /** Maximum amount the client must pre-authorise for this
   *  operation. Equal to the per-call price for Exact; equal to
   *  the budget cap for Stream. */
  def maxAmount: BigInt

  /** Render this pricing into an x402-core `PaymentRequirements`
   *  for `resource` — what gets included in the -32402 error data. */
  def toRequirements(resource: String, description: String, maxTimeoutSeconds: Int = 300): PaymentRequirements =
    val scheme = this match
      case Pricing.Exact(amt, _, _)           => PaymentScheme.Exact(amt)
      case s: Pricing.Stream                  =>
        PaymentScheme.Stream(s.ratePerUnit, s.unitName, s.maxUnits, s.maxAmount)
    PaymentRequirements(
      scheme            = scheme,
      network           = asset.network,
      asset             = asset,
      payTo             = payTo,
      resource          = resource,
      description       = description,
      maxTimeoutSeconds = maxTimeoutSeconds,
    )

object Pricing:
  /** Fixed per-call price. */
  case class Exact(amount: BigInt, asset: Asset, payTo: String) extends Pricing:
    def maxAmount: BigInt = amount

  /** Metered (streaming) pricing — rate-per-unit with a budget cap. */
  case class Stream(
    ratePerUnit: BigInt,
    unitName:    String,         // "token", "second", "byte", "request", …
    maxUnits:    Int,
    maxAmount:   BigInt,
    asset:       Asset,
    payTo:       String,
  ) extends Pricing

/** Backwards-compat alias for Phase 3-6 callers that wrote
 *  `ToolPrice(amount, asset, payTo)`. */
type ToolPrice = Pricing.Exact
object ToolPrice:
  def apply(amount: BigInt, asset: Asset, payTo: String): ToolPrice =
    Pricing.Exact(amount, asset, payTo)

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
  def price:         Pricing
  def scope:         PaymentScope

/** Configuration for one priced MCP tool. */
case class PricedToolConfig(
  name:        String,
  description: String,
  price:       Pricing,
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
  price:       Pricing,
  scope:       PaymentScope   = PaymentScope.oneShot,
  mimeType:    Option[String] = None,
) extends PricedOperationConfig:
  def resourceLabel: String = s"resource:$uri"

/** Configuration for a priced MCP prompt (`prompts/get`). */
case class PricedPromptConfig(
  name:        String,
  description: String,
  price:       Pricing,
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
