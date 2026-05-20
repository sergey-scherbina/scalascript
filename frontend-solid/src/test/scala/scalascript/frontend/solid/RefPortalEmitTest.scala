package scalascript.frontend.solid

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

/** A6 unit tests — DomRef + Portal on the Solid backend.  Refs lower
 *  to module-scope `let` + imperative assignment (matches Solid's
 *  hand-written-imperative pattern; `<Portal>` from solid-js/web
 *  needs JSX which we don't transpile).  Portals lower to
 *  `document.querySelector(target).appendChild(...)`. */
class RefPortalEmitTest extends AnyFunSuite:

  test("DomRef — module-scope let + assignment after createElement") {
    val backend = new SolidFrameworkBackend
    val inputRef = new DomRef("nameInput")
    val app = ComponentDef("App", Nil, _ => View.Element(
      "input",
      Map("ref" -> AttrValue.RefBinding(inputRef), "type" -> AttrValue.Str("text")),
      Map.empty,
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js

    // Module-scope let.
    assert(js.contains("let nameInput = null;"),
      s"DomRef must lower to a module-scope let:\n$js")
    // Window exposure.
    assert(js.contains("Object.defineProperty(window, 'nameInput'"),
      s"DomRef must be exposed on window:\n$js")
    // Imperative assignment after createElement.
    assert(js.contains("nameInput = n0;"),
      s"DomRef must be assigned the live element node (n0):\n$js")
    // Other attrs still use setAttribute.
    assert(js.contains("setAttribute('type', 'text')"))
  }

  test("DomRef — invalid jsName rejected") {
    val backend = new SolidFrameworkBackend
    val bad = new DomRef("bad name")
    val app = ComponentDef("App", Nil, _ => View.Element(
      "input", Map("ref" -> AttrValue.RefBinding(bad)), Map.empty, Seq.empty
    ))
    val ex = intercept[IllegalArgumentException] {
      backend.emit(FrontendModule(List(app), "App", "/"))
    }
    assert(ex.getMessage.contains("bad name"))
  }

  test("Portal — querySelector + appendChild on the target") {
    val backend = new SolidFrameworkBackend
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty,
      Seq(
        View.TextNode(() => "inline"),
        View.Portal("#modal-root", Seq(
          View.Element("span",
            Map("id" -> AttrValue.Str("toast")),
            Map.empty,
            Seq(View.TextNode(() => "Hello, modal!")))
        ))
      )
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("document.querySelector('#modal-root')"),
      s"Portal must look up the target selector:\n$js")
    assert(js.contains("Portal target not found:"),
      s"Portal must throw if target is missing:\n$js")
    assert(js.contains("setAttribute('id', 'toast')"))
    assert(js.contains("'Hello, modal!'"))
  }

  test("Portal — empty children still emits the lookup") {
    val backend = new SolidFrameworkBackend
    val app = ComponentDef("App", Nil, _ =>
      View.Portal("body", Seq.empty)
    )
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("document.querySelector('body')"))
    assert(!js.contains("root.appendChild"))
  }

  test("Refs + Portal compose — ref inside a portal still hoists") {
    val backend = new SolidFrameworkBackend
    val closeBtn = new DomRef("closeBtn")
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty,
      Seq(View.Portal("#modal-root", Seq(
        View.Element("button",
          Map("ref" -> AttrValue.RefBinding(closeBtn)),
          Map.empty,
          Seq(View.TextNode(() => "close")))
      )))
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("let closeBtn = null;"),
      s"ref inside portal must still hoist:\n$js")
  }
