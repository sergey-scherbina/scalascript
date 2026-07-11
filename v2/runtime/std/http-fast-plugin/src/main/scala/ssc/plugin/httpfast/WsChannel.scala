package ssc.plugin.httpfast

import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** Uniform view of a WebSocket the ssc `ws` value drives, whether it is a server-accepted
  * connection ([[WsConnection]]) or an outbound client connection (`java.net.http.WebSocket`
  * via `wsConnect`). Lets the tagged-method bridge treat both identically. */
private[httpfast] trait WsChannel:
  def sendText(s: String): Unit
  def sendBytes(b: Array[Byte]): Unit
  def ping(): Unit
  def close(code: Int, reason: String): Unit
  def isClosed: Boolean
  def request: Option[RawRequest]
  def subprotocol: Option[String]
  def user: Option[String]
  def setOnText(f: String => Unit): Unit
  def setOnBinary(f: Array[Byte] => Unit): Unit
  def setOnClose(f: (Int, String) => Unit): Unit
  def setOnPong(f: Array[Byte] => Unit): Unit
  /** Registry cleanup, run once at final close (independent of the user `onClose`). */
  def setOnTeardown(f: () => Unit): Unit

/** Server-side channel backed by an accepted [[WsConnection]] (the engine's read loop). */
private[httpfast] final class ServerWsChannel(val conn: WsConnection) extends WsChannel:
  def sendText(s: String): Unit       = conn.sendText(s)
  def sendBytes(b: Array[Byte]): Unit = conn.sendBytes(b)
  def ping(): Unit                    = conn.ping()
  def close(code: Int, reason: String): Unit = conn.close(code, reason)
  def isClosed: Boolean               = conn.isClosed
  def request: Option[RawRequest]     = Some(conn.request)
  def subprotocol: Option[String]     = conn.subprotocol
  def user: Option[String]            = conn.user
  def setOnText(f: String => Unit): Unit      = conn.onText = f
  def setOnBinary(f: Array[Byte] => Unit): Unit = conn.onBinary = f
  def setOnClose(f: (Int, String) => Unit): Unit = conn.onClose = f
  def setOnPong(f: Array[Byte] => Unit): Unit  = conn.onPong = f
  def setOnTeardown(f: () => Unit): Unit       = conn.onTeardown = f

/** Client-side channel wrapping `java.net.http.WebSocket`. Message reassembly + demand are
  * handled by the JDK listener, which forwards to the callbacks the ssc handler sets. */
private[httpfast] final class ClientWsChannel extends WsChannel:
  @volatile private var socket: WebSocket | Null = null
  private val closedFlag = new AtomicBoolean(false)
  @volatile private var onText_   : String => Unit       = _ => ()
  @volatile private var onBinary_ : Array[Byte] => Unit  = _ => ()
  @volatile private var onClose_  : (Int, String) => Unit = (_, _) => ()
  @volatile private var onPong_   : Array[Byte] => Unit  = _ => ()
  @volatile private var onTeardown_ : () => Unit         = () => ()

  def attach(ws: WebSocket): Unit = socket = ws

  /** The JDK listener that drives this channel's callbacks (assembles fragments). */
  val listener: WebSocket.Listener = new WebSocket.Listener:
    private val textBuf = new StringBuilder
    private val binBuf  = new java.io.ByteArrayOutputStream()
    override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletableFuture[?] =
      textBuf.append(data)
      if last then { val s = textBuf.toString; textBuf.setLength(0); safe(onText_(s)) }
      ws.request(1); null
    override def onBinary(ws: WebSocket, data: ByteBuffer, last: Boolean): CompletableFuture[?] =
      val chunk = new Array[Byte](data.remaining()); data.get(chunk); binBuf.write(chunk)
      if last then { val b = binBuf.toByteArray; binBuf.reset(); safe(onBinary_(b)) }
      ws.request(1); null
    override def onPong(ws: WebSocket, message: ByteBuffer): CompletableFuture[?] =
      val b = new Array[Byte](message.remaining()); message.get(b); safe(onPong_(b))
      ws.request(1); null
    override def onClose(ws: WebSocket, status: Int, reason: String): CompletableFuture[?] =
      if closedFlag.compareAndSet(false, true) then { safe(onClose_(status, reason)); safe(onTeardown_()) }
      null
    override def onError(ws: WebSocket, error: Throwable): Unit =
      if closedFlag.compareAndSet(false, true) then
        safe(onClose_(1006, String.valueOf(error.getMessage))); safe(onTeardown_())

  def sendText(s: String): Unit       = { val w = socket; if w != null then w.nn.sendText(s, true) }
  def sendBytes(b: Array[Byte]): Unit = { val w = socket; if w != null then w.nn.sendBinary(ByteBuffer.wrap(b), true) }
  def ping(): Unit                    = { val w = socket; if w != null then w.nn.sendPing(ByteBuffer.allocate(0)) }
  def close(code: Int, reason: String): Unit =
    val w = socket
    if w != null && closedFlag.compareAndSet(false, true) then w.nn.sendClose(code, reason)
  def isClosed: Boolean               = closedFlag.get() || { val w = socket; w != null && w.nn.isInputClosed }
  def request: Option[RawRequest]     = None
  def subprotocol: Option[String]     = { val w = socket; if w == null then None else Option(w.nn.getSubprotocol).filter(_.nonEmpty) }
  def user: Option[String]            = None
  def setOnText(f: String => Unit): Unit       = onText_ = f
  def setOnBinary(f: Array[Byte] => Unit): Unit = onBinary_ = f
  def setOnClose(f: (Int, String) => Unit): Unit = onClose_ = f
  def setOnPong(f: Array[Byte] => Unit): Unit   = onPong_ = f
  def setOnTeardown(f: () => Unit): Unit        = onTeardown_ = f

  private def safe(body: => Unit): Unit = try body catch case _: Throwable => ()
