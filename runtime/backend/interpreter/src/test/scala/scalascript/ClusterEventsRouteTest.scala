package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.{Interpreter, Value}
import scalascript.parser.Parser

/** Smoke test for `GET /_ssc-cluster/events`: drives a few events
 *  through the local node (LeaderElected via self-claim, DrainStateChanged
 *  via setDraining), then asks the route for the ring-buffer JSON
 *  and asserts both events show up. */
class ClusterEventsRouteTest extends AnyFunSuite with Matchers:

  test("events ring buffer captures LeaderElected + DrainStateChanged"):
    scalascript.server.Routes.clear()
    val src = """# T
```scalascript
runActors {
  startNode("solo-events", "ws://127.0.0.1:0/_ssc-actors")
  electLeader()
  setDraining(true)
}
```"""
    val buf = java.io.ByteArrayOutputStream()
    Interpreter(java.io.PrintStream(buf)).run(Parser.parse(src))

    val Some((e, _)) = scalascript.server.Routes.matchRequest("GET", "/_ssc-cluster/events"): @unchecked
    val resp = e.interpreter.invoke(e.handler, List(Value.InstanceV("Request", Map.empty)))
    val (status, body) = resp match
      case Value.InstanceV("Response", fields) =>
        val st = fields.get("status").collect { case Value.IntV(n) => n.toInt }.getOrElse(0)
        val bd = fields.get("body").collect   { case Value.StringV(s) => s }.getOrElse("")
        (st, bd)
      case other => fail(s"handler did not return a Response: $other")

    status shouldBe 200
    body should startWith ("[")
    body should endWith ("]")
    body should include ("\"type\":\"LeaderElected\"")
    body should include ("\"nodeId\":\"solo-events\"")
    body should include ("\"type\":\"LeaderLost\"")     // step-down from setDraining
    body should include ("\"type\":\"DrainStateChanged\"")
    body should include ("\"draining\":true")

  test("?since=<ts> filter drops older entries"):
    scalascript.server.Routes.clear()
    val src = """# T
```scalascript
runActors {
  startNode("solo-filter", "ws://127.0.0.1:0/_ssc-actors")
  electLeader()
}
```"""
    val buf = java.io.ByteArrayOutputStream()
    Interpreter(java.io.PrintStream(buf)).run(Parser.parse(src))

    val Some((e, _)) = scalascript.server.Routes.matchRequest("GET", "/_ssc-cluster/events"): @unchecked
    // since = far-future epoch ms ⇒ zero matches
    val futureReq = Value.InstanceV("Request", Map(
      "query" -> Value.MapV(Map(
        Value.StringV("since") -> Value.StringV("9999999999999")
      ))
    ))
    val resp = e.interpreter.invoke(e.handler, List(futureReq))
    val body = resp match
      case Value.InstanceV("Response", fs) =>
        fs.get("body").collect { case Value.StringV(s) => s }.getOrElse("")
      case _ => ""
    body shouldBe "[]"
