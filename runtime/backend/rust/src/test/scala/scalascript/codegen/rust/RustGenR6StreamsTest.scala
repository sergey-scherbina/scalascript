package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** Phase R.6 — synchronous stream pipeline: Source.range, Source.fromList,
 *  .toList on top of Rust iterator chains.
 *  Verifies:
 *  - RustCapabilities declares Feature.Streams
 *  - Source.range(lo, hi) lowers to (lo..=hi) Rust range
 *  - Source.fromList(list) lowers to the list (Vec passthrough)
 *  - .toList on a Source collects to Vec
 *  - .map/.filter/.foldLeft chain on Source works end-to-end */
class RustGenR6StreamsTest extends AnyFunSuite:

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

  test("RustCapabilities declares Streams"):
    assert(new RustBackend().capabilities.features.contains(Feature.Streams))

  test("Source.range(lo, hi) lowers to (lo..=hi) Rust inclusive range"):
    val g = gen(
      """```scalascript
        |def workload(): Int = Source.range(1, 10).foldLeft(0)((a, b) => a + b)
        |```
        |""".stripMargin)
    assert(g.contains("(1i64..=10i64)"), s"Source.range not lowered to (lo..=hi) in:\n$g")

  test("Source.range + map + filter + foldLeft pipeline"):
    val g = gen(
      """```scalascript
        |def workload(): Int =
        |  Source.range(1, 10)
        |    .map(x => x * 2)
        |    .filter(x => x % 3 == 0)
        |    .foldLeft(0)((sum, x) => sum + x)
        |```
        |""".stripMargin)
    assert(g.contains("(1i64..=10i64)"), s"range missing in:\n$g")
    assert(g.contains(".map("),          s".map missing in:\n$g")
    assert(g.contains(".filter("),       s".filter missing in:\n$g")
    assert(g.contains(".fold("),         s".fold missing in:\n$g")

  test("Source.fromList(list) passes the list through"):
    val g = gen(
      """```scalascript
        |val xs: List[Int] = List(1, 2, 3)
        |def workload(): Int = Source.fromList(xs).foldLeft(0)((a, b) => a + b)
        |```
        |""".stripMargin)
    // Source.fromList just passes xs; foldLeft wraps it
    assert(g.contains("xs"), s"xs missing in:\n$g")
    assert(g.contains(".fold("),  s".fold missing in:\n$g")

  test("Source.range.toList collects to Vec"):
    val g = gen(
      """```scalascript
        |def workload(): List[Int] = Source.range(1, 5).toList
        |```
        |""".stripMargin)
    assert(g.contains("collect::<Vec<_>>()"), s".toList not lowered to collect in:\n$g")

  test("streams-pipeline bench corpus compiles"):
    // Smoke test: the acceptance bench compiles without error.
    val src =
      """# streams-pipeline
        |
        |```scalascript
        |def workload(): Int =
        |  Source.range(1, 10)
        |    .map(x => x * 2)
        |    .filter(x => x % 3 == 0)
        |    .foldLeft(0)((sum, x) => sum + x)
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("pub fn workload()"), s"workload missing in:\n$g")
    assert(g.contains("(1i64..=10i64)"),   s"range missing in:\n$g")

  test("Cargo stays dep-free for streams pipeline"):
    new RustBackend().compile(Normalize(Parser.parse(
      """```scalascript
        |def workload(): Int = Source.range(1, 5).foldLeft(0)((a, b) => a + b)
        |```
        |""".stripMargin
    )), emptyOpts) match
      case CompileResult.Segmented(segs) =>
        val toml = segs.collectFirst {
          case Segment.Asset("Cargo.toml", b, _) => new String(b, "UTF-8")
        }.getOrElse(fail("Cargo.toml missing"))
        assert(!toml.contains("tokio"),        s"tokio should not appear for streams:\n$toml")
        assert(!toml.contains("futures-util"), s"futures-util should not appear:\n$toml")
      case other => fail(s"expected Segmented, got $other")
