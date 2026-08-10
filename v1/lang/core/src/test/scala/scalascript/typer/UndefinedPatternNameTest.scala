package scalascript.typer

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

/** A CAPITALISED bare name in pattern position MATCHES against something that must already exist;
 *  a lowercase one BINDS. `case Nope =>` against nothing resolved to nothing and fell through
 *  silently, so the arm simply never matched — and the three lanes each invented an answer: int and
 *  native printed the fallback at exit 0, js emitted the name verbatim and threw a run-time
 *  `ReferenceError` (BUGS.md `an-undefined-name-in-a-pattern-means-three-different-things`).
 *  v3 already reports `unknown constructor '…' in a pattern`; this says the same sentence.
 *
 *  The last two tests are the controls that make the first mean something: the capitalisation rule
 *  itself was never the defect, so a lowercase binder and a real constructor must both be untouched.
 *  Without them this check could pass by rejecting every pattern. */
class UndefinedPatternNameTest extends AnyFunSuite:

  private def moduleOf(src: String): scalascript.ast.Module =
    Parser.parse(s"# Test\n\n```scalascript\n$src\n```\n")

  private def errorsOf(src: String): List[String] =
    Typer().typeCheck(moduleOf(src)).errors.map(_.msg)

  test("a capitalised pattern name that resolves to nothing is reported"):
    val msgs = errorsOf(
      """val x = 1
        |x match
        |  case Nope => println("BOUND")
        |  case _ => println("NO MATCH")""".stripMargin)
    assert(msgs.exists(_.contains("unknown constructor 'Nope'")), msgs.mkString(" | "))

  test("a LOWERCASE name binds and is NOT reported"):
    val msgs = errorsOf(
      """val x = 1
        |x match
        |  case nope => println(nope.toString)""".stripMargin)
    assert(!msgs.exists(_.contains("unknown constructor")), msgs.mkString(" | "))

  test("a REAL constructor is NOT reported"):
    val msgs = errorsOf(
      """val x: Option[Int] = Some(3)
        |x match
        |  case Some(v) => println(v.toString)
        |  case None => println("none")""".stripMargin)
    assert(!msgs.exists(_.contains("unknown constructor")), msgs.mkString(" | "))

  test("a locally declared case object is NOT reported"):
    val msgs = errorsOf(
      """case object Red
        |val x: Any = Red
        |x match
        |  case Red => println("red")
        |  case _ => println("other")""".stripMargin)
    assert(!msgs.exists(_.contains("unknown constructor")), msgs.mkString(" | "))
