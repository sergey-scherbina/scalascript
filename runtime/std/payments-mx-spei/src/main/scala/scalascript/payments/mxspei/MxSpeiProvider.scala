package scalascript.payments.mxspei

import scalascript.payments.bankrails.*
import scalascript.payments.money.{Money, Currency}
import scalascript.payments.webhook.WebhookReceiver
import java.time.Instant

/** Mexico SPEI BankRailsProvider adapter.
 *
 *  Communicates with a BANXICO-connected aggregator (STP, Conekta, or similar)
 *  via REST JSON over HTTPS.
 *
 *  SPEI is a real-time gross settlement system operated by BANXICO (Bank of Mexico).
 *  Transfers are irrevocable once submitted — there is no cancellation window.
 *
 *  Recipient identification: CLABE (Clave Bancaria Estandarizada), an 18-digit number
 *  with a check digit. Validation is performed before submission via `ClabeValidator`.
 *
 *  MXN-only validation: if the request amount is not in MXN, `IllegalArgumentException` is raised.
 *
 *  Settlement: T+0, near-real-time. BANXICO operating hours apply for same-day settlement
 *  (typically 06:00–21:30 CST on business days); off-hours transfers settle next business day.
 *
 *  Auth: Bearer token (`apiKey`) on every request.
 *
 *  See docs/payment-rails-apac.md §MX_SPEI.
 */
class MxSpeiProvider(config: MxSpeiConfig) extends BankRailsProvider:

  private val api = MxSpeiApi(config)

  def id:             String        = "mx-spei"
  def displayName:    String        = "Mexico SPEI (BANXICO real-time gross settlement)"
  def spiVersion:     String        = "1.57.3"
  def supportedRails: Set[RailKind] = Set(RailKind.MX_SPEI)

  // ── Push: SPEI credit transfer ────────────────────────────────────────────

  def initiateTransfer(req: InitiateTransferRequest): BankTransfer =
    if !supportedRails.contains(req.rail) then
      throw UnsupportedRail(req.rail, id)

    // SPEI is MXN-only
    if req.amount.currency.code != "MXN" then
      throw new IllegalArgumentException(
        s"SPEI only supports MXN transfers; got ${req.amount.currency.code}"
      )

    val clabe = req.recipient.clabe.getOrElse(
      throw new IllegalArgumentException(
        "BankAccount.clabe is required for MX_SPEI transfers (set on recipient)"
      )
    )

    // Validate CLABE before submitting
    ClabeValidator.validate(clabe) match
      case Left(err) => throw new IllegalArgumentException(err)
      case Right(_)  => ()

    api.submitTransfer(req)

  def getTransfer(id: TransferId): BankTransfer =
    api.getTransferStatus(id)

  def cancelTransfer(id: TransferId): Unit =
    // SPEI transfers are irrevocable once submitted to BANXICO
    throw BankRailsCancelError(id, "SPEI transfers are irrevocable")

  // ── Pull: not supported on SPEI (push-only rail) ──────────────────────────

  def initiateDirectDebit(req: InitiateDirectDebitRequest): BankTransfer =
    throw UnsupportedRail(req.rail, id)

  def getDirectDebit(id: TransferId): BankTransfer =
    throw TransferNotFound(id)

  // ── Webhook ───────────────────────────────────────────────────────────────

  def webhookReceiver: WebhookReceiver[BankRailsEvent] = MxSpeiWebhookReceiver()
