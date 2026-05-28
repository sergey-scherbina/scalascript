package scalascript.gov.signing

import scala.concurrent.{Future, ExecutionContext}
import java.time.Instant

class MockSigningProvider(
  certInfo: CertificateInfo = MockSigningProvider.defaultCert
) extends SigningProvider:

  def id:          String = "mock"
  def displayName: String = "Mock signing provider (tests only)"

  def sign(data: Array[Byte], format: SignatureFormat)(using ExecutionContext): Future[SignedDocument] =
    Future.successful(SignedDocument(
      originalData = data,
      signature    = MockSigningProvider.fakeSignature(data),
      format       = format,
      timestamp    = Instant.now()
    ))

  def verify(doc: SignedDocument)(using ExecutionContext): Future[VerificationResult] =
    val expected = MockSigningProvider.fakeSignature(doc.originalData)
    val valid    = java.util.Arrays.equals(expected, doc.signature)
    Future.successful(VerificationResult(
      valid  = valid,
      signer = if valid then Some(certInfo) else None,
      errors = if valid then Nil else List("Mock signature mismatch")
    ))

  def certificateInfo(using ExecutionContext): Future[CertificateInfo] =
    Future.successful(certInfo)

object MockSigningProvider:

  val defaultCert: CertificateInfo = CertificateInfo(
    subject      = "CN=Mock Test Signer, O=Test Org, C=PL",
    issuer       = "CN=Mock Test CA, O=Test Org, C=PL",
    validFrom    = Instant.parse("2024-01-01T00:00:00Z"),
    validUntil   = Instant.parse("2027-01-01T00:00:00Z"),
    serialNumber = "deadbeef",
    qualified    = false
  )

  def fakeSignature(data: Array[Byte]): Array[Byte] =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.digest(data)
