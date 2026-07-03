package scalascript.dap

import ujson.*
import java.io.{InputStream, OutputStream}

/** Content-Length framing identical to LSP:
 *  {{{Content-Length: <N>\r\n\r\n<N bytes of UTF-8 JSON>}}}
 */
object DapProtocol:

  def readMessage(in: InputStream): Value =
    // Read headers until blank line
    val headerBuf = StringBuilder()
    var cur  = in.read()
    while cur != -1 do
      headerBuf.append(cur.toChar)
      // detect \r\n\r\n (end of headers)
      if headerBuf.length >= 4 &&
         headerBuf.charAt(headerBuf.length - 4) == '\r' &&
         headerBuf.charAt(headerBuf.length - 3) == '\n' &&
         headerBuf.charAt(headerBuf.length - 2) == '\r' &&
         headerBuf.charAt(headerBuf.length - 1) == '\n' then
        cur = -1 // break
      else
        cur = in.read()

    val headers = headerBuf.toString
    val contentLength = headers.linesIterator
      .find(_.startsWith("Content-Length:"))
      .map(_.drop("Content-Length:".length).trim.toInt)
      .getOrElse(throw java.io.IOException("Missing Content-Length header"))

    val body = in.readNBytes(contentLength)
    if body.length < contentLength then
      throw java.io.EOFException("Unexpected end of stream reading DAP body")
    ujson.read(body)

  def writeMessage(out: OutputStream, msg: Value): Unit =
    val json  = ujson.write(msg)
    val bytes = json.getBytes("UTF-8")
    val header = s"Content-Length: ${bytes.length}\r\n\r\n"
    out.write(header.getBytes("UTF-8"))
    out.write(bytes)
    out.flush()
