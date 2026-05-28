package scalascript.gov.pl.social

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.gov.*
import scalascript.gov.social.*
import scalascript.payments.money.{Currency, Money}
import java.time.{LocalDate, YearMonth}
import scala.annotation.nowarn
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.global

@nowarn("msg=not declared infix")
class ZusNrbGeneratorTest extends AnyFunSuite with Matchers:

  test("generate produces 26-digit NRB for SocialInsurance") {
    val nrb = ZusNrbGenerator.generate("1234567890", ContributionType.SocialInsurance)
    nrb should have length 26
    nrb.forall(_.isDigit) shouldBe true
  }

  test("generate produces 26-digit NRB for HealthInsurance") {
    val nrb = ZusNrbGenerator.generate("1234567890", ContributionType.HealthInsurance)
    nrb should have length 26
    nrb.forall(_.isDigit) shouldBe true
  }

  test("generate produces 26-digit NRB for LaborFund") {
    val nrb = ZusNrbGenerator.generate("1234567890", ContributionType.LaborFund)
    nrb should have length 26
    nrb.forall(_.isDigit) shouldBe true
  }

  test("generate produces 26-digit NRB for FunduszGwarantowanych") {
    val nrb = ZusNrbGenerator.generate("1234567890", ContributionType.FunduszGwarantowanych)
    nrb should have length 26
    nrb.forall(_.isDigit) shouldBe true
  }

  test("generateIban produces 28-character PL IBAN") {
    val iban = ZusNrbGenerator.generateIban("1234567890", ContributionType.SocialInsurance)
    iban should have length 28
    iban should startWith("PL")
    iban.drop(2).forall(_.isDigit) shouldBe true
  }

  test("different NIPs produce different NRBs") {
    val nrb1 = ZusNrbGenerator.generate("1234567890", ContributionType.SocialInsurance)
    val nrb2 = ZusNrbGenerator.generate("9876543210", ContributionType.SocialInsurance)
    nrb1 should not equal nrb2
  }

  test("different contribution types produce different NRBs for same NIP") {
    val nip  = "1234567890"
    val nrb1 = ZusNrbGenerator.generate(nip, ContributionType.SocialInsurance)
    val nrb2 = ZusNrbGenerator.generate(nip, ContributionType.HealthInsurance)
    nrb1 should not equal nrb2
  }

  test("same NIP always produces same NRB (deterministic)") {
    val nrb1 = ZusNrbGenerator.generate("5252344078", ContributionType.SocialInsurance)
    val nrb2 = ZusNrbGenerator.generate("5252344078", ContributionType.SocialInsurance)
    nrb1 shouldBe nrb2
  }

  test("MOD-97 check digits satisfy IBAN validation rule") {
    val nrb  = ZusNrbGenerator.generate("1234567890", ContributionType.SocialInsurance)
    val iban = s"PL$nrb"
    val rearranged = iban.drop(4) + iban.take(4)
    val numeric = rearranged.map {
      case c if c.isDigit  => c.toString
      case c               => (c - 'A' + 10).toString
    }.mkString
    val remainder = BigInt(numeric) % 97
    remainder shouldBe 1
  }

  test("NRB starts with sort code for SocialInsurance (10101023)") {
    val nrb = ZusNrbGenerator.generate("1234567890", ContributionType.SocialInsurance)
    nrb.substring(2, 10) shouldBe "10101023"
  }

  test("NRB starts with sort code for HealthInsurance (10101024)") {
    val nrb = ZusNrbGenerator.generate("1234567890", ContributionType.HealthInsurance)
    nrb.substring(2, 10) shouldBe "10101024"
  }

@nowarn("msg=not declared infix")
class ZusContributionCalculatorTest extends AnyFunSuite with Matchers:

  private val PLN = Currency("PLN")

  private val employer = BusinessEntity(
    name = "Employer Sp. z o.o.", legalForm = LegalForm.LimitedLiabilityCompany,
    country = CountryCode.PL,
    taxIds = List(TaxIdentifier(TaxIdType.NIP, TaxId("1234567890"), CountryCode.PL)),
    address = Address("ul. Testowa 1", None, "Warszawa", "00-001", CountryCode.PL),
  )

  private val empRecord = EmployeeRecord(
    firstName = "Jan", lastName = "Kowalski",
    pesel = Some(TaxId("90010112345")),
    birthDate = LocalDate.of(1990, 1, 1),
    address = Address("ul. Prywatna 2", None, "Kraków", "30-001", CountryCode.PL),
    contractType = ContractType.Employment,
    startDate = LocalDate.of(2024, 1, 1),
    employer = employer,
  )

  private def params(base: Long) = ContributionParams(
    entity = employer,
    period = YearMonth.of(2024, 1),
    baseAmount = Money(base, PLN),
    contributionBase = ContributionBase.Employee(empRecord),
  )

  test("calculateContributions returns non-negative values for each component") {
    val calc = ZusContributionCalculator.calculate(params(500000)) // 5000 PLN base
    calc.pension.minorUnits should be > 0L
    calc.disability.minorUnits should be > 0L
    calc.sickness.minorUnits should be > 0L
    calc.accident.minorUnits should be > 0L
    calc.health.minorUnits should be > 0L
    calc.laborFund.minorUnits should be > 0L
    calc.fgsp.minorUnits should be > 0L
  }

  test("total equals sum of all components") {
    val calc = ZusContributionCalculator.calculate(params(500000))
    val sum = calc.pension.minorUnits + calc.disability.minorUnits + calc.sickness.minorUnits +
              calc.accident.minorUnits + calc.health.minorUnits + calc.laborFund.minorUnits +
              calc.fgsp.minorUnits
    calc.total.minorUnits shouldBe sum
  }

  test("pension rate 19.52% of base") {
    val base = 500000L
    val calc = ZusContributionCalculator.calculate(params(base))
    val expected = (BigDecimal(base) * BigDecimal("0.1952")).setScale(0, scala.math.BigDecimal.RoundingMode.HALF_UP).toLong
    calc.pension.minorUnits shouldBe expected
  }

  test("health insurance rate 9.00% of base") {
    val base = 500000L
    val calc = ZusContributionCalculator.calculate(params(base))
    val expected = (BigDecimal(base) * BigDecimal("0.0900")).setScale(0, scala.math.BigDecimal.RoundingMode.HALF_UP).toLong
    calc.health.minorUnits shouldBe expected
  }

  test("disability rate 8.00% of base") {
    val base = 500000L
    val calc = ZusContributionCalculator.calculate(params(base))
    val expected = (BigDecimal(base) * BigDecimal("0.0800")).setScale(0, scala.math.BigDecimal.RoundingMode.HALF_UP).toLong
    calc.disability.minorUnits shouldBe expected
  }

  test("sickness rate 2.45% of base") {
    val base = 500000L
    val calc = ZusContributionCalculator.calculate(params(base))
    val expected = (BigDecimal(base) * BigDecimal("0.0245")).setScale(0, scala.math.BigDecimal.RoundingMode.HALF_UP).toLong
    calc.sickness.minorUnits shouldBe expected
  }

  test("FGŚP rate 0.10% of base") {
    val base = 500000L
    val calc = ZusContributionCalculator.calculate(params(base))
    val expected = (BigDecimal(base) * BigDecimal("0.0010")).setScale(0, scala.math.BigDecimal.RoundingMode.HALF_UP).toLong
    calc.fgsp.minorUnits shouldBe expected
  }

  test("contributions all use same currency as base") {
    val calc = ZusContributionCalculator.calculate(params(500000))
    calc.pension.currency shouldBe PLN
    calc.health.currency shouldBe PLN
    calc.total.currency shouldBe PLN
  }

  test("period is preserved in result") {
    val period = YearMonth.of(2024, 3)
    val calc   = ZusContributionCalculator.calculate(params(500000).copy(period = period))
    calc.period shouldBe period
  }

  test("zero base results in zero contributions") {
    val calc = ZusContributionCalculator.calculate(params(0))
    calc.total.minorUnits shouldBe 0L
    calc.pension.minorUnits shouldBe 0L
  }

@nowarn("msg=not declared infix")
class PlZusAdapterTest extends AnyFunSuite with Matchers:

  private given ExecutionContext = global
  private def await[T](f: Future[T]): T = Await.result(f, Duration(10, "seconds"))

  private val cfg = PlZusConfig(TaxId("1234567890"), "user", "pass".toCharArray)
  private val PLN = Currency("PLN")

  private val employer = BusinessEntity(
    name = "Employer Sp. z o.o.", legalForm = LegalForm.LimitedLiabilityCompany,
    country = CountryCode.PL,
    taxIds = List(TaxIdentifier(TaxIdType.NIP, TaxId("1234567890"), CountryCode.PL)),
    address = Address("ul. Testowa 1", None, "Warszawa", "00-001", CountryCode.PL),
  )

  private val employee = EmployeeRecord(
    firstName = "Anna", lastName = "Nowak",
    pesel = Some(TaxId("85060512345")),
    birthDate = LocalDate.of(1985, 6, 5),
    address = Address("ul. Prywatna 3", None, "Gdańsk", "80-001", CountryCode.PL),
    contractType = ContractType.Employment,
    startDate = LocalDate.of(2024, 2, 1),
    employer = employer,
  )

  private val sampleDecl = ContributionDeclaration(
    declarationType = "DRA",
    period          = YearMonth.of(2024, 1),
    employer        = employer,
    xmlContent      = "<DRA><Kwota>12345</Kwota></DRA>",
    schemaVersion   = "4-0",
  )

  private val submitOkResp   = "<Response><IdDokumentu>ZUS-REF-001</IdDokumentu></Response>"
  private val submitErrResp  = "<Response><KodBledu>BLAD01</KodBledu><OpisBledu>Invalid NIP</OpisBledu></Response>"
  private val statusOkResp   = "<Response><Status>OK</Status></Response>"
  private val statusProcResp = "<Response><Status>PRZETWARZANY</Status></Response>"

  private def adapter(postResps: List[String], getResps: List[String] = Nil): PlZusAdapter =
    val postQ = collection.mutable.Queue.from(postResps)
    val getQ  = collection.mutable.Queue.from(getResps)
    new PlZusAdapter(cfg) {
      override protected def postXml(path: String, body: String): String =
        if postQ.isEmpty then throw BureauError.ServiceUnavailable("no more responses")
        else postQ.dequeue()
      override protected def getXml(path: String): String =
        if getQ.isEmpty then throw BureauError.ServiceUnavailable("no more responses")
        else getQ.dequeue()
    }

  test("submitDeclaration DRA returns Pending with reference number") {
    val a   = adapter(List(submitOkResp))
    val res = await(a.submitDeclaration(sampleDecl))
    res.submissionId shouldBe "ZUS-REF-001"
    res.status.isInstanceOf[SubmissionStatus.Pending] shouldBe true
  }

  test("submitDeclaration includes employer NIP and type in KEDU") {
    val bodies = collection.mutable.ListBuffer.empty[String]
    val a = new PlZusAdapter(cfg) {
      override protected def postXml(path: String, body: String): String =
        bodies += body
        submitOkResp
    }
    await(a.submitDeclaration(sampleDecl))
    val body = bodies.head
    body should include("1234567890")
    body should include("DRA")
  }

  test("submitDeclaration returns Rejected on error response") {
    val a   = adapter(List(submitErrResp))
    val res = await(a.submitDeclaration(sampleDecl))
    res.status.isInstanceOf[SubmissionStatus.Rejected] shouldBe true
    val rej: SubmissionStatus.Rejected = res.status.asInstanceOf[SubmissionStatus.Rejected]
    rej.errors.head.code shouldBe "BLAD01"
  }

  test("pollDeclarationStatus returns Accepted for OK status") {
    val a   = adapter(Nil, List(statusOkResp))
    val res = await(a.pollDeclarationStatus("ZUS-REF-001"))
    res.status shouldBe SubmissionStatus.Accepted
  }

  test("pollDeclarationStatus returns Processing for PRZETWARZANY") {
    val a   = adapter(Nil, List(statusProcResp))
    val res = await(a.pollDeclarationStatus("ZUS-REF-001"))
    res.status shouldBe SubmissionStatus.Processing
  }

  test("getPaymentReference generates NRB for employer NIP") {
    val a   = adapter(Nil)
    val ref = await(a.getPaymentReference(employer, YearMonth.of(2024, 1)))
    ref.accountNumber should have length 26
    ref.accountNumber.forall(_.isDigit) shouldBe true
  }

  test("getPaymentReference due date is 15th of following month for non-December") {
    val a   = adapter(Nil)
    val ref = await(a.getPaymentReference(employer, YearMonth.of(2024, 3)))
    ref.dueDate shouldBe LocalDate.of(2024, 4, 15)
  }

  test("getPaymentReference due date is 20th for December") {
    val a   = adapter(Nil)
    val ref = await(a.getPaymentReference(employer, YearMonth.of(2024, 12)))
    ref.dueDate shouldBe LocalDate.of(2025, 1, 20)
  }

  test("getPaymentReference metadata includes health IBAN") {
    val a   = adapter(Nil)
    val ref = await(a.getPaymentReference(employer, YearMonth.of(2024, 1)))
    ref.metadata.get("health") shouldBe defined
    ref.metadata.get("health").get should startWith("PL")
  }

  test("calculateContributions is synchronous (uses local formula)") {
    val a      = adapter(Nil)
    val params = ContributionParams(employer, YearMonth.of(2024, 1), Money(500000, PLN), ContributionBase.SelfEmployed)
    val calc   = await(a.calculateContributions(params))
    calc.total.minorUnits should be > 0L
    calc.pension.minorUnits should be > 0L
  }

  test("registerEmployee includes ZUA KEDU document") {
    val bodies = collection.mutable.ListBuffer.empty[String]
    val a = new PlZusAdapter(cfg) {
      override protected def postXml(path: String, body: String): String =
        bodies += body
        submitOkResp
    }
    await(a.registerEmployee(employee))
    val body = bodies.head
    body should include("ZUA")
    body should include("Anna")
    body should include("Nowak")
    body should include("85060512345")
  }

  test("deregisterEmployee includes ZWUA KEDU with reason code") {
    val bodies = collection.mutable.ListBuffer.empty[String]
    val a = new PlZusAdapter(cfg) {
      override protected def postXml(path: String, body: String): String =
        bodies += body
        submitOkResp
    }
    await(a.deregisterEmployee(employee, DeregistrationReason.Termination, LocalDate.of(2024, 3, 31)))
    val body = bodies.head
    body should include("ZWUA")
    body should include("100")  // Termination code
    body should include("2024-03-31")
  }

  test("updateEmployee includes ZIUA KEDU document") {
    val bodies = collection.mutable.ListBuffer.empty[String]
    val a = new PlZusAdapter(cfg) {
      override protected def postXml(path: String, body: String): String =
        bodies += body
        submitOkResp
    }
    await(a.updateEmployee(employee))
    bodies.head should include("ZIUA")
  }

  test("submitDeclaration on 429 throws RateLimitError") {
    val a = new PlZusAdapter(cfg) {
      override protected def postXml(path: String, body: String): String =
        throw BureauError.RateLimitError(Some(60))
    }
    an[BureauError.RateLimitError] should be thrownBy await(a.submitDeclaration(sampleDecl))
  }

  test("parseZusResponse extracts reference from XML") {
    val a   = adapter(Nil)
    val xml = "<Response><IdDokumentu>TEST-REF-XYZ</IdDokumentu></Response>"
    val res = a.parseZusResponse(xml)
    res.submissionId shouldBe "TEST-REF-XYZ"
  }

  test("extractXmlTag handles namespaced tags") {
    val a = adapter(Nil)
    a.extractXmlTag("<ns:Tag><ns:Val>hello</ns:Val></ns:Tag>", "Val") shouldBe Some("hello")
  }
