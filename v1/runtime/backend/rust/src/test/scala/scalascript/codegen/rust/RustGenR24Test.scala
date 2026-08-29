package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** Phase R.2.4 — closures + function types. */
class RustGenR24Test extends AnyFunSuite:

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

  test("Type.Function (A => B) at a param position lowers to `impl Fn(A) -> B`"):
    val src =
      """```scalascript
        |def apply(f: Long => Long, x: Long): Long = f(x)
        |```
        |""".stripMargin
    val g = gen(src)
    // `+ 'static` since 2026-08-11: a callback that is only passed through needs no bound, but one
    // STORED in a struct or enum field does -- Rc::new(f) on a bare `impl Fn` is E0310, "may not
    // live long enough", which is what std/ui/input.ssc hit. The closures this lane emits are
    // `move |...|` over owned captures, so the bound states what was already true.
    // (rust-std-corpus-badrust-column.)
    //
    // NARROWED 2026-08-29 to a per-parameter check (`paramEscapesViaRcCtor`): the blanket bound
    // broke `scanOpaque(…, validate, elements, …)` (`uniml/xml`'s `Doc.scala`'s `scanCData`) —
    // `validate` closes over `elements: &mut Vec<String>`, a `&mut` PARAMETER of the ENCLOSING
    // def, not an owned capture, and the closure is only ever CALLED (never stored), so the
    // `'static` bound demanded a lifetime a stack-local `&mut` reference can never have
    // (`error[E0521]`). `apply` here matches that "only called" shape — `f` never reaches an
    // `Rc<dyn Fn>` ctor field — so it no longer gets the bound; see the test below for the case
    // that still needs it.
    assert(g.contains("pub fn apply(f: impl Fn(i64) -> i64, x: i64) -> i64 {"),
      s"function-type-emit not found in:\n$g")
    assert(g.contains("f(x)"),
      s"closure-param call not found in:\n$g")

  test("Type.Function with no return value (Unit) drops the arrow"):
    val src =
      """```scalascript
        |def run(f: Long => Unit, x: Long): Unit = f(x)
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("pub fn run(f: impl Fn(i64), x: i64) {"),
      s"Unit-returning Fn not emitted as `impl Fn(i64)`:\n$g")

  test("a closure-typed parameter STORED via an Rc<dyn Fn> ctor field keeps `+ 'static`"):
    // `selectFrom(items, key, optionFn, …) = SelectFromNode(items, key, optionFn, …)`
    // (`std/ui/input.ssc`) — `key`/`optionFn` are passed BARE into a ctor whose corresponding
    // fields are closure-typed (`Rc<dyn Fn>`); the ctor-argument renderer wraps them in
    // `Rc::new(...)`, which demands `'static` — `error[E0310]: may not live long enough` without
    // the bound. `paramEscapesViaRcCtor` must still detect this and keep it.
    val src =
      """```scalascript
        |case class Holder(onClick: Long => Long)
        |
        |def register(onClick: Long => Long): Holder = Holder(onClick)
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("pub fn register(onClick: impl Fn(i64) -> i64 + 'static) -> Holder {"),
      s"a param stored via an Rc<dyn Fn> ctor field must keep `+ 'static`:\n$g")

  test("(params) => body lowers to a Rust `move |params| { body }` closure"):
    val src =
      """```scalascript
        |def apply(f: Long => Long, x: Long): Long = f(x)
        |def use(): Long = apply((x: Long) => x * 2, 21)
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("move |x: i64| { (x * 2i64) }"),
      s"closure-emit not found in:\n$g")

  test("call to a closure-typed parameter does not require user-def registration"):
    // The R.2.1 strict-rejection of unknown free names is relaxed in
    // R.2.4 — `f(x)` inside `apply` resolves to the closure parameter.
    val src =
      """```scalascript
        |def apply(f: Long => Long, x: Long): Long = f(x)
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("f(x)"))
