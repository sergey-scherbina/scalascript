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

  test("a DOTTED name refers, whatever the case of its first segment") {
    // `case scala.util.Failure(e) =>` is a stable identifier, not a binder — Scala binds only a
    // SIMPLE identifier, so once a `.` follows, the first segment's case stops mattering. The
    // dialect used to send a lowercase first segment down the binder path and then choke on the
    // dot; `examples/bank-rails-fednow.ssc` spent 10 diagnostics on exactly that.
    val lower = parse("def f(v: Int): Int =\n  v match\n    case scala.util.Failure(e) => 1")
    assert(lower.diagnostics.isEmpty, s"${lower.diagnostics.map(_.message)}")
    assert(lower.roots.flatMap(kindsOf).contains("spike.cpat"), "a dotted lowercase path must be a constructor pattern")

    val upper = parse("def f(v: Int): Int =\n  v match\n    case Status.Ok => 1")
    assert(upper.diagnostics.isEmpty, s"${upper.diagnostics.map(_.message)}")
    assert(upper.roots.flatMap(kindsOf).contains("spike.cpat"))

    // Control: WITHOUT the dot the same lowercase name still binds. If this flips, the change went
    // too far and every lowercase pattern became a reference.
    val bare = parse("def f(v: Int): Int =\n  v match\n    case scala => 1")
    assert(!bare.roots.flatMap(kindsOf).contains("spike.cpat"), "a simple lowercase identifier must still bind")
  }

  test("a non-ASCII capital matches, exactly as an ASCII one does — the table settled this") {
    // Sergiy's call, taken after the tableless answer was measured and found to diverge. `Число`
    // now behaves as `Foo` does, which is what a reader of the source would expect and what every
    // other Scala implementation does.
    val cyrillic = parse("def f(v: Int): Int =\n  v match\n    case Число => 1")
    assert(cyrillic.diagnostics.isEmpty, s"${cyrillic.diagnostics.map(_.message)}")
    val ascii = parse("def f(v: Int): Int =\n  v match\n    case Foo => 1")
    val lower = parse("def f(v: Int): Int =\n  v match\n    case число => 1")
    assert(
      cyrillic.roots.flatMap(kindsOf).contains("spike.cpat"),
      "a Cyrillic CAPITAL must match, not bind — this is the behaviour the Unicode table was added for",
    )
    assert(
      ascii.roots.flatMap(kindsOf).contains("spike.cpat"),
      "control: the identical shape with an ASCII capital, so the test cannot pass for a reason unrelated to case",
    )
    assert(
      !lower.roots.flatMap(kindsOf).contains("spike.cpat"),
      "control in the other direction: a Cyrillic LOWERCASE must still bind, or the table is answering true for everything",
    )
  }
