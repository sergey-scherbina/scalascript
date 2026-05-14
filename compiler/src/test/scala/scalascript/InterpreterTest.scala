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

  test("named given — direct access by name") {
    captured("""
      trait Show[A]:
        def show(x: A): String

      given int: Show[Int] with
        def show(x: Int): String = "Int(" + x.toString + ")"

      given bool: Show[Boolean] with
        def show(x: Boolean): String = if x then "yes" else "no"

      println(int.show(42))
      println(bool.show(true))
      println(summon[Show[Int]].show(7))
    """) shouldBe "Int(42)\nyes\nInt(7)"
  }

  // ── Extension methods ────────────────────────────────────────────

  test("extension method on String") {
    captured("""
      extension (s: String)
        def shout: String = s.toUpperCase + "!"
      println("hello".shout)
    """) shouldBe "HELLO!"
  }

  test("extension method with argument") {
    captured("""
      extension (n: Int)
        def times(f: Int => Int): Int = f(n)
      println(5.times(x => x * 2))
    """) shouldBe "10"
  }

  test("extension method on List") {
    captured("""
      extension (xs: List[Int])
        def second: Int = xs.tail.head
      println(List(10, 20, 30).second)
    """) shouldBe "20"
  }

  test("multiple extension methods in one block") {
    captured("""
      extension (s: String)
        def shout: String = s.toUpperCase
        def whisper: String = s.toLowerCase
      println("Hello".shout)
      println("Hello".whisper)
    """) shouldBe "HELLO\nhello"
  }

  // ── For comprehensions ───────────────────────────────────────────

  test("for-yield simple") {
    captured("""
      val xs = for x <- List(1, 2, 3) yield x * 2
      println(xs.mkString(", "))
    """) shouldBe "2, 4, 6"
  }

  test("for-yield with guard") {
    captured("""
      val evens = for x <- List(1, 2, 3, 4, 5) if x % 2 == 0 yield x
      println(evens.mkString(", "))
    """) shouldBe "2, 4"
  }

  test("for-yield nested generators") {
    captured("""
      val pairs = for
        x <- List(1, 2)
        y <- List("a", "b")
      yield s"${x}${y}"
      println(pairs.mkString(", "))
    """) shouldBe "1a, 1b, 2a, 2b"
  }

  test("for-do foreach") {
    captured("""
      var sum = 0
      for x <- List(1, 2, 3, 4, 5) do sum = sum + x
      println(sum)
    """) shouldBe "15"
  }

  // ── Tuple destructuring ──────────────────────────────────────────

  test("val tuple destructuring") {
    captured("""
      val (a, b) = (10, 20)
      println(a)
      println(b)
    """) shouldBe "10\n20"
  }

  test("val tuple destructuring in for") {
    captured("""
      val pairs = List((1, "one"), (2, "two"), (3, "three"))
      for (n, s) <- pairs do println(s"${n}=${s}")
    """) shouldBe "1=one\n2=two\n3=three"
  }

  // ── While loop ───────────────────────────────────────────────────

  test("while loop") {
    captured("""
      var i = 0
      var sum = 0
      while i < 5 do
        sum = sum + i
        i = i + 1
      println(sum)
    """) shouldBe "10"
  }

  // ── String methods ───────────────────────────────────────────────

  test("string split") {
    captured("""println("a,b,c".split(",").mkString(" "))""") shouldBe "a b c"
  }

  test("string replace") {
    captured("""println("hello world".replace("world", "scala"))""") shouldBe "hello scala"
  }

  test("string take and drop") {
    captured("""println("hello".take(3))""") shouldBe "hel"
    captured("""println("hello".drop(2))""") shouldBe "llo"
  }

  test("string startsWith and endsWith") {
    captured("""println("hello".startsWith("hel"))""") shouldBe "true"
    captured("""println("hello".endsWith("llo"))""") shouldBe "true"
    captured("""println("hello".startsWith("abc"))""") shouldBe "false"
  }

  test("string trim") {
    captured("""println("  hi  ".trim)""") shouldBe "hi"
  }

  // ── List methods ─────────────────────────────────────────────────

  test("list zip") {
    captured("""
      val zipped = List(1, 2, 3).zip(List("a", "b", "c"))
      println(zipped.map(p => s"${p._1}${p._2}").mkString(", "))
    """) shouldBe "1a, 2b, 3c"
  }

  test("list zipWithIndex") {
    captured("""
      val xs = List("a", "b", "c").zipWithIndex
      println(xs.map(p => s"${p._2}:${p._1}").mkString(", "))
    """) shouldBe "0:a, 1:b, 2:c"
  }

  test("list find") {
    captured("""
      val xs = List(1, 2, 3, 4, 5)
      println(xs.find(x => x > 3).isDefined)
      println(xs.find(x => x > 10).isDefined)
    """) shouldBe "true\nfalse"
  }

  test("list count") {
    captured("""
      val xs = List(1, 2, 3, 4, 5)
      println(xs.count(x => x % 2 == 0))
    """) shouldBe "2"
  }

  test("list exists and forall") {
    captured("""
      val xs = List(2, 4, 6)
      println(xs.exists(x => x > 5))
      println(xs.forall(x => x % 2 == 0))
      println(xs.forall(x => x > 5))
    """) shouldBe "true\ntrue\nfalse"
  }

  test("list flatten") {
    captured("""
      val xs = List(List(1, 2), List(3, 4), List(5))
      println(xs.flatten.mkString(", "))
    """) shouldBe "1, 2, 3, 4, 5"
  }

  test("list foldRight") {
    captured("""
      val xs = List(1, 2, 3, 4, 5)
      println(xs.foldRight(0)(_ + _))
    """) shouldBe "15"
  }

  test("list distinct") {
    captured("""
      val xs = List(1, 2, 2, 3, 3, 3)
      println(xs.distinct.mkString(", "))
    """) shouldBe "1, 2, 3"
  }

  // ── Map methods ──────────────────────────────────────────────────

  test("map updated and removed") {
    captured("""
      val m = Map("a" -> 1, "b" -> 2)
      val m2 = m.updated("c", 3)
      println(m2.size)
      val m3 = m2.removed("a")
      println(m3.contains("a"))
      println(m3.contains("b"))
    """) shouldBe "3\nfalse\ntrue"
  }

  test("map keys and values") {
    captured("""
      val m = Map("x" -> 10)
      println(m.keys.mkString)
      println(m.values.mkString)
    """) shouldBe "x\n10"
  }

  test("map contains") {
    captured("""
      val m = Map("a" -> 1)
      println(m.contains("a"))
      println(m.contains("z"))
    """) shouldBe "true\nfalse"
  }

  // ── Option methods ───────────────────────────────────────────────

  test("option map and flatMap") {
    captured("""
      val x: Option[Int] = Some(5)
      println(x.map(n => n * 2).get)
      val y: Option[Int] = None
      println(y.map(n => n * 2).isDefined)
    """) shouldBe "10\nfalse"
  }

  test("option orElse") {
    captured("""
      val n: Option[Int] = None
      val s: Option[Int] = Some(42)
      println(n.orElse(Some(99)).get)
      println(s.orElse(Some(99)).get)
    """) shouldBe "99\n42"
  }

  // ── Numeric helpers ──────────────────────────────────────────────

  test("int to and until ranges") {
    captured("""println((1 to 5).mkString(", "))""") shouldBe "1, 2, 3, 4, 5"
    captured("""println((1 until 5).mkString(", "))""") shouldBe "1, 2, 3, 4"
  }

  test("int and double abs") {
    captured("""println(-7.abs)""") shouldBe "7"
    captured("""println(-3.14.abs)""") shouldBe "3.14"
  }

  test("int toDouble and double toInt") {
    captured("""println(3.toDouble + 0.5)""") shouldBe "3.5"
    captured("""println(3.7.toInt)""") shouldBe "3"
  }

  // ── new keyword ──────────────────────────────────────────────────

  test("new class instantiation") {
    captured("""
      case class Box(value: Int)
      val b = new Box(42)
      println(b.value)
    """) shouldBe "42"
  }

  // ── return keyword ───────────────────────────────────────────────

  test("explicit return from function") {
    captured("""
      def firstPositive(xs: List[Int]): Int =
        for x <- xs do
          if x > 0 then return x
        -1
      println(firstPositive(List(-3, -1, 5, 2)))
      println(firstPositive(List(-1, -2)))
    """) shouldBe "5\n-1"
  }

  // ── Parser ───────────────────────────────────────────────────────

  test("parser strips shebang line") {
    val src = "#!/usr/bin/env ssc\nprintln(42)\n"
    val module = Parser.parse(src)
    val buf = java.io.ByteArrayOutputStream()
    Interpreter(java.io.PrintStream(buf)).run(module)
    buf.toString.trim shouldBe "42"
  }

  test("parser handles pure scala without markdown") {
    val module = Parser.parse("println(\"pure scala\")\n")
    module.sections should have length 1
    module.sections.head.heading.text shouldBe "Script"
  }

