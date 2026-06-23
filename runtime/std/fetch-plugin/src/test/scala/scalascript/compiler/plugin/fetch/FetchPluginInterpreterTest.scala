package scalascript.compiler.plugin.fetch

import scala.annotation.nowarn
import org.scalatest.funsuite.AnyFunSuite
import scalascript.compiler.plugin.frontend.FrontendInterpreterPlugin
import scalascript.frontend.{EventHandler, FieldColumnDef, FetchUrlSignal, RowActionDef, TableDataSource, View}
import scalascript.interpreter.Value
import scalascript.testkit.TestInterpreter

@nowarn("cat=deprecation")
class FetchPluginInterpreterTest extends AnyFunSuite:

  private def interp: TestInterpreter =
    TestInterpreter(List(FrontendInterpreterPlugin(), FetchPlugin()))

  private lazy val repoRoot: os.Path =
    var p = os.pwd
    while !os.exists(p / "build.sbt") do
      val up = p / os.up
      if up == p then
        throw RuntimeException(s"could not locate repo root walking up from ${os.pwd}")
      p = up
    p

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

  test("Fetch plugin builds dataTableView from fieldColumn + rowDeleteAction + rowPostAction"):
    val result = interp.eval(
      """
      val tick    = signal("tick", 0)
      val linkSig = signal("selected", "")
      val sig     = fetchUrlSignal("empRows", "/api/employees", tick)
      val cols    = [fieldColumn("Name","name", "", rowEditAction("PATCH", "/api/emp/update", "id", tick)),
                     fieldColumn("Dept","department"),
                     fieldColumn("Plain","plain", "", null)]
      val acts    = [rowDeleteAction("/api/emp/delete", "id", tick, null),
                     rowPostAction("Promote", "POST", "/api/emp/promote", "id", tick, null),
                     rowLinkAction("Select", linkSig, "id")]
      dataTableView(sig, cols, acts)
      """
    )
    result match
      case Value.Foreign("View", dt: View.DataTable) =>
        val fetchUrl = dt.source match
          case TableDataSource.Remote(sig, _) => sig.fetchUrl
          case other => fail(s"expected Remote source, got $other"); ""
        assert(fetchUrl == "/api/employees")
        assert(dt.columns.length == 3)
        assert(dt.columns(0).title == "Name")
        assert(dt.columns(0).fieldPath == "name")
        dt.columns(0).editAction match
          case Some(RowActionDef.RowInlineEdit(method, url, idField, editTick, headers)) =>
            assert(method == "PATCH")
            assert(url == "/api/emp/update")
            assert(idField == "id")
            assert(editTick.id == "tick")
            assert(headers.isEmpty)
          case other => fail(s"expected RowInlineEdit, got $other")
        assert(dt.columns(1) == FieldColumnDef("Dept", "department"))
        assert(dt.columns(2) == FieldColumnDef("Plain", "plain"))
        assert(dt.actions.length == 3)
        dt.actions(0) match
          case RowActionDef.RowDelete(url, idField, _, headers) =>
            assert(url == "/api/emp/delete"); assert(idField == "id")
            assert(headers.isEmpty)
          case other => fail(s"expected RowDelete, got $other")
        dt.actions(1) match
          case RowActionDef.RowPost(label, method, url, _, _, headers) =>
            assert(label == "Promote"); assert(method == "POST"); assert(url == "/api/emp/promote")
            assert(headers.isEmpty)
          case other => fail(s"expected RowPost, got $other")
        dt.actions(2) match
          case RowActionDef.RowLink(label, sig, fieldPath) =>
            assert(label == "Select"); assert(sig.id == "selected"); assert(fieldPath == "id")
          case other => fail(s"expected RowLink, got $other")
      case other => fail(s"expected DataTable foreign value, got $other")

  test("std/ui data imports rowEditAction for public rowEdit helper"):
    val source = os.read(repoRoot / "runtime" / "std" / "ui" / "data.ssc")
    val primitiveImport =
      source.linesIterator.find(_.contains("](primitives.ssc)")).getOrElse("")

    assert(source.contains("def rowEdit("))
    assert(
      primitiveImport.contains("rowEditAction"),
      s"std/ui/data.ssc rowEdit helper must import rowEditAction; import line was: $primitiveImport"
    )

  test("std/ui data exposes remoteTable composing fetchRowsSource + dataTableView (nested rowsPath)"):
    val source = os.read(repoRoot / "runtime" / "std" / "ui" / "data.ssc")
    val primitiveImport =
      source.linesIterator.find(_.contains("](primitives.ssc)")).getOrElse("")
    assert(
      primitiveImport.contains("fetchRowsSource"),
      s"remoteTable needs fetchRowsSource imported; import line was: $primitiveImport"
    )
    assert(source.contains("def remoteTable("), "std/ui/data.ssc must define remoteTable")
    assert(
      source.contains("dataTableView(fetchRowsSource("),
      "remoteTable must compose fetchRowsSource(...) into dataTableView (so the Remote source carries rowsPath)"
    )

  test("Fetch plugin: fetchRowsSource builds a Remote table data source (Scope B.3)"):
    val result = interp.eval(
      """
      val tick = signal("tick", 0)
      val sig  = fetchUrlSignal("rows", "/api/invoices", tick)
      val src  = fetchRowsSource(sig, "result.items")
      dataTableView(src, [fieldColumn("No","number")], [])
      """
    )
    result match
      case Value.Foreign("View", dt: View.DataTable) =>
        dt.source match
          case TableDataSource.Remote(s, rowsPath) =>
            assert(s.fetchUrl == "/api/invoices")
            // Scope B.3 — rowsPath is now carried on the model (server-rendered drill).
            assert(rowsPath == "result.items", s"expected rowsPath carried, got '$rowsPath'")
          case other => fail(s"expected Remote source from fetchRowsSource, got $other")
        assert(dt.columns.length == 1)
      case other => fail(s"expected DataTable foreign value, got $other")

  test("Fetch plugin: fetchActionWith builds a FetchAction using the first onBumpTick (Scope B.4)"):
    val result = interp.eval(
      """
      val tick = signal("ordersTick", 0)
      val status = signal("status", "")
      val body = signal("body", "{}")
      fetchActionWith("POST", "/api/orders", body,
        [onBumpTick(tick), onSetSignal(status, "ok"), onNavigate("/done")])
      """
    )
    result match
      case Value.Foreign("EventHandler", EventHandler.FetchAction(method, url, _, onSuccessTick, _, _)) =>
        assert(method == "POST")
        assert(url == "/api/orders")
        assert(onSuccessTick.id == "ordersTick")
      case other => fail(s"expected FetchAction from fetchActionWith, got $other")

  test("Fetch plugin: fetchActionWith accepts a formBody(...) body (Scope B.4+)"):
    val result = interp.eval(
      """
      val tick = signal("ordersTick", 0)
      fetchActionWith("POST", "/api/orders", formBody(["customer", "amount"]),
        [onBumpTick(tick)])
      """
    )
    result match
      case Value.Foreign("EventHandler", EventHandler.FetchAction(method, url, _, onSuccessTick, _, _)) =>
        assert(method == "POST")
        assert(url == "/api/orders")
        // first onBumpTick → onSuccessTick (the form body is assembled browser-side)
        assert(onSuccessTick.id == "ordersTick")
      case other => fail(s"expected FetchAction from fetchActionWith(formBody), got $other")
