package scalascript.frontend

import org.scalatest.funsuite.AnyFunSuite

import scala.collection.immutable.ListMap
import scala.collection.mutable.ListBuffer

class ViewTraversalTest extends AnyFunSuite:

  test("foreachDepthFirst walks semantic and typed-model children in stable pre-order") {
    val fetch = new FetchUrlSignal("sheet", "/api/sheet", "tick")
    val tab   = new ReactiveSignal[Int]("tab", 0)
    val route = new ReactiveSignal[String]("route", "home")
    val root = View.Column(
      Seq(
        View.TextNode(() => "root-child"),
        View.ModelView(fetch, "sheet", View.Row(Seq(View.TextNode(() => "model-child")))),
        View.ForModel("sheet", "lines", "line", View.TextNode(() => "line-child")),
        View.TabBar(Seq(Tab("Home", None, View.TextNode(() => "tab-child"))), tab),
        View.NavigationStack(ListMap("home" -> (() => View.TextNode(() => "route-child"))), route)
      )
    )

    val seen = ListBuffer.empty[String]
    ViewTraversal.foreachDepthFirst(root) {
      case View.TextNode(value) => seen += value()
      case _                    => ()
    }

    assert(seen.toList == List("root-child", "model-child", "line-child", "tab-child", "route-child"))
  }

  test("Adaptive traversal can inspect all branches or the rendered web branch") {
    val root = View.Adaptive(
      web = Some(View.TextNode(() => "web")),
      desktop = Some(View.TextNode(() => "desktop")),
      mobile = Some(View.TextNode(() => "mobile")),
      fallback = View.TextNode(() => "fallback")
    )
    val fallbackOnly = View.Adaptive(fallback = View.TextNode(() => "fallback"))

    def labels(v: View[?], options: ViewTraversal.Options): List[String] =
      val seen = ListBuffer.empty[String]
      ViewTraversal.foreachDepthFirst(v, options) {
        case View.TextNode(value) => seen += value()
        case _                    => ()
      }
      seen.toList

    assert(labels(root, ViewTraversal.Options.Default) == List("web", "desktop", "mobile", "fallback"))
    assert(labels(root, ViewTraversal.Options.Web) == List("web"))
    assert(labels(fallbackOnly, ViewTraversal.Options.Web) == List("fallback"))
  }
