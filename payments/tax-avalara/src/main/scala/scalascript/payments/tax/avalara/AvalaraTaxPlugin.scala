package scalascript.payments.tax.avalara

/** ServiceLoader entry point for the Avalara AvaTax adapter. */
class AvalaraTaxPlugin:
  def id:          String             = "avalara"
  def displayName: String             = "Avalara AvaTax REST v2"
  def create():    AvalaraTaxProvider = AvalaraTaxProvider(AvalaraConfig.fromEnv)
