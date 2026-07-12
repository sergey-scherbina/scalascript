package scalascript.server

import com.sun.net.httpserver.HttpExchange
import scala.jdk.CollectionConverters.*

/** Parse a JDK `HttpExchange` into the shared POJO [[Request]] model.
 *  Same logic on both backends (interpreter `WebServer.dispatchRoute`
 *  and codegen `serveRuntime._handle`): lowercase header keys, body
 *  size guard, body decoded both as UTF-8 (`body`) and ISO-8859-1
 *  (`bodyLatin1` — for multipart byte exactness), form / multipart
 *  parsed eagerly, signed cookie session dereferenced through an
 *  opt-in server-side store, bearer/basic auth pre-extracted, JWT
 *  claims verified via the supplied callback. */
object RequestBuilder:

  /** Thrown when the body exceeds `Config.maxBodySize`.  Callers map
   *  this to `413 Request Entity Too Large` on the wire. */
  final class BodyTooLargeError extends RuntimeException("Request Entity Too Large")

  /** M1: read the request body with a hard cap, aborting mid-stream. Unlike
   *  readAllBytes() + a post-hoc length check, this bounds a chunked/streamed
   *  body (no Content-Length) so it can't buffer unbounded and OOM the server. */
  private def readBoundedBody(is: java.io.InputStream, max: Long): Array[Byte] =
    val out = new java.io.ByteArrayOutputStream()
    val buf = new Array[Byte](8192)
    var total = 0L
    var n = is.read(buf)
    while n >= 0 do
      total += n
      if total > max then throw new BodyTooLargeError()
      out.write(buf, 0, n)
      n = is.read(buf)
    out.toByteArray

  /** Per-server config + per-call hooks needed to fully populate a
   *  Request.  Built once per server start (the callbacks close over
   *  the per-backend `SessionStore` / JWT verify implementation) and
   *  reused per request.
   *
   *  Defaults assume an unconfigured server (no body cap, no opt-in
   *  session store, no JWT verification). */
  case class Config(
      maxBodySize:        Long                                 = 16L * 1024 * 1024, // M1: 16 MB default (was unbounded)
      spoolThreshold:     Long                                 = 1024L * 1024L,
      uploadDir:          String                               = System.getProperty("java.io.tmpdir"),
      sessionStoreEnabled: Boolean                             = false,
      sessionStoreGet:     String => Option[Map[String, String]] = _ => None,
      jwtVerify:           String => Option[Map[String, String]] = _ => None
  )

  /** Build the Request together with the raw (signed) cookie session
   *  map.  Callers pass the raw session to [[ResponseWriter.Config]]
   *  so SSID rotation lines up between request and response. */
  def parse(
      ex:     HttpExchange,
      method: String,
      path:   String,
      params: Map[String, String],
      cfg:    Config = Config()
  ): (Request, Map[String, String], List[java.io.File]) =
    // Lowercase header keys for portable lookup — matches Node's
    // `req.headers` and the WS handshake convention.
    val headers: Map[String, String] =
      ex.getRequestHeaders.entrySet.iterator.asScala.flatMap { e =>
        if e.getValue.isEmpty then None
        else Some(e.getKey.toLowerCase -> e.getValue.get(0))
      }.toMap

    // Body size guard — reject before buffering when Content-Length is known.
    val clHdr = try
      Option(ex.getRequestHeaders.getFirst("Content-Length")).map(_.toLong).getOrElse(0L)
    catch case _: Throwable => 0L
    if clHdr > cfg.maxBodySize then throw new BodyTooLargeError()
    val bodyBytes = readBoundedBody(ex.getRequestBody, cfg.maxBodySize)
    val rawQuery = Option(ex.getRequestURI.getRawQuery).getOrElse("")
    parseRaw(method, path, params, HttpHelpers.parseQuery(rawQuery), headers, bodyBytes, cfg)

  /** Build the same fully populated Request from a transport-neutral raw
   *  request. Backends with their own parser use this instead of duplicating
   *  form, cookie-session and authorization semantics. */
  def parseRaw(
      method:    String,
      path:      String,
      params:    Map[String, String],
      query:     Map[String, String],
      headers:   Map[String, String],
      bodyBytes: Array[Byte],
      cfg:       Config = Config()
  ): (Request, Map[String, String], List[java.io.File]) =
    if bodyBytes.length.toLong > cfg.maxBodySize then throw new BodyTooLargeError()
    val normalizedHeaders = headers.map { case (k, v) =>
      k.toLowerCase(java.util.Locale.ROOT) -> v
    }
    val body       = new String(bodyBytes, "UTF-8")
    val bodyLatin1 = new String(bodyBytes, "ISO-8859-1")
    val contentType = normalizedHeaders.getOrElse("content-type", "")

    val ctLower = contentType.toLowerCase
    val (form, files, spooledTmps): (Map[String, String], Map[String, UploadedFile], List[java.io.File]) =
      if ctLower.startsWith("application/x-www-form-urlencoded") then
        (HttpHelpers.parseQuery(body), Map.empty[String, UploadedFile], List.empty[java.io.File])
      else if ctLower.startsWith("multipart/form-data") then
        Multipart.parse(contentType, bodyLatin1, cfg.spoolThreshold, cfg.uploadDir)
      else
        (Map.empty[String, String], Map.empty[String, UploadedFile], List.empty[java.io.File])

    val cookieHeader = normalizedHeaders.getOrElse("cookie", "")
    val rawCookieSession =
      if cookieHeader.isEmpty then Map.empty[String, String]
      else SessionCookie.fromHeader(cookieHeader).getOrElse(Map.empty)
    // Generic cookie map for handler convenience (parallels the WS-side
    // `ws.request.cookies`).  Separate from the signed `session` map.
    val cookies: Map[String, String] = HttpHelpers.parseCookieHeader(cookieHeader)
    val session: Map[String, String] =
      if cfg.sessionStoreEnabled then
        rawCookieSession.get("_ssid").flatMap(cfg.sessionStoreGet).getOrElse(Map.empty)
      else rawCookieSession

    val authHeader = normalizedHeaders.getOrElse("authorization", "")
    val bearer    = Jwt.fromAuthHeader(authHeader)
    val claims    = bearer.flatMap(cfg.jwtVerify)
    val basicAuth = BasicAuth.fromHeader(authHeader)

    val req = Request(
      method      = method,
      path        = path,
      params      = params,
      query       = query,
      headers     = normalizedHeaders,
      body        = body,
      form        = form,
      files       = files,
      session     = session,
      bearerToken = bearer,
      jwtClaims   = claims,
      basicAuth   = basicAuth,
      cookies     = cookies
    )
    (req, rawCookieSession, spooledTmps)
