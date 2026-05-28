package scalascript.gov.pl.fiscal

case class PlKsefConfig(
  nip:     String,
  baseUrl: String = "https://ksef.mf.gov.pl/api",
)

object PlKsefConfig:
  def fromEnv: PlKsefConfig = PlKsefConfig(
    nip     = sys.env.getOrElse("KSEF_NIP", ""),
    baseUrl = sys.env.getOrElse("KSEF_BASE_URL", "https://ksef.mf.gov.pl/api"),
  )

  def test(nip: String): PlKsefConfig = PlKsefConfig(
    nip     = nip,
    baseUrl = "https://ksef-test.mf.gov.pl/api",
  )
