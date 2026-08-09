package scalascript.compiler.plugin.http

import scalascript.backend.spi.*
import scalascript.backend.spi.NativeContextFeatureKeys as Keys

/** The Http effect runner, extracted from the interpreter core (core-minimization, §2d).
 *
 *  `runHttp { body }` performs real outbound requests; `runHttpStub(routes) { body }` replies from a
 *  url→body map (tests). The body's `Http.get/post/request` perform the `"Http"` effect; the handler
 *  replies with a `Response { status, headers, body }` record built via `ctx.makeRecord`. Base-url /
 *  timeout / retry config is read via `ctx.featureLocal` — set by the core `httpClient(baseUrl)` form,
 *  which stays in the interpreter (a feature-local config setter, not an effect handler). */
object HttpEffectRunner extends BlockForm:
  def effectName: String = "Http"

  def newHandler(ctx: BlockContext, args: List[SpiValue]): EffectHandler =
    // runHttpStub(routes) → args = [routes MapV]; runHttp → args = [].
    val routes: Option[Map[String, String]] = args.headOption.collect {
      case SpiValue.MapV(entries) =>
        entries.collect { case (SpiValue.StrV(k), SpiValue.StrV(v)) => k -> v }.toMap
    }
    new HttpHandler(ctx, routes)

private class HttpHandler(ctx: BlockContext, routes: Option[Map[String, String]]) extends EffectHandler:

  private def cfgStr(key: String, default: String): String =
    ctx.featureLocal(key).collect { case s: String => s }.getOrElse(default)
  private def cfgLong(key: String, default: Long): Long =
    ctx.featureLocal(key).collect { case n: Long => n }.getOrElse(default)
  private def cfgInt(key: String, default: Int): Int =
    ctx.featureLocal(key).collect { case n: Int => n }.getOrElse(default)

  private def response(status: Long, body: String, headers: List[(String, String)]): SpiValue =
    ctx.makeRecord("Response", List(
      "status"  -> SpiValue.IntV(status),
      "body"    -> SpiValue.StrV(body),
      "headers" -> SpiValue.MapV(headers.map((k, v) => SpiValue.StrV(k) -> SpiValue.StrV(v)))))

  private def stubResponse(url: String): SpiValue =
    routes.flatMap(_.get(url)) match
      case Some(v) => response(200, v, Nil)
      case None    => response(404, "", Nil)

  private def realRequest(method: String, rawUrl: String, body: String, headers: Map[String, String]): SpiValue =
    import java.net.http.{HttpClient as JHttpClient, HttpRequest, HttpResponse}
    import scala.jdk.CollectionConverters.*
    val base    = cfgStr(Keys.HttpBaseUrl, "")
    val url     = if base.nonEmpty && !rawUrl.startsWith("http") then base + rawUrl else rawUrl
    val timeout = java.time.Duration.ofMillis(cfgLong(Keys.HttpTimeoutMs, 30000L))
    val client  = JHttpClient.newBuilder().connectTimeout(timeout).build()
    val builder = HttpRequest.newBuilder().uri(java.net.URI.create(url)).timeout(timeout)
    headers.foreach((k, v) => builder.header(k, v))
    val req = method match
      case "GET"    => builder.GET().build()
      case "POST"   => builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
      case "PUT"    => builder.PUT(HttpRequest.BodyPublishers.ofString(body)).build()
      case "DELETE" => builder.DELETE().build()
      case m        => builder.method(m, HttpRequest.BodyPublishers.ofString(body)).build()
    val maxTries = cfgInt(Keys.HttpMaxRetries, 0) + 1
    val delayMs  = cfgLong(Keys.HttpRetryDelayMs, 1000L)
    var attempt  = 0
    var lastResp: Option[HttpResponse[String]] = None
    var lastErr:  Option[Throwable] = None
    while attempt < maxTries do
      try { lastResp = Some(client.send(req, HttpResponse.BodyHandlers.ofString())); lastErr = None }
      catch case e: Throwable => lastErr = Some(e)
      val shouldRetry = lastErr.isDefined || lastResp.exists(_.statusCode() >= 500)
      attempt += 1
      if shouldRetry && attempt < maxTries then Thread.sleep(delayMs) else attempt = maxTries
    lastErr.foreach(e => throw e)
    lastResp match
      case Some(resp) =>
        val hdrs = resp.headers().map().entrySet().iterator().asScala.flatMap { e =>
          if e.getValue.isEmpty then None else Some(e.getKey -> e.getValue.get(0))
        }.toList
        response(resp.statusCode().toLong, resp.body(), hdrs)
      case None => throw new RuntimeException("http: no response")

  def reply(op: String, args: List[SpiValue]): SpiValue = (op, args) match
    case ("get", List(SpiValue.StrV(url))) =>
      routes.fold(realRequest("GET", url, "", Map.empty))(_ => stubResponse(url))
    case ("post", List(SpiValue.StrV(url), SpiValue.StrV(body))) =>
      routes.fold(realRequest("POST", url, body, Map.empty))(_ => stubResponse(url))
    case ("request", List(SpiValue.StrV(method), SpiValue.StrV(url), hdrs, SpiValue.StrV(body))) =>
      val hdrMap = hdrs match
        case SpiValue.MapV(es) => es.collect { case (SpiValue.StrV(k), SpiValue.StrV(v)) => k -> v }.toMap
        case _                 => Map.empty[String, String]
      routes.fold(realRequest(method, url, body, hdrMap))(_ => stubResponse(url))
    case _ => throw new IllegalArgumentException(s"Http.$op: unsupported operation/arguments")
