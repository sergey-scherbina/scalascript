package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** Phase R.6 — guaranteed TCO via while-loop rewrite.
 *  Verifies:
 *  - `RustCapabilities` declares `Feature.TailCallOptimization`
 *  - self-tail-recursive defs emit `loop { ... }` instead of recursion
 *  - params become `mut` in TCO defs
 *  - tail calls become param assignments (with temps for ordering safety)
 *  - non-recursive branches get `return`
 *  - non-TCO defs are not affected */
class RustGenR6TcoTest extends AnyFunSuite:

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

  test("RustCapabilities declares TailCallOptimization"):
    assert(new RustBackend().capabilities.features.contains(Feature.TailCallOptimization))

  test("simple accumulator TCO: params become mut + loop emitted"):
    val g = gen(
      """```scalascript
        |def sumTco(n: Int, acc: Int): Int =
        |  if n <= 0 then acc
        |  else sumTco(n - 1, acc + n)
        |```
        |""".stripMargin)
    assert(g.contains("mut n: i64"),  s"param 'n' missing mut in:\n$g")
    assert(g.contains("mut acc: i64"), s"param 'acc' missing mut in:\n$g")
    assert(g.contains("loop {"),      s"'loop {' missing in:\n$g")
    // Only the function declaration should contain `sumTco(` — no recursive body call.
    val callCount = "sumTco\\(".r.findAllIn(g).length
    assert(callCount == 1, s"expected 1 occurrence of 'sumTco(' (signature only), got $callCount in:\n$g")

  test("TCO: base case gets 'return'"):
    val g = gen(
      """```scalascript
        |def sumTco(n: Int, acc: Int): Int =
        |  if n <= 0 then acc
        |  else sumTco(n - 1, acc + n)
        |```
        |""".stripMargin)
    assert(g.contains("return acc"),  s"return missing for base case in:\n$g")

  test("TCO: tail call becomes param assignments via temps"):
    val g = gen(
      """```scalascript
        |def sumTco(n: Int, acc: Int): Int =
        |  if n <= 0 then acc
        |  else sumTco(n - 1, acc + n)
        |```
        |""".stripMargin)
    // temps _tco_0 and _tco_1 should appear
    assert(g.contains("_tco_0"),  s"temp _tco_0 missing in:\n$g")
    assert(g.contains("_tco_1"),  s"temp _tco_1 missing in:\n$g")
    assert(g.contains("n = _tco_0") || g.contains("acc = _tco_0"),
      s"param reassignment missing in:\n$g")

  test("reversed branches: tail call in then, base in else"):
    val g = gen(
      """```scalascript
        |def countDown(n: Int): Int =
        |  if n > 0 then countDown(n - 1)
        |  else 0
        |```
        |""".stripMargin)
    assert(g.contains("loop {"),   s"loop missing in:\n$g")
    assert(g.contains("return 0"), s"base case missing return in:\n$g")
    val callCount = "countDown\\(".r.findAllIn(g).length
    assert(callCount == 1, s"expected 1 occurrence (signature only), got $callCount in:\n$g")

  test("non-TCO def is not affected (no loop)"):
    val g = gen(
      """```scalascript
        |def fib(n: Int): Int =
        |  if n <= 1 then n
        |  else fib(n - 1) + fib(n - 2)
        |```
        |""".stripMargin)
    // fib is NOT tail-recursive (last op is +, not a plain call), so no loop
    assert(!g.contains("loop {"),  s"non-TCO def should not emit loop, got:\n$g")

  test("workload calling TCO helper still compiles"):
    val g = gen(
      """```scalascript
        |def sumTco(n: Int, acc: Int): Int =
        |  if n <= 0 then acc
        |  else sumTco(n - 1, acc + n)
        |
        |def workload(): Int = sumTco(100000, 0)
        |```
        |""".stripMargin)
    assert(g.contains("pub fn workload()"),   s"workload missing in:\n$g")
    assert(g.contains("sumTco(100000i64, 0i64)"), s"workload call missing in:\n$g")
    assert(g.contains("loop {"),              s"TCO loop missing in:\n$g")
