package scalascript.gov.fiscal

import scalascript.gov.*
import scalascript.payments.money.{Money, Currency}
import java.time.LocalDate

case class ExchangeRate(
  fromCurrency: Currency,
  toCurrency:   Currency,
  rate:         BigDecimal,
  date:         LocalDate
)

case class FiscalInvoice(
  invoiceNumber: String,
  issueDate:     LocalDate,
  seller:        BusinessEntity,
  buyer:         BusinessEntity,
  lines:         List[InvoiceLine],
  taxSummary:    List[TaxSummaryLine],
  totalNet:      Money,
  totalTax:      Money,
  totalGross:    Money,
  currency:      Currency,
  exchangeRate:  Option[ExchangeRate]  = None,
  paymentDue:    Option[LocalDate]    = None,
  notes:         Option[String]       = None,
  metadata:      Map[String, String]  = Map.empty
)

case class InvoiceLine(
  description: String,
  quantity:    BigDecimal,
  unit:        String,
  unitNet:     Money,
  vatRate:     VatRate,
  totalNet:    Money,
  totalTax:    Money
)

case class TaxSummaryLine(
  vatRate:  VatRate,
  net:      Money,
  tax:      Money,
  gross:    Money
)

enum VatRate:
  case Standard
  case Reduced
  case SuperReduced
  case Zero
  case Exempt
  case ReverseCharge
  case Custom(rate: BigDecimal)

case class InvoiceFilter(
  dateFrom: Option[LocalDate] = None,
  dateTo:   Option[LocalDate] = None,
  role:     InvoiceRole       = InvoiceRole.Any,
  limit:    Int               = 100
)

enum InvoiceRole:
  case Seller; case Buyer; case Any

case class InvoiceRef(id: String, date: LocalDate, total: Money, status: String)

case class InvoiceSubmissionResult(
  submissionResult: SubmissionResult,
  invoiceId:        Option[String]
)
