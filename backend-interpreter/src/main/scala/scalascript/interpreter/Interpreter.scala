package scalascript.interpreter

import scalascript.ast.*
import scala.collection.mutable
import scala.meta.*
import Computation.{Pure, Perform, FlatMap}

/** Tree-walking interpreter for ScalaScript documents.
 *
 *  Execution model:
 *  - Sections are processed in document order.
 *  - Each scala/ssc code block is executed eagerly (defs bind, exprs run).
 *  - After all sections, `main()` is auto-called if defined and not already invoked.
 *
 *  Algebraic effects use a **Free Monad** representation: `eval` returns a
 *  `Computation` (Pure | Perform). Effect ops produce `Perform` nodes; handlers
 *  walk the tree and dispatch them. `resume(v)` invokes the captured Scala
 *  continuation directly — no replay; side effects in body run exactly once;
 *  multi-shot works by calling the continuation multiple times.
 */
class Interpreter(
    val out:  java.io.PrintStream = System.out,
    baseDir:  Option[os.Path]     = None,
    /** When true, `serve(port)` is a no-op: routes still register, but
     *  the HTTP server doesn't bind a port or block on `Thread.join`.
     *  Used by `ssc render` for static-site generation — the route
     *  table is filled in, then handlers are invoked off-band with
     *  synthetic requests. */
    headless: Boolean              = false):
  private val globals      = mutable.Map.empty[String, Value]
  private val extensions   = mutable.Map.empty[(String, String), Value.FunV]
  // Methods declared inside a `class` / `case class` body, keyed by type name.
  // Stored separately from instance fields so `show` and pattern matching see
  // only data fields.
  private val typeMethods  = mutable.Map.empty[String, Map[String, Value.FunV]]
  // Field declaration order per type — needed for positional `.copy(...)`
  // since `InstanceV.fields` is an unordered Map for instances with more
  // than four fields.
  private val typeFieldOrder = mutable.Map.empty[String, List[String]]
  private var mainCalled   = false
  private var placeholderIdx = 0
  // Tracks the last known source position for error messages (0-based line, 0-based column).
  private var currentSpan: Option[(Int, Int)] = None
  // Source of the code block currently being executed — used to print the
  // offending line under the error message with a caret.
  private var currentSource: String = ""
  // When the parser falls back to wrapping the block in `{ ... }` to accept
  // top-level expressions, every scalameta position is shifted down by one
  // line. `lineOffset` compensates so error messages report the user's line.
  private var lineOffset: Int = 0

  // ─── Reactive signals (fine-grained reactivity) ──────────────────────
  //
  // Signals are mutable cells with subscriber tracking.  Reading a signal
  // inside an active `effect` block registers a mutual subscription so
  // the effect re-runs when the signal changes.  `computed` is an effect
  // whose body's return value feeds another signal — derived state.
  //
  // We store backing state in a process-local side-table keyed by a
  // monotone id; the user-visible value is an `InstanceV("Signal", {id})`
  // / `InstanceV("Effect", {id})`.  Cross-backend semantics line up
  // because all three backends use the same registry-based push model.

  private class SignalState(var value: Value, val subs: mutable.HashSet[Long])
  private class EffectState(val thunk: Value, val deps: mutable.HashSet[Long])

  private val signals = mutable.HashMap.empty[Long, SignalState]
  private val effects = mutable.HashMap.empty[Long, EffectState]
  private var reactiveCounter: Long = 0L
  // Stack of currently-tracking effect ids.  An effect-thunk that calls
  // another effect (rare but legal) pushes its own id while running.
  private val effectStack = mutable.Stack.empty[Long]
  // Pending effect reruns queued by `signalSet` while we're inside an
  // active flush.  A LinkedHashSet so each effect runs at most once per
  // synchronous transaction (deduplicates the diamond — derived signal
  // and final consumer both react to the same root change) and reruns
  // happen in registration order for determinism.
  private val pendingEffects = mutable.LinkedHashSet.empty[Long]

  // v1.5 Tier 5 #20 — validation collector stack.  Each `validate { … }`
  // block pushes a fresh ordered map; the `require*` natives check the
  // head of the stack: when present they record the error and return a
  // type-appropriate default so the body keeps running and accumulates
  // every problem in one pass.  When empty (handler called require*
  // outside a validate block) the call throws as before.
  private val validationStack: mutable.Stack[mutable.LinkedHashMap[String, String]] =
    mutable.Stack.empty
  private var reactiveFlushing = false

  /** Per-FunV cache of the TCO classification — three full body walks
   *  (`tailCallTargets`, `callsInTailPos`, `hasNonTailSelfCall`) used to
   *  cost a tail-recursive call up-front on every invocation. The body is
   *  immutable per FunV, so the result is too. Keyed by FunV identity. */
  private case class TcoInfo(
    tailTargets:   Set[String],
    isSelfTailRec: Boolean,
    noNonTailSelf: Boolean
  )
  private val tcoCache: java.util.IdentityHashMap[Value.FunV, TcoInfo] =
    java.util.IdentityHashMap()

  /** Intern table from `Lit` AST nodes to the `Computation` they evaluate
   *  to. The parsed AST is reused across all evaluations, so for hot
   *  loop literals (`0`, `1`, `2`, …) this saves a fresh `Pure(IntV(...))`
   *  allocation on every visit. */
  private val litCache: java.util.IdentityHashMap[Lit, Computation] =
    java.util.IdentityHashMap()

  // ── v1.6 Actors — Phase 1 + Phase 2 runtime state ───────────────
  // Each `runActors { ... }` installs a fresh ActorRuntime for its
  // duration so nested invocations don't share state.
  private class ActorRuntime:
    val mailboxes = mutable.LongMap.empty[mutable.Queue[Value]]
    // (cases, env, continuation, optional wall-clock deadline, wrap-in-some?)
    // `wrapSome` is true for timeout-receive: matched body's value is wrapped
    // in Some(...) before being fed to the continuation so a single `receive`
    // expression yields Option[A].
    val blocked = mutable.LongMap.empty[
      (List[Case], Env, Value => Computation, Option[Long], Boolean)
    ]
    // Computation to run next when this actor is dispatched
    val pending = mutable.LongMap.empty[Computation]
    val ready   = mutable.Queue.empty[Long]
    var nextId  = 0L
    var currentId = -1L
    // Phase 2 — supervision
    // links(id) = set of actor IDs bidirectionally linked to `id`
    val links      = mutable.LongMap.empty[mutable.Set[Long]]
    // monitors(watchedId) = Map(monitorRef → observerId)
    val monitors   = mutable.LongMap.empty[mutable.Map[Long, Long]]
    // trapExit(id) = true  →  Exit signals arrive as messages, not crashes
    val trapExit   = mutable.LongMap.empty[Boolean]
    var nextMonRef = 0L
  private var actorRt: ActorRuntime = null
  // Base URL for httpClient {} scopes — thread-local so nested calls restore correctly.
  private val _httpBaseUrl = ThreadLocal.withInitial[String](() => "")
  // Receive-spec boxing: we can't squeeze AST cases into `Value`, so
  // `receive { case … }` stashes (cases, env) in a side map and the
  // Perform's args carry just the opaque integer token.
  private val receiveSpecs    = mutable.LongMap.empty[(List[Case], Env)]
  private var receiveSpecNext = 0L

  /** Cache of `closure.updated(name, f)` per FunV — the self-ref binding
   *  is identical on every invocation of the same closure, so we save
   *  one HashMap.updated allocation per call. */
  private val closureWithSelfCache: java.util.IdentityHashMap[Value.FunV, Env] =
    java.util.IdentityHashMap()

  private def closureWithSelfFor(f: Value.FunV): Env =
    if f.name.isEmpty then f.closure
    else
      val cached = closureWithSelfCache.get(f)
      if cached != null then cached
      else
        val w = f.closure.updated(f.name, f)
        closureWithSelfCache.put(f, w)
        w

  private def tcoInfoFor(f: Value.FunV): TcoInfo =
    val cached = tcoCache.get(f)
    if cached != null then cached
    else
      val info =
        if f.name.isEmpty then TcoInfo(Set.empty, false, false)
        else
          val targets = tailCallTargets(f.body, f.name, tailPos = true)
          val selfTR  = callsInTailPos(f.body, f.name)
          val noNTS   = !hasNonTailSelfCall(f.body, f.name, tailPos = true)
          TcoInfo(targets, selfTR, noNTS)
      tcoCache.put(f, info)
      info

  /** Format a position prefix like "[line 5, col 3] " or "" if unknown. */
  private def posPrefix: String = currentSpan match
    case Some((line, col)) => s"[line ${line + 1}, col ${col + 1}] "
    case None              => ""

  /** Render the source line at `currentSpan` with a caret underneath, or
   *  empty string if no position / source is known. Two-line output, indented
   *  so it lines up cleanly under the error message. */
  private def sourceContext: String = currentSpan match
    case Some((line, col)) if currentSource.nonEmpty =>
      val lines = currentSource.split("\n", -1)
      if line < 0 || line >= lines.length then ""
      else
        val src    = lines(line).stripTrailing
        val gutter = s"${line + 1}"
        val pad    = " " * gutter.length
        val caret  = " " * col.max(0).min(src.length) + "^"
        s"\n  $gutter | $src\n  $pad | $caret"
    case _ => ""

  /** Prefix `msg` with position info and throw InterpretError with source
   *  context appended underneath. */
  private def located(msg: String): Nothing =
    throw InterpretError(s"$posPrefix$msg$sourceContext")

  /** Update currentSpan from a scalameta tree's position (no-op if position is empty). */
  private def trackPos(tree: scala.meta.Tree): Unit =
    val p = tree.pos
    if !p.isEmpty then currentSpan = Some((p.startLine - lineOffset, p.startColumn))

  // ─── Public API ──────────────────────────────────────────────────

  /** Module-level `dependencies:` from the front-matter, captured at the
   *  top of `run` so any `[Card](dep://card.ssc)` import in this module
   *  can rewrite its scheme through `ImportResolver`. */
  private var moduleDeps: Map[String, String] = Map.empty

  def run(module: Module): Unit =
    initBuiltins()
    moduleDeps = module.manifest.map(_.dependencies).getOrElse(Map.empty)
    registerFrontmatterRoutes(module)
    module.sections.foreach(runSection)
    if !mainCalled then
      globals.get("main").foreach {
        case f: Value.FunV if f.params.isEmpty => Computation.run(callFun(f, Nil)); mainCalled = true
        case _ => ()
      }

  /** Register each `routes:` entry from front-matter as if the user had
   *  written `route(method, path) { req => handler(req) }` inline.  We
   *  register BEFORE evaluating sections (since the user's `serve(port)`
   *  call at the end of a section blocks forever) and bind a lazy
   *  wrapper that resolves the handler from `globals` at request time,
   *  once the section defs have run. */
  private def registerFrontmatterRoutes(module: Module): Unit =
    module.manifest.foreach { m =>
      m.routes.foreach { r =>
        val lazyHandler = Value.NativeFnV(
          s"frontmatter.route.${r.handler}",
          Computation.pureFn { args =>
            globals.get(r.handler) match
              case Some(h) => Computation.run(callValue(h, args, Map.empty))
              case None    =>
                throw InterpretError(
                  s"front-matter route ${r.method} ${r.path} references unknown handler '${r.handler}'"
                )
          }
        )
        scalascript.server.Routes.register(r.method, r.path, lazyHandler, this)
      }
    }

  // ─── Health-route defaults ────────────────────────────────────────

  // Extracted from initBuiltins so installNativeIntrinsics' NativeContext
  // can forward to it via ctx.registerHealthDefaults().
  private def registerHealthDefaults(): Unit =
    def isRegistered(path: String): Boolean =
      scalascript.server.Routes.all.exists(e => e.method == "GET" && e.path == path)
    val okResponse = Value.InstanceV("Response", Map(
      "status"  -> Value.IntV(200),
      "headers" -> Value.MapV(Map(
        Value.StringV("Content-Type") -> Value.StringV("application/json")
      )),
      "body"    -> Value.StringV("""{"status":"ok"}""")
    ))
    val handler = Value.NativeFnV("_healthOk", Computation.pureFn(_ => okResponse))
    if !isRegistered("/_health") then
      scalascript.server.Routes.register("GET", "/_health", handler, this)
    if !isRegistered("/_ready") then
      scalascript.server.Routes.register("GET", "/_ready", handler, this)

  // ─── Built-ins ───────────────────────────────────────────────────

  private def initBuiltins(): Unit =
    def nativeP(name: String)(f: List[Value] => Value): Unit =
      globals(name) = Value.NativeFnV(name, Computation.pureFn(f))

    // println / print / route / serve / stop now live in InterpreterIntrinsics
    // (Stage 5+/B); installNativeIntrinsics routes them through Backend.intrinsics.
    installNativeIntrinsics(InterpreterIntrinsics)

    nativeP("assert") {
      case List(Value.BoolV(true))             => Value.UnitV
      case List(Value.BoolV(false))            => throw InterpretError("Assertion failed")
      case List(Value.BoolV(true),  _)         => Value.UnitV
      case List(Value.BoolV(false), msg)       => throw InterpretError(s"Assertion failed: ${Value.show(msg)}")
      case _                                   => Value.UnitV
    }
    nativeP("require") {
      case List(Value.BoolV(true))        => Value.UnitV
      case List(Value.BoolV(false), msg)  => throw InterpretError(s"Requirement failed: ${Value.show(msg)}")
      case _                              => Value.UnitV
    }

    nativeP("tls") {
      case List(Value.StringV(cert), Value.StringV(key)) =>
        Value.InstanceV("TlsContext", Map(
          "cert" -> Value.StringV(cert),
          "key"  -> Value.StringV(key)
        ))
      case _ => throw InterpretError("tls(certPath, keyPath)")
    }

    // serve() with TLS overrides the HttpIntrinsics NativeImpl registration
    // (last registration wins) to add the serve(port, tls(cert,key)) case.
    nativeP("serve") {
      case List(Value.IntV(port)) =>
        registerHealthDefaults()
        if !headless then scalascript.server.WebServer.start(port.toInt, ".", out)
        Value.UnitV
      case List(Value.IntV(port), Value.StringV(dir)) =>
        registerHealthDefaults()
        if !headless then scalascript.server.WebServer.start(port.toInt, dir, out)
        Value.UnitV
      case List(Value.IntV(port), Value.InstanceV("TlsContext", tlsFields)) =>
        registerHealthDefaults()
        if !headless then
          val cert = tlsFields.get("cert").collect { case Value.StringV(s) => s }.getOrElse("")
          val key  = tlsFields.get("key").collect  { case Value.StringV(s) => s }.getOrElse("")
          scalascript.server.WebServer.start(port.toInt, ".", out, cert, key)
        Value.UnitV
      case _ => throw InterpretError("serve(port), serve(port, dir), or serve(port, tls(cert, key))")
    }

    // Wall-clock for benchmarks — same name across all three backends.
    nativeP("nanoTime") { _ => Value.IntV(java.lang.System.nanoTime()) }

    // ── Outbound HTTP client ──────────────────────────────────────────
    // httpGet / httpPost / httpClient use java.net.http.HttpClient
    // (built-in since JDK 11, Loom-friendly, HTTP/2-capable).  The
    // thread-local _httpBaseUrl (class-level field) lets httpClient{}
    // prepend a base URL without threading it through every call site.

    def httpDoRequest(
        method:  String,
        rawUrl:  String,
        body:    String,
        headers: Map[String, String]
    ): Value =
      import java.net.http.{HttpClient as JHttpClient, HttpRequest, HttpResponse}
      import scala.jdk.CollectionConverters.*
      val base = _httpBaseUrl.get()
      val url  = if base.nonEmpty && !rawUrl.startsWith("http") then base + rawUrl else rawUrl
      val client = JHttpClient.newHttpClient()
      val builder = HttpRequest.newBuilder().uri(java.net.URI.create(url))
      headers.foreach((k, v) => builder.header(k, v))
      val req = method match
        case "GET"    => builder.GET().build()
        case "POST"   => builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
        case "PUT"    => builder.PUT(HttpRequest.BodyPublishers.ofString(body)).build()
        case "DELETE" => builder.DELETE().build()
        case m        => builder.method(m, HttpRequest.BodyPublishers.ofString(body)).build()
      val resp  = client.send(req, HttpResponse.BodyHandlers.ofString())
      val hdrs: Map[Value, Value] = resp.headers().map().entrySet().iterator().asScala.flatMap { e =>
        if e.getValue.isEmpty then None
        else Some((Value.StringV(e.getKey): Value) -> (Value.StringV(e.getValue.get(0)): Value))
      }.toMap
      Value.InstanceV("Response", Map(
        "status"  -> Value.IntV(resp.statusCode().toLong),
        "body"    -> Value.StringV(resp.body()),
        "headers" -> Value.MapV(hdrs)
      ))

    def headersArg(v: Value): Map[String, String] = v match
      case Value.MapV(m) => m.collect {
        case (Value.StringV(k), Value.StringV(vv)) => k -> vv
      }.toMap
      case _ => Map.empty

    nativeP("httpGet") {
      case List(Value.StringV(url)) =>
        httpDoRequest("GET", url, "", Map.empty)
      case List(Value.StringV(url), hdrs) =>
        httpDoRequest("GET", url, "", headersArg(hdrs))
      case _ => throw InterpretError("httpGet(url[, headers])")
    }
    nativeP("httpPost") {
      case List(Value.StringV(url), Value.StringV(body)) =>
        httpDoRequest("POST", url, body, Map.empty)
      case List(Value.StringV(url), Value.StringV(body), hdrs) =>
        httpDoRequest("POST", url, body, headersArg(hdrs))
      case _ => throw InterpretError("httpPost(url, body[, headers])")
    }
    // httpClient(baseUrl) { block } — handled as a special form in eval
    // (double-apply pattern) so the block is evaluated directly rather than
    // wrapped as a thunk.  See the Term.Apply case in eval below.

    // wsConnect(url[, headers[, protocols]]) { ws => … }
    // Curried: first call returns a NativeFnV that takes the handler.
    // Blocks on the calling thread until the server closes the connection.
    nativeP("wsConnect") {
      case List(Value.StringV(url)) =>
        Value.NativeFnV("wsConnect.handler", Computation.pureFn {
          case List(handler) =>
            val sess = scalascript.server.WsClientSession(url, Map.empty, Nil, this, out)
            sess.connect()
            invoke(handler, List(sess.wsObj))
            sess.awaitClose()
            Value.UnitV
          case _ => throw InterpretError("wsConnect(url) { ws => … }")
        })
      case List(Value.StringV(url), hdrs) =>
        val headers = headersArg(hdrs)
        Value.NativeFnV("wsConnect.handler", Computation.pureFn {
          case List(handler) =>
            val sess = scalascript.server.WsClientSession(url, headers, Nil, this, out)
            sess.connect()
            invoke(handler, List(sess.wsObj))
            sess.awaitClose()
            Value.UnitV
          case _ => throw InterpretError("wsConnect(url, headers) { ws => … }")
        })
      case List(Value.StringV(url), hdrs, Value.ListV(prots)) =>
        val headers   = headersArg(hdrs)
        val protocols = prots.collect { case Value.StringV(s) => s }
        Value.NativeFnV("wsConnect.handler", Computation.pureFn {
          case List(handler) =>
            val sess = scalascript.server.WsClientSession(url, headers, protocols, this, out)
            sess.connect()
            invoke(handler, List(sess.wsObj))
            sess.awaitClose()
            Value.UnitV
          case _ => throw InterpretError("wsConnect(url, headers, protocols) { ws => … }")
        })
      case _ => throw InterpretError("wsConnect(url[, headers[, protocols]]) { ws => … }")
    }

    // cors(origins[, methods[, allowedHeaders]]) — global CORS policy
    nativeP("cors") {
      case List(Value.ListV(origins)) =>
        scalascript.server.WebServer.configureCors(
          origins.collect { case Value.StringV(s) => s },
          List("GET","POST","PUT","DELETE","OPTIONS","PATCH"), Nil)
        Value.UnitV
      case List(Value.ListV(origins), Value.ListV(methods)) =>
        scalascript.server.WebServer.configureCors(
          origins.collect { case Value.StringV(s) => s },
          methods.collect { case Value.StringV(s) => s }, Nil)
        Value.UnitV
      case List(Value.ListV(origins), Value.ListV(methods), Value.ListV(hdrs)) =>
        scalascript.server.WebServer.configureCors(
          origins.collect { case Value.StringV(s) => s },
          methods.collect { case Value.StringV(s) => s },
          hdrs.collect { case Value.StringV(s) => s })
        Value.UnitV
      case _ => throw InterpretError("cors(origins[, methods[, headers]])")
    }

    nativeP("useGzip") {
      case Nil => scalascript.server.WebServer.enableGzip(); Value.UnitV
      case _   => throw InterpretError("useGzip()")
    }

    def addCacheHdrs(v: Value, extra: Map[String, String]): Value = v match
      case Value.InstanceV("Response", fields) =>
        val h = fields.get("headers") match
          case Some(Value.MapV(m)) => m
          case _                   => Map.empty[Value, Value]
        val merged = h ++ extra.map { case (k, vv) => (Value.StringV(k): Value) -> (Value.StringV(vv): Value) }
        Value.InstanceV("Response", fields + ("headers" -> Value.MapV(merged)))
      case other => other

    nativeP("cacheable") {
      case List(resp, Value.IntV(maxAge)) =>
        addCacheHdrs(resp, Map("Cache-Control" -> s"public, max-age=$maxAge"))
      case List(resp, Value.IntV(maxAge), Value.StringV(etag)) =>
        addCacheHdrs(resp, Map("Cache-Control" -> s"public, max-age=$maxAge", "ETag" -> etag))
      case _ => throw InterpretError("cacheable(response, maxAge[, etag])")
    }

    nativeP("noCache") {
      case List(resp) =>
        addCacheHdrs(resp, Map("Cache-Control" -> "no-store, no-cache, must-revalidate"))
      case _ => throw InterpretError("noCache(response)")
    }

    // streamResponse { write => ... } — chunked streaming from a route handler.
    // Returns a StreamResponse sentinel detected by WebServer.dispatchRoute
    // before calling writeResponse.  Curried forms for status / headers too.
    nativeP("streamResponse") {
      case List(Value.IntV(status)) =>
        Value.NativeFnV("streamResponse.block", Computation.pureFn {
          case List(block) => Value.InstanceV("StreamResponse", Map(
            "status" -> Value.IntV(status.toInt), "headers" -> Value.MapV(Map.empty), "callback" -> block))
          case _ => throw InterpretError("streamResponse(status)(block)")
        })
      case List(Value.IntV(status), Value.MapV(hdrs)) =>
        Value.NativeFnV("streamResponse.block", Computation.pureFn {
          case List(block) => Value.InstanceV("StreamResponse", Map(
            "status" -> Value.IntV(status.toInt), "headers" -> Value.MapV(hdrs), "callback" -> block))
          case _ => throw InterpretError("streamResponse(status, headers)(block)")
        })
      case List(block) =>
        Value.InstanceV("StreamResponse", Map(
          "status" -> Value.IntV(200), "headers" -> Value.MapV(Map.empty), "callback" -> block))
      case _ => throw InterpretError("streamResponse(block)")
    }

    // maxBodySize(bytes) — reject requests whose body exceeds the limit with 413.
    nativeP("maxBodySize") {
      case List(Value.IntV(n)) => scalascript.server.WebServer.setMaxBodySize(n.toLong); Value.UnitV
      case _ => throw InterpretError("maxBodySize(bytes: Int)")
    }

    // Environment variable reader, same surface on all three backends.
    // `getenv(key)` returns the value or empty string when unset.
    // `getenv(key, default)` substitutes the default when missing/empty.
    nativeP("getenv") {
      case List(Value.StringV(k)) =>
        Value.StringV(Option(java.lang.System.getenv(k)).getOrElse(""))
      case List(Value.StringV(k), Value.StringV(d)) =>
        val v = java.lang.System.getenv(k)
        Value.StringV(if v == null || v.isEmpty then d else v)
      case _ => throw InterpretError("getenv(key[, default])")
    }

    // doc(...) builds a DocV; render(...) prints it
    nativeP("doc")    { args => Value.DocV(args) }
    nativeP("render") { args =>
      val text = args match
        case List(Value.DocV(parts)) => parts.map(Value.show).mkString("\n")
        case List(v)                 => Value.show(v)
        case vs                      => vs.map(Value.show).mkString("\n")
      out.println(text)
      Value.UnitV
    }

    nativeP("Some")  { case List(v) => Value.OptionV(Some(v)); case _ => throw InterpretError("Some takes 1 arg") }
    nativeP("List")  { args => Value.ListV(args) }
    // List companion object — fill/tabulate are curried (List.fill(n)(elem))
    globals("List.fill") = Value.NativeFnV("List.fill", {
      case List(Value.IntV(n)) =>
        Pure(Value.NativeFnV("List.fill.n", {
          case List(elem) => Pure(Value.ListV(List.fill(n.toInt)(elem)))
          case _          => throw InterpretError("List.fill(n)(elem)")
        }))
      case _ => throw InterpretError("List.fill(n)(elem)")
    })
    globals("List.tabulate") = Value.NativeFnV("List.tabulate", {
      case List(Value.IntV(n)) =>
        Pure(Value.NativeFnV("List.tabulate.n", {
          case List(f) =>
            // f(i) may perform effects — sequence the computations
            Computation.sequence((0 until n.toInt).toList.map(i =>
              callValue(f, List(Value.IntV(i)), Map.empty)))
          case _ => throw InterpretError("List.tabulate(n)(f)")
        }))
      case _ => throw InterpretError("List.tabulate(n)(f)")
    })
    globals("List.range") = Value.NativeFnV("List.range", {
      case List(Value.IntV(from), Value.IntV(until)) =>
        Pure(Value.ListV((from.toInt until until.toInt).map(i => Value.IntV(i)).toList))
      case List(Value.IntV(from), Value.IntV(until), Value.IntV(step)) =>
        Pure(Value.ListV((from.toInt until until.toInt by step.toInt).map(i => Value.IntV(i)).toList))
      case _ => throw InterpretError("List.range(from, until[, step])")
    })
    val listNative = globals("List")
    globals("List") = Value.InstanceV("List", Map(
      "fill"     -> globals("List.fill"),
      "tabulate" -> globals("List.tabulate"),
      "range"    -> globals("List.range"),
      "empty"    -> Value.ListV(Nil),
      "apply"    -> listNative
    ))
    nativeP("Map") { args =>
      val entries = args.collect { case Value.TupleV(List(k, v)) => k -> v }.toMap
      Value.MapV(entries)
    }
    globals("None") = Value.OptionV(None)
    globals("Nil")  = Value.ListV(Nil)

    nativeP("math.sqrt")  { case List(Value.DoubleV(d)) => Value.DoubleV(math.sqrt(d))
                            case List(Value.IntV(n))    => Value.DoubleV(math.sqrt(n.toDouble))
                            case _ => Value.UnitV }
    nativeP("math.abs")   { case List(Value.DoubleV(d)) => Value.DoubleV(math.abs(d))
                            case List(Value.IntV(n))    => Value.IntV(math.abs(n))
                            case _ => Value.UnitV }
    nativeP("math.pow")   { case List(Value.DoubleV(a), Value.DoubleV(b)) => Value.DoubleV(math.pow(a, b))
                            case List(Value.IntV(a),    Value.DoubleV(b)) => Value.DoubleV(math.pow(a.toDouble, b))
                            case List(Value.DoubleV(a), Value.IntV(b))    => Value.DoubleV(math.pow(a, b.toDouble))
                            case List(Value.IntV(a),    Value.IntV(b))    => Value.DoubleV(math.pow(a.toDouble, b.toDouble))
                            case _ => Value.UnitV }
    nativeP("math.floor") { case List(Value.DoubleV(d)) => Value.DoubleV(math.floor(d)); case _ => Value.UnitV }
    nativeP("math.ceil")  { case List(Value.DoubleV(d)) => Value.DoubleV(math.ceil(d));  case _ => Value.UnitV }
    nativeP("math.round") { case List(Value.DoubleV(d)) => Value.IntV(math.round(d));    case _ => Value.UnitV }
    globals("math.Pi")   = Value.DoubleV(math.Pi)
    globals("math.E")    = Value.DoubleV(math.E)
    // math as an object so `math.sqrt(x)` works via field dispatch
    globals("math") = Value.InstanceV("math", Map(
      "sqrt"  -> globals("math.sqrt"),
      "abs"   -> globals("math.abs"),
      "pow"   -> globals("math.pow"),
      "floor" -> globals("math.floor"),
      "ceil"  -> globals("math.ceil"),
      "round" -> globals("math.round"),
      "Pi"    -> globals("math.Pi"),
      "E"     -> globals("math.E")
    ))

    // ─── Web primitives: escape, raw, route, Request, Response ────────

    nativeP("escape") {
      case List(Value.StringV(s)) => Value.StringV(htmlEscape(s))
      case List(v)                => Value.StringV(htmlEscape(Value.show(v)))
      case _                      => throw InterpretError("escape(s)")
    }

    // `collectCss(comp1, comp2, ...)` — concatenate each argument's `css`
    // field into one CSS string for a page-level <style>.  Anything
    // without a `css` field is silently skipped; anything whose `css` is
    // not a String is skipped too.  Convention helper for component-style
    // .ssc files (see SPEC §8.4).
    nativeP("collectCss") { args =>
      val parts = args.flatMap {
        case Value.InstanceV(_, fields) =>
          fields.get("css").collect { case Value.StringV(s) => s }
        case _ => None
      }
      Value.StringV(parts.mkString("\n"))
    }

    // `collectJs(comp1, comp2, ...)` — same shape as collectCss, only it
    // reads each argument's `js` field.  Pages stitch the result into
    // a single page-level <script> tag.
    nativeP("collectJs") { args =>
      val parts = args.flatMap {
        case Value.InstanceV(_, fields) =>
          fields.get("js").collect { case Value.StringV(s) => s }
        case _ => None
      }
      Value.StringV(parts.mkString("\n"))
    }

    // `scope("Card")` returns a small object with two helpers used by
    // component-style .ssc files to suffix class names (see SPEC §8.4):
    //
    //   val s = scope("Card")
    //   s.css(""".title { ... }""")  // → ".title__Card { ... }"
    //   s.cls("title")               // → "title__Card"
    //
    // Two components can both use bare class names like `.title` and
    // their concatenated CSS won't conflict.  The CSS rewriter is a
    // simple `\.identifier` regex pass — class chains (`.a.b`) are
    // handled, but `.ident` inside CSS string contents (e.g. inside a
    // `url("./file.ext")`) would also be rewritten; keep URL strings
    // free of bare-identifier dots if you depend on them.
    nativeP("scope") {
      case List(Value.StringV(scopeName)) =>
        def rewrite(css: String): String =
          val pat = """\.([A-Za-z_][A-Za-z0-9_-]*)""".r
          pat.replaceAllIn(css, m =>
            java.util.regex.Matcher.quoteReplacement(s".${m.group(1)}__$scopeName")
          )
        Value.InstanceV("Scope", Map(
          "name" -> Value.StringV(scopeName),
          "css"  -> Value.NativeFnV("scope.css", Computation.pureFn {
            case List(Value.StringV(s)) => Value.StringV(rewrite(s))
            case _                       => throw InterpretError("scope.css(s)")
          }),
          "cls"  -> Value.NativeFnV("scope.cls", Computation.pureFn {
            case List(Value.StringV(n)) => Value.StringV(s"${n}__$scopeName")
            case _                       => throw InterpretError("scope.cls(name)")
          })
        ))
      case _ => throw InterpretError("scope(name: String)")
    }

    // ─── Typed HTML DSL — `div(cls := "x", h1("hi"))` style ───────────
    //
    // Each tag is a native fn that takes a list of mixed args: Attr values
    // (key=value pairs from `<key> := <value>`) and children (Strings, _Raw
    // markers, or arbitrary Values rendered via Value.show).  The result is
    // a _Raw HTML node so it composes with html"..." without re-escaping.

    def attrKey(htmlName: String): Value.InstanceV =
      Value.InstanceV("AttrKey", Map("name" -> Value.StringV(htmlName)))

    // Attribute keys live under an `attr` namespace to avoid clobbering
    // very common user-side bindings like `name`, `id`, `title`, `value`.
    // Usage: `div(attr.cls := "hero", attr.id := "main")`.  Names that
    // collide with Scala reserved words use an underscore suffix
    // (`attr.type_`, `attr.for_`, `attr.method_`).
    globals("attr") = Value.InstanceV("attr", Map(
      "cls"         -> attrKey("class"),
      "id"          -> attrKey("id"),
      "href"        -> attrKey("href"),
      "src"         -> attrKey("src"),
      "alt"         -> attrKey("alt"),
      "name"        -> attrKey("name"),
      "title"       -> attrKey("title"),
      "style"       -> attrKey("style"),
      "type_"       -> attrKey("type"),
      "value_"      -> attrKey("value"),
      "placeholder" -> attrKey("placeholder"),
      "method_"     -> attrKey("method"),
      "action"      -> attrKey("action"),
      "target"      -> attrKey("target"),
      "rel"         -> attrKey("rel"),
      "for_"        -> attrKey("for"),
      "role"        -> attrKey("role"),
      "colspan"     -> attrKey("colspan"),
      "rowspan"     -> attrKey("rowspan"),
      "disabled"    -> attrKey("disabled"),
    ))

    def htmlNode(s: String): Value.InstanceV =
      Value.InstanceV("_Raw", Map("html" -> Value.StringV(s)))

    /** Render a single child node: trusted html (_Raw) passes through,
     *  Lists flatten so `xs.map(li)` composes naturally inside a parent
     *  tag, everything else goes through `Value.show` + `htmlEscape`. */
    def renderChild(v: Value): String = v match
      case Value.InstanceV("_Raw", fields) =>
        fields.get("html").map(Value.show).getOrElse("")
      case Value.ListV(items) =>
        items.map(renderChild).mkString
      case other => htmlEscape(Value.show(other))

    /** Split a tag's arg-list into attribute pairs (from `key := value`)
     *  and children (everything else, rendered as HTML).  A `ListV` arg
     *  flattens into multiple children. */
    def renderTag(name: String, args: List[Value], voidTag: Boolean = false): Value.InstanceV =
      val attrs    = scala.collection.mutable.LinkedHashMap.empty[String, String]
      val children = StringBuilder()
      def handle(v: Value): Unit = v match
        case Value.InstanceV("Attr", fields) =>
          val k = fields.get("name").map(Value.show).getOrElse("")
          val vv = fields.get("value").map(Value.show).getOrElse("")
          attrs(k) = vv
        case Value.ListV(items) =>
          items.foreach(handle)
        case other =>
          children ++= renderChild(other)
      args.foreach(handle)
      val attrStr =
        if attrs.isEmpty then ""
        else attrs.map((k, v) => s""" $k="${htmlEscape(v)}"""").mkString
      if voidTag then htmlNode(s"<$name$attrStr>")
      else            htmlNode(s"<$name$attrStr>${children.toString}</$name>")

    // All tags live at the top level for ergonomics — `div(...)`, `h1(...)`,
    // `body(...)`.  When user code rebinds one of these names (`val body =
    // req.body` inside a route handler) the local binding shadows the tag
    // global the usual way, just like any other top-level definition.
    val containerTags = List(
      "html", "head", "body", "title", "style", "script", "main",
      "section", "header", "footer", "nav", "article", "aside",
      "div", "span", "p", "a", "em", "strong", "small", "code", "pre",
      "h1", "h2", "h3", "h4", "h5", "h6",
      "ul", "ol", "li", "dl", "dt", "dd",
      "table", "thead", "tbody", "tfoot", "tr", "td", "th",
      "form", "button", "label", "select", "option", "textarea",
      "figure", "figcaption", "blockquote",
    )
    containerTags.foreach { t => nativeP(t) { args => renderTag(t, args) } }

    // Void tags: no children, no closing tag.  `<br>`, `<img src=...>`, etc.
    val voidTags = List("br", "hr", "img", "input", "link", "meta")
    voidTags.foreach { t => nativeP(t) { args => renderTag(t, args, voidTag = true) } }

    // raw(s) marks a string as pre-escaped HTML so html"..." doesn't re-escape.
    nativeP("raw") {
      case List(Value.StringV(s)) => Value.InstanceV("_Raw", Map("html" -> Value.StringV(s)))
      case List(v)                => Value.InstanceV("_Raw", Map("html" -> Value.StringV(Value.show(v))))
      case _                      => throw InterpretError("raw(s)")
    }

    // Response(status, headers, body) — Map-based, all optional.
    def mkResponse(
        status:  Int,
        headers: Map[Value, Value] = Map.empty,
        body:    String = ""
    ): Value.InstanceV =
      Value.InstanceV("Response", Map(
        "status"  -> Value.IntV(status),
        "headers" -> Value.MapV(headers),
        "body"    -> Value.StringV(body)
      ))

    def bodyOf(v: Value): String = v match
      case Value.StringV(s)                                    => s
      case Value.InstanceV("_Raw", fields)                     => fields.get("html").map(Value.show).getOrElse("")
      case other                                               => Value.show(other)

    /** JSON-encode a Value. Strings pass through as raw JSON (so callers
     *  that hand-build a JSON string still work); everything else gets
     *  proper escaping + structural emission. */
    def toJson(v: Value): String =
      def quote(s: String): String =
        val sb = StringBuilder().append('"')
        var i = 0
        while i < s.length do
          val c = s.charAt(i)
          c match
            case '"'  => sb.append("\\\"")
            case '\\' => sb.append("\\\\")
            case '\n' => sb.append("\\n")
            case '\r' => sb.append("\\r")
            case '\t' => sb.append("\\t")
            case '\b' => sb.append("\\b")
            case '\f' => sb.append("\\f")
            case c if c < 0x20 => sb.append("\\u%04x".format(c.toInt))
            case c    => sb.append(c)
          i += 1
        sb.append('"').toString
      def keyStr(k: Value): String = k match
        case Value.StringV(s) => s
        case other            => Value.show(other)
      v match
        case Value.StringV(s)         => quote(s)
        case Value.IntV(n)            => n.toString
        case Value.DoubleV(d)         => d.toString
        case Value.BoolV(b)           => b.toString
        case Value.NullV | Value.UnitV => "null"
        case Value.CharV(c)           => quote(c.toString)
        case Value.ListV(items)       => items.map(toJson).mkString("[", ",", "]")
        case Value.MapV(entries)      =>
          entries.map((k, v) => quote(keyStr(k)) + ":" + toJson(v)).mkString("{", ",", "}")
        case Value.OptionV(None)      => "null"
        case Value.OptionV(Some(x))   => toJson(x)
        case Value.TupleV(elems)      => elems.map(toJson).mkString("[", ",", "]")
        case Value.InstanceV(_, fields) =>
          fields.map((k, v) => quote(k) + ":" + toJson(v)).mkString("{", ",", "}")
        case other                    => quote(Value.show(other))

    nativeP("jsonStringify") {
      case List(v) => Value.StringV(toJson(v))
      case _       => throw InterpretError("jsonStringify(v)")
    }
    nativeP("jsonParse") {
      case List(Value.StringV(s)) =>
        try JsonParser.parse(s)
        catch case e: JsonParser.ParseError => throw InterpretError(e.getMessage)
      case _ => throw InterpretError("jsonParse(s: String)")
    }

    // v1.5 Tier 5 #22 — JsonValue wrapper (option c).
    //
    // `jsonRead(s)` returns a `JsonValue` that supports idiomatic
    // field / index / typed-accessor calls:
    //
    //     val v = jsonRead("""{"user":{"name":"Ada"},"tags":["math"]}""")
    //     v("user")("name").asString          // "Ada"
    //     v("tags").asList.map(_.asString)    // List("math")
    //     v.get("missing").map(_.asString)    // None
    //     v("user").keys                       // List("name")
    //
    // Implementation is a self-recursive `InstanceV("JsonValue", …)` that
    // bundles every accessor as a NativeFnV — `apply` for field /
    // index access, `get` for the Option variants, `asX` / `isNull` /
    // `keys` for type extraction.
    def wrapJson(inner: Value): Value =
      def typedFail(what: String, got: Value): Nothing =
        throw InterpretError(s"JsonValue.$what: expected ${what.stripPrefix("as").toLowerCase} but got ${Value.show(got)}")
      def pureFn(f: List[Value] => Value): List[Value] => Computation =
        args => Pure(f(args))
      val applyFn = Value.NativeFnV("JsonValue.apply", pureFn {
        case List(Value.StringV(k)) => inner match
          case Value.MapV(m) => m.get(Value.StringV(k)) match
            case Some(v) => wrapJson(v)
            case None    => throw InterpretError(s"JsonValue: no key '$k'")
          case _ => throw InterpretError(s"JsonValue.apply($k): not an object")
        case List(Value.IntV(i)) => inner match
          case Value.ListV(items) =>
            if i >= 0 && i < items.length then wrapJson(items(i.toInt))
            else throw InterpretError(s"JsonValue: index $i out of bounds (size=${items.length})")
          case _ => throw InterpretError(s"JsonValue.apply($i): not an array")
        case args => throw InterpretError(s"JsonValue.apply(key: String | index: Int), got ${args.length} arg(s)")
      })
      val getFn = Value.NativeFnV("JsonValue.get", pureFn {
        case List(Value.StringV(k)) => inner match
          case Value.MapV(m) => Value.OptionV(m.get(Value.StringV(k)).map(wrapJson))
          case _             => Value.OptionV(None)
        case List(Value.IntV(i)) => inner match
          case Value.ListV(items) if i >= 0 && i < items.length =>
            Value.OptionV(Some(wrapJson(items(i.toInt))))
          case _ => Value.OptionV(None)
        case _ => throw InterpretError("JsonValue.get(key | index)")
      })
      val asStringFn = Value.NativeFnV("JsonValue.asString", pureFn(_ => inner match
        case Value.StringV(s) => Value.StringV(s)
        case other            => typedFail("asString", other)))
      val asIntFn = Value.NativeFnV("JsonValue.asInt", pureFn(_ => inner match
        case Value.IntV(n)    => Value.IntV(n)
        case Value.DoubleV(d) => Value.IntV(d.toLong)
        case other            => typedFail("asInt", other)))
      val asDoubleFn = Value.NativeFnV("JsonValue.asDouble", pureFn(_ => inner match
        case Value.DoubleV(d) => Value.DoubleV(d)
        case Value.IntV(n)    => Value.DoubleV(n.toDouble)
        case other            => typedFail("asDouble", other)))
      val asBoolFn = Value.NativeFnV("JsonValue.asBool", pureFn(_ => inner match
        case Value.BoolV(b) => Value.BoolV(b)
        case other          => typedFail("asBool", other)))
      val asListFn = Value.NativeFnV("JsonValue.asList", pureFn(_ => inner match
        case Value.ListV(items) => Value.ListV(items.map(wrapJson))
        case other              => typedFail("asList", other)))
      val asMapFn = Value.NativeFnV("JsonValue.asMap", pureFn(_ => inner match
        case Value.MapV(m) => Value.MapV(m.map { case (k, v) => k -> wrapJson(v) })
        case other         => typedFail("asMap", other)))
      val rawFn = Value.NativeFnV("JsonValue.raw", pureFn(_ => inner))
      val isNullFn = Value.NativeFnV("JsonValue.isNull", pureFn(_ => inner match
        case Value.UnitV | Value.OptionV(None) => Value.BoolV(true)
        case _                                  => Value.BoolV(false)))
      val keysFn = Value.NativeFnV("JsonValue.keys", pureFn(_ => inner match
        case Value.MapV(m) => Value.ListV(m.keys.toList)
        case _             => Value.ListV(Nil)))
      val sizeFn = Value.NativeFnV("JsonValue.size", pureFn(_ => inner match
        case Value.ListV(items) => Value.IntV(items.length.toLong)
        case Value.MapV(m)      => Value.IntV(m.size.toLong)
        case Value.StringV(s)   => Value.IntV(s.length.toLong)
        case _                  => Value.IntV(0L)))
      Value.InstanceV("JsonValue", Map(
        "_inner"   -> inner,
        "apply"    -> applyFn,
        "get"      -> getFn,
        "asString" -> asStringFn,
        "asInt"    -> asIntFn,
        "asLong"   -> asIntFn,
        "asDouble" -> asDoubleFn,
        "asBool"   -> asBoolFn,
        "asList"   -> asListFn,
        "asMap"    -> asMapFn,
        "raw"      -> rawFn,
        "isNull"   -> isNullFn,
        "keys"     -> keysFn,
        "size"     -> sizeFn,
      ))

    nativeP("jsonRead") {
      case List(Value.StringV(s)) =>
        try wrapJson(JsonParser.parse(s))
        catch case e: JsonParser.ParseError => throw InterpretError(e.getMessage)
      case List(v) => wrapJson(v)  // already-parsed value
      case _       => throw InterpretError("jsonRead(s: String) or jsonRead(parsedAny)")
    }

    // v1.5 Tier 5 #22 — indexed access on `Any`-typed JSON values.
    // `jsonParse` returns `Any` because the shape is data-dependent;
    // the interpreter and JS already let users write `obj("name")`
    // dynamically, but JvmGen-emitted Scala can't (the Scala typer
    // rejects `apply` on `Any`).  `lookup(v, k)` is the cross-
    // backend escape hatch — pattern-matches at runtime, throws
    // when the key is absent (matches Map.apply semantics).
    // `lookupOpt(v, k)` is the Option-returning variant.
    def lookupKey(v: Value, k: Value): Option[Value] = v match
      case Value.MapV(m)     => m.get(k)
      case Value.ListV(items) => k match
        case Value.IntV(i) if i >= 0 && i < items.length => Some(items(i.toInt))
        case _                                            => None
      case Value.InstanceV(_, fields) => k match
        case Value.StringV(name) => fields.get(name)
        case _                   => None
      case Value.StringV(s) => k match
        case Value.IntV(i) if i >= 0 && i < s.length =>
          Some(Value.StringV(s.charAt(i.toInt).toString))
        case _ => None
      case _ => None

    nativeP("lookup") {
      case List(v, k) => lookupKey(v, k) match
        case Some(x) => x
        case None    => throw InterpretError(s"lookup: key ${Value.show(k)} not found in ${Value.show(v)}")
      case _ => throw InterpretError("lookup(v, key)")
    }
    nativeP("lookupOpt") {
      case List(v, k) => Value.OptionV(lookupKey(v, k))
      case _          => throw InterpretError("lookupOpt(v, key)")
    }

    // Tier 5 #20 — typed request validation primitives.  Look up the
    // field in `req.form` first, then `req.query`.  `requireX` throws
    // `RestValidationError(msg)` which the HTTP dispatchers (WebServer
    // for INT, JsGen-emitted serve() for JS, JvmGen-emitted route
    // dispatch for JVM) catch and convert to a 400 Bad Request response
    // — handlers write linear code without explicit error checks.
    def fieldOf(req: Value, name: String): Option[String] =
      def lookup(field: String): Option[String] = req match
        case Value.InstanceV(_, fields) => fields.get(field) match
          case Some(Value.MapV(m)) => m.get(Value.StringV(name)) match
            case Some(Value.StringV(s)) => Some(s)
            case _                      => None
          case _ => None
        case _ => None
      lookup("form").orElse(lookup("query"))

    // Inside a `validate { … }` block we record the error and return
    // the supplied default so the body can keep running; outside one
    // we throw as before and the route dispatcher emits a 400.
    def recordOrThrow(name: String, msg: String, default: Value): Value =
      validationStack.headOption match
        case Some(buf) => buf.put(name, msg); default
        case None      => throw new scalascript.server.RestValidationError(msg)

    nativeP("requireString") {
      case List(req, Value.StringV(name)) =>
        fieldOf(req, name) match
          case Some(s) => Value.StringV(s)
          case None    => recordOrThrow(name, s"missing field: $name", Value.StringV(""))
      case _ => throw InterpretError("requireString(req, name)")
    }
    nativeP("optionalString") {
      case List(req, Value.StringV(name)) =>
        Value.OptionV(fieldOf(req, name).map(Value.StringV(_)))
      case _ => throw InterpretError("optionalString(req, name)")
    }
    nativeP("requireInt") {
      case List(req, Value.StringV(name)) =>
        fieldOf(req, name) match
          case Some(s) =>
            try Value.IntV(s.toLong)
            catch case _: NumberFormatException =>
              recordOrThrow(name, s"invalid integer for field: $name", Value.IntV(0L))
          case None =>
            recordOrThrow(name, s"missing field: $name", Value.IntV(0L))
      case _ => throw InterpretError("requireInt(req, name)")
    }
    nativeP("optionalInt") {
      case List(req, Value.StringV(name)) =>
        val parsed = fieldOf(req, name).flatMap { s =>
          try Some(Value.IntV(s.toLong))
          catch case _: NumberFormatException => None
        }
        Value.OptionV(parsed)
      case _ => throw InterpretError("optionalInt(req, name)")
    }
    nativeP("requireDouble") {
      case List(req, Value.StringV(name)) =>
        fieldOf(req, name) match
          case Some(s) =>
            try Value.DoubleV(s.toDouble)
            catch case _: NumberFormatException =>
              recordOrThrow(name, s"invalid number for field: $name", Value.DoubleV(0.0))
          case None =>
            recordOrThrow(name, s"missing field: $name", Value.DoubleV(0.0))
      case _ => throw InterpretError("requireDouble(req, name)")
    }
    nativeP("optionalDouble") {
      case List(req, Value.StringV(name)) =>
        val parsed = fieldOf(req, name).flatMap { s =>
          try Some(Value.DoubleV(s.toDouble))
          catch case _: NumberFormatException => None
        }
        Value.OptionV(parsed)
      case _ => throw InterpretError("optionalDouble(req, name)")
    }
    nativeP("requireBool") {
      case List(req, Value.StringV(name)) =>
        fieldOf(req, name) match
          case Some(s) => s.toLowerCase match
            case "true"  | "1" | "yes" | "on"  => Value.BoolV(true)
            case "false" | "0" | "no"  | "off" => Value.BoolV(false)
            case _ => recordOrThrow(name, s"invalid boolean for field: $name", Value.BoolV(false))
          case None =>
            recordOrThrow(name, s"missing field: $name", Value.BoolV(false))
      case _ => throw InterpretError("requireBool(req, name)")
    }
    nativeP("optionalBool") {
      case List(req, Value.StringV(name)) =>
        val parsed = fieldOf(req, name).flatMap { s =>
          s.toLowerCase match
            case "true"  | "1" | "yes" | "on"  => Some(Value.BoolV(true))
            case "false" | "0" | "no"  | "off" => Some(Value.BoolV(false))
            case _ => None
        }
        Value.OptionV(parsed)
      case _ => throw InterpretError("optionalBool(req, name)")
    }

    // v1.5 Tier 5 #20 — bounded numeric require.  Same coercion +
    // validation surface as `requireInt`, plus an inclusive
    // `[min..max]` range check.  Returns 0 on miss/invalid/oor inside
    // a `validate { … }` block so the body keeps running.
    nativeP("requireRange") {
      case List(req, Value.StringV(name), Value.IntV(min), Value.IntV(max)) =>
        fieldOf(req, name) match
          case Some(s) =>
            try
              val n = s.toLong
              if n < min || n > max then
                recordOrThrow(name, s"out of range [$min..$max] for field: $name", Value.IntV(min))
              else Value.IntV(n)
            catch case _: NumberFormatException =>
              recordOrThrow(name, s"invalid integer for field: $name", Value.IntV(min))
          case None =>
            recordOrThrow(name, s"missing field: $name", Value.IntV(min))
      case _ => throw InterpretError("requireRange(req, name, min, max)")
    }
    nativeP("requireRangeDouble") {
      case List(req, Value.StringV(name), Value.DoubleV(min), Value.DoubleV(max)) =>
        fieldOf(req, name) match
          case Some(s) =>
            try
              val n = s.toDouble
              if n < min || n > max then
                recordOrThrow(name, s"out of range [$min..$max] for field: $name", Value.DoubleV(min))
              else Value.DoubleV(n)
            catch case _: NumberFormatException =>
              recordOrThrow(name, s"invalid number for field: $name", Value.DoubleV(min))
          case None =>
            recordOrThrow(name, s"missing field: $name", Value.DoubleV(min))
      case _ => throw InterpretError("requireRangeDouble(req, name, min: Double, max: Double)")
    }
    nativeP("requireOneOf") {
      case List(req, Value.StringV(name), Value.ListV(opts)) =>
        val allowed = opts.collect { case Value.StringV(s) => s }
        fieldOf(req, name) match
          case Some(s) if allowed.contains(s) => Value.StringV(s)
          case Some(s) =>
            recordOrThrow(name,
              s"invalid value '$s' for field: $name (expected one of: ${allowed.mkString(", ")})",
              Value.StringV(allowed.headOption.getOrElse("")))
          case None =>
            recordOrThrow(name, s"missing field: $name",
              Value.StringV(allowed.headOption.getOrElse("")))
      case _ => throw InterpretError("requireOneOf(req, name, options: List[String])")
    }

    nativeP("Response.html") {
      case List(v) =>
        Value.InstanceV("Response", Map(
          "status"  -> Value.IntV(200),
          "headers" -> Value.MapV(Map(Value.StringV("Content-Type") -> Value.StringV("text/html; charset=utf-8"))),
          "body"    -> Value.StringV(bodyOf(v))
        ))
      case _ => throw InterpretError("Response.html(body)")
    }
    nativeP("Response.text") {
      case List(v) => mkResponse(200, Map(Value.StringV("Content-Type") -> Value.StringV("text/plain; charset=utf-8")), bodyOf(v))
      case _       => throw InterpretError("Response.text(body)")
    }
    nativeP("Response.json") {
      // String → raw passthrough (back-compat with hand-built JSON);
      // any other Value → structural JSON encode.
      case List(s: Value.StringV) =>
        mkResponse(200, Map(Value.StringV("Content-Type") -> Value.StringV("application/json")), s.v)
      case List(v) =>
        mkResponse(200, Map(Value.StringV("Content-Type") -> Value.StringV("application/json")), toJson(v))
      case _ => throw InterpretError("Response.json(body)")
    }
    nativeP("Response.redirect") {
      case List(Value.StringV(loc)) => mkResponse(302, Map(Value.StringV("Location") -> Value.StringV(loc)), "")
      case _ => throw InterpretError("Response.redirect(url)")
    }
    nativeP("Response.notFound") {
      case Nil      => mkResponse(404, body = "Not Found")
      case List(v)  => mkResponse(404, body = bodyOf(v))
      case _        => throw InterpretError("Response.notFound([body])")
    }
    nativeP("Response.status") {
      case List(Value.IntV(s))                       => mkResponse(s.toInt)
      case List(Value.IntV(s), v)                    => mkResponse(s.toInt, body = bodyOf(v))
      case _                                         => throw InterpretError("Response.status(code[, body])")
    }
    // `Response.basicAuthChallenge(realm)` — 401 + WWW-Authenticate so
    // the browser shows a Basic auth prompt with the given realm label.
    nativeP("Response.basicAuthChallenge") {
      case List(Value.StringV(realm)) =>
        // Quotes around the realm escape literal `"` per RFC 7617.
        val safe = realm.replace("\\", "\\\\").replace("\"", "\\\"")
        mkResponse(401,
          Map(Value.StringV("WWW-Authenticate") -> Value.StringV(s"""Basic realm="$safe"""")),
          "Authentication required")
      case _ => throw InterpretError("Response.basicAuthChallenge(realm: String)")
    }
    // Response companion object — fields call the underlying natives.
    globals("Response") = Value.InstanceV("Response", Map(
      "html"               -> globals("Response.html"),
      "text"               -> globals("Response.text"),
      "json"               -> globals("Response.json"),
      "redirect"           -> globals("Response.redirect"),
      "notFound"           -> globals("Response.notFound"),
      "status"             -> globals("Response.status"),
      "basicAuthChallenge" -> globals("Response.basicAuthChallenge")
    ))

    // ── CSRF helpers ─────────────────────────────────────────────────
    // `csrfToken()` returns a fresh url-safe random string; the caller
    // is expected to stash it under "csrf" in the session and render it
    // in their form.  `csrfValid(req)` checks the form's `csrf` field or
    // the `X-CSRF-Token` header against the session's stored value.
    nativeP("csrfToken") { _ =>
      val bytes = new Array[Byte](24)
      java.security.SecureRandom().nextBytes(bytes)
      Value.StringV(java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes))
    }
    nativeP("csrfValid") {
      case List(Value.InstanceV("Request", fields)) =>
        def asMap(v: Option[Value]): Map[String, String] = v match
          case Some(Value.MapV(m)) =>
            m.collect { case (Value.StringV(k), Value.StringV(s)) => k -> s }.toMap
          case _ => Map.empty
        val form    = asMap(fields.get("form"))
        val headers = asMap(fields.get("headers"))
        val session = asMap(fields.get("session"))
        val expected = session.getOrElse("csrf", "")
        val supplied = form.get("csrf")
          .orElse(headers.collectFirst { case (k, v) if k.equalsIgnoreCase("X-CSRF-Token") => v })
          .getOrElse("")
        val ok =
          if expected.isEmpty || supplied.isEmpty then false
          else java.security.MessageDigest.isEqual(
            expected.getBytes("UTF-8"), supplied.getBytes("UTF-8"))
        Value.BoolV(ok)
      case _ => throw InterpretError("csrfValid(req)")
    }

    // ── Base64-url codec (handy for WebAuthn ceremonies, JWT, etc.) ──
    nativeP("base64UrlEncode") {
      case List(Value.StringV(s)) =>
        Value.StringV(java.util.Base64.getUrlEncoder.withoutPadding
          .encodeToString(s.getBytes("UTF-8")))
      case _ => throw InterpretError("base64UrlEncode(s: String)")
    }
    nativeP("base64UrlDecode") {
      case List(Value.StringV(s)) =>
        try Value.StringV(String(java.util.Base64.getUrlDecoder.decode(s), "UTF-8"))
        catch case _: Throwable => Value.StringV("")
      case _ => throw InterpretError("base64UrlDecode(s: String)")
    }

    // ── WebAuthn / passkeys (stage 2a: challenge + store) ────────────
    // The verifier (attestation + assertion signature) lands in a
    // follow-up commit; these primitives let the handler issue a
    // challenge, consume it on the matching ceremony, and persist
    // (credentialId, publicKey) pairs.
    nativeP("webauthnChallenge") {
      case List(Value.StringV(uid)) =>
        Value.StringV(scalascript.server.WebAuthn.challenge(uid))
      case _ => throw InterpretError("webauthnChallenge(userId: String)")
    }
    nativeP("webauthnConsumeChallenge") {
      case List(Value.StringV(s)) =>
        scalascript.server.WebAuthn.consumeChallenge(s) match
          case Some(uid) => Value.OptionV(Some(Value.StringV(uid)))
          case None      => Value.OptionV(None)
      case _ => throw InterpretError("webauthnConsumeChallenge(challenge: String)")
    }
    nativeP("webauthnStorePut") {
      case List(Value.StringV(uid), Value.StringV(cid),
                Value.StringV(pk), Value.IntV(cnt)) =>
        scalascript.server.WebAuthn.storePut(uid,
          scalascript.server.WebAuthn.Credential(cid, pk, cnt))
        Value.UnitV
      case _ => throw InterpretError(
        "webauthnStorePut(userId, credentialId, publicKeyB64, signCount)")
    }
    nativeP("webauthnStoreGet") {
      case List(Value.StringV(uid)) =>
        Value.ListV(scalascript.server.WebAuthn.storeGet(uid).map { c =>
          Value.MapV(Map(
            Value.StringV("credentialId") -> Value.StringV(c.credentialId),
            Value.StringV("publicKey")    -> Value.StringV(c.publicKey),
            Value.StringV("signCount")    -> Value.IntV(c.signCount),
          ))
        })
      case _ => throw InterpretError("webauthnStoreGet(userId: String)")
    }
    nativeP("webauthnStoreFind") {
      case List(Value.StringV(uid), Value.StringV(cid)) =>
        scalascript.server.WebAuthn.storeFind(uid, cid) match
          case Some(c) =>
            Value.OptionV(Some(Value.MapV(Map(
              Value.StringV("credentialId") -> Value.StringV(c.credentialId),
              Value.StringV("publicKey")    -> Value.StringV(c.publicKey),
              Value.StringV("signCount")    -> Value.IntV(c.signCount),
            ))))
          case None => Value.OptionV(None)
      case _ => throw InterpretError("webauthnStoreFind(userId, credentialId)")
    }
    nativeP("webauthnUpdateSignCount") {
      case List(Value.StringV(uid), Value.StringV(cid), Value.IntV(cnt)) =>
        Value.BoolV(scalascript.server.WebAuthn.storeUpdateSignCount(uid, cid, cnt))
      case _ => throw InterpretError(
        "webauthnUpdateSignCount(userId, credentialId, newSignCount)")
    }
    // `webauthnVerifyRegistration(clientDataJSONb64, attestationObjectB64,
    //                             expectedOrigin)` — returns Some(Map)
    // with userId / credentialId / publicKey / signCount on success.
    // Currently accepts `none` attestation only (Apple, 1Password,
    // iOS); other formats land in a follow-up.
    // `webauthnVerifyAssertion(clientDataJSONb64, authenticatorDataB64,
    //   signatureB64, credentialIdB64, expectedOrigin)` — login side.
    // Verifies the ECDSA P-256 signature against the stored credential
    // and bumps signCount; returns Some(Map("userId", "signCount")) on
    // success.
    nativeP("webauthnVerifyAssertion") {
      case List(Value.StringV(cd), Value.StringV(ad),
                Value.StringV(sig), Value.StringV(cid),
                Value.StringV(origin)) =>
        scalascript.server.WebAuthn.verifyAssertion(cd, ad, sig, cid, origin) match
          case Some(a) =>
            Value.OptionV(Some(Value.MapV(Map(
              Value.StringV("userId")    -> Value.StringV(a.userId),
              Value.StringV("signCount") -> Value.IntV(a.signCount),
            ))))
          case None => Value.OptionV(None)
      case _ => throw InterpretError(
        "webauthnVerifyAssertion(clientDataJSONb64, authenticatorDataB64, " +
        "signatureB64, credentialIdB64, expectedOrigin)")
    }
    nativeP("webauthnVerifyRegistration") {
      case List(Value.StringV(cd), Value.StringV(att), Value.StringV(origin)) =>
        scalascript.server.WebAuthn.verifyRegistration(cd, att, origin) match
          case Some(r) =>
            Value.OptionV(Some(Value.MapV(Map(
              Value.StringV("userId")       -> Value.StringV(r.userId),
              Value.StringV("credentialId") -> Value.StringV(r.credentialId),
              Value.StringV("publicKey")    -> Value.StringV(r.publicKey),
              Value.StringV("signCount")    -> Value.IntV(r.signCount),
            ))))
          case None => Value.OptionV(None)
      case _ => throw InterpretError(
        "webauthnVerifyRegistration(clientDataJSONb64, attestationObjectB64, expectedOrigin)")
    }

    // ── Rate limiting ────────────────────────────────────────────────
    // `rateLimit(key, limit, windowSeconds)` — fixed-window counter.
    // Returns true if the call is allowed (and bumps the counter),
    // false if `limit` requests already happened within the window.
    nativeP("rateLimit") {
      case List(Value.StringV(key), Value.IntV(lim), Value.IntV(win)) =>
        Value.BoolV(scalascript.server.RateLimit.tryAcquire(key, lim, win))
      case _ => throw InterpretError("rateLimit(key: String, limit: Int, windowSeconds: Int)")
    }
    nativeP("rateLimitReset") {
      case List(Value.StringV(key)) =>
        scalascript.server.RateLimit.reset(key)
        Value.UnitV
      case _ => throw InterpretError("rateLimitReset(key: String)")
    }

    // ── TOTP / 2FA (RFC 6238) ────────────────────────────────────────
    // `totpSecret()` mints a base32 secret to share with the user's
    // authenticator app via QR (`totpUri`); `totpCode(secret)` is the
    // current 6-digit code; `totpValid(secret, code)` checks input
    // with ±1 step clock-skew tolerance.
    nativeP("totpSecret") { _ =>
      Value.StringV(scalascript.server.Totp.secret())
    }
    nativeP("totpUri") {
      case List(Value.StringV(s), Value.StringV(account)) =>
        Value.StringV(scalascript.server.Totp.uri(s, account))
      case List(Value.StringV(s), Value.StringV(account), Value.StringV(issuer)) =>
        Value.StringV(scalascript.server.Totp.uri(s, account, issuer))
      case _ => throw InterpretError("totpUri(secret, account[, issuer])")
    }
    nativeP("totpCode") {
      case List(Value.StringV(s)) =>
        Value.StringV(scalascript.server.Totp.code(s))
      case _ => throw InterpretError("totpCode(secret)")
    }
    nativeP("totpValid") {
      case List(Value.StringV(s), Value.StringV(code)) =>
        Value.BoolV(scalascript.server.Totp.valid(s, code))
      case List(Value.StringV(s), Value.StringV(code), Value.IntV(skew)) =>
        Value.BoolV(scalascript.server.Totp.valid(s, code, skew.toInt))
      case _ => throw InterpretError("totpValid(secret, code[, skew])")
    }

    // ── Password hashing (PBKDF2-HMAC-SHA256) ────────────────────────
    // `hashPassword(pass)` returns `pbkdf2$iter=N$<b64_salt>$<b64_hash>`;
    // `verifyPassword(pass, enc)` checks it with constant-time compare.
    // Default work factor is 200k iterations.
    nativeP("hashPassword") {
      case List(Value.StringV(pass)) =>
        Value.StringV(scalascript.server.Password.hash(pass))
      case List(Value.StringV(pass), Value.IntV(iter)) =>
        Value.StringV(scalascript.server.Password.hash(pass, iter.toInt))
      case _ => throw InterpretError("hashPassword(password[, iter])")
    }
    nativeP("verifyPassword") {
      case List(Value.StringV(pass), Value.StringV(encoded)) =>
        Value.BoolV(scalascript.server.Password.verify(pass, encoded))
      case _ => throw InterpretError("verifyPassword(password, encoded)")
    }

    // ── Cookie security config ───────────────────────────────────────
    // `cookieConfig(secure = true, sameSite = "Strict")` — production
    // hardening for the session cookie.  Defaults are HttpOnly +
    // SameSite=Lax, no Secure (so localhost http works in dev).
    nativeP("cookieConfig") {
      case List(Value.BoolV(secure)) =>
        scalascript.server.SessionCookie.setCookieConfig(secure, scalascript.server.SessionCookie.cookieSameSite)
        Value.UnitV
      case List(Value.BoolV(secure), Value.StringV(sameSite)) =>
        scalascript.server.SessionCookie.setCookieConfig(secure, sameSite)
        Value.UnitV
      case _ => throw InterpretError("cookieConfig(secure: Boolean[, sameSite: String])")
    }

    // ── Server-side session store opt-in ─────────────────────────────
    // After `useSessionStore()` is called, withSession/clearSession and
    // req.session indirect through scalascript.server.SessionStore (an
    // in-memory Map keyed by random SSIDs).  The cookie carries only
    // `{"_ssid": "..."}` instead of the full payload.
    nativeP("useSessionStore") {
      case Nil =>
        scalascript.server.SessionStore.useStore()
        Value.UnitV
      case List(Value.IntV(ttl)) =>
        scalascript.server.SessionStore.useStore(ttl)
        Value.UnitV
      case _ => throw InterpretError("useSessionStore() or useSessionStore(ttlSeconds: Int)")
    }

    // ── JWT helpers ──────────────────────────────────────────────────
    // `jwtSign(Map(...))` returns a compact HS256 JWT.
    // `jwtVerify(token)` returns `Some(claims)` if the signature checks
    // out and any `exp` claim is not in the past, otherwise `None`.
    nativeP("jwtSign") {
      case List(Value.MapV(m)) =>
        val claims = m.collect {
          case (Value.StringV(k), Value.StringV(v)) => k -> v
          case (Value.StringV(k), other)            => k -> Value.show(other)
        }.toMap
        Value.StringV(scalascript.server.Jwt.sign(claims))
      case _ => throw InterpretError("jwtSign(Map[String, String])")
    }
    nativeP("jwtVerify") {
      case List(Value.StringV(token)) =>
        scalascript.server.Jwt.verify(token) match
          case Some(claims) =>
            Value.OptionV(Some(Value.MapV(claims.map((k, v) => Value.StringV(k) -> Value.StringV(v)))))
          case None => Value.OptionV(None)
      case _ => throw InterpretError("jwtVerify(token: String)")
    }

    // ── JWT RS256 (asymmetric) ───────────────────────────────────────
    // Reads keys from `SSC_JWT_PRIVATE_KEY` (PKCS#8 RSA PEM) and
    // `SSC_JWT_PUBLIC_KEY` (X.509 SPKI PEM).  Use when verifier and
    // signer are different processes — multiple services validating
    // the same token without sharing a secret.
    nativeP("jwtSignRsa") {
      case List(Value.MapV(m)) =>
        val claims = m.collect {
          case (Value.StringV(k), Value.StringV(v)) => k -> v
          case (Value.StringV(k), other)            => k -> Value.show(other)
        }.toMap
        Value.StringV(scalascript.server.JwtRsa.sign(claims))
      case _ => throw InterpretError("jwtSignRsa(Map[String, String])")
    }
    nativeP("jwtVerifyRsa") {
      case List(Value.StringV(token)) =>
        scalascript.server.JwtRsa.verify(token) match
          case Some(claims) =>
            Value.OptionV(Some(Value.MapV(claims.map((k, v) => Value.StringV(k) -> Value.StringV(v)))))
          case None => Value.OptionV(None)
      case _ => throw InterpretError("jwtVerifyRsa(token: String)")
    }

    // ── OAuth2 helpers ────────────────────────────────────────────────
    // `oauthAuthorizeUrl(provider, clientId, redirectUri, state[, scope])`
    // — pure URL builder for the provider's `/authorize` endpoint.
    // `oauthExchangeCode(provider, code, clientId, clientSecret, redirectUri)`
    // — POSTs to the provider's `/token` endpoint and returns the parsed
    // response (`access_token` + provider-specific fields).
    nativeP("oauthAuthorizeUrl") { args =>
      def s(v: Value): String = v match
        case Value.StringV(x) => x
        case other => Value.show(other)
      args match
        case List(prov, cid, redir, state) =>
          Value.StringV(scalascript.server.OAuth.authorizeUrl(s(prov), s(cid), s(redir), s(state)))
        case List(prov, cid, redir, state, scope) =>
          Value.StringV(scalascript.server.OAuth.authorizeUrl(s(prov), s(cid), s(redir), s(state), s(scope)))
        case _ => throw InterpretError(
          "oauthAuthorizeUrl(provider, clientId, redirectUri, state[, scope])")
    }
    nativeP("oauthExchangeCode") {
      case List(Value.StringV(prov), Value.StringV(code),
                Value.StringV(cid),  Value.StringV(csec),
                Value.StringV(redir)) =>
        scalascript.server.OAuth.exchangeCode(prov, code, cid, csec, redir) match
          case Some(m) =>
            Value.OptionV(Some(Value.MapV(m.map((k, v) => Value.StringV(k) -> Value.StringV(v)))))
          case None => Value.OptionV(None)
      case _ => throw InterpretError(
        "oauthExchangeCode(provider, code, clientId, clientSecret, redirectUri)")
    }
    nativeP("oauthUserinfo") {
      case List(Value.StringV(prov), Value.StringV(token)) =>
        scalascript.server.OAuth.userinfo(prov, token) match
          case Some(m) =>
            Value.OptionV(Some(Value.MapV(m.map((k, v) => Value.StringV(k) -> Value.StringV(v)))))
          case None => Value.OptionV(None)
      case _ => throw InterpretError("oauthUserinfo(provider, accessToken)")
    }
    // `oauthRefreshToken(provider, refresh, clientId, clientSecret)` —
    // refresh-grant flow.  Returns the new token response or None.
    nativeP("oauthRefreshToken") {
      case List(Value.StringV(prov), Value.StringV(refresh),
                Value.StringV(cid),  Value.StringV(csec)) =>
        scalascript.server.OAuth.refreshToken(prov, refresh, cid, csec) match
          case Some(m) =>
            Value.OptionV(Some(Value.MapV(m.map((k, v) => Value.StringV(k) -> Value.StringV(v)))))
          case None => Value.OptionV(None)
      case _ => throw InterpretError(
        "oauthRefreshToken(provider, refreshToken, clientId, clientSecret)")
    }
    // `oauthRegisterProvider(name, Map("authorizeUrl" -> ..., ...))` —
    // adds (or overrides) a provider config at runtime.  Required keys:
    // `authorizeUrl`, `tokenUrl`.  Optional: `userinfoUrl`, `defaultScope`.
    nativeP("oauthRegisterProvider") {
      case List(Value.StringV(name), Value.MapV(m)) =>
        val cfg = m.collect { case (Value.StringV(k), Value.StringV(v)) => k -> v }.toMap
        scalascript.server.OAuth.registerProvider(name, cfg)
        Value.UnitV
      case _ => throw InterpretError(
        "oauthRegisterProvider(name, Map[String, String])")
    }

    // route / serve / stop now live in InterpreterIntrinsics (Stage 5+/B).

    // onWebSocket(path)(handler) — registers a WS upgrade handler.  The
    // handler receives a `WebSocket` value with `send` / `close` methods
    // and `onMessage` / `onClose` callback registration.  Path syntax is
    // the same as `route` (literal + `:name` captures).
    //
    // Two-arg form `onWebSocket(path, origins)(handler)` restricts the
    // upgrade to requests whose `Origin:` header is in the list — a
    // browser-side CSRF guard since cross-site `new WebSocket(...)` is
    // not blocked by the same-origin policy.
    nativeP("onWebSocket") {
      case List(Value.StringV(path)) =>
        Value.NativeFnV("onWebSocket.handler", Computation.pureFn {
          case List(handler) =>
            scalascript.server.WsRoutes.register(path, handler, this)
            Value.UnitV
          case _ => throw InterpretError("onWebSocket(path) { ws => … }")
        })
      case List(Value.StringV(path), Value.ListV(origins)) =>
        val origs = origins.collect { case Value.StringV(s) => s }
        Value.NativeFnV("onWebSocket.handler", Computation.pureFn {
          case List(handler) =>
            scalascript.server.WsRoutes.register(path, handler, this, origs)
            Value.UnitV
          case _ => throw InterpretError("onWebSocket(path, origins) { ws => … }")
        })
      case List(Value.StringV(path), Value.ListV(origins), Value.ListV(protocols)) =>
        val origs = origins.collect   { case Value.StringV(s) => s }
        val protos = protocols.collect { case Value.StringV(s) => s }
        Value.NativeFnV("onWebSocket.handler", Computation.pureFn {
          case List(handler) =>
            scalascript.server.WsRoutes.register(path, handler, this, origs, protos)
            Value.UnitV
          case _ => throw InterpretError("onWebSocket(path, origins, protocols) { ws => … }")
        })
      // Four-arg form adds a per-route active-connection cap.  0 (or
      // negative) means no limit; positive values refuse upgrades
      // past the cap with 503.  Composes with `setMaxWsConnections`
      // (process-wide): both caps must permit.
      case List(Value.StringV(path), Value.ListV(origins), Value.ListV(protocols), Value.IntV(maxConn)) =>
        val origs  = origins.collect   { case Value.StringV(s) => s }
        val protos = protocols.collect { case Value.StringV(s) => s }
        val cap    = if maxConn > Int.MaxValue.toLong || maxConn < 0 then 0 else maxConn.toInt
        Value.NativeFnV("onWebSocket.handler", Computation.pureFn {
          case List(handler) =>
            scalascript.server.WsRoutes.register(path, handler, this, origs, protos, cap)
            Value.UnitV
          case _ => throw InterpretError("onWebSocket(path, origins, protocols, maxConnections) { ws => … }")
        })
      // Five-arg form also caps inbound messages per second per
      // connection.  Overrun closes the offending client with code
      // 1008.  0 = unlimited.
      case List(Value.StringV(path), Value.ListV(origins), Value.ListV(protocols), Value.IntV(maxConn), Value.IntV(maxRate)) =>
        val origs  = origins.collect   { case Value.StringV(s) => s }
        val protos = protocols.collect { case Value.StringV(s) => s }
        val cap    = if maxConn > Int.MaxValue.toLong || maxConn < 0 then 0 else maxConn.toInt
        val rate   = if maxRate > Int.MaxValue.toLong || maxRate < 0 then 0 else maxRate.toInt
        Value.NativeFnV("onWebSocket.handler", Computation.pureFn {
          case List(handler) =>
            scalascript.server.WsRoutes.register(path, handler, this, origs, protos, cap, rate)
            Value.UnitV
          case _ => throw InterpretError("onWebSocket(path, origins, protocols, maxConnections, maxMessagesPerSec) { ws => … }")
        })
      case _ => throw InterpretError("onWebSocket(path[, origins[, protocols[, maxConnections[, maxMessagesPerSec]]]]) { ws => … }")
    }

    // onWebSocketAuth(path, authFn)(handler) — pre-upgrade auth hook.
    // `authFn` receives the Request (same shape REST handlers see) and
    // returns Option[Any]: None refuses the upgrade with HTTP 401;
    // Some(value) accepts and stashes `value` on the resulting
    // WebSocket as `ws.user`, so handler bodies don't have to re-parse
    // headers / claims / sessions.  Runs synchronously on the proxy
    // selector thread, so the hook MUST NOT mutate interpreter globals —
    // read-only decisions over headers / cookies / Origin only.
    nativeP("onWebSocketAuth") {
      case List(Value.StringV(path), authFn) =>
        Value.NativeFnV("onWebSocketAuth.handler", Computation.pureFn {
          case List(handler) =>
            scalascript.server.WsRoutes.register(
              path, handler, this,
              auth = Some(authFn)
            )
            Value.UnitV
          case _ => throw InterpretError("onWebSocketAuth(path, authFn) { ws => … }")
        })
      case _ => throw InterpretError("onWebSocketAuth(path, authFn) { ws => … }")
    }

    // metrics() — snapshot of process-wide counters as Map[String, Long].
    // Same keys across all backends; scraped by health-checks / log
    // shippers.  Reads are atomic; the map is built on demand so
    // calls are cheap but not free — don't hammer it on hot paths.
    nativeP("metrics") {
      case Nil =>
        val snap = scalascript.server.Metrics.snapshot()
        Value.MapV(snap.map((k, v) => Value.StringV(k) -> Value.IntV(v)))
      case _ => throw InterpretError("metrics() — no arguments")
    }

    // setMaxWsConnections(n) — process-wide cap on simultaneously-open
    // WebSocket sessions.  Upgrades past the cap are refused with 503.
    // Default = unlimited (Int.MaxValue).  Setting it lower than the
    // current active count is fine — existing sessions keep running,
    // new upgrades are refused until the count drops.
    nativeP("setMaxWsConnections") {
      case List(Value.IntV(n)) =>
        scalascript.server.WsConnection.maxActive.set(
          if n > Int.MaxValue.toLong || n < 0 then Int.MaxValue else n.toInt
        )
        Value.UnitV
      case _ => throw InterpretError("setMaxWsConnections(n)")
    }

    // WsRoom() — thread-safe registry of WebSocket clients with a
    // built-in `broadcast(msg)` helper.  Replaces the boilerplate
    // `var clients = List[WebSocket](); clients.foreach { c => c.send(msg) }`
    // pattern that every chat-style demo otherwise reinvents (and
    // gets the synchronisation wrong on JvmGen).
    nativeP("WsRoom") {
      case Nil =>
        val members = java.util.concurrent.CopyOnWriteArrayList[Value]()
        val add = Value.NativeFnV("WsRoom.add", Computation.pureFn {
          case List(ws) => members.add(ws); Value.UnitV
          case _ => throw InterpretError("room.add(ws)")
        })
        val remove = Value.NativeFnV("WsRoom.remove", Computation.pureFn {
          case List(ws) => members.remove(ws); Value.UnitV
          case _ => throw InterpretError("room.remove(ws)")
        })
        val broadcast = Value.NativeFnV("WsRoom.broadcast", Computation.pureFn {
          case List(Value.StringV(msg)) =>
            val it = members.iterator()
            while it.hasNext do
              it.next() match
                case Value.InstanceV("WebSocket", fields) =>
                  fields.get("send") match
                    case Some(f: Value.NativeFnV) =>
                      try Computation.run(f.f(List(Value.StringV(msg))))
                      catch case _: Throwable => () // dead client; will be reaped via onClose
                    case _ => ()
                case _ => ()
            Value.UnitV
          case _ => throw InterpretError("room.broadcast(msg)")
        })
        val size = Value.NativeFnV("WsRoom.size", Computation.pureFn {
          case Nil => Value.IntV(members.size.toLong)
          case _   => throw InterpretError("room.size()")
        })
        Value.InstanceV("WsRoom", Map(
          "add"       -> add,
          "remove"    -> remove,
          "broadcast" -> broadcast,
          "size"      -> size
        ))
      case _ => throw InterpretError("WsRoom()")
    }

    // ── Storage: built-in effect for key-value persistence ──────────
    //
    // Same Free-Monad shape as Async: each op produces a `Perform`
    // node; `runStorage(body)` is the JSON file-backed handler and
    // `runEphemeralStorage(body)` is the in-memory test handler.
    globals("Storage") = Value.InstanceV("Storage", Map(
      "get"    -> Value.NativeFnV("Storage.get",
        args => Perform("Storage", "get", args)),
      "put"    -> Value.NativeFnV("Storage.put",
        args => Perform("Storage", "put", args)),
      "remove" -> Value.NativeFnV("Storage.remove",
        args => Perform("Storage", "remove", args)),
      "has"    -> Value.NativeFnV("Storage.has",
        args => Perform("Storage", "has", args)),
      "keys"   -> Value.NativeFnV("Storage.keys",
        args => Perform("Storage", "keys", args)),
    ))

    // ── Async: built-in effect for async-style code ──────────────────
    //
    // Four operations — each produces a Perform node; `runAsync(body)`
    // is the default handler.  See `evalRunAsync` / `asyncDispatch`
    // below.  The model is single-threaded: thunks passed to
    // `async` / `parallel` execute immediately on the calling thread
    // (so output is deterministic and identical across all three
    // backends).  Real concurrency on the JVM is a handler-swap away.
    globals("Async") = Value.InstanceV("Async", Map(
      "delay"    -> Value.NativeFnV("Async.delay",
        args => Perform("Async", "delay", args)),
      "async"    -> Value.NativeFnV("Async.async",
        args => Perform("Async", "async", args)),
      "await"    -> Value.NativeFnV("Async.await",
        args => Perform("Async", "await", args)),
      "parallel" -> Value.NativeFnV("Async.parallel",
        args => Perform("Async", "parallel", args)),
    ))
    // `Future(v)` — wrap a value in a Future cell.  Used by handlers
    // to materialise the result of an `async` thunk; users normally
    // only construct Futures via `Async.async(...)`.
    globals("Future") = Value.NativeFnV("Future", {
      case List(v) => Pure(Value.InstanceV("Future", Map("value" -> v)))
      case _       => throw InterpretError("Future(value)")
    })

    // ── v1.4 standard-library effects ────────────────────────────────────
    //
    // Each object registers operation names that produce Perform nodes;
    // the matching `run*` special forms (in `eval`) drive the handlers.

    // Logger: info / warn / error / debug — four log levels.
    // Handlers: runLogger (text, to `out`), runLoggerJson, runLoggerToList.
    globals("Logger") = Value.InstanceV("Logger", Map(
      "info"  -> Value.NativeFnV("Logger.info",  args => Perform("Logger", "info",  args)),
      "warn"  -> Value.NativeFnV("Logger.warn",  args => Perform("Logger", "warn",  args)),
      "error" -> Value.NativeFnV("Logger.error", args => Perform("Logger", "error", args)),
      "debug" -> Value.NativeFnV("Logger.debug", args => Perform("Logger", "debug", args)),
    ))

    // Random: nextInt(n) / nextDouble() / uuid() / pick(xs).
    // Handlers: runRandom (ThreadLocalRandom), runRandomSeeded(seed)(body).
    globals("Random") = Value.InstanceV("Random", Map(
      "nextInt"    -> Value.NativeFnV("Random.nextInt",
        args => Perform("Random", "nextInt",    args)),
      "nextDouble" -> Value.NativeFnV("Random.nextDouble",
        args => Perform("Random", "nextDouble", args)),
      "uuid"       -> Value.NativeFnV("Random.uuid",
        args => Perform("Random", "uuid",       args)),
      "pick"       -> Value.NativeFnV("Random.pick",
        args => Perform("Random", "pick",       args)),
    ))

    // Clock: now() / nowIso() / sleep(ms).
    // Handlers: runClock (wall clock), runClockAt(t0)(body) (frozen time).
    globals("Clock") = Value.InstanceV("Clock", Map(
      "now"    -> Value.NativeFnV("Clock.now",
        args => Perform("Clock", "now",    args)),
      "nowIso" -> Value.NativeFnV("Clock.nowIso",
        args => Perform("Clock", "nowIso", args)),
      "sleep"  -> Value.NativeFnV("Clock.sleep",
        args => Perform("Clock", "sleep",  args)),
    ))

    // Env: get(key) / set(key, v) / required(key).
    // Handlers: runEnv (real process env), runEnvWith(map)(body) (fixture map).
    globals("Env") = Value.InstanceV("Env", Map(
      "get"      -> Value.NativeFnV("Env.get",
        args => Perform("Env", "get",      args)),
      "set"      -> Value.NativeFnV("Env.set",
        args => Perform("Env", "set",      args)),
      "required" -> Value.NativeFnV("Env.required",
        args => Perform("Env", "required", args)),
    ))

    // ── v1.6 Actors — Phase 1 natives (spawn / self / send) ────────────
    //
    // `runActors { … }` is the handler — it spawns the body as a root
    // actor and drives a cooperative scheduler until all actors are
    // either complete or deadlocked.
    //
    //   spawn(() => { … })   — new actor; returns Pid
    //   self                 — current actor's Pid
    //   pid ! msg            — fire-and-forget send (handled in `infix`)
    //   receive { case … }   — special form; AST-level case extraction
    //                          so the driver can dispatch matches itself
    globals("spawn") = Value.NativeFnV("spawn", {
      case List(thunk) => Perform("Actor", "spawn", List(thunk))
      case _           => throw InterpretError("spawn(behavior: () => Unit)")
    })
    globals("self") = Value.NativeFnV("self", {
      case Nil => Perform("Actor", "self", Nil)
      case _   => throw InterpretError("self takes no arguments")
    })
    globals("exit") = Value.NativeFnV("exit", {
      case List(pid @ Value.InstanceV("Pid", _), reason) =>
        Perform("Actor", "exit", List(pid, reason))
      case _ => throw InterpretError("exit(pid, reason)")
    })
    // Phase 2 — supervision primitives
    globals("link") = Value.NativeFnV("link", {
      case List(pid @ Value.InstanceV("Pid", _)) => Perform("Actor", "link", List(pid))
      case _ => throw InterpretError("link(pid): Unit")
    })
    globals("monitor") = Value.NativeFnV("monitor", {
      case List(pid @ Value.InstanceV("Pid", _)) => Perform("Actor", "monitor", List(pid))
      case _ => throw InterpretError("monitor(pid): MonitorRef")
    })
    globals("demonitor") = Value.NativeFnV("demonitor", {
      case List(ref @ Value.IntV(_)) => Perform("Actor", "demonitor", List(ref))
      case _ => throw InterpretError("demonitor(ref): Unit")
    })
    globals("trapExit") = Value.NativeFnV("trapExit", {
      case List(b @ Value.BoolV(_)) => Perform("Actor", "trapExit", List(b))
      case _ => throw InterpretError("trapExit(on: Boolean): Unit")
    })

    // ── Reactive primitives: Signal / computed / effect ────────────────
    globals("Signal") = Value.NativeFnV("Signal", {
      case List(init) => Pure(makeSignal(init))
      case _          => throw InterpretError("Signal(initial)")
    })
    globals("computed") = Value.NativeFnV("computed", {
      case List(thunk) => Pure(makeComputed(thunk))
      case _           => throw InterpretError("computed { ... }")
    })
    globals("effect") = Value.NativeFnV("effect", {
      case List(thunk) => makeEffect(thunk); Pure(Value.UnitV)
      case _           => throw InterpretError("effect { ... }")
    })

  /** Invoke an interpreter Value (closure or native fn) from outside —
   *  used by WebServer to call route handlers in response to HTTP requests. */
  def invoke(fn: Value, args: List[Value]): Value =
    Computation.run(callValue(fn, args, Map.empty))

  /** Install a native function under `name` into the global table.
   *  Used by `InterpreterBackend` to surface `Backend.intrinsics`
   *  entries (`NativeImpl` variant) as callable globals before
   *  user code runs.  Symmetric with `initBuiltins`'s `nativeP`,
   *  but public so external SPI consumers can register their own
   *  intrinsics without touching the interpreter source. */
  def registerNative(name: String, fn: List[Value] => Value): Unit =
    globals(name) = Value.NativeFnV(name, Computation.pureFn(fn))

  /** Install every `NativeImpl` entry from a `Backend.intrinsics` map
   *  as a callable global.  Bridges `Value` ↔ `Any` at the boundary
   *  and provides a `NativeContext` whose `out` points at this
   *  interpreter's `out` (so renderCommand's null-PrintStream
   *  wrapping is respected).  Other intrinsic variants (`InlineCode`
   *  / `RuntimeCall` / `HostCallback`) are no-ops here — they target
   *  compiled or out-of-process backends. */
  def installNativeIntrinsics(
      intrinsics: Map[scalascript.ir.QualifiedName, scalascript.backend.spi.IntrinsicImpl]
  ): Unit =
    val ctx = new scalascript.backend.spi.NativeContext:
      def out = Interpreter.this.out
      def err = System.err
      override def headless = Interpreter.this.headless
      override def registerRoute(method: String, path: String, handler: Any): Unit =
        scalascript.server.Routes.register(method, path, handler.asInstanceOf[Value], Interpreter.this)
      override def registerHealthDefaults(): Unit = Interpreter.this.registerHealthDefaults()
    intrinsics.foreach {
      case (qn, scalascript.backend.spi.NativeImpl(eval)) =>
        registerNative(qn.value, args =>
          val raw = args.map(unwrapValueAsAny)
          val ret = eval(ctx, raw)
          wrapAnyAsValue(ret)
        )
      case _ => ()
    }

  private def unwrapValueAsAny(v: Value): Any = v match
    case Value.IntV(n)    => n
    case Value.DoubleV(d) => d
    case Value.StringV(s) => s
    case Value.BoolV(b)   => b
    case Value.UnitV      => ()
    // Complex values: stringify via Value.show so intrinsics like
    // println produce identical output to the pre-migration path.
    case other            => Value.show(other)

  private def wrapAnyAsValue(a: Any): Value = a match
    case n: Long    => Value.IntV(n)
    case i: Int     => Value.IntV(i.toLong)
    case d: Double  => Value.DoubleV(d)
    case s: String  => Value.StringV(s)
    case b: Boolean => Value.BoolV(b)
    case ()         => Value.UnitV
    case v: Value   => v
    case other      => Value.StringV(other.toString)

  /** HTML-escape a string for safe interpolation in an html block. */
  private def htmlEscape(s: String): String =
    val sb = StringBuilder()
    s.foreach {
      case '&'  => sb ++= "&amp;"
      case '<'  => sb ++= "&lt;"
      case '>'  => sb ++= "&gt;"
      case '"'  => sb ++= "&quot;"
      case '\'' => sb ++= "&#39;"
      case c    => sb += c
    }
    sb.toString

  /** Escape `rendered` unless the underlying value is a `raw(...)` marker,
   *  in which case the marker's body is already trusted HTML. */
  private def htmlEscapeUnlessRaw(v: Value, rendered: String): String = v match
    case Value.InstanceV("_Raw", _) => rendered
    case _                          => htmlEscape(rendered)

  def exportedGlobals: Map[String, Value] = globals.toMap

  /** Extension methods registered by this interpreter, exposed so that
   *  parents can re-register them when importing a child module — the
   *  JS and JVM backends inline imports wholesale and pick these up
   *  for free, but the interpreter only copies the values named in
   *  the import binding list and would otherwise drop extensions. */
  def exportedExtensions: Map[(String, String), Value.FunV] = extensions.toMap

  // ─── Section / block execution ───────────────────────────────────

  private def runSection(section: Section): Unit =
    section.content.foreach {
      case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
        currentSource = cb.source
        // The parser falls back to wrapping the block in `{\n...\n}` when
        // scalameta's Source parser rejects the script (e.g. top-level
        // expressions). Detect that by tree shape and offset position lines
        // so error messages quote the user's line numbers, not the wrapper's.
        lineOffset = cb.tree match
          case Some(t) => ScalaNode.fold(t) {
            case _: Term.Block => 1
            case _             => 0
          }
          case None => 0
        cb.tree.foreach(execBlock)
      case cb: Content.CodeBlock if Lang.isStringBlock(cb.lang) =>
        runStringBlock(cb, section)
      case imp: Content.Import =>
        runImport(imp)
      case _ => ()
    }
    section.subsections.foreach(runSection)

  /** Evaluate an `html` or `css` block: ${expr} interpolations are resolved
   *  in the current globals scope, html-escaped where appropriate, and the
   *  resulting String is bound to `<sectionId>.<lang>` so the section's
   *  scalascript blocks can reach it as a normal value.  When a section name
   *  doesn't form a usable identifier, the binding is skipped — the block
   *  still exists for its side effect of being parsed for errors. */
  private def runStringBlock(cb: Content.CodeBlock, section: Section): Unit =
    val rendered = renderStringBlock(cb.source, cb.lang == Lang.Html)
    sectionIdent(section.heading.text).foreach { id =>
      val existing = globals.get(id) match
        case Some(Value.InstanceV(_, fields)) => fields
        case _                                => Map.empty[String, Value]
      val updated = existing + (cb.lang -> Value.StringV(rendered))
      globals(id) = Value.InstanceV(id, updated)
    }

  /** Turn a markdown heading into a Scala identifier.  Words (alphanumeric
   *  runs) become camelCase; the first word preserves its original casing
   *  so headings like `Page` bind to `Page` (object-style) and `my page`
   *  to `myPage` (val-style).  Returns None when there are no alphanumeric
   *  characters at all. */
  private def sectionIdent(text: String): Option[String] =
    val parts = text.split("[^A-Za-z0-9]+").filter(_.nonEmpty)
    if parts.isEmpty then None
    else
      val head = parts.head
      val tail = parts.tail.map(p => p.head.toUpper + p.tail)
      val raw  = head + tail.mkString
      Some(if raw.head.isDigit then "_" + raw else raw)

  /** Substitute `${expr}` segments in `src` with the evaluated value's
   *  `Value.show`.  When `escape` is true (html blocks) and the expression
   *  result isn't a `raw(...)` marker, the substituted value is HTML-escaped. */
  private def renderStringBlock(src: String, escape: Boolean): String =
    val sb  = StringBuilder()
    var i   = 0
    val len = src.length
    while i < len do
      if i + 1 < len && src.charAt(i) == '$' && src.charAt(i + 1) == '{' then
        val end = findClosingBrace(src, i + 2)
        if end < 0 then
          sb.append(src.substring(i)); i = len
        else
          val exprSrc = src.substring(i + 2, end)
          val parsed  = scala.meta.dialects.Scala3(exprSrc).parse[scala.meta.Term].get
          val v       = Computation.run(eval(parsed, globals.toMap))
          val shown   = Value.show(v)
          sb.append(if escape then htmlEscapeUnlessRaw(v, shown) else shown)
          i = end + 1
      else
        sb.append(src.charAt(i)); i += 1
    sb.toString

  /** Scan for the matching `}` from `from`, respecting balanced `{`/`}` so
   *  expressions like `${ if x then "{" else "}" }` parse correctly. */
  private def findClosingBrace(src: String, from: Int): Int =
    var depth = 1
    var i = from
    while i < src.length && depth > 0 do
      src.charAt(i) match
        case '{' => depth += 1
        case '}' => depth -= 1; if depth == 0 then return i
        case _   => ()
      i += 1
    -1

  private def runImport(imp: Content.Import): Unit =
    import scalascript.parser.Parser
    val base = baseDir.getOrElse(os.pwd)
    val resolvedPath =
      try scalascript.imports.ImportResolver.resolve(imp.path, base, moduleDeps)
      catch case e: Throwable => throw InterpretError(s"Import ${imp.path}: ${e.getMessage}")
    if !os.exists(resolvedPath) then
      throw InterpretError(s"Import not found: ${imp.path}")
    val childDir = resolvedPath / os.up
    val child    = Interpreter(out, Some(childDir))
    child.run(Parser.parse(os.read(resolvedPath)))
    val exported = child.exportedGlobals
    for binding <- imp.bindings do
      val sourceName = binding.name
      val targetName = binding.alias.getOrElse(binding.name)
      exported.get(sourceName) match
        case Some(v) => globals(targetName) = v
        case None    => throw InterpretError(s"'$sourceName' not found in ${imp.path}")
    // Extensions registered by the imported module become available in
    // the importer's scope.  Without this, an `extension` declared
    // inside an imported `given ... with` would never dispatch — the
    // child interpreter's `extensions` map is private to that child.
    extensions ++= child.exportedExtensions

  private def execBlockStats(stats: List[Stat]): Unit =
    stats.zipWithIndex.foreach { (s, i) =>
      execStat(s, globals, printResult = i == stats.length - 1)
    }

  private def execBlock(node: ScalaNode): Unit =
    ScalaNode.fold(node) {
      case Source(stats)     => execBlockStats(stats)
      case Term.Block(stats) => execBlockStats(stats)
      case t: Term           => Computation.run(eval(t, globals.toMap)); ()
      case other             => located(s"Expected Source/Block, got ${other.productPrefix}")
    }

  // ─── Statement execution ─────────────────────────────────────────

  private def execStat(stat: Stat, env: mutable.Map[String, Value], printResult: Boolean = false): Unit =
    trackPos(stat)
    stat match
    case Defn.Val(_, pats, _, rhs) =>
      val rhsVal = Computation.run(eval(rhs, env.toMap))
      pats match
        case List(Pat.Var(n)) => env(n.value) = rhsVal
        case List(pat) =>
          matchPat(pat, rhsVal, env.toMap) match
            case Some(patEnv) => patEnv.foreach { (k, v) => env(k) = v }
            case None         => located(s"Val pattern match failed")
        case _ => ()

    case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
      env(n.value) = Computation.run(eval(rhs, env.toMap))

    case d: Defn.Def if scalascript.transform.EffectAnalysis.isExternDef(d.body) =>
      // Stage 5+/A.6 (Б-1) — extern def stub is type-only; the
      // intrinsic table (`InterpreterIntrinsics`) provides the
      // real impl via `installNativeIntrinsics` at session start.
      // Skip the def so the runtime keeps the intrinsic binding
      // (otherwise our FunV would shadow it with a body that fails
      // when called — `__extern__` is undefined).
      ()

    case d: Defn.Def =>
      val paramVals = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).toList
      val params    = paramVals.map(_.name.value)
      val defaults  = paramVals.map(_.default)
      // See Term.Function above for why we drop only globals-shadowed keys.
      val capturedEnv = env.iterator.collect {
        case (k, v) if !globals.get(k).contains(v) => k -> v
      }.toMap
      val fn: Value.FunV = Value.FunV(params, d.body, capturedEnv, d.name.value, defaults)
      env(d.name.value) = fn
      if d.name.value == "main" && params.isEmpty then mainCalled = false

    case d: Defn.Object =>
      val objectName = d.name.value
      val members = mutable.Map.empty[String, Value]
      d.templ.body.stats.foreach {
        case dd: Defn.Def if isEffectOpDef(dd.body) =>
          val effName = objectName
          val opName  = dd.name.value
          // Effect op: a bare Perform request. The "rest of the computation" is
          // captured in an outer FlatMap by the bind chain that consumed it.
          members(opName) = Value.NativeFnV(s"$effName.$opName",
            args => Perform(effName, opName, args))
        case s => execStat(s, members)
      }
      env(objectName) = Value.InstanceV(objectName, members.toMap)

    case d: Defn.Class =>
      val params = d.ctor.paramClauses.flatMap(_.values).toList
      val paramNames    = params.map(_.name.value)
      val paramDefaults = params.map(_.default)
      val typeName = d.name.value
      val ctorEnv = env.toMap
      typeFieldOrder(typeName) = paramNames
      env(typeName) = Value.NativeFnV(typeName, args => {
        val filled = applyDefaults(paramNames, paramDefaults, args, ctorEnv)
        Pure(Value.InstanceV(typeName, paramNames.zip(filled).toMap))
      })
      // Methods defined inside the class body are stored in a separate
      // type-keyed registry; dispatch on an InstanceV consults it and re-binds
      // each method's closure with the instance's data fields so the body can
      // refer to them by name (`x`, `y` in `def distanceTo(other) = ...x...`).
      val classEnv = env.toMap
      val methodPairs: List[(String, Value.FunV)] = d.templ.body.stats.collect {
        case dd: Defn.Def =>
          val mparamVals = dd.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).toList
          val mparams    = mparamVals.map(_.name.value)
          val mdefaults  = mparamVals.map(_.default)
          (dd.name.value, Value.FunV(mparams, dd.body, classEnv, dd.name.value, mdefaults))
      }
      val methodDefs: Map[String, Value.FunV] = methodPairs.toMap
      if methodDefs.nonEmpty then typeMethods(typeName) = methodDefs

    case d: Defn.Enum =>
      val enumName = d.name.value
      val caseFields = mutable.Map.empty[String, Value]
      val ctorEnv = env.toMap
      d.templ.body.stats.foreach {
        case ec: Defn.EnumCase =>
          val caseName = ec.name.value
          val ecParams      = ec.ctor.paramClauses.flatMap(_.values).toList
          val paramNames    = ecParams.map(_.name.value)
          val paramDefaults = ecParams.map(_.default)
          if paramNames.nonEmpty then typeFieldOrder(caseName) = paramNames
          val v: Value =
            if paramNames.isEmpty then Value.InstanceV(caseName, Map.empty)
            else Value.NativeFnV(caseName, args => {
              val filled = applyDefaults(paramNames, paramDefaults, args, ctorEnv)
              Pure(Value.InstanceV(caseName, paramNames.zip(filled).toMap))
            })
          env(caseName) = v
          caseFields(caseName) = v
        case _ => ()
      }
      env(enumName) = Value.InstanceV(enumName, caseFields.toMap)

    case _: Defn.Trait => () // trait is erased — only the given instances matter

    case d: Defn.Given =>
      d.templ.inits.headOption.foreach { init =>
        val typeKeyOpt: Option[String] = init.tpe match
          case n: Type.Name  => Some(n.value)
          case ta: Type.Apply =>
            (ta.tpe match { case n: Type.Name => Some(n.value); case _ => None }).map { tc =>
              val arg = ta.argClause.values match
                case List(n: Type.Name) => n.value
                case _                  => "_"
              s"$tc[$arg]"
            }
          case _ => None
        typeKeyOpt.foreach { typeKey =>
          val members = mutable.Map.from(globals)
          d.templ.body.stats.foreach(s => execStat(s, members))
          val implNames = d.templ.body.stats.collect { case dd: Defn.Def => dd.name.value }.toSet
          val instance  = Value.InstanceV(typeKey, members.view.filterKeys(implNames.contains).toMap)
          env(typeKey) = instance
          val explicitName = d.name.value
          if explicitName.nonEmpty then env(explicitName) = instance
        }
      }

    case _: Decl.Def => () // abstract method declaration — no body

    case d: Defn.ExtensionGroup =>
      d.paramClauseGroup.foreach { pcg =>
        pcg.paramClauses.headOption.flatMap(_.values.headOption).foreach { recvParam =>
          val recvName = recvParam.name.value
          val recvTypeName = recvParam.decltpe match
            case Some(Type.Name(n))   => n
            case Some(ta: Type.Apply) => ta.tpe match { case Type.Name(n) => n; case _ => "Any" }
            case _                    => "Any"
          def registerDef(defn: Defn.Def): Unit =
            val mparamVals   = defn.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).toList
            val methodParams = mparamVals.map(_.name.value)
            // Receiver param has no default; method params keep theirs.
            val methodDefaults: List[Option[Term]] = None :: mparamVals.map(_.default)
            extensions((recvTypeName, defn.name.value)) =
              Value.FunV(recvName :: methodParams, defn.body, env.toMap, "", methodDefaults)
          d.body match
            case defn: Defn.Def    => registerDef(defn)
            case Term.Block(stats) => stats.foreach { case defn: Defn.Def => registerDef(defn); case _ => () }
            case _                 => ()
        }
      }

    case t: Term =>
      val result = Computation.run(eval(t, env.toMap))
      t match
        case Term.Apply.After_4_6_0(Term.Name("main"), _) => mainCalled = true
        case _                                => ()
      if printResult then autoOutput(result)
      else result: @annotation.nowarn("msg=Discarded")

    case _ => () // type aliases, imports, exports, etc.

  // ─── Expression evaluation ───────────────────────────────────────

  private def eval(term: Term, env: Env): Computation =
    trackPos(term)
    term match
    // Literals — interned by Lit identity so a hot loop reuses the same
    // `Pure(Value)` instance instead of reallocating on every eval. The
    // parser produces a stable AST; each `Lit.Int(1)` etc. is the same
    // Scala object across all visits.
    case lit: Lit =>
      val cached = litCache.get(lit)
      if cached != null then cached
      else
        val c = lit match
          case Lit.Int(v)     => Pure(Value.IntV(v.toLong))
          case Lit.Long(v)    => Pure(Value.IntV(v))
          case Lit.Double(v)  => Pure(Value.DoubleV(v.toString.toDouble))
          case Lit.Float(v)   => Pure(Value.DoubleV(v.toString.toDouble))
          case Lit.String(v)  => Pure(Value.StringV(v))
          case Lit.Boolean(v) => Pure(Value.BoolV(v))
          case Lit.Char(v)    => Pure(Value.CharV(v))
          case Lit.Unit()     => Pure(Value.UnitV)
          case Lit.Null()     => Pure(Value.NullV)
          case _              => Pure(Value.NullV)
        litCache.put(lit, c)
        c

    // Name lookup: local env first, then globals
    case Term.Name(name) =>
      Pure(env.getOrElse(name, globals.getOrElse(name, located(s"Undefined: $name"))))

    // Special form: handle(body) { case Eff.op(args, resume) => ... }
    case Term.Apply.After_4_6_0(
      Term.Apply.After_4_6_0(Term.Name("handle"), bodyArgClause),
      pfArgClause
    ) if bodyArgClause.values.size == 1 =>
      pfArgClause.values match
        case List(pf: Term.PartialFunction) =>
          evalHandle(bodyArgClause.values.head.asInstanceOf[Term], pf.cases, env)
        case _ => located("handle expects a partial function { case Eff.op(args, resume) => ... }")

    // Special form: runAsync(body) — default Async handler.  The body is
    // a by-name expression; we evaluate it lazily so its effects compose
    // into the Computation tree before the driver walks them.
    case Term.Apply.After_4_6_0(Term.Name("runAsync"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      asyncInterp(eval(bodyArgClause.values.head, env))

    // Special form: runAsyncParallel(body) — alternate Async handler
    // that executes thunks passed to `async` / `parallel` on real
    // JVM threads (ExecutorService + CompletableFuture).  `await`
    // blocks the calling thread on the future; `parallel` returns
    // results in declared order regardless of completion order so
    // value-deterministic code still produces byte-identical output.
    case Term.Apply.After_4_6_0(Term.Name("runAsyncParallel"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      asyncParInterp(eval(bodyArgClause.values.head, env))

    // Special forms: runStorage / runEphemeralStorage — Storage-effect
    // handlers.  The former hydrates from / persists to a JSON file
    // (path from the optional second arg or `SSC_STORAGE_PATH` env,
    // defaulting to `./ssc-storage.json`); the latter keeps the map
    // in-memory and discards it at scope exit.
    case Term.Apply.After_4_6_0(Term.Name("runStorage"), bodyArgClause)
        if bodyArgClause.values.size >= 1 =>
      val pathOpt = bodyArgClause.values.lift(1).map { p =>
        Computation.run(eval(p, env)) match
          case Value.StringV(s) => s
          case _                => storageDefaultPath
      }
      storageInterp(eval(bodyArgClause.values.head, env), Some(pathOpt.getOrElse(storageDefaultPath)))
    case Term.Apply.After_4_6_0(Term.Name("runEphemeralStorage"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      storageInterp(eval(bodyArgClause.values.head, env), None)

    // ── v1.4 Logger effect handlers ───────────────────────────────────────
    // runLogger { body }        — writes "[LEVEL] msg\n" to `out`
    // runLoggerJson { body }    — writes {"level":"…","msg":"…"} newline JSON
    // runLoggerToList { body }  — collects log lines; returns (result, list)
    case Term.Apply.After_4_6_0(Term.Name("runLogger"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      loggerRun(eval(bodyArgClause.values.head, env), "text", out)
    case Term.Apply.After_4_6_0(Term.Name("runLoggerJson"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      loggerRun(eval(bodyArgClause.values.head, env), "json", out)
    case Term.Apply.After_4_6_0(Term.Name("runLoggerToList"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      loggerToListRun(eval(bodyArgClause.values.head, env))

    // ── v1.4 Random effect handlers ───────────────────────────────────────
    // runRandom { body }            — ThreadLocalRandom
    // runRandomSeeded(seed) { body } — deterministic LCG, seed is Long
    case Term.Apply.After_4_6_0(Term.Name("runRandom"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      randomRun(eval(bodyArgClause.values.head, env), None)
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("runRandomSeeded"), seedClause),
        bodyClause)
        if seedClause.values.size == 1 && bodyClause.values.size == 1 =>
      val seed = Computation.run(eval(seedClause.values.head, env)) match
        case Value.IntV(n) => n
        case _             => throw InterpretError("runRandomSeeded(seed: Long) { body }")
      randomRun(eval(bodyClause.values.head, env), Some(seed))

    // ── v1.4 Clock effect handlers ────────────────────────────────────────
    // runClock { body }        — real wall clock; Clock.sleep → Thread.sleep
    // runClockAt(t0) { body }  — frozen at t0 ms epoch; sleep is a no-op
    case Term.Apply.After_4_6_0(Term.Name("runClock"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      clockRun(eval(bodyArgClause.values.head, env), None)
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("runClockAt"), t0Clause),
        bodyClause)
        if t0Clause.values.size == 1 && bodyClause.values.size == 1 =>
      val t0 = Computation.run(eval(t0Clause.values.head, env)) match
        case Value.IntV(n) => n
        case _             => throw InterpretError("runClockAt(t0: Long) { body }")
      clockRun(eval(bodyClause.values.head, env), Some(t0))

    // ── v1.4 Env effect handlers ──────────────────────────────────────────
    // runEnv { body }               — reads real process env; Env.set is local
    // runEnvWith(Map(...)) { body }  — fixture map; Env.set mutates overlay
    case Term.Apply.After_4_6_0(Term.Name("runEnv"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      envRun(eval(bodyArgClause.values.head, env), None)
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("runEnvWith"), mapClause),
        bodyClause)
        if mapClause.values.size == 1 && bodyClause.values.size == 1 =>
      val overlay = Computation.run(eval(mapClause.values.head, env)) match
        case Value.MapV(m) =>
          m.map { (k, v) => Value.show(k) -> Value.show(v) }.toMap
        case _ => throw InterpretError("runEnvWith(map: Map[String, String]) { body }")
      envRun(eval(bodyClause.values.head, env), Some(overlay))

    // ── v1.5 httpClient(baseUrl) { block } ───────────────────────────────
    // Double-apply special form: evaluate body directly (not as a thunk) so
    // any statements inside the block run with _httpBaseUrl set, then restore.
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("httpClient"), baseClause),
        bodyClause)
        if baseClause.values.size == 1 && bodyClause.values.size == 1 =>
      val baseComp = eval(baseClause.values.head, env)
      baseComp match
        case Pure(Value.StringV(base)) =>
          val prior = _httpBaseUrl.get()
          _httpBaseUrl.set(base.stripSuffix("/"))
          try eval(bodyClause.values.head, env)
          finally _httpBaseUrl.set(prior)
        case _ =>
          FlatMap(baseComp, {
            case Value.StringV(base) =>
              val prior = _httpBaseUrl.get()
              _httpBaseUrl.set(base.stripSuffix("/"))
              try eval(bodyClause.values.head, env)
              finally _httpBaseUrl.set(prior)
            case _ => throw InterpretError("httpClient(baseUrl: String) { body }")
          })

    // ── v1.6 Actors Phase 1 ────────────────────────────────────────────
    // `runActors { body }` installs an actor scheduler, spawns the body
    // as the root actor, and drives until quiescence.  The result is
    // whatever the root actor returned, or UnitV if it never did.
    case Term.Apply.After_4_6_0(Term.Name("runActors"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      actorInterp(eval(bodyArgClause.values.head, env))

    // v1.5 Tier 5 #20 — `validate { body }` runs `body` with an active
    // validation collector.  Returns `Right(bodyResult)` when the body
    // ran without any `require*` complaint, else `Left(Map[field,
    // reason])` capturing every problem in document order.  `require*`
    // inside the block returns a safe default on miss/invalid so the
    // body keeps running and accumulates errors in one pass.
    case Term.Apply.After_4_6_0(Term.Name("validate"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val buf = mutable.LinkedHashMap.empty[String, String]
      validationStack.push(buf)
      val bodyComp = try eval(bodyArgClause.values.head, env)
                     finally ()  // never pop in the try — see below
      bodyComp.flatMap { result =>
        validationStack.pop()
        if buf.nonEmpty then
          val errMap = Value.MapV(
            scala.collection.immutable.ListMap.from(
              buf.map { (k, v) => Value.StringV(k) -> Value.StringV(v) }
            )
          )
          Pure(Value.InstanceV("Left", Map("value" -> errMap)))
        else
          Pure(Value.InstanceV("Right", Map("value" -> result)))
      }

    // `receive { case … }` — special form so we can pull out the AST
    // cases at dispatch time.  Stashes (cases, env) and emits a Perform
    // whose payload is the integer token into that side table.
    case Term.Apply.After_4_6_0(Term.Name("receive"), pfArgClause)
        if pfArgClause.values.size == 1 =>
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val id = receiveSpecNext; receiveSpecNext += 1
          receiveSpecs(id) = (pf.cases, env)
          Perform("Actor", "receive", List(Value.IntV(id)))
        case _ =>
          located("receive expects a partial function { case msg => ... }")

    // `receive(timeout = N) { case … }` — same dispatch, but the
    // driver also tracks a deadline.  On timeout the receive value
    // is None; on match it's Some(body-value).
    case Term.Apply.After_4_6_0(
            Term.Apply.After_4_6_0(Term.Name("receive"), timeoutClause),
            pfArgClause)
        if pfArgClause.values.size == 1 && timeoutClause.values.size == 1 =>
      val timeoutTerm = timeoutClause.values.head match
        case Term.Assign(Term.Name("timeout"), v) => v
        case other: Term                          => other
      val timeoutMs = Computation.run(eval(timeoutTerm, env)) match
        case Value.IntV(n) => n
        case _ => throw InterpretError("receive timeout must be an Int (milliseconds)")
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val id = receiveSpecNext; receiveSpecNext += 1
          receiveSpecs(id) = (pf.cases, env)
          Perform("Actor", "receive_t", List(Value.IntV(id), Value.IntV(timeoutMs)))
        case _ =>
          located("receive expects a partial function { case msg => ... }")

    // Special forms: `computed { ... }` / `effect { ... }` — by-name
    // bodies wrapped as zero-arg closures so the reactive machinery can
    // re-run them when dependencies change.  Without the special form
    // the block would be evaluated eagerly at call time and the runtime
    // would get a plain value instead of a thunk.
    case Term.Apply.After_4_6_0(Term.Name("computed"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val body  = bodyArgClause.values.head.asInstanceOf[Term]
      val thunk = Value.FunV(Nil, body, env, "")
      Pure(makeComputed(thunk))
    case Term.Apply.After_4_6_0(Term.Name("effect"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val body  = bodyArgClause.values.head.asInstanceOf[Term]
      val thunk = Value.FunV(Nil, body, env, "")
      makeEffect(thunk)
      Pure(Value.UnitV)

    // Function application: detect obj.method(args) and dispatch directly.
    // All sub-terms are evaluated eagerly here; the FlatMap chain composes
    // already-built Computations so placeholderIdx and other eval-time state
    // is observed correctly.
    case app: Term.Apply =>
      app.fun match
        // ── .copy(field = value, ...) on an InstanceV ────────────────
        // Named args arrive as Term.Assign(Term.Name(field), rhs); we have
        // to intercept BEFORE the generic eval path, otherwise Term.Assign
        // would fall into the var-assignment case and mutate globals.
        case Term.Select(qual, Term.Name("copy")) =>
          evalCopy(qual, app.argClause.values, env)
        // ── Focus[T](_.a.b) / Focus(_.a.b) — Monocle-style lens ──────
        // Inspect the lambda body at AST level to extract a field-access
        // chain, then synthesise a Lens value with get / set / modify /
        // andThen. Done at AST level because the placeholder lambda is
        // otherwise erased to an opaque NativeFnV.
        case ta: Term.ApplyType if isFocusName(ta.fun) =>
          evalFocus(app.argClause.values)
        case Term.Name("Focus") =>
          evalFocus(app.argClause.values)
        case Term.Select(qual, Term.Name(method)) =>
          val qualC    = eval(qual, env)
          // Named args (Term.Assign) must evaluate only the RHS; the full
          // Term.Assign path at line 2338 treats them as var-assignments and
          // returns UnitV, destroying the actual value.
          val argComps = app.argClause.values.map {
            case Term.Assign(_, rhs) => eval(rhs, env)
            case other               => eval(other, env)
          }
          qualC match
            case Pure(qualV) if argComps.forall(_.isInstanceOf[Pure]) =>
              val argVs = argComps.map { case Pure(v) => v; case _ => Value.UnitV }
              dispatch(qualV, method, argVs, env)
            case _ =>
              FlatMap(qualC, qualV =>
                threadValues(argComps)(argVals => dispatch(qualV, method, argVals, env)))
        case fun =>
          val funC     = eval(fun, env)
          val argComps = app.argClause.values.map {
            case Term.Assign(_, rhs) => eval(rhs, env)
            case other               => eval(other, env)
          }
          funC match
            case Pure(fv) if argComps.forall(_.isInstanceOf[Pure]) =>
              val argVs = argComps.map { case Pure(v) => v; case _ => Value.UnitV }
              callValue(fv, argVs, env)
            case _ =>
              FlatMap(funC, fv =>
                threadValues(argComps)(argVals => callValue(fv, argVals, env)))

    // Infix operators: a op b
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
      val lhsC     = eval(lhs, env)
      val argComps = argClause.values.map(eval(_, env))
      // Fast path: both sides already pure (the typical case for hot
      // arithmetic like `n - 1`, `acc + n`, `n < 2`). Skip the FlatMap
      // chain and call infix directly.
      lhsC match
        case Pure(lhsV) if argComps.forall(_.isInstanceOf[Pure]) =>
          val argVs = argComps.map { case Pure(v) => v; case _ => Value.UnitV }
          infix(lhsV, op.value, argVs, env)
        case _ =>
          FlatMap(lhsC, lhsV =>
            threadValues(argComps)(argVs => infix(lhsV, op.value, argVs, env)))

    // Field / method selection: a.b  (no-arg call)
    case Term.Select(qual, name) =>
      val qualC = eval(qual, env)
      FlatMap(qualC, qualV => dispatch(qualV, name.value, Nil, env))

    // Block { stmts; expr }
    case Term.Block(stats) =>
      evalBlock(stats, env)

    // if/then/else
    case t: Term.If =>
      eval(t.cond, env) match
        // Fast path: cond evaluated eagerly to a pure BoolV (the typical
        // case after pure-value shortcuts kick in). Skip the FlatMap.
        case Pure(Value.BoolV(true))  => eval(t.thenp, env)
        case Pure(Value.BoolV(false)) => eval(t.elsep, env)
        case Pure(other)              => located(s"if condition must be Boolean, got ${Value.show(other)}")
        case condC                    => condC.flatMap {
          case Value.BoolV(true)  => eval(t.thenp, env)
          case Value.BoolV(false) => eval(t.elsep, env)
          case other              => located(s"if condition must be Boolean, got ${Value.show(other)}")
        }

    // String interpolation s"..." / f"..." / md"..." / html"..." / css"..."
    case Term.Interpolate(Term.Name(prefix), parts, args)
        if prefix == "s" || prefix == "f" || prefix == "md"
        || prefix == "html" || prefix == "css" =>
      evalArgs(args.map(_.asInstanceOf[Term]), env) { argVs =>
        val sb = StringBuilder()
        // f"..." semantics: the literal part following each ${} can begin
        // with a Java/printf-style format spec applied to the preceding arg.
        //   `f"${pi}%.2f"` →  parts = ["", "%.2f"], spec consumed off parts(1)
        val fmtRe = "^%[-+# 0,(]*\\d*(?:\\.\\d+)?[bBhHsScCdoxXeEfgGaAtT%]".r
        for i <- parts.indices do
          val partStr = parts(i).asInstanceOf[Lit.String].value
          // The first part precedes any interpolation; never consumes a spec.
          val (consumedSpec, partRest) =
            if i > 0 && prefix == "f" then
              fmtRe.findFirstIn(partStr) match
                case Some(spec) => (Some(spec), partStr.substring(spec.length))
                case None       => (None, partStr)
            else (None, partStr)
          // Emit the interpolated value for index (i - 1) before this part's text.
          if i > 0 then
            val v = argVs(i - 1)
            val rendered = consumedSpec match
              case Some(spec) =>
                val boxed: AnyRef = v match
                  case Value.IntV(n)    => java.lang.Long.valueOf(n)
                  case Value.DoubleV(d) => java.lang.Double.valueOf(d)
                  case Value.BoolV(b)   => java.lang.Boolean.valueOf(b)
                  case Value.CharV(c)   => java.lang.Character.valueOf(c)
                  case Value.StringV(s) => s
                  case other            => Value.show(other)
                try String.format(spec, boxed)
                catch case _: java.util.IllegalFormatException => Value.show(v)
              case None =>
                Value.show(v)
            sb ++= (if prefix == "html" then htmlEscapeUnlessRaw(v, rendered) else rendered)
          sb ++= partRest
        val raw = sb.toString
        Pure(Value.StringV(if prefix == "md" then stripIndent(raw) else raw))
      }

    // Anonymous function with _ placeholders: _.field, _ + 1, _ + _, etc.
    case t: Term.AnonymousFunction =>
      Pure(Value.NativeFnV("anon", args => {
        val saved = placeholderIdx
        placeholderIdx = 0
        val phEnv = env ++ args.zipWithIndex.map { (v, i) => s"_$$${i}" -> v }
        try eval(t.body, phEnv)
        finally placeholderIdx = saved
      }))

    // _ placeholder — numbered left-to-right via mutable counter
    case _: Term.Placeholder =>
      val i = placeholderIdx
      placeholderIdx += 1
      Pure(env.getOrElse(s"_$$${i}", located("Unexpected _")))

    // Lambda  x => body  or  (x, y) => body
    case Term.Function.After_4_6_0(paramClause, body) =>
      // Drop only the keys whose env value still matches the live globals —
      // those are top-level bindings that the lambda should re-read from
      // `globals` at call time (so a `var` reassigned later is visible).
      // Genuine closure captures (outer def params, block-local vals) have
      // a different value in env than in globals and must be kept; otherwise
      // a param like `a` for `def adder(a)` is stripped because globals also
      // hold the `<a>` HTML tag under that name, and the inner `b => a + b`
      // would resolve `a` to the tag instead of the captured Int.
      val closure = env.filter { case (k, v) => !globals.get(k).contains(v) }
      Pure(Value.FunV(paramClause.values.map(_.name.value), body, closure))

    // Partial function  { case pat => body; ... }  — e.g. xs.map { case (k, v) => ... }
    case Term.PartialFunction(cases) =>
      Pure(Value.NativeFnV("partial", args => {
        val arg = args match
          case List(v) => v
          case vs      => Value.TupleV(vs)
        cases.iterator
          .flatMap { c =>
            matchPat(c.pat, arg, env).flatMap { patEnv =>
              val guardOk = c.cond.forall(g => Computation.run(eval(g, patEnv)) match
                case Value.BoolV(b) => b
                case _              => false)
              if guardOk then Some(eval(c.body, patEnv)) else None
            }
          }
          .nextOption()
          .getOrElse(located(s"Partial function match failure: ${Value.show(arg)}"))
      }))

    // Match / pattern match
    case t: Term.Match =>
      eval(t.expr, env).flatMap { scrutV =>
        t.casesBlock.cases.iterator
          .flatMap { c =>
            matchPat(c.pat, scrutV, env).flatMap { patEnv =>
              val guardOk = c.cond.forall(g => Computation.run(eval(g, patEnv)) match
                case Value.BoolV(b) => b
                case _              => false)
              if guardOk then Some(eval(c.body, patEnv)) else None
            }
          }
          .nextOption()
          .getOrElse(located(s"Match failure: ${Value.show(scrutV)}"))
      }

    // Tuple  (a, b, ...)
    case Term.Tuple(elems) =>
      evalArgs(elems, env)(vs => Pure(Value.TupleV(vs)))

    // new ClassName(args)
    case Term.New(Init.After_4_6_0(tpe, _, argClauses)) =>
      val typeName = tpe match { case Type.Name(n) => n; case _ => "?" }
      val argTerms = argClauses.toList.flatMap(_.values)
      evalArgs(argTerms, env) { argVals =>
        env.getOrElse(typeName, globals.getOrElse(typeName,
          located(s"Unknown constructor: $typeName"))) match
            case c: Value.NativeFnV => c.f(argVals)
            case f: Value.FunV      => callFun(f, argVals)
            case _ => located(s"$typeName is not a constructor")
      }

    // for x <- xs yield f(x)
    case t: Term.ForYield =>
      evalForYield(t.enumsBlock.enums, t.body, env)

    // for x <- xs do f(x)
    case t: Term.For =>
      evalForDo(t.enumsBlock.enums, t.body, env, Map.empty).map(_ => Value.UnitV)

    // while cond do body  — refresh env from globals each iteration so mutations are visible
    case t: Term.While =>
      def loop: Computation =
        val freshEnv = env.map { (k, v) => k -> globals.getOrElse(k, v) }
        eval(t.expr, freshEnv).flatMap {
          case Value.BoolV(true) => eval(t.body, freshEnv).flatMap(_ => loop)
          case _                 => Pure(Value.UnitV)
        }
      loop

    // return expr  (non-local via exception)
    case Term.Return(expr) =>
      eval(expr, env).flatMap(v => throw ReturnSignal(v))

    // var/field assignment
    case Term.Assign(Term.Name(name), rhs) =>
      eval(rhs, env).flatMap { v => globals(name) = v; Pure(Value.UnitV) }

    // summon[TC[T]] — retrieve a given instance from the table
    case t: Term.ApplyType =>
      (t.fun, t.argClause.values) match
        case (Term.Name("summon"), List(typeArg)) =>
          val key = typeArg match
            case n: Type.Name  => n.value
            case ta: Type.Apply =>
              val tc  = ta.tpe match { case n: Type.Name => n.value; case _ => located("summon: bad type") }
              val arg = ta.argClause.values match { case List(n: Type.Name) => n.value; case _ => "_" }
              s"$tc[$arg]"
            case _ => located("summon: unsupported type")
          Pure(env.getOrElse(key, globals.getOrElse(key, located(s"No given for $key"))))
        // Prism[Outer, Variant] — focus on a single sum-type variant.
        case (Term.Name("Prism"), List(_, variantType)) =>
          val variantName = variantType match
            case n: Type.Name => n.value
            case _            => located("Prism[Outer, Variant]: Variant must be a simple type name")
          Pure(buildPrism(variantName))
        case _ => eval(t.fun, env)  // other type applications — erase type args

    // Prefix unary operators: `!x`, `-x`, `+x`, `~x`.
    case t: Term.ApplyUnary =>
      eval(t.arg, env).flatMap { v =>
        (t.op.value, v) match
          case ("!", Value.BoolV(b))   => Pure(Value.BoolV(!b))
          case ("-", Value.IntV(n))    => Pure(Value.IntV(-n))
          case ("-", Value.DoubleV(d)) => Pure(Value.DoubleV(-d))
          case ("+", n: Value.IntV)    => Pure(n)
          case ("+", d: Value.DoubleV) => Pure(d)
          case ("~", Value.IntV(n))    => Pure(Value.IntV(~n))
          case (op, other)             => located(s"Cannot apply unary $op to ${Value.show(other)}")
      }

    case other => located(s"Cannot eval: ${other.productPrefix}")

  // ─── Lenses / Focus / .copy ──────────────────────────────────────

  /** A single step in an optic path. `FieldStep` is a case-class field
   *  selection; `SomeStep` traverses into an Option's value (turning the
   *  surrounding optic into an Optional); `EachStep` traverses into every
   *  element of a List (turning the surrounding optic into a Traversal). */
  private enum PathStep:
    case FieldStep(name: String)
    case SomeStep
    case EachStep
    /** v0.9 — pointwise access into a `List[A]`.  Returns `None` on
     *  out-of-bounds reads; sets are no-ops out of bounds. */
    case IndexStep(i: Int)
    /** v0.9 — pointwise access into a `Map[K, V]`.  Returns `None`
     *  for absent keys; sets insert / overwrite. */
    case AtKey(key: Value)

  /** `recv.copy(field = value, ...)` — produce a new InstanceV with the
   *  named fields overridden. Mixing named and positional args is allowed:
   *  positionals fill the parameter list left-to-right, then named overrides
   *  apply on top. Field order comes from `typeFieldOrder`, populated when
   *  the case class / enum case was registered. */
  private def evalCopy(qual: Term, args: List[Term], env: Env): Computation =
    val qualC = eval(qual, env)
    // Walk args once, preserving order. Positional Computations are kept
    // in order; named ones are tagged with their field name. RHS is
    // evaluated BEFORE eval would see Term.Assign (which is also the
    // var-assignment node), so the name doesn't leak into globals.
    // Scala 3 disallows positional after named — mirror that here.
    val firstNamed = args.indexWhere {
      case Term.Assign(_: Term.Name, _) => true
      case _                            => false
    }
    if firstNamed >= 0 && args.drop(firstNamed).exists {
      case Term.Assign(_: Term.Name, _) => false
      case _                            => true
    } then located(".copy: positional argument after named argument is not allowed")
    val tagged: List[(Option[String], Computation)] = args.map {
      case Term.Assign(Term.Name(field), rhs) => (Some(field), eval(rhs, env))
      case other                              => (None,        eval(other, env))
    }
    val (tags, comps) = tagged.unzip
    FlatMap(qualC, qualV =>
      threadValues(comps) { newVals =>
        qualV match
          case Value.InstanceV(typeName, fields) =>
            val order = typeFieldOrder.getOrElse(typeName, fields.keys.toList)
            val named = tags.zip(newVals).collect { case (Some(n), v) => n -> v }.toMap
            val positionals = tags.zip(newVals).collect { case (None, v) => v }
            val firstFreeFields = order.filterNot(named.contains).take(positionals.length)
            if positionals.length > firstFreeFields.length then
              located(s".copy: $typeName takes ${order.length} fields, got ${tags.length}")
            else
              val unknownNamed = named.keySet -- fields.keySet
              if unknownNamed.nonEmpty then
                located(s".copy: unknown field(s) on $typeName: ${unknownNamed.mkString(", ")}")
              else
                val fromPositions = firstFreeFields.zip(positionals).toMap
                Pure(Value.InstanceV(typeName, fields ++ fromPositions ++ named))
          case other =>
            located(s".copy: not a case-class instance: ${Value.show(other)}")
      })

  private def isFocusName(t: Term): Boolean = t match
    case Term.Name("Focus") => true
    case _                  => false

  /** `Focus[T](_.a.b)` or `Focus(x => x.a.b)` — synthesise a Lens or
   *  Optional by walking the lambda body. A `.some` step in the path
   *  (e.g. `_.maybe.some.field`) makes the result an Optional whose
   *  `set` is a no-op when the Option is empty. */
  private def evalFocus(args: List[Term]): Computation =
    args match
      case List(lambda) =>
        val stepsOpt: Option[List[PathStep]] = lambda match
          case Term.AnonymousFunction(body) =>
            extractPathSteps(body, isBase = _.isInstanceOf[Term.Placeholder])
          case Term.Function.After_4_6_0(paramClause, body) =>
            paramClause.values.headOption.map(_.name.value) match
              case Some(p) =>
                extractPathSteps(body, isBase = {
                  case Term.Name(n) => n == p
                  case _            => false
                })
              case None => None
          case _ => None
        stepsOpt match
          case Some(steps) if steps.nonEmpty =>
            val hasIndexOrAt = steps.exists {
              case _: PathStep.IndexStep | _: PathStep.AtKey => true
              case _                                         => false
            }
            if steps.contains(PathStep.EachStep)                 then Pure(buildPathTraversal(steps))
            else if steps.contains(PathStep.SomeStep) || hasIndexOrAt then Pure(buildPathOptional(steps))
            else Pure(buildPathLens(steps.collect { case PathStep.FieldStep(n) => n }))
          case _ => located("Focus: expected a field-access lambda like _.field.subfield")
      case _ => located("Focus expects exactly one lambda argument")

  /** Walk a Term.Select chain to extract the field-access path. `.some`
   *  selects translate to `SomeStep`, `.each` to `EachStep`; all other
   *  names become `FieldStep`. */
  private def extractPathSteps(body: Term, isBase: Term => Boolean): Option[List[PathStep]] =
    def litToValue(lit: Lit): Option[Value] = lit match
      case Lit.Int(v)     => Some(Value.IntV(v.toLong))
      case Lit.Long(v)    => Some(Value.IntV(v))
      case Lit.String(v)  => Some(Value.StringV(v))
      case Lit.Boolean(v) => Some(Value.BoolV(v))
      case Lit.Double(v)  => Some(Value.DoubleV(v.toDouble))
      case _              => None
    def loop(t: Term, acc: List[PathStep]): Option[List[PathStep]] = t match
      case Term.Select(qual, Term.Name("some")) =>
        loop(qual, PathStep.SomeStep :: acc)
      case Term.Select(qual, Term.Name("each")) =>
        loop(qual, PathStep.EachStep :: acc)
      // v0.9 pointwise — `_.users.index(3)` / `_.byId.at("u-42")`.
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("index")), argClause)
          if argClause.values.size == 1 =>
        argClause.values.head match
          case Lit.Int(i)  => loop(qual, PathStep.IndexStep(i) :: acc)
          case Lit.Long(i) => loop(qual, PathStep.IndexStep(i.toInt) :: acc)
          case _           => None
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("at")), argClause)
          if argClause.values.size == 1 =>
        argClause.values.head match
          case lit: Lit => litToValue(lit).flatMap(v => loop(qual, PathStep.AtKey(v) :: acc))
          case _        => None
      case Term.Select(qual, name) =>
        loop(qual, PathStep.FieldStep(name.value) :: acc)
      case other if isBase(other) => Some(acc)
      case _                      => None
    loop(body, Nil)

  /** Walk a path into a nested InstanceV, returning the final value. */
  private def lensGet(target: Value, path: List[String]): Value = path match
    case Nil          => target
    case head :: rest => target match
      case Value.InstanceV(_, fields) =>
        fields.get(head) match
          case Some(v) => lensGet(v, rest)
          case None    => throw InterpretError(s"Lens.get: no field '$head' on ${Value.show(target)}")
      case _ => throw InterpretError(s"Lens.get: not an instance at '$head'")

  /** Functional update of a nested field path. */
  private def lensSet(target: Value, path: List[String], newVal: Value): Value = path match
    case Nil          => newVal
    case head :: rest => target match
      case Value.InstanceV(typeName, fields) =>
        val child = fields.getOrElse(head, throw InterpretError(s"Lens.set: no field '$head'"))
        Value.InstanceV(typeName, fields.updated(head, lensSet(child, rest, newVal)))
      case _ => throw InterpretError(s"Lens.set: not an instance at '$head'")

  /** Build a Lens for a static field path. Exposed as an InstanceV so
   *  `.get` / `.set` / `.modify` / `.andThen` dispatch via the usual
   *  field-access machinery. */
  private def buildPathLens(path: List[String]): Value.InstanceV =
    val getFn = Value.NativeFnV("Lens.get", {
      case List(s) => Pure(lensGet(s, path))
      case _       => throw InterpretError("Lens.get(s)")
    })
    val setFn = Value.NativeFnV("Lens.set", {
      case List(s, v) => Pure(lensSet(s, path, v))
      case _          => throw InterpretError("Lens.set(s, v)")
    })
    val modifyFn = Value.NativeFnV("Lens.modify", {
      case List(s, f) =>
        val old = lensGet(s, path)
        callValue(f, List(old), Map.empty).map(newV => lensSet(s, path, newV))
      case _ => throw InterpretError("Lens.modify(s, f)")
    })
    val andThenFn = Value.NativeFnV("Lens.andThen", {
      case List(Value.InstanceV("Lens", other)) =>
        // If `other` is also a path-lens we can compose paths directly so
        // the result keeps the simple representation. Otherwise fall back
        // to functional composition (always works, costs a couple of
        // extra calls per get/set).
        other.get("_path") match
          case Some(Value.ListV(items)) =>
            val otherPath = items.collect { case Value.StringV(s) => s }
            Pure(buildPathLens(path ++ otherPath))
          case _ =>
            Pure(composedLens(buildPathLens(path), Value.InstanceV("Lens", other)))
      case List(Value.InstanceV("Optional", other)) =>
        // Lens.andThen(Optional) → Optional. Lift the field path to steps
        // and concatenate.
        stepsFromFields(other) match
          case Some(innerSteps) =>
            val outerSteps = path.map(PathStep.FieldStep(_))
            Pure(buildPathOptional(outerSteps ++ innerSteps))
          case None => throw InterpretError("Lens.andThen(Optional): malformed Optional")
      case List(Value.InstanceV("Traversal", other)) =>
        // Lens.andThen(Traversal) → Traversal.
        stepsFromFields(other) match
          case Some(innerSteps) =>
            val outerSteps = path.map(PathStep.FieldStep(_))
            Pure(buildPathTraversal(outerSteps ++ innerSteps))
          case None => throw InterpretError("Lens.andThen(Traversal): malformed Traversal")
      case List(other) =>
        throw InterpretError(s"Lens.andThen expects a Lens / Optional / Traversal, got ${Value.show(other)}")
      case _ => throw InterpretError("Lens.andThen(other)")
    })
    Value.InstanceV("Lens", Map(
      "get"     -> getFn,
      "set"     -> setFn,
      "modify"  -> modifyFn,
      "andThen" -> andThenFn,
      "_path"   -> Value.ListV(path.map(Value.StringV.apply))
    ))

  /** `Prism[Outer, Variant]` — sum-type optic. `getOption` returns `Some(s)` if
   *  the value is the named variant, else `None`. `set` / `modify` are no-ops
   *  when the variant doesn't match. */
  private def buildPrism(variantName: String): Value.InstanceV =
    val getOptionFn = Value.NativeFnV("Prism.getOption", {
      case List(s) => s match
        case Value.InstanceV(t, _) if t == variantName => Pure(Value.OptionV(Some(s)))
        case _                                          => Pure(Value.OptionV(None))
      case _ => throw InterpretError("Prism.getOption(s)")
    })
    val reverseGetFn = Value.NativeFnV("Prism.reverseGet", {
      case List(v) => Pure(v)
      case _       => throw InterpretError("Prism.reverseGet(v)")
    })
    val setFn = Value.NativeFnV("Prism.set", {
      case List(s, v) => s match
        case Value.InstanceV(t, _) if t == variantName => Pure(v)
        case _                                          => Pure(s)
      case _ => throw InterpretError("Prism.set(s, v)")
    })
    val modifyFn = Value.NativeFnV("Prism.modify", {
      case List(s, f) => s match
        case Value.InstanceV(t, _) if t == variantName => callValue(f, List(s), Map.empty)
        case _                                          => Pure(s)
      case _ => throw InterpretError("Prism.modify(s, f)")
    })
    val andThenFn = Value.NativeFnV("Prism.andThen", {
      case List(other: Value.InstanceV) if other.typeName == "Prism" =>
        other.fields.get("_variant") match
          case Some(Value.StringV(inner)) => Pure(buildPrismChain(variantName, inner))
          case _ => throw InterpretError("Prism.andThen: malformed Prism")
      case List(_) =>
        throw InterpretError("Prism.andThen(other): only Prism-Prism composition supported in this stage")
      case _ => throw InterpretError("Prism.andThen(other)")
    })
    Value.InstanceV("Prism", Map(
      "getOption"  -> getOptionFn,
      "reverseGet" -> reverseGetFn,
      "set"        -> setFn,
      "modify"     -> modifyFn,
      "andThen"    -> andThenFn,
      "_variant"   -> Value.StringV(variantName)
    ))

  /** Compose two prisms: `Prism[A, B].andThen(Prism[B, C]): Prism[A, C]`.
   *  Match succeeds only when the value is the *inner* variant (a B that is
   *  also a C in our dynamic type model means typeName == innerVariant). */
  private def buildPrismChain(outerVariant: String, innerVariant: String): Value.InstanceV =
    // For our flat enum model, both checks collapse to "is the innermost variant".
    // We keep `outerVariant` only to preserve the documented shape.
    val _ = outerVariant
    buildPrism(innerVariant)

  /** Compose two arbitrary lenses by chaining their get / set / modify. */
  private def composedLens(a: Value.InstanceV, b: Value.InstanceV): Value.InstanceV =
    val aGet = a.fields("get");    val bGet = b.fields("get")
    val aSet = a.fields("set");    val bSet = b.fields("set")
    val aMod = a.fields("modify"); val bMod = b.fields("modify")
    val getFn = Value.NativeFnV("Lens.get", {
      case List(s) =>
        callValue(aGet, List(s), Map.empty).flatMap(x => callValue(bGet, List(x), Map.empty))
      case _ => throw InterpretError("Lens.get(s)")
    })
    val setFn = Value.NativeFnV("Lens.set", {
      case List(s, v) =>
        callValue(aGet, List(s), Map.empty).flatMap { x =>
          callValue(bSet, List(x, v), Map.empty).flatMap { x2 =>
            callValue(aSet, List(s, x2), Map.empty)
          }
        }
      case _ => throw InterpretError("Lens.set(s, v)")
    })
    val modifyFn = Value.NativeFnV("Lens.modify", {
      case List(s, f) =>
        val inner = Value.NativeFnV("inner", {
          case List(x) => callValue(bMod, List(x, f), Map.empty)
          case _       => throw InterpretError("modify inner")
        })
        callValue(aMod, List(s, inner), Map.empty)
      case _ => throw InterpretError("Lens.modify(s, f)")
    })
    val andThenFn = Value.NativeFnV("Lens.andThen", {
      case List(other: Value.InstanceV) if other.typeName == "Lens" =>
        Pure(composedLens(Value.InstanceV("Lens", Map(
          "get" -> getFn, "set" -> setFn, "modify" -> modifyFn,
          "andThen" -> Value.NativeFnV("Lens.andThen.recur", _ => throw InterpretError("recur")))
        ), other))
      case _ => throw InterpretError("Lens.andThen(other)")
    })
    Value.InstanceV("Lens", Map(
      "get" -> getFn, "set" -> setFn, "modify" -> modifyFn,
      "andThen" -> andThenFn
    ))

  // ─── Optional (partial optic) ────────────────────────────────────

  /** Walk `steps` into `target`. Returns `None` as soon as a `SomeStep`
   *  hits a `None`, or a `FieldStep` hits a non-instance. */
  private def opticGetOption(target: Value, steps: List[PathStep]): Option[Value] = steps match
    case Nil => Some(target)
    case PathStep.FieldStep(n) :: rest => target match
      case Value.InstanceV(_, fields) => fields.get(n).flatMap(v => opticGetOption(v, rest))
      case _                          => None
    case PathStep.SomeStep :: rest => target match
      case Value.OptionV(Some(inner)) => opticGetOption(inner, rest)
      case _                          => None
    case PathStep.IndexStep(i) :: rest => target match
      case Value.ListV(items) if i >= 0 && i < items.length =>
        opticGetOption(items(i), rest)
      case _ => None
    case PathStep.AtKey(k) :: rest => target match
      case Value.MapV(m) => m.get(k).flatMap(v => opticGetOption(v, rest))
      case _             => None
    case PathStep.EachStep :: _ =>
      None  // Traversal steps are handled by opticGetAll, not here.

  /** Functional set along `steps`. Returns the rebuilt value, or the
   *  original `target` unchanged when the path doesn't fully resolve
   *  (mirrors Optional.set semantics: no-op on miss). */
  private def opticSet(target: Value, steps: List[PathStep], newVal: Value): Value = steps match
    case Nil => newVal
    case PathStep.FieldStep(n) :: rest => target match
      case Value.InstanceV(typeName, fields) =>
        fields.get(n) match
          case Some(child) =>
            Value.InstanceV(typeName, fields.updated(n, opticSet(child, rest, newVal)))
          case None => target
      case _ => target
    case PathStep.SomeStep :: rest => target match
      case Value.OptionV(Some(inner)) =>
        Value.OptionV(Some(opticSet(inner, rest, newVal)))
      case other => other
    case PathStep.IndexStep(i) :: rest => target match
      case Value.ListV(items) if i >= 0 && i < items.length =>
        Value.ListV(items.updated(i, opticSet(items(i), rest, newVal)))
      case other => other
    case PathStep.AtKey(k) :: rest => target match
      case Value.MapV(m) =>
        m.get(k) match
          case Some(child) => Value.MapV(m.updated(k, opticSet(child, rest, newVal)))
          // Absent key + remaining steps: no parent to write into.
          case None if rest.isEmpty => Value.MapV(m.updated(k, newVal))
          case None                  => target
      case other => other
    case PathStep.EachStep :: _ =>
      target  // Traversal steps are handled by opticModifyAll, not here.

  /** Build an Optional for a path that contains at least one `SomeStep`.
   *  `getOption` returns `None` if any Option in the chain is empty;
   *  `set` / `modify` are no-ops in that case. */
  private def buildPathOptional(steps: List[PathStep]): Value.InstanceV =
    val getOptionFn = Value.NativeFnV("Optional.getOption", {
      case List(s) => Pure(Value.OptionV(opticGetOption(s, steps)))
      case _       => throw InterpretError("Optional.getOption(s)")
    })
    val setFn = Value.NativeFnV("Optional.set", {
      case List(s, v) => Pure(opticSet(s, steps, v))
      case _          => throw InterpretError("Optional.set(s, v)")
    })
    val modifyFn = Value.NativeFnV("Optional.modify", {
      case List(s, f) => opticGetOption(s, steps) match
        case Some(old) =>
          callValue(f, List(old), Map.empty).map(newV => opticSet(s, steps, newV))
        case None => Pure(s)
      case _ => throw InterpretError("Optional.modify(s, f)")
    })
    // Optional.andThen accepts another path-optic and chains by
    // concatenating steps. Composition with a Traversal yields a
    // Traversal (multi-foci dominates).
    val andThenFn = Value.NativeFnV("Optional.andThen", {
      case List(Value.InstanceV("Traversal", other)) =>
        stepsFromFields(other) match
          case Some(rest) => Pure(buildPathTraversal(steps ++ rest))
          case None       => throw InterpretError("Optional.andThen(Traversal): malformed")
      case List(Value.InstanceV(t, other)) if t == "Optional" || t == "Lens" =>
        stepsFromFields(other) match
          case Some(rest) => Pure(buildPathOptional(steps ++ rest))
          case None       => throw InterpretError("Optional.andThen: cannot compose with non-path optic")
      case _ => throw InterpretError("Optional.andThen(other): only path optic supported")
    })
    val stepsValue = stepsAsListV(steps)
    Value.InstanceV("Optional", Map(
      "getOption" -> getOptionFn,
      "set"       -> setFn,
      "modify"    -> modifyFn,
      "andThen"   -> andThenFn,
      "_steps"    -> stepsValue
    ))

  // ─── Traversal (multi-foci optic, `.each` paths) ─────────────────

  /** Collect every reachable value along `steps`. SomeStep with None and
   *  FieldStep with a missing field contribute zero values; EachStep
   *  flat-maps over List elements. */
  private def opticGetAll(target: Value, steps: List[PathStep]): List[Value] = steps match
    case Nil => List(target)
    case PathStep.FieldStep(n) :: rest => target match
      case Value.InstanceV(_, fields) =>
        fields.get(n).map(v => opticGetAll(v, rest)).getOrElse(Nil)
      case _ => Nil
    case PathStep.SomeStep :: rest => target match
      case Value.OptionV(Some(inner)) => opticGetAll(inner, rest)
      case _                          => Nil
    case PathStep.EachStep :: rest => target match
      case Value.ListV(items) => items.flatMap(item => opticGetAll(item, rest))
      case _                  => Nil
    case PathStep.IndexStep(i) :: rest => target match
      case Value.ListV(items) if i >= 0 && i < items.length =>
        opticGetAll(items(i), rest)
      case _ => Nil
    case PathStep.AtKey(k) :: rest => target match
      case Value.MapV(m) => m.get(k).map(v => opticGetAll(v, rest)).getOrElse(Nil)
      case _             => Nil

  /** Walk `steps` and apply `f` to every focus. Missing layers
   *  (None / not-an-instance) leave the corresponding subtree
   *  unchanged. Returns a Computation because `f` may be a user
   *  function whose body performs effects. */
  private def opticModifyAll(target: Value, steps: List[PathStep], f: Value): Computation = steps match
    case Nil => callValue(f, List(target), Map.empty)
    case PathStep.FieldStep(n) :: rest => target match
      case Value.InstanceV(typeName, fields) =>
        fields.get(n) match
          case Some(child) =>
            opticModifyAll(child, rest, f).map(updated =>
              Value.InstanceV(typeName, fields.updated(n, updated)))
          case None => Pure(target)
      case _ => Pure(target)
    case PathStep.SomeStep :: rest => target match
      case Value.OptionV(Some(inner)) =>
        opticModifyAll(inner, rest, f).map(updated => Value.OptionV(Some(updated)))
      case _ => Pure(target)
    case PathStep.EachStep :: rest => target match
      case Value.ListV(items) =>
        Computation.sequence(items.map(item => opticModifyAll(item, rest, f))).map {
          case Value.ListV(updated) => Value.ListV(updated)
          case _                    => target
        }
      case _ => Pure(target)
    case PathStep.IndexStep(i) :: rest => target match
      case Value.ListV(items) if i >= 0 && i < items.length =>
        opticModifyAll(items(i), rest, f).map(updated => Value.ListV(items.updated(i, updated)))
      case _ => Pure(target)
    case PathStep.AtKey(k) :: rest => target match
      case Value.MapV(m) => m.get(k) match
        case Some(child) =>
          opticModifyAll(child, rest, f).map(updated => Value.MapV(m.updated(k, updated)))
        case None => Pure(target)
      case _ => Pure(target)

  private def stepsAsListV(steps: List[PathStep]): Value.ListV =
    Value.ListV(steps.map {
      case PathStep.FieldStep(n) => Value.StringV(n)
      case PathStep.SomeStep     => Value.StringV("__some__")
      case PathStep.EachStep     => Value.StringV("__each__")
      case PathStep.IndexStep(i) => Value.TupleV(List(Value.StringV("__index__"), Value.IntV(i.toLong)))
      case PathStep.AtKey(k)     => Value.TupleV(List(Value.StringV("__at__"), k))
    })

  private def stepsFromFields(fields: Map[String, Value]): Option[List[PathStep]] =
    fields.get("_steps").orElse(fields.get("_path")) match
      case Some(Value.ListV(items)) => Some(items.collect {
        case Value.StringV("__some__") => PathStep.SomeStep
        case Value.StringV("__each__") => PathStep.EachStep
        case Value.TupleV(List(Value.StringV("__index__"), Value.IntV(i))) =>
          PathStep.IndexStep(i.toInt)
        case Value.TupleV(List(Value.StringV("__at__"), k)) =>
          PathStep.AtKey(k)
        case Value.StringV(n)          => PathStep.FieldStep(n)
      })
      case _ => None

  /** Build a Traversal for a path containing at least one `EachStep`.
   *  `getAll` collects every focused value into a `List`; `modify`
   *  applies a function to each focus and rebuilds the structure;
   *  `set` replaces every focus with the same value. */
  private def buildPathTraversal(steps: List[PathStep]): Value.InstanceV =
    val getAllFn = Value.NativeFnV("Traversal.getAll", {
      case List(s) => Pure(Value.ListV(opticGetAll(s, steps)))
      case _       => throw InterpretError("Traversal.getAll(s)")
    })
    val modifyFn = Value.NativeFnV("Traversal.modify", {
      case List(s, f) => opticModifyAll(s, steps, f)
      case _          => throw InterpretError("Traversal.modify(s, f)")
    })
    val setFn = Value.NativeFnV("Traversal.set", {
      case List(s, v) =>
        val constFn = Value.NativeFnV("const", _ => Pure(v))
        opticModifyAll(s, steps, constFn)
      case _ => throw InterpretError("Traversal.set(s, v)")
    })
    val andThenFn = Value.NativeFnV("Traversal.andThen", {
      case List(Value.InstanceV(t, other))
          if t == "Traversal" || t == "Optional" || t == "Lens" =>
        stepsFromFields(other) match
          case Some(rest) => Pure(buildPathTraversal(steps ++ rest))
          case None       => throw InterpretError("Traversal.andThen: cannot compose")
      case _ => throw InterpretError("Traversal.andThen(other): only path optic supported")
    })
    Value.InstanceV("Traversal", Map(
      "getAll"  -> getAllFn,
      "modify"  -> modifyFn,
      "set"     -> setFn,
      "andThen" -> andThenFn,
      "_steps"  -> stepsAsListV(steps)
    ))

  /** Evaluate a list of argument terms eagerly to a list of Computations, then
   *  thread their values into `k` via FlatMap chain.
   *
   *  Eager evaluation matters because `eval` mutates `placeholderIdx`; deferring
   *  sub-term evaluation into a FlatMap continuation would observe a wrong index
   *  later. After this call all sub-Computations are fully built; only the final
   *  composition (the FlatMap chain) is interpreted lazily. */
  private def evalArgs(args: List[Term], env: Env)(k: List[Value] => Computation): Computation =
    val argComps = args.map(eval(_, env))
    threadValues(argComps)(k)

  /** Thread a list of already-built Computations: bind each in order and feed
   *  the resulting values to `k`. */
  private def threadValues(comps: List[Computation])(k: List[Value] => Computation): Computation =
    def chain(remaining: List[Computation], acc: List[Value]): Computation = remaining match
      case Nil       => k(acc.reverse)
      case c :: rest => FlatMap(c, v => chain(rest, v :: acc))
    chain(comps, Nil)

  /** Evaluate a block of statements; effects propagate through statements via flatMap.
   *  val/var declarations are threaded as Computation so effects in their rhs work. */
  private def evalBlock(stats: List[Stat], env: Env): Computation =
    val local = mutable.Map.from(env)
    // Keys whose value in `env` differs from the current `globals` snapshot —
    // these are local overrides (lambda params, outer-block locals) that the
    // refresh-from-globals loop below must NOT clobber.  Without this guard a
    // lambda param like `p` (which also exists in globals as the `<p>` HTML
    // tag) would be overwritten by the global between statements.
    val localOverrides: scala.collection.mutable.Set[String] =
      scala.collection.mutable.Set.from(local.iterator.collect {
        case (k, v) if !globals.get(k).contains(v) => k
      })
    def step(remaining: List[Stat], lastVal: Value): Computation = remaining match
      case Nil => Pure(lastVal)
      case s :: rest =>
        // Re-read globals into local so mutations from prior statements are visible
        local.keys.foreach { k =>
          if !localOverrides.contains(k) then globals.get(k).foreach(local(k) = _)
        }
        s match
          case Defn.Val(_, pats, _, rhs) =>
            eval(rhs, local.toMap).flatMap { rhsVal =>
              pats match
                case List(Pat.Var(n)) =>
                  local(n.value) = rhsVal
                  localOverrides += n.value
                case List(pat) =>
                  matchPat(pat, rhsVal, local.toMap) match
                    case Some(patEnv) =>
                      patEnv.foreach { (k, v) => local(k) = v; localOverrides += k }
                    case None         => located("Val pattern match failed")
                case _ => ()
              step(rest, Value.UnitV)
            }
          case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
            eval(rhs, local.toMap).flatMap { v =>
              local(n.value) = v
              localOverrides += n.value
              step(rest, Value.UnitV)
            }
          case t: Term =>
            eval(t, local.toMap).flatMap(v => step(rest, v))
          case stat =>
            execStat(stat, local)
            step(rest, Value.UnitV)
    step(stats, Value.UnitV)

  // ─── Call helpers ─────────────────────────────────────────────────

  private def callValue(fn: Value, args: List[Value], env: Env): Computation = fn match
    case f: Value.FunV      => callFun(f, args)
    case f: Value.NativeFnV => f.f(args)
    case Value.InstanceV(_, fields) =>
      fields.get("apply") match
        case Some(f) => callValue(f, args, env)
        case None    => located(s"Instance is not callable")
    // `xs(i)` and `m(k)` — apply-as-indexing on collections.
    case _: Value.ListV | _: Value.MapV => dispatch(fn, "apply", args, env)
    case _ => located(s"Not callable: ${Value.show(fn)}")

  private def callFun(f: Value.FunV, args: List[Value]): Computation =
    // Auto-tuple: an N-parameter lambda passed where a 1-arg function on
    // an N-tuple is expected (e.g. `pairs.foreach((n, s) => ...)`) gets
    // its single tuple argument destructured into the N parameters.
    val tupledArgs = args match
      case List(Value.TupleV(elems))
        if f.params.length > 1 && elems.length == f.params.length =>
        elems
      case _ => args
    // Only allocate the defaults-pass base env when defaults are actually
    // needed — most calls pass enough args and skip the allocation.
    // `eval` for Term.Name already falls back through `globals`, so locals
    // (closure + self ref + params) are all the env we need.
    val effArgs =
      if tupledArgs.length >= f.params.length then tupledArgs
      else
        val selfEntry = if f.name.nonEmpty then Map(f.name -> f) else Map.empty
        applyDefaults(f.params, f.defaults, tupledArgs, f.closure ++ selfEntry)
    val info      = tcoInfoFor(f)
    val hasMutualTail = info.tailTargets.nonEmpty && info.tailTargets.exists { n =>
      (globals.get(n) orElse f.closure.get(n)).exists(_.isInstanceOf[Value.FunV])
    }
    if info.noNonTailSelf && (info.isSelfTailRec || hasMutualTail) then
      tcoTrampoline(f, effArgs, null)
    else
      // Build callEnv as a `FrameMap` — parallel arrays of param names /
      // values on top of the (cached) closure-with-self map. Cheaper than
      // a HashMap.updated chain for the typical 1-2 param case and
      // turns lookup into a linear scan over `slots` (tiny), falling
      // through to the closure map only for non-locals.
      val withSelf = closureWithSelfFor(f)
      val callEnv: Env = f.params match
        case Nil               => withSelf
        case p :: Nil          => FrameMap.one(p, effArgs.head, withSelf)
        case p1 :: p2 :: Nil   => FrameMap.two(p1, effArgs.head, p2, effArgs(1), withSelf)
        case ps                =>
          val names = ps.toArray
          val arr   = effArgs.iterator.take(names.length).toArray
          FrameMap.of(names, arr, withSelf)
      try runUntilSuspension(eval(f.body, callEnv))
      catch case r: ReturnSignal => Pure(r.value)

  /** Extend `args` with default values for any missing trailing parameters.
   *  Each default is evaluated in `baseEnv` augmented with the bindings of all
   *  parameters to its left (provided ones plus already-filled defaults), so
   *  defaults like `def f(x: Int, y: Int = x + 1)` see `x` correctly. */
  private def applyDefaults(
    params:   List[String],
    defaults: List[Option[Term]],
    args:     List[Value],
    baseEnv:  Env
  ): List[Value] =
    if args.length >= params.length then args
    else
      val provided = args
      var env = baseEnv ++ params.zip(provided).toMap
      val filled = (provided.length until params.length).map { i =>
        val pname      = params(i)
        val defaultOpt = defaults.lift(i).flatten
        defaultOpt match
          case Some(defaultTerm) =>
            val v = Computation.run(eval(defaultTerm, env))
            env = env + (pname -> v)
            v
          case None =>
            located(s"missing argument for parameter '$pname'")
      }.toList
      provided ++ filled

  /** TCO trampoline that survives effect suspensions.
   *
   *  Body code is evaluated under an env that maps the function name (and any
   *  mutually-recursive friends) to native fns that throw `TailCall` /
   *  `MutualTailCall`. The outer while-loop catches those and re-runs the body
   *  with the new arg vector — same trick as the classic trampoline, but here
   *  the inner step uses `runUntilSuspension` so Performs propagate.
   *
   *  When the body suspends at `FlatMap(Perform, k)`, the trampoline returns
   *  the Perform to its caller (the enclosing handler) but wraps `k` so that
   *  when resume invokes it, control re-enters `tcoTrampoline` with `k(v)` as
   *  the initial Computation. This way TailCalls thrown by the post-suspension
   *  code are caught by the trampoline, not by an already-exited frame.
   *
   *  Each `resume` invocation pays one Scala stack frame for re-entering the
   *  trampoline; subsequent tail iterations and bind-chain stepping use the
   *  while-loops and stay O(1). */
  private def tcoTrampoline(
    initialFun:  Value.FunV,
    initialArgs: List[Value],
    initialComp: Computation
  ): Computation =
    var curFun: Value.FunV   = initialFun
    var curArgs: List[Value] = initialArgs
    var current: Computation = initialComp
    // Stable, per-curFun part of the call env: closure + self-tco stub +
    // mutual-tail-call stubs. The only thing that varies across tail
    // iterations is the param binding, so build this once per `curFun`
    // and refresh it only when a mutual jump changes `curFun`.
    var envStable: Map[String, Value] = null
    var envStableFor: Value.FunV      = null
    while true do
      try
        if current == null then
          if (envStable eq null) || (envStableFor ne curFun) then
            val targets = tcoInfoFor(curFun).tailTargets
            val mutualEntries: Map[String, Value] = targets.flatMap { name =>
              (globals.get(name) orElse curFun.closure.get(name)).collect {
                case fn: Value.FunV =>
                  name -> (Value.NativeFnV(name, a => throw new MutualTailCall(fn, a)): Value)
              }
            }.toMap
            val selfTco = Value.NativeFnV(curFun.name, a => throw new TailCall(a))
            envStable     = curFun.closure.updated(curFun.name, selfTco) ++ mutualEntries
            envStableFor  = curFun
          val callEnv = curFun.params match
            case Nil               => envStable
            case p :: Nil          => envStable.updated(p, curArgs.head)
            case p1 :: p2 :: Nil   => envStable.updated(p1, curArgs.head).updated(p2, curArgs(1))
            case _                 => envStable ++ curFun.params.iterator.zip(curArgs.iterator).toMap
          current = eval(curFun.body, callEnv)
        // Inner step loop — re-associate FlatMaps and step Pure short-circuits.
        // Exits via `return` inside the match; the condition stays `true`.
        while true do
          current match
            case Pure(_)              => return current
            case Perform(_, _, _)     => return current
            case FlatMap(sub, k) => sub match
              case Pure(v)               => current = k(v)
              case Perform(eff, op, a)   =>
                val funSnapshot  = curFun
                val argsSnapshot = curArgs
                // Compute `k(v)` lazily inside a try so a TailCall thrown by
                // a tail-recursive self-call in `k`'s body re-enters the
                // trampoline rather than escaping. Without this the resume
                // continuation evaluates `k(v)` eagerly as a strict argument
                // to tcoTrampoline and any TailCall it throws falls through
                // both the outer trampoline's try (already exited) and the
                // re-entry's try (not yet armed).
                return FlatMap(Perform(eff, op, a), v =>
                  try tcoTrampoline(funSnapshot, argsSnapshot, k(v))
                  catch
                    case tc: TailCall       => tcoTrampoline(funSnapshot, tc.args, null)
                    case mc: MutualTailCall =>
                      val next = mc.f
                      if next.name.nonEmpty && tcoInfoFor(next).noNonTailSelf then
                        tcoTrampoline(next, mc.args, null)
                      else callFun(next, mc.args))
              case FlatMap(sub2, g)      =>
                current = FlatMap(sub2, x => FlatMap(g(x), k))
      catch
        case r: ReturnSignal    => return Pure(r.value)
        case tc: TailCall       =>
          curArgs = tc.args
          current = null
        case mc: MutualTailCall =>
          val next = mc.f
          if next.name.nonEmpty && tcoInfoFor(next).noNonTailSelf then
            curFun  = next
            curArgs = mc.args
            current = null
          else
            return callFun(next, mc.args)
    throw InterpretError("unreachable")

  /** Run a Computation through Pure short-circuits and FlatMap re-associations
   *  until it either resolves to Pure, or hits a Perform that needs to escape
   *  to an outer handler. The while-loop with right-association makes this
   *  stack-safe regardless of how deep the bind chain is (Bjarnason 2012).
   *
   *  ReturnSignal / TailCall / MutualTailCall propagate to the caller. */
  private def runUntilSuspension(c: Computation): Computation =
    var current: Computation = c
    while true do
      current match
        case Pure(_)             => return current
        case Perform(_, _, _)    => return current
        case FlatMap(sub, f) => sub match
          case Pure(v)              => current = f(v)
          case Perform(eff, op, args) =>
            return FlatMap(Perform(eff, op, args), f)
          case FlatMap(sub2, g)     =>
            current = FlatMap(sub2, x => FlatMap(g(x), f))
    throw InterpretError("unreachable")

  /** True if `fname` appears in a tail position of `tree`. */
  private def callsInTailPos(tree: scala.meta.Tree, fname: String): Boolean = tree match
    case Term.Apply.After_4_6_0(Term.Name(`fname`), _) => true
    case t: Term.If =>
      callsInTailPos(t.thenp, fname) || callsInTailPos(t.elsep, fname)
    case Term.Block(stats) =>
      stats.lastOption.exists { case t: Term => callsInTailPos(t, fname); case _ => false }
    case t: Term.Match =>
      t.casesBlock.cases.exists(c => callsInTailPos(c.body, fname))
    case _ => false

  // Returns names of functions called in tail position in term (excluding selfName).
  private def tailCallTargets(tree: scala.meta.Tree, selfName: String, tailPos: Boolean): Set[String] =
    tree match
      case Term.Apply.After_4_6_0(Term.Name(n), argClause) =>
        if tailPos && n != selfName then Set(n)
        else argClause.values.flatMap(a => tailCallTargets(a, selfName, false)).toSet
      case t: Term.If =>
        tailCallTargets(t.cond,  selfName, false) ++
        tailCallTargets(t.thenp, selfName, tailPos) ++
        tailCallTargets(t.elsep, selfName, tailPos)
      case Term.Block(stats) =>
        stats.dropRight(1).flatMap(s => tailCallTargets(s, selfName, false)).toSet ++
        stats.lastOption.map(s => tailCallTargets(s, selfName, tailPos)).getOrElse(Set.empty)
      case t: Term.Match =>
        tailCallTargets(t.expr, selfName, false) ++
        t.casesBlock.cases.flatMap(c => tailCallTargets(c.body, selfName, tailPos)).toSet
      case other =>
        other.children.flatMap(c => tailCallTargets(c, selfName, false)).toSet

  // Returns true if term has a self-call to fname NOT in tail position.
  private def hasNonTailSelfCall(term: Term, fname: String, tailPos: Boolean): Boolean =
    import scala.meta.*
    term match
      case Term.Apply.After_4_6_0(Term.Name(`fname`), argClause) =>
        if tailPos then argClause.values.collect { case t: Term => t }
                                        .exists(hasNonTailSelfCall(_, fname, tailPos = false))
        else true
      case t: Term.If =>
        hasNonTailSelfCall(t.cond,  fname, tailPos = false) ||
        hasNonTailSelfCall(t.thenp, fname, tailPos = tailPos) ||
        hasNonTailSelfCall(t.elsep, fname, tailPos = tailPos)
      case Term.Block(stats) =>
        stats.dropRight(1).exists {
          case t: Term => hasNonTailSelfCall(t, fname, tailPos = false)
          case _       => false
        } || stats.lastOption.exists {
          case t: Term => hasNonTailSelfCall(t, fname, tailPos = tailPos)
          case _       => false
        }
      case t: Term.Match =>
        hasNonTailSelfCall(t.expr, fname, tailPos = false) ||
        t.casesBlock.cases.exists(c => hasNonTailSelfCall(c.body, fname, tailPos = tailPos))
      case other =>
        anywhereContainsSelfCall(other, fname)

  private def anywhereContainsSelfCall(tree: scala.meta.Tree, fname: String): Boolean =
    import scala.meta.*
    tree match
      case Term.Apply.After_4_6_0(Term.Name(`fname`), _) => true
      case t => t.children.exists(anywhereContainsSelfCall(_, fname))

  // ─── Algebraic effects (Free Monad interpreter) ──────────────────

  private def isEffectOpDef(body: Term): Boolean = body match
    case Term.Name("__effectOp__") => true
    case _                         => false

  /** Interpret `handle(body) { cases }` — trampolined.
   *
   *  The body is evaluated to a Computation tree. We walk it with a while-loop:
   *
   *    Pure(v)                     → return Pure(v)
   *    Perform(eff, op, args)      → handler matched? dispatch with resume = identity
   *                                   else propagate as-is (no continuation to wrap)
   *    FlatMap(Pure(v), f)         → step to f(v)
   *    FlatMap(FlatMap(c, g), f)   → re-associate to FlatMap(c, x => FlatMap(g(x), f))
   *    FlatMap(Perform(...), f)    → handler matched? dispatch with resume = v => interp(f(v))
   *                                   else propagate as FlatMap(Perform, v => interp(f(v)))
   *
   *  The re-association keeps the Scala call stack O(1) regardless of how deeply
   *  the bind chain is nested. `resume(v) = interp(k(v))` is itself a closure;
   *  invoking it (from the handler case body) starts a fresh trampoline.
   *  Multi-shot is calling that closure more than once — each invocation walks
   *  a fresh branch of the tree.
   */
  private def evalHandle(body: Term, cases: List[Case], env: Env): Computation =
    val handledOps: Set[(String, String)] = cases.flatMap { c =>
      c.pat match
        case Pat.Extract.After_4_6_0(Term.Select(Term.Name(eff), Term.Name(op)), _) => Some((eff, op))
        case _ => None
    }.toSet

    def dispatchCase(eff: String, op: String, args: List[Value], resume: Value): Computation =
      cases.iterator.flatMap { c =>
        c.pat match
          case Pat.Extract.After_4_6_0(Term.Select(Term.Name(`eff`), Term.Name(`op`)), argClause) =>
            val patArgs   = argClause.values
            val argPats   = patArgs.dropRight(1).map(_.asInstanceOf[Pat])
            val resumePat = patArgs.lastOption
            argPats.zip(args).foldLeft(Option(env): Option[Env]) {
              case (Some(e), (pat, v)) => matchPat(pat, v, e)
              case (None, _)           => None
            }.flatMap { argEnv =>
              val finalEnv = resumePat match
                case Some(pv: Pat.Var) => argEnv + (pv.name.value -> resume)
                case _                 => argEnv
              val guardOk = c.cond.forall { g =>
                Computation.run(eval(g, finalEnv)) match
                  case Value.BoolV(b) => b
                  case _              => false
              }
              if guardOk then Some(eval(c.body, finalEnv)) else None
            }
          case _ => None
      }.nextOption()
        .getOrElse(throw InterpretError(s"Unhandled effect: $eff.$op (no matching case)"))

    def interp(initial: Computation): Computation =
      var current: Computation = initial
      while true do
        current match
          case Pure(_) => return current
          case Perform(eff, op, args) =>
            if !handledOps.contains((eff, op)) then return current
            else
              val resume = Value.NativeFnV("resume", rargs => {
                val v = rargs match
                  case List(v) => v; case Nil => Value.UnitV; case vs => Value.TupleV(vs)
                Pure(v)  // bare Perform: no rest — resume returns the injected value
              })
              current = dispatchCase(eff, op, args, resume)
          case FlatMap(sub, f) => sub match
            case Pure(v) =>
              current = f(v)
            case FlatMap(sub2, g) =>
              current = FlatMap(sub2, x => FlatMap(g(x), f))
            case Perform(eff, op, args) =>
              if !handledOps.contains((eff, op)) then
                // Unhandled: propagate, but re-enter this handler's interp on
                // resume so nested Performs handled here still get dispatched
                // by us when an outer handler resumes the continuation.
                return FlatMap(Perform(eff, op, args), v => interp(f(v)))
              else
                // Handled: resume runs the captured continuation through interp
                // to a Value (multi-shot: each call interprets a fresh branch
                // and the case body composes the values directly via JS-level
                // flatMap/etc.). interp's inner while-loop is stack-safe in the
                // bind-chain depth; the recursion across sequential handler
                // dispatches grows stack linearly with handler-dispatch depth.
                val resume = Value.NativeFnV("resume", rargs => {
                  val v = rargs match
                    case List(v) => v; case Nil => Value.UnitV; case vs => Value.TupleV(vs)
                  interp(f(v))
                })
                current = dispatchCase(eff, op, args, resume)
      throw InterpretError("unreachable")

    interp(eval(body, env))

  // ─── Reactive primitives: implementation helpers ───────────────────

  private def freshReactiveId(): Long =
    reactiveCounter += 1; reactiveCounter

  private def signalInstance(id: Long): Value.InstanceV =
    Value.InstanceV("Signal", Map("id" -> Value.IntV(id)))

  /** Allocate a new Signal cell and return its user-facing handle. */
  private def makeSignal(init: Value): Value.InstanceV =
    val id = freshReactiveId()
    signals(id) = SignalState(init, mutable.HashSet.empty)
    signalInstance(id)

  /** Read a signal, registering a subscription with the active effect (if any). */
  private def signalGet(id: Long): Value =
    val s = signals.getOrElse(id, throw InterpretError("Signal disposed or unknown id"))
    if effectStack.nonEmpty then
      val eid = effectStack.top
      s.subs += eid
      effects.get(eid).foreach(_.deps += id)
    s.value

  /** Write a signal: replace its value and queue its subscribers for
   *  rerun.  Reruns are deduplicated within the surrounding flush so a
   *  diamond (root → derived → consumer; consumer also reads root) sees
   *  each effect exactly once per transaction, matching Solid-style
   *  fine-grained reactivity.  Subscribers currently on the effect
   *  stack are skipped — without this guard an effect that writes to
   *  a signal it also reads infinite-loops itself. */
  private def signalSet(id: Long, v: Value): Unit =
    val s = signals.getOrElse(id, throw InterpretError("Signal disposed or unknown id"))
    s.value = v
    s.subs.foreach { eid =>
      if !effectStack.contains(eid) then pendingEffects += eid
    }
    if !reactiveFlushing then reactiveFlush()

  private def reactiveFlush(): Unit =
    reactiveFlushing = true
    try
      while pendingEffects.nonEmpty do
        val eid = pendingEffects.head
        pendingEffects -= eid
        runEffect(eid)
    finally reactiveFlushing = false

  /** Detach an effect from all signals it currently depends on.  Called
   *  before each rerun so a freshly-evaluated dependency set replaces
   *  the previous one (handles `if` branches that read different
   *  signals on different runs). */
  private def clearEffectDeps(eid: Long): Unit =
    effects.get(eid).foreach { e =>
      e.deps.foreach { sid => signals.get(sid).foreach(_.subs -= eid) }
      e.deps.clear()
    }

  /** Run an effect's thunk under tracking — pushes its id on the stack
   *  so signal reads inside the thunk register as deps. */
  private def runEffect(eid: Long): Unit =
    effects.get(eid).foreach { e =>
      clearEffectDeps(eid)
      effectStack.push(eid)
      try Computation.run(callValue(e.thunk, Nil, Map.empty))
      finally effectStack.pop()
    }

  /** Create a side-effecting reactive block; runs once immediately then
   *  re-runs whenever any signal it read changes. */
  private def makeEffect(thunk: Value): Value =
    val id = freshReactiveId()
    effects(id) = EffectState(thunk, mutable.HashSet.empty)
    runEffect(id)
    Value.UnitV

  /** Derived signal: runs `thunk` under tracking, stores the result in a
   *  fresh signal, and re-runs (updating the signal) when its deps
   *  change.  Reading the returned signal subscribes to *its* changes,
   *  so downstream effects/computeds compose naturally. */
  private def makeComputed(thunk: Value): Value.InstanceV =
    val sid = freshReactiveId()
    val eid = freshReactiveId()
    signals(sid) = SignalState(Value.UnitV, mutable.HashSet.empty)
    val updater = Value.NativeFnV("computed.update", Computation.pureFn { _ =>
      val v = Computation.run(callValue(thunk, Nil, Map.empty))
      signalSet(sid, v)
      Value.UnitV
    })
    effects(eid) = EffectState(updater, mutable.HashSet.empty)
    runEffect(eid)
    signalInstance(sid)

  /** Driver for the built-in `Async` effect — same Free-Monad walk as
   *  `evalHandle` but with a fixed dispatch table for `Async.*` ops
   *  (delay / async / await / parallel).  Non-Async Performs propagate
   *  outward so an enclosing `handle` can pick them up.  Single-threaded
   *  semantics: a thunk passed to `async` / `parallel` runs immediately
   *  on the calling thread, so the resulting output is deterministic
   *  and identical to the JS / JvmGen backends. */
  private def asyncInterp(initial: Computation): Computation =
    var current: Computation = initial
    while true do
      current match
        case Pure(_) => return current
        case Perform("Async", op, args) =>
          // Bare Perform — no continuation; dispatch with identity resume.
          current = asyncDispatch(op, args, v => Pure(v))
        case Perform(_, _, _) => return current  // unhandled, propagate
        case FlatMap(sub, f) => sub match
          case Pure(v) =>
            current = f(v)
          case FlatMap(sub2, g) =>
            current = FlatMap(sub2, x => FlatMap(g(x), f))
          case Perform("Async", op, args) =>
            current = asyncDispatch(op, args, v => asyncInterp(f(v)))
          case Perform(_, _, _) =>
            return FlatMap(sub, v => asyncInterp(f(v)))
    throw InterpretError("unreachable")

  private def asyncDispatch(
    op:     String,
    args:   List[Value],
    resume: Value => Computation
  ): Computation = op match
    case "delay" => args match
      case List(Value.IntV(ms)) =>
        if ms > 0 then Thread.sleep(ms)
        resume(Value.UnitV)
      case _ => throw InterpretError("Async.delay(ms: Int)")
    case "async" => args match
      case List(thunk) =>
        asyncInterp(callValue(thunk, Nil, Map.empty)) match
          case Pure(v) =>
            resume(Value.InstanceV("Future", Map("value" -> v)))
          case _ => throw InterpretError(
            "Async.async thunk leaked an unhandled non-Async effect")
      case _ => throw InterpretError("Async.async(thunk)")
    case "await" => args match
      case List(Value.InstanceV("Future", fields)) =>
        resume(fields.getOrElse("value", Value.UnitV))
      case _ => throw InterpretError("Async.await(future)")
    case "parallel" => args match
      case List(Value.ListV(thunks)) =>
        // Single-threaded: run each thunk through this driver, in declared
        // order, collect results.  A real-concurrent backend can swap this
        // for an executor without changing observable output (results are
        // returned in input order regardless of completion order).
        val results = thunks.map { t =>
          asyncInterp(callValue(t, Nil, Map.empty)) match
            case Pure(v) => v
            case _ => throw InterpretError(
              "Async.parallel thunk leaked an unhandled non-Async effect")
        }
        resume(Value.ListV(results))
      case _ => throw InterpretError("Async.parallel(thunks: List[() => A])")
    case _ => throw InterpretError(s"Unknown Async operation: $op")

  // ── v1.6 Actors Phase 1 — cooperative scheduler ────────────────────
  //
  // Single-threaded green-thread scheduler over Computation.  Each
  // actor's "current frame" lives in `pending(id)`.  Stepping an actor
  // walks its Computation tree until it either returns Pure (actor
  // completes) or hits a `Perform("Actor", op, …)` node we handle here.
  // `receive` on an empty mailbox stores the (cases, env, continuation)
  // triple in `blocked` and switches to another ready actor.
  // `send` enqueues into the recipient's mailbox; if the recipient was
  // blocked and the new head matches, we splice the case-body into
  // its pending slot and mark it ready.
  //
  // The driver runs until every actor is either complete or blocked
  // with an empty mailbox.  The root actor's final value is returned.
  private def actorInterp(initial: Computation): Computation =
    val rt = new ActorRuntime
    val savedRt = actorRt
    actorRt = rt
    try
      val rootId = rt.nextId
      rt.nextId += 1
      rt.mailboxes(rootId) = mutable.Queue.empty
      rt.pending(rootId)   = initial
      rt.ready.enqueue(rootId)
      val rootResult = mutable.LongMap.empty[Value]
      var deadlockGuard = 0
      val maxIterations = 100000

      while (rt.ready.nonEmpty || rt.blocked.exists(_._2._4.isDefined)) && deadlockGuard < maxIterations do
        deadlockGuard += 1
        if rt.ready.isEmpty then
          // Quiescent except for timeout-armed receives: sleep until
          // the earliest deadline, then deliver None to that actor.
          val now = System.currentTimeMillis()
          val earliest = rt.blocked.collect {
            case (id, (_, _, _, Some(d), _)) => (id, d)
          }.minByOption(_._2)
          earliest match
            case Some((id, d)) =>
              val sleepFor = d - now
              if sleepFor > 0 then Thread.sleep(sleepFor)
              rt.blocked.remove(id) match
                case Some((_, _, k, _, _)) =>
                  rt.pending(id) = k(Value.InstanceV("None", Map.empty))
                  rt.ready.enqueue(id)
                case None => ()
            case None => () // shouldn't happen — loop exit condition would fire
        else
          val id = rt.ready.dequeue()
          // An actor may have been re-enqueued after completion if a
          // late send arrived; skip if already done.
          if rt.pending.contains(id) then
            rt.currentId = id
            val stepResult = stepActor(rt, id, rt.pending(id))
            stepResult match
              case ActorStep.Done(v)   =>
                rt.pending.remove(id)
                if id == rootId then rootResult(rootId) = v
              case ActorStep.Suspend   =>
                // Already moved to blocked or removed by stepActor.
                ()
              case ActorStep.Yield(c)  =>
                rt.pending(id) = c
                rt.ready.enqueue(id)

      // Drain dead-letter logging for messages stuck in completed
      // actors' mailboxes — informational only, doesn't fail the run.
      Pure(rootResult.getOrElse(rootId, Value.UnitV))
    finally
      actorRt = savedRt

  private enum ActorStep:
    case Done(value: Value)
    case Suspend
    case Yield(next: Computation)

  /** Walk one actor's Computation tree until we either complete it,
   *  hit a suspend point, or yield (re-queue for a fresh slice). */
  private def stepActor(rt: ActorRuntime, id: Long, initial: Computation): ActorStep =
    var current: Computation = initial
    while true do
      current match
        case Pure(v) =>
          return ActorStep.Done(v)

        // Bare Perform — Actor op with no continuation.
        case Perform("Actor", op, args) =>
          handleActorOp(rt, id, op, args, v => Pure(v)) match
            case Right(next) => current = next
            case Left(_)     => return ActorStep.Suspend

        case Perform(eff, op, _) =>
          throw InterpretError(s"Unhandled effect inside actor: $eff.$op")

        case FlatMap(sub, f) => sub match
          case Pure(v) =>
            current = f(v)
          case FlatMap(sub2, g) =>
            current = FlatMap(sub2, x => FlatMap(g(x), f))
          case Perform("Actor", op, args) =>
            handleActorOp(rt, id, op, args, f) match
              case Right(next) => current = next
              case Left(_)     => return ActorStep.Suspend
          case Perform(eff, op, _) =>
            throw InterpretError(s"Unhandled effect inside actor: $eff.$op")
    throw InterpretError("unreachable")

  /** Handle one Actor effect op.
   *  Right(next) — continue stepping this actor with `next`.
   *  Left(())   — actor suspended; driver should move on. */
  private def handleActorOp(
      rt: ActorRuntime,
      id: Long,
      op: String,
      args: List[Value],
      k:  Value => Computation
  ): Either[Unit, Computation] = op match
    case "spawn" =>
      val thunk = args.head
      val childId = rt.nextId
      rt.nextId += 1
      rt.mailboxes(childId) = mutable.Queue.empty
      // Eagerly build the child's initial Computation by calling the
      // thunk with no args.  Defer actually stepping it until the
      // scheduler picks the child off the ready queue.
      val savedCurrent = rt.currentId
      rt.currentId = childId
      val childBody =
        try callValue(thunk, Nil, Map.empty)
        finally rt.currentId = savedCurrent
      rt.pending(childId) = childBody
      rt.ready.enqueue(childId)
      Right(k(Value.InstanceV("Pid", Map("id" -> Value.IntV(childId)))))

    case "self" =>
      Right(k(Value.InstanceV("Pid", Map("id" -> Value.IntV(id)))))

    case "send" => args match
      case List(Value.InstanceV("Pid", fields), msg) =>
        fields.get("id") match
          case Some(Value.IntV(targetId)) =>
            rt.mailboxes.get(targetId) match
              case Some(mb) =>
                mb.enqueue(msg)
                // If target was blocked, try to deliver right now so
                // it becomes runnable for the next tick.
                rt.blocked.get(targetId) match
                  case Some((cases, blockedEnv, blockedK, _, wrapSome)) =>
                    tryDeliver(rt, targetId, cases, blockedEnv, blockedK, wrapSome) match
                      case Some(newPending) =>
                        rt.blocked.remove(targetId)
                        rt.pending(targetId) = newPending
                        rt.ready.enqueue(targetId)
                      case None => ()  // head didn't match, stays blocked
                  case None => ()
              case None =>
                // Send to dead PID — silent no-op, Erlang semantics.
                ()
          case _ => ()
        Right(k(Value.UnitV))
      case _ => throw InterpretError("send(pid, msg) expects a Pid and a value")

    case "receive" => args match
      case List(Value.IntV(specId)) =>
        val (cases, recvEnv) = receiveSpecs(specId)
        tryDeliver(rt, id, cases, recvEnv, k, wrapSome = false) match
          case Some(next) => Right(next)
          case None       =>
            rt.blocked(id) = (cases, recvEnv, k, None, false)
            Left(())
      case _ => throw InterpretError("receive expects an internal spec token")

    case "receive_t" => args match
      case List(Value.IntV(specId), Value.IntV(timeoutMs)) =>
        val (cases, recvEnv) = receiveSpecs(specId)
        tryDeliver(rt, id, cases, recvEnv, k, wrapSome = true) match
          case Some(next) => Right(next)
          case None       =>
            val deadline = System.currentTimeMillis() + timeoutMs
            rt.blocked(id) = (cases, recvEnv, k, Some(deadline), true)
            Left(())
      case _ => throw InterpretError("receive(timeout = N) — internal arg mismatch")

    case "exit" => args match
      case List(Value.InstanceV("Pid", fields), reason) =>
        fields.get("id") match
          case Some(Value.IntV(targetId)) => killActor(rt, targetId, reason)
          case _                          => ()
        // Self-exit or link propagation may have killed the current actor.
        if rt.mailboxes.contains(id) then Right(k(Value.UnitV))
        else Left(())
      case _ => throw InterpretError("exit(pid, reason)")

    // ── Phase 2 — supervision ────────────────────────────────────────

    case "link" => args match
      case List(Value.InstanceV("Pid", fields)) =>
        fields.get("id") match
          case Some(Value.IntV(targetId)) =>
            if rt.mailboxes.contains(targetId) then
              // Both alive — create bidirectional link
              rt.links.getOrElseUpdate(id,       mutable.Set.empty) += targetId
              rt.links.getOrElseUpdate(targetId, mutable.Set.empty) += id
            else
              // Target already dead — noproc exit signal
              val noproc = Value.InstanceV("noproc", Map.empty)
              if rt.trapExit.getOrElse(id, false) then
                val exitMsg = Value.InstanceV("Exit", Map(
                  "from"   -> Value.InstanceV("Pid", Map("id" -> Value.IntV(targetId))),
                  "reason" -> noproc))
                rt.mailboxes.get(id).foreach(_.enqueue(exitMsg))
              else
                killActor(rt, id, noproc)
          case _ => ()
        // Link to dead target with no trapExit kills us — check before continuing.
        if rt.mailboxes.contains(id) then Right(k(Value.UnitV))
        else Left(())
      case _ => throw InterpretError("link(pid)")

    case "monitor" => args match
      case List(Value.InstanceV("Pid", fields)) =>
        fields.get("id") match
          case Some(Value.IntV(targetId)) =>
            val monRef = rt.nextMonRef
            rt.nextMonRef += 1
            if rt.mailboxes.contains(targetId) then
              rt.monitors.getOrElseUpdate(targetId, mutable.Map.empty)(monRef) = id
            else
              // Already dead — immediately deliver Down
              val downMsg = Value.InstanceV("Down", Map(
                "ref"    -> Value.IntV(monRef),
                "from"   -> Value.InstanceV("Pid", Map("id" -> Value.IntV(targetId))),
                "reason" -> Value.InstanceV("noproc", Map.empty)))
              rt.mailboxes.get(id).foreach(_.enqueue(downMsg))
              wakeBlocked(rt, id)
            Right(k(Value.IntV(monRef)))
          case _ => Right(k(Value.IntV(-1L)))
      case _ => throw InterpretError("monitor(pid)")

    case "demonitor" => args match
      case List(Value.IntV(monRef)) =>
        rt.monitors.foreachEntry((_, monMap) => monMap.remove(monRef))
        Right(k(Value.UnitV))
      case _ => throw InterpretError("demonitor(ref)")

    case "trapExit" => args match
      case List(Value.BoolV(b)) =>
        rt.trapExit(id) = b
        Right(k(Value.UnitV))
      case _ => throw InterpretError("trapExit(on: Boolean)")

    case other =>
      throw InterpretError(s"Unknown Actor op: $other")

  /** Pop messages from the head of `id`'s mailbox; for each one,
   *  try to match a case.  Match: return Some(caseBody ⊛ k).
   *  No-match: pop, log as dead-letter, try the next.
   *  Empty mailbox: None. */
  private def tryDeliver(
      rt:        ActorRuntime,
      id:        Long,
      cases:     List[Case],
      env:       Env,
      k:         Value => Computation,
      wrapSome:  Boolean
  ): Option[Computation] =
    val mb = rt.mailboxes(id)
    while mb.nonEmpty do
      val msg = mb.head
      var matched: Option[Computation] = None
      val it = cases.iterator
      while matched.isEmpty && it.hasNext do
        val c = it.next()
        matchPat(c.pat, msg, env) match
          case Some(extEnv) =>
            val guardOk = c.cond.forall { g =>
              Computation.run(eval(g, extEnv)) match
                case Value.BoolV(b) => b
                case _              => false
            }
            if guardOk then
              val body = eval(c.body, extEnv)
              matched = Some(
                if wrapSome then body.flatMap(v =>
                  k(Value.InstanceV("Some", Map("value" -> v))))
                else body.flatMap(k)
              )
          case None => ()
      if matched.isDefined then
        mb.dequeue()
        return matched
      // Dead letter: discard the head and try the next.
      out.println(s"[dead-letter] actor=$id msg=${Value.show(msg)}")
      mb.dequeue()
    None

  // ── Phase 2 — supervision helpers ────────────────────────────────

  /** Kill actor `targetId` with `reason`.  Propagates through links:
   *  - linked actors with trapExit=false also die (recursive)
   *  - linked actors with trapExit=true receive an Exit message
   *  Monitors on the dead actor fire Down messages to observers.
   *  Idempotent: actors without a mailbox entry are already dead. */
  private def killActor(rt: ActorRuntime, targetId: Long, reason: Value): Unit =
    if !rt.mailboxes.contains(targetId) then return  // already dead

    // Mark dead immediately to prevent re-entry from circular links.
    rt.pending.remove(targetId)
    rt.blocked.remove(targetId)
    rt.mailboxes.remove(targetId)
    rt.trapExit.remove(targetId)

    val deadPid = Value.InstanceV("Pid", Map("id" -> Value.IntV(targetId)))

    // Notify linked actors.
    rt.links.remove(targetId).foreach { linkedSet =>
      linkedSet.foreach { linkedId =>
        rt.links.get(linkedId).foreach(_.remove(targetId))
        if rt.trapExit.getOrElse(linkedId, false) then
          val exitMsg = Value.InstanceV("Exit", Map("from" -> deadPid, "reason" -> reason))
          rt.mailboxes.get(linkedId).foreach(_.enqueue(exitMsg))
          wakeBlocked(rt, linkedId)
        else
          killActor(rt, linkedId, reason)
      }
    }

    // Fire Down messages for all monitors on this actor.
    rt.monitors.remove(targetId).foreach { monMap =>
      monMap.foreach { (monRef, observerId) =>
        val downMsg = Value.InstanceV("Down", Map(
          "ref"    -> Value.IntV(monRef),
          "from"   -> deadPid,
          "reason" -> reason))
        rt.mailboxes.get(observerId).foreach(_.enqueue(downMsg))
        wakeBlocked(rt, observerId)
      }
    }

  /** If `id` is currently blocked on a receive and a newly enqueued
   *  message can be delivered, move it back to pending/ready. */
  private def wakeBlocked(rt: ActorRuntime, id: Long): Unit =
    rt.blocked.get(id) match
      case Some((cases, env, k, _, wrapSome)) =>
        tryDeliver(rt, id, cases, env, k, wrapSome) match
          case Some(next) =>
            rt.blocked.remove(id)
            rt.pending(id) = next
            rt.ready.enqueue(id)
          case None => ()
      case None => ()

  // ── runAsyncParallel — real-thread driver for the same Async API ──
  //
  // Same Free Monad walk as `asyncInterp` but `async` / `parallel`
  // dispatch their thunks to a per-driver `ExecutorService` instead
  // of running them inline.  `await` blocks the calling thread on the
  // future; `parallel` returns results in declared order regardless
  // of completion order so value-deterministic code retains
  // byte-identical output across the single- and parallel-handler
  // variants.  `delay` still uses `Thread.sleep` because the calling
  // thread is the right thread to block on the JVM.

  private def asyncParInterp(initial: Computation): Computation =
    val ex = java.util.concurrent.Executors.newCachedThreadPool()
    try
      // Inner driver re-used for thunks-inside-thunks (the future's
      // body itself may use `Async.*`); shares the executor with the
      // outer call so worker threads recursively submit to the same
      // pool instead of allocating a fresh one per nesting level.
      def driver(c: Computation): Computation =
        var current: Computation = c
        while true do
          current match
            case Pure(_) => return current
            case Perform("Async", op, args) =>
              current = asyncParDispatch(op, args, v => Pure(v), ex, driver)
            case Perform(_, _, _) => return current
            case FlatMap(sub, f) => sub match
              case Pure(v)          => current = f(v)
              case FlatMap(s2, g)   => current = FlatMap(s2, x => FlatMap(g(x), f))
              case Perform("Async", op, args) =>
                current = asyncParDispatch(op, args, v => driver(f(v)), ex, driver)
              case Perform(_, _, _) =>
                return FlatMap(sub, v => driver(f(v)))
        throw InterpretError("unreachable")
      driver(initial)
    finally ex.shutdown()

  private def asyncParDispatch(
    op:     String,
    args:   List[Value],
    resume: Value => Computation,
    ex:     java.util.concurrent.ExecutorService,
    driver: Computation => Computation
  ): Computation = op match
    case "delay" => args match
      case List(Value.IntV(ms)) =>
        if ms > 0 then Thread.sleep(ms)
        resume(Value.UnitV)
      case _ => throw InterpretError("Async.delay(ms: Int)")
    case "async" => args match
      case List(thunk) =>
        // Submit the thunk to the pool; the future holds whatever
        // value its computation reduces to (driven through the same
        // inner driver so nested Async.* ops still hit this handler).
        val fut: java.util.concurrent.Future[Value] = ex.submit(
          new java.util.concurrent.Callable[Value]:
            def call(): Value = driver(callValue(thunk, Nil, Map.empty)) match
              case Pure(v) => v
              case _ => throw InterpretError(
                "Async.async thunk leaked an unhandled non-Async effect")
        )
        // Stash the Java Future inside the InstanceV through a
        // synthetic NativeFnV whose closure carries it — keeps the
        // Value ADT untouched while letting us round-trip the ref.
        val carrier = Value.NativeFnV("_futureRef", _ => {
          throw InterpretError("Future ref is opaque")
        })
        val fid = freshFutureId()
        parallelFutures.put(fid, fut)
        resume(Value.InstanceV("Future", Map(
          "_parId" -> Value.IntV(fid),
          "value"  -> carrier
        )))
      case _ => throw InterpretError("Async.async(thunk)")
    case "await" => args match
      case List(Value.InstanceV("Future", fields)) =>
        fields.get("_parId") match
          case Some(Value.IntV(fid)) =>
            val fut = parallelFutures.remove(fid)
            if fut == null then throw InterpretError("Async.await: stale Future")
            resume(fut.get())
          case _ =>
            // Single-thread Future (from runAsync) — unwrap directly.
            resume(fields.getOrElse("value", Value.UnitV))
      case _ => throw InterpretError("Async.await(future)")
    case "parallel" => args match
      case List(Value.ListV(thunks)) =>
        // Submit all thunks first, then block on each in declared
        // order so the result list mirrors input order regardless of
        // completion order.
        val futs = thunks.map { t =>
          ex.submit(new java.util.concurrent.Callable[Value]:
            def call(): Value = driver(callValue(t, Nil, Map.empty)) match
              case Pure(v) => v
              case _ => throw InterpretError(
                "Async.parallel thunk leaked an unhandled non-Async effect")
          )
        }
        val results = futs.map(_.get())
        resume(Value.ListV(results))
      case _ => throw InterpretError("Async.parallel(thunks: List[() => A])")
    case _ => throw InterpretError(s"Unknown Async operation: $op")

  private val parallelFutures =
    new java.util.concurrent.ConcurrentHashMap[Long, java.util.concurrent.Future[Value]]()
  private val parallelFutureSeq = new java.util.concurrent.atomic.AtomicLong(0L)
  private def freshFutureId(): Long = parallelFutureSeq.incrementAndGet()

  // ── Storage handler: walks the Free tree, dispatches Storage.* ops
  // against a local mutable Map; on each mutation flushes to JSON
  // file (file-backed mode) or skips persistence (ephemeral mode). ──

  private def storageDefaultPath: String =
    Option(java.lang.System.getenv("SSC_STORAGE_PATH"))
      .filter(_.nonEmpty)
      .getOrElse("./ssc-storage.json")

  private def storageInterp(
    initial: Computation,
    path:    Option[String]    // None = ephemeral / in-memory
  ): Computation =
    val state = scala.collection.mutable.LinkedHashMap.empty[String, String]
    path.foreach { p => storageLoad(p, state) }
    storageRun(initial, state, path)

  /** Drive a Free tree against an existing storage state.  Same shape
   *  as `asyncInterp`: handled `Perform("Storage", ...)` ops dispatch
   *  to the local map (file-flushed when `path` is set); other
   *  Performs propagate so an outer handler picks them up; the
   *  resume continuation re-enters `storageRun` with the same state
   *  so a FlatMap's producer and consumer share the map. */
  private def storageRun(
    initial: Computation,
    state:   scala.collection.mutable.LinkedHashMap[String, String],
    path:    Option[String]
  ): Computation =
    def flush(): Unit = path.foreach { p => storageSave(p, state) }
    def dispatch(op: String, args: List[Value], resume: Value => Computation): Computation =
      op match
        case "get" => args match
          case List(Value.StringV(k)) =>
            resume(state.get(k).map(v => Value.OptionV(Some(Value.StringV(v))))
                                .getOrElse(Value.OptionV(None)))
          case _ => throw InterpretError("Storage.get(key: String)")
        case "put" => args match
          case List(Value.StringV(k), v) =>
            state(k) = Value.show(v); flush(); resume(Value.UnitV)
          case _ => throw InterpretError("Storage.put(key: String, value)")
        case "remove" => args match
          case List(Value.StringV(k)) =>
            state.remove(k); flush(); resume(Value.UnitV)
          case _ => throw InterpretError("Storage.remove(key: String)")
        case "has" => args match
          case List(Value.StringV(k)) => resume(Value.BoolV(state.contains(k)))
          case _ => throw InterpretError("Storage.has(key: String)")
        case "keys" => args match
          case Nil => resume(Value.ListV(state.keys.toList.map(Value.StringV.apply)))
          case _   => throw InterpretError("Storage.keys()")
        case _ => throw InterpretError(s"Unknown Storage operation: $op")
    var current: Computation = initial
    while true do
      current match
        case Pure(_) => return current
        case Perform("Storage", op, args) =>
          current = dispatch(op, args, v => Pure(v))
        case Perform(_, _, _) => return current
        case FlatMap(sub, f) => sub match
          case Pure(v)          => current = f(v)
          case FlatMap(s2, g)   => current = FlatMap(s2, x => FlatMap(g(x), f))
          case Perform("Storage", op, args) =>
            current = dispatch(op, args, v => storageRun(f(v), state, path))
          case Perform(_, _, _) =>
            return FlatMap(sub, v => storageRun(f(v), state, path))
    throw InterpretError("unreachable")

  private def storageLoad(
    path:  String,
    state: scala.collection.mutable.LinkedHashMap[String, String]
  ): Unit =
    val p = java.nio.file.Paths.get(path)
    if java.nio.file.Files.exists(p) then
      val src = java.nio.file.Files.readString(p)
      storageParseJson(src).foreach { case (k, v) => state(k) = v }

  private def storageSave(
    path:  String,
    state: scala.collection.mutable.LinkedHashMap[String, String]
  ): Unit =
    val json = storageRenderJson(state.toList)
    java.nio.file.Files.writeString(java.nio.file.Paths.get(path), json)

  /** Minimal JSON parser for `{"key":"value", ...}` — Storage values
   *  are always strings so we don't need numbers / nested objects /
   *  arrays.  Returns entries in source order. */
  private def storageParseJson(src: String): List[(String, String)] =
    val s   = src.trim
    val out = scala.collection.mutable.ListBuffer.empty[(String, String)]
    if !s.startsWith("{") || !s.endsWith("}") then return Nil
    var i = 1
    val end = s.length - 1
    def skipWs(): Unit = while i < end && s.charAt(i).isWhitespace do i += 1
    def readStr(): String =
      if i >= end || s.charAt(i) != '"' then
        throw InterpretError(s"Storage JSON: expected string at index $i")
      i += 1
      val sb = StringBuilder()
      while i < end && s.charAt(i) != '"' do
        if s.charAt(i) == '\\' && i + 1 < end then
          s.charAt(i + 1) match
            case '"'  => sb.append('"');  i += 2
            case '\\' => sb.append('\\'); i += 2
            case 'n'  => sb.append('\n'); i += 2
            case 't'  => sb.append('\t'); i += 2
            case 'r'  => sb.append('\r'); i += 2
            case c    => sb.append(c);    i += 2
        else
          sb.append(s.charAt(i)); i += 1
      i += 1
      sb.toString
    skipWs()
    while i < end do
      val k = readStr(); skipWs()
      if i >= end || s.charAt(i) != ':' then
        throw InterpretError("Storage JSON: expected ':'")
      i += 1; skipWs()
      val v = readStr(); skipWs()
      out += (k -> v)
      if i < end && s.charAt(i) == ',' then i += 1
      skipWs()
    out.toList

  private def storageRenderJson(entries: List[(String, String)]): String =
    def esc(s: String): String =
      val sb = StringBuilder()
      sb.append('"')
      s.foreach {
        case '"'  => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case c    => sb.append(c)
      }
      sb.append('"').toString
    entries.map { case (k, v) => s"${esc(k)}:${esc(v)}" }.mkString("{", ",", "}")

  // ─── Infix operators ──────────────────────────────────────────────

  private def infix(lhs: Value, op: String, args: List[Value], env: Env): Computation =
    val rhs = args.headOption.getOrElse(Value.UnitV)
    (lhs, op, rhs) match
      // HTML DSL: `cls := "hero"` builds an Attr instance that tag fns
      // recognise as an attribute pair.
      case (Value.InstanceV("AttrKey", fields), ":=", v) =>
        val name = fields.get("name").map(Value.show).getOrElse("")
        Pure(Value.InstanceV("Attr", Map(
          "name"  -> Value.StringV(name),
          "value" -> Value.StringV(Value.show(v))
        )))
      // v1.6 actors — Pid ! msg fires off to the addressee's mailbox.
      // No-op if Pid is unknown (Erlang semantics).
      case (pid @ Value.InstanceV("Pid", _), "!", msg) =>
        Perform("Actor", "send", List(pid, msg))
      case (Value.IntV(a),    "+",  Value.IntV(b))    => Pure(Value.IntV(a + b))
      case (Value.IntV(a),    "-",  Value.IntV(b))    => Pure(Value.IntV(a - b))
      case (Value.IntV(a),    "*",  Value.IntV(b))    => Pure(Value.IntV(a * b))
      case (Value.IntV(a),    "/",  Value.IntV(b))    => Pure(Value.IntV(a / b))
      case (Value.IntV(a),    "%",  Value.IntV(b))    => Pure(Value.IntV(a % b))
      case (Value.DoubleV(a), "+",  Value.DoubleV(b)) => Pure(Value.DoubleV(a + b))
      case (Value.DoubleV(a), "-",  Value.DoubleV(b)) => Pure(Value.DoubleV(a - b))
      case (Value.DoubleV(a), "*",  Value.DoubleV(b)) => Pure(Value.DoubleV(a * b))
      case (Value.DoubleV(a), "/",  Value.DoubleV(b)) => Pure(Value.DoubleV(a / b))
      case (Value.IntV(a),    "+",  Value.DoubleV(b)) => Pure(Value.DoubleV(a + b))
      case (Value.DoubleV(a), "+",  Value.IntV(b))    => Pure(Value.DoubleV(a + b))
      case (Value.IntV(a),    "*",  Value.DoubleV(b)) => Pure(Value.DoubleV(a * b))
      case (Value.DoubleV(a), "*",  Value.IntV(b))    => Pure(Value.DoubleV(a * b))
      case (Value.IntV(a),    "-",  Value.DoubleV(b)) => Pure(Value.DoubleV(a - b))
      case (Value.DoubleV(a), "-",  Value.IntV(b))    => Pure(Value.DoubleV(a - b))
      case (Value.IntV(a),    "/",  Value.DoubleV(b)) => Pure(Value.DoubleV(a / b))
      case (Value.DoubleV(a), "/",  Value.IntV(b))    => Pure(Value.DoubleV(a / b))
      case (Value.StringV(a), "+",  b)                => Pure(Value.StringV(a + Value.show(b)))
      case (Value.StringV(a), "*",  Value.IntV(n))    => Pure(Value.StringV(a * n.toInt))
      case (a, "==",  b) => Pure(Value.BoolV(a == b))
      case (a, "!=",  b) => Pure(Value.BoolV(a != b))
      case (Value.IntV(a),    "<",  Value.IntV(b))    => Pure(Value.BoolV(a < b))
      case (Value.IntV(a),    ">",  Value.IntV(b))    => Pure(Value.BoolV(a > b))
      case (Value.IntV(a),    "<=", Value.IntV(b))    => Pure(Value.BoolV(a <= b))
      case (Value.IntV(a),    ">=", Value.IntV(b))    => Pure(Value.BoolV(a >= b))
      case (Value.DoubleV(a), "<",  Value.DoubleV(b)) => Pure(Value.BoolV(a < b))
      case (Value.DoubleV(a), ">",  Value.DoubleV(b)) => Pure(Value.BoolV(a > b))
      case (Value.DoubleV(a), "<=", Value.DoubleV(b)) => Pure(Value.BoolV(a <= b))
      case (Value.DoubleV(a), ">=", Value.DoubleV(b)) => Pure(Value.BoolV(a >= b))
      case (Value.BoolV(a),   "&&", Value.BoolV(b))   => Pure(Value.BoolV(a && b))
      case (Value.BoolV(a),   "||", Value.BoolV(b))   => Pure(Value.BoolV(a || b))
      case (v, "::",  Value.ListV(ls))                => Pure(Value.ListV(v :: ls))
      case (Value.ListV(a), "++", Value.ListV(b))     => Pure(Value.ListV(a ++ b))
      case (Value.ListV(a), ":::", Value.ListV(b))    => Pure(Value.ListV(a ++ b))
      case (Value.ListV(ls), ":+", v)                 => Pure(Value.ListV(ls :+ v))
      case (v, "+:",  Value.ListV(ls))                => Pure(Value.ListV(v +: ls))
      case (k, "->", v)                               => Pure(Value.TupleV(List(k, v)))
      // Fallback: method call on lhs
      case _ => dispatch(lhs, op, args, env)

  // ─── Dispatch ─────────────────────────────────────────────────────

  private def dispatch(recv: Value, name: String, args: List[Value], env: Env): Computation =
    (recv, name, args) match
      // ── String ──────────────────────────────────────────────────
      case (Value.StringV(s), "length",       Nil) => Pure(Value.IntV(s.length.toLong))
      case (Value.StringV(s), "size",         Nil) => Pure(Value.IntV(s.length.toLong))
      case (Value.StringV(s), "isEmpty",      Nil) => Pure(Value.BoolV(s.isEmpty))
      case (Value.StringV(s), "nonEmpty",     Nil) => Pure(Value.BoolV(s.nonEmpty))
      case (Value.StringV(s), "trim",         Nil) => Pure(Value.StringV(s.trim))
      case (Value.StringV(s), "toUpperCase",  Nil) => Pure(Value.StringV(s.toUpperCase))
      case (Value.StringV(s), "toLowerCase",  Nil) => Pure(Value.StringV(s.toLowerCase))
      case (Value.StringV(s), "reverse",      Nil) => Pure(Value.StringV(s.reverse))
      case (Value.StringV(s), "toInt",        Nil) => Pure(Value.IntV(s.toLong))
      case (Value.StringV(s), "toDouble",     Nil) => Pure(Value.DoubleV(s.toDouble))
      case (Value.StringV(s), "toString",     Nil) => Pure(Value.StringV(s))
      case (Value.StringV(s), "contains",     List(Value.StringV(t))) => Pure(Value.BoolV(s.contains(t)))
      case (Value.StringV(s), "startsWith",   List(Value.StringV(t))) => Pure(Value.BoolV(s.startsWith(t)))
      case (Value.StringV(s), "endsWith",     List(Value.StringV(t))) => Pure(Value.BoolV(s.endsWith(t)))
      case (Value.StringV(s), "split",        List(Value.StringV(sep))) =>
        Pure(Value.ListV(s.split(java.util.regex.Pattern.quote(sep)).toList.map(Value.StringV(_))))
      case (Value.StringV(s), "mkString",     _)  => Pure(Value.StringV(s))
      case (Value.StringV(s), "take",         List(Value.IntV(n))) => Pure(Value.StringV(s.take(n.toInt)))
      case (Value.StringV(s), "drop",         List(Value.IntV(n))) => Pure(Value.StringV(s.drop(n.toInt)))
      case (Value.StringV(s), "substring",    List(Value.IntV(a))) =>
        Pure(Value.StringV(s.substring(a.toInt.max(0).min(s.length))))
      case (Value.StringV(s), "substring",    List(Value.IntV(a), Value.IntV(b))) =>
        val from = a.toInt.max(0).min(s.length)
        val to   = b.toInt.max(from).min(s.length)
        Pure(Value.StringV(s.substring(from, to)))
      case (Value.StringV(s), "replace",      List(Value.StringV(a), Value.StringV(b))) => Pure(Value.StringV(s.replace(a, b)))
      case (Value.StringV(s), "charAt",       List(Value.IntV(i))) =>
        if i < 0 || i >= s.length then located(s"index $i out of bounds for string of length ${s.length}")
        else Pure(Value.CharV(s.charAt(i.toInt)))
      case (Value.StringV(s), "apply",        List(Value.IntV(i))) =>
        if i < 0 || i >= s.length then located(s"index $i out of bounds for string of length ${s.length}")
        else Pure(Value.CharV(s.charAt(i.toInt)))
      case (Value.StringV(s), "head",         Nil) =>
        if s.isEmpty then located("head on empty String") else Pure(Value.CharV(s.head))
      case (Value.StringV(s), "last",         Nil) =>
        if s.isEmpty then located("last on empty String") else Pure(Value.CharV(s.last))
      case (Value.StringV(s), "indexOf",      List(Value.StringV(t))) => Pure(Value.IntV(s.indexOf(t).toLong))
      case (Value.StringV(s), "indexOf",      List(Value.CharV(c)))   => Pure(Value.IntV(s.indexOf(c.toInt).toLong))
      case (Value.StringV(s), "codePointAt",  List(Value.IntV(i)))    =>
        if i < 0 || i >= s.length then located(s"index $i out of bounds for string of length ${s.length}")
        else Pure(Value.IntV(s.codePointAt(i.toInt).toLong))
      // ── Char ────────────────────────────────────────────────────
      case (Value.CharV(c), "toInt",      Nil) => Pure(Value.IntV(c.toInt.toLong))
      case (Value.CharV(c), "toLong",     Nil) => Pure(Value.IntV(c.toLong))
      case (Value.CharV(c), "toString",   Nil) => Pure(Value.StringV(c.toString))
      case (Value.CharV(c), "isDigit",    Nil) => Pure(Value.BoolV(c.isDigit))
      case (Value.CharV(c), "isLetter",   Nil) => Pure(Value.BoolV(c.isLetter))
      case (Value.StringV(s), "map",          List(f)) =>
        Computation.sequence(s.toList.map(c => callValue(f, List(Value.CharV(c)), env))).map {
          case Value.ListV(items) => Value.StringV(items.map(Value.show).mkString)
          case _                  => Value.StringV(s)
        }
      case (Value.StringV(s), "takeWhile",    List(f)) =>
        def loop(i: Int): Computation =
          if i >= s.length then Pure(Value.StringV(s))
          else callValue(f, List(Value.CharV(s.charAt(i))), env).flatMap {
            case Value.BoolV(true) => loop(i + 1)
            case _                 => Pure(Value.StringV(s.substring(0, i)))
          }
        loop(0)
      case (Value.StringV(s), "dropWhile",    List(f)) =>
        def loop(i: Int): Computation =
          if i >= s.length then Pure(Value.StringV(""))
          else callValue(f, List(Value.CharV(s.charAt(i))), env).flatMap {
            case Value.BoolV(true) => loop(i + 1)
            case _                 => Pure(Value.StringV(s.substring(i)))
          }
        loop(0)
      // ── List ────────────────────────────────────────────────────
      case (Value.ListV(ls), "length",     Nil)  => Pure(Value.IntV(ls.length.toLong))
      case (Value.ListV(ls), "size",       Nil)  => Pure(Value.IntV(ls.size.toLong))
      case (Value.ListV(ls), "indices",    Nil)  => Pure(Value.ListV(ls.indices.map(i => Value.IntV(i.toLong)).toList))
      case (Value.ListV(ls), "apply",      List(Value.IntV(i))) =>
        if i < 0 || i >= ls.length then located(s"index $i out of bounds for list of length ${ls.length}")
        else Pure(ls(i.toInt))
      case (Value.ListV(ls), "isEmpty",    Nil)  => Pure(Value.BoolV(ls.isEmpty))
      case (Value.ListV(ls), "nonEmpty",   Nil)  => Pure(Value.BoolV(ls.nonEmpty))
      case (Value.ListV(ls), "head",       Nil)  => Pure(ls.headOption.getOrElse(located("head on Nil")))
      case (Value.ListV(ls), "tail",       Nil)  => Pure(Value.ListV(ls.tail))
      case (Value.ListV(ls), "last",       Nil)  => Pure(ls.lastOption.getOrElse(located("last on Nil")))
      case (Value.ListV(ls), "init",       Nil)  => Pure(Value.ListV(ls.init))
      case (Value.ListV(ls), "reverse",    Nil)  => Pure(Value.ListV(ls.reverse))
      case (Value.ListV(ls), "distinct",   Nil)  => Pure(Value.ListV(ls.distinct))
      case (Value.ListV(ls), "sorted",     Nil)  => Pure(Value.ListV(ls.sortBy(Value.show)))
      case (Value.ListV(ls), "toList",     Nil)  => Pure(Value.ListV(ls))
      case (Value.ListV(ls), "toSet",      Nil)  => Pure(Value.ListV(ls.distinct))
      case (Value.ListV(ls), "contains",   List(v)) => Pure(Value.BoolV(ls.contains(v)))
      case (Value.ListV(ls), "indexOf",    List(v)) => Pure(Value.IntV(ls.indexOf(v).toLong))
      case (Value.ListV(ls), "take",       List(Value.IntV(n))) => Pure(Value.ListV(ls.take(n.toInt)))
      case (Value.ListV(ls), "drop",       List(Value.IntV(n))) => Pure(Value.ListV(ls.drop(n.toInt)))
      case (Value.ListV(ls), "splitAt",    List(Value.IntV(n))) =>
        val (a, b) = ls.splitAt(n.toInt)
        Pure(Value.TupleV(List(Value.ListV(a), Value.ListV(b))))
      case (Value.ListV(ls), "takeRight",  List(Value.IntV(n))) => Pure(Value.ListV(ls.takeRight(n.toInt)))
      case (Value.ListV(ls), "dropRight",  List(Value.IntV(n))) => Pure(Value.ListV(ls.dropRight(n.toInt)))
      // Higher-order: sequence the callback computations
      case (Value.ListV(ls), "map",        List(f)) =>
        Computation.sequence(ls.map(item => callValue(f, List(item), env)))
      case (Value.ListV(ls), "flatMap",    List(f)) =>
        Computation.sequence(ls.map(item => callValue(f, List(item), env))).map {
          case Value.ListV(results) => Value.ListV(results.flatMap {
            case Value.ListV(inner) => inner
            case v                  => List(v)
          })
          case other => other
        }
      case (Value.ListV(ls), "filter",     List(f)) =>
        Computation.sequence(ls.map(item => callValue(f, List(item), env))).map {
          case Value.ListV(flags) =>
            Value.ListV(ls.zip(flags).collect { case (v, Value.BoolV(true)) => v })
          case other => other
        }
      case (Value.ListV(ls), "filterNot",  List(f)) =>
        Computation.sequence(ls.map(item => callValue(f, List(item), env))).map {
          case Value.ListV(flags) =>
            Value.ListV(ls.zip(flags).collect { case (v, Value.BoolV(false)) => v })
          case other => other
        }
      case (Value.ListV(ls), "foreach",    List(f)) =>
        Computation.sequence(ls.map(item => callValue(f, List(item), env))).map(_ => Value.UnitV)
      case (Value.ListV(ls), "count",      List(f)) =>
        Computation.sequence(ls.map(item => callValue(f, List(item), env))).map {
          case Value.ListV(flags) =>
            Value.IntV(flags.count { case Value.BoolV(true) => true; case _ => false }.toLong)
          case _ => Value.IntV(0)
        }
      case (Value.ListV(ls), "find",       List(f)) =>
        def loop(remaining: List[Value]): Computation = remaining match
          case Nil => Pure(Value.OptionV(None))
          case h :: rest =>
            callValue(f, List(h), env).flatMap {
              case Value.BoolV(true) => Pure(Value.OptionV(Some(h)))
              case _                 => loop(rest)
            }
        loop(ls)
      case (Value.ListV(ls), "exists",     List(f)) =>
        def loop(remaining: List[Value]): Computation = remaining match
          case Nil => Pure(Value.BoolV(false))
          case h :: rest =>
            callValue(f, List(h), env).flatMap {
              case Value.BoolV(true) => Pure(Value.BoolV(true))
              case _                 => loop(rest)
            }
        loop(ls)
      case (Value.ListV(ls), "forall",     List(f)) =>
        def loop(remaining: List[Value]): Computation = remaining match
          case Nil => Pure(Value.BoolV(true))
          case h :: rest =>
            callValue(f, List(h), env).flatMap {
              case Value.BoolV(false) => Pure(Value.BoolV(false))
              case _                  => loop(rest)
            }
        loop(ls)
      case (Value.ListV(ls), "sortBy",     List(f)) =>
        Computation.sequence(ls.map(item => callValue(f, List(item), env))).map {
          case Value.ListV(keys) =>
            Value.ListV(ls.zip(keys).sortBy(p => Value.show(p._2)).map(_._1))
          case _ => Value.ListV(ls)
        }
      case (Value.ListV(ls), "zip",        List(Value.ListV(other))) =>
        Pure(Value.ListV(ls.zip(other).map { case (a, b) => Value.TupleV(List(a, b)) }))
      case (Value.ListV(ls), "zipWithIndex", Nil) =>
        Pure(Value.ListV(ls.zipWithIndex.map { case (a, i) => Value.TupleV(List(a, Value.IntV(i.toLong))) }))
      case (Value.ListV(ls), "mkString",   Nil)  => Pure(Value.StringV(ls.map(Value.show).mkString))
      case (Value.ListV(ls), "mkString",   List(Value.StringV(sep))) =>
        Pure(Value.StringV(ls.map(Value.show).mkString(sep)))
      case (Value.ListV(ls), "mkString",   List(Value.StringV(s), Value.StringV(sep), Value.StringV(e))) =>
        Pure(Value.StringV(ls.map(Value.show).mkString(s, sep, e)))
      case (Value.ListV(ls), "sum",        Nil)  =>
        Pure(ls.foldLeft[Value](Value.IntV(0)) {
          case (Value.IntV(a),    Value.IntV(b))    => Value.IntV(a + b)
          case (Value.DoubleV(a), Value.DoubleV(b)) => Value.DoubleV(a + b)
          case (Value.IntV(a),    Value.DoubleV(b)) => Value.DoubleV(a + b)
          case (Value.DoubleV(a), Value.IntV(b))    => Value.DoubleV(a + b)
          case (a, b) => located(s"Cannot sum $a and $b")
        })
      case (Value.ListV(ls), "min",        Nil)  =>
        Pure(ls.minBy { case Value.IntV(n) => n.toDouble; case Value.DoubleV(d) => d; case _ => 0.0 })
      case (Value.ListV(ls), "max",        Nil)  =>
        Pure(ls.maxBy { case Value.IntV(n) => n.toDouble; case Value.DoubleV(d) => d; case _ => 0.0 })
      case (Value.ListV(ls), "foldLeft",   List(init)) =>
        Pure(Value.NativeFnV("foldLeft", {
          case List(f) =>
            def loop(remaining: List[Value], acc: Value): Computation = remaining match
              case Nil       => Pure(acc)
              case h :: rest => callValue(f, List(acc, h), env).flatMap(v => loop(rest, v))
            loop(ls, init)
          case _ => throw InterpretError("foldLeft expects one function argument")
        }))
      case (Value.ListV(ls), "foldRight",  List(init)) =>
        Pure(Value.NativeFnV("foldRight", {
          case List(f) =>
            def loop(remaining: List[Value], acc: Value): Computation = remaining match
              case Nil       => Pure(acc)
              case h :: rest => callValue(f, List(h, acc), env).flatMap(v => loop(rest, v))
            loop(ls.reverse, init)
          case _ => throw InterpretError("foldRight expects one function argument")
        }))
      case (Value.ListV(ls), "reduceLeft", List(f)) =>
        ls match
          case Nil => located("reduceLeft on empty list")
          case h :: t =>
            def loop(remaining: List[Value], acc: Value): Computation = remaining match
              case Nil       => Pure(acc)
              case x :: rest => callValue(f, List(acc, x), env).flatMap(v => loop(rest, v))
            loop(t, h)
      case (Value.ListV(ls), "flatten",    Nil) =>
        Pure(Value.ListV(ls.flatMap { case Value.ListV(inner) => inner; case v => List(v) }))
      case (Value.ListV(ls), "sliding",    List(Value.IntV(n))) =>
        Pure(Value.ListV(ls.sliding(n.toInt).map(Value.ListV(_)).toList))
      case (Value.ListV(ls), "grouped",    List(Value.IntV(n))) =>
        Pure(Value.ListV(ls.grouped(n.toInt).map(Value.ListV(_)).toList))
      case (Value.ListV(ls), "appended",   List(v)) => Pure(Value.ListV(ls :+ v))
      case (Value.ListV(ls), "prepended",  List(v)) => Pure(Value.ListV(v +: ls))
      // ── Map ─────────────────────────────────────────────────────
      case (Value.MapV(m), "size",       Nil)     => Pure(Value.IntV(m.size.toLong))
      case (Value.MapV(m), "isEmpty",    Nil)     => Pure(Value.BoolV(m.isEmpty))
      case (Value.MapV(m), "nonEmpty",   Nil)     => Pure(Value.BoolV(m.nonEmpty))
      case (Value.MapV(m), "keys",       Nil)     => Pure(Value.ListV(m.keys.toList))
      case (Value.MapV(m), "values",     Nil)     => Pure(Value.ListV(m.values.toList))
      case (Value.MapV(m), "toList",     Nil)     =>
        Pure(Value.ListV(m.toList.map { (k, v) => Value.TupleV(List(k, v)) }))
      case (Value.MapV(m), "contains",   List(k)) => Pure(Value.BoolV(m.contains(k)))
      case (Value.MapV(m), "get",        List(k)) => Pure(Value.OptionV(m.get(k)))
      case (Value.MapV(m), "apply",      List(k)) =>
        Pure(m.getOrElse(k, located(s"Key not found: ${Value.show(k)}")))
      case (Value.MapV(m), "getOrElse",  List(k, d)) => Pure(m.getOrElse(k, d))
      case (Value.MapV(m), "updated",    List(k, v)) => Pure(Value.MapV(m + (k -> v)))
      case (Value.MapV(m), "removed",    List(k))    => Pure(Value.MapV(m - k))
      // Scala syntax: `m + (k -> v)` parses as `m.+((k, v))` — accept the
      // tupled form as a shortcut for `.updated`, and `++` for map merge.
      case (Value.MapV(m), "+",  List(Value.TupleV(List(k, v))))  => Pure(Value.MapV(m + (k -> v)))
      case (Value.MapV(m), "++", List(Value.MapV(other)))         => Pure(Value.MapV(m ++ other))
      case (Value.MapV(m), "-",  List(k))                          => Pure(Value.MapV(m - k))
      case (Value.MapV(m), "map",        List(f)) =>
        Computation.sequence(m.toList.map { (k, v) =>
          callValue(f, List(Value.TupleV(List(k, v))), env)
        }).map {
          case Value.ListV(entries) =>
            Value.MapV(entries.collect {
              case Value.TupleV(List(nk, nv)) => nk -> nv
            }.toMap)
          case _ => Value.MapV(Map.empty)
        }
      case (Value.MapV(m), "filter",     List(f)) =>
        val items = m.toList
        Computation.sequence(items.map { (k, v) =>
          callValue(f, List(Value.TupleV(List(k, v))), env)
        }).map {
          case Value.ListV(flags) =>
            Value.MapV(items.zip(flags).collect {
              case ((k, v), Value.BoolV(true)) => k -> v
            }.toMap)
          case _ => Value.MapV(Map.empty)
        }
      case (Value.MapV(m), "foreach",    List(f)) =>
        Computation.sequence(m.toList.map { (k, v) =>
          callValue(f, List(Value.TupleV(List(k, v))), env)
        }).map(_ => Value.UnitV)
      case (Value.MapV(m), "mkString",   Nil)  => Pure(Value.StringV(Value.show(Value.MapV(m))))
      // String-key access shorthand: map.key
      case (Value.MapV(m), key, Nil) =>
        Pure(m.getOrElse(Value.StringV(key), located(s"No key '$key' in map")))
      // ── Option ──────────────────────────────────────────────────
      case (Value.OptionV(Some(v)), "get",        Nil) => Pure(v)
      case (Value.OptionV(opt),     "isDefined",  Nil) => Pure(Value.BoolV(opt.isDefined))
      case (Value.OptionV(opt),     "isEmpty",    Nil) => Pure(Value.BoolV(opt.isEmpty))
      case (Value.OptionV(opt),     "nonEmpty",   Nil) => Pure(Value.BoolV(opt.nonEmpty))
      case (Value.OptionV(opt),     "contains",   List(v)) => Pure(Value.BoolV(opt.contains(v)))
      case (Value.OptionV(Some(v)), "getOrElse",  _)   => Pure(v)
      case (Value.OptionV(None),    "getOrElse",  List(d)) => Pure(d)
      case (Value.OptionV(opt),     "map",        List(f)) =>
        opt match
          case None    => Pure(Value.OptionV(None))
          case Some(v) => callValue(f, List(v), env).map(r => Value.OptionV(Some(r)))
      case (Value.OptionV(opt),     "flatMap",    List(f)) =>
        opt match
          case None    => Pure(Value.OptionV(None))
          case Some(v) => callValue(f, List(v), env).map {
            case o: Value.OptionV => o
            case other            => Value.OptionV(Some(other))
          }
      case (Value.OptionV(opt),     "filter",     List(f)) =>
        opt match
          case None    => Pure(Value.OptionV(None))
          case Some(v) => callValue(f, List(v), env).map {
            case Value.BoolV(true) => Value.OptionV(Some(v))
            case _                 => Value.OptionV(None)
          }
      case (Value.OptionV(opt),     "foreach",    List(f)) =>
        opt match
          case None    => Pure(Value.UnitV)
          case Some(v) => callValue(f, List(v), env).map(_ => Value.UnitV)
      case (Value.OptionV(opt),     "toList",     Nil) => Pure(Value.ListV(opt.toList))
      case (Value.OptionV(None),    "orElse",     List(other)) => Pure(other)
      case (opt: Value.OptionV,     "orElse",     _) => Pure(opt)
      // ── Int / Double ─────────────────────────────────────────────
      case (Value.IntV(n),    "toDouble",  Nil) => Pure(Value.DoubleV(n.toDouble))
      case (Value.IntV(n),    "toLong",    Nil) => Pure(Value.IntV(n))
      case (Value.IntV(n),    "toInt",     Nil) => Pure(Value.IntV(n))
      case (Value.IntV(n),    "abs",       Nil) => Pure(Value.IntV(math.abs(n)))
      case (Value.IntV(n),    "toString",  Nil) => Pure(Value.StringV(n.toString))
      case (Value.IntV(n),    "to",        List(Value.IntV(m))) =>
        Pure(Value.ListV((n to m).map(Value.IntV(_)).toList))
      case (Value.IntV(n),    "until",     List(Value.IntV(m))) =>
        Pure(Value.ListV((n until m).map(Value.IntV(_)).toList))
      case (Value.DoubleV(d), "toInt",     Nil) => Pure(Value.IntV(d.toLong))
      case (Value.DoubleV(d), "toLong",    Nil) => Pure(Value.IntV(d.toLong))
      case (Value.DoubleV(d), "abs",       Nil) => Pure(Value.DoubleV(math.abs(d)))
      case (Value.DoubleV(d), "toString",  Nil) => Pure(Value.StringV(d.toString))
      case (Value.DoubleV(d), "round",     Nil) => Pure(Value.IntV(math.round(d)))
      case (Value.DoubleV(d), "floor",     Nil) => Pure(Value.DoubleV(math.floor(d)))
      case (Value.DoubleV(d), "ceil",      Nil) => Pure(Value.DoubleV(math.ceil(d)))
      // ── Tuple ────────────────────────────────────────────────────
      case (Value.TupleV(es), "_1", Nil) => Pure(es(0))
      case (Value.TupleV(es), "_2", Nil) => Pure(es(1))
      case (Value.TupleV(es), "_3", Nil) => Pure(es(2))
      case (Value.TupleV(es), "_4", Nil) => Pure(es(3))
      // ── Signal methods (reactive) — must precede the generic InstanceV
      // field-access paths so `.get`/`.set` don't fall into them. ──
      case (Value.InstanceV("Signal", fields), "get", Nil) =>
        fields.get("id") match
          case Some(Value.IntV(id)) => Pure(signalGet(id))
          case _                    => located("Signal handle missing id")
      case (Value.InstanceV("Signal", fields), "set", List(v)) =>
        fields.get("id") match
          case Some(Value.IntV(id)) => signalSet(id, v); Pure(Value.UnitV)
          case _                    => located("Signal handle missing id")
      case (Value.InstanceV("Signal", fields), "apply", Nil) =>
        // s() — sugar for s.get
        fields.get("id") match
          case Some(Value.IntV(id)) => Pure(signalGet(id))
          case _                    => located("Signal handle missing id")
      // ── Class method (declared inside `class`/`case class` body) ──
      case (Value.InstanceV(typeName, fields), fname, fargs)
        if typeMethods.get(typeName).exists(_.contains(fname)) =>
        val fn = typeMethods(typeName)(fname)
        // Re-bind the method's closure with this instance's data fields so the
        // body can refer to them by name (`x`, `y`, …).
        callFun(fn.copy(closure = fn.closure ++ fields), fargs)
      // ── Response builder methods (cookie sessions) ───────────────
      // `resp.withSession(Map(...))` / `resp.clearSession()` attach a
      // `setSession` field; the HTTP runtime turns that into a Set-Cookie.
      // Must precede the InstanceV no-arg / enum-companion cases below so
      // `clearSession()` and `withSession(...)` aren't shadowed by field
      // lookup on the Response instance.
      case (Value.InstanceV("Response", fields), "withSession", List(Value.MapV(m))) =>
        Pure(Value.InstanceV("Response", fields + ("setSession" -> Value.MapV(m))))
      case (Value.InstanceV("Response", fields), "clearSession", Nil) =>
        Pure(Value.InstanceV("Response", fields + ("setSession" -> Value.MapV(Map.empty))))
      // `resp.withHeader("X-Trace-Id", "abc")` — used by std/middleware.ssc
      // to attach observability headers without rebuilding the Response
      // by hand.  Merges into the existing `headers` Map; later
      // withHeader calls overwrite earlier ones for the same key.
      case (Value.InstanceV("Response", fields), "withHeader", List(Value.StringV(name), Value.StringV(value))) =>
        val existing = fields.get("headers") match
          case Some(Value.MapV(m)) => m
          case _                   => Map.empty[Value, Value]
        val merged = existing + (Value.StringV(name) -> Value.StringV(value))
        Pure(Value.InstanceV("Response", fields + ("headers" -> Value.MapV(merged))))
      // ── Instance (case class / enum case) field access ───────────
      // No-arg defs and no-arg native fns are called automatically on access
      case (Value.InstanceV(_, fields), fname, Nil) =>
        fields.get(fname) match
          case Some(f: Value.FunV)      if f.params.isEmpty => callFun(f, Nil)
          case Some(f: Value.NativeFnV)                     => f.f(Nil)
          case Some(v)                                       => Pure(v)
          // Scala's auto-generated `toString` on case classes / enum cases:
          // fall through to the generic Value.show path so users get the
          // expected "Circle(3.0)" rendering without having to define it.
          case None if fname == "toString"                   => Pure(Value.StringV(Value.show(recv)))
          case None                                          => located(s"No field '$fname'")
      // ── Enum companion call (Color.RGB(1,2,3)) ───────────────────
      case (Value.InstanceV(_, fields), fname, fargs) if fields.contains(fname) =>
        callValue(fields(fname), fargs, env)
      // ── Generic ──────────────────────────────────────────────────
      case (v, "toString", Nil)   => Pure(Value.StringV(Value.show(v)))
      case (v, "apply",    fargs) => callValue(v, fargs, env)
      // ── Extension method via given: "hello".show → Show[String].show("hello")
      case _ =>
        extensionDispatch(recv, name, args, env)
          .getOrElse(located(s"No method '$name' on ${recv.getClass.getSimpleName}(${Value.show(recv)})"))

  private def extensionDispatch(recv: Value, method: String, args: List[Value], env: Env): Option[Computation] =
    val typeName = recv match
      case _: Value.IntV        => "Int"
      case _: Value.DoubleV     => "Double"
      case _: Value.StringV     => "String"
      case _: Value.BoolV       => "Boolean"
      case _: Value.ListV       => "List"
      case _: Value.OptionV     => "Option"
      case _: Value.MapV        => "Map"
      case Value.TupleV(elems)  => s"Tuple${elems.length}"
      case Value.InstanceV(t,_) => t
      case _                    => "Any"
    extensions.get((typeName, method)).map { fn =>
      callValue(fn, recv :: args, env)
    }.orElse {
      globals.values.collectFirst {
        case Value.InstanceV(name, fields)
          if name.endsWith(s"[$typeName]") && fields.contains(method) =>
          callValue(fields(method), recv :: args, env)
      }
    }

  // ─── Pattern matching ────────────────────────────────────────────

  private def matchPat(pat: Pat, scrutinee: Value, env: Env): Option[Env] = pat match
    case Pat.Wildcard()  => Some(env)
    case Pat.Var(name)   => Some(env + (name.value -> scrutinee))
    case lit: Lit =>
      val litV: Value = lit match
        case Lit.Int(v)     => Value.IntV(v.toLong)
        case Lit.Long(v)    => Value.IntV(v)
        case Lit.String(v)  => Value.StringV(v)
        case Lit.Boolean(v) => Value.BoolV(v)
        case Lit.Double(v)  => Value.DoubleV(v.toString.toDouble)
        case Lit.Null()     => Value.NullV
        case _              => Value.NullV
      Option.when(litV == scrutinee)(env)
    case Pat.Tuple(pats) =>
      scrutinee match
        case Value.TupleV(elems) if elems.length == pats.length =>
          pats.zip(elems).foldLeft(Option(env)) { (acc, pe) =>
            acc.flatMap(e => matchPat(pe._1, pe._2, e))
          }
        case _ => None
    case Pat.Extract.After_4_6_0(fn, argClause) =>
      val typeNameOpt = fn match
        case Term.Name(n)                 => Some(n)
        case Term.Select(_, Term.Name(n)) => Some(n)
        case _                            => None
      typeNameOpt.flatMap { typeName =>
        val args = argClause.values
        scrutinee match
          case Value.InstanceV(t, fields) if t == typeName =>
            val fieldVals = fields.values.toList
            if args.length != fieldVals.length then None
            else args.zip(fieldVals).foldLeft(Option(env)) { (acc, pe) =>
              acc.flatMap(e => matchPat(pe._1, pe._2, e))
            }
          case Value.OptionV(Some(v)) if typeName == "Some" && args.length == 1 =>
            matchPat(args.head, v, env)
          case Value.OptionV(None) if typeName == "None" && args.isEmpty =>
            Some(env)
          case _ => None
      }
    // List cons pattern: `head :: tail` matches a non-empty ListV.
    case Pat.ExtractInfix.After_4_6_0(headPat, Term.Name("::"), tailClause) =>
      scrutinee match
        case Value.ListV(h :: t) if tailClause.values.length == 1 =>
          matchPat(headPat, h, env).flatMap(e =>
            matchPat(tailClause.values.head, Value.ListV(t), e))
        case _ => None
    case Pat.Typed(inner, _)       => matchPat(inner, scrutinee, env)
    case Pat.Alternative(lhs, rhs) =>
      List(lhs, rhs).iterator.flatMap(p => matchPat(p, scrutinee, env)).nextOption()
    case t: Term.Name =>
      env.get(t.value).orElse(globals.get(t.value))
        .flatMap(v => Option.when(v == scrutinee)(env))
    case Term.Select(_, Term.Name(n)) =>
      env.get(n).orElse(globals.get(n))
        .flatMap(v => Option.when(v == scrutinee)(env))
    case _ => None

  // ─── For comprehension helpers ────────────────────────────────────

  private def evalCollection(v: Value): List[Value] = v match
    case Value.ListV(ls)        => ls
    case Value.OptionV(Some(v)) => List(v)
    case Value.OptionV(None)    => Nil
    case _ => located(s"Cannot iterate over ${Value.show(v)}")

  private def patVarNames(pat: Pat): Set[String] = pat match
    case Pat.Var(n)           => Set(n.value)
    case Pat.Wildcard()       => Set.empty
    case Pat.Tuple(pats)      => pats.flatMap(patVarNames).toSet
    case Pat.Extract.After_4_6_0(_, argClause) => argClause.values.flatMap(patVarNames).toSet
    case Pat.Typed(inner, _)  => patVarNames(inner)
    case _                    => Set.empty

  private def evalForYield(enums: List[Enumerator], body: Term, env: Env): Computation =
    enums match
      case Nil => eval(body, env)
      case Enumerator.Generator(pat, rhs) :: rest =>
        eval(rhs, env).flatMap { rhsV =>
          val items = evalCollection(rhsV)
          val isLast = rest.isEmpty
          // Evaluate each branch; matches that fail are skipped.
          val branches = items.flatMap { item =>
            matchPat(pat, item, env).map(patEnv => evalForYield(rest, body, patEnv))
          }
          Computation.sequence(branches).map {
            case Value.ListV(results) if isLast =>
              Value.ListV(results)
            case Value.ListV(results) =>
              Value.ListV(results.flatMap {
                case Value.ListV(ls) => ls
                case v               => List(v)
              })
            case other => other
          }
        }
      case Enumerator.Guard(cond) :: rest =>
        eval(cond, env).flatMap {
          case Value.BoolV(true) => evalForYield(rest, body, env)
          case _                 => Pure(Value.ListV(Nil))
        }
      case Enumerator.Val(pat, rhs) :: rest =>
        eval(rhs, env).flatMap { v =>
          matchPat(pat, v, env) match
            case Some(patEnv) => evalForYield(rest, body, patEnv)
            case None         => Pure(Value.ListV(Nil))
        }
      case _ :: rest => evalForYield(rest, body, env)

  // evalForDo keeps loop vars separate from globals so assignments to outer vars are visible.
  private def evalForDo(enums: List[Enumerator], body: Term, outerEnv: Env, loopVars: Env): Computation =
    val env = outerEnv ++ globals.toMap ++ loopVars
    enums match
      case Nil => eval(body, env).map(_ => Value.UnitV)
      case Enumerator.Generator(pat, rhs) :: rest =>
        eval(rhs, env).flatMap { rhsV =>
          val items = evalCollection(rhsV)
          def loop(remaining: List[Value]): Computation = remaining match
            case Nil => Pure(Value.UnitV)
            case item :: tail =>
              matchPat(pat, item, env) match
                case Some(patEnv) =>
                  val newVars = patVarNames(pat).map(k => k -> patEnv(k)).toMap
                  evalForDo(rest, body, outerEnv, loopVars ++ newVars).flatMap(_ => loop(tail))
                case None => loop(tail)
          loop(items)
        }
      case Enumerator.Guard(cond) :: rest =>
        eval(cond, env).flatMap {
          case Value.BoolV(true) => evalForDo(rest, body, outerEnv, loopVars)
          case _                 => Pure(Value.UnitV)
        }
      case Enumerator.Val(pat, rhs) :: rest =>
        eval(rhs, env).flatMap { v =>
          matchPat(pat, v, env) match
            case Some(patEnv) =>
              val newVars = patVarNames(pat).map(k => k -> patEnv(k)).toMap
              evalForDo(rest, body, outerEnv, loopVars ++ newVars)
            case None => Pure(Value.UnitV)
        }
      case _ :: rest => evalForDo(rest, body, outerEnv, loopVars)

  private def autoOutput(v: Value): Unit = v match
    case Value.UnitV => ()
    case _           => out.println(Value.show(v))

  private def stripIndent(s: String): String =
    val lines = s.split('\n').toList
    val body  = lines.dropWhile(_.isBlank).reverse.dropWhile(_.isBlank).reverse
    if body.isEmpty then ""
    else
      val minIndent = body.filter(_.exists(_ != ' ')).map(_.takeWhile(_ == ' ').length).minOption.getOrElse(0)
      body.map(l => if l.isBlank then "" else l.drop(minIndent)).mkString("\n")

  def runSnippet(code: String): Unit =
    import scalascript.parser.Parser
    val src    = s"# Snippet\n\n```scala\n$code\n```\n"
    val module = Parser.parse(src)
    module.sections.foreach(runSection)

  // ── v1.4 Logger effect ─────────────────────────────────────────────────
  //
  // Walk the Free tree; intercept Perform("Logger", …) nodes and write to
  // the supplied PrintStream.  Non-Logger Performs propagate outward.
  //
  // format = "text" → "[LEVEL] msg\n"
  // format = "json" → {"level":"…","msg":"…"}\n

  private def loggerRun(
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
  // `run` drives the body and returns the raw body result; the outer
  // flatMap attaches the accumulated log once (avoids double-wrapping
  // when `run` is called recursively from dispatch continuations).
  private def loggerToListRun(initial: Computation): Computation =
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
        Value.TupleV(List(Value.StringV(lv), Value.StringV(msg)))
      })
      Pure(Value.TupleV(List(v, entries)))
    }

  private def loggerJsonStr(s: String): String =
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

  // ── v1.4 Random effect ─────────────────────────────────────────────────
  //
  // seed = None  → ThreadLocalRandom (non-deterministic)
  // seed = Some  → java.util.Random(seed) (deterministic / test-friendly)

  private def randomRun(initial: Computation, seed: Option[Long]): Computation =
    val rng = seed.fold(
      new java.util.Random(): java.util.Random
    )(s => new java.util.Random(s))
    def dispatch(op: String, args: List[Value], resume: Value => Computation): Computation =
      op match
        case "nextInt" => args match
          case List(Value.IntV(n)) =>
            resume(Value.IntV(rng.nextInt(n.toInt).toLong))
          case _ => throw InterpretError("Random.nextInt(n: Int)")
        case "nextDouble" =>
          resume(Value.DoubleV(rng.nextDouble()))
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

  // ── v1.4 Clock effect ──────────────────────────────────────────────────
  //
  // frozen = None    → real wall clock; Clock.sleep → Thread.sleep(ms)
  // frozen = Some(t) → always returns t; Clock.sleep is a no-op

  private def clockRun(initial: Computation, frozen: Option[Long]): Computation =
    def nowMs(): Long  = frozen.getOrElse(java.lang.System.currentTimeMillis())
    def nowIso(): String =
      val inst = java.time.Instant.ofEpochMilli(nowMs())
      java.time.format.DateTimeFormatter.ISO_INSTANT.format(inst)
    def dispatch(op: String, args: List[Value], resume: Value => Computation): Computation =
      op match
        case "now"    => resume(Value.IntV(nowMs()))
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

  // ── v1.4 Env effect ────────────────────────────────────────────────────
  //
  // overlay = None      → read from real process env; Env.set mutates a
  //                       local overlay (does not touch the real env)
  // overlay = Some(map) → reads from map first, then process env for misses;
  //                       Env.set mutates the overlay

  private def envRun(
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
            resume(Value.OptionV(lookup(k).map(Value.StringV.apply)))
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

object Interpreter:
  def run(module: Module, out: java.io.PrintStream = System.out, baseDir: Option[os.Path] = None): Unit =
    Interpreter(out, baseDir).run(module)
