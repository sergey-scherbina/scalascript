package scalascript.cli

import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

// The interactive REPL command and its `:`-command handlers (serve, stop,
// mount, load, http, call, break, debug stepping, …) plus REPL-local HTTP
// helpers. Extracted from Main.scala. The replHandle* entry points are public
// because ReplWebTest / ReplWebIntegrationTest drive them directly.

final class ReplCmd extends CliCommand:
  def name = "repl"
  override def summary = "Start an interactive REPL (blank line runs, :quit exits)"
  override def category = "Run & develop"
  def run(args: List[String]): Unit =
    import scala.io.StdIn
    val interp = Interpreter()
    interp.run(Parser.parse("# REPL\n"))   // initialise builtins, no code runs
    val dbgHooks = ReplDebugHooks()
    interp.setDebugSourceFile("<repl>")
    interp.setDebugHooks(Some(dbgHooks.mkHooks()))
    System.err.println("ScalaScript REPL  (:help for commands, :quit to exit, blank line to run)")
    var running = true
    // Global REPL setting: include handler deserialization details in 400 errors.
    // Read by replHandleMount for typed handler wrapping (Phase 7).
    var errorDetails: Boolean = true
    // Tracks the port the HTTP server is currently listening on (None = stopped).
    val serverPort = new java.util.concurrent.atomic.AtomicReference[Option[Int]](None)
    while running do
      Option(StdIn.readLine("ssc> ")) match
        case None | Some(":quit" | ":q" | ":exit") => running = false
        case Some(":help" | ":h") =>
          System.err.println(
            """|ScalaScript REPL
             |
             |Input:
             |  Type code and press Enter to add a line.
             |  Blank line          — run the accumulated snippet.
             |  Multi-line input is shown with the "   | " continuation prompt.
             |
             |General:
             |  :help  :h           — this message
             |  :quit  :q  :exit    — exit the REPL
             |  :reset              — clear all bindings, restart interpreter
             |  :set errorDetails <true|false>  — verbose deser errors (default: true)
             |
             |HTTP server:
             |  :serve [port]       — start HTTP server in background (default: 8080)
             |  :stop [--keep-routes] — stop server; clears routes unless --keep-routes
             |  :clear              — clear route table without stopping server
             |
             |Routes:
             |  :mount M /path { expr }         — register inline handler
             |  :mount M /path name             — register function from REPL bindings
             |  :mount M /path file.ssc [k=v]  — register handler from file
             |  :load file.ssc      — run file's route() calls; replaces previous routes
             |  :reload file.ssc    — re-run without repeating method/path
             |  :unmount M /path    — remove a specific route
             |  :routes             — list all registered routes
             |
             |Testing:
             |  :http M /path [body] [-H "K: V"]  — real HTTP request to localhost:<port>
             |  :call M /path [body] [-H "K: V"]  — in-process dispatch (no server needed)
             |
             |Breakpoints & stepping:
             |  :break <N>          — set breakpoint at snippet line N
             |  :break list         — list all breakpoints
             |  :break clear        — remove all breakpoints
             |  :step               — enable step-in for the next snippet
             |
             |Debug sub-prompt (appears when a breakpoint is hit):
             |  :continue  :c       — resume to next breakpoint or end
             |  :next      :n       — step over
             |  :step      :s       — step into
             |  :out                — step out of current function
             |  :locals    :l       — show local variables
             |  :stack     :bt      — show call stack
             |  :print <expr>       — evaluate expression in current frame
             |  :quit      :q       — abort snippet, return to ssc>
             |""".stripMargin)
        case Some(":reset") =>
          interp.run(Parser.parse("# REPL\n"))
          System.err.println("[reset] interpreter cleared")
        case Some(s) if s.startsWith(":set ") =>
          replHandleSet(s.trim, { v => errorDetails = v })
        case Some(s) if s == ":serve" || s.startsWith(":serve ") =>
          replHandleServe(s.trim, serverPort, interp)
        case Some(s) if s == ":stop" || s == ":stop --keep-routes" =>
          replHandleStop(s.trim, serverPort)
        case Some(":clear") =>
          scalascript.server.Routes.clear()
          System.err.println("Routes cleared.")
        case Some(s) if s.startsWith(":mount ") =>
          replHandleMount(s.trim, interp, errorDetails)
        case Some(s) if s.startsWith(":load ") =>
          replHandleLoad(s.trim, interp)
        case Some(s) if s.startsWith(":reload ") =>
          replHandleReload(s.trim, interp)
        case Some(s) if s.startsWith(":unmount ") =>
          replHandleUnmount(s.trim)
        case Some(":routes") =>
          replHandleRoutes()
        case Some(s) if s == ":http" || s.startsWith(":http ") =>
          replHandleHttp(s.trim, serverPort)
        case Some(s) if s == ":call" || s.startsWith(":call ") =>
          replHandleCall(s.trim, interp)
        case Some(s) if s.startsWith(":break")      => replHandleBreak(s.trim, dbgHooks)
        case Some(":step")                          =>
          dbgHooks.enableStepIn()
          System.err.println("[step] step-in enabled — enter your snippet")
        case Some(first) =>
          val lines = scala.collection.mutable.ArrayBuffer(first)
          var more = true
          while more do
            Option(StdIn.readLine("   | ")) match
              case None | Some("") => more = false
              case Some(next)      => lines += next
          val code = lines.mkString("\n").trim
          if code.nonEmpty then
            if dbgHooks.isDebugActive then
              runReplSnippetDebug(code, interp, dbgHooks)
            else
              try   interp.runSnippet(code)
              catch case e: Exception => System.err.println(s"Error: ${e.getMessage}")

/** Handle `:serve [port]` in the REPL.
 *  Starts WebServer in a background virtual thread (non-blocking).
 *  `serverPort` tracks the running port; call is a no-op if already serving. */
def replHandleServe(
    cmd:        String,
    serverPort: java.util.concurrent.atomic.AtomicReference[Option[Int]],
    interp:     Interpreter
): Unit =
  serverPort.get() match
    case Some(p) =>
      System.err.println(s"Already serving on :$p.")
    case None =>
      val portOpt: Option[Int] = cmd.stripPrefix(":serve").trim match
        case ""  => Some(8080)
        case s   => s.toIntOption.orElse { System.err.println(s"Invalid port: $s"); None }
      portOpt.foreach { port =>
        val dir = System.getProperty("user.dir")
        serverPort.set(Some(port))
        Thread.ofVirtual().start { () =>
          try
            scalascript.server.WebServer.start(port, dir, interp.out,
              wsRoutes = interp.wsRoutes)
          catch
            case _: Throwable => serverPort.set(None)
        }
        System.err.println(s"Listening on :$port")
      }

/** Handle `:stop [--keep-routes]` in the REPL.
 *  Stops the running server; clears routes unless `--keep-routes` is present. */
def replHandleStop(
    cmd:        String,
    serverPort: java.util.concurrent.atomic.AtomicReference[Option[Int]]
): Unit =
  serverPort.get() match
    case None =>
      System.err.println("No server running.")
    case Some(_) =>
      scalascript.server.WebServer.stop()
      serverPort.set(None)
      val keepRoutes = cmd.endsWith("--keep-routes")
      if keepRoutes then
        System.err.println("Server stopped. Routes kept.")
      else
        scalascript.server.Routes.clear()
        System.err.println("Server stopped. Routes cleared.")

/** Handle `:set <key> <value>` in the REPL.
 *
 *  Currently supported keys:
 *  - `errorDetails` — `true` or `false`; controls verbose deser errors (Phase 7).
 *
 *  `setFn` is a callback that receives the parsed Boolean; the caller stores it in
 *  its local `var errorDetails`.  This avoids threading a mutable cell through all
 *  other helpers. */
def replHandleSet(cmd: String, setFn: Boolean => Unit): Unit =
  val rest = cmd.stripPrefix(":set").trim
  rest.split("\\s+", 2).toList match
    case List("errorDetails", value) =>
      value match
        case "true"  => setFn(true);  System.err.println("errorDetails = true")
        case "false" => setFn(false); System.err.println("errorDetails = false")
        case _       => System.err.println("Expected true or false")
    case List(key, _) =>
      System.err.println(s"Unknown setting: $key. Known: errorDetails")
    case _ =>
      System.err.println("Usage: :set errorDetails true|false")

/** Handle `:mount METHOD /path REST` in the REPL.
 *
 *  Three forms are supported:
 *  - Inline:    `:mount GET /ping { _ => Response.text("pong") }`
 *  - By name:   `:mount GET /greet greet`
 *  - From file: `:mount GET /items/:id handlers/entity.ssc [key=value ...]`
 *
 *  `errorDetails` is the global REPL setting from `:set errorDetails`.
 */
def replHandleMount(cmd: String, interp: Interpreter, errorDetails: Boolean = true): Unit =
  import scalascript.server.Routes
  import scalascript.interpreter.{Value, TypedHandlerWrapper}
  // Strip ":mount " prefix and split into at most 3 parts: method, path, rest
  val rest0 = cmd.stripPrefix(":mount").trim
  val parts = rest0.split("\\s+", 3)
  if parts.length < 3 then
    System.err.println("Usage: :mount METHOD /path { expr | name | file.ssc [k=v ...] }")
    return
  val method = parts(0).toUpperCase
  val path   = parts(1)
  val rest   = parts(2)

  if rest.startsWith("{") then
    // ── Form 1: inline handler expression ────────────────────────────────
    try
      interp.runSnippet(rest)
      val rawHandler = interp.lastResult
      val baseHandler: Value = rawHandler match
        case fn: Value.FunV => fn
        case other =>
          // Auto-wrap bare Response values
          Value.NativeFnV("mount.static",
            scalascript.interpreter.Computation.pureFn(_ => other))
      val handler = TypedHandlerWrapper.wrapIfTyped(
        baseHandler,
        invoke      = (fn, args) => interp.invoke(fn, args),
        globalsView = interp.globalsView,
        mountedPath = path,
        errorDetails = errorDetails,
      )
      Routes.register(method, path, handler, interp, source = None, mountCtx = Map.empty)
      System.err.println(s"Mounted: $method $path")
    catch
      case e: Exception => System.err.println(s"Error evaluating handler: ${e.getMessage}")

  else if rest.contains(".ssc") && (rest.endsWith(".ssc") || rest.contains(".ssc ")) then
    // ── Form 3: file + optional ctx key=value tokens ──────────────────────
    val tokens = rest.split("\\s+").toList
    val file   = tokens.head
    val ctx: Map[String, Value] = tokens.drop(1).flatMap { t =>
      val pair = t.split("=", 2)
      if pair.length == 2 then Some(pair(0) -> (Value.StringV(pair(1)): Value))
      else None
    }.toMap
    val absPath = java.nio.file.Paths.get(file).toAbsolutePath.normalize().toString
    try
      interp.mountFileAsRoute(method, path, absPath, ctx)
      val ctxStr = if ctx.nonEmpty then
        s", ctx: {${ctx.map((k, v) => s"$k=${Value.show(v)}").mkString(", ")}}"
      else ""
      System.err.println(s"Mounted: $method $path  ($file$ctxStr)")
    catch
      case e: Exception => System.err.println(s"Error mounting $file: ${e.getMessage}")

  else
    // ── Form 2: function name from REPL globals ───────────────────────────
    val name = rest.trim
    interp.globalsView.get(name) match
      case None =>
        System.err.println(s"Unknown name: $name")
      case Some(fn: Value.FunV) =>
        val handler = TypedHandlerWrapper.wrapIfTyped(
          fn,
          invoke       = (fn2, args) => interp.invoke(fn2, args),
          globalsView  = interp.globalsView,
          mountedPath  = path,
          errorDetails = errorDetails,
        )
        Routes.register(method, path, handler, interp, source = None, mountCtx = Map.empty)
        System.err.println(s"Mounted: $method $path  ($name)")
      case Some(_) =>
        System.err.println(s"Not a function: $name")

/** Handle `:load file.ssc` in the REPL.
 *
 *  Resolves the file to an absolute path, clears any routes previously
 *  registered by that file (via [[Routes.removeBySource]]), then parses and
 *  runs the file with [[Interpreter.setLoadingFile]] set so that every
 *  `route()` call inside records `source = Some(absPath)` and `style = "load"`.
 *  The file is executed in the existing REPL interpreter's context so that
 *  all its globals and plugins are available.  Prints the newly registered
 *  routes on success.
 */
def replHandleLoad(cmd: String, interp: Interpreter): Unit =
  import scalascript.server.Routes
  import scalascript.parser.Parser
  val file = cmd.stripPrefix(":load").trim
  if file.isEmpty then
    System.err.println("Usage: :load file.ssc")
    return
  val absPath = java.nio.file.Paths.get(file).toAbsolutePath.normalize().toString
  val f = new java.io.File(absPath)
  if !f.exists() then
    System.err.println(s"File not found: $file")
    return
  // Remove stale routes from a previous load of this file
  Routes.removeBySource(absPath)
  // Tag all route() calls inside with source + style="load"
  interp.setLoadingFile(Some(absPath))
  try
    val contents = scala.io.Source.fromFile(absPath).mkString
    // Use run() rather than runSections() so that builtins and plugin
    // intrinsics are (re-)initialised before the file's sections execute.
    // run() is additive — it does not clear existing REPL globals, it
    // only (re-)installs builtins on top.
    interp.run(Parser.parse(contents))
  catch
    case e: Exception =>
      System.err.println(s"Error loading $file: ${e.getMessage}")
  finally
    interp.setLoadingFile(None)
  // Print registered routes from this file
  val registered = Routes.all.filter(_.source.contains(absPath))
  if registered.isEmpty then
    System.err.println(s"Loaded $file: (no routes registered)")
  else
    System.err.println(s"Loaded $file:")
    registered.foreach { e =>
      System.err.println(s"  ${e.method.padTo(6, ' ')} ${e.path}")
    }

/** Handle `:reload file.ssc` in the REPL.
 *
 *  Looks up existing [[Routes.Entry]] records with `source == Some(absPath)`.
 *  If the entries have `style == "load"` (registered via `:load`), re-runs
 *  [[replHandleLoad]].  If they have `style == "mount"` (registered via
 *  `:mount file.ssc`), re-mounts each one using the stored method, path,
 *  and mountCtx.  Mixed styles in the same file are handled entry-by-entry.
 */
def replHandleReload(cmd: String, interp: Interpreter): Unit =
  import scalascript.server.Routes
  val file = cmd.stripPrefix(":reload").trim
  if file.isEmpty then
    System.err.println("Usage: :reload file.ssc")
    return
  val absPath = java.nio.file.Paths.get(file).toAbsolutePath.normalize().toString
  val existing = Routes.all.filter(_.source.contains(absPath))
  if existing.isEmpty then
    System.err.println(s"Unknown file: $file — use :mount or :load first.")
    return
  val f = new java.io.File(absPath)
  if !f.exists() then
    System.err.println(s"File not found: $file")
    return
  // Partition by registration style
  val (loadEntries, mountEntries) = existing.partition(_.style == "load")
  // Re-load style: clear + rerun (replHandleLoad handles printing)
  if loadEntries.nonEmpty then
    replHandleLoad(s":load $file", interp)
  // Re-mount style: re-mount each entry with its original method/path/ctx
  mountEntries.foreach { entry =>
    try
      interp.mountFileAsRoute(entry.method, entry.path, absPath, entry.mountCtx)
      System.err.println(s"Reloaded: ${entry.method} ${entry.path}  ($file)")
    catch
      case e: Exception =>
        System.err.println(s"Error reloading ${entry.method} ${entry.path}: ${e.getMessage}")
  }

/** Handle `:unmount METHOD /path` in the REPL. */
def replHandleUnmount(cmd: String): Unit =
  import scalascript.server.Routes
  val rest = cmd.stripPrefix(":unmount").trim
  val parts = rest.split("\\s+", 2)
  if parts.length < 2 then
    System.err.println("Usage: :unmount METHOD /path")
    return
  val method = parts(0).toUpperCase
  val path   = parts(1)
  if Routes.remove(method, path) then
    System.err.println(s"Unmounted: $method $path")
  else
    System.err.println(s"Not mounted: $method $path")

/** Handle `:routes` in the REPL.
 *
 *  Prints a formatted table of all registered routes:
 *    method (padded to 6) | path (padded to longest+2) | source | ctx
 *
 *  If no routes are registered: prints `(no routes registered)`. */
def replHandleRoutes(): Unit =
  import scalascript.server.Routes
  import scalascript.interpreter.Value
  val entries = Routes.all
  if entries.isEmpty then
    System.err.println("(no routes registered)")
  else
    val pathWidth = (entries.map(_.path.length) :+ 4).max + 2
    entries.foreach { e =>
      val method  = e.method.padTo(6, ' ')
      val path    = e.path.padTo(pathWidth, ' ')
      val src     = e.source match
        case None       => "<inline>"
        case Some(abs)  =>
          // Try to make it relative to CWD; fall back to basename only
          val cwd = java.nio.file.Paths.get("").toAbsolutePath
          try
            val rel = cwd.relativize(java.nio.file.Paths.get(abs)).toString
            if rel.length < abs.length then rel else java.nio.file.Paths.get(abs).getFileName.toString
          catch case _: Throwable => java.nio.file.Paths.get(abs).getFileName.toString
      val ctxStr  = if e.mountCtx.nonEmpty then
        "  {" + e.mountCtx.map((k, v) => s"$k=${Value.show(v)}").mkString(", ") + "}"
      else ""
      System.err.println(s"  $method $path $src$ctxStr")
    }

/** Parse `-H "Key: Value"` flags and body tokens from REPL `:http`/`:call` args.
 *
 *  Returns `(headers: Map[String,String], bodyTokens: List[String])`.
 *  Tokens following `-H` (each must be a single "Key: Value" string) are
 *  consumed as headers; all remaining tokens are joined as the body. */
private def parseHttpArgs(tokens: List[String]): (Map[String, String], String) =
  val headers = scala.collection.mutable.LinkedHashMap.empty[String, String]
  val body    = scala.collection.mutable.ListBuffer.empty[String]
  var i = 0
  val arr = tokens.toArray
  while i < arr.length do
    if arr(i) == "-H" && i + 1 < arr.length then
      val hv = arr(i + 1)
      val colon = hv.indexOf(':')
      if colon > 0 then
        headers(hv.take(colon).trim) = hv.drop(colon + 1).trim
      i += 2
    else
      body += arr(i)
      i += 1
  (headers.toMap, body.mkString(" "))

/** Map an HTTP numeric status code to its standard reason phrase. */
private def httpStatusText(status: Int): String = status match
  case 100 => "Continue"
  case 101 => "Switching Protocols"
  case 200 => "OK"
  case 201 => "Created"
  case 202 => "Accepted"
  case 204 => "No Content"
  case 206 => "Partial Content"
  case 301 => "Moved Permanently"
  case 302 => "Found"
  case 303 => "See Other"
  case 304 => "Not Modified"
  case 307 => "Temporary Redirect"
  case 308 => "Permanent Redirect"
  case 400 => "Bad Request"
  case 401 => "Unauthorized"
  case 403 => "Forbidden"
  case 404 => "Not Found"
  case 405 => "Method Not Allowed"
  case 409 => "Conflict"
  case 410 => "Gone"
  case 422 => "Unprocessable Entity"
  case 429 => "Too Many Requests"
  case 500 => "Internal Server Error"
  case 501 => "Not Implemented"
  case 502 => "Bad Gateway"
  case 503 => "Service Unavailable"
  case _   => ""

/** Print a `:http` / `:call` response line in the format:
 *  {{{
 *  → 200 OK  text/plain
 *  pong
 *  }}}
 */
private def printHttpResponse(status: Int, contentType: String, body: String): Unit =
  val reason = httpStatusText(status)
  val statusLine = if reason.nonEmpty then s"→ $status $reason" else s"→ $status"
  val ctLine = if contentType.nonEmpty then s"$statusLine  $contentType" else statusLine
  System.err.println(ctLine)
  if body.nonEmpty then System.err.println(body)

/** Handle `:http METHOD /path [body] [-H "Key: Value" ...]` in the REPL.
 *
 *  Sends a real HTTP/1.1 request over a raw `java.net.Socket` to
 *  `localhost:<port>`.  Requires a server started with `:serve`. */
def replHandleHttp(
    cmd:        String,
    serverPort: java.util.concurrent.atomic.AtomicReference[Option[Int]]
): Unit =
  serverPort.get() match
    case None =>
      System.err.println("No server running. Use :serve [port] first.")
    case Some(port) =>
      val rest = cmd.stripPrefix(":http").trim
      val tokens = splitRespectingQuotes(rest)
      if tokens.length < 2 then
        System.err.println("Usage: :http METHOD /path [body] [-H \"Key: Value\"] ...")
        return
      val method = tokens(0).toUpperCase
      val path   = tokens(1)
      val (headers, body) = parseHttpArgs(tokens.drop(2))
      try
        val sock  = java.net.Socket("localhost", port)
        try
          val out  = sock.getOutputStream
          val bodyBytes = body.getBytes("UTF-8")
          val sb = new StringBuilder
          sb.append(s"$method $path HTTP/1.1\r\n")
          sb.append(s"Host: localhost\r\n")
          if bodyBytes.nonEmpty then
            sb.append(s"Content-Length: ${bodyBytes.length}\r\n")
          headers.foreach { case (k, v) => sb.append(s"$k: $v\r\n") }
          sb.append("\r\n")
          out.write(sb.toString.getBytes("UTF-8"))
          if bodyBytes.nonEmpty then out.write(bodyBytes)
          out.flush()

          // Read response
          val in = sock.getInputStream
          val response = readHttpResponse(in)
          val (status, contentType, respBody) = response
          printHttpResponse(status, contentType, respBody)
        finally
          sock.close()
      catch
        case e: Exception =>
          System.err.println(s"HTTP error: ${e.getMessage}")

/** Read a minimal HTTP/1.1 response from an InputStream.
 *  Returns `(statusCode, contentType, body)`. */
private def readHttpResponse(in: java.io.InputStream): (Int, String, String) =
  var endOfHeaders = false
  val headers = scala.collection.mutable.LinkedHashMap.empty[String, String]
  var status  = 200
  var statusParsed = false
  // Read line-by-line through the header section
  val lineBytes = new java.io.ByteArrayOutputStream
  var b = in.read()
  while b != -1 && !endOfHeaders do
    if b == '\n' then
      val line = lineBytes.toString("UTF-8").stripTrailing()
      lineBytes.reset()
      if !statusParsed then
        // HTTP/1.1 200 OK
        val parts = line.split(" +", 3)
        if parts.length >= 2 then
          status = parts(1).toIntOption.getOrElse(200)
        statusParsed = true
      else if line.isEmpty then
        endOfHeaders = true
      else
        val colon = line.indexOf(':')
        if colon > 0 then
          headers(line.take(colon).trim.toLowerCase) = line.drop(colon + 1).trim
    else if b != '\r' then
      lineBytes.write(b)
    b = in.read()
  // Read body: Content-Length if present, else read until EOF
  val ct = headers.getOrElse("content-type", "")
  val contentType = ct.split(";").headOption.map(_.trim).getOrElse(ct)
  val bodyStr =
    headers.get("content-length") match
      case Some(lenStr) =>
        val len = lenStr.toIntOption.getOrElse(0)
        if len > 0 then
          val bodyArr = new Array[Byte](len)
          var total = 0
          while total < len do
            val n = in.read(bodyArr, total, len - total)
            if n < 0 then total = len else total += n
          new String(bodyArr, "UTF-8")
        else ""
      case None =>
        // No Content-Length — read until connection close
        val bodyBuf = new java.io.ByteArrayOutputStream
        val chunk = new Array[Byte](4096)
        var n = in.read(chunk)
        while n > 0 do
          bodyBuf.write(chunk, 0, n)
          n = in.read(chunk)
        bodyBuf.toString("UTF-8")
  (status, contentType, bodyStr)

/** Tokenize a string respecting double-quoted groups.
 *  `foo "bar baz" qux` → `List("foo", "bar baz", "qux")`. */
private def splitRespectingQuotes(s: String): List[String] =
  val tokens = scala.collection.mutable.ListBuffer.empty[String]
  val cur    = new StringBuilder
  var inQ    = false
  for ch <- s do
    if ch == '"' then
      inQ = !inQ
    else if ch == ' ' && !inQ then
      if cur.nonEmpty then { tokens += cur.toString(); cur.clear() }
    else
      cur += ch
  if cur.nonEmpty then tokens += cur.toString()
  tokens.toList

/** Handle `:call METHOD /path [body] [-H "Key: Value" ...]` in the REPL.
 *
 *  In-process dispatch — no network, no `:serve` needed.  Builds a synthetic
 *  `Request` value from the parsed tokens, dispatches via `Routes.matchRequest`,
 *  invokes the handler, and prints the result. */
def replHandleCall(cmd: String, @annotation.unused interp: Interpreter): Unit =
  import scalascript.server.Routes
  import scalascript.interpreter.Value
  val rest = cmd.stripPrefix(":call").trim
  val tokens = splitRespectingQuotes(rest)
  if tokens.length < 2 then
    System.err.println("Usage: :call METHOD /path [body] [-H \"Key: Value\"] ...")
    return
  val method = tokens(0).toUpperCase
  // Path may contain a query string
  val rawPath = tokens(1)
  val (pathOnly, queryStr) =
    val q = rawPath.indexOf('?')
    if q >= 0 then (rawPath.take(q), rawPath.drop(q + 1)) else (rawPath, "")
  val (headers, body) = parseHttpArgs(tokens.drop(2))
  // Parse query string into Map[Value, Value] (required by MapV)
  val query: Map[Value, Value] = queryStr.split('&').flatMap { kv =>
    val eq = kv.indexOf('=')
    if eq > 0 then
      Some((Value.StringV(kv.take(eq)): Value) -> (Value.StringV(kv.drop(eq + 1)): Value))
    else if kv.nonEmpty then
      Some((Value.StringV(kv): Value) -> (Value.EmptyStr: Value))
    else None
  }.toMap
  Routes.matchRequest(method, pathOnly) match
    case None =>
      System.err.println("→ 404 Not Found")
    case Some((entry, params)) =>
      val req = Value.InstanceV("Request", Map(
        "method"      -> Value.StringV(method),
        "path"        -> Value.StringV(pathOnly),
        "params"      -> Value.MapV(params.map((k, v) => Value.StringV(k) -> Value.StringV(v))),
        "query"       -> Value.MapV(query),
        "headers"     -> Value.MapV(headers.map((k, v) => (Value.StringV(k): Value) -> (Value.StringV(v): Value))),
        "body"        -> Value.StringV(body),
        "form"        -> Value.EmptyMap,
        "files"       -> Value.EmptyMap,
        "session"     -> Value.EmptyMap,
        "bearerToken" -> Value.NoneV,
        "jwtClaims"   -> Value.NoneV,
        "basicAuth"   -> Value.NoneV
      ))
      try
        val result = entry.interpreter.invoke(entry.handler, List(req))
        val (status, contentType, respBody) = extractCallResponse(result)
        printHttpResponse(status, contentType, respBody)
      catch
        case e: Exception =>
          System.err.println(s"→ 500 Internal Server Error")
          System.err.println(s"Error: ${e.getMessage}")

/** Extract status, content-type, and body from an invoked handler's result
 *  for `:call` (in-process dispatch, no HTTP socket). */
private def extractCallResponse(v: scalascript.interpreter.Value): (Int, String, String) =
  import scalascript.interpreter.Value
  v match
    case Value.InstanceV("Response", fields) =>
      val status = fields.get("status") match
        case Some(Value.IntV(n)) => n.toInt
        case _                   => 200
      val ct = fields.get("headers") match
        case Some(Value.MapV(m)) =>
          m.collectFirst {
            case (Value.StringV(k), Value.StringV(v)) if k.equalsIgnoreCase("content-type") => v
          }.getOrElse("").split(";").headOption.map(_.trim).getOrElse("")
        case _ => ""
      val body = fields.get("body") match
        case Some(Value.StringV(s)) => s
        case Some(other)            => Value.show(other)
        case None                   => ""
      (status, ct, body)
    case Value.StringV(s) =>
      (200, "text/plain", s)
    case Value.UnitV =>
      (204, "", "")
    case other =>
      (200, "text/plain", Value.show(other))

def replHandleBreak(cmd: String, hooks: ReplDebugHooks): Unit =
  cmd match
    case ":break clear" | ":b clear" =>
      hooks.clearAllBreakpoints()
      System.err.println("[break] all breakpoints cleared")
    case ":break list" | ":b list" =>
      val bps = hooks.listBreakpoints
      if bps.isEmpty then System.err.println("[break] no breakpoints set")
      else System.err.println(s"[break] lines: ${bps.mkString(", ")}")
    case s =>
      s.stripPrefix(":break").stripPrefix(":b").trim.toIntOption match
        case Some(n) =>
          hooks.setBreakpoint(n)
          System.err.println(s"[break] set at line $n")
        case None =>
          System.err.println("Usage: :break <N> | :break clear | :break list")

/** Run a snippet on a background thread with debug hooks active.
 *  Blocks the calling (REPL main) thread until execution completes or the user quits. */
def runReplSnippetDebug(code: String, interp: Interpreter, hooks: ReplDebugHooks): Unit =
  val codeLines = code.linesIterator.toVector
  hooks.resetForNewSnippet()
  val thread = Thread.ofVirtual().start { () =>
    try   interp.runSnippet(code)
    catch case e: Exception => System.err.println(s"Error: ${e.getMessage}")
    finally hooks.signalFinished()
  }
  var inSnippet = true
  while inSnippet do
    val item = hooks.stoppedQueue.take()
    item match
      case None        => inSnippet = false
      case Some(frame) =>
        replPrintStop(frame, codeLines, hooks.blockDocLine)
        inSnippet = replDebugSubLoop(frame, thread, interp, hooks)
  thread.join()
  hooks.clearStepMode()

/** Interactive `(debug) ` sub-loop for one stop.
 *  Returns true = snippet still running (user resumed), false = user quit. */
def replDebugSubLoop(
    frame:  scalascript.interpreter.debug.DebugFrame,
    thread: Thread,
    interp: Interpreter,
    hooks:  ReplDebugHooks
): Boolean =
  import scala.io.StdIn
  import ReplDebugHooks.StepMode
  var resume    = false
  var keepGoing = true
  while !resume do
    Option(StdIn.readLine("(debug) ")) match
      case None | Some(":quit" | ":q") =>
        thread.interrupt()
        keepGoing = false
        resume    = true
      case Some(":continue" | ":c") =>
        hooks.resume(StepMode.Off)
        resume = true
      case Some(":next" | ":n") =>
        hooks.resume(StepMode.StepOver(frame.callDepth))
        resume = true
      case Some(":step" | ":s") =>
        hooks.resume(StepMode.StepIn)
        resume = true
      case Some(":out") =>
        hooks.resume(StepMode.StepOut(frame.callDepth))
        resume = true
      case Some(":locals" | ":l") =>
        replPrintLocals(frame)
      case Some(":stack" | ":bt") =>
        replPrintCallStack(frame, hooks.blockDocLine)
      case Some(s) if s.startsWith(":print ") =>
        replEvalPrint(s.drop(7).trim, frame, interp)
      case Some(":help" | ":h") =>
        replPrintDebugHelp()
      case Some("") => ()
      case Some(other) =>
        System.err.println(s"  Unknown: $other  (:help for commands)")
  keepGoing

def replPrintStop(
    frame:        scalascript.interpreter.debug.DebugFrame,
    codeLines:    Vector[String],
    blockDocLine: Int
): Unit =
  val snippetLine = frame.line - blockDocLine
  val lineText    = codeLines.lift(snippetLine - 1).getOrElse("???")
  System.err.println(s"[stopped] at line $snippetLine")
  System.err.println(s"  > $lineText")

def replPrintLocals(frame: scalascript.interpreter.debug.DebugFrame): Unit =
  val visible = frame.locals
    .filter { case (k, v) =>
      !k.startsWith("_") && !k.startsWith("$") &&
      !v.isInstanceOf[scalascript.interpreter.Value.NativeFnV]
    }
    .toList.sortBy(_._1)
  if visible.isEmpty then System.err.println("  (no locals)")
  else visible.foreach { case (n, v) =>
    System.err.println(s"  $n = ${scalascript.interpreter.Value.show(v)}")
  }

def replPrintCallStack(
    frame:        scalascript.interpreter.debug.DebugFrame,
    blockDocLine: Int
): Unit =
  val snippetLine = frame.line - blockDocLine
  System.err.println(s"  [0] ${frame.name} : line $snippetLine")
  frame.callFrames.reverseIterator.zipWithIndex.foreach { case (cf, i) =>
    val cfLine = cf.line - blockDocLine
    System.err.println(s"  [${i + 1}] ${cf.name} : line $cfLine")
  }

def replEvalPrint(
    exprSrc: String,
    frame:   scalascript.interpreter.debug.DebugFrame,
    interp:  Interpreter
): Unit =
  try
    val v = interp.evalExpr(exprSrc, frame.locals)
    System.err.println(s"  = ${scalascript.interpreter.Value.show(v)}")
  catch case e: Exception =>
    System.err.println(s"  Error: ${e.getMessage}")

def replPrintDebugHelp(): Unit =
  System.err.println(
    """|  Debug commands:
       |    :continue | :c      — resume to next breakpoint or end
       |    :next     | :n      — step over to next line
       |    :step     | :s      — step into next expression
       |    :out               — step out of current function
       |    :locals   | :l      — show local variables
       |    :stack    | :bt     — show call stack
       |    :print <expr>      — evaluate expression in current context
       |    :quit     | :q      — stop and return to REPL""".stripMargin
  )
