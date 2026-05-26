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
    def run(): Unit = WebServer.start(port, dir, log, certPath, keyPath, wsRoutes = interp.wsRoutes)
    if async then
      Thread.ofVirtual().start { () =>
        try run()
        catch case _: Throwable => ()
      }
    else run()

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
  def recvText(): Option[String] = delegate.recvText()
  def abort(): Unit = delegate.abort()
  def wsObj: Value = delegate.wsObj
  def awaitClose(): Unit = delegate.awaitClose()
