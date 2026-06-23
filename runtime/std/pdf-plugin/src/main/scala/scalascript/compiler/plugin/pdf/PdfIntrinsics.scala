package scalascript.compiler.plugin.pdf

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginComputation, PluginNative, PluginValue}

object PdfIntrinsics:

  private def native(f: List[Any] => PluginValue): NativeImpl =
    PluginNative.eval { (_, args) =>
      PluginComputation.pure(PluginValue.wrap(f(args.map(_.unwrap))))
    }

  /** Render an HTML string to PDF bytes (base64).  Confined to the static
   *  HTML/CSS subset OpenHTMLtoPDF supports (table layout, basic typography,
   *  borders, backgrounds, A4 page) — the busi invoice template surface.
   *
   *  HTML is parsed leniently with jsoup (real-world HTML, not only strict
   *  XHTML) and converted to a W3C DOM for the renderer.  Unsupported CSS is
   *  logged and skipped by the engine rather than throwing; a genuinely
   *  unparseable document throws a clear error. */
  private def htmlToPdfBase64Raw(html: String): String =
    val baos = new java.io.ByteArrayOutputStream()
    try
      val jsoupDoc = org.jsoup.Jsoup.parse(html)
      jsoupDoc.outputSettings().syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml)
      val w3c = new org.jsoup.helper.W3CDom().fromJsoup(jsoupDoc)
      val builder = new com.openhtmltopdf.pdfboxout.PdfRendererBuilder()
      builder.useFastMode()
      builder.withW3cDocument(w3c, "")
      builder.toStream(baos)
      builder.run()
      java.util.Base64.getEncoder.encodeToString(baos.toByteArray)
    catch case e: Throwable => throw new RuntimeException(s"htmlToPdfBase64: ${e.getMessage}")

  /** Load a base64 PDF and run `f` over the document, always closing it. */
  private def withPdf[A](pdfBase64: String, op: String)(f: org.apache.pdfbox.pdmodel.PDDocument => A): A =
    val bytes = java.util.Base64.getDecoder.decode(pdfBase64.trim)
    val doc =
      try org.apache.pdfbox.pdmodel.PDDocument.load(bytes)
      catch case e: Throwable => throw new RuntimeException(s"$op: not a readable PDF: ${e.getMessage}")
    try f(doc) finally doc.close()

  private def pdfPageCountRaw(pdfBase64: String): Long =
    withPdf(pdfBase64, "pdfPageCount")(_.getNumberOfPages.toLong)

  /** Extract the text of every page as Markdown: each page's stripped text,
   *  pages separated by a horizontal rule.  This is plain-text extraction (the
   *  reading order PDFBox recovers) — no layout, font, or heading inference, so
   *  it is honest about what a text layer actually contains.  Image-only PDFs
   *  yield empty/partial text rather than throwing. */
  private def pdfToMarkdownRaw(pdfBase64: String): String =
    withPdf(pdfBase64, "pdfToMarkdown") { doc =>
      val n = doc.getNumberOfPages
      val stripper = new org.apache.pdfbox.text.PDFTextStripper()
      val sb = new StringBuilder
      var page = 1
      while page <= n do
        stripper.setStartPage(page)
        stripper.setEndPage(page)
        if page > 1 then sb.append("\n\n---\n\n")
        sb.append(stripper.getText(doc).strip())
        page += 1
      sb.toString
    }

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    QualifiedName("htmlToPdfBase64") -> native {
      case List(html: String) => PluginValue.string(htmlToPdfBase64Raw(html))
      case _                   => throw new RuntimeException("htmlToPdfBase64(html)")
    },

    QualifiedName("pdfPageCount") -> native {
      case List(pdfBase64: String) => PluginValue.int(pdfPageCountRaw(pdfBase64))
      case _                       => throw new RuntimeException("pdfPageCount(pdfBase64)")
    },

    QualifiedName("pdfToMarkdown") -> native {
      case List(pdfBase64: String) => PluginValue.string(pdfToMarkdownRaw(pdfBase64))
      case _                       => throw new RuntimeException("pdfToMarkdown(pdfBase64)")
    },

  )
