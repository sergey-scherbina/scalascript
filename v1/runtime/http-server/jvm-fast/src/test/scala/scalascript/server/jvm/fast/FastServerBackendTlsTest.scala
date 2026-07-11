package scalascript.server.jvm.fast

import org.scalatest.funsuite.AnyFunSuite
import scalascript.server.{Request, Response}
import scalascript.server.spi.*
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import java.security.{KeyStore, SecureRandom}
import java.util.Base64
import javax.net.ssl.{SSLContext, TrustManager, X509TrustManager}

/** Exercises the TLS path of the fast backend end-to-end — the `SSLServerSocket` branch had no
  * coverage. Also verifies #6: under HTTPS the session cookie carries `Secure`. The self-signed
  * cert is generated with `keytool` (always in JAVA_HOME) + exported to PEM in-JVM (no openssl);
  * the test cancels if keytool is somehow unavailable. */
class FastServerBackendTlsTest extends AnyFunSuite:

  private def keytoolPath: String =
    val exe = new java.io.File(System.getProperty("java.home"), "bin/keytool")
    if exe.exists() then exe.getAbsolutePath else "keytool"

  private def selfSignedPem(): (Path, Path) =
    val dir = Files.createTempDirectory("ssc-tls-test")
    val ks  = dir.resolve("ks.p12")
    val proc = new ProcessBuilder(keytoolPath, "-genkeypair", "-alias", "t", "-keyalg", "RSA",
      "-keysize", "2048", "-dname", "CN=localhost", "-validity", "1",
      "-ext", "san=ip:127.0.0.1,dns:localhost", "-storetype", "PKCS12",
      "-keystore", ks.toString, "-storepass", "changeit", "-keypass", "changeit", "-noprompt")
      .redirectErrorStream(true).start()
    val log = new String(proc.getInputStream.readAllBytes())
    if proc.waitFor() != 0 then throw new RuntimeException(s"keytool failed:\n$log")
    val store = KeyStore.getInstance("PKCS12")
    val in = Files.newInputStream(ks)
    try store.load(in, "changeit".toCharArray) finally in.close()
    val cert = store.getCertificate("t")
    val key  = store.getKey("t", "changeit".toCharArray)
    val enc  = Base64.getMimeEncoder(64, "\n".getBytes("US-ASCII"))
    val certPem = dir.resolve("cert.pem")
    val keyPem  = dir.resolve("key.pem")
    Files.writeString(certPem,
      s"-----BEGIN CERTIFICATE-----\n${enc.encodeToString(cert.getEncoded)}\n-----END CERTIFICATE-----\n")
    Files.writeString(keyPem,
      s"-----BEGIN PRIVATE KEY-----\n${enc.encodeToString(key.getEncoded)}\n-----END PRIVATE KEY-----\n")
    (certPem, keyPem)

  private def trustAllClient(): HttpClient =
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, Array[TrustManager](new X509TrustManager:
      def getAcceptedIssuers: Array[java.security.cert.X509Certificate] = Array.empty
      def checkClientTrusted(c: Array[java.security.cert.X509Certificate], a: String): Unit = ()
      def checkServerTrusted(c: Array[java.security.cert.X509Certificate], a: String): Unit = ()
    ), new SecureRandom())
    HttpClient.newBuilder().sslContext(ctx).build()

  test("serves HTTPS over the fast backend and sets a Secure session cookie under TLS") {
    val (cert, key) =
      try selfSignedPem()
      catch case e: Throwable => cancel(s"self-signed cert unavailable: ${e.getMessage}")
    val handler = new HttpHandler:
      def onHttpRequest(req: Request): HttpResult =
        HttpResult.PlainResp(Response(200, Map.empty, "secure:" + req.path).withSession(Map("u" -> "ada")))
      def onWsUpgrade(req: Request): WsUpgradeResult = WsUpgradeResult.Reject(404, "no ws")
    val backend = new FastServerBackend
    backend.start(0, Some(TlsConfig(cert.toString, key.toString)), handler)
    try
      val client = trustAllClient()
      val r = client.send(
        HttpRequest.newBuilder(URI.create(s"https://127.0.0.1:${backend.localPort}/hi")).GET().build(),
        HttpResponse.BodyHandlers.ofString())
      assert(r.statusCode() == 200)
      assert(r.body() == "secure:/hi")
      val cookie = r.headers().firstValue("set-cookie").orElseThrow()
      assert(cookie.toLowerCase.contains("secure"), s"session cookie missing Secure under TLS: $cookie")
    finally backend.stop()
  }
