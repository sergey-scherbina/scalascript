package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.{Interpreter, Value}
import scalascript.parser.Parser

/** Bearer-token gate on the cluster endpoints.  Three cases:
 *    1. Token unset (default) ⇒ routes are open, 200 on no-Authorization.
 *    2. Token set, no Authorization header ⇒ 401.
 *    3. Token set, matching Bearer ⇒ 200. */
class ClusterAuthTest extends AnyFunSuite with Matchers:

  private def runWithRoutes(src: String): Unit =
    scalascript.server.Routes.clear()
    scalascript.server.WsRoutes.clear()
    val buf = java.io.ByteArrayOutputStream()
    Interpreter(java.io.PrintStream(buf)).run(Parser.parse(src))

  private def call(method: String, path: String, headers: Map[String, String]): (Int, String) =
    val Some((e, _)) = scalascript.server.Routes.matchRequest(method, path): @unchecked
    val reqVal = Value.InstanceV("Request", Map(
      "headers" -> Value.MapV(
        headers.map((k, v) => Value.StringV(k) -> Value.StringV(v))
      )
    ))
    e.interpreter.invoke(e.handler, List(reqVal)) match
      case Value.InstanceV("Response", fs) =>
        val st = fs.get("status").collect { case Value.IntV(n) => n.toInt }.getOrElse(0)
        val bd = fs.get("body").collect   { case Value.StringV(s) => s }.getOrElse("")
        (st, bd)
      case _ => fail("not a Response")

  test("token unset: status/drain/events open to anonymous"):
    runWithRoutes("""# T
```scalascript
runActors {
  startNode("auth-open", "ws://127.0.0.1:0/_ssc-actors")
}
```""")
    val (s1, _) = call("GET", "/_ssc-cluster/status", Map.empty)
    s1 shouldBe 200
    val (s2, _) = call("GET", "/_ssc-cluster/events", Map.empty)
    s2 shouldBe 200

  test("token set, no Authorization: 401 on each endpoint"):
    runWithRoutes("""# T
```scalascript
runActors {
  startNode("auth-on", "ws://127.0.0.1:0/_ssc-actors")
  setClusterAuthToken("s3cret")
}
```""")
    val (s1, b1) = call("GET", "/_ssc-cluster/status", Map.empty)
    s1 shouldBe 401
    b1 should include ("unauthorized")
    val (s2, _) = call("GET", "/_ssc-cluster/events", Map.empty)
    s2 shouldBe 401
    val (s3, _) = call("POST", "/_ssc-cluster/drain", Map.empty)
    s3 shouldBe 401

  test("token set, matching Bearer: 200"):
    runWithRoutes("""# T
```scalascript
runActors {
  startNode("auth-pass", "ws://127.0.0.1:0/_ssc-actors")
  setClusterAuthToken("s3cret")
}
```""")
    val auth = Map("Authorization" -> "Bearer s3cret")
    val (s1, b1) = call("GET", "/_ssc-cluster/status", auth)
    s1 shouldBe 200
    b1 should include ("\"nodeId\":\"auth-pass\"")
    val (s2, _) = call("GET", "/_ssc-cluster/events", auth)
    s2 shouldBe 200

  test("token set, wrong Bearer value: 401"):
    runWithRoutes("""# T
```scalascript
runActors {
  startNode("auth-wrong", "ws://127.0.0.1:0/_ssc-actors")
  setClusterAuthToken("s3cret")
}
```""")
    val (s, _) = call("GET", "/_ssc-cluster/status",
      Map("Authorization" -> "Bearer nope"))
    s shouldBe 401

  test("case-insensitive Authorization header match"):
    runWithRoutes("""# T
```scalascript
runActors {
  startNode("auth-lower", "ws://127.0.0.1:0/_ssc-actors")
  setClusterAuthToken("s3cret")
}
```""")
    val (s, _) = call("GET", "/_ssc-cluster/status",
      Map("authorization" -> "Bearer s3cret"))
    s shouldBe 200
