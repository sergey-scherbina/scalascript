package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** Curried / multi-parameter-group functions on the Rust backend.
 *
 *  Previously RustGen rejected any `def f(a)(b)` with "multiple parameter groups;
 *  R.2 accepts a single (…) group", and a curried call `f(a)(b)` reported "no
 *  resolvable name". Both the def signature and the call now flatten the groups
 *  into a single Rust fn / single call. (`using` evidence params still need
 *  typeclass→trait monomorphisation — a separate follow-up.)
 */
class RustGenCurriedDefsTest extends AnyFunSuite:

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

  test("two-group curried def + call flatten into one Rust fn / one call"):
    val src =
      """```scalascript
        |def add(a: Int)(b: Int): Int = a + b
        |def workload(): Int = add(2)(3)
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("pub fn add(a: i64, b: i64) -> i64"), g)
    assert(g.contains("add(2i64, 3i64)"), g)

  test("three-group curried def + call flatten in source order"):
    val src =
      """```scalascript
        |def combine(a: Int)(b: Int)(c: Int): Int = a + b * c
        |def workload(): Int = combine(1)(2)(3)
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("pub fn combine(a: i64, b: i64, c: i64) -> i64"), g)
    assert(g.contains("combine(1i64, 2i64, 3i64)"), g)

  test("curried def with a String group flattens"):
    val src =
      """```scalascript
        |def label(prefix: String)(n: Int): String = prefix + n.toString
        |def workload(): Int = label("x")(5).length
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("pub fn label(prefix: String, n: i64) -> String"), g)
    assert(g.contains("""label("x".to_string(), 5i64)"""), g)
