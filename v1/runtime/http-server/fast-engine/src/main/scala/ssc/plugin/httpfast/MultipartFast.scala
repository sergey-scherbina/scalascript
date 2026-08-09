package ssc.plugin.httpfast

import java.nio.charset.StandardCharsets.ISO_8859_1

/** `multipart/form-data` parsing for the fast engine.
 *
 *  Written here rather than reusing `scalascript.server.Multipart` (which the interpreter and JS
 *  lanes go through) on purpose: that lives in the v1 `http-server/common` module, and depending on
 *  it from the v2 native plugin would pull a v1 module into the tree v2 is built to be independent
 *  of. This engine already owns `HttpProtocol`, so the parse belongs beside it.
 *
 *  Until 2026-08-05 the native lane had NO multipart at all — searching the whole `v2/` tree, the
 *  only occurrence of the word outside tests was a `Content-Type` header being WRITTEN by the
 *  PDF/MIME plugin. `HttpFastNativePlugin` declared `files` among the Request fields and filled it
 *  with an empty map, so `req.files` was permanently empty and every upload handler took its own
 *  else-branch: HTTP **200** carrying the program's own "missing 'file' part". Not a failure the
 *  status code could see — a wrong answer.
 *  BUGS `multipart-upload-three-lanes-three-answers`.
 *
 *  Field shape matches `UploadedFile` in `std/http.ssc` exactly, because an ssc program
 *  reads `f.filename` / `f.size` / `f.bytes` the same way on every lane. `bytes` is the ISO-8859-1
 *  view (1 char = 1 byte), recovered with `bytes.getBytes("ISO-8859-1")`, as the other lanes do.
 *
 *  No disk spooling: the other lanes spill parts over a threshold to a temp file and leave `bytes`
 *  empty with `path` set. This engine keeps every part in memory and always reports `path = ""`.
 *  That is a deliberate limit, not an oversight — the threshold there is configured through v1
 *  server settings this engine does not have — and it is stated here so the next reader does not
 *  read an empty `path` as a bug.
 */
object MultipartFast:

  /** One parsed part. `name` is the form field name; `filename` is empty for a non-file part. */
  final case class Part(
      name:        String,
      filename:    String,
      contentType: String,
      size:        Int,
      bytes:       String)

  /** The `boundary=` parameter of a `multipart/form-data` content type, or None.
   *
   *  RFC 2046 allows the value to be quoted, and browsers do quote it when it contains characters
   *  like `:`; both spellings are accepted here. */
  def boundaryOf(contentType: String): Option[String] =
    val ct = contentType.trim
    if !ct.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/form-data") then None
    else
      val marker = "boundary="
      val i = ct.toLowerCase(java.util.Locale.ROOT).indexOf(marker)
      if i < 0 then None
      else
        var v = ct.substring(i + marker.length).trim
        val semi = v.indexOf(';')
        if semi >= 0 then v = v.substring(0, semi).trim
        if v.length >= 2 && v.startsWith("\"") && v.endsWith("\"") then v = v.substring(1, v.length - 1)
        if v.isEmpty then None else Some(v)

  /** Parse `body` into parts keyed by form field name. A later part with the same name wins, which
   *  is what the reference lanes do. Returns an empty map for anything that is not a well-formed
   *  multipart body — a malformed upload must not take the server down. */
  def parse(body: Array[Byte], contentType: String): Map[String, Part] =
    boundaryOf(contentType) match
      case None => Map.empty
      case Some(boundary) =>
        val dash  = ("--" + boundary).getBytes(ISO_8859_1)
        val crlf2 = "\r\n\r\n".getBytes(ISO_8859_1)
        var out   = Map.empty[String, Part]
        var i     = indexOf(body, dash, 0)
        while i >= 0 do
          val afterDelim = i + dash.length
          // `--boundary--` closes the body; anything after it is the epilogue.
          if afterDelim + 1 < body.length && body(afterDelim) == '-' && body(afterDelim + 1) == '-' then
            i = -1
          else
            val headStart = skipCrLf(body, afterDelim)
            val headEnd   = indexOf(body, crlf2, headStart)
            if headEnd < 0 then i = -1
            else
              val bodyStart = headEnd + crlf2.length
              val next      = indexOf(body, dash, bodyStart)
              // The CRLF before the next delimiter belongs to the delimiter, not to the content.
              val bodyEnd   = if next < 0 then body.length else math.max(bodyStart, next - 2)
              val headers   = new String(body, headStart, headEnd - headStart, ISO_8859_1)
              val disp      = headerValue(headers, "content-disposition")
              val name      = param(disp, "name")
              if name.nonEmpty then
                val content = new String(body, bodyStart, bodyEnd - bodyStart, ISO_8859_1)
                out = out.updated(name, Part(
                  name        = name,
                  filename    = param(disp, "filename"),
                  contentType = headerValue(headers, "content-type"),
                  size        = bodyEnd - bodyStart,
                  bytes       = content))
              i = next
        out

  /** Value of `name` in a part's header block, or "" — header names are case-insensitive. */
  private def headerValue(headers: String, name: String): String =
    val want = name.toLowerCase(java.util.Locale.ROOT)
    headers.split("\r\n").iterator
      .map(_.trim).filter(_.nonEmpty)
      .find(_.toLowerCase(java.util.Locale.ROOT).startsWith(want + ":"))
      .map(l => l.substring(l.indexOf(':') + 1).trim)
      .getOrElse("")

  /** `name="value"` (or bare `name=value`) out of a header's parameter list, or "". */
  private def param(header: String, key: String): String =
    val parts = header.split(';').iterator.map(_.trim)
    parts.find(_.toLowerCase(java.util.Locale.ROOT).startsWith(key.toLowerCase(java.util.Locale.ROOT) + "=")) match
      case None => ""
      case Some(p) =>
        var v = p.substring(p.indexOf('=') + 1).trim
        if v.length >= 2 && v.startsWith("\"") && v.endsWith("\"") then v = v.substring(1, v.length - 1)
        v

  private def skipCrLf(body: Array[Byte], at: Int): Int =
    if at + 1 < body.length && body(at) == '\r' && body(at + 1) == '\n' then at + 2 else at

  /** First index of `needle` in `hay` at or after `from`, or -1. */
  private def indexOf(hay: Array[Byte], needle: Array[Byte], from: Int): Int =
    if needle.isEmpty || needle.length > hay.length then -1
    else
      var i    = math.max(0, from)
      val last = hay.length - needle.length
      var found = -1
      while found < 0 && i <= last do
        var j = 0
        while j < needle.length && hay(i + j) == needle(j) do j += 1
        if j == needle.length then found = i else i += 1
      found
