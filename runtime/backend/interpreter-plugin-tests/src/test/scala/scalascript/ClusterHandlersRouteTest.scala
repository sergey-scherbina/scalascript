package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.{Interpreter, Value}
import scalascript.interpreter.actors.ActorsInterpreterPlugin
import scalascript.parser.Parser

/** Tests for `GET /_ssc-cluster/handlers`: registered automatically when
 *  `startNode` is called; returns a JSON array of remote handler metadata. */
class ClusterHandlersRouteTest extends AnyFunSuite with Matchers:

  test("startNode registers /_ssc-cluster/handlers returning empty JSON array when no handlers"):
    scalascript.server.Routes.clear()
    val src = """# T
```scalascript
runActors {
  startNode("node-handlers-empty", "ws://127.0.0.1:0/_ssc-actors")
}
```"""
    val buf = java.io.ByteArrayOutputStream()
    val _i = Interpreter(java.io.PrintStream(buf)); _i.installPlugins(List(new ActorsInterpreterPlugin)); _i.run(Parser.parse(src))

    val entry = scalascript.server.Routes.matchRequest("GET", "/_ssc-cluster/handlers")
    assert(entry.nonEmpty, "GET /_ssc-cluster/handlers not registered")
    val (e, _) = entry.get
    val emptyReq = Value.InstanceV("Request", Map.empty)
    val resp = e.interpreter.invoke(e.handler, List(emptyReq))
    val (status, body) = resp match
      case Value.InstanceV("Response", fields) =>
        val st = fields.get("status").collect { case Value.IntV(n) => n.toInt }.getOrElse(0)
        val bd = fields.get("body").collect   { case Value.StringV(s) => s }.getOrElse("")
        (st, bd)
      case other => fail(s"handler did not return a Response: $other")

    status shouldBe 200
    body shouldBe "[]"

  test("/_ssc-cluster/handlers returns registered remote handler metadata"):
    scalascript.server.Routes.clear()
    val src = """---
name: handlers-test
remoteHandlers:
  greet.hello:
    function: hello
    path: /rpc/hello
    request: String
    response: String
---
# T
```scalascript
def hello(name: String): String = "Hello " + name

runActors {
  startNode("node-handlers-meta", "ws://127.0.0.1:0/_ssc-actors")
}
```"""
    val buf = java.io.ByteArrayOutputStream()
    val _i = Interpreter(java.io.PrintStream(buf)); _i.installPlugins(List(new ActorsInterpreterPlugin)); _i.run(Parser.parse(src))

    val Some((e, _)) = scalascript.server.Routes.matchRequest("GET", "/_ssc-cluster/handlers"): @unchecked
    val resp = e.interpreter.invoke(e.handler, List(Value.InstanceV("Request", Map.empty)))
    val body = resp match
      case Value.InstanceV("Response", fields) =>
        fields.get("body").collect { case Value.StringV(s) => s }.getOrElse("")
      case other => fail(s"handler did not return a Response: $other")

    body should startWith ("[")
    body should endWith ("]")
    body should include ("\"name\":\"greet.hello\"")
    body should include ("\"function\":\"hello\"")
    body should include ("\"path\":\"/rpc/hello\"")
    body should include ("\"requestType\":\"String\"")
    body should include ("\"responseType\":\"String\"")
    body should include ("\"transports\":[")
    body should include ("\"http-json\"")
    body should include ("\"in-process\"")
