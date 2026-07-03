package scalascript.codegen

import scalascript.typeddata.TypedJsonCodecRuntime

object JvmRuntimeSwingClient:
  val source: String =
    """|// ── Typed route clients: Swing in-process transport ────────────────
       |import scala.compiletime.{erasedValue, summonInline}
       |
       |private def _ssc_api_url_encode(value: Any): String =
       |  java.net.URLEncoder.encode(_show(value), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")
       |
       |private def _ssc_api_product_fields(value: Any): Map[String, Any] =
       |  value match
       |    case p: Product =>
       |      p.productElementNames.zip(p.productIterator).toMap
       |    case _ => Map.empty
       |
       |private def _ssc_api_path_param_names(pathTemplate: String): List[String] =
       |  pathTemplate.split('/').toList.filter(_.startsWith(":")).map(_.drop(1))
       |
       |private def _ssc_api_path(pathTemplate: String, input: Any): String =
       |  val names = _ssc_api_path_param_names(pathTemplate)
       |  val fields = _ssc_api_product_fields(input)
       |  val primitiveForSingleParam =
       |    names.size == 1 && fields.isEmpty && input != null && input != ()
       |  pathTemplate.split('/').toList.map { segment =>
       |    if segment.startsWith(":") then
       |      val name = segment.drop(1)
       |      val value =
       |        if primitiveForSingleParam then input
       |        else fields.getOrElse(name, throw RuntimeException("typed route client: missing path field '" + name + "'"))
       |      _ssc_api_url_encode(value)
       |    else segment
       |  }.mkString("/")
       |
       |private def _ssc_api_query(pathTemplate: String, input: Any): String =
       |  val used = _ssc_api_path_param_names(pathTemplate).toSet
       |  val fields = _ssc_api_product_fields(input).filterNot((k, _) => used.contains(k))
       |  if fields.isEmpty then ""
       |  else fields.iterator.map((k, v) => _ssc_api_url_encode(k) + "=" + _ssc_api_url_encode(v)).mkString("?", "&", "")
       |
       |""".stripMargin + TypedJsonCodecRuntime.jvmFacade + """|private inline def _ssc_api_body[Req](method: String, input: Req): String =
       |  if method == "GET" || input == () then ""
       |  else _ssc_typed_json_encode[Req](input)
       |
       |private var _ssc_api_extra_headers: Map[String, String] = Map.empty
       |def _ssc_api_set_headers(headers: Map[String, String]): Unit =
       |  _ssc_api_extra_headers = headers
       |def _ssc_set_auth_token(token: String): Unit =
       |  if token == null || token.isEmpty then
       |    _ssc_api_extra_headers = _ssc_api_extra_headers - "Authorization"
       |  else
       |    _ssc_api_extra_headers = _ssc_api_extra_headers + ("Authorization" -> ("Bearer " + token))
       |
       |private var _ssc_api_retry_policy: (Int, Long) = (0, 0L)
       |def _ssc_api_set_retry(maxRetries: Int, delayMs: Long): Unit =
       |  _ssc_api_retry_policy = (maxRetries, delayMs)
       |
       |private var _ssc_api_wire_format: String = "json"
       |def _ssc_api_set_wire_format(fmt: String): Unit =
       |  _ssc_api_wire_format = fmt
       |
       |private val _SscWireMsgPack = "application/vnd.scalascript.wire+msgpack"
       |private val _SscWireCbor    = "application/vnd.scalascript.wire+cbor"
       |
       |private def _ssc_api_accept_header: String = _ssc_api_wire_format match
       |  case "cbor"    => s"$_SscWireCbor, $_SscWireMsgPack, application/json;q=0.5"
       |  case "msgpack" => s"$_SscWireMsgPack, application/json;q=0.5"
       |  case _         => "application/json"
       |
       |private def _ssc_api_encode_binary_body(jsonBody: String): Array[Byte] =
       |  val payload = scalascript.wire.WireValue.Str(jsonBody)
       |  val env     = scalascript.wire.WireEnvelope.rpc(_ssc_api_wire_format, "request", payload)
       |  _ssc_api_wire_format match
       |    case "cbor"    => scalascript.wire.cbor.CborWireCodec.encodeEnvelope(env)
       |    case "msgpack" => scalascript.wire.msgpack.MsgPackWireCodec.encodeEnvelope(env)
       |    case _         => jsonBody.getBytes(java.nio.charset.StandardCharsets.UTF_8)
       |
       |private def _ssc_api_decode_binary_response(
       |  bytes: Array[Byte], contentType: String
       |): scalascript.backend.spi.BackendResponse =
       |  val ct = contentType.split(';').head.trim.toLowerCase
       |  val envResult = ct match
       |    case `_SscWireMsgPack` | "application/x-msgpack" =>
       |      scalascript.wire.msgpack.MsgPackWireCodec.decodeEnvelope(bytes)
       |    case `_SscWireCbor` | "application/cbor" =>
       |      scalascript.wire.cbor.CborWireCodec.decodeEnvelope(bytes)
       |    case _ => Left(scalascript.wire.WireDecodeError.MalformedInput("not a wire response"))
       |  envResult match
       |    case Left(_)  => scalascript.backend.spi.BackendResponse(200, Map.empty, bytes)
       |    case Right(e) => e.payload match
       |      case scalascript.wire.WireValue.Str(json) =>
       |        scalascript.backend.spi.BackendResponse(200, Map("Content-Type" -> "application/json"),
       |          json.getBytes(java.nio.charset.StandardCharsets.UTF_8))
       |      case _ => scalascript.backend.spi.BackendResponse(200, Map.empty, bytes)
       |
       |class _SscCancelToken:
       |  private val _cancelled = new java.util.concurrent.atomic.AtomicBoolean(false)
       |  def cancel(): Unit = _cancelled.set(true)
       |  def isCancelled: Boolean = _cancelled.get()
       |def _ssc_api_cancel_token(): _SscCancelToken = new _SscCancelToken()
       |
       |private def _ssc_api_send(
       |  req: scalascript.backend.spi.BackendRequest,
       |  maxRetries: Int, delayMs: Long, attempt: Int,
       |  cancelToken: _SscCancelToken
       |): scalascript.backend.spi.BackendResponse =
       |  if cancelToken != null && cancelToken.isCancelled then
       |    throw RuntimeException("typed route client: request cancelled")
       |  try
       |    val resp = scala.concurrent.Await.result(
       |      _ssc_ui_backend_transport.request(req), scala.concurrent.duration.Duration.Inf)
       |    if resp.status >= 500 && attempt < maxRetries then
       |      if delayMs > 0 then Thread.sleep(delayMs)
       |      _ssc_api_send(req, maxRetries, delayMs, attempt + 1, cancelToken)
       |    else resp
       |  catch
       |    case _: Exception if attempt < maxRetries =>
       |      if cancelToken != null && cancelToken.isCancelled then
       |        throw RuntimeException("typed route client: request cancelled")
       |      if delayMs > 0 then Thread.sleep(delayMs)
       |      _ssc_api_send(req, maxRetries, delayMs, attempt + 1, cancelToken)
       |
       |inline def _ssc_api_request[Req, Resp](methodRaw: String, pathTemplate: String, input: Req, callHeaders: Map[String, String] = Map.empty, cancelToken: _SscCancelToken = null): Resp =
       |  if cancelToken != null && cancelToken.isCancelled then
       |    throw RuntimeException("typed route client: request cancelled")
       |  val method   = methodRaw.toUpperCase
       |  val url      = _ssc_api_path(pathTemplate, input) + _ssc_api_query(pathTemplate, input)
       |  val jsonBody = _ssc_api_body[Req](method, input)
       |  val useBinary = _ssc_api_wire_format != "json" && jsonBody.nonEmpty
       |  val (reqBodyBytes, ctHeader): (Array[Byte], Map[String, String]) =
       |    if useBinary then
       |      val raw = _ssc_api_encode_binary_body(jsonBody)
       |      val b64 = java.util.Base64.getEncoder.encodeToString(raw)
       |      (_ssc_ui_utf8(b64), Map("Content-Type" -> (if _ssc_api_wire_format == "cbor" then _SscWireCbor else _SscWireMsgPack)))
       |    else if jsonBody.nonEmpty then
       |      (_ssc_ui_utf8(jsonBody), Map("Content-Type" -> "application/json"))
       |    else
       |      (Array.emptyByteArray, Map.empty)
       |  val acceptHeader = Map("Accept" -> _ssc_api_accept_header)
       |  val req = scalascript.backend.spi.BackendRequest(method, url,
       |    ctHeader ++ acceptHeader ++ _ssc_api_extra_headers ++ callHeaders, reqBodyBytes)
       |  val (maxRetries, delayMs) = _ssc_api_retry_policy
       |  val rawResponse = _ssc_api_send(req, maxRetries, delayMs, 0, cancelToken)
       |  val responseBody = String(rawResponse.body, java.nio.charset.StandardCharsets.UTF_8)
       |  if rawResponse.status < 200 || rawResponse.status >= 300 then
       |    throw RuntimeException("typed route client: " + method + " " + url + " returned " + rawResponse.status + ": " + responseBody)
       |  val respCt = rawResponse.headers.get("Content-Type").orElse(rawResponse.headers.get("content-type")).getOrElse("")
       |  val response =
       |    if respCt.toLowerCase.contains("vnd.scalascript.wire") then
       |      try
       |        val decoded = java.util.Base64.getDecoder.decode(responseBody.trim)
       |        _ssc_api_decode_binary_response(decoded, respCt)
       |      catch case _: Exception => rawResponse
       |    else rawResponse
       |  _ssc_typed_json_decode_response[Resp](response)
       |
       |trait _SscWsHandle extends AutoCloseable:
       |  def send(msg: String): Unit
       |  def close(): Unit
       |
       |private def _ssc_api_ws_decode_frame[Resp](bytes: Array[Byte])(using dec: io.circe.Decoder[Resp]): Either[String, Resp] =
       |  scalascript.wire.msgpack.MsgPackWireCodec.decodeEnvelope(bytes).orElse(
       |    scalascript.wire.cbor.CborWireCodec.decodeEnvelope(bytes)) match
       |    case Right(env) => env.payload match
       |      case scalascript.wire.WireValue.Str(json) => io.circe.parser.decode[Resp](json)
       |      case _ => Left("binary WS frame: expected string payload")
       |    case Left(_) =>
       |      Left("binary WS frame: decode error")
       |
       |def _ssc_api_ws_request[Req, Resp](
       |  pathTemplate: String, input: Req,
       |  onEvent: Resp => Unit, onError: String => Unit,
       |  onOpen: _SscWsHandle => Unit
       |)(using io.circe.Decoder[Resp], io.circe.Encoder[Req]): _SscWsHandle =
       |  val base = _ssc_api_base_url()
       |  val httpBase = if base.nonEmpty then base else ""
       |  val wsBase = httpBase.replaceFirst("^https://", "wss://").replaceFirst("^http://", "ws://")
       |  val path = _ssc_api_path(pathTemplate, input)
       |  val query = _ssc_api_query(pathTemplate, input)
       |  val uriStr = (if wsBase.nonEmpty then wsBase else "ws://localhost") + path + query
       |  val uri = java.net.URI.create(uriStr)
       |  @volatile var wsRef: java.net.http.WebSocket = null
       |  @volatile var closed = false
       |  val handle = new _SscWsHandle:
       |    def send(msg: String): Unit =
       |      if wsRef != null && !closed then
       |        if _ssc_api_wire_format != "json" then
       |          val raw = _ssc_api_encode_binary_body(msg)
       |          wsRef.sendBinary(java.nio.ByteBuffer.wrap(raw), true)
       |        else wsRef.sendText(msg, true)
       |    def close(): Unit = { closed = true; if wsRef != null then wsRef.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "") }
       |  val listener = new java.net.http.WebSocket.Listener:
       |    private val buf = new StringBuilder
       |    private val binBuf = new java.io.ByteArrayOutputStream
       |    override def onOpen(ws: java.net.http.WebSocket): Unit =
       |      wsRef = ws
       |      if input != (()) then
       |        try
       |          val jsonStr = io.circe.Encoder[Req].apply(input).noSpaces
       |          if _ssc_api_wire_format != "json" then
       |            ws.sendBinary(java.nio.ByteBuffer.wrap(_ssc_api_encode_binary_body(jsonStr)), true)
       |          else ws.sendText(jsonStr, true)
       |        catch case e: Exception => ()
       |      onOpen(handle)
       |      ws.request(1)
       |    override def onText(ws: java.net.http.WebSocket, data: CharSequence, last: Boolean): java.util.concurrent.CompletableFuture[?] =
       |      buf.append(data)
       |      if last then
       |        val text = buf.toString; buf.clear()
       |        io.circe.parser.decode[Resp](text) match
       |          case Right(v) => try onEvent(v) catch case e: Exception => onError("WS decode error: " + e.getMessage)
       |          case Left(e)  => onError("WS decode error: " + e.getMessage)
       |      ws.request(1)
       |      null
       |    override def onBinary(ws: java.net.http.WebSocket, data: java.nio.ByteBuffer, last: Boolean): java.util.concurrent.CompletableFuture[?] =
       |      val arr = new Array[Byte](data.remaining); data.get(arr); binBuf.write(arr)
       |      if last then
       |        val bytes = binBuf.toByteArray; binBuf.reset()
       |        _ssc_api_ws_decode_frame[Resp](bytes) match
       |          case Right(v) => try onEvent(v) catch case e: Exception => onError("WS binary decode error: " + e.getMessage)
       |          case Left(e)  => onError("WS binary decode error: " + e)
       |      ws.request(1)
       |      null
       |    override def onError(ws: java.net.http.WebSocket, error: Throwable): Unit =
       |      if !closed then onError("WS error: " + error.getMessage)
       |    override def onClose(ws: java.net.http.WebSocket, statusCode: Int, reason: String): java.util.concurrent.CompletableFuture[?] =
       |      closed = true; null
       |  java.net.http.HttpClient.newHttpClient()
       |    .newWebSocketBuilder()
       |    .buildAsync(uri, listener)
       |    .exceptionally { e => onError("WS connect error: " + e.getMessage); null }
       |  handle
       |
       |def _ssc_api_stream_request[Req, Resp](
       |  methodRaw: String, pathTemplate: String, input: Req,
       |  onEvent: Resp => Unit, onError: String => Unit,
       |  callHeaders: Map[String, String] = Map.empty
       |)(using io.circe.Decoder[Resp], io.circe.Encoder[Req]): AutoCloseable =
       |  val method = methodRaw.toUpperCase
       |  val url = _ssc_api_base_url() + _ssc_api_path(pathTemplate, input) + _ssc_api_query(pathTemplate, input)
       |  val body = _ssc_api_body[Req](method, input)
       |  val baseHeaders: Map[String, String] = Map("Accept" -> "text/event-stream") ++
       |    (if body.nonEmpty then Map("Content-Type" -> "application/json") else Map.empty)
       |  val allHeaders = baseHeaders ++ _ssc_api_extra_headers ++ callHeaders
       |  @volatile var closed = false
       |  val thread = new Thread(() => {
       |    try
       |      val jurl = new java.net.URL(url)
       |      val conn = jurl.openConnection().asInstanceOf[java.net.HttpURLConnection]
       |      conn.setRequestMethod(method)
       |      allHeaders.foreach { case (k, v) => conn.setRequestProperty(k, v) }
       |      if body.nonEmpty then
       |        conn.setDoOutput(true)
       |        val os = conn.getOutputStream
       |        os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
       |        os.flush()
       |      val status = conn.getResponseCode
       |      if status < 200 || status >= 300 then
       |        onError("typed route client SSE: " + method + " " + url + " returned " + status)
       |      else
       |        val reader = new java.io.BufferedReader(
       |          new java.io.InputStreamReader(conn.getInputStream, java.nio.charset.StandardCharsets.UTF_8))
       |        try
       |          var line: String = null
       |          while !closed && { line = reader.readLine(); line != null } do
       |            if line.startsWith("data:") then
       |              val data = line.stripPrefix("data:").trim
       |              if data.nonEmpty then
       |                io.circe.parser.decode[Resp](data) match
       |                  case Right(v) => onEvent(v)
       |                  case Left(e)  => onError("SSE decode error: " + e.getMessage)
       |        finally reader.close()
       |    catch
       |      case _: InterruptedException => ()
       |      case e: Exception => if !closed then onError("SSE error: " + e.getMessage)
       |  }, "_ssc_sse")
       |  thread.setDaemon(true)
       |  thread.start()
       |  new AutoCloseable:
       |    def close(): Unit =
       |      closed = true
       |      thread.interrupt()
       |
       |""".stripMargin
