package scalascript.uniml.dialect.scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

/** Where capitalisation matters and where it does not.
  *
  * Scala imposes NO capitalisation on declarations: `class foo`, `object bar`, `type baz` are all
  * legal. The dialect used to demand an uppercase name for classes, objects, enums, enum cases and
  * type aliases — stricter than the language it models — and that requirement is gone.
  *
  * Case is load-bearing in exactly ONE position: a pattern. There, a SIMPLE identifier starting
  * lowercase binds a new variable, and anything else refers to something that exists. `case Red =>`
  * compares; `case red =>` matches everything. Nothing else in the grammar reads the case of a
  * letter, which is why the whole non-ASCII case question is confined to these three tests.
  */
final class SpikeCapitalisationSpec extends AnyFunSuite:
  private val src = SourceId("memory:capitalisation")

  private def parse(text: String): ParseResult =
    UniML.parse(SourceInput.fromString(src, text), SpikeDialect)

  private def kindsOf(n: UniNode): Vector[String] = n match
    case b: UniNode.Branch => Vector(b.kind) ++ b.edges.flatMap(e => kindsOf(e.child))
    case _                 => Vector.empty

  test("a lowercase declaration name is accepted — Scala has no such requirement") {
    // Each of these was a diagnostic before the requirement was dropped. If this ever goes red by
    // reporting again, the dialect has re-acquired a rule the language does not have.
    val sources = Vector(
      "case class foo(x: Int)",
      "object bar:\n  def f(): Int = 1",
      "enum baz:\n  case one\n  case two",
      "type qux = Int",
    )
    sources.foreach { text =>
      val result = parse(text)
      assert(result.diagnostics.isEmpty, s"expected no diagnostics for: $text — got ${result.diagnostics.map(_.message)}")
    }
  }

  test("an uppercase declaration name still works — this loosened a rule, it did not invert one") {
    val result = parse("case class Foo(x: Int)\nobject Bar:\n  def f(): Int = 1")
    assert(result.diagnostics.isEmpty, s"${result.diagnostics.map(_.message)}")
  }

  test("in a pattern, case decides between binding and matching — the one place it is load-bearing") {
    // Applied to arguments: an extractor whatever the case. Reachable only now that `class foo` is
    // legal, and unparseable before this went in.
    val extractor = parse("def f(v: Int): Int =\n  v match\n    case foo(x) => x")
    assert(extractor.diagnostics.isEmpty, s"${extractor.diagnostics.map(_.message)}")
    assert(extractor.roots.flatMap(kindsOf).contains("spike.cpat"), "a lowercase name applied to arguments must be a constructor pattern")

    // A simple lowercase identifier still BINDS — it does not become a reference to `foo`.
    val binder = parse("def f(v: Int): Int =\n  v match\n    case foo => 1")
    assert(binder.diagnostics.isEmpty, s"${binder.diagnostics.map(_.message)}")
    // The observable difference is the constructor-pattern FRAME: a binder is a bare leaf, a
    // reference builds `spike.cpat`. The leaf's own role is overwritten by the enclosing `case.pat`,
    // so asserting on it would be asserting on something the tree does not keep.
    assert(!binder.roots.flatMap(kindsOf).contains("spike.cpat"), "a simple lowercase identifier must bind, not match")

    // A simple uppercase identifier is the opposite: a reference, not a binder.
    val matcher = parse("def f(v: Int): Int =\n  v match\n    case Foo => 1")
    assert(matcher.diagnostics.isEmpty, s"${matcher.diagnostics.map(_.message)}")
    assert(matcher.roots.flatMap(kindsOf).contains("spike.cpat"), "a simple uppercase identifier must match, not bind")
  }

  test("the non-ASCII case question, stated as a test rather than left in a comment") {
    // `Число` starts with a character the tableless alphabet cannot call uppercase, so it binds.
    // This test does not assert that this is RIGHT — it pins the current answer so that changing
    // it is a deliberate act with a visible diff, and so the behaviour is discoverable without
    // reading the alphabet's source.
    val cyrillic = parse("def f(v: Int): Int =\n  v match\n    case Число => 1")
    assert(cyrillic.diagnostics.isEmpty, s"${cyrillic.diagnostics.map(_.message)}")
    val ascii = parse("def f(v: Int): Int =\n  v match\n    case Foo => 1")
    assert(
      !cyrillic.roots.flatMap(kindsOf).contains("spike.cpat"),
      "with an ASCII-only case test a Cyrillic name BINDS — if this flips, the language decision changed and specs/uniml-ssc3-frontend.md needs updating with it",
    )
    assert(
      ascii.roots.flatMap(kindsOf).contains("spike.cpat"),
      "control: the identical shape with an ASCII capital MUST match, or this test proves nothing about case",
    )
  }
