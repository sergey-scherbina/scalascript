package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.mcp.*
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global

/** v1.17.x — Elicitation: server pops a request asking the user for
 *  input that matches a JSON Schema during a tool call.  Three-way
 *  reply: accept(content) / decline / cancel. */
class McpElicitationTest extends AnyFunSuite with Matchers:

  test("parseElicitationResult: accept → Accept(content)"):
    val js = ujson.Obj("action" -> "accept", "content" -> ujson.Obj("answer" -> 42))
    McpProtocol.parseElicitationResult(js) match
      case McpProtocol.ElicitationResult.Accept(c) =>
        c("answer").num shouldBe 42.0
      case other => fail(s"expected Accept, got $other")

  test("parseElicitationResult: decline → Decline"):
    val js = ujson.Obj("action" -> "decline")
    McpProtocol.parseElicitationResult(js) shouldBe McpProtocol.ElicitationResult.Decline

  test("parseElicitationResult: cancel and unknown both → Cancel"):
    val r1 = McpProtocol.parseElicitationResult(ujson.Obj("action" -> "cancel"))
    val r2 = McpProtocol.parseElicitationResult(ujson.Obj("action" -> "garbage"))
    val r3 = McpProtocol.parseElicitationResult(ujson.Obj())  // no action key
    val r4 = McpProtocol.parseElicitationResult(ujson.Str("scalar"))  // not even an object
    r1 shouldBe McpProtocol.ElicitationResult.Cancel
    r2 shouldBe McpProtocol.ElicitationResult.Cancel
    r3 shouldBe McpProtocol.ElicitationResult.Cancel
    r4 shouldBe McpProtocol.ElicitationResult.Cancel

  test("accept with missing content defaults to empty object (no crash)"):
    val js = ujson.Obj("action" -> "accept")
    McpProtocol.parseElicitationResult(js) match
      case McpProtocol.ElicitationResult.Accept(c) => c shouldBe ujson.Obj()
      case other => fail(s"expected Accept, got $other")

  test("ElicitationResult helpers: isAccepted + acceptedContent"):
    val a = McpProtocol.ElicitationResult.Accept(ujson.Obj("x" -> 1))
    val d = McpProtocol.ElicitationResult.Decline
    val c = McpProtocol.ElicitationResult.Cancel
    a.isAccepted shouldBe true
    d.isAccepted shouldBe false
    c.isAccepted shouldBe false
    a.acceptedContent shouldBe Some(ujson.Obj("x" -> 1))
    d.acceptedContent shouldBe None
    c.acceptedContent shouldBe None

  test("clientSupportsElicitation reflects initialize capabilities"):
    val builder = new McpServerBuilder
    builder.clientSupportsElicitation shouldBe false
    McpServerCore.dispatch(builder, McpProtocol.Method.Initialize,
      ujson.Obj("capabilities" -> ujson.Obj("elicitation" -> ujson.Obj())),
      ujson.Num(1), "srv", "1.0.0")
    builder.clientSupportsElicitation shouldBe true

  test("srv.elicit sends elicitation/create and routes the accept reply"):
    val builder = new McpServerBuilder
    val subscribed = Promise[String]()
    builder.addSubscriber { frame =>
      if !subscribed.isCompleted then subscribed.success(frame)
    }
    Future {
      val outgoing = scala.concurrent.Await.result(subscribed.future,
        scala.concurrent.duration.Duration("2 seconds"))
      val js = ujson.read(outgoing.trim)
      js("method").str shouldBe McpProtocol.Method.ElicitationCreate
      js("params")("message").str shouldBe "Confirm?"
      js("params")("requestedSchema").obj.contains("type") shouldBe true
      val id = js("id")
      val resp = JsonRpc.encodeResult(id,
        ujson.Obj("action" -> "accept", "content" -> ujson.Obj("ok" -> true)))
      JsonRpc.parse(resp) match
        case Right(r: JsonRpc.Message.Response) => builder.routeInboundResponse(r)
        case _                                  => fail("response did not parse")
    }
    val schema = ujson.Obj("type" -> "object",
      "properties" -> ujson.Obj("ok" -> ujson.Obj("type" -> "boolean")))
    builder.elicit("Confirm?", schema, 2000L) match
      case Right(McpProtocol.ElicitationResult.Accept(content)) =>
        content("ok").bool shouldBe true
      case other => fail(s"expected Accept, got $other")

  test("srv.elicit routes a decline reply"):
    val builder = new McpServerBuilder
    val subscribed = Promise[String]()
    builder.addSubscriber { frame =>
      if !subscribed.isCompleted then subscribed.success(frame)
    }
    Future {
      val outgoing = scala.concurrent.Await.result(subscribed.future,
        scala.concurrent.duration.Duration("2 seconds"))
      val id = ujson.read(outgoing.trim)("id")
      val resp = JsonRpc.encodeResult(id, ujson.Obj("action" -> "decline"))
      JsonRpc.parse(resp) match
        case Right(r: JsonRpc.Message.Response) => builder.routeInboundResponse(r)
        case _                                  => fail("response did not parse")
    }
    builder.elicit("Are you sure?", ujson.Obj("type" -> "object"), 2000L) match
      case Right(McpProtocol.ElicitationResult.Decline) => succeed
      case other => fail(s"expected Decline, got $other")

  test("srv.elicit with no subscribers returns InternalError"):
    val builder = new McpServerBuilder
    builder.elicit("x", ujson.Obj("type" -> "object"), 500L) match
      case Left(e)  => e.message should include("no active client subscribers")
      case Right(_) => fail("expected error")
