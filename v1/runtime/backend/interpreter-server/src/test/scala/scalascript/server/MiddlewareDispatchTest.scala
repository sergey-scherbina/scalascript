package scalascript.server

import java.io.{ByteArrayOutputStream, PrintStream}
import org.scalatest.funsuite.AnyFunSuite
import scalascript.compiler.plugin.http.HttpInterpreterPlugin
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser
import scalascript.server.spi.HttpResult

/** specs/middleware-before-routing.md — a global `use { (req, next) => … }`
 *  middleware must run for UNROUTED paths too, so it can short-circuit them
 *  (health probe, blanket auth) instead of being skipped by an early 404.
 *  Matched routes and the no-middleware fast path are unchanged. */
class MiddlewareDispatchTest extends AnyFunSuite:

  private def sink: PrintStream = new PrintStream(new ByteArrayOutputStream())

  private def handlerFor(program: String): InterpreterHttpHandler =
    Routes.clear()
    val interp = Interpreter(out = sink)
    interp.installPlugins(List(HttpInterpreterPlugin()))
    interp.run(Parser.parse(s"# Test\n\n```scala\n$program\n```\n"))
    new InterpreterHttpHandler(
      log              = sink,
      wsExecutor       = (r: Runnable) => r.run(),
      routeRegistry    = interp.routeRegistry,
      wsRoutes         = interp.wsRoutes,
      fallbackRenderer = _ => None,
      maxBodySizeBytes = () => 0L,
      spoolThreshold   = () => 0L,
      uploadDir        = () => "",
      corsOrigins      = () => Nil,
      corsMethods      = () => Nil,
      corsHeaders      = () => Nil,
      gzipEnabled      = () => false)

  private def get(h: InterpreterHttpHandler, path: String): HttpResult =
    h.onHttpRequest(Request("GET", path, Map.empty, Map.empty, Map.empty, ""))

  private val withMiddleware =
    """route("GET", "/ping")(req => Response.text("pong"))
      |use((req, next) => if req.path == "/health" then Response.text("alive") else next())""".stripMargin

  test("global middleware intercepts an UNROUTED path (the fix)"):
    val h = handlerFor(withMiddleware)
    get(h, "/health") match
      case HttpResult.PlainResp(r) =>
        assert(r.status == 200, s"expected 200, got ${r.status}")
        assert(r.body == "alive", s"expected middleware short-circuit body, got '${r.body}'")
      case other => fail(s"expected PlainResp, got $other")

  test("middleware that calls next() on an unrouted path falls through to 404"):
    val h = handlerFor(withMiddleware)
    get(h, "/nope") match
      case HttpResult.PlainResp(r) =>
        assert(r.status == 404, s"expected 404 fall-through, got ${r.status}")
      case HttpResult.Reject(404, _, _) => () // also acceptable
      case other => fail(s"expected a 404, got $other")

  test("matched route still runs through middleware unchanged"):
    val h = handlerFor(withMiddleware)
    get(h, "/ping") match
      case HttpResult.PlainResp(r) =>
        assert(r.status == 200 && r.body == "pong", s"route handler changed: ${r.status} '${r.body}'")
      case other => fail(s"expected PlainResp, got $other")

  test("no-middleware unrouted path keeps the fast-path Reject(404)"):
    val h = handlerFor("""route("GET", "/ping")(req => Response.text("pong"))""")
    get(h, "/missing") match
      case HttpResult.Reject(404, body, _) =>
        assert(body.contains("/missing"), s"unexpected 404 body: $body")
      case other => fail(s"expected fast-path Reject(404), got $other")
