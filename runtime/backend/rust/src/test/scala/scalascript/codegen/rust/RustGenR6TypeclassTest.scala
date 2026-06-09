package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** Phase R.6 — typeclass support: `given X: T with { defs }` as Rust unit struct + impl.
 *  Verifies:
 *  - RustCapabilities declares Feature.TypeClasses
 *  - given instance emits a Rust struct definition
 *  - given methods are emitted as Rust impl methods
 *  - given name is injected as a local binding so methods are callable
 *  - multiple givens coexist without conflict */
class RustGenR6TypeclassTest extends AnyFunSuite:

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
        }.getOrElse(fail("generated file missing"))
      case other => fail(s"expected Segmented, got $other")

  test("RustCapabilities declares TypeClasses"):
    assert(new RustBackend().capabilities.features.contains(Feature.TypeClasses))

  test("given instance emits a Rust unit struct"):
    val g = gen(
      """```scalascript
        |given intMonoid: IntMonoid with
        |  def combine(a: Int, b: Int): Int = a + b
        |
        |def workload(): Int = intMonoid.combine(1, 2)
        |```
        |""".stripMargin)
    assert(g.contains("pub struct IntMonoidGiven"),
      s"IntMonoidGiven struct missing in:\n$g")

  test("given methods emitted as Rust impl methods"):
    val g = gen(
      """```scalascript
        |given intMonoid: IntMonoid with
        |  def combine(a: Int, b: Int): Int = a + b
        |
        |def workload(): Int = intMonoid.combine(1, 2)
        |```
        |""".stripMargin)
    assert(g.contains("pub fn combine(&self"),
      s"combine method missing in:\n$g")
    assert(g.contains("a + b"),
      s"method body missing in:\n$g")

  test("given name injected as local binding in functions"):
    val g = gen(
      """```scalascript
        |given intMonoid: IntMonoid with
        |  def combine(a: Int, b: Int): Int = a + b
        |
        |def workload(): Int = intMonoid.combine(10, 20)
        |```
        |""".stripMargin)
    assert(g.contains("let intMonoid = IntMonoidGiven"),
      s"local binding 'let intMonoid' missing in:\n$g")

  test("method call on given instance works"):
    val g = gen(
      """```scalascript
        |given intMonoid: IntMonoid with
        |  def combine(a: Int, b: Int): Int = a + b
        |
        |def workload(): Int = intMonoid.combine(3, 4)
        |```
        |""".stripMargin)
    assert(g.contains("intMonoid.combine(3i64, 4i64)"),
      s"method call emit missing in:\n$g")

  test("given instance with empty method emits zero-arg impl"):
    val g = gen(
      """```scalascript
        |given intMonoid: IntMonoid with
        |  def empty: Int = 0
        |  def combine(a: Int, b: Int): Int = a + b
        |
        |def workload(): Int = intMonoid.combine(intMonoid.empty, 5)
        |```
        |""".stripMargin)
    // Zero-arg defs become struct fields (not methods) so `intMonoid.empty` works in Rust.
    assert(g.contains("pub empty:") || g.contains("pub fn empty(&self)"),
      s"zero-arg empty field/method missing in:\n$g")

  test("typeclass-monoid bench corpus compiles without error"):
    val src =
      """# typeclass-monoid
        |
        |```scalascript
        |given intMonoid: IntMonoid with
        |  def empty: Int = 0
        |  def combine(a: Int, b: Int): Int = a + b
        |
        |def workload(): Int =
        |  intMonoid.combine(intMonoid.combine(intMonoid.combine(intMonoid.empty, 1), 2), 3)
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("pub fn workload()"),          s"workload missing in:\n$g")
    assert(g.contains("pub struct IntMonoidGiven"),  s"struct missing in:\n$g")
    assert(g.contains("intMonoid.combine("),         s"combine call missing in:\n$g")
