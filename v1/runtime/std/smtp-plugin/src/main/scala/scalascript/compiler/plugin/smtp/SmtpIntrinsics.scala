package scalascript.compiler.plugin.smtp

import java.io.{BufferedReader, InputStreamReader, OutputStream}
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets
import javax.net.ssl.{SSLContext, SSLSocket, SSLSocketFactory, TrustManager, X509TrustManager}

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginError, PluginNative, PluginValue}
import scalascript.plugin.api.PluginValue.{Str, Lst}

/** Hand-rolled, dependency-free SMTP submission client (RFC 5321).
 *
 *  Drives the standard transactional flow — greeting → `EHLO` → optional
 *  `STARTTLS` → optional `AUTH LOGIN` → `MAIL FROM` → `RCPT TO`(×N) → `DATA` →
 *  `QUIT` — over a plain or STARTTLS-upgraded socket.  Connect/read timeouts are
 *  bounded so a dead host never hangs the interpreter.  Failures are raised as
 *  a `PluginError` tagged `permanent` (5xx) or `transient` (4xx) plus the
 *  server reply, so a caller's send/retry audit can classify them.  JVM only. */
object SmtpIntrinsics:

  private val CRLF        = "\r\n"
  private val ConnectMs   = 15000
  private val ReadMs      = 30000

  private def b64(s: String): String =
    java.util.Base64.getEncoder.encodeToString(s.getBytes(StandardCharsets.UTF_8))

  /** True → don't validate the STARTTLS peer certificate (internal relays with a
   *  self-signed cert).  Off by default; opt in with `-Dssc.smtp.insecureTrustAll=true`. */
  private def insecureTrustAll: Boolean =
    sys.props.get("ssc.smtp.insecureTrustAll").exists(_.equalsIgnoreCase("true"))

  private final class Conn(var sock: Socket):
    var in:  BufferedReader = mkReader(sock)
    var out: OutputStream   = sock.getOutputStream

    private def mkReader(s: Socket): BufferedReader =
      new BufferedReader(new InputStreamReader(s.getInputStream, StandardCharsets.UTF_8))

    def rebind(s: Socket): Unit =
      sock = s
      in   = mkReader(s)
      out  = s.getOutputStream

    def write(line: String): Unit =
      out.write((line + CRLF).getBytes(StandardCharsets.UTF_8))
      out.flush()

    /** Read one (possibly multi-line) SMTP reply.  Continuation lines are
     *  `NNN-text`; the final line is `NNN text`.  Returns (code, full text). */
    def reply(): (Int, String) =
      val sb = new StringBuilder
      var code = -1
      var done = false
      while !done do
        val line = in.readLine()
        if line == null then throw err("transient", 421, "connection closed by server")
        if sb.nonEmpty then sb.append("\n")
        sb.append(line)
        if line.length >= 3 then
          try code = line.substring(0, 3).toInt catch case _: Throwable => ()
        // final line when the 4th char is a space (or the line is exactly NNN)
        done = line.length < 4 || line.charAt(3) == ' '
      (code, sb.toString)

  /** Internal control-flow marker for a classified SMTP failure; converted to a
   *  `PluginError` at the intrinsic boundary so it surfaces as a language error. */
  private final class SmtpFail(msg: String) extends RuntimeException(msg)

  private def err(tag: String, code: Int, text: String): SmtpFail =
    new SmtpFail(s"smtpSend: $tag SMTP failure ($code): ${text.replace("\n", " | ")}")

  /** Classify a non-expected reply: 5xx permanent, 4xx (or anything else) transient. */
  private def fail(code: Int, text: String): Nothing =
    val tag = if code >= 500 && code < 600 then "permanent" else "transient"
    throw err(tag, code, text)

  private def expect(c: Conn, wanted: Int): String =
    val (code, text) = c.reply()
    if code != wanted then fail(code, text)
    text

  /** Build the STARTTLS SSL factory — validating by default, trust-all when the
   *  insecure escape hatch is set. */
  private def sslFactory(): SSLSocketFactory =
    if !insecureTrustAll then SSLSocketFactory.getDefault.asInstanceOf[SSLSocketFactory]
    else
      val ctx = SSLContext.getInstance("TLS")
      val trustAll: Array[TrustManager] = Array(new X509TrustManager:
        def checkClientTrusted(c: Array[java.security.cert.X509Certificate], a: String): Unit = ()
        def checkServerTrusted(c: Array[java.security.cert.X509Certificate], a: String): Unit = ()
        def getAcceptedIssuers: Array[java.security.cert.X509Certificate] = Array.empty)
      ctx.init(null, trustAll, new java.security.SecureRandom())
      ctx.getSocketFactory

  private def ehloName: String =
    try java.net.InetAddress.getLocalHost.getCanonicalHostName
    catch case _: Throwable => "localhost"

  private def send(
      host: String, port: Int, username: String, password: String,
      useTls: Boolean, envelopeFrom: String, recipients: List[String], message: String): String =
    if recipients.isEmpty then
      throw new SmtpFail("smtpSend: envelopeTo must have at least one recipient")
    val sock = new Socket()
    try
      sock.connect(new InetSocketAddress(host, port), ConnectMs)
      sock.setSoTimeout(ReadMs)
      val c = new Conn(sock)

      expect(c, 220)                       // greeting
      c.write(s"EHLO $ehloName"); expect(c, 250)

      if useTls then
        c.write("STARTTLS"); expect(c, 220)
        val ssl = sslFactory()
          .createSocket(c.sock, host, port, true).asInstanceOf[SSLSocket]
        ssl.startHandshake()
        c.rebind(ssl)
        c.write(s"EHLO $ehloName"); expect(c, 250)   // re-EHLO over TLS

      if username.nonEmpty then
        c.write("AUTH LOGIN"); expect(c, 334)
        c.write(b64(username)); expect(c, 334)
        c.write(b64(password)); expect(c, 235)        // 235 = auth accepted

      c.write(s"MAIL FROM:<$envelopeFrom>"); expect(c, 250)
      for rcpt <- recipients do
        c.write(s"RCPT TO:<$rcpt>"); expect(c, 250)

      c.write("DATA"); expect(c, 354)
      c.out.write(dotStuff(message).getBytes(StandardCharsets.UTF_8))
      c.out.write((CRLF + "." + CRLF).getBytes(StandardCharsets.UTF_8))
      c.out.flush()
      val finalReply = expect(c, 250)                  // queued

      try { c.write("QUIT"); c.reply() } catch case _: Throwable => ()
      finalReply
    catch
      case e: SmtpFail => throw e
      case e: java.net.SocketTimeoutException =>
        throw err("transient", 0, s"timeout talking to $host:$port — ${e.getMessage}")
      case e: java.io.IOException =>
        throw err("transient", 0, s"cannot reach $host:$port — ${e.getMessage}")
    finally
      try sock.close() catch case _: Throwable => ()

  /** RFC 5321 transparency: a line starting with '.' gets an extra leading '.'.
   *  Normalise bare LF to CRLF first so the dot-stuffing scan sees real lines. */
  private def dotStuff(message: String): String =
    val normalised = message.replace("\r\n", "\n").replace("\r", "\n").replace("\n", CRLF)
    normalised.split("\r\n", -1).map(l => if l.startsWith(".") then "." + l else l).mkString(CRLF)

  private def recipientsOf(v: Any): List[String] = v match
    case Lst(items) => items.map {
      case Str(s) => s
      case other  => throw new SmtpFail(
        s"smtpSend: each recipient must be a String, got ${PluginValue.showAny(other)}")
    }
    case other => throw new SmtpFail(
      s"smtpSend: envelopeTo must be a List[String], got ${PluginValue.showAny(other)}")

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    QualifiedName("smtpSend") -> PluginNative.evalLegacy { (_, args) =>
      try
        args match
          case (host: String) :: (port: Long) :: (username: String) :: (password: String) ::
               (useTls: Boolean) :: (envelopeFrom: String) :: (to: Any) :: (message: String) :: Nil =>
            PluginValue.string(send(host, port.toInt, username, password, useTls,
                               envelopeFrom, recipientsOf(to), message)).unwrap
          case _ =>
            PluginError.raise(
              "smtpSend(host, port, username, password, useTls, envelopeFrom, envelopeTo, message)")
      catch case e: SmtpFail => PluginError.raise(e.getMessage)
    },

  )
