package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Conformance tests for v1.24 — Union types (`String | Int`). */
class UnionTypeTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    Interpreter(ps).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  // ── Basic show function with String | Int union ───────────────────

  test("show(String | Int) with String argument returns the string"):
    captured("""
      def show(x: String | Int): String = x match
        case s: String => s
        case n: Int    => n.toString
      println(show("hello"))
    """) shouldBe "hello"

  test("show(String | Int) with Int argument returns string representation"):
    captured("""
      def show(x: String | Int): String = x match
        case s: String => s
        case n: Int    => n.toString
      println(show(42))
    """) shouldBe "42"

  test("show dispatches correctly for both types"):
    captured("""
      def show(x: String | Int): String = x match
        case s: String => s
        case n: Int    => n.toString
      println(show("hello"))
      println(show(42))
    """) shouldBe "hello\n42"

  // ── Union type as parameter — function can be called with either type ──

  test("union type parameter accepts String"):
    captured("""
      def describe(x: String | Int): String =
        x match
          case s: String => s"string:$s"
          case n: Int    => s"int:$n"
      println(describe("world"))
    """) shouldBe "string:world"

  test("union type parameter accepts Int"):
    captured("""
      def describe(x: String | Int): String =
        x match
          case s: String => s"string:$s"
          case n: Int    => s"int:$n"
      println(describe(99))
    """) shouldBe "int:99"

  // ── List[String | Int] — mixed list ─────────────────────────────────

  test("List of mixed String and Int values"):
    captured("""
      def show(x: String | Int): String = x match
        case s: String => s
        case n: Int    => n.toString
      val xs: List[String | Int] = List("a", 1, "b", 2)
      val results = xs.map(show)
      println(results.mkString(","))
    """) shouldBe "a,1,b,2"

  test("map over List[String | Int] collects correct results"):
    captured("""
      def kind(x: String | Int): String = x match
        case _: String => "str"
        case _: Int    => "int"
      val xs: List[String | Int] = List("hello", 42, "world", 0)
      println(xs.map(kind).mkString(" "))
    """) shouldBe "str int str int"

  // ── Match on union: each arm matches the right type ──────────────────

  test("type-test pattern match: String arm fires for String value"):
    captured("""
      val x: String | Int = "test"
      val result = x match
        case s: String => s"got string: $s"
        case n: Int    => s"got int: $n"
      println(result)
    """) shouldBe "got string: test"

  test("type-test pattern match: Int arm fires for Int value"):
    captured("""
      val x: String | Int = 7
      val result = x match
        case s: String => s"got string: $s"
        case n: Int    => s"got int: $n"
      println(result)
    """) shouldBe "got int: 7"

  // ── Nested union: String | Int | Boolean ─────────────────────────────

  test("three-way union type: String | Int | Boolean"):
    captured("""
      def classify(x: String | Int | Boolean): String = x match
        case s: String  => s"string:$s"
        case n: Int     => s"int:$n"
        case b: Boolean => s"bool:$b"
      println(classify("hi"))
      println(classify(5))
      println(classify(true))
    """) shouldBe "string:hi\nint:5\nbool:true"

  // ── Type-test with wildcard binder ────────────────────────────────────

  test("wildcard arm fires when no typed arm matches"):
    captured("""
      def show(x: String | Int): String = x match
        case s: String => s
        case _         => "other"
      println(show("abc"))
      println(show(1))
    """) shouldBe "abc\nother"

  // ── Union return type ─────────────────────────────────────────────────

  test("function returning String | Int returns correct typed values"):
    captured("""
      def pick(flag: Boolean): String | Int =
        if flag then "yes" else 0
      def show(x: String | Int): String = x match
        case s: String => s"str:$s"
        case n: Int    => s"int:$n"
      println(show(pick(true)))
      println(show(pick(false)))
    """) shouldBe "str:yes\nint:0"
