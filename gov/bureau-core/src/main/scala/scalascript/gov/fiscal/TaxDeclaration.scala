package scalascript.gov.fiscal

import scalascript.gov.BusinessEntity
import java.time.YearMonth

case class TaxDeclaration(
  declarationType: String,
  period:          YearMonth,
  entity:          BusinessEntity,
  xmlContent:      String,
  schemaVersion:   String
)
