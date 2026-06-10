package scalascript.compiler.plugin.pdf

import scalascript.backend.spi.*
import scalascript.interpreter.Value
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginComputation, PluginNative, PluginValue}

object PdfIntrinsics:

  private def native(f: List[Any] => Value): NativeImpl =
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

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    QualifiedName("htmlToPdfBase64") -> native {
      case List(html: String) => Value.StringV(htmlToPdfBase64Raw(html))
      case _                   => throw new RuntimeException("htmlToPdfBase64(html)")
    },

  )
