package scalascript.gov.eu

import scalascript.gov.*
import scalascript.gov.registry.*
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JHttpRequest}
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}
import scala.concurrent.{Future, ExecutionContext}

/** VIES VAT verification adapter.
 *
 *  Calls the EC VIES checkVat SOAP operation:
 *  https://ec.europa.eu/taxation_customs/vies/services/checkVatService
 *
 *  VAT numbers are split from the EU prefix (e.g. "PL1234567890" → countryCode="PL", vatNumber="1234567890"). */
class EuViesAdapter(baseUrl: String = "https://ec.europa.eu/taxation_customs/vies/services/checkVatService"):

  protected def postSoap(url: String, body: String, soapAction: String): String =
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    val req    = JHttpRequest.newBuilder()
      .uri(URI.create(url))
      .header("Content-Type", "text/xml; charset=utf-8")
      .header("SOAPAction", soapAction)
      .POST(JHttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
      .build()
    val resp = client.send(req, BodyHandlers.ofString(StandardCharsets.UTF_8))
    resp.statusCode() match
      case s if s >= 200 && s < 300 => resp.body()
      case 429 => throw BureauError.RateLimitError(Some(60))
      case 503 => throw BureauError.ServiceUnavailable("VIES service unavailable")
      case s   => throw BureauError.ApiError(s"VIES HTTP $s", Some(s.toString), Some(s))

  def checkVat(countryCode: String, vatNumber: String)(using ExecutionContext): Future[VatPayerStatus] =
    Future {
      val reqBody  = buildCheckVatRequest(countryCode, vatNumber)
      val response = postSoap(baseUrl, reqBody, "urn:ec.europa.eu:taxud:vies:services:checkVat:ports:checkVatPort")
      parseSoapFault(response).foreach(msg => throw BureauError.ApiError(s"VIES fault: $msg"))
      parseCheckVatResponse(response, countryCode, vatNumber)
    }

  private def buildCheckVatRequest(cc: String, vat: String): String =
    s"""<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                  xmlns:urn="urn:ec.europa.eu:taxud:vies:services:checkVat:types">
  <soapenv:Header/>
  <soapenv:Body>
    <urn:checkVat>
      <urn:countryCode>${escape(cc)}</urn:countryCode>
      <urn:vatNumber>${escape(vat)}</urn:vatNumber>
    </urn:checkVat>
  </soapenv:Body>
</soapenv:Envelope>"""

  private[eu] def parseCheckVatResponse(xml: String, countryCode: String, vatNumber: String): VatPayerStatus =
    val valid   = extractXmlTag(xml, "valid").exists(_.equalsIgnoreCase("true"))
    val name    = extractXmlTag(xml, "name").filter(s => s.nonEmpty && s != "---")
    val taxId   = TaxIdentifier(TaxIdType.VatEU, TaxId(countryCode + vatNumber), CountryCode(countryCode))
    VatPayerStatus(
      active       = valid,
      id           = taxId,
      name         = name,
      bankAccounts = Nil,
      checkedAt    = Instant.now(),
    )

  private[eu] def parseSoapFault(xml: String): Option[String] =
    extractXmlTag(xml, "faultstring").orElse(extractXmlTag(xml, "faultString"))

  private[eu] def extractXmlTag(xml: String, tag: String): Option[String] =
    val pat = s"<(?:[^:>]*:)?$tag>([\\s\\S]*?)</(?:[^:>]*:)?$tag>"
    pat.r.findFirstMatchIn(xml).map(_.group(1).trim).filter(_.nonEmpty)

  private def escape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
     .replace("\"", "&quot;").replace("'", "&apos;")
