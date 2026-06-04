package scalascript.payments.ukbacs

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** AUDDIS (Automated Direct Debit Instruction Service) file builder.
 *
 *  AUDDIS enables Service Users to notify the payer's bank (PSB) of new,
 *  cancelled, or amended Direct Debit mandates electronically, replacing the
 *  paper-based DDI (Direct Debit Instruction) process.
 *
 *  The AUDDIS file uses the same Standard-18 110-character fixed-width format
 *  as the BACS payment file.  Transaction codes differ:
 *
 *    0N — New instruction (new mandate)
 *    0C — Cancellation of instruction
 *    0S — Service User's own instruction cancellation
 *
 *  Processing cycle:
 *    Day 1 — AUDDIS file submitted
 *    Day 2 — Vocalink routes to payer's bank (PSB)
 *    Day 3 — PSB processes and accepts or returns the instruction
 *
 *  On acceptance the adapter raises `BankRailsEvent.BacsAuddisAccepted`.
 *  If the PSB rejects the instruction (e.g. account closed, wrong details),
 *  the rejection arrives via ADDACS and maps to `BacsAruddReturned`.
 *
 *  See docs/specs/international-bank-rails.md §v1.55.4 for spec.
 */
object AuddisFile:

  val RECORD_LENGTH = 110

  // AUDDIS instruction transaction codes
  object InstructionCode:
    val New          = "0N"  // new Direct Debit mandate
    val Cancel       = "0C"  // cancellation by payer
    val OwnCancel    = "0S"  // cancellation by Service User

  private val DDMMYY = DateTimeFormatter.ofPattern("ddMMyy")

  /** One AUDDIS instruction record (one per mandate operation). */
  case class AuddisInstruction(
    sortCode:        String,   // payer sort code — 6 digits
    accountNumber:   String,   // payer account number — 8 digits
    instructionCode: String,   // "0N" / "0C" / "0S"
    originatorSortCode:  String,  // SUN holder (creditor) sort code — 6 digits
    originatorAccNumber: String,  // SUN holder (creditor) account number — 8 digits
    accountName:     String,   // payer account name — max 18 chars
    ref:             String,   // mandate reference — max 18 chars
  )

  /** Build a complete AUDDIS instruction file.
   *
   *  @param config         BACS config (SUN, originator details)
   *  @param instructions   list of mandate instructions (new, cancel, etc.)
   *  @param processingDate Day 3 date for this AUDDIS cycle (defaults to today + 3 business days)
   */
  def build(
      config:         BacsConfig,
      instructions:   List[AuddisInstruction],
      processingDate: Option[LocalDate] = None,
  ): String =
    val entryDate = processingDate.getOrElse(LocalDate.now().plusDays(3))
    val dateStr   = entryDate.format(DDMMYY)

    val volumeHdr    = volumeHeader(config)
    val instrLines   = instructions.map(i => instructionRecord(i, dateStr))
    val trailer      = trailerRecord(config, instructions)

    val lines = List(volumeHdr) ++ instrLines ++ List(trailer)
    lines.mkString("\n")

  // ── Record Type 0 — Volume Header ───────────────────────────────────────

  /** Volume Header for AUDDIS file — identical layout to BacsFile.volumeHeader
   *  but with a different file label indicating AUDDIS content.
   *
   *  [1]      Record type = "0"
   *  [2-7]    SUN — 6 chars, zero-padded
   *  [8-13]   File creation date — DDMMYY
   *  [14-25]  File label — 12 chars, space-padded
   *  [26-110] Filler
   */
  def volumeHeader(config: BacsConfig): String =
    val b = StringBuilder()
    b ++= "0"
    b ++= leftPad(config.serviceUserNumber, 6)
    b ++= LocalDate.now().format(DDMMYY)
    b ++= rightPad("AUDDIS      ", 12)     // AUDDIS-specific label
    b ++= " " * (RECORD_LENGTH - b.length)
    b.toString().take(RECORD_LENGTH)

  // ── AUDDIS Instruction Record ────────────────────────────────────────────

  /** AUDDIS instruction record — 110 chars.
   *
   *  Uses the same Standard-18 positional layout as the BACS debit record,
   *  but with AUDDIS-specific instruction codes in the transaction-code field.
   *
   *  Layout:
   *    [1]      Record type = "1" (AUDDIS instructions are carried as type-1 records)
   *    [2-7]    Payer sort code — 6 digits
   *    [8-15]   Payer account number — 8 digits
   *    [16-17]  AUDDIS instruction code — "0N" / "0C" / "0S"
   *    [18-27]  Zero (no monetary amount in AUDDIS instructions) — 10 zeros
   *    [28-33]  Originator sort code — 6 digits
   *    [34-41]  Originator account number — 8 digits
   *    [42-47]  Processing date DDMMYY
   *    [48-49]  Spare
   *    [50-67]  Payer account name — 18 chars
   *    [68-85]  Mandate reference — 18 chars
   *    [86-110] Filler
   */
  def instructionRecord(i: AuddisInstruction, dateStr: String): String =
    val b = StringBuilder()
    b ++= "1"
    b ++= leftPad(i.sortCode.filter(_.isDigit).take(6), 6)
    b ++= leftPad(i.accountNumber.filter(_.isDigit).take(8), 8)
    b ++= rightPad(i.instructionCode.take(2), 2)
    b ++= "0000000000"                                      // amount = 0 for AUDDIS
    b ++= leftPad(i.originatorSortCode.filter(_.isDigit).take(6), 6)
    b ++= leftPad(i.originatorAccNumber.filter(_.isDigit).take(8), 8)
    b ++= dateStr
    b ++= "  "                                              // spare
    b ++= rightPad(i.accountName.take(18), 18)
    b ++= rightPad(i.ref.take(18), 18)
    b ++= " " * (RECORD_LENGTH - b.length)
    b.toString().take(RECORD_LENGTH)

  // ── Record Type 9 — Trailer ─────────────────────────────────────────────

  /** AUDDIS Trailer record — 110 chars.
   *
   *  [1]      Record type = "9"
   *  [2-7]    SUN
   *  [8-17]   Total debits (0 for AUDDIS — no monetary values)
   *  [18-27]  Total credits (0 for AUDDIS)
   *  [28-33]  Number of new instructions ("0N" records)
   *  [34-39]  Number of cancel instructions ("0C" + "0S" records)
   *  [40-110] Filler
   */
  def trailerRecord(config: BacsConfig, instructions: List[AuddisInstruction]): String =
    val newCount    = instructions.count(_.instructionCode == InstructionCode.New)
    val cancelCount = instructions.count(i =>
      i.instructionCode == InstructionCode.Cancel ||
      i.instructionCode == InstructionCode.OwnCancel
    )
    val b = StringBuilder()
    b ++= "9"
    b ++= leftPad(config.serviceUserNumber, 6)
    b ++= leftPad("0", 10)   // total debits = 0
    b ++= leftPad("0", 10)   // total credits = 0
    b ++= leftPad(newCount.toString, 6)
    b ++= leftPad(cancelCount.toString, 6)
    b ++= " " * (RECORD_LENGTH - b.length)
    b.toString().take(RECORD_LENGTH)

  // ── Helpers ──────────────────────────────────────────────────────────────

  private[ukbacs] def rightPad(s: String, n: Int): String =
    val t = s.take(n)
    t + " " * (n - t.length)

  private[ukbacs] def leftPad(s: String, n: Int): String =
    val t = s.take(n)
    "0" * (n - t.length) + t
