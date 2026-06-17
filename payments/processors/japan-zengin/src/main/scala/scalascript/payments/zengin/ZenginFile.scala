package scalascript.payments.zengin

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Zengin 21 flat-file builder.
 *
 *  Produces fixed-width 120-byte records per the Zengin Data Telecommunication
 *  System (全銀データ通信システム) format 21 specification published by the JBA
 *  (Japanese Bankers Association / 全国銀行協会).
 *
 *  Record type codes:
 *  - Type 1 (header):   file type "21", sender bank/branch/account, creation date
 *  - Type 2 (data):     receiving bank code, branch code, account type, account number,
 *                       account holder name (half-width kana), transfer amount
 *  - Type 8 (trailer):  total data record count, total transfer amount
 *  - Type 9 (end):      padding zeros
 *
 *  All records are exactly 120 characters wide.
 *
 *  See specs/international-bank-rails.md §v1.55.7 for spec.
 */
object ZenginFile:

  val RECORD_LENGTH = 120

  /** Account type codes used in type-2 data records. */
  object AccountType:
    val Ordinary = "1"  // 普通預金 (futsu yokin) — ordinary savings
    val Current  = "2"  // 当座預金 (toza yokin)  — current/checking account
    val Savings  = "4"  // 貯蓄預金 (chochiku yokin) — savings deposit

  /** Build a complete Zengin 21 file for a list of transfer data records.
   *
   *  @param config   Zengin sender configuration
   *  @param records  one or more transfer data records (type-2)
   *  @param date     file creation date (default: today)
   */
  def build(
      config:  ZenginConfig,
      records: List[ZenginDataRecord],
      date:    LocalDate = LocalDate.now(),
  ): String =
    val header  = headerRecord(config, date, records.size)
    val data    = records.map(dataRecord)
    val trailer = trailerRecord(records)
    val end     = endRecord()
    (List(header) ++ data ++ List(trailer, end)).mkString("\n")

  // ── Type-1 Header Record ──────────────────────────────────────────────────

  def headerRecord(config: ZenginConfig, date: LocalDate, totalDataRecords: Int): String =
    val b = StringBuilder()
    b ++= "1"                                         // [1]     Record type code
    b ++= "21"                                        // [2-3]   File type (21 = domestic transfer)
    b ++= "0"                                         // [4]     Code classification (0 = new)
    b ++= leftPad(config.senderBankCode, 4)           // [5-8]   Sender bank code (金融機関コード)
    b ++= rightPad(config.senderBankName, 15)         // [9-23]  Sender bank name (kana, 15 chars)
    b ++= leftPad(config.senderBranchCode, 3)         // [24-26] Sender branch code (支店コード)
    b ++= rightPad(config.senderBranchName, 15)       // [27-41] Sender branch name (kana, 15 chars)
    b ++= "  "                                        // [42-43] Reserved (2 spaces)
    b ++= "1"                                         // [44]    Account type (1 = ordinary)
    b ++= leftPad(config.senderAccountNumber, 7)      // [45-51] Sender account number
    b ++= rightPad(config.senderName, 40)             // [52-91] Sender name (kana, 40 chars)
    val dateStr = date.format(DateTimeFormatter.ofPattern("MMdd"))
    b ++= dateStr                                     // [92-95] Transfer date (MMDD)
    b ++= leftPad(totalDataRecords.toString, 6)       // [96-101] Total number of data records
    b ++= leftPad("0", 12)                            // [102-113] Total transfer amount (filled in trailer; 0 here)
    b ++= " " * 7                                     // [114-120] Filler (7 spaces)
    b.toString().take(RECORD_LENGTH).padTo(RECORD_LENGTH, ' ')

  // ── Type-2 Data Record ────────────────────────────────────────────────────

  def dataRecord(r: ZenginDataRecord): String =
    val b = StringBuilder()
    b ++= "2"                                         // [1]     Record type code
    b ++= leftPad(r.bankCode, 4)                      // [2-5]   Receiving bank code (金融機関コード)
    b ++= rightPad(r.bankName, 15)                    // [6-20]  Receiving bank name (kana, 15 chars)
    b ++= leftPad(r.branchCode, 3)                    // [21-23] Receiving branch code (支店コード)
    b ++= rightPad(r.branchName, 15)                  // [24-38] Receiving branch name (kana, 15 chars)
    b ++= "  "                                        // [39-40] Reserved (2 spaces)
    b ++= r.accountType                               // [41]    Account type (1/2/4)
    b ++= leftPad(r.accountNumber, 7)                 // [42-48] Receiving account number
    b ++= rightPad(r.accountName, 30)                 // [49-78] Receiving account holder name (kana, 30 chars)
    b ++= leftPad(r.amountYen.toString, 10)           // [79-88] Transfer amount (integer yen, no decimals)
    b ++= "0"                                         // [89]    New code (0 = new account)
    b ++= rightPad(r.customerId, 20)                  // [90-109] Customer transfer ID / idempotency key
    b ++= " " * 11                                    // [110-120] Filler (11 spaces)
    b.toString().take(RECORD_LENGTH).padTo(RECORD_LENGTH, ' ')

  // ── Type-8 Trailer Record ─────────────────────────────────────────────────

  def trailerRecord(records: List[ZenginDataRecord]): String =
    val totalCount  = records.size
    val totalAmount = records.map(_.amountYen).sum
    val b = StringBuilder()
    b ++= "8"                                         // [1]     Record type code
    b ++= leftPad(totalCount.toString, 6)             // [2-7]   Total number of data records
    b ++= leftPad(totalAmount.toString, 12)           // [8-19]  Total transfer amount (integer yen)
    b ++= " " * 101                                   // [20-120] Filler (101 spaces)
    b.toString().take(RECORD_LENGTH).padTo(RECORD_LENGTH, ' ')

  // ── Type-9 End Record ─────────────────────────────────────────────────────

  def endRecord(): String =
    "9" + "0" * 119  // [1] type code "9", [2-120] zeros

  // ── Helpers ───────────────────────────────────────────────────────────────

  private def rightPad(s: String, n: Int): String =
    val trimmed = s.take(n)
    trimmed + " " * (n - trimmed.length)

  private def leftPad(s: String, n: Int): String =
    val trimmed = s.take(n)
    "0" * (n - trimmed.length) + trimmed


/** A single Zengin 21 type-2 data record (transfer instruction). */
case class ZenginDataRecord(
  bankCode:      String,   // 4-digit receiving bank code (金融機関コード)
  bankName:      String,   // receiving bank name (kana, up to 15 chars)
  branchCode:    String,   // 3-digit receiving branch code (支店コード)
  branchName:    String,   // receiving branch name (kana, up to 15 chars)
  accountType:   String,   // "1" = ordinary, "2" = current, "4" = savings
  accountNumber: String,   // 7-digit receiving account number
  accountName:   String,   // account holder name in half-width kana (max 30 chars)
  amountYen:     Long,     // transfer amount in integer yen (no decimal)
  customerId:    String,   // idempotency key / customer transfer ID (max 20 chars)
)
