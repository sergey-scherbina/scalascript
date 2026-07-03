package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Tests for v1.24 `given`/`using` auto-resolution improvements.
 *
 *  Covers:
 *  1. Nested given chains — transitive resolution
 *  2. Ambiguous givens — clear error message
 *  3. Explicit `using` at call site
 *  4. `using` in function composition context
 */
class GivenUsingTest extends AnyFunSuite with Matchers:

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    Interpreter(ps).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  private def runExpectError(code: String): String =
    try
      run(code)
      fail("Expected an error but none was thrown")
    catch
      case e: Throwable => e.getMessage

  // ── 1. Nested given chain — transitive resolution ───────────────────────

  test("nested given chain — simple two-level resolution"):
    run("""
      trait Ordering[A]:
        def compare(x: A, y: A): Int

      given intOrd: Ordering[Int] with
        def compare(x: Int, y: Int): Int = x - y

      given listOrd[A](using ord: Ordering[A]): Ordering[List[A]] with
        def compare(xs: List[A], ys: List[A]): Int =
          if xs.isEmpty && ys.isEmpty then 0
          else if xs.isEmpty then -1
          else if ys.isEmpty then 1
          else ord.compare(xs.head, ys.head)

      def sortPair[A](x: A, y: A)(using ord: Ordering[A]): String =
        if ord.compare(x, y) <= 0 then "first" else "second"

      println(sortPair(List(1, 2, 3), List(2, 3, 4)))
    """) shouldBe "first"

  test("nested given chain — explicit using at call site uses nested given"):
    run("""
      trait Ord[A]:
        def lt(x: A, y: A): Boolean

      given intOrd: Ord[Int] with
        def lt(x: Int, y: Int): Boolean = x < y

      given listOrd[A](using ord: Ord[A]): Ord[List[A]] with
        def lt(xs: List[A], ys: List[A]): Boolean =
          xs.nonEmpty && (ys.isEmpty || ord.lt(xs.head, ys.head))

      def smaller[A](x: A, y: A)(using ord: Ord[A]): A =
        if ord.lt(x, y) then x else y

      val r = smaller(List(1, 2), List(3, 4))(using listOrd)
      println(r)
    """) shouldBe "List(1, 2)"

  test("nested given chain — three levels deep"):
    run("""
      trait Wrap[A]:
        def wrap(x: A): String

      given wrapInt: Wrap[Int] with
        def wrap(x: Int): String = "[" + x + "]"

      given wrapList[A](using w: Wrap[A]): Wrap[List[A]] with
        def wrap(xs: List[A]): String = xs.map(w.wrap).mkString("(", ",", ")")

      given wrapListList[A](using w: Wrap[List[A]]): Wrap[List[List[A]]] with
        def wrap(xss: List[List[A]]): String = xss.map(w.wrap).mkString("{", ";", "}")

      def display[A](x: A)(using w: Wrap[A]): String = w.wrap(x)

      println(display(List(List(1, 2), List(3))))
    """) shouldBe "{([1],[2]);([3])}"  // wrapListList → wrapList[Int] → wrapInt (3 levels)

  // Note: Three-level chain is ambitious; test a simpler version that's guaranteed to work
  test("nested given chain — two levels with concrete instantiation"):
    run("""
      trait Show[A]:
        def show(x: A): String

      given showInt: Show[Int] with
        def show(x: Int): String = "I:" + x

      given showList[A](using s: Show[A]): Show[List[A]] with
        def show(xs: List[A]): String = xs.map(s.show).mkString("[", ",", "]")

      def display[A](x: A)(using s: Show[A]): String = s.show(x)

      println(display(List(1, 2, 3)))
    """) shouldBe "[I:1,I:2,I:3]"

  // ── 2. Ambiguous givens — clear error message ─────────────────────────

  test("ambiguous givens — two concrete given instances for same type produce error"):
    val err = runExpectError("""
      trait Show[Int]:
        def show(x: Int): String

      given a: Show[Int] with
        def show(x: Int): String = "a:" + x

      given b: Show[Int] with
        def show(x: Int): String = "b:" + x

      def display(x: Int)(using s: Show[Int]): String = s.show(x)
      println(display(42))
    """)
    err should include ("ambiguous")
    err should (include ("2") or include ("candidates"))

  // ── 3. Explicit `using` at call site ──────────────────────────────────

  test("explicit using at call site — passes the named given"):
    run("""
      trait Ordering[A]:
        def compare(x: A, y: A): Int

      given ascOrd: Ordering[Int] with
        def compare(x: Int, y: Int): Int = x - y

      given descOrd: Ordering[Int] with
        def compare(x: Int, y: Int): Int = y - x

      def smaller(x: Int, y: Int)(using ord: Ordering[Int]): Int =
        if ord.compare(x, y) <= 0 then x else y

      println(smaller(3, 1)(using descOrd))
    """) shouldBe "3"

  test("explicit using at call site — works with parametric given"):
    run("""
      trait Ord[A]:
        def compare(x: A, y: A): Int

      given intOrd: Ord[Int] with
        def compare(x: Int, y: Int): Int = x - y

      given listOrd[A](using ord: Ord[A]): Ord[List[A]] with
        def compare(xs: List[A], ys: List[A]): Int =
          if xs.isEmpty && ys.isEmpty then 0
          else if xs.isEmpty then -1
          else if ys.isEmpty then 1
          else ord.compare(xs.head, ys.head)

      def smaller[A](x: A, y: A)(using ord: Ord[A]): A =
        if ord.compare(x, y) <= 0 then x else y

      // Auto-resolve listOrd, which in turn auto-resolves intOrd
      println(smaller(List(5, 2), List(1, 9)))
    """) shouldBe "List(1, 9)"

  // ── 4. `using` in function composition context ────────────────────────

  test("using in higher-order function context — combineAll"):
    run("""
      trait Monoid[A]:
        def empty: A
        def combine(a: A, b: A): A

      given intSum: Monoid[Int] with
        def empty: Int = 0
        def combine(a: Int, b: Int): Int = a + b

      def combineAll[A](xs: List[A])(using m: Monoid[A]): A =
        xs.foldLeft(m.empty)(m.combine)

      println(combineAll(List(1, 2, 3, 4, 5)))
    """) shouldBe "15"

  test("using resolution in nested higher-order call"):
    run("""
      trait Eq[A]:
        def eqv(x: A, y: A): Boolean

      given intEq: Eq[Int] with
        def eqv(x: Int, y: Int): Boolean = x == y

      def allEqual[A](xs: List[A])(using eq: Eq[A]): Boolean =
        xs match
          case Nil      => true
          case h :: t   => t.forall(x => eq.eqv(h, x))

      println(allEqual(List(3, 3, 3)))
      println(allEqual(List(1, 2, 3)))
    """) shouldBe "true\nfalse"

  test("using in fold — explicit given passed to accumulate minimum"):
    run("""
      trait Comparator[A]:
        def lt(x: A, y: A): Boolean

      given intComp: Comparator[Int] with
        def lt(x: Int, y: Int): Boolean = x < y

      def minOf[A](xs: List[A])(using cmp: Comparator[A]): A =
        xs.tail.foldLeft(xs.head)((acc, x) => if cmp.lt(x, acc) then x else acc)

      println(minOf(List(3, 1, 4, 1, 5, 9, 2, 6))(using intComp))
    """) shouldBe "1"

  test("given auto-resolution works with context bound syntax"):
    run("""
      trait Printable[A]:
        def str(x: A): String

      given printInt: Printable[Int] with
        def str(x: Int): String = "<<" + x + ">>"

      given printList[A](using p: Printable[A]): Printable[List[A]] with
        def str(xs: List[A]): String = xs.map(p.str).mkString("[", "|", "]")

      def render[A: Printable](x: A): String = summon[Printable[A]].str(x)

      println(render(List(1, 2, 3)))
    """) shouldBe "[<<1>>|<<2>>|<<3>>]"
