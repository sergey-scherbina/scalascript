package scalascript.server

import java.net.Socket
import java.util.concurrent.{Executor, LinkedBlockingQueue}
import java.util.concurrent.atomic.AtomicReference
import scalascript.interpreter.{Interpreter, Value, Computation, InterpretError}

/** Blocking-IO WebSocket session for the interpreter's TLS mode.
 *
 *  Mirrors the `asValue` interface of [[WsConnection]] using plain
 *  blocking InputStream / OutputStream so it works naturally with
 *  SSLSocket (which has no NIO channel).  Each instance owns one
 *  accept()-ed socket; [[runReadLoop]] blocks the calling thread until
 *  the connection closes.
 *
 *  Frame writing is synchronised on `this` to prevent interleaving
 *  from concurrent `ws.send` / close / heartbeat callers. */
final class BlockingWsSession(
    socket:           Socket,
    in:               java.io.InputStream,
    out:              java.io.OutputStream,
    interpreter:      Interpreter,
    request:          Value,
    onTerminate:      () => Unit = () => (),
    maxMessagesPerSec: Int      = 0,
    user:             Option[Value] = None,
    val subprotocol:  String      = "",
    log:              java.io.PrintStream
):
  val id: String = java.util.UUID.randomUUID().toString
  private val startedAtMs = System.currentTimeMillis()

  @volatile private var closingSent  = false
  @volatile private var closedFired  = false
  @volatile private var recvEnabled  = false

  private val onCloseCb:    AtomicReference[Value | Null] = AtomicReference(null)
  @volatile private var onMessageCb: Option[Value]        = None
  @volatile private var onPongCb:    Option[Value]        = None

  private val recvQueue: LinkedBlockingQueue[String | Null] =
    new LinkedBlockingQueue[String | Null]()

  // ── Rate-limit state (fixed-window) ─────────────────────────────────
  private var rateWindowStart = 0L
  private var rateWindowCount = 0

  // ── Write helpers ────────────────────────────────────────────────────

  private def writeFrame(bytes: Array[Byte]): Unit = this.synchronized {
    try { out.write(bytes); out.flush() }
    catch case _: Throwable => doClose()
    Metrics.wsBytesOut.addAndGet(bytes.length.toLong)
  }

  // ── Public API (called from interpreter thread) ──────────────────────

  def send(text: String): Unit =
    if !closingSent then
      Metrics.wsMessagesOut.incrementAndGet()
      writeFrame(WsFraming.encodeText(text))

  def sendBinary(bytes: Array[Byte]): Unit =
    if !closingSent then
      Metrics.wsMessagesOut.incrementAndGet()
      writeFrame(WsFraming.encodeBinary(bytes))

  def sendClose(status: Int, reason: String): Unit =
    if !closingSent then
      closingSent = true
      writeFrame(WsFraming.encodeClose(status, reason))

  def ping(payload: Array[Byte] = Array.emptyByteArray): Unit =
    if !closingSent then writeFrame(WsFraming.encodePing(payload))

  def isClosed: Boolean = closingSent

  // ── Read loop (runs on the accept thread) ────────────────────────────

  /** Blocking read loop — returns when the connection closes cleanly or
   *  on any IO error.  Called from [[TlsProxy.handleConnection]] after
   *  handing `asValue` to the user's upgrade handler. */
  def runReadLoop(): Unit =
    val rawBuf = new Array[Byte](16 * 1024)
    var acc    = new Array[Byte](0)
    // Fragmented-message reassembly
    var fragOpcode: WsFraming.Opcode | Null = null
    val fragBuf = new java.io.ByteArrayOutputStream()
    try
      var running = true
      while running do
        val n = try in.read(rawBuf) catch case _: Throwable => -1
        if n < 0 then
          running = false
        else
          Metrics.wsBytesIn.addAndGet(n.toLong)
          acc = acc ++ rawBuf.slice(0, n)
          var offset = 0
          var parsing = true
          while parsing do
            WsFraming.tryParse(acc, offset, acc.length) match
              case None => parsing = false
              case Some(frame) =>
                offset += frame.consumed
                import WsFraming.Opcode
                frame.opcode match
                  case Opcode.Ping =>
                    writeFrame(WsFraming.encodePong(frame.payload))
                  case Opcode.Pong =>
                    onPongCb.foreach { cb =>
                      val payload = Value.StringV(new String(frame.payload, "ISO-8859-1"))
                      dispatchOn { () => interpreter.invoke(cb, List(payload)) }
                    }
                  case Opcode.Close =>
                    sendClose(1000, "")
                    running = false; parsing = false
                  case Opcode.Text | Opcode.Binary =>
                    val joined =
                      if frame.fin then frame.payload
                      else { fragOpcode = frame.opcode; fragBuf.reset(); fragBuf.write(frame.payload); null }
                    if joined != null then dispatchMessage(frame.opcode, joined, frame.fin)
                  case Opcode.Continuation =>
                    fragBuf.write(frame.payload)
                    if frame.fin then
                      val op = if fragOpcode != null then fragOpcode.asInstanceOf[WsFraming.Opcode]
                               else WsFraming.Opcode.Text
                      dispatchMessage(op, fragBuf.toByteArray, fin = true)
                      fragOpcode = null; fragBuf.reset()
          if offset > 0 then acc = acc.drop(offset)
    catch case _: Throwable => ()
    finally doClose()

  private def dispatchMessage(opcode: WsFraming.Opcode, payload: Array[Byte], fin: Boolean): Unit =
    if maxMessagesPerSec > 0 then
      val now = System.currentTimeMillis()
      if now - rateWindowStart > 1000L then
        rateWindowStart = now; rateWindowCount = 0
      rateWindowCount += 1
      if rateWindowCount > maxMessagesPerSec then
        sendClose(1008, "rate limit exceeded"); return

    Metrics.wsMessagesIn.incrementAndGet()
    val text = opcode match
      case WsFraming.Opcode.Text   => new String(payload, "UTF-8")
      case WsFraming.Opcode.Binary => new String(payload, "ISO-8859-1")
      case _                       => new String(payload, "UTF-8")
    if recvEnabled then recvQueue.offer(text)
    onMessageCb.foreach { cb =>
      val msg = Value.StringV(text)
      dispatchOn { () => interpreter.invoke(cb, List(msg)) }
    }

  private def dispatchOn(f: () => Unit): Unit =
    try f()
    catch case e: Throwable => log.println(s"TLS WS handler error: ${e.getMessage}")

  private def doClose(): Unit =
    if !closedFired then
      closedFired = true
      try socket.close() catch case _: Throwable => ()
      WsConnection.releaseSlot()
      try onTerminate() catch case _: Throwable => ()
      val durMs = System.currentTimeMillis() - startedAtMs
      log.println(s"ws.close\tid=$id\tduration_ms=$durMs")
      val cb = onCloseCb.getAndSet(null)
      if cb != null then dispatchOn { () => interpreter.invoke(cb, Nil) }
      if recvEnabled then recvQueue.offer(null)

  // ── Value interface (mirrors WsConnection.asValue) ───────────────────

  def asValue: Value =
    val sendFn = Value.NativeFnV("WebSocket.send", Computation.pureFn {
      case List(Value.StringV(s)) => send(s); Value.UnitV
      case _ => throw InterpretError("ws.send(text)")
    })
    val sendBytesFn = Value.NativeFnV("WebSocket.sendBytes", Computation.pureFn {
      case List(Value.StringV(s)) =>
        sendBinary(s.getBytes("ISO-8859-1")); Value.UnitV
      case _ => throw InterpretError("ws.sendBytes(bytes)")
    })
    val closeFn = Value.NativeFnV("WebSocket.close", Computation.pureFn {
      case Nil                                         => sendClose(1000, ""); Value.UnitV
      case List(Value.IntV(code))                      => sendClose(code.toInt, ""); Value.UnitV
      case List(Value.IntV(code), Value.StringV(r))    => sendClose(code.toInt, r); Value.UnitV
      case _ => throw InterpretError("ws.close() or ws.close(code) or ws.close(code, reason)")
    })
    val onMessageFn = Value.NativeFnV("WebSocket.onMessage", Computation.pureFn {
      case List(cb) => onMessageCb = Some(cb); Value.UnitV
      case _ => throw InterpretError("ws.onMessage { msg => … }")
    })
    val onCloseFn = Value.NativeFnV("WebSocket.onClose", Computation.pureFn {
      case List(cb) => onCloseCb.set(cb); Value.UnitV
      case _ => throw InterpretError("ws.onClose { () => … }")
    })
    val pingFn = Value.NativeFnV("WebSocket.ping", Computation.pureFn {
      case Nil               => ping(); Value.UnitV
      case List(Value.StringV(s)) => ping(s.getBytes("ISO-8859-1")); Value.UnitV
      case _ => throw InterpretError("ws.ping() or ws.ping(payload)")
    })
    val onPongFn = Value.NativeFnV("WebSocket.onPong", Computation.pureFn {
      case List(cb) => onPongCb = Some(cb); Value.UnitV
      case _ => throw InterpretError("ws.onPong { payload => … }")
    })
    val recvFn = Value.NativeFnV("WebSocket.recv", Computation.pureFn {
      case Nil =>
        recvEnabled = true
        val v = recvQueue.take()
        if v == null then Value.OptionV(None) else Value.OptionV(Some(Value.StringV(v)))
      case _ => throw InterpretError("ws.recv()")
    })
    val isClosedFn = Value.NativeFnV("WebSocket.isClosed", Computation.pureFn {
      case Nil => Value.BoolV(isClosed)
      case _   => throw InterpretError("ws.isClosed")
    })

    Value.InstanceV("WebSocket", Map(
      "id"          -> Value.StringV(id),
      "send"        -> sendFn,
      "sendBytes"   -> sendBytesFn,
      "close"       -> closeFn,
      "onMessage"   -> onMessageFn,
      "onClose"     -> onCloseFn,
      "ping"        -> pingFn,
      "onPong"      -> onPongFn,
      "recv"        -> recvFn,
      "isClosed"    -> isClosedFn,
      "subprotocol" -> Value.StringV(subprotocol),
      "request"     -> request,
      "user"        -> Value.OptionV(user)
    ))
