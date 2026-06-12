package scalascript.typer

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

/** Nominal subtyping for `case class … extends Trait` (busi testbed find).
 *
 *  Before the fix, `Typer.isCompatible` had no nominal-subtype rule: the only
 *  reason a `C <: T` upcast ever passed was an accidental `looksLikeTypeVar`
 *  match on single-uppercase names. Real multi-character names (`Ab`, `Foo`,
 *  `MemoryRepo`) failed with a confusing `Type mismatch`. Spec:
 *  `specs/typer-nominal-subtype.md`.
 */
class NominalSubtypeTest extends AnyFunSuite:

  private def moduleOf(src: String): scalascript.ast.Module =
    Parser.parse(s"""# Test
                    |
                    |```scalascript
                    |$src
                    |```
                    |""".stripMargin)

  private def errs(src: String): List[String] =
    Typer.typeCheck(moduleOf(src)).errors.map(_.msg)

  test("case class extends trait — multi-char names upcast cleanly"):
    val e = errs(
      """trait Ab:
        |  def m: Int
        |case class Bc() extends Ab:
        |  def m: Int = 1
        |def f(): Ab = Bc()""".stripMargin)
    assert(e.isEmpty, s"expected no errors, got: ${e.mkString(" | ")}")

  test("transitive: case class -> trait -> trait"):
    val e = errs(
      """trait Top:
        |  def m: Int
        |trait Mid extends Top
        |case class Leaf() extends Mid:
        |  def m: Int = 1
        |def f(): Top = Leaf()""".stripMargin)
    assert(e.isEmpty, s"expected no errors, got: ${e.mkString(" | ")}")

  test("enum extends trait — a case is usable where the trait is expected"):
    val e = errs(
      """trait Event
        |enum LedgerEvent extends Event:
        |  case Created(id: String)
        |  case Closed(id: String)
        |def asEvent(): Event = LedgerEvent.Created("x")""".stripMargin)
    assert(e.isEmpty, s"expected no errors, got: ${e.mkString(" | ")}")

  test("single-uppercase names still upcast (regression guard)"):
    val e = errs(
      """trait A:
        |  def m: Int
        |case class B() extends A:
        |  def m: Int = 1
        |def f(): A = B()""".stripMargin)
    assert(e.isEmpty, s"expected no errors, got: ${e.mkString(" | ")}")

  test("negative — an unrelated class is NOT accepted as the trait"):
    val e = errs(
      """trait Animal:
        |  def legs: Int
        |case class Dog() extends Animal:
        |  def legs: Int = 4
        |case class Rock()
        |def wrong(): Animal = Rock()""".stripMargin)
    assert(e.exists(_.contains("Type mismatch")),
      s"expected a Type mismatch for Rock (not an Animal), got: ${e.mkString(" | ")}")
