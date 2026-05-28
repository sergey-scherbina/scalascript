package scalascript.gov.social

import scalascript.gov.*
import java.time.LocalDate

case class EmployeeRecord(
  firstName:    String,
  lastName:     String,
  pesel:        Option[TaxId]      = None,
  passport:     Option[String]     = None,
  birthDate:    LocalDate,
  address:      Address,
  contractType: ContractType,
  startDate:    LocalDate,
  employer:     BusinessEntity,
  metadata:     Map[String, String] = Map.empty
)

enum ContractType:
  case Employment     // umowa o pracę
  case Mandate        // umowa zlecenie
  case SpecificWork   // umowa o dzieło
  case B2B            // business-to-business contractor

enum DeregistrationReason:
  case Termination; case Resignation; case Retirement; case Death; case Other(code: String)
