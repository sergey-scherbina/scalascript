package scalascript.frontend.solid

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

class SolidFrameworkBackendTest extends AnyFunSuite:

  test("ServiceLoader discovers SolidFrameworkBackend") {
    FrontendFrameworks.setBackend(null)
    val impls = FrontendFrameworks.all()
    assert(impls.exists(_.name == "solid"),
      s"Expected 'solid' in [${impls.map(_.name).mkString(", ")}]")
  }

  test("name + capabilities + jsDeps") {
    val backend = new SolidFrameworkBackend
    assert(backend.name == "solid")
    assert(backend.capabilities.contains(Capability.Untrack))
    assert(backend.capabilities.contains(Capability.SignalState))
    val depNames = backend.jsDeps.map(_.npmName).toSet
    assert(depNames.contains("solid-js"))
  }

  test("emit — unknown entryPoint throws with helpful message") {
    val backend = new SolidFrameworkBackend
    val ex = intercept[IllegalArgumentException] {
      backend.emit(FrontendModule(Nil, "Missing", "/"))
    }
    assert(ex.getMessage.contains("Missing"))
  }

  test("emit — minimal SPA produces idiomatic Solid (no h, no JSX)") {
    val backend = new SolidFrameworkBackend
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div",
      Map("class" -> AttrValue.Str("hello")),
      Map.empty,
      Seq(View.TextNode(() => "Hello, world!"))
    ))
    val emitted = backend.emit(FrontendModule(List(app), "App", "/"))

    // HTML shell uses importmap so the bundle can `import` from solid-js.
    assert(emitted.html.contains("importmap"))
    assert(emitted.html.contains("solid-js"))

    // Imports only what we use — no solid-js/h (broken in latest).
    assert(emitted.js.contains("import { createSignal, createEffect } from 'solid-js';"),
      s"Solid imports must be just createSignal + createEffect:\n${emitted.js}")
    assert(!emitted.js.contains("solid-js/h"),
      s"emit must NOT depend on solid-js/h (upstream-broken):\n${emitted.js}")
    assert(!emitted.js.contains("solid-js/web"),
      s"emit must NOT depend on solid-js/web (we do imperative DOM ourselves):\n${emitted.js}")

    assert(emitted.js.contains("function App(root)"))
    assert(emitted.js.contains("document.createElement('div')"))
    // Solid uses `class` natively (unlike React's className).
    assert(emitted.js.contains("setAttribute('class', 'hello')"),
      s"Solid must keep 'class':\n${emitted.js}")
    assert(emitted.js.contains("document.createTextNode('Hello, world!')"))
    assert(emitted.js.contains("App(document.getElementById('app'))"))
  }

  test("emit — ReactiveSignal lowers to createSignal at top of component") {
    val backend = new SolidFrameworkBackend
    val count = new ReactiveSignal[Int]("count", 0)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty,
      Seq(View.SignalText(count))
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("const [count, setCount] = createSignal(0);"),
      s"expected createSignal hoist:\n$js")
    // SignalText: createTextNode reads current value, createEffect
    // re-runs textContent assignment on signal change.
    assert(js.contains("createTextNode(count())"),
      s"text node initial value comes from signal getter:\n$js")
    assert(js.contains("createEffect(() => {") && js.contains(".textContent = count();"),
      s"createEffect must auto-track the signal read:\n$js")
  }

  test("emit — IncrementSignal lowers to addEventListener + functional setX") {
    val backend = new SolidFrameworkBackend
    val count = new ReactiveSignal[Int]("count", 5)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button",
      Map.empty,
      Map("click" -> EventHandler.IncrementSignal(count, by = 2)),
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("const [count, setCount] = createSignal(5);"))
    assert(js.contains("addEventListener('click', () => setCount(c => c + 2))"),
      s"expected functional setX:\n$js")
  }

  test("emit — SetSignalLiteral lowers to addEventListener + setX(literal)") {
    val backend = new SolidFrameworkBackend
    val greeting = new ReactiveSignal[String]("greeting", "Hello")
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button",
      Map.empty,
      Map("click" -> EventHandler.SetSignalLiteral(greeting, "world")),
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("const [greeting, setGreeting] = createSignal('Hello');"))
    assert(js.contains("addEventListener('click', () => setGreeting('world'))"))
  }

  test("emit — Fragment uses DocumentFragment") {
    val backend = new SolidFrameworkBackend
    val app = ComponentDef("App", Nil, _ => View.Fragment(Seq(
      View.Element("span", Map.empty, Map.empty, Seq(View.TextNode(() => "a"))),
      View.Element("span", Map.empty, Map.empty, Seq(View.TextNode(() => "b")))
    )))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("createDocumentFragment()"),
      s"Fragment must lower to DocumentFragment:\n$js")
  }

  test("emit — empty Fragment produces no appendChild on root") {
    val backend = new SolidFrameworkBackend
    val app = ComponentDef("App", Nil, _ => View.Fragment(Nil))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(!js.contains("root.appendChild"),
      s"Empty Fragment must not append to root:\n$js")
  }

  test("emit — Show takes the cond() snapshot branch") {
    val backend = new SolidFrameworkBackend
    val app = ComponentDef("App", Nil, _ => View.Show(
      cond      = () => true,
      whenTrue  = () => View.TextNode(() => "shown"),
      whenFalse = () => View.TextNode(() => "hidden")
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("'shown'"))
    assert(!js.contains("'hidden'"))
  }

  test("emit — For iterates items() snapshot") {
    val backend = new SolidFrameworkBackend
    val list = ComponentDef("App", Nil, _ => View.Element(
      "ul", Map.empty, Map.empty,
      Seq(View.For[String](
        items  = () => Seq("a", "b"),
        render = name => View.Element("li", Map.empty, Map.empty,
                          Seq(View.TextNode(() => name)))
      ))
    ))
    val js = backend.emit(FrontendModule(List(list), "App", "/")).js
    assert(js.contains("document.createElement('li')"))
    assert(js.contains("'a'"))
    assert(js.contains("'b'"))
  }

  test("emit — signal referenced ONLY in event handler still gets createSignal") {
    val backend = new SolidFrameworkBackend
    val count = new ReactiveSignal[Int]("count", 0)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button",
      Map.empty,
      Map("click" -> EventHandler.IncrementSignal(count)),
      Seq(View.TextNode(() => "Click"))
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("const [count, setCount] = createSignal(0);"),
      s"signals referenced only in handlers must be hoisted:\n$js")
  }

  test("emit — duplicate signal name + different initial throws loudly") {
    val backend = new SolidFrameworkBackend
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

  test("emit — JVM-closure events emit a marker, no listener") {
    val backend = new SolidFrameworkBackend
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button",
      Map.empty,
      Map("click" -> EventHandler.Simple(() => ())),
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("JVM closure"),
      s"expected JVM-closure marker:\n$js")
    assert(!js.contains("addEventListener"),
      s"closure handler must not emit a real listener:\n$js")
  }

  test("emit — XSS-y text content is escaped") {
    val backend = new SolidFrameworkBackend
    val sneaky = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty,
      Seq(View.TextNode(() => "</script><script>alert(1)</script>"))
    ))
    val js = backend.emit(FrontendModule(List(sneaky), "App", "/")).js
    assert(!js.contains("</script>"))
  }
