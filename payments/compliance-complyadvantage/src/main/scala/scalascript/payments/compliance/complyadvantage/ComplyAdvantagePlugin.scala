package scalascript.payments.compliance.complyadvantage

/** ServiceLoader entry point for the ComplyAdvantage adapter. */
class ComplyAdvantagePlugin:
  def id:          String                    = "complyadvantage"
  def displayName: String                    = "ComplyAdvantage v1"
  def create():    ComplyAdvantageProvider   = ComplyAdvantageProvider(ComplyAdvantageConfig.fromEnv)
