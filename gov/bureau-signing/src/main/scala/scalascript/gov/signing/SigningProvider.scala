package scalascript.gov.signing

import scala.concurrent.{Future, ExecutionContext}
import java.time.Instant

trait SigningProvider:
  def id:          String
  def displayName: String

  def sign(data: Array[Byte], format: SignatureFormat)(using ExecutionContext): Future[SignedDocument]
  def verify(doc: SignedDocument)(using ExecutionContext): Future[VerificationResult]
  def certificateInfo(using ExecutionContext): Future[CertificateInfo]

enum SignatureFormat:
  case XAdES   // XML Advanced Electronic Signature
  case PAdES   // PDF Advanced Electronic Signature
  case CAdES   // CMS/PKCS#7 detached
  case JWS     // JSON Web Signature (RFC 7515)

case class SignedDocument(
  originalData: Array[Byte],
  signature:    Array[Byte],
  format:       SignatureFormat,
  timestamp:    Instant
)

case class VerificationResult(
  valid:  Boolean,
  signer: Option[CertificateInfo],
  errors: List[String]
)

case class CertificateInfo(
  subject:      String,
  issuer:       String,
  validFrom:    Instant,
  validUntil:   Instant,
  serialNumber: String,
  qualified:    Boolean
)

sealed abstract class SigningError(message: String, cause: Throwable = null)
  extends Exception(message, cause)

object SigningError:
  case class KeystoreError(message: String, override val getCause: Throwable = null)
    extends SigningError(message, getCause)
  case class CertificateExpired(serialNumber: String, validUntil: Instant)
    extends SigningError(s"Certificate $serialNumber expired at $validUntil")
  case class UnsupportedFormat(format: SignatureFormat)
    extends SigningError(s"Signing format not supported: $format")
  case class VerificationFailed(errors: List[String])
    extends SigningError(s"Signature verification failed: ${errors.mkString(", ")}")
