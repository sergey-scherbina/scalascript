package scalascript.gov

import java.time.LocalDate

case class Address(
  line1:      String,
  line2:      Option[String] = None,
  city:       String,
  postalCode: String,
  country:    CountryCode,
  region:     Option[String] = None
)

case class BusinessEntity(
  name:          String,
  legalForm:     LegalForm,
  country:       CountryCode,
  taxIds:        List[TaxIdentifier],
  address:       Address,
  vatRegistered: Boolean              = false,
  registeredAt:  Option[LocalDate]   = None,
  metadata:      Map[String, String] = Map.empty
):
  def taxId(t: TaxIdType): Option[TaxId] =
    taxIds.find(_.idType == t).map(_.value)

  def requireTaxId(t: TaxIdType): TaxId =
    taxId(t).getOrElse(throw BureauError.MissingTaxId(t, country))
