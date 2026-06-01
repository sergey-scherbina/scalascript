package scalascript.payments.caeft

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest, HttpResponse as JHttpResponse}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.{Duration, LocalDate}
import java.time.format.DateTimeFormatter

/** Canada EFT / Interac e-Transfer configuration.
 *
 *  @param apiKey         API key for the aggregator (e.g. Central 1, Caledon, Peoples Trust)
 *  @param baseUrl        aggregator base URL
 *  @param webhookSecret  HMAC secret for `X-Interac-Signature` webhook verification
 *  @param institutionId  3-digit CPA institution number for the originating financial institution
 */
case class CaEftConfig(
  apiKey:        String,
  baseUrl:       String,
  webhookSecret: String,
  institutionId: String,
)

object CaEftConfig:
  /** Load config from environment variables.
   *  CA_EFT_API_KEY, CA_EFT_BASE_URL, CA_EFT_WEBHOOK_SECRET, CA_EFT_INSTITUTION_ID */
  def fromEnv: CaEftConfig =
    CaEftConfig(
      apiKey        = sys.env.getOrElse("CA_EFT_API_KEY",         ""),
      baseUrl       = sys.env.getOrElse("CA_EFT_BASE_URL",        "https://api.ca-eft-gateway.example.com/v1"),
      webhookSecret = sys.env.getOrElse("CA_EFT_WEBHOOK_SECRET",  ""),
      institutionId = sys.env.getOrElse("CA_EFT_INSTITUTION_ID",  "001"),
    )

/** CPA Standard 005 AFT logical record.
 *
 *  Used for both credit (AFT credit = type 450) and debit (AFT debit = type 470) records.
 *
 *  CPA Standard 005 file format:
 *  - Each logical record is exactly 1,464 bytes (1,464-character fixed-width ASCII).
 *  - A file consists of: 1 header record + N detail records + 1 trailer record.
 *
 *  Key AFT transaction types:
 *    450 — AFT Credit (push money to payee account)
 *    470 — AFT Debit  (pull money from payer account)
 *
 *  See docs/international-bank-rails.md §CA_INTERAC for CPA 005 specification.
 */
case class AftRecord(
  transactionType: Int,       // 450 = credit, 470 = debit
  amount:          Long,      // amount in cents (CAD)
  transitNumber:   String,    // 5-digit transit number (branch code)
  institutionNumber: String,  // 3-digit institution number (bank code)
  accountNumber:   String,    // 7–12-digit account number
  payeeName:       String,    // name of payee / payer (max 30 chars)
  sundryInfo:      String,    // sundry / reference information (max 19 chars)
)

/** File-level header for CPA Standard 005 AFT file. */
case class AftFileHeader(
  originatorId:   String,    // originator's 10-digit CPA ID
  fileCreationDate: LocalDate,
  fileSequenceNumber: Int,   // 4-digit sequence number
)

/** Low-level REST client for Canada EFT + Interac e-Transfer aggregator API.
 *
 *  Wraps three operations:
 *  1. Interac e-Transfer send    — push a transfer to recipient by email or phone
 *  2. EFT file submission        — submit a CPA Standard 005 AFT file (credit or debit)
 *  3. Transfer status lookup     — poll aggregator for the current status of any transfer
 *
 *  Auth: Bearer token (`apiKey`) on every request.
 *
 *  See docs/international-bank-rails.md §CA_INTERAC for aggregator contract.
 */
class CaEftApi(config: CaEftConfig):

  private val http: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

  // ── Interac e-Transfer ─────────────────────────────────────────────────────

  /** Initiate an Interac e-Transfer to the recipient (by email or phone).
   *
   *  @param transferId   idempotency key / reference for this transfer
   *  @param amountCents  amount in CAD cents
   *  @param recipient    email or phone number of the recipient
   *  @param recipientType "email" or "phone"
   *  @param reference    payment message shown to recipient (max 40 chars)
   *  @return             raw JSON response body from aggregator
   */
  def sendInteracTransfer(
      transferId:    String,
      amountCents:   Long,
      recipient:     String,
      recipientType: String,
      reference:     String,
  ): String =
    val json =
      s"""{
         |  "transferId": "${escJson(transferId)}",
         |  "amount": $amountCents,
         |  "currency": "CAD",
         |  "recipientType": "${escJson(recipientType)}",
         |  "recipient": "${escJson(recipient)}",
         |  "message": "${escJson(reference.take(40))}"
         |}""".stripMargin
    postJson("/interac/transfers", json)

  /** Recall (cancel) an Interac e-Transfer that has not yet been deposited.
   *
   *  @param transferId  the aggregator-assigned or idempotency transfer ID
   *  @return            raw JSON response body from aggregator
   */
  def recallInteracTransfer(transferId: String): String =
    val json = s"""{"transferId":"${escJson(transferId)}"}"""
    postJson("/interac/transfers/recall", json)

  // ── EFT file submission ────────────────────────────────────────────────────

  /** Submit a CPA Standard 005 AFT file to the aggregator.
   *
   *  Builds the fixed-width file from the provided records and POSTs it to
   *  the aggregator's EFT submission endpoint.
   *
   *  @param header   file header (originator, date, sequence)
   *  @param records  list of AFT credit or debit records
   *  @return         raw JSON response body from aggregator (includes file reference ID)
   */
  def submitEftFile(header: AftFileHeader, records: List[AftRecord]): String =
    val fileContent = buildAftFile(header, records)
    val json =
      s"""{
         |  "fileContent": "${escJson(fileContent)}",
         |  "fileType": "CPA005",
         |  "sequenceNumber": ${header.fileSequenceNumber}
         |}""".stripMargin
    postJson("/eft/files", json)

  /** Fetch the current status of a transfer (Interac or EFT) by aggregator ID. */
  def getTransferStatus(id: String): String =
    getJson(s"/transfers/${escJson(id)}")

  // ── CPA Standard 005 fixed-width file builder ─────────────────────────────

  /** Build a CPA Standard 005 AFT file (1,464-byte logical records per line).
   *
   *  File structure:
   *    Record type A — File Header        (1 record)
   *    Record type D — Transaction Detail  (N records, one per AftRecord)
   *    Record type Z — File Trailer        (1 record)
   *
   *  Field encoding:
   *    - Numeric fields: right-justified, zero-padded.
   *    - Alpha fields: left-justified, space-padded.
   *    - All records are exactly 1,464 characters wide.
   *
   *  Key field positions (1-based) in the Detail record (record type D):
   *    [1]       Record type = "D"
   *    [2-4]     Transaction type (e.g. "450" or "470")
   *    [5-14]    Amount in cents — 10 digits, zero-padded
   *    [15-19]   Transit number — 5 digits
   *    [20-22]   Institution number — 3 digits
   *    [23-34]   Account number — 12 chars, space-padded
   *    [35-64]   Payee name — 30 chars, space-padded
   *    [65-83]   Sundry info — 19 chars, space-padded
   *    [84-93]   Originator's short name — 10 chars, space-padded
   *    [94-103]  Originator ID — 10 chars
   *    [104-1464] Reserved / filler
   */
  def buildAftFile(header: AftFileHeader, records: List[AftRecord]): String =
    val dateStr = header.fileCreationDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
    val headerLine   = buildAftHeader(header, dateStr)
    val detailLines  = records.map(r => buildAftDetail(r, header.originatorId))
    val trailerLine  = buildAftTrailer(header, records)
    (List(headerLine) ++ detailLines ++ List(trailerLine)).mkString("\n")

  // ── Record type A — File Header ────────────────────────────────────────────

  /** CPA 005 file header record — exactly 1,464 chars.
   *
   *  Layout (1-based):
   *    [1]       Record type = "A"
   *    [2-11]    Originator ID — 10 chars, space-padded
   *    [12-19]   File creation date — YYYYMMDD
   *    [20-23]   File sequence number — 4 digits, zero-padded
   *    [24-1464] Reserved / filler
   */
  private[caeft] def buildAftHeader(header: AftFileHeader, dateStr: String): String =
    val b = StringBuilder()
    b ++= "A"
    b ++= rightPad(header.originatorId.take(10), 10)
    b ++= dateStr
    b ++= leftPad(header.fileSequenceNumber.toString, 4)
    b ++= " " * (AFT_RECORD_LENGTH - b.length)
    b.toString().take(AFT_RECORD_LENGTH)

  // ── Record type D — Transaction Detail ────────────────────────────────────

  /** CPA 005 AFT detail record — exactly 1,464 chars. */
  private[caeft] def buildAftDetail(r: AftRecord, originatorId: String): String =
    val b = StringBuilder()
    b ++= "D"                                                        // [1]    Record type
    b ++= r.transactionType.toString                                  // [2-4]  Transaction type (3 digits)
    b ++= leftPad(r.amount.toString, 10)                             // [5-14] Amount cents
    b ++= leftPad(r.transitNumber.filter(_.isDigit).take(5), 5)      // [15-19] Transit
    b ++= leftPad(r.institutionNumber.filter(_.isDigit).take(3), 3)  // [20-22] Institution
    b ++= rightPad(r.accountNumber.take(12), 12)                     // [23-34] Account
    b ++= rightPad(r.payeeName.take(30), 30)                         // [35-64] Payee name
    b ++= rightPad(r.sundryInfo.take(19), 19)                        // [65-83] Sundry info
    b ++= rightPad(originatorId.take(10), 10)                        // [84-93] Originator short name
    b ++= rightPad(originatorId.take(10), 10)                        // [94-103] Originator ID
    b ++= " " * (AFT_RECORD_LENGTH - b.length)                       // filler to 1464
    b.toString().take(AFT_RECORD_LENGTH)

  // ── Record type Z — File Trailer ──────────────────────────────────────────

  /** CPA 005 AFT trailer record — exactly 1,464 chars.
   *
   *  Layout (1-based):
   *    [1]       Record type = "Z"
   *    [2-11]    Originator ID — 10 chars
   *    [12-21]   Total credit amount — 10 digits, zero-padded
   *    [22-31]   Total debit amount — 10 digits, zero-padded
   *    [32-37]   Credit record count — 6 digits, zero-padded
   *    [38-43]   Debit record count — 6 digits, zero-padded
   *    [44-1464] Reserved / filler
   */
  private[caeft] def buildAftTrailer(header: AftFileHeader, records: List[AftRecord]): String =
    val credits      = records.filter(_.transactionType == 450)
    val debits       = records.filter(_.transactionType == 470)
    val totalCredit  = credits.map(_.amount).sum
    val totalDebit   = debits.map(_.amount).sum
    val creditCount  = credits.size
    val debitCount   = debits.size
    val b = StringBuilder()
    b ++= "Z"
    b ++= rightPad(header.originatorId.take(10), 10)
    b ++= leftPad(totalCredit.toString, 10)
    b ++= leftPad(totalDebit.toString, 10)
    b ++= leftPad(creditCount.toString, 6)
    b ++= leftPad(debitCount.toString, 6)
    b ++= " " * (AFT_RECORD_LENGTH - b.length)
    b.toString().take(AFT_RECORD_LENGTH)

  // ── HTTP helpers ───────────────────────────────────────────────────────────

  private[caeft] def postJson(path: String, json: String): String =
    val req = JHttpRequest.newBuilder(URI.create(s"${config.baseUrl}$path"))
      .header("Authorization", s"Bearer ${config.apiKey}")
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .POST(JHttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
      .timeout(Duration.ofSeconds(30))
      .build()
    checkResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private[caeft] def getJson(path: String): String =
    val req = JHttpRequest.newBuilder(URI.create(s"${config.baseUrl}$path"))
      .header("Authorization", s"Bearer ${config.apiKey}")
      .GET()
      .timeout(Duration.ofSeconds(30))
      .build()
    checkResponse(http.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8)))

  private def checkResponse(resp: JHttpResponse[String]): String =
    if resp.statusCode() >= 400 then
      throw new RuntimeException(s"CA EFT/Interac aggregator API error ${resp.statusCode()}: ${resp.body()}")
    resp.body()

  // ── JSON helpers ───────────────────────────────────────────────────────────

  private[caeft] def extractField(json: String, key: String): Option[String] =
    s""""$key"\\s*:\\s*"([^"\\\\]*)"""".r.findFirstMatchIn(json).map(_.group(1))

  private def escJson(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")

  // ── Padding helpers ────────────────────────────────────────────────────────

  private[caeft] def rightPad(s: String, n: Int): String =
    val t = s.take(n); t + " " * (n - t.length)

  private[caeft] def leftPad(s: String, n: Int): String =
    val t = s.take(n); "0" * (n - t.length) + t

/** CPA Standard 005 logical record length (1,464 bytes). */
val AFT_RECORD_LENGTH = 1464
