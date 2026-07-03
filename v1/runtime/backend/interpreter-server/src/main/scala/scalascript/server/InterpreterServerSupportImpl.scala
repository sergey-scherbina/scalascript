package scalascript.server

import java.io.PrintStream

import scalascript.interpreter.*

final class InterpreterServerSupportImpl extends InterpreterServerSupport:
  def startServer(
      interp: Interpreter,
      port: Int,
      dir: String,
      log: PrintStream,
      certPath: String,
      keyPath: String,
      async: Boolean
  ): Unit =
    if async then
      // Block until the socket is actually bound (or binding fails) before
      // returning, so a caller that issues an httpGet right after serveAsync(...)
      // no longer races the bind (ConnectException / ClosedChannelException).
      val ready = java.util.concurrent.CountDownLatch(1)
      val error = java.util.concurrent.atomic.AtomicReference[Throwable](null)
      Thread.ofVirtual().start { () =>
        try WebServer.start(port, dir, log, certPath, keyPath,
              wsRoutes = interp.wsRoutes, routeRegistry = interp.routeRegistry,
              onBound = () => ready.countDown())
        catch case t: Throwable => error.set(t); ready.countDown()
      }
      if !ready.await(15, java.util.concurrent.TimeUnit.SECONDS) then
        throw new RuntimeException(s"serveAsync($port): server did not bind within 15s")
      val e = error.get()
      if e != null then
        throw new RuntimeException(s"serveAsync($port) failed to bind: ${e.getMessage}", e)
    else
      WebServer.start(port, dir, log, certPath, keyPath,
        wsRoutes = interp.wsRoutes, routeRegistry = interp.routeRegistry)

  def stopServer(): Unit = WebServer.stop()

  def setMaxWsConnections(n: Int): Unit =
    _root_.scalascript.server.jvm._wsMaxActive.set(n)

  def wsConnectSync(
      interp: Interpreter,
      url: String,
      headers: Map[String, String],
      protocols: List[String],
      handler: Value,
      log: PrintStream
  ): Unit =
    val sess = WsClientSession(url, headers, protocols, interp, log)
    sess.connect()
    interp.invoke(handler, List(sess.wsObj))
    sess.awaitClose()

  def openWsClient(
      interp: Interpreter,
      url: String,
      headers: Map[String, String],
      protocols: List[String],
      log: PrintStream
  ): InterpreterWsClientSession =
    WsClientSessionAdapter(WsClientSession(url, headers, protocols, interp, log))

  def configureCors(origins: List[String], methods: List[String], allowedHeaders: List[String]): Unit =
    WebServer.configureCors(origins, methods, allowedHeaders)
  def enableGzip(): Unit = WebServer.enableGzip()
  def setMaxBodySize(bytes: Long): Unit = WebServer.setMaxBodySize(bytes)
  def setSpoolThreshold(bytes: Long): Unit = WebServer.setSpoolThreshold(bytes)
  def setUploadDir(path: String): Unit = WebServer.setUploadDir(path)

private final class WsClientSessionAdapter(delegate: WsClientSession) extends InterpreterWsClientSession:
  def connect(): Unit = delegate.connect()
  def sendText(text: String): Unit = delegate.sendText(text)
  override def sendBinary(bytes: Array[Byte]): Unit = delegate.sendBinary(bytes)
  def recvText(): Option[String] = delegate.recvText()
  override def negotiatedProtocol: String = delegate.subprotocol
  def abort(): Unit = delegate.abort()
  def wsObj: Value = delegate.wsObj
  def awaitClose(): Unit = delegate.awaitClose()
