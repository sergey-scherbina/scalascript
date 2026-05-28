package scalascript.gov.pl.registry

import scalascript.gov.*
import scalascript.gov.registry.*
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant, LocalDate}

/** Ministerstwo Finansów "Biała Lista" (White List) VAT payer registry adapter.
 *  Endpoint: `https://wl-api.mf.gov.pl/api/search/nip/{nip}?date=YYYY-MM-DD`
 *  Auth: API key in `Authorization: Bearer <key>` header (optional for low-volume). */
class BialaListaAdapter(config: PlRegistryConfig):

  protected def getJson(url: String): String =
    val client  = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    val builder = JHttpRequest.newBuilder().uri(URI.create(url)).GET()
    config.bialaListaApiKey.foreach(k => builder.header("Authorization", s"Bearer $k"))
    val resp = client.send(builder.build(), BodyHandlers.ofString(StandardCharsets.UTF_8))
    resp.statusCode() match
      case 404 => throw NotFoundException(s"Biała Lista: NIP not found at $url")
      case 429 =>
        val retryAfter = resp.headers().firstValueAsLong("Retry-After").orElse(-1L)
        throw BureauError.RateLimitError(Some(if retryAfter > 0 then retryAfter.toInt else 60))
      case s if s >= 400 =>
        throw BureauError.ApiError(s"Biała Lista error $s", Some(s.toString), Some(s))
      case _ => resp.body()

  def checkVatStatus(nip: String, date: LocalDate = LocalDate.now()): VatPayerStatus =
    val url = s"${config.bialaListaBaseUrl}/api/search/nip/$nip?date=$date"
    try
      val json = getJson(url)
      parseVatStatus(nip, json)
    catch
      case _: NotFoundException =>
        VatPayerStatus(
          active       = false,
          id           = TaxIdentifier(TaxIdType.NIP, TaxId(nip), CountryCode.PL),
          name         = None,
          bankAccounts = Nil,
          checkedAt    = Instant.now(),
        )

  def lookup(nip: String): Option[BusinessRecord] =
    val url = s"${config.bialaListaBaseUrl}/api/search/nip/$nip?date=${LocalDate.now()}"
    try
      val json = getJson(url)
      parseBusinessRecord(nip, json)
    catch
      case _: NotFoundException => None

  private def parseVatStatus(nip: String, json: String): VatPayerStatus =
    val statusVal = extractField(json, "statusVat")
    val active    = statusVal.exists(_.equalsIgnoreCase("Czynny"))
    val name     = extractField(json, "name").orElse(extractField(json, "firma"))
    val accounts = extractBankAccounts(json)
    VatPayerStatus(
      active       = active,
      id           = TaxIdentifier(TaxIdType.NIP, TaxId(nip), CountryCode.PL),
      name         = name,
      bankAccounts = accounts,
      checkedAt    = Instant.now(),
    )

  private def parseBusinessRecord(nip: String, json: String): Option[BusinessRecord] =
    val subject = extractObject(json, "subject").orElse(extractObject(json, "result.subject"))
    val src     = subject.getOrElse(json)
    val name    = extractField(src, "name").orElse(extractField(src, "firma"))
    if name.isEmpty then return None
    val accounts   = extractBankAccounts(json)
    val street     = extractField(src, "workingAddress").orElse(extractField(src, "residenceAddress")).getOrElse("")
    val regon      = extractField(src, "regon")
    val krs        = extractField(src, "krs")
    val taxIds = List(
      Some(TaxIdentifier(TaxIdType.NIP, TaxId(nip), CountryCode.PL)),
      regon.map(v => TaxIdentifier(TaxIdType.REGON, TaxId(v), CountryCode.PL)),
      krs.map(v => TaxIdentifier(TaxIdType.KRS, TaxId(v), CountryCode.PL)),
    ).flatten
    val meta = if accounts.nonEmpty then Map("bankAccounts" -> accounts.mkString(",")) else Map.empty
    Some(BusinessRecord(
      name         = name.getOrElse(""),
      legalForm    = None,
      taxIds       = taxIds,
      address      = if street.nonEmpty then Some(Address(line1 = street, city = "", postalCode = "", country = CountryCode.PL)) else None,
      status       = RegistrationStatus.Active,
      registeredAt = None,
      metadata     = meta,
    ))

  private[registry] def extractField(json: String, key: String): Option[String] =
    val pattern = s"""\"$key\"\\s*:\\s*\"([^\"]*)\""""
    pattern.r.findFirstMatchIn(json).map(_.group(1)).filter(_.nonEmpty)

  private def extractObject(json: String, path: String): Option[String] =
    val key = path.split('.').last
    val pat = s""""$key"\\s*:\\s*(\\{[^{}]*\\})"""
    pat.r.findFirstMatchIn(json).map(_.group(1))

  private def extractBankAccounts(json: String): List[String] =
    val pat = """"accountNumbers"\s*:\s*\[([^\]]*)\]""".r
    pat.findFirstMatchIn(json)
      .map(_.group(1))
      .map(arr => """\"([^\"]+)\"""".r.findAllMatchIn(arr).map(_.group(1)).toList)
      .getOrElse(Nil)
      .filter(_.nonEmpty)
