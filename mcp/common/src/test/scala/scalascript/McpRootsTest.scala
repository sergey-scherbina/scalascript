package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*
import java.util.concurrent.LinkedBlockingQueue
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global

/** v1.17.x — Roots: server pulls workspace info from the client via
 *  `roots/list` (server-initiated request) and reacts to
 *  `notifications/roots/list_changed` when the client tells us its
 *  workspace changed. */
class McpRootsTest extends AnyFunSuite with Matchers:

  test("parseRootsListResult: typed parsing of well-formed response"):
    val js = ujson.Obj("roots" -> ujson.Arr(
      ujson.Obj("uri" -> "file:///home/u/proj", "name" -> "proj"),
      ujson.Obj("uri" -> "file:///tmp")
    ))
    val roots = McpProtocol.parseRootsListResult(js)
    roots.length shouldBe 2
    roots(0).uri  shouldBe "file:///home/u/proj"
    roots(0).name shouldBe Some("proj")
    roots(1).uri  shouldBe "file:///tmp"
    roots(1).name shouldBe None

  test("parseRootsListResult: garbage in → empty list (no crash)"):
    val r1 = McpProtocol.parseRootsListResult(ujson.Obj())
    val r2 = McpProtocol.parseRootsListResult(ujson.Str("not-an-object"))
    val r3 = McpProtocol.parseRootsListResult(ujson.Obj("roots" -> ujson.Arr(
      ujson.Obj("name" -> "no-uri"),  // missing uri → skip this entry
      ujson.Obj("uri" -> "file:///ok")
    )))
    r1.isEmpty shouldBe true
    r2.isEmpty shouldBe true
    r3.length  shouldBe 1
    r3(0).uri shouldBe "file:///ok"

  test("rootsListResult builder round-trips through parseRootsListResult"):
    val roots = List(
      McpProtocol.Root("file:///a", Some("A")),
      McpProtocol.Root("file:///b", None)
    )
    val parsed = McpProtocol.parseRootsListResult(McpProtocol.rootsListResult(roots))
    parsed shouldBe roots

  test("initialize captures client capabilities for clientSupportsRoots"):
    val builder = new McpServerBuilder
    builder.clientSupportsRoots shouldBe false  // before initialize
    val params = ujson.Obj(
      "capabilities" -> ujson.Obj("roots" -> ujson.Obj("listChanged" -> true))
    )
    val reply = McpServerCore.dispatch(builder,
      McpProtocol.Method.Initialize, params, ujson.Num(1),
      "srv", "1.0.0")
    ujson.read(reply.trim)("result")("protocolVersion").str shouldBe McpProtocol.ProtocolVersion
    builder.clientSupportsRoots shouldBe true
    builder.currentClientCapabilities.obj.contains("roots") shouldBe true

  test("initialize with no capabilities → clientSupportsRoots stays false"):
    val builder = new McpServerBuilder
    McpServerCore.dispatch(builder,
      McpProtocol.Method.Initialize, ujson.Obj(), ujson.Num(1),
      "srv", "1.0.0")
    builder.clientSupportsRoots shouldBe false

  test("notifications/roots/list_changed fires the registered callback"):
    val builder = new McpServerBuilder
    val fired = new java.util.concurrent.atomic.AtomicInteger(0)
    builder.setOnRootsListChanged(() => { fired.incrementAndGet(); () })
    // Run a fake serve() loop with one input notification
    val input  = scala.collection.mutable.Queue[String](
      JsonRpc.encodeNotification(McpProtocol.Method.RootsListChanged, ujson.Obj())
    )
    val output = new LinkedBlockingQueue[String]()
    McpServerCore.serve(builder,
      () => if input.nonEmpty then Some(input.dequeue()) else None,
      output.offer, "srv", "1.0.0")
    fired.get shouldBe 1

  test("handleHttpRequest routes roots/list_changed notifications too"):
    val builder = new McpServerBuilder
    val fired = new java.util.concurrent.atomic.AtomicInteger(0)
    builder.setOnRootsListChanged(() => { fired.incrementAndGet(); () })
    val frame = JsonRpc.encodeNotification(McpProtocol.Method.RootsListChanged, ujson.Obj())
    val reply = McpServerCore.handleHttpRequest(builder, frame)
    reply shouldBe ""  // notifications return empty body
    fired.get shouldBe 1

  test("srv.listRoots sends roots/list request and parses the response"):
    val builder = new McpServerBuilder
    // Fake client: subscriber captures the outgoing request, then replies
    // through routeInboundResponse on a background thread.  Models a
    // real connected client without spinning up an actual transport.
    val subscribed = Promise[String]()
    builder.addSubscriber { frame =>
      if !subscribed.isCompleted then subscribed.success(frame)
    }
    Future {
      val outgoing = scala.concurrent.Await.result(subscribed.future,
        scala.concurrent.duration.Duration("2 seconds"))
      val js = ujson.read(outgoing.trim)
      js("method").str shouldBe McpProtocol.Method.RootsList
      val id = js("id")
      val result = McpProtocol.rootsListResult(List(
        McpProtocol.Root("file:///workspace", Some("ws"))
      ))
      val resp = JsonRpc.encodeResult(id, result)
      JsonRpc.parse(resp) match
        case Right(r: JsonRpc.Message.Response) => builder.routeInboundResponse(r)
        case _                                  => fail("encoded response did not parse")
    }
    builder.listRoots(2000L) match
      case Right(roots) =>
        roots.length     shouldBe 1
        roots.head.uri   shouldBe "file:///workspace"
        roots.head.name  shouldBe Some("ws")
      case Left(e) => fail(s"listRoots failed: ${e.message}")

  test("srv.listRoots with no subscribers returns InternalError"):
    val builder = new McpServerBuilder
    builder.listRoots(500L) match
      case Left(e)  => e.message should include("no active client subscribers")
      case Right(_) => fail("expected error when no subscribers")
