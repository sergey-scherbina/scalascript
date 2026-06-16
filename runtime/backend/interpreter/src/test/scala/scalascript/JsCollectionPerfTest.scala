package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JsGen
import scalascript.parser.Parser

/** js-collection-perf: JsGen lowers hot collection/numeric ops to native JS instead of the
 *  megamorphic `_call` / `_dispatch` / `_arith` runtime helpers — vector-index 17.2→4.67,
 *  array-update 24.8→16.3 ms/iter. Guarded by static type tracking (listElemType / isNumericExpr)
 *  so untyped / String receivers still route through the safe runtime path. */
class JsCollectionPerfTest extends AnyFunSuite:

  private def gen(body: String): String =
    JsGen.generate(Parser.parse(s"# T\n```scalascript\n$body\n```\n"))

  test("seq(idx) on a numeric-element Vector lowers to direct v[idx] (no _call)"):
    val js = gen("val v: Vector[Int] = Vector(1, 2, 3)\ndef f(i: Int): Int = v(i)")
    assert(js.contains("v[i]"), s"expected direct index, got:\n$js")
    assert(!js.contains("_call(v"), "should not _call a known seq val")

  test("local Array(...) is tracked: a(i) read direct + a(i)=x store direct"):
    val js = gen(
      "def f(): Int =\n  val a = Array(0, 0, 0)\n  a(1) = 5\n  a(1)")
    assert(js.contains("a[1] = ") || js.contains("a[(1)] = "), s"expected direct store, got:\n$js")
    assert(!js.contains("_call(a"), "should not _call a local Array val")

  test(".toInt/.toLong on a numeric receiver lower to Math.trunc (no _dispatch)"):
    // Integer receiver: `.toInt` → `(x | 0)` (ToInt32 — Scala 32-bit wrap + V8 int32/SMI),
    // `.toLong` → identity. A Double receiver → Math.trunc.
    val js = gen("def f(n: Int): Int = (n % 16).toInt")
    assert(js.contains("| 0)"), s"expected (x | 0) for integer .toInt, got:\n$js")
    assert(!js.contains("'toInt'"), "numeric .toInt should not _dispatch")
    val jsD = gen("def g(d: Double): Int = d.toInt")
    assert(jsD.contains("Math.trunc"), s"expected Math.trunc for Double .toInt, got:\n$jsD")

  test("seq(idx).toLong makes surrounding arithmetic native (no _arith / _dispatch)"):
    val js = gen(
      "val v: Vector[Int] = Vector(1, 2, 3)\ndef f(s: Int): Int = s + v(s % 3).toLong")
    assert(js.contains("v[") && !js.contains("_call(v"), s"expected native v-index, got:\n$js")
    assert(!js.contains("_arith"), "numeric add should be native")

  test(".toInt on a String receiver still routes through the runtime (not Math.trunc)"):
    // A String's .toInt is parseInt — the numeric fast path must NOT fire here.
    val js = gen("def f(s: String): Int = s.toInt")
    assert(js.contains("_dispatch") && js.contains("'toInt'"),
      s"String .toInt must stay on the runtime path, got:\n$js")

  test("LazyList.from(s).map(f).take(n).sum is fused into a native loop (no _lz/_dispatch chain)"):
    val js = gen("def f(start: Int): Int = LazyList.from(start).map(x => x * 2).take(8).sum")
    assert(js.contains("__acc") && js.contains("while"), s"expected a fused loop, got:\n$js")
    assert(!js.contains("'from'") && !js.contains("'take'"),
      "the matched LazyList pipeline should not emit a _dispatch/_lz chain")

  test("unbounded LazyList (no take) is NOT fused"):
    val js = gen("def f(start: Int): Any = LazyList.from(start).map(x => x * 2)")
    assert(!js.contains("__acc"), "an unbounded LazyList must not be rewritten")
