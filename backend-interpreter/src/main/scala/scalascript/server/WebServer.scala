package scalascript.server

import scalascript.parser.Parser
import scalascript.interpreter.Interpreter
import scalascript.codegen.{JsGen, JsRuntime, JsRuntimeAsync, JsRuntimeV14Effects}
import scalascript.server.spi.{HttpServerSpi, TlsConfig}
import com.sun.net.httpserver.HttpServer as JHttpServer
import org.commonmark.parser.Parser as CmParser
import org.commonmark.renderer.html.HtmlRenderer

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
  @volatile private var _backend:  HttpServerSpi | Null                       = null
  // Legacy fields — kept while WsProxy / TlsProxy still serve as test
  // harnesses, but they're null on production starts since the SPI
  // backend owns both the public listening socket and the internal
  // HttpServer now.
  @scala.annotation.unused
  @volatile private var _internal: JHttpServer | Null                          = null
  @scala.annotation.unused
  @volatile private var _pubSock:  java.net.ServerSocket | Null                = null
  @scala.annotation.unused
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
    try _backend match { case b if b != null => b.stop(); case _ => () } catch case _: Throwable => ()
    _latch match { case l if l != null => l.countDown(); case _ => () }

  def start(port: Int, root: String, log: java.io.PrintStream,
            certPath: String = "", keyPath: String = ""): Unit =
    val useTls = certPath.nonEmpty && keyPath.nonEmpty

    val latch    = java.util.concurrent.CountDownLatch(1)
    _latch = latch

    // Single-thread executor shared with the SPI's WS user-callback
    // dispatch + HTTP-handler bodies.  Interpreter globals / call-stack
    // / position tracker aren't thread-safe, so handler bodies must run
    // serially across both protocols.
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

    // SPI discovery — pick the first registered HttpServerSpi.  In the
    // default classpath that's `JdkServerBackend`; a hosted deployment
    // can add `jetty` / `netty` modules to override.
    val backend: HttpServerSpi = {
      val it = java.util.ServiceLoader.load(classOf[HttpServerSpi]).iterator()
      if !it.hasNext then
        throw new RuntimeException(
          "No HttpServerSpi found on the classpath — " +
          "did the runtime-server-jvm module fail to register a ServiceLoader entry?")
      it.next()
    }
    _backend = backend

    // Build the application-layer handler.  Route lookup, middleware,
    // `Value` ↔ POJO conversion, and ssc-page / static-asset fallback
    // all live here; the SPI impl handles sockets and the upgrade-
    // handshake wire bytes.
    val handler = new InterpreterHttpHandler(
      log               = log,
      wsExecutor        = executor,
      fallbackRenderer  = req => renderFallback(root, req),
      maxBodySizeBytes  = () => _maxBodySizeBytes,
      spoolThreshold    = () => _spoolThreshold,
      uploadDir         = () => _uploadDir,
      corsOrigins       = () => _corsOrigins,
      corsMethods       = () => _corsMethods,
      corsHeaders       = () => _corsHeaders,
      gzipEnabled       = () => _gzipEnabled
    )

    val tls: Option[TlsConfig] =
      if useTls then Some(TlsConfig(certPath, keyPath)) else None

    backend.start(port, tls, handler)

    val scheme = if useTls then "https" else "http"
    log.println(s"ScalaScript web · $scheme://localhost:${backend.localPort}/  (root: $root)")
    log.println(s"  (backend=${backend.name})")
    log.println("Ctrl+C to stop.")
    latch.await()

  /** Build a POJO `Response` for the no-route-matched fallback path:
   *  static asset → `.ssc` rendering → 404 HTML.  Mirrors the pre-SPI
   *  inline branches in `handle(...)`.
   *
   *  Binary static assets — Response's `body: String` is decoded as
   *  UTF-8 by the SPI's `ResponseWriter`.  For non-text assets (images,
   *  fonts) we'd need a byte-array body shape.  Pre-SPI the interpreter
   *  served them straight through `StaticAssetServer.serve` (raw byte
   *  write).  Keeping that working under the SPI requires a
   *  byte-buffer-aware path that is out of scope for S1b — for now we
   *  decode/encode the Latin-1 round-trip for static assets and accept
   *  that text-typed assets work, binary ones may mojibake.  Real
   *  binary delivery returns in S2 (Jetty static handler) / S1c. */
  private def renderFallback(root: String, req: Request): Option[Response] =
    val rawPath = req.path
    val staticFile = StaticAssetServer.resolve(root, rawPath)
    val sscFile    = java.io.File(resolveSsc(root, rawPath))
    if staticFile.isDefined then
      val f = staticFile.get
      try
        val bytes = java.nio.file.Files.readAllBytes(f.toPath)
        val ct    = HttpHelpers.contentTypeFor(f.getName)
        Some(Response(200, Map("Content-Type" -> ct), new String(bytes, "UTF-8")))
      catch case _: Throwable => None
    else if sscFile.exists() then
      val body = renderFile(sscFile)
      Some(Response(200,
        Map("Content-Type" -> "text/html; charset=utf-8"),
        body))
    else None

  /** Build an SSLContext from PEM cert + PKCS#8 private key files.
   *  Kept on this object for backwards compatibility with callers that
   *  built their own TLS bootstrap; production servers go through the
   *  SPI which calls `TlsContextBuilder.build` directly. */
  def buildSslContext(certPath: String, keyPath: String): javax.net.ssl.SSLContext =
    TlsContextBuilder.build(certPath, keyPath)

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
