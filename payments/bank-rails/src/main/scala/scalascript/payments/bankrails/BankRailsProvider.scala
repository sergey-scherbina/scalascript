package scalascript.payments.bankrails

import scalascript.payments.webhook.WebhookReceiver

/** Bank rails payment provider SPI.
 *
 *  Six-method contract covering push (credit transfer) and pull (direct debit)
 *  bank payments, plus webhook event delivery.
 *
 *  All bank rails are inherently async-settlement: `initiateTransfer` returns
 *  `BankTransfer(status = Pending)` and terminal states (Settled/Rejected/Returned)
 *  arrive via webhooks or polling (`getTransfer`).
 *
 *  See docs/specs/bank-rails.md §4.4 for the full SPI specification.
 */
trait BankRailsProvider:
  def id:             String      // "sepa" | "ach" | "pix" | "fednow" | "modern-treasury" | …
  def displayName:    String
  def spiVersion:     String
  def supportedRails: Set[RailKind]

  // ── Push payments (credit transfer, Pix, FedNow) ─────────────────────────
  def initiateTransfer(req: InitiateTransferRequest):       BankTransfer
  def getTransfer(id: TransferId):                          BankTransfer
  def cancelTransfer(id: TransferId):                       Unit

  // ── Pull payments (direct debit) ─────────────────────────────────────────
  def initiateDirectDebit(req: InitiateDirectDebitRequest): BankTransfer
  def getDirectDebit(id: TransferId):                       BankTransfer

  // ── Webhook delivery ──────────────────────────────────────────────────────
  def webhookReceiver: WebhookReceiver[BankRailsEvent]
