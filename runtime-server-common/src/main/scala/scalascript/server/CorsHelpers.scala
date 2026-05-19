package scalascript.server

import com.sun.net.httpserver.HttpExchange

/** Apply CORS response headers to a `HttpExchange` given the current
 *  CORS configuration.  Same behaviour on the interpreter side
 *  (`WebServer.applyCorsHeaders`) and the codegen-emitted side
 *  (`serveRuntime._applyCors`): if `origins` is non-empty and the
 *  request's `Origin` is allowed, sets `Access-Control-Allow-Origin` +
 *  the configured methods / headers and a `Vary: Origin` cache hint. */
object CorsHelpers:

  def apply(
      ex:      HttpExchange,
      origins: List[String],
      methods: List[String],
      headers: List[String]
  ): Unit =
    if origins.nonEmpty then
      val origin  = Option(ex.getRequestHeaders.getFirst("Origin")).getOrElse("")
      val allowed =
        if origins.contains("*")         then "*"
        else if origins.contains(origin) then origin
        else                                  ""
      if allowed.nonEmpty then
        ex.getResponseHeaders.add("Access-Control-Allow-Origin", allowed)
        if methods.nonEmpty then
          ex.getResponseHeaders.add("Access-Control-Allow-Methods", methods.mkString(", "))
        if headers.nonEmpty then
          ex.getResponseHeaders.add("Access-Control-Allow-Headers", headers.mkString(", "))
        ex.getResponseHeaders.add("Vary", "Origin")
