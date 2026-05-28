package scalascript.gov.mock

import scalascript.gov.*
import scalascript.gov.fiscal.*
import scalascript.gov.providers.{FiscalProvider, VatVerificationResult}
import java.time.Instant
import scala.collection.mutable
import scala.concurrent.{Future, ExecutionContext}

/** Configurable in-memory FiscalProvider for testing.
 *
 *  All methods succeed by default; set `succeed = false` to make every call fail.
 *  Recorded calls are available via `recorded*` accessors. */
class MockFiscalProvider(var succeed: Boolean = true) extends FiscalProvider:

  private val _invoices    = mutable.ListBuffer[FiscalInvoice]()
  private val _declarations= mutable.ListBuffer[TaxDeclaration]()
  private val _auditFiles  = mutable.ListBuffer[AuditFile]()
  private val _vatChecks   = mutable.ListBuffer[TaxIdentifier]()

  def recordedInvoices:     List[FiscalInvoice]    = _invoices.toList
  def recordedDeclarations: List[TaxDeclaration]   = _declarations.toList
  def recordedAuditFiles:   List[AuditFile]        = _auditFiles.toList
  def recordedVatChecks:    List[TaxIdentifier]    = _vatChecks.toList

  def reset(): Unit =
    _invoices.clear()
    _declarations.clear()
    _auditFiles.clear()
    _vatChecks.clear()

  def submitInvoice(invoice: FiscalInvoice)(using ExecutionContext): Future[InvoiceSubmissionResult] =
    _invoices += invoice
    if succeed then Future.successful(InvoiceSubmissionResult(mockSubmissionResult("INV"), Some("MOCK-INV-001")))
    else Future.failed(BureauError.ApiError("mock fiscal failure"))

  def pollInvoiceStatus(ticketId: String)(using ExecutionContext): Future[InvoiceSubmissionResult] =
    if succeed then Future.successful(InvoiceSubmissionResult(mockAcceptedResult(ticketId), Some(ticketId)))
    else Future.failed(BureauError.ApiError("mock poll failure"))

  def fetchInvoice(id: String)(using ExecutionContext): Future[FiscalInvoice] =
    _invoices.find(_.invoiceNumber == id) match
      case Some(inv) => Future.successful(inv)
      case None if succeed =>
        Future.failed(BureauError.ApiError(s"Invoice $id not found", Some("NOT_FOUND"), Some(404)))
      case None => Future.failed(BureauError.ApiError("mock fetch failure"))

  def queryInvoices(filter: InvoiceFilter)(using ExecutionContext): Future[List[InvoiceRef]] =
    if succeed then Future.successful(Nil)
    else Future.failed(BureauError.ApiError("mock query failure"))

  def submitDeclaration(decl: TaxDeclaration)(using ExecutionContext): Future[SubmissionResult] =
    _declarations += decl
    if succeed then Future.successful(mockSubmissionResult("DECL"))
    else Future.failed(BureauError.ApiError("mock declaration failure"))

  def pollDeclarationStatus(ticketId: String)(using ExecutionContext): Future[SubmissionResult] =
    if succeed then Future.successful(mockAcceptedResult(ticketId))
    else Future.failed(BureauError.ApiError("mock status failure"))

  def submitAuditFile(file: AuditFile)(using ExecutionContext): Future[SubmissionResult] =
    _auditFiles += file
    if succeed then Future.successful(mockSubmissionResult("AUDIT"))
    else Future.failed(BureauError.ApiError("mock audit failure"))

  def pollAuditFileStatus(ticketId: String)(using ExecutionContext): Future[SubmissionResult] =
    if succeed then Future.successful(mockAcceptedResult(ticketId))
    else Future.failed(BureauError.ApiError("mock audit status failure"))

  def verifyVatNumber(id: TaxIdentifier)(using ExecutionContext): Future[VatVerificationResult] =
    _vatChecks += id
    if succeed then Future.successful(VatVerificationResult(valid = true, entity = None, checkedAt = Instant.now()))
    else Future.successful(VatVerificationResult(valid = false, entity = None, checkedAt = Instant.now()))

  private def mockSubmissionResult(prefix: String): SubmissionResult =
    val id = s"$prefix-MOCK-${System.nanoTime()}"
    SubmissionResult(submissionId = id, status = SubmissionStatus.Pending(id), timestamp = Instant.now(), reference = Some(id))

  private def mockAcceptedResult(id: String): SubmissionResult =
    SubmissionResult(submissionId = id, status = SubmissionStatus.Accepted, timestamp = Instant.now(), reference = Some(id))
