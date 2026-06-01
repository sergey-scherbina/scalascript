package scalascript.compiler.plugin.fetch

import scala.annotation.nowarn
import org.scalatest.funsuite.AnyFunSuite
import scalascript.compiler.plugin.frontend.FrontendInterpreterPlugin
import scalascript.frontend.{EventHandler, FetchUrlSignal, ReactiveSignal, View}
import scalascript.interpreter.Value
import scalascript.testkit.TestInterpreter

@nowarn("cat=deprecation")
class FetchPluginInterpreterTest extends AnyFunSuite:

  private def interp: TestInterpreter =
    TestInterpreter(List(FrontendInterpreterPlugin(), FetchPlugin()))

  test("Fetch plugin creates fetch actions from frontend signals in isolation"):
    val result = interp.eval(
      """
      val body = signal("body", "")
      val tick = signal("tick", 0)
      fetchActionClear("POST", "/api/items", body, tick)
      """
    )

    result match
      case Value.Foreign("EventHandler", EventHandler.FetchAction(method, url, body, tick, clearBody, headers)) =>
        assert(method == "POST")
        assert(url == "/api/items")
        assert(body.id == "body")
        assert(tick.id == "tick")
        assert(clearBody)
        assert(headers.isEmpty)
      case other => fail(s"expected FetchAction foreign value, got $other")

  test("Fetch plugin threads headers Signal through fetchAction"):
    val result = interp.eval(
      """
      val body   = signal("body", "")
      val tick   = signal("tick", 0)
      val token  = signal("token", "")
      fetchAction("POST", "/api/items", body, tick, token)
      """
    )

    result match
      case Value.Foreign("EventHandler", EventHandler.FetchAction(_, _, _, _, _, headers)) =>
        assert(headers.isDefined)
        assert(headers.get.id == "token")
      case other => fail(s"expected FetchAction with headers, got $other")

  test("Fetch plugin creates fetch URL signals and table views in isolation"):
    val fetchUrl = interp.eval(
      """
      val tick = signal("refresh", 0)
      fetchUrlSignal("itemsJson", "/api/items", tick)
      """
    )

    fetchUrl match
      case Value.Foreign("ReactiveSignal", signal: FetchUrlSignal) =>
        assert(signal.id == "itemsJson")
        assert(signal.fetchUrl == "/api/items")
        assert(signal.tickId == "refresh")
        assert(signal.headersId.isEmpty)
      case other => fail(s"expected FetchUrlSignal foreign value, got $other")

  test("Fetch plugin threads headers through fetchUrlSignal"):
    val result = interp.eval(
      """
      val tick    = signal("refresh", 0)
      val token   = signal("token", "")
      fetchUrlSignal("itemsJson", "/api/items", tick, token)
      """
    )

    result match
      case Value.Foreign("ReactiveSignal", signal: FetchUrlSignal) =>
        assert(signal.headersId == Some("token"))
      case other => fail(s"expected FetchUrlSignal with headers, got $other")

    val table = interp.eval(
      """
      val tick = signal("refresh", 0)
      fetchTableView("/api/items", "/api/items/delete", tick)
      """
    )

    table match
      case Value.Foreign("View", View.FetchTable(tableId, fetchUrl, deleteUrl, tick: ReactiveSignal[Int], headers)) =>
        assert(tableId == "sscRows__api_items")
        assert(fetchUrl == "/api/items")
        assert(deleteUrl == "/api/items/delete")
        assert(tick.id == "refresh")
        assert(headers.isEmpty)
      case other => fail(s"expected FetchTable foreign value, got $other")
