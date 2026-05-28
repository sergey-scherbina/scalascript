package scalascript.interpreter

import java.io.PrintStream
import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*

/** Optional bridge from the interpreter core into the interpreter-owned
 *  server runtime.  The concrete implementation lives in
 *  `backendInterpreterServer` so HTTP/WS sockets no longer have to be
 *  compiled as part of the interpreter core module.
 */
trait InterpreterServerSupport:
  def startServer(
      interp: Interpreter,
      port: Int,
      dir: String,
      log: PrintStream,
      certPath: String,
      keyPath: String,
      async: Boolean
  ): Unit

  def stopServer(): Unit

  def setMaxWsConnections(n: Int): Unit

  def wsConnectSync(
      interp: Interpreter,
      url: String,
      headers: Map[String, String],
      protocols: List[String],
      handler: Value,
      log: PrintStream
  ): Unit

  def openWsClient(
      interp: Interpreter,
      url: String,
      headers: Map[String, String],
      protocols: List[String],
      log: PrintStream
  ): InterpreterWsClientSession

  def configureCors(origins: List[String], methods: List[String], allowedHeaders: List[String]): Unit
  def enableGzip(): Unit
  def setMaxBodySize(bytes: Long): Unit
  def setSpoolThreshold(bytes: Long): Unit
  def setUploadDir(path: String): Unit

trait InterpreterWsClientSession:
  def connect(): Unit
  def sendText(text: String): Unit
  def sendBinary(bytes: Array[Byte]): Unit = ()
  def recvText(): Option[String]
  def negotiatedProtocol: String = ""
  def abort(): Unit
  def wsObj: Value
  def awaitClose(): Unit

object InterpreterServerSupport:
  lazy val current: InterpreterServerSupport =
    ServiceLoader.load(classOf[InterpreterServerSupport]).iterator().asScala.toList match
      case first :: _ => first
      case Nil        => MissingInterpreterServerSupport

private object MissingInterpreterServerSupport extends InterpreterServerSupport:
  private def missing(): Nothing =
    throw InterpretError("Interpreter server runtime is not on the classpath; add scalascript-backend-interpreter-server")

  def startServer(
      interp: Interpreter,
      port: Int,
      dir: String,
      log: PrintStream,
      certPath: String,
      keyPath: String,
      async: Boolean
  ): Unit = missing()

  def stopServer(): Unit = missing()
  def setMaxWsConnections(n: Int): Unit =
    _root_.scalascript.server.jvm._wsMaxActive.set(n)

  def wsConnectSync(
      interp: Interpreter,
      url: String,
      headers: Map[String, String],
      protocols: List[String],
      handler: Value,
      log: PrintStream
  ): Unit = missing()

  def openWsClient(
      interp: Interpreter,
      url: String,
      headers: Map[String, String],
      protocols: List[String],
      log: PrintStream
  ): InterpreterWsClientSession = missing()

  def configureCors(origins: List[String], methods: List[String], allowedHeaders: List[String]): Unit = missing()
  def enableGzip(): Unit = missing()
  def setMaxBodySize(bytes: Long): Unit = missing()
  def setSpoolThreshold(bytes: Long): Unit = missing()
  def setUploadDir(path: String): Unit = missing()
