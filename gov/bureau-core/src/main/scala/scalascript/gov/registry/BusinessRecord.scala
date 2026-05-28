package scalascript.gov.registry

import scalascript.gov.*
import scalascript.payments.money.Money
import java.time.LocalDate

case class BusinessRecord(
  name:         String,
  legalForm:    Option[LegalForm],
  taxIds:       List[TaxIdentifier],
  address:      Option[Address],
  status:       RegistrationStatus,
  registeredAt: Option[LocalDate],
  metadata:     Map[String, String] = Map.empty
)

enum RegistrationStatus:
  case Active; case Suspended; case Liquidation; case Dissolved; case Unknown

case class RegistrationDetails(
  record:       BusinessRecord,
  directors:    List[String],
  shareholders: List[String],
  activities:   List[String],
  capital:      Option[Money],
  rawData:      Map[String, String] = Map.empty
)
