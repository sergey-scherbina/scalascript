package scalascript.gov.pl.registry

import scalascript.gov.*
import scalascript.gov.registry.*
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.{Duration, LocalDate}

/** KRS (Krajowy Rejestr Sądowy) REST adapter.
 *  Endpoint: `https://api-krs.ms.gov.pl/api/krs/` — public, no auth required.
 *  Covers companies (sp. z o.o., S.A.) and NGOs. */
class KrsAdapter(config: PlRegistryConfig):

  protected def getJson(url: String): String =
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    val req    = JHttpRequest.newBuilder()
      .uri(URI.create(url))
      .header("Accept", "application/json")
      .GET()
      .build()
    val resp = client.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8))
    resp.statusCode() match
      case 404 => throw NotFoundException(s"KRS: not found at $url")
      case s if s >= 400 =>
        throw BureauError.ApiError(s"KRS API error $s", Some(s.toString), Some(s))
      case _ => resp.body()

  def lookupByKrs(krs: String): Option[BusinessRecord] =
    val url = s"${config.krsBaseUrl}/podmiot/$krs"
    try
      val json = getJson(url)
      parseKrsEntity(json)
    catch
      case _: NotFoundException => None

  def searchByName(name: String): List[BusinessRecord] =
    val encoded = java.net.URLEncoder.encode(name, "UTF-8")
    val url = s"${config.krsBaseUrl}/podmiot?nazwa=$encoded&strona=1&maxWynikow=20"
    try
      val json = getJson(url)
      parseKrsSearchResult(json)
    catch
      case _: NotFoundException => Nil

  def getDetails(krs: String): Option[RegistrationDetails] =
    val url = s"${config.krsBaseUrl}/podmiot/$krs"
    try
      val json = getJson(url)
      parseKrsDetails(json)
    catch
      case _: NotFoundException => None

  private def parseKrsEntity(json: String): Option[BusinessRecord] =
    val name    = extractField(json, "nazwa").orElse(extractField(json, "name"))
    if name.isEmpty then return None
    val krs     = extractField(json, "numerKrs").orElse(extractField(json, "krs"))
    val nip     = extractField(json, "nip").orElse(extractField(json, "NIP"))
    val regon   = extractField(json, "regon").orElse(extractField(json, "REGON"))
    val city    = extractField(json, "miejscowosc").orElse(extractField(json, "miasto")).getOrElse("")
    val postal  = extractField(json, "kodPocztowy").getOrElse("")
    val street  = extractField(json, "ulica").orElse(extractField(json, "adres")).getOrElse("")
    val formStr = extractField(json, "forma").orElse(extractField(json, "formaPrawna"))
    val regDate = extractField(json, "dataRejestracji").orElse(extractField(json, "dataWpisu"))
      .flatMap(d => scala.util.Try(LocalDate.parse(d.take(10))).toOption)
    val statusStr = extractField(json, "status").orElse(extractField(json, "stan"))
    val taxIds  = List(
      krs.map(v => TaxIdentifier(TaxIdType.KRS, TaxId(v), CountryCode.PL)),
      nip.map(v => TaxIdentifier(TaxIdType.NIP, TaxId(v), CountryCode.PL)),
      regon.map(v => TaxIdentifier(TaxIdType.REGON, TaxId(v), CountryCode.PL)),
    ).flatten
    Some(BusinessRecord(
      name         = name.getOrElse(""),
      legalForm    = formStr.map(toLegalForm),
      taxIds       = taxIds,
      address      = if city.nonEmpty || street.nonEmpty then Some(Address(line1 = street, city = city, postalCode = postal, country = CountryCode.PL)) else None,
      status       = statusStr.map(toStatus).getOrElse(RegistrationStatus.Active),
      registeredAt = regDate,
    ))

  private def parseKrsSearchResult(json: String): List[BusinessRecord] =
    val itemPattern = """\{[^{}]*"numerKrs"[^{}]*\}""".r
    val objs        = itemPattern.findAllIn(json).toList
    if objs.nonEmpty then objs.flatMap(parseKrsEntity)
    else
      val widePattern = """\{[^{}]{20,}\}""".r
      widePattern.findAllIn(json).toList.flatMap(parseKrsEntity)

  private def parseKrsDetails(json: String): Option[RegistrationDetails] =
    parseKrsEntity(json).map { record =>
      val directors    = extractArray(json, "czlonkowie").orElse(extractArray(json, "reprezentacja")).getOrElse(Nil)
      val shareholders = extractArray(json, "wspolnicy").orElse(extractArray(json, "akcjonariusze")).getOrElse(Nil)
      val activities   = extractArray(json, "pkd").orElse(extractArray(json, "przedmiotDzialalnosci")).getOrElse(Nil)
      val capitalStr   = extractField(json, "kapitalZakladowy").orElse(extractField(json, "kapital"))
      RegistrationDetails(
        record       = record,
        directors    = directors,
        shareholders = shareholders,
        activities   = activities,
        capital      = None,
        rawData      = capitalStr.map("capital" -> _).toMap,
      )
    }

  private def toLegalForm(s: String): LegalForm = s.toUpperCase match
    case f if f.contains("SP. Z O.O.") || f.contains("SPÓŁKA Z OGRANICZONĄ") => LegalForm.LimitedLiabilityCompany
    case f if f.contains("S.A.") || f.contains("SPÓŁKA AKCYJNA")              => LegalForm.JointStockCompany
    case f if f.contains("JAWNA")                                              => LegalForm.GeneralPartnership
    case f if f.contains("KOMANDYTOWO-AKCYJNA")                                => LegalForm.LimitedJointStockPartnership
    case f if f.contains("KOMANDYTOWA")                                        => LegalForm.LimitedPartnership
    case f if f.contains("PARTNERSKA")                                         => LegalForm.ProfessionalPartnership
    case f if f.contains("SPÓŁDZIELNIA")                                       => LegalForm.Cooperative
    case f if f.contains("FUNDACJA")                                           => LegalForm.Foundation
    case f if f.contains("STOWARZYSZENIE")                                     => LegalForm.Association
    case f if f.contains("PROSTA SPÓŁKA AKCYJNA") || f.contains("P.S.A.")     => LegalForm.JointStockCompany
    case other                                                                 => LegalForm.Other(other)

  private def toStatus(s: String): RegistrationStatus = s.toLowerCase match
    case "aktywny" | "active" | "wpisany"     => RegistrationStatus.Active
    case "zawieszony" | "suspended"            => RegistrationStatus.Suspended
    case "w likwidacji" | "liquidation"        => RegistrationStatus.Liquidation
    case "wykreślony" | "deleted" | "dissolved" => RegistrationStatus.Dissolved
    case _                                     => RegistrationStatus.Unknown

  private[registry] def extractField(json: String, key: String): Option[String] =
    val pattern = s"""\"$key\"\\s*:\\s*\"([^\"]*)\""""
    pattern.r.findFirstMatchIn(json).map(_.group(1)).filter(_.nonEmpty)

  private def extractArray(json: String, key: String): Option[List[String]] =
    val pat = s""""$key"\\s*:\\s*\\[([^\\]]*)\\]""".r
    pat.findFirstMatchIn(json).map { m =>
      """\"([^\"]+)\"""".r.findAllMatchIn(m.group(1)).map(_.group(1)).toList
    }
