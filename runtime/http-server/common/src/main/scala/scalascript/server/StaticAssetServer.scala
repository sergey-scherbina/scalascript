package scalascript.server

import com.sun.net.httpserver.HttpExchange

/** Static-asset server — resolves a URL path against a filesystem root,
 *  guards against `..` traversal via canonical-path checks, refuses to
 *  serve `.ssc` source files (those belong to route dispatch / the
 *  `.ssc` page renderer), and writes the file bytes with a sniffed
 *  `Content-Type`.  Shared between the interpreter's `WebServer.handle`
 *  and the codegen-emitted `serveRuntime._handle` so they pick up the
 *  same security guard. */
object StaticAssetServer:

  /** Resolve `urlPath` against `root`.  Returns `Some(file)` only when
   *  the file exists, is a regular file, lives inside `root`, and isn't
   *  a `.ssc` source.  Pure — no IO beyond `File.getCanonicalFile`. */
  def resolve(root: String, urlPath: String): Option[java.io.File] =
    val cleaned   = urlPath.stripPrefix("/")
    val effective = if cleaned.isEmpty then "index.html" else cleaned
    val rootDir   = new java.io.File(root).getCanonicalFile
    val target    = new java.io.File(rootDir, effective).getCanonicalFile
    if !target.exists() || !target.isFile() then None
    else if !target.getPath.startsWith(rootDir.getPath) then None
    else if target.getName.endsWith(".ssc") then None
    else Some(target)

  /** Write `file` to the exchange with `Content-Type` from `HttpHelpers.contentTypeFor`. */
  def serve(file: java.io.File, ex: HttpExchange): Unit =
    val bytes = java.nio.file.Files.readAllBytes(file.toPath)
    ex.getResponseHeaders.add("Content-Type", HttpHelpers.contentTypeFor(file.getName))
    ex.sendResponseHeaders(200, bytes.length.toLong)
    ex.getResponseBody.write(bytes)

  /** Convenience: resolve + serve in one call.  Returns `Some(())` on
   *  hit, `None` on miss so callers can fall through to `.ssc`
   *  rendering / 404.  Used by JvmGen which only ever serves from the
   *  CWD; the interpreter handles `.ssc` pages between resolve and
   *  serve so it calls `resolve` + `serve` separately. */
  def tryServe(ex: HttpExchange, urlPath: String, root: String = "."): Option[Unit] =
    resolve(root, urlPath).map { f => serve(f, ex); () }
