package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** rust-web-toolkit — bring the high-level web toolkit up on Rust.
 *
 *  I1 — string interpolation with a compound `${ expr }` splice. scalameta
 *  wraps the braced splice in a `Term.Block`; before the fix `renderTerm`
 *  rejected it as "unsupported expression: Term.Block". Spec:
 *  `specs/rust-web-toolkit.md`.
 */
class RustGenWebToolkitTest extends AnyFunSuite:

  private val emptyOpts = BackendOptions(
    baseDir = None, outputDir = None,
    optimizationLevel = 0, emitSourceMaps = false, emitAssertions = false,
    target = None, extra = Map.empty
  )

  private def gen(src: String): String =
    new RustBackend().compile(Normalize(Parser.parse(src)), emptyOpts) match
      case CompileResult.Segmented(segs) =>
        segs.collectFirst {
          case Segment.Asset("src/generated/ssc_program.rs", b, _) => new String(b, "UTF-8")
        }.getOrElse(fail("generated module missing"))
      case other => fail(s"expected Segmented, got $other")

  private def assets(src: String): Map[String, String] =
    new RustBackend().compile(Normalize(Parser.parse(src)), emptyOpts) match
      case CompileResult.Segmented(segs) =>
        segs.collect { case Segment.Asset(n, b, _) => n -> new String(b, "UTF-8") }.toMap
      case other => fail(s"expected Segmented, got $other")

  // ── I1: compound interpolation splices ─────────────────────────────

  test("s\"…${literal arith}…\" lowers the braced splice to a format! arg"):
    val src =
      """```scalascript
        |def two(): String = s"sum=${1 + 2}"
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("""format!("sum={}", (1i64 + 2i64))"""),
      s"expected a format! with the unwrapped arithmetic splice, got:\n$g")

  test("s\"…${expr over a param}…\" renders the inner expression, not a Block"):
    val src =
      """```scalascript
        |def label(n: Long): String = s"n=${n + 1}"
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("""format!("n={}", (n + 1i64))"""),
      s"expected the splice to render `(n + 1i64)`, got:\n$g")

  test("a bare $name splice still works (no regression)"):
    val src =
      """```scalascript
        |def greet(who: String): String = s"hi $who"
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("""format!("hi {}", who)"""),
      s"expected the bare-name splice unchanged, got:\n$g")

  // ── S1: HTML/SSR binding of the std/ui View primitives ─────────────

  test("textNode/fragment usage emits the ui intrinsic calls"):
    val src =
      """```scalascript
        |@main def run(): Unit =
        |  val a = textNode("hi")
        |  val b = fragment(List(a))
        |  ()
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("crate::runtime::ui::_ui_text"),
      s"expected a _ui_text call, got:\n$g")
    assert(g.contains("crate::runtime::ui::_ui_fragment"),
      s"expected a _ui_fragment call, got:\n$g")

  test("using a View primitive wires the ui runtime module + asset"):
    val src =
      """```scalascript
        |@main def run(): Unit =
        |  val a = textNode("hi")
        |  ()
        |```
        |""".stripMargin
    val a = assets(src)
    assert(a.contains("src/runtime/ui.rs"),
      s"ui.rs missing from: ${a.keys.toList.sorted}")
    assert(a("src/runtime/mod.rs").contains("pub mod ui;"))
    val ui = a("src/runtime/ui.rs")
    assert(ui.contains("pub fn _ui_render(v: View) -> String"))
    assert(ui.contains("pub enum View"))
    assert(ui.contains("fn _ui_is_void"), "void-element handling should be present")

  test("signal SSR runtime carries + renders the initial value (S5a)"):
    val src =
      """```scalascript
        |@main def run(): Unit =
        |  val a = textNode("hi")
        |  ()
        |```
        |""".stripMargin
    val ui = assets(src)("src/runtime/ui.rs")
    // signal(name, default) carries name + default; signalText emits a `data-ssc-text`
    // span rendering the current value; showSignal picks a branch from the value.
    assert(ui.contains("pub fn _ui_signal<T: Into<Value>>"),
      s"_ui_signal should carry its default into a Value, got:\n$ui")
    assert(ui.contains("\"data-ssc-text\".to_string()") && ui.contains("View::Text(v.show())"),
      "signalText should render the value inside a data-ssc-text span")
    assert(ui.contains("if cond.is_truthy()"),
      "showSignal should pick a branch from the signal's value")
    // S5b client wiring: inputChange marks the input; serve appends the reactivity script.
    assert(ui.contains("data-ssc-input") && ui.contains("_UI_CLIENT_SCRIPT"),
      "inputChange should mark the input + a client script const should exist")
    // S5b.2-B derived signals: computedSignal evaluates its thunk; seedSignal carries the name.
    assert(ui.contains("pub fn _ui_computed_signal") && ui.contains("pub fn _ui_seed_signal"),
      "computed/seed signal runtimes should be present")

  test("set/toggle signal client wiring (S5 deferred refinement)"):
    val src =
      """```scalascript
        |@main def run(): Unit =
        |  val a = textNode("hi")
        |  ()
        |```
        |""".stripMargin
    val ui = assets(src)("src/runtime/ui.rs")
    // setSignal/toggleSignal used to be no-op (Value::Unit) → no client wiring. Now they
    // encode markers that _ui_element surfaces as data-ssc-set / data-ssc-toggle attributes.
    assert(ui.contains("pub fn _ui_set_signal<T: Into<Value>>") &&
           ui.contains("ssc-set:{}:{}"),
      s"setSignal should encode an ssc-set:<name>:<value> marker, got:\n$ui")
    assert(ui.contains("ssc-toggle:{}") &&
           ui.contains("Value::Str(format!(\"ssc-toggle:{}\", name))"),
      "toggleSignal should encode an ssc-toggle:<name> marker")
    assert(ui.contains("data-ssc-set") && ui.contains("data-ssc-toggle"),
      "_ui_element should surface set/toggle markers as data-ssc-* attributes")
    // The appended client script wires click → set/toggle, with server-push so the poll
    // doesn't revert the local change.
    assert(ui.contains("getAttribute('data-ssc-set')") &&
           ui.contains("getAttribute('data-ssc-toggle')") &&
           ui.contains("_sscPush"),
      "client script should handle click→set/toggle and persist via _sscPush")

  test("SSE push transport: /__ssc/events streams signal updates (S5 deferred refinement)"):
    val src =
      """```scalascript
        |@main def run(): Unit =
        |  serve(element("div", Map(), Map(), List(signalText(signal("c", "0")))), 8234)
        |```
        |""".stripMargin
    val a = assets(src)
    val http = a("src/runtime/http.rs")
    // Server side: a broadcast channel + an /__ssc/events SSE endpoint that streams
    // `data: <state-json>` frames; push notifies subscribers.
    assert(http.contains("broadcast::Sender<String>") && http.contains("fn ssc_events"),
      s"http.rs should have an SSE broadcast channel, got:\n$http")
    assert(http.contains("/__ssc/events") && http.contains("text/event-stream") &&
           http.contains("StreamBody") && http.contains("fn ssc_set_and_notify"),
      "http.rs should serve /__ssc/events as a text/event-stream StreamBody + notify on push")
    assert(http.contains("BoxBody<Bytes, std::convert::Infallible>"),
      "response bodies should unify to BoxBody so the SSE stream and Full coexist")
    // Client side: prefer EventSource('/__ssc/events'), fall back to the poll.
    val ui = a("src/runtime/ui.rs")
    assert(ui.contains("EventSource") && ui.contains("/__ssc/events") &&
           ui.contains("setInterval"),
      "client should use EventSource with a setInterval poll fallback")

  test("computed signal reading another signal compiles + SSRs the dep value (S5)"):
    // Before this fix `computedSignal(() => loc())` emitted `loc()`, which doesn't
    // compile (`Value` is not callable). A 0-arg apply on a Signal-typed local is a
    // signal READ → lowers to `loc.signal_value().show()`.
    val src =
      """```scalascript
        |@main def run(): Unit =
        |  val loc = signal("locale", "fr")
        |  val txt = computedSignal(() => loc())
        |  println(renderHtml(element("div", Map(), Map(), List(signalText(txt)))))
        |```
        |""".stripMargin
    val prog = gen(src)
    assert(prog.contains("loc.signal_value().show()"),
      s"a signal read loc() inside a computed thunk should lower to loc.signal_value().show(), got:\n$prog")
    assert(!prog.contains("{ loc() }"),
      "the bare uncallable `loc()` should no longer be emitted")
    // signal_value takes &self (so a repeatedly-called computed closure doesn't move it).
    val v = assets(src)("src/value.rs")
    assert(v.contains("pub fn signal_value(&self)"),
      "signal_value should take &self for use in Fn closures")

  test("renderHtml(view) wires the SSR render entry"):
    val src =
      """```scalascript
        |@main def run(): Unit =
        |  println(renderHtml(fragment(List(textNode("hi")))))
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("crate::runtime::ui::_ui_render"),
      s"expected a _ui_render call, got:\n$g")
    val a = assets(src)
    assert(a("src/runtime/ui.rs").contains("pub fn _ui_render(v: View) -> String"))

  test("element with Map attrs emits _ui_element with tuple attrs (-> arrow)"):
    val src =
      """```scalascript
        |@main def run(): Unit =
        |  println(renderHtml(element("div", Map("class" -> "root"), Map(), List(textNode("hi")))))
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("crate::runtime::ui::_ui_element"),
      s"expected a _ui_element call, got:\n$g")
    assert(g.contains("""__m.insert("class".to_string(), crate::runtime::ui::_ui_attr("root".to_string()));"""),
      s"expected the attr value to be coerced via _ui_attr, got:\n$g")

  test("serve(view, port) dispatches to the SSR _ui_serve overload"):
    val src =
      """```scalascript
        |@main def run(): Unit =
        |  serve(element("div", Map(), Map(), List(textNode("hi"))), 8099)
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("crate::runtime::http::_ui_serve"),
      s"expected serve(view,port) to dispatch to _ui_serve, got:\n$g")
    val a = assets(src)
    assert(a("src/runtime/http.rs").contains("pub fn _ui_serve"),
      "http.rs should carry the _ui_serve overload when ui is used")
    assert(a.contains("src/runtime/ui.rs"), "ui.rs must be present for serve(view,port)")
    // S5b.2 server-push: serve exposes the signal store endpoints + broadcast; the client polls.
    val http = a("src/runtime/http.rs")
    assert(http.contains("/__ssc/push") && http.contains("/__ssc/state")
      && http.contains("pub fn _ui_broadcast_signal"),
      "serve(view,port) should expose the signal-push/state endpoints + broadcast")
    assert(a("src/runtime/ui.rs").contains("/__ssc/state"),
      "the client runtime should poll /__ssc/state for server-pushed signal updates")

  test("pure route/serve(port) program omits the ui _ui_serve overload"):
    val src =
      """```scalascript
        |@main def run(): Unit = serve(8080)
        |```
        |""".stripMargin
    val a = assets(src)
    assert(a.contains("src/runtime/http.rs"))
    assert(!a("src/runtime/http.rs").contains("_ui_serve"),
      "a pure-http program must NOT reference runtime::ui via _ui_serve")
    assert(!a.contains("src/runtime/ui.rs"))

  test("a block expression in value position lowers to a Rust block"):
    val src =
      """```scalascript
        |def f(): Long =
        |  val x = { val a = 2; a + 1 }
        |  x
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("let x = { let a = 2i64;"),
      s"expected the block-valued binding to lower to a Rust block, got:\n$g")

  test("placeholder _-lambda desugars to a Rust closure"):
    val src =
      """```scalascript
        |def inc(xs: List[Long]): List[Long] = xs.map(_ + 1)
        |```
        |""".stripMargin
    val g = gen(src)
    // An inline iterator-arg lambda borrows (no `move`) so captures can be re-read.
    assert(g.contains("|__p0|"),
      s"expected the `_`-lambda to desugar to a closure, got:\n$g")
    assert(g.contains("__p0 + 1i64"),
      s"expected the placeholder body, got:\n$g")

  test("List ++ concat lowers to a Rust concat"):
    val src =
      """```scalascript
        |def cat(a: List[Long], b: List[Long]): List[Long] = a ++ b
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains(".concat()"),
      s"expected `a ++ b` to lower to a Vec concat, got:\n$g")

  test("a named call argument lowers to a positional Rust argument"):
    // `f(gap = 12)` parses as a `Term.Assign`; a Rust call is positional, so the
    // `name =` must be dropped (else rustc reads it as an assignment: `cannot find value`).
    val src =
      """```scalascript
        |def box(gap: Long): Long = gap
        |@main def run(): Unit = println(box(gap = 12))
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("r#box(12i64)"),
      s"expected named arg to lower to a positional `12i64`, got:\n$g")
    assert(!g.contains("gap = 12i64"),
      s"named arg should not emit a Rust assignment, got:\n$g")

  test("a call omitting trailing default params fills the defaults"):
    // Rust has no default parameters, so `greet("hi")` must be lowered with the
    // `punct`/`loud` defaults filled in, else rustc rejects the arity.
    val src =
      """```scalascript
        |def greet(name: String, punct: String = "!", loud: Boolean = false): String = name
        |@main def run(): Unit = println(greet("hi"))
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("""greet("hi".to_string(), "!".to_string(), false)"""),
      s"expected omitted defaults to be filled, got:\n$g")

  test("a program with no View primitives stays ui-free"):
    val src =
      """```scalascript
        |@main def run(): Unit = println("hi")
        |```
        |""".stripMargin
    val a = assets(src)
    assert(!a.contains("src/runtime/ui.rs"))
    assert(!a("src/runtime/mod.rs").contains("pub mod ui;"))

  // ── identity catch-all over an exhaustive enum match ───────────────
  // `std/ui/lower` ends its TkNode match with `case alreadyLowered => alreadyLowered`
  // — a JVM-side idempotency passthrough.  On the statically-typed Rust enum the
  // variant arms are exhaustive, so the catch-all can't fire and its body (the bound
  // enum value) would mistype against the match's result.  Drop it; let rustc check
  // exhaustiveness over the variants.
  test("trailing identity catch-all on a covered enum match is dropped"):
    val src =
      """```scalascript
        |sealed trait Shape
        |case class Circle(r: Long) extends Shape
        |case class Square(s: Long) extends Shape
        |
        |def norm(sh: Shape): Shape = sh match
        |  case Circle(r) => Square(r)
        |  case Square(s) => Square(s)
        |  case other     => other
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("Shape::Circle") && g.contains("Shape::Square"),
      s"expected the two variant arms, got:\n$g")
    assert(!g.contains("=> other"),
      s"identity catch-all should be dropped on an exhaustive enum match, got:\n$g")
