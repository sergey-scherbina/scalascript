package scalascript.payments.compliance.chainalysis

/** ServiceLoader entry point for the Chainalysis KYT adapter. */
class ChainalysisPlugin:
  def id:          String                 = "chainalysis"
  def displayName: String                 = "Chainalysis KYT v2"
  def create():    ChainalysisProvider    = ChainalysisProvider(ChainalysisConfig.fromEnv)
