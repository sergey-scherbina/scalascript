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

  test("sealed trait + case class ADT emits Rust enum variants"):
    val src =
      """```scalascript
        |sealed trait Shape
        |case class Circle(r: Double) extends Shape
        |case class Square(s: Double) extends Shape
        |
        |def area(s: Shape): Double = s match
        |  case Circle(r) => 3.14 * r * r
        |  case Square(s) => s * s
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("pub enum Shape {"))
    assert(g.contains("Circle { r: f64 }"))
    assert(g.contains("Square { s: f64 }"))
    assert(g.contains("Shape::Circle { r: "))

  test("sealed trait case-class constructors call as enum variants"):
    val src =
      """```scalascript
        |sealed trait Shape
        |case class Circle(r: Double) extends Shape
        |
        |def mk(): Shape = Circle(1.5)
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("Shape::Circle { r: 1.5f64 }"))

  test("tuple type maps to Rust tuple syntax"):
    val src =
      """```scalascript
        |def make(): (Int, Double, String) = (1, 2.0, "ok")
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("pub fn make() -> (i64, f64, String)"))
    assert(g.contains("(1i64, 2f64, \"ok\".to_string())"))

  test("tuple ++ tuple-literal flattening emits one wider tuple"):
    val src =
      """```scalascript
        |def concat(): (Int, Int, Int, Int) = (1, 2) ++ (3, 4)
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("pub fn concat() -> (i64, i64, i64, i64)"))
    assert(g.contains("(1i64, 2i64, 3i64, 4i64)"))

  test("tuple ++ nested-literal chain flattens recursively"):
    val src =
      """```scalascript
        |def concat(): (Int, Int, Int, Int, Int, Int) = (1, 2) ++ (3, 4) ++ (5, 6)
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("pub fn concat() -> (i64, i64, i64, i64, i64, i64)"))
    assert(g.contains("(1i64, 2i64, 3i64, 4i64, 5i64, 6i64)"))

  test("String.split + trim + toInt lowers to String helpers and parse"):
    val src =
      """```scalascript
        |def sum(): Int = "1,2,3".split(",").map(s => s.trim.toInt).foldLeft(0)((a, b) => a + b)
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains(""".split(",").map(|p| p.to_string()).collect::<Vec<String>>()"""))
    assert(g.contains(".trim().to_string()"))
    assert(g.contains(".parse::<i32>().unwrap_or(0)"))

  test("String.toInt uses numeric parse on string literals"):
    val src =
      """```scalascript
        |def value(): Int = "42".toInt
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("42".to_string().parse::<i32>().unwrap_or(0)"))

  test("Option type maps to Rust Option and constructors"):
    val src =
      """```scalascript
        |def mk(v: Int): Option[Int] =
        |  if v % 2 == 0 then Some(v) else None
        |
        |def empty(): Option[Int] = None
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("pub fn mk(v: i64) -> Option<i64>"))
    assert(g.contains("if v % 2 == 0 {")
    assert(g.contains("Some(v)"))
    assert(g.contains("empty() -> Option<i64>"))
    assert(g.contains("None"))

  test("Option monadic methods map/flatMap/getOrElse"):
    val src =
      """```scalascript
        |def lookup(i: Int): Option[Int] = if i % 2 == 0 then Some(i * 2) else None
        |def work(i: Int): Int = Some(i).flatMap(x => lookup(x)).map(x => x + 1).getOrElse(0)
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("Some(i)"))
    assert(g.contains(".and_then("))
    assert(g.contains(".map("))
    assert(g.contains(".unwrap_or(0)"))

  test("Range until/to lower to Rust range syntax and support map/foldLeft"):
    val src =
      """```scalascript
        |def sum(): Int = (0 until 5).map(i => i * i).foldLeft(0)((a, b) => a + b)
        |def bounded(v: Int): Int = (0 to v).foldLeft(0)((a, b) => a + b)
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("(0..5)"))
    assert(g.contains("(0..=v)"))
    assert(g.contains(".map("))
    assert(g.contains(".fold(0,"))

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
