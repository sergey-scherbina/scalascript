package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser
import scalascript.typer.Typer

/** Conformance tests for v1.24 — type aliases (`type UserId = String`). */
class TypeAliasTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    Interpreter(ps).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  private def typeErrors(code: String): List[String] =
    val src    = s"# Test\n\n```scala\n$code\n```\n"
    val module = Parser.parse(src)
    Typer.typeCheck(module).errors.map(_.msg)

  // ── Simple alias ────────────────────────────────────────────────────

  test("simple alias: type Name = String used in function signature"):
    captured("""
      type Name = String
      def greet(n: Name): String = s"Hello $n"
      println(greet("Alice"))
    """) shouldBe "Hello Alice"

  test("simple alias: type UserId = String, alias name accepted at call site"):
    captured("""
      type UserId = String
      def show(id: UserId): String = id
      println(show("u-42"))
    """) shouldBe "u-42"

  test("simple alias: type alias does not produce any runtime output"):
    captured("""
      type Phantom = Int
      println("ok")
    """) shouldBe "ok"

  test("simple alias: value of aliased type can be passed to function expecting base type"):
    captured("""
      type Score = Int
      def doubled(s: Score): Int = s * 2
      println(doubled(21))
    """) shouldBe "42"

  // ── Parameterized alias ─────────────────────────────────────────────

  test("parameterized alias: type Opt[A] = Option[A]"):
    captured("""
      type Opt[A] = Option[A]
      val x: Opt[Int] = Some(7)
      println(x.getOrElse(0))
    """) shouldBe "7"

  test("parameterized alias: type Pair[A, B] = (A, B) — alias in val type annotation"):
    // The interpreter doesn't enforce val type annotations at runtime,
    // so we just verify the code runs without error and produces correct output.
    captured("""
      type Pair[A, B] = (A, B)
      val p: Pair[Int, String] = (1, "one")
      println(p._1)
      println(p._2)
    """) shouldBe "1\none"

  test("parameterized alias: type Result[A] = Either[String, A] — use in def return type"):
    captured("""
      type Result[A] = Either[String, A]
      def parse(s: String): Result[Int] =
        if s == "42" then Right(42) else Left("bad")
      val r = parse("42")
      println(r.isRight)
    """) shouldBe "true"

  // ── Alias of alias ──────────────────────────────────────────────────

  test("alias of alias: type A = String; type B = A — B expands to String"):
    captured("""
      type A = String
      type B = A
      def echo(b: B): String = b
      println(echo("hello"))
    """) shouldBe "hello"

  test("alias of alias: chained expansion works in function param"):
    captured("""
      type Raw = Int
      type Count = Raw
      def inc(c: Count): Count = c + 1
      println(inc(41))
    """) shouldBe "42"

  // ── Typer: alias registered in scope ───────────────────────────────

  test("typer: simple alias is registered in scope with no type errors"):
    typeErrors("""
      type Name = String
      def greet(n: Name): String = s"Hi $n"
    """) shouldBe empty

  test("typer: parameterized alias is registered in scope with no type errors"):
    // Use the alias in a def parameter position (avoids assignability checks
    // that the lightweight typer doesn't resolve inside generics).
    typeErrors("""
      type Pair[A, B] = (A, B)
      def first(p: Pair[Int, String]): Int = p._1
    """) shouldBe empty

  // ── Recursive alias detection ───────────────────────────────────────

  test("typer: directly recursive alias produces a type error"):
    val errs = typeErrors("type Bad = List[Bad]")
    errs should not be empty
    errs.head should include("Recursive")

  // ── Error case: wrong argument count for parameterized alias ────────

  test("typer: arity mismatch for parameterized alias produces a type error"):
    val errs = typeErrors("""
      type Pair[A, B] = (A, B)
      val x: Pair[Int] = (1, 2)
    """)
    errs should not be empty
    errs.head should include("Pair")
