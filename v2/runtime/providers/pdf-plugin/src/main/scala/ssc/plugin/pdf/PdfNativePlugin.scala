package ssc.plugin.pdf

import java.io.ByteArrayOutputStream
import java.util.Base64
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import org.jsoup.helper.W3CDom
import org.jsoup.nodes.Document
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import ssc.Value
import ssc.plugin.{NativePlugin, NativePluginContext}

/** Explicit core-free PDF provider for the ScalaScript 2.1 native runtime. */
final class PdfNativePlugin extends NativePlugin:
  def id: String = "90-pdf-explicit"

  private def text(value: Value, operation: String): String = value match
    case Value.StrV(result) => result
    case _ => throw new IllegalArgumentException(s"$operation expects String")

  private def render(html: String): String =
    val bytes = ByteArrayOutputStream()
    try
      val parsed = Jsoup.parse(html)
      parsed.outputSettings().syntax(Document.OutputSettings.Syntax.xml)
      val builder = PdfRendererBuilder()
      builder.useFastMode()
      builder.withW3cDocument(W3CDom().fromJsoup(parsed), "")
      builder.toStream(bytes)
      builder.run()
      Base64.getEncoder.encodeToString(bytes.toByteArray)
    catch case error: Throwable =>
      throw new RuntimeException(s"htmlToPdfBase64: ${error.getMessage}", error)

  private def withPdf[A](encoded: String, operation: String)(fn: PDDocument => A): A =
    val bytes =
      try Base64.getDecoder.decode(encoded.trim)
      catch case error: Throwable =>
        throw new RuntimeException(s"$operation: invalid base64: ${error.getMessage}", error)
    val document =
      try PDDocument.load(bytes)
      catch case error: Throwable =>
        throw new RuntimeException(s"$operation: not a readable PDF: ${error.getMessage}", error)
    try fn(document) finally document.close()

  private def markdown(encoded: String): String = withPdf(encoded, "pdfToMarkdown") { document =>
    val stripper = PDFTextStripper()
    val result = StringBuilder()
    var page = 1
    while page <= document.getNumberOfPages do
      stripper.setStartPage(page)
      stripper.setEndPage(page)
      if page > 1 then result.append("\n\n---\n\n")
      result.append(stripper.getText(document).strip())
      page += 1
    result.toString
  }

  def install(context: NativePluginContext): Unit =
    context.register("htmlToPdfBase64") {
      case value :: Nil => Value.StrV(render(text(value, "htmlToPdfBase64")))
      case _ => throw new IllegalArgumentException("htmlToPdfBase64(html)")
    }
    context.register("pdfPageCount") {
      case value :: Nil => Value.IntV(withPdf(text(value, "pdfPageCount"), "pdfPageCount")(
        _.getNumberOfPages.toLong))
      case _ => throw new IllegalArgumentException("pdfPageCount(pdfBase64)")
    }
    context.register("pdfToMarkdown") {
      case value :: Nil => Value.StrV(markdown(text(value, "pdfToMarkdown")))
      case _ => throw new IllegalArgumentException("pdfToMarkdown(pdfBase64)")
    }

