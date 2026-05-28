package scalascript.gov.mock

import scalascript.gov.*
import scalascript.gov.providers.SocialProvider
import scalascript.gov.social.*
import scalascript.payments.money.{Currency, Money}
import java.time.{Instant, LocalDate, YearMonth}
import scala.collection.mutable
import scala.concurrent.{Future, ExecutionContext}

/** Configurable in-memory SocialProvider for testing. */
class MockSocialProvider(var succeed: Boolean = true) extends SocialProvider:

  private val _declarations  = mutable.ListBuffer[ContributionDeclaration]()
  private val _registrations = mutable.ListBuffer[EmployeeRecord]()
  private val _deregistrations = mutable.ListBuffer[(EmployeeRecord, DeregistrationReason)]()
  private val _updates       = mutable.ListBuffer[EmployeeRecord]()

  def recordedDeclarations:    List[ContributionDeclaration]                 = _declarations.toList
  def recordedRegistrations:   List[EmployeeRecord]                          = _registrations.toList
  def recordedDeregistrations: List[(EmployeeRecord, DeregistrationReason)]  = _deregistrations.toList
  def recordedUpdates:         List[EmployeeRecord]                          = _updates.toList

  def reset(): Unit =
    _declarations.clear()
    _registrations.clear()
    _deregistrations.clear()
    _updates.clear()

  def submitDeclaration(decl: ContributionDeclaration)(using ExecutionContext): Future[SubmissionResult] =
    _declarations += decl
    if succeed then Future.successful(mockPending("DRA"))
    else Future.failed(BureauError.ApiError("mock social failure"))

  def pollDeclarationStatus(ticketId: String)(using ExecutionContext): Future[SubmissionResult] =
    if succeed then Future.successful(mockAccepted(ticketId))
    else Future.failed(BureauError.ApiError("mock poll failure"))

  def getPaymentReference(entity: BusinessEntity, period: YearMonth)(using ExecutionContext): Future[PaymentReference] =
    if succeed then Future.successful(PaymentReference(
      accountNumber = "00000000000000000000000000",
      amount        = Money(0L, Currency("PLN")),
      dueDate       = period.atDay(1).plusMonths(1).withDayOfMonth(15),
      period        = period,
      description   = s"Mock ZUS reference for ${period}",
    ))
    else Future.failed(BureauError.ApiError("mock reference failure"))

  def calculateContributions(params: ContributionParams)(using ExecutionContext): Future[ContributionCalculation] =
    val cur = params.baseAmount.currency
    val zero = Money(0L, cur)
    Future.successful(ContributionCalculation(
      period     = params.period,
      pension    = zero,
      disability = zero,
      sickness   = zero,
      accident   = zero,
      health     = zero,
      laborFund  = zero,
      fgsp       = zero,
      total      = zero,
    ))

  def registerEmployee(employee: EmployeeRecord)(using ExecutionContext): Future[SubmissionResult] =
    _registrations += employee
    if succeed then Future.successful(mockPending("ZUA"))
    else Future.failed(BureauError.ApiError("mock register failure"))

  def deregisterEmployee(employee: EmployeeRecord, reason: DeregistrationReason, effectiveDate: LocalDate)
      (using ExecutionContext): Future[SubmissionResult] =
    _deregistrations += ((employee, reason))
    if succeed then Future.successful(mockPending("ZWUA"))
    else Future.failed(BureauError.ApiError("mock deregister failure"))

  def updateEmployee(employee: EmployeeRecord)(using ExecutionContext): Future[SubmissionResult] =
    _updates += employee
    if succeed then Future.successful(mockPending("ZIUA"))
    else Future.failed(BureauError.ApiError("mock update failure"))

  private def mockPending(prefix: String): SubmissionResult =
    val id = s"$prefix-MOCK-${System.nanoTime()}"
    SubmissionResult(submissionId = id, status = SubmissionStatus.Pending(id), timestamp = Instant.now(), reference = Some(id))

  private def mockAccepted(id: String): SubmissionResult =
    SubmissionResult(submissionId = id, status = SubmissionStatus.Accepted, timestamp = Instant.now(), reference = Some(id))
