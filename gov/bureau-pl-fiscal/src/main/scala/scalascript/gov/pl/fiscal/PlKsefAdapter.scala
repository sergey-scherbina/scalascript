package scalascript.gov.pl.fiscal

import scalascript.gov.*
import scalascript.gov.fiscal.*
import scalascript.gov.providers.{FiscalProvider, VatVerificationResult}
import scalascript.gov.signing.{SigningProvider, SignatureFormat}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant, LocalDate}
import java.util.Base64
import scala.concurrent.{Future, ExecutionContext}

/** KSeF (Krajowy System e-Faktur) REST adapter.
 *
 *  Auth flow: POST /online/Session/AuthorisationChallenge → sign challenge with QES
 *  → POST /online/Session/Authorisation → session token.
 *
 *  Session tokens are cached in `KsefSessionStore` for 24 h.
 *  Injectable HTTP methods allow tests to mock all API calls. */
class PlKsefAdapter(config: PlKsefConfig, signing: SigningProvider, session: KsefSessionStore = KsefSessionStore()) extends FiscalProvider:

  protected def postJson(path: String, body: String, token: Option[String] = None): String =
    val client  = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
    val builder = JHttpRequest.newBuilder()
      .uri(URI.create(s"${config.baseUrl}$path"))
      .header("Content-Type", "application/json")
      .header("Accept",       "application/json")
    token.foreach(t => builder.header("SessionToken", t))
    builder.POST(JHttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
    val resp = client.send(builder.build(), BodyHandlers.ofString(StandardCharsets.UTF_8))
    handleResponse(resp.statusCode(), resp.body(), path)

  protected def getJson(path: String, token: Option[String] = None): String =
    val client  = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
    val builder = JHttpRequest.newBuilder()
      .uri(URI.create(s"${config.baseUrl}$path"))
      .header("Accept", "application/json")
    token.foreach(t => builder.header("SessionToken", t))
    builder.GET()
    val resp = client.send(builder.build(), BodyHandlers.ofString(StandardCharsets.UTF_8))
    handleResponse(resp.statusCode(), resp.body(), path)

  protected def deleteJson(path: String, token: Option[String] = None): String =
    val client  = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
    val builder = JHttpRequest.newBuilder()
      .uri(URI.create(s"${config.baseUrl}$path"))
      .header("Accept", "application/json")
    token.foreach(t => builder.header("SessionToken", t))
    builder.DELETE()
    val resp = client.send(builder.build(), BodyHandlers.ofString(StandardCharsets.UTF_8))
    handleResponse(resp.statusCode(), resp.body(), path)

  private def handleResponse(status: Int, body: String, path: String): String = status match
    case s if s >= 200 && s < 300 => body
    case 401 =>
      session.invalidate()
      throw BureauError.AuthenticationError(s"KSeF 401 Unauthorized: $path")
    case 429 =>
      throw BureauError.RateLimitError(Some(60))
    case 503 =>
      throw BureauError.ServiceUnavailable(s"KSeF 503 Service Unavailable: $path")
    case s =>
      throw BureauError.ApiError(s"KSeF $s: $path — ${body.take(200)}", Some(s.toString), Some(s))

  private def ensureSession()(using ExecutionContext): String =
    session.get().getOrElse {
      val token = authenticate()
      session.put(token)
      token
    }

  private def authenticate()(using ExecutionContext): String =
    val challengeBody = s"""{"contextIdentifier":{"type":"onip","identifier":"${config.nip}"}}"""
    val challengeResp = postJson("/online/Session/AuthorisationChallenge", challengeBody)
    val challenge     = extractJsonField(challengeResp, "challenge")
      .getOrElse(throw BureauError.AuthenticationError("KSeF: no challenge in response"))
    val challengeBytes = challenge.getBytes(StandardCharsets.UTF_8)
    val signedDoc = scala.concurrent.Await.result(
      signing.sign(challengeBytes, SignatureFormat.XAdES),
      scala.concurrent.duration.Duration(30, "seconds")
    )
    val signedB64  = Base64.getEncoder.encodeToString(signedDoc.signature)
    val authBody   = s"""{"contextIdentifier":{"type":"onip","identifier":"${config.nip}"},"authorisationChallenge":"$challenge","signature":{"type":"xades","encoding":"Base64","algorithm":"SHA256withRSA","value":"$signedB64"}}"""
    val authResp   = postJson("/online/Session/Authorisation", authBody)
    extractJsonField(authResp, "sessionToken")
      .getOrElse(throw BureauError.AuthenticationError("KSeF: no sessionToken in auth response"))

  def submitInvoice(invoice: FiscalInvoice)(using ExecutionContext): Future[InvoiceSubmissionResult] =
    Future {
      val token   = ensureSession()
      val xml     = KsefXmlBuilder.buildFaVatXml(invoice)
      val xmlB64  = Base64.getEncoder.encodeToString(xml.getBytes(StandardCharsets.UTF_8))
      val body    = s"""{"invoiceHash":{"hashSHA":{"algorithm":"SHA-256","encoding":"Base64","value":"${sha256B64(xml)}"}},"invoicePayload":{"type":"plain","invoiceBody":"$xmlB64"}}"""
      val resp    = postJson("/online/Invoice/Send", body, Some(token))
      val ticketId = extractJsonField(resp, "elementReferenceNumber")
        .getOrElse(extractJsonField(resp, "referenceNumber")
          .getOrElse(throw BureauError.ApiError("KSeF: no reference in invoice send response")))
      InvoiceSubmissionResult(
        submissionResult = SubmissionResult(
          submissionId = ticketId,
          status       = SubmissionStatus.Pending(ticketId),
          timestamp    = Instant.now(),
          reference    = Some(ticketId),
        ),
        invoiceId = None,
      )
    }

  def pollInvoiceStatus(ticketId: String)(using ExecutionContext): Future[InvoiceSubmissionResult] =
    Future {
      val token   = ensureSession()
      val resp    = getJson(s"/online/Invoice/Status/$ticketId", Some(token))
      val status  = extractJsonField(resp, "invoiceStatus").orElse(extractJsonField(resp, "processingCode")).getOrElse("UNKNOWN")
      val invoiceId = extractJsonField(resp, "ksefReferenceNumber")
      val submissionStatus = parseKsefStatus(status, ticketId, resp)
      InvoiceSubmissionResult(
        submissionResult = SubmissionResult(
          submissionId = ticketId,
          status       = submissionStatus,
          timestamp    = Instant.now(),
          reference    = invoiceId.orElse(Some(ticketId)),
        ),
        invoiceId = invoiceId,
      )
    }

  def fetchInvoice(id: String)(using ExecutionContext): Future[FiscalInvoice] =
    Future {
      val token   = ensureSession()
      val resp    = getJson(s"/online/Invoice/Get/$id", Some(token))
      val xmlB64  = extractJsonField(resp, "invoiceData")
        .getOrElse(throw BureauError.ApiError(s"KSeF: no invoiceData for $id"))
      val xml     = String(Base64.getDecoder.decode(xmlB64), StandardCharsets.UTF_8)
      parseFaVatInvoice(xml, id)
    }

  def queryInvoices(filter: InvoiceFilter)(using ExecutionContext): Future[List[InvoiceRef]] =
    Future {
      val token  = ensureSession()
      val body   = buildQueryBody(filter)
      val resp   = postJson("/online/Query/Invoice/sync", body, Some(token))
      parseQueryResult(resp)
    }

  @annotation.nowarn("msg=unused")
  def submitDeclaration(decl: TaxDeclaration)(using ExecutionContext): Future[SubmissionResult] =
    Future.failed(BureauError.UnsupportedOperation(CountryCode.PL, GovDomain.Fiscal, "submitDeclaration — use PlDeclarationAdapter"))

  @annotation.nowarn("msg=unused")
  def pollDeclarationStatus(ticketId: String)(using ExecutionContext): Future[SubmissionResult] =
    Future.failed(BureauError.UnsupportedOperation(CountryCode.PL, GovDomain.Fiscal, "pollDeclarationStatus — use PlDeclarationAdapter"))

  @annotation.nowarn("msg=unused")
  def submitAuditFile(file: AuditFile)(using ExecutionContext): Future[SubmissionResult] =
    Future.failed(BureauError.UnsupportedOperation(CountryCode.PL, GovDomain.Fiscal, "submitAuditFile — use PlDeclarationAdapter"))

  @annotation.nowarn("msg=unused")
  def pollAuditFileStatus(ticketId: String)(using ExecutionContext): Future[SubmissionResult] =
    Future.failed(BureauError.UnsupportedOperation(CountryCode.PL, GovDomain.Fiscal, "pollAuditFileStatus — use PlDeclarationAdapter"))

  @annotation.nowarn("msg=unused")
  def verifyVatNumber(id: TaxIdentifier)(using ExecutionContext): Future[VatVerificationResult] =
    Future.failed(BureauError.UnsupportedOperation(CountryCode.PL, GovDomain.Fiscal, "verifyVatNumber — use PlRegistryProvider"))

  def terminateSession()(using ExecutionContext): Future[Unit] =
    Future {
      session.get().foreach { token =>
        try deleteJson("/online/Session/Terminate", Some(token))
        catch case _: Exception => ()
        finally session.invalidate()
      }
    }

  private def parseKsefStatus(status: String, ticketId: String, fullResp: String): SubmissionStatus =
    status.toUpperCase match
      case "200" | "RECEIVED" | "ACCEPTED"  => SubmissionStatus.Accepted
      case "100" | "OCR" | "PROCESSING" | "RECEIVED_BY_WORKER" => SubmissionStatus.Processing
      case s if s.startsWith("4") || s == "REJECTED" =>
        val msg = extractJsonField(fullResp, "exceptionDetailList").orElse(extractJsonField(fullResp, "message")).getOrElse(s)
        SubmissionStatus.Rejected(List(GovError(s, msg)))
      case _ =>
        SubmissionStatus.Pending(ticketId)

  private def buildQueryBody(filter: InvoiceFilter): String =
    val from = filter.dateFrom.getOrElse(LocalDate.now().minusDays(30)).toString
    val to   = filter.dateTo.getOrElse(LocalDate.now()).toString
    s"""{"queryCriteria":{"subjectType":"subject1","dateRange":{"startDate":"$from","endDate":"$to"}},"pageSize":${filter.limit},"pageOffset":0}"""

  private def parseQueryResult(json: String): List[InvoiceRef] =
    val idPat   = """"ksefReferenceNumber"\s*:\s*"([^"]+)"""".r
    val datePat = """"invoiceDate"\s*:\s*"([^"]+)"""".r
    val ids     = idPat.findAllMatchIn(json).map(_.group(1)).toList
    val dates   = datePat.findAllMatchIn(json).map(_.group(1)).toList
    ids.zipAll(dates, "", "").map { (id, dateStr) =>
      val date = scala.util.Try(LocalDate.parse(dateStr.take(10))).getOrElse(LocalDate.now())
      InvoiceRef(id, date, scalascript.payments.money.Money(0, scalascript.payments.money.Currency.USD), "ACCEPTED")
    }

  private def parseFaVatInvoice(xml: String, id: String): FiscalInvoice =
    val fields = KsefXmlBuilder.parseFaVatXml(xml)
    val date   = scala.util.Try(LocalDate.parse(fields.getOrElse("P_1", "").take(10))).getOrElse(LocalDate.now())
    val number = fields.getOrElse("P_2", id)
    val currency = scalascript.payments.money.Currency(fields.getOrElse("KodWaluty", "PLN"))
    FiscalInvoice(
      invoiceNumber = number,
      issueDate     = date,
      seller        = emptyEntity(fields.getOrElse("PelnaNazwa", ""), fields.getOrElse("NIP", "")),
      buyer         = emptyEntity("", ""),
      lines         = Nil,
      taxSummary    = Nil,
      totalNet      = scalascript.payments.money.Money(0, currency),
      totalTax      = scalascript.payments.money.Money(0, currency),
      totalGross    = scalascript.payments.money.Money(parseCents(fields.getOrElse("P_15", "0")), currency),
      currency      = currency,
      metadata      = Map("ksefId" -> id),
    )

  private def emptyEntity(name: String, nip: String): BusinessEntity =
    BusinessEntity(
      name      = name,
      legalForm = LegalForm.Other(""),
      country   = CountryCode.PL,
      taxIds    = if nip.nonEmpty then List(TaxIdentifier(TaxIdType.NIP, TaxId(nip), CountryCode.PL)) else Nil,
      address   = Address("", None, "", "", CountryCode.PL),
    )

  private def parseCents(s: String): Long =
    scala.util.Try {
      val clean = s.replace(",", ".").trim
      (BigDecimal(clean) * 100).toLong
    }.getOrElse(0L)

  private[fiscal] def extractJsonField(json: String, key: String): Option[String] =
    val pattern = s"""\"$key\"\\s*:\\s*\"([^\"]*)\""""
    pattern.r.findFirstMatchIn(json).map(_.group(1)).filter(_.nonEmpty)

  private def sha256B64(input: String): String =
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    Base64.getEncoder.encodeToString(digest.digest(input.getBytes(StandardCharsets.UTF_8)))
