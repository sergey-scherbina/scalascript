package scalascript.payments.swift

import scalascript.payments.bankrails.*
import scalascript.payments.money.Currency
import java.time.{LocalDateTime, ZoneOffset}
import java.time.format.DateTimeFormatter

/** Builds an ISO 20022 pacs.008.001.10 (CBPR+) FIToFICstmrCdtTrf XML string
 *  from a BankTransfer / InitiateTransferRequest.
 *
 *  CBPR+ mandatory fields:
 *    GrpHdr: MsgId, CreDtTm, NbOfTxs, SttlmMtd (INDA)
 *    CdtTrfTxInf: InstrId, EndToEndId, UETR, IntrBkSttlmAmt,
 *                 ChrgBr, Dbtr, DbtrAgt (BICFI), Cdtr, CdtrAgt (BICFI)
 *
 *  See docs/specs/international-bank-rails.md §8 v1.55.1.
 */
object SwiftPacs008Builder:

  private val DtTmFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

  /** Build a pacs.008.001.10 XML message from an InitiateTransferRequest.
   *
   *  @param req  the transfer request
   *  @param uetr the UETR (UUID v4) assigned to this transfer
   *  @return     a pacs.008 XML string
   */
  def build(req: InitiateTransferRequest, uetr: Uetr): String =
    val now        = LocalDateTime.now(ZoneOffset.UTC)
    val msgId      = sanitize(req.idempotencyKey, 35)
    val instrId    = sanitize(req.idempotencyKey, 35)
    val e2eId      = sanitize(req.idempotencyKey, 35)
    val amount     = formatAmount(req.amount)
    val currency   = req.amount.currency.toString
    val chrgBr     = chargeBearer(req.chargeBearer)
    val dbtrBic    = req.sender.bic.getOrElse("NOTPROVIDED")
    val cdtrBic    = req.recipient.bic.getOrElse("NOTPROVIDED")
    val dbtrName   = xml(req.sender.holderName)
    val cdtrName   = xml(req.recipient.holderName)
    val dbtrAcct   = req.sender.iban.orElse(req.sender.accountNumber).getOrElse("")
    val cdtrAcct   = req.recipient.iban.orElse(req.recipient.accountNumber).getOrElse("")

    s"""<?xml version="1.0" encoding="UTF-8"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.10">
  <FIToFICstmrCdtTrf>
    <GrpHdr>
      <MsgId>${xml(msgId)}</MsgId>
      <CreDtTm>${now.format(DtTmFmt)}</CreDtTm>
      <NbOfTxs>1</NbOfTxs>
      <SttlmInf>
        <SttlmMtd>INDA</SttlmMtd>
      </SttlmInf>
    </GrpHdr>
    <CdtTrfTxInf>
      <PmtId>
        <InstrId>${xml(instrId)}</InstrId>
        <EndToEndId>${xml(e2eId)}</EndToEndId>
        <UETR>${uetr.value}</UETR>
      </PmtId>
      <IntrBkSttlmAmt Ccy="$currency">$amount</IntrBkSttlmAmt>
      <ChrgBr>$chrgBr</ChrgBr>
      <Dbtr>
        <Nm>$dbtrName</Nm>
        <PstlAdr><Ctry>${xml(req.sender.countryCode)}</Ctry></PstlAdr>
      </Dbtr>
      <DbtrAcct>
        <Id><IBAN>${xml(dbtrAcct)}</IBAN></Id>
      </DbtrAcct>
      <DbtrAgt>
        <FinInstnId><BICFI>$dbtrBic</BICFI></FinInstnId>
      </DbtrAgt>
      <CdtrAgt>
        <FinInstnId><BICFI>$cdtrBic</BICFI></FinInstnId>
      </CdtrAgt>
      <Cdtr>
        <Nm>$cdtrName</Nm>
        <PstlAdr><Ctry>${xml(req.recipient.countryCode)}</Ctry></PstlAdr>
      </Cdtr>
      <CdtrAcct>
        <Id><IBAN>${xml(cdtrAcct)}</IBAN></Id>
      </CdtrAcct>
      <RmtInf>
        <Ustrd>${xml(req.reference.take(140))}</Ustrd>
      </RmtInf>
    </CdtTrfTxInf>
  </FIToFICstmrCdtTrf>
</Document>"""

  // ── Helpers ────────────────────────────────────────────────────────────────

  /** Format a Money value as a decimal string for ISO 20022 XML (e.g. "1234.56"). */
  private[swift] def formatAmount(money: scalascript.payments.money.Money): String =
    val power = Currency.minorUnitsPower(money.currency)
    if power == 0 then money.minorUnits.toString
    else
      val factor = math.pow(10, power).toLong
      val whole  = money.minorUnits / factor
      val frac   = math.abs(money.minorUnits % factor)
      s"$whole.${frac.toString.reverse.padTo(power, '0').reverse}"

  /** Map ChargeBearer to ISO 20022 ChrgBr codes. */
  private[swift] def chargeBearer(cb: ChargeBearer): String = cb match
    case ChargeBearer.OUR => "DEBT"  // ISO 20022 pacs.008 uses DEBT/CRED/SHAR/SLEV
    case ChargeBearer.SHA => "SHAR"
    case ChargeBearer.BEN => "CRED"

  private def sanitize(s: String, maxLen: Int): String = s.trim.take(maxLen)

  /** XML-escape a string. */
  private[swift] def xml(s: String): String =
    s.replace("&", "&amp;")
     .replace("<", "&lt;")
     .replace(">", "&gt;")
     .replace("\"", "&quot;")
