package scalascript.compiler.plugin.request

import org.scalatest.funsuite.AnyFunSuite
import scalascript.testkit.TestInterpreter

class RequestPluginInterpreterTest extends AnyFunSuite:

  private def interp: TestInterpreter =
    TestInterpreter(List(RequestInterpreterPlugin()))

  test("Request plugin reads required and optional fields in isolation"):
    val result = interp.eval(
      """
      case class Req(form: Map[String, String], query: Map[String, String])
      val req = Req(
        Map("name" -> "Ada", "age" -> "37", "active" -> "yes"),
        Map("score" -> "9.5")
      )
      val name = requireString(req, "name")
      val age = requireInt(req, "age")
      val active = requireBool(req, "active")
      val score = optionalDouble(req, "score").get
      val missing = optionalString(req, "missing").isEmpty
      s"$name:$age:$active:$score:$missing"
      """
    )

    assert(result == "Ada:37:true:9.5:true")

  test("Request plugin validates range and one-of helpers in isolation"):
    val result = interp.eval(
      """
      case class Req(form: Map[String, String], query: Map[String, String])
      val req = Req(Map("age" -> "42", "role" -> "admin"), Map())
      val age = requireRange(req, "age", 18, 65)
      val role = requireOneOf(req, "role", List("user", "admin"))
      role + ":" + age
      """
    )

    assert(result == "admin:42")
