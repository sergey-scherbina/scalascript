package scalascript.gov.social

import scalascript.gov.BusinessEntity
import java.time.YearMonth

case class ContributionDeclaration(
  declarationType: String,
  period:          YearMonth,
  employer:        BusinessEntity,
  xmlContent:      String,
  schemaVersion:   String
)
