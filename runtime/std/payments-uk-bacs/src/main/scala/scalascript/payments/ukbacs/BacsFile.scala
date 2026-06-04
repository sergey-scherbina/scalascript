package scalascript.payments.ukbacs

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** BACS Standard-18 fixed-width file builder.
 *
 *  Produces a Standard-18 BACS submission file for Direct Debit (and/or Credit)
 *  entries.  All records are exactly 110 characters wide — this is the defining
 *  constraint of the BACS Standard-18 format.
 *
 *  Record type codes (first character of each record):
 *    0 — Volume Header (file header)
 *    1 — Debit instruction (transaction code 01 = Direct Debit, 17 = SUN-managed DD)
 *    5 — Credit instruction (transaction code 99 = credit)
 *    9 — Trailer (file trailer with totals)
 *
 *  The BACS 3-day processing cycle:
 *    Day 1 — input day: file submitted to aggregator / BACSTEL-IP gateway
 *    Day 2 — processing day: Vocalink processes and routes entries
 *    Day 3 — entry day / settlement: funds transferred between accounts
 *
 *  Field encoding:
 *    - Numeric fields are right-justified, zero-padded.
 *    - Alphanumeric fields are left-justified, space-padded.
 *    - Amounts are in pence (integer, no decimal point).
 *    - Dates in DDMMYY format (processing date for the entry).
 *
 *  See docs/specs/international-bank-rails.md §v1.55.4 for spec.
 */
object BacsFile:

  val RECORD_LENGTH = 110

  // BACS transaction codes
  object TransactionCode:
    val DirectDebit    = "01"  // Standard Direct Debit
    val SunManaged     = "17"  // SUN-managed Direct Debit
    val DirectCredit   = "99"  // Direct Credit (BACS Credit — out of scope for v1.55.4)

  private val DDMMYY = DateTimeFormatter.ofPattern("ddMMyy")

  /** Debit instruction record — one per Direct Debit collection. */
  case class DebitRecord(
    sortCode:        String,   // 6-digit sort code (no dashes)
    accountNumber:   String,   // 8-digit account number
    transactionCode: String,   // "01" for standard DD, "17" for SUN-managed
    amount:          Long,     // amount in pence
    originatorSortCode:   String,  // originator (SUN holder) sort code — 6 digits
    originatorAccNumber:  String,  // originator account number — 8 digits
    accountName:     String,   // payer account name — max 18 chars
    ref:             String,   // payment reference — max 18 chars
  )

  /** Credit instruction record — one per outbound payment (not used for DD). */
  case class CreditRecord(
    sortCode:        String,
    accountNumber:   String,
    amount:          Long,
    originatorSortCode:   String,
    originatorAccNumber:  String,
    accountName:     String,
    ref:             String,
  )

  /** Build a complete BACS Standard-18 file with a single batch of records.
   *
   *  @param config          BACS config (SUN, originator details)
   *  @param debits          debit instruction records (Direct Debits)
   *  @param credits         credit instruction records (Direct Credits — normally empty for DD)
   *  @param processingDate  the BACS entry date (Day 3 settlement date); defaults to today + 3 business days
   */
  def build(
      config:         BacsConfig,
      debits:         List[DebitRecord],
      credits:        List[CreditRecord] = Nil,
      processingDate: Option[LocalDate]  = None,
  ): String =
    val entryDate   = processingDate.getOrElse(LocalDate.now().plusDays(3))
    val dateStr     = entryDate.format(DDMMYY)

    val volumeHdr   = volumeHeader(config)
    val debitLines  = debits.map(d  => debitRecord(d, dateStr))
    val creditLines = credits.map(c => creditRecord(c, dateStr))
    val trailer     = trailerRecord(config, debits, credits)

    val lines = List(volumeHdr) ++ debitLines ++ creditLines ++ List(trailer)
    lines.mkString("\n")

  // ── Record Type 0 — Volume Header ───────────────────────────────────────

  /** Volume Header (record type 0) — 110 chars.
   *
   *  Layout (1-based positions):
   *    [1]      Record type = "0"
   *    [2-7]    SUN (Service User Number) — 6 chars, left-padded with zeros
   *    [8-13]   File creation date — DDMMYY
   *    [14-25]  Free-format label / file name — 12 chars, space-padded
   *    [26-110] Space-padded filler
   */
  def volumeHeader(config: BacsConfig): String =
    val b = StringBuilder()
    b ++= "0"                                       // [1]    Record type
    b ++= leftPad(config.serviceUserNumber, 6)      // [2-7]  SUN
    b ++= LocalDate.now().format(DDMMYY)            // [8-13] File creation date
    b ++= rightPad(config.fileLabel.take(12), 12)   // [14-25] File label
    b ++= " " * (RECORD_LENGTH - b.length)          // [26-110] Filler
    b.toString().take(RECORD_LENGTH)

  // ── Record Type 1 — Debit Instruction ───────────────────────────────────

  /** Debit instruction record (record type 1) — 110 chars.
   *
   *  Layout (1-based positions):
   *    [1]      Record type = "1"
   *    [2-7]    Payer sort code — 6 digits
   *    [8-15]   Payer account number — 8 digits
   *    [16-17]  Transaction code — "01" or "17"
   *    [18-27]  Amount in pence — 10 digits, zero-padded
   *    [28-33]  Originator sort code — 6 digits
   *    [34-41]  Originator account number — 8 digits
   *    [42-49]  Processing date — DDMMYY (6 chars) + 2 spare chars
   *    [50-67]  Payer account name — 18 chars, space-padded
   *    [68-85]  Payment reference — 18 chars, space-padded
   *    [86-110] Reserved / filler — 25 chars
   */
  def debitRecord(d: DebitRecord, dateStr: String): String =
    val b = StringBuilder()
    b ++= "1"                                                  // [1]    Record type
    b ++= leftPad(d.sortCode.filter(_.isDigit).take(6), 6)    // [2-7]  Payer sort code
    b ++= leftPad(d.accountNumber.filter(_.isDigit).take(8), 8) // [8-15] Payer account number
    b ++= rightPad(d.transactionCode.take(2), 2)               // [16-17] Transaction code
    b ++= leftPad(d.amount.toString, 10)                       // [18-27] Amount in pence
    b ++= leftPad(d.originatorSortCode.filter(_.isDigit).take(6), 6) // [28-33] Orig sort code
    b ++= leftPad(d.originatorAccNumber.filter(_.isDigit).take(8), 8) // [34-41] Orig acc num
    b ++= dateStr                                              // [42-47] Processing date DDMMYY
    b ++= "  "                                                 // [48-49] Spare
    b ++= rightPad(d.accountName.take(18), 18)                 // [50-67] Payer account name
    b ++= rightPad(d.ref.take(18), 18)                         // [68-85] Payment reference
    b ++= " " * (RECORD_LENGTH - b.length)                     // [86-110] Filler
    b.toString().take(RECORD_LENGTH)

  // ── Record Type 5 — Credit Instruction ──────────────────────────────────

  /** Credit instruction record (record type 5) — 110 chars.
   *
   *  Layout mirrors the debit record with record type "5" and transaction code "99".
   */
  def creditRecord(c: CreditRecord, dateStr: String): String =
    val b = StringBuilder()
    b ++= "5"                                                   // [1]    Record type
    b ++= leftPad(c.sortCode.filter(_.isDigit).take(6), 6)     // [2-7]  Payee sort code
    b ++= leftPad(c.accountNumber.filter(_.isDigit).take(8), 8)// [8-15] Payee account number
    b ++= "99"                                                  // [16-17] Transaction code (credit)
    b ++= leftPad(c.amount.toString, 10)                        // [18-27] Amount in pence
    b ++= leftPad(c.originatorSortCode.filter(_.isDigit).take(6), 6) // [28-33] Orig sort code
    b ++= leftPad(c.originatorAccNumber.filter(_.isDigit).take(8), 8) // [34-41] Orig acc num
    b ++= dateStr                                               // [42-47] Processing date DDMMYY
    b ++= "  "                                                  // [48-49] Spare
    b ++= rightPad(c.accountName.take(18), 18)                  // [50-67] Payee account name
    b ++= rightPad(c.ref.take(18), 18)                          // [68-85] Payment reference
    b ++= " " * (RECORD_LENGTH - b.length)                      // [86-110] Filler
    b.toString().take(RECORD_LENGTH)

  // ── Record Type 9 — Trailer ─────────────────────────────────────────────

  /** Trailer record (record type 9) — 110 chars.
   *
   *  Layout:
   *    [1]      Record type = "9"
   *    [2-7]    SUN — 6 chars
   *    [8-17]   Total debit value in pence — 10 digits, zero-padded
   *    [18-27]  Total credit value in pence — 10 digits, zero-padded
   *    [28-33]  Number of debit records — 6 digits, zero-padded
   *    [34-39]  Number of credit records — 6 digits, zero-padded
   *    [40-110] Filler
   */
  def trailerRecord(config: BacsConfig, debits: List[DebitRecord], credits: List[CreditRecord]): String =
    val totalDebit  = debits.map(_.amount).sum
    val totalCredit = credits.map(_.amount).sum
    val debitCount  = debits.size
    val creditCount = credits.size
    val b = StringBuilder()
    b ++= "9"                                             // [1]    Record type
    b ++= leftPad(config.serviceUserNumber, 6)            // [2-7]  SUN
    b ++= leftPad(totalDebit.toString, 10)                // [8-17] Total debit pence
    b ++= leftPad(totalCredit.toString, 10)               // [18-27] Total credit pence
    b ++= leftPad(debitCount.toString, 6)                 // [28-33] Debit record count
    b ++= leftPad(creditCount.toString, 6)                // [34-39] Credit record count
    b ++= " " * (RECORD_LENGTH - b.length)                // [40-110] Filler
    b.toString().take(RECORD_LENGTH)

  // ── Helpers ──────────────────────────────────────────────────────────────

  private[ukbacs] def rightPad(s: String, n: Int): String =
    val t = s.take(n)
    t + " " * (n - t.length)

  private[ukbacs] def leftPad(s: String, n: Int): String =
    val t = s.take(n)
    "0" * (n - t.length) + t
