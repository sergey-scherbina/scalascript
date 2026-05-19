package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.{Interpreter, Value}
import scalascript.parser.Parser

/** Smoke test for `GET /_ssc-cluster/metrics-prom` — verifies the
 *  Prometheus text exposition output covers every recorded metric +
 *  per-node entry, with the proper `# TYPE` declarations and label
 *  syntax. */
class ClusterMetricsPromTest extends AnyFunSuite with Matchers:

  test("startNode registers /_ssc-cluster/metrics-prom returning Prom text") {
    scalascript.server.Routes.clear()
    scalascript.server.WsRoutes.clear()
    val src = """# T
```scalascript
runActors {
  startNode("solo-prom", "ws://127.0.0.1:0/_ssc-actors")
  // Two gauges, set on the local node.  No peers ⇒ each appears once.
  clusterMetricSet("requests_per_second", 42.0)
  clusterMetricSet("queue_depth", 7.0)
}
```"""
    val buf = java.io.ByteArrayOutputStream()
    Interpreter(java.io.PrintStream(buf)).run(Parser.parse(src))

    val Some((e, _)) = scalascript.server.Routes
      .matchRequest("GET", "/_ssc-cluster/metrics-prom"): @unchecked
    val resp = e.interpreter.invoke(e.handler, List(Value.InstanceV("Request", Map.empty)))
    val (status, body, ct) = resp match
      case Value.InstanceV("Response", fs) =>
        val st = fs.get("status").collect { case Value.IntV(n) => n.toInt }.getOrElse(0)
        val bd = fs.get("body").collect   { case Value.StringV(s) => s }.getOrElse("")
        val ctt = fs.get("headers").collect {
          case Value.MapV(m) =>
            m.collectFirst { case (Value.StringV("Content-Type"), Value.StringV(v)) => v }
              .getOrElse("")
        }.getOrElse("")
        (st, bd, ctt)
      case other => fail(s"not a Response: $other")
    info(s"body:\n$body")
    status shouldBe 200
    ct should include ("text/plain")
    ct should include ("version=0.0.4")
    body should include ("# TYPE requests_per_second gauge")
    body should include ("requests_per_second{nodeId=\"solo-prom\"} 42.0")
    body should include ("# TYPE queue_depth gauge")
    body should include ("queue_depth{nodeId=\"solo-prom\"} 7.0")
  }

  test("metric names with illegal chars get sanitized") {
    scalascript.server.Routes.clear()
    scalascript.server.WsRoutes.clear()
    val src = """# T
```scalascript
runActors {
  startNode("solo-sanitize", "ws://127.0.0.1:0/_ssc-actors")
  // Dots, dashes, slashes — all illegal in Prom metric names ⇒
  // replaced with `_`.
  clusterMetricSet("api.latency-p99/ms", 12.3)
}
```"""
    val buf = java.io.ByteArrayOutputStream()
    Interpreter(java.io.PrintStream(buf)).run(Parser.parse(src))
    val Some((e, _)) = scalascript.server.Routes
      .matchRequest("GET", "/_ssc-cluster/metrics-prom"): @unchecked
    val resp = e.interpreter.invoke(e.handler, List(Value.InstanceV("Request", Map.empty)))
    val body = resp match
      case Value.InstanceV("Response", fs) =>
        fs.get("body").collect { case Value.StringV(s) => s }.getOrElse("")
      case _ => ""
    info(s"body:\n$body")
    body should include ("api_latency_p99_ms{nodeId=\"solo-sanitize\"} 12.3")
  }

  test("token-gated 401 when SSC_CLUSTER_TOKEN is set") {
    scalascript.server.Routes.clear()
    scalascript.server.WsRoutes.clear()
    val src = """# T
```scalascript
runActors {
  startNode("solo-auth-prom", "ws://127.0.0.1:0/_ssc-actors")
  setClusterAuthToken("s3cret")
}
```"""
    val buf = java.io.ByteArrayOutputStream()
    Interpreter(java.io.PrintStream(buf)).run(Parser.parse(src))
    val Some((e, _)) = scalascript.server.Routes
      .matchRequest("GET", "/_ssc-cluster/metrics-prom"): @unchecked
    val resp = e.interpreter.invoke(e.handler, List(Value.InstanceV("Request", Map.empty)))
    val status = resp match
      case Value.InstanceV("Response", fs) =>
        fs.get("status").collect { case Value.IntV(n) => n.toInt }.getOrElse(0)
      case _ => 0
    status shouldBe 401
  }
