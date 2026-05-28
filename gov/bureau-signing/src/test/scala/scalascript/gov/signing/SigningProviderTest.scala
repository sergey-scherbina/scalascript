package scalascript.gov.signing

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.global
import java.nio.file.{Files, Path}
import scala.annotation.nowarn
import scala.compiletime.uninitialized

@nowarn("msg=not declared infix")
class MockSigningProviderTest extends AnyFunSuite with Matchers:

  private val mock = MockSigningProvider()
  private given ExecutionContext = global
  private def await[T](f: scala.concurrent.Future[T]): T = Await.result(f, Duration(5, "seconds"))

  test("MockSigningProvider.sign returns a SignedDocument") {
    val data = "Hello, ZUS".getBytes("UTF-8")
    val doc  = await(mock.sign(data, SignatureFormat.XAdES))
    doc.format shouldBe SignatureFormat.XAdES
    doc.originalData shouldBe data
    doc.signature.length should be > 0
  }

  test("MockSigningProvider.verify succeeds for its own signature") {
    val data   = "KSeF invoice payload".getBytes("UTF-8")
    val doc    = await(mock.sign(data, SignatureFormat.CAdES))
    val result = await(mock.verify(doc))
    result.valid shouldBe true
    result.signer shouldBe defined
    result.errors shouldBe empty
  }

  test("MockSigningProvider.verify fails for tampered signature") {
    val data    = "Original data".getBytes("UTF-8")
    val doc     = await(mock.sign(data, SignatureFormat.CAdES))
    val tampered = doc.copy(signature = Array.fill(32)(0.toByte))
    val result  = await(mock.verify(tampered))
    result.valid shouldBe false
    result.errors should not be empty
  }

  test("MockSigningProvider.certificateInfo returns expected cert fields") {
    val info = await(mock.certificateInfo)
    info.subject should include("Mock")
    info.issuer  should include("Mock")
    info.serialNumber should not be empty
  }

  test("MockSigningProvider supports all SignatureFormat values") {
    val data = "test".getBytes("UTF-8")
    for fmt <- SignatureFormat.values do
      val doc = await(mock.sign(data, fmt))
      doc.format shouldBe fmt
  }

  test("MockSigningProvider id and displayName are stable") {
    mock.id          shouldBe "mock"
    mock.displayName should not be empty
  }

  test("MockSigningProvider round-trip works for binary data") {
    val data = (0 to 255).map(_.toByte).toArray
    val doc  = await(mock.sign(data, SignatureFormat.PAdES))
    val res  = await(mock.verify(doc))
    res.valid shouldBe true
  }

@nowarn("msg=not declared infix")
class PfxSigningProviderTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  private given ExecutionContext = global
  private def await[T](f: scala.concurrent.Future[T]): T = Await.result(f, Duration(5, "seconds"))

  private var pfxPath: Path = uninitialized

  override def beforeAll(): Unit =
    pfxPath = generateTestPfx()

  override def afterAll(): Unit =
    if pfxPath != null then Files.deleteIfExists(pfxPath)

  private def provider =
    PfxSigningProvider(PfxConfig(pfxPath, "testpass".toCharArray, None))

  test("PfxSigningProvider.sign returns SignedDocument with CAdES") {
    val data = "Government payload".getBytes("UTF-8")
    val doc  = await(provider.sign(data, SignatureFormat.CAdES))
    doc.format shouldBe SignatureFormat.CAdES
    doc.originalData shouldBe data
    doc.signature.length should be > 0
  }

  test("PfxSigningProvider.sign returns SignedDocument with XAdES") {
    val data = "<Invoice/>".getBytes("UTF-8")
    val doc  = await(provider.sign(data, SignatureFormat.XAdES))
    doc.format shouldBe SignatureFormat.XAdES
  }

  test("PfxSigningProvider.verify validates its own signature") {
    val data   = "Round-trip test".getBytes("UTF-8")
    val doc    = await(provider.sign(data, SignatureFormat.CAdES))
    val result = await(provider.verify(doc))
    result.valid shouldBe true
    result.errors shouldBe empty
  }

  test("PfxSigningProvider.verify rejects tampered signature") {
    val data    = "Untampered".getBytes("UTF-8")
    val doc     = await(provider.sign(data, SignatureFormat.CAdES))
    val tampered = doc.copy(signature = Array.fill(doc.signature.length)(0xff.toByte))
    val result  = await(provider.verify(tampered))
    result.valid shouldBe false
  }

  test("PfxSigningProvider.certificateInfo returns certificate details") {
    val info = await(provider.certificateInfo)
    info.subject should not be empty
    info.issuer  should not be empty
    info.serialNumber should not be empty
  }

  private def generateTestPfx(): Path =
    SelfSignedCertHelper.generatePfxFile("CN=Test Signer, O=Test, C=PL", "testpass")
