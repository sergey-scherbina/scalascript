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

  test("jsonValue total accessors: missing key / wrong shape never throw"):
    val result = interp.eval(
      """
      val j = jsonValue("{\"name\":\"Ada\",\"n\":3,\"on\":true}")
      j.get("name").asString + "|" +
      j.get("n").asInt + "|" +
      j.get("on").asBool + "|" +
      j.get("missing").asString + "|" +
      j.get("missing").isNull + "|" +
      j.get("name").asInt
      """
    )
    // name=Ada, n=3, on=true, missing→"" , isNull→true, asInt of a string→0
    assert(result == "Ada|3|true||true|0")

  test("jsonValue tolerant parse: malformed input is Null, not an exception"):
    val result = interp.eval(
      """
      val j = jsonValue("not json at all")
      "isNull=" + j.isNull + "|" + j.get("x").asString
      """
    )
    assert(result == "isNull=true|")

  test("jsonValue money: asDecimal is lossless from a JSON string"):
    val result = interp.eval(
      """
      val j = jsonValue("{\"amt\":\"1000.01\",\"bare\":250.5}")
      j.get("amt").asDecimal.toString + "|" + j.get("bare").asDecimal.toString
      """
    )
    // string "1000.01" keeps its exact text; bare number 250.5 round-trips
    assert(result == "1000.01|250.5")

  test("jsonValue asList navigates arrays of objects"):
    val result = interp.eval(
      """
      val j = jsonValue("{\"xs\":[{\"k\":\"a\"},{\"k\":\"b\"}]}")
      j.get("xs").asList.map(e => e.get("k").asString).mkString(",")
      """
    )
    assert(result == "a,b")

  test("jsonValue optDecimal distinguishes present numeric from absent"):
    val result = interp.eval(
      """
      val j = jsonValue("{\"amt\":\"9.99\"}")
      "present=" + j.get("amt").optDecimal.isDefined + "|" +
      j.get("missing").optDecimal.isDefined
      """
    )
    assert(result == "present=true|false")
