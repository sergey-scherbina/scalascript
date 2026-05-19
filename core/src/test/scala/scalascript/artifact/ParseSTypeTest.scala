package scalascript.artifact

import org.scalatest.funsuite.AnyFunSuite
import scalascript.typer.{SType, RefMember, MatchCase}

/** Round-trip + shape coverage for [[InterfaceScope.parseSType]].
 *
 *  The contract is: for every shape the typer emits (`Named` with or
 *  without type args, `Function`, `Tuple`), `parseSType(t.show) == t`.
 *  Shapes the parser intentionally does not cover (unions, intersections,
 *  type variables, errors) fall back to `SType.Any`.
 */
class ParseSTypeTest extends AnyFunSuite:

  /** Re-exposes the package-private parser for tests. */
  private def parse(s: String): SType = InterfaceScope.parseSType(s)

  // ── Primitives ─────────────────────────────────────────────────────────

  test("primitive named types round-trip via show") {
    val primitives = List(
      SType.Any, SType.Unit, SType.Boolean, SType.Int, SType.Long,
      SType.Double, SType.String, SType.Char, SType.Nothing, SType.Null
    )
    primitives.foreach { t =>
      assert(parse(t.show) == t, s"failed to round-trip ${t.show}")
    }
  }

  test("bare identifier becomes Named with no args") {
    assert(parse("Int") == SType.Int)
    assert(parse("Foo") == SType.Named("Foo", Nil))
    // Single-letter type variables are preserved as Named for now.
    assert(parse("A") == SType.Named("A", Nil))
    assert(parse("T") == SType.Named("T", Nil))
  }

  // ── Generic application ────────────────────────────────────────────────

  test("List[Int] parses to Named with one arg") {
    assert(parse("List[Int]") == SType.Named("List", List(SType.Int)))
  }

  test("Map[String, Int] parses to Named with two args") {
    assert(parse("Map[String, Int]") ==
      SType.Named("Map", List(SType.String, SType.Int)))
  }

  test("Option[A] preserves the type variable as Named") {
    assert(parse("Option[A]") ==
      SType.Named("Option", List(SType.Named("A", Nil))))
  }

  test("nested type application: List[Option[Int]]") {
    val expected = SType.Named("List",
      List(SType.Named("Option", List(SType.Int))))
    assert(parse("List[Option[Int]]") == expected)
  }

  // ── Functions ──────────────────────────────────────────────────────────

  test("Int => String parses to single-param Function") {
    assert(parse("Int => String") ==
      SType.Function(List(SType.Int), SType.String))
  }

  test("multi-arg function (Int, String) => Boolean") {
    assert(parse("(Int, String) => Boolean") ==
      SType.Function(List(SType.Int, SType.String), SType.Boolean))
  }

  test("nullary function () => Int") {
    assert(parse("() => Int") == SType.Function(Nil, SType.Int))
  }

  test("right-associative arrows: A => B => C") {
    val expected = SType.Function(
      List(SType.Named("A", Nil)),
      SType.Function(List(SType.Named("B", Nil)), SType.Named("C", Nil)))
    assert(parse("A => B => C") == expected)
  }

  test("higher-order function: (Int => String) => Boolean") {
    val expected = SType.Function(
      List(SType.Function(List(SType.Int), SType.String)),
      SType.Boolean)
    assert(parse("(Int => String) => Boolean") == expected)
  }

  // ── Tuples ─────────────────────────────────────────────────────────────

  test("(Int, String) parses to Tuple of two") {
    assert(parse("(Int, String)") ==
      SType.Tuple(List(SType.Int, SType.String)))
  }

  test("triple tuple") {
    assert(parse("(Int, String, Boolean)") ==
      SType.Tuple(List(SType.Int, SType.String, SType.Boolean)))
  }

  test("parenthesised single type is not a tuple") {
    assert(parse("(Int)") == SType.Int)
  }

  test("bare () is Unit, not a tuple") {
    assert(parse("()") == SType.Unit)
  }

  // ── Qualified paths ────────────────────────────────────────────────────

  test("dotted path stays as Named with dots preserved") {
    assert(parse("foo.Bar")        == SType.Named("foo.Bar", Nil))
    assert(parse("scala.Option")   == SType.Named("scala.Option", Nil))
    assert(parse("std.actors.ChildSpec") ==
      SType.Named("std.actors.ChildSpec", Nil))
  }

  test("qualified path with type args") {
    assert(parse("scala.Option[Int]") ==
      SType.Named("scala.Option", List(SType.Int)))
  }

  // ── Whitespace tolerance ───────────────────────────────────────────────

  test("whitespace around tokens is ignored") {
    assert(parse("  Int  ")             == SType.Int)
    assert(parse("List[ Int ]")         == SType.Named("List", List(SType.Int)))
    assert(parse("Map [String , Int]")  ==
      SType.Named("Map", List(SType.String, SType.Int)))
    assert(parse("Int=>String")         ==
      SType.Function(List(SType.Int), SType.String))
  }

  // ── Robustness ─────────────────────────────────────────────────────────

  test("empty / garbage / partial input collapses to Any") {
    assert(parse("")        == SType.Any)
    assert(parse("   ")     == SType.Any)
    assert(parse("???")     == SType.Any)
    assert(parse("List[")   == SType.Any)
    assert(parse("(Int,)")  == SType.Any)
    assert(parse("=> Int")  == SType.Any)
    assert(parse("Int =>")  == SType.Any)
  }

  // ── Union, intersection, higher-kinded ─────────────────────────────────

  test("simple union: A | B") {
    assert(parse("A | B") ==
      SType.Union(List(SType.Named("A", Nil), SType.Named("B", Nil))))
  }

  test("union flattens left-to-right: A | B | C") {
    assert(parse("A | B | C") == SType.Union(List(
      SType.Named("A", Nil),
      SType.Named("B", Nil),
      SType.Named("C", Nil))))
  }

  test("simple intersection: A & B") {
    assert(parse("A & B") ==
      SType.Intersection(List(SType.Named("A", Nil), SType.Named("B", Nil))))
  }

  test("intersection of typeclass apps: Eq[A] & Show[A]") {
    assert(parse("Eq[A] & Show[A]") == SType.Intersection(List(
      SType.Named("Eq",   List(SType.Named("A", Nil))),
      SType.Named("Show", List(SType.Named("A", Nil))))))
  }

  test("`&` binds tighter than `|`: A | B & C") {
    assert(parse("A | B & C") == SType.Union(List(
      SType.Named("A", Nil),
      SType.Intersection(List(SType.Named("B", Nil), SType.Named("C", Nil))))))
  }

  test("`|` and `&` bind tighter than `=>`: Int | String => Boolean") {
    assert(parse("Int | String => Boolean") == SType.Function(
      List(SType.Union(List(SType.Int, SType.String))),
      SType.Boolean))
  }

  test("parens override default precedence: (A | B) & C") {
    assert(parse("(A | B) & C") == SType.Intersection(List(
      SType.Union(List(SType.Named("A", Nil), SType.Named("B", Nil))),
      SType.Named("C", Nil))))
  }

  test("higher-kinded type parameter: F[_]") {
    assert(parse("F[_]") == SType.HigherKinded("F", 1))
  }

  test("higher-kinded with concrete name: Eq[_]") {
    assert(parse("Eq[_]") == SType.HigherKinded("Eq", 1))
  }

  test("higher-kinded arity 2: F[_, _]") {
    assert(parse("F[_, _]") == SType.HigherKinded("F", 2))
  }

  test("mixed `_` with concrete arg stays a Named app — not higher-kinded") {
    // Decision: only when ALL type args are `_` do we collapse to
    // `HigherKinded`.  A partial wildcard like `Map[String, _]` keeps
    // its structure as `Named("Map", List(String, Named("_", Nil)))`
    // so the wildcard slot is still observable.
    assert(parse("Map[String, _]") == SType.Named("Map",
      List(SType.String, SType.Named("_", Nil))))
  }

  // ── Round-trip across the full SType.show grammar ─────────────────────

  test("structural round-trip for representative SType values") {
    val samples = List(
      SType.Int,
      SType.Named("Foo", Nil),
      SType.Named("List", List(SType.Int)),
      SType.Named("Map", List(SType.String, SType.Int)),
      SType.Named("Option", List(SType.Named("A", Nil))),
      SType.Named("List", List(SType.Named("Option", List(SType.Int)))),
      SType.Function(List(SType.Int), SType.String),
      SType.Function(List(SType.Int, SType.String), SType.Boolean),
      SType.Function(Nil, SType.Int),
      SType.Function(List(SType.Function(List(SType.Int), SType.String)),
                     SType.Boolean),
      SType.Tuple(List(SType.Int, SType.String)),
      SType.Tuple(List(SType.Int, SType.String, SType.Boolean)),
      // Qualified path retained verbatim — Named already supports dots.
      SType.Named("std.actors.ChildSpec", Nil),
      SType.Named("scala.Option", List(SType.Int)),
      // Union / intersection / higher-kinded — surface-type round-trip.
      SType.Union(List(SType.Named("A", Nil), SType.Named("B", Nil))),
      SType.Union(List(SType.Int, SType.String, SType.Boolean)),
      SType.Intersection(List(
        SType.Named("Eq",   List(SType.Named("A", Nil))),
        SType.Named("Show", List(SType.Named("A", Nil))))),
      // `&` binds tighter than `|`: print and re-parse must preserve
      // the `Union(A, Intersection(B, C))` shape.
      SType.Union(List(
        SType.Named("A", Nil),
        SType.Intersection(List(SType.Named("B", Nil), SType.Named("C", Nil))))),
      // `Intersection` containing a `Union` — must be parenthesised by
      // `show` so re-parsing keeps the precedence right.
      SType.Intersection(List(
        SType.Union(List(SType.Named("A", Nil), SType.Named("B", Nil))),
        SType.Named("C", Nil))),
      // Function whose param is a `Union` — show must parenthesise the
      // union since `=>` is the loosest construct.
      SType.Function(
        List(SType.Union(List(SType.Int, SType.String))),
        SType.Boolean),
      SType.HigherKinded("F", 1),
      SType.HigherKinded("Eq", 1),
      SType.HigherKinded("Functor", 2)
    )
    samples.foreach { t =>
      val rendered = t.show
      val parsed   = parse(rendered)
      assert(parsed == t,
        s"round-trip failed: ${t} -> ${rendered} -> ${parsed}")
    }
  }

  // ── Refinement types ───────────────────────────────────────────────────

  test("refinement with one def member: A { def foo: Int }") {
    assert(parse("A { def foo: Int }") ==
      SType.Refinement(
        SType.Named("A", Nil),
        List(RefMember("def", "foo", SType.Int))))
  }

  test("refinement with multiple members: A { def foo: Int; val bar: String }") {
    assert(parse("A { def foo: Int; val bar: String }") ==
      SType.Refinement(
        SType.Named("A", Nil),
        List(
          RefMember("def", "foo", SType.Int),
          RefMember("val", "bar", SType.String))))
  }

  test("refinement with type member: A { type T = Int }") {
    // We don't capture `= Int` semantics — only the declaration shape:
    // kind=type, name=T, sig=Int.  The parser tolerates both the
    // source-level `=` separator and the canonical `:` separator that
    // `SType.show` emits, so the structural payload is preserved.
    val expected = SType.Refinement(
      SType.Named("A", Nil),
      List(RefMember("type", "T", SType.Int)))
    assert(parse("A { type T = Int }") == expected)
    assert(parse("A { type T: Int }")  == expected)
  }

  test("refinement of generic base: List[Int] { def head: Int }") {
    assert(parse("List[Int] { def head: Int }") ==
      SType.Refinement(
        SType.Named("List", List(SType.Int)),
        List(RefMember("def", "head", SType.Int))))
  }

  test("refinement with function-typed member: A { def f: Int => String }") {
    assert(parse("A { def f: Int => String }") ==
      SType.Refinement(
        SType.Named("A", Nil),
        List(RefMember("def", "f",
          SType.Function(List(SType.Int), SType.String)))))
  }

  test("empty refinement: A { } parses to empty member list") {
    assert(parse("A { }") ==
      SType.Refinement(SType.Named("A", Nil), Nil))
  }

  // ── Match types ────────────────────────────────────────────────────────

  test("match type: T match { case Int => String; case _ => Any }") {
    assert(parse("T match { case Int => String; case _ => Any }") ==
      SType.Match(
        SType.Named("T", Nil),
        List(
          MatchCase(SType.Int, SType.String),
          MatchCase(SType.Named("_", Nil), SType.Any))))
  }

  test("match type with single case: T match { case Int => String }") {
    assert(parse("T match { case Int => String }") ==
      SType.Match(
        SType.Named("T", Nil),
        List(MatchCase(SType.Int, SType.String))))
  }

  test("match type with generic scrutinee: F[A] match { case Int => String }") {
    assert(parse("F[A] match { case Int => String }") ==
      SType.Match(
        SType.Named("F", List(SType.Named("A", Nil))),
        List(MatchCase(SType.Int, SType.String))))
  }

  // ── Round-trip refinement / match through show + parse ────────────────

  test("structural round-trip for Refinement and Match SType values") {
    val samples = List(
      SType.Refinement(
        SType.Named("A", Nil),
        List(RefMember("def", "foo", SType.Int))),
      SType.Refinement(
        SType.Named("A", Nil),
        List(
          RefMember("def", "foo", SType.Int),
          RefMember("val", "bar", SType.String),
          RefMember("type", "T",   SType.Int))),
      SType.Refinement(
        SType.Named("List", List(SType.Int)),
        List(RefMember("def", "head", SType.Int))),
      SType.Refinement(
        SType.Named("A", Nil),
        List(RefMember("def", "f",
          SType.Function(List(SType.Int), SType.String)))),
      SType.Match(
        SType.Named("T", Nil),
        List(
          MatchCase(SType.Int, SType.String),
          MatchCase(SType.Named("_", Nil), SType.Any))),
      SType.Match(
        SType.Named("F", List(SType.Named("A", Nil))),
        List(MatchCase(SType.Int, SType.String)))
    )
    samples.foreach { t =>
      val rendered = t.show
      val parsed   = parse(rendered)
      assert(parsed == t,
        s"round-trip failed: $t -> $rendered -> $parsed")
    }
  }

  // ── Sanity vs. the prior collapse-to-Any behaviour ─────────────────────

  test("realistic interface entries no longer collapse to Any") {
    // Before this change, anything beyond ~7 primitives became Any and
    // any unary function shape was hard-coded as List(arg) -> ret with
    // no real parsing of nested arrows or tuples.  Spot-check that we
    // now retain useful structure.
    val parsed = parse("(String, Int) => List[Int]")
    assert(parsed == SType.Function(
      List(SType.String, SType.Int),
      SType.Named("List", List(SType.Int))))
    assert(parsed != SType.Any)
  }
