package scalascript.payments.fednow

import scalascript.payments.bankrails.*
import scalascript.payments.money.Money
import java.time.{LocalDateTime, ZoneOffset}
import java.time.format.DateTimeFormatter

/** Builds ISO 20022 XML messages for FedNow instant payments.
 *
 *  pacs.008.001.08 — FIToFICustomerCreditTransfer (credit transfer initiation)
 *  pacs.002.001.10 — FIToFIPaymentStatusReport (status query / ack parsing)
 *
 *  These are minimal but valid ISO 20022 XML documents suitable for submission
 *  to the FedNow Connect REST API via FedLine Advantage.
 *
 *  See docs/bank-rails.md §v1.54.4 and the FedNow ISO 20022 Message Guide.
 */
object Iso20022Xml:

  private val DtTmFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
  private val DateFmt = DateTimeFormatter.ISO_LOCAL_DATE

  /** Build a minimal pacs.008.001.08 FIToFICustomerCreditTransfer message.
   *
   *  Structure: Document / FIToFICstmrCdtTrf
   *    GrpHdr: MsgId, CreDtTm, NbOfTxs, SttlmInf
   *    CdtTrfTxInf: PmtId (InstrId + EndToEndId), IntrBkSttlmAmt, IntrBkSttlmDt,
   *                 Dbtr, DbtrAcct, DbtrAgt, Cdtr, CdtrAcct, CdtrAgt
   *
   *  @param req  the transfer request
   *  @param participantId  FedNow participant ID of the sending FI
   *  @param routingNumber  ABA routing number of the sending FI
   */
  def buildPacs008(req: InitiateTransferRequest, routingNumber: String): String =
    val now       = LocalDateTime.now(ZoneOffset.UTC)
    val msgId     = sanitize(req.idempotencyKey, 35)
    val instrId   = sanitize(req.idempotencyKey, 35)
    val e2eId     = sanitize(req.idempotencyKey, 35)
    val sttlmDt   = req.scheduledDate.map(_.format(DateFmt))
                      .getOrElse(now.toLocalDate.format(DateFmt))
    val amount    = formatAmount(req.amount)
    val currency  = req.amount.currency.toString

    // Use ABA routing (bankCode) if present, otherwise fall back to routingNumber config
    val dbtrRoutingNum = req.sender.bankCode.orElse(req.sender.routingNumber)
                           .getOrElse(routingNumber)
    val cdtrRoutingNum = req.recipient.bankCode.orElse(req.recipient.routingNumber)
                           .getOrElse("021000021")  // placeholder; real transfers supply this

    // Use IBAN if present, otherwise use Othr/accountNumber
    val dbtrAcctElem = req.sender.iban match
      case Some(iban) => s"<IBAN>${xml(iban)}</IBAN>"
      case None       => s"<Othr><Id>${xml(req.sender.accountNumber.getOrElse(""))}</Id></Othr>"

    val cdtrAcctElem = req.recipient.iban match
      case Some(iban) => s"<IBAN>${xml(iban)}</IBAN>"
      case None       => s"<Othr><Id>${xml(req.recipient.accountNumber.getOrElse(""))}</Id></Othr>"

    s"""<?xml version="1.0" encoding="UTF-8"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08">
  <FIToFICstmrCdtTrf>
    <GrpHdr>
      <MsgId>${xml(msgId)}</MsgId>
      <CreDtTm>${now.format(DtTmFmt)}</CreDtTm>
      <NbOfTxs>1</NbOfTxs>
      <SttlmInf><SttlmMtd>CLRG</SttlmMtd></SttlmInf>
    </GrpHdr>
    <CdtTrfTxInf>
      <PmtId>
        <InstrId>${xml(instrId)}</InstrId>
        <EndToEndId>${xml(e2eId)}</EndToEndId>
      </PmtId>
      <IntrBkSttlmAmt Ccy="$currency">$amount</IntrBkSttlmAmt>
      <IntrBkSttlmDt>$sttlmDt</IntrBkSttlmDt>
      <Dbtr><Nm>${xml(req.sender.holderName)}</Nm></Dbtr>
      <DbtrAcct><Id>$dbtrAcctElem</Id></DbtrAcct>
      <DbtrAgt><FinInstnId><ClrSysMmbId><MmbId>${xml(dbtrRoutingNum)}</MmbId></ClrSysMmbId></FinInstnId></DbtrAgt>
      <Cdtr><Nm>${xml(req.recipient.holderName)}</Nm></Cdtr>
      <CdtrAcct><Id>$cdtrAcctElem</Id></CdtrAcct>
      <CdtrAgt><FinInstnId><ClrSysMmbId><MmbId>${xml(cdtrRoutingNum)}</MmbId></ClrSysMmbId></FinInstnId></CdtrAgt>
    </CdtTrfTxInf>
  </FIToFICstmrCdtTrf>
</Document>"""

  /** Parse a pacs.002.001.10 FIToFIPaymentStatusReport and return the TxSts code.
   *
   *  TxSts values:
   *    ACCP — Accepted (passed validation, pending settlement)
   *    RJCT — Rejected (validation failed or rules violation)
   *    PDNG — Pending  (queued, not yet processed)
   *    ACSC — Accepted Settlement Completed (funds settled)
   *
   *  Returns BankTransferStatus derived from TxSts:
   *    ACCP / PDNG → Pending
   *    ACSC        → Settled
   *    RJCT        → Rejected(code, description)
   */
  def parsePacs002Status(xml: String): BankTransferStatus =
    val txSts = extractXmlElement(xml, "TxSts").getOrElse("PDNG")
    txSts.trim.toUpperCase match
      case "ACSC"              => BankTransferStatus.Settled
      case "RJCT"              =>
        val reasonCode = extractXmlElement(xml, "Rsn")
                           .orElse(extractXmlElement(xml, "Cd"))
                           .getOrElse("FOCR")
        val addlInfo   = extractXmlElement(xml, "AddtlInf").getOrElse("")
        BankTransferStatus.Rejected(RejectCode(reasonCode), addlInfo)
      case _                   => BankTransferStatus.Pending  // ACCP, PDNG, unknown

  /** Extract the InstrId from a pacs.008 or pacs.002 XML body (first occurrence). */
  def extractInstrId(xmlBody: String): Option[String] =
    extractXmlElement(xmlBody, "InstrId")

  // ── Helpers ────────────────────────────────────────────────────────────────

  /** Format a Money amount as decimal string (e.g. "10.50" for 1050 USD cents). */
  private[fednow] def formatAmount(money: Money): String =
    val power = scalascript.payments.money.Currency.minorUnitsPower(money.currency)
    if power == 0 then money.minorUnits.toString
    else
      val factor = math.pow(10, power).toLong
      val whole  = money.minorUnits / factor
      val frac   = math.abs(money.minorUnits % factor)
      s"$whole.${frac.toString.reverse.padTo(power, '0').reverse}"

  /** Extract the text content of the first XML element with the given tag name. */
  private[fednow] def extractXmlElement(xml: String, tag: String): Option[String] =
    s"<$tag>([^<]*)</$tag>".r.findFirstMatchIn(xml).map(_.group(1).trim)

  /** Truncate to max length and strip leading/trailing whitespace. */
  private def sanitize(s: String, maxLen: Int): String = s.trim.take(maxLen)

  /** XML-escape a string (only characters that must be escaped in XML content). */
  private[fednow] def xml(s: String): String =
    s.replace("&", "&amp;")
     .replace("<", "&lt;")
     .replace(">", "&gt;")
     .replace("\"", "&quot;")
