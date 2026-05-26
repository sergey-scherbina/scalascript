package scalascript.compiler.plugin.http

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Value
import scalascript.testkit.TestInterpreter

class HttpPluginInterpreterTest extends AnyFunSuite:

  private def interp: TestInterpreter =
    TestInterpreter(List(HttpInterpreterPlugin()))

  test("HTTP plugin builds Response values in isolation"):
    val text = interp.eval("""Response.text("hello", 201)""")
    assert(responseStatus(text) == 201L)
    assert(responseBody(text) == "hello")
    assert(responseHeader(text, "Content-Type") == Some("text/plain; charset=utf-8"))

    val json = interp.eval("""Response.json(Map("ok" -> true, "n" -> 2))""")
    assert(responseStatus(json) == 200L)
    assert(responseBody(json) == """{"ok":true,"n":2}""")
    assert(responseHeader(json, "Content-Type") == Some("application/json"))

    val redirect = interp.eval("""Response.redirect("/next")""")
    assert(responseStatus(redirect) == 302L)
    assert(responseHeader(redirect, "Location") == Some("/next"))

    val missing = interp.eval("""Response.notFound("missing")""")
    assert(responseStatus(missing) == 404L)
    assert(responseBody(missing) == "missing")

    val status = interp.eval("""Response.status(204)""")
    assert(responseStatus(status) == 204L)

  test("HTTP plugin applies cache headers and TLS helper in isolation"):
    val cached = interp.eval("""cacheable(Response.text("cached"), 60, "v1")""")
    assert(responseHeader(cached, "Cache-Control") == Some("public, max-age=60"))
    assert(responseHeader(cached, "ETag") == Some("v1"))

    val noCache = interp.eval("""noCache(Response.text("fresh"))""")
    assert(responseHeader(noCache, "Cache-Control") == Some("no-store, no-cache, must-revalidate"))

    val tls = interp.eval("""tls("cert.pem", "key.pem")""")
    tls match
      case Value.InstanceV("TlsContext", fields) =>
        assert(fields("cert") == Value.StringV("cert.pem"))
        assert(fields("key") == Value.StringV("key.pem"))
      case other => fail(s"expected TlsContext, got $other")

  test("HTTP plugin registers route handlers without opening a server"):
    val result = interp.eval(
      """
      route("GET", "/ping") { req => Response.text("pong") }
      1
      """
    )

    assert(result == 1L)

  private def responseStatus(value: Any): Long =
    responseFields(value)("status") match
      case Value.IntV(n) => n
      case other         => fail(s"expected response status int, got $other")

  private def responseBody(value: Any): String =
    responseFields(value)("body") match
      case Value.StringV(s) => s
      case other            => fail(s"expected response body string, got $other")

  private def responseHeader(value: Any, name: String): Option[String] =
    responseFields(value).get("headers") match
      case Some(Value.MapV(headers)) =>
        headers.get(Value.StringV(name)).collect { case Value.StringV(s) => s }
      case _ => None

  private def responseFields(value: Any): Map[String, Value] = value match
    case Value.InstanceV("Response", fields) => fields
    case other                               => fail(s"expected Response, got $other")
