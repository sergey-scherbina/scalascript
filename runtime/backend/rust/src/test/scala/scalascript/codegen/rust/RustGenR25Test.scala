package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** Phase R.2.5 — for-comprehensions + List/Vec. */
class RustGenR25Test extends AnyFunSuite:

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

  test("List[T] / Vec[T] type lowers to Vec<T>"):
    val src =
      """```scalascript
        |def f(xs: List[Long]): List[Long] = xs
        |def g(xs: Vec[Long]): Vec[Long]   = xs
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("pub fn f(xs: Vec<i64>) -> Vec<i64>"))
    assert(g.contains("pub fn g(xs: Vec<i64>) -> Vec<i64>"))

  test("List(args) / Vec(args) construct as vec![…]"):
    val src =
      """```scalascript
        |def mk(): List[Long] = List(1, 2, 3)
        |def mk2(): Vec[Long] = Vec(4, 5)
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("vec![1i64, 2i64, 3i64]"))
    assert(g.contains("vec![4i64, 5i64]"))

  test("for x <- xs yield expr lowers to into_iter().map(|x| expr).collect()"):
    val src =
      """```scalascript
        |def doubled(xs: List[Long]): List[Long] =
        |  for x <- xs yield x * 2
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("xs.into_iter().map(move |x| { (x * 2i64) }).collect::<Vec<_>>()"),
      s"for-yield emit not found in:\n$g")

  test(".size / .length / .len on a Vec emit as .len() as i64"):
    val src =
      """```scalascript
        |def n(xs: List[Long]): Long = xs.size
        |def m(xs: List[Long]): Long = xs.length
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("(xs.len() as i64)"),
      s"len-emit not found in:\n$g")

  test("RustCapabilities declares ForComprehensions + ExtensionMethods + DefaultParameters"):
    val caps = new RustBackend().capabilities.features
    assert(caps.contains(Feature.ForComprehensions))
    assert(caps.contains(Feature.ExtensionMethods))
    assert(caps.contains(Feature.DefaultParameters))
