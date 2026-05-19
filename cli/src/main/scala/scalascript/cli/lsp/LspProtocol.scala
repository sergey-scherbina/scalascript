package scalascript.cli.lsp

import java.io.{InputStream, OutputStream}
import java.nio.charset.StandardCharsets

/** Minimal JSON-RPC 2.0 / LSP protocol primitives — hand-rolled.
 *
 *  We only model the shapes we actually exchange:
 *   - `Request`      — client → server, expects a `Response`.
 *   - `Notification` — fire-and-forget (no `id`).
 *   - `Response`     — server → client, either `result` or `error`.
 *
 *  Wire format is LSP's HTTP-style framing:
 *      Content-Length: <bytes>\r\n
 *      \r\n
 *      <payload bytes>
 *
 *  See https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/
 */
object LspProtocol:

  /** Standard JSON-RPC error codes used by LSP. */
  object ErrorCodes:
    val ParseError:     Int = -32700
    val InvalidRequest: Int = -32600
    val MethodNotFound: Int = -32601
    val InvalidParams:  Int = -32602
    val InternalError:  Int = -32603
    // LSP-specific
    val ServerNotInitialized: Int = -32002
    val UnknownErrorCode:     Int = -32001
    val RequestCancelled:     Int = -32800
    val ContentModified:      Int = -32801

  /** A parsed JSON-RPC message — either a Request (has `id`), a
   *  Notification (no `id`), or a Response (carries `result`/`error`). */
  sealed trait Message
  case class Request(id: ujson.Value, method: String, params: ujson.Value) extends Message
  case class Notification(method: String, params: ujson.Value)             extends Message
  case class Response(
      id: ujson.Value,
      result: Option[ujson.Value],
      error:  Option[ResponseError]
  ) extends Message
  case class ResponseError(code: Int, message: String, data: Option[ujson.Value] = None)

  // ─── Decoding ───────────────────────────────────────────────────────────

  /** Parse a JSON-RPC payload from a string.  Returns `Left(error)` if the
   *  string is malformed JSON or lacks the `jsonrpc` envelope. */
  def decode(json: String): Either[String, Message] =
    val parsed = scala.util.Try(ujson.read(json)).toEither.left.map(e =>
      s"malformed JSON: ${e.getMessage}"
    )
    parsed.flatMap { v =>
      v.objOpt match
        case None => Left("payload is not a JSON object")
        case Some(obj) =>
          val isResponse = obj.contains("result") || obj.contains("error")
          val isNotification = !obj.contains("id")
          if isResponse then
            val id     = obj.getOrElse("id", ujson.Null)
            val result = obj.get("result")
            val err    = obj.get("error").flatMap { e =>
              for
                code <- e.objOpt.flatMap(_.get("code")).flatMap(_.numOpt).map(_.toInt)
                msg  <- e.objOpt.flatMap(_.get("message")).flatMap(_.strOpt)
              yield ResponseError(code, msg, e.objOpt.flatMap(_.get("data")))
            }
            Right(Response(id, result, err))
          else
            obj.get("method").flatMap(_.strOpt) match
              case None         => Left("missing 'method' field")
              case Some(method) =>
                val params = obj.getOrElse("params", ujson.Null)
                if isNotification then Right(Notification(method, params))
                else Right(Request(obj("id"), method, params))
    }

  // ─── Encoding ───────────────────────────────────────────────────────────

  def encode(msg: Message): String = msg match
    case Request(id, method, params) =>
      ujson.write(ujson.Obj(
        "jsonrpc" -> "2.0",
        "id"      -> id,
        "method"  -> method,
        "params"  -> params
      ))
    case Notification(method, params) =>
      ujson.write(ujson.Obj(
        "jsonrpc" -> "2.0",
        "method"  -> method,
        "params"  -> params
      ))
    case Response(id, result, err) =>
      val obj = ujson.Obj("jsonrpc" -> "2.0", "id" -> id)
      result.foreach(r => obj("result") = r)
      err.foreach { e =>
        val eobj = ujson.Obj("code" -> e.code, "message" -> e.message)
        e.data.foreach(d => eobj("data") = d)
        obj("error") = eobj
      }
      ujson.write(obj)

  // ─── Convenience constructors ──────────────────────────────────────────

  def success(id: ujson.Value, result: ujson.Value): Response =
    Response(id, Some(result), None)
  def failure(id: ujson.Value, code: Int, message: String): Response =
    Response(id, None, Some(ResponseError(code, message)))

  // ─── Framing ───────────────────────────────────────────────────────────

  /** Read one framed message body from the input stream.
   *
   *  Returns `Right(Some(payload))` for a successful frame, `Right(None)`
   *  on clean EOF (no more frames), or `Left(error)` for a malformed
   *  header / truncated body. */
  def readFrame(in: InputStream): Either[String, Option[String]] =
    // 1) Read headers up to the blank line "\r\n\r\n".
    var contentLength = -1
    var sawAnyHeader  = false
    var done          = false

    def readLine(): Either[String, Option[String]] =
      val sb = new StringBuilder()
      var b  = in.read()
      if b == -1 then return Right(None)
      while b != -1 && b != '\r' do
        sb.append(b.toChar)
        b = in.read()
      if b == -1 then
        // No CR — treat trailing partial header as truncated.
        if sb.isEmpty then Right(None)
        else Left(s"truncated header (no CRLF): '${sb.toString}'")
      else
        val nl = in.read()
        if nl != '\n' then Left(s"expected LF after CR, got byte $nl")
        else Right(Some(sb.toString))

    while !done do
      readLine() match
        case Left(err)            => return Left(err)
        case Right(None) if !sawAnyHeader => return Right(None) // clean EOF before any header
        case Right(None)          => return Left("EOF inside header block")
        case Right(Some(""))      => done = true
        case Right(Some(line)) =>
          sawAnyHeader = true
          val idx = line.indexOf(':')
          if idx < 0 then return Left(s"malformed header (no ':'): '$line'")
          val key = line.substring(0, idx).trim
          val v   = line.substring(idx + 1).trim
          if key.equalsIgnoreCase("Content-Length") then
            v.toIntOption match
              case Some(n) if n >= 0 => contentLength = n
              case _ => return Left(s"invalid Content-Length: '$v'")

    if contentLength < 0 then return Left("missing Content-Length header")

    // 2) Read the body — exactly contentLength bytes.
    val buf  = new Array[Byte](contentLength)
    var read = 0
    while read < contentLength do
      val n = in.read(buf, read, contentLength - read)
      if n < 0 then return Left(s"EOF inside body (read $read of $contentLength)")
      read += n
    Right(Some(new String(buf, StandardCharsets.UTF_8)))

  /** Write one framed message to the output stream.  Flushes before
   *  returning so the receiver sees the frame promptly. */
  def writeFrame(out: OutputStream, payload: String): Unit =
    val bytes  = payload.getBytes(StandardCharsets.UTF_8)
    val header = s"Content-Length: ${bytes.length}\r\n\r\n"
    out.write(header.getBytes(StandardCharsets.US_ASCII))
    out.write(bytes)
    out.flush()
