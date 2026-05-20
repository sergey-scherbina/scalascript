package scalascript.frontend.react

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

/** A6 unit tests — DomRef + Portal on the React backend.  Refs lower
 *  to `useRef(null)` + `ref: <name>` prop; portals lower to
 *  `ReactDOM.createPortal(child, document.querySelector(target))`. */
class RefPortalEmitTest extends AnyFunSuite:

  test("DomRef — RefBinding lowers to useRef + ref prop") {
    val backend = new ReactFrameworkBackend
    val inputRef = new DomRef("nameInput")
    val app = ComponentDef("App", Nil, _ => View.Element(
      "input",
      Map("ref" -> AttrValue.RefBinding(inputRef), "type" -> AttrValue.Str("text")),
      Map.empty,
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js

    // useRef hoisted at top of App().
    assert(js.contains("const nameInput = useRef(null);"),
      s"DomRef must lower to useRef(null):\n$js")
    // Window exposure so external imperative JS can read the ref.
    assert(js.contains("window['nameInput'] = nameInput;"),
      s"DomRef must be exposed on window:\n$js")
    // `ref` prop passed to React.createElement.
    assert(js.contains("'ref': nameInput"),
      s"DomRef must lower to React's `ref` prop:\n$js")
    // Other attrs still emit normally.
    assert(js.contains("'type': 'text'"),
      s"Non-ref attrs must still appear in props:\n$js")
  }

  test("DomRef — bad jsName rejected") {
    val backend = new ReactFrameworkBackend
    val bad = new DomRef("nope-dashes")
    val app = ComponentDef("App", Nil, _ => View.Element(
      "input", Map("ref" -> AttrValue.RefBinding(bad)), Map.empty, Seq.empty
    ))
    val ex = intercept[IllegalArgumentException] {
      backend.emit(FrontendModule(List(app), "App", "/"))
    }
    assert(ex.getMessage.contains("nope-dashes"))
  }

  test("Portal — lowers to ReactDOM.createPortal with target selector") {
    val backend = new ReactFrameworkBackend
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty,
      Seq(
        View.TextNode(() => "inline"),
        View.Portal("#modal-root", Seq(
          View.Element("span",
            Map("class" -> AttrValue.Str("toast")),
            Map.empty,
            Seq(View.TextNode(() => "Hello, modal!")))
        ))
      )
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js

    assert(js.contains("ReactDOM.createPortal("),
      s"Portal must lower to ReactDOM.createPortal:\n$js")
    assert(js.contains("document.querySelector('#modal-root')"),
      s"Portal must pass the target selector via querySelector:\n$js")
    // The toast child still renders with className remap (React-specific).
    assert(js.contains("'className': 'toast'"))
    assert(js.contains("'Hello, modal!'"))
  }

  test("Portal — multiple children wrapped in Fragment so createPortal gets one node") {
    val backend = new ReactFrameworkBackend
    val app = ComponentDef("App", Nil, _ =>
      View.Portal("#modal-root", Seq(
        View.Element("span", Map.empty, Map.empty, Seq(View.TextNode(() => "a"))),
        View.Element("span", Map.empty, Map.empty, Seq(View.TextNode(() => "b")))
      ))
    )
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("ReactDOM.createPortal(h(Fragment"),
      s"multi-child portal must wrap in React.Fragment:\n$js")
  }

  test("Refs + Portal compose — useRef for an element inside a portal") {
    val backend = new ReactFrameworkBackend
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
    // useRef declared at top of App — even though the bound element lives in a portal.
    assert(js.contains("const closeBtn = useRef(null);"),
      s"ref inside portal must still hoist useRef:\n$js")
    // The portal contains the button with the ref prop.
    assert(js.contains("'ref': closeBtn"),
      s"button inside portal must still carry the ref prop:\n$js")
  }
