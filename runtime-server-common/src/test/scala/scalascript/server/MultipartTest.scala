package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class MultipartTest extends AnyFunSuite with Matchers:

  private def body(boundary: String, parts: String*): String =
    parts.map(p => s"--$boundary\r\n$p").mkString("") + s"--$boundary--"

  private val B = "----WebKitFormBoundaryABC123"

  test("parse — simple text field") {
    val rawBody = body(B,
      "Content-Disposition: form-data; name=\"username\"\r\n\r\nalice\r\n"
    )
    val (form, files, tmps) = Multipart.parse(s"multipart/form-data; boundary=$B", rawBody)
    form("username") shouldBe "alice"
    files shouldBe empty
    tmps  shouldBe empty
  }

  test("parse — multiple text fields") {
    val rawBody = body(B,
      "Content-Disposition: form-data; name=\"a\"\r\n\r\n1\r\n",
      "Content-Disposition: form-data; name=\"b\"\r\n\r\n2\r\n"
    )
    val (form, _, _) = Multipart.parse(s"multipart/form-data; boundary=$B", rawBody)
    form shouldBe Map("a" -> "1", "b" -> "2")
  }

  test("parse — file part produces UploadedFile") {
    val fileContent = "hello file"
    val rawBody = body(B,
      s"Content-Disposition: form-data; name=\"upload\"; filename=\"test.txt\"\r\n" +
      s"Content-Type: text/plain\r\n\r\n$fileContent\r\n"
    )
    val (form, files, tmps) = Multipart.parse(s"multipart/form-data; boundary=$B", rawBody)
    form shouldBe empty
    tmps shouldBe empty
    val uf = files("upload")
    uf.filename    shouldBe "test.txt"
    uf.contentType shouldBe "text/plain"
    uf.bytes shouldBe fileContent
  }

  test("parse — missing boundary returns empty maps") {
    val (form, files, _) = Multipart.parse("multipart/form-data", "anything")
    form  shouldBe empty
    files shouldBe empty
  }

  test("parse — file above spoolThreshold is written to temp file") {
    val big     = "x" * 10
    val rawBody = body(B,
      s"Content-Disposition: form-data; name=\"big\"; filename=\"big.bin\"\r\n" +
      s"Content-Type: application/octet-stream\r\n\r\n$big\r\n"
    )
    val (_, files, tmps) = Multipart.parse(
      s"multipart/form-data; boundary=$B",
      rawBody,
      spoolThreshold = 5L
    )
    val uf = files("big")
    uf.path should not be empty
    tmps should have size 1
    // cleanup
    tmps.foreach(_.delete())
  }

  test("parse — mixed text and file parts") {
    val rawBody = body(B,
      "Content-Disposition: form-data; name=\"note\"\r\n\r\nhello\r\n",
      "Content-Disposition: form-data; name=\"doc\"; filename=\"doc.txt\"\r\n" +
      "Content-Type: text/plain\r\n\r\ncontent\r\n"
    )
    val (form, files, _) = Multipart.parse(s"multipart/form-data; boundary=$B", rawBody)
    form("note")        shouldBe "hello"
    files("doc").filename shouldBe "doc.txt"
  }
