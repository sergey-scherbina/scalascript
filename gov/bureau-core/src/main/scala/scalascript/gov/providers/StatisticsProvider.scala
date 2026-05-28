package scalascript.gov.providers

import scalascript.gov.*
import scala.concurrent.{Future, ExecutionContext}
import java.time.YearMonth

trait StatisticsProvider:
  def submitReport(report: StatisticsReport)(using ExecutionContext): Future[SubmissionResult]
  def pollStatus(ticketId: String)(using ExecutionContext): Future[SubmissionResult]

case class StatisticsReport(
  reportType: String,
  period:     YearMonth,
  entity:     BusinessEntity,
  xmlContent: String
)
