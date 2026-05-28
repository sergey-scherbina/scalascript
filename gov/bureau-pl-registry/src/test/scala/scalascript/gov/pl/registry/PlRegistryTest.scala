package scalascript.gov.pl.registry

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.gov.*
import scalascript.gov.registry.*
import java.time.{Instant, LocalDate}
import scala.annotation.nowarn
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.global

@nowarn("msg=not declared infix")
class CeidgAdapterTest extends AnyFunSuite with Matchers:

  private def adapter(response: Map[String, String]): CeidgAdapter =
    new CeidgAdapter(PlRegistryConfig()) {
      override protected def getJson(url: String, apiKey: Option[String]): String =
        response.find((k, _) => url.contains(k)).map(_._2)
          .getOrElse(throw NotFoundException(s"not found: $url"))
    }

  test("lookupByNip returns BusinessRecord for valid NIP") {
    val json = """{"nip":"1234567890","nazwa":"Kowalski Jan USŁUGI BUDOWLANE",
      |"status":"aktywna","ulica":"ul. Kwiatowa 1","miasto":"Warszawa",
      |"kodPocztowy":"00-001","dataRozpoczeciaDzialalnosci":"2020-01-15"}""".stripMargin
    val a   = adapter(Map("nip=1234567890" -> json))
    val rec = a.lookupByNip("1234567890")
    rec shouldBe defined
    rec.get.name should include("Kowalski")
    rec.get.legalForm shouldBe Some(LegalForm.SoleProprietor)
    rec.get.taxIds.map(_.idType) should contain(TaxIdType.NIP)
  }

  test("lookupByNip returns None for not-found NIP") {
    val a = adapter(Map.empty)
    a.lookupByNip("9999999999") shouldBe None
  }

  test("lookupByPesel returns BusinessRecord for valid PESEL") {
    val json = """{"pesel":"90010112345","nazwa":"Nowak Anna HANDEL","status":"aktywna",
      |"miasto":"Kraków","kodPocztowy":"31-001"}""".stripMargin
    val a   = adapter(Map("pesel=90010112345" -> json))
    val rec = a.lookupByPesel("90010112345")
    rec shouldBe defined
    rec.get.taxIds.map(_.idType) should contain(TaxIdType.PESEL)
  }

  test("lookupByPesel returns None when not in CEIDG") {
    val a = adapter(Map.empty)
    a.lookupByPesel("00000000000") shouldBe None
  }

  test("search returns list of records matching query") {
    val json = """[{"nip":"1111111111","nazwa":"Firma ABC","status":"aktywna",
      |"miasto":"Gdańsk","kodPocztowy":"80-001"},
      |{"nip":"2222222222","nazwa":"Firma ABC 2","status":"aktywna",
      |"miasto":"Gdynia","kodPocztowy":"81-001"}]""".stripMargin
    val a    = adapter(Map("name=Firma%20ABC" -> json, "name=Firma+ABC" -> json))
    val recs = a.search("Firma ABC")
    recs should not be empty
  }

  test("search returns empty list when no match") {
    val a = adapter(Map.empty)
    a.search("NoSuchCompany") shouldBe Nil
  }

  test("CEIDG status parsing: aktywna → Active") {
    val json = """{"nip":"1234567890","nazwa":"Test","status":"aktywna","miasto":"W","kodPocztowy":"00"}"""
    val a    = adapter(Map("nip=1234567890" -> json))
    val rec  = a.lookupByNip("1234567890")
    rec.get.status shouldBe RegistrationStatus.Active
  }

  test("CEIDG status parsing: zawieszona → Suspended") {
    val json = """{"nip":"1234567890","nazwa":"Test","status":"zawieszona","miasto":"W","kodPocztowy":"00"}"""
    val a    = adapter(Map("nip=1234567890" -> json))
    val rec  = a.lookupByNip("1234567890")
    rec.get.status shouldBe RegistrationStatus.Suspended
  }

  test("CEIDG status parsing: wykreslona → Dissolved") {
    val json = """{"nip":"1234567890","nazwa":"Test","status":"wykreslona","miasto":"W","kodPocztowy":"00"}"""
    val a    = adapter(Map("nip=1234567890" -> json))
    val rec  = a.lookupByNip("1234567890")
    rec.get.status shouldBe RegistrationStatus.Dissolved
  }

  test("CEIDG record includes address") {
    val json = """{"nip":"1234567890","nazwa":"Test Co","status":"aktywna","ulica":"ul. Testowa 5",
      |"miasto":"Poznań","kodPocztowy":"60-001"}""".stripMargin
    val a   = adapter(Map("nip=1234567890" -> json))
    val rec = a.lookupByNip("1234567890")
    rec.get.address shouldBe defined
    rec.get.address.get.city shouldBe "Poznań"
    rec.get.address.get.postalCode shouldBe "60-001"
  }

  test("CEIDG record with registration date") {
    val json = """{"nip":"1234567890","nazwa":"Test","status":"aktywna","miasto":"W",
      |"kodPocztowy":"00","dataRozpoczeciaDzialalnosci":"2019-03-22"}""".stripMargin
    val a   = adapter(Map("nip=1234567890" -> json))
    val rec = a.lookupByNip("1234567890")
    rec.get.registeredAt shouldBe Some(LocalDate.of(2019, 3, 22))
  }

  test("CEIDG extractField handles missing key") {
    val a = adapter(Map.empty)
    a.extractField("{}", "missing") shouldBe None
  }

  test("CEIDG returns None for empty JSON object") {
    val a = adapter(Map("nip=0000000000" -> "{}"))
    a.lookupByNip("0000000000") shouldBe None
  }

@nowarn("msg=not declared infix")
class RegonAdapterTest extends AnyFunSuite with Matchers:

  private val sampleSoapResp = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body>
    <DaneSzukajPodmiotyResponse xmlns="http://CIS/BIR/PUBL/2014/07">
      <DaneSzukajPodmiotyResult><![CDATA[<?xml version="1.0" encoding="utf-8"?>
<root><dane>
  <Regon>123456789</Regon>
  <Nip>1234567890</Nip>
  <Nazwa>TESTOWA SPÓŁKA Z OGRANICZONĄ ODPOWIEDZIALNOŚCIĄ</Nazwa>
  <Ulica>ul. Główna</Ulica>
  <NrNieruchomosci>10</NrNieruchomosci>
  <MiejscowoscSiedziby>Wrocław</MiejscowoscSiedziby>
  <KodPocztowy>50-001</KodPocztowy>
  <Form>SPÓŁKA Z OGRANICZONĄ ODPOWIEDZIALNOŚCIĄ</Form>
  <DataPowstania>2015-06-01</DataPowstania>
</dane></root>]]></DaneSzukajPodmiotyResult>
    </DaneSzukajPodmiotyResponse>
  </s:Body>
</s:Envelope>"""

  private val loginResp = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body>
    <ZalogujResponse xmlns="http://CIS/BIR/PUBL/2014/07">
      <ZalogujResult>abc123session</ZalogujResult>
    </ZalogujResponse>
  </s:Body>
</s:Envelope>"""

  private val logoutResp = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body><WylogujResponse xmlns="http://CIS/BIR/PUBL/2014/07"><WylogujResult>true</WylogujResult></WylogujResponse></s:Body>
</s:Envelope>"""

  private def adapter(responses: List[String]): RegonAdapter =
    val queue = collection.mutable.Queue.from(responses)
    new RegonAdapter(PlRegistryConfig(regonApiKey = Some("test-key"))) {
      override protected def postSoap(url: String, envelope: String, action: String): String =
        if queue.isEmpty then logoutResp else queue.dequeue()
    }

  test("lookupByNip returns BusinessRecord from SOAP response") {
    val a   = adapter(List(loginResp, sampleSoapResp, logoutResp))
    val rec = a.lookupByNip("1234567890")
    rec shouldBe defined
    rec.get.name should include("TESTOWA SPÓŁKA")
    rec.get.taxIds.map(_.idType) should contain(TaxIdType.NIP)
    rec.get.taxIds.map(_.idType) should contain(TaxIdType.REGON)
  }

  test("lookupByNip returns None for empty SOAP result") {
    val emptyResp = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body>
    <DaneSzukajPodmiotyResponse xmlns="http://CIS/BIR/PUBL/2014/07">
      <DaneSzukajPodmiotyResult>0</DaneSzukajPodmiotyResult>
    </DaneSzukajPodmiotyResponse>
  </s:Body>
</s:Envelope>"""
    val a   = adapter(List(loginResp, emptyResp, logoutResp))
    val rec = a.lookupByNip("9999999999")
    rec shouldBe None
  }

  test("lookupByRegon uses 9-digit REGON path") {
    val a   = adapter(List(loginResp, sampleSoapResp, logoutResp))
    val rec = a.lookupByRegon("123456789")
    rec shouldBe defined
    rec.get.taxIds.map(_.idType) should contain(TaxIdType.REGON)
  }

  test("REGON legal form: sp. z o.o. → LimitedLiabilityCompany") {
    val a   = adapter(List(loginResp, sampleSoapResp, logoutResp))
    val rec = a.lookupByNip("1234567890")
    rec.get.legalForm shouldBe Some(LegalForm.LimitedLiabilityCompany)
  }

  test("REGON address is parsed") {
    val a   = adapter(List(loginResp, sampleSoapResp, logoutResp))
    val rec = a.lookupByNip("1234567890")
    rec.get.address shouldBe defined
    rec.get.address.get.city shouldBe "Wrocław"
  }

  test("REGON registration date is parsed") {
    val a   = adapter(List(loginResp, sampleSoapResp, logoutResp))
    val rec = a.lookupByNip("1234567890")
    rec.get.registeredAt shouldBe Some(LocalDate.of(2015, 6, 1))
  }

  test("REGON extractSoapValue handles missing tag") {
    val a = adapter(Nil)
    a.extractSoapValue("<root></root>", "missing") shouldBe None
  }

  test("REGON AuthenticationError when login returns empty token") {
    val badLoginResp = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body>
    <ZalogujResponse xmlns="http://CIS/BIR/PUBL/2014/07">
      <ZalogujResult></ZalogujResult>
    </ZalogujResponse>
  </s:Body>
</s:Envelope>"""
    val a = adapter(List(badLoginResp))
    an[BureauError.AuthenticationError] should be thrownBy a.lookupByNip("1234567890")
  }

@nowarn("msg=not declared infix")
class BialaListaAdapterTest extends AnyFunSuite with Matchers:

  private def adapter(response: Map[String, String]): BialaListaAdapter =
    new BialaListaAdapter(PlRegistryConfig(bialaListaApiKey = Some("test-key"))) {
      override protected def getJson(url: String): String =
        response.find((k, _) => url.contains(k)).map(_._2)
          .getOrElse(throw NotFoundException(s"not found: $url"))
    }

  private val sampleJson = """{
    "result": {
      "subject": {
        "name": "ACME SP. Z O.O.",
        "nip": "5252344078",
        "regon": "017474503",
        "krs": "0000066790",
        "statusVat": "Czynny",
        "workingAddress": "ul. Testowa 1, 00-001 Warszawa"
      },
      "accountNumbers": ["PL61109010140000071219812874", "PL72102010260000400201115246"]
    }
  }"""

  test("checkVatStatus returns active=true for Czynny VAT payer") {
    val a      = adapter(Map("5252344078" -> sampleJson))
    val status = a.checkVatStatus("5252344078")
    status.active shouldBe true
    status.bankAccounts should have length 2
    status.bankAccounts.head should startWith("PL")
  }

  test("checkVatStatus returns active=false for unknown NIP") {
    val a      = adapter(Map.empty)
    val status = a.checkVatStatus("9999999999")
    status.active shouldBe false
    status.bankAccounts shouldBe Nil
  }

  test("checkVatStatus returns active=false for non-Czynny status") {
    val json   = """{"result":{"subject":{"name":"Firma X","nip":"1111111111","statusVat":"Niezarejestrowany"}}}"""
    val a      = adapter(Map("1111111111" -> json))
    val status = a.checkVatStatus("1111111111")
    status.active shouldBe false
  }

  test("lookup returns BusinessRecord with name and tax IDs") {
    val a   = adapter(Map("5252344078" -> sampleJson))
    val rec = a.lookup("5252344078")
    rec shouldBe defined
    rec.get.name should include("ACME")
    rec.get.taxIds.map(_.idType) should contain(TaxIdType.NIP)
    rec.get.taxIds.map(_.idType) should contain(TaxIdType.KRS)
  }

  test("lookup returns None for unknown NIP") {
    val a = adapter(Map.empty)
    a.lookup("9999999999") shouldBe None
  }

  test("extractBankAccounts returns all account numbers") {
    val a      = adapter(Map("5252344078" -> sampleJson))
    val status = a.checkVatStatus("5252344078")
    status.bankAccounts should contain("PL61109010140000071219812874")
    status.bankAccounts should contain("PL72102010260000400201115246")
  }

  test("checkVatStatus checkedAt is close to now") {
    val a      = adapter(Map("5252344078" -> sampleJson))
    val before = Instant.now()
    val status = a.checkVatStatus("5252344078")
    val after  = Instant.now()
    status.checkedAt.isAfter(before.minusSeconds(1)) shouldBe true
    status.checkedAt.isBefore(after.plusSeconds(1)) shouldBe true
  }

  test("BiałaLista RateLimitError on 429") {
    val rateLimitAdapter = new BialaListaAdapter(PlRegistryConfig()) {
      override protected def getJson(url: String): String =
        throw BureauError.RateLimitError(Some(60))
    }
    an[BureauError.RateLimitError] should be thrownBy rateLimitAdapter.checkVatStatus("1234567890")
  }

  test("extractField works with nested subject JSON") {
    val a = adapter(Map("5252344078" -> sampleJson))
    a.extractField(sampleJson, "name") shouldBe Some("ACME SP. Z O.O.")
  }

@nowarn("msg=not declared infix")
class KrsAdapterTest extends AnyFunSuite with Matchers:

  private def adapter(response: Map[String, String]): KrsAdapter =
    new KrsAdapter(PlRegistryConfig()) {
      override protected def getJson(url: String): String =
        response.find((k, _) => url.contains(k)).map(_._2)
          .getOrElse(throw NotFoundException(s"not found: $url"))
    }

  private val sampleKrsJson = """{
    "numerKrs": "0000066790",
    "nip": "5252344078",
    "regon": "017474503",
    "nazwa": "WIDGET FACTORY SP. Z O.O.",
    "forma": "SP. Z O.O.",
    "status": "aktywny",
    "dataRejestracji": "2001-11-09",
    "miejscowosc": "WARSZAWA",
    "kodPocztowy": "02-676",
    "ulica": "ul. Puławska",
    "czlonkowie": ["Jan Kowalski", "Anna Nowak"],
    "pkd": ["72.11.Z", "46.52.Z"]
  }"""

  test("lookupByKrs returns BusinessRecord for valid KRS") {
    val a   = adapter(Map("0000066790" -> sampleKrsJson))
    val rec = a.lookupByKrs("0000066790")
    rec shouldBe defined
    rec.get.name should include("WIDGET FACTORY")
    rec.get.taxIds.map(_.idType) should contain(TaxIdType.KRS)
    rec.get.taxIds.map(_.idType) should contain(TaxIdType.NIP)
  }

  test("lookupByKrs returns None for unknown KRS") {
    val a = adapter(Map.empty)
    a.lookupByKrs("9999999999") shouldBe None
  }

  test("KRS legal form: sp. z o.o. → LimitedLiabilityCompany") {
    val a   = adapter(Map("0000066790" -> sampleKrsJson))
    val rec = a.lookupByKrs("0000066790")
    rec.get.legalForm shouldBe Some(LegalForm.LimitedLiabilityCompany)
  }

  test("KRS status: aktywny → Active") {
    val a   = adapter(Map("0000066790" -> sampleKrsJson))
    val rec = a.lookupByKrs("0000066790")
    rec.get.status shouldBe RegistrationStatus.Active
  }

  test("KRS registration date is parsed") {
    val a   = adapter(Map("0000066790" -> sampleKrsJson))
    val rec = a.lookupByKrs("0000066790")
    rec.get.registeredAt shouldBe Some(LocalDate.of(2001, 11, 9))
  }

  test("KRS address is populated") {
    val a   = adapter(Map("0000066790" -> sampleKrsJson))
    val rec = a.lookupByKrs("0000066790")
    rec.get.address shouldBe defined
    rec.get.address.get.city shouldBe "WARSZAWA"
  }

  test("getDetails returns RegistrationDetails with directors") {
    val a       = adapter(Map("0000066790" -> sampleKrsJson))
    val details = a.getDetails("0000066790")
    details shouldBe defined
    details.get.directors should contain("Jan Kowalski")
    details.get.activities should contain("72.11.Z")
  }

  test("getDetails returns None for unknown KRS") {
    val a = adapter(Map.empty)
    a.getDetails("0000000000") shouldBe None
  }

  test("searchByName returns list of matching records") {
    val searchJson = s"""[$sampleKrsJson]"""
    val a          = adapter(Map("nazwa=WIDGET" -> searchJson, "nazwa=WIDGET%20FACTORY" -> searchJson))
    val results    = a.searchByName("WIDGET")
    results should not be empty
    results.head.name should include("WIDGET")
  }

  test("searchByName returns empty list when no match") {
    val a = adapter(Map.empty)
    a.searchByName("NoSuchCompanyXYZ") shouldBe Nil
  }

  test("KRS status: w likwidacji → Liquidation") {
    val json = sampleKrsJson.replace("\"aktywny\"", "\"w likwidacji\"")
    val a    = adapter(Map("0000066790" -> json))
    val rec  = a.lookupByKrs("0000066790")
    rec.get.status shouldBe RegistrationStatus.Liquidation
  }

  test("KRS status: wykreślony → Dissolved") {
    val json = sampleKrsJson.replace("\"aktywny\"", "\"wykreślony\"")
    val a    = adapter(Map("0000066790" -> json))
    val rec  = a.lookupByKrs("0000066790")
    rec.get.status shouldBe RegistrationStatus.Dissolved
  }

@nowarn("msg=not declared infix")
class PlRegistryProviderTest extends AnyFunSuite with Matchers:

  private given ExecutionContext = global
  private def await[T](f: Future[T]): T = Await.result(f, Duration(5, "seconds"))

  private val sampleRecord = BusinessRecord(
    name         = "TEST SP. Z O.O.",
    legalForm    = Some(LegalForm.LimitedLiabilityCompany),
    taxIds       = List(TaxIdentifier(TaxIdType.NIP, TaxId("1234567890"), CountryCode.PL)),
    address      = Some(Address("ul. Testowa 1", None, "Warszawa", "00-001", CountryCode.PL)),
    status       = RegistrationStatus.Active,
    registeredAt = None,
  )

  private val sampleVatStatus = VatPayerStatus(
    active       = true,
    id           = TaxIdentifier(TaxIdType.NIP, TaxId("1234567890"), CountryCode.PL),
    name         = Some("TEST SP. Z O.O."),
    bankAccounts = List("PL61109010140000071219812874"),
    checkedAt    = Instant.now(),
  )

  private def provider(
    ceidgRec: Option[BusinessRecord]     = None,
    regonRec: Option[BusinessRecord]     = None,
    blRec:    Option[BusinessRecord]     = None,
    blVat:    VatPayerStatus             = sampleVatStatus,
    krsRec:   Option[BusinessRecord]     = None,
    krsDetails: Option[RegistrationDetails] = None
  ): PlRegistryProvider =
    val cfg = PlRegistryConfig()
    new PlRegistryProvider(cfg) {
      override protected val ceidg = new CeidgAdapter(cfg) {
        override def lookupByNip(nip: String) = ceidgRec
        override def lookupByPesel(pesel: String) = ceidgRec
        override def search(q: String) = ceidgRec.toList
      }
      override protected val regon = new RegonAdapter(cfg) {
        override def lookupByNip(nip: String) = regonRec
        override def lookupByRegon(r: String) = regonRec
      }
      override protected val bialaLista = new BialaListaAdapter(cfg) {
        override def lookup(nip: String) = blRec
        override def checkVatStatus(nip: String, date: java.time.LocalDate) = blVat
      }
      override protected val krs = new KrsAdapter(cfg) {
        override def lookupByKrs(k: String) = krsRec
        override def searchByName(n: String) = krsRec.toList
        override def getDetails(k: String) = krsDetails
      }
    }

  test("lookup by NIP uses BiałaLista first") {
    val p   = provider(blRec = Some(sampleRecord))
    val rec = await(p.lookup(TaxIdentifier(TaxIdType.NIP, TaxId("1234567890"), CountryCode.PL)))
    rec shouldBe Some(sampleRecord)
  }

  test("lookup by NIP falls back to REGON when BiałaLista returns None") {
    val p   = provider(regonRec = Some(sampleRecord))
    val rec = await(p.lookup(TaxIdentifier(TaxIdType.NIP, TaxId("1234567890"), CountryCode.PL)))
    rec shouldBe Some(sampleRecord)
  }

  test("lookup by NIP returns None when both BiałaLista and REGON miss") {
    val p   = provider()
    val rec = await(p.lookup(TaxIdentifier(TaxIdType.NIP, TaxId("9999999999"), CountryCode.PL)))
    rec shouldBe None
  }

  test("lookup by REGON uses REGON adapter") {
    val p   = provider(regonRec = Some(sampleRecord))
    val rec = await(p.lookup(TaxIdentifier(TaxIdType.REGON, TaxId("123456789"), CountryCode.PL)))
    rec shouldBe Some(sampleRecord)
  }

  test("lookup by KRS uses KRS adapter") {
    val p   = provider(krsRec = Some(sampleRecord))
    val rec = await(p.lookup(TaxIdentifier(TaxIdType.KRS, TaxId("0000066790"), CountryCode.PL)))
    rec shouldBe Some(sampleRecord)
  }

  test("lookup by PESEL uses CEIDG adapter") {
    val p   = provider(ceidgRec = Some(sampleRecord))
    val rec = await(p.lookup(TaxIdentifier(TaxIdType.PESEL, TaxId("90010112345"), CountryCode.PL)))
    rec shouldBe Some(sampleRecord)
  }

  test("lookup by unsupported type throws UnsupportedOperation") {
    val p = provider()
    val ex = intercept[BureauError.UnsupportedOperation] {
      await(p.lookup(TaxIdentifier(TaxIdType.VatEU, TaxId("PL1234567890"), CountryCode.PL)))
    }
    ex.getMessage should include("Registry")
  }

  test("checkVatStatus delegates to BiałaLista for NIP") {
    val p      = provider()
    val status = await(p.checkVatStatus(TaxIdentifier(TaxIdType.NIP, TaxId("1234567890"), CountryCode.PL)))
    status.active shouldBe true
    status.bankAccounts should not be empty
  }

  test("checkVatStatus throws UnsupportedOperation for non-NIP") {
    val p = provider()
    val ex = intercept[BureauError.UnsupportedOperation] {
      await(p.checkVatStatus(TaxIdentifier(TaxIdType.REGON, TaxId("123456789"), CountryCode.PL)))
    }
    ex.getMessage should include("VAT status")
  }

  test("search combines CEIDG and KRS results") {
    val rec2 = sampleRecord.copy(name = "KRS COMPANY")
    val p    = provider(ceidgRec = Some(sampleRecord), krsRec = Some(rec2))
    val list = await(p.search("TEST", CountryCode.PL))
    list should have length 2
    list.map(_.name) should contain("TEST SP. Z O.O.")
    list.map(_.name) should contain("KRS COMPANY")
  }

  test("search deduplicates results by name") {
    val p    = provider(ceidgRec = Some(sampleRecord), krsRec = Some(sampleRecord))
    val list = await(p.search("TEST", CountryCode.PL))
    list should have length 1
  }

  test("getDetails by KRS delegates to KRS adapter") {
    val details = RegistrationDetails(sampleRecord, List("CEO"), List("Shareholder 1"), List("72.11.Z"), None)
    val p       = provider(krsRec = Some(sampleRecord), krsDetails = Some(details))
    val result  = await(p.getDetails(TaxIdentifier(TaxIdType.KRS, TaxId("0000066790"), CountryCode.PL)))
    result.directors should contain("CEO")
    result.activities should contain("72.11.Z")
  }

  test("getDetails by KRS throws ApiError when not found") {
    val p = provider()
    val ex = intercept[BureauError.ApiError] {
      await(p.getDetails(TaxIdentifier(TaxIdType.KRS, TaxId("0000000000"), CountryCode.PL)))
    }
    ex.httpStatus shouldBe Some(404)
  }

  test("getDetails by NIP wraps BiałaLista record") {
    val p      = provider(blRec = Some(sampleRecord))
    val result = await(p.getDetails(TaxIdentifier(TaxIdType.NIP, TaxId("1234567890"), CountryCode.PL)))
    result.record shouldBe sampleRecord
    result.directors shouldBe Nil
  }

  test("getDetails by PESEL wraps CEIDG record") {
    val p      = provider(ceidgRec = Some(sampleRecord))
    val result = await(p.getDetails(TaxIdentifier(TaxIdType.PESEL, TaxId("90010112345"), CountryCode.PL)))
    result.record shouldBe sampleRecord
  }

  test("getDetails by unsupported type throws UnsupportedOperation") {
    val p = provider()
    val ex = intercept[BureauError.UnsupportedOperation] {
      await(p.getDetails(TaxIdentifier(TaxIdType.VatEU, TaxId("PL1234567890"), CountryCode.PL)))
    }
    ex.getMessage should include("getDetails")
  }
