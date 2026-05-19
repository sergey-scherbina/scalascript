package scalascript.server

import scalascript.parser.Parser
import scalascript.interpreter.Interpreter
import scalascript.codegen.{JsGen, JsRuntime, JsRuntimeAsync, JsRuntimeV14Effects}
import com.sun.net.httpserver.{HttpServer as JHttpServer, HttpExchange}
import org.commonmark.parser.{Parser as CmParser}
import org.commonmark.renderer.html.HtmlRenderer
import java.net.InetSocketAddress
import scala.jdk.CollectionConverters.*

/** Minimal HTTP server that serves .ssc files as HTML pages.
 *
 *  GET /path  →  serves  <rootDir>/path.ssc  rendered as HTML
 *  GET /      →  serves  <rootDir>/index.ssc
 *
 *  Rendering pipeline per request:
 *    1. Markdown sections  → rendered as HTML via commonmark HtmlRenderer
 *    2. Scala code blocks  → executed, output appended below each block
 */
object WebServer:
  private val mdParser   = CmParser.builder().build()
  private val htmlRender = HtmlRenderer.builder().build()

  @volatile private var _latch:    java.util.concurrent.CountDownLatch | Null = null
  @volatile private var _internal: JHttpServer | Null                          = null
  @volatile private var _pubSock:  java.net.ServerSocket | Null                = null
  @volatile private var _proxy:    WsProxy | Null                              = null

  @volatile private var _corsOrigins: List[String] = Nil
  @volatile private var _corsMethods: List[String] = Nil
  @volatile private var _corsHeaders: List[String] = Nil
  @volatile private var _gzipEnabled = false
  @volatile private var _maxBodySizeBytes: Long = Long.MaxValue
  @volatile private var _spoolThreshold: Long   = 1024L * 1024L
  @volatile private var _uploadDir: String       = System.getProperty("java.io.tmpdir")

  def configureCors(origins: List[String], methods: List[String], hdrs: List[String]): Unit =
    _corsOrigins = origins; _corsMethods = methods; _corsHeaders = hdrs

  def enableGzip(): Unit = _gzipEnabled = true

  def setMaxBodySize(n: Long): Unit = _maxBodySizeBytes = n
  def setSpoolThreshold(n: Long): Unit = _spoolThreshold = n
  def setUploadDir(path: String): Unit = _uploadDir = path

  def stop(): Unit =
    try _pubSock  match { case s if s != null => s.close(); case _ => () } catch case _: Throwable => ()
    try _proxy    match { case p if p != null => p.stop();  case _ => () } catch case _: Throwable => ()
    try _internal match { case h if h != null => h.stop(0); case _ => () } catch case _: Throwable => ()
    _latch match { case l if l != null => l.countDown(); case _ => () }

  def start(port: Int, root: String, log: java.io.PrintStream,
            certPath: String = "", keyPath: String = ""): Unit =
    val useTls = certPath.nonEmpty && keyPath.nonEmpty

    val latch    = java.util.concurrent.CountDownLatch(1)
    _latch = latch

    // Explicit single-thread executor shared between the HTTP handlers
    // (via JDK HttpServer) and the WebSocket app callbacks (via WsProxy
    // → WsConnection.dispatch).  The interpreter's globals / call-stack
    // / position tracker are not thread-safe, so handler bodies must run
    // serially regardless of which protocol triggered them.  JvmGen's
    // `serveRuntime` does the same for compiled output.
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

    // Internal HttpServer on a loopback ephemeral port.  Anything that
    // isn't a `Upgrade: websocket` request reaches it through the proxy
    // below — REST handlers and `.ssc` page rendering keep working
    // exactly as before regardless of whether TLS is in use.
    val internalAddr = InetSocketAddress("127.0.0.1", 0)
    val internal     = JHttpServer.create(internalAddr, 0)
    internal.createContext("/", handle(root, log, _))
    internal.setExecutor(executor)
    internal.start()
    _internal = internal
    val internalPort = internal.getAddress.getPort

    if useTls then
      // TLS mode: SSLServerSocket + virtual-thread-per-connection proxy.
      val sslCtx = buildSslContext(certPath, keyPath)
      val pub = sslCtx.getServerSocketFactory.createServerSocket(port)
        .asInstanceOf[javax.net.ssl.SSLServerSocket]
      _pubSock = pub
      log.println(s"ScalaScript web · https://localhost:$port/  (root: $root)")
      log.println(s"  (TLS proxy → internal HttpServer on 127.0.0.1:$internalPort)")
      log.println("Ctrl+C to stop.")
      val pool = buildVThreadPool()
      Thread({ () =>
        while !pub.isClosed do
          try
            val c = pub.accept()
            pool.execute { () =>
              TlsProxy.handleConnection(c, internalPort, executor, log)
            }
          catch case _: Throwable => ()
      }, "tls-proxy-accept").start()
      latch.await()
    else
      // Non-TLS mode: NIO proxy (original behaviour).
      val proxy = WsProxy(
        publicPort   = port,
        internalAddr = InetSocketAddress("127.0.0.1", internalPort),
        wsExecutor   = executor,
        log          = log
      )
      _proxy = proxy
      proxy.start()
      log.println(s"ScalaScript web · http://localhost:$port/  (root: $root)")
      log.println(s"  (NIO proxy → internal HttpServer on 127.0.0.1:$internalPort)")
      log.println("Ctrl+C to stop.")
      latch.await()

  /** Build an SSLContext from PEM cert + PKCS#8 private key files.
   *
   *  Accepts both traditional (PKCS#8 `BEGIN PRIVATE KEY`) and RSA
   *  (`BEGIN RSA PRIVATE KEY`) PEM formats; strips the header/footer
   *  and decodes the DER payload.  For RSA keys the raw bytes are
   *  wrapped in a minimal PKCS#8 envelope so `PKCS8EncodedKeySpec`
   *  accepts them without extra dependencies. */
  /** TLS SSLContext + virtual-thread pool builders delegate to the
   *  shared `TlsContextBuilder` in runtime-server-common — same logic
   *  for the interpreter HTTP server and the JvmGen-emitted runtime. */
  def buildSslContext(certPath: String, keyPath: String): javax.net.ssl.SSLContext =
    TlsContextBuilder.build(certPath, keyPath)
  private def buildVThreadPool(): java.util.concurrent.ExecutorService =
    TlsContextBuilder.vthreadPool()

  private def applyCorsHeaders(ex: HttpExchange): Unit =
    CorsHelpers(ex, _corsOrigins, _corsMethods, _corsHeaders)

  private def handle(root: String, log: java.io.PrintStream, ex: HttpExchange): Unit =
    Metrics.httpRequests.incrementAndGet()
    val startNs = java.lang.System.nanoTime()
    val accessMethod = ex.getRequestMethod
    val accessPath   = ex.getRequestURI.getPath
    val accessIp     = Option(ex.getRemoteAddress).map(_.getAddress.getHostAddress).getOrElse("?")
    val accessUa     = Option(ex.getRequestHeaders.getFirst("User-Agent")).getOrElse("")
    try
      val method  = ex.getRequestMethod
      val rawPath = ex.getRequestURI.getPath
      // CORS preflight — OPTIONS with Origin bypasses route dispatch
      if method == "OPTIONS" && _corsOrigins.nonEmpty then
        applyCorsHeaders(ex)
        ex.sendResponseHeaders(204, -1)
      else
      Routes.matchRequest(method, rawPath) match
        case Some((entry, params)) =>
          dispatchRoute(entry, params, ex)
        case None =>
          // No route matched.  Try, in order:
          //   1. A static asset (any non-.ssc file under the root) — serve
          //      with a sniffed Content-Type and pass-through bytes.
          //   2. A `.ssc` page — render it as HTML (the original behaviour).
          //   3. 404.
          val staticFile = resolveStatic(root, rawPath)
          val sscFile    = java.io.File(resolveSsc(root, rawPath))
          if staticFile.isDefined then
            serveStatic(staticFile.get, ex)
          else if sscFile.exists() then
            val body  = renderFile(sscFile)
            val bytes = body.getBytes("UTF-8")
            ex.getResponseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.sendResponseHeaders(200, bytes.length)
            ex.getResponseBody.write(bytes)
          else
            val body  = page("404", s"<h1>404</h1><p>Not found: <code>$rawPath</code></p>")
            val bytes = body.getBytes("UTF-8")
            ex.getResponseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.sendResponseHeaders(404, bytes.length)
            ex.getResponseBody.write(bytes)
    catch case e: Exception =>
      log.println(s"Error: ${e.getMessage}")
      Metrics.http5xx.incrementAndGet()
    finally
      // Bucket 4xx based on the response code the handler actually
      // wrote.  5xx is counted in the catch above so we don't
      // double-bump for handler-thrown exceptions that never made
      // it to `sendResponseHeaders`.  `getResponseCode == -1` when
      // the response wasn't sent.
      val code = try ex.getResponseCode catch case _: Throwable => -1
      if code >= 400 && code < 500 then Metrics.http4xx.incrementAndGet()
      else if code >= 500           then Metrics.http5xx.incrementAndGet()
      // Structured access log (Sprint 4 #15) — one tab-separated
      // line per request.  Stable key order so log shippers can
      // parse it without quoting tricks.  `duration_ms` is wall
      // clock from entry to finally (post-write but pre-close).
      val durMs = (java.lang.System.nanoTime() - startNs) / 1_000_000L
      val effCode = if code < 0 then 0 else code
      log.println(s"http\tip=$accessIp\tmethod=$accessMethod\tpath=$accessPath\tstatus=$effCode\tduration_ms=$durMs\tua=\"${accessUa.replace('"', '\'')}\"")
      ex.close()

  /** Invoke the user's route handler closure with a `Request` value, then
   *  serialise its returned `Response` value back to the HTTP exchange. */
  private def dispatchRoute(
      entry:  Routes.Entry,
      params: Map[String, String],
      ex:     HttpExchange
  ): Unit =
    import scalascript.interpreter.Value
    // Parse the JDK HttpExchange through the shared RequestBuilder
    // (runtime-server-common) so the interpreter and the codegen output
    // emit byte-identical Requests for the same inputs.  413 on
    // BodyTooLargeError is written here (the codegen side throws its
    // local `_BodyTooLarge` to climb out of the dispatch loop).
    val (pojoReq, rawCookieSession, _spooledTmps) =
      try RequestBuilder.parse(
        ex, ex.getRequestMethod, ex.getRequestURI.getPath, params,
        RequestBuilder.Config(
          maxBodySize         = _maxBodySizeBytes,
          spoolThreshold      = _spoolThreshold,
          uploadDir           = _uploadDir,
          sessionStoreEnabled = SessionStore.isEnabled,
          sessionStoreGet     = SessionStore.get,
          jwtVerify           = Jwt.verify
        )
      )
      catch case _: RequestBuilder.BodyTooLargeError =>
        val msg = "Request Entity Too Large".getBytes("UTF-8")
        ex.sendResponseHeaders(413, msg.length.toLong)
        ex.getResponseBody.write(msg)
        return

    // Lift the POJO Request into the `Value.InstanceV("Request", …)`
    // shape user-defined route handlers expect.  `files` carry the
    // shared POJO `UploadedFile` records, which need their own
    // wrap into `Value.InstanceV("UploadedFile", …)`.
    val req = Value.InstanceV("Request", Map(
      "method"  -> Value.StringV(pojoReq.method),
      "path"    -> Value.StringV(pojoReq.path),
      "params"  -> Value.MapV(pojoReq.params.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
      "query"   -> Value.MapV(pojoReq.query.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
      "headers" -> Value.MapV(pojoReq.headers.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
      "body"    -> Value.StringV(pojoReq.body),
      // Lenient `req.json` — Some(parsed) on success, None on parse
      // failure or empty body.
      "json"    -> Value.OptionV(
        if pojoReq.body.isEmpty then None
        else scalascript.interpreter.JsonParser.parseOption(pojoReq.body)
      ),
      "form"    -> Value.MapV(pojoReq.form.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
      "files"   -> Value.MapV(pojoReq.files.map { case (k, f) =>
        Value.StringV(k) -> Value.InstanceV("UploadedFile", Map(
          "name"        -> Value.StringV(f.name),
          "filename"    -> Value.StringV(f.filename),
          "contentType" -> Value.StringV(f.contentType),
          "size"        -> Value.IntV(f.size),
          "bytes"       -> Value.StringV(f.bytes),
          "path"        -> Value.StringV(f.path)
        ))
      }),
      "session" -> Value.MapV(pojoReq.session.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
      "cookies" -> Value.MapV(pojoReq.cookies.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
      "bearerToken" -> pojoReq.bearerToken.map(t => Value.OptionV(Some(Value.StringV(t))))
        .getOrElse(Value.OptionV(None)),
      "jwtClaims"   -> pojoReq.jwtClaims.map(c =>
          Value.OptionV(Some(Value.MapV(c.map((k, v) => Value.StringV(k) -> Value.StringV(v))))))
        .getOrElse(Value.OptionV(None)),
      "basicAuth"   -> pojoReq.basicAuth.map((u, p) =>
          Value.OptionV(Some(Value.TupleV(List(Value.StringV(u), Value.StringV(p))))))
        .getOrElse(Value.OptionV(None))
    ))
    // Tier 5 #20 — typed-validation primitives short-circuit by
    // throwing RestValidationError, which we catch here and convert
    // into a 400 Bad Request.  Handlers can stay linear:
    //   val email = requireString(req, "email")
    //   val age   = requireInt(req, "age")
    //   ...
    // Build middleware chain: innermost = route handler, outermost = first registered middleware.
    val mws = Routes.middlewares
    def baseHandler(): Value =
      try entry.interpreter.invoke(entry.handler, List(req))
      catch case ve: RestValidationError =>
        Value.InstanceV("Response", Map(
          "status"  -> Value.IntV(400),
          "headers" -> Value.MapV(Map(
            Value.StringV("Content-Type") -> Value.StringV("text/plain; charset=utf-8")
          )),
          "body"    -> Value.StringV(ve.getMessage)
        ))
    var chain: () => Value = () => baseHandler()
    mws.reverse.foreach { (fn, interp) =>
      val nextChain = chain
      val nextFn = scalascript.interpreter.Value.NativeFnV("next",
        scalascript.interpreter.Computation.pureFn { _ => nextChain() })
      chain = () => interp.invoke(fn, List(req, nextFn))
    }
    try
      val result = chain()
      result match
        case Value.InstanceV("StreamResponse", fields) =>
          handleStreamResponse(fields, ex, rawCookieSession, entry.interpreter)
        case _ =>
          writeResponse(result, ex, rawCookieSession)
    finally
      _spooledTmps.foreach(f => try f.delete() catch case _: Throwable => ())

  private def handleStreamResponse(
      fields:  Map[String, scalascript.interpreter.Value],
      ex:      HttpExchange,
      @annotation.unused _unused: Map[String, String],
      interp:  Interpreter
  ): Unit =
    import scalascript.interpreter.Value
    val status = fields.get("status") match
      case Some(Value.IntV(n)) => n.toInt
      case _                   => 200
    val hdrs = fields.get("headers") match
      case Some(Value.MapV(m)) => m.collect { case (Value.StringV(k), Value.StringV(v)) => k -> v }.toMap
      case _                   => Map.empty[String, String]
    val callback = fields.getOrElse("callback",
      throw new RuntimeException("StreamResponse missing callback"))
    // Delegate to the shared `StreamResponseWriter` (CORS + headers +
    // chunked write).  The interpreter-specific `runWriter` builds a
    // `Value.NativeFnV` that forwards each `write` call back into the
    // user closure via `Interpreter.invoke`.
    StreamResponseWriter.write(ex, status, hdrs,
      ResponseWriter.Config(
        corsOrigins = _corsOrigins,
        corsMethods = _corsMethods,
        corsHeaders = _corsHeaders
      ),
      { write =>
        val writeNative = Value.NativeFnV("streamWrite",
          scalascript.interpreter.Computation.pureFn { args =>
            val chunk = args match
              case List(Value.StringV(s)) => s
              case List(other)            => Value.show(other)
              case _                      => ""
            write(chunk)
            Value.UnitV
          })
        interp.invoke(callback, List(writeNative))
      }
    )

  /** Convert a `Value.InstanceV("Response", …)` (or string / unit) into
   *  the POJO `Response` and hand off to the shared `ResponseWriter`
   *  (in runtime-server-common) which performs CORS / setSession / 304
   *  / gzip / body write. */
  private def writeResponse(
      v:                scalascript.interpreter.Value,
      ex:               HttpExchange,
      rawCookieSession: Map[String, String]
  ): Unit =
    import scalascript.interpreter.Value
    val resp = v match
      case Value.InstanceV("Response", fields) =>
        val s = fields.get("status") match
          case Some(Value.IntV(n)) => n.toInt
          case _                   => 200
        val h = fields.get("headers") match
          case Some(Value.MapV(m)) =>
            m.collect { case (Value.StringV(k), Value.StringV(v)) => k -> v }.toMap
          case _ => Map.empty[String, String]
        val b = fields.get("body") match
          case Some(Value.StringV(s)) => s
          case Some(other)            => Value.show(other)
          case None                   => ""
        // `withSession`/`clearSession` attach a `setSession: Map[String, String]`
        // field — Some(empty) means clear, Some(non-empty) means write,
        // None means leave the client's cookie alone.
        val ss = fields.get("setSession") match
          case Some(Value.MapV(m)) =>
            Some(m.collect { case (Value.StringV(k), Value.StringV(v)) => k -> v }.toMap)
          case _ => None
        Response(s, h, b, ss)
      case Value.StringV(s) => Response(200, Map("Content-Type" -> "text/plain; charset=utf-8"), s)
      case Value.UnitV      => Response(204, Map.empty, "")
      case other            => Response(200, Map("Content-Type" -> "text/plain; charset=utf-8"), Value.show(other))
    ResponseWriter.write(ex, resp, rawCookieSession, ResponseWriter.Config(
      corsOrigins         = _corsOrigins,
      corsMethods         = _corsMethods,
      corsHeaders         = _corsHeaders,
      gzipEnabled         = _gzipEnabled,
      sessionStoreEnabled = SessionStore.isEnabled,
      sessionStoreDelete  = SessionStore.delete,
      sessionStorePut     = SessionStore.put
    ))

  /** Resolve a static (non-`.ssc`) file under `root` from the URL path.
   *  Returns the file only if it exists, is a regular file, lies inside
   *  `root` (path-traversal guard), and has a known asset extension.
   *  `.ssc` files are deliberately excluded so route dispatch + the `.ssc`
   *  rendering path keep ownership of them. */
  private def resolveStatic(root: String, urlPath: String): Option[java.io.File] =
    val cleaned = urlPath.stripPrefix("/")
    if cleaned.isEmpty then return None
    val rootDir = java.io.File(root).getCanonicalFile
    val target  = java.io.File(rootDir, cleaned).getCanonicalFile
    val name    = target.getName
    if !target.exists() || !target.isFile() then None
    else if !target.getPath.startsWith(rootDir.getPath) then None    // escaped root
    else if name.endsWith(".ssc") then None
    else Some(target)

  private def serveStatic(file: java.io.File, ex: HttpExchange): Unit =
    val bytes = java.nio.file.Files.readAllBytes(file.toPath)
    ex.getResponseHeaders.add("Content-Type", contentTypeFor(file.getName))
    ex.sendResponseHeaders(200, bytes.length.toLong)
    ex.getResponseBody.write(bytes)

  /** Map a filename suffix to a Content-Type.  Probe Files.probeContentType
   *  first (covers many less-common types via the platform mime DB), fall
   *  back to a small explicit table for the web essentials, then a safe
   *  `application/octet-stream`. */
  private def contentTypeFor(name: String): String = HttpHelpers.contentTypeFor(name)

  // `parseMultipart` and `parseQuery` (previously local Value-bridge
  // wrappers) collapsed into `RequestBuilder.parse` — see `dispatchRoute`
  // above for the single call site that now does the POJO → Value lift
  // inline on the `pojoReq.files` map.

  private def resolveSsc(root: String, urlPath: String): String =
    val clean = urlPath.stripSuffix("/").stripSuffix(".ssc")
    val name  = if clean.isEmpty || clean == "/" then "/index" else clean
    s"$root/$name.ssc".replace("//", "/")

  private def renderFile(file: java.io.File): String =
    val src    = scala.io.Source.fromFile(file, "UTF-8").mkString
    val module = Parser.parse(src)
    val title  = module.manifest.flatMap(_.name).getOrElse(file.getName.stripSuffix(".ssc"))

    // 1. Render the Markdown body as HTML (strip shebang / front-matter first)
    val clean  = if src.startsWith("#!") then src.dropWhile(_ != '\n').drop(1) else src
    val body   = stripFrontMatter(clean)
    val mdHtml = htmlRender.render(mdParser.parse(body))

    // 2. Run all Scala blocks server-side, capture combined output
    val output = captureRun(module)
    val serverOutHtml = if output.isEmpty then ""
      else s"""<section class="output server-out"><h3>Server output</h3><pre>${esc(output)}</pre></section>"""

    // 3. Generate JS for browser-side execution
    val generatedJs = try JsGen.generate(module) catch case e: Exception => s"/* JsGen error: ${e.getMessage} */"
    val browserPanel =
      s"""<section class="output browser-out">
<h3>Browser output</h3>
<pre id="browser-output">Running...</pre>
</section>
<script>
try {
${JsRuntime}
${JsRuntimeAsync}
${JsRuntimeV14Effects}
${generatedJs}
  document.getElementById('browser-output').textContent = _output.join('\\n') || '(no output)';
} catch(e) {
  document.getElementById('browser-output').textContent = 'Error: ' + e.message;
}
</script>"""

    page(title, mdHtml + serverOutHtml + browserPanel)

  private def captureRun(module: scalascript.ast.Module): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true, "UTF-8")
    try Interpreter.run(module, ps)
    catch case e: Exception => ps.println(s"Runtime error: ${e.getMessage}")
    buf.toString("UTF-8").trim

  private def stripFrontMatter(src: String): String =
    if !src.startsWith("---") then return src
    val rest = src.dropWhile(_ != '\n').drop(1)
    val end  = rest.indexOf("\n---")
    if end < 0 then src else rest.substring(end + 4).dropWhile(_ == '\n')

  private def esc(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  private def page(title: String, content: String): String =
    s"""<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title — ScalaScript</title>
<style>
  :root{--fg:#1a1a2e;--bg:#fafafa;--accent:#e94560;--code:#f0f0f0}
  *{box-sizing:border-box;margin:0;padding:0}
  body{font:1rem/1.7 system-ui,sans-serif;color:var(--fg);background:var(--bg);
       max-width:800px;margin:0 auto;padding:2rem 1.5rem}
  h1,h2,h3{margin:1.4em 0 .4em;line-height:1.2}
  h1{font-size:2rem;color:var(--accent)}
  p{margin:.6em 0}
  pre,code{font-family:ui-monospace,monospace;background:var(--code);border-radius:4px}
  pre{padding:1rem;overflow-x:auto;font-size:.88rem;margin:1em 0}
  code{padding:.1em .35em;font-size:.9em}
  section.output{margin-top:1.5rem;border-top:2px solid var(--accent);padding-top:1rem}
  section.output h3{color:var(--accent);font-size:.9rem;text-transform:uppercase;
                    letter-spacing:.05em;margin-bottom:.5rem}
  section.browser-out{border-top-color:#2e7d32}
  section.browser-out h3{color:#2e7d32}
  a{color:var(--accent)}
  nav{margin-bottom:2rem;font-size:.85rem;opacity:.6}
</style>
</head><body>
<nav><a href="/">⌂ home</a></nav>
$content
</body></html>"""
