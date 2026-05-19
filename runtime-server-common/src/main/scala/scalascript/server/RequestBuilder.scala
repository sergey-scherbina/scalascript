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

  /** Per-server config + per-call hooks needed to fully populate a
   *  Request.  Built once per server start (the callbacks close over
   *  the per-backend `SessionStore` / JWT verify implementation) and
   *  reused per request.
   *
   *  Defaults assume an unconfigured server (no body cap, no opt-in
   *  session store, no JWT verification). */
  case class Config(
      maxBodySize:        Long                                 = Long.MaxValue,
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
    val bodyBytes = ex.getRequestBody.readAllBytes()
    if bodyBytes.length.toLong > cfg.maxBodySize then throw new BodyTooLargeError()

    val body       = new String(bodyBytes, "UTF-8")
    val bodyLatin1 = new String(bodyBytes, "ISO-8859-1")
    val contentType = headers.collectFirst {
      case (k, v) if k.equalsIgnoreCase("Content-Type") => v
    }.getOrElse("")

    val ctLower = contentType.toLowerCase
    val (form, files, spooledTmps): (Map[String, String], Map[String, UploadedFile], List[java.io.File]) =
      if ctLower.startsWith("application/x-www-form-urlencoded") then
        (HttpHelpers.parseQuery(body), Map.empty, Nil)
      else if ctLower.startsWith("multipart/form-data") then
        Multipart.parse(contentType, bodyLatin1, cfg.spoolThreshold, cfg.uploadDir)
      else
        (Map.empty, Map.empty, Nil)

    val cookieHeader = headers.collectFirst {
      case (k, v) if k.equalsIgnoreCase("Cookie") => v
    }.getOrElse("")
    val rawCookieSession =
      if cookieHeader.isEmpty then Map.empty[String, String]
      else SessionCookie.fromHeader(cookieHeader).getOrElse(Map.empty)
    // Generic cookie map for handler convenience (parallels the WS-side
    // `ws.request.cookies`).  Separate from the signed `session` map.
    val cookies: Map[String, String] =
      if cookieHeader.isEmpty then Map.empty
      else cookieHeader.split(';').iterator.flatMap { pair =>
        val t = pair.trim
        val i = t.indexOf('=')
        if i < 0 then None else Some(t.substring(0, i).trim -> t.substring(i + 1).trim)
      }.toMap
    val session: Map[String, String] =
      if cfg.sessionStoreEnabled then
        rawCookieSession.get("_ssid").flatMap(cfg.sessionStoreGet).getOrElse(Map.empty)
      else rawCookieSession

    val authHeader = headers.collectFirst {
      case (k, v) if k.equalsIgnoreCase("Authorization") => v
    }.getOrElse("")
    val bearer    = Jwt.fromAuthHeader(authHeader)
    val claims    = bearer.flatMap(cfg.jwtVerify)
    val basicAuth = BasicAuth.fromHeader(authHeader)

    val rawQuery = Option(ex.getRequestURI.getRawQuery).getOrElse("")
    val req = Request(
      method      = method,
      path        = path,
      params      = params,
      query       = HttpHelpers.parseQuery(rawQuery),
      headers     = headers,
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
