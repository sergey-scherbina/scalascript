package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** v1.24 — Pattern matching improvements: nested patterns, guards, @ binders. */
class PatternMatchTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    val module = Parser.parse(src)
    Interpreter(ps).run(module)
    ps.flush()
    buf.toString.trim

  // ─── Nested patterns ────────────────────────────────────────────

  test("nested — Some inside tuple") {
    captured("""
      val pair = (Some(42), Some("hello"))
      val result = pair match
        case (Some(n), Some(s)) => s"$n $s"
        case _ => "nope"
      println(result)
    """) shouldBe "42 hello"
  }

  test("nested — None inside tuple falls to wildcard") {
    captured("""
      val pair = (Some(1), None)
      val result = pair match
        case (Some(n), Some(s)) => s"both: $n $s"
        case _ => "not both"
      println(result)
    """) shouldBe "not both"
  }

  test("nested — Some inside case class field") {
    captured("""
      case class Wrapper(value: Option[Int])
      val w = Wrapper(Some(99))
      val result = w match
        case Wrapper(Some(n)) => s"got $n"
        case Wrapper(None)    => "empty"
      println(result)
    """) shouldBe "got 99"
  }

  test("nested — tuple of tuples") {
    captured("""
      val t = ((1, 2), (3, 4))
      val result = t match
        case ((a, b), (c, d)) => a + b + c + d
      println(result)
    """) shouldBe "10"
  }

  test("nested — case class inside Option") {
    captured("""
      case class Point(x: Int, y: Int)
      val opt: Option[Point] = Some(Point(3, 4))
      val result = opt match
        case Some(Point(x, y)) => x + y
        case None => 0
      println(result)
    """) shouldBe "7"
  }

  test("nested — list cons with nested Some") {
    captured("""
      val xs = List(Some(1), Some(2), Some(3))
      val result = xs match
        case Some(h) :: _ => s"first=$h"
        case _ => "nope"
      println(result)
    """) shouldBe "first=1"
  }

  // ─── Guard expressions ──────────────────────────────────────────

  test("guard — positive / negative / zero") {
    captured("""
      def classify(n: Int): String = n match
        case x if x > 0 => "positive"
        case x if x < 0 => "negative"
        case _ => "zero"
      println(classify(5))
      println(classify(-3))
      println(classify(0))
    """) shouldBe "positive\nnegative\nzero"
  }

  test("guard — string length condition") {
    captured("""
      def label(s: String): String = s match
        case x if x.length > 5 => "long"
        case x if x.length > 0 => "short"
        case _ => "empty"
      println(label("hello world"))
      println(label("hi"))
      println(label(""))
    """) shouldBe "long\nshort\nempty"
  }

  test("guard — combined pattern and guard") {
    captured("""
      case class Person(name: String, age: Int)
      def greet(p: Person): String = p match
        case Person(n, a) if a >= 18 => s"adult $n"
        case Person(n, _) => s"minor $n"
      println(greet(Person("Alice", 30)))
      println(greet(Person("Bob", 15)))
    """) shouldBe "adult Alice\nminor Bob"
  }

  test("guard — multiple guards on same pattern shape") {
    captured("""
      def fizzbuzz(n: Int): String = n match
        case x if x % 15 == 0 => "FizzBuzz"
        case x if x % 3 == 0  => "Fizz"
        case x if x % 5 == 0  => "Buzz"
        case x                 => x.toString
      println(fizzbuzz(15))
      println(fizzbuzz(9))
      println(fizzbuzz(10))
      println(fizzbuzz(7))
    """) shouldBe "FizzBuzz\nFizz\nBuzz\n7"
  }

  // ─── @ binders ──────────────────────────────────────────────────

  test("@ binder — bind whole list and use head") {
    captured("""
      val list = List(1, 2, 3)
      val result = list match
        case xs @ (h :: _) => s"head=$h size=${xs.length}"
        case Nil => "empty"
      println(result)
    """) shouldBe "head=1 size=3"
  }

  test("@ binder — bind Some value") {
    captured("""
      val opt = Some(42)
      val result = opt match
        case all @ Some(n) => s"val=$n bound=${all.isDefined}"
        case None => "none"
      println(result)
    """) shouldBe "val=42 bound=true"
  }

  test("@ binder — empty list falls to wildcard") {
    captured("""
      val list: List[Int] = Nil
      val result = list match
        case xs @ (h :: _) => s"head=$h"
        case Nil => "empty"
      println(result)
    """) shouldBe "empty"
  }

  test("@ binder — nested pattern with binder on inner") {
    captured("""
      val pair = (Some(10), Some(20))
      val result = pair match
        case (all @ Some(n), Some(m)) => s"n=$n m=$m total=${n + m}"
        case _ => "no"
      println(result)
    """) shouldBe "n=10 m=20 total=30"
  }

  test("@ binder — use bound name in guard") {
    captured("""
      val list = List(1, 2, 3)
      val result = list match
        case xs @ (h :: _) if xs.length > 2 => s"long list, head=$h"
        case xs @ (_ :: _) => "short list"
        case Nil => "empty"
      println(result)
    """) shouldBe "long list, head=1"
  }

  // ─── Combined: nested + guard + @ binder ────────────────────────

  test("combined — nested Some with guard") {
    captured("""
      val pair = (Some(5), Some(3))
      val result = pair match
        case (Some(a), Some(b)) if a > b => s"$a > $b"
        case (Some(a), Some(b)) if a < b => s"$a < $b"
        case (Some(a), Some(b)) => s"$a == $b"
        case _ => "none"
      println(result)
    """) shouldBe "5 > 3"
  }

  test("combined — @ binder with nested pattern") {
    captured("""
      case class Pair(left: Int, right: Int)
      val p = Pair(1, 2)
      val result = p match
        case all @ Pair(l, r) if l < r => s"ordered l=$l r=$r"
        case all @ Pair(l, r)          => s"unordered l=$l r=$r"
      println(result)
    """) shouldBe "ordered l=1 r=2"
  }
