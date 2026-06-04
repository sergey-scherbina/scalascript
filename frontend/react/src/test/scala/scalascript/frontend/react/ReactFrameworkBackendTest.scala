package scalascript.frontend.react

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

class ReactFrameworkBackendTest extends AnyFunSuite:

  test("ServiceLoader discovers ReactFrameworkBackend") {
    FrontendFrameworks.setBackend(null)
    val impls = FrontendFrameworks.all()
    assert(impls.exists(_.name == "react"),
      s"Expected 'react' in [${impls.map(_.name).mkString(", ")}]")
  }

  test("name + capabilities + jsDeps") {
    val backend = new ReactFrameworkBackend
    assert(backend.name == "react")
    assert(backend.capabilities.contains(Capability.Suspense))
    assert(backend.capabilities.contains(Capability.Portals))
    val depNames = backend.jsDeps.map(_.npmName).toSet
    assert(depNames.contains("react"))
    assert(depNames.contains("react-dom"))
  }

  test("emit — unknown entryPoint throws with helpful message") {
    val backend = new ReactFrameworkBackend
    val ex = intercept[IllegalArgumentException] {
      backend.emit(FrontendModule(Nil, "Missing", "/"))
    }
    assert(ex.getMessage.contains("Missing"))
  }

  test("emit — minimal SPA produces React.createElement output") {
    val backend = new ReactFrameworkBackend
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div",
      Map("class" -> AttrValue.Str("hello")),
      Map.empty,
      Seq(View.TextNode(() => "Hello, world!"))
    ))
    val emitted = backend.emit(FrontendModule(List(app), "App", "/"))

    // HTML shell loads React from CDN by default.
    assert(emitted.html.contains("react.production.min.js"))
    assert(emitted.html.contains("react-dom.production.min.js"))
    assert(emitted.html.contains("""<div id="app"></div>"""))

    // JS uses h() alias for React.createElement.  A6 added useRef to
    // the destructure list; just check that the relevant pieces are
    // all there (order is stable but matching the full line is brittle).
    assert(emitted.js.contains("const { useState"))
    assert(emitted.js.contains("useRef"))
    assert(emitted.js.contains("createElement: h"))
    assert(emitted.js.contains("Fragment } = React;"))
    assert(emitted.js.contains("function App()"))
    assert(emitted.js.contains("h('div'"))
    // class → className mapping.
    assert(emitted.js.contains("'className': 'hello'"),
      s"expected class -> className remap:\n${emitted.js}")
    assert(emitted.js.contains("'Hello, world!'"))
    // Mounted via createRoot.
    assert(emitted.js.contains("ReactDOM.createRoot(document.getElementById('app'))"))
    assert(emitted.js.contains("root.render(h(App))"))
  }

  test("emit — ReactiveSignal lowers to useState at top of component") {
    val backend = new ReactFrameworkBackend
    val count = new ReactiveSignal[Int]("count", 0)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty,
      Seq(View.SignalText(count))
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("const [count, setCount] = useState(0);"),
      s"expected useState hoist:\n$js")
    // SignalText interpolates the variable directly — no quotes.
    assert(js.contains("h('div', null, count)"),
      s"SignalText must interpolate bare variable, not stringify:\n$js")
  }

  test("emit — IncrementSignal lowers to functional setState (c => c + by)") {
    val backend = new ReactFrameworkBackend
    val count = new ReactiveSignal[Int]("count", 5)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button",
      Map.empty,
      Map("click" -> EventHandler.IncrementSignal(count, by = 2)),
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("const [count, setCount] = useState(5);"))
    assert(js.contains("'onClick': () => setCount(c => c + 2)"),
      s"expected functional setState for increment:\n$js")
  }

  test("emit — SetSignalLiteral lowers to setX(literal)") {
    val backend = new ReactFrameworkBackend
    val greeting = new ReactiveSignal[String]("greeting", "Hello")
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button",
      Map.empty,
      Map("click" -> EventHandler.SetSignalLiteral(greeting, "world")),
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("const [greeting, setGreeting] = useState('Hello');"))
    assert(js.contains("'onClick': () => setGreeting('world')"))
  }

  test("emit — SeedSignal syncs from source while pristine and dirty-writes on input") {
    val backend = new ReactFrameworkBackend
    val source = new ReactiveSignal[String]("sourceName", "Ada")
    val draft  = new SeedSignal("draftName", source)
    val app = ComponentDef("App", Nil, _ => View.TextInput(
      draft,
      placeholder = "Name",
      multiline = false,
      secure = false,
      style = Style()
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("const [sourceName, setSourceName] = useState('Ada');"), s"source state:\n$js")
    assert(js.contains("const [draftName, setDraftName] = useState('Ada');"), s"draft state:\n$js")
    assert(js.contains("const draftNamePristine = useRef(true);"), s"pristine ref:\n$js")
    assert(js.contains("useEffect(() => { if (draftNamePristine.current) setDraftName(sourceName); }, [sourceName]);"),
      s"source sync:\n$js")
    assert(js.contains("'onChange': (e) => { draftNamePristine.current = false; setDraftName(e.target.value); }"),
      s"dirty input write:\n$js")
  }

  test("emit — Fragment becomes React.Fragment node") {
    val backend = new ReactFrameworkBackend
    val app = ComponentDef("App", Nil, _ => View.Fragment(Seq(
      View.Element("span", Map.empty, Map.empty, Seq(View.TextNode(() => "a"))),
      View.Element("span", Map.empty, Map.empty, Seq(View.TextNode(() => "b")))
    )))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("h(Fragment, null"),
      s"expected Fragment node:\n$js")
  }

  test("emit — Show takes the cond() snapshot branch") {
    val backend = new ReactFrameworkBackend
    val app = ComponentDef("App", Nil, _ => View.Show(
      cond      = () => true,
      whenTrue  = () => View.TextNode(() => "shown"),
      whenFalse = () => View.TextNode(() => "hidden")
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("'shown'"))
    assert(!js.contains("'hidden'"))
  }

  test("emit — For becomes a literal array of children") {
    val backend = new ReactFrameworkBackend
    val list = ComponentDef("App", Nil, _ => View.Element(
      "ul", Map.empty, Map.empty,
      Seq(View.For[String](
        items  = () => Seq("a", "b"),
        render = name => View.Element("li", Map.empty, Map.empty,
                          Seq(View.TextNode(() => name)))
      ))
    ))
    val js = backend.emit(FrontendModule(List(list), "App", "/")).js
    assert(js.contains("h('ul', null, [h('li'"),
      s"For must produce an array literal of li elements:\n$js")
    assert(js.contains("'a'"))
    assert(js.contains("'b'"))
  }

  test("emit — signal referenced ONLY in event handler still gets useState") {
    val backend = new ReactFrameworkBackend
    val count = new ReactiveSignal[Int]("count", 0)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button",
      Map.empty,
      Map("click" -> EventHandler.IncrementSignal(count)),
      Seq(View.TextNode(() => "Click"))
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("const [count, setCount] = useState(0);"),
      s"signals referenced only in handlers must be hoisted:\n$js")
  }

  test("emit — duplicate signal name + different initial throws loudly") {
    val backend = new ReactFrameworkBackend
    val a = new ReactiveSignal[String]("x", "alpha")
    val b = new ReactiveSignal[String]("x", "beta")
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty,
      Seq(View.SignalText(a), View.SignalText(b))
    ))
    val ex = intercept[IllegalArgumentException] {
      backend.emit(FrontendModule(List(app), "App", "/"))
    }
    assert(ex.getMessage.contains("twice"))
  }

  test("emit — JVM-closure events emit a comment in props, no listener") {
    val backend = new ReactFrameworkBackend
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button",
      Map.empty,
      Map("click" -> EventHandler.Simple(() => ())),
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("JVM closure"),
      s"expected JVM-closure marker:\n$js")
    assert(!js.contains("'onClick': ()"),
      s"closure handler must not emit a real onClick:\n$js")
  }

  test("emit — XSS-y text content is escaped") {
    val backend = new ReactFrameworkBackend
    val sneaky = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty,
      Seq(View.TextNode(() => "</script><script>alert(1)</script>"))
    ))
    val js = backend.emit(FrontendModule(List(sneaky), "App", "/")).js
    assert(!js.contains("</script>"),
      s"raw </script> in emitted JS leaks XSS:\n$js")
  }

  test("emit — DOM event name maps to camelCase React handler") {
    val backend = new ReactFrameworkBackend
    val x = new ReactiveSignal[Int]("x", 0)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "input",
      Map.empty,
      Map("mousedown" -> EventHandler.IncrementSignal(x)),
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("'onMousedown'"),
      s"expected onMousedown camelCase prop:\n$js")
  }
