package scalascript.server

import java.io.File
import java.nio.file.{Files, Paths}

/** `multipart/form-data` parser shared between the interpreter and
 *  the JvmGen-emitted server runtime.  Returns POJO [[UploadedFile]]
 *  instances; callers that need interpreter `Value`s (the interpreter
 *  bridge in `WebServer.parseMultipart`) wrap each one accordingly.
 *
 *  `bodyLatin1` is the request body decoded as ISO-8859-1, where each
 *  Java char is byte-equivalent to the original input byte — boundary
 *  matching and part splitting therefore work byte-exactly on Strings.
 *
 *  Text parts (no `filename=`) become String values in `form`, UTF-8
 *  decoded from their byte representation.
 *
 *  File parts (with `filename=`) become an `UploadedFile` in `files`.
 *  Parts larger than `spoolThreshold` are written to a temp file inside
 *  `uploadDir` so the in-memory map stays bounded; their `UploadedFile`
 *  has an empty `bytes` field and a non-empty `path` pointing at the
 *  spool file.  Smaller parts keep `bytes` populated and `path` empty.
 *
 *  Returns a triple of `(form, files, spooledTempFiles)` — callers can
 *  use the third element to clean up the temp files after the request
 *  handler finishes. */
object Multipart:

  def parse(
      contentType:    String,
      bodyLatin1:     String,
      spoolThreshold: Long   = 1024L * 1024L,
      uploadDir:      String = System.getProperty("java.io.tmpdir")
  ): (Map[String, String], Map[String, UploadedFile], List[File]) =
    val boundary = "boundary=([^;]+)".r.findFirstMatchIn(contentType).map { m =>
      val raw = m.group(1).trim
      if raw.startsWith("\"") && raw.endsWith("\"") then raw.substring(1, raw.length - 1) else raw
    }
    boundary.fold((Map.empty[String, String], Map.empty[String, UploadedFile], List.empty[File])) { b =>
      val sep     = "--" + b
      val parts   = bodyLatin1.split(java.util.regex.Pattern.quote(sep), -1)
      val form    = scala.collection.mutable.Map.empty[String, String]
      val files   = scala.collection.mutable.Map.empty[String, UploadedFile]
      val spooled = scala.collection.mutable.ListBuffer.empty[File]
      // First chunk before the first boundary and the trailing "--" chunk
      // contain no part data — skip both.
      parts.drop(1).dropRight(1).foreach { raw =>
        val part   = raw.stripPrefix("\r\n").stripSuffix("\r\n")
        val sepIdx = part.indexOf("\r\n\r\n")
        if sepIdx >= 0 then
          val headerText = part.substring(0, sepIdx)
          val partBody   = part.substring(sepIdx + 4) // still ISO-8859-1
          val disp = headerText.linesIterator
            .find(_.toLowerCase.startsWith("content-disposition"))
            .getOrElse("")
          val ctype = headerText.linesIterator
            .find(_.toLowerCase.startsWith("content-type"))
            .map(_.split(":", 2).lift(1).getOrElse("").trim)
            .getOrElse("application/octet-stream")
          val name     = """name="([^"]*)"""".r.findFirstMatchIn(disp).map(_.group(1))
          val filename = """filename="([^"]*)"""".r.findFirstMatchIn(disp).map(_.group(1))
          (name, filename) match
            case (Some(n), Some(fn)) =>
              val (bytesStr, pathStr) =
                if partBody.length.toLong > spoolThreshold then
                  val tmp = Files.createTempFile(Paths.get(uploadDir), "ssc-upload-", "").toFile
                  Files.write(tmp.toPath, partBody.getBytes("ISO-8859-1"))
                  spooled += tmp
                  ("", tmp.getAbsolutePath)
                else (partBody, "")
              files(n) = UploadedFile(n, fn, ctype, partBody.length, bytesStr, pathStr)
            case (Some(n), None) =>
              form(n) = new String(partBody.getBytes("ISO-8859-1"), "UTF-8")
            case _ => ()
      }
      (form.toMap, files.toMap, spooled.toList)
    }
