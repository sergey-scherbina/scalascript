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
    assert(g.contains("""__m.insert("class".to_string(), "root".to_string());"""),
      s"expected the `->` entry to lower to a HashMap insert, got:\n$g")

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
    assert(g.contains("move |__p0|"),
      s"expected the `_`-lambda to desugar to a closure, got:\n$g")
    assert(g.contains("__p0 + 1i64"),
      s"expected the placeholder body, got:\n$g")

  test("a program with no View primitives stays ui-free"):
    val src =
      """```scalascript
        |@main def run(): Unit = println("hi")
        |```
        |""".stripMargin
    val a = assets(src)
    assert(!a.contains("src/runtime/ui.rs"))
    assert(!a("src/runtime/mod.rs").contains("pub mod ui;"))
