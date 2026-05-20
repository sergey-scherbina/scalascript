package scalascript.mcp

/** Pure-Scala JSON-RPC 2.0 framing for v1.17 MCP own-implementation
 *  on the interpreter backend.
 *
 *  Mirrors the spec at https://www.jsonrpc.org/specification, scoped
 *  to what MCP needs:
 *
 *    - **Request**: `{jsonrpc:"2.0", method, params?, id}` — expects a response.
 *    - **Notification**: same shape without `id` — no response.
 *    - **Response**: `{jsonrpc:"2.0", id, result|error}` — exactly one of
 *      `result` / `error` populated.
 *
 *  IDs are kept as `ujson.Value` so we accept both string and integer
 *  forms (the MCP TS SDK uses numeric ids; some clients use strings).
 *  We never generate string ids ourselves; outgoing requests always use
 *  monotonically-increasing integers. */
object JsonRpc:

  /** Standard JSON-RPC error codes (from the spec). MCP layers its own
   *  application codes on top — they live in `McpProtocol`. */
  object ErrorCode:
    val ParseError       = -32700
    val InvalidRequest   = -32600
    val MethodNotFound   = -32601
    val InvalidParams    = -32602
    val InternalError    = -32603

  /** Inbound message parsed off the wire — either a request/notification
   *  (the server reads these) or a response (the client reads these).
   *  Distinguishing the two on read is a syntactic check: presence of
   *  `method` → request/notification; presence of `result`/`error` →
   *  response. */
  enum Message:
    case Request(method: String, params: ujson.Value, id: ujson.Value)
    case Notification(method: String, params: ujson.Value)
    case Response(id: ujson.Value, result: Option[ujson.Value], error: Option[Error])

  case class Error(code: Int, message: String, data: Option[ujson.Value] = None)

  /** Parse one wire frame.  Returns `Left(parseErrorMessage)` for malformed
   *  JSON or shape-violating payloads — callers translate that into an
   *  appropriate JSON-RPC `error` response if the source had an id.
   *
   *  Tolerant of missing `jsonrpc:"2.0"` since the MCP TS SDK historically
   *  omitted it on a few internal messages; treats any object with `method`
   *  as request/notification regardless. */
  def parse(line: String): Either[String, Message] =
    val trimmed = line.trim
    if trimmed.isEmpty then Left("empty frame")
    else
      try
        val v = ujson.read(trimmed)
        v.objOpt match
          case None      => Left("not a JSON object")
          case Some(obj) =>
            obj.get("method").flatMap(_.strOpt) match
              case Some(method) =>
                val params = obj.getOrElse("params", ujson.Obj())
                obj.get("id") match
                  case None         => Right(Message.Notification(method, params))
                  case Some(idJson) => Right(Message.Request(method, params, idJson))
              case None =>
                obj.get("id") match
                  case None         => Left("response without id")
                  case Some(idJson) =>
                    val result = obj.get("result")
                    val err    = obj.get("error").map(parseError)
                    Right(Message.Response(idJson, result, err))
      catch case e: Throwable => Left(s"parse error: ${e.getMessage}")

  private def parseError(v: ujson.Value): Error =
    val obj  = v.obj
    val code = obj.get("code").flatMap(_.numOpt).map(_.toInt).getOrElse(ErrorCode.InternalError)
    val msg  = obj.get("message").flatMap(_.strOpt).getOrElse("")
    val data = obj.get("data")
    Error(code, msg, data)

  /** Serialise a request to a single-line JSON frame (line-delimited
   *  framing — what stdio transport expects).  Adds the trailing `\n`. */
  def encodeRequest(method: String, params: ujson.Value, id: Long): String =
    // Explicit ujson.Num wrap — `"id" -> (id: Long)` would otherwise serialise
    // as a String via ujson's default Long → Str conversion (since JSON has
    // no native Long type, ujson plays it safe).  The spec allows both
    // numeric and string ids; our convention is numeric.
    val obj = ujson.Obj(
      "jsonrpc" -> "2.0",
      "method"  -> method,
      "params"  -> params,
      "id"      -> ujson.Num(id.toDouble)
    )
    obj.render() + "\n"

  /** Encode a notification — like `encodeRequest` minus the id. */
  def encodeNotification(method: String, params: ujson.Value): String =
    val obj = ujson.Obj(
      "jsonrpc" -> "2.0",
      "method"  -> method,
      "params"  -> params
    )
    obj.render() + "\n"

  /** Encode a successful response. */
  def encodeResult(id: ujson.Value, result: ujson.Value): String =
    val obj = ujson.Obj(
      "jsonrpc" -> "2.0",
      "id"      -> id,
      "result"  -> result
    )
    obj.render() + "\n"

  /** Encode an error response. */
  def encodeError(id: ujson.Value, code: Int, message: String, data: Option[ujson.Value] = None): String =
    val errObj = ujson.Obj(
      "code"    -> code,
      "message" -> message
    )
    data.foreach(d => errObj("data") = d)
    val obj = ujson.Obj(
      "jsonrpc" -> "2.0",
      "id"      -> id,
      "error"   -> errObj
    )
    obj.render() + "\n"
