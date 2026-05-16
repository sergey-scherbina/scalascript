package scalascript.server

import scalascript.parser.Parser
import scalascript.interpreter.Interpreter
import scalascript.codegen.{JsGen, JsRuntime}
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

  def start(port: Int, root: String, log: java.io.PrintStream): Unit =
    val server = JHttpServer.create(InetSocketAddress(port), 0)
    server.createContext("/", handle(root, log, _))
    server.setExecutor(null)
    server.start()
    log.println(s"ScalaScript web · http://localhost:$port/  (root: $root)")
    log.println("Ctrl+C to stop.")
    Thread.currentThread().join()

  private def handle(root: String, log: java.io.PrintStream, ex: HttpExchange): Unit =
    try
      val method  = ex.getRequestMethod
      val rawPath = ex.getRequestURI.getPath
      Routes.matchRequest(method, rawPath) match
        case Some((entry, params)) =>
          dispatchRoute(entry, params, ex)
        case None =>
          // Fall back to static .ssc page rendering (GET only).
          val filePath = resolveSsc(root, rawPath)
          val file = java.io.File(filePath)
          val (status, body) =
            if file.exists() then (200, renderFile(file))
            else (404, page("404", s"<h1>404</h1><p>Not found: <code>$rawPath</code></p>"))
          val bytes = body.getBytes("UTF-8")
          ex.getResponseHeaders.add("Content-Type", "text/html; charset=utf-8")
          ex.sendResponseHeaders(status, bytes.length)
          ex.getResponseBody.write(bytes)
    catch case e: Exception =>
      log.println(s"Error: ${e.getMessage}")
    finally
      ex.close()

  /** Invoke the user's route handler closure with a `Request` value, then
   *  serialise its returned `Response` value back to the HTTP exchange. */
  private def dispatchRoute(
      entry:  Routes.Entry,
      params: Map[String, String],
      ex:     HttpExchange
  ): Unit =
    import scalascript.interpreter.Value
    val query = parseQuery(Option(ex.getRequestURI.getRawQuery).getOrElse(""))
    val headers: Map[Value, Value] =
      ex.getRequestHeaders.entrySet.iterator.asScala.flatMap { e =>
        if e.getValue.isEmpty then None
        else Some(Value.StringV(e.getKey) -> Value.StringV(e.getValue.get(0)))
      }.toMap
    val body = scala.io.Source.fromInputStream(ex.getRequestBody, "UTF-8").mkString
    val req = Value.InstanceV("Request", Map(
      "method"  -> Value.StringV(ex.getRequestMethod),
      "path"    -> Value.StringV(ex.getRequestURI.getPath),
      "params"  -> Value.MapV(params.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
      "query"   -> Value.MapV(query.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
      "headers" -> Value.MapV(headers),
      "body"    -> Value.StringV(body)
    ))
    val result = entry.interpreter.invoke(entry.handler, List(req))
    writeResponse(result, ex)

  private def writeResponse(v: scalascript.interpreter.Value, ex: HttpExchange): Unit =
    import scalascript.interpreter.Value
    val (status, headers, body) = v match
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
        (s, h, b)
      case Value.StringV(s)  => (200, Map("Content-Type" -> "text/plain; charset=utf-8"), s)
      case Value.UnitV       => (204, Map.empty[String, String], "")
      case other             => (200, Map("Content-Type" -> "text/plain; charset=utf-8"), Value.show(other))
    headers.foreach((k, v) => ex.getResponseHeaders.add(k, v))
    if !headers.contains("Content-Type") then
      ex.getResponseHeaders.add("Content-Type", "text/plain; charset=utf-8")
    val bytes = body.getBytes("UTF-8")
    ex.sendResponseHeaders(status, if bytes.isEmpty then -1 else bytes.length.toLong)
    if bytes.nonEmpty then ex.getResponseBody.write(bytes)

  private def parseQuery(q: String): Map[String, String] =
    if q.isEmpty then Map.empty
    else q.split('&').iterator.flatMap { pair =>
      val i = pair.indexOf('=')
      if i < 0 then Some(java.net.URLDecoder.decode(pair, "UTF-8") -> "")
      else Some(
        java.net.URLDecoder.decode(pair.substring(0, i), "UTF-8") ->
        java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8")
      )
    }.toMap

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
