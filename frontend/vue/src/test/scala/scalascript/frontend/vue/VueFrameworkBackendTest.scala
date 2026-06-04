package scalascript.frontend.vue

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

class VueFrameworkBackendTest extends AnyFunSuite:

  test("ServiceLoader discovers VueFrameworkBackend") {
    FrontendFrameworks.setBackend(null)
    val impls = FrontendFrameworks.all()
    assert(impls.exists(_.name == "vue"),
      s"Expected 'vue' in [${impls.map(_.name).mkString(", ")}]")
  }

  test("name + capabilities + jsDeps") {
    val backend = new VueFrameworkBackend
    assert(backend.name == "vue")
    assert(backend.capabilities.contains(Capability.TwoWayBinding))
    assert(backend.capabilities.contains(Capability.Suspense))
    val depNames = backend.jsDeps.map(_.npmName).toSet
    assert(depNames.contains("vue"))
  }

  test("emit — unknown entryPoint throws with helpful message") {
    val backend = new VueFrameworkBackend
    val ex = intercept[IllegalArgumentException] {
      backend.emit(FrontendModule(Nil, "Missing", "/"))
    }
    assert(ex.getMessage.contains("Missing"))
  }

  test("emit — minimal SPA produces Vue setup + render functions") {
    val backend = new VueFrameworkBackend
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div",
      Map("class" -> AttrValue.Str("hello")),
      Map.empty,
      Seq(View.TextNode(() => "Hello, world!"))
    ))
    val emitted = backend.emit(FrontendModule(List(app), "App", "/"))

    // HTML shell uses importmap pointing at esm.sh.
    assert(emitted.html.contains("importmap"))
    assert(emitted.html.contains("vue"))

    // JS imports vue 3 primitives.
    assert(emitted.js.contains("import { ref, h, Fragment, createApp } from 'vue';"))

    // Component is an options object with setup + render.
    assert(emitted.js.contains("const App = {"))
    assert(emitted.js.contains("setup() {"))
    assert(emitted.js.contains("render() {"))
    // Vue uses `class` natively (like Solid, unlike React).
    assert(emitted.js.contains("'class': 'hello'"),
      s"Vue must keep 'class', not remap to 'className':\n${emitted.js}")
    assert(!emitted.js.contains("className"),
      s"Vue must NOT use 'className':\n${emitted.js}")
    // Mounted via createApp + mount.
    assert(emitted.js.contains("createApp(App).mount('#app')"))
  }

  test("emit — ReactiveSignal lowers to ref() in setup, returned for proxy") {
    val backend = new VueFrameworkBackend
    val count = new ReactiveSignal[Int]("count", 0)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty,
      Seq(View.SignalText(count))
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("const count = ref(0);"),
      s"expected ref(0) in setup:\n$js")
    assert(js.contains("return { count };"),
      s"setup must return ref for proxy unwrap:\n$js")
    // SignalText interpolates this.count (auto-unwrapped by proxy).
    assert(js.contains("[this.count]"),
      s"SignalText must read via this.count, embedded in children array:\n$js")
  }

  test("emit — IncrementSignal lowers to this.x = this.x + by (with proxy this)") {
    val backend = new VueFrameworkBackend
    val count = new ReactiveSignal[Int]("count", 5)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button",
      Map.empty,
      Map("click" -> EventHandler.IncrementSignal(count, by = 2)),
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("const count = ref(5);"))
    // Arrow function — captures render()'s `this` (the proxy) lexically.
    assert(js.contains("'onClick': () => { this.count = this.count + 2; }"),
      s"expected this.x = this.x + by with arrow function:\n$js")
  }

  test("emit — SetSignalLiteral lowers to this.x = value") {
    val backend = new VueFrameworkBackend
    val greeting = new ReactiveSignal[String]("greeting", "Hello")
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button",
      Map.empty,
      Map("click" -> EventHandler.SetSignalLiteral(greeting, "world")),
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("const greeting = ref('Hello');"))
    assert(js.contains("'onClick': () => { this.greeting = 'world'; }"))
  }

  test("emit — SeedSignal syncs from source while pristine and dirty-writes on input") {
    val backend = new VueFrameworkBackend
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
    assert(js.contains("import { ref, h, Fragment, watch, createApp } from 'vue';"), s"watch import:\n$js")
    assert(js.contains("const sourceName = ref('Ada');"), s"source ref:\n$js")
    assert(js.contains("const draftName = ref('Ada');"), s"draft ref:\n$js")
    assert(js.contains("const draftName_pristine = ref(true);"), s"pristine ref:\n$js")
    assert(js.contains("watch(sourceName, (v) => { if (draftName_pristine.value) draftName.value = v; });"),
      s"source sync:\n$js")
    assert(js.contains("'onInput': (e) => { this.draftName_pristine = false; this.draftName = e.target.value; }"),
      s"dirty input write:\n$js")
    assert(js.contains("return { sourceName, draftName, draftName_pristine };"), s"returned refs:\n$js")
  }

  test("emit — Fragment becomes Vue Fragment element") {
    val backend = new VueFrameworkBackend
    val app = ComponentDef("App", Nil, _ => View.Fragment(Seq(
      View.Element("span", Map.empty, Map.empty, Seq(View.TextNode(() => "a"))),
      View.Element("span", Map.empty, Map.empty, Seq(View.TextNode(() => "b")))
    )))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("h(Fragment"),
      s"Vue Fragment must use the Fragment symbol:\n$js")
  }

  test("emit — Show takes the cond() snapshot branch") {
    val backend = new VueFrameworkBackend
    val app = ComponentDef("App", Nil, _ => View.Show(
      cond      = () => true,
      whenTrue  = () => View.TextNode(() => "shown"),
      whenFalse = () => View.TextNode(() => "hidden")
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("'shown'"))
    assert(!js.contains("'hidden'"))
  }

  test("emit — For becomes array of children") {
    val backend = new VueFrameworkBackend
    val list = ComponentDef("App", Nil, _ => View.Element(
      "ul", Map.empty, Map.empty,
      Seq(View.For[String](
        items  = () => Seq("a", "b"),
        render = name => View.Element("li", Map.empty, Map.empty,
                          Seq(View.TextNode(() => name)))
      ))
    ))
    val js = backend.emit(FrontendModule(List(list), "App", "/")).js
    assert(js.contains("h('ul', null, [[h('li'"),
      s"For must produce array of children inside ul:\n$js")
  }

  test("emit — signal referenced ONLY in event handler still gets ref") {
    val backend = new VueFrameworkBackend
    val count = new ReactiveSignal[Int]("count", 0)
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button",
      Map.empty,
      Map("click" -> EventHandler.IncrementSignal(count)),
      Seq(View.TextNode(() => "Click"))
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("const count = ref(0);"),
      s"signals referenced only in handlers must be hoisted to setup:\n$js")
  }

  test("emit — duplicate signal name + different initial throws loudly") {
    val backend = new VueFrameworkBackend
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

  test("emit — JVM-closure events emit a marker, no real handler") {
    val backend = new VueFrameworkBackend
    val app = ComponentDef("App", Nil, _ => View.Element(
      "button",
      Map.empty,
      Map("click" -> EventHandler.Simple(() => ())),
      Seq.empty
    ))
    val js = backend.emit(FrontendModule(List(app), "App", "/")).js
    assert(js.contains("JVM closure"),
      s"expected JVM-closure marker:\n$js")
  }

  test("emit — XSS-y text content is escaped") {
    val backend = new VueFrameworkBackend
    val sneaky = ComponentDef("App", Nil, _ => View.Element(
      "div", Map.empty, Map.empty,
      Seq(View.TextNode(() => "</script><script>alert(1)</script>"))
    ))
    val js = backend.emit(FrontendModule(List(sneaky), "App", "/")).js
    assert(!js.contains("</script>"))
  }

  test("emit — DOM event name maps to camelCase Vue handler prop") {
    val backend = new VueFrameworkBackend
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
