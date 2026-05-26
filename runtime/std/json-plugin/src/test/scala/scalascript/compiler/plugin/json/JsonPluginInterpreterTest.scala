package scalascript.compiler.plugin.json

import org.scalatest.funsuite.AnyFunSuite
import scalascript.testkit.TestInterpreter

class JsonPluginInterpreterTest extends AnyFunSuite:

  private def interp: TestInterpreter =
    TestInterpreter(List(JsonInterpreterPlugin()))

  test("JSON plugin reads object fields through JsonValue wrapper in isolation"):
    val result = interp.eval(
      """
      val parsed = jsonRead("{\"name\":\"Ada\",\"scores\":[1,2,3],\"active\":true}")
      parsed("name").asString + ":" + parsed("scores")(1).asInt + ":" + parsed("active").asBool
      """
    )

    assert(result == "Ada:2:true")

  test("JSON plugin parses, looks up, and stringifies values in isolation"):
    val result = interp.eval(
      """
      val raw = jsonParse("{\"name\":\"Ada\",\"age\":37}")
      val name = lookup(raw, "name")
      val age = lookup(raw, "age")
      val missing = lookupOpt(raw, "missing").isEmpty
      jsonStringify(List(name, age, missing))
      """
    )

    assert(result == """["Ada",37,true]""")
