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
