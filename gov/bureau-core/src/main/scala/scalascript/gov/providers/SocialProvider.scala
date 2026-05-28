package scalascript.gov.providers

import scalascript.gov.*
import scalascript.gov.social.*
import scala.concurrent.{Future, ExecutionContext}
import java.time.{LocalDate, YearMonth}

trait SocialProvider:

  def submitDeclaration(decl: ContributionDeclaration)(using ExecutionContext): Future[SubmissionResult]
  def pollDeclarationStatus(ticketId: String)(using ExecutionContext): Future[SubmissionResult]

  def getPaymentReference(entity: BusinessEntity, period: YearMonth)(using ExecutionContext): Future[PaymentReference]

  def calculateContributions(params: ContributionParams)(using ExecutionContext): Future[ContributionCalculation]

  def registerEmployee(employee: EmployeeRecord)(using ExecutionContext): Future[SubmissionResult]
  def deregisterEmployee(employee: EmployeeRecord, reason: DeregistrationReason, effectiveDate: LocalDate)
      (using ExecutionContext): Future[SubmissionResult]
  def updateEmployee(employee: EmployeeRecord)(using ExecutionContext): Future[SubmissionResult]
