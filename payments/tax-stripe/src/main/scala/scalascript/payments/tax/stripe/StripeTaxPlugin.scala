package scalascript.payments.tax.stripe

/** ServiceLoader entry point for the Stripe Tax adapter.
 *
 *  Registered via `META-INF/services/scalascript.payments.tax.stripe.StripeTaxPlugin`.
 */
class StripeTaxPlugin:
  def id:          String            = "stripe-tax"
  def displayName: String            = "Stripe Tax Calculations API v1"
  def create():    StripeTaxProvider = StripeTaxProvider(StripeTaxConfig.fromEnv)
