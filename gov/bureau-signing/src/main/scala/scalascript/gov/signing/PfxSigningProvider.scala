package scalascript.gov.signing

import scala.concurrent.{Future, ExecutionContext}
import java.security.{KeyStore, Signature}
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Arrays

class PfxSigningProvider(config: PfxConfig) extends SigningProvider:

  def id:          String = "pfx"
  def displayName: String = "PFX/PKCS#12 file-based signing"

  private lazy val (privateKey, certificate) =
    val pwCopy = config.password.clone()
    val ks     = KeyStore.getInstance("PKCS12")
    val stream = java.io.FileInputStream(config.keystorePath.toFile)
    try ks.load(stream, pwCopy)
    finally stream.close()
    val alias = config.alias.getOrElse {
      val aliases = ks.aliases()
      if !aliases.hasMoreElements then throw SigningError.KeystoreError("No entries in keystore")
      var found: Option[String] = None
      while aliases.hasMoreElements && found.isEmpty do
        val a = aliases.nextElement()
        if ks.isKeyEntry(a) then found = Some(a)
      found.getOrElse(throw SigningError.KeystoreError("No private key entry in keystore"))
    }
    val key  = ks.getKey(alias, pwCopy).asInstanceOf[java.security.PrivateKey]
    val cert = ks.getCertificate(alias).asInstanceOf[X509Certificate]
    Arrays.fill(pwCopy, ' ')
    (key, cert)

  def sign(data: Array[Byte], format: SignatureFormat)(using ExecutionContext): Future[SignedDocument] =
    Future {
      if format != SignatureFormat.CAdES && format != SignatureFormat.XAdES && format != SignatureFormat.PAdES then
        throw SigningError.UnsupportedFormat(format)
      val sig = Signature.getInstance("SHA256withRSA")
      sig.initSign(privateKey)
      sig.update(data)
      val bytes = sig.sign()
      SignedDocument(data, bytes, format, Instant.now())
    }

  def verify(doc: SignedDocument)(using ExecutionContext): Future[VerificationResult] =
    Future {
      val sig = Signature.getInstance("SHA256withRSA")
      sig.initVerify(certificate.getPublicKey)
      sig.update(doc.originalData)
      val valid = sig.verify(doc.signature)
      val info  = toCertInfo(certificate)
      if valid then VerificationResult(valid = true, signer = Some(info), errors = Nil)
      else VerificationResult(valid = false, signer = None, errors = List("Signature does not match"))
    }

  def certificateInfo(using ExecutionContext): Future[CertificateInfo] =
    Future.successful(toCertInfo(certificate))

  private def toCertInfo(cert: X509Certificate): CertificateInfo =
    CertificateInfo(
      subject      = cert.getSubjectX500Principal.getName,
      issuer       = cert.getIssuerX500Principal.getName,
      validFrom    = cert.getNotBefore.toInstant,
      validUntil   = cert.getNotAfter.toInstant,
      serialNumber = cert.getSerialNumber.toString(16),
      qualified    = false
    )
