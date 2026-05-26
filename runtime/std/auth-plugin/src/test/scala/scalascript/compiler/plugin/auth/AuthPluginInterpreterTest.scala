package scalascript.compiler.plugin.auth

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Value
import scalascript.testkit.TestInterpreter

class AuthPluginInterpreterTest extends AnyFunSuite:

  private def interp: TestInterpreter =
    TestInterpreter(List(AuthInterpreterPlugin()))

  test("Auth plugin builds basic auth challenges in isolation"):
    val response = interp.eval("""Response.basicAuthChallenge("Members \"Only\"")""")

    assert(responseStatus(response) == 401L)
    assert(responseBody(response) == "Authentication required")
    assert(responseHeader(response, "WWW-Authenticate") == Some("""Basic realm="Members \"Only\"""""))

  test("Auth plugin encodes and decodes base64-url values in isolation"):
    val result = interp.eval(
      """
      val encoded = base64UrlEncode("hello?")
      base64UrlDecode(encoded)
      """
    )

    assert(result == "hello?")

  test("Auth plugin validates CSRF tokens in isolation"):
    val result = interp.eval(
      """
      case class Request(
        form: Map[String, String],
        headers: Map[String, String],
        session: Map[String, String]
      )

      val ok = csrfValid(Request(Map("csrf" -> "token"), Map(), Map("csrf" -> "token")))
      val headerOk = csrfValid(Request(Map(), Map("X-CSRF-Token" -> "token"), Map("csrf" -> "token")))
      val bad = csrfValid(Request(Map("csrf" -> "wrong"), Map(), Map("csrf" -> "token")))
      List(ok, headerOk, bad)
      """
    )

    assert(result == List(true, true, false))

  test("Auth plugin hashes and verifies passwords in isolation"):
    val result = interp.eval(
      """
      val hashed = hashPassword("secret", 1000)
      List(verifyPassword("secret", hashed), verifyPassword("wrong", hashed))
      """
    )

    assert(result == List(true, false))

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
