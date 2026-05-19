package scalascript.server.jvm

// Phase 3e — JVM-codegen outbound HTTP + WebSocket client runtime
// (Part2 lines 3242-3456 of the original serveRuntime string template,
// ~215 LOC).  Owns the `http(url)` REST client + the `wsConnect(url)`
// blocking WS client used by user code.

// BUILD-ONLY:start
import scalascript.server.*
// BUILD-ONLY:end

// ── Outbound HTTP client ────────────────────────────────────────────────
private var _httpBaseUrl:    String = ""
private var _httpTimeoutMs:  Long   = 30_000L
private var _httpMaxRetries: Int    = 0
private var _httpRetryDelay: Long   = 1_000L

def httpTimeout(ms: Int): Unit  = _httpTimeoutMs = ms.toLong
def httpRetry(n: Int, delayMs: Int = 1000): Unit = { _httpMaxRetries = n; _httpRetryDelay = delayMs.toLong }

private def _httpDoRequest(method: String, url: String, body: String,
    headers: Map[String, String]): Any =
  import java.net.http.{HttpClient as JHC, HttpRequest, HttpResponse}
  import scala.jdk.CollectionConverters.*
  val effectiveUrl = if _httpBaseUrl.nonEmpty && !url.startsWith("http") then _httpBaseUrl + url else url
  val timeout = java.time.Duration.ofMillis(_httpTimeoutMs)
  val client  = JHC.newBuilder().connectTimeout(timeout).build()
  val builder = HttpRequest.newBuilder().uri(java.net.URI.create(effectiveUrl)).timeout(timeout)
  headers.foreach((k, v) => builder.header(k, v))
  val req = method match
    case "GET"    => builder.GET().build()
    case "DELETE" => builder.DELETE().build()
    case m        => builder.method(m, HttpRequest.BodyPublishers.ofString(body)).build()
  val maxTries = _httpMaxRetries + 1
  var attempt = 0; var lastResp: HttpResponse[String] | Null = null; var lastErr: Throwable | Null = null
  while attempt < maxTries do
    try { lastResp = client.send(req, HttpResponse.BodyHandlers.ofString()); lastErr = null }
    catch case e: Throwable => lastErr = e
    val shouldRetry = lastErr != null || (lastResp != null && lastResp.statusCode() >= 500)
    attempt += 1
    if shouldRetry && attempt < maxTries then Thread.sleep(_httpRetryDelay)
    else attempt = maxTries
  if lastErr != null then throw lastErr
  val resp = lastResp.nn
  val hdrs = resp.headers().map().entrySet().iterator().asScala.flatMap { e =>
    if e.getValue.isEmpty then None
    else Some(e.getKey -> e.getValue.get(0))
  }.toMap
  Response(status = resp.statusCode(), body = resp.body(), headers = hdrs)

def httpGet(url: String, headers: Map[String, String] = Map.empty): Any =
  _httpDoRequest("GET", url, "", headers)

def httpPost(url: String, body: String, headers: Map[String, String] = Map.empty): Any =
  _httpDoRequest("POST", url, body, headers)

def httpPut(url: String, body: String, headers: Map[String, String] = Map.empty): Any =
  _httpDoRequest("PUT", url, body, headers)

def httpPatch(url: String, body: String, headers: Map[String, String] = Map.empty): Any =
  _httpDoRequest("PATCH", url, body, headers)

def httpDelete(url: String, headers: Map[String, String] = Map.empty): Any =
  _httpDoRequest("DELETE", url, "", headers)

def httpClient(baseUrl: String)(block: => Any): Any =
  val priorBase = _httpBaseUrl; val priorT = _httpTimeoutMs
  val priorR = _httpMaxRetries; val priorD = _httpRetryDelay
  _httpBaseUrl = baseUrl
  try block finally { _httpBaseUrl = priorBase; _httpTimeoutMs = priorT
                       _httpMaxRetries = priorR; _httpRetryDelay = priorD }

// Streaming variants — call handler for each line as it arrives.
// Uses BodyHandlers.ofLines() so lines are emitted incrementally.
private def _httpDoRequestStream(method: String, url: String, body: String,
    headers: Map[String, String], handler: String => Any): Any =
  import java.net.http.{HttpClient as JHC, HttpRequest, HttpResponse}
  import scala.jdk.CollectionConverters.*
  val effectiveUrl = if _httpBaseUrl.nonEmpty && !url.startsWith("http") then _httpBaseUrl + url else url
  val timeout = java.time.Duration.ofMillis(_httpTimeoutMs)
  val client  = JHC.newBuilder().connectTimeout(timeout).build()
  val builder = HttpRequest.newBuilder().uri(java.net.URI.create(effectiveUrl))
  headers.foreach((k, v) => builder.header(k, v))
  val req = method match
    case "GET"  => builder.GET().build()
    case m      => builder.method(m, HttpRequest.BodyPublishers.ofString(body)).build()
  val resp = client.send(req, HttpResponse.BodyHandlers.ofLines())
  val hdrs = resp.headers().map().entrySet().iterator().asScala.flatMap { e =>
    if e.getValue.isEmpty then None else Some(e.getKey -> e.getValue.get(0))
  }.toMap
  resp.body().forEach { line => handler(line) }
  Response(status = resp.statusCode(), headers = hdrs, body = "")

def httpGetStream(url: String, headers: Map[String, String] = Map.empty)(handler: String => Any): Any =
  _httpDoRequestStream("GET", url, "", headers, handler)

def httpPostStream(url: String, body: String, headers: Map[String, String] = Map.empty)(handler: String => Any): Any =
  _httpDoRequestStream("POST", url, body, headers, handler)

// ── Outbound WebSocket client ─────────────────────────────────────────

private class _WsClientConn(url: String, extraHdrs: Map[String, String], protocols: List[String]):
  import java.net.URI
  import java.net.http.{HttpClient => _JHttpClient, WebSocket => _JWs}
  import java.nio.ByteBuffer
  import java.util.concurrent.{LinkedBlockingQueue, CountDownLatch, CompletableFuture}
  import java.util.concurrent.atomic.AtomicReference

  val id: String = java.util.UUID.randomUUID().toString
  @volatile private var _ws: _JWs | Null = null
  @volatile private var closingSent = false
  @volatile private var closedFired = false
  @volatile private var _subprotocol = ""
  private val onCloseCbRef  = new AtomicReference[Any | Null](null)
  @volatile private var onMessageCb: Option[Any] = None
  @volatile private var onPongCb:    Option[Any] = None
  private val recvQueue = new LinkedBlockingQueue[String | Null]()
  private val closeLatch = new CountDownLatch(1)
  private val textBuf = new StringBuilder()

  private def dispatch(f: () => Unit): Unit =
    try f()
    catch case e: Throwable => System.err.println(s"wsConnect callback error: ${e.getMessage}")

  private def doClose(): Unit =
    if !closedFired then
      closedFired = true
      recvQueue.offer(null)
      val cb = onCloseCbRef.getAndSet(null)
      if cb != null then dispatch { () => cb.asInstanceOf[() => Any]() }
      closeLatch.countDown()

  private val _listener: _JWs.Listener = new _JWs.Listener:
    override def onText(ws: _JWs, data: CharSequence, last: Boolean): CompletableFuture[?] =
      textBuf.append(data)
      if last then
        val msg = textBuf.toString(); textBuf.setLength(0)
        recvQueue.offer(msg)
        onMessageCb.foreach { cb =>
          dispatch { () => cb.asInstanceOf[String => Any](msg) }
        }
      ws.request(1)
      CompletableFuture.completedFuture(null)
    override def onBinary(ws: _JWs, data: ByteBuffer, last: Boolean): CompletableFuture[?] =
      if last then
        val bytes = new Array[Byte](data.remaining()); data.get(bytes)
        val msg = new String(bytes, "ISO-8859-1")
        recvQueue.offer(msg)
        onMessageCb.foreach { cb =>
          dispatch { () => cb.asInstanceOf[String => Any](msg) }
        }
      ws.request(1)
      CompletableFuture.completedFuture(null)
    override def onClose(ws: _JWs, statusCode: Int, reason: String): CompletableFuture[?] =
      doClose(); CompletableFuture.completedFuture(null)
    override def onPong(ws: _JWs, message: ByteBuffer): CompletableFuture[?] =
      onPongCb.foreach { cb =>
        val payload = new String(message.array(), "ISO-8859-1")
        dispatch { () => cb.asInstanceOf[String => Any](payload) }
      }
      CompletableFuture.completedFuture(null)
    override def onError(ws: _JWs | Null, error: Throwable): Unit =
      System.err.println(s"wsConnect error [$url]: ${error.getMessage}")
      doClose()

  def connect(): Unit =
    val builder = _JHttpClient.newHttpClient().newWebSocketBuilder()
    extraHdrs.foreach { case (k, v) => builder.header(k, v) }
    if protocols.nonEmpty then builder.subprotocols(protocols.head, protocols.tail*)
    val ws = builder.buildAsync(URI.create(url), _listener).join()
    _ws = ws
    _subprotocol = ws.getSubprotocol

  def awaitClose(): Unit = closeLatch.await()
  def subprotocol: String = _subprotocol

  def wsMap: Map[String, Any] = Map(
    "id"          -> id,
    "subprotocol" -> _subprotocol,
    "send"        -> ((s: Any) => {
                       if !closingSent then _ws match
                         case ws if ws != null => ws.sendText(s.toString, true)
                         case _ => ()
                       () }),
    "sendBytes"   -> ((s: Any) => {
                       if !closingSent then _ws match
                         case ws if ws != null =>
                           ws.sendBinary(ByteBuffer.wrap(s.toString.getBytes("ISO-8859-1")), true)
                         case _ => ()
                       () }),
    "close"       -> ((args: Any) => {
                       if !closingSent then
                         closingSent = true
                         _ws match
                           case ws if ws != null =>
                             args match
                               case ()             => ws.sendClose(1000, "")
                               case code: Int      => ws.sendClose(code, "")
                               case (code: Int, r: String) => ws.sendClose(code, r)
                               case _              => ws.sendClose(1000, "")
                           case _ => doClose()
                       () }),
    "onMessage"   -> ((cb: Any) => { onMessageCb = Some(cb); () }),
    "onClose"     -> ((cb: Any) => { onCloseCbRef.set(cb); () }),
    "ping"        -> ((payload: Any) => {
                       _ws match
                         case ws if ws != null =>
                           payload match
                             case s: String => ws.sendPing(ByteBuffer.wrap(s.getBytes("ISO-8859-1")))
                             case _         => ws.sendPing(ByteBuffer.allocate(0))
                         case _ => ()
                       () }),
    "onPong"      -> ((cb: Any) => { onPongCb = Some(cb); () }),
    "recv"        -> (() => {
                       val v = recvQueue.take()
                       if v == null then None else Some(v) }),
    "isClosed"    -> (() => closingSent)
  )

def wsConnect(url: String, headers: Map[String, String] = Map.empty, protocols: List[String] = Nil)(handler: Map[String, Any] => Any): Any =
  val sess = _WsClientConn(url, headers, protocols)
  sess.connect()
  handler(sess.wsMap)
  sess.awaitClose()
  ()

