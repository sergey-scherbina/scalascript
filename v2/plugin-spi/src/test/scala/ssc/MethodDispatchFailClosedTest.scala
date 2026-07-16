package ssc

import org.scalatest.funsuite.AnyFunSuite
import ssc.Value.*

/** Regression gate for BUGS.md `v2-zero-arg-unknown-method-fails-open`.
 *
 * An unknown ZERO-ARGUMENT method used to return an undispatched value and let the
 * program exit 0 (`42.bogusMethod()` printed `<closure>`; `List(1,2).bogusMethod()`
 * printed `Stub`). Arity was the discriminator: only an applied argument list reached
 * a throwing fallback.
 *
 * Root cause: a bare selection `recv.m` and an applied call `recv.m()` lower to
 * BYTE-IDENTICAL CoreIR, so `__method__`'s fallthrough could not tell a genuine method
 * REF (must eta-expand) from a typo (must fail). The lowerer now emits `__method0__`
 * for the applied form, which fails closed. These tests pin BOTH arities and BOTH
 * fail-open values (the eta closure and the `Stub` breadcrumb).
 *
 * Home: this lives in the plugin-spi test tree because it is the module that depends on
 * `v2Core` (the kernel in `v2/src`) and has a test configuration; the assertions are
 * about the kernel, not about plugins.
 */
class MethodDispatchFailClosedTest extends AnyFunSuite:

  private def m0(name: String, recv: Value, args: Value*): Value =
    Prims.resolve("__method0__")(StrV(name) :: recv :: args.toList)
  private def m(name: String, recv: Value, args: Value*): Value =
    Prims.resolve("__method__")(StrV(name) :: recv :: args.toList)

  private val emptyList = DataV("Nil", Vector.empty)
  private val aList     = DataV("Cons", Vector(IntV(1), DataV("Cons", Vector(IntV(2), emptyList))))
  private val aRecord   = DataV("P", Vector(IntV(1)))

  // ── fails closed: the reported bug, every receiver shape ──────────────────────

  test("unknown zero-arg method fails closed on an Int receiver") {
    val e = intercept[RuntimeException](m0("bogusMethod", IntV(42)))
    assert(e.getMessage.contains("no dispatch for .bogusMethod"))
    assert(e.getMessage.contains("42"))
  }

  test("unknown zero-arg method fails closed on a String receiver") {
    val e = intercept[RuntimeException](m0("bogusMethod", StrV("hi")))
    assert(e.getMessage.contains("no dispatch for .bogusMethod"))
  }

  test("unknown zero-arg method fails closed on a List receiver (the Stub breadcrumb path)") {
    val e = intercept[RuntimeException](m0("bogusMethod", aList))
    assert(e.getMessage.contains("no dispatch for .bogusMethod"))
  }

  test("unknown zero-arg method fails closed on a record receiver (the Stub breadcrumb path)") {
    val e = intercept[RuntimeException](m0("bogusMethod", aRecord))
    assert(e.getMessage.contains("no dispatch for .bogusMethod"))
  }

  test("unknown zero-arg method fails closed on a closure receiver") {
    val f = ClosV(Runtime.emptyEnv, 1, env => Done(env.last))
    val e = intercept[RuntimeException](m0("totallyBogus", f))
    assert(e.getMessage.contains("no dispatch for .totallyBogus"))
  }

  // ── the OTHER arity must keep failing closed (it always did) ──────────────────

  test("unknown method WITH an argument still fails closed (arity is no longer the discriminator)") {
    val e = intercept[RuntimeException](m("bogus", IntV(42), IntV(1)))
    assert(e.getMessage.contains("no dispatch for .bogus"))
  }

  // ── must NOT regress: real zero-arg methods still dispatch ───────────────────

  test("a real zero-arg method still dispatches through __method0__") {
    assert(m0("toString", IntV(42)) == StrV("42"))
    assert(m0("trim", StrV("  hi  ")) == StrV("hi"))
  }

  test("a real field access on a record still resolves through __method__") {
    // bare selection keeps the __method__ path; a registered field must win over the
    // Stub breadcrumb (this is what made the Stub fallback load-bearing).
    assert(m("toString", aRecord).isInstanceOf[StrV])
  }

  // ── must NOT regress: the method REF (eta-expansion) this fallback exists for ──

  test("a bare selection still eta-expands to a usable function value") {
    // `list.exists(lc.contains)` — `lc.contains` reaches __method__ with zero args and
    // must become `x => lc.contains(x)`. This is the case the fail-open was buying.
    val eta = m("contains", StrV("hello world"))
    assert(eta.isInstanceOf[ClosV], s"expected an eta-expanded closure, got $eta")
    assert(Prims.runClos1(eta.asInstanceOf[ClosV], StrV("world")) == BoolV(true))
    assert(Prims.runClos1(eta.asInstanceOf[ClosV], StrV("zzz")) == BoolV(false))
  }

  test("the eta closure is marked, and ONLY the eta closure is") {
    val eta = m("contains", StrV("hello")).asInstanceOf[ClosV]
    assert(eta.etaMethodRef != null)
    val plain = ClosV(Runtime.emptyEnv, 1, env => Done(env.last))
    assert(plain.etaMethodRef == null)
  }
