package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Conformance tests for v1.14 — `inline` + `derives` metaprogramming MVP. */
class InlineDerivesTest extends AnyFunSuite with Matchers:

  private val repoRoot = TestPaths.repoRoot

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    Interpreter(ps).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  private def capturedWithEq(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src =
      s"""# Test
         |
         |[Eq, eqv, neqv](std/eq.ssc)
         |
         |```scala
         |$code
         |```
         |""".stripMargin
    Interpreter(ps, baseDir = Some(repoRoot)).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  private def capturedWithShow(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src =
      s"""# Test
         |
         |[Show, show](std/show.ssc)
         |
         |```scala
         |$code
         |```
         |""".stripMargin
    Interpreter(ps, baseDir = Some(repoRoot)).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  private def capturedWithHash(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src =
      s"""# Test
         |
         |[Hash, hash](std/hash.ssc)
         |
         |```scala
         |$code
         |```
         |""".stripMargin
    Interpreter(ps, baseDir = Some(repoRoot)).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  private def capturedWithOrder(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src =
      s"""# Test
         |
         |[Order, compare, lt, gt, lte, gte, min, max](std/order.ssc)
         |
         |```scala
         |$code
         |```
         |""".stripMargin
    Interpreter(ps, baseDir = Some(repoRoot)).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  private def capturedWithEqShow(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src =
      s"""# Test
         |
         |[Eq, eqv, neqv](std/eq.ssc)
         |[Show, show](std/show.ssc)
         |
         |```scala
         |$code
         |```
         |""".stripMargin
    Interpreter(ps, baseDir = Some(repoRoot)).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  // ── Phase 1: inline def / val / if / match ────────────────────────

  test("inline def — basic call works"):
    captured("""
      inline def double(n: Int): Int = n * 2
      println(double(21))
    """) shouldBe "42"

  test("inline val — constant value"):
    captured("""
      inline val Pi = 3.14
      println(Pi)
    """) shouldBe "3.14"

  test("inline if — condition folding"):
    captured("""
      inline val flag = true
      val result = inline if flag then "yes" else "no"
      println(result)
    """) shouldBe "yes"

  test("inline match — wildcard and literal patterns"):
    captured("""
      inline def label(x: Any): String =
        x match
          case 1     => "one"
          case 2     => "two"
          case _     => "many"
      println(label(1))
      println(label(2))
      println(label(99))
    """) shouldBe "one\ntwo\nmany"

  // ── Phase 2: compiletime.* ────────────────────────────────────────

  test("compiletime.constValue[42] returns 42"):
    captured("""
      val v = compiletime.constValue[42]
      println(v)
    """) shouldBe "42"

  test("compiletime.constValue[\"hello\"] returns \"hello\""):
    captured("""
      val v = compiletime.constValue["hello"]
      println(v)
    """) shouldBe "hello"

  test("compiletime.summonInline[Eq[Int]] returns an Eq instance"):
    capturedWithEq("""
      given Eq[Int] with
        def eqv(a: Int, b: Int): Boolean = a == b
      val eq = compiletime.summonInline[Eq[Int]]
      println(eq.eqv(1, 1))
      println(eq.eqv(1, 2))
    """) shouldBe "true\nfalse"

  test("compiletime.error throws"):
    an[Exception] should be thrownBy captured("""
      compiletime.error("this is a compile-time error")
    """)

  // ── Phase 4: restricted quoted macro interpreter parity ───────────

  test("restricted quoted macro — direct interpreter run expands quoted body"):
    captured("""
      inline def plusOne(x: Int): Int = ${ plusOneImpl('x) }
      def plusOneImpl(x: Expr[Int])(using q: QuotedContext): Expr[Int] = '{ $x + 1 }
      println(plusOne(41))
    """) shouldBe "42"

  test("restricted quoted macro — Expr.asValue exposes runtime quoted value"):
    captured("""
      inline def literalLabel(x: Int): String = ${ literalLabelImpl('x) }
      def literalLabelImpl(x: Expr[Int])(using q: QuotedContext): Expr[String] =
        "literal: " + x.asValue.getOrElse("?")
      println(literalLabel(7))
    """) shouldBe "literal: 7"

  test("restricted quoted macro — Expr.asTerm exposes opaque term metadata"):
    captured("""
      inline def termName(x: Int): String = ${ termNameImpl('x) }
      def termNameImpl(x: Expr[Int])(using q: QuotedContext): Expr[String] =
        x.asTerm.name
      println(termName(5))
    """) shouldBe "x"

  test("restricted quoted macro — unsupported entrypoint reports a diagnostic"):
    val ex = intercept[Exception] {
      captured("""
        inline def bad(x: Int): Int = ${ badImpl(x) }
        def badImpl(x: Int): Int = x + 1
        println(bad(1))
      """)
    }
    ex.getMessage should include ("quoted macro error")
    ex.getMessage should include ("quoted arguments")

  // ── Phase 3: derives Eq ───────────────────────────────────────────

  test("derives Eq — eqv(a, a) is true"):
    capturedWithEq("""
      case class Point(x: Int, y: Int) derives Eq
      val p = Point(1, 2)
      val eq = summon[Eq[Point]]
      println(eq.eqv(p, p))
    """) shouldBe "true"

  test("derives Eq — eqv(a, b) with different values is false"):
    capturedWithEq("""
      case class Point(x: Int, y: Int) derives Eq
      val p1 = Point(1, 2)
      val p2 = Point(3, 4)
      val eq = summon[Eq[Point]]
      println(eq.eqv(p1, p2))
    """) shouldBe "false"

  // ── Phase 3: derives Show ─────────────────────────────────────────

  test("derives Show — renders case class as TypeName(field=value, ...)"):
    capturedWithShow("""
      case class Person(name: String, age: Int) derives Show
      val p = Person("Alice", 30)
      val s = summon[Show[Person]]
      println(s.show(p))
    """) shouldBe "Person(name=Alice, age=30)"

  // ── Phase 3: derives Hash ─────────────────────────────────────────

  test("derives Hash — same value produces same hash"):
    capturedWithHash("""
      case class Key(id: Int) derives Hash
      val k = Key(42)
      val h = summon[Hash[Key]]
      println(h.hash(k) == h.hash(k))
    """) shouldBe "true"

  // ── Phase 3: derives Order ────────────────────────────────────────

  test("derives Order — compare(a, b) correct sign"):
    capturedWithOrder("""
      case class Score(value: Int) derives Order
      val s1 = Score(10)
      val s2 = Score(20)
      val ord = summon[Order[Score]]
      println(ord.compare(s1, s2) < 0)
      println(ord.compare(s2, s1) > 0)
      println(ord.compare(s1, s1) == 0)
    """) shouldBe "true\ntrue\ntrue"

  // ── Phase 3: multiple derives ─────────────────────────────────────

  test("derives Eq, Show — multiple derives on one class"):
    capturedWithEqShow("""
      case class Tag(label: String) derives Eq, Show
      val t1 = Tag("foo")
      val t2 = Tag("bar")
      val eq   = summon[Eq[Tag]]
      val show = summon[Show[Tag]]
      println(eq.eqv(t1, t1))
      println(eq.eqv(t1, t2))
      println(show.show(t1))
    """) shouldBe "true\nfalse\nTag(label=foo)"

  // ── Phase 3b: derives on sealed trait subtype ─────────────────────

  test("derives Show on sealed trait subtype — renders correctly"):
    capturedWithShow("""
      sealed trait Shape derives Show
      case class Circle(radius: Double) derives Show
      val c = Circle(5.5)
      val s = summon[Show[Circle]]
      println(s.show(c))
    """) shouldBe "Circle(radius=5.5)"

  // ── arch-meta-v2-p5: public Mirror metadata + user typeclass derives ───

  test("Mirror.Of — summon exposes product labels, types, and fromProduct"):
    captured("""
      case class Person(name: String, age: Int)
      val m = summon[Mirror.Of[Person]]
      println(m.label)
      println(m.elemLabels.mkString("|"))
      println(m.elemTypes.mkString("|"))
      val p = m.fromProduct(List("Ada", 42))
      println(p.name)
      println(p.age)
    """) shouldBe "Person\nname|age\nString|Int\nAda\n42"

  test("derives custom typeclass via Mirror metadata"):
    captured("""
      trait Csv[A]:
        def header: String

      case class CsvInstance(header: String) extends Csv[Any]

      object Csv:
        def derived(m: Mirror): Csv[Any] =
          CsvInstance(m.elemLabels.mkString(","))

      case class Person(name: String, age: Int) derives Csv
      val csv = summon[Csv[Person]]
      println(csv.header)
    """) shouldBe "name,age"
