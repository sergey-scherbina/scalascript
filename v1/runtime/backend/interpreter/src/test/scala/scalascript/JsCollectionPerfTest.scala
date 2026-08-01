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
    // Integer receiver: `.toInt` → `_toI32(x)` (ToInt32 — 32-bit wrap; BigInt-safe now
    // that Long is a JS BigInt, v1-js-long-precision-and-bitops). A Double → Math.trunc.
    val js = gen("def f(n: Int): Int = (n % 16).toInt")
    assert(js.contains("_toI32("), s"expected _toI32(x) for integer .toInt, got:\n$js")
    assert(!js.contains("'toInt'"), "numeric .toInt should not _dispatch")
    val jsD = gen("def g(d: Double): Int = d.toInt")
    assert(jsD.contains("Math.trunc"), s"expected Math.trunc for Double .toInt, got:\n$jsD")

  test("seq(idx).toLong marks the surrounding arithmetic Long (routes through _larith)"):
    // `.toLong` produces a JS BigInt (v1-js-long-precision-and-bitops), so the add cannot be a
    // native `+` — that would mix BigInt+Number and throw. It must go through a helper that
    // coerces the Int operand. The seq index itself still lowers to a native `v[...]`.
    //
    // WHICH helper is the whole point, and this assertion used to name the wrong one. `Long` and
    // `BigInt` share one JS representation (`bigint`) but NOT one semantics: `Long` wraps at 64
    // bits, `BigInt` is unbounded. `_larith` is `_arith` plus `BigInt.asIntN(64, …)`; masking
    // inside the shared `_arith` truncated `BigInt(1e9)^4` from 1e36 to -5527149226598858752, which
    // is why the split exists (656732866). Only JsGen knows the static type, so only JsGen decides,
    // and a Long add must therefore emit `_larith`. Accepting either name here would let the wrap
    // silently disappear.
    val js = gen(
      "val v: Vector[Int] = Vector(1, 2, 3)\ndef f(s: Int): Int = s + v(s % 3).toLong")
    assert(js.contains("v[") && !js.contains("_call(v"), s"expected native v-index, got:\n$js")
    assert(js.contains("_larith"), s"a Long add must wrap at 64 bits via _larith, got:\n$js")

  test("BigInt arithmetic keeps the UNMASKED _arith — the twin of the test above"):
    // The two live here TOGETHER on purpose. They are one decision seen from two sides, and when
    // they sat apart, changing the emitter for one of them left the other asserting the old name
    // and CI red on a landed, correct change. Whoever edits either assertion now has the other in
    // front of them: `BigInt` must NOT be masked to 64 bits, so it must NOT reach `_larith`.
    val js = gen("def f(a: BigInt, b: BigInt): BigInt = a * b * a * b")
    // NOT VACUOUS: the BigInt multiply must actually reach a helper. If the emitter ever lowered it
    // to a native `*`, the assertion above would pass while proving nothing, so the presence of
    // `_arith` is asserted first.
    assert(js.contains("_arith"), s"the BigInt multiply must route through _arith at all, got:\n$js")
    assert(!js.contains("_larith"), s"BigInt must not be masked to 64 bits, got:\n$js")

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
