package ssc.plugin.pdf

import java.nio.charset.StandardCharsets
import java.util.Base64
import ssc.{Prims, Value}
import ssc.plugin.{NativePlugin, NativePluginContext}

/** Dependency-free MIME companion selected with the explicit PDF invoice lane. */
final class MimeNativePlugin extends NativePlugin:
  def id: String = "91-mime-explicit"

  private val CrLf = "\r\n"

  private def text(value: Value, label: String): String = value match
    case Value.StrV(result) => result
    case _ => throw new IllegalArgumentException(s"buildMimeMessage: $label must be String")

  private def wrap76(value: String): String =
    value.filterNot(_.isWhitespace).grouped(76).mkString(CrLf)

  private def encoded(value: String): String =
    Base64.getEncoder.encodeToString(value.getBytes(StandardCharsets.UTF_8))

  private def header(value: String): String =
    if value.forall(char => char >= 32 && char < 127) then value
    else s"=?UTF-8?B?${encoded(value)}?="

  private def attachments(value: Value): List[(String, String, String)] =
    Prims.unlistPub(value).map {
      case Value.DataV(_, IndexedSeq(filename, mediaType, content)) =>
        (text(filename, "attachment filename"), text(mediaType, "attachment media type"),
          text(content, "attachment content"))
      case _ => throw new IllegalArgumentException(
        "buildMimeMessage: each attachment must be (filename, mimeType, contentBase64)")
    }

  private def build(
      from: String,
      to: String,
      subject: String,
      body: String,
      attachments: List[(String, String, String)]): String =
    val out = StringBuilder()
    out.append("From: ").append(from).append(CrLf)
    out.append("To: ").append(to).append(CrLf)
    out.append("Subject: ").append(header(subject)).append(CrLf)
    out.append("MIME-Version: 1.0").append(CrLf)
    val body64 = wrap76(encoded(body))
    if attachments.isEmpty then
      out.append("Content-Type: text/html; charset=UTF-8").append(CrLf)
      out.append("Content-Transfer-Encoding: base64").append(CrLf).append(CrLf)
      out.append(body64).append(CrLf)
    else
      val boundary = "----=_ssc_native_pdf_invoice"
      out.append(s"Content-Type: multipart/mixed; boundary=\"$boundary\"").append(CrLf)
      out.append(CrLf).append("--").append(boundary).append(CrLf)
      out.append("Content-Type: text/html; charset=UTF-8").append(CrLf)
      out.append("Content-Transfer-Encoding: base64").append(CrLf).append(CrLf)
      out.append(body64).append(CrLf)
      attachments.foreach { case (filename, mediaType, content) =>
        out.append("--").append(boundary).append(CrLf)
        out.append(s"Content-Type: $mediaType; name=\"$filename\"").append(CrLf)
        out.append("Content-Transfer-Encoding: base64").append(CrLf)
        out.append(s"Content-Disposition: attachment; filename=\"$filename\"").append(CrLf)
        out.append(CrLf).append(wrap76(content)).append(CrLf)
      }
      out.append("--").append(boundary).append("--").append(CrLf)
    out.toString

  def install(context: NativePluginContext): Unit =
    context.register("buildMimeMessage") {
      case from :: to :: subject :: body :: files :: Nil =>
        Value.StrV(build(
          text(from, "from"),
          text(to, "to"),
          text(subject, "subject"),
          text(body, "htmlBody"),
          attachments(files)))
      case _ => throw new IllegalArgumentException(
        "buildMimeMessage(from, to, subject, htmlBody, attachments)")
    }
