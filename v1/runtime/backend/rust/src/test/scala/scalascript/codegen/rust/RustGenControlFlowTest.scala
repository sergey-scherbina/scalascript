package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** Rust backend control-flow / expression coverage added on top of R.2:
 *  type ascription, statement-form `for … do` loops, and `match` case guards. */
class RustGenControlFlowTest extends AnyFunSuite:

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

  test("type ascription `(expr: T)` emits just the inner expression"):
    val g = gen(
      """```scalascript
        |def workload(): Int = (5: Int) + 1
        |```
        |""".stripMargin)
    assert(g.contains("pub fn workload() -> i64"), g)
    // The ascription is dropped; `(5i64) + 1i64` (no `: Int` artifact in the output).
    assert(g.contains("5i64"), g)
    assert(!g.contains(": Int"), g)

  test("statement-form `for x <- a to b do …` lowers to a Rust for-loop"):
    val g = gen(
      """```scalascript
        |def workload(): Int =
        |  var sum = 0
        |  for i <- 1 to 5 do
        |    sum = sum + i
        |  sum
        |```
        |""".stripMargin)
    assert(g.contains("for i in (1i64..=5i64) {"), g)
    assert(g.contains("sum = (sum + i)") || g.contains("sum = sum + i"), g)

  test("match case guard `case x if cond =>` becomes a Rust match guard"):
    val g = gen(
      """```scalascript
        |def cls(n: Int): String =
        |  n match
        |    case x if x > 0 => "pos"
        |    case 0          => "zero"
        |    case _          => "neg"
        |def workload(): String = cls(5)
        |```
        |""".stripMargin)
    assert(g.contains("if (x > 0i64) =>") || g.contains("if x > 0i64 =>") || g.contains("if (x > 0i64)"), g)
    assert(g.contains("match n {"), g)
