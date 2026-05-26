package scalascript.typer

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

/** v2.0 — signature-level real type inference for top-level Def / Val / Class.
 *
 *  Before this work, the typer collapsed almost every top-level definition's
 *  recorded type to `SType.Any`, which made `.scim` interfaces near-useless
 *  for cross-module type checking.  These tests pin down the shapes that
 *  now infer real types and the cases that intentionally stay `Any`.
 *
 *  The inference is intentionally narrow:
 *    - declared annotations always win (round-trip via `typeAnnotToSType`);
 *    - inference for un-annotated `Def` / `Val` bodies covers literals,
 *      single-`VarRef`, simple arithmetic, blocks, and if/else;
 *    - anything richer (calls, selects, lambdas, news) falls back to `Any`.
 */
class TyperRealTypesTest extends AnyFunSuite:

  /** Build a single-block scalascript module from a code body. */
  private def moduleOf(scalascriptSource: String): scalascript.ast.Module =
    val withFence =
      s"""# Test
         |
         |```scalascript
         |$scalascriptSource
         |```
         |""".stripMargin
    Parser.parse(withFence)

  /** Gather all `DefSummary` entries produced by the typer for the given
   *  scalascript source. */
  private def summaries(scalascriptSource: String): List[DefSummary] =
    val typed = Typer.typeCheck(moduleOf(scalascriptSource))
    val buf   = scala.collection.mutable.ListBuffer.empty[DefSummary]
    def walk(s: TypedSection): Unit =
      s.definitions.foreach {
        case TypedDef.CodeBlock(_, _, defs) => buf ++= defs
        case _                              => ()
      }
      s.subsections.foreach(walk)
    typed.sections.foreach(walk)
    buf.toList

  private def summaryOf(scalascriptSource: String, name: String): DefSummary =
    val all = summaries(scalascriptSource)
    all.find(_.name == name).getOrElse {
      fail(s"no DefSummary found for `$name`; got: ${all.map(_.name)}")
    }

  // ── Defn.Def with explicit return type ──────────────────────────────────

  test("def with declared return type — `def foo(a: Int, b: String): List[Int]`"):
    val d = summaryOf(
      """def foo(a: Int, b: String): List[Int] = List(a)""",
      "foo"
    )
    assert(d.kind == SymbolKind.Def)
    val expected = SType.Function(
      List(SType.Int, SType.String),
      SType.Named("List", List(SType.Int))
    )
    assert(d.tpe == expected, s"expected $expected, got ${d.tpe.show}")

  // ── Defn.Def without declared return type — body inference ──────────────

  test("def w/o declared return — `def foo(a: Int) = a + 1` infers Int return"):
    val d = summaryOf("""def foo(a: Int) = a + 1""", "foo")
    val expected = SType.Function(List(SType.Int), SType.Int)
    assert(d.tpe == expected,
      s"expected $expected, got ${d.tpe.show}")

  test("def w/o declared return — literal body infers from literal"):
    assert(summaryOf("""def s() = "hi"""", "s").tpe ==
      SType.Function(Nil, SType.String))
    assert(summaryOf("""def b() = true""", "b").tpe ==
      SType.Function(Nil, SType.Boolean))
    assert(summaryOf("""def d() = 1.5""", "d").tpe ==
      SType.Function(Nil, SType.Double))

  test("def w/o declared return — block body uses last expression's type"):
    val d = summaryOf(
      """def computeIt(a: Int) =
        |  val tmp = a + 1
        |  tmp + 2""".stripMargin,
      "computeIt"
    )
    assert(d.tpe == SType.Function(List(SType.Int), SType.Int),
      s"expected Int => Int, got ${d.tpe.show}")

  // ── Defn.Val with declared type ─────────────────────────────────────────

  test("val with declared type — `val x: Map[String, Int]`"):
    val d = summaryOf(
      """val x: Map[String, Int] = Map.empty""",
      "x"
    )
    assert(d.kind == SymbolKind.Val)
    val expected = SType.Named("Map", List(SType.String, SType.Int))
    assert(d.tpe == expected, s"expected $expected, got ${d.tpe.show}")

  test("val w/o declared type — `val n = 42` infers Int"):
    val d = summaryOf("""val n = 42""", "n")
    assert(d.tpe == SType.Int, s"expected Int, got ${d.tpe.show}")

  test("val w/o declared type — `val s = \"hi\"` infers String"):
    val d = summaryOf("""val s = "hi"""", "s")
    assert(d.tpe == SType.String, s"expected String, got ${d.tpe.show}")

  // ── Defn.Class — constructor signature ──────────────────────────────────

  test("case class — DefSummary records the constructor signature"):
    val d = summaryOf("""case class Foo(x: Int)""", "Foo")
    assert(d.kind == SymbolKind.Class)
    val expected = SType.Function(
      List(SType.Int),
      SType.Named("Foo", Nil)
    )
    assert(d.tpe == expected,
      s"expected (Int) => Foo, got ${d.tpe.show}")

  test("case class with multiple params records the full signature"):
    val d = summaryOf("""case class Pair(x: Int, y: String)""", "Pair")
    val expected = SType.Function(
      List(SType.Int, SType.String),
      SType.Named("Pair", Nil)
    )
    assert(d.tpe == expected,
      s"expected (Int, String) => Pair, got ${d.tpe.show}")

  // ── Declared annotations win over body inference ────────────────────────

  test("declared return type wins — `def bar(): Boolean = if (1 > 0) true else false`"):
    val d = summaryOf(
      """def bar(): Boolean = if (1 > 0) true else false""",
      "bar"
    )
    assert(d.tpe == SType.Function(Nil, SType.Boolean),
      s"expected () => Boolean, got ${d.tpe.show}")

  // ── Graceful fallback for unsupported bodies ────────────────────────────

  test("def w/o declared return — unsupported body falls back to Any without crashing"):
    val d = summaryOf("""def baz() = someUndefinedComplexThing()""", "baz")
    // We can't pin the entire shape because inferType for an unknown name
    // returns `Any`, which then collapses Term.Apply back to `Any`.
    assert(d.tpe == SType.Function(Nil, SType.Any),
      s"expected () => Any for unsupported body, got ${d.tpe.show}")

  // ── If/else convergence ─────────────────────────────────────────────────

  test("def w/o declared return — `if/else` with both branches Int infers Int"):
    val d = summaryOf(
      """def pick(a: Int, b: Int) = if (a > b) a else b""",
      "pick"
    )
    assert(d.tpe == SType.Function(List(SType.Int, SType.Int), SType.Int),
      s"expected (Int, Int) => Int, got ${d.tpe.show}")

  test("def w/o declared return — `if/else` with divergent branches infers Any"):
    val d = summaryOf(
      """def diverge(a: Int) = if (a > 0) 1 else "no"""".stripMargin,
      "diverge"
    )
    assert(d.tpe == SType.Function(List(SType.Int), SType.Any),
      s"expected (Int) => Any, got ${d.tpe.show}")

  // ── Union / intersection annotations ────────────────────────────────────

  test("declared union return type — `def pick(): Int | String`"):
    val d = summaryOf(
      """def pick(): Int | String = 1""",
      "pick"
    )
    assert(d.tpe == SType.Function(
      Nil,
      SType.Union(List(SType.Int, SType.String))),
      s"expected () => Int | String, got ${d.tpe.show}")

  test("declared intersection return type — `def both(): A & B`"):
    val d = summaryOf(
      """def both(): A & B = ???""",
      "both"
    )
    val expected = SType.Function(
      Nil,
      SType.Intersection(List(SType.Named("A", Nil), SType.Named("B", Nil))))
    assert(d.tpe == expected,
      s"expected () => A & B, got ${d.tpe.show}")

  test("declared chained union — `val x: A | B | C`"):
    val d = summaryOf("""val x: A | B | C = ???""", "x")
    val expected = SType.Union(List(
      SType.Named("A", Nil),
      SType.Named("B", Nil),
      SType.Named("C", Nil)))
    assert(d.tpe == expected,
      s"expected A | B | C, got ${d.tpe.show}")

  // ── Tier-5 .scim granularity push (Open question #1) ────────────────────
  //
  // Pre-Tier-5, every `new Foo(...)` body, lambda, and constructor call
  // collapsed to `SType.Any` in the .scim — making interface-based type
  // checking near-useless for case-class APIs and lambda combinators.
  // These tests pin the two highest-impact inferences that now produce
  // real types.

  test("val w/o decltpe — `new Foo(...)` infers Named(\"Foo\")"):
    val d = summaryOf(
      """case class Foo(x: Int)
        |val f = new Foo(42)""".stripMargin, "f")
    assert(d.tpe == SType.Named("Foo", Nil),
      s"expected Named(\"Foo\"), got ${d.tpe.show}")

  test("val w/o decltpe — `new Foo[Int]` infers Named(\"Foo\", List(Int))"):
    val d = summaryOf(
      """class Foo[A]
        |val f = new Foo[Int]""".stripMargin, "f")
    assert(d.tpe == SType.Named("Foo", List(SType.Int)),
      s"expected Named(\"Foo\", List(Int)), got ${d.tpe.show}")

  test("val w/o decltpe — typed-param lambda infers Function(P, R)"):
    val d = summaryOf("val f = (x: Int) => x + 1", "f")
    assert(d.tpe == SType.Function(List(SType.Int), SType.Int),
      s"expected (Int) => Int, got ${d.tpe.show}")

  test("val w/o decltpe — multi-param typed lambda"):
    val d = summaryOf("val f = (a: Int, b: String) => b", "f")
    assert(d.tpe == SType.Function(List(SType.Int, SType.String), SType.String),
      s"expected (Int, String) => String, got ${d.tpe.show}")

  test("val w/o decltpe — typed-param lambda returning literal"):
    val d = summaryOf("val const = (x: Int) => \"ok\"", "const")
    assert(d.tpe == SType.Function(List(SType.Int), SType.String),
      s"expected (Int) => String, got ${d.tpe.show}")

  test("val w/o decltpe — lambda with untyped params still falls back to Any"):
    // No `_.decltpe` → can't infer params → fall back, as documented.
    val d = summaryOf("val f = x => x + 1", "f")
    assert(d.tpe == SType.Any,
      s"untyped lambda params should still be Any; got ${d.tpe.show}")

  test("def return inferred from `new Foo()` body"):
    val d = summaryOf(
      """case class Box(v: Int)
        |def boxed(n: Int) = new Box(n)""".stripMargin, "boxed")
    // The function type's return must now be Named("Box"), not Any.
    d.tpe match
      case SType.Function(params, ret, _) =>
        assert(params == List(SType.Int),
          s"expected params [Int]; got $params")
        assert(ret == SType.Named("Box", Nil),
          s"expected return Named(\"Box\"); got ${ret.show}")
      case other =>
        fail(s"expected Function, got: ${other.show}")

  // ── Tier-5 .scim granularity — Term.Match LUB ──────────────────────────
  //
  // A `match { case … => body }` expression's body type was previously
  // collapsed to `Any` in every case.  When every case-arm RHS infers to
  // the same type, that type now propagates up; divergent arms still
  // fall back to `Any`.  Matches the if/else inference behaviour above.

  test("def w/o declared return — match with same-typed arms infers that type"):
    val d = summaryOf(
      """def label(n: Int) = n match
        |  case 0 => "zero"
        |  case _ => "other"
        |""".stripMargin, "label")
    assert(d.tpe == SType.Function(List(SType.Int), SType.String),
      s"expected (Int) => String, got ${d.tpe.show}")

  test("def w/o declared return — match with divergent arms infers Any"):
    val d = summaryOf(
      """def m(n: Int) = n match
        |  case 0 => "zero"
        |  case _ => 42
        |""".stripMargin, "m")
    assert(d.tpe == SType.Function(List(SType.Int), SType.Any),
      s"expected (Int) => Any, got ${d.tpe.show}")

  test("val w/o decltpe — match RHS picks up arm type"):
    val d = summaryOf(
      """val x = 1 match
        |  case 1 => true
        |  case _ => false
        |""".stripMargin, "x")
    assert(d.tpe == SType.Boolean, s"expected Boolean, got ${d.tpe.show}")

  test("def w/o declared return — match with single arm picks up its type"):
    val d = summaryOf(
      """def go(x: Int) = x match
        |  case _ => List(x)
        |""".stripMargin, "go")
    // List(x) currently infers to `Any` (call-site inference) — match
    // shouldn't make it worse than that, and shouldn't promote to a
    // mismatched type.
    d.tpe match
      case SType.Function(List(SType.Int), _, _) => ()  // any return is OK
      case other => fail(s"expected (Int) => _, got ${other.show}")

  // ── Tier-5 .scim granularity — Term.Select on case-class field ─────────
  //
  // `someFoo.x` where `Foo` is a case class with `x: Int` now resolves
  // to `Int` instead of collapsing to `Any`.  Driven by the new
  // `classFields` table populated by the Defn.Class arm of checkStat.

  test("val w/o decltpe — `foo.x` infers field type from case-class table"):
    val d = summaryOf(
      """case class Foo(x: Int, name: String)
        |val f = new Foo(1, "hi")
        |val n = f.x""".stripMargin, "n")
    assert(d.tpe == SType.Int, s"expected Int, got ${d.tpe.show}")

  test("val w/o decltpe — `foo.name` infers String from case-class table"):
    val d = summaryOf(
      """case class Foo(x: Int, name: String)
        |val f = new Foo(1, "hi")
        |val s = f.name""".stripMargin, "s")
    assert(d.tpe == SType.String, s"expected String, got ${d.tpe.show}")

  test("val w/o decltpe — `foo.unknown` falls back to Any"):
    val d = summaryOf(
      """case class Foo(x: Int)
        |val f = new Foo(1)
        |val u = f.unknown""".stripMargin, "u")
    assert(d.tpe == SType.Any, s"expected Any, got ${d.tpe.show}")

  test("def return — chained `.field.field` on case-class instances"):
    val d = summaryOf(
      """case class Inner(v: Int)
        |case class Outer(inner: Inner)
        |def get(o: Outer) = o.inner.v
        |""".stripMargin, "get")
    assert(d.tpe == SType.Function(List(SType.Named("Outer", Nil)), SType.Int),
      s"expected (Outer) => Int, got ${d.tpe.show}")

  // ── Tier-5 .scim granularity — for-comprehension yield ─────────────────
  //
  // `for { x <- xs } yield body` is the Scala spelling for `xs.map(x =>
  // body)`.  With no method/`map`-signature awareness in the typer
  // we can't infer the wrapping container, but we CAN infer the
  // body type — and a `def` whose RHS is a `for/yield` should at
  // least have a Function return type rather than `Any`.  This test
  // pins the body-inference behaviour; the wrapping container stays
  // `Any` for now (full polymorphic-method-call inference deferred).

  test("def w/o declared return — for/yield body infers to Any when xs is unknown"):
    val d = summaryOf(
      """def go(xs: List[Int]) = for x <- xs yield x + 1""", "go")
    // We don't yet infer `List[Int]` for the container, but the function
    // signature must still be `(List[Int]) => _`.  Accept any return.
    d.tpe match
      case SType.Function(List(SType.Named("List", List(SType.Int))), _, _) => ()
      case other => fail(s"expected (List[Int]) => _, got ${other.show}")
