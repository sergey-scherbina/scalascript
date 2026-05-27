package scalascript.payments.sepa

import scalascript.payments.bankrails.*
import scalascript.payments.money.Money
import java.time.{LocalDateTime, ZoneOffset}
import java.time.format.DateTimeFormatter

/** Builds ISO 20022 PAIN XML messages for SEPA Credit Transfer and Direct Debit.
 *
 *  PAIN.001.001.03 — Customer Credit Transfer Initiation (CT)
 *  PAIN.008.001.02 — Customer Direct Debit Initiation (DD)
 *
 *  These are minimal but valid XML documents suitable for submission to a SEPA-
 *  participating bank or aggregator API.  Fields are ISO 20022 compliant.
 */
object SepaPainXml:

  private val DtTmFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
  private val DateFmt = DateTimeFormatter.ISO_LOCAL_DATE

  /** Build a minimal PAIN.001.001.03 Credit Transfer Initiation message.
   *
   *  Structure: Document / CstmrCdtTrfInitn
   *    GrpHdr: MsgId, CreDtTm, NbOfTxs, CtrlSum, InitgPty
   *    PmtInf: PmtInfId, PmtMtd (TRF), NbOfTxs, CtrlSum, PmtTpInf, ReqdExctnDt, Dbtr, DbtrAcct, DbtrAgt
   *      CdtTrfTxInf: PmtId (InstrId + EndToEndId), Amt, Cdtr, CdtrAcct
   */
  def buildPain001(req: InitiateTransferRequest): String =
    val now       = LocalDateTime.now(ZoneOffset.UTC)
    val msgId     = sanitize(req.idempotencyKey, 35)
    val pmtInfId  = s"PII-${sanitize(req.idempotencyKey, 30)}"
    val e2eId     = sanitize(req.idempotencyKey, 35)
    val execDate  = req.scheduledDate.map(_.format(DateFmt))
                      .getOrElse(now.plusDays(1).toLocalDate.format(DateFmt))
    val amount    = formatAmount(req.amount)
    val currency  = req.amount.currency.toString

    s"""<?xml version="1.0" encoding="UTF-8"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.001.001.03">
  <CstmrCdtTrfInitn>
    <GrpHdr>
      <MsgId>${xml(msgId)}</MsgId>
      <CreDtTm>${now.format(DtTmFmt)}</CreDtTm>
      <NbOfTxs>1</NbOfTxs>
      <CtrlSum>$amount</CtrlSum>
      <InitgPty>
        <Nm>${xml(req.sender.holderName)}</Nm>
      </InitgPty>
    </GrpHdr>
    <PmtInf>
      <PmtInfId>${xml(pmtInfId)}</PmtInfId>
      <PmtMtd>TRF</PmtMtd>
      <NbOfTxs>1</NbOfTxs>
      <CtrlSum>$amount</CtrlSum>
      <PmtTpInf>
        <SvcLvl><Cd>SEPA</Cd></SvcLvl>
      </PmtTpInf>
      <ReqdExctnDt>$execDate</ReqdExctnDt>
      <Dbtr>
        <Nm>${xml(req.sender.holderName)}</Nm>
        <PstlAdr><Ctry>${xml(req.sender.countryCode)}</Ctry></PstlAdr>
      </Dbtr>
      <DbtrAcct>
        <Id><IBAN>${xml(req.sender.iban.getOrElse(""))}</IBAN></Id>
      </DbtrAcct>
      <DbtrAgt>
        <FinInstnId><Othr><Id>NOTPROVIDED</Id></Othr></FinInstnId>
      </DbtrAgt>
      <CdtTrfTxInf>
        <PmtId>
          <InstrId>${xml(e2eId)}</InstrId>
          <EndToEndId>${xml(e2eId)}</EndToEndId>
        </PmtId>
        <Amt>
          <InstdAmt Ccy="$currency">$amount</InstdAmt>
        </Amt>
        <Cdtr>
          <Nm>${xml(req.recipient.holderName)}</Nm>
          <PstlAdr><Ctry>${xml(req.recipient.countryCode)}</Ctry></PstlAdr>
        </Cdtr>
        <CdtrAcct>
          <Id><IBAN>${xml(req.recipient.iban.getOrElse(""))}</IBAN></Id>
        </CdtrAcct>
        <RmtInf>
          <Ustrd>${xml(req.reference.take(140))}</Ustrd>
        </RmtInf>
      </CdtTrfTxInf>
    </PmtInf>
  </CstmrCdtTrfInitn>
</Document>"""

  /** Build a minimal PAIN.008.001.02 Direct Debit Initiation message.
   *
   *  Structure: Document / CstmrDrctDbtInitn
   *    GrpHdr: MsgId, CreDtTm, NbOfTxs, CtrlSum, InitgPty
   *    PmtInf: PmtInfId, PmtMtd (DD), NbOfTxs, CtrlSum, PmtTpInf (SeqTp), ReqdColltnDt,
   *            Cdtr, CdtrAcct, CdtrAgt, CdtrSchmeId
   *      DrctDbtTxInf: PmtId, InstdAmt, DrctDbtTx (MndtRltdInf), DbtrAgt, Dbtr, DbtrAcct
   */
  def buildPain008(req: InitiateDirectDebitRequest, mandate: DirectDebitMandate): String =
    val now        = LocalDateTime.now(ZoneOffset.UTC)
    val msgId      = sanitize(req.idempotencyKey, 35)
    val pmtInfId   = s"PII-${sanitize(req.idempotencyKey, 30)}"
    val e2eId      = sanitize(req.idempotencyKey, 35)
    val collDate   = req.scheduledDate.map(_.format(DateFmt))
                       .getOrElse(now.plusDays(2).toLocalDate.format(DateFmt))
    val amount     = formatAmount(req.amount)
    val currency   = req.amount.currency.toString
    val seqTp      = mandate.sequenceType match
      case MandateSequenceType.First     => "FRST"
      case MandateSequenceType.Recurring => "RCUR"
      case MandateSequenceType.Final     => "FNAL"
      case MandateSequenceType.OneOff    => "OOFF"
    val mndtId     = mandate.id.value.take(35)
    val mndtDate   = mandate.signedAt
                       .map(i => java.time.LocalDate.ofInstant(i, ZoneOffset.UTC).format(DateFmt))
                       .getOrElse(now.toLocalDate.format(DateFmt))

    s"""<?xml version="1.0" encoding="UTF-8"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.008.001.02">
  <CstmrDrctDbtInitn>
    <GrpHdr>
      <MsgId>${xml(msgId)}</MsgId>
      <CreDtTm>${now.format(DtTmFmt)}</CreDtTm>
      <NbOfTxs>1</NbOfTxs>
      <CtrlSum>$amount</CtrlSum>
      <InitgPty>
        <Nm>${xml(req.creditorName)}</Nm>
      </InitgPty>
    </GrpHdr>
    <PmtInf>
      <PmtInfId>${xml(pmtInfId)}</PmtInfId>
      <PmtMtd>DD</PmtMtd>
      <NbOfTxs>1</NbOfTxs>
      <CtrlSum>$amount</CtrlSum>
      <PmtTpInf>
        <SvcLvl><Cd>SEPA</Cd></SvcLvl>
        <LclInstrm><Cd>CORE</Cd></LclInstrm>
        <SeqTp>$seqTp</SeqTp>
      </PmtTpInf>
      <ReqdColltnDt>$collDate</ReqdColltnDt>
      <Cdtr>
        <Nm>${xml(req.creditorName)}</Nm>
        <PstlAdr><Ctry>${xml(req.creditorAccount.countryCode)}</Ctry></PstlAdr>
      </Cdtr>
      <CdtrAcct>
        <Id><IBAN>${xml(req.creditorAccount.iban.getOrElse(""))}</IBAN></Id>
      </CdtrAcct>
      <CdtrAgt>
        <FinInstnId><Othr><Id>NOTPROVIDED</Id></Othr></FinInstnId>
      </CdtrAgt>
      <CdtrSchmeId>
        <Id>
          <PrvtId>
            <Othr>
              <Id>${xml(req.creditorAccount.bankCode.getOrElse("UNKNOWN"))}</Id>
              <SchmeNm><Prtry>SEPA</Prtry></SchmeNm>
            </Othr>
          </PrvtId>
        </Id>
      </CdtrSchmeId>
      <DrctDbtTxInf>
        <PmtId>
          <InstrId>${xml(e2eId)}</InstrId>
          <EndToEndId>${xml(e2eId)}</EndToEndId>
        </PmtId>
        <InstdAmt Ccy="$currency">$amount</InstdAmt>
        <DrctDbtTx>
          <MndtRltdInf>
            <MndtId>${xml(mndtId)}</MndtId>
            <DtOfSgntr>$mndtDate</DtOfSgntr>
          </MndtRltdInf>
        </DrctDbtTx>
        <DbtrAgt>
          <FinInstnId><Othr><Id>NOTPROVIDED</Id></Othr></FinInstnId>
        </DbtrAgt>
        <Dbtr>
          <Nm>${xml(req.debtorAccount.holderName)}</Nm>
          <PstlAdr><Ctry>${xml(req.debtorAccount.countryCode)}</Ctry></PstlAdr>
        </Dbtr>
        <DbtrAcct>
          <Id><IBAN>${xml(req.debtorAccount.iban.getOrElse(""))}</IBAN></Id>
        </DbtrAcct>
        <RmtInf>
          <Ustrd>${xml(req.reference.take(140))}</Ustrd>
        </RmtInf>
      </DrctDbtTxInf>
    </PmtInf>
  </CstmrDrctDbtInitn>
</Document>"""

  // ── Helpers ────────────────────────────────────────────────────────────────

  /** Format a Money amount as decimal string for PAIN XML (e.g. "49.99"). */
  private[sepa] def formatAmount(money: Money): String =
    val power = scalascript.payments.money.Currency.minorUnitsPower(money.currency)
    if power == 0 then money.minorUnits.toString
    else
      val factor = math.pow(10, power).toLong
      val whole  = money.minorUnits / factor
      val frac   = math.abs(money.minorUnits % factor)
      s"$whole.${frac.toString.reverse.padTo(power, '0').reverse}"

  /** Truncate to max length and strip leading/trailing whitespace. */
  private def sanitize(s: String, maxLen: Int): String = s.trim.take(maxLen)

  /** XML-escape a string (only the characters that must be escaped in XML content). */
  private[sepa] def xml(s: String): String =
    s.replace("&", "&amp;")
     .replace("<", "&lt;")
     .replace(">", "&gt;")
     .replace("\"", "&quot;")
