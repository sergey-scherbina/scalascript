package scalascript.gov.pl.social

import scalascript.gov.TaxId

case class PlZusConfig(
  nip:      TaxId,
  login:    String,
  password: Array[Char],
  baseUrl:  String = "https://pue.zus.pl",
)

object PlZusConfig:
  def fromEnv: PlZusConfig = PlZusConfig(
    nip      = TaxId(sys.env.getOrElse("ZUS_NIP", "")),
    login    = sys.env.getOrElse("ZUS_LOGIN", ""),
    password = sys.env.getOrElse("ZUS_PASSWORD", "").toCharArray,
    baseUrl  = sys.env.getOrElse("ZUS_BASE_URL", "https://pue.zus.pl"),
  )
