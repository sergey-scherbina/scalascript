package scalascript.codegen

import scalascript.ast.*
import scalascript.codegen.JvmGenStringUtils.*
import scala.collection.mutable
import scala.meta.*

/** Preamble + runtime emission: HTML-DSL tag bindings, user-top-name / declared-
 *  var-type collection, runtime-source loaders, the logger/common/serve runtime
 *  string blocks, model rendering, and the UI helper functions. Lifted out of
 *  JvmGen to keep the generator navigable; self-typed because it reads/writes
 *  generator state (`declaredVarTypes`, capability flags) and reuses the loader
 *  methods + companion `JvmGen.Block`. Completes the p2 split (p2 moved only the
 *  pure runtime-source constants; these defs are state-coupled). */
private[codegen] trait JvmGenPreamble:
  self: JvmGen =>


  // HTML DSL tag names. Tags collide with top-level user `val`/`def`/`object`
  // names (Scala can't shadow within the same scope), so we filter the list
  // against `userTopNames` before emission — mirroring the JS preamble's
  // `if (globalThis[k] === undefined)` guard for the same reason.
  private val containerTagNames: List[String] = List(
    "html","head","body","title","style","script","main",
    "section","header","footer","nav","article","aside",
    "div","span","p","a","em","strong","small","code","pre",
    "h1","h2","h3","h4","h5","h6",
    "ul","ol","li","dl","dt","dd",
    "table","thead","tbody","tfoot","tr","td","th",
    "form","button","label","select","option","textarea",
    "figure","figcaption","blockquote"
  )
  private val voidTagNames: List[String] = List(
    "br","hr","img","input","link","meta"
  )

  private[codegen] def htmlDslTagBindings(userTopNames: Set[String]): String =
    val sb = StringBuilder()
    sb.append("\n// Tag value bindings (skipped where the user binds the same name)\n")
    containerTagNames.filterNot(userTopNames.contains).foreach { t =>
      sb.append(s"""val $t = _Tag("$t")\n""")
    }
    voidTagNames.filterNot(userTopNames.contains).foreach { t =>
      sb.append(s"""val $t = _Tag("$t", voidTag = true)\n""")
    }
    sb.append("\n")
    sb.toString

  /** Collect top-level identifiers defined in the user's parsed blocks
   *  (val, def, object, class, enum, trait, type, given). Local bindings
   *  inside function bodies don't reach this set — they shadow at their
   *  own scope and don't conflict with module-level tag vals. */
  private[codegen] def collectUserTopNames(blocks: List[JvmGen.Block]): Set[String] =
    val names = mutable.Set.empty[String]
    def fromStats(stats: List[Stat]): Unit = stats.foreach {
      case d: Defn.Val => d.pats.foreach { case Pat.Var(n) => names += n.value; case _ => () }
      case Defn.Var.After_4_7_2(_, pats, _, _) => pats.foreach { case Pat.Var(n) => names += n.value; case _ => () }
      case d: Defn.Def    => names += d.name.value
      case d: Defn.Object => names += d.name.value
      case d: Defn.Class  => names += d.name.value
      case d: Defn.Trait  => names += d.name.value
      case d: Defn.Enum   => names += d.name.value
      case d: Defn.Type   => names += d.name.value
      case d: Defn.Given  => names += d.name.value
      case _ => ()
    }
    blocks.foreach { block =>
      block.node.tree match
        case Source(stats)     => fromStats(stats)
        case Term.Block(stats) => fromStats(stats)
        // A fence whose tree is a single top-level definition arrives as the
        // bare Defn (no Source/Block wrapper) — without this arm a lone
        // `effect Console:` fence was invisible here, so the preamble's
        // Console println-shadow collided with the user's effect object.
        case s: Stat           => fromStats(List(s))
        case _                 => ()
    }
    names.toSet

  private[codegen] def collectDeclaredVarTypes(blocks: List[JvmGen.Block]): Unit =
    blocks.foreach { block =>
      ScalaNode.fold(block.node) { tree =>
        tree.collect {
          case Defn.Var.After_4_7_2(_, pats, Some(tpe), _) =>
            pats.foreach {
              case Pat.Var(n) => declaredVarTypes(n.value) = tpe.syntax
              case _          => ()
            }
        }
      }
    }

  /** Server runtime — REST routing + JDK HttpServer dispatcher.  Emitted only
   *  when the module calls `route(...)`.  Provides the same Request/Response
   *  shape and `Response.{html,text,json,redirect,notFound,status}` factories
   *  as the interpreter, so a single `.ssc` source runs identically through
   *  `ssc` / `ssc compile`.  `serve(port)` blocks the calling thread; the
   *  default executor is single-threaded so handler bodies see no concurrency
   *  unless the user supplies their own synchronisation. */
  /** Read a `.scala` source file from one of our runtime resource
   *  bundles and return its body with the leading `package …` line
   *  stripped.  The result is suitable for direct inlining into a
   *  top-level scala-cli script (which has no package declaration).
   *  Imports inside the file are preserved.
   *
   *  Two bundles exist:
   *    - `http-server-common-sources/scalascript/server/`
   *      (Phase 1b — pure protocol primitives + POJO HTTP model +
   *      shared dispatch loops; shared with the interpreter)
   *    - `http-server-jvm-sources/scalascript/server/jvm/`
   *      (Phase 3 — JVM-specific server lifecycle, route / WS
   *      registration, proxy, outbound clients; what used to be
   *      `serveRuntime`'s `"""|..."""` string template)
   *
   *  See the `runtimeServerCommon` / `runtimeServerJvm` settings in
   *  `build.sbt` for how the resources get packaged. */
  private def loadRuntimeSource(bundle: String, subPath: String, name: String): String =
    val path = s"/$bundle/$subPath/$name.scala"
    val stream = getClass.getResourceAsStream(path)
    if stream == null then
      throw new RuntimeException(s"runtime resource missing: $path " +
        s"— is `$bundle / copyResources` up to date?")
    val raw = try
      new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    finally stream.close()
    // Drop the leading `package …` declaration.  The generated scala-cli
    // script is top-level; mixing a package decl with top-level statements
    // would be invalid.  Leading blank line(s) after the package line are
    // also dropped for readability.  Also rewrite `private[server]` /
    // `protected[server]` (and the `[jvm]` variants used in the JVM bundle)
    // access modifiers to bare `private` / `protected` — at top level the
    // qualified form has no referent and file-local visibility is
    // sufficient since all inlined sources end up in the same compilation
    // unit.
    // Drop `// BUILD-ONLY:start … // BUILD-ONLY:end` blocks — files in
    // `runtime-server-jvm` use those to declare local stubs for symbols
    // that are defined elsewhere in the inlined output (e.g. the
    // preamble's `_show`, Part2's `_Metrics`) so the file type-checks
    // standalone in our build.  At scala-cli inline time the stubs are
    // stripped so they don't clash with the real definitions.
    val noStubs =
      val sb = new StringBuilder
      var inStub = false
      raw.linesIterator.foreach { l =>
        val trimmed = l.trim
        if trimmed.startsWith("// BUILD-ONLY:start") then inStub = true
        else if trimmed.startsWith("// BUILD-ONLY:end") then inStub = false
        else if !inStub then sb.append(l).append('\n')
      }
      sb.toString
    noStubs.linesIterator
      .dropWhile(l => l.trim.startsWith("package ") || l.trim.isEmpty)
      .map(_.replace("private[server]",    "private")
            .replace("protected[server]",  "protected")
            .replace("private[jvm]",       "private")
            .replace("protected[jvm]",     "protected"))
      .mkString("\n", "\n", "\n")

  /** Phase 1b loader — pulls files from `runtime-server-common`. */
  private def loadCommonSource(name: String): String =
    loadRuntimeSource("http-server-common-sources", "scalascript/server", name)

  /** Phase 3 loader — pulls files from `runtime-server-jvm`.  Will
   *  be used by Phase 3b–3e as the migration of `serveRuntime` content
   *  proceeds; suppress the "unused" warning until then. */
  @scala.annotation.unused
  private def loadJvmRuntimeSource(name: String): String =
    loadRuntimeSource("http-server-jvm-sources", "scalascript/server/jvm", name)

  /** v1.17.6 / Phase S1c loader — pulls SPI traits from
   *  `runtime-server-spi`.  Same shape as the common / JVM loaders
   *  above; emitted at the top of `serveRuntime` so the codegen
   *  `serve(port, tls)` can route through `HttpServerBackends.current()`
   *  exactly like the interpreter does. */
  private def loadSpiRuntimeSource(name: String): String =
    loadRuntimeSource("http-server-spi-sources", "scalascript/server/spi", name)

  /** Inline `scalascript.logging.Logger` from the `logger` module's JAR
   *  resource.  Strips the `package scalascript.logging` declaration so the
   *  class lands at the generated script's top level, where inlined
   *  runtime-server sources can reference it by the unqualified name `Logger`
   *  (their BUILD-ONLY import blocks provide the qualified name for the
   *  module build). */
  private lazy val loggerRuntime: String = JvmGenRuntimeCache.memo("loggerRuntime"):
    val path = "/logger-sources/scalascript/logging/Logger.scala"
    val stream = getClass.getResourceAsStream(path)
    if stream == null then
      throw new RuntimeException(s"logger resource missing: $path " +
        "— is `logger / copyResources` up to date?")
    val raw = try
      new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    finally stream.close()
    val header =
      "\n// ── scalascript-logger (inlined from classpath resources) ─────────────\n" +
      "// Source of truth: logger/src/main/scala/scalascript/logging/Logger.scala\n"
    val body = raw.linesIterator
      .dropWhile(l => l.trim.startsWith("package ") || l.trim.isEmpty)
      .mkString("\n", "\n", "\n")
    val withEffectMethods = body.replace(
      "object Logger:\n  def apply(name: String): Logger  = new Logger(name)",
      """object Logger:
  def info (msg: Any): Any  = _perform("Logger", "info",  msg)
  def warn (msg: Any): Any  = _perform("Logger", "warn",  msg)
  def error(msg: Any): Any  = _perform("Logger", "error", msg)
  def debug(msg: Any): Any  = _perform("Logger", "debug", msg)

  def apply(name: String): Logger  = new Logger(name)"""
    )
    header + withEffectMethods

  /** Concatenate the pure-primitive sources from runtime-server-common in
   *  a deterministic order.  Emitted as a `commonRuntime` block at the
   *  top of the generated script (before `serveRuntime`) so the inlined
   *  objects (WsFraming, Password, Jwt, …) are in scope for adapter
   *  shims inside `serveRuntime` and for user code.
   *
   *  Logger is prepended first so runtime-server sources that reference
   *  the unqualified `Logger` name find it in scope. */
  private[codegen] lazy val commonRuntime: String = JvmGenRuntimeCache.memo("commonRuntime"):
    val files = List(
      "RestValidationError", "DerCodec", "WsFraming", "Metrics",
      "RateLimit", "Password", "Totp", "Jwt", "JwtRsa",
      "SessionCookie", "SessionStore", "OAuth", "WebAuthn",
      "UploadedFile", "HttpHelpers", "Multipart", "TlsContextBuilder",
      "CorsHelpers", "HttpModel", "BasicAuth", "ResponseWriter",
      "RequestBuilder", "StreamResponseWriter", "HttpDispatchLoop",
      "StaticAssetServer", "WsHandshake", "WsReassembler",
      "WsFrameDispatch", "WsRateLimiter"
    )
    val header =
      "\n// ── runtime-server-common (inlined from classpath resources) ──────────\n" +
      "// Source of truth: runtime-server-common/src/main/scala/scalascript/server/*.scala\n"
    loggerRuntime + header + files.map(loadCommonSource).mkString("\n")

  /** Non-server scripts still need shared utility sources (Logger, JWT,
   *  Password, etc.) and the always-emitted effect runtime still references
   *  route/HTTP types in dead cluster helper definitions.  Do not export the
   *  public HTTP POJO names here: they collide with ordinary user case classes
   *  such as `case class Request(...)` in `run-jvm`.
   */
  private[codegen] lazy val commonRuntimeWithoutHttpModel: String =
    JvmGenRuntimeCache.memo("commonRuntimeWithoutHttpModel"):
      val files = List(
        "RestValidationError", "DerCodec", "WsFraming", "Metrics",
        "RateLimit", "Password", "Totp", "Jwt", "JwtRsa",
        "SessionCookie", "SessionStore", "OAuth", "WebAuthn",
        "UploadedFile", "HttpHelpers", "Multipart", "TlsContextBuilder",
        "CorsHelpers", "BasicAuth", "StaticAssetServer", "WsHandshake",
        "WsReassembler", "WsFrameDispatch", "WsRateLimiter"
      )
      val header =
        "\n// ── runtime-server-common non-server subset (inlined from classpath resources) ──────────\n" +
        "// Source of truth: runtime-server-common/src/main/scala/scalascript/server/*.scala\n"
      loggerRuntime + header + files.map(loadCommonSource).mkString("\n")

  private[codegen] val nonServerHttpModelStubs: String =
    """|
       |// ── private non-server HTTP model stubs for effect/cluster helpers ───────
       |private case class _SscRuntimeRequest(
       |    method:  String              = "",
       |    path:    String              = "",
       |    params:  Map[String, String] = Map.empty,
       |    query:   Map[String, String] = Map.empty,
       |    headers: Map[String, String] = Map.empty,
       |    body:    String              = ""
       |)
       |private case class _SscRuntimeResponse(
       |    status:  Int                 = 200,
       |    headers: Map[String, String] = Map.empty,
       |    body:    String              = "",
       |    setSession: Option[Map[String, String]] = None
       |):
       |  def withSession(payload: Map[String, String]): _SscRuntimeResponse =
       |    copy(setSession = Some(payload))
       |  def clearSession(): _SscRuntimeResponse =
       |    copy(setSession = Some(Map.empty))
       |  def withHeader(name: String, value: String): _SscRuntimeResponse =
       |    copy(headers = headers + (name -> value))
       |private case class _SscRuntimeStreamResponse(
       |    status:  Int,
       |    headers: Map[String, String],
       |    writer:  (String => Unit) => Any
       |)
       |""".stripMargin

  private[codegen] def nonServerHttpModelRefs(src: String): String =
    src
      .replaceAll("\\bStreamResponse\\b", "_SscRuntimeStreamResponse")
      .replaceAll("\\bResponse\\b", "_SscRuntimeResponse")
      .replaceAll("\\bRequest\\b", "_SscRuntimeRequest")

  /** Server-side runtime (routes, sessions, JWT, OAuth, WS, …).
   *
   *  Phase 3 (Option A from `specs/runtime-server-strategic-plan.md`)
   *  is complete: the entire content used to live in three
   *  triple-quoted string templates (Part1 / Part1b / Part2);
   *  now it's four real Scala source files in
   *  `runtime-server-jvm/src/main/scala/scalascript/server/jvm/`,
   *  type-checked at our build time and inlined into the codegen
   *  output via `loadJvmRuntimeSource`.
   *
   *  v1.17.6 / Phase S1c: the SPI traits + `HttpServerBackends`
   *  registry + `JdkServerBackend` impl are inlined at the top so
   *  `serve(port, tls)` resolves through `HttpServerBackends.current()`
   *  instead of constructing its own accept loop.  Wire-equivalent
   *  to the interpreter's S1b flow (`WebServer.start` →
   *  `HttpServerBackends.current().start(port, tls, handler)`). */
  // Stub definitions for symbols referenced by the always-included actor and Http-effect
  // runtimes (commonRuntime, effectsRuntime) when no HTTP server is needed.
  // Without these, scripts that don't use serve/route/websocket still fail to compile
  // because the actor cluster and Http-effect handler reference _routes/route/onWebSocket.
  // Stub definitions for symbols referenced by the always-included actor and Http-effect
  // runtimes (commonRuntime, effectsRuntime) when no HTTP server is needed.
  // Request/Response/StreamResponse are already defined in commonRuntime; only the
  // server dispatch functions (_routes, route, onWebSocket, _httpDoRequest) need stubs.
  private[codegen] val serveRuntime: String = JvmGenRuntimeCache.memo("serveRuntime"):
    val spiHeader =
      "\n// ── runtime-server-spi (inlined from classpath resources) ────────────\n" +
      "// Source of truth: runtime-server-spi/src/main/scala/scalascript/server/spi/*.scala\n"
    val jvmHeader =
      "\n// ── runtime-server-jvm (inlined from classpath resources) ─────────────\n" +
      "// Source of truth: runtime-server-jvm/src/main/scala/scalascript/server/jvm/*.scala\n"
    spiHeader + loadSpiRuntimeSource("HttpServerSpi") +
                loadSpiRuntimeSource("HttpServerBackends") +
    jvmHeader + loadJvmRuntimeSource("RestRuntime") +
                loadJvmRuntimeSource("WebSocketRuntime") +
                loadJvmRuntimeSource("JdkServerBackend") +
                loadJvmRuntimeSource("ProxyRuntime") +
                loadJvmRuntimeSource("OutboundClients")

  /** Top-level helper functions for frontend modules.  Prepended to the user
   *  section so they're defined BEFORE the `import std.ui.primitives.{serve,...}`
   *  line — this ensures `serve(port)` inside `_ssc_ui_serve` resolves to the
   *  preamble's `serve(port: Int, ...)` rather than the opaque-typed wrapper. */
  private def renderModelFieldType(t: scalascript.ast.ModelFieldType): String =
    import scalascript.ast.ModelFieldType.*
    t match
      case Str          => "scalascript.ast.ModelFieldType.Str"
      case IntF         => "scalascript.ast.ModelFieldType.IntF"
      case DblF         => "scalascript.ast.ModelFieldType.DblF"
      case BoolF        => "scalascript.ast.ModelFieldType.BoolF"
      case Nested(n)    => s"""scalascript.ast.ModelFieldType.Nested(${scalaStringLiteral(n)})"""
      case ListOf(i)    => s"scalascript.ast.ModelFieldType.ListOf(${renderModelFieldType(i)})"
      case Optional(i)  => s"scalascript.ast.ModelFieldType.Optional(${renderModelFieldType(i)})"

  private def renderModelDef(m: scalascript.ast.ModelDef): String =
    val fields = m.fields.map { f =>
      s"""scalascript.ast.ModelField(${scalaStringLiteral(f.name)}, ${renderModelFieldType(f.tpe)})"""
    }.mkString(", ")
    s"""scalascript.ast.ModelDef(${scalaStringLiteral(m.name)}, List($fields))"""

  private[codegen] def uiHelperFunctions(
    frontendName: String,
    appIcon:      Option[String],
    bundleId:     Option[String],
    displayName:  Option[String],
    version:      Option[String],
    models:       List[scalascript.ast.ModelDef]
  ): String =
    val bundleIdLit    = escapeStringLit(bundleId.getOrElse("com.example.app"))
    val displayNameLit = escapeStringLit(displayName.getOrElse("ScalaScript App"))
    val versionLit     = escapeStringLit(version.getOrElse("1.0.0"))
    val modelsLit      = if models.isEmpty then "Nil"
                         else s"List(${models.map(renderModelDef).mkString(", ")})"
    s"""|
       |// ── UI helpers injected by JvmGen for frontend-framework modules ──────────
       |{
       |  val _fe_prop = System.getProperty("scalascript.frontend")
       |  val _fe_name = if _fe_prop != null && _fe_prop.nonEmpty then _fe_prop else "$frontendName"
       |  scalascript.frontend.FrontendFrameworks.setBackend(_fe_name)
       |  _ssc_frontend_name = _fe_name
       |}
       |def _ssc_ui_decodeAttrs(m: Map[String, Any]): Map[String, scalascript.frontend.AttrValue] =
       |  m.collect {
       |    case (k, v: String)  => k -> scalascript.frontend.AttrValue.Str(v)
       |    case (k, v: Boolean) => k -> scalascript.frontend.AttrValue.Bool(v)
       |    case (k, v: Int)     => k -> scalascript.frontend.AttrValue.Num(v.toDouble)
       |    case (k, v: Long)    => k -> scalascript.frontend.AttrValue.Num(v.toDouble)
       |    case (k, v: Double)  => k -> scalascript.frontend.AttrValue.Num(v)
       |    case (k, v: scalascript.frontend.ReactiveSignal[?]) =>
       |      k -> scalascript.frontend.AttrValue.Reactive(v)
       |  }
       |
       |def _ssc_ui_decodeEvents(m: Map[String, Any]): Map[String, scalascript.frontend.EventHandler] =
       |  m.collect { case (k, v: scalascript.frontend.EventHandler) => k -> v }
       |
       |def _ssc_ui_buildModule(view: scalascript.frontend.View[?], extraCss: String = ""): scalascript.frontend.FrontendModule =
       |  scalascript.frontend.FrontendModule(
       |    List(scalascript.frontend.ComponentDef("App", Nil, _ =>
       |      scalascript.frontend.View.Element("div",
       |        Map("id" -> scalascript.frontend.AttrValue.Str("ui-app")),
       |        Map.empty, Seq(view)))),
       |    "App", "/", extraCss)
       |
       |def _ssc_ui_build_native_module(view: scalascript.frontend.View[?], platform: scalascript.frontend.Platform): scalascript.frontend.FrontendModule =
       |  val _manifest = scalascript.frontend.AppManifest(
       |    bundleId    = "$bundleIdLit",
       |    displayName = "$displayNameLit",
       |    version     = "$versionLit"
       |  )
       |  scalascript.frontend.FrontendModule(
       |    List(scalascript.frontend.ComponentDef("App", Nil, _ => view)),
       |    "App", "/", "", platform, Some(_manifest), $modelsLit)
       |
       |def _ssc_ui_emit_to_dir(view: scalascript.frontend.View[?], dir: String, extraCss: String = ""): Unit =
       |  val _mod     = _ssc_ui_buildModule(view, extraCss)
       |  val _emitted = scalascript.frontend.FrontendFrameworks.current().emit(_mod)
       |  val _p = java.nio.file.Paths.get(dir)
       |  java.nio.file.Files.createDirectories(_p)
       |  java.nio.file.Files.writeString(_p.resolve("index.html"), _emitted.html)
       |  val _appJs = if _ssc_client_sql_js.nonEmpty then _emitted.js + "\\n" + _ssc_client_sql_js
       |               else _emitted.js
       |  java.nio.file.Files.writeString(_p.resolve("app.js"), _appJs)
       |  if _emitted.css.nonEmpty then
       |    java.nio.file.Files.writeString(_p.resolve("app.css"), _emitted.css)
       |
       |def _ssc_ui_emit_to_tempdir(view: scalascript.frontend.View[?], extraCss: String = ""): String =
       |  val _mod     = _ssc_ui_buildModule(view, extraCss)
       |  val _emitted = scalascript.frontend.FrontendFrameworks.current().emit(_mod)
       |  val _tmpDir  = java.nio.file.Files.createTempDirectory("ssc-ui")
       |  java.nio.file.Files.writeString(_tmpDir.resolve("index.html"), _emitted.html)
       |  val _appJs = if _ssc_client_sql_js.nonEmpty then _emitted.js + "\\n" + _ssc_client_sql_js
       |               else _emitted.js
       |  java.nio.file.Files.writeString(_tmpDir.resolve("app.js"), _appJs)
       |  if _emitted.css.nonEmpty then
       |    java.nio.file.Files.writeString(_tmpDir.resolve("app.css"), _emitted.css)
       |  _tmpDir.toString
       |
       |def _ssc_ui_emit_native_to_dir(view: scalascript.frontend.View[?], dir: String, extraCss: String = ""): Unit =
       |  val _mod = _ssc_ui_buildModule(view, extraCss)
       |  val _artifact = scalascript.frontend.FrontendFrameworks.current()
       |    .emitNative(_mod, scalascript.frontend.Platform.Desktop())
       |    .getOrElse(throw IllegalStateException("selected frontend does not emit a JVM desktop native app"))
       |  val _p = java.nio.file.Paths.get(dir)
       |  java.nio.file.Files.createDirectories(_p)
       |  for ((_name, _source) <- _artifact.sources) do
       |    val _target = _p.resolve(_name)
       |    val _parent = _target.getParent
       |    if _parent != null then java.nio.file.Files.createDirectories(_parent)
       |    java.nio.file.Files.writeString(_target, _source)
       |  for ((_name, _bytes) <- _artifact.resources) do
       |    val _target = _p.resolve(_name)
       |    val _parent = _target.getParent
       |    if _parent != null then java.nio.file.Files.createDirectories(_parent)
       |    java.nio.file.Files.write(_target, _bytes)
       |
       |def _ssc_ui_emit_native_platform_to_dir(view: scalascript.frontend.View[?], dir: String, platform: scalascript.frontend.Platform): Unit =
       |  val _mod = _ssc_ui_build_native_module(view, platform)
       |  val _artifact = scalascript.frontend.FrontendFrameworks.current()
       |    .emitNative(_mod, platform)
       |    .getOrElse(throw IllegalStateException(s"selected frontend does not emit a native app for $$platform"))
       |  val _p = java.nio.file.Paths.get(dir)
       |  java.nio.file.Files.createDirectories(_p)
       |  for ((_name, _source) <- _artifact.sources) do
       |    val _target = _p.resolve(_name)
       |    val _parent = _target.getParent
       |    if _parent != null then java.nio.file.Files.createDirectories(_parent)
       |    java.nio.file.Files.writeString(_target, _source)
       |  for ((_name, _bytes) <- _artifact.resources) do
       |    val _target = _p.resolve(_name)
       |    val _parent = _target.getParent
       |    if _parent != null then java.nio.file.Files.createDirectories(_parent)
       |    java.nio.file.Files.write(_target, _bytes)
       |
       |private def _ssc_ui_utf8(value: String): Array[Byte] =
       |  Option(value).getOrElse("").getBytes(java.nio.charset.StandardCharsets.UTF_8)
       |
       |""".stripMargin +
    (if frontendName != "swiftui" then
      s"""|def _ssc_ui_backend_response(value: Any): scalascript.backend.spi.BackendResponse =
         |  value match
         |    case r: Response =>
         |      scalascript.backend.spi.BackendResponse(
         |        r.status,
         |        r.headers,
         |        _ssc_ui_utf8(r.body)
         |      )
         |    case sr: _StreamResponse =>
         |      val sb = StringBuilder()
         |      sr.writer(chunk => sb.append(chunk))
         |      scalascript.backend.spi.BackendResponse(
         |        sr.status,
         |        sr.headers,
         |        _ssc_ui_utf8(sb.toString)
         |      )
         |    case other =>
         |      scalascript.backend.spi.BackendResponse(
         |        200,
         |        Map("Content-Type" -> "text/plain; charset=utf-8"),
         |        _ssc_ui_utf8(_show(other))
         |      )
         |
         |private val _ssc_ui_backend_transport: scalascript.backend.spi.BackendTransport =
         |  new scalascript.backend.spi.BackendTransport:
         |    def request(req0: scalascript.backend.spi.BackendRequest): scala.concurrent.Future[scalascript.backend.spi.BackendResponse] =
         |      val method = req0.method.toUpperCase
         |      val queryIdx = req0.path.indexOf('?')
         |      val path = if queryIdx >= 0 then req0.path.take(queryIdx) else req0.path
         |      val query = if queryIdx >= 0 then _parseQuery(req0.path.drop(queryIdx + 1)) else Map.empty[String, String]
         |      val segs = path.split('/').toList.filter(_.nonEmpty)
         |      val body = String(req0.body, java.nio.charset.StandardCharsets.UTF_8)
         |      val loweredHeaders = req0.headers.map((k, v) => k.toLowerCase -> v)
         |      val response =
         |        _routes.iterator
         |          .filter(_.method == method)
         |          .flatMap(r => _matchPath(r.pattern, segs).map(params => (r, params)))
         |          .nextOption() match
         |            case Some((r, params)) =>
         |              val req = Request(method, path, params, query, loweredHeaders, body)
         |              try
         |                def runHandler(): Any = r.handler(req)
         |                val chain = _middlewares.reverseIterator.foldLeft(() => runHandler()) { (next, mw) =>
         |                  () => mw(req, next)
         |                }
         |                _ssc_ui_backend_response(chain())
         |              catch
         |                case e: RestValidationError =>
         |                  scalascript.backend.spi.BackendResponse(
         |                    400,
         |                    Map("Content-Type" -> "text/plain; charset=utf-8"),
         |                    _ssc_ui_utf8(e.getMessage)
         |                  )
         |                case e: Throwable =>
         |                  scalascript.backend.spi.BackendResponse(
         |                    500,
         |                    Map("Content-Type" -> "text/plain; charset=utf-8"),
         |                    _ssc_ui_utf8(e.getMessage)
         |                  )
         |            case None =>
         |              scalascript.backend.spi.BackendResponse(
         |                404,
         |                Map("Content-Type" -> "text/plain; charset=utf-8"),
         |                _ssc_ui_utf8("Not Found: " + path)
         |              )
         |      scala.concurrent.Future.successful(response)
         |
         |def _ssc_ui_backend_request(method: String, url: String, body: String): scalascript.backend.spi.BackendResponse =
         |  scala.concurrent.Await.result(
         |    _ssc_ui_backend_transport.request(
         |      scalascript.backend.spi.BackendRequest(
         |        method,
         |        url,
         |        Map("Content-Type" -> "application/json"),
         |        _ssc_ui_utf8(body)
         |      )
         |    ),
         |    scala.concurrent.duration.Duration.Inf
         |  )
         |
         |""".stripMargin
    else "") +
    (if frontendName != "swiftui" then
      s"""|def _ssc_ui_inprocess_fetch(methodRaw: String, url: String, body: String): scalascript.frontend.swing.SwingRuntime.FetchResponse =
         |  val response = _ssc_ui_backend_request(methodRaw, url, body)
         |  scalascript.frontend.swing.SwingRuntime.FetchResponse(
         |    response.status,
         |    String(response.body, java.nio.charset.StandardCharsets.UTF_8),
         |    response.headers
         |  )
         |
         |def _ssc_ui_inprocess_fetch_javafx(methodRaw: String, url: String, body: String): scalascript.frontend.javafx.JavaFxRuntime.FetchResponse =
         |  val response = _ssc_ui_backend_request(methodRaw, url, body)
         |  scalascript.frontend.javafx.JavaFxRuntime.FetchResponse(
         |    response.status,
         |    String(response.body, java.nio.charset.StandardCharsets.UTF_8),
         |    response.headers
         |  )
         |
         |def _ssc_ui_run_native(view: scalascript.frontend.View[?], extraCss: String = ""): Unit =
         |  val _mod = _ssc_ui_buildModule(view, extraCss)
         |  println("ssc: launching Swing")
         |  println("     mode:   same-process JVM")
         |  scalascript.frontend.swing.SwingRuntime.run(
         |    _mod,
         |    scalascript.frontend.swing.SwingRuntime.Options(
         |      fetchDispatcher = Some(new scalascript.frontend.swing.SwingRuntime.FetchDispatcher:
         |        def request(method: String, url: String, body: String): scalascript.frontend.swing.SwingRuntime.FetchResponse =
         |          _ssc_ui_inprocess_fetch(method, url, body)
         |      )${appIcon.map(p => s""",\n      iconPath = Some("${escapeStringLit(p)}")""").getOrElse("")}
         |    )
         |  )
         |
         |def _ssc_ui_run_native_javafx(view: scalascript.frontend.View[?], extraCss: String = ""): Unit =
         |  val _mod = _ssc_ui_buildModule(view, extraCss)
         |  println("ssc: launching JavaFX")
         |  println("     mode:   same-process JVM")
         |  scalascript.frontend.javafx.JavaFxRuntime.run(
         |    _mod,
         |    scalascript.frontend.javafx.JavaFxRuntime.Options(
         |      fetchDispatcher = Some(new scalascript.frontend.javafx.JavaFxRuntime.FetchDispatcher:
         |        def request(method: String, url: String, body: String): scalascript.frontend.javafx.JavaFxRuntime.FetchResponse =
         |          _ssc_ui_inprocess_fetch_javafx(method, url, body)
         |      )
         |    )
         |  )
         |
         |def _ssc_ui_serve(tree: Any, port: Int, extraCss: String = ""): Unit =
         |  if _ssc_frontend_name == "swing" then
         |    _ssc_ui_run_native(tree.asInstanceOf[scalascript.frontend.View[?]], extraCss)
         |  else if _ssc_frontend_name == "javafx" then
         |    _ssc_ui_run_native_javafx(tree.asInstanceOf[scalascript.frontend.View[?]], extraCss)
         |  else
         |    val _outDir = _ssc_ui_emit_to_tempdir(tree.asInstanceOf[scalascript.frontend.View[?]], extraCss)
         |    _ssc_static_root = _outDir
         |    serve(port)
         |
         |// ── Overloads to shadow preamble names that conflict with UI widget imports ──
         |// serve(view, port[, extraCss]): beats preamble serve(Int) / serve(Int,String) / serve(Int,TlsConfig)
         |def serve(tree: Any, port: Int): Unit = _ssc_ui_serve(tree, port)
         |def serve(tree: Any, port: Int, extraCss: String): Unit = _ssc_ui_serve(tree, port, extraCss)
         |// text(String): beats extension (r: Response.type) def text(body: Any)
         |def text(content: String) = std.ui.typography.text(content)
         |
         |""".stripMargin
    else
      s"""|def _ssc_ui_serve(tree: Any, port: Int, extraCss: String = ""): Unit =
         |  val _outDir = Option(System.getProperty("ssc.build.outdir"))
         |    .getOrElse { System.err.println("swiftui: ssc.build.outdir system property not set"); System.exit(1); "" }
         |  val _platformStr = Option(System.getProperty("ssc.build.platform")).getOrElse("ios")
         |  val _platform: scalascript.frontend.Platform = _platformStr match
         |    case "macos" => scalascript.frontend.Platform.Desktop(scalascript.frontend.DesktopOs.MacOS)
         |    case _       => scalascript.frontend.Platform.Mobile(scalascript.frontend.MobileOs.iOS)
         |  _ssc_ui_emit_native_platform_to_dir(tree.asInstanceOf[scalascript.frontend.View[?]], _outDir, _platform)
         |
         |def serve(tree: Any, port: Int): Unit = _ssc_ui_serve(tree, port)
         |def serve(tree: Any, port: Int, extraCss: String): Unit = _ssc_ui_serve(tree, port, extraCss)
         |// text(String): beats extension (r: Response.type) def text(body: Any) — same
         |// shadow-fix as the non-swiftui branch above; real std/ui-based programs (the
         |// only ones reachable via --target macos|ios) call text(...) too.
         |def text(content: String) = std.ui.typography.text(content)
         |
         |// Bring View extension methods (foreground, background, fontWeight, etc.) into scope
         |import scalascript.frontend.{foreground, background, fontWeight, fontSize, cornerRadius, opacity, padding}
         |
         |// ── SwiftUI reactive Signal (frontend.ReactiveSignal-backed) ──────────────
         |private var _ssc_sig_seq: Long = 0L
         |private def _ssc_fresh_id(): String = { _ssc_sig_seq += 1; s"_sig$${_ssc_sig_seq}" }
         |
         |type Signal[A] = scalascript.frontend.ReactiveSignal[A]
         |object Signal:
         |  def apply[A](initial: A): scalascript.frontend.ReactiveSignal[A] =
         |    new scalascript.frontend.ReactiveSignal[A](_ssc_fresh_id(), initial)
         |
         |extension [A](s: scalascript.frontend.ReactiveSignal[A])
         |  def +=(v: A)(using n: Numeric[A]): Unit = s.update(x => n.plus(x, v))
         |  def -=(v: A)(using n: Numeric[A]): Unit = s.update(x => n.minus(x, v))
         |
         |extension (s: scalascript.frontend.ReactiveSignal[String])
         |  def nonEmpty: scalascript.frontend.ReactiveSignal[Boolean] =
         |    new scalascript.frontend.ReactiveSignal[Boolean](s.id + "__nonempty", s().nonEmpty)
         |  def isEmpty: scalascript.frontend.ReactiveSignal[Boolean] =
         |    new scalascript.frontend.ReactiveSignal[Boolean](s.id + "__empty", s().isEmpty)
         |
         |extension (s: scalascript.frontend.FetchUrlSignal)
         |  def isLoading: scalascript.frontend.ReactiveSignal[Boolean] =
         |    new scalascript.frontend.ReactiveSignal[Boolean](s.id + "_loading", false)
         |  def isLoaded: scalascript.frontend.ReactiveSignal[Boolean] =
         |    new scalascript.frontend.ReactiveSignal[Boolean](s.id + "_loaded", false)
         |  def isError: scalascript.frontend.ReactiveSignal[Boolean] =
         |    new scalascript.frontend.ReactiveSignal[Boolean](s.id + "_error", false)
         |
         |// ── FetchJsonSignal[T] typed helper ───────────────────────────────────────
         |def FetchJsonSignal[T: scala.reflect.ClassTag](
         |  id: String, url: String, tick: Any, headers: Any = null
         |): scalascript.frontend.FetchJsonSignal =
         |  val modelName = scala.reflect.classTag[T].runtimeClass.getSimpleName
         |  val tickId    = tick.asInstanceOf[scalascript.frontend.ReactiveSignal[?]].id
         |  val headersId = Option(headers).map(_.asInstanceOf[scalascript.frontend.ReactiveSignal[?]].id)
         |  new scalascript.frontend.FetchJsonSignal(id, url, tickId, modelName, headersId)
         |
         |// ── Font / alignment constants ────────────────────────────────────────────
         |val Bold     = scalascript.frontend.FontWeight.Bold
         |val SemiBold = scalascript.frontend.FontWeight.SemiBold
         |val Regular  = scalascript.frontend.FontWeight.Regular
         |val Light    = scalascript.frontend.FontWeight.Light
         |val Start    = scalascript.frontend.HAlign.Start
         |val Center   = scalascript.frontend.HAlign.Center
         |val End      = scalascript.frontend.HAlign.End
         |val Top      = scalascript.frontend.VAlign.Top
         |val Bottom   = scalascript.frontend.VAlign.Bottom
         |val Stretch  = scalascript.frontend.HAlign.Stretch
         |
         |// ── Color alias so user code can write Color.blue, Color.secondary, etc. ─
         |val Color = scalascript.frontend.Color
         |
         |// ── Color companion helpers ───────────────────────────────────────────────
         |extension (c: scalascript.frontend.Color.type)
         |  def blue:      scalascript.frontend.Color = scalascript.frontend.Color.Named("blue")
         |  def red:       scalascript.frontend.Color = scalascript.frontend.Color.Named("red")
         |  def green:     scalascript.frontend.Color = scalascript.frontend.Color.Named("green")
         |  def orange:    scalascript.frontend.Color = scalascript.frontend.Color.Named("orange")
         |  def gray:      scalascript.frontend.Color = scalascript.frontend.Color.Named("gray")
         |  def purple:    scalascript.frontend.Color = scalascript.frontend.Color.Named("purple")
         |  def yellow:    scalascript.frontend.Color = scalascript.frontend.Color.Named("yellow")
         |  def white:     scalascript.frontend.Color = scalascript.frontend.Color.Named("white")
         |  def black:     scalascript.frontend.Color = scalascript.frontend.Color.Named("black")
         |  def secondary: scalascript.frontend.Color = scalascript.frontend.Color.System("secondary")
         |  def primary:   scalascript.frontend.Color = scalascript.frontend.Color.System("primary")
         |  def accent:    scalascript.frontend.Color = scalascript.frontend.Color.System("accent")
         |  def systemGray6: scalascript.frontend.Color = scalascript.frontend.Color.System("systemGray6")
         |
         |// ── View style / padding extensions (named-arg overloads) ─────────────────
         |extension (v: scalascript.frontend.View[?])
         |  def style(
         |    foreground:  scalascript.frontend.Color    = null,
         |    background:  scalascript.frontend.Color    = null,
         |    fontWeight:  scalascript.frontend.FontWeight = null,
         |    fontSize:    Double = -1,
         |    borderRadius: Double = -1,
         |    opacity:     Double = -1
         |  ): scalascript.frontend.View[?] =
         |    var r: scalascript.frontend.View[?] = v
         |    if foreground  != null then r = r.foreground(foreground)
         |    if background  != null then r = r.background(background)
         |    if fontWeight  != null then r = r.fontWeight(fontWeight)
         |    if fontSize    >= 0    then r = r.fontSize(fontSize)
         |    if borderRadius >= 0   then r = r.cornerRadius(borderRadius)
         |    if opacity     >= 0    then r = r.opacity(opacity)
         |    r
         |  def padding(e: scalascript.frontend.EdgeInsets): scalascript.frontend.View[?] =
         |    v match
         |      case scalascript.frontend.View.Styled(_c, _s) =>
         |        scalascript.frontend.View.Styled(_c, _s.copy(layout = _s.layout.copy(padding = e)))
         |      case _other =>
         |        scalascript.frontend.View.Styled(_other, scalascript.frontend.Style(layout = scalascript.frontend.LayoutStyle(padding = e)))
         |  def padding(
         |    horizontal: Double = -1,
         |    vertical:   Double = -1,
         |    top:        Double = -1,
         |    bottom:     Double = -1,
         |    left:       Double = -1,
         |    right:      Double = -1
         |  ): scalascript.frontend.View[?] =
         |    if horizontal >= 0 || vertical >= 0 || top >= 0 || bottom >= 0 || left >= 0 || right >= 0 then
         |      val h = if horizontal >= 0 then horizontal else 0.0
         |      val vc = if vertical   >= 0 then vertical  else 0.0
         |      val t = if top    >= 0 then top    else vc
         |      val b = if bottom >= 0 then bottom else vc
         |      val l = if left   >= 0 then left   else h
         |      val r = if right  >= 0 then right  else h
         |      v.padding(scalascript.frontend.EdgeInsets(t, r, b, l))
         |    else v
         |  def tabItem(label: String, icon: String = "", tag: Int = 0): scalascript.frontend.View[?] =
         |    _ssc_tab_items += ((v, label, if icon.isEmpty then None else Some(icon), tag))
         |    v
         |
         |// ── DataTable / fetchUrlSignal primitives ────────────────────────────────────
         |def seedSignal(name: String, source: Any): Any =
         |  new scalascript.frontend.SeedSignal(
         |    name, source.asInstanceOf[scalascript.frontend.ReactiveSignal[String]])
         |
         |def fetchUrlSignal(name: String, url: String, refreshTick: Any, headers: Any = null): Any =
         |  val _tick = refreshTick.asInstanceOf[scalascript.frontend.ReactiveSignal[?]]
         |  val _hOpt = Option(headers)
         |    .map(_.asInstanceOf[scalascript.frontend.ReactiveSignal[String]])
         |    .filter(_.id != "__ssc_empty_headers").map(_.id)
         |  new scalascript.frontend.FetchUrlSignal(name, url, _tick.id, _hOpt)
         |
         |def fetchStreamSignal(name: String, url: String, body: Any, tick: Any, headers: Any = null): Any =
         |  val _body = body.asInstanceOf[scalascript.frontend.ReactiveSignal[?]]
         |  val _tick = tick.asInstanceOf[scalascript.frontend.ReactiveSignal[?]]
         |  val _hOpt = Option(headers)
         |    .map(_.asInstanceOf[scalascript.frontend.ReactiveSignal[String]])
         |    .filter(_.id != "__ssc_empty_headers").map(_.id)
         |  new scalascript.frontend.FetchStreamSignal(name, url, _body.id, _tick.id, _hOpt)
         |
         |def intervalTick(name: String, ms: Int): Any =
         |  new scalascript.frontend.IntervalTick(name, ms)
         |
         |// fetchRowsSource(sig, rowsPath): Scope B.3 — a managed-fetch Remote source whose
         |// optional dotted envelope path (e.g. "result.items") is carried on the model so the
         |// emitted client fetch JS drills it (StaticJsEmitter __ssc_rowsOf), matching the browser.
         |def fetchRowsSource(sig: Any, rowsPath: Any = ""): Any =
         |  scalascript.frontend.TableDataSource.Remote(
         |    sig.asInstanceOf[scalascript.frontend.FetchUrlSignal],
         |    Option(rowsPath).map(_.toString).filter(_ != "null").getOrElse(""))
         |
         |def emptyHeaders: Any = new scalascript.frontend.ReactiveSignal[String]("__ssc_empty_headers", "")
         |
         |def fieldColumn(title: String, fieldPath: String, align: String = ""): Any =
         |  scalascript.frontend.FieldColumnDef(title, fieldPath, Option(align).filter(_.nonEmpty))
         |
         |private def _ssc_dottedRowName(name: String, operation: String): String =
         |  if name.nonEmpty && name.split("\\\\.", -1).forall(_.nonEmpty) then name
         |  else throw IllegalArgumentException(s"$$operation requires a non-empty dotted field path")
         |
         |private def _ssc_exactRowPayload(payload: Any, operation: String): scalascript.frontend.RowPayload =
         |  val candidate = payload match
         |    case name: String => scalascript.frontend.RowPayload.Field(name)
         |    case value: scalascript.frontend.RowPayload => value
         |    case _ => throw IllegalArgumentException(s"$$operation payload must be String or RowPayload")
         |  candidate match
         |    case scalascript.frontend.RowPayload.Field(name) => scalascript.frontend.RowPayload.Field(_ssc_dottedRowName(name, operation))
         |    case scalascript.frontend.RowPayload.WholeRow => scalascript.frontend.RowPayload.WholeRow
         |    case scalascript.frontend.RowPayload.Fields(names)
         |        if names.nonEmpty && names.distinct.size == names.size && names.forall(n => n.nonEmpty && n.split("\\\\.", -1).forall(_.nonEmpty)) =>
         |      scalascript.frontend.RowPayload.Fields(names)
         |    case scalascript.frontend.RowPayload.Fields(_) =>
         |      throw IllegalArgumentException(s"$$operation fields must be unique non-empty dotted field paths")
         |
         |def rowDeleteAction(url: String, idField: String, tick: Any, headers: Any = null): Any =
         |  scalascript.frontend.RowActionDef.RowDelete(url, _ssc_dottedRowName(idField, "rowDeleteAction"),
         |    tick.asInstanceOf[scalascript.frontend.ReactiveSignal[Int]],
         |    Option(headers).map(_.asInstanceOf[scalascript.frontend.ReactiveSignal[String]]))
         |
         |def fieldPayload(name: String): Any = _ssc_exactRowPayload(name, "fieldPayload")
         |def wholeRowPayload(): Any = scalascript.frontend.RowPayload.WholeRow
         |def fieldsPayload(names: List[String]): Any = _ssc_exactRowPayload(scalascript.frontend.RowPayload.Fields(names), "fieldsPayload")
         |
         |def rowPostAction(label: String, method: String, url: String, payload: Any,
         |                  tick: Any, headers: Any = null): Any =
         |  val _payload = _ssc_exactRowPayload(payload, "rowPostAction")
         |  scalascript.frontend.RowActionDef.RowPost(label, method, url, _payload,
         |    tick.asInstanceOf[scalascript.frontend.ReactiveSignal[Int]],
         |    Option(headers).map(_.asInstanceOf[scalascript.frontend.ReactiveSignal[String]]))
         |
         |def rowLinkAction(label: String, signal: Any, fieldPath: String): Any =
         |  scalascript.frontend.RowActionDef.RowLink(label,
         |    signal.asInstanceOf[scalascript.frontend.ReactiveSignal[String]], _ssc_dottedRowName(fieldPath, "rowLinkAction"))
         |
         |// dataTableView itself is NOT redeclared here (was a byte-for-byte duplicate of
         |// JvmRuntimeUiPrimitives.scala's version, made ambiguous by the ui.primitives.{...}
         |// hoist that's always active alongside this preamble — "Reference to dataTableView
         |// is ambiguous"). dataTable below resolves it via the hoisted import instead.
         |def fcol(title: String, fieldPath: String, align: String = ""): Any = fieldColumn(title, fieldPath, align)
         |def rowDelete(url: String, idField: String, tick: Any, headers: Any = null): Any = rowDeleteAction(url, idField, tick, headers)
         |def rowPost(label: String, method: String, url: String, payload: Any, tick: Any, headers: Any = null): Any = rowPostAction(label, method, url, payload, tick, headers)
         |def rowLink(label: String, signal: Any, fieldPath: String): Any = rowLinkAction(label, signal, fieldPath)
         |def dataTable(signal: Any, columns: Any, actions: Any = List(), rowKeyPath: String = "id"): Any = std.ui.primitives.dataTableView(signal, columns, actions, rowKeyPath)
         |
         |// ── Widget children stack (for multi-statement DSL blocks) ─────────────────
         |private val _ssc_wstack = java.util.ArrayDeque[scala.collection.mutable.ArrayBuffer[scalascript.frontend.View[?]]]()
         |private val _ssc_tab_items = scala.collection.mutable.ArrayBuffer.empty[(scalascript.frontend.View[?], String, Option[String], Int)]
         |
         |private def _ssc_pcollect(block: => Any): List[scalascript.frontend.View[?]] =
         |  val buf = scala.collection.mutable.ArrayBuffer.empty[scalascript.frontend.View[?]]
         |  _ssc_wstack.push(buf)
         |  block
         |  _ssc_wstack.pop()
         |  buf.toList
         |
         |private def _ssc_push[V <: scalascript.frontend.View[?]](v: V): V =
         |  val peek = _ssc_wstack.peek()
         |  if peek != null then peek += v
         |  v
         |
         |// ── Layout widgets ────────────────────────────────────────────────────────
         |def Column(
         |  spacing: Double = 0,
         |  align: scalascript.frontend.HAlign = scalascript.frontend.HAlign.Start,
         |  style: scalascript.frontend.Style = scalascript.frontend.Style()
         |)(block: => Any): scalascript.frontend.View[?] =
         |  _ssc_push(scalascript.frontend.View.Column(_ssc_pcollect(block), spacing, align, style))
         |
         |def Row(
         |  spacing: Double = 0,
         |  align: scalascript.frontend.VAlign = scalascript.frontend.VAlign.Center,
         |  style: scalascript.frontend.Style = scalascript.frontend.Style()
         |)(block: => Any): scalascript.frontend.View[?] =
         |  _ssc_push(scalascript.frontend.View.Row(_ssc_pcollect(block), spacing, align, style))
         |
         |def ScrollView(
         |  axis: scalascript.frontend.Axis = scalascript.frontend.Axis.Vertical,
         |  style: scalascript.frontend.Style = scalascript.frontend.Style()
         |)(block: => Any): scalascript.frontend.View[?] =
         |  val ch = _ssc_pcollect(block)
         |  val child = if ch.length == 1 then ch.head else scalascript.frontend.View.Fragment(ch)
         |  _ssc_push(scalascript.frontend.View.ScrollView(child, axis, style))
         |
         |// ── Text ─────────────────────────────────────────────────────────────────
         |def Text(content: => String, style: scalascript.frontend.Style = scalascript.frontend.Style()): scalascript.frontend.View[?] =
         |  _ssc_push(scalascript.frontend.View.Text(() => content, style))
         |
         |// ── Spacer / Divider ──────────────────────────────────────────────────────
         |def Spacer(size: Option[Double] = None): scalascript.frontend.View[?] =
         |  _ssc_push(scalascript.frontend.View.Spacer(size))
         |
         |def Divider(axis: scalascript.frontend.Axis = scalascript.frontend.Axis.Horizontal, style: scalascript.frontend.Style = scalascript.frontend.Style()): scalascript.frontend.View[?] =
         |  _ssc_push(scalascript.frontend.View.Divider(axis, style))
         |
         |// ── Interactive widgets ───────────────────────────────────────────────────
         |def Button(label: String)(action: => Unit): scalascript.frontend.View[?] =
         |  _ssc_push(scalascript.frontend.View.Button(
         |    scalascript.frontend.View.Text(() => label, scalascript.frontend.Style()),
         |    scalascript.frontend.EventHandler.Simple(() => action)
         |  ))
         |
         |def TextInput(value: Any, placeholder: String = "", multiline: Boolean = false, secure: Boolean = false, style: scalascript.frontend.Style = scalascript.frontend.Style()): scalascript.frontend.View[?] =
         |  _ssc_push(scalascript.frontend.View.TextInput(
         |    value.asInstanceOf[scalascript.frontend.ReactiveSignal[String]],
         |    placeholder, multiline, secure, style
         |  ))
         |
         |def Toggle(checked: Any, label: String = "", style: scalascript.frontend.Style = scalascript.frontend.Style()): scalascript.frontend.View[?] =
         |  _ssc_push(scalascript.frontend.View.Toggle(
         |    checked.asInstanceOf[scalascript.frontend.ReactiveSignal[Boolean]],
         |    label, style
         |  ))
         |
         |// ── Show ─────────────────────────────────────────────────────────────────
         |def Show(cond: Any)(block: => Any): scalascript.frontend.View[?] =
         |  val ch = _ssc_pcollect(block)
         |  val content = if ch.length == 1 then ch.head else scalascript.frontend.View.Fragment(ch)
         |  cond match
         |    case rs: scalascript.frontend.ReactiveSignal[?] =>
         |      _ssc_push(scalascript.frontend.View.ShowSignal(
         |        rs.asInstanceOf[scalascript.frontend.ReactiveSignal[Boolean]],
         |        content
         |      ))
         |    case b: Boolean if b =>
         |      _ssc_push(content)
         |    case _ =>
         |      _ssc_push(scalascript.frontend.View.Fragment(Nil))
         |
         |// ── Data-binding widgets ──────────────────────────────────────────────────
         |def ModelView(signal: Any, bindingVar: String)(block: => Any): scalascript.frontend.View[?] =
         |  val ch = _ssc_pcollect(block)
         |  val template = if ch.length == 1 then ch.head else scalascript.frontend.View.Fragment(ch)
         |  _ssc_push(scalascript.frontend.View.ModelView(
         |    signal.asInstanceOf[scalascript.frontend.FetchUrlSignal],
         |    bindingVar, template
         |  ))
         |
         |def ForModel(bindingVar: String, fieldPath: String, itemVar: String)(block: => Any): scalascript.frontend.View[?] =
         |  val ch = _ssc_pcollect(block)
         |  val template = if ch.length == 1 then ch.head else scalascript.frontend.View.Fragment(ch)
         |  _ssc_push(scalascript.frontend.View.ForModel(bindingVar, fieldPath, itemVar, template))
         |
         |def ModelText(varName: String, fieldPath: String, style: scalascript.frontend.Style = scalascript.frontend.Style()): scalascript.frontend.View[?] =
         |  _ssc_push(scalascript.frontend.View.ModelText(varName, fieldPath, style))
         |
         |// ── TabView ───────────────────────────────────────────────────────────────
         |def TabView(selection: Any)(block: => Any): scalascript.frontend.View[?] =
         |  _ssc_tab_items.clear()
         |  _ssc_pcollect(block)
         |  val tabs = _ssc_tab_items.toList.sortBy(_._4).map { case (content, label, icon, _) =>
         |    scalascript.frontend.Tab(label, icon, content)
         |  }
         |  _ssc_tab_items.clear()
         |  _ssc_push(scalascript.frontend.View.TabBar(
         |    tabs    = tabs,
         |    current = selection.asInstanceOf[scalascript.frontend.ReactiveSignal[Int]]
         |  ))
         |
         |// ── NavigationStack (pass-through wrapper) ────────────────────────────────
         |def NavigationStack(style: scalascript.frontend.Style = scalascript.frontend.Style())(block: => Any): scalascript.frontend.View[?] =
         |  val ch = _ssc_pcollect(block)
         |  val child = if ch.length == 1 then ch.head else scalascript.frontend.View.Fragment(ch)
         |  _ssc_push(child)
         |
         |""".stripMargin
    )
