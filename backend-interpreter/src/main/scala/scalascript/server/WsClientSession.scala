package scalascript.server

import java.net.URI
import java.net.http.{HttpClient as JHttpClient, WebSocket as JWs}
import java.nio.ByteBuffer
import java.util.concurrent.{LinkedBlockingQueue, CountDownLatch, CompletableFuture}
import java.util.concurrent.atomic.AtomicReference
import scalascript.interpreter.{Interpreter, Value, Computation, InterpretError}

/** Outbound WebSocket client session for the interpreter.
 *
 *  Uses `java.net.http.HttpClient.newWebSocketBuilder()` which handles
 *  the RFC 6455 handshake, masking, TLS (wss://) and ping/pong automatically.
 *
 *  Threading model:
 *   - connect() blocks until the HTTP 101 handshake completes.
 *   - The user's handler is invoked on the calling thread.
 *   - Listener callbacks (onText / onClose / onPong) arrive on the JDK
 *     HttpClient's thread pool and call interpreter.invoke() directly,
 *     matching BlockingWsSession's dispatch approach.
 *   - awaitClose() blocks the calling thread until onClose fires. */
final class WsClientSession(
    url:         String,
    extraHdrs:   Map[String, String],
    protocols:   List[String],
    interpreter: Interpreter,
    log:         java.io.PrintStream
):
  val id: String = java.util.UUID.randomUUID().toString

  @volatile private var _ws: JWs | Null = null
  @volatile private var closingSent  = false
  @volatile private var closedFired  = false
  @volatile private var _subprotocol = ""

  private val onCloseCb:    AtomicReference[Value | Null] = AtomicReference(null)
  @volatile private var onMessageCb: Option[Value] = None
  @volatile private var onPongCb:    Option[Value] = None
  private val recvQueue = LinkedBlockingQueue[String | Null]()
  private val closeLatch = CountDownLatch(1)
  private val textBuf = new StringBuilder()

  private val listener: JWs.Listener = new JWs.Listener:
    override def onText(ws: JWs, data: CharSequence, last: Boolean): CompletableFuture[?] =
      textBuf.append(data)
      if last then
        val msg = textBuf.toString(); textBuf.setLength(0)
        Metrics.wsMessagesIn.incrementAndGet()
        recvQueue.offer(msg)
        onMessageCb.foreach { cb =>
          dispatch { () => interpreter.invoke(cb, List(Value.StringV(msg))) }
        }
      ws.request(1)
      CompletableFuture.completedFuture(null)

    override def onBinary(ws: JWs, data: ByteBuffer, last: Boolean): CompletableFuture[?] =
      if last then
        val bytes = new Array[Byte](data.remaining()); data.get(bytes)
        val msg = new String(bytes, "ISO-8859-1")
        Metrics.wsMessagesIn.incrementAndGet()
        recvQueue.offer(msg)
        onMessageCb.foreach { cb =>
          dispatch { () => interpreter.invoke(cb, List(Value.StringV(msg))) }
        }
      ws.request(1)
      CompletableFuture.completedFuture(null)

    override def onClose(ws: JWs, statusCode: Int, reason: String): CompletableFuture[?] =
      doClose()
      CompletableFuture.completedFuture(null)

    override def onPong(ws: JWs, message: ByteBuffer): CompletableFuture[?] =
      onPongCb.foreach { cb =>
        val payload = Value.StringV(new String(message.array(), "ISO-8859-1"))
        dispatch { () => interpreter.invoke(cb, List(payload)) }
      }
      CompletableFuture.completedFuture(null)

    override def onError(ws: JWs | Null, error: Throwable): Unit =
      log.println(s"wsConnect error [$url]: ${error.getMessage}")
      doClose()

  def connect(): Unit =
    val builder = JHttpClient.newHttpClient().newWebSocketBuilder()
    extraHdrs.foreach { case (k, v) => builder.header(k, v) }
    if protocols.nonEmpty then builder.subprotocols(protocols.head, protocols.tail*)
    val ws = builder.buildAsync(URI.create(url), listener).join()
    _ws = ws
    _subprotocol = ws.getSubprotocol

  def awaitClose(): Unit = closeLatch.await()

  def subprotocol: String = _subprotocol

  /** Scala-side send — bypasses the Value/Computation machinery for use on
   *  virtual threads without involving the interpreter's actor scheduler. */
  def sendText(text: String): Unit =
    _ws match
      case ws if ws != null =>
        Metrics.wsMessagesOut.incrementAndGet()
        ws.sendText(text, true)
      case _ => ()

  /** Blocking receive — returns None on close.  Safe to call on virtual
   *  threads; blocks the carrier only while the OS is waiting for data. */
  def recvText(): Option[String] =
    val v = recvQueue.take()
    if v == null then None else Some(v)

  private def dispatch(f: () => Unit): Unit =
    try f()
    catch case e: Throwable => log.println(s"wsConnect callback error: ${e.getMessage}")

  private def doClose(): Unit =
    if !closedFired then
      closedFired = true
      recvQueue.offer(null)
      val cb = onCloseCb.getAndSet(null)
      if cb != null then dispatch { () => interpreter.invoke(cb, Nil) }
      closeLatch.countDown()

  def wsObj: Value =
    import Value.*; import Computation.*
    InstanceV("WebSocket", Map(
      "id" -> StringV(id),
      "send" -> NativeFnV("WebSocket.send", pureFn {
        case List(StringV(s)) =>
          if !closingSent then _ws match
            case ws if ws != null =>
              Metrics.wsMessagesOut.incrementAndGet()
              ws.sendText(s, true)
            case _ => ()
          UnitV
        case _ => throw InterpretError("ws.send(text)")
      }),
      "sendBytes" -> NativeFnV("WebSocket.sendBytes", pureFn {
        case List(StringV(s)) =>
          if !closingSent then _ws match
            case ws if ws != null =>
              Metrics.wsMessagesOut.incrementAndGet()
              ws.sendBinary(ByteBuffer.wrap(s.getBytes("ISO-8859-1")), true)
            case _ => ()
          UnitV
        case _ => throw InterpretError("ws.sendBytes(bytes)")
      }),
      "close" -> NativeFnV("WebSocket.close", pureFn {
        case Nil =>
          if !closingSent then { closingSent = true; _ws match { case ws if ws != null => ws.sendClose(1000, ""); case _ => doClose() } }
          UnitV
        case List(IntV(code)) =>
          if !closingSent then { closingSent = true; _ws match { case ws if ws != null => ws.sendClose(code.toInt, ""); case _ => doClose() } }
          UnitV
        case List(IntV(code), StringV(r)) =>
          if !closingSent then { closingSent = true; _ws match { case ws if ws != null => ws.sendClose(code.toInt, r); case _ => doClose() } }
          UnitV
        case _ => throw InterpretError("ws.close() or ws.close(code) or ws.close(code, reason)")
      }),
      "onMessage" -> NativeFnV("WebSocket.onMessage", pureFn {
        case List(cb) => onMessageCb = Some(cb); UnitV
        case _ => throw InterpretError("ws.onMessage { msg => … }")
      }),
      "onClose" -> NativeFnV("WebSocket.onClose", pureFn {
        case List(cb) => onCloseCb.set(cb); UnitV
        case _ => throw InterpretError("ws.onClose { () => … }")
      }),
      "ping" -> NativeFnV("WebSocket.ping", pureFn {
        case Nil =>
          _ws match { case ws if ws != null => ws.sendPing(ByteBuffer.allocate(0)); case _ => () }
          UnitV
        case List(StringV(s)) =>
          _ws match { case ws if ws != null => ws.sendPing(ByteBuffer.wrap(s.getBytes("ISO-8859-1"))); case _ => () }
          UnitV
        case _ => throw InterpretError("ws.ping() or ws.ping(payload)")
      }),
      "onPong" -> NativeFnV("WebSocket.onPong", pureFn {
        case List(cb) => onPongCb = Some(cb); UnitV
        case _ => throw InterpretError("ws.onPong { payload => … }")
      }),
      "recv" -> NativeFnV("WebSocket.recv", pureFn {
        case Nil =>
          val v = recvQueue.take()
          if v == null then OptionV(None) else OptionV(Some(StringV(v)))
        case _ => throw InterpretError("ws.recv()")
      }),
      "isClosed" -> NativeFnV("WebSocket.isClosed", pureFn {
        case Nil => BoolV(closingSent)
        case _   => throw InterpretError("ws.isClosed")
      }),
      "subprotocol" -> StringV(subprotocol)
    ))
