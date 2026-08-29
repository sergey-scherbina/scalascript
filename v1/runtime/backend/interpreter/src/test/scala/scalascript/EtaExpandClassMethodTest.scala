package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Regression gate for BUGS.md `point-free-class-method-never-eta-expands-on-int`
 *  (`v1/runtime/backend/interpreter/BUGS.md`).
 *
 *  A bare selection of a plain `class`/`case class` instance's OWN method (`m.combine`, no
 *  call) unconditionally dispatched with zero args and crashed on the arity check
 *  `applyDefaults` raises (`"missing argument for parameter 'a'"`) — `EvalRuntime`'s
 *  `Term.Select` handler called `DispatchRuntime.dispatch(qualV, method, Nil, ...)` with no
 *  eta-expansion attempt, and `pickArity`'s no-exact-fit fallback returns the real,
 *  wrong-arity closure rather than signalling a miss. A `given ... with` instance in the
 *  identical position already eta-expanded correctly — its methods are plain `FunV` fields,
 *  reached through a different code path (`dispatchInstanceAfterMethods`) because
 *  `interp.typeMethods` has no entry for a given-instance's synthetic type name.
 *
 *  Fixed by `DispatchRuntime.dispatchBareSelection`, used only by the bare-selection
 *  (`Term.Select`, no `Term.Apply`) evaluation site — an applied call, even with an explicit
 *  empty argument list (`recv.method()`), is a different Scalameta node evaluated elsewhere
 *  (`evalApplyGeneral`), so — unlike the v2/native lane's twin bug — there is no ambiguity
 *  here between "bare selection" and "applied zero-arg call" to resolve at the dispatch
 *  level; the AST already tells them apart. The anti-rows below (applied wrong-arity call,
 *  applied right-arity call, real nullary method) pin that nothing at the APPLY side moved.
 */
class EtaExpandClassMethodTest extends AnyFunSuite with Matchers:

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n"))
    ps.flush()
    buf.toString.trim

  // ── the reported bug: point-free access must eta-expand, not invoke with zero args ──

  test("Interpreter: a bare selection of a 2-arg class method eta-expands to a usable function"):
    run("""
      class ConstMonoid(z: Int):
        def combine(a: Int, b: Int): Int = a + b

      def main(): Unit =
        val cm = ConstMonoid(0)
        val f = cm.combine
        println(f(3, 4))
    """) shouldBe "7"

  test("Interpreter: a bare selection of a 1-arg class method eta-expands to a usable function"):
    run("""
      class Doubler:
        def apply2(x: Int): Int = x * 2

      def main(): Unit =
        val d = Doubler()
        val f = d.apply2
        println(f(5))
    """) shouldBe "10"

  test("Interpreter: the original repro — point-free method passed straight into foldLeft"):
    run("""
      class ConstMonoid(z: Int):
        def empty: Int = z
        def combine(a: Int, b: Int): Int = a + b

      def main(): Unit =
        val cm = ConstMonoid(0)
        println(List(1, 2, 3).foldLeft(cm.empty)(cm.combine))
    """) shouldBe "6"

  // ── must NOT regress: an applied call still fails/succeeds exactly as before ────────

  test("Interpreter: an applied zero-arg call with the WRONG arity still refuses (not eta-expand)"):
    val err = intercept[Exception](run("""
      case class R(z: Int):
        def k(a: Int): String = "K(" + a.toString + ")"

      def main(): Unit = println(R(0).k())
    """))
    err.getMessage should include("missing argument for parameter 'a'")

  test("Interpreter: an applied call with the right arity is unaffected"):
    run("""
      case class Ok(z: Int):
        def combine(a: Int, b: Int): Int = a + b

      def main(): Unit = println(Ok(0).combine(1, 2))
    """) shouldBe "3"

  test("Interpreter: a real 0-arity class method still dispatches normally through a bare selection"):
    run("""
      case class C(z: Int):
        def zed: Int = z

      def main(): Unit =
        val c = C(9)
        println(c.zed)
    """) shouldBe "9"

  // ── must NOT regress: the given-instance eta path this bug's report compared against ──

  test("Interpreter: a given instance's method still eta-expands point-free (unaffected by this fix)"):
    run("""
      trait Monoid[A]:
        def empty: A
        def combine(a: A, b: A): A

      given intMonoid: Monoid[Int] with
        def empty: Int = 0
        def combine(a: Int, b: Int): Int = a + b

      def main(): Unit =
        println(List(1, 2, 3).foldLeft(intMonoid.empty)(intMonoid.combine))
    """) shouldBe "6"
