package scalascript.gov.pl.fiscal

import scalascript.gov.*
import scalascript.gov.fiscal.*
import scalascript.gov.providers.{FiscalProvider, VatVerificationResult}
import scalascript.gov.signing.{SigningProvider, SignatureFormat}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}
import java.util.Base64
import scala.concurrent.{Future, ExecutionContext}

/** Polish e-Deklaracje / JPK declaration and audit-file adapter.
 *
 *  Handles:
 *  - JPK_VAT7M / JPK_VAT7K — monthly and quarterly VAT audit
 *  - JPK_FA — sales invoice audit
 *  - CIT-8 / PIT-36 / PIT-36L — income tax declarations via e-Deklaracje SOAP
 *
 *  Endpoint: `https://e-deklaracje.mf.gov.pl/rejestracja/`
 *  Auth: QES signature mandatory (signs the full XML document).
 *
 *  Polling: submission returns a `Pending(referenceNumber)`;
 *  `pollDeclarationStatus` queries via `GET /statusdek?id=<ref>`. */
class PlDeclarationAdapter(config: PlDeclarationConfig, signing: SigningProvider) extends FiscalProvider:

  protected def postSoap(path: String, envelope: String): String =
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    val req    = JHttpRequest.newBuilder()
      .uri(URI.create(s"${config.baseUrl}$path"))
      .header("Content-Type", "text/xml; charset=utf-8")
      .header("SOAPAction",   "")
      .POST(JHttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8))
      .build()
    val resp = client.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8))
    resp.statusCode() match
      case s if s >= 200 && s < 300 => resp.body()
      case 429 => throw BureauError.RateLimitError(Some(60))
      case s   => throw BureauError.ApiError(s"e-Deklaracje SOAP $s: ${resp.body().take(200)}", Some(s.toString), Some(s))

  protected def getHttp(path: String): String =
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
    val req    = JHttpRequest.newBuilder()
      .uri(URI.create(s"${config.baseUrl}$path"))
      .GET()
      .build()
    val resp = client.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8))
    resp.statusCode() match
      case s if s >= 200 && s < 300 => resp.body()
      case s => throw BureauError.ApiError(s"e-Deklaracje GET $s: $path", Some(s.toString), Some(s))

  def submitDeclaration(decl: TaxDeclaration)(using ExecutionContext): Future[SubmissionResult] =
    Future {
      val signedXml  = signXml(decl.xmlContent)
      val envelope   = buildSubmitEnvelope(decl, signedXml)
      val response   = postSoap("/rejestracja", envelope)
      parseSubmitResponse(response)
    }

  def pollDeclarationStatus(ticketId: String)(using ExecutionContext): Future[SubmissionResult] =
    Future {
      val response = getHttp(s"/statusdek?id=$ticketId")
      parseStatusResponse(response, ticketId)
    }

  @annotation.nowarn("msg=unused")
  def submitAuditFile(file: AuditFile)(using ExecutionContext): Future[SubmissionResult] =
    Future {
      val envelope = buildAuditFileEnvelope(file)
      val response = postSoap("/rejestracja", envelope)
      parseSubmitResponse(response)
    }

  def pollAuditFileStatus(ticketId: String)(using ExecutionContext): Future[SubmissionResult] =
    Future {
      val response = getHttp(s"/statusjpk?id=$ticketId")
      parseStatusResponse(response, ticketId)
    }

  @annotation.nowarn("msg=unused")
  def submitInvoice(invoice: FiscalInvoice)(using ExecutionContext): Future[InvoiceSubmissionResult] =
    Future.failed(BureauError.UnsupportedOperation(CountryCode.PL, GovDomain.Fiscal, "submitInvoice — use PlKsefAdapter"))

  @annotation.nowarn("msg=unused")
  def pollInvoiceStatus(ticketId: String)(using ExecutionContext): Future[InvoiceSubmissionResult] =
    Future.failed(BureauError.UnsupportedOperation(CountryCode.PL, GovDomain.Fiscal, "pollInvoiceStatus — use PlKsefAdapter"))

  @annotation.nowarn("msg=unused")
  def fetchInvoice(id: String)(using ExecutionContext): Future[FiscalInvoice] =
    Future.failed(BureauError.UnsupportedOperation(CountryCode.PL, GovDomain.Fiscal, "fetchInvoice — use PlKsefAdapter"))

  @annotation.nowarn("msg=unused")
  def queryInvoices(filter: InvoiceFilter)(using ExecutionContext): Future[List[InvoiceRef]] =
    Future.failed(BureauError.UnsupportedOperation(CountryCode.PL, GovDomain.Fiscal, "queryInvoices — use PlKsefAdapter"))

  @annotation.nowarn("msg=unused")
  def verifyVatNumber(id: TaxIdentifier)(using ExecutionContext): Future[VatVerificationResult] =
    Future.failed(BureauError.UnsupportedOperation(CountryCode.PL, GovDomain.Fiscal, "verifyVatNumber — use PlRegistryProvider"))

  private def signXml(xml: String)(using ExecutionContext): String =
    val signed = scala.concurrent.Await.result(
      signing.sign(xml.getBytes(StandardCharsets.UTF_8), SignatureFormat.XAdES),
      scala.concurrent.duration.Duration(30, "seconds")
    )
    val sigB64 = Base64.getEncoder.encodeToString(signed.signature)
    xml.replace("</Dokument>",
      s"""<Podpis><ds:Signature xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
         |  <ds:SignatureValue>$sigB64</ds:SignatureValue>
         |</ds:Signature></Podpis></Dokument>""".stripMargin)

  private def buildSubmitEnvelope(decl: TaxDeclaration, signedXml: String): String =
    val xmlB64 = Base64.getEncoder.encodeToString(signedXml.getBytes(StandardCharsets.UTF_8))
    s"""<?xml version="1.0" encoding="utf-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                  xmlns:bram="http://e-deklaracje.mf.gov.pl/rejestracja/system/">
  <soapenv:Body>
    <bram:PrzeslijDokument>
      <bram:NazwaFormularza>${decl.declarationType}</bram:NazwaFormularza>
      <bram:Dokument>$xmlB64</bram:Dokument>
    </bram:PrzeslijDokument>
  </soapenv:Body>
</soapenv:Envelope>"""

  private def buildAuditFileEnvelope(file: AuditFile): String =
    val xmlB64 = Base64.getEncoder.encodeToString(file.xmlContent.getBytes(StandardCharsets.UTF_8))
    s"""<?xml version="1.0" encoding="utf-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                  xmlns:jpk="http://jpk.mf.gov.pl/wzor/2023/">
  <soapenv:Body>
    <jpk:WyslijJpk>
      <jpk:NazwaPliku>${file.fileType}</jpk:NazwaPliku>
      <jpk:Dokument>$xmlB64</jpk:Dokument>
    </jpk:WyslijJpk>
  </soapenv:Body>
</soapenv:Envelope>"""

  private[fiscal] def parseSubmitResponse(xml: String): SubmissionResult =
    val refNum   = extractXmlTag(xml, "NumerReferencyjny")
      .orElse(extractXmlTag(xml, "ReferenceNumber"))
      .orElse(extractXmlTag(xml, "ElementReferenceNumber"))
      .getOrElse(s"EDK-${System.currentTimeMillis()}")
    val errCode  = extractXmlTag(xml, "KodBledu").orElse(extractXmlTag(xml, "ErrorCode"))
    val errMsg   = extractXmlTag(xml, "OpisBledu").orElse(extractXmlTag(xml, "ErrorMessage"))
    if errCode.isDefined then
      SubmissionResult(
        submissionId = refNum,
        status       = SubmissionStatus.Rejected(List(GovError(errCode.get, errMsg.getOrElse("Submission rejected")))),
        timestamp    = Instant.now(),
      )
    else
      SubmissionResult(
        submissionId = refNum,
        status       = SubmissionStatus.Pending(refNum),
        timestamp    = Instant.now(),
        reference    = Some(refNum),
      )

  private[fiscal] def parseStatusResponse(xml: String, ticketId: String): SubmissionResult =
    val statusCode = extractXmlTag(xml, "KodStatusu").orElse(extractXmlTag(xml, "StatusCode")).getOrElse("")
    val upoRef     = extractXmlTag(xml, "NumerUPO").orElse(extractXmlTag(xml, "UpoReferenceNumber"))
    val status = statusCode match
      case "200" | "20" | "30" | "WyslaneDoPodpisu" =>
        upoRef.map(_ => SubmissionStatus.Accepted).getOrElse(SubmissionStatus.Processing)
      case "400" | "401" | "WycofaneDoPoprawy" =>
        val msg = extractXmlTag(xml, "OpisBledu").getOrElse("Rejected by e-Deklaracje")
        SubmissionStatus.Rejected(List(GovError(statusCode, msg)))
      case "305" | "WRealizacji" | "PrzetworzoneCzesciowo" => SubmissionStatus.Processing
      case _ => SubmissionStatus.Pending(ticketId)
    SubmissionResult(
      submissionId = ticketId,
      status       = status,
      timestamp    = Instant.now(),
      reference    = upoRef.orElse(Some(ticketId)),
    )

  private[fiscal] def extractXmlTag(xml: String, tag: String): Option[String] =
    val pat = s"<(?:[^:>]*:)?$tag>([\\s\\S]*?)</(?:[^:>]*:)?$tag>"
    pat.r.findFirstMatchIn(xml).map(_.group(1).trim).filter(_.nonEmpty)
