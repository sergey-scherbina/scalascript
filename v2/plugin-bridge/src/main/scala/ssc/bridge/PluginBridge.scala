package ssc.bridge

import scalascript.backend.spi.{Backend, BlockContext, BlockForm, EffectHandler, IntrinsicImpl, NativeImpl, NativeContext, SpiValue}
import scalascript.interpreter.{DataValue, Value as V1Value}
import scalascript.interpreter.DataValue.*
import ssc.{Done, Runtime, Show, Value as V2Value, V2EffectContext, V2PluginRegistry}

/** Loads v1 Backend plugins from the classpath and registers:
 *   1. NativeImpl intrinsics — via V2PluginRegistry.register (existing).
 *   2. BlockForm effect runners (runLogger, runState, …) — as synthetic v2
 *      ClosV globals in V2PluginRegistry.registerGlobal. Each runner installs
 *      a V2EffectContext handler for the duration of its body, then pops it.
 *      Logger.log/State.get/etc. dispatch through __method__ → V2EffectContext.
 *   3. `handle` global — runs the Free-monad loop for typed `handle { case … }` effects.
 *
 *  Usage: call PluginBridge.loadAll() before running any v2 program that needs plugins. */
/** Preserves the tag from v1 Value.Foreign("tag", h) through the v2 ForeignV round-trip. */
private final case class TaggedForeign(tag: String, value: AnyRef)

/** `throw expr` in bridged code: carries the thrown v2 VALUE so try/catch
 *  handlers can pattern-match it (message-only InterpretError lost the value). */
final case class BridgeThrow(value: ssc.Value) extends RuntimeException:
  override def getMessage: String = value.toString

object PluginBridge:

  // ── DB connection registry for `databases:` frontmatter ─────────────────
  // Programs with `databases: default: url: jdbc:h2:...` frontmatter register
  // connections here before running so `Db.query`/`Db.execute` work under v2.
  private val dbRegistry = new java.util.concurrent.ConcurrentHashMap[String, java.sql.Connection]()

  /** Register an in-process JDBC connection by name (called by FrontendBridge
   *  when it processes `databases:` YAML frontmatter). */
  def registerDb(name: String, url: String): Unit =
    val conn = java.sql.DriverManager.getConnection(url)
    dbRegistry.put(name, conn)

  /** Clear all registered DB connections (call between batch runs). */
  def clearDbs(): Unit = dbRegistry.clear()

  /** Minimal NativeContext for stateless intrinsics (IO, hash, math, etc.). */
  private object MinimalCtx extends NativeContext:
    def out: java.io.PrintStream = Console.out
    def err: java.io.PrintStream = Console.err
    override def dbConnect(dbName: String): java.sql.Connection =
      Option(dbRegistry.get(dbName)).getOrElse(
        throw new RuntimeException(
          s"No database registered for '$dbName' — add a databases: section to front-matter"))

  /** Load all Backend plugins via ServiceLoader; register NativeImpl prims AND
   *  BlockForm runners. Also registers the built-in `handle` global. */
  def loadAll(): Int =
    registerHandle()
    registerSys()
    registerInterpreterBuiltins()
    registerAmbientEffectOps()
    registerOptics()
    registerComputedCellDispatch()
    var count = 0
    val cl = Thread.currentThread().getContextClassLoader
    val loader = java.util.ServiceLoader.load(classOf[Backend], cl)
    val it = loader.iterator()
    while it.hasNext do
      scala.util.Try(it.next()).foreach { backend =>
        backend.intrinsics.foreach { case (qn, impl) =>
          val op = qn.toString
          impl match
            case NativeImpl(eval) =>
              val nativeFn: List[V2Value] => V2Value = args => {
                val rawArgs: List[Any] = args.map(v2ToRaw)
                val rawResult: Any = eval(MinimalCtx, rawArgs)
                rawToV2(rawResult)
              }
              // Register as prim (for Prim(op, args) IR nodes)
              V2PluginRegistry.register(op, args => nativeFn(args))
              // Register as global (for App(Global(name), args) IR nodes)
              // arity=-1 = variadic: env = all call args (no captured env), skip arity check.
              V2PluginRegistry.registerGlobal(op, V2Value.ClosV(Runtime.emptyEnv, -1, env => {
                Done(nativeFn(env.toList))
              }))
              count += 1
            case _ => // InlineCode / RuntimeCall: compile-time only, skip
        }
        backend.blockForms.foreach { case (runnerName, bf) =>
          registerBlockForm(runnerName, bf)
          count += 1
        }
      }
    // 0-arity OS globals must be pre-computed values (not closures), because callers write
    // `cwd` not `cwd()`. Register AFTER plugins so we override any ClosV they registered.
    V2PluginRegistry.registerGlobal("cwd", V2Value.StrV(System.getProperty("user.dir", ".")))
    V2PluginRegistry.registerGlobal("sep", V2Value.StrV(java.io.File.separator))
    V2PluginRegistry.registerGlobal("platform", V2Value.DataV("JVM", Vector.empty))
    // Pre-call known 0-arg plugin functions (declared as `extern def foo: T` with no parens in ssc).
    // These must be registered as plain values, not ClosV, so `fetchActionClear(..., emptyHeaders)`
    // works without `emptyHeaders()` at the call site (ssc no-parens def semantics).
    Seq("emptyHeaders", "hashSignal").foreach { name =>
      V2PluginRegistry.lookupGlobal(name).collect { case c: V2Value.ClosV =>
        val result = scala.util.Try(Runtime.run(c.code, c.env))
        result.foreach { v => V2PluginRegistry.registerGlobal(name, v) }
      }
    }
    // Mirror.Of[T] support: DataV("Mirror", [label, elemLabels, elemTypes]) dispatch
    V2PluginRegistry.registerFieldNames("Mirror", Vector("label", "elemLabels", "elemTypes"))

    // Register plugin-owned extern type field names so v2ToV1(DataV) produces named fields.
    // These are types created in v2 user code but consumed by v1 plugins by name.
    V2PluginRegistry.registerFieldNames("Response",
      Vector("status", "headers", "body"))
    V2PluginRegistry.registerFieldNames("StreamResponse",
      Vector("status", "headers", "callback"))
    V2PluginRegistry.registerFieldNames("Request",
      Vector("method", "path", "body", "headers", "params", "query", "json", "form", "files", "session", "cookies", "bearerToken", "jwtClaims", "basicAuth"))
    // Override OIDC client prims BEFORE building namespace objects so the namespace
    // ForeignV picks up the overridden versions (buildNamespaceObjects reads from registry).
    overrideOidcClientStubs()
    // Build namespace objects for dotted globals: "oauth.authServer" → oauth = {authServer: ClosV}
    buildNamespaceObjects()
    // Override serve/emit/mount with no-op stubs: batch runner has no web server.
    // These are called at end of program; silently succeeding avoids plugin arg-type errors.
    import V2Value.*
    V2PluginRegistry.registerGlobal("serve",  ClosV(Runtime.emptyEnv, -1, _ => Done(UnitV)))
    V2PluginRegistry.registerGlobal("emit",   ClosV(Runtime.emptyEnv, -1, _ => Done(UnitV)))
    V2PluginRegistry.registerGlobal("mount",  ClosV(Runtime.emptyEnv, -1, _ => Done(UnitV)))
    registerBatchRunnerStubs()
    // Actor runtime registers LAST so its spawn/receive/self/exit/runActors
    // globals win over same-named v1 plugin intrinsics (a bridged os 'exit'
    // used to shadow the actor exit and System.exit(0) killed the batch JVM).
    registerActors()

    count

  /** Override oauth.client.discoverAs/exchangeAuthorizationCode BEFORE buildNamespaceObjects()
   *  so the namespace ForeignV picks up the batch-mode stubs.
   *
   *  discoverAs: instead of making a real HTTP call to localhost, return the discovery
   *  document directly from the in-memory OidcServer registered by oidc.server(as).
   *
   *  exchangeAuthorizationCode: return stub token strings; idp.userInfo() is patched
   *  in OidcHelpers.scala to return the first registered user for any token. */
  private def overrideOidcClientStubs(): Unit =
    import V2Value.*
    import scalascript.compiler.plugin.oauth.{OidcIntrinsicHelpers, OAuthIntrinsicHelpers}

    // ujson.Value → v2 Value (Map → ForeignV(Map[StrV→v2]), Str→StrV, Num→FloatV, Bool→BoolV)
    def ujsonToV2(v: ujson.Value): V2Value = v match
      case ujson.Str(s)  => StrV(s)
      case ujson.Num(d)  => if d == d.toLong then IntV(d.toLong) else FloatV(d)
      case ujson.Bool(b) => BoolV(b)
      case ujson.Null    => DataV("None", Vector.empty)
      case ujson.Arr(xs) => xs.foldRight(DataV("Nil", Vector.empty): V2Value)((x, acc) =>
        DataV("Cons", Vector(ujsonToV2(x), acc)))
      case ujson.Obj(kvs) =>
        val hm = collection.mutable.LinkedHashMap.empty[V2Value, V2Value]
        kvs.foreach { case (k, v) => hm(StrV(k)) = ujsonToV2(v) }
        ForeignV(hm.asInstanceOf[AnyRef])

    V2PluginRegistry.registerGlobal("oauth.client.discoverAs",
      ClosV(Runtime.emptyEnv, -1, env => Done({
        val issuer = env.headOption.collect { case StrV(s) => s }.getOrElse("")
        OidcIntrinsicHelpers.findByIssuer(issuer) match
          case Some(idp) => ujsonToV2(idp.discoveryJson())
          case None =>
            // Minimal discovery doc for unknown localhost issuers
            val hm = collection.mutable.LinkedHashMap.empty[V2Value, V2Value]
            hm(StrV("issuer"))                = StrV(issuer)
            hm(StrV("authorization_endpoint"))= StrV(s"$issuer/authorize")
            hm(StrV("token_endpoint"))        = StrV(s"$issuer/token")
            hm(StrV("userinfo_endpoint"))     = StrV(s"$issuer/userinfo")
            hm(StrV("jwks_uri"))              = StrV(s"$issuer/.well-known/jwks.json")
            ForeignV(hm.asInstanceOf[AnyRef])
      })))

    V2PluginRegistry.registerGlobal("oauth.client.exchangeAuthorizationCode",
      ClosV(Runtime.emptyEnv, -1, _ => Done({
        val hm = collection.mutable.LinkedHashMap.empty[V2Value, V2Value]
        hm(StrV("accessToken"))  = StrV("batch-stub-access-token")
        hm(StrV("idToken"))      = StrV("batch-stub-id-token")
        hm(StrV("refreshToken")) = StrV("batch-stub-refresh-token")
        hm(StrV("tokenType"))    = StrV("Bearer")
        hm(StrV("expiresIn"))    = IntV(3600L)
        hm(StrV("scope"))        = StrV("openid profile email offline_access")
        ForeignV(hm.asInstanceOf[AnyRef])
      })))

  /** Batch-runner stubs for features that need a server / document context.
   *  These override plugin registrations so examples run without errors. */
  private def registerBatchRunnerStubs(): Unit =
    import V2Value.*
    val stub = DataV("Stub", Vector.empty)
    val dividerNode = DataV("DividerNode", Vector.empty)
    def noopClos = ClosV(Runtime.emptyEnv, -1, _ => Done(UnitV))
    def stubClos = ClosV(Runtime.emptyEnv, -1, _ => Done(stub))
    def stubOpt  = DataV("Some", Vector(stub))

    // ── Content plugin stubs (require document context, batch runner has none) ──────
    // These override the NativeImpl registrations from the content plugin.
    Seq(
      "contentDocument" -> ClosV(Runtime.emptyEnv, 0, _ => Done(stub)),
      "contentCurrentSection" -> ClosV(Runtime.emptyEnv, 0, _ => Done(stub)),
      "contentSection" -> ClosV(Runtime.emptyEnv, -1, _ => Done(stubOpt)),
      "contentBlock"   -> ClosV(Runtime.emptyEnv, -1, _ => Done(stubOpt)),
      "contentData"    -> ClosV(Runtime.emptyEnv, -1, _ => Done(stubOpt)),
      "contentMetadata" -> ClosV(Runtime.emptyEnv, -1, _ => Done(stubOpt)),
      "contentBind"    -> ClosV(Runtime.emptyEnv, -1, env => Done(if env.nonEmpty then env(0) else stub)),
      "contentPlainText"  -> ClosV(Runtime.emptyEnv, -1, _ => Done(StrV(""))),
      "contentToMarkdown" -> ClosV(Runtime.emptyEnv, -1, _ => Done(StrV(""))),
      "contentToolkitNode"    -> ClosV(Runtime.emptyEnv, -1, _ => Done(dividerNode)),
      "contentToolkitBlock"   -> ClosV(Runtime.emptyEnv, -1, _ => Done(dividerNode)),
      "contentToolkitSection" -> ClosV(Runtime.emptyEnv, -1, _ => Done(dividerNode)),
      "contentModules"     -> ClosV(Runtime.emptyEnv, 0, _ => Done(ForeignV(collection.immutable.Map.empty[String, V2Value].asInstanceOf[AnyRef]))),
      "contentModule"      -> ClosV(Runtime.emptyEnv, -1, _ => Done(DataV("None", Vector.empty))),
      "contentModuleSection" -> ClosV(Runtime.emptyEnv, -1, _ => Done(stubOpt)),
      "contentModuleBlock"   -> ClosV(Runtime.emptyEnv, -1, _ => Done(stubOpt)),
      "contentModuleData"    -> ClosV(Runtime.emptyEnv, -1, _ => Done(stubOpt)),
      "contentModuleMetadata" -> ClosV(Runtime.emptyEnv, -1, _ => Done(stubOpt)),
    ).foreach { case (name, fn) =>
      V2PluginRegistry.registerGlobal(name, fn)
      V2PluginRegistry.register(name, args => Runtime.run(fn.code, if fn.env.isEmpty then Array.empty else fn.env))
    }

    // ── Sync stub (browser IndexedDB sync helper) ─────────────────────────────────
    val syncStatusStub = DataV("SyncStatus", Vector(IntV(0), IntV(0), BoolV(false)))
    val syncMethods: collection.immutable.Map[String, V2Value] = collection.immutable.Map(
      "put"      -> noopClos,
      "status"   -> ClosV(Runtime.emptyEnv, -1, _ => Done(syncStatusStub)),
      "pending"  -> ClosV(Runtime.emptyEnv, -1, _ => Done(DataV("Nil", Vector.empty))),
      "isOnline" -> BoolV(false),
      "sync"     -> noopClos,
    )
    V2PluginRegistry.registerGlobal("Sync", ForeignV(syncMethods.asInstanceOf[AnyRef]))
    // awaitClient(expr) — run expr and return UnitV
    V2PluginRegistry.registerGlobal("awaitClient", ClosV(Runtime.emptyEnv, 1, env => Done(env(0))))

    // ── Duration extension methods on Int/Long (e.g. 30.seconds) ────────────────
    // Returns stub — callers like Await.result ignore the actual duration value.
    for durMethod <- Seq("seconds", "minutes", "milliseconds", "millis", "nanoseconds", "nanos", "hours", "days") do
      V2PluginRegistry.register(s"__method__.$durMethod", args => stub)

    // ── Await stub (Await.result ignores timeout, returns stub) ──────────────────
    if V2PluginRegistry.lookupGlobal("Await").isEmpty then
      val awaitMethods: collection.immutable.Map[String, V2Value] = collection.immutable.Map(
        "result" -> ClosV(Runtime.emptyEnv, -1, env => Done(if env.nonEmpty then env(0) else stub)),
        "ready"  -> ClosV(Runtime.emptyEnv, -1, env => Done(if env.nonEmpty then env(0) else stub)),
      )
      V2PluginRegistry.registerGlobal("Await", ForeignV(awaitMethods.asInstanceOf[AnyRef]))

    // ── spark stub (SparkSession skeleton) ────────────────────────────────────────
    // Any method on spark returns DataV("Stub", []) which propagates through Op stub dispatch.
    if V2PluginRegistry.lookupGlobal("spark").isEmpty then
      V2PluginRegistry.registerGlobal("spark", stub)
    // StructType stub for Spark streaming schemas
    if V2PluginRegistry.lookupGlobal("StructType").isEmpty then
      V2PluginRegistry.registerGlobal("StructType", stubClos)
    if V2PluginRegistry.lookupGlobal("IntegerType").isEmpty then
      V2PluginRegistry.registerGlobal("IntegerType", stub)
    if V2PluginRegistry.lookupGlobal("StringType").isEmpty then
      V2PluginRegistry.registerGlobal("StringType", stub)
    if V2PluginRegistry.lookupGlobal("LongType").isEmpty then
      V2PluginRegistry.registerGlobal("LongType", stub)
    if V2PluginRegistry.lookupGlobal("DoubleType").isEmpty then
      V2PluginRegistry.registerGlobal("DoubleType", stub)

    // ── HTTP server backend selection — no-op in batch runner ────────────────────
    // The batch runner never starts a real server; setHttpServerBackend("jetty") would
    // throw "No HttpServerSpi impl named 'jetty'". Force-override the plugin's registration.
    V2PluginRegistry.register("setHttpServerBackend", _ => V2Value.UnitV)
    V2PluginRegistry.registerGlobal("setHttpServerBackend", ClosV(Runtime.emptyEnv, -1, _ => Done(UnitV)))

    // ── In-process HTTP dispatch for self-contained examples ─────────────────────
    // Examples like rozum-agent-* start a local mock server via serveAsync(port)
    // and then connect to it via runAgent(AgentEndpoint("http://localhost:PORT")).
    // Since serveAsync is a no-op stub, the real httpPost/httpPostStream would fail
    // with ConnectionRefused. We intercept localhost calls and return a stub OpenAI
    // response so runAgent exits immediately with finish_reason="stop".
    val origHttpPost = V2PluginRegistry.lookup("httpPost")
    val fakeStopBody = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Batch-mode stub response."}}]}"""
    val fakeStopResp = fakeHttpResponse(200, fakeStopBody, "application/json")
    V2PluginRegistry.register("httpPost", args => {
      val url = args.headOption.collect { case StrV(s) => s }.getOrElse("")
      if isLocalhostUrl(url) then fakeStopResp
      else origHttpPost.map(_(args)).getOrElse(stub)
    })
    V2PluginRegistry.registerGlobal("httpPost", ClosV(Runtime.emptyEnv, -1, env => Done(
      V2PluginRegistry.lookup("httpPost").map(_(env.toList)).getOrElse(stub)
    )))
    // httpPostStream: SSE streaming variant — curried: httpPostStream(url,body,hdrs){ line => }
    val origHttpPostStream = V2PluginRegistry.lookup("httpPostStream")
    val fakeStopResp200 = fakeHttpResponse(200, "done", "text/event-stream")
    V2PluginRegistry.register("httpPostStream", args => {
      val url = args.headOption.collect { case StrV(s) => s }.getOrElse("")
      if isLocalhostUrl(url) then {
        // Return a curried handler: when called with the SSE line callback, call it with
        // a single stop event line and then return the response.
        val stopLine = """{"choices":[{"delta":{},"finish_reason":"stop"}]}"""
        ClosV(Runtime.emptyEnv, 1, handlerEnv => {
          val handler = handlerEnv(0)
          // Call the SSE handler closure with the stop event line
          handler match
            case c: ClosV =>
              val callArgs = Array[V2Value](StrV(s"data: $stopLine"))
              val callEnv  = if c.env.isEmpty then callArgs else c.env ++ callArgs
              scala.util.Try(Runtime.run(c.code, callEnv))  // ignore result/errors
            case _ => ()
          Done(fakeStopResp200)
        })
      } else origHttpPostStream.map(_(args)).getOrElse(stub)
    })
    V2PluginRegistry.registerGlobal("httpPostStream", ClosV(Runtime.emptyEnv, -1, env => Done(
      V2PluginRegistry.lookup("httpPostStream").map(_(env.toList)).getOrElse(stub)
    )))

    // ── httpGet localhost interceptor + http namespace (for oidc-login-flow.ssc) ─────────────
    // oidc-login-flow.ssc calls http.get(authorizationUrl, Map("followRedirects" -> false))
    // which would connect to localhost:8080 (not running in batch mode). We intercept
    // httpGet for localhost URLs and return a fake 302 redirect with the state echoed back.
    // We also register the `http` namespace so http.get/http.parseUrl resolve correctly.
    val origHttpGet = V2PluginRegistry.lookup("httpGet")
    V2PluginRegistry.register("httpGet", args => {
      val url = args.headOption.collect { case StrV(s) => s }.getOrElse("")
      if isLocalhostUrl(url) then makeLocalhostGetResp(url)
      else origHttpGet.map(_(args)).getOrElse(stub)
    })
    V2PluginRegistry.registerGlobal("httpGet", ClosV(Runtime.emptyEnv, -1, env => Done(
      V2PluginRegistry.lookup("httpGet").map(_(env.toList)).getOrElse(stub)
    )))

    // `http` namespace: http.get = httpGet wrapper; http.parseUrl = URL query parser
    val httpGetClos = ClosV(Runtime.emptyEnv, -1, env => Done(
      V2PluginRegistry.lookup("httpGet").map(_(env.toList)).getOrElse(stub)
    ))
    if V2PluginRegistry.lookupGlobal("http").isEmpty then
      V2PluginRegistry.registerGlobal("http", ForeignV(collection.immutable.Map[String, V2Value](
        "get"      -> httpGetClos,
        "parseUrl" -> ClosV(Runtime.emptyEnv, 1, env => Done(makeUrlParserV2(env(0))))
      ).asInstanceOf[AnyRef]))

    // ── mcpConnect stub — fake MCP client so MCP examples don't error with "client closed" ─────
    // mcp-client-discover.ssc and agent-mcp-toolsource.ssc spawn an external node process via
    // Transport.Spawn. In batch mode, stub mcpConnect to return a no-tools client.
    val emptyMcpList: V2Value = DataV("Nil", IndexedSeq.empty)
    val fakeMcpClient = ForeignV(new ssc.Value.NamedMethodObj {
      def getField(n: String): Option[ssc.Value] = n match
        case "listTools"     => Some(ClosV(Runtime.emptyEnv, 0, _ => Done(emptyMcpList)))
        case "listResources" => Some(ClosV(Runtime.emptyEnv, 0, _ => Done(emptyMcpList)))
        case "listPrompts"   => Some(ClosV(Runtime.emptyEnv, 0, _ => Done(emptyMcpList)))
        case "close"         => Some(ClosV(Runtime.emptyEnv, 0, _ => Done(UnitV)))
        case "callTool"      => Some(ClosV(Runtime.emptyEnv, 2, _ => Done(
          DataV("McpToolResult", IndexedSeq(BoolV(false), emptyMcpList)))))
        case _ => None
      def underlying: AnyRef = this
      override def toString = "McpClient(batch-stub)"
    })
    V2PluginRegistry.register("mcpConnect", _ => fakeMcpClient)
    V2PluginRegistry.registerGlobal("mcpConnect", ClosV(Runtime.emptyEnv, -1, _ => Done(fakeMcpClient)))

    // Register postChatCompletions as a global so the bridge skips compiling the ssc def
    // (bridge line 442: skip defs already registered as globals). This bypasses the field-
    // index mismatch: fieldIndex("body")=3 (from Request, registered first due to DFS import
    // order) causes runAgentLoop to read errorText instead of body, triggering mkString on ().
    // We put fakeBody at index 3 (what fieldIndex("body") resolves to) and also at index 1
    // (real AgentHttpAttempt.body).
    val fakeAgentBody = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Batch-mode stub response."}}]}"""
    V2PluginRegistry.registerGlobal("postChatCompletions", ClosV(Runtime.emptyEnv, -1, _ => Done(
      DataV("AgentHttpAttempt", Vector(
        IntV(200),               // 0: status
        StrV(fakeAgentBody),     // 1: body (real index in AgentHttpAttempt)
        BoolV(false),            // 2: transportError
        StrV(fakeAgentBody)      // 3: body (fieldIndex("body")=3 from Request, registered first)
      ))
    )))

    // Register postChatCompletionsStream as a global stub for the streaming variant.
    // fieldIndex("transportError")=2 (from AgentHttpAttempt, registered before AgentStreamAttempt),
    // but in AgentStreamAttempt transportError is at real index 1.
    // fieldIndex("events")=1 (from AgentStreamResult), but in AgentStreamAttempt events is at index 7.
    // We lay out the DataV to satisfy the WRONG but consistent fieldIndex accesses:
    //   index 0: status (IntV 200)
    //   index 1: events=Nil (fieldIndex("events")=1 from AgentStreamResult)
    //   index 2: transportError=false (fieldIndex("transportError")=2 from AgentHttpAttempt)
    //   index 3: errorText="" (fieldIndex("errorText")=3 from AgentHttpAttempt)
    //   index 4: toolCalls=Nil (fieldIndex("toolCalls")=4 from AgentStreamAttempt)
    //   index 5: finishReason="stop" (fieldIndex("finishReason")=5 from AgentStreamAttempt)
    //   index 6: streamError="" (fieldIndex("streamError")=6 from AgentStreamAttempt)
    //   index 7: events=Nil (real index in AgentStreamAttempt, not used via fieldIndex)
    // postChatCompletionsStream stub: take the early transportError branch so we avoid
    // `assistantTextMessage(attempt.text)` where attempt.text would be the status Int.
    // fieldIndex("events")=1, fieldIndex("transportError")=2, fieldIndex("errorText")=3.
    val emptyNil = DataV("Nil", Vector.empty)
    V2PluginRegistry.registerGlobal("postChatCompletionsStream", ClosV(Runtime.emptyEnv, -1, _ => Done(
      DataV("AgentStreamAttempt", Vector(
        IntV(0),             // 0: (fieldIndex("text")=0 from AgentResult — kept as Int to signal error)
        emptyNil,            // 1: events (fieldIndex("events")=1 from AgentStreamResult)
        BoolV(true),         // 2: transportError=true (fieldIndex("transportError")=2) → takes error branch
        StrV(""),            // 3: errorText="" (fieldIndex("errorText")=3)
        emptyNil,            // 4: toolCalls
        StrV("stop"),        // 5: finishReason
        StrV(""),            // 6: streamError
        emptyNil             // 7: real events index in AgentStreamAttempt (unused via fieldIndex)
      ))
    )))

  /** For each registered global of the form "a.b[.c...]", build nested namespace ForeignV(Map)
   *  objects so `oauth.authServer(...)` and `oauth.client.discoverAs(...)` work via __method__. */
  private def buildNamespaceObjects(): Unit =
    import V2Value.*
    // Collect all dotted names and group by their top-level prefix
    type NestedMap = scala.collection.mutable.Map[String, Any] // String→V2Value or String→NestedMap
    def treeOf(ns: NestedMap, parts: List[String], leaf: V2Value): Unit = parts match
      case Nil         => ()
      case key :: Nil  => ns(key) = leaf
      case key :: rest =>
        val child = ns.getOrElseUpdate(key, scala.collection.mutable.Map.empty[String, Any])
          .asInstanceOf[NestedMap]
        ns(key) = child
        treeOf(child, rest, leaf)

    val tree = scala.collection.mutable.Map.empty[String, NestedMap]
    V2PluginRegistry.allGlobalNames().foreach { name =>
      val parts = name.split("\\.").toList
      if parts.length >= 2 && parts.head.nonEmpty && !Character.isUpperCase(parts.head.charAt(0)) then
        val ns = tree.getOrElseUpdate(parts.head, scala.collection.mutable.Map.empty)
        V2PluginRegistry.lookupGlobal(name).foreach { fn => treeOf(ns, parts.tail, fn) }
    }

    def toForeignV(m: scala.collection.mutable.Map[String, Any]): V2Value =
      val im: scala.collection.immutable.Map[String, V2Value] =
        scala.collection.immutable.Map.from(m.map { case (k, v) =>
          k -> (v match
            case nested: scala.collection.mutable.Map[?, ?] =>
              toForeignV(nested.asInstanceOf[NestedMap])
            case v2: V2Value => v2
          )
        })
      ForeignV(im.asInstanceOf[AnyRef])

    tree.foreach { case (ns, methods) =>
      if V2PluginRegistry.lookupGlobal(ns).isEmpty then
        V2PluginRegistry.registerGlobal(ns, toForeignV(methods))
    }

  /** Load a specific Backend (e.g., for testing). */
  def loadBackend(backend: Backend): Int =
    registerHandle()
    var count = 0
    backend.intrinsics.foreach { case (qn, impl) =>
      val op = qn.toString
      impl match
        case NativeImpl(eval) =>
          val nativeFn: List[V2Value] => V2Value = args => {
            val rawArgs: List[Any] = args.map(v2ToRaw)
            rawToV2(eval(MinimalCtx, rawArgs))
          }
          V2PluginRegistry.register(op, args => nativeFn(args))
          V2PluginRegistry.registerGlobal(op, V2Value.ClosV(Runtime.emptyEnv, -1, env => Done(nativeFn(env.toList))))
          count += 1
        case _ => // compile-time variants; not bridgeable
    }
    backend.blockForms.foreach { case (runnerName, bf) =>
      registerBlockForm(runnerName, bf)
      count += 1
    }
    count

  // ── BlockForm registration ──────────────────────────────────────────────────

  /** Register a BlockForm runner (e.g. runLogger, runState(s0)) as a v2 global.
   *
   *  Block-form call shapes handled:
   *   - 1-arg:  runLogger { body }   → Global("runLogger")(thunk)
   *   - 2-arg:  runState(s0) { body } → Global("runState")(s0)(thunk)
   *
   *  We register a curried 1-arg v2 ClosV that collects config args until it receives
   *  a thunk (arity-0 closure), then executes. Simple heuristic: the body thunk is
   *  always arity-0 (Lam(0, body)).  For multi-arg runners we curry eagerly. */
  private def registerBlockForm(name: String, bf: BlockForm): Unit =
    // Build a Scala-native v2 ClosV that acts as the runner global.
    // We pre-build a stable ClosV and capture it by reference.
    val runnerClosure = makeRunnerClosure(bf, accArgs = Nil)
    V2PluginRegistry.registerGlobal(name, runnerClosure)

  /** Recursively build a curried closure that collects config args until it sees
   *  a thunk (arity-0 ClosV), then executes the block-form. */
  private def makeRunnerClosure(bf: BlockForm, accArgs: List[V2Value]): V2Value =
    V2Value.ClosV(Runtime.emptyEnv, 1, env => {
      val arg = env.last // arity-1 called with [arg]; Local(0) = env.last
      arg match
        case thunk: V2Value.ClosV if thunk.arity == 0 =>
          // Got the body thunk — run the block form
          val cfgSpi: List[SpiValue] = accArgs.reverse.map(v2ToSpi)
          val bctx = makeBCtx()
          val handler = bf.newHandler(bctx, cfgSpi)
          val effectTag = bf.effectName
          val v2Handler: V2EffectContext.EH = (op, args) =>
            val spiArgs = args.map(v2ToSpi)
            val spiResult = handler.reply(op, spiArgs)
            spiToV2(spiResult)
          V2EffectContext.push(effectTag, v2Handler)
          val bodyResult = try
            callThunk(thunk)
          finally
            V2EffectContext.pop(effectTag)
          val spiBody = v2ToSpi(bodyResult)
          Done(spiToV2(bf.result(spiBody, handler)))
        case _ =>
          // Config arg — accumulate and return next curried closure
          Done(makeRunnerClosure(bf, arg :: accArgs))
    })

  /** Build a BlockContext for v2 use. applyFn calls v2 closure via Runtime.run. */
  private def makeBCtx(): BlockContext = new BlockContext:
    def out: java.io.PrintStream = Console.out
    override def applyFn(fn: SpiValue, args: List[SpiValue]): SpiValue =
      val fnV = spiToV2(fn)
      val argVs = args.map(spiToV2)
      v2ToSpi(callClosure(fnV, argVs))

  /** Call an arity-0 v2 ClosV (a thunk). */
  private def callThunk(c: V2Value.ClosV): V2Value =
    Runtime.run(c.code, c.env)

  /** Call any v2 closure with a list of args. */
  private def callClosure(fn: V2Value, args: List[V2Value]): V2Value = fn match
    case c: V2Value.ClosV =>
      val argsArr = args.toArray
      Runtime.run(c.code, if args.isEmpty then c.env else Runtime.extend(c.env, argsArr))
    case _ => sys.error(s"PluginBridge.callClosure: expected closure, got $fn")

  // ── `handle` global — Free-monad interpreter for typed effects ─────────────

  /** Register `handle` as a v2 global.
   *
   *  Typed effects compile to:
   *    handle(bodyResult)(handlerFn)
   *  where bodyResult may be:
   *    - DataV("Op", [StrV(label), arg, k])  — an effectful step (Free monad node)
   *    - any other value                      — pure result (treated as Return)
   *
   *  The handler lambda has shape: `lam 1 (match x { case op(args, resume) => ...; case Return(_) => ... })`
   *  so we call it with DataV(opName, [arg, resumeFn]) or DataV("Return", [result]).
   */
  /** Register stubs for interpreter-internal globals that aren't in any SPI plugin.
   *  These are complex language features (async, generators, signals, storage, setArgs).
   *  Stubs allow programs to load/initialize without crashing on unbound; actual
   *  functionality requires proper implementation (open TODO). */
  /** Optics runtime — mirrors v1's OpticsRuntime for the bridged pipeline.
   *  An optic = ForeignV(OpticSteps) exposing get/getOption/set/modify/andThen
   *  as NamedMethodObj fields; steps walk v2 values (DataV records via the
   *  field-name registry, Some/None, Cons/Nil lists, map wrappers). */
  private final class OpticSteps(val steps: List[V2Value])

  private def registerOptics(): Unit =
    import V2Value.*
    def unlist(v: V2Value): List[V2Value] =
      val b = List.newBuilder[V2Value]
      var cur = v
      var go = true
      while go do cur match
        case DataV("Cons", IndexedSeq(h, t)) => b += h; cur = t
        case _ => go = false
      b.result()
    def listIdx(v: V2Value, i: Long): Option[V2Value] =
      val xs = unlist(v); if i >= 0 && i < xs.length then Some(xs(i.toInt)) else None
    def listSet(v: V2Value, i: Long, nv: V2Value): V2Value =
      val xs = unlist(v)
      if i < 0 || i >= xs.length then v
      else xs.updated(i.toInt, nv).foldRight[V2Value](DataV("Nil", Vector.empty))((x, acc) => DataV("Cons", Vector(x, acc)))
    def mapOf(v: V2Value): Option[collection.mutable.Map[V2Value, V2Value]] = v match
      case ForeignV(m: collection.mutable.Map[?, ?]) => Some(m.asInstanceOf[collection.mutable.Map[V2Value, V2Value]])
      case _ => None

    def getOpt(target: V2Value, ss: List[V2Value]): Option[V2Value] = ss match
      case Nil => Some(target)
      case st :: rest => st match
        case DataV("OField", IndexedSeq(StrV(f))) => target match
          case DataV(tag, fields) =>
            ssc.V2PluginRegistry.lookupFieldNames(tag).flatMap { ns =>
              val i = ns.indexOf(f)
              if i >= 0 && i < fields.length then getOpt(fields(i), rest) else None
            }
          case _ => None
        case DataV("OSome", _) => target match
          case DataV("Some", IndexedSeq(v)) => getOpt(v, rest)
          case _ => None
        case DataV("OIndex", IndexedSeq(IntV(i))) => listIdx(target, i).flatMap(getOpt(_, rest))
        case DataV("OAt", IndexedSeq(k)) => mapOf(target).flatMap(_.get(k)).flatMap(getOpt(_, rest))
        case _ => None

    def setPath(target: V2Value, ss: List[V2Value], nv: V2Value): V2Value = ss match
      case Nil => nv
      case st :: rest => st match
        case DataV("OField", IndexedSeq(StrV(f))) => target match
          case DataV(tag, fields) =>
            ssc.V2PluginRegistry.lookupFieldNames(tag) match
              case Some(ns) =>
                val i = ns.indexOf(f)
                if i >= 0 && i < fields.length then DataV(tag, fields.updated(i, setPath(fields(i), rest, nv)))
                else target
              case None => target
          case _ => target
        case DataV("OSome", _) => target match
          case DataV("Some", IndexedSeq(v)) => DataV("Some", Vector(setPath(v, rest, nv)))
          case _ => target
        case DataV("OIndex", IndexedSeq(IntV(i))) =>
          listIdx(target, i).fold(target)(old => listSet(target, i, setPath(old, rest, nv)))
        case DataV("OAt", IndexedSeq(k)) =>
          mapOf(target) match
            case Some(m) if m.contains(k) =>
              val copy = m.clone()
              copy(k) = setPath(m(k), rest, nv)
              ForeignV(copy)
            case _ => target
        case _ => target

    def mkList(xs: List[V2Value]): V2Value =
      xs.foldRight[V2Value](DataV("Nil", Vector.empty))((x, acc) => DataV("Cons", Vector(x, acc)))

    /** Multi-foci walk: OEach fans out over list elements; narrow steps are 0-or-1. */
    def getAll(target: V2Value, ss: List[V2Value]): List[V2Value] = ss match
      case Nil => List(target)
      case st :: rest => st match
        case DataV("OEach", _) => unlist(target).flatMap(getAll(_, rest))
        case _ => getOpt(target, st :: Nil).toList.flatMap { _ =>
          // reuse the single-focus step then continue
          getOpt(target, List(st)).toList.flatMap(getAll(_, rest))
        }

    /** Apply f at EVERY focus (traversal modify / set-all). */
    def modifyAll(target: V2Value, ss: List[V2Value], f: V2Value => V2Value): V2Value = ss match
      case Nil => f(target)
      case st :: rest => st match
        case DataV("OEach", _) =>
          mkList(unlist(target).map(modifyAll(_, rest, f)))
        case _ =>
          getOpt(target, List(st)) match
            case Some(sub) => setPath(target, List(st), modifyAll(sub, rest, f))
            case None      => target

    def isPartial(ss: List[V2Value]): Boolean = ss.exists {
      case DataV("OField", _) => false
      case _ => true
    }

    def opticKind(ss: List[V2Value]): String =
      if ss.exists { case DataV("OEach", _) => true; case _ => false } then "Traversal"
      else if isPartial(ss) then "Optional"
      else "Lens"

    def mkOptic(ss: List[V2Value], path: String = ""): V2Value =
      ForeignV(new ssc.Value.NamedMethodObj {
        def underlying: AnyRef = new OpticSteps(ss)
        override def toString: String = s"${opticKind(ss)}($path)"
        def getField(name: String): Option[V2Value] = name match
          case "_show" => Some(StrV(s"${opticKind(ss)}($path)"))
          case "get" => Some(ClosV(Runtime.emptyEnv, -1, env =>
            Done(getOpt(env(0), ss).getOrElse(sys.error("optic.get: path missing")))))
          case "getOption" => Some(ClosV(Runtime.emptyEnv, -1, env =>
            Done(getOpt(env(0), ss).fold[V2Value](DataV("None", Vector.empty))(v => DataV("Some", Vector(v))))))
          case "set" => Some(ClosV(Runtime.emptyEnv, -1, env =>
            Done(
              if ss.exists { case DataV("OEach", _) => true; case _ => false }
              then modifyAll(env(0), ss, _ => env(1))
              else setPath(env(0), ss, env(1)))))
          case "modify" => Some(ClosV(Runtime.emptyEnv, -1, env => {
            val target = env(0)
            val f = env(1).asInstanceOf[ClosV]
            if ss.exists { case DataV("OEach", _) => true; case _ => false } then
              Done(modifyAll(target, ss, v => callClosure(f, List(v))))
            else getOpt(target, ss) match
              case Some(cur) => Done(setPath(target, ss, callClosure(f, List(cur))))
              case None      => Done(target)
          }))
          case "getAll" => Some(ClosV(Runtime.emptyEnv, -1, env =>
            Done(mkList(getAll(env(0), ss)))))
          case "modifyAll" => Some(ClosV(Runtime.emptyEnv, -1, env => {
            val f = env(1).asInstanceOf[ClosV]
            Done(modifyAll(env(0), ss, v => callClosure(f, List(v))))
          }))
          case "andThen" => Some(ClosV(Runtime.emptyEnv, -1, env => env(0) match
            case ForeignV(other: ssc.Value.NamedMethodObj) => other.underlying match
              case os: OpticSteps => Done(mkOptic(ss ++ os.steps, path))
              case _ => sys.error("optic.andThen: not an optic")
            case _ => sys.error("optic.andThen: not an optic")))
          case "isPartial" => Some(BoolV(isPartial(ss)))
          case _ => None
      })

    // Context-bound dictionary resolution: pick the given instance whose type
    // head matches the runtime WITNESS value. args = tc :: drill :: witness ::
    // (head, instance) pairs (table embedded at the call site by the bridge).
    V2PluginRegistry.register("__resolve_given__", args => args match
      case StrV(tc) :: StrV(drill) :: witness :: table =>
        def headOf(v: V2Value): String = v match
          case IntV(_)   => "Int"
          case StrV(_)   => "String"
          case BoolV(_)  => "Boolean"
          case FloatV(_) => "Double"
          case DataV("Cons" | "Nil", _)  => "List"
          case DataV("Some" | "None", _) => "Option"
          case DataV(t, _) => t
          case _ => "?"
        val w = drill match
          case "elem" => witness match
            case DataV("Cons", IndexedSeq(h, _)) => h
            case other => other
          case _ => witness
        val want = headOf(w)
        val pairs = table.grouped(2).collect { case List(StrV(h), inst) => h -> inst }.toList
        pairs.find(_._1 == want).orElse(pairs.headOption).map(_._2)
          .getOrElse(sys.error(s"__resolve_given__: no $tc instance for $want"))
      case _ => sys.error("__resolve_given__: bad args"))

    V2PluginRegistry.register("optics.focus", args => args match
      case List(steps, StrV(path)) => mkOptic(unlist(steps), path)
      case List(steps)             => mkOptic(unlist(steps))
      case _ => sys.error("optics.focus(steps[, path])"))

    // Prism[Outer, Variant] — v1 buildPrism mirror: getOption/set/modify hit only
    // when the target's tag equals the variant; reverseGet is identity.
    def mkPrism(variant: String): V2Value =
      def tagMatches(v: V2Value): Boolean = v match
        case DataV(t, _) => t == variant
        case _ => false
      ForeignV(new ssc.Value.NamedMethodObj {
        def underlying: AnyRef = ("Prism", variant)
        def getField(name: String): Option[V2Value] = name match
          case "getOption" => Some(ClosV(Runtime.emptyEnv, -1, env =>
            Done(if tagMatches(env(0)) then DataV("Some", Vector(env(0))) else DataV("None", Vector.empty))))
          case "reverseGet" => Some(ClosV(Runtime.emptyEnv, -1, env => Done(env(0))))
          case "set" => Some(ClosV(Runtime.emptyEnv, -1, env =>
            Done(if tagMatches(env(0)) then env(1) else env(0))))
          case "modify" => Some(ClosV(Runtime.emptyEnv, -1, env => {
            if tagMatches(env(0)) then Done(callClosure(env(1).asInstanceOf[ClosV], List(env(0))))
            else Done(env(0))
          }))
          case "_variant" => Some(StrV(variant))
          case "_show"    => Some(StrV(s"Prism[?, $variant]"))
          case _ => None
      })
    V2PluginRegistry.register("optics.prism", args => args match
      case List(StrV(v)) => mkPrism(v)
      case _ => sys.error("optics.prism(variant)"))

  /** Ambient effect ops: in v1 the core effects (Random/Clock) work WITHOUT an
   *  explicit handler block (ambient defaults). The bridge's dispatch tries plugin
   *  lookup before the free-monad Op fallback, so registering these here gives the
   *  same ambient semantics (mapreduce's jobId = Random.uuid() etc.). */
  private def registerAmbientEffectOps(): Unit =
    V2PluginRegistry.register("Random.uuid", _ => V2Value.StrV(java.util.UUID.randomUUID().toString))
    V2PluginRegistry.register("Random.int", {
      case List(V2Value.IntV(n)) => V2Value.IntV(scala.util.Random.nextLong(n))
      case _                     => V2Value.IntV(scala.util.Random.nextLong())
    })
    V2PluginRegistry.register("Random.double", _ => V2Value.FloatV(scala.util.Random.nextDouble()))
    V2PluginRegistry.register("Clock.now", _ => V2Value.IntV(System.currentTimeMillis()))
    V2PluginRegistry.register("Clock.nanos", _ => V2Value.IntV(System.nanoTime()))

  private def registerInterpreterBuiltins(): Unit =
    import V2Value.*
    // println / print — variadic so println() works (0-arg prints a blank line).
    // v1-INTERPRETER display parity (T4.4 output-equality): tuples render as
    // (a, b); Cons/Nil chains as List(...); strings are RAW even inside
    // containers; integral Doubles drop the trailing .0. The ssc0-native
    // pipeline keeps the kernel Show — this only applies to bridged programs.
    def v1Show(v: V2Value): String = v match
      case V2Value.StrV(s)   => s
      case V2Value.FloatV(d) =>
        if d == d.toLong.toDouble && math.abs(d) < 1e15 then d.toLong.toString else d.toString
      case V2Value.DataV(t, fs) if t.startsWith("Tuple") && t.length > 5 && t.drop(5).forall(_.isDigit) =>
        fs.map(v1Show).mkString("(", ", ", ")")
      case lv @ V2Value.DataV("Cons" | "Nil", _) =>
        val items = collection.mutable.ListBuffer.empty[String]
        var cur: V2Value = lv
        var listy = true
        while listy do cur match
          case V2Value.DataV("Cons", IndexedSeq(h, t)) => items += v1Show(h); cur = t
          case V2Value.DataV("Nil", _)                 => listy = false
          case other                                   => items += ("…" + v1Show(other)); listy = false
        items.mkString("List(", ", ", ")")
      case V2Value.DataV(tag, fs) if fs.isEmpty => tag
      case V2Value.DataV(tag, fs) => tag + fs.map(v1Show).mkString("(", ", ", ")")
      case V2Value.ForeignV(m: collection.mutable.Map[?, ?]) =>
        m.asInstanceOf[collection.mutable.Map[V2Value, V2Value]]
          .map { case (k, vv) => s"${v1Show(k)} -> ${v1Show(vv)}" }.mkString("Map(", ", ", ")")
      case V2Value.ForeignV(nmo: ssc.Value.NamedMethodObj) =>
        nmo.getField("_show") match
          case Some(V2Value.StrV(s)) => s
          case _ =>
            // v1 facades (json wrapJson etc.): render the INNER value via the
            // 0-arg `raw` field, the way v1 prints the wrapped value itself.
            nmo.getField("raw") match
              case Some(c: V2Value.ClosV) => v1Show(callClosure(c, Nil))
              case _ => Show.show(v)
      case other => Show.show(other)
    def showForPrint(v: V2Value): String = v1Show(v)
    if V2PluginRegistry.lookupGlobal("println").isEmpty then
      V2PluginRegistry.registerGlobal("println", ClosV(Runtime.emptyEnv, -1, env => {
        if env.isEmpty then Console.out.println()
        else Console.out.println(showForPrint(env(0)))
        Done(UnitV)
      }))
    if V2PluginRegistry.lookupGlobal("print").isEmpty then
      V2PluginRegistry.registerGlobal("print", ClosV(Runtime.emptyEnv, -1, env => {
        if env.nonEmpty then Console.out.print(showForPrint(env(0)))
        Done(UnitV)
      }))
    // setHttpServerBackend(name) — already no-op'd in registerBatchRunnerStubs()
    // runAsync { body } — run the body immediately (no async in v2 yet)
    if V2PluginRegistry.lookupGlobal("runAsync").isEmpty then
      V2PluginRegistry.registerGlobal("runAsync", ClosV(Runtime.emptyEnv, -1, env => {
        val thunk = env.last
        Done(thunk match
          case c: ClosV => callClosure(c, Nil)
          case v => v)
      }))
    // runAsyncParallel { body } — same stub as runAsync
    if V2PluginRegistry.lookupGlobal("runAsyncParallel").isEmpty then
      V2PluginRegistry.registerGlobal("runAsyncParallel", ClosV(Runtime.emptyEnv, -1, env => {
        val thunk = env.last
        Done(thunk match
          case c: ClosV => callClosure(c, Nil)
          case v => v)
      }))
    // computed(f) — returns a thunk-based pseudo-signal cell that re-evaluates on .get
    if V2PluginRegistry.lookupGlobal("computed").isEmpty then
      V2PluginRegistry.registerGlobal("computed", ClosV(Runtime.emptyEnv, -1, env => {
        val thunk = env.last
        // Wrap thunk in a special ComputedCell: ForeignV(Array) where Array holds the thunk
        // The .get dispatch will call the thunk to get the current value
        thunk match
          case c: ClosV => Done(ForeignV(new ComputedCell(c).asInstanceOf[AnyRef]))
          case v => Done(ForeignV(Array[V2Value](v).asInstanceOf[AnyRef]))
      }))
    // generator { body } — virtual-thread coroutine generator; suspend(v) yields values
    if V2PluginRegistry.lookupGlobal("generator").isEmpty then
      V2PluginRegistry.registerGlobal("generator", ClosV(Runtime.emptyEnv, -1, env => {
        val thunk = env.lastOption.getOrElse(UnitV)
        val queue = new java.util.concurrent.LinkedBlockingQueue[Option[V2Value]]()
        Thread.ofVirtual().start { () =>
          genQueueTL.set(queue)
          try callClosure(thunk, Nil)
          catch case _: Throwable => ()
          finally try queue.put(None) catch case _ => ()
        }
        Done(makeGenerator(queue))
      }))
    // suspend(v) — yield value from inside a generator body
    if V2PluginRegistry.lookupGlobal("suspend").isEmpty then
      V2PluginRegistry.registerGlobal("suspend", ClosV(Runtime.emptyEnv, 1, env => {
        val v   = env.last
        val q   = genQueueTL.get()
        if q != null then q.put(Some(v))
        Done(UnitV)
      }))
    // runEphemeralStorage { body } — run body with an in-memory map store
    if V2PluginRegistry.lookupGlobal("runEphemeralStorage").isEmpty then
      V2PluginRegistry.registerGlobal("runEphemeralStorage", ClosV(Runtime.emptyEnv, -1, env => {
        val thunk = env.last
        val map = scala.collection.mutable.LinkedHashMap.empty[String, String]
        val prev = storageLocal.get()
        storageLocal.set(map)
        val result = try thunk match
          case c: ClosV => callClosure(c, Nil)
          case v => v
        finally storageLocal.set(prev)
        Done(result)
      }))
    // args — command-line args as a Cons/Nil list
    if V2PluginRegistry.lookupGlobal("args").isEmpty then
      val argsList = sys.props.get("scalascript.args")
        .map(_.split(",").toList)
        .getOrElse(Nil)
        .foldRight[V2Value](DataV("Nil", Vector.empty)) { (a, acc) =>
          DataV("Cons", Vector(StrV(a), acc))
        }
      V2PluginRegistry.registerGlobal("args", argsList)
    // startNode — cluster node stub
    if V2PluginRegistry.lookupGlobal("startNode").isEmpty then
      V2PluginRegistry.registerGlobal("startNode", ClosV(Runtime.emptyEnv, -1, _ => Done(UnitV)))
    // contentBlock — content rendering stub
    if V2PluginRegistry.lookupGlobal("contentBlock").isEmpty then
      V2PluginRegistry.registerGlobal("contentBlock", ClosV(Runtime.emptyEnv, -1, env =>
        Done(env.lastOption.getOrElse(UnitV))))
    if V2PluginRegistry.lookupGlobal("nanoTime").isEmpty then
      V2PluginRegistry.registerGlobal("nanoTime", ClosV(Runtime.emptyEnv, 0, _ => Done(IntV(System.nanoTime()))))
    // __throw__ — lowered from throw expressions in FrontendBridge
    if V2PluginRegistry.lookupGlobal("__throw__").isEmpty then
      V2PluginRegistry.registerGlobal("__throw__", ClosV(Runtime.emptyEnv, 1, env => {
        throw new BridgeThrow(env.last)
      }))
    // ```sql fenced blocks (v1.26): execute over JDBC. SELECT → List[Map[label,
    // value]] (rows applied by column label); DML/DDL → Int (affected rows).
    V2PluginRegistry.register("__sqlExec__", args => args match
      case StrV(url) :: StrV(sql) :: bindsV :: Nil =>
        val binds = bindsV match
          case DataV("Cons", _) | DataV("Nil", _) =>
            var out = List.empty[V2Value]; var cur = bindsV
            while cur.isInstanceOf[DataV] && cur.asInstanceOf[DataV].tag == "Cons" do
              val c = cur.asInstanceOf[DataV]; out = out :+ c.fields(0); cur = c.fields(1)
            out
          case _ => Nil
        // Fail-soft on engines this JVM lane can't serve (sqlite::memory:,
        // duckdb — browser/spark-lane examples): a Stub keeps the doc's other
        // fences running, matching the previous ignore-sql behavior.
        val connOpt =
          try Some(java.sql.DriverManager.getConnection(url))
          catch { case _: java.sql.SQLException => None }
        connOpt match
         case None => DataV("Stub", Vector(StrV(s"sql:no-driver:$url")))
         case Some(conn) =>
          try
            val ps = conn.prepareStatement(sql)
            binds.zipWithIndex.foreach { case (b, i) =>
              val raw: AnyRef = b match
                case IntV(n)   => java.lang.Long.valueOf(n)
                case FloatV(d) => java.lang.Double.valueOf(d)
                case BoolV(x)  => java.lang.Boolean.valueOf(x)
                case StrV(t)   => t
                case other     => other.toString
              ps.setObject(i + 1, raw)
            }
            if sql.contains(";") && !sql.trim.toUpperCase.startsWith("SELECT") then
              // ```transaction fence: multiple ;-separated statements, atomically;
              // binds distributed in order by each statement's ? count. Result =
              // the LAST statement's affected-row count.
              conn.setAutoCommit(false)
              var rest = binds
              var last = 0
              try
                sql.split(";").map(_.trim).filter(_.nonEmpty).foreach { st =>
                  val n = st.count(_ == '?')
                  val (mine, more) = rest.splitAt(n)
                  rest = more
                  val p2 = conn.prepareStatement(st)
                  mine.zipWithIndex.foreach { case (b, i) =>
                    val raw: AnyRef = b match
                      case IntV(nn)  => java.lang.Long.valueOf(nn)
                      case FloatV(d) => java.lang.Double.valueOf(d)
                      case BoolV(x)  => java.lang.Boolean.valueOf(x)
                      case StrV(t)   => t
                      case other     => other.toString
                    p2.setObject(i + 1, raw)
                  }
                  last = p2.executeUpdate()
                }
                conn.commit()
              catch { case e: Throwable => conn.rollback(); throw e }
              IntV(last.toLong)
            else if sql.trim.toUpperCase.startsWith("SELECT") then
              val rs = ps.executeQuery()
              val md = rs.getMetaData
              val cols = (1 to md.getColumnCount).map(md.getColumnLabel).toList
              var rows = List.empty[V2Value]
              while rs.next() do
                val m = scala.collection.mutable.LinkedHashMap.empty[V2Value, V2Value]
                cols.foreach { c =>
                  val v: V2Value = rs.getObject(c) match
                    case null                     => DataV("None", Vector.empty)
                    case n: java.lang.Long        => IntV(n)
                    case n: java.lang.Integer     => IntV(n.longValue)
                    case d: java.lang.Double      => FloatV(d)
                    case b: java.lang.Boolean     => BoolV(b)
                    case s: String                => StrV(s)
                    case o                        => StrV(o.toString)
                  m(StrV(c)) = v
                }
                rows = rows :+ V2Value.ForeignV(m)
              rows.foldRight(DataV("Nil", Vector.empty): V2Value)((r, acc) => DataV("Cons", Vector(r, acc)))
            else IntV(ps.executeUpdate().toLong)
          finally conn.close()
      case _ => sys.error("__sqlExec__(url, sql, binds)"))

    V2PluginRegistry.register("__try__", args => args match
      case List(thunk: ClosV, handler: ClosV) =>
        try callClosure(thunk, Nil)
        catch
          case BridgeThrow(v) => callClosure(handler, List(v))
          case e: scalascript.interpreter.InterpretError =>
            callClosure(handler, List(StrV(e.getMessage)))
      case _ => sys.error("__try__(thunk, handler)"))
    // getenv(key[, default]) — interpreter built-in not in any SPI plugin
    if V2PluginRegistry.lookupGlobal("getenv").isEmpty then
      V2PluginRegistry.registerGlobal("getenv", ClosV(Runtime.emptyEnv, -1, env => {
        val key = env.headOption match { case Some(StrV(s)) => s; case Some(v) => v.toString; case None => "" }
        val default_ = if env.length >= 2 then env(1) else StrV("")
        val v = System.getenv(key)
        Done(if v != null && v.nonEmpty then StrV(v) else default_)
      }))
    // Decimal / BigDecimal — exact numeric type backed by java.math.BigDecimal
    if V2PluginRegistry.lookupGlobal("Decimal").isEmpty then
      registerDecimalSupport()
    // BigInt factory: BigInt(n) / BigInt("123")
    if V2PluginRegistry.lookupGlobal("BigInt").isEmpty then
      V2PluginRegistry.registerGlobal("BigInt", ClosV(Runtime.emptyEnv, -1, env => env.toList match
        case List(IntV(n)) => Done(BigV(BigInt(n)))
        case List(StrV(s)) => Done(BigV(BigInt(s)))
        case List(BigV(n)) => Done(BigV(n))
        case _             => Done(BigV(BigInt(0)))
      ))
    // Exception factories used in throw expressions
    Seq("RuntimeException","Exception","IllegalArgumentException","IllegalStateException","UnsupportedOperationException").foreach { exName =>
      if V2PluginRegistry.lookupGlobal(exName).isEmpty then
        V2PluginRegistry.registerGlobal(exName, ClosV(Runtime.emptyEnv, -1, env => {
          val msg = env.headOption.map {
            case StrV(s) => s; case v => v.toString
          }.getOrElse(exName)
          Done(DataV(exName, Vector(StrV(msg))))
        }))
    }
    // scope(name) — CSS scoping helper: returns object with cls(n)→"n__name" and css(s)→scoped CSS
    if V2PluginRegistry.lookupGlobal("scope").isEmpty then
      V2PluginRegistry.registerGlobal("scope", ClosV(Runtime.emptyEnv, 1, env => {
        val name = env.last match { case StrV(s) => s; case v => v.toString }
        val clsFn = ClosV(Runtime.emptyEnv, 1, e => e.last match
          case StrV(n) => Done(StrV(s"${n}__${name}")); case v => Done(v))
        val cssFn = ClosV(Runtime.emptyEnv, 1, e => Done(e.lastOption.getOrElse(StrV(""))))
        Done(ForeignV(new ssc.Value.NamedMethodObj {
          def getField(field: String): Option[ssc.Value] = field match
            case "cls" => Some(clsFn)
            case "css" => Some(cssFn)
            case _     => None
          def underlying: AnyRef = name
        }))
      }))
    // raw(s) — pass-through for raw HTML/CSS strings
    if V2PluginRegistry.lookupGlobal("raw").isEmpty then
      V2PluginRegistry.registerGlobal("raw", ClosV(Runtime.emptyEnv, -1, env => Done(env.lastOption.getOrElse(UnitV))))
    // attr(name)(value) — HTML attribute builder stub → return value as-is
    if V2PluginRegistry.lookupGlobal("attr").isEmpty then
      V2PluginRegistry.registerGlobal("attr", ClosV(Runtime.emptyEnv, -1, env => Done(env.lastOption.getOrElse(UnitV))))

  private def registerDecimalSupport(): Unit =
    import java.math.{BigDecimal => JBD, RoundingMode => JRM, MathContext}
    import V2Value.*
    def toBD(v: V2Value): JBD = v match
      case StrV(s)           => new JBD(s.trim)
      case IntV(n)           => JBD.valueOf(n)
      case FloatV(d)         => JBD.valueOf(d)
      case ForeignV(bd: JBD) => bd
      case DataV("Decimal", fields) if fields.nonEmpty => toBD(fields.head)
      case _                 => throw scalascript.interpreter.InterpretError(s"Decimal: not a number: $v")
    def fromBD(bd: JBD): V2Value = ForeignV(bd)
    def toRM(v: V2Value): JRM = v match
      case DataV(name, _)    => try JRM.valueOf(name) catch case _: Throwable => JRM.HALF_UP
      case ForeignV(rm: JRM) => rm
      case _                 => JRM.HALF_UP
    val decimalCtor = ClosV(Runtime.emptyEnv, -1, env => env.toList match
      case List(v: V2Value)    => Done(fromBD(toBD(v)))
      case List(u: V2Value, s: V2Value) =>
        val unscaled = u match { case IntV(n) => BigInt(n); case BigV(n) => n; case _ => BigInt(toBD(u).unscaledValue()) }
        Done(fromBD(new JBD(unscaled.bigInteger, s match { case IntV(n) => n.toInt; case _ => 0 })))
      case _ => Done(fromBD(JBD.ZERO))
    )
    V2PluginRegistry.registerGlobal("Decimal", decimalCtor)
    V2PluginRegistry.registerGlobal("BigDecimal", decimalCtor)
    // RoundingMode object with fields
    val rmMap: Map[String, V2Value] = Seq("UP","DOWN","CEILING","FLOOR","HALF_UP","HALF_DOWN","HALF_EVEN","UNNECESSARY")
      .map(n => n -> ForeignV(JRM.valueOf(n))).toMap
    V2PluginRegistry.registerGlobal("RoundingMode", ForeignV(rmMap.asInstanceOf[AnyRef]))
    // Dispatch setScale/round/add/sub/mul/div/compareTo on ForeignV(JBD)
    V2PluginRegistry.register("__method__.setScale", args => args(1) match
      case ForeignV(bd: JBD) =>
        val scale = args(2) match { case IntV(n) => n.toInt; case _ => 2 }
        val rm    = if args.length >= 4 then toRM(args(3)) else JRM.HALF_UP
        fromBD(bd.setScale(scale, rm))
      case _ => sys.error(s"__method__: no dispatch for .setScale on ${args(1)}")
    )
    V2PluginRegistry.register("__method__.round", args => args(1) match
      case ForeignV(bd: JBD) =>
        val scale = args(2) match { case IntV(n) => n.toInt; case FloatV(d) => d.toInt; case _ => 0 }
        fromBD(bd.setScale(scale, JRM.HALF_UP))
      case _ => sys.error(s"__method__: no dispatch for .round on ${args(1)}")
    )
    V2PluginRegistry.register("__method__.toInt", args => args(1) match
      case ForeignV(bd: JBD) => IntV(bd.longValueExact())
      case _ => sys.error(s"__method__: no dispatch for .toInt on ${args(1)}")
    )
    V2PluginRegistry.register("__method__.toLong", args => args(1) match
      case ForeignV(bd: JBD) => IntV(bd.longValueExact())
      case _ => sys.error(s"__method__: no dispatch for .toLong on ${args(1)}")
    )
    V2PluginRegistry.register("__method__.toDouble", args => args(1) match
      case ForeignV(bd: JBD) => FloatV(bd.doubleValue())
      case _ => sys.error(s"__method__: no dispatch for .toDouble on ${args(1)}")
    )
    V2PluginRegistry.register("__method__.scale", args => args(1) match
      case ForeignV(bd: JBD) => IntV(bd.scale())
      case _ => sys.error(s"__method__: no dispatch for .scale on ${args(1)}")
    )
    V2PluginRegistry.register("__method__.unscaledValue", args => args(1) match
      case ForeignV(bd: JBD) => BigV(BigInt(bd.unscaledValue()))
      case _ => sys.error(s"__method__: no dispatch for .unscaledValue on ${args(1)}")
    )
    V2PluginRegistry.register("__method__.compareTo", args => args(1) match
      case ForeignV(x: JBD) => args(2) match
        case ForeignV(y: JBD) => IntV(x.compareTo(y))
        case IntV(n)          => IntV(x.compareTo(JBD.valueOf(n)))
        case v                => IntV(x.compareTo(toBD(v)))
      case _ => sys.error(s"__method__: no dispatch for .compareTo on ${args(1)}")
    )
    V2PluginRegistry.register("__method__.abs", args => args(1) match
      case ForeignV(bd: JBD) => fromBD(bd.abs())
      case _ => sys.error(s"__method__: no dispatch for .abs on ${args(1)}")
    )
    // Arithmetic on Decimal: __arith__ dispatches "+","-","*","/" etc.
    V2PluginRegistry.register("decimal.add", args => fromBD(toBD(args(0)).add(toBD(args(1)))))
    V2PluginRegistry.register("decimal.sub", args => fromBD(toBD(args(0)).subtract(toBD(args(1)))))
    V2PluginRegistry.register("decimal.mul", args => fromBD(toBD(args(0)).multiply(toBD(args(1)))))
    V2PluginRegistry.register("decimal.div", args => {
      val (a, b) = (toBD(args(0)), toBD(args(1)))
      fromBD(a.divide(b, a.scale().max(b.scale()).max(10), JRM.HALF_UP))
    })
    V2PluginRegistry.register("decimal.lt",  args => BoolV(toBD(args(0)).compareTo(toBD(args(1))) < 0))
    V2PluginRegistry.register("decimal.le",  args => BoolV(toBD(args(0)).compareTo(toBD(args(1))) <= 0))
    V2PluginRegistry.register("decimal.gt",  args => BoolV(toBD(args(0)).compareTo(toBD(args(1))) > 0))
    V2PluginRegistry.register("decimal.ge",  args => BoolV(toBD(args(0)).compareTo(toBD(args(1))) >= 0))
    V2PluginRegistry.register("decimal.eq",  args => BoolV(toBD(args(0)).compareTo(toBD(args(1))) == 0))

  /** Register dispatch for ComputedCell.get and Storage.get. */
  private def registerComputedCellDispatch(): Unit =
    V2PluginRegistry.register("__method__.get", args => {
      // args = [StrV("get"), recv, ...margs]
      if args.length >= 2 then args(1) match
        case V2Value.ForeignV(cell: ComputedCell) => cell.get()
        case V2Value.DataV("Storage", _) =>
          val key = if args.length >= 3 then args(2) match { case V2Value.StrV(s) => s; case v => v.toString } else ""
          val map = storageLocal.get()
          if map == null then sys.error("Storage.get called outside runEphemeralStorage / runStorage")
          val sv = map.getOrElse(key, null)
          if sv != null then V2Value.DataV("Some", Vector(V2Value.StrV(sv)))
          else V2Value.DataV("None", Vector.empty)
        case _ => V2Value.DataV("Stub", Vector.empty)  // stub: .get on unknown/Op value
      else sys.error(s"__method__.get: too few args")
    })
    V2PluginRegistry.register("__method__.put", args => {
      if args.length >= 4 then args(1) match
        case V2Value.DataV("Storage", _) =>
          val key = args(2) match { case V2Value.StrV(s) => s; case v => v.toString }
          val value = args(3) match { case V2Value.StrV(s) => s; case v => v.toString }
          val map = storageLocal.get()
          if map != null then map(key) = value
          V2Value.UnitV
        case _ => V2Value.UnitV  // stub: .put on unknown/Op value
      else if args.length >= 2 then V2Value.UnitV  // too few args for put: treat as no-op
      else sys.error(s"__method__.put: too few args")
    })
    V2PluginRegistry.register("__method__.remove", args => {
      if args.length >= 3 then args(1) match
        case V2Value.DataV("Storage", _) =>
          val key = args(2) match { case V2Value.StrV(s) => s; case v => v.toString }
          val map = storageLocal.get()
          if map != null then map.remove(key)
          V2Value.UnitV
        case _ => V2Value.UnitV  // stub: .remove on unknown value
      else V2Value.UnitV
    })
    V2PluginRegistry.register("__method__.has", args => {
      if args.length >= 3 then args(1) match
        case V2Value.DataV("Storage", _) =>
          val key = args(2) match { case V2Value.StrV(s) => s; case v => v.toString }
          val map = storageLocal.get()
          V2Value.BoolV(map != null && map.contains(key))
        case _ => V2Value.BoolV(false)  // stub: .has on unknown value
      else V2Value.BoolV(false)
    })
    V2PluginRegistry.register("__method__.keys", args => {
      if args.length >= 2 then args(1) match
        case V2Value.DataV("Storage", _) =>
          val map = storageLocal.get()
          val keys = if map != null then map.keys.toList else Nil
          keys.foldRight[V2Value](V2Value.DataV("Nil", Vector.empty)) { (k, acc) =>
            V2Value.DataV("Cons", Vector(V2Value.StrV(k), acc))
          }
        case _ => V2Value.DataV("Cons", Vector(V2Value.StrV("stub-key"), V2Value.DataV("Nil", Vector.empty)))
      else V2Value.DataV("Nil", Vector.empty)
    })
    V2PluginRegistry.register("__method__.apply", args => {
      if args.length >= 2 then args(1) match
        case V2Value.ForeignV(cell: ComputedCell) => cell.get()
        case _ => V2Value.DataV("Stub", Vector.empty)  // stub: .apply on unknown value
      else V2Value.DataV("Stub", Vector.empty)
    })

  /** Thread-local in-memory storage map for runEphemeralStorage / runStorage. */
  private val storageLocal: ThreadLocal[scala.collection.mutable.LinkedHashMap[String, String]] =
    new ThreadLocal()

  /** Computed signal: re-evaluates its thunk on every .get call (no reactive tracking). */
  private class ComputedCell(val thunk: V2Value.ClosV):
    def get(): V2Value = callClosure(thunk, Nil)

  private val genQueueTL: java.lang.ThreadLocal[java.util.concurrent.LinkedBlockingQueue[Option[V2Value]]] =
    java.lang.ThreadLocal.withInitial(() => null)

  private def makeGenerator(queue: java.util.concurrent.LinkedBlockingQueue[Option[V2Value]]): V2Value =
    import V2Value.*
    type Q = java.util.concurrent.LinkedBlockingQueue[Option[V2Value]]
    def chainedGen(body: Q => Unit): V2Value =
      val q2 = new Q()
      Thread.ofVirtual().start { () =>
        try body(q2)
        catch case _: Throwable => ()
        finally try q2.put(None) catch case _ => ()
      }
      makeGenerator(q2)
    def listFrom(q: Q): V2Value =
      val buf = collection.mutable.ArrayBuffer.empty[V2Value]
      var item = q.take()
      while item.isDefined do
        buf += item.get
        item = q.take()
      buf.foldRight[V2Value](DataV("Nil", Vector.empty))((v, acc) => DataV("Cons", Vector(v, acc)))
    ForeignV(scala.collection.immutable.Map[String, V2Value](
      "next" -> ClosV(Runtime.emptyEnv, 0, _ => Done(
        queue.take() match
          case Some(v) => DataV("Some", Vector(v))
          case None    => DataV("None", Vector.empty)
      )),
      "toList" -> ClosV(Runtime.emptyEnv, 0, _ => Done(listFrom(queue))),
      "foreach" -> ClosV(Runtime.emptyEnv, 1, env => {
        val f = env.last
        var item = queue.take()
        while item.isDefined do
          callClosure(f, List(item.get))
          item = queue.take()
        Done(UnitV)
      }),
      "map" -> ClosV(Runtime.emptyEnv, 1, env => {
        val f = env.last
        Done(chainedGen { q2 =>
          var item = queue.take()
          while item.isDefined do
            q2.put(Some(callClosure(f, List(item.get))))
            item = queue.take()
        })
      }),
      "filter" -> ClosV(Runtime.emptyEnv, 1, env => {
        val pred = env.last
        Done(chainedGen { q2 =>
          var item = queue.take()
          while item.isDefined do
            if callClosure(pred, List(item.get)) == BoolV(true) then q2.put(Some(item.get))
            item = queue.take()
        })
      }),
      "take" -> ClosV(Runtime.emptyEnv, 1, env => {
        val n = env.last match { case IntV(n) => n.toInt; case _ => 0 }
        Done(chainedGen { q2 =>
          var remaining = n
          var item = queue.take()
          while item.isDefined && remaining > 0 do
            q2.put(Some(item.get))
            remaining -= 1
            item = if remaining > 0 then queue.take() else None
        })
      }),
      "drop" -> ClosV(Runtime.emptyEnv, 1, env => {
        val n = env.last match { case IntV(n) => n.toInt; case _ => 0 }
        Done(chainedGen { q2 =>
          var toDrop = n
          var item = queue.take()
          while item.isDefined && toDrop > 0 do
            toDrop -= 1
            item = queue.take()
          while item.isDefined do
            q2.put(Some(item.get))
            item = queue.take()
        })
      }),
      "flatMap" -> ClosV(Runtime.emptyEnv, 1, env => {
        val f = env.last
        Done(chainedGen { q2 =>
          var item = queue.take()
          while item.isDefined do
            val subGen = callClosure(f, List(item.get))
            // drain the sub-generator into q2
            subGen match
              case ForeignV(m: collection.immutable.Map[?, ?]) =>
                val mm = m.asInstanceOf[collection.immutable.Map[String, V2Value]]
                mm.get("toList") match
                  case Some(fn: ClosV) =>
                    val lst = Runtime.run(fn.code, if fn.env.isEmpty then Array.empty else fn.env)
                    def drainList(v: V2Value): Unit = v match
                      case DataV("Cons", elems) => q2.put(Some(elems(0))); drainList(elems(1))
                      case _ => ()
                    drainList(lst)
                  case _ => ()
              case _ => ()
            item = queue.take()
        })
      }),
      "zip" -> ClosV(Runtime.emptyEnv, 1, env => {
        val other = env.last
        Done(chainedGen { q2 =>
          // get next from other generator via its next method
          def otherNext(): Option[V2Value] = other match
            case ForeignV(m: collection.immutable.Map[?, ?]) =>
              val mm = m.asInstanceOf[collection.immutable.Map[String, V2Value]]
              mm.get("next") match
                case Some(fn: ClosV) =>
                  Runtime.run(fn.code, if fn.env.isEmpty then Array.empty else fn.env) match
                    case DataV("Some", elems) => Some(elems(0))
                    case _ => None
                case _ => None
            case _ => None
          var item = queue.take()
          var running = true
          while item.isDefined && running do
            otherNext() match
              case Some(r) =>
                q2.put(Some(DataV("Tuple2", Vector(item.get, r))))
                item = queue.take()
              case None => running = false
        })
      }),
      "zipWithIndex" -> ClosV(Runtime.emptyEnv, 0, _ => {
        Done(chainedGen { q2 =>
          var idx = 0L
          var item = queue.take()
          while item.isDefined do
            q2.put(Some(DataV("Tuple2", Vector(item.get, IntV(idx)))))
            idx += 1
            item = queue.take()
        })
      }),
    ).asInstanceOf[AnyRef])

  /** Register `sys` global: sys.env(key) / sys.env.getOrElse(k,d) / sys.exit(code). */
  private def registerSys(): Unit =
    import V2Value.*
    if V2PluginRegistry.lookupGlobal("sys").isDefined then return
    // envGetOrElse: called when sys.env.getOrElse(key, default) is used
    val envGetOrElse = ClosV(Runtime.emptyEnv, 2, env => {
      val key = env(env.length - 2) match { case StrV(s) => s; case v => v.toString }
      val dflt = env.last
      Done(Option(System.getenv(key)).map(StrV.apply).getOrElse(dflt))
    })
    val envGet = ClosV(Runtime.emptyEnv, 1, env => {
      val key = env.last match { case StrV(s) => s; case v => v.toString }
      Done(Option(System.getenv(key)).map(s => DataV("Some", Vector(StrV(s)))).getOrElse(DataV("None", Vector.empty)))
    })
    // envObj: sys.env alone → ForeignV(Map) with getOrElse/get methods for chaining
    val envObj = ForeignV(scala.collection.immutable.Map[String, V2Value](
      "getOrElse" -> envGetOrElse,
      "get"       -> envGet,
    ).asInstanceOf[AnyRef])
    // Register __method__.env handler: called when sys.env("KEY") or sys.env is accessed
    // (Runtime.scala ForeignV(Map) dispatch falls through to plugin when value is a ForeignV)
    V2PluginRegistry.register("__method__.env", args => {
      val margs = args.drop(2)  // args = [name, recv, ...margs]
      margs match
        case Nil           => envObj  // sys.env alone → envObj for chaining
        case List(StrV(k)) => Option(System.getenv(k)).map(StrV.apply).getOrElse(DataV("None", Vector.empty))
        case _             => DataV("None", Vector.empty)
    })
    val sysObj = ForeignV(scala.collection.immutable.Map[String, V2Value](
      "env"  -> envObj,  // 0-arg: sys.env → envObj; "KEY"-arg: handled by __method__.env plugin
      "exit" -> ClosV(Runtime.emptyEnv, 1, env => {
        ssc.Runtime.exitHandler(env.last match { case IntV(n) => n.toInt; case _ => 0 })
        Done(UnitV)
      }),
    ).asInstanceOf[AnyRef])
    V2PluginRegistry.registerGlobal("sys", sysObj)

  private def registerHandle(): Unit =
    if V2PluginRegistry.lookupGlobal("handle").isDefined then return // idempotent
    // handle(bodyResult)(handlerFn) — curried, 2 sequential arity-1 applications
    val handleClosure = V2Value.ClosV(Runtime.emptyEnv, 1, env => {
      val bodyResult = env.last  // Local(0) = most recent arg
      // Inner closure captures bodyResult; when called with handler:
      // extend([bodyResult], [handler]) = [bodyResult, handler]
      // Local(0) = handler = env2.last; Local(1) = bodyResult = env2(0)
      Done(V2Value.ClosV(Array(bodyResult), 1, env2 => {
        // `handle { expr }` may arrive as a 0-arg THUNK (block-arg convention) —
        // force it before entering the loop, else Return(thunk) leaks a closure.
        val body = env2(0) match
          case t: V2Value.ClosV if t.arity == 0 => callClosure(t, Nil)
          case v => v
        val handler = env2.last  // new arg = handlerFn
        Done(runEffectLoop(body, handler))
      }))
    })
    V2PluginRegistry.registerGlobal("handle", handleClosure)
    // Also register "effect" as a no-op (FrontendBridge emits it for `effect Foo:` declarations)
    V2PluginRegistry.registerGlobal("effect", V2Value.ClosV(Runtime.emptyEnv, 1, _ => Done(V2Value.UnitV)))

  /** Run the Free-monad interpreter loop.
   *  - Op("EffTag.opName", arg, k): call handler with DataV(opName, [arg, resumeFn])
   *  - anything else:                call handler with DataV("Return", [v]) */
  private def runEffectLoop(v: V2Value, handler: V2Value): V2Value = v match
    case V2Value.DataV("Op", IndexedSeq(V2Value.StrV(label), arg, k)) =>
      val opName = label.split("\\.").last  // "Logger.log" → "log"
      // resumeFn captures k and handler; when called with r:
      // extend([k, handler], [r]) = [k, handler, r] → Local(0)=r=env2.last, Local(1)=handler=env2(1), Local(2)=k=env2(0)
      val resumeFn = V2Value.ClosV(Array(k.asInstanceOf[V2Value], handler), 1, env2 => {
        val resumeArg = env2.last // Local(0) = new arg r
        val kk        = env2(0)   // captured k (first in base env)
        val h         = env2(1)   // captured handler (second in base env)
        val next = callClosure(kk, List(resumeArg))
        Done(runEffectLoop(next, h))
      })
      val margs = arg match
        case V2Value.UnitV => List(resumeFn)
        case _             => List(arg, resumeFn)
      callClosure(handler, List(V2Value.DataV(opName, margs.toVector)))
    case _ =>
      // Pure result — call handler with Return(v). A handler WITHOUT a Return
      // clause passes the value through unchanged (v1 semantics: the return
      // clause is optional).
      try callClosure(handler, List(V2Value.DataV("Return", Vector(v))))
      catch
        case e: RuntimeException if e.getMessage != null
            && e.getMessage.startsWith("match: no arm for Return") => v

  // ── Actor runtime — VirtualThread-per-actor model ───────────────────────────

  /** One actor's mailbox + thread state. */
  private class ActorMailbox:
    val queue = new java.util.concurrent.LinkedBlockingQueue[V2Value]()
    @volatile var thread: Thread | Null = null
    @volatile var dead: Boolean = false
    @volatile var trapExit: Boolean = false  // Erlang-style: exits become messages
    // Supervision: links are notified with Exit(reason), monitors with Down(reason)
    val links    = java.util.concurrent.ConcurrentHashMap.newKeySet[ActorMailbox]()
    val monitors = java.util.concurrent.ConcurrentHashMap.newKeySet[ActorMailbox]()

  /** Notify links + monitors that `mb` terminated with `reason` (Erlang semantics:
   *  a trapping link gets Exit(reason) as a message; a non-trapping link is killed;
   *  monitors always get a Down(reason) message). */
  private def notifyDeath(mb: ActorMailbox, reason: V2Value): Unit =
    mb.links.forEach { l =>
      if !l.dead then
        if l.trapExit then l.queue.put(V2Value.DataV("Exit", Vector(reason)))
        else { l.dead = true; val t = l.thread; if t != null then t.interrupt() }
    }
    mb.monitors.forEach { m =>
      if !m.dead then m.queue.put(V2Value.DataV("Down", Vector(reason)))
    }
    mb.links.clear(); mb.monitors.clear()

  private val actorTL = new ThreadLocal[ActorMailbox | Null]:
    override def initialValue(): ActorMailbox | Null = null


  /** Register actor globals: spawn, receive, self, exit, runActors.
   *  `!` is dispatched via __arith__("!", actorRef, msg) in Runtime.scala's
   *  dispatch table (separate patch), so it is wired via V2PluginRegistry.register. */
  /** Build a receive-with-timeout closure. Returns a ClosV(arity=1) that takes a handler
   *  and either calls it with the message (returning Some(result)) or returns None on timeout. */
  private def receiveWithTimeout(ms: Long): V2Value =
    V2Value.ClosV(Array(V2Value.IntV(ms)), 1, env2 => {
      val handler = env2.last
      val timeoutMs = env2(0).asInstanceOf[V2Value.IntV].n
      val mb = actorTL.get()
      if mb == null then sys.error("receive(timeout) called outside an actor")
      val msg = if timeoutMs < 0 then mb.queue.take()
                else mb.queue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
      if msg == null || mb.dead then Done(V2Value.DataV("None", Vector.empty))
      else Done(V2Value.DataV("Some", Vector(callClosure(handler.asInstanceOf[V2Value.ClosV], List(msg)))))
    })

  /** Read the @timeout cell registered by registerActors. Returns -1 if unset. */
  private def readTimeoutMs(): Long =
    V2PluginRegistry.lookupGlobal("@timeout") match
      case Some(V2Value.ForeignV(arr: Array[V2Value] @unchecked)) =>
        arr(0) match { case V2Value.IntV(n) => n; case _ => -1L }
      case _ => -1L

  private def registerActors(): Unit =
    if V2PluginRegistry.lookupGlobal("runActors").isDefined then return
    // Register @timeout cell (used by receive(timeout = n) named-arg pattern)
    val timeoutCell = Array[V2Value](V2Value.IntV(-1))
    V2PluginRegistry.registerGlobal("@timeout", V2Value.ForeignV(timeoutCell.asInstanceOf[AnyRef]))

    // spawn { () => body } — starts a VirtualThread, returns ForeignV(mailbox)
    V2PluginRegistry.registerGlobal("spawn", V2Value.ClosV(Runtime.emptyEnv, 1, env => {
      val thunk = env.last
      val mb = new ActorMailbox()
      val t = Thread.ofVirtual().start(() => {
        actorTL.set(mb)
        mb.thread = Thread.currentThread()
        var reason: V2Value = V2Value.StrV("normal")
        try callThunk(thunk.asInstanceOf[V2Value.ClosV])
        catch
          case _: InterruptedException => reason = V2Value.StrV("killed")
          case e: Throwable => reason = V2Value.StrV(Option(e.getMessage).getOrElse("error"))
        finally
          mb.dead = true
          notifyDeath(mb, reason)
        ()
      })
      mb.thread = t
      Done(V2Value.ForeignV(mb))
    }))

    // receive { case msg => ... } — blocks on mailbox, calls handler with msg
    // receive(timeout = n) { case msg => ... } — with timeout (returns Some/None)
    V2PluginRegistry.registerGlobal("receive", V2Value.ClosV(Runtime.emptyEnv, 1, env => {
      val arg = env.last
      arg match
        case handler: V2Value.ClosV =>
          // Direct receive: receive { case msg => ... }
          val mb = actorTL.get()
          if mb == null then sys.error("receive called outside an actor")
          val msg = mb.queue.take()
          if mb.dead then Done(V2Value.UnitV) else Done(callClosure(handler, List(msg)))
        case V2Value.UnitV =>
          // receive(timeout = n) {...}: named arg emits cell.set(@timeout, n) → UnitV
          // read the @timeout cell that was just set by cell.set
          val ms = readTimeoutMs()
          Done(receiveWithTimeout(ms))
        case V2Value.IntV(ms) =>
          // receive(ms)(handler) — directly passed Int timeout
          Done(receiveWithTimeout(ms))
        case _ =>
          sys.error(s"receive: unexpected arg $arg")
    }))

    // trapExit(flag) — Erlang-style: when true, exit(self, reason) becomes an
    // Exit(reason) MESSAGE in this actor's mailbox instead of a kill.
    V2PluginRegistry.registerGlobal("trapExit", V2Value.ClosV(Runtime.emptyEnv, -1, env => {
      val mb = actorTL.get()
      if mb != null then
        mb.trapExit = env.headOption match
          case Some(V2Value.BoolV(b)) => b
          case _ => true
      Done(V2Value.UnitV)
    }))

    // link(actorRef) — bidirectional supervision link with the CURRENT actor:
    // when either side dies, the other is killed (or messaged if it traps exits).
    V2PluginRegistry.registerGlobal("link", V2Value.ClosV(Runtime.emptyEnv, 1, env => {
      val me = actorTL.get()
      env.last match
        case V2Value.ForeignV(other: ActorMailbox) if me != null =>
          other.links.add(me); me.links.add(other)
        case _ => ()
      Done(V2Value.UnitV)
    }))

    // monitor(actorRef) — unidirectional: current actor gets Down(reason) when it dies.
    V2PluginRegistry.registerGlobal("monitor", V2Value.ClosV(Runtime.emptyEnv, 1, env => {
      val me = actorTL.get()
      env.last match
        case V2Value.ForeignV(other: ActorMailbox) if me != null =>
          other.monitors.add(me)
        case _ => ()
      Done(V2Value.UnitV)
    }))

    // self() — returns current actor's mailbox ref
    V2PluginRegistry.registerGlobal("self", V2Value.ClosV(Runtime.emptyEnv, 0, env => {
      val mb = actorTL.get()
      if mb == null then sys.error("self() called outside an actor")
      Done(V2Value.ForeignV(mb))
    }))

    // exit — POLYMORPHIC (variadic): exit(actorRef[, reason]) kills an actor;
    // exit([code]) is a process exit routed through Runtime.exitHandler so an
    // embedding batch runner can intercept it instead of dying.
    V2PluginRegistry.registerGlobal("exit", V2Value.ClosV(Runtime.emptyEnv, -1, env => {
      val first = if env.nonEmpty then env(0) else V2Value.UnitV
      first match
        case V2Value.ForeignV(mb: ActorMailbox) =>
          if mb.trapExit then
            // trapExit: deliver Exit(reason) as a message instead of killing
            val reason = if env.length >= 2 then env(1) else V2Value.StrV("normal")
            mb.queue.put(V2Value.DataV("Exit", Vector(reason)))
          else
            mb.dead = true
            val t = mb.thread
            if t != null then t.interrupt()
            val reason = if env.length >= 2 then env(1) else V2Value.StrV("killed")
            notifyDeath(mb, reason)
          Done(V2Value.UnitV)
        case V2Value.IntV(code) => Runtime.exitHandler(code.toInt)
        case _ => Runtime.exitHandler(0)
    }))

    // runActors { body } — BlockForm-like: runs body in a main actor VirtualThread
    // and waits for it to complete
    V2PluginRegistry.registerGlobal("runActors", V2Value.ClosV(Runtime.emptyEnv, 1, env => {
      val thunk = env.last.asInstanceOf[V2Value.ClosV]
      val mb = new ActorMailbox()
      @volatile var result: V2Value = V2Value.UnitV
      @volatile var err: Throwable | Null = null
      val latch = new java.util.concurrent.CountDownLatch(1)
      val t = Thread.ofVirtual().start(() => {
        actorTL.set(mb)
        mb.thread = Thread.currentThread()
        try
          result = callThunk(thunk)
        catch
          case e: InterruptedException => ()
          case e: Throwable => err = e
        finally latch.countDown()
        ()
      })
      mb.thread = t
      latch.await()
      val e = err
      if e != null then throw e
      Done(result)
    }))

    // Register "!" operator handler in __arith__ dispatch (ForeignV send)
    V2PluginRegistry.register("actor.send", args => args match
      case List(V2Value.ForeignV(mb: ActorMailbox), msg) =>
        if !mb.dead then mb.queue.put(msg)
        V2Value.UnitV
      case _ => V2Value.UnitV
    )

  // ── Batch-runner HTTP helpers ────────────────────────────────────────────────

  /** Build a fake GET response for localhost URLs (oidc-login-flow.ssc authorize step).
   *  For /authorize?... URLs: returns a 302 redirect echoing `redirect_uri?code=stub&state={state}`.
   *  For any other path: returns 200 OK with an empty body. */
  private def makeLocalhostGetResp(url: String): V2Value =
    import V2Value.*
    val parsed  = scala.util.Try(new java.net.URI(url)).toOption
    val path    = parsed.map(_.getPath).getOrElse("")
    val rawQ    = parsed.flatMap(u => Option(u.getRawQuery)).getOrElse("")
    val params  = rawQ.split("&").flatMap { kv =>
      kv.split("=", 2) match
        case Array(k, v) => Some(java.net.URLDecoder.decode(k, "UTF-8") ->
                                  java.net.URLDecoder.decode(v, "UTF-8"))
        case _ => None
    }.toMap
    if path.endsWith("/authorize") then
      val state       = params.getOrElse("state", "batch-state")
      val redirectUri = params.getOrElse("redirect_uri", "http://localhost:3000/callback")
      val location    = s"$redirectUri?code=batch_code&state=$state"
      // NamedMethodObj supports .header(name), .status, .body
      ForeignV(new ssc.Value.NamedMethodObj {
        def getField(n: String): Option[ssc.Value] = n match
          case "status" => Some(IntV(302))
          case "body"   => Some(StrV(""))
          case "header" => Some(ClosV(Runtime.emptyEnv, 1, env => Done(
            env(0) match
              case StrV("Location") => DataV("Some", Vector(StrV(location)))
              case _                => DataV("None", Vector.empty)
          )))
          case _ => None
        def underlying: AnyRef = this
        override def toString = s"Response(302, Location=$location)"
      })
    else
      ForeignV(new ssc.Value.NamedMethodObj {
        def getField(n: String): Option[ssc.Value] = n match
          case "status" => Some(IntV(200))
          case "body"   => Some(StrV(""))
          case "header" => Some(ClosV(Runtime.emptyEnv, 1, _ => Done(DataV("None", Vector.empty))))
          case _ => None
        def underlying: AnyRef = this
        override def toString = s"Response(200)"
      })

  /** Build a URL-parser object: http.parseUrl(url) returns an obj with query(name) method. */
  private def makeUrlParserV2(urlV: V2Value): V2Value =
    import V2Value.*
    val url    = urlV match { case StrV(s) => s; case _ => "" }
    val rawQ: String = scala.util.Try(new java.net.URI(url)).toOption
      .flatMap(u => Option(u.getRawQuery))
      .getOrElse { val qi = url.indexOf('?'); if qi >= 0 then url.substring(qi + 1) else "" }
    val params = rawQ.split("&").flatMap { kv =>
      kv.split("=", 2) match
        case Array(k, v) => Some(java.net.URLDecoder.decode(k, "UTF-8") ->
                                  java.net.URLDecoder.decode(v, "UTF-8"))
        case _ => None
    }.toMap
    ForeignV(new ssc.Value.NamedMethodObj {
      def getField(n: String): Option[ssc.Value] = n match
        case "query" => Some(ClosV(Runtime.emptyEnv, 1, env => Done(
          env(0) match
            case StrV(name) => params.get(name).map(StrV(_)).getOrElse(StrV(""))
            case _ => StrV("")
        )))
        case _ => None
      def underlying: AnyRef = this
      override def toString = s"ParsedUrl($url)"
    })

  private def isLocalhostUrl(url: String): Boolean =
    url.startsWith("http://localhost") || url.startsWith("https://localhost") ||
    url.startsWith("http://127.0.0.1") || url.startsWith("https://127.0.0.1")

  /** Build a fake HTTP Response v2 value for runAgent/std/agent.ssc.
   *
   *  The FrontendBridge's fieldIndex("body") is non-deterministic: "body" is at index 1
   *  in AgentHttpAttempt but at index 2 in Response. To handle both orderings, we create
   *  a DataV("Response") that puts the body string at indices 0, 1, 2 and a safe fallback
   *  at index 3+. Status is embedded via the ForeignV NamedMethodObj so __method__ lookups
   *  also work, but fieldAt accesses go straight to the Vector.
   *
   *  Vector layout (by index):
   *   0 → IntV(status)    (status — only at 0 in all case classes, safe)
   *   1 → StrV(body)      (body when fieldIndex("body")=1 from AgentHttpAttempt ordering)
   *   2 → StrV(body)      (body when fieldIndex("body")=2 from Response ordering)
   *   3 → StrV("")        (errorText fallback for any index-3 access)
   *   4 → BoolV(false)    (transportError fallback for any index-4 access)
   */
  private def fakeHttpResponse(status: Int, body: String, contentType: String): V2Value =
    import V2Value.*
    DataV("Response", Vector(
      IntV(status.toLong),    // 0: status
      StrV(body),             // 1: body (AgentHttpAttempt field order)
      StrV(body),             // 2: body (Response field order)
      StrV(""),               // 3: errorText fallback
      BoolV(false)            // 4: transportError fallback
    ))

  // ── SpiValue ↔ V2Value conversion ─────────────────────────────────────────

  def v2ToSpi(v: V2Value): SpiValue = v match
    case V2Value.UnitV        => SpiValue.UnitV
    case V2Value.BoolV(b)     => SpiValue.BoolV(b)
    case V2Value.IntV(n)      => SpiValue.IntV(n)
    case V2Value.FloatV(d)    => SpiValue.DoubleV(d)
    case V2Value.StrV(s)      => SpiValue.StrV(s)
    case V2Value.BigV(n)      => SpiValue.BigIntV(n)
    case V2Value.DataV(tag, fields) =>
      SpiValue.ListV(fields.toList.map(v2ToSpi)) // best effort: fields as list
    case _                    => SpiValue.Opaque(v)

  def spiToV2(s: SpiValue): V2Value = s match
    case SpiValue.UnitV        => V2Value.UnitV
    case SpiValue.BoolV(b)     => V2Value.BoolV(b)
    case SpiValue.IntV(n)      => V2Value.IntV(n)
    case SpiValue.DoubleV(d)   => V2Value.FloatV(d)
    case SpiValue.StrV(s)      => V2Value.StrV(s)
    case SpiValue.BigIntV(n)   => V2Value.BigV(n)
    case SpiValue.ListV(items) =>
      items.foldRight[V2Value](V2Value.DataV("Nil", Vector.empty)) { (x, acc) =>
        V2Value.DataV("Cons", Vector(spiToV2(x), acc))
      }
    case SpiValue.TupleV(elems) =>
      V2Value.DataV(s"Tuple${elems.length}", elems.map(spiToV2).toVector)
    case SpiValue.Opaque(v: V2Value) => v
    case SpiValue.Opaque(v)          => V2Value.ForeignV(v.asInstanceOf[AnyRef])

  // ── v2 ↔ raw-Any for NativeImpl (mirrors Interpreter.unwrapValueAsAny) ────────

  /** Convert v2 value to the raw-Any type that NativeImpl.eval expects.
   *  Scalars become JVM primitives; complex values become v1 Values (for
   *  plugins that pattern-match on v1 InstanceV / ListV / etc.). */
  private def v2ToRaw(v: V2Value): Any = v match
    case V2Value.StrV(s)        => s
    case V2Value.IntV(n)        => n
    case V2Value.FloatV(d)      => d
    case V2Value.BoolV(b)       => b
    case V2Value.UnitV          => ()
    case V2Value.DataV("None", _) => null  // null works for isNullish(), optionalStringArg, PluginValue.Opt(None)
    case _                      => v2ToV1(v)  // complex → v1Value

  /** Convert the raw-Any returned by NativeImpl.eval back to a v2 value.
   *  Mirrors Interpreter.wrapAnyAsValue + the v1→v2 path. */
  private def rawToV2(a: Any): V2Value = a match
    case s: String  => V2Value.StrV(s)
    case n: Long    => V2Value.IntV(n)
    case i: Int     => V2Value.IntV(i.toLong)
    case d: Double  => V2Value.FloatV(d)
    case f: Float   => V2Value.FloatV(f.toDouble)
    case b: Boolean => V2Value.BoolV(b)
    case ()         => V2Value.UnitV
    case v1: V1Value => v1ToV2(v1)
    // v1 NativeFnV → variadic v2 ClosV (arity=-1, accepts any number of args)
    case nfv: scalascript.interpreter.Value.NativeFnV =>
      V2Value.ClosV(Runtime.emptyEnv, -1, env => {
        val v1Args = env.toList.map(v2ToV1)
        val computation = nfv.f(v1Args)
        import scalascript.interpreter.Computation
        Done(v1ToV2(Computation.run(computation)))
      })
    case lst: scala.collection.immutable.List[?] =>
      lst.foldRight[V2Value](V2Value.DataV("Nil", Vector.empty)) { (x, acc) =>
        V2Value.DataV("Cons", Vector(rawToV2(x.asInstanceOf[Any]), acc))
      }
    case null       => V2Value.DataV("None", Vector.empty)
    case other      => V2Value.ForeignV(other.asInstanceOf[AnyRef])

  // ── Value translation: v2 Value → v1 Value ─────────────────────────────

  /** Convert a v2 VM value to a v1 interpreter Value (for plugin arguments). */
  def v2ToV1(v: V2Value): V1Value = v match
    case V2Value.UnitV        => DataValue.UnitV
    case V2Value.BoolV(b)     => DataValue.BoolV(b)
    case V2Value.IntV(n)      => DataValue.IntV(n)
    case V2Value.FloatV(d)    => DataValue.DoubleV(d)
    case V2Value.StrV(s)      => DataValue.StringV(s)
    case V2Value.BigV(n)      => DataValue.BigIntV(n)
    // Cons/Nil list chain → v1 ListV
    case V2Value.DataV("Nil", _) =>
      scalascript.interpreter.Value.ListV(Nil)
    case cons @ V2Value.DataV("Cons", _) =>
      def toList(v: V2Value): List[V1Value] = v match
        case V2Value.DataV("Nil", _)           => Nil
        case V2Value.DataV("Cons", Seq(h, t))  => v2ToV1(h) :: toList(t)
        case _                                 => List(v2ToV1(v))
      scalascript.interpreter.Value.ListV(toList(cons))
    // Option variants
    case V2Value.DataV("None", _) =>
      scalascript.interpreter.Value.OptionV(null)
    case V2Value.DataV("Some", Seq(inner)) =>
      scalascript.interpreter.Value.OptionV(v2ToV1(inner))
    case V2Value.BytesV(bs)   =>
      // Bytes: v1 has no BytesV; wrap as a list of IntV bytes
      val items = bs.toList.map(b => DataValue.IntV((b & 0xff).toLong): V1Value)
      scalascript.interpreter.Value.ListV(items)
    case V2Value.DataV(tag, fields) if tag.startsWith("Tuple") && tag.drop(5).forall(_.isDigit) =>
      // TupleN → v1 TupleV
      scalascript.interpreter.Value.TupleV(fields.toList.map(v2ToV1))
    case V2Value.DataV(tag, fields) =>
      // Use named fields from the bridge's field registry when available;
      // fall back to positional _0/_1/… so plugins can still use positional access.
      val knownNames: Option[Vector[String]] = V2PluginRegistry.lookupFieldNames(tag)
      val fieldMap: Map[String, V1Value] = knownNames match
        case Some(names) if names.length == fields.length =>
          // Named field map (both named and positional keys for compatibility)
          (names.zip(fields).map { case (n, fv) => n -> v2ToV1(fv) } ++
           fields.zipWithIndex.map { case (fv, i) => s"_$i" -> v2ToV1(fv) }).toMap
        case _ =>
          fields.zipWithIndex.map { case (fv, i) => s"_$i" -> v2ToV1(fv) }.toMap
      val inst = scalascript.interpreter.Value.InstanceV(tag, fieldMap)
      val arr: Array[V1Value] = fields.map(v2ToV1).toArray
      val names: Array[String] = knownNames match
        case Some(ns) if ns.length == fields.length => ns.toArray
        case _ => fields.indices.map(i => s"_$i").toArray
      inst.fieldsArr = arr
      inst.fieldNames = names
      inst
    case V2Value.ForeignV(m: scala.collection.mutable.Map[?, ?]) =>
      // v2 Map (from __mk_map__) → v1 MapV so plugins can MapVal.unapply
      val converted = m.asInstanceOf[scala.collection.mutable.Map[V2Value, V2Value]]
        .map { case (k, v2) => v2ToV1(k) -> v2ToV1(v2) }.toMap
      scalascript.interpreter.Value.MapV(converted)
    case V2Value.ForeignV(m: scala.collection.immutable.Map[?, ?]) if {
          // Named-field maps (from InstanceV conversion) have String keys — treat as InstanceV
          m.nonEmpty && m.keys.head.isInstanceOf[String]
        } =>
      val nm = m.asInstanceOf[scala.collection.immutable.Map[String, V2Value]]
      val fieldMap = nm.map { case (k, v2) => k -> v2ToV1(v2) }.toMap
      val inst = scalascript.interpreter.Value.InstanceV("record", fieldMap)
      val names = nm.keys.toArray
      inst.fieldNames = names
      inst.fieldsArr  = names.map(k => fieldMap(k))
      inst
    case V2Value.ForeignV(m: scala.collection.immutable.Map[?, ?]) =>
      val converted = m.asInstanceOf[scala.collection.immutable.Map[V2Value, V2Value]]
        .map { case (k, v2) => v2ToV1(k) -> v2ToV1(v2) }
      scalascript.interpreter.Value.MapV(converted)
    case V2Value.ForeignV(nmo: ssc.Value.NamedMethodObj) =>
      nmo.underlying match
        case inst: scalascript.interpreter.Value.InstanceV => inst
        case v1: V1Value                                   => v1
        case other => scalascript.interpreter.Value.Foreign(other.getClass.getSimpleName, other)
    case V2Value.ForeignV(tf: TaggedForeign) =>
      scalascript.interpreter.Value.Foreign(tf.tag, tf.value)
    case V2Value.ForeignV(h) =>
      scalascript.interpreter.Value.Foreign(h.getClass.getSimpleName, h)
    case c: V2Value.ClosV =>
      // Wrap v2 ClosV as a v1 NativeFnV so plugins can call it via invokeCallback.
      scalascript.interpreter.Value.NativeFnV("v2Closure", v1Args => {
        val v2Args = v1Args.map(v1ToV2)
        val env    = if v2Args.isEmpty then c.env else Runtime.extend(c.env, v2Args.toArray)
        scalascript.interpreter.Computation.Pure(v2ToV1(Runtime.run(c.code, env)))
      })
    case _ =>
      // LongCellV and other non-translatable values — wrap as opaque Foreign
      scalascript.interpreter.Value.Foreign("v2Value", v.asInstanceOf[AnyRef])

  // ── Value translation: v1 Value → v2 Value ─────────────────────────────

  /** Convert a v1 interpreter Value to a v2 VM value (for plugin return values). */
  def v1ToV2(v: Any): V2Value = v match
    case DataValue.UnitV        => V2Value.UnitV
    case DataValue.BoolV(b)     => V2Value.BoolV(b)
    case DataValue.IntV(n)      => V2Value.IntV(n)
    case DataValue.DoubleV(d)   => V2Value.FloatV(d)
    case DataValue.StringV(s)   => V2Value.StrV(s)
    case DataValue.BigIntV(n)   => V2Value.BigV(n)
    case DataValue.DecimalV(d)  => V2Value.FloatV(d.toDouble)
    case DataValue.CharV(c)     => V2Value.StrV(c.toString)
    case DataValue.NullV        => V2Value.DataV("None", Vector.empty) // closest v2 equivalent
    case scalascript.interpreter.Value.InstanceV(tag, _) =>
      val inst = v.asInstanceOf[scalascript.interpreter.Value.InstanceV]
      val effFields = inst.effectiveFields
      val arr = inst.fieldsArr
      if arr != null then
        // orderedInstance: positional array → DataV preserves fieldAt compatibility
        V2Value.DataV(tag, arr.toVector.map(v1ToV2))
      else
        // If the tag has registered field names (from case class in imported .ssc lib),
        // build DataV in declaration order so fieldAt(obj, i) works correctly.
        V2PluginRegistry.lookupFieldNames(tag) match
          case Some(orderedNames) if effFields.nonEmpty =>
            val orderedVals = orderedNames.map(n =>
              effFields.get(n).map(v1ToV2).getOrElse(V2Value.UnitV))
            V2Value.DataV(tag, orderedVals.toVector)
          case _ =>
            val hasRealNames = effFields.nonEmpty &&
              !effFields.keys.forall(k => k.matches("_\\d+"))
            if hasRealNames then
              V2Value.ForeignV(new ssc.Value.NamedMethodObj {
                def getField(n: String): Option[ssc.Value] = effFields.get(n).map(v1ToV2)
                def underlying: AnyRef = inst
                override def toString: String = s"$tag(${effFields.keys.mkString(", ")})"
              })
            else
              V2Value.DataV(tag, effFields.values.toVector.map(v1ToV2))
    case scalascript.interpreter.Value.ListV(items) =>
      // Encode as a Cons/Nil chain (v2 list encoding)
      items.foldRight[V2Value](V2Value.DataV("Nil", Vector.empty)) { (item, acc) =>
        V2Value.DataV("Cons", Vector(v1ToV2(item), acc))
      }
    case scalascript.interpreter.Value.VectorV(items) =>
      items.foldRight[V2Value](V2Value.DataV("Nil", Vector.empty)) { (item, acc) =>
        V2Value.DataV("Cons", Vector(v1ToV2(item), acc))
      }
    case scalascript.interpreter.Value.OptionV(null) =>
      V2Value.DataV("None", Vector.empty)
    case scalascript.interpreter.Value.OptionV(inner) =>
      V2Value.DataV("Some", Vector(v1ToV2(inner)))
    case scalascript.interpreter.Value.TupleV(elems) =>
      V2Value.DataV(s"Tuple${elems.length}", elems.map(v1ToV2).toVector)
    case scalascript.interpreter.Value.MapV(entries) =>
      val hm = scala.collection.mutable.HashMap[V2Value, V2Value]()
      entries.foreach { case (k, v) => hm(v1ToV2(k)) = v1ToV2(v) }
      V2Value.ForeignV(hm)
    case orig @ scalascript.interpreter.Value.Foreign(_, s: scalascript.frontend.Signal[?]) =>
      // Signal[T]: callable (0 args → get, 1 arg → set) AND round-trip-safe —
      // a bare ClosV here DESTROYED the Foreign("ReactiveSignal") identity, so
      // natives like fetchUrlSignal(_,_,tick) failed their Foreign match after
      // one v1→v2→v1 trip. NamedMethodObj keeps the original as `underlying`
      // (v2ToV1 restores it exactly) and stays callable via applyFallback.
      V2Value.ForeignV(new ssc.Value.NamedMethodObj {
        def underlying: AnyRef = orig
        def getField(name: String): Option[V2Value] = name match
          case "apply" => Some(V2Value.ClosV(Runtime.emptyEnv, -1, env =>
            if env.isEmpty then Done(rawToV2(s.apply().asInstanceOf[Any]))
            else { s.asInstanceOf[scalascript.frontend.Signal[Any]].set(v2ToV1(env.last)); Done(V2Value.UnitV) }))
          case _ => None
      })
    case scalascript.interpreter.Value.Foreign(tag, h: AnyRef) =>
      V2Value.ForeignV(TaggedForeign(tag, h))
    // v1 NativeFnV → variadic v2 ClosV
    case nfv: scalascript.interpreter.Value.NativeFnV =>
      V2Value.ClosV(Runtime.emptyEnv, -1, env => {
        val v1Args = env.toList.map(v2ToV1)
        Done(v1ToV2(scalascript.interpreter.Computation.run(nfv.f(v1Args))))
      })
    case _ =>
      // Closures and other complex v1 values: wrap in ForeignV
      V2Value.ForeignV(v.asInstanceOf[AnyRef])
