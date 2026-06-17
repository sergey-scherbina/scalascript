package scalascript.payments.swift

import scalascript.payments.bankrails.*
import scalascript.payments.money.Currency
import java.time.{LocalDate, ZoneOffset}
import java.time.format.DateTimeFormatter

/** Builds a SWIFT MT103 message string from a BankTransfer / InitiateTransferRequest.
 *
 *  MT103 is the ISO 15022 single-customer credit transfer message.
 *  Key fields:
 *    Field 20  — Transaction Reference Number (TRN = idempotencyKey, max 16 chars)
 *    Field 32A — Value Date, Currency, Amount (YYMMDD + CCY + 15d amount)
 *    Field 50K — Ordering Customer (sender name + account)
 *    Field 57A — Account With Institution (beneficiary BIC)
 *    Field 59  — Beneficiary Customer (name + IBAN or account)
 *    Field 70  — Remittance Information (free-text, max 4×35 chars)
 *    Field 71A — Details of Charges (OUR / SHA / BEN)
 *
 *  The output is a plain-text MT103 message formatted per SWIFT standards.
 *  See specs/international-bank-rails.md §8 v1.55.1.
 */
object SwiftMt103Builder:

  private val ValueDateFmt = DateTimeFormatter.ofPattern("yyMMdd")

  /** Build an MT103 message string from an InitiateTransferRequest.
   *
   *  @param req  the transfer request
   *  @param uetr the UETR (UUID v4) assigned to this transfer
   *  @return     an MT103 message as a plain-text string
   */
  def build(req: InitiateTransferRequest, uetr: Uetr): String =
    val trn         = sanitizeTrn(req.idempotencyKey)
    val valueDate   = req.scheduledDate
                        .getOrElse(LocalDate.now(ZoneOffset.UTC).plusDays(1))
                        .format(ValueDateFmt)
    val currency    = req.amount.currency.toString
    val amount      = formatMt103Amount(req.amount)
    val chargeCode  = chargeBearer(req.chargeBearer)
    val senderName  = sanitizeText(req.sender.holderName, 35)
    val senderAcct  = req.sender.iban.orElse(req.sender.accountNumber).getOrElse("")
    val benBic      = req.recipient.bic.getOrElse("NOTPROVIDED")
    val benName     = sanitizeText(req.recipient.holderName, 35)
    val benAcct     = req.recipient.iban.orElse(req.recipient.accountNumber).getOrElse("")
    val remittance  = sanitizeText(req.reference, 35)

    s"""{1:F01NOTPROVIDED0000000000}{2:I103NOTPROVIDEDXXXXN}{4:
:20:$trn
:23B:CRED
:32A:${valueDate}${currency}${amount}
:50K:/$senderAcct
${senderName}
:57A:${benBic}
:59:/$benAcct
${benName}
:70:${remittance}
:71A:${chargeCode}
:121:${uetr.value}
-}"""

  // ── Helpers ────────────────────────────────────────────────────────────────

  /** Format a Money value as an MT103 amount string.
   *  MT103 uses comma as decimal separator and no thousands separator.
   *  e.g. EUR 1234.56 → "1234,56"
   */
  private[swift] def formatMt103Amount(money: scalascript.payments.money.Money): String =
    val power  = Currency.minorUnitsPower(money.currency)
    if power == 0 then money.minorUnits.toString
    else
      val factor = math.pow(10, power).toLong
      val whole  = money.minorUnits / factor
      val frac   = math.abs(money.minorUnits % factor)
      s"$whole,${frac.toString.reverse.padTo(power, '0').reverse}"

  /** Map ChargeBearer to MT103 field 71A codes. */
  private[swift] def chargeBearer(cb: ChargeBearer): String = cb match
    case ChargeBearer.OUR => "OUR"
    case ChargeBearer.SHA => "SHA"
    case ChargeBearer.BEN => "BEN"

  /** Truncate and strip control chars for MT103 text fields. */
  private def sanitizeText(s: String, maxLen: Int): String =
    s.replaceAll("[\\r\\n\\t]", " ").trim.take(maxLen)

  /** MT103 Field 20 TRN: max 16 chars, alphanumeric + / - . only. */
  private[swift] def sanitizeTrn(key: String): String =
    key.replaceAll("[^A-Za-z0-9/\\-.]", "").take(16)
