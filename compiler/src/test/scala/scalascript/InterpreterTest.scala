package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.{Interpreter, Value, InterpretError}
import scalascript.parser.Parser

class InterpreterTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    val module = Parser.parse(src)
    Interpreter(ps).run(module)
    ps.flush()
    buf.toString.trim

  // ── Arithmetic ──────────────────────────────────────────────────

  test("integer arithmetic") {
    captured("println(2 + 3 * 4)") shouldBe "14"
    captured("println(10 / 3)") shouldBe "3"
    captured("println(10 % 3)") shouldBe "1"
  }

  test("double arithmetic") {
    captured("println(1.5 + 2.5)") shouldBe "4"
    captured("println(2.0 * 3.0)") shouldBe "6"
  }

  test("boolean operators") {
    captured("println(true && false)") shouldBe "false"
    captured("println(true || false)") shouldBe "true"
  }

  test("comparison operators") {
    captured("println(3 > 2)") shouldBe "true"
    captured("println(3 == 3)") shouldBe "true"
    captured("println(3 != 4)") shouldBe "true"
  }

  // ── String ──────────────────────────────────────────────────────

  test("string interpolation") {
    captured("""val name = "World"; println(s"Hello, ${name}!")""") shouldBe "Hello, World!"
  }

  test("string methods") {
    captured("""println("hello".toUpperCase)""") shouldBe "HELLO"
    captured("""println("hello".length)""") shouldBe "5"
    captured("""println("hello".reverse)""") shouldBe "olleh"
    captured("""println("hello world".contains("world"))""") shouldBe "true"
  }

  // ── Control flow ────────────────────────────────────────────────

  test("if/else") {
    captured("println(if 2 > 1 then \"yes\" else \"no\")") shouldBe "yes"
    captured("println(if 1 > 2 then \"yes\" else \"no\")") shouldBe "no"
  }

  test("def and call") {
    captured("def double(x: Int): Int = x * 2; println(double(5))") shouldBe "10"
  }

  test("recursive function") {
    captured("""
      def fib(n: Int): Int =
        if n <= 1 then n else fib(n - 1) + fib(n - 2)
      println(fib(10))
    """) shouldBe "55"
  }

  test("nested functions and closures") {
    captured("""
      def adder(x: Int) = (y: Int) => x + y
      val add5 = adder(5)
      println(add5(3))
    """) shouldBe "8"
  }

  // ── List ────────────────────────────────────────────────────────

  test("list construction and map") {
    captured("""
      val xs = List(1, 2, 3)
      println(xs.map(x => x * 2).mkString(", "))
    """) shouldBe "2, 4, 6"
  }

  test("list filter and sum") {
    captured("""
      val xs = List(1, 2, 3, 4, 5)
      println(xs.filter(x => x % 2 == 0).sum)
    """) shouldBe "6"
  }

  test("list foldLeft") {
    captured("""
      val xs = List(1, 2, 3, 4, 5)
      println(xs.foldLeft(0)(_ + _))
    """) shouldBe "15"
  }

  test("list with underscore lambda") {
    captured("""
      val xs = List(1, 2, 3)
      println(xs.map(_ * 3).mkString(", "))
    """) shouldBe "3, 6, 9"
  }

  test("list mkString") {
    captured("""println(List("a", "b", "c").mkString(", "))""") shouldBe "a, b, c"
  }

  // ── Map ─────────────────────────────────────────────────────────

  test("map construction and access") {
    captured("""
      val m = Map("a" -> 1, "b" -> 2)
      println(m.get("a").get)
      println(m.get("c").isDefined)
    """) shouldBe "1\nfalse"
  }

  test("map size") {
    captured("""println(Map("x" -> 1, "y" -> 2).size)""") shouldBe "2"
  }

  // ── Option ──────────────────────────────────────────────────────

  test("option some/none") {
    captured("""
      val x: Option[Int] = Some(42)
      println(x.isDefined)
      println(x.get)
      val n: Option[Int] = None
      println(n.isDefined)
      println(n.getOrElse(0))
    """) shouldBe "true\n42\nfalse\n0"
  }

  // ── Pattern matching ────────────────────────────────────────────

  test("basic pattern match") {
    captured("""
      val x = 2
      val s = x match
        case 1 => "one"
        case 2 => "two"
        case _ => "other"
      println(s)
    """) shouldBe "two"
  }

  test("pattern match with guard") {
    captured("""
      def classify(n: Int): String = n match
        case x if x < 0 => "negative"
        case 0           => "zero"
        case x if x > 0 => "positive"
      println(classify(-5))
      println(classify(0))
      println(classify(7))
    """) shouldBe "negative\nzero\npositive"
  }

  // ── Case class ──────────────────────────────────────────────────

  test("case class construction and field access") {
    captured("""
      case class Point(x: Int, y: Int)
      val p = Point(3, 4)
      println(p.x)
      println(p.y)
    """) shouldBe "3\n4"
  }

  test("case class pattern matching") {
    captured("""
      case class Person(name: String, age: Int)
      def greet(p: Person): String = p match
        case Person(n, a) if a >= 18 => s"Hello, adult $n"
        case Person(n, _)            => s"Hello, young $n"
      println(greet(Person("Alice", 30)))
      println(greet(Person("Bob", 15)))
    """) shouldBe "Hello, adult Alice\nHello, young Bob"
  }

  // ── Enum ────────────────────────────────────────────────────────

  test("enum simple cases") {
    captured("""
      enum Color:
        case Red
        case Green
        case Blue
      def name(c: Color): String = c match
        case Red   => "red"
        case Green => "green"
        case Blue  => "blue"
      println(name(Red))
      println(name(Blue))
    """) shouldBe "red\nblue"
  }

  test("enum with parameters") {
    captured("""
      enum Shape:
        case Circle(radius: Double)
        case Rect(w: Double, h: Double)
      def area(s: Shape): Double = s match
        case Circle(r) => 3.14159 * r * r
        case Rect(w, h) => w * h
      println(area(Rect(3.0, 4.0)))
    """) shouldBe "12"
  }

  // ── Object ──────────────────────────────────────────────────────

  test("object members") {
    captured("""
      object Counter:
        val start = 0
        def next(n: Int): Int = n + 1
      println(Counter.start)
      println(Counter.next(41))
    """) shouldBe "0\n42"
  }

  // ── Multi-section execution ──────────────────────────────────────

  test("hello world via main") {
    val src = """---
name: test
---
# Test

```scala
def main(): Unit =
  println("Hello, World!")
```
"""
    val module = Parser.parse(src)
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter.run(module, ps)
    ps.flush()
    buf.toString.trim shouldBe "Hello, World!"
  }

  // ── Typeclass algebra ────────────────────────────────────────────

  test("given instance — summon and call") {
    captured("""
      trait Printable[F]:
        def show(x: F): String

      given Printable[Int] with
        def show(x: Int): String = x.toString

      given Printable[String] with
        def show(x: String): String = s"'$x'"

      println(summon[Printable[Int]].show(42))
      println(summon[Printable[String]].show("hello"))
    """) shouldBe "42\n'hello'"
  }

  test("summon retrieves a given instance") {
    captured("""
      trait Eq[A]:
        def eql(a: A, b: A): Boolean

      given Eq[Int] with
        def eql(a: Int, b: Int): Boolean = a == b

      val inst = summon[Eq[Int]]
      println(inst.eql(1, 1))
      println(inst.eql(1, 2))
    """) shouldBe "true\nfalse"
  }

  test("given instance with multiple methods") {
    captured("""
      trait Ordered[A]:
        def lt(a: A, b: A): Boolean
        def gt(a: A, b: A): Boolean

      given Ordered[Int] with
        def lt(a: Int, b: Int): Boolean = a < b
        def gt(a: Int, b: Int): Boolean = a > b

      val ord = summon[Ordered[Int]]
      println(ord.lt(1, 2))
      println(ord.gt(5, 3))
    """) shouldBe "true\ntrue"
  }
