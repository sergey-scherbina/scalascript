package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** Phase R.2.3 — Scala 3 `enum` → Rust enum + `match`. */
class RustGenR23Test extends AnyFunSuite:

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

  test("Scala 3 enum lowers to a Rust enum with struct-style variants"):
    val src =
      """```scalascript
        |enum Shape:
        |  case Circle(r: Double)
        |  case Square(s: Double)
        |
        |def noop(): Unit = ()
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("pub enum Shape {"))
    assert(g.contains("Circle { r: f64 }"))
    assert(g.contains("Square { s: f64 }"))

  test("constructor app emits EnumName::Ctor { field: arg }"):
    val src =
      """```scalascript
        |enum Shape:
        |  case Circle(r: Double)
        |
        |def mk(): Shape = Circle(1.5)
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("Shape::Circle { r: 1.5f64 }"))

  test("Term.Match lowers to Rust match with pattern destructuring"):
    val src =
      """```scalascript
        |enum Shape:
        |  case Circle(r: Double)
        |  case Square(s: Double)
        |
        |def area(sh: Shape): Double = sh match
        |  case Circle(r) => 3.14 * r * r
        |  case Square(s) => s * s
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("match sh {"))
    assert(g.contains("Shape::Circle { r } =>"))
    assert(g.contains("Shape::Square { s } =>"))

  test("RustCapabilities declares PatternMatching"):
    val caps = new RustBackend().capabilities.features
    assert(caps.contains(Feature.PatternMatching))
