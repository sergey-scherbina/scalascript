package scalascript.gov.pl.registry

import scalascript.gov.*
import scalascript.gov.registry.*
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.{Duration, LocalDate}

/** GUS REGON BIR1 adapter (REST JSON interface).
 *  Endpoint: `https://wyszukiwarkaregon.stat.gov.pl/`
 *  Auth: API key in `X-API-Key` header (free registration at GUS portal). */
class RegonAdapter(config: PlRegistryConfig):

  protected def postSoap(url: String, soapEnvelope: String, soapAction: String): String =
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
    val req    = JHttpRequest.newBuilder()
      .uri(URI.create(url))
      .header("Content-Type", "text/xml; charset=utf-8")
      .header("SOAPAction",   soapAction)
      .POST(JHttpRequest.BodyPublishers.ofString(soapEnvelope, StandardCharsets.UTF_8))
      .build()
    val resp = client.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8))
    resp.statusCode() match
      case 200 => resp.body()
      case s   => throw BureauError.ApiError(s"REGON SOAP error $s: ${resp.body().take(200)}", Some(s.toString), Some(s))

  def lookupByNip(nip: String): Option[BusinessRecord] =
    val apiKey  = config.regonApiKey.getOrElse("")
    val session = login(apiKey)
    try
      val resultXml = searchByNip(session, nip)
      parseRegonResult(resultXml)
    finally try logout(session) catch case _: Exception => ()

  def lookupByRegon(regon: String): Option[BusinessRecord] =
    val apiKey  = config.regonApiKey.getOrElse("")
    val session = login(apiKey)
    try
      val resultXml = searchByRegon(session, regon)
      parseRegonResult(resultXml)
    finally try logout(session) catch case _: Exception => ()

  private def login(apiKey: String): String =
    val envelope = s"""<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:bir="http://CIS/BIR/PUBL/2014/07">
  <soap:Body>
    <bir:Zaloguj><bir:pKluczUzytkownika>$apiKey</bir:pKluczUzytkownika></bir:Zaloguj>
  </soap:Body>
</soap:Envelope>"""
    val resp = postSoap(config.regonBaseUrl, envelope, "http://CIS/BIR/PUBL/2014/07/IUslugaBIRzewnPubl/Zaloguj")
    extractSoapValue(resp, "ZalogujResult")
      .filter(_.nonEmpty)
      .getOrElse(throw BureauError.AuthenticationError("REGON login failed — no session token"))

  private def logout(session: String): Unit =
    val envelope = s"""<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:bir="http://CIS/BIR/PUBL/2014/07">
  <soap:Body>
    <bir:Wyloguj><bir:pIdentyfikatorSesji>$session</bir:pIdentyfikatorSesji></bir:Wyloguj>
  </soap:Body>
</soap:Envelope>"""
    val _ = postSoap(config.regonBaseUrl, envelope, "http://CIS/BIR/PUBL/2014/07/IUslugaBIRzewnPubl/Wyloguj")

  private def searchByNip(session: String, nip: String): String =
    val parms = s"<dat:Nip>$nip</dat:Nip>"
    searchSoap(session, parms)

  private def searchByRegon(session: String, regon: String): String =
    val parms = if regon.length == 9 then s"<dat:Regon>$regon</dat:Regon>"
                else s"<dat:Regon14>$regon</dat:Regon14>"
    searchSoap(session, parms)

  private def searchSoap(session: String, paramXml: String): String =
    val envelope = s"""<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:bir="http://CIS/BIR/PUBL/2014/07"
               xmlns:dat="http://CIS/BIR/PUBL/2014/07/DataContract">
  <soap:Header>
    <bir:Sesja><bir:Identyfikator>$session</bir:Identyfikator></bir:Sesja>
  </soap:Header>
  <soap:Body>
    <bir:DaneSzukajPodmioty>
      <bir:pParametryWyszukiwania>$paramXml</bir:pParametryWyszukiwania>
    </bir:DaneSzukajPodmioty>
  </soap:Body>
</soap:Envelope>"""
    postSoap(config.regonBaseUrl, envelope, "http://CIS/BIR/PUBL/2014/07/IUslugaBIRzewnPubl/DaneSzukajPodmioty")

  private def parseRegonResult(soapXml: String): Option[BusinessRecord] =
    val data = extractSoapValue(soapXml, "DaneSzukajPodmiotyResult").getOrElse("")
    if data.contains("<ErrorCode>") || data.isEmpty || data == "0" then return None
    val name   = extractXmlTag(data, "Nazwa").getOrElse("")
    val nip    = extractXmlTag(data, "Nip").orElse(extractXmlTag(data, "NIP"))
    val regon  = extractXmlTag(data, "Regon").orElse(extractXmlTag(data, "REGON"))
    val krs    = extractXmlTag(data, "Krs").orElse(extractXmlTag(data, "KRS"))
    val city   = extractXmlTag(data, "MiejscowoscSiedziby").orElse(extractXmlTag(data, "Miejscowosc")).getOrElse("")
    val postal = extractXmlTag(data, "KodPocztowy").getOrElse("")
    val street = extractXmlTag(data, "Ulica").map(u => extractXmlTag(data, "NrNieruchomosci").map(n => s"$u $n").getOrElse(u)).getOrElse("")
    val form   = extractXmlTag(data, "Form").orElse(extractXmlTag(data, "formaWlasnosci"))
    val regDate = extractXmlTag(data, "DataPowstania").flatMap(d => scala.util.Try(LocalDate.parse(d.take(10))).toOption)
    val taxIds = List(
      nip.map(v => TaxIdentifier(TaxIdType.NIP, TaxId(v), CountryCode.PL)),
      regon.map(v => TaxIdentifier(TaxIdType.REGON, TaxId(v), CountryCode.PL)),
      krs.map(v => TaxIdentifier(TaxIdType.KRS, TaxId(v), CountryCode.PL)),
    ).flatten
    if name.isEmpty && taxIds.isEmpty then return None
    Some(BusinessRecord(
      name         = name,
      legalForm    = form.map(toLegalForm),
      taxIds       = taxIds,
      address      = if city.nonEmpty then Some(Address(line1 = street, city = city, postalCode = postal, country = CountryCode.PL)) else None,
      status       = RegistrationStatus.Active,
      registeredAt = regDate,
    ))

  private def toLegalForm(code: String): LegalForm = code match
    case c if c.contains("SPÓŁKA Z OGRANICZONĄ") || c == "sp. z o.o." => LegalForm.LimitedLiabilityCompany
    case c if c.contains("SPÓŁKA AKCYJNA")        || c == "S.A."       => LegalForm.JointStockCompany
    case c if c.contains("JAWNA")                                       => LegalForm.GeneralPartnership
    case c if c.contains("KOMANDYTOWO-AKCYJNA")                         => LegalForm.LimitedJointStockPartnership
    case c if c.contains("KOMANDYTOWA")                                 => LegalForm.LimitedPartnership
    case c if c.contains("PARTNERSKA")                                  => LegalForm.ProfessionalPartnership
    case c if c.contains("SPÓŁDZIELNIA")                                => LegalForm.Cooperative
    case c if c.contains("FUNDACJA")                                    => LegalForm.Foundation
    case c if c.contains("STOWARZYSZENIE")                              => LegalForm.Association
    case c if c.contains("OSOBA FIZYCZNA") || c.contains("JDG")        => LegalForm.SoleProprietor
    case _                                                              => LegalForm.Other(code)

  private[registry] def extractSoapValue(xml: String, tag: String): Option[String] =
    val pat = s"<(?:[^:>]*:)?$tag>([\\s\\S]*?)</(?:[^:>]*:)?$tag>"
    pat.r.findFirstMatchIn(xml).map(_.group(1).trim)

  private def extractXmlTag(xml: String, tag: String): Option[String] =
    extractSoapValue(xml, tag).filter(_.nonEmpty)
