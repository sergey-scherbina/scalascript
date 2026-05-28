package scalascript.gov.pl.registry

import scalascript.gov.*
import scalascript.gov.registry.*
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.{Duration, LocalDate}

/** CEIDG (Centralna Ewidencja i Informacja o Działalności Gospodarczej) REST adapter.
 *  Covers sole proprietors (JDG) registered in Poland.
 *  Endpoint: GET `<base>/SearchData?...` — public, no auth required.
 *  Query by NIP or PESEL. */
class CeidgAdapter(config: PlRegistryConfig):

  protected def getJson(url: String, apiKey: Option[String]): String =
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    val builder = JHttpRequest.newBuilder().uri(URI.create(url)).GET()
    apiKey.foreach(k => builder.header("Authorization", s"Bearer $k"))
    val resp = client.send(builder.build(), BodyHandlers.ofString(StandardCharsets.UTF_8))
    resp.statusCode() match
      case 404 => throw NotFoundException(s"CEIDG: not found at $url")
      case s if s >= 400 =>
        throw BureauError.ApiError(s"CEIDG API error $s", Some(s.toString), Some(s))
      case _ => resp.body()

  def lookupByNip(nip: String): Option[BusinessRecord] =
    val url = s"${config.ceidgBaseUrl}/SearchData?nip=$nip"
    try
      val json = getJson(url, config.ceidgApiKey)
      parseCeidgJson(json)
    catch
      case _: NotFoundException => None

  def lookupByPesel(pesel: String): Option[BusinessRecord] =
    val url = s"${config.ceidgBaseUrl}/SearchData?pesel=$pesel"
    try
      val json = getJson(url, config.ceidgApiKey)
      parseCeidgJson(json)
    catch
      case _: NotFoundException => None

  def search(query: String): List[BusinessRecord] =
    val encoded = java.net.URLEncoder.encode(query, "UTF-8")
    val url = s"${config.ceidgBaseUrl}/SearchData?name=$encoded"
    try
      val json = getJson(url, config.ceidgApiKey)
      parseCeidgSearchJson(json)
    catch
      case _: NotFoundException => Nil

  private def parseCeidgJson(json: String): Option[BusinessRecord] =
    if json.contains("\"status\":\"not_found\"") || json.trim == "{}" || json.trim == "[]" then
      return None
    val name    = extractField(json, "nazwa").getOrElse("")
    val nip     = extractField(json, "nip")
    val pesel   = extractField(json, "pesel")
    val street  = extractField(json, "ulica").getOrElse("")
    val city    = extractField(json, "miasto").getOrElse("")
    val postal  = extractField(json, "kodPocztowy").getOrElse("")
    val status  = extractField(json, "status").map(toRegistrationStatus).getOrElse(RegistrationStatus.Unknown)
    val regDate = extractField(json, "dataRozpoczeciaDzialalnosci").flatMap(parseDate)
    val taxIds  = List(
      nip.map(v => TaxIdentifier(TaxIdType.NIP, TaxId(v), CountryCode.PL)),
      pesel.map(v => TaxIdentifier(TaxIdType.PESEL, TaxId(v), CountryCode.PL)),
    ).flatten
    Some(BusinessRecord(
      name         = name,
      legalForm    = Some(LegalForm.SoleProprietor),
      taxIds       = taxIds,
      address      = Some(Address(line1 = street, city = city, postalCode = postal, country = CountryCode.PL)),
      status       = status,
      registeredAt = regDate,
    ))

  private def parseCeidgSearchJson(json: String): List[BusinessRecord] =
    if json.trim.startsWith("{") then
      parseCeidgJson(json).toList
    else
      val items = splitJsonArray(json)
      items.flatMap(item => parseCeidgJson(item))

  private def toRegistrationStatus(s: String): RegistrationStatus = s.toLowerCase match
    case "active" | "aktywny" | "aktywna" => RegistrationStatus.Active
    case "suspended" | "zawieszona"        => RegistrationStatus.Suspended
    case "deleted" | "wykreslona"          => RegistrationStatus.Dissolved
    case _                                 => RegistrationStatus.Unknown

  private[registry] def extractField(json: String, key: String): Option[String] =
    val pattern = s"""\"$key\"\\s*:\\s*\"([^\"]*)\""""
    pattern.r.findFirstMatchIn(json).map(_.group(1)).filter(_.nonEmpty)

  private def parseDate(s: String): Option[LocalDate] =
    scala.util.Try(LocalDate.parse(s.take(10))).toOption

  private def splitJsonArray(json: String): List[String] =
    val trimmed = json.trim
    if !trimmed.startsWith("[") then return List(trimmed)
    var depth   = 0
    var start   = -1
    val items   = collection.mutable.ListBuffer.empty[String]
    trimmed.zipWithIndex.foreach { (ch, i) =>
      ch match
        case '{' =>
          if depth == 0 then start = i
          depth += 1
        case '}' =>
          depth -= 1
          if depth == 0 && start >= 0 then
            items += trimmed.substring(start, i + 1)
            start = -1
        case _ =>
    }
    items.toList

private case class NotFoundException(msg: String) extends Exception(msg)
