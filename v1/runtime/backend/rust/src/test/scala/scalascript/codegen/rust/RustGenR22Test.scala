package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** Phase R.2.2 — `var` + reassignment + `while`. */
class RustGenR22Test extends AnyFunSuite:

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

  test("var → let mut, val → let"):
    val src =
      """```scalascript
        |@main def run(): Unit =
        |  var a: Long = 0
        |  val b: Long = 1
        |  a = a + b
        |  println(a)
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("let mut a: i64 = 0i64;"))
    assert(g.contains("let b: i64 = 1i64;"))
    assert(g.contains("a = (a + b);"))
    assert(g.contains("crate::runtime::_println(a);"))

  test("while emits Rust while with block body"):
    val src =
      """```scalascript
        |@main def run(): Unit =
        |  var i: Long = 0
        |  while (i < 3) {
        |    i = i + 1
        |  }
        |  println(i)
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("while (i < 3i64) {"))
    assert(g.contains("i = (i + 1i64);"))

  test("RustCapabilities now declares MutableState + WhileLoops"):
    val caps = new RustBackend().capabilities.features
    assert(caps.contains(Feature.MutableState))
    assert(caps.contains(Feature.WhileLoops))
