package scalascript.frontend.custom

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

/** A6 unit tests — DomRef + Portal on the Custom backend (imperative
 *  ref assignment + querySelector-based portal). */
class RefPortalEmitTest extends AnyFunSuite:

  test("DomRef — element with RefBinding emits let + assignment + window exposure") {
    val backend = new CustomFrameworkBackend
    val inputRef = new DomRef("nameInput")
    val app = ComponentDef("App", Nil, _ => View.Element(
      "input",
      Map("ref" -> AttrValue.RefBinding(inputRef), "type" -> AttrValue.Str("text")),
      Map.empty,
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js

    // Module-scope declaration for the ref variable.
    assert(js.contains("let nameInput = null;"),
      s"DomRef must lower to a let declaration:\n$js")
    // Window exposure for imperative user JS.
    assert(js.contains("Object.defineProperty(window, 'nameInput'"),
      s"DomRef must be exposed on window:\n$js")
    // Imperative assignment right after createElement.
    assert(js.contains("nameInput = n0;") || js.contains("nameInput = n1;"),
      s"DomRef must be assigned the live element node:\n$js")
    // Non-ref attrs still apply via setAttribute.
    assert(js.contains("setAttribute('type', 'text')"),
      s"Non-ref attrs must still emit setAttribute:\n$js")
    // The 'ref' attr key itself does NOT become a setAttribute.
    assert(!js.contains("setAttribute('ref'"),
      s"RefBinding must NOT emit setAttribute for the attr key:\n$js")
  }

  test("DomRef — bad jsName rejected") {
    val backend = new CustomFrameworkBackend
    val badRef  = new DomRef("123-bad")
    val app = ComponentDef("App", Nil, _ => View.Element(
      "input", Map("ref" -> AttrValue.RefBinding(badRef)), Map.empty, Seq.empty
    ))
    val ex = intercept[IllegalArgumentException] {
      backend.emit(FrontendModule(List(app), "App", "/"))
    }
    assert(ex.getMessage.contains("123-bad"),
      s"validation must mention the bad name:\n${ex.getMessage}")
  }

  test("Portal — children appended to document.querySelector(target)") {
    val backend = new CustomFrameworkBackend
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

    // querySelector lookup of the target.
    assert(js.contains("document.querySelector('#modal-root')"),
      s"Portal must look up the target selector:\n$js")
    // Loud failure if the target is missing.
    assert(js.contains("Portal target not found:"),
      s"Portal must throw if target is absent:\n$js")
    // The toast span is appended to the portal target, NOT to the parent div.
    assert(js.contains(".appendChild"))
    // The toast content + class still emits.
    assert(js.contains("setAttribute('class', 'toast')"))
    assert(js.contains("'Hello, modal!'"))
    // Sibling text 'inline' is still mounted to the parent div.
    assert(js.contains("'inline'"))
  }

  test("Portal — empty children renders the lookup + no appendChild") {
    val backend = new CustomFrameworkBackend
    val app = ComponentDef("App", Nil, _ =>
      View.Portal("#empty-target", Seq.empty)
    )
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("document.querySelector('#empty-target')"),
      s"Portal target lookup must still emit even when empty:\n$js")
    // No appendChild on the target — empty portal is a no-op past the lookup.
    assert(!js.contains("root.appendChild"),
      s"Empty portal as root should not call root.appendChild:\n$js")
  }

  test("Refs + Portal compose — refs inside portal still get hoisted") {
    val backend = new CustomFrameworkBackend
    val modalBtn = new DomRef("modalCloseBtn")
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty,
      Seq(View.Portal("#modal-root", Seq(
        View.Element("button",
          Map("ref" -> AttrValue.RefBinding(modalBtn)),
          Map.empty,
          Seq(View.TextNode(() => "close")))
      )))
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    // Ref declared at module scope even though the element lives inside a portal.
    assert(js.contains("let modalCloseBtn = null;"),
      s"ref inside a portal must still get hoisted:\n$js")
    // And exposed on window.
    assert(js.contains("Object.defineProperty(window, 'modalCloseBtn'"))
  }
