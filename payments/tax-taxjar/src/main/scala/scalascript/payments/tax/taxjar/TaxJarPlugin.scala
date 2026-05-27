package scalascript.payments.tax.taxjar

/** ServiceLoader entry point for the TaxJar adapter. */
class TaxJarPlugin:
  def id:          String             = "taxjar"
  def displayName: String             = "TaxJar SmartCalcs v2"
  def create():    TaxJarProvider  = TaxJarProvider(TaxJarConfig.fromEnv)
