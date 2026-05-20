package scalascript.frontend.examples

import scalascript.frontend.*

/** Demo 3 — Todo list.
 *
 *  Exercises `ReactiveSignalList[String]` + the list-mutation event
 *  handlers (`PushSignalLiteral`, `ClearSignalList`) + `View.ForSignal`.
 *
 *  A2e restricts list items to a single `<tag>String(item)</tag>`
 *  per-item template — rich per-item views (nested elements, per-item
 *  events) need a richer IR and are deferred.  For the canonical
 *  demo a single seeded item plus an "add" / "clear" pair is enough
 *  to show the full add / clear / re-render cycle. */
object TodoListDemo:

  val Name: String = "todo"

  def buildModule(): FrontendModule =
    val todos = new ReactiveSignalList[String]("todos", Seq("write some ScalaScript"))

    val app = ComponentDef("App", Nil, _ =>
      View.Element(
        tag      = "div",
        attrs    = Map("id" -> AttrValue.Str("todo-demo")),
        events   = Map.empty,
        children = Seq(
          View.Element(
            "h1",
            attrs    = Map.empty,
            events   = Map.empty,
            children = Seq(View.TextNode(() => "Todo list"))
          ),
          View.Element(
            "button",
            attrs    = Map("id" -> AttrValue.Str("add")),
            events   = Map("click" -> EventHandler.PushSignalLiteral(todos, "new item")),
            children = Seq(View.TextNode(() => "add"))
          ),
          View.Element(
            "button",
            attrs    = Map("id" -> AttrValue.Str("clear")),
            events   = Map("click" -> EventHandler.ClearSignalList(todos)),
            children = Seq(View.TextNode(() => "clear"))
          ),
          View.Element(
            "ul",
            attrs    = Map("id" -> AttrValue.Str("list")),
            events   = Map.empty,
            children = Seq(
              View.ForSignal(
                items = todos,
                tag   = "li",
                attrs = Map.empty
              )
            )
          )
        )
      )
    )

    FrontendModule(
      components   = List(app),
      entryPoint   = "App",
      initialRoute = "/"
    )
