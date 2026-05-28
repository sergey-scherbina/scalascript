package scalascript.compiler.plugin.streams

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.{Interpreter, Value}
import scalascript.parser.Parser

/** Tests for `Source[A].remote(name)`, `RemoteSource[A].local(buffer)`,
 *  and `DStream[A].remote(name)` (v1.63.6). */
class RemoteSourceTest extends AnyFunSuite with Matchers:

  private def run(src: String): Interpreter =
    scalascript.server.Routes.clear()
    val interp = Interpreter(java.io.PrintStream(java.io.OutputStream.nullOutputStream()))
    interp.installPlugins(List(StreamsInterpreterPlugin()))
    interp.run(Parser.parse(s"# Test\n\n```scalascript\n$src\n```"))
    interp

  test("Source.remote returns a RemoteSource instance with the registered name"):
    val interp = run(
      """
      val src = Source.from(List(1, 2, 3))
      src.remote("test.numbers", RemoteStreamPolicy.Default)
      """
    )
    interp.lastResult match
      case Value.InstanceV("RemoteSource", fields) =>
        val name = fields.get("name").collect { case Value.StringV(s) => s }
        name shouldBe Some("test.numbers")
      case other =>
        fail(s"expected InstanceV(RemoteSource,...) but got $other")

  test("RemoteSource.local retrieves in-process source and returns its elements"):
    val interp = run(
      """
      val src = Source.from(List(10, 20, 30))
      val rs = src.remote("test.inproc", RemoteStreamPolicy.Default)
      remoteSourceLocal[Int](rs, 256).runToList()
      """
    )
    interp.lastResult match
      case Value.ListV(items) =>
        items.collect { case Value.IntV(n) => n.toInt } shouldBe List(10, 20, 30)
      case other =>
        fail(s"expected ListV but got $other")

  test("Source.remote registers GET /streams/<name> SSE route"):
    run(
      """
      val src = Source.from(List("a", "b", "c"))
      src.remote("test.sse-route", RemoteStreamPolicy.Default)
      """
    )
    val route = scalascript.server.Routes.matchRequest("GET", "/streams/test.sse-route")
    assert(route.nonEmpty, "GET /streams/test.sse-route should be registered")

  test("RemoteStreamPolicy companion constants are accessible"):
    val interp = run("RemoteStreamPolicy.Default")
    interp.lastResult match
      case Value.InstanceV(name, _) => name should startWith ("RemoteStreamPolicy")
      case other => fail(s"expected RemoteStreamPolicy.* InstanceV but got $other")

  test("SseOverflowPolicy companion constants are accessible"):
    val interp = run("SseOverflowPolicy.DropOldest")
    interp.lastResult match
      case Value.InstanceV(name, _) => name should startWith ("SseOverflowPolicy")
      case other => fail(s"expected SseOverflowPolicy.* InstanceV but got $other")
