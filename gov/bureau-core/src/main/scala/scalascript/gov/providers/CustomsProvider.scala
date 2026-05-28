package scalascript.gov.providers

import scalascript.gov.*
import scala.concurrent.{Future, ExecutionContext}
import java.time.YearMonth

trait CustomsProvider:
  def submitIntrastat(report: IntrastatReport)(using ExecutionContext): Future[SubmissionResult]
  def pollStatus(ticketId: String)(using ExecutionContext): Future[SubmissionResult]

case class IntrastatReport(
  period:     YearMonth,
  flow:       TradeFlow,
  entity:     BusinessEntity,
  lines:      List[IntrastatLine],
  xmlContent: String
)

case class IntrastatLine(
  commodityCode: String,
  description:   String,
  countryOfOrigin: CountryCode,
  netMass:       BigDecimal,
  quantity:      BigDecimal,
  value:         scalascript.payments.money.Money
)

enum TradeFlow:
  case Arrival; case Dispatch
