package scalascript.compiler.plugin.mime

import org.scalatest.funsuite.AnyFunSuite
import scalascript.testkit.TestInterpreter

class MimePluginTest extends AnyFunSuite:

  private def interp: TestInterpreter =
    TestInterpreter(List(MimeInterpreterPlugin()))

  private def evalStr(snippet: String): String =
    interp.eval(snippet).asInstanceOf[String]

  /** Parse a raw RFC 5322 message with the Jakarta Mail (Angus) reference parser. */
  private def parse(mime: String): jakarta.mail.internet.MimeMessage =
    val session = jakarta.mail.Session.getInstance(new java.util.Properties())
    new jakarta.mail.internet.MimeMessage(
      session, new java.io.ByteArrayInputStream(mime.getBytes("UTF-8")))

  private def b64(s: String): String =
    java.util.Base64.getEncoder.encodeToString(s.getBytes("UTF-8"))

  test("no attachments → a valid text/html email; body round-trips"):
    val mime = evalStr(
      """buildMimeMessage("a@x.com", "b@y.com", "Faktura",
        |  "<html><body><p>Witaj</p></body></html>", List())""".stripMargin)
    val msg = parse(mime)
    assert(msg.getFrom()(0).toString == "a@x.com")
    assert(msg.getAllRecipients()(0).toString == "b@y.com")
    assert(msg.getSubject == "Faktura")
    assert(msg.getContentType.toLowerCase.startsWith("text/html"))
    assert(msg.getContent.toString.contains("<p>Witaj</p>"))

  test("non-ASCII subject is RFC 2047 encoded and decodes back"):
    val mime = evalStr(
      """buildMimeMessage("a@x.com", "b@y.com", "Faktura zażółć gęślą",
        |  "<html><body>hi</body></html>", List())""".stripMargin)
    assert(parse(mime).getSubject == "Faktura zażółć gęślą")

  test("one attachment → multipart/mixed; html part + attachment round-trip"):
    val pdfB64 = b64("%PDF-1.4 fake pdf bytes")
    val mime = evalStr(
      s"""buildMimeMessage("a@x.com", "b@y.com", "Invoice",
         |  "<html><body><p>see attached</p></body></html>",
         |  List(("invoice.pdf", "application/pdf", "$pdfB64")))""".stripMargin)
    val msg = parse(mime)
    assert(msg.getContentType.toLowerCase.startsWith("multipart/mixed"))
    val mp = msg.getContent.asInstanceOf[jakarta.mail.internet.MimeMultipart]
    assert(mp.getCount == 2, s"expected html + 1 attachment, got ${mp.getCount}")
    // Part 0: the HTML body.
    val html = mp.getBodyPart(0)
    assert(html.getContentType.toLowerCase.contains("text/html"))
    assert(html.getContent.toString.contains("see attached"))
    // Part 1: the attachment.
    val att = mp.getBodyPart(1)
    assert(att.getFileName == "invoice.pdf")
    assert(att.getContentType.toLowerCase.contains("application/pdf"))
    val attBytes = att.getInputStream.readAllBytes()
    assert(new String(attBytes, "UTF-8") == "%PDF-1.4 fake pdf bytes")

  test("two attachments → three parts, both filenames preserved"):
    val a = b64("AAA"); val c = b64("CCC")
    val mime = evalStr(
      s"""buildMimeMessage("a@x.com", "b@y.com", "Two",
         |  "<html><body>two</body></html>",
         |  List(("a.txt", "text/plain", "$a"), ("c.bin", "application/octet-stream", "$c")))""".stripMargin)
    val mp = parse(mime).getContent.asInstanceOf[jakarta.mail.internet.MimeMultipart]
    assert(mp.getCount == 3)
    assert(mp.getBodyPart(1).getFileName == "a.txt")
    assert(mp.getBodyPart(2).getFileName == "c.bin")
