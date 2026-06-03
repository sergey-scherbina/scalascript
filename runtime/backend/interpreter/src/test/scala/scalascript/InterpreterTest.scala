package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.{Interpreter, InterpretError}
import scalascript.parser.Parser

class InterpreterTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scalascript\n$code\n```\n"
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

  test("unary operators") {
    captured("""
      println(-7)
      println(+7)
      println(-2.5)
      println(!false)
      println(~5)
    """) shouldBe "-7\n7\n-2.5\ntrue\n-6"
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

  test("def with three params") {
    captured("def sum3(a: Int, b: Int, c: Int): Int = a + b + c; println(sum3(1, 2, 3))") shouldBe "6"
  }

  test("recursive function") {
    captured("""
      def fib(n: Int): Int =
        if n <= 1 then n else fib(n - 1) + fib(n - 2)
      println(fib(10))
    """) shouldBe "55"
  }

  test("recursive function with multi-stat block body (JIT A.5)") {
    captured("""
      def sumSquares(n: Int): Int =
        val sq = n * n
        val prev = if n <= 1 then 0 else sumSquares(n - 1)
        prev + sq
      println(sumSquares(5))
    """) shouldBe "55"
  }

  test("while loop with ref-arg match in LRefExpr position (LRefMatch)") {
    captured("""
      enum Shape:
        case Circle(r: Int)
        case Square(s: Int)
      val shape: Shape = Shape.Circle(5)
      var total = 0
      var i = 0
      while i < 100 do
        total = total + (shape match { case Shape.Circle(r) => r; case Shape.Square(s) => s })
        i = i + 1
      println(total)
    """) shouldBe "500"
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

  test("case class missing required field raises error") {
    val ex = intercept[InterpretError] {
      captured("""
        case class Point(x: Int, y: Int)
        println(Point(3).x)
      """)
    }
    ex.getMessage should include ("missing argument")
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

  test("nested Some pattern in case class") {
    captured("""
      case class Person(name: String, email: Option[String])
      def show(p: Person): String = p match
        case Person(n, Some(e)) => s"$n <$e>"
        case Person(n, None)    => s"$n (no email)"
      println(show(Person("Alice", Some("alice@example.com"))))
      println(show(Person("Bob", None)))
    """) shouldBe "Alice <alice@example.com>\nBob (no email)"
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

  // ── Tuple monoid ++ ─────────────────────────────────────────────

  test("tuple ++ concatenation") {
    captured("""
      val a = (1, "hello")
      val b = (true, 42)
      val r = a ++ b
      println(r)
    """) shouldBe "(1, hello, true, 42)"
  }

  test("tuple ++ left identity: () ++ t = t") {
    captured("""
      val t = (1, 2)
      val r = () ++ t
      println(r)
    """) shouldBe "(1, 2)"
  }

  test("tuple ++ right identity: t ++ () = t") {
    captured("""
      val t = (1, 2)
      val r = t ++ ()
      println(r)
    """) shouldBe "(1, 2)"
  }

  test("tuple ++ associativity") {
    captured("""
      val a = (1, 10)
      val b = (2, 20)
      val c = (3, 30)
      println((a ++ b) ++ c)
      println(a ++ (b ++ c))
    """) shouldBe "(1, 10, 2, 20, 3, 30)\n(1, 10, 2, 20, 3, 30)"
  }

  // ── Bare-value tuple append (v1.60.4) ───────────────────────────

  test("tuple ++ bare value appends element") {
    captured("""
      val t = (1, "hello")
      println(t ++ true)
    """) shouldBe "(1, hello, true)"
  }

  test("bare value ++ tuple prepends element") {
    captured("""
      val t = ("b", "c")
      println("a" ++ t)
    """) shouldBe "(a, b, c)"
  }

  test("bare ++ bare creates 2-tuple") {
    captured("""
      println(1 ++ "x")
    """) shouldBe "(1, x)"
  }

  test("() ++ bare = bare (identity law with bare)") {
    captured("""
      println(() ++ 42)
    """) shouldBe "42"
  }

  test("bare ++ () = bare (identity law with bare)") {
    captured("""
      println(42 ++ ())
    """) shouldBe "42"
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

  test("top-level while observes global mutation from called function") {
    captured("""
      var i = 0
      def bump(): Unit =
        i = i + 2
      while i < 5 do
        bump()
      println(i)
    """) shouldBe "6"
  }

  test("while loop direct assignments preserve statement order") {
    captured("""
      var x = 0
      var y = 0
      while x < 3 do
        x = x + 1
        y = x
      println(y)
    """) shouldBe "3"
  }

  // ── String methods ───────────────────────────────────────────────

  test("string split") {
    captured("""println("a,b,c".split(",").mkString(" "))""") shouldBe "a b c"
    captured("""println("a,".split(",").mkString("|"))""") shouldBe "a|"
    captured("""println("a\n".lines.mkString("|"))""") shouldBe "a|"
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

  test("string char higher-order methods") {
    captured("""
      println("abc".map(c => c.toUpper).mkString)
      println("abc".filter(c => c.toString != "b"))
      println("abcde".takeWhile(c => c.toString != "d"))
      println("abcde".dropWhile(c => c.toString != "c"))
      println("abcde".count(c => c.toString != "d"))
      println("abcde".exists(c => c.toString == "d"))
      println("abcde".forall(c => c.toString != "z"))
      var acc = ""
      "ab".foreach(c => acc = acc + c.toString)
      println(acc)
    """) shouldBe "ABC\nac\nabc\ncde\n4\ntrue\ntrue\nab"
  }

  test("string toList and zipWithIndex") {
    captured("""println("abc".toList.map(c => c.toString).mkString("-"))""") shouldBe "a-b-c"
    captured("""
      val xs = "abc".zipWithIndex
      println(xs.map(p => s"${p._2}:${p._1}").mkString(", "))
    """) shouldBe "0:a, 1:b, 2:c"
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

  test("list indices") {
    captured("""println(List("a", "b", "c").indices.mkString(", "))""") shouldBe "0, 1, 2"
    captured("""println(Nil.indices.mkString(", "))""") shouldBe ""
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

  test("list aggregators scanLeft reduceLeft partition and groupBy") {
    captured("""
      val xs = List(1, 2, 3, 4, 5)
      println(xs.scanLeft(0)(_ + _).mkString(", "))
      println(xs.reduceLeft(_ + _))
      val parts = xs.partition(x => x % 2 == 0)
      println(parts._1.mkString(", "))
      println(parts._2.mkString(", "))
      val grouped = xs.groupBy(x => x % 2)
      println(grouped(0).mkString(", "))
      println(grouped(1).mkString(", "))
    """) shouldBe "0, 1, 3, 6, 10, 15\n15\n2, 4\n1, 3, 5\n2, 4\n1, 3, 5"
  }

  test("list distinct") {
    captured("""
      val xs = List(1, 2, 2, 3, 3, 3)
      println(xs.distinct.mkString(", "))
    """) shouldBe "1, 2, 3"
  }

  test("list headOption and lastOption") {
    captured("""
      println(List(1, 2, 3).headOption.isDefined)
      println(List(1, 2, 3).headOption.get)
      println(Nil.headOption.isDefined)
      println(List(1, 2, 3).lastOption.get)
    """) shouldBe "true\n1\nfalse\n3"
  }

  test("Int Long Double constructors from String") {
    captured("""
      println(Int("42"))
      println(Int("-7"))
      println(Long("9999"))
      println(Double("3.14"))
    """) shouldBe "42\n-7\n9999\n3.14"
  }

  test("Int constructor from numeric types") {
    captured("""
      println(Int(3.7))
      println(Int(5))
    """) shouldBe "3\n5"
  }

  test("asInstanceOf is a no-op at runtime") {
    captured("""
      val x: Any = "hello"
      val s = x.asInstanceOf[String]
      println(s)
      val n: Any = 42
      println(n.asInstanceOf[Int])
    """) shouldBe "hello\n42"
  }

  test("list extra one-arg collection methods") {
    captured("""
      val xs = List(1, 2, 3, 4, 5)
      println(xs.takeWhile(x => x < 4).mkString(", "))
      println(xs.dropWhile(x => x < 3).mkString(", "))
      println(List(3, 1, 2).sortWith((a, b) => a < b).mkString(", "))
      val split = xs.splitAt(2)
      println(split._1.mkString(", "))
      println(split._2.mkString(", "))
      println(xs.takeRight(2).mkString(", "))
      println(xs.dropRight(3).mkString(", "))
      println(xs.intersect(List(2, 4, 9)).mkString(", "))
      println(xs.diff(List(2, 5)).mkString(", "))
    """) shouldBe "1, 2, 3\n3, 4, 5\n1, 2, 3\n1, 2\n3, 4, 5\n4, 5\n1, 2\n2, 4\n1, 3, 4"
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

  test("map higher-order single-arg methods") {
    captured("""
      val m = Map("a" -> 1, "b" -> 2, "c" -> 3)
      println(m.count((k, v) => v > 1))
      println(m.exists((k, v) => k == "b" && v == 2))
      println(m.forall((k, v) => v > 0))
      println(m.find((k, v) => k == "c").isDefined)
      println(m.foldLeft(0)((acc, entry) => acc + entry._2))
    """) shouldBe "2\ntrue\ntrue\ntrue\n6"
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
    captured("""println((5 to 3).mkString(", "))""") shouldBe ""
    captured("""println((5 until 5).mkString(", "))""") shouldBe ""
    captured("""println(List.range(2, 5).mkString(", "))""") shouldBe "2, 3, 4"
    captured("""println(List.range(5, 5).mkString(", "))""") shouldBe ""
  }

  test("int max and min"):
    captured("""
      println(3.max(7))
      println(3.min(7))
      println(3.max(2.5))
      println(3.min(2.5))
    """) shouldBe "7\n3\n3\n2.5"

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

  // ── md interpolator ──────────────────────────────────────────────

  test("md interpolator strips indentation") {
    captured(
      "val name = \"Alice\"\n" +
      "val result = md\"\"\"\n  Hello $name\n  World\n\"\"\"\nprintln(result)"
    ) shouldBe "Hello Alice\nWorld"
  }

  test("md interpolator trims leading and trailing blank lines") {
    captured(
      "val s = md\"\"\"\n\n  line1\n  line2\n\n\"\"\"\nprintln(s)"
    ) shouldBe "line1\nline2"
  }

  // ── auto-output of last expression ──────────────────────────────

  test("auto-output prints last non-unit expression") {
    captured("1 + 1") shouldBe "2"
  }

  test("auto-output prints string expression") {
    captured(""""hello".toUpperCase""") shouldBe "HELLO"
  }

  test("auto-output skips unit result") {
    captured("println(42)") shouldBe "42"
  }

  test("auto-output skips last def binding") {
    captured("def foo(x: Int): Int = x\nprintln(foo(3))") shouldBe "3"
  }

  // ── doc builder ──────────────────────────────────────────────────

  test("doc assembles parts with newlines") {
    captured("""render(doc("line1", "line2", "line3"))""") shouldBe "line1\nline2\nline3"
  }

  test("doc nested inside doc") {
    captured("""
      val inner = doc("  a", "  b")
      render(doc("header:", inner))
    """) shouldBe "header:\n  a\n  b"
  }

  // ── partial function (case lambda) ──────────────────────────────

  test("partial function in map") {
    captured("""
      val pairs = List((1, "a"), (2, "b"))
      println(pairs.map { case (n, s) => s"$n:$s" }.mkString(", "))
    """) shouldBe "1:a, 2:b"
  }

  // ── Tail-call optimisation ───────────────────────────────────────

  test("self-TCO — sum with accumulator, depth 100 000") {
    captured("""
      def sum(n: Long, acc: Long): Long =
        if n <= 0 then acc else sum(n - 1, acc + n)
      println(sum(100000, 0))
    """) shouldBe "5000050000"
  }

  test("self-TCO — countdown, depth 100 000") {
    captured("""
      def countdown(n: Int): Int =
        if n <= 0 then 0 else countdown(n - 1)
      println(countdown(100000))
    """) shouldBe "0"
  }

  test("self-TCO — reverse list with accumulator") {
    captured("""
      def rev(xs: List[Int], acc: List[Int]): List[Int] =
        if xs.isEmpty then acc else rev(xs.tail, xs.head :: acc)
      println(rev(List(1, 2, 3, 4, 5), List()).mkString(", "))
    """) shouldBe "5, 4, 3, 2, 1"
  }

  test("self-TCO — non-tail-recursive factorial still works") {
    captured("""
      def factorial(n: Int): Int =
        if n <= 1 then 1 else n * factorial(n - 1)
      println(factorial(10))
    """) shouldBe "3628800"
  }

  // ── Mutual tail-call optimisation ───────────────────────────────

  test("mutual-TCO — isEven / isOdd, depth 100 000") {
    captured("""
      def isEven(n: Int): Boolean =
        if n == 0 then true else isOdd(n - 1)
      def isOdd(n: Int): Boolean =
        if n == 0 then false else isEven(n - 1)
      println(isEven(100000))
      println(isOdd(100000))
      println(isEven(0))
      println(isOdd(1))
    """) shouldBe "true\nfalse\ntrue\ntrue"
  }

  test("mutual-TCO — three-way ping-pong") {
    captured("""
      def ping(n: Int): String =
        if n == 0 then "ping" else pong(n - 1)
      def pong(n: Int): String =
        if n == 0 then "pong" else pang(n - 1)
      def pang(n: Int): String =
        if n == 0 then "pang" else ping(n - 1)
      println(ping(99999))
      println(ping(99998))
      println(ping(99997))
    """) shouldBe "ping\npang\npong"
  }

  test("mutual-TCO — accumulator-style even sum / odd sum") {
    captured("""
      def evenSum(n: Int, acc: Int): Int =
        if n == 0 then acc else oddSum(n - 1, acc + n)
      def oddSum(n: Int, acc: Int): Int =
        if n == 0 then acc else evenSum(n - 1, acc)
      println(evenSum(100, 0))
    """) shouldBe "2550"
  }

  // ── Algebraic effects ────────────────────────────────────────────

  test("effects — Console one-shot routing") {
    captured("""
      effect Console:
        def writeLine(s: String): Unit
        def readLine(): String

      def greet(): String =
        val name = Console.readLine()
        Console.writeLine(s"Hello, $name!")
        name

      val r = handle(greet()) {
        case Console.readLine(resume)       => resume("Alice")
        case Console.writeLine(msg, resume) => println(msg); resume(())
      }
      println(r)
    """) shouldBe "Hello, Alice!\nAlice"
  }

  test("effects — Choose multi-shot nondeterminism") {
    captured("""
      multi effect Choose:
        def pick(opts: List[Int]): Int

      val r = handle {
        val x = Choose.pick(List(1, 2, 3))
        val y = Choose.pick(List(10, 20))
        x + y
      } {
        case Choose.pick(opts, resume) => opts.flatMap(opt => resume(opt))
      }
      println(r)
    """) shouldBe "List(11, 21, 12, 22, 13, 23)"
  }

  test("effects — Fail early return") {
    captured("""
      effect Fail:
        def raise(msg: String): Int

      def safeDiv(a: Int, b: Int): Int =
        if b == 0 then Fail.raise("division by zero")
        else a / b

      println(handle(safeDiv(10, 2)) { case Fail.raise(msg, resume) => -1 })
      println(handle(safeDiv(10, 0)) { case Fail.raise(msg, resume) => -1 })
    """) shouldBe "5\n-1"
  }

  test("effects — collect output into list") {
    captured("""
      effect Console:
        def writeLine(s: String): Unit
        def readLine(): String

      def program(): Unit =
        Console.writeLine("a")
        Console.writeLine("b")
        Console.writeLine("c")

      val lines = handle { program(); List() } {
        case Console.writeLine(msg, resume) => msg :: resume(())
      }
      println(lines.mkString(", "))
    """) shouldBe "a, b, c"
  }

  test("effects — stack-safe bind chains (trampolined Free)") {
    // Tail-recursive loop performs an effect on every step. The Free Monad
    // bind chain is right-associated in a while-loop on each handler resume,
    // so processing the per-iteration body uses O(1) Scala stack regardless
    // of the bind depth (Bjarnason 2012). 1000 iterations comfortably exceed
    // what a direct (non-trampolined) Free Monad would survive when combined
    // with a deeply nested bind chain in each step.
    captured("""
      effect Counter:
        def tick(): Int

      def loop(n: Int, acc: Int): Int =
        if n == 0 then acc
        else
          val v = Counter.tick()
          loop(n - 1, acc + v)

      val total = handle(loop(1000, 0)) {
        case Counter.tick(resume) => resume(1)
      }
      println(total)
    """) shouldBe "1000"
  }

  // ── Default parameters ──────────────────────────────────────────

  test("def — all defaults, called with no args") {
    captured("""
      def greet(name: String = "World", excited: Boolean = false): String =
        if excited then s"Hello, $name!" else s"Hello, $name"
      println(greet())
      println(greet("Sergiy"))
      println(greet("Sergiy", true))
    """) shouldBe "Hello, World\nHello, Sergiy\nHello, Sergiy!"
  }

  test("def — default references a previous parameter") {
    captured("""
      def shift(x: Int, by: Int = x + 1): Int = x + by
      println(shift(10))
      println(shift(10, 5))
    """) shouldBe "21\n15"
  }

  test("def — default uses outer val from closure") {
    captured("""
      val base = 100
      def offset(by: Int = base): Int = by * 2
      println(offset())
      println(offset(3))
    """) shouldBe "200\n6"
  }

  test("case class — default field values") {
    captured("""
      case class Box(width: Int = 10, height: Int = 20)
      val b1 = Box()
      val b2 = Box(5)
      val b3 = Box(5, 7)
      println(s"${b1.width}x${b1.height}")
      println(s"${b2.width}x${b2.height}")
      println(s"${b3.width}x${b3.height}")
    """) shouldBe "10x20\n5x20\n5x7"
  }

  test("class with default ctor params and method") {
    captured("""
      class Counter(start: Int = 0):
        def show(): Int = start * 2
      val c = Counter()
      println(c.show())
    """) shouldBe "0"
  }

  test("class method default parameter") {
    captured("""
      class Greeter:
        def greet(name: String = "World"): String = s"Hi $name"
      val g = Greeter()
      println(g.greet())
      println(g.greet("Anna"))
    """) shouldBe "Hi World\nHi Anna"
  }

  test("class method with one argument"):
    captured("""
      class Counter(start: Int):
        def add(n: Int): Int = start + n
      val c = Counter(10)
      println(c.add(5))
      println(c.add(-3))
    """) shouldBe "15\n7"

  test("class method with three arguments"):
    captured("""
      class Calculator(seed: Int):
        def mix(a: Int, b: Int, c: Int): Int = seed + a * 100 + b * 10 + c
      val calc = Calculator(5)
      println(calc.mix(1, 2, 3))
    """) shouldBe "128"

  test("enum case — default parameters") {
    captured("""
      enum Shape:
        case Circle(radius: Int = 1)
        case Square(side: Int = 2)
      println(Circle().radius)
      println(Square().side)
      println(Circle(7).radius)
    """) shouldBe "1\n2\n7"
  }

  test("enum case missing required field raises error") {
    val ex = intercept[InterpretError] {
      captured("""
        enum Shape:
          case Rect(width: Int, height: Int)
        println(Rect(10).width)
      """)
    }
    ex.getMessage should include ("missing argument")
  }

  test("recursive function with default seed") {
    captured("""
      def sumTo(n: Int, acc: Int = 0): Int =
        if n == 0 then acc else sumTo(n - 1, acc + n)
      println(sumTo(10))
    """) shouldBe "55"
  }

  test("missing required argument raises error") {
    val ex = intercept[InterpretError] {
      captured("""
        def f(x: Int, y: Int = 10): Int = x + y
        println(f())
      """)
    }
    ex.getMessage should include ("missing argument")
  }

  test("type ascription — bare expression") {
    captured("println((42: Int))") shouldBe "42"
    captured("println((\"hi\": String))") shouldBe "hi"
  }

  test("type ascription — inside larger expression") {
    captured("println(((1 + 2): Int) + 1)") shouldBe "4"
  }

  test("type ascription — None / Nil disambiguation") {
    captured("val x: Option[Int] = (None: Option[Int]); println(x)") shouldBe "None"
    captured("val xs: List[Int] = (Nil: List[Int]); println(xs)") shouldBe "List()"
  }

  // ── html block: findClosingBrace string-awareness ────────────────────────

  /** Run a full .ssc document and return trimmed stdout. */
  private def capturedDoc(src: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  test("html block: } inside string literal does not prematurely close ${}") {
    // The expression ${ "hello" + "}" } contains a } inside a double-quoted string.
    // Before the fix, findClosingBrace would stop at that } and the parse would
    // fail or produce garbled output.
    val src =
      """|# Card
         |
         |```html
         |<div>${ "hello" + "}" }</div>
         |```
         |
         |# Test
         |
         |```scala
         |println(Card.html)
         |```
         |""".stripMargin
    capturedDoc(src) shouldBe "<div>hello}</div>"
  }

  test("html block: conditional with } in string does not prematurely close ${}") {
    val src =
      """|# Card
         |
         |```html
         |<span>${ if true then "}" else "}x" }</span>
         |```
         |
         |# Test
         |
         |```scala
         |println(Card.html)
         |```
         |""".stripMargin
    capturedDoc(src) shouldBe "<span>}</span>"
  }

  test("html block: balanced braces still work after string-aware fix") {
    // A simple expression with no string literals — depth tracking still correct.
    val src =
      """|# Card
         |
         |```html
         |<p>${ 1 + 2 }</p>
         |```
         |
         |# Test
         |
         |```scala
         |println(Card.html)
         |```
         |""".stripMargin
    capturedDoc(src) shouldBe "<p>3</p>"
  }

  test("html block: nested block expression braces still work") {
    // Expression contains nested { } via a block expression — depth must still track correctly.
    val src =
      """|# Card
         |
         |```html
         |<b>${ { val x = 5; x * 2 } }</b>
         |```
         |
         |# Test
         |
         |```scala
         |println(Card.html)
         |```
         |""".stripMargin
    capturedDoc(src) shouldBe "<b>10</b>"
  }

  // ── javascript block: string-valued, no html-escape (v1.25 Phase 1) ───────

  test("javascript block: bound to <section>.javascript as raw String") {
    // The `javascript` tag is treated like `css` — no html-escaping. The body
    // is captured as a String with `${expr}` interpolation against the
    // surrounding scalascript scope.
    val src =
      """|# Widget
         |
         |```javascript
         |const x = ${ 1 + 2 };
         |```
         |
         |# Test
         |
         |```scala
         |println(Widget.javascript)
         |```
         |""".stripMargin
    capturedDoc(src) shouldBe "const x = 3;"
  }

  test("js alias: bound to <section>.js as raw String") {
    val src =
      """|# Widget
         |
         |```js
         |console.log(${ "\"hello\"" });
         |```
         |
         |# Test
         |
         |```scala
         |println(Widget.js)
         |```
         |""".stripMargin
    capturedDoc(src) shouldBe "console.log(\"hello\");"
  }

  test("javascript block: angle brackets are not html-escaped") {
    // A regression guard: `javascript` must NOT route through the html-escape
    // branch of renderStringBlock, even though it lives next to html in the
    // Lang object.
    val src =
      """|# Widget
         |
         |```javascript
         |if (a < b && b > c) { /* ${ 42 } */ }
         |```
         |
         |# Test
         |
         |```scala
         |println(Widget.javascript)
         |```
         |""".stripMargin
    capturedDoc(src) shouldBe "if (a < b && b > c) { /* 42 */ }"
  }

  // ── Bug repro: getOrElse with function-call default in a match arm where
  //    the same fn also appears in a sibling tail position. ────────────────
  //
  // Root cause: TcoRuntime's mutual-tail-call analysis sees `en` in tail
  // position at `case None => en(k)` and installs a NativeFnV stub that
  // throws MutualTailCall. The SAME stub then also resolves `en` in the
  // sibling arm's `m.getOrElse(k, en(k))` where `en(k)` is NOT a tail call
  // (it's just the default value argument of getOrElse). Evaluating it
  // throws, escapes argument evaluation, and the trampoline jumps to `en`
  // before `m.getOrElse` runs — discarding the map lookup.
  test("map.getOrElse with fn-call default in match arm returns map value") {
    // `en("hello")` returns "fb:hello" — distinguishable from the map's
    // "hello" -> "Hello", so a buggy run (where `en(k)` is invoked via the
    // mutual-tail-call stub and replaces the whole match result) yields
    // "fb:hello" instead of the correct map lookup "Hello".
    captured("""
      val nested = Map("en" -> Map("hello" -> "Hello", "world" -> "World"))
      def en(k: String): String = "fb:" + k
      def t(loc: String, k: String): String = nested.get(loc) match {
        case Some(m) => m.getOrElse(k, en(k))
        case None    => en(k)
      }
      println(t("en", "hello"))
      println(t("en", "world"))
      println(t("fr",  "hello"))
    """) shouldBe "Hello\nWorld\nfb:hello"
  }
