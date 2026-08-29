package ssc

import org.scalatest.funsuite.AnyFunSuite
import ssc.Value.*

/** Regression gate for BUGS.md `point-free-class-method-never-eta-expands-on-native`.
 *
 * A bare selection of a plain `class` instance's OWN method (`m.combine`, no call) never
 * eta-expanded — it invoked the method immediately with zero arguments and crashed on the
 * arity check `__regmethod__` installs (`"<Tag>.<name>: expected N argument(s), got 0"`).
 * A `given ... with` instance in the identical position (a `ForeignV`/method-object
 * receiver, not `DataV`) always eta-expanded correctly — dispatch for that receiver shape
 * already has the `margs.isEmpty && fn.arity > 0` guard `methodDispatch1`'s `DataV` arm
 * lacked.
 *
 * Root cause: the `DataV` tagged-method dispatch arm matched on the BARE method name
 * whenever ANY method of that name was registered on the tag, regardless of whether the
 * zero supplied args actually satisfied that method's real arity, and invoked it
 * unconditionally. Fixed by falling through to an eta-expansion built from
 * `taggedMethodArity` (recorded alongside the method at `__regmethod__` time, from the
 * same `ClosV` `__regmethod__` already reads `.arity` off of) whenever the exact-arity key
 * is absent AND the call carries zero arguments — i.e. exactly a point-free reference, not
 * a genuinely wrong-arity call (which always supplies at least one argument and is
 * unaffected by this fix; see the "must NOT regress" tests below).
 *
 * Home: same as `MethodDispatchFailClosedTest` — this module is what depends on `v2Core`
 * and has a test configuration; the assertions are about the kernel in `v2/src`.
 */
class EtaExpandClassMethodTest extends AnyFunSuite:

  private def m(name: String, recv: Value, args: Value*): Value =
    Prims.resolve("__method__")(StrV(name) :: recv :: args.toList)

  /** Registers a `class`-instance method the way the front's `__regmethod__` call does:
   *  `tag` is the class's DataV tag, `name` is the bare method name, `arity` is the
   *  CALLING-CONVENTION arity (self + declared params). */
  private def regMethod(tag: String, name: String, arity: Int)(body: Array[Value] => Value): Unit =
    val fn = ClosV(Runtime.emptyEnv, arity, env => Done(body(env)))
    Prims.resolve("__regmethod__")(List(StrV(tag), StrV(name), fn))

  private def runN(k: ClosV, args: Value*): Value =
    Runtime.run(k.code, Runtime.extend(k.env, args.toArray))

  // ── the reported bug: point-free access must eta-expand, not invoke with zero args ──

  test("a bare selection of a 2-arg class method eta-expands to a usable 2-arg function") {
    regMethod("ConstMonoid", "combine", 3) { env => // self, a, b
      IntV(asLong(env(1)) + asLong(env(2)))
    }
    val recv = DataV("ConstMonoid", IndexedSeq(IntV(0)))
    val eta = m("combine", recv)
    assert(eta.isInstanceOf[ClosV], s"expected an eta-expanded closure, got $eta")
    val closure = eta.asInstanceOf[ClosV]
    assert(closure.arity == 2, s"eta closure should take exactly the 2 declared params, got arity ${closure.arity}")
    assert(runN(closure, IntV(3), IntV(4)) == IntV(7))
  }

  test("a bare selection of a 1-arg class method eta-expands to a usable 1-arg function") {
    regMethod("Doubler", "apply", 2) { env => // self, x
      IntV(asLong(env(1)) * 2)
    }
    val recv = DataV("Doubler", IndexedSeq.empty)
    val eta = m("apply", recv)
    assert(eta.isInstanceOf[ClosV], s"expected an eta-expanded closure, got $eta")
    assert(Prims.runClos1(eta.asInstanceOf[ClosV], IntV(5)) == IntV(10))
  }

  test("the eta closure is marked, the same way the builtin-receiver eta fallback is") {
    regMethod("Marked", "op", 2) { env => env(1) }
    val eta = m("op", DataV("Marked", IndexedSeq.empty)).asInstanceOf[ClosV]
    assert(eta.etaMethodRef != null)
  }

  // ── must NOT regress: a genuinely wrong-arity CALL still fails the same way ──────────

  test("calling a 2-arg class method with only 1 argument still refuses (not silently eta-expands)") {
    regMethod("D", "h", 3) { env => IntV(asLong(env(1)) + asLong(env(2))) }
    val recv = DataV("D", IndexedSeq.empty)
    val e = intercept[RuntimeException](m("h", recv, IntV(7)))
    assert(e.getMessage.contains("D.h"))
    assert(e.getMessage.contains("expected 2 argument(s), got 1"), e.getMessage)
  }

  test("a real 0-arity class method still dispatches normally, not through the eta path") {
    regMethod("K", "value", 1) { _ => IntV(42) } // self only
    val recv = DataV("K", IndexedSeq.empty)
    assert(m("value", recv) == IntV(42))
  }

  test("calling a 2-arg class method with the exact right arity is unaffected") {
    regMethod("Ok", "combine", 3) { env => IntV(asLong(env(1)) + asLong(env(2))) }
    val recv = DataV("Ok", IndexedSeq.empty)
    assert(m("combine", recv, IntV(1), IntV(2)) == IntV(3))
  }

  // ── the regression the first cut of this fix produced (v2-unknown-member-refuses-gate's
  // `class-method-nullary-call` row): `R(0).k()` is an APPLIED zero-arg call — it reaches
  // `__method0__`, which delegates into the very same DataV arm above with `applied = true`
  // — and a wrong-arity method there must still throw the SPECIFIC arity message, not the
  // eta-expansion (which `__method0__` would otherwise demote to a generic "no dispatch").

  private def m0(name: String, recv: Value): Value =
    Prims.resolve("__method0__")(List(StrV(name), recv))

  test("an explicit zero-arg CALL (__method0__) on a wrong-arity class method still refuses with the specific message") {
    regMethod("R", "k", 2) { env => IntV(asLong(env(1))) } // self, a
    val recv = DataV("R", IndexedSeq(IntV(0)))
    val e = intercept[RuntimeException](m0("k", recv))
    assert(e.getMessage.contains("R.k"), e.getMessage)
    assert(e.getMessage.contains("expected 1 argument(s), got 0"), e.getMessage)
  }

  test("an explicit zero-arg CALL (__method0__) on a real nullary class method still dispatches") {
    regMethod("K", "value", 1) { _ => IntV(42) } // self only
    val recv = DataV("K", IndexedSeq.empty)
    assert(m0("value", recv) == IntV(42))
  }

  private def asLong(v: Value): Long = v match
    case IntV(n) => n
    case other   => fail(s"expected IntV, got $other")
