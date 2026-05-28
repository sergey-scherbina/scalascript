package scalascript.gov.providers

import scalascript.gov.*
import scalascript.gov.fiscal.*
import scalascript.gov.registry.BusinessRecord
import scala.concurrent.{Future, ExecutionContext}
import java.time.Instant

trait FiscalProvider:

  def submitInvoice(invoice: FiscalInvoice)(using ExecutionContext): Future[InvoiceSubmissionResult]
  def pollInvoiceStatus(ticketId: String)(using ExecutionContext): Future[InvoiceSubmissionResult]
  def fetchInvoice(id: String)(using ExecutionContext): Future[FiscalInvoice]
  def queryInvoices(filter: InvoiceFilter)(using ExecutionContext): Future[List[InvoiceRef]]

  def submitDeclaration(decl: TaxDeclaration)(using ExecutionContext): Future[SubmissionResult]
  def pollDeclarationStatus(ticketId: String)(using ExecutionContext): Future[SubmissionResult]

  def submitAuditFile(file: AuditFile)(using ExecutionContext): Future[SubmissionResult]
  def pollAuditFileStatus(ticketId: String)(using ExecutionContext): Future[SubmissionResult]

  def verifyVatNumber(id: TaxIdentifier)(using ExecutionContext): Future[VatVerificationResult]

case class VatVerificationResult(
  valid:     Boolean,
  entity:    Option[BusinessRecord],
  checkedAt: Instant
)
