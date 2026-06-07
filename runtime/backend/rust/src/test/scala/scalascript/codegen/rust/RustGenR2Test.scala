package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** Phase R.2 — literals/blocks/if/infix + typed params + user-fn calls
 *  + s"…" interpolation. */
class RustGenR2Test extends AnyFunSuite:

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

  private def diagnostics(src: String): List[Diagnostic] =
    new RustBackend().compile(Normalize(Parser.parse(src)), emptyOpts) match
      case CompileResult.Failed(ds) => ds
      case other => fail(s"expected Failed, got $other")

  // ── typed params + non-Unit return ─────────────────────────────────

  test("def with typed Long params + Long return emits a Rust signature"):
    val src =
      """```scalascript
        |def add(a: Long, b: Long): Long = a + b
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("pub fn add(a: i64, b: i64) -> i64 {"))
    assert(g.contains("(a + b)"))

  test("String params map to Rust `String`"):
    val src =
      """```scalascript
        |def echo(s: String): String = s
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("pub fn echo(s: String) -> String {"))

  // ── if expression ──────────────────────────────────────────────────

  test("if/else lowers to a Rust if expression"):
    val src =
      """```scalascript
        |def sign(n: Long): Long = if (n < 0) -1 else 1
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("if (n < 0i64) { -1i64 } else { 1i64 }"),
      s"if-emit not found in:\n$g")

  // ── infix operators ────────────────────────────────────────────────

  test("arithmetic + comparison infix operators emit as Rust infix"):
    val src =
      """```scalascript
        |def f(a: Long, b: Long): Boolean = (a + b) > (a - b)
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("((a + b) > (a - b))"),
      s"infix-emit not found in:\n$g")

  test("unsupported infix operator yields a structured diagnostic"):
    val src =
      """```scalascript
        |def bad(a: Long, b: Long): Long = a ^^^ b
        |```
        |""".stripMargin
    val ds = diagnostics(src)
    assert(ds.exists {
      case Diagnostic.Generic(m, _) => m.contains("^^^")
      case _                        => false
    }, s"diags: $ds")

  // ── user-defined fn call ───────────────────────────────────────────

  test("call to in-scope user fn emits a direct Rust call"):
    val src =
      """```scalascript
        |def inc(n: Long): Long = n + 1
        |def use(n: Long): Long = inc(n) + 2
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("pub fn use(n: i64) -> i64 {"))
    assert(g.contains("(inc(n) + 2i64)"),
      s"user-fn call not found in:\n$g")

  test("call to an unknown free name passes through to Rust (cargo rejects)"):
    // R.2.4 widened the apply path so closure-parameter calls (`f(x)`)
    // succeed even when `f` is neither a known fn nor a known ctor.
    // The flip side: unresolved free names like `mystery` are now
    // emitted as-is and rejected by `cargo build`, not by RustCodeWalk.
    val src =
      """```scalascript
        |def use(n: Long): Long = mystery(n) + 1
        |```
        |""".stripMargin
    new RustBackend().compile(Normalize(Parser.parse(src)), emptyOpts) match
      case CompileResult.Segmented(segs) =>
        val g = segs.collectFirst {
          case Segment.Asset("src/generated/ssc_program.rs", b, _) => new String(b, "UTF-8")
        }.getOrElse(fail("generated module missing"))
        assert(g.contains("(mystery(n) + 1i64)"),
          s"expected pass-through mystery call:\n$g")
      case CompileResult.Failed(ds) =>
        fail(s"expected Segmented, got Failed: $ds")
      case other => fail(s"expected Segmented, got $other")

  // ── s"…" interpolation ─────────────────────────────────────────────

  test("""s"…" interpolation lowers to Rust format!(...)"""):
    val src =
      """```scalascript
        |def greet(name: String, n: Long): String = s"Hi $name #$n"
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("""format!("Hi {} #{}", name, n)"""),
      s"format-emit not found in:\n$g")

  test("""s"…" with braces in literal text escapes them as {{/}}"""):
    val src =
      """```scalascript
        |def fmt(x: Long): String = s"{ $x }"
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("""format!("{{ {} }}", x)"""),
      s"brace-escape not found in:\n$g")

  // ── end-to-end fib shape (no cargo dep) ────────────────────────────

  test("fib-like recursive def renders cleanly without diagnostics"):
    val src =
      """```scalascript
        |def fib(n: Long): Long =
        |  if (n < 2) n else fib(n - 1) + fib(n - 2)
        |
        |@main def run(): Unit = println(fib(10))
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("pub fn fib(n: i64) -> i64 {"))
    assert(g.contains("pub fn run() {"))
    assert(g.contains("crate::runtime::_println(fib(10i64));"),
      s"println(fib(10)) not found in:\n$g")
