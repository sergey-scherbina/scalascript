package scalascript.gov.pl.registry

/** Configuration for the Polish registry adapters.
 *  All external API keys are optional — adapters that don't need auth
 *  (CEIDG, KRS) work without them. */
case class PlRegistryConfig(
  ceidgApiKey:      Option[String] = None,
  regonApiKey:      Option[String] = None,
  bialaListaApiKey: Option[String] = None,
  ceidgBaseUrl:     String = "https://aplikacja.ceidg.gov.pl/CEIDG/CEIDG.Public.UI",
  regonBaseUrl:     String = "https://wyszukiwarkaregon.stat.gov.pl/wsBIR/UslugaBIRzewnPubl.svc",
  bialaListaBaseUrl: String = "https://wl-api.mf.gov.pl",
  krsBaseUrl:       String = "https://api-krs.ms.gov.pl/api/krs",
)
