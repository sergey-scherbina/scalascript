package scalascript.gov.pl.social

import scalascript.gov.*
import scalascript.gov.social.*
import scalascript.gov.providers.SocialProvider
import scalascript.payments.money.{Currency, Money}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant, LocalDate, YearMonth}
import java.util.Base64
import scala.concurrent.{Future, ExecutionContext}

/** ZUS PUE REST adapter implementing `SocialProvider` for Poland.
 *
 *  Covers:
 *  - KEDU declaration submission (DRA, RCA, ZUA, ZWUA)
 *  - NRB payment reference generation (deterministic, no API call)
 *  - Contribution calculation (local formula, no API call)
 *  - Employee registration/deregistration via ZUA/ZWUA KEDU XML */
class PlZusAdapter(config: PlZusConfig) extends SocialProvider:

  protected def postXml(path: String, body: String): String =
    val client  = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    val encoded = Base64.getEncoder.encodeToString(s"${config.login}:${String(config.password)}".getBytes(StandardCharsets.UTF_8))
    val req     = JHttpRequest.newBuilder()
      .uri(URI.create(s"${config.baseUrl}$path"))
      .header("Content-Type", "application/xml; charset=utf-8")
      .header("Authorization", s"Basic $encoded")
      .POST(JHttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
      .build()
    val resp = client.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8))
    resp.statusCode() match
      case s if s >= 200 && s < 300 => resp.body()
      case 429 => throw BureauError.RateLimitError(Some(60))
      case s   => throw BureauError.ApiError(s"ZUS PUE $s", Some(s.toString), Some(s))

  protected def getXml(path: String): String =
    val client  = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
    val encoded = Base64.getEncoder.encodeToString(s"${config.login}:${String(config.password)}".getBytes(StandardCharsets.UTF_8))
    val req     = JHttpRequest.newBuilder()
      .uri(URI.create(s"${config.baseUrl}$path"))
      .header("Authorization", s"Basic $encoded")
      .GET()
      .build()
    val resp = client.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8))
    resp.body()

  def submitDeclaration(decl: ContributionDeclaration)(using ExecutionContext): Future[SubmissionResult] =
    Future {
      val kedu    = buildKeduEnvelope(decl)
      val response = postXml("/services/Dokumenty", kedu)
      parseZusResponse(response)
    }

  def pollDeclarationStatus(ticketId: String)(using ExecutionContext): Future[SubmissionResult] =
    Future {
      val response = getXml(s"/services/Dokumenty/$ticketId")
      parseZusStatusResponse(response, ticketId)
    }

  def getPaymentReference(entity: BusinessEntity, period: YearMonth)(using ExecutionContext): Future[PaymentReference] =
    Future {
      val nip      = entity.requireTaxId(TaxIdType.NIP)
      val nrb      = ZusNrbGenerator.generate(nip, ContributionType.SocialInsurance)
      val dueDay   = if period.getMonthValue == 12 then 20 else 15
      val dueDate  = period.atDay(1).plusMonths(1).withDayOfMonth(dueDay)
      PaymentReference(
        accountNumber = nrb,
        amount        = Money(0, Currency("PLN")),
        dueDate       = dueDate,
        period        = period,
        description   = s"ZUS składki za ${period.getMonth}/${period.getYear}, NIP: $nip",
        metadata      = Map(
          "iban"   -> ZusNrbGenerator.generateIban(nip, ContributionType.SocialInsurance),
          "health" -> ZusNrbGenerator.generateIban(nip, ContributionType.HealthInsurance),
          "fp"     -> ZusNrbGenerator.generateIban(nip, ContributionType.LaborFund),
        ),
      )
    }

  def calculateContributions(params: ContributionParams)(using ExecutionContext): Future[ContributionCalculation] =
    Future.successful(ZusContributionCalculator.calculate(params))

  def registerEmployee(employee: EmployeeRecord)(using ExecutionContext): Future[SubmissionResult] =
    Future {
      val kedu     = buildZuaKedu(employee)
      val response = postXml("/services/Dokumenty", kedu)
      parseZusResponse(response)
    }

  def deregisterEmployee(employee: EmployeeRecord, reason: DeregistrationReason, effectiveDate: LocalDate)
      (using ExecutionContext): Future[SubmissionResult] =
    Future {
      val kedu     = buildZwuaKedu(employee, reason, effectiveDate)
      val response = postXml("/services/Dokumenty", kedu)
      parseZusResponse(response)
    }

  def updateEmployee(employee: EmployeeRecord)(using ExecutionContext): Future[SubmissionResult] =
    Future {
      val kedu     = buildZiuaKedu(employee)
      val response = postXml("/services/Dokumenty", kedu)
      parseZusResponse(response)
    }

  private def buildKeduEnvelope(decl: ContributionDeclaration): String =
    val nip = decl.employer.requireTaxId(TaxIdType.NIP)
    s"""<?xml version="1.0" encoding="UTF-8"?>
<KEDU wersja_schematu="${decl.schemaVersion}" xmlns="http://www.zus.pl/2013/KEDU">
  <Naglowek>
    <NIP>$nip</NIP>
    <TypDokumentu>${decl.declarationType}</TypDokumentu>
    <Okres>${decl.period}</Okres>
  </Naglowek>
  <Dane>
${decl.xmlContent}
  </Dane>
</KEDU>"""

  private def buildZuaKedu(emp: EmployeeRecord): String =
    val nip = emp.employer.requireTaxId(TaxIdType.NIP)
    val pesel = emp.pesel.map(p => s"<PESEL>$p</PESEL>").getOrElse("")
    s"""<?xml version="1.0" encoding="UTF-8"?>
<KEDU wersja_schematu="4-0" xmlns="http://www.zus.pl/2013/KEDU">
  <Naglowek><NIP>$nip</NIP><TypDokumentu>ZUA</TypDokumentu></Naglowek>
  <Dane>
    <ZUA>
      <DaneUbezpieczonego>
        <Imie>${emp.firstName}</Imie>
        <Nazwisko>${emp.lastName}</Nazwisko>
        $pesel
        <DataUrodzenia>${emp.birthDate}</DataUrodzenia>
      </DaneUbezpieczonego>
      <DatyCzlonkostwa>
        <DataObjecia>${emp.startDate}</DataObjecia>
      </DatyCzlonkostwa>
    </ZUA>
  </Dane>
</KEDU>"""

  private def buildZwuaKedu(emp: EmployeeRecord, reason: DeregistrationReason, effectiveDate: LocalDate): String =
    val nip = emp.employer.requireTaxId(TaxIdType.NIP)
    val pesel = emp.pesel.map(p => s"<PESEL>$p</PESEL>").getOrElse("")
    val reasonCode = reason match
      case DeregistrationReason.Termination => "100"
      case DeregistrationReason.Resignation => "200"
      case DeregistrationReason.Retirement  => "500"
      case DeregistrationReason.Death       => "600"
      case DeregistrationReason.Other(c)    => c
    s"""<?xml version="1.0" encoding="UTF-8"?>
<KEDU wersja_schematu="4-0" xmlns="http://www.zus.pl/2013/KEDU">
  <Naglowek><NIP>$nip</NIP><TypDokumentu>ZWUA</TypDokumentu></Naglowek>
  <Dane>
    <ZWUA>
      <DaneUbezpieczonego>
        <Imie>${emp.firstName}</Imie>
        <Nazwisko>${emp.lastName}</Nazwisko>
        $pesel
      </DaneUbezpieczonego>
      <DataWyrejestrowania>${effectiveDate}</DataWyrejestrowania>
      <KodPrzyczynWyrejestrowania>$reasonCode</KodPrzyczynWyrejestrowania>
    </ZWUA>
  </Dane>
</KEDU>"""

  private def buildZiuaKedu(emp: EmployeeRecord): String =
    val nip = emp.employer.requireTaxId(TaxIdType.NIP)
    val pesel = emp.pesel.map(p => s"<PESEL>$p</PESEL>").getOrElse("")
    s"""<?xml version="1.0" encoding="UTF-8"?>
<KEDU wersja_schematu="4-0" xmlns="http://www.zus.pl/2013/KEDU">
  <Naglowek><NIP>$nip</NIP><TypDokumentu>ZIUA</TypDokumentu></Naglowek>
  <Dane>
    <ZIUA>
      <DaneUbezpieczonego>
        <Imie>${emp.firstName}</Imie>
        <Nazwisko>${emp.lastName}</Nazwisko>
        $pesel
      </DaneUbezpieczonego>
    </ZIUA>
  </Dane>
</KEDU>"""

  private[social] def parseZusResponse(xml: String): SubmissionResult =
    val refNum = extractXmlTag(xml, "IdDokumentu")
      .orElse(extractXmlTag(xml, "NumerReferencyjny"))
      .orElse(extractXmlTag(xml, "Id"))
      .getOrElse(s"ZUS-${System.currentTimeMillis()}")
    val errCode = extractXmlTag(xml, "KodBledu").orElse(extractXmlTag(xml, "Blad"))
    val errMsg  = extractXmlTag(xml, "OpisBledu").orElse(extractXmlTag(xml, "Komunikat"))
    if errCode.isDefined then
      SubmissionResult(
        submissionId = refNum,
        status       = SubmissionStatus.Rejected(List(GovError(errCode.get, errMsg.getOrElse("ZUS rejection")))),
        timestamp    = Instant.now(),
      )
    else
      SubmissionResult(
        submissionId = refNum,
        status       = SubmissionStatus.Pending(refNum),
        timestamp    = Instant.now(),
        reference    = Some(refNum),
      )

  private[social] def parseZusStatusResponse(xml: String, ticketId: String): SubmissionResult =
    val statusCode = extractXmlTag(xml, "Status").orElse(extractXmlTag(xml, "KodStatusu")).getOrElse("")
    val status = statusCode.toUpperCase match
      case "OK" | "ZATWIERDZONY" | "200" => SubmissionStatus.Accepted
      case "ODRZUCONY" | "400" =>
        val msg = extractXmlTag(xml, "OpisBledu").getOrElse("ZUS rejected declaration")
        SubmissionStatus.Rejected(List(GovError(statusCode, msg)))
      case "WOCZEKIWANIU" | "PRZETWARZANY" => SubmissionStatus.Processing
      case _ => SubmissionStatus.Pending(ticketId)
    SubmissionResult(
      submissionId = ticketId,
      status       = status,
      timestamp    = Instant.now(),
    )

  private[social] def extractXmlTag(xml: String, tag: String): Option[String] =
    val pat = s"<(?:[^:>]*:)?$tag>([\\s\\S]*?)</(?:[^:>]*:)?$tag>"
    pat.r.findFirstMatchIn(xml).map(_.group(1).trim).filter(_.nonEmpty)
