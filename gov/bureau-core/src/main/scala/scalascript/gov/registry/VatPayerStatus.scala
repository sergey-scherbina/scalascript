package scalascript.gov.registry

import scalascript.gov.TaxIdentifier
import java.time.Instant

case class VatPayerStatus(
  active:       Boolean,
  id:           TaxIdentifier,
  name:         Option[String],
  bankAccounts: List[String],
  checkedAt:    Instant
)
