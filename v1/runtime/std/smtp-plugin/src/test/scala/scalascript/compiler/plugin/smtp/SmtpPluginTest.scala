package scalascript.compiler.plugin.smtp

import java.io.{BufferedReader, InputStreamReader}
import java.net.{ServerSocket, Socket}
import java.nio.charset.StandardCharsets
import javax.net.ssl.{SSLContext, SSLSocket}

import org.scalatest.funsuite.AnyFunSuite
import scalascript.testkit.TestInterpreter

/** specs/smtp-send.md — drives the hand-rolled SMTP client end-to-end through the
 *  interpreter against [[FakeSmtpServer]], an in-process SMTP server we fully
 *  control (no embedded-server dependency).  Covers plain delivery, AUTH LOGIN,
 *  a real STARTTLS handshake before AUTH, multi-recipient fan-out, permanent
 *  classification on auth failure, and a bounded error on an unreachable host. */
class SmtpPluginTest extends AnyFunSuite:

  private def interp: TestInterpreter =
    TestInterpreter(List(SmtpInterpreterPlugin()))

  private def evalStr(snippet: String): String =
    interp.eval(snippet).asInstanceOf[String]

  // A minimal valid RFC 5322 message; `\\n` is a newline in the .ssc literal.
  private def message(body: String): String =
    s"Subject: Test\\nFrom: a@x.com\\nTo: b@y.com\\n\\n$body"

  test("plain SMTP delivers the message"):
    val srv = FakeSmtpServer.start()
    try
      val reply = evalStr(
        s"""smtpSend("127.0.0.1", ${srv.port}, "", "", false,
           |  "a@x.com", List("b@y.com"), "${message("hello plain")}")""".stripMargin)
      assert(reply.startsWith("250"), s"expected 250, got: $reply")
      val rec = srv.awaitDone()
      assert(rec.data.contains("hello plain"))
      assert(rec.recipients == List("b@y.com"))
      assert(!rec.tlsUsed)
    finally srv.close()

  test("AUTH LOGIN succeeds with correct credentials"):
    val srv = FakeSmtpServer.start(authUser = Some("smtpuser"), authPass = Some("s3cret"))
    try
      val reply = evalStr(
        s"""smtpSend("127.0.0.1", ${srv.port}, "smtpuser", "s3cret", false,
           |  "a@x.com", List("b@y.com"), "${message("hello auth")}")""".stripMargin)
      assert(reply.startsWith("250"))
      val rec = srv.awaitDone()
      assert(rec.authedUser.contains("smtpuser"))
      assert(rec.data.contains("hello auth"))
    finally srv.close()

  test("STARTTLS negotiates TLS before AUTH and delivers over the upgraded socket"):
    val srv = FakeSmtpServer.start(
      tls = true, authUser = Some("smtpuser"), authPass = Some("s3cret"))
    System.setProperty("ssc.smtp.insecureTrustAll", "true")  // trust the test self-signed cert
    try
      val reply = evalStr(
        s"""smtpSend("127.0.0.1", ${srv.port}, "smtpuser", "s3cret", true,
           |  "a@x.com", List("b@y.com"), "${message("hello tls")}")""".stripMargin)
      assert(reply.startsWith("250"))
      val rec = srv.awaitDone()
      assert(rec.tlsUsed, "server should have upgraded to TLS")
      assert(rec.authedAfterTls, "AUTH must happen after the TLS upgrade")
      assert(rec.data.contains("hello tls"))
    finally
      srv.close()
      System.clearProperty("ssc.smtp.insecureTrustAll")

  test("multiple RCPT TO recipients are all sent"):
    val srv = FakeSmtpServer.start()
    try
      val reply = evalStr(
        s"""smtpSend("127.0.0.1", ${srv.port}, "", "", false,
           |  "a@x.com", List("b@y.com", "c@y.com"), "${message("hello many")}")""".stripMargin)
      assert(reply.startsWith("250"))
      assert(srv.awaitDone().recipients == List("b@y.com", "c@y.com"))
    finally srv.close()

  test("auth failure is classified permanent and does not hang"):
    val srv = FakeSmtpServer.start(authUser = Some("smtpuser"), authPass = Some("right"))
    try
      val ex = intercept[Exception](evalStr(
        s"""smtpSend("127.0.0.1", ${srv.port}, "smtpuser", "wrong", false,
           |  "a@x.com", List("b@y.com"), "${message("nope")}")""".stripMargin))
      assert(ex.getMessage.contains("permanent"), s"expected permanent, got: ${ex.getMessage}")
    finally srv.close()

  test("unreachable host raises a bounded error, never hangs"):
    val port = FakeSmtpServer.freePort() // nothing is listening here
    val ex = intercept[Exception](evalStr(
      s"""smtpSend("127.0.0.1", $port, "", "", false,
         |  "a@x.com", List("b@y.com"), "${message("x")}")""".stripMargin))
    assert(ex.getMessage.contains("cannot reach") || ex.getMessage.contains("transient"),
      s"expected a connect error, got: ${ex.getMessage}")

/** A tiny single-connection SMTP server for tests.  Speaks just enough of RFC
 *  5321 to exercise the client: greeting, EHLO (advertising STARTTLS when `tls`),
 *  optional STARTTLS upgrade with a runtime-generated self-signed cert, optional
 *  AUTH LOGIN validation, MAIL/RCPT/DATA capture.  Runs the dialogue on a daemon
 *  thread; the test reads the captured [[FakeSmtpServer.Received]] via awaitDone. */
final class FakeSmtpServer private (
    val server: ServerSocket, tls: Boolean,
    authUser: Option[String], authPass: Option[String]):
  import FakeSmtpServer.*

  def port: Int = server.getLocalPort

  @volatile private var received: Received = null
  @volatile private var error: Throwable   = null
  private val done = new java.util.concurrent.CountDownLatch(1)

  private val thread = new Thread(() => {
    try received = serve()
    catch case t: Throwable => error = t
    finally done.countDown()
  }, "fake-smtp")
  thread.setDaemon(true)
  thread.start()

  def awaitDone(): Received =
    if !done.await(10, java.util.concurrent.TimeUnit.SECONDS) then
      throw new AssertionError("fake SMTP server did not finish in time")
    if error != null then throw error
    received

  def close(): Unit =
    try server.close() catch case _: Throwable => ()

  private def serve(): Received =
    val sock = server.accept()
    sock.setSoTimeout(10000)
    var in  = reader(sock)
    var out = sock.getOutputStream
    def send(s: String): Unit = { out.write((s + "\r\n").getBytes(StandardCharsets.UTF_8)); out.flush() }

    var tlsUsed         = false
    var authedUser      = Option.empty[String]
    var authedAfterTls  = false
    val recipients      = scala.collection.mutable.ListBuffer.empty[String]

    send("220 fake-smtp ready")
    var line = in.readLine()
    var quit = false
    val data = new StringBuilder
    while line != null && !quit do
      val upper = line.toUpperCase
      if upper.startsWith("EHLO") || upper.startsWith("HELO") then
        if tls && !tlsUsed then send("250-fake-smtp\r\n250 STARTTLS")
        else send("250 fake-smtp")
      else if upper.startsWith("STARTTLS") then
        send("220 go ahead")
        val ssl = serverTls(sock)
        ssl.startHandshake()
        tlsUsed = true
        in  = reader(ssl)
        out = ssl.getOutputStream
      else if upper.startsWith("AUTH LOGIN") then
        send("334 VXNlcm5hbWU6")                       // "Username:"
        val u = new String(java.util.Base64.getDecoder.decode(in.readLine().trim), "UTF-8")
        send("334 UGFzc3dvcmQ6")                       // "Password:"
        val p = new String(java.util.Base64.getDecoder.decode(in.readLine().trim), "UTF-8")
        if authUser.contains(u) && authPass.contains(p) then
          authedUser = Some(u); authedAfterTls = tlsUsed; send("235 2.7.0 Authentication successful")
        else
          send("535 5.7.8 Authentication credentials invalid")
      else if upper.startsWith("MAIL FROM") then send("250 2.1.0 Ok")
      else if upper.startsWith("RCPT TO") then
        recipients += line.substring(line.indexOf('<') + 1, line.indexOf('>'))
        send("250 2.1.5 Ok")
      else if upper.startsWith("DATA") then
        send("354 End data with <CR><LF>.<CR><LF>")
        var dl = in.readLine()
        while dl != null && dl != "." do { data.append(dl).append("\n"); dl = in.readLine() }
        send("250 2.0.0 Ok: queued")
      else if upper.startsWith("QUIT") then { send("221 Bye"); quit = true }
      else send("250 Ok")
      if !quit then line = in.readLine()

    Received(recipients.toList, data.toString, tlsUsed, authedUser, authedAfterTls)

  private def serverTls(plain: Socket): SSLSocket =
    val ctx = FakeSmtpServer.serverSslContext
    val ssl = ctx.getSocketFactory
      .createSocket(plain, plain.getInetAddress.getHostAddress, plain.getPort, true)
      .asInstanceOf[SSLSocket]
    ssl.setUseClientMode(false)
    ssl

  private def reader(s: Socket): BufferedReader =
    new BufferedReader(new InputStreamReader(s.getInputStream, StandardCharsets.UTF_8))

object FakeSmtpServer:

  final case class Received(
      recipients: List[String], data: String, tlsUsed: Boolean,
      authedUser: Option[String], authedAfterTls: Boolean)

  def freePort(): Int =
    val s = new ServerSocket(0)
    try s.getLocalPort finally s.close()

  def start(
      tls: Boolean = false,
      authUser: Option[String] = None,
      authPass: Option[String] = None): FakeSmtpServer =
    new FakeSmtpServer(new ServerSocket(0), tls, authUser, authPass)

  /** A self-signed RSA keystore, generated once via the JDK `keytool`, backing
   *  the server side of the STARTTLS handshake. */
  private lazy val serverSslContext: SSLContext =
    val ks = java.io.File.createTempFile("ssc-smtp-test", ".p12")
    ks.delete()
    val pb = new ProcessBuilder(
      keytool, "-genkeypair", "-alias", "smtp", "-keyalg", "RSA", "-keysize", "2048",
      "-validity", "1", "-dname", "CN=localhost", "-storetype", "PKCS12",
      "-keystore", ks.getAbsolutePath, "-storepass", "changeit", "-keypass", "changeit")
    pb.redirectErrorStream(true)
    val proc = pb.start()
    val log  = new String(proc.getInputStream.readAllBytes(), "UTF-8")
    if proc.waitFor() != 0 then throw new RuntimeException(s"keytool failed:\n$log")
    val store = java.security.KeyStore.getInstance("PKCS12")
    val fis = new java.io.FileInputStream(ks)
    try store.load(fis, "changeit".toCharArray) finally fis.close()
    ks.delete()
    val kmf = javax.net.ssl.KeyManagerFactory.getInstance(
      javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm)
    kmf.init(store, "changeit".toCharArray)
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(kmf.getKeyManagers, null, new java.security.SecureRandom())
    ctx

  private def keytool: String =
    val home = System.getProperty("java.home")
    val exe  = new java.io.File(home, "bin/keytool")
    if exe.exists() then exe.getAbsolutePath else "keytool"
