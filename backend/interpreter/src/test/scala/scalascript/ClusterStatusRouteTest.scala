package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.{Interpreter, Value}
import scalascript.parser.Parser

/** Smoke test for `GET /_ssc-cluster/status`: the route is registered
 *  automatically on `startNode`, returns 200 + JSON, and reflects this
 *  node's live view of leadership and drain state.  No real peer needed
 *  — we exercise the local-state branches only. */
class ClusterStatusRouteTest extends AnyFunSuite with Matchers:

  test("startNode registers /_ssc-cluster/status returning a JSON snapshot"):
    // Fresh route table each test — Routes.clear() so re-runs in one JVM
    // don't see stale entries.
    scalascript.server.Routes.clear()

    val src = """# T
```scalascript
runActors {
  startNode("node-status-test", "ws://127.0.0.1:0/_ssc-actors")
  electLeader()
  setDraining(true)
}
```"""
    val buf = java.io.ByteArrayOutputStream()
    Interpreter(java.io.PrintStream(buf)).run(Parser.parse(src))

    val entry = scalascript.server.Routes.matchRequest("GET", "/_ssc-cluster/status")
    assert(entry.nonEmpty, "GET /_ssc-cluster/status not registered")
    val (e, _) = entry.get
    // Invoke the handler with an empty Request — the handler ignores
    // it, so any value-shape stand-in works.
    val emptyReq = Value.InstanceV("Request", Map.empty)
    val resp = e.interpreter.invoke(e.handler, List(emptyReq))
    val (status, body) = resp match
      case Value.InstanceV("Response", fields) =>
        val st = fields.get("status").collect { case Value.IntV(n) => n.toInt }.getOrElse(0)
        val bd = fields.get("body").collect   { case Value.StringV(s) => s }.getOrElse("")
        (st, bd)
      case other => fail(s"handler did not return a Response: $other")

    status shouldBe 200
    body should include ("\"nodeId\":\"node-status-test\"")
    // Leader was self-claimed in electLeader (no higher peers), then
    // stepped down by setDraining(true) — the post-drain view is
    // exactly the empty-leader case, which is what we want documented.
    body should include ("\"leader\":\"\"")
    body should include ("\"protocol\":\"bully\"")
    body should include ("\"members\":[]")
    body should include ("\"drainingSelf\":true")
    body should include ("\"drainingPeers\":[]")
    body should include ("\"raftTerm\":0")
    body should include ("\"raftState\":\"follower\"")

  test("/_ssc-cluster/status shows leader=self when not draining"):
    scalascript.server.Routes.clear()
    val src = """# T
```scalascript
runActors {
  startNode("node-elect-test", "ws://127.0.0.1:0/_ssc-actors")
  electLeader()
}
```"""
    val buf = java.io.ByteArrayOutputStream()
    Interpreter(java.io.PrintStream(buf)).run(Parser.parse(src))

    val Some((e, _)) = scalascript.server.Routes.matchRequest("GET", "/_ssc-cluster/status"): @unchecked
    val resp = e.interpreter.invoke(e.handler, List(Value.InstanceV("Request", Map.empty)))
    val body = resp match
      case Value.InstanceV("Response", fields) =>
        fields.get("body").collect { case Value.StringV(s) => s }.getOrElse("")
      case _ => ""
    body should include ("\"leader\":\"node-elect-test\"")
    body should include ("\"drainingSelf\":false")
