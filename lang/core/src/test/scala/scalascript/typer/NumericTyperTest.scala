package scalascript.typer

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

/** exact-numerics v1.64.3 — typer support for BigInt / Decimal:
 *  the numeric tower and the deliberate Decimal⊕Double type error. */
class NumericTyperTest extends AnyFunSuite:

  private def moduleOf(src: String): scalascript.ast.Module =
    Parser.parse(s"# Test\n\n```scalascript\n$src\n```\n")

  private def errorsFor(src: String): List[String] =
    Typer.typeCheck(moduleOf(src)).errors.map(_.msg)

  test("Decimal + Double is a type error"):
    val msgs = errorsFor("""def f() = Decimal("1.5") + 2.0""")
    assert(msgs.exists(_.contains("Decimal")) && msgs.exists(_.contains("Double")),
      s"expected a Decimal/Double mix error; got: $msgs")

  test("Double + Decimal is a type error (either order)"):
    val msgs = errorsFor("""def f() = 2.0 * Decimal("1.5")""")
    assert(msgs.exists(m => m.contains("Decimal") && m.contains("Double")),
      s"expected a Decimal/Double mix error; got: $msgs")

  test("BigInt + Double is a type error"):
    val msgs = errorsFor("""def f() = BigInt("9") - 1.0""")
    assert(msgs.exists(m => m.contains("BigInt") && m.contains("Double")),
      s"expected a BigInt/Double mix error; got: $msgs")

  test("Decimal + Int is fine (Int widens into the exact world)"):
    assert(errorsFor("""def f() = Decimal("1.5") + 2""").isEmpty,
      "Decimal + Int should type-check cleanly")

  test("BigInt + Int is fine"):
    assert(errorsFor("""def f() = BigInt("9") + 2""").isEmpty,
      "BigInt + Int should type-check cleanly")

  test("Decimal * BigInt is fine"):
    assert(errorsFor("""def f() = Decimal("2.5") * BigInt("4")""").isEmpty,
      "Decimal * BigInt should type-check cleanly")

  test("Int is assignable where Decimal is expected"):
    assert(errorsFor("""val x: Decimal = 5""").isEmpty,
      "Int literal should widen to a Decimal binding")

  test("BigInt is assignable where Decimal is expected"):
    assert(errorsFor("""val x: Decimal = BigInt("5")""").isEmpty,
      "BigInt should widen to a Decimal binding")

  test("Decimal is NOT assignable where Double is expected"):
    assert(errorsFor("""val x: Double = Decimal("1.5")""").nonEmpty,
      "Decimal must not silently flow into a Double binding")

  test("Decimal comparison with Int yields Boolean, no error"):
    assert(errorsFor("""def f(): Boolean = Decimal("1.5") < 2""").isEmpty,
      "Decimal < Int should type-check as Boolean")

  test("annotated Decimal/BigInt types are recognised (not unknown)"):
    assert(errorsFor(
      """def total(a: Decimal, b: BigInt): Decimal = a + b"""
    ).isEmpty, "Decimal and BigInt annotations should resolve and type-check")
