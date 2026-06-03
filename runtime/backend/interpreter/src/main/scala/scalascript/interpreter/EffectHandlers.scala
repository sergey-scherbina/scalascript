package scalascript.interpreter

import Computation.{Pure, Perform, FlatMap}
import scala.collection.immutable.{Map => IMap}

/** Free-Monad effect handlers: Logger / Random / Clock / Env / Http /
 *  Retry / Cache / State.  Each `*Run` walks the computation tree,
 *  intercepts Perform nodes for its effect tag, and leaves all other
 *  Perform nodes propagating outward.
 */
private[interpreter] object EffectHandlers:

  // ── Logger ──────────────────────────────────────────────────────────

  def loggerRun(
    initial: Computation,
    format:  String,
    sink:    java.io.PrintStream
  ): Computation =
    def write(level: String, msg: String): Unit = format match
      case "json" =>
        sink.println(s"""{"level":"$level","msg":${loggerJsonStr(msg)}}""")
      case _ =>
        sink.println(s"[${level.toUpperCase}] $msg")
    def dispatch(op: String, args: List[Value], resume: Value => Computation): Computation =
      args match
        case List(v) => write(op, Value.show(v)); resume(Value.UnitV)
        case _       => throw InterpretError(s"Logger.$op(msg)")
    var current: Computation = initial
    while true do
      current match
        case Pure(_) => return current
        case Perform("Logger", op, args) =>
          current = dispatch(op, args, v => Pure(v))
        case Perform(_, _, _) => return current
        case FlatMap(sub, f) => sub match
          case Pure(v)                      => current = f(v)
          case FlatMap(s2, g)               => current = FlatMap(s2, x => FlatMap(g(x), f))
          case Perform("Logger", op, args)  =>
            current = dispatch(op, args, v => loggerRun(f(v), format, sink))
          case Perform(_, _, _)             =>
            return FlatMap(sub, v => loggerRun(f(v), format, sink))
    throw InterpretError("unreachable")

  // Returns (bodyResult, List((level, msg), …)) as a TupleV pair.
  def loggerToListRun(initial: Computation): Computation =
    val log = scala.collection.mutable.ListBuffer.empty[(String, String)]
    def dispatch(op: String, args: List[Value], resume: Value => Computation): Computation =
      args match
        case List(v) => log += (op -> Value.show(v)); resume(Value.UnitV)
        case _       => throw InterpretError(s"Logger.$op(msg)")
    def run(current0: Computation): Computation =
      var current = current0
      while true do
        current match
          case Pure(_)                     => return current
          case Perform("Logger", op, args) =>
            current = dispatch(op, args, v => Pure(v))
          case Perform(_, _, _)            => return current
          case FlatMap(sub, f) => sub match
            case Pure(v)                     => current = f(v)
            case FlatMap(s2, g)              => current = FlatMap(s2, x => FlatMap(g(x), f))
            case Perform("Logger", op, args) =>
              current = dispatch(op, args, v => run(f(v)))
            case Perform(_, _, _)            =>
              return FlatMap(sub, v => run(f(v)))
      throw InterpretError("unreachable")
    run(initial).flatMap { v =>
      val entries = Value.ListV(log.toList.map { (lv, msg) =>
        Value.TupleV(Value.StringV(lv) :: Value.StringV(msg) :: Nil)
      })
      Pure(Value.TupleV(v :: entries :: Nil))
    }

  def loggerJsonStr(s: String): String =
    val sb = new StringBuilder("\"")
    s.foreach {
      case '"'  => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case c    => sb.append(c)
    }
    sb.append('"').toString

  // ── Random ──────────────────────────────────────────────────────────

  def randomRun(initial: Computation, seed: Option[Long]): Computation =
    val rng = seed.fold(
      new java.util.Random(): java.util.Random
    )(s => new java.util.Random(s))
    def dispatch(op: String, args: List[Value], resume: Value => Computation): Computation =
      op match
        case "nextInt" => args match
          case List(Value.IntV(n)) =>
            resume(Value.intV(rng.nextInt(n.toInt).toLong))
          case _ => throw InterpretError("Random.nextInt(n: Int)")
        case "nextDouble" =>
          resume(Value.doubleV(rng.nextDouble()))
        case "uuid" =>
          val bytes = new Array[Byte](16)
          rng.nextBytes(bytes)
          bytes(6) = ((bytes(6) & 0x0f) | 0x40).toByte
          bytes(8) = ((bytes(8) & 0x3f) | 0x80).toByte
          def hex(b: Byte) = f"${b & 0xff}%02x"
          val u = bytes.map(hex).mkString
          resume(Value.StringV(s"${u.take(8)}-${u.slice(8,12)}-${u.slice(12,16)}-${u.slice(16,20)}-${u.drop(20)}"))
        case "pick" => args match
          case List(Value.ListV(items)) if items.nonEmpty =>
            resume(items(rng.nextInt(items.size)))
          case _ => throw InterpretError("Random.pick(xs: List[A]) — list must be non-empty")
        case _ => throw InterpretError(s"Unknown Random operation: $op")
    def run(current0: Computation): Computation =
      var current = current0
      while true do
        current match
          case Pure(_) => return current
          case Perform("Random", op, args) =>
            current = dispatch(op, args, v => Pure(v))
          case Perform(_, _, _) => return current
          case FlatMap(sub, f) => sub match
            case Pure(v)                      => current = f(v)
            case FlatMap(s2, g)               => current = FlatMap(s2, x => FlatMap(g(x), f))
            case Perform("Random", op, args)  =>
              current = dispatch(op, args, v => run(f(v)))
            case Perform(_, _, _)             =>
              return FlatMap(sub, v => run(f(v)))
      throw InterpretError("unreachable")
    run(initial)

  // ── Clock ───────────────────────────────────────────────────────────

  def clockRun(initial: Computation, frozen: Option[Long]): Computation =
    def nowMs(): Long  = frozen.getOrElse(java.lang.System.currentTimeMillis())
    def nowIso(): String =
      val inst = java.time.Instant.ofEpochMilli(nowMs())
      java.time.format.DateTimeFormatter.ISO_INSTANT.format(inst)
    def dispatch(op: String, args: List[Value], resume: Value => Computation): Computation =
      op match
        case "now"    => resume(Value.intV(nowMs()))
        case "nowIso" => resume(Value.StringV(nowIso()))
        case "sleep"  => args match
          case List(Value.IntV(ms)) =>
            if frozen.isEmpty && ms > 0 then Thread.sleep(ms)
            resume(Value.UnitV)
          case _ => throw InterpretError("Clock.sleep(ms: Long)")
        case _ => throw InterpretError(s"Unknown Clock operation: $op")
    def run(current0: Computation): Computation =
      var current = current0
      while true do
        current match
          case Pure(_) => return current
          case Perform("Clock", op, args) =>
            current = dispatch(op, args, v => Pure(v))
          case Perform(_, _, _) => return current
          case FlatMap(sub, f) => sub match
            case Pure(v)                    => current = f(v)
            case FlatMap(s2, g)             => current = FlatMap(s2, x => FlatMap(g(x), f))
            case Perform("Clock", op, args) =>
              current = dispatch(op, args, v => run(f(v)))
            case Perform(_, _, _)           =>
              return FlatMap(sub, v => run(f(v)))
      throw InterpretError("unreachable")
    run(initial)

  // ── Env ─────────────────────────────────────────────────────────────

  def envRun(
    initial: Computation,
    overlay: Option[Map[String, String]]
  ): Computation =
    val local = scala.collection.mutable.Map.empty[String, String]
    overlay.foreach(m => local ++= m)
    def lookup(key: String): Option[String] =
      local.get(key)
        .orElse(if overlay.isEmpty then Option(java.lang.System.getenv(key)).filter(_.nonEmpty) else None)
    def dispatch(op: String, args: List[Value], resume: Value => Computation): Computation =
      op match
        case "get" => args match
          case List(Value.StringV(k)) =>
            val sv = lookup(k).orNull
            resume(if sv != null then Value.OptionV(Value.StringV(sv)) else Value.NoneV)
          case _ => throw InterpretError("Env.get(key: String)")
        case "set" => args match
          case List(Value.StringV(k), v) =>
            local(k) = Value.show(v); resume(Value.UnitV)
          case _ => throw InterpretError("Env.set(key: String, value)")
        case "required" => args match
          case List(Value.StringV(k)) =>
            lookup(k) match
              case Some(v) => resume(Value.StringV(v))
              case None    => throw InterpretError(s"Env.required: key '$k' not found in environment")
          case _ => throw InterpretError("Env.required(key: String)")
        case _ => throw InterpretError(s"Unknown Env operation: $op")
    def run(current0: Computation): Computation =
      var current = current0
      while true do
        current match
          case Pure(_) => return current
          case Perform("Env", op, args) =>
            current = dispatch(op, args, v => Pure(v))
          case Perform(_, _, _) => return current
          case FlatMap(sub, f) => sub match
            case Pure(v)                  => current = f(v)
            case FlatMap(s2, g)           => current = FlatMap(s2, x => FlatMap(g(x), f))
            case Perform("Env", op, args) =>
              current = dispatch(op, args, v => run(f(v)))
            case Perform(_, _, _)         =>
              return FlatMap(sub, v => run(f(v)))
      throw InterpretError("unreachable")
    run(initial)

  // ── Http ────────────────────────────────────────────────────────────

  def httpRun(
    initial: Computation,
    routes:  Option[Value.MapV],
    interp:  Interpreter
  ): Computation =
    def stubResponse(url: String): Value =
      routes match
        case Some(Value.MapV(m)) =>
          val key = Value.StringV(url)
          m.get(key) match
            case Some(v) =>
              Value.InstanceV("Response", new IMap.Map3(
                "status",  Value.intV(200),
                "headers", Value.EmptyMap,
                "body",    Value.StringV(Value.show(v))
              ))
            case None =>
              Value.InstanceV("Response", new IMap.Map3(
                "status",  Value.intV(404),
                "headers", Value.EmptyMap,
                "body",    Value.EmptyStr
              ))
        case _ => throw InterpretError("httpRun: stub routes must be a Map[String, String]")
    def dispatch(op: String, args: List[Value], resume: Value => Computation): Computation =
      val ctx = interp.mkHttpCtx()
      op match
        case "get" => args match
          case List(Value.StringV(url)) =>
            val resp = routes.fold(
              doHttpRequest("GET", url, "", Map.empty, ctx)
            )(_ => stubResponse(url))
            resume(resp)
          case _ => throw InterpretError("Http.get(url: String)")
        case "post" => args match
          case List(Value.StringV(url), Value.StringV(body)) =>
            val resp = routes.fold(
              doHttpRequest("POST", url, body, Map.empty, ctx)
            )(_ => stubResponse(url))
            resume(resp)
          case _ => throw InterpretError("Http.post(url: String, body: String)")
        case "request" => args match
          case List(Value.StringV(method), Value.StringV(url), hdrs, Value.StringV(body)) =>
            val hdrMap = hdrs match
              case Value.MapV(m) => m.collect {
                case (Value.StringV(k), Value.StringV(v)) => k -> v
              }.toMap
              case _ => Map.empty[String, String]
            val resp = routes.fold(
              doHttpRequest(method, url, body, hdrMap, ctx)
            )(_ => stubResponse(url))
            resume(resp)
          case _ => throw InterpretError("Http.request(method, url, headers, body)")
        case _ => throw InterpretError(s"Unknown Http operation: $op")
    def run(current0: Computation): Computation =
      var current = current0
      while true do
        current match
          case Pure(_) => return current
          case Perform("Http", op, args) =>
            current = dispatch(op, args, v => Pure(v))
          case Perform(_, _, _) => return current
          case FlatMap(sub, f) => sub match
            case Pure(v)                    => current = f(v)
            case FlatMap(s2, g)             => current = FlatMap(s2, x => FlatMap(g(x), f))
            case Perform("Http", op, args)  =>
              current = dispatch(op, args, v => run(f(v)))
            case Perform(_, _, _)           =>
              return FlatMap(sub, v => run(f(v)))
      throw InterpretError("unreachable")
    run(initial)

  // ── Retry ───────────────────────────────────────────────────────────

  def retryRun(initial: Computation, sleep: Boolean, interp: Interpreter): Computation =
    def dispatch(op: String, args: List[Value], resume: Value => Computation): Computation =
      op match
        case "attempt" => args match
          case List(Value.IntV(n), Value.IntV(delayMs), thunk) =>
            var lastErr: Throwable = null
            var result: Value = Value.UnitV
            var attempt = 0
            var succeeded = false
            while attempt <= n && !succeeded do
              try
                result = Computation.run(interp.callValue(thunk, Nil, Map.empty))
                succeeded = true
              catch case e: Throwable =>
                lastErr = e
                attempt += 1
                if attempt <= n && sleep && delayMs > 0 then Thread.sleep(delayMs)
            if succeeded then resume(result)
            else throw lastErr
          case _ => throw InterpretError("Retry.attempt(n: Int, delayMs: Long)(thunk)")
        case _ => throw InterpretError(s"Unknown Retry operation: $op")
    def run(current0: Computation): Computation =
      var current = current0
      while true do
        current match
          case Pure(_) => return current
          case Perform("Retry", op, args) =>
            current = dispatch(op, args, v => Pure(v))
          case Perform(_, _, _) => return current
          case FlatMap(sub, f) => sub match
            case Pure(v)                     => current = f(v)
            case FlatMap(s2, g)             => current = FlatMap(s2, x => FlatMap(g(x), f))
            case Perform("Retry", op, args)  =>
              current = dispatch(op, args, v => run(f(v)))
            case Perform(_, _, _)            =>
              return FlatMap(sub, v => run(f(v)))
      throw InterpretError("unreachable")
    run(initial)

  // ── Cache ───────────────────────────────────────────────────────────

  def cacheRun(initial: Computation, bypass: Boolean, interp: Interpreter): Computation =
    val priorBypass = interp._cacheBypass.get()
    interp._cacheBypass.set(bypass)
    def dispatch(op: String, args: List[Value], resume: Value => Computation): Computation =
      op match
        case "memoize" => args match
          case List(Value.StringV(key), Value.IntV(ttlSeconds), thunk) =>
            val result: Value =
              if interp._cacheBypass.get() then
                Computation.run(interp.callValue(thunk, Nil, Map.empty))
              else
                val nowMs = java.lang.System.currentTimeMillis()
                val cached = Option(interp._cacheStore.get(key))
                cached match
                  case Some((expiry, v)) if nowMs < expiry => v
                  case _ =>
                    val v = Computation.run(interp.callValue(thunk, Nil, Map.empty))
                    interp._cacheStore.put(key, (nowMs + ttlSeconds * 1000L, v))
                    v
            resume(result)
          case _ => throw InterpretError("Cache.memoize(key: String, ttlSeconds: Long)(thunk)")
        case _ => throw InterpretError(s"Unknown Cache operation: $op")
    def run(current0: Computation): Computation =
      var current = current0
      while true do
        current match
          case Pure(_) => return current
          case Perform("Cache", op, args) =>
            current = dispatch(op, args, v => Pure(v))
          case Perform(_, _, _) => return current
          case FlatMap(sub, f) => sub match
            case Pure(v)                     => current = f(v)
            case FlatMap(s2, g)              => current = FlatMap(s2, x => FlatMap(g(x), f))
            case Perform("Cache", op, args)  =>
              current = dispatch(op, args, v => run(f(v)))
            case Perform(_, _, _)            =>
              return FlatMap(sub, v => run(f(v)))
      throw InterpretError("unreachable")
    try run(initial)
    finally interp._cacheBypass.set(priorBypass)

  // ── State ───────────────────────────────────────────────────────────

  def stateRun(initial: Computation, s0: Value, interp: Interpreter): Computation =
    var state = s0
    def dispatch(op: String, args: List[Value], resume: Value => Computation): Computation =
      op match
        case "get"    =>
          resume(state)
        case "set"    => args match
          case List(s) => state = s; resume(Value.UnitV)
          case _       => throw InterpretError("State.set(s)")
        case "modify" => args match
          case List(f) =>
            val newState = Computation.run(interp.callValue1(f, state, Map.empty))
            state = newState; resume(Value.UnitV)
          case _ => throw InterpretError("State.modify(f: S => S)")
        case _ => throw InterpretError(s"Unknown State operation: $op")
    def run(current0: Computation): Computation =
      var current = current0
      while true do
        current match
          case Pure(_) => return current
          case Perform("State", op, args) =>
            current = dispatch(op, args, v => Pure(v))
          case Perform(_, _, _) => return current
          case FlatMap(sub, f) => sub match
            case Pure(v)                      => current = f(v)
            case FlatMap(s2, g)               => current = FlatMap(s2, x => FlatMap(g(x), f))
            case Perform("State", op, args)   =>
              current = dispatch(op, args, v => run(f(v)))
            case Perform(_, _, _)             =>
              return FlatMap(sub, v => run(f(v)))
      throw InterpretError("unreachable")
    run(initial).flatMap { result =>
      Pure(Value.TupleV(state :: result :: Nil))
    }

  // ── HTTP request helper (used by httpRun above) ─────────────────────
  // Duplicated from HttpIntrinsics (std/http-plugin) which we can't import
  // here without a circular dependency.  httpRun pre-dates the plugin
  // split and needs the low-level client directly.

  private[interpreter] def doHttpRequest(
      method:  String,
      rawUrl:  String,
      body:    String,
      headers: Map[String, String],
      ctx:     scalascript.backend.spi.NativeContext
  ): Value =
    import java.net.http.{HttpClient as JHttpClient, HttpRequest, HttpResponse}
    import scala.jdk.CollectionConverters.*
    val base    = ctx.httpBaseUrl
    val url     = if base.nonEmpty && !rawUrl.startsWith("http") then base + rawUrl else rawUrl
    val timeout = java.time.Duration.ofMillis(ctx.httpTimeoutMs)
    val client  = JHttpClient.newBuilder().connectTimeout(timeout).build()
    val builder = HttpRequest.newBuilder().uri(java.net.URI.create(url)).timeout(timeout)
    headers.foreach((k, v) => builder.header(k, v))
    val req = method match
      case "GET"    => builder.GET().build()
      case "POST"   => builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
      case "PUT"    => builder.PUT(HttpRequest.BodyPublishers.ofString(body)).build()
      case "DELETE" => builder.DELETE().build()
      case m        => builder.method(m, HttpRequest.BodyPublishers.ofString(body)).build()
    val maxTries = ctx.httpMaxRetries + 1
    val delayMs  = ctx.httpRetryDelayMs
    var attempt  = 0
    var lastResp: HttpResponse[String] | Null = null
    var lastErr:  Throwable | Null = null
    while attempt < maxTries do
      try { lastResp = client.send(req, HttpResponse.BodyHandlers.ofString()); lastErr = null }
      catch case e: Throwable => lastErr = e
      val shouldRetry = lastErr != null || (lastResp != null && lastResp.statusCode() >= 500)
      attempt += 1
      if shouldRetry && attempt < maxTries then Thread.sleep(delayMs)
      else attempt = maxTries
    if lastErr != null then throw lastErr
    val resp = lastResp.nn
    val hdrs: Map[Value, Value] = resp.headers().map().entrySet().iterator().asScala.flatMap { e =>
      if e.getValue.isEmpty then None
      else Some((Value.StringV(e.getKey): Value) -> (Value.StringV(e.getValue.get(0)): Value))
    }.toMap
    Value.InstanceV("Response", new IMap.Map3(
      "status",  Value.intV(resp.statusCode().toLong),
      "body",    Value.StringV(resp.body()),
      "headers", Value.MapV(hdrs)
    ))

  // ── Stream (v1.51.6) ─────────────────────────────────────────────────
  // runStream { body } — canonical algebraic-effects form.
  // Collects Stream.emit(x) calls; handles complete()/error(msg)/request(n).
  // Returns (Source[A], R): a tuple of the emitted source and the body's final value.
  def streamRun(initial: Computation, interp: Interpreter): Computation =
    interp.ensurePluginsLoaded()
    val buf        = scala.collection.mutable.ListBuffer.empty[Value]
    var terminated = false
    var errorMsg   = Option.empty[String]

    def makeSource(): Value =
      val emitted = Value.ListV(buf.toList)
      if errorMsg.isDefined then
        // Return a Source that fails on first pull by wrapping in a failed source if available.
        val failedFn = interp.globals.getOrElse("Source.failed", null)
        if failedFn != null then
          try interp.invoke(failedFn, Value.StringV(errorMsg.get) :: Nil)
          catch case _: Throwable => emitted
        else emitted
      else
        val fromFn = interp.globals.getOrElse("Source.from", null)
        if fromFn != null then
          try interp.invoke(fromFn, emitted :: Nil)
          catch case _: Throwable => emitted
        else emitted

    def finish(bodyResult: Value): Computation =
      Pure(Value.TupleV(makeSource() :: bodyResult :: Nil))

    def go(c0: Computation): Computation =
      var cur  = c0
      var done = false
      var result: Computation = Computation.PureUnit
      while !done do
        if terminated || errorMsg.isDefined then
          result = finish(Value.UnitV); done = true
        else cur match
          case Pure(v) =>
            result = finish(v); done = true
          case Perform("Stream", "emit", args) =>
            buf += args.headOption.getOrElse(Value.UnitV)
            cur = Computation.PureUnit
          case Perform("Stream", "complete", _) =>
            terminated = true; result = finish(Value.UnitV); done = true
          case Perform("Stream", "error", args) =>
            errorMsg = Some(args.headOption match
              case Some(Value.StringV(m)) => m
              case Some(v)                => v.toString
              case None                   => "Stream error")
            result = finish(Value.UnitV); done = true
          case Perform("Stream", "request", _) =>
            cur = Computation.PureUnit  // advisory no-op in v1.51.6
          case Perform(_, _, _) =>
            result = cur; done = true
          case FlatMap(sub, f) => sub match
            case Pure(v)                          => cur = f(v)
            case FlatMap(s2, g)                   => cur = FlatMap(s2, x => FlatMap(g(x), f))
            case Perform("Stream", "emit", args)  =>
              buf += args.headOption.getOrElse(Value.UnitV)
              cur = f(Value.UnitV)
            case Perform("Stream", "complete", _) =>
              terminated = true; result = finish(Value.UnitV); done = true
            case Perform("Stream", "error", args) =>
              errorMsg = Some(args.headOption match
                case Some(Value.StringV(m)) => m
                case Some(v)                => v.toString
                case None                   => "Stream error")
              result = finish(Value.UnitV); done = true
            case Perform("Stream", "request", _)  =>
              cur = f(Value.UnitV)
            case Perform(_, _, _)                 =>
              result = FlatMap(sub, v => go(f(v))); done = true
      result
    go(initial)
