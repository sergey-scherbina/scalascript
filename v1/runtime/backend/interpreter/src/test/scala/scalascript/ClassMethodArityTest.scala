package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Same-name class methods that differ in ARITY.
 *
 *  `StatRuntime` built the type-method table with a plain `.toMap`, so two definitions of one name
 *  collapsed to the LAST one and the other was dropped at registration time. A class declaring both
 *  `f(a)` and `f(a, b)` answered `missing argument for parameter 'b'` for `c.f(7)` — a silently
 *  discarded declaration reported as a mistake at the CALL site
 *  (interp-same-name-class-methods-collapse-to-the-last).
 *
 *  The other half of that fix — `extern class` members no longer being registered as callable
 *  methods at all — is NOT tested here, deliberately. Its whole failure mode is that the
 *  declaration shadows a PLUGIN's member, so a case without a plugin cannot see it. It is covered
 *  end-to-end by `tests/e2e/v21-standard-mcp-smoke.sh`, which drives eight declared `srv` members
 *  over stdio on both lanes and is exactly the program that used to die with
 *  `Undefined: __extern__`.
 *
 *  The last two cases are the ones that make this a test rather than a demonstration: a method with
 *  DEFAULTS and a method declared only ONCE must behave exactly as before, because the fix adds
 *  `name#<arity>` keys and a lookup that must not fire for them.
 */
class ClassMethodArityTest extends AnyFunSuite with Matchers:

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n"))
    ps.flush()
    buf.toString.trim

  test("Interpreter: two arities of one method name are both reachable"):
    run("""
      class Calc:
        def f(a: Int): Int = a * 10
        def f(a: Int, b: Int): Int = a + b

      val c = Calc()
      println(c.f(7))
      println(c.f(1, 2))
    """) shouldBe "70\n3"

  test("Interpreter: the FIRST declaration is the one dropped without the fix"):
    // Ordering matters to this defect: `.toMap` kept the last, so it was always the EARLIER
    // declaration that vanished. Declaring the two-parameter one first proves the fix is not just
    // "keep the first" with the arguments reversed.
    run("""
      class Calc:
        def f(a: Int, b: Int): Int = a + b
        def f(a: Int): Int = a * 10

      val c = Calc()
      println(c.f(7))
      println(c.f(1, 2))
    """) shouldBe "70\n3"

  test("Interpreter: three arities of one name"):
    run("""
      class Calc:
        def f(a: Int): Int = a
        def f(a: Int, b: Int): Int = a + b
        def f(a: Int, b: Int, c: Int): Int = a + b + c

      val c = Calc()
      println(c.f(1))
      println(c.f(1, 2))
      println(c.f(1, 2, 3))
    """) shouldBe "1\n3\n6"

  test("Interpreter: a method with DEFAULTS still fills them"):
    // The fix registers each definition under every arity it accepts, which is computed FROM the
    // defaults. Getting that range wrong would break defaulting for ordinary, non-overloaded
    // methods — the overwhelming majority of every program.
    run("""
      class Calc:
        def g(a: Int, b: Int = 5): Int = a - b

      val c = Calc()
      println(c.g(9))
      println(c.g(9, 4))
    """) shouldBe "4\n5"

  test("Interpreter: a name declared ONCE is untouched by the arity keys"):
    run("""
      class Calc:
        def only(a: Int): Int = a + 1

      val c = Calc()
      println(c.only(1))
    """) shouldBe "2"

  test("Interpreter: overloaded methods on a TRAIT"):
    // The trait body had the same `.toMap`, and it is a separate registration site.
    run("""
      trait Greets:
        def hi(a: String): String = "hi " + a
        def hi(a: String, b: String): String = "hi " + a + " and " + b

      class Host extends Greets

      val h = Host()
      println(h.hi("ann"))
      println(h.hi("ann", "bo"))
    """) shouldBe "hi ann\nhi ann and bo"
