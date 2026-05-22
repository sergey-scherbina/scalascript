package scalascript.server

import java.security.{KeyStore, KeyFactory}
import java.security.cert.CertificateFactory
import javax.net.ssl.{KeyManagerFactory, SSLContext}

/** Build an `SSLContext` from PEM-encoded certificate + private key files
 *  on disk.  Handles both PKCS#8 (`-----BEGIN PRIVATE KEY-----`) and
 *  PKCS#1 (`-----BEGIN RSA PRIVATE KEY-----`) RSA keys, plus EC private
 *  keys; the PKCS#1 wrap uses the shared [[DerCodec]] helper.
 *
 *  Single source of truth for TLS bootstrap on the JVM — same code path
 *  for the interpreter's `WebServer.start(..., certPath, keyPath)` and
 *  the JvmGen-emitted `serve(port, tlsCfg)`. */
object TlsContextBuilder:

  def build(certPath: String, keyPath: String): SSLContext =
    val certBytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(certPath))
    val keyBytes  = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(keyPath))

    val certFactory = CertificateFactory.getInstance("X.509")
    val cert = certFactory.generateCertificate(java.io.ByteArrayInputStream(certBytes))

    val keyPemRaw = new String(keyBytes, "UTF-8")
    val keyPem = keyPemRaw
      .replaceAll("-----BEGIN [^-]+-----", "")
      .replaceAll("-----END [^-]+-----", "")
      .replaceAll("\\s", "")
    val rawDer = java.util.Base64.getDecoder.decode(keyPem)

    val isPkcs1 = keyPemRaw.contains("BEGIN RSA PRIVATE KEY") ||
                  !keyPemRaw.contains("BEGIN PRIVATE KEY")
    val pkcs8Der = if isPkcs1 then DerCodec.wrapPkcs1InPkcs8(rawDer) else rawDer

    val keySpec = java.security.spec.PKCS8EncodedKeySpec(pkcs8Der)
    val privateKey =
      try KeyFactory.getInstance("RSA").generatePrivate(keySpec)
      catch case _: Throwable =>
        KeyFactory.getInstance("EC").generatePrivate(keySpec)

    val ks = KeyStore.getInstance("JKS")
    ks.load(null, null)
    ks.setCertificateEntry("cert", cert)
    ks.setKeyEntry("key", privateKey, Array.emptyCharArray, Array(cert))

    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    kmf.init(ks, Array.emptyCharArray)

    val ctx = SSLContext.getInstance("TLS")
    ctx.init(kmf.getKeyManagers, null, null)
    ctx

  /** One virtual thread per accepted connection — requires Java 21 LTS (Project Loom).
   *  Eliminates the one-thread-per-connection OS-thread overhead; a parked virtual
   *  thread costs ~few KB of heap vs ~1 MB for a platform thread stack. */
  def vthreadPool(): java.util.concurrent.ExecutorService =
    // Java 21 requirement: virtual threads are stable in JDK 21 LTS (Project Loom).
    java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
