package scalascript.payments.ach

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Nacha ACH flat-file builder.
 *
 *  Produces standard 94-character fixed-width records per Nacha Operating Rules.
 *  Record type codes: 1=File Header, 5=Batch Header, 6=Entry Detail, 7=Addenda,
 *  8=Batch Control, 9=File Control.
 *
 *  File is padded with "9" records to a multiple of 10 lines (blocking factor 10).
 *
 *  See docs/specs/bank-rails.md §v1.54.2 for spec.
 */
object NachaFile:

  private val RECORD_LENGTH = 94
  private val YYYYMMDD      = DateTimeFormatter.ofPattern("yyMMdd")

  /** Transaction codes for Entry Detail records. */
  object TransactionCode:
    val CheckingCredit = "22"  // ACH Credit to checking account
    val CheckingDebit  = "27"  // ACH Debit from checking account
    val SavingsCredit  = "32"  // ACH Credit to savings account
    val SavingsDebit   = "37"  // ACH Debit from savings account

  /** Service class codes for Batch Header / Batch Control. */
  object ServiceClass:
    val Mixed   = "200"  // both credits and debits
    val Credits = "220"  // credits only
    val Debits  = "225"  // debits only

  /** Build a complete Nacha ACH file for one batch of entries.
   *
   *  @param config         ACH originator configuration
   *  @param entries        one or more entry detail records to include in the batch
   *  @param serviceClass   "200" (mixed), "220" (credits only), "225" (debits only)
   *  @param sameDay        if true, sets effective entry date = today + "SAMEDAY" in description
   *  @param effectiveDate  override effective entry date (default: tomorrow for standard, today for same-day)
   */
  def build(
      config:       AchConfig,
      entries:      List[EntryDetail],
      serviceClass: String = ServiceClass.Mixed,
      sameDay:      Boolean = false,
      effectiveDate: Option[LocalDate] = None,
  ): String =
    val today    = LocalDate.now()
    val effDate  = effectiveDate.getOrElse(if sameDay then today else today.plusDays(1))
    val fileDate = today.format(YYYYMMDD)
    val fileTime = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HHmm"))

    val companyDesc = if sameDay then "SAMEDAY   " else "PAYMENT   "

    val header        = fileHeader(config, fileDate, fileTime)
    val batchHeader   = batchHeaderRecord(config, serviceClass, companyDesc, effDate)
    val entryRecords  = entries.map(e => entryDetailRecord(e))
    val batchControl  = batchControlRecord(config, serviceClass, entries)
    val fileCtrl      = fileControlRecord(entries)

    val lines = List(header, batchHeader) ++ entryRecords ++ List(batchControl, fileCtrl)

    // Pad to multiple of 10 with "9" blocking records
    val paddingNeeded = (10 - (lines.size % 10)) % 10
    val padding       = List.fill(paddingNeeded)("9" * RECORD_LENGTH)

    (lines ++ padding).mkString("\n")

  // ── File Header (Record Type 1) ─────────────────────────────────────────

  def fileHeader(config: AchConfig, fileDate: String, fileTime: String): String =
    val b = StringBuilder()
    b ++= "1"                                    // [1]    Record Type Code
    b ++= "01"                                   // [2-3]  Priority Code
    b ++= " " + leftPad(config.achRoutingNumber, 9) // [4-13] Immediate Destination (space + 9-digit routing)
    b ++= leftPad(config.achCompanyId, 10)       // [14-23] Immediate Origin (company ID, 10 chars)
    b ++= leftPad(fileDate, 6)                   // [24-29] File Creation Date YYMMDD
    b ++= leftPad(fileTime, 4)                   // [30-33] File Creation Time HHMM
    b ++= "A"                                    // [34]   File ID Modifier
    b ++= "094"                                  // [35-37] Record Size
    b ++= "10"                                   // [38-39] Blocking Factor
    b ++= "1"                                    // [40]   Format Code
    b ++= rightPad(config.achCompanyName, 23)    // [41-63] Immediate Destination Name
    b ++= rightPad(config.achCompanyName, 23)    // [64-86] Immediate Origin Name
    b ++= "        "                             // [87-94] Reference Code (8 spaces)
    b.toString().take(RECORD_LENGTH)

  // ── Batch Header (Record Type 5) ────────────────────────────────────────

  def batchHeaderRecord(
      config:       AchConfig,
      serviceClass: String,
      companyDesc:  String,
      effectiveDate: LocalDate,
  ): String =
    val b = StringBuilder()
    b ++= "5"                                          // [1]    Record Type Code
    b ++= serviceClass                                 // [2-4]  Service Class Code
    b ++= rightPad(config.achCompanyName, 16)          // [5-20] Company Name
    b ++= "          "                                 // [21-30] Company Discretionary Data (10 spaces)
    b ++= rightPad(config.achCompanyId, 10)            // [31-40] Company Identification
    b ++= "PPD"                                        // [41-43] Standard Entry Class Code
    b ++= rightPad(companyDesc.take(10), 10)           // [44-53] Company Entry Description
    b ++= "      "                                     // [54-59] Company Descriptive Date (6 spaces)
    b ++= effectiveDate.format(YYYYMMDD)               // [60-65] Effective Entry Date YYMMDD
    b ++= "   "                                        // [66-68] Settlement Date (3 spaces — filled by bank)
    b ++= "1"                                          // [69]   Originator Status Code
    b ++= leftPad(config.achRoutingNumber.take(8), 8)  // [70-77] ODFI Routing Number (first 8 digits)
    b ++= "0000001"                                    // [78-84] Batch Number
    b.toString().take(RECORD_LENGTH)

  // ── Entry Detail (Record Type 6) ────────────────────────────────────────

  def entryDetailRecord(e: EntryDetail): String =
    val b = StringBuilder()
    b ++= "6"                                         // [1]    Record Type Code
    b ++= e.transactionCode                           // [2-3]  Transaction Code
    b ++= leftPad(e.rdfiRouting.take(8), 8)           // [4-11] Receiving DFI Identification (8 digits)
    b ++= e.rdfiRouting.drop(8).take(1)               // [12]   Check Digit (9th digit of routing)
    b ++= rightPad(e.accountNumber.take(17), 17)      // [13-29] DFI Account Number
    b ++= leftPad(e.amountCents.toString, 10)         // [30-39] Amount (in cents, no decimal point)
    b ++= rightPad(e.individualId.take(15), 15)       // [40-54] Individual Identification Number
    b ++= rightPad(e.individualName.take(22), 22)     // [55-76] Individual Name
    b ++= "  "                                        // [77-78] Discretionary Data
    b ++= (if e.addenda then "1" else "0")            // [79]   Addenda Record Indicator
    b ++= leftPad(e.traceNumber.take(15), 15)         // [80-94] Trace Number
    b.toString().take(RECORD_LENGTH)

  // ── Addenda (Record Type 7) ─────────────────────────────────────────────

  def addendaRecord(paymentInfo: String, seqNum: Int, entrySeq: Int): String =
    val b = StringBuilder()
    b ++= "7"                                            // [1]   Record Type Code
    b ++= "05"                                           // [2-3] Addenda Type Code (05 = PPD addenda)
    b ++= rightPad(paymentInfo.take(80), 80)             // [4-83] Payment Related Information
    b ++= leftPad(seqNum.toString, 4)                    // [84-87] Sequence Number
    b ++= leftPad(entrySeq.toString, 7)                  // [88-94] Entry Detail Sequence Number
    b.toString().take(RECORD_LENGTH)

  // ── Batch Control (Record Type 8) ───────────────────────────────────────

  def batchControlRecord(
      config:       AchConfig,
      serviceClass: String,
      entries:      List[EntryDetail],
  ): String =
    val entryCount  = entries.size
    val entryHash   = entryHashSum(entries)
    val totalDebits = entries.filter(e => e.transactionCode == TransactionCode.CheckingDebit || e.transactionCode == TransactionCode.SavingsDebit)
                             .map(_.amountCents).sum
    val totalCredits = entries.filter(e => e.transactionCode == TransactionCode.CheckingCredit || e.transactionCode == TransactionCode.SavingsCredit)
                              .map(_.amountCents).sum
    val b = StringBuilder()
    b ++= "8"                                          // [1]    Record Type Code
    b ++= serviceClass                                 // [2-4]  Service Class Code
    b ++= leftPad(entryCount.toString, 6)              // [5-10] Entry/Addenda Count
    b ++= leftPad(entryHash, 10)                       // [11-20] Entry Hash (last 10 digits of routing sum)
    b ++= leftPad(totalDebits.toString, 12)            // [21-32] Total Debit Entry Dollar Amount
    b ++= leftPad(totalCredits.toString, 12)           // [33-44] Total Credit Entry Dollar Amount
    b ++= rightPad(config.achCompanyId, 10)            // [45-54] Company Identification
    b ++= " " * 19                                     // [55-73] Message Authentication Code
    b ++= "   "                                        // [74-76] Reserved
    b ++= leftPad(config.achRoutingNumber.take(8), 8)  // [77-84] ODFI Routing Number
    b ++= "0000001"                                    // [85-94] Batch Number (matching batch header)
    b.toString().take(RECORD_LENGTH)

  // ── File Control (Record Type 9) ────────────────────────────────────────

  def fileControlRecord(
      entries:     List[EntryDetail],
  ): String =
    // Total records: 1 (file header) + 1 (batch header) + entries + 1 (batch ctrl) + 1 (file ctrl)
    val totalRecords    = 2 + entries.size + 2  // before padding
    val blockCount      = math.ceil(totalRecords.toDouble / 10).toInt
    val batchCount      = 1
    val entryAddenda    = entries.size
    val entryHash       = entryHashSum(entries)
    val totalDebits     = entries.filter(e => e.transactionCode == TransactionCode.CheckingDebit || e.transactionCode == TransactionCode.SavingsDebit)
                                 .map(_.amountCents).sum
    val totalCredits    = entries.filter(e => e.transactionCode == TransactionCode.CheckingCredit || e.transactionCode == TransactionCode.SavingsCredit)
                                 .map(_.amountCents).sum
    val b = StringBuilder()
    b ++= "9"                                       // [1]    Record Type Code
    b ++= leftPad(batchCount.toString, 6)           // [2-7]  Batch Count
    b ++= leftPad(blockCount.toString, 6)           // [8-13] Block Count
    b ++= leftPad(entryAddenda.toString, 8)         // [14-21] Entry/Addenda Count
    b ++= leftPad(entryHash, 10)                    // [22-31] Entry Hash (last 10 digits of routing sum)
    b ++= leftPad(totalDebits.toString, 12)         // [32-43] Total Debit Entry Dollar Amount
    b ++= leftPad(totalCredits.toString, 12)        // [44-55] Total Credit Entry Dollar Amount
    b ++= " " * 39                                  // [56-94] Reserved
    b.toString().take(RECORD_LENGTH)

  // ── Helpers ──────────────────────────────────────────────────────────────

  /** Sum the first 8 digits of each entry's routing number; return last 10 digits. */
  def entryHashSum(entries: List[EntryDetail]): String =
    val sum = entries.map { e =>
      scala.util.Try(e.rdfiRouting.take(8).toLong).getOrElse(0L)
    }.sum
    // Last 10 digits of the sum
    val s = sum.toString
    val padded = if s.length >= 10 then s.takeRight(10) else s.reverse.padTo(10, '0').reverse
    padded

  private def rightPad(s: String, n: Int): String =
    val trimmed = s.take(n)
    trimmed + " " * (n - trimmed.length)

  private def leftPad(s: String, n: Int): String =
    val trimmed = s.take(n)
    "0" * (n - trimmed.length) + trimmed

/** Entry detail record parameters. */
case class EntryDetail(
  transactionCode: String,   // e.g. "22" = checking credit
  rdfiRouting:     String,   // 9-digit routing number of receiving bank
  accountNumber:   String,   // receiving account number
  amountCents:     Long,     // amount in cents (no decimal point in Nacha file)
  individualId:    String,   // individual identification number (idempotency key)
  individualName:  String,   // individual / company name
  traceNumber:     String,   // 15-digit trace number (ODFI routing (8) + seq (7))
  addenda:         Boolean = false,  // whether an addenda record follows
)
