package scalascript.compiler.plugin

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8

/** A minimal **reference** HTTP server over a [[FileRegistry]] — the thing a hosted
 *  `registry.scalascript.io` runs (remote-package-registry slice 4). Dependency-free (JDK
 *  `com.sun.net.httpserver`), bound to loopback by default; intended as the reference/dev server, not a
 *  hardened public endpoint (auth + TLS are follow-ups). Routes:
 *  {{{
 *  GET  /packages.yaml                          → the client index (RegistryClient / ssc search consume this)
 *  GET  /packages/<id>/<version>.sscpkg         → artifact bytes
 *  POST /publish/<id>/<version>[?description=…]  → publish the request body
 *  }}}
 *  The GET side matches exactly what the existing `RegistryClient` fetches, so this server is drop-in for the
 *  current client. */
class RegistryHttpServer(
    registry: FileRegistry,
    baseUrl: String = "",
    host: String = "127.0.0.1",
    /** Bearer tokens accepted for `POST /publish`. When EMPTY, publish is OPEN (the reference/dev default);
     *  when non-empty, a publish must carry `Authorization: Bearer <token>` with a token in this set, else 401.
     *  (GET reads are always public.) A hosted registry sets this so not anyone can write. */
    publishTokens: Set[String] = Set.empty):
  @volatile private var server: HttpServer | Null = null
  // The base URL `packages.yaml` entries point at. If not given, derive `http://host:port` after binding
  // (so the emitted index self-references this server) — convenient for tests + a single-host deployment.
  @volatile private var boundBase: String = baseUrl

  /** Start on `port` (0 = an ephemeral port) and return the actual bound port. */
  def start(port: Int = 0): Int =
    val s = HttpServer.create(new InetSocketAddress(host, port), 0)
    s.createContext("/", (ex: HttpExchange) => handle(ex))
    s.setExecutor(null)
    s.start()
    server = s
    val bound = s.getAddress.getPort
    if boundBase.isEmpty then boundBase = s"http://$host:$bound"
    bound

  def stop(): Unit =
    val s = server
    if s != null then { s.stop(0); server = null }

  private def handle(ex: HttpExchange): Unit =
    try
      val path = ex.getRequestURI.getPath
      (ex.getRequestMethod, path) match
        case ("GET", "/packages.yaml") =>
          respondText(ex, 200, registry.exportPackagesYaml(boundBase))

        case ("GET", p) if p.startsWith("/packages/") && p.endsWith(".sscpkg") =>
          splitIdVersion(p.stripPrefix("/packages/").stripSuffix(".sscpkg")) match
            case Some((id, version)) =>
              registry.fetch(id, version) match
                case Some(bytes) => respondBytes(ex, 200, bytes)
                case None        => respondText(ex, 404, "not found")
            case None => respondText(ex, 404, "not found")

        case ("POST", p) if p.startsWith("/publish/") && !authorized(ex) =>
          ex.getResponseHeaders.add("WWW-Authenticate", "Bearer")
          respondText(ex, 401, "unauthorized: POST /publish requires a valid Bearer token")

        case ("POST", p) if p.startsWith("/publish/") =>
          splitIdVersion(p.stripPrefix("/publish/")) match
            case Some((id, version)) =>
              val body  = ex.getRequestBody.readAllBytes()
              val desc  = queryParam(ex.getRequestURI.getQuery, "description").getOrElse("")
              val entry = registry.publish(id, version, body, desc)
              registry.writePackagesYaml(boundBase)
              respondText(ex, 200, s"published ${entry.id}@${entry.version} ${entry.sha256}\n")
            case None => respondText(ex, 400, "usage: POST /publish/<id>/<version>")

        case _ => respondText(ex, 404, "not found")
    catch case e: Throwable => respondText(ex, 500, s"error: ${e.getMessage}")

  /** `<id>/<version>` where id may itself contain dots but not slashes; the LAST segment is the version. */
  private def splitIdVersion(rel: String): Option[(String, String)] =
    val idx = rel.lastIndexOf('/')
    if idx <= 0 || idx == rel.length - 1 then None
    else Some((rel.substring(0, idx), rel.substring(idx + 1)))

  /** True when publish is open (no tokens configured) or the request carries an accepted Bearer token. */
  private def authorized(ex: HttpExchange): Boolean =
    publishTokens.isEmpty || {
      val hdr   = Option(ex.getRequestHeaders.getFirst("Authorization")).getOrElse("")
      val token = if hdr.startsWith("Bearer ") then hdr.stripPrefix("Bearer ").trim else ""
      token.nonEmpty && publishTokens.contains(token)
    }

  private def queryParam(query: String | Null, key: String): Option[String] =
    Option(query).flatMap { q =>
      q.split('&').iterator.map(_.split("=", 2)).collectFirst {
        case Array(k, v) if k == key => java.net.URLDecoder.decode(v, UTF_8)
      }
    }

  private def respondText(ex: HttpExchange, code: Int, body: String): Unit =
    respondBytes(ex, code, body.getBytes(UTF_8))

  private def respondBytes(ex: HttpExchange, code: Int, body: Array[Byte]): Unit =
    ex.sendResponseHeaders(code, body.length.toLong)
    val os = ex.getResponseBody
    try os.write(body) finally os.close()
