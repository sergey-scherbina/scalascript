package scalascript.frontend.vue

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

/** A6 unit tests — DomRef + Portal on the Vue backend.  Refs lower
 *  to setup-level `ref(null)` + a template-ref binding via the
 *  reserved `ref` prop.  Portals lower to `h(Teleport, { to }, ...)`. */
class RefPortalEmitTest extends AnyFunSuite:

  test("DomRef — RefBinding lowers to ref(null) in setup + 'ref' prop") {
    val backend = new VueFrameworkBackend
    val inputRef = new WidgetRef("nameInput")
    val app = ComponentDef("App", Nil, _ => View.Element(
      "input",
      Map("ref" -> AttrValue.RefBinding(inputRef), "type" -> AttrValue.Str("text")),
      Map.empty,
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js

    // Setup-level ref().
    assert(js.contains("const nameInput = ref(null);"),
      s"DomRef must lower to ref(null) in setup():\n$js")
    // Returned from setup so render can see it via the proxy.
    assert(js.contains("return { nameInput }") || js.contains(", nameInput }"),
      s"DomRef must be returned from setup():\n$js")
    // Window exposure.
    assert(js.contains("window['nameInput'] = nameInput;"),
      s"DomRef must be exposed on window:\n$js")
    // The 'ref' prop binds the ref to the element in h(...).
    assert(js.contains("'ref': nameInput"),
      s"DomRef must be bound via Vue's `ref` prop:\n$js")
    // Other attrs still emit normally.
    assert(js.contains("'type': 'text'"))
  }

  test("DomRef — invalid jsName rejected") {
    val backend = new VueFrameworkBackend
    val bad = new WidgetRef("with.dots")
    val app = ComponentDef("App", Nil, _ => View.Element(
      "input", Map("ref" -> AttrValue.RefBinding(bad)), Map.empty, Seq.empty
    ))
    val ex = intercept[IllegalArgumentException] {
      backend.emit(FrontendModule(List(app), "App", "/"))
    }
    assert(ex.getMessage.contains("with.dots"))
  }

  test("Portal — lowers to h(Teleport, { to: target }, [...children])") {
    val backend = new VueFrameworkBackend
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
    // Teleport pulled into the import list only when a portal is present.
    assert(js.contains("import { ref, h, Fragment, Teleport, createApp } from 'vue';"),
      s"Vue Teleport import must be added when Portal is used:\n$js")
    // The Teleport call.
    assert(js.contains("h(Teleport, { 'to': '#modal-root' }"),
      s"Portal must lower to h(Teleport, { to: ... }):\n$js")
    assert(js.contains("'class': 'toast'"))
    assert(js.contains("'Hello, modal!'"))
  }

  test("Portal — absent means no Teleport import (clean bundle)") {
    val backend = new VueFrameworkBackend
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty, Seq(View.TextNode(() => "hi"))
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    // The generated-on header mentions Teleport for explanation; check
    // the actual `import {...} from 'vue';` line specifically.
    assert(js.contains("import { ref, h, Fragment, createApp } from 'vue';"),
      s"Vue import line must NOT include Teleport when no Portal is used:\n$js")
    assert(!js.contains("h(Teleport"),
      s"No Teleport call when no Portal in tree:\n$js")
  }

  test("Refs + Portal compose — ref inside Teleport still hoists to setup") {
    val backend = new VueFrameworkBackend
    val closeBtn = new WidgetRef("closeBtn")
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
    assert(js.contains("const closeBtn = ref(null);"),
      s"ref inside portal must hoist to setup:\n$js")
    assert(js.contains("'ref': closeBtn"),
      s"button inside Teleport must carry the ref binding:\n$js")
  }
