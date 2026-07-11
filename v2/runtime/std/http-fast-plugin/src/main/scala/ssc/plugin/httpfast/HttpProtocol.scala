package ssc.plugin.httpfast

import java.io.{EOFException, InputStream, OutputStream}
import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}

/** Parsed request as seen by the transport engine (before ssc-value routing/binding).
  * `path` is percent-decoded and query-free; `query` is decoded (`+`→space). */
final case class RawRequest(
    method: String,
    target: String,
    path: String,
    query: Map[String, String],
    headers: Map[String, String], // header names lower-cased
    body: Array[Byte],
    keepAlive: Boolean)

/** A response ready to serialize. `headers` keys are used verbatim; the engine adds
  * `Content-Length`, `Date`, and `Connection` itself. */
final case class RawResponse(status: Int, headers: Map[String, String], body: Array[Byte])

/** Raised when a request is malformed → the engine answers `400` and closes. */
final class BadRequest(msg: String) extends RuntimeException(msg)

/** A small, non-synchronized buffered reader over a blocking `InputStream`.
  *
  * `readLine` scans the internal buffer for CRLF without per-byte stream calls, growing /
  * compacting as needed; `readFully` drains buffered bytes first then reads the rest. The
  * relative scan offset is stable across compaction because compaction preserves byte order
  * from `start`. Header bytes are ISO-8859-1 (HTTP/1.1); bodies stay raw. */
final class HttpReader(in: InputStream, initialCap: Int = 16 * 1024, maxLine: Int = 64 * 1024):
  private var buf   = new Array[Byte](initialCap)
  private var start = 0
  private var end   = 0

  private def readMore(): Int =
    if start > 0 then
      System.arraycopy(buf, start, buf, 0, end - start)
      end -= start
      start = 0
    if end == buf.length then
      if buf.length >= maxLine then throw new BadRequest("header line too long")
      buf = java.util.Arrays.copyOf(buf, math.min(buf.length * 2, maxLine))
    val n = in.read(buf, end, buf.length - end)
    if n > 0 then end += n
    n

  /** Next CRLF/LF-terminated line as a String, or `null` at a clean EOF. */
  def readLine(): String | Null =
    var i = 0
    while true do
      while start + i < end do
        if buf(start + i) == '\n'.toByte then
          val lineEnd = if i > 0 && buf(start + i - 1) == '\r'.toByte then i - 1 else i
          val s = new String(buf, start, lineEnd, ISO_8859_1)
          start = start + i + 1
          return s
        i += 1
      val n = readMore()
      if n < 0 then return null // EOF
    null // unreachable

  /** Read exactly `n` bytes (buffered first, then from the stream). */
  def readFully(n: Int): Array[Byte] =
    val out = new Array[Byte](n)
    val avail   = end - start
    val fromBuf = math.min(avail, n)
    if fromBuf > 0 then
      System.arraycopy(buf, start, out, 0, fromBuf)
      start += fromBuf
    var got = fromBuf
    while got < n do
      val r = in.read(out, got, n - got)
      if r < 0 then throw new EOFException("unexpected EOF in request body")
      got += r
    out

object HttpProtocol:
  /** Caps guarding against abusive requests (tunable via the host). */
  final case class Limits(maxHeaderBytes: Int = 64 * 1024, maxBodyBytes: Long = 16L * 1024 * 1024)

  /** Parse one request off `reader`. Returns `null` on a clean connection-close (no bytes
    * before the request line). Throws [[BadRequest]] on malformed input. May write a
    * `100 Continue` interim response to `out` when the client sent `Expect: 100-continue`. */
  def parse(reader: HttpReader, out: OutputStream, limits: Limits): RawRequest | Null =
    val requestLine = reader.readLine()
    if requestLine == null then return null
    if requestLine.isEmpty then throw new BadRequest("empty request line")

    val sp1 = requestLine.indexOf(' ')
    val sp2 = if sp1 >= 0 then requestLine.indexOf(' ', sp1 + 1) else -1
    if sp1 < 0 || sp2 < 0 then throw new BadRequest(s"malformed request line: $requestLine")
    val method  = requestLine.substring(0, sp1)
    val target  = requestLine.substring(sp1 + 1, sp2)
    val version = requestLine.substring(sp2 + 1)

    val headers = collection.mutable.LinkedHashMap.empty[String, String]
    var headerBytes = 0
    var line = reader.readLine()
    while line != null && line.nonEmpty do
      headerBytes += line.length + 2
      if headerBytes > limits.maxHeaderBytes then throw new BadRequest("headers too large")
      val colon = line.indexOf(':')
      if colon > 0 then
        val name  = line.substring(0, colon).trim.toLowerCase(java.util.Locale.ROOT)
        val value = line.substring(colon + 1).trim
        headers.get(name) match
          case Some(prev) => headers(name) = prev + ", " + value
          case None       => headers(name) = value
      line = reader.readLine()
    if line == null then throw new BadRequest("unexpected EOF in headers")

    val http11   = version.equalsIgnoreCase("HTTP/1.1")
    val connHdr  = headers.get("connection").map(_.toLowerCase(java.util.Locale.ROOT)).getOrElse("")
    val keepAlive =
      if connHdr.contains("close") then false
      else if connHdr.contains("keep-alive") then true
      else http11 // HTTP/1.1 defaults to keep-alive

    if headers.get("expect").exists(_.equalsIgnoreCase("100-continue")) then
      out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(ISO_8859_1))
      out.flush()

    val body: Array[Byte] =
      if headers.get("transfer-encoding").exists(_.toLowerCase(java.util.Locale.ROOT).contains("chunked")) then
        readChunked(reader, limits.maxBodyBytes)
      else
        headers.get("content-length") match
          case Some(cl) =>
            val len = cl.toLongOption.getOrElse(throw new BadRequest(s"bad Content-Length: $cl"))
            if len < 0 || len > limits.maxBodyBytes then throw new BadRequest(s"body too large: $len")
            reader.readFully(len.toInt)
          case None => EMPTY

    val q = target.indexOf('?')
    val rawPath = if q >= 0 then target.substring(0, q) else target
    val query   = if q >= 0 then parseQuery(target.substring(q + 1)) else Map.empty[String, String]
    RawRequest(method, target, percentDecode(rawPath, plusAsSpace = false), query,
      headers.toMap, body, keepAlive)

  private def readChunked(reader: HttpReader, maxBody: Long): Array[Byte] =
    val out = new java.io.ByteArrayOutputStream()
    var total = 0L
    var done  = false
    while !done do
      val sizeLine = reader.readLine()
      if sizeLine == null then throw new BadRequest("unexpected EOF in chunk size")
      val semi = sizeLine.indexOf(';') // strip chunk extensions
      val hex  = (if semi >= 0 then sizeLine.substring(0, semi) else sizeLine).trim
      val size =
        try Integer.parseInt(hex, 16)
        catch case _: NumberFormatException => throw new BadRequest(s"bad chunk size: $hex")
      if size == 0 then
        // consume trailing headers up to the blank line
        var t = reader.readLine()
        while t != null && t.nonEmpty do t = reader.readLine()
        done = true
      else
        total += size
        if total > maxBody then throw new BadRequest("chunked body too large")
        out.write(reader.readFully(size))
        reader.readFully(2) // trailing CRLF
    out.toByteArray

  /** Percent-decode; in query context `+` becomes a space. Malformed `%` sequences are
    * left literal (lenient). */
  def percentDecode(s: String, plusAsSpace: Boolean): String =
    if s.indexOf('%') < 0 && !(plusAsSpace && s.indexOf('+') >= 0) then return s
    val bytes = new java.io.ByteArrayOutputStream(s.length)
    var i = 0
    val n = s.length
    while i < n do
      val c = s.charAt(i)
      if c == '%' && i + 2 < n then
        val h = hexVal(s.charAt(i + 1))
        val l = hexVal(s.charAt(i + 2))
        if h >= 0 && l >= 0 then { bytes.write((h << 4) | l); i += 3 }
        else { bytes.write(c.toInt); i += 1 }
      else if c == '+' && plusAsSpace then { bytes.write(' '.toInt); i += 1 }
      else { bytes.write(s.substring(i, i + 1).getBytes(UTF_8)); i += 1 }
    new String(bytes.toByteArray, UTF_8)

  private def hexVal(c: Char): Int =
    if c >= '0' && c <= '9' then c - '0'
    else if c >= 'a' && c <= 'f' then c - 'a' + 10
    else if c >= 'A' && c <= 'F' then c - 'A' + 10
    else -1

  private def parseQuery(q: String): Map[String, String] =
    if q.isEmpty then Map.empty
    else
      val out = collection.mutable.LinkedHashMap.empty[String, String]
      for pair <- q.split("&") if pair.nonEmpty do
        val eq = pair.indexOf('=')
        if eq < 0 then out(percentDecode(pair, plusAsSpace = true)) = ""
        else out(percentDecode(pair.substring(0, eq), plusAsSpace = true)) =
          percentDecode(pair.substring(eq + 1), plusAsSpace = true)
      out.toMap

  val EMPTY: Array[Byte] = new Array[Byte](0)

  private val reasons: Map[Int, String] = Map(
    100 -> "Continue", 200 -> "OK", 201 -> "Created", 202 -> "Accepted", 204 -> "No Content",
    301 -> "Moved Permanently", 302 -> "Found", 303 -> "See Other", 304 -> "Not Modified",
    307 -> "Temporary Redirect", 308 -> "Permanent Redirect",
    400 -> "Bad Request", 401 -> "Unauthorized", 403 -> "Forbidden", 404 -> "Not Found",
    405 -> "Method Not Allowed", 408 -> "Request Timeout", 409 -> "Conflict",
    413 -> "Payload Too Large", 415 -> "Unsupported Media Type", 429 -> "Too Many Requests",
    500 -> "Internal Server Error", 501 -> "Not Implemented", 502 -> "Bad Gateway",
    503 -> "Service Unavailable")

  def reason(status: Int): String = reasons.getOrElse(status,
    if status < 200 then "Informational" else if status < 300 then "OK"
    else if status < 400 then "Redirect" else if status < 500 then "Client Error"
    else "Server Error")

  private val httpDate =
    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.withZone(java.time.ZoneOffset.UTC)

  /** Serialize a response onto `out`. Sets Content-Length, Date, and Connection; the
    * handler-provided headers win for everything else. */
  def writeResponse(out: OutputStream, resp: RawResponse, keepAlive: Boolean): Unit =
    val sb = new java.lang.StringBuilder(256)
    sb.append("HTTP/1.1 ").append(resp.status).append(' ').append(reason(resp.status)).append("\r\n")
    var hasDate = false
    for (k, v) <- resp.headers do
      val lk = k.toLowerCase(java.util.Locale.ROOT)
      if lk != "content-length" && lk != "connection" then // engine owns these
        if lk == "date" then hasDate = true
        sb.append(k).append(": ").append(v).append("\r\n")
    if !hasDate then
      sb.append("Date: ").append(httpDate.format(java.time.Instant.now())).append("\r\n")
    sb.append("Content-Length: ").append(resp.body.length).append("\r\n")
    sb.append("Connection: ").append(if keepAlive then "keep-alive" else "close").append("\r\n")
    sb.append("\r\n")
    out.write(sb.toString.getBytes(ISO_8859_1))
    if resp.body.length > 0 then out.write(resp.body)
    out.flush()
