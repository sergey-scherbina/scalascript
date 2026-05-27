package scalascript.payments.ukchaps

import scalascript.payments.bankrails.*
import scalascript.payments.money.Money
import java.time.{LocalDateTime, ZoneOffset}
import java.time.format.DateTimeFormatter

/** Builds ISO 20022 pacs.008.001.08 XML messages for UK CHAPS.
 *
 *  CHAPS-specific differences from SEPA pacs.008 (SCT Inst):
 *  - SvcLvl/Cd = CHAPS  (identifies the CHAPS scheme; no LclInstrm)
 *  - SttlmMtd = INDA    (instructed agent: settlement directly at BoE RTGS)
 *  - No ClrSys element  (CHAPS does not route through an external clearing system)
 *  - Creditor account: IBAN preferred; falls back to SortCode + AccountNumber
 *  - BIC of creditor bank included where available (BankAccount.bic)
 *  - IntrBkSttlmAmt currency must be GBP
 *
 *  The aggregator (ClearBank, Starling Payments, Lloyds TSB CHAPS gateway)
 *  converts this pacs.008 to the BoE CHAPS IS20022 format internally.
 *
 *  See docs/international-bank-rails.md §v1.55.5.
 */
object ChapsPacs008Builder:

  private val DtTmFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
  private val DateFmt = DateTimeFormatter.ISO_LOCAL_DATE

  /** Build a pacs.008.001.08 FI-to-FI Customer Credit Transfer for CHAPS.
   *
   *  Required fields populated:
   *    GrpHdr: MsgId, CreDtTm, NbOfTxs, SttlmInf (SttlmMtd=INDA)
   *    CdtTrfTxInf: InstrId, EndToEndId, IntrBkSttlmAmt (GBP), SvcLvl (CHAPS),
   *                 Dbtr/DbtrAcct, CdtrAgt (BIC where available), Cdtr/CdtrAcct
   *
   *  @param req the transfer initiation request; amount.currency must be GBP
   */
  def buildPacs008(req: InitiateTransferRequest): String =
    require(
      req.amount.currency.toString == "GBP",
      s"CHAPS only supports GBP; got ${req.amount.currency}"
    )

    val now      = LocalDateTime.now(ZoneOffset.UTC)
    val msgId    = sanitize(req.idempotencyKey, 35)
    val e2eId    = sanitize(req.idempotencyKey, 35)
    val amount   = formatAmount(req.amount)
    val sttlmDate = now.toLocalDate.format(DateFmt)

    val cdtrAcctXml = buildCreditorAccount(req.recipient)
    val cdtrAgtXml  = buildCreditorAgent(req.recipient)
    val dbtrAcctXml = buildDebtorAccount(req.sender)

    s"""<?xml version="1.0" encoding="UTF-8"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08">
  <FIToFICstmrCdtTrf>
    <GrpHdr>
      <MsgId>${xml(msgId)}</MsgId>
      <CreDtTm>${now.format(DtTmFmt)}</CreDtTm>
      <NbOfTxs>1</NbOfTxs>
      <SttlmInf>
        <!-- SttlmMtd=INDA: settlement directly at the instructed agent (BoE RTGS) -->
        <SttlmMtd>INDA</SttlmMtd>
      </SttlmInf>
    </GrpHdr>
    <CdtTrfTxInf>
      <PmtId>
        <InstrId>${xml(e2eId)}</InstrId>
        <EndToEndId>${xml(e2eId)}</EndToEndId>
      </PmtId>
      <PmtTpInf>
        <!-- SvcLvl CHAPS: identifies this as a CHAPS payment routed to BoE RTGS -->
        <SvcLvl><Cd>CHAPS</Cd></SvcLvl>
      </PmtTpInf>
      <!-- IntrBkSttlmAmt: interbank settlement amount; CHAPS requires GBP -->
      <IntrBkSttlmAmt Ccy="GBP">$amount</IntrBkSttlmAmt>
      <IntrBkSttlmDt>$sttlmDate</IntrBkSttlmDt>
      <DbtrAgt>
        <FinInstnId><Othr><Id>NOTPROVIDED</Id></Othr></FinInstnId>
      </DbtrAgt>
      <Dbtr>
        <Nm>${xml(req.sender.holderName)}</Nm>
        <PstlAdr><Ctry>${xml(req.sender.countryCode)}</Ctry></PstlAdr>
      </Dbtr>
      <DbtrAcct>
        $dbtrAcctXml
      </DbtrAcct>
      <CdtrAgt>
        $cdtrAgtXml
      </CdtrAgt>
      <Cdtr>
        <Nm>${xml(req.recipient.holderName)}</Nm>
        <PstlAdr><Ctry>${xml(req.recipient.countryCode)}</Ctry></PstlAdr>
      </Cdtr>
      <CdtrAcct>
        $cdtrAcctXml
      </CdtrAcct>
      <RmtInf>
        <Ustrd>${xml(req.reference.take(140))}</Ustrd>
      </RmtInf>
    </CdtTrfTxInf>
  </FIToFICstmrCdtTrf>
</Document>"""

  // ── Private XML fragment builders ─────────────────────────────────────────

  /** Creditor account: IBAN preferred; falls back to UK sort-code + account number. */
  private def buildCreditorAccount(account: BankAccount): String =
    account.iban match
      case Some(iban) =>
        s"<Id><IBAN>${xml(iban)}</IBAN></Id>"
      case None =>
        val sortCode      = account.sortCode.getOrElse("")
        val accountNumber = account.accountNumber.getOrElse("")
        s"""<Id><Othr><Id>${xml(sortCode)}-${xml(accountNumber)}</Id></Othr></Id>"""

  /** Creditor agent: BIC if present; otherwise NOTPROVIDED placeholder. */
  private def buildCreditorAgent(account: BankAccount): String =
    account.bic match
      case Some(bic) =>
        s"<FinInstnId><BICFI>${xml(bic)}</BICFI></FinInstnId>"
      case None =>
        "<FinInstnId><Othr><Id>NOTPROVIDED</Id></Othr></FinInstnId>"

  /** Debtor account: IBAN preferred; falls back to UK sort-code + account number. */
  private def buildDebtorAccount(account: BankAccount): String =
    account.iban match
      case Some(iban) =>
        s"<Id><IBAN>${xml(iban)}</IBAN></Id>"
      case None =>
        val sortCode      = account.sortCode.getOrElse("")
        val accountNumber = account.accountNumber.getOrElse("")
        s"""<Id><Othr><Id>${xml(sortCode)}-${xml(accountNumber)}</Id></Othr></Id>"""

  // ── Helpers ────────────────────────────────────────────────────────────────

  /** Format a Money value as a decimal string (e.g. "12500.00"). */
  private[ukchaps] def formatAmount(money: Money): String =
    val power = scalascript.payments.money.Currency.minorUnitsPower(money.currency)
    if power == 0 then money.minorUnits.toString
    else
      val factor = math.pow(10, power).toLong
      val whole  = money.minorUnits / factor
      val frac   = math.abs(money.minorUnits % factor)
      s"$whole.${frac.toString.reverse.padTo(power, '0').reverse}"

  /** Truncate to max length and strip whitespace. */
  private def sanitize(s: String, maxLen: Int): String = s.trim.take(maxLen)

  /** XML-escape characters that must be escaped in XML content. */
  private[ukchaps] def xml(s: String): String =
    s.replace("&", "&amp;")
     .replace("<", "&lt;")
     .replace(">", "&gt;")
     .replace("\"", "&quot;")
