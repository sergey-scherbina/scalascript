package scalascript.payments.fx.oer

/** ServiceLoader entry point for the Open Exchange Rates FX adapter.
 *
 *  Schemes: `"openexchangerates"` and `"oer"`
 *
 *  `create()` reads `OPENEXCHANGERATES_APP_ID` from the environment.
 */
class OerFxPlugin:
  def schemes:     Seq[String]   = Seq("openexchangerates", "oer")
  def displayName: String        = "Open Exchange Rates (OER) API v6"
  def create():    OerFxProvider = OerFxProvider(OerConfig.fromEnv)
