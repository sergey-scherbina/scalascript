package scalascript.artifact

import org.scalatest.funsuite.AnyFunSuite
import scalascript.typer.SType

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

  test("union and intersection shapes are not parsed yet — fall back to Any") {
    // These would need richer grammar; ensure the parser doesn't claim
    // success and produce a malformed Named like "Int |".
    assert(parse("Int | String") == SType.Any)
    assert(parse("Eq[A] & Show[A]") == SType.Any)
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
      SType.Named("scala.Option", List(SType.Int))
    )
    samples.foreach { t =>
      val rendered = t.show
      val parsed   = parse(rendered)
      assert(parsed == t,
        s"round-trip failed: ${t} -> ${rendered} -> ${parsed}")
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
