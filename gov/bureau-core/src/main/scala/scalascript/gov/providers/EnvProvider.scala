package scalascript.gov.providers

import scalascript.gov.*
import scala.concurrent.{Future, ExecutionContext}
import java.time.YearMonth

trait EnvProvider:
  def submitReport(report: EnvironmentReport)(using ExecutionContext): Future[SubmissionResult]
  def pollStatus(ticketId: String)(using ExecutionContext): Future[SubmissionResult]

case class EnvironmentReport(
  reportType: String,
  period:     YearMonth,
  entity:     BusinessEntity,
  xmlContent: String
)
