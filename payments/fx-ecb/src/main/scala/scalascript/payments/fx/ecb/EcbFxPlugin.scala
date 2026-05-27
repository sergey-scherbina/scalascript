package scalascript.payments.fx.ecb

/** ServiceLoader entry point for the ECB FX adapter.
 *
 *  Scheme: `"ecb"`
 *
 *  Registered via `META-INF/services/scalascript.payments.fx.ecb.EcbFxPlugin`
 *  (not a Backend SPI — this is a pure payments SPI).
 */
class EcbFxPlugin:
  def scheme:      String        = "ecb"
  def displayName: String        = "ECB daily reference rates"
  def create():    EcbFxProvider = EcbFxProvider()
