package scalascript.uniml.dialect.scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

/** A `def`'s result type is OPTIONAL, as it is in Scala.
  *
  * The dialect used to demand `: T`, which made `def f(x: Int) = x + 1` — ordinary code — report
  * two diagnostics. Measured on the corpus at the time: 76 of the 172 diagnostics coming from
  * TAGGED fences, 44% of everything the language column reported.
  *
  * The floor in `SscBreadthSpec` would catch a large regression here, but it has headroom by
  * design. These parses pin the behaviour itself.
  */
final class SpikeInferredResultTypeSpec extends AnyFunSuite:
  private val src = SourceId("memory:inferred-result")

  private def parse(text: String): ParseResult =
    UniML.parse(SourceInput.fromString(src, text), SpikeDialect)

  private def clean(text: String): Unit =
    val r = parse(text)
    assert(r.diagnostics.isEmpty, s"expected a clean parse of:\n$text\ngot ${r.diagnostics.map(_.message)}")

  test("a def with no result type parses, in every body shape the dialect distinguishes") {
    clean("def f(x: Int) = x + 1")                        // same-line expression body
    clean("def f() = 1")                                  // empty parameter list
    clean("def f =\n  1")                                 // parameterless, indented body
    clean("def f(x: Int) =\n  val y = x + 1\n  y")        // indented block body
    clean("def f(x: Int) = if x > 0 then 1 else 2")       // branch body
  }

  test("an annotated def still parses — this made an annotation optional, it did not remove it") {
    clean("def f(x: Int): Int = x + 1")
    clean("def f(): Unit =\n  println(1)")
    clean("def f: Int = 1")
    // the function-type return and the effect row, which sit on the same path as the annotation
    clean("def f(x: Int): Int => Int = y => y + x")
  }

  test("the two forms produce the SAME shape apart from the annotation") {
    // Without this, "it parses" could mean the parser recovered into something unusable. The
    // annotated parse carries `def.retColon`/`def.retType` and the inferred one carries neither;
    // everything else — the name, the parameter, the body — must match.
    def roles(n: UniNode): Vector[String] = n match
      case b: UniNode.Branch => b.edges.flatMap(e => e.role.toVector ++ roles(e.child))
      case _                 => Vector.empty
    // Trivia is excluded: the two sources are different TEXT — the annotated one has a space the
    // other does not — so a whitespace token differs for a reason that has nothing to do with the
    // structure being compared. Keeping it in made this fail on ` ` and say nothing.
    val annotated = parse("def f(x: Int): Int = x + 1").roots.flatMap(roles).filterNot(_ == "trivia")
    val inferred = parse("def f(x: Int) = x + 1").roots.flatMap(roles).filterNot(_ == "trivia")
    assert(annotated.contains("def.retColon") && annotated.contains("def.retType"))
    assert(!inferred.contains("def.retColon") && !inferred.contains("def.retType"))
    assert(inferred == annotated.filterNot(r => r == "def.retColon" || r == "def.retType"),
      s"inferred shape diverges beyond the missing annotation:\n  annotated $annotated\n  inferred  $inferred")
  }
