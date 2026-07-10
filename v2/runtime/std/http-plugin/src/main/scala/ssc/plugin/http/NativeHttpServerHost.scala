package ssc.plugin.http

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{CountDownLatch, ExecutorService, Executors}
import scala.jdk.CollectionConverters.*
import ssc.Value
import ssc.plugin.NativePluginContext

/** Native HTTP server lifecycle seam; independent of v1 interpreter/server types. */
private[http] trait NativeHttpServerHost:
  def register(method: String, path: String, handler: Value): Unit
  def serve(port: Int, asynchronous: Boolean): Unit
  def stop(): Unit

/** JDK-only standard host used by the slim ScalaScript 2.1 runtime. */
private[http] final class JdkNativeHttpServerHost(context: NativePluginContext) extends NativeHttpServerHost:
  private final case class Route(method: String, path: String, handler: Value)

  private val routes = collection.mutable.ArrayBuffer.empty[Route]
  @volatile private var server: HttpServer | Null = null
  @volatile private var executor: ExecutorService | Null = null
  @volatile private var stopped = CountDownLatch(1)

  def register(method: String, path: String, handler: Value): Unit = synchronized {
    if server != null then throw new RuntimeException("route registration after serve is not supported")
    routes += Route(method.toUpperCase(java.util.Locale.ROOT), path, handler)
  }

  def serve(port: Int, asynchronous: Boolean): Unit =
    start(port)
    if !asynchronous then stopped.await()

  private def start(port: Int): Unit = synchronized {
    if server != null then throw new RuntimeException("native HTTP server is already running")
    stopped = CountDownLatch(1)
    val next = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    val nextExecutor = Executors.newVirtualThreadPerTaskExecutor()
    next.setExecutor(nextExecutor)
    next.createContext("/", exchange => handle(exchange))
    next.start()
    executor = nextExecutor
    server = next
  }

  def stop(): Unit = synchronized {
    val current = server
    if current != null then current.stop(0)
    val currentExecutor = executor
    if currentExecutor != null then currentExecutor.shutdownNow()
    server = null
    executor = null
    stopped.countDown()
  }

  private def handle(exchange: HttpExchange): Unit =
    try
      val method = exchange.getRequestMethod.toUpperCase(java.util.Locale.ROOT)
      val path = exchange.getRequestURI.getPath
      routes.find(route => route.method == method && route.path == path) match
        case Some(route) => write(exchange, context.invoke(route.handler, List(requestValue(exchange))))
        case None => writeRaw(exchange, 404, Map("Content-Type" -> "text/plain; charset=utf-8"), "Not Found")
    catch case error: Throwable =>
      writeRaw(exchange, 500, Map("Content-Type" -> "text/plain; charset=utf-8"),
        s"native HTTP handler failed: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}")
    finally exchange.close()

  private def requestValue(exchange: HttpExchange): Value =
    val requestHeaders = exchange.getRequestHeaders.entrySet().iterator().asScala.flatMap { entry =>
      entry.getValue.asScala.headOption.map(entry.getKey.toLowerCase(java.util.Locale.ROOT) -> _)
    }.toMap
    Value.DataV("Request", Vector(
      Value.StrV(exchange.getRequestMethod),
      Value.StrV(exchange.getRequestURI.getPath),
      valueMap(requestHeaders),
      Value.StrV(String(exchange.getRequestBody.readAllBytes(), UTF_8)),
      valueMap(Map.empty),
      valueMap(Map.empty),
      valueMap(Map.empty),
      valueMap(Map.empty),
      Value.DataV("None", Vector.empty)))

  private def write(exchange: HttpExchange, value: Value): Unit = value match
    case Value.DataV("Response", Seq(Value.IntV(status), headerValue, Value.StrV(body))) =>
      writeRaw(exchange, status.toInt, stringMap(headerValue), body)
    case Value.StrV(body) =>
      writeRaw(exchange, 200, Map("Content-Type" -> "text/plain; charset=utf-8"), body)
    case other =>
      writeRaw(exchange, 200, Map("Content-Type" -> "text/plain; charset=utf-8"), other.toString)

  private def writeRaw(exchange: HttpExchange, status: Int, headers: Map[String, String], body: String): Unit =
    headers.foreach { case (key, value) => exchange.getResponseHeaders.set(key, value) }
    val bytes = body.getBytes(UTF_8)
    exchange.sendResponseHeaders(status, bytes.length.toLong)
    exchange.getResponseBody.write(bytes)

  private def valueMap(values: Map[String, String]): Value =
    val result = collection.mutable.LinkedHashMap.empty[Value, Value]
    values.toList.sortBy(_._1).foreach { case (key, value) => result(Value.StrV(key)) = Value.StrV(value) }
    Value.ForeignV(result)

  private def stringMap(value: Value): Map[String, String] = value match
    case Value.ForeignV(map: collection.Map[?, ?]) if map.keysIterator.forall(_.isInstanceOf[Value]) =>
      map.asInstanceOf[collection.Map[Value, Value]].iterator.collect {
        case (Value.StrV(key), Value.StrV(item)) => key -> item
      }.toMap
    case _ => Map.empty
