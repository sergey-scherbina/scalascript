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
//
// Shared outbound (client-side) WebSocket session.  Both the interpreter
// (via `scalascript.server.WsClientSession`, a thin bridge) and the JVM
// codegen runtime (via the `wsConnect(url)` user-facing function below)
// use this single class — exactly the inbound-side pattern landed in
// Phase 3b/3c, where `scalascript.server.jvm.WebSocket` is the shared
// per-connection state and `scalascript.server.WsConnection` is the
// interpreter-side bridge that wraps it into `Value.InstanceV(...)`.
//
// The class wraps `java.net.http.HttpClient.newWebSocketBuilder()`,
// which handles the RFC 6455 handshake, masking, TLS (wss://) and
// ping/pong frames automatically.  Internal callbacks are plain
// `String => Unit` / `() => Unit` functions; both backends wrap their
// own callback types (Value.Closure for the interpreter, raw lambdas
// for the codegen `wsMap`) into that shape at the boundary.

class WsClient(
    url:       String,
    extraHdrs: Map[String, String],
    protocols: List[String],
    _log:      java.io.PrintStream = System.err):
  import java.net.URI
  import java.net.http.{HttpClient => _JHttpClient, WebSocket => _JWs}
  import java.nio.ByteBuffer
  import java.util.concurrent.{LinkedBlockingQueue, CountDownLatch, CompletableFuture}
  import java.util.concurrent.atomic.AtomicReference

  val id: String = java.util.UUID.randomUUID().toString
  @volatile private var _ws: _JWs | Null = null
  @volatile private var closingSent  = false
  @volatile private var closedFired  = false
  @volatile private var _subprotocol = ""
  private val onCloseCbRef = new AtomicReference[() => Unit](null)
  @volatile private var onMessageCb: String => Unit = null
  @volatile private var onPongCb:    String => Unit = null
  private val recvQueue  = new LinkedBlockingQueue[String | Null]()
  private val closeLatch = new CountDownLatch(1)
  private val textBuf    = new StringBuilder()

  private def dispatch(f: () => Unit): Unit =
    try f()
    catch case e: Throwable => _log.println(s"wsConnect callback error: ${e.getMessage}")

  private def doClose(): Unit =
    if !closedFired then
      closedFired = true
      recvQueue.offer(null)
      val cb = onCloseCbRef.getAndSet(null)
      if cb != null then dispatch { () => cb() }
      closeLatch.countDown()

  private val _listener: _JWs.Listener = new _JWs.Listener:
    override def onText(ws: _JWs, data: CharSequence, last: Boolean): CompletableFuture[?] =
      textBuf.append(data)
      if last then
        val msg = textBuf.toString(); textBuf.setLength(0)
        Metrics.wsMessagesIn.incrementAndGet()
        recvQueue.offer(msg)
        val cb = onMessageCb
        if cb != null then dispatch { () => cb(msg) }
      ws.request(1)
      CompletableFuture.completedFuture(null)
    override def onBinary(ws: _JWs, data: ByteBuffer, last: Boolean): CompletableFuture[?] =
      if last then
        val bytes = new Array[Byte](data.remaining()); data.get(bytes)
        val msg = new String(bytes, "ISO-8859-1")
        Metrics.wsMessagesIn.incrementAndGet()
        recvQueue.offer(msg)
        val cb = onMessageCb
        if cb != null then dispatch { () => cb(msg) }
      ws.request(1)
      CompletableFuture.completedFuture(null)
    override def onClose(ws: _JWs, statusCode: Int, reason: String): CompletableFuture[?] =
      doClose(); CompletableFuture.completedFuture(null)
    override def onPong(ws: _JWs, message: ByteBuffer): CompletableFuture[?] =
      val cb = onPongCb
      if cb != null then
        val payload = new String(message.array(), "ISO-8859-1")
        dispatch { () => cb(payload) }
      CompletableFuture.completedFuture(null)
    override def onError(ws: _JWs | Null, error: Throwable): Unit =
      _log.println(s"wsConnect error [$url]: ${error.getMessage}")
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

  def send(s: String): Unit =
    if !closingSent then _ws match
      case ws if ws != null =>
        Metrics.wsMessagesOut.incrementAndGet()
        ws.sendText(s, true)
      case _ => ()

  def sendBytes(s: String): Unit =
    if !closingSent then _ws match
      case ws if ws != null =>
        Metrics.wsMessagesOut.incrementAndGet()
        ws.sendBinary(ByteBuffer.wrap(s.getBytes("ISO-8859-1")), true)
      case _ => ()

  def sendBinary(bytes: Array[Byte]): Unit =
    if !closingSent then _ws match
      case ws if ws != null =>
        Metrics.wsMessagesOut.incrementAndGet()
        ws.sendBinary(ByteBuffer.wrap(bytes), true)
      case _ => ()

  def close(code: Int = 1000, reason: String = ""): Unit =
    if !closingSent then
      closingSent = true
      _ws match
        case ws if ws != null => ws.sendClose(code, reason)
        case _                => doClose()

  def ping(): Unit = ping("")
  def ping(payload: String): Unit =
    _ws match
      case ws if ws != null =>
        val bytes =
          if payload.isEmpty then ByteBuffer.allocate(0)
          else ByteBuffer.wrap(payload.getBytes("ISO-8859-1"))
        ws.sendPing(bytes)
      case _ => ()

  def onMessage(cb: String => Unit): Unit = onMessageCb   = cb
  def onClose(cb: () => Unit):       Unit = onCloseCbRef.set(cb)
  def onPong(cb: String => Unit):    Unit = onPongCb      = cb

  def recv(): Option[String] =
    val v = recvQueue.take()
    if v == null then None else Some(v)

  def isClosed: Boolean = closingSent

  /** Hard-close the underlying WebSocket (e.g. heartbeat timeout).
   *  Skips the close-frame handshake; falls back to `doClose()` if the
   *  socket was never connected or already torn down. */
  def abort(): Unit =
    try _ws match
      case ws if ws != null => ws.abort()
      case _                => doClose()
    catch case _: Throwable => doClose()

  /** User-facing Map shape returned to scripts by `wsConnect(url)(h)`.
   *  Key names and value shapes are part of the codegen ABI — emitted
   *  scripts pattern-match on these names directly, so they must not
   *  change.  The Map wraps the typed methods above with the lossy
   *  `Any` shape the codegen Map calling convention uses. */
  def wsMap: Map[String, Any] = Map(
    "id"          -> id,
    "subprotocol" -> _subprotocol,
    "send"        -> ((s: Any) => { send(s.toString); () }),
    "sendBytes"   -> ((s: Any) => { sendBytes(s.toString); () }),
    "close"       -> ((args: Any) => {
                       args match
                         case ()                     => close(1000, "")
                         case code: Int              => close(code, "")
                         case (code: Int, r: String) => close(code, r)
                         case _                      => close(1000, "")
                       () }),
    "onMessage"   -> ((cb: Any) => { onMessage(cb.asInstanceOf[String => Any].andThen(_ => ())); () }),
    "onClose"     -> ((cb: Any) => { onClose(() => { cb.asInstanceOf[() => Any](); () }); () }),
    "ping"        -> ((payload: Any) => {
                       payload match
                         case s: String => ping(s)
                         case _         => ping()
                       () }),
    "onPong"      -> ((cb: Any) => { onPong(cb.asInstanceOf[String => Any].andThen(_ => ())); () }),
    "recv"        -> (() => recv()),
    "isClosed"    -> (() => isClosed)
  )

def wsConnect(url: String, headers: Map[String, String] = Map.empty, protocols: List[String] = Nil)(handler: Map[String, Any] => Any): Any =
  val sess = WsClient(url, headers, protocols)
  sess.connect()
  handler(sess.wsMap)
  sess.awaitClose()
  ()

