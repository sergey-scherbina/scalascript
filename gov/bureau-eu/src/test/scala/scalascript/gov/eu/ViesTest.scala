package scalascript.gov.eu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.gov.*
import scalascript.gov.registry.*
import java.time.Instant
import scala.annotation.nowarn
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.global

// ─── helpers ────────────────────────────────────────────────────────────────

private def validViesResponse(cc: String = "PL", vat: String = "1234567890",
                              name: String = "ACME SP Z O O", valid: Boolean = true): String =
  s"""<env:Envelope xmlns:env="http://schemas.xmlsoap.org/soap/envelope/">
  <env:Body>
    <ns2:checkVatResponse xmlns:ns2="urn:ec.europa.eu:taxud:vies:services:checkVat:types">
      <ns2:countryCode>$cc</ns2:countryCode>
      <ns2:vatNumber>$vat</ns2:vatNumber>
      <ns2:requestDate>2024-01-15+01:00</ns2:requestDate>
      <ns2:valid>$valid</ns2:valid>
      <ns2:name>$name</ns2:name>
      <ns2:address>UL. TESTOWA 1, 00-001 WARSZAWA</ns2:address>
    </ns2:checkVatResponse>
  </env:Body>
</env:Envelope>"""

private def soapFaultResponse(msg: String = "INVALID_INPUT"): String =
  s"""<env:Envelope xmlns:env="http://schemas.xmlsoap.org/soap/envelope/">
  <env:Body>
    <env:Fault>
      <faultcode>env:Server</faultcode>
      <faultstring>$msg</faultstring>
    </env:Fault>
  </env:Body>
</env:Envelope>"""

// ─── EuViesAdapterTest ──────────────────────────────────────────────────────

@nowarn("msg=not declared infix")
class EuViesAdapterTest extends AnyFunSuite with Matchers:

  private def adapter(response: String): EuViesAdapter =
    new EuViesAdapter("http://vies.test") {
      override protected def postSoap(url: String, body: String, soapAction: String): String = response
    }

  private def failAdapter(error: Exception): EuViesAdapter =
    new EuViesAdapter("http://vies.test") {
      override protected def postSoap(url: String, body: String, soapAction: String): String = throw error
    }

  test("checkVat returns active=true for valid VAT response") {
    val a      = adapter(validViesResponse())
    val status = Await.result(a.checkVat("PL", "1234567890")(using global), Duration.Inf)
    status.active shouldBe true
  }

  test("checkVat returns active=false for invalid VAT response") {
    val a      = adapter(validViesResponse(valid = false, name = "---"))
    val status = Await.result(a.checkVat("PL", "0000000000")(using global), Duration.Inf)
    status.active shouldBe false
  }

  test("checkVat populates name field from response") {
    val a      = adapter(validViesResponse(name = "TEST COMPANY SP Z O O"))
    val status = Await.result(a.checkVat("PL", "1234567890")(using global), Duration.Inf)
    status.name shouldBe Some("TEST COMPANY SP Z O O")
  }

  test("checkVat name field is None when response returns ---") {
    val a      = adapter(validViesResponse(name = "---", valid = false))
    val status = Await.result(a.checkVat("PL", "0000000000")(using global), Duration.Inf)
    status.name shouldBe None
  }

  test("checkVat populates taxId with VatEU type and country prefix") {
    val a      = adapter(validViesResponse(cc = "DE", vat = "123456789"))
    val status = Await.result(a.checkVat("DE", "123456789")(using global), Duration.Inf)
    status.id.idType shouldBe TaxIdType.VatEU
    status.id.value shouldBe "DE123456789"
    status.id.country shouldBe CountryCode("DE")
  }

  test("checkVat sets checkedAt to recent timestamp") {
    val before = Instant.now()
    val a      = adapter(validViesResponse())
    val status = Await.result(a.checkVat("PL", "1234567890")(using global), Duration.Inf)
    val after  = Instant.now()
    status.checkedAt.isAfter(before.minusSeconds(1)) shouldBe true
    status.checkedAt.isBefore(after.plusSeconds(1))  shouldBe true
  }

  test("checkVat throws ApiError when SOAP fault returned") {
    val a = adapter(soapFaultResponse("INVALID_INPUT"))
    val ex = intercept[BureauError.ApiError] {
      Await.result(a.checkVat("PL", "bad")(using global), Duration.Inf)
    }
    ex.message should include("INVALID_INPUT")
  }

  test("checkVat propagates ServiceUnavailable from HTTP 503") {
    val a = failAdapter(BureauError.ServiceUnavailable("VIES service unavailable"))
    val ex = intercept[BureauError.ServiceUnavailable] {
      Await.result(a.checkVat("PL", "1234567890")(using global), Duration.Inf)
    }
    ex.message should include("unavailable")
  }

  test("checkVat propagates RateLimitError from HTTP 429") {
    val a = failAdapter(BureauError.RateLimitError(Some(60)))
    intercept[BureauError.RateLimitError] {
      Await.result(a.checkVat("PL", "1234567890")(using global), Duration.Inf)
    }
  }

  test("extractXmlTag handles namespaced response tags") {
    val a   = adapter("")
    val xml = "<ns2:valid>true</ns2:valid>"
    a.extractXmlTag(xml, "valid") shouldBe Some("true")
  }

  test("parseSoapFault returns fault message") {
    val a   = adapter("")
    val xml = soapFaultResponse("SERVICE_UNAVAILABLE")
    a.parseSoapFault(xml) shouldBe Some("SERVICE_UNAVAILABLE")
  }

  test("parseSoapFault returns None when no fault present") {
    val a = adapter("")
    a.parseSoapFault(validViesResponse()) shouldBe None
  }

// ─── EuRegistryProviderTest ─────────────────────────────────────────────────

@nowarn("msg=not declared infix")
class EuRegistryProviderTest extends AnyFunSuite with Matchers:

  private def provider(response: String): EuRegistryProvider =
    new EuRegistryProvider(new EuViesAdapter("http://vies.test") {
      override protected def postSoap(url: String, body: String, soapAction: String): String = response
    })

  private def vatId(value: String, cc: String = "PL"): TaxIdentifier =
    TaxIdentifier(TaxIdType.VatEU, TaxId(value), CountryCode(cc))

  test("lookup returns Some(BusinessRecord) for active VAT number") {
    val p      = provider(validViesResponse())
    val result = Await.result(p.lookup(vatId("PL1234567890"))(using global), Duration.Inf)
    result.isDefined shouldBe true
    result.get.status shouldBe RegistrationStatus.Active
  }

  test("lookup returns None for inactive VAT number") {
    val p      = provider(validViesResponse(valid = false, name = "---"))
    val result = Await.result(p.lookup(vatId("PL0000000000"))(using global), Duration.Inf)
    result shouldBe None
  }

  test("lookup business record contains name from VIES") {
    val p      = provider(validViesResponse(name = "MEGA CORP SP Z O O"))
    val result = Await.result(p.lookup(vatId("PL1234567890"))(using global), Duration.Inf)
    result.get.name shouldBe "MEGA CORP SP Z O O"
  }

  test("lookup business record metadata contains eu_vat key") {
    val p      = provider(validViesResponse())
    val result = Await.result(p.lookup(vatId("PL1234567890"))(using global), Duration.Inf)
    result.get.metadata("eu_vat") shouldBe "PL1234567890"
  }

  test("lookup business record taxIds contains VatEU identifier") {
    val p      = provider(validViesResponse())
    val result = Await.result(p.lookup(vatId("PL1234567890"))(using global), Duration.Inf)
    result.get.taxIds.exists(_.idType == TaxIdType.VatEU) shouldBe true
  }

  test("lookup with non-VatEU id type fails with UnsupportedOperation") {
    val p  = provider("")
    val id = TaxIdentifier(TaxIdType.NIP, TaxId("1234567890"), CountryCode.PL)
    val ex = intercept[BureauError.UnsupportedOperation] {
      Await.result(p.lookup(id)(using global), Duration.Inf)
    }
    ex.op should include("NIP")
  }

  test("checkVatStatus returns active VatPayerStatus for valid VAT") {
    val p      = provider(validViesResponse())
    val status = Await.result(p.checkVatStatus(vatId("PL1234567890"))(using global), Duration.Inf)
    status.active shouldBe true
    status.id.idType shouldBe TaxIdType.VatEU
  }

  test("checkVatStatus fails with UnsupportedOperation for non-VatEU id") {
    val p  = provider("")
    val id = TaxIdentifier(TaxIdType.NIP, TaxId("1234567890"), CountryCode.PL)
    intercept[BureauError.UnsupportedOperation] {
      Await.result(p.checkVatStatus(id)(using global), Duration.Inf)
    }
  }

  test("search fails with UnsupportedOperation") {
    val p = provider("")
    intercept[BureauError.UnsupportedOperation] {
      Await.result(p.search("company", CountryCode.EU)(using global), Duration.Inf)
    }
  }

  test("getDetails returns RegistrationDetails for active VAT") {
    val p       = provider(validViesResponse(name = "DETAILS CORP"))
    val details = Await.result(p.getDetails(vatId("PL1234567890"))(using global), Duration.Inf)
    details.record.name shouldBe "DETAILS CORP"
    details.directors shouldBe Nil
    details.activities shouldBe Nil
  }

  test("getDetails fails with ApiError for inactive VAT") {
    val p = provider(validViesResponse(valid = false, name = "---"))
    intercept[BureauError.ApiError] {
      Await.result(p.getDetails(vatId("PL0000000000"))(using global), Duration.Inf)
    }
  }

  test("checkVat correctly splits 2-char country prefix from VAT number") {
    val p      = provider(validViesResponse(cc = "DE", vat = "987654321"))
    val status = Await.result(p.checkVatStatus(vatId("DE987654321", "DE"))(using global), Duration.Inf)
    status.id.value shouldBe "DE987654321"
    status.id.country shouldBe CountryCode("DE")
  }

  test("checkVat accepts different EU country codes") {
    val p      = provider(validViesResponse(cc = "FR", vat = "12345678901"))
    val status = Await.result(p.checkVatStatus(vatId("FR12345678901", "FR"))(using global), Duration.Inf)
    status.id.country shouldBe CountryCode("FR")
  }
