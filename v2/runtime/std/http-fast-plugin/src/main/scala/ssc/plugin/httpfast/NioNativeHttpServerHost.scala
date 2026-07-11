package ssc.plugin.httpfast

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CountDownLatch
import ssc.Value
import ssc.plugin.NativePluginContext

/** Bridges the value-agnostic [[FastHttpServer]] engine to the ssc `Value` world: routes a
  * parsed request through a [[Router]] of ssc handler closures, builds the 9-field `Request`
  * DataV, invokes the handler on the VM, and serializes its `Response` (or bare string). */
private[httpfast] final class NioNativeHttpServerHost(context: NativePluginContext):

  private val router = new Router[Value]
  @volatile private var server: FastHttpServer | Null = null
  @volatile private var stopped = new CountDownLatch(1)
  private var idleTimeoutMs: Int = 30_000
  private var maxBody: Long      = 16L * 1024 * 1024

  def register(method: String, path: String, handler: Value): Unit = synchronized {
    if server != null then throw new RuntimeException("route registration after serve is not supported")
    router.add(method, path, handler)
  }

  def setMaxBodyBytes(n: Long): Unit  = synchronized { maxBody = n.max(0L) }
  def setIdleTimeoutMs(n: Int): Unit  = synchronized { idleTimeoutMs = n.max(0) }

  def serve(port: Int, asynchronous: Boolean): Unit =
    start(port)
    if !asynchronous then stopped.await()

  private def start(port: Int): Unit = synchronized {
    if server != null then throw new RuntimeException("native HTTP server is already running")
    router.freeze()
    stopped = new CountDownLatch(1)
    val limits = HttpProtocol.Limits(maxBodyBytes = maxBody)
    val srv = new FastHttpServer(dispatch, limits = limits, idleTimeoutMs = idleTimeoutMs)
    srv.start(port)
    server = srv
  }

  def stop(): Unit = synchronized {
    val current = server
    if current != null then current.nn.stop()
    server = null
    stopped.countDown()
  }

  /** Bound port (or -1 if not serving) — used by tests / `serveAsync` callers. */
  def boundPort: Int = { val s = server; if s == null then -1 else s.nn.port }

  private def dispatch(req: RawRequest): RawResponse =
    router.find(req.method, req.path) match
      case Some(m) =>
        val result = context.invoke(m.handler, List(requestValue(req, m.params)))
        toResponse(result)
      case None =>
        if router.hasPath(req.path) then
          val allow = router.allowedMethods(req.path).toList.sorted.mkString(", ")
          RawResponse(405, Map("Allow" -> allow, "Content-Type" -> "text/plain; charset=utf-8"),
            "Method Not Allowed".getBytes(UTF_8))
        else
          RawResponse(404, Map("Content-Type" -> "text/plain; charset=utf-8"),
            "Not Found".getBytes(UTF_8))

  private def requestValue(req: RawRequest, pathParams: Map[String, String]): Value =
    // `form` carries query + path params (path params win) to stay 9-field compatible.
    val form = req.query ++ pathParams
    Value.DataV("Request", Vector(
      Value.StrV(req.method),
      Value.StrV(req.path),
      valueMap(req.headers),
      Value.StrV(new String(req.body, UTF_8)),
      valueMap(form),
      valueMap(Map.empty),
      valueMap(cookies(req.headers)),
      valueMap(Map.empty),
      Value.DataV("None", Vector.empty)))

  private def toResponse(value: Value): RawResponse = value match
    case Value.DataV("Response", Seq(Value.IntV(status), headerValue, Value.StrV(body))) =>
      RawResponse(status.toInt, stringMap(headerValue), body.getBytes(UTF_8))
    case Value.StrV(body) =>
      RawResponse(200, Map("Content-Type" -> "text/plain; charset=utf-8"), body.getBytes(UTF_8))
    case other =>
      RawResponse(200, Map("Content-Type" -> "text/plain; charset=utf-8"), other.toString.getBytes(UTF_8))

  private def cookies(headers: Map[String, String]): Map[String, String] =
    headers.get("cookie") match
      case None => Map.empty
      case Some(raw) =>
        raw.split(";").iterator.map(_.trim).filter(_.nonEmpty).flatMap { pair =>
          val eq = pair.indexOf('=')
          if eq < 0 then None else Some(pair.substring(0, eq).trim -> pair.substring(eq + 1).trim)
        }.toMap

  private def valueMap(values: Map[String, String]): Value =
    val result = Value.MapV.empty
    values.toList.sortBy(_._1).foreach { case (k, v) => result.entries(Value.StrV(k)) = Value.StrV(v) }
    result

  private def stringMap(value: Value): Map[String, String] = value match
    case Value.MapV(map) => map.iterator.collect {
      case (Value.StrV(k), Value.StrV(v)) => k -> v
    }.toMap
    case Value.ForeignV(map: collection.Map[?, ?]) if map.keysIterator.forall(_.isInstanceOf[Value]) =>
      map.asInstanceOf[collection.Map[Value, Value]].iterator.collect {
        case (Value.StrV(k), Value.StrV(v)) => k -> v
      }.toMap
    case _ => Map.empty
