package scalascript.gov

opaque type CountryCode <: String = String

object CountryCode:
  val PL: CountryCode = "PL"
  val DE: CountryCode = "DE"
  val FR: CountryCode = "FR"
  val UA: CountryCode = "UA"
  val EU: CountryCode = "EU"   // supranational (VIES, PEPPOL)

  def apply(s: String): CountryCode =
    require(s.length == 2, s"CountryCode must be 2 chars: $s")
    s.toUpperCase
