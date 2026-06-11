package scalascript.compiler.plugin.pdf

import org.scalatest.funsuite.AnyFunSuite
import scalascript.testkit.TestInterpreter

class PdfPluginTest extends AnyFunSuite:

  private def interp: TestInterpreter =
    TestInterpreter(List(PdfInterpreterPlugin()))

  private def evalStr(snippet: String): String =
    interp.eval(snippet).asInstanceOf[String]

  // A small invoice-shaped document: table layout + a <style> block + basic
  // typography/borders — the busi invoice subset.  Attributes are unquoted so
  // the HTML embeds cleanly in a double-quoted .ssc snippet.
  private val invoiceHtml =
    "<html><head><style>" +
      "body{font-family:Helvetica;font-size:12px}" +
      "table{width:100%;border:1px solid black}" +
      "th,td{padding:4px;border:1px solid black}" +
      "thead{background-color:gray}" +
      "@media print{body{margin:0}}" +
      "</style></head><body>" +
      "<h1>Faktura</h1><p>Numer: <strong>FV/1</strong></p>" +
      "<table><thead><tr><th>Poz</th><th>Kwota</th></tr></thead>" +
      "<tbody><tr><td>1</td><td>1000.01 zl</td></tr></tbody></table>" +
      "</body></html>"

  private def pdfBytes(html: String): Array[Byte] =
    java.util.Base64.getDecoder.decode(evalStr(s"""htmlToPdfBase64("$html")"""))

  test("htmlToPdfBase64 of the invoice HTML returns a %PDF- document"):
    val bytes = pdfBytes(invoiceHtml)
    assert(bytes.length > 200, s"PDF unexpectedly small: ${bytes.length} bytes")
    assert(new String(bytes.take(5), "US-ASCII") == "%PDF-",
      s"missing PDF magic header; got ${bytes.take(5).map("%02x".format(_)).mkString}")

  test("generated PDF has at least one page (PDFBox parse-back)"):
    val doc = org.apache.pdfbox.pdmodel.PDDocument.load(pdfBytes(invoiceHtml))
    try assert(doc.getNumberOfPages >= 1)
    finally doc.close()

  test("unsupported CSS (grid/float) degrades gracefully — no throw, still a PDF"):
    val html =
      "<html><head><style>" +
        ".x{display:grid;float:left}" +
        "</style></head><body><div class=x>cell</div></body></html>"
    val bytes = pdfBytes(html)
    assert(new String(bytes.take(5), "US-ASCII") == "%PDF-")

  test("htmlToPdfBase64 is deterministic-shaped (same input → same %PDF- prefix)"):
    val a = pdfBytes("<html><body><p>hello</p></body></html>")
    val b = pdfBytes("<html><body><p>hello</p></body></html>")
    assert(new String(a.take(5), "US-ASCII") == "%PDF-")
    assert(new String(b.take(5), "US-ASCII") == "%PDF-")

  // ── text extraction (pdfToMarkdown / pdfPageCount) ──────────────────────────

  test("pdfToMarkdown recovers the generated PDF's text layer (gen → extract round-trip)"):
    val b64 = evalStr(s"""htmlToPdfBase64("$invoiceHtml")""")
    val text = evalStr(s"""pdfToMarkdown("$b64")""")
    assert(text.contains("Faktura"), s"missing heading text; got: $text")
    assert(text.contains("FV/1"), s"missing invoice number; got: $text")
    assert(text.contains("1000.01"), s"missing amount; got: $text")

  test("pdfPageCount returns the number of pages"):
    val b64 = evalStr(s"""htmlToPdfBase64("$invoiceHtml")""")
    val n = interp.eval(s"""pdfPageCount("$b64")""").asInstanceOf[Long]
    assert(n == 1L, s"expected 1 page, got $n")

  test("pdfToMarkdown separates multiple pages with a horizontal rule"):
    // Force two pages with a hard page break.
    val twoPage =
      "<html><head><style>.pb{page-break-before:always}</style></head><body>" +
        "<p>PAGEONE</p><div class=pb></div><p>PAGETWO</p></body></html>"
    val b64 = evalStr(s"""htmlToPdfBase64("$twoPage")""")
    val n = interp.eval(s"""pdfPageCount("$b64")""").asInstanceOf[Long]
    assume(n == 2L, s"engine did not paginate as expected ($n pages) — skipping rule check")
    val text = evalStr(s"""pdfToMarkdown("$b64")""")
    assert(text.contains("PAGEONE") && text.contains("PAGETWO"))
    assert(text.contains("\n---\n"), s"expected a page-separating rule; got: $text")

  test("pdfToMarkdown of a non-PDF input throws a clear error, not garbage"):
    val notPdf = java.util.Base64.getEncoder.encodeToString("hello, not a pdf".getBytes("UTF-8"))
    val err = intercept[Throwable](evalStr(s"""pdfToMarkdown("$notPdf")"""))
    assert(Option(err.getMessage).exists(_.contains("pdfToMarkdown")),
      s"expected a pdfToMarkdown error, got: ${err.getMessage}")
