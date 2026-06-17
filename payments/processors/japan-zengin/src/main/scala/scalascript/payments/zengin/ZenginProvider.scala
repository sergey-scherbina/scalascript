package scalascript.payments.zengin

import scalascript.payments.bankrails.*
import scalascript.payments.webhook.WebhookReceiver
import java.time.{Instant, ZoneId, ZonedDateTime, LocalTime}
import java.util.concurrent.ConcurrentHashMap
import scala.collection.concurrent.{Map as CMap}
import scala.jdk.CollectionConverters.*

/** Japan Zengin BankRailsProvider adapter.
 *
 *  Implements domestic JPY bank transfers via the Zengin Data Telecommunication
 *  System (全銀データ通信システム). Communicates with an aggregator REST API
 *  (e.g. GMO Payment Gateway, AnyPay) over HTTPS.
 *
 *  Settlement windows:
 *  - Weekdays 08:30–15:30 JST: same-day settlement (Zengin business hours)
 *  - After 15:30 JST or weekends/holidays: queued to next business day
 *
 *  Kana constraint: `BankAccount.holderName` must be in half-width katakana
 *  (U+FF66–U+FF9F range).  `initiateTransfer` calls `KatakanaValidator.validate`
 *  and throws `IllegalArgumentException` on invalid characters.  Transliteration
 *  from full-width or romaji is the caller's responsibility.
 *
 *  Amount: JPY has no minor unit (0 decimal places).  `Money.minorUnits` equals
 *  the yen integer amount directly.
 *
 *  See specs/international-bank-rails.md §v1.55.7 for full spec.
 */
/** @param config  Zengin adapter configuration
 *  @param nowJst  injectable clock for settlement window checks (defaults to real time);
 *                 useful in tests to avoid time-of-day sensitivity. */
class ZenginProvider(
    val config: ZenginConfig,
    nowJst: () => ZonedDateTime = () => ZonedDateTime.now(ZoneId.of("Asia/Tokyo")),
) extends BankRailsProvider:

  def id:             String        = "zengin"
  def displayName:    String        = "Japan Zengin (全銀) Domestic Bank Transfer"
  def spiVersion:     String        = "1.55.7"
  def supportedRails: Set[RailKind] = Set(RailKind.JP_ZENGIN)

  private val JstZone = ZoneId.of("Asia/Tokyo")
  private val WindowOpen  = LocalTime.of(8, 30)
  private val WindowClose = LocalTime.of(15, 30)

  // In-memory transfer store (keyed by idempotency key for dedup + by transfer ID for lookup)
  private val transfers: CMap[String, BankTransfer] =
    new ConcurrentHashMap[String, BankTransfer]().asScala

  // ── Push: Zengin Credit Transfer ──────────────────────────────────────────

  def initiateTransfer(req: InitiateTransferRequest): BankTransfer =
    if !supportedRails.contains(req.rail) then throw UnsupportedRail(req.rail, id)

    // Idempotency check
    transfers.get(req.idempotencyKey) match
      case Some(existing) => return existing
      case None =>

    val recipient = req.recipient

    // Validate kana name
    KatakanaValidator.validate(recipient.holderName) match
      case Left(invalid) =>
        throw new IllegalArgumentException(
          s"Zengin account holder name contains non-kana characters: ${invalid.mkString(", ")}"
        )
      case Right(_) =>

    val bankCode = recipient.zenginBankCode.getOrElse(
      throw new IllegalArgumentException("Zengin transfer requires recipient.zenginBankCode")
    )
    val branchCode = recipient.zenginBranchCode.getOrElse(
      throw new IllegalArgumentException("Zengin transfer requires recipient.zenginBranchCode")
    )
    val accountNumber = recipient.accountNumber.getOrElse(
      throw new IllegalArgumentException("Zengin transfer requires recipient.accountNumber")
    )

    // Check settlement window
    checkSettlementWindow(nowJst())

    // Build Zengin data record (JPY: minorUnits == yen integer)
    val dataRecord = ZenginDataRecord(
      bankCode      = bankCode,
      bankName      = "",                    // blank — aggregator resolves from bank code
      branchCode    = branchCode,
      branchName    = "",                    // blank — aggregator resolves from branch code
      accountType   = ZenginFile.AccountType.Ordinary,
      accountNumber = accountNumber,
      accountName   = recipient.holderName,
      amountYen     = req.amount.minorUnits, // JPY: minorUnits = yen (no decimals)
      customerId    = req.idempotencyKey.take(20),
    )

    val zenginFileContent = ZenginFile.build(config, List(dataRecord))

    // Deliver file to aggregator (stub — real impl would POST to config.baseUrl)
    deliverZenginFile(zenginFileContent)

    val transferId = s"zengin-${System.currentTimeMillis()}-${req.idempotencyKey.take(8)}"
    val transfer = BankTransfer(
      id        = TransferId(transferId),
      rail      = RailKind.JP_ZENGIN,
      amount    = req.amount,
      sender    = req.sender,
      recipient = req.recipient,
      reference = req.reference,
      status    = BankTransferStatus.Pending,
      createdAt = Instant.now(),
      metadata  = req.metadata,
    )
    transfers.put(req.idempotencyKey, transfer)
    transfers.put(transferId, transfer)
    transfer

  def getTransfer(id: TransferId): BankTransfer =
    transfers.getOrElse(id.value, throw TransferNotFound(id))

  def cancelTransfer(id: TransferId): Unit =
    val transfer = transfers.getOrElse(id.value, throw TransferNotFound(id))
    transfer.status match
      case BankTransferStatus.Pending =>
        val canceled = transfer.copy(status = BankTransferStatus.Canceled)
        transfers.put(id.value, canceled)
        transfers.put(transfer.id.value, canceled)
      case _ =>
        throw BankRailsCancelError(id, s"cannot cancel transfer with status ${transfer.status}")

  // ── Direct Debit — not supported for Zengin ───────────────────────────────

  def initiateDirectDebit(req: InitiateDirectDebitRequest): BankTransfer =
    throw UnsupportedRail(req.rail, id)

  def getDirectDebit(id: TransferId): BankTransfer =
    throw UnsupportedRail(RailKind.JP_ZENGIN, id.value)

  // ── Webhook ───────────────────────────────────────────────────────────────

  def webhookReceiver: WebhookReceiver[BankRailsEvent] = ZenginWebhookReceiver()

  // ── Settlement window check ───────────────────────────────────────────────

  /** Checks whether `now` is within the Zengin settlement window (08:30–15:30 JST,
   *  Monday–Friday). Raises `ZenginOutsideWindow` if not. */
  def checkSettlementWindow(nowJst: ZonedDateTime): Unit =
    import java.time.DayOfWeek.*
    val dayOfWeek = nowJst.getDayOfWeek
    val time      = nowJst.toLocalTime
    val isWeekday = dayOfWeek != SATURDAY && dayOfWeek != SUNDAY
    val inWindow  = !time.isBefore(WindowOpen) && time.isBefore(WindowClose)
    if !isWeekday || !inWindow then
      val nextOpen = nextWindowOpen(nowJst)
      throw ZenginOutsideWindow(nextOpen)

  /** Compute the next settlement window opening from `now`. */
  private def nextWindowOpen(now: ZonedDateTime): ZonedDateTime =
    import java.time.DayOfWeek.*
    // If today is a weekday and before window open, next open is today at 08:30
    val todayOpen = now.toLocalDate.atTime(WindowOpen).atZone(JstZone)
    val candidate =
      if now.isBefore(todayOpen) && now.getDayOfWeek != SATURDAY && now.getDayOfWeek != SUNDAY then
        todayOpen
      else
        // Find next weekday
        var next = now.toLocalDate.plusDays(1).atTime(WindowOpen).atZone(JstZone)
        while next.getDayOfWeek == SATURDAY || next.getDayOfWeek == SUNDAY do
          next = next.plusDays(1)
        next
    candidate

  // ── Helpers ───────────────────────────────────────────────────────────────

  /** Deliver the Zengin 21 file to the configured aggregator endpoint.
   *  In production, POST to config.baseUrl with HMAC-SHA256 signature.
   *  Here we record the file content for testability. */
  private[zengin] def deliverZenginFile(content: String): Unit =
    lastDeliveredFile = Some(content)

  // Visible for tests
  private[zengin] var lastDeliveredFile: Option[String] = None


/** Zengin adapter configuration.
 *  See specs/international-bank-rails.md §v1.55.7 for auth and field descriptions. */
case class ZenginConfig(
  clientId:            String,   // aggregator client ID
  apiKey:              String,   // aggregator API key (used in HMAC-SHA256 signing)
  baseUrl:             String,   // aggregator base URL (e.g. "https://api.gmo-pg.com/zengin")
  senderBankCode:      String,   // 4-digit originator bank code
  senderBranchCode:    String,   // 3-digit originator branch code
  senderAccountNumber: String,   // 7-digit originator account number
  senderName:          String,   // originator name in half-width kana (max 40 chars)
  senderBankName:      String = "",   // originator bank name in kana (optional display only)
  senderBranchName:    String = "",   // originator branch name in kana (optional display only)
)

object ZenginConfig:
  /** Load config from environment variables.
   *  ZENGIN_CLIENT_ID, ZENGIN_API_KEY, ZENGIN_BASE_URL,
   *  ZENGIN_SENDER_BANK_CODE, ZENGIN_SENDER_BRANCH_CODE,
   *  ZENGIN_SENDER_ACCOUNT_NUMBER, ZENGIN_SENDER_NAME */
  def fromEnv: ZenginConfig =
    ZenginConfig(
      clientId            = sys.env.getOrElse("ZENGIN_CLIENT_ID",             "zengin-client"),
      apiKey              = sys.env.getOrElse("ZENGIN_API_KEY",               "change-me"),
      baseUrl             = sys.env.getOrElse("ZENGIN_BASE_URL",              "https://api.zengin-aggregator.example.com"),
      senderBankCode      = sys.env.getOrElse("ZENGIN_SENDER_BANK_CODE",      "0001"),
      senderBranchCode    = sys.env.getOrElse("ZENGIN_SENDER_BRANCH_CODE",    "001"),
      senderAccountNumber = sys.env.getOrElse("ZENGIN_SENDER_ACCOUNT_NUMBER", "1234567"),
      senderName          = sys.env.getOrElse("ZENGIN_SENDER_NAME",           "ｱｸﾒ ｶ-ｼｬ"),
    )
