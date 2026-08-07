package scalascript.uniml.ssc

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*
import scalascript.uniml.dialect.scalascript.SpikeDialect
import scalascript.uniml.dialect.json.JsonDialect

/** A fence body is spliced back in FULL, however many roots its dialect produced.
  *
  * `inject` used to splice `roots.headOption`. A parse yields one root per top-level construct, so
  * a body that is not a single construct has several, and every root after the first was silently
  * dropped. It survived 1,236 of 1,238 corpus files because a body containing a `def` is ONE root
  * — a definition's tree absorbs the trailing trivia — and that is the only shape most fences have.
  *
  * The two it did break looked like unrelated defects: a JSON fence lost ONE character (its
  * trailing newline, two roots) and a ScalaScript fence of only comments lost SIXTY-SEVEN (four
  * roots). They were the same line.
  */
final class SscMultiRootFenceSpec extends AnyFunSuite:

  private def text(n: UniNode): String = n match
    case b: UniNode.Branch => b.edges.map(e => text(e.child)).mkString
    case UniNode.Token(t)  => t.lexeme

  private def rootsOf(dia: DialectAdapter, body: String): Int =
    UniML.parse(SourceInput.fromString(SourceId("m:body"), body), dia).roots.size

  private def exact(src: String): Unit =
    val got = text(SscCompose.parse(src).root)
    assert(got == src,
      s"composed round-trip differs\n  src ${src.replace("\n", "\\n")}\n  got ${got.replace("\n", "\\n")}")

  test("the bodies that motivated this really are multi-root — the premise, asserted") {
    // If a dialect ever collapses these to one root the tests below stop testing anything, so the
    // premise is checked rather than described.
    assert(rootsOf(SpikeDialect, "// a\n") > 1)
    assert(rootsOf(SpikeDialect, "// a\n//   b\n") > 1)
    assert(rootsOf(JsonDialect, "{ \"a\": 1 }\n") > 1)
    assert(rootsOf(SpikeDialect, "def f(): Int = 1\n") == 1) // the shape that always worked
  }

  test("a ScalaScript fence of only comments keeps every line") {
    exact("```scalascript\n// a\n```\n")
    exact("```scalascript\n// a\n//   b\n```\n")
    exact("```scalascript\n// a\n//   b\n//     c\n```\n")
  }

  test("a JSON fence keeps the newline before its closing marker") {
    exact("```json\n{ \"a\": 1 }\n```\n")
    exact("```json\n{ \"a\": 1,\n  \"b\": 2 }\n```\n")
  }

  test("front matter is injected the same way and has the same shape") {
    exact("---\nname: x\n---\n\n# h\n\n```scalascript\ndef f(): Int = 1\n```\n")
  }

  test("the single-root shapes that always worked still do") {
    exact("```scalascript\ndef f(): Int = 1\n```\n")
    exact("```scalascript\ndef f(): Int = 1\n// tail\n```\n")
    exact("```text\nnot a registered dialect, stays inert\n```\n")
  }
