package scalascript.compiler.plugin.mime

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginNative, PluginValue, PluginError}
import scalascript.plugin.api.PluginValue.*

object MimeIntrinsics:

  private val CRLF = "\r\n"

  private def str(v: PluginValue): String =
    v.asString.getOrElse(PluginError.raise(s"buildMimeMessage: expected a String, got ${v.show}"))

  private def b64(bytes: Array[Byte]): String =
    java.util.Base64.getEncoder.encodeToString(bytes)

  /** Wrap a base64 blob to 76-char lines (RFC 2045). */
  private def wrap76(s: String): String =
    s.replaceAll("\\s", "").grouped(76).mkString(CRLF)

  /** RFC 2047 encoded-word for a non-ASCII header value; pass ASCII through. */
  private def encodeHeader(s: String): String =
    if s.forall(c => c >= 32 && c < 127) then s
    else "=?UTF-8?B?" + b64(s.getBytes("UTF-8")) + "?="

  private def attachments(v: PluginValue): List[(String, String, String)] =
    v.asList.getOrElse(PluginError.raise(
      s"buildMimeMessage: attachments must be a List of (filename, mimeType, contentBase64), got ${v.show}"))
      .map { item =>
        item.asTuple match
          case Some(List(fn, mt, ct)) => (str(fn), str(mt), str(ct))
          case _ => PluginError.raise(
            s"buildMimeMessage: each attachment must be (filename, mimeType, contentBase64), got ${item.show}")
      }

  private def newBoundary(): String =
    val r = java.util.concurrent.ThreadLocalRandom.current()
    "----=_ssc_" + java.lang.Long.toHexString(r.nextLong()) + java.lang.Long.toHexString(System.nanoTime())

  /** Assemble an RFC 5322 message: a `text/html` body and N attachments.  With
   *  no attachments the result is a plain `text/html` email; with one or more it
   *  is `multipart/mixed` (the HTML part first, then each attachment).  Bodies
   *  are base64 (`Content-Transfer-Encoding: base64`) so any UTF-8 content and
   *  binary attachment survives intact. */
  private def buildMime(
      from: String, to: String, subject: String,
      htmlBody: String, atts: List[(String, String, String)]): String =
    val htmlPart = wrap76(b64(htmlBody.getBytes("UTF-8")))
    val sb = new StringBuilder
    sb.append("From: ").append(from).append(CRLF)
    sb.append("To: ").append(to).append(CRLF)
    sb.append("Subject: ").append(encodeHeader(subject)).append(CRLF)
    sb.append("MIME-Version: 1.0").append(CRLF)
    if atts.isEmpty then
      sb.append("Content-Type: text/html; charset=UTF-8").append(CRLF)
      sb.append("Content-Transfer-Encoding: base64").append(CRLF)
      sb.append(CRLF)
      sb.append(htmlPart).append(CRLF)
    else
      val boundary = newBoundary()
      sb.append(s"""Content-Type: multipart/mixed; boundary="$boundary"""").append(CRLF)
      sb.append(CRLF)
      sb.append("--").append(boundary).append(CRLF)
      sb.append("Content-Type: text/html; charset=UTF-8").append(CRLF)
      sb.append("Content-Transfer-Encoding: base64").append(CRLF)
      sb.append(CRLF)
      sb.append(htmlPart).append(CRLF)
      for (filename, mimeType, contentB64) <- atts do
        sb.append("--").append(boundary).append(CRLF)
        sb.append(s"""Content-Type: $mimeType; name="$filename"""").append(CRLF)
        sb.append("Content-Transfer-Encoding: base64").append(CRLF)
        sb.append(s"""Content-Disposition: attachment; filename="$filename"""").append(CRLF)
        sb.append(CRLF)
        sb.append(wrap76(contentB64)).append(CRLF)
      sb.append("--").append(boundary).append("--").append(CRLF)
    sb.toString

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    // NativeImpl unwraps scalar args (String → String) but leaves collections as a runtime value
    // (the attachment list arrives as a PluginValue list of tuples).
    QualifiedName("buildMimeMessage") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case (from: String) :: (to: String) :: (subject: String) :: (htmlBody: String) :: atts :: Nil =>
          PluginValue.string(buildMime(from, to, subject, htmlBody, attachments(PluginValue.wrap(atts)))).unwrap
        case _ =>
          PluginError.raise("buildMimeMessage(from, to, subject, htmlBody, attachments)")
    },

  )
