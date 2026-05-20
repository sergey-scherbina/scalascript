package scalascript.server

import com.sun.net.httpserver.HttpExchange

/** Serialise a [[Response]] POJO onto a JDK `HttpExchange`, applying the
 *  same headers / CORS / session-cookie / ETag-304 / gzip logic on every
 *  backend.  Each side (interpreter `WebServer.writeResponse`, codegen
 *  `serveRuntime._writeResponse`) passes its own per-server config in
 *  via the [[ResponseWriter.Config]] record so the writer itself stays
 *  pure. */
object ResponseWriter:

  /** Per-server config the writer needs to apply CORS, drive the
   *  opt-in server-side session store, and decide whether to gzip
   *  the body.  Built once per server start and reused per request. */
  case class Config(
      corsOrigins:        List[String]                  = Nil,
      corsMethods:        List[String]                  = Nil,
      corsHeaders:        List[String]                  = Nil,
      gzipEnabled:        Boolean                       = false,
      sessionStoreEnabled: Boolean                      = false,
      sessionStoreDelete:  String => Unit               = _ => (),
      sessionStorePut:     Map[String, String] => String = _ => "",
      buildSetCookie:      Map[String, String] => String =
        (p: Map[String, String]) => SessionCookie.toSetCookie(p, secureFlag = false)
  )

  def write(
      ex:               HttpExchange,
      r:                Response,
      rawCookieSession: Map[String, String],
      cfg:              Config
  ): Unit =
    r.headers.foreach((k, v) => ex.getResponseHeaders.add(k, v))
    if !r.headers.contains("Content-Type") then
      ex.getResponseHeaders.add("Content-Type", "text/plain; charset=utf-8")
    CorsHelpers(ex, cfg.corsOrigins, cfg.corsMethods, cfg.corsHeaders)
    r.setSession.foreach { payload =>
      val cookiePayload: Map[String, String] =
        if !cfg.sessionStoreEnabled then payload
        else if payload.isEmpty then
          // clearSession: also evict from the store so a stolen cookie
          // stops working server-side, not just client-side.
          rawCookieSession.get("_ssid").foreach(cfg.sessionStoreDelete)
          Map.empty
        else
          // Replace any prior SSID so refresh-on-rotate is implicit;
          // free the old slot so the store doesn't accumulate dead
          // entries on every withSession call.
          rawCookieSession.get("_ssid").foreach(cfg.sessionStoreDelete)
          val ssid = cfg.sessionStorePut(payload)
          Map("_ssid" -> ssid)
      ex.getResponseHeaders.add("Set-Cookie", cfg.buildSetCookie(cookiePayload))
    }
    // 304 short-circuit when client's ETag matches.
    val responseEtag = r.headers.getOrElse("ETag", r.headers.getOrElse("etag", ""))
    val ifNoneMatch  = Option(ex.getRequestHeaders.getFirst("If-None-Match")).getOrElse("")
    val etagUnquoted = ifNoneMatch.stripPrefix("\"").stripSuffix("\"")
    if responseEtag.nonEmpty && ifNoneMatch.nonEmpty &&
       (responseEtag == ifNoneMatch || responseEtag == etagUnquoted ||
        s""""$responseEtag"""" == ifNoneMatch) then
      ex.sendResponseHeaders(304, -1L)
    else
      val rawBytes     = r.body.getBytes("UTF-8")
      val acceptGzip   = Option(ex.getRequestHeaders.getFirst("Accept-Encoding")).getOrElse("").contains("gzip")
      val contentType  = Option(ex.getResponseHeaders.getFirst("Content-Type")).getOrElse("")
      val compressible = contentType.startsWith("text/") || contentType.contains("json") || contentType.contains("javascript")
      val bytes =
        if cfg.gzipEnabled && acceptGzip && compressible && rawBytes.nonEmpty then
          val baos = new java.io.ByteArrayOutputStream()
          val gz   = new java.util.zip.GZIPOutputStream(baos)
          gz.write(rawBytes); gz.finish()
          ex.getResponseHeaders.add("Content-Encoding", "gzip")
          baos.toByteArray
        else rawBytes
      ex.sendResponseHeaders(r.status, if bytes.isEmpty then -1L else bytes.length.toLong)
      if bytes.nonEmpty then ex.getResponseBody.write(bytes)
