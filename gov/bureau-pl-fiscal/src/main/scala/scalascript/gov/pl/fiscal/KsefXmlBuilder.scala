package scalascript.gov.pl.fiscal

import scalascript.gov.fiscal.*
import scalascript.gov.*
import scalascript.payments.money.Currency

/** Builds FA_VAT 2.0 XML for KSeF submission.
 *
 *  FA_VAT schema: https://www.mf.gov.pl/documents/764034/6350171/Schemat_FA_VAT.xsd
 *  All string interpolations XML-escape user-supplied values to prevent injection. */
object KsefXmlBuilder:

  def buildFaVatXml(invoice: FiscalInvoice): String =
    val sel  = invoice.seller
    val buy  = invoice.buyer
    val date = invoice.issueDate.toString
    s"""<?xml version="1.0" encoding="UTF-8"?>
<Faktura xmlns="http://crd.gov.pl/wzor/2023/06/29/12648/"
         xmlns:etd="http://crd.gov.pl/xml/schematy/dziedzinowe/mf/2022/01/05/eD/DefinicjeTypy/">
  <Naglowek>
    <KodFormularza kodSystemowy="FA (2)" wersjaSchemy="1-0E">FA</KodFormularza>
    <WariantFormularza>2</WariantFormularza>
    <DataWytworzeniaFa>${date}T00:00:00Z</DataWytworzeniaFa>
    <SystemInfo>ScalaScript Bureau v1.59</SystemInfo>
  </Naglowek>
  <Podmiot1>
    <DaneIdentyfikacyjne>
      <NIP>${escape(sel.taxIds.find(_.idType == TaxIdType.NIP).map(_.value).getOrElse(""))}</NIP>
      <PelnaNazwa>${escape(sel.name)}</PelnaNazwa>
    </DaneIdentyfikacyjne>
    <Adres>
      <KodKraju>${escape(sel.country)}</KodKraju>
      <AdresL1>${escape(sel.address.line1)}</AdresL1>
      <KodPocztowy>${escape(sel.address.postalCode)}</KodPocztowy>
      <Miejscowosc>${escape(sel.address.city)}</Miejscowosc>
    </Adres>
  </Podmiot1>
  <Podmiot2>
    <DaneIdentyfikacyjne>
      <NIP>${escape(buy.taxIds.find(_.idType == TaxIdType.NIP).map(_.value).getOrElse(""))}</NIP>
      <PelnaNazwa>${escape(buy.name)}</PelnaNazwa>
    </DaneIdentyfikacyjne>
    <Adres>
      <KodKraju>${escape(buy.country)}</KodKraju>
      <AdresL1>${escape(buy.address.line1)}</AdresL1>
      <KodPocztowy>${escape(buy.address.postalCode)}</KodPocztowy>
      <Miejscowosc>${escape(buy.address.city)}</Miejscowosc>
    </Adres>
  </Podmiot2>
  <Fa>
    <KodWaluty>${escape(invoice.currency.code)}</KodWaluty>
    <P_1>${date}</P_1>
    <P_2>${escape(invoice.invoiceNumber)}</P_2>
    <P_15>${formatAmount(invoice.totalGross.minorUnits, 2)}</P_15>
    <Adnotacje>
      <P_16>2</P_16>
      <P_17>2</P_17>
      <P_18>2</P_18>
      <P_18A>2</P_18A>
      <P_23>2</P_23>
    </Adnotacje>
    <RodzajFaktury>VAT</RodzajFaktury>
${buildLines(invoice.lines)}
${buildTaxSummary(invoice.taxSummary)}
  </Fa>
</Faktura>"""

  private def buildLines(lines: List[InvoiceLine]): String =
    lines.zipWithIndex.map { (line, i) =>
      s"""    <FaWiersz>
      <NrWierszaFa>${i + 1}</NrWierszaFa>
      <P_7>${escape(line.description)}</P_7>
      <P_8A>${escape(line.unit)}</P_8A>
      <P_8B>${line.quantity.bigDecimal.toPlainString}</P_8B>
      <P_9A>${formatAmount(line.unitNet.minorUnits, 2)}</P_9A>
      <P_11>${formatAmount(line.totalNet.minorUnits, 2)}</P_11>
      <P_12>${vatRateCode(line.vatRate)}</P_12>
    </FaWiersz>"""
    }.mkString("\n")

  private def buildTaxSummary(lines: List[TaxSummaryLine]): String =
    lines.map { tsl =>
      s"""    <P_13_${vatRateCode(tsl.vatRate)}>${formatAmount(tsl.net.minorUnits, 2)}</P_13_${vatRateCode(tsl.vatRate)}>
    <P_14_${vatRateCode(tsl.vatRate)}>${formatAmount(tsl.tax.minorUnits, 2)}</P_14_${vatRateCode(tsl.vatRate)}>"""
    }.mkString("\n")

  private def vatRateCode(rate: VatRate): String = rate match
    case VatRate.Standard    => "1"   // 23%
    case VatRate.Reduced     => "2"   // 8%
    case VatRate.SuperReduced => "3"  // 5%
    case VatRate.Zero        => "4"   // 0%
    case VatRate.Exempt      => "5"   // zw
    case VatRate.ReverseCharge => "6" // oo
    case VatRate.Custom(_)   => "1"

  private def formatAmount(minorUnits: Long, decimals: Int): String =
    val divisor = math.pow(10, decimals).toLong
    val whole   = minorUnits / divisor
    val frac    = math.abs(minorUnits % divisor)
    val fracStr = frac.toString.reverse.padTo(decimals, '0').reverse
    s"$whole.$fracStr"

  def escape(s: String): String =
    s.replace("&", "&amp;")
     .replace("<", "&lt;")
     .replace(">", "&gt;")
     .replace("\"", "&quot;")
     .replace("'", "&apos;")

  def parseFaVatXml(xml: String): Map[String, String] =
    val tagPat = "<([A-Za-z0-9_:]+)>([^<]*)</[A-Za-z0-9_:]+>".r
    tagPat.findAllMatchIn(xml).map(m => m.group(1) -> m.group(2)).toMap
