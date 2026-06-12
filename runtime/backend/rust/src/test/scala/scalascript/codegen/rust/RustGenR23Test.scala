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
    assert(g.contains("Shape::Circle { r }"))

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
    assert(g.contains("(1i64, 2.0f64, \"ok\".to_string())"))

  test("type lambda application is beta-reduced in a type annotation"):
    // `type Pair = [A] =>> (A, A)` applied as `Pair[Long]` reduces to `(Long, Long)`
    // → Rust `(i64, i64)`. (Other backends erase the type; rust needs the real one.)
    val src =
      """```scalascript
        |type Pair = [A] =>> (A, A)
        |def make(): Long =
        |  val p: Pair[Long] = (3, 4)
        |  p._1 + p._2
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("let p: (i64, i64) ="), s"expected reduced tuple type, got:\n$g")

  test("multi-param type lambda reorders args under reduction"):
    // `type Swap = [A, B] =>> Map[B, A]` applied as `Swap[Int, String]` reduces to
    // `Map[String, Int]` → `HashMap<String, i64>`.
    val src =
      """```scalascript
        |type Swap = [A, B] =>> Map[B, A]
        |def keys(m: Swap[Int, String]): Long = 0L
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("std::collections::HashMap<String, i64>"), s"got:\n$g")

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

  test("tuple accessor `_1`/`_4` remaps to Rust 0-indexed `.0`/`.3`"):
    // Scala tuples are 1-indexed; Rust tuples are 0-indexed. Without the
    // remap, `t._1` emits `t._1` and rustc rejects it (no field `_1`).
    val src =
      """```scalascript
        |def pick(): Int =
        |  val t = (1, 2) ++ (3, 4)
        |  t._1 + t._4
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("t.0"))
    assert(g.contains("t.3"))
    assert(!g.contains("t._1"))
    assert(!g.contains("t._4"))

  test("String.split + trim + toInt lowers to String helpers and parse"):
    val src =
      """```scalascript
        |def sum(): Int = "1,2,3".split(",").map(s => s.trim.toInt).foldLeft(0)((a, b) => a + b)
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains(""".split(",").map(|p| p.to_string()).collect::<Vec<String>>()"""))
    assert(g.contains(".trim().to_string()"))
    assert(g.contains(".parse::<i64>().unwrap_or(0)"))

  test("String.split(sep, limit) supports Vec-like chaining"):
    val src =
      """```scalascript
        |def pick(): List[String] = "a,b,c".split(",", 2).map(s => s.trim)
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains(".splitn(2i64 as usize, \",\")"))
    assert(g.contains("map(|p| p.to_string()).collect::<Vec<String>>()"))

  test("String.toInt uses numeric parse on string literals"):
    val src =
      """```scalascript
        |def value(): Int = "42".toInt
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("\"42\".to_string().parse::<i64>().unwrap_or(0)"))

  test("Numeric conversions and String+number concat lower to expected Rust casts"):
    val src =
      """```scalascript
        |def asLong(i: Int): Long = i.toLong
        |def asInt(l: Long): Int = l.toInt
        |def asDouble(i: Int): Double = i.toDouble
        |def asFloat(i: Int): Float = i.toFloat
        |def label(i: Int): String = "item-" + i
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("(i as i64)"))
    // `.toInt` truncates to 32-bit then widens back to i64, because ScalaScript
    // `Int` maps to rust `i64` (a bare `as i32` can't be passed to an i64 param).
    assert(g.contains("(l as i32 as i64)"))
    assert(g.contains("(i as f64)"))
    assert(g.contains("format!(\"{}{}\", \"item-\".to_string(), i)"))

  test("Map[K, V] lowers to HashMap and supports updated/getOrElse"):
    val src =
      """```scalascript
        |def makeMap(): Map[Int, Int] = Map[Int, Int]().updated(1, 10)
        |def readMap(k: Int): Int = makeMap().getOrElse(k, 0)
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("pub fn makeMap() -> std::collections::HashMap<i64, i64>"))
    assert(g.contains("HashMap::new()"))
    assert(g.contains("let mut m2 ="))
    assert(g.contains(".insert(1i64, 10i64);"))
    assert(g.contains(".get(&k).copied().unwrap_or(0i64)"))

  test("Either[L, R] lowers to pub enum and supports map/flatMap/fold"):
    val src =
      """```scalascript
        |def chain(v: Int): Either[String, Int] =
        |  if v < 0 then Left("neg")
        |  else Right(v).map(x => x + 1).flatMap(x => Right(x + 10))
        |
        |def folded(v: Int): String =
        |  if v < 0 then Left("bad") else Right("ok")
        |  .fold(_ => "neg", _ => "ok")
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("pub enum Either<L, R>"))
    assert(g.contains("Either::Left(\"neg\".to_string())"))
    assert(g.contains("Either::Right(v) => Either::Right("))
    assert(g.contains("Either::Left(v) =>"))
    assert(g.contains("Either::Right(v) =>"))

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
    assert(g.contains("if ((v % 2i64) == 0i64) {"))
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
    assert(g.contains(".unwrap_or(0i64)"))

  test("Range until/to lower to Rust range syntax and support map/foldLeft"):
    val src =
      """```scalascript
        |def sum(): Int = (0 until 5).map(i => i * i).foldLeft(0)((a, b) => a + b)
        |def bounded(v: Int): Int = (0 to v).foldLeft(0)((a, b) => a + b)
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("(0i64..5i64)"))
    assert(g.contains("(0i64..=v)"))
    assert(g.contains(".map("))
    assert(g.contains(".fold(0i64,"))

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
