package scalascript.server

import scalascript.backend.spi.{BackendRequest, BackendResponse, BackendTransport}
import scalascript.server.spi.HttpResult

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import scala.concurrent.Future

/** In-process BackendTransport for interpreter-backed route tests and future
 *  monolithic full-stack runtimes. It dispatches through the same
 *  InterpreterHttpHandler path as the HTTP server, but never opens a socket. */
final class InProcessBackendTransport(
    log: java.io.PrintStream = System.err
) extends BackendTransport:

  private val directExecutor: java.util.concurrent.Executor =
    (command: Runnable) => command.run()

  private val handler = InterpreterHttpHandler(
    log              = log,
    wsExecutor       = directExecutor,
    wsRoutes         = WsRoutes(),
    fallbackRenderer = _ => None,
    maxBodySizeBytes = () => Long.MaxValue,
    spoolThreshold   = () => Long.MaxValue,
    uploadDir        = () => "",
    corsOrigins      = () => Nil,
    corsMethods      = () => Nil,
    corsHeaders      = () => Nil,
    gzipEnabled      = () => false
  )

  override def request(req: BackendRequest): Future[BackendResponse] =
    val result = handler.onHttpRequest(toServerRequest(req))
    Future.successful(fromHttpResult(result))

  private def toServerRequest(req: BackendRequest): Request =
    val (pathOnly, query) = splitPathAndQuery(req.path)
    val headers = req.headers.map { case (k, v) => k.toLowerCase -> v }
    val bearer = headers.get("authorization").collect {
      case v if v.regionMatches(true, 0, "bearer ", 0, "bearer ".length) =>
        v.drop("bearer ".length)
    }
    Request(
      method      = req.method.toUpperCase,
      path        = pathOnly,
      params      = Map.empty,
      query       = query,
      headers     = headers,
      body        = String(req.body, StandardCharsets.UTF_8),
      bearerToken = bearer
    )

  private def fromHttpResult(result: HttpResult): BackendResponse =
    result match
      case HttpResult.PlainResp(resp) =>
        BackendResponse(
          status  = resp.status,
          headers = resp.headers,
          body    = resp.body.getBytes(StandardCharsets.UTF_8)
        )
      case HttpResult.Reject(status, body, contentType) =>
        BackendResponse(
          status  = status,
          headers = Map("Content-Type" -> contentType),
          body    = body.getBytes(StandardCharsets.UTF_8)
        )
      case HttpResult.StreamResp(resp) =>
        val buffered = StringBuilder()
        resp.writer(chunk => buffered.append(chunk))
        BackendResponse(
          status  = resp.status,
          headers = resp.headers,
          body    = buffered.result().getBytes(StandardCharsets.UTF_8)
        )

  private def splitPathAndQuery(rawPath: String): (String, Map[String, String]) =
    val idx = rawPath.indexOf('?')
    if idx < 0 then (rawPath, Map.empty)
    else (rawPath.take(idx), parseQuery(rawPath.drop(idx + 1)))

  private def parseQuery(raw: String): Map[String, String] =
    raw.split('&').iterator
      .filter(_.nonEmpty)
      .map { part =>
        val idx = part.indexOf('=')
        if idx >= 0 then decode(part.take(idx)) -> decode(part.drop(idx + 1))
        else decode(part) -> ""
      }
      .toMap

  private def decode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)
