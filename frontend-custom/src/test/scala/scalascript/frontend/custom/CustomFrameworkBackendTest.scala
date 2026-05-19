package scalascript.frontend.custom

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

class CustomFrameworkBackendTest extends AnyFunSuite:

  test("ServiceLoader discovers CustomFrameworkBackend") {
    FrontendFrameworks.setBackend(null)
    val impls = FrontendFrameworks.all()
    assert(impls.exists(_.name == "custom"),
      s"Expected 'custom' in [${impls.map(_.name).mkString(", ")}]")
  }

  test("name + capabilities + jsDeps") {
    val backend = new CustomFrameworkBackend
    assert(backend.name == "custom")
    assert(backend.capabilities.contains(Capability.SignalState))
    assert(backend.capabilities.contains(Capability.ComponentTree))
    assert(backend.jsDeps.isEmpty)
  }

  test("emit — unknown entryPoint throws with helpful message") {
    val backend = new CustomFrameworkBackend
    val ex = intercept[IllegalArgumentException] {
      backend.emit(FrontendModule(Nil, "Missing", "/"))
    }
    assert(ex.getMessage.contains("Missing"))
    assert(ex.getMessage.contains("entryPoint"))
  }

  test("emit — minimal static SPA (Hello, world!)") {
    val backend = new CustomFrameworkBackend
    val hello = ComponentDef(
      name  = "App",
      props = Nil,
      body  = _ => View.Element(
        tag      = "div",
        attrs    = Map("class" -> AttrValue.Str("hello")),
        events   = Map.empty,
        children = Seq(View.TextNode(() => "Hello, world!"))
      )
    )
    val emitted = backend.emit(FrontendModule(List(hello), "App", "/"))

    // HTML shell has the mount point + script reference + initial-route data attr.
    assert(emitted.html.contains("""<div id="app"></div>"""))
    assert(emitted.html.contains("""src="./app.js""""))
    assert(emitted.html.contains("""data-initial-route="/""""))

    // JS builds the DOM with the expected calls.
    assert(emitted.js.contains("function mount(root)"))
    assert(emitted.js.contains("document.createElement('div')"))
    assert(emitted.js.contains("setAttribute('class', 'hello')"))
    assert(emitted.js.contains("document.createTextNode('Hello, world!')"))
    assert(emitted.js.contains("mount(document.getElementById('app'))"))

    // No CSS for the trivial case.
    assert(emitted.css == "")
  }

  test("emit — nested Element children build append chain") {
    val backend = new CustomFrameworkBackend
    val outer = ComponentDef("App", Nil, _ => View.Element(
      "section",
      Map.empty,
      Map.empty,
      Seq(
        View.Element("h1", Map.empty, Map.empty, Seq(View.TextNode(() => "Title"))),
        View.Element("p",  Map.empty, Map.empty, Seq(View.TextNode(() => "Body")))
      )
    ))
    val emitted = backend.emit(FrontendModule(List(outer), "App", "/"))
    // section is n0; h1 is n1 with text n2; p is n3 with text n4.
    assert(emitted.js.contains("document.createElement('section')"))
    assert(emitted.js.contains("document.createElement('h1')"))
    assert(emitted.js.contains("document.createElement('p')"))
    assert(emitted.js.contains("document.createTextNode('Title')"))
    assert(emitted.js.contains("document.createTextNode('Body')"))
    // h1 and p both appended to section before section is appended to root.
    val sectionAppend = emitted.js.indexOf("root.appendChild(n0)")
    val childAppends  = emitted.js.indexOf("n0.appendChild(n1)")
    assert(childAppends > 0)
    assert(sectionAppend > childAppends,
      s"section must be appended to root AFTER its children:\n${emitted.js}")
  }

  test("emit — Fragment with multiple children uses DocumentFragment") {
    val backend = new CustomFrameworkBackend
    val frag = ComponentDef("App", Nil, _ => View.Fragment(Seq(
      View.Element("span", Map.empty, Map.empty, Seq(View.TextNode(() => "a"))),
      View.Element("span", Map.empty, Map.empty, Seq(View.TextNode(() => "b")))
    )))
    val emitted = backend.emit(FrontendModule(List(frag), "App", "/"))
    assert(emitted.js.contains("createDocumentFragment()"))
    assert(emitted.js.count(_ == 's') > 0) // at least the two spans
    assert(emitted.js.contains("document.createTextNode('a')"))
    assert(emitted.js.contains("document.createTextNode('b')"))
  }

  test("emit — empty Fragment produces no appendChild on root") {
    val backend = new CustomFrameworkBackend
    val empty = ComponentDef("App", Nil, _ => View.Fragment(Nil))
    val emitted = backend.emit(FrontendModule(List(empty), "App", "/"))
    assert(!emitted.js.contains("root.appendChild"),
      s"Empty Fragment should not append anything to root:\n${emitted.js}")
  }

  test("emit — Show picks branch via cond() snapshot") {
    val backend = new CustomFrameworkBackend
    val condTrue = ComponentDef("App", Nil, _ => View.Show(
      cond      = () => true,
      whenTrue  = () => View.TextNode(() => "shown"),
      whenFalse = () => View.TextNode(() => "hidden")
    ))
    val truthyJs = backend.emit(FrontendModule(List(condTrue), "App", "/")).js
    assert(truthyJs.contains("'shown'"))
    assert(!truthyJs.contains("'hidden'"))

    val condFalse = ComponentDef("App", Nil, _ => View.Show(
      cond      = () => false,
      whenTrue  = () => View.TextNode(() => "shown"),
      whenFalse = () => View.TextNode(() => "hidden")
    ))
    val falsyJs = backend.emit(FrontendModule(List(condFalse), "App", "/")).js
    assert(falsyJs.contains("'hidden'"))
    assert(!falsyJs.contains("'shown'"))
  }

  test("emit — For iterates items() snapshot") {
    val backend = new CustomFrameworkBackend
    val list = ComponentDef("App", Nil, _ => View.Element(
      "ul",
      Map.empty, Map.empty,
      Seq(View.For[String](
        items  = () => Seq("alpha", "beta", "gamma"),
        render = name => View.Element("li", Map.empty, Map.empty,
                          Seq(View.TextNode(() => name)))
      ))
    ))
    val js = backend.emit(FrontendModule(List(list), "App", "/")).js
    assert(js.contains("'alpha'"))
    assert(js.contains("'beta'"))
    assert(js.contains("'gamma'"))
    // Each rendered <li> appears as an Element.
    assert(js.contains("document.createElement('li')"))
  }

  test("emit — JVM-closure events emit a skipped marker comment") {
    val backend = new CustomFrameworkBackend
    val button = ComponentDef("App", Nil, _ => View.Element(
      "button",
      Map.empty,
      events = Map("click" -> EventHandler.Simple(() => ())),
      children = Seq(View.TextNode(() => "Click me"))
    ))
    val js = backend.emit(FrontendModule(List(button), "App", "/")).js
    // A2c can translate SetSignalLiteral / IncrementSignal but not raw
    // JVM closures — Simple / WithEvent still leave a marker.
    assert(js.contains("JVM closure"),
      s"Expected A2c to leave a marker for the untranslatable closure handler:\n$js")
    assert(!js.contains("addEventListener"),
      s"Closure handler must not emit an addEventListener call:\n$js")
  }

  test("emit — XSS-y attribute values are escaped, not raw") {
    val backend = new CustomFrameworkBackend
    val sneaky = ComponentDef("App", Nil, _ => View.Element(
      "div",
      attrs    = Map("title" -> AttrValue.Str("</script><script>alert(1)</script>")),
      events   = Map.empty,
      children = Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(sneaky), "App", "/")).js
    // The raw `</script>` substring must not appear unescaped in the emitted JS
    // (or it would terminate an inline <script> if the bundle was inlined).
    assert(!js.contains("</script>"),
      s"Unescaped </script> in emitted JS — XSS / script-injection risk:\n$js")
    assert(js.contains("\\u003c"),
      s"Expected <  to be escaped as \\u003c in JS strings:\n$js")
  }

  test("emit — Dynamic AttrValue is snapshotted via String.valueOf") {
    val backend = new CustomFrameworkBackend
    var reads = 0
    val dyn = ComponentDef("App", Nil, _ => View.Element(
      "input",
      attrs = Map("value" -> AttrValue.Dynamic[Int](() => { reads += 1; 42 })),
      events = Map.empty,
      children = Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(dyn), "App", "/")).js
    assert(reads == 1, s"Dynamic AttrValue read $reads times; expected 1 (snapshot)")
    assert(js.contains("setAttribute('value', '42')"))
  }

  test("emit — Num AttrValue formats integers without decimals") {
    val backend = new CustomFrameworkBackend
    val img = ComponentDef("App", Nil, _ => View.Element(
      "img",
      attrs = Map(
        "width"  -> AttrValue.Num(640),
        "height" -> AttrValue.Num(480.5)
      ),
      events = Map.empty,
      children = Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(img), "App", "/")).js
    assert(js.contains("setAttribute('width', 640)"),
      s"Integer Num should format without .0:\n$js")
    assert(js.contains("setAttribute('height', 480.5)"),
      s"Fractional Num should keep the decimal:\n$js")
  }

  test("emit — ComponentInstance inlines the child component's body") {
    val backend = new CustomFrameworkBackend
    val greeting: Component[String] = new Component[String]:
      def render(name: String): View =
        View.Element("span", Map.empty, Map.empty, Seq(View.TextNode(() => s"Hi $name")))
    val root = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty,
      Seq(View.ComponentInstance(greeting, "world"))
    ))
    val js = backend.emit(FrontendModule(List(root), "App", "/")).js
    assert(js.contains("document.createElement('span')"))
    assert(js.contains("document.createTextNode('Hi world')"))
  }
