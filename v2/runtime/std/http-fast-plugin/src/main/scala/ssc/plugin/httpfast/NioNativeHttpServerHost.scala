package ssc.plugin.httpfast

import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{ConcurrentHashMap, CopyOnWriteArrayList, CountDownLatch}
import java.util.concurrent.atomic.AtomicLong
import ssc.Value
import ssc.plugin.NativePluginContext

/** Bridges the value-agnostic [[FastHttpServer]] engine to the ssc `Value` world: routes a
  * parsed request through a [[Router]] of ssc handler closures, builds the 9-field `Request`
  * DataV, invokes the handler on the VM, and serializes its `Response` (or bare string).
  * Also implements the engine's [[FastHttpServer.WebSocketDispatcher]] for `onWebSocket`. */
private[httpfast] final class NioNativeHttpServerHost(context: NativePluginContext)
    extends FastHttpServer.WebSocketDispatcher:

  private val router = new Router[Value]
  @volatile private var server: FastHttpServer | Null = null
  @volatile private var stopped = new CountDownLatch(1)
  private var idleTimeoutMs: Int = 30_000
  private var maxBody: Long      = 16L * 1024 * 1024

  // --- WebSocket state ---
  private final case class WsRoute(handler: Value, auth: Option[Value], protocols: List[String])
  private val wsRouter    = new Router[WsRoute]
  private val wsChannels  = new ConcurrentHashMap[Long, WsChannel]()
  private val wsIds       = new AtomicLong(0)
  private val rooms       = new ConcurrentHashMap[Long, CopyOnWriteArrayList[java.lang.Long]]()
  private val roomIds     = new AtomicLong(0)

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
    wsRouter.freeze()
    stopped = new CountDownLatch(1)
    val limits = HttpProtocol.Limits(maxBodyBytes = maxBody)
    val srv = new FastHttpServer(dispatch, limits = limits, idleTimeoutMs = idleTimeoutMs,
      webSocket = if wsRouter.isEmpty then None else Some(this))
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

  // ---- WebSocket: routing + the engine dispatcher ----

  def registerWs(path: String, handler: Value, auth: Option[Value], protocols: List[String]): Unit =
    synchronized {
      if server != null then throw new RuntimeException("onWebSocket registration after serve is not supported")
      wsRouter.add("GET", path, WsRoute(handler, auth, protocols))
    }

  def hasRoute(path: String): Boolean = wsRouter.find("GET", path).isDefined

  override def selectSubprotocol(path: String, offered: List[String]): Option[String] =
    wsRouter.find("GET", path).flatMap { m =>
      m.handler.protocols.find(offered.contains)
    }

  def open(request: RawRequest, sock: java.net.Socket, reader: HttpReader,
           out: java.io.OutputStream, subprotocol: Option[String]): WsConnection =
    val route = wsRouter.find("GET", request.path)
      .getOrElse(throw new RuntimeException(s"no websocket route for ${request.path}"))
    val id      = wsIds.incrementAndGet()
    val conn    = new WsConnection(id, sock, reader, out, request, subprotocol)
    val channel = new ServerWsChannel(conn)
    wsChannels.put(id, channel)
    conn.onTeardown = () => { wsChannels.remove(id); removeFromAllRooms(id) }

    // Optional auth: authFn(request) — reject (close 1008) on None/false/Unit.
    val authorized = route.handler.auth match
      case None => true
      case Some(authFn) =>
        val verdict = context.invoke(authFn, List(requestValue(request, route.params)))
        verdict match
          case Value.DataV("None", _) | Value.UnitV | Value.BoolV(false) => false
          case Value.DataV("Some", Seq(u)) => conn.user = Some(valueToString(u)); true
          case Value.StrV(u)               => conn.user = Some(u); true
          case _                           => true
    if !authorized then
      conn.close(1008, "unauthorized")
    else
      context.invoke(route.handler.handler, List(wsValue(id))) // wires ws.onMessage/onClose/…
    conn

  // ---- WebSocket: operations invoked by the plugin's tagged methods ----

  def wsValue(id: Long): Value = Value.DataV("WebSocket", Vector(Value.IntV(id)))

  private def wsIdOf(recv: Value): Long = recv match
    case Value.DataV("WebSocket", Seq(Value.IntV(id))) => id
    case _ => throw new RuntimeException("WebSocket handle expected")

  private def channelOf(recv: Value): WsChannel =
    val ch = wsChannels.get(wsIdOf(recv))
    if ch == null then throw new RuntimeException("WebSocket is closed or unknown") else ch

  def wsSend(recv: Value, msg: String): Unit       = channelOf(recv).sendText(msg)
  def wsSendBytes(recv: Value, b: Array[Byte]): Unit = channelOf(recv).sendBytes(b)
  def wsPing(recv: Value): Unit                    = channelOf(recv).ping()
  def wsClose(recv: Value, code: Int, reason: String): Unit = channelOf(recv).close(code, reason)
  def wsIsClosed(recv: Value): Boolean = { val ch = wsChannels.get(wsIdOf(recv)); ch == null || ch.isClosed }
  def wsOnMessage(recv: Value, cb: Value): Unit =
    channelOf(recv).setOnText(s => context.invoke(cb, List(Value.StrV(s))))
  def wsOnClose(recv: Value, cb: Value): Unit =
    channelOf(recv).setOnClose((code, _) => context.invoke(cb, List(Value.IntV(code.toLong))))
  def wsOnPong(recv: Value, cb: Value): Unit =
    channelOf(recv).setOnPong(b => context.invoke(cb, List(Value.BytesV(b.toVector))))
  def wsRequest(recv: Value): Value =
    channelOf(recv).request.map(r => requestValue(r, Map.empty)).getOrElse(Value.DataV("None", Vector.empty))
  def wsSubprotocol(recv: Value): Value = Value.StrV(channelOf(recv).subprotocol.getOrElse(""))
  def wsUser(recv: Value): Value =
    channelOf(recv).user match
      case Some(u) => Value.DataV("Some", Vector(Value.StrV(u)))
      case None    => Value.DataV("None", Vector.empty)

  /** Client: `wsConnect(url)(handler)`. Runs the handler to wire callbacks, then connects. */
  def connectClient(url: String, headers: Map[String, String], protocols: List[String], handler: Value): Value =
    val id      = wsIds.incrementAndGet()
    val channel = new ClientWsChannel()
    wsChannels.put(id, channel)
    channel.setOnTeardown(() => { wsChannels.remove(id); removeFromAllRooms(id) })
    context.invoke(handler, List(wsValue(id))) // wire callbacks before connecting
    val builder = HttpClient.newHttpClient().newWebSocketBuilder()
    headers.foreach((k, v) => builder.header(k, v))
    protocols match
      case first :: rest => builder.subprotocols(first, rest*)
      case Nil           => ()
    val ws = builder.buildAsync(URI.create(url), channel.listener).join()
    channel.attach(ws)
    wsValue(id)

  // ---- WsRoom: broadcast helper ----

  def roomCreate(): Value =
    val id = roomIds.incrementAndGet()
    rooms.put(id, new CopyOnWriteArrayList[java.lang.Long]())
    Value.DataV("WsRoom", Vector(Value.IntV(id)))

  private def roomOf(recv: Value): CopyOnWriteArrayList[java.lang.Long] = recv match
    case Value.DataV("WsRoom", Seq(Value.IntV(id))) =>
      val r = rooms.get(id); if r == null then throw new RuntimeException("WsRoom unknown") else r
    case _ => throw new RuntimeException("WsRoom handle expected")

  def roomAdd(recv: Value, ws: Value): Unit =
    val list = roomOf(recv); val id = java.lang.Long.valueOf(wsIdOf(ws))
    if !list.contains(id) then list.add(id)
  def roomRemove(recv: Value, ws: Value): Unit = roomOf(recv).remove(java.lang.Long.valueOf(wsIdOf(ws)))
  def roomSize(recv: Value): Int = roomOf(recv).size
  def roomBroadcast(recv: Value, msg: String): Unit =
    roomOf(recv).forEach { id =>
      val ch = wsChannels.get(id.longValue)
      if ch != null && !ch.isClosed then try ch.sendText(msg) catch case _: Throwable => ()
    }

  private def removeFromAllRooms(id: Long): Unit =
    val boxed = java.lang.Long.valueOf(id)
    rooms.values.forEach(_.remove(boxed))

  private def valueToString(v: Value): String = v match
    case Value.StrV(s)  => s
    case Value.IntV(n)  => n.toString
    case other          => other.toString

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
