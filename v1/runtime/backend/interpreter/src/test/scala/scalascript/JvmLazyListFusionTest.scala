package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JvmGen
import scalascript.parser.Parser

/** jvm-lazylist-fusion: JvmGen fuses a bounded `LazyList.from(s).map(f)?.take(n).sum` pipeline
 *  into a native `while` loop (no lazy cons cells) — the emitted Scala no longer pays LazyList
 *  cost (`lazylist-take` 5.87 → 0.052 ms/iter). Guarded to the exact shape; other LazyList uses
 *  pass through verbatim. spec: specs/jit-collection-ops.md. */
class JvmLazyListFusionTest extends AnyFunSuite:

  private def gen(body: String): String =
    JvmGen.generate(Parser.parse(s"# T\n```scalascript\n$body\n```\n"))

  test("map+take+sum pipeline is fused into a while loop (no LazyList)"):
    val sc = gen("val r = LazyList.from(3).map(x => x * 2).take(8).sum")
    assert(sc.contains("__acc") && sc.contains("while"),
      s"expected a fused while-loop, got:\n$sc")
    assert(!sc.contains("LazyList.from(3).map"),
      "the matched pipeline should no longer emit a real LazyList chain")

  test("take+sum (no map) pipeline is fused"):
    val sc = gen("val r = LazyList.from(5).take(4).sum")
    assert(sc.contains("__acc") && sc.contains("while"))
    assert(!sc.contains("LazyList.from(5).take"))

  test("a trailing .toLong stays applied to the fused block"):
    val sc = gen("val r = LazyList.from(1).map(x => x * 2).take(8).sum.toLong")
    assert(sc.contains("}.toLong"), s"expected fused-block.toLong, got:\n$sc")

  test("unbounded LazyList (no take) is NOT fused — left verbatim"):
    // No terminal bounded `.sum` to fuse against; must stay a real LazyList.
    val sc = gen("val r = LazyList.from(3).map(x => x * 2)")
    assert(sc.contains("LazyList.from(3)"),
      "an unbounded LazyList must not be rewritten (would never terminate if summed)")

  test("non-LazyList code is untouched (guard early-returns)"):
    val sc = gen("val r = List(1, 2, 3).map(x => x * 2).sum")
    assert(!sc.contains("__acc"), "List pipelines must not be rewritten by the LazyList fusion")
