package scalascript.uniml.dialect.markdown

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

/** A container's continuation prefix must land where the source put it, even when a code span
  * SWALLOWS the line break it follows.
  *
  * `matchContainers` strips a list item's continuation indent into `paragraphPendingPrefix`, and
  * `emitParagraphWithSegments` puts it back after the k-th BREAK PIECE — "the k-th break ends
  * segment k, so segment k+1's prefix follows". When a code span crosses the line break there IS no
  * break piece: the newline sits inside the span's single content lexeme. The prefix then had no
  * position between pieces, was never consumed, and `finishParagraph` flushed it AFTER the block.
  *
  * The failure is ORDER, not loss — every character is present, so a length check passes and only
  * comparing the reconstructed string catches it. That is why every assertion here is on the exact
  * string.
  *
  * The isolation is worth keeping: each half was measured GREEN on its own before the combination
  * reproduced it, so three hypotheses tested clean before the fourth found the bug. A construct
  * that behaves alone says nothing about the construct it is nested in.
  */
final class MarkdownContinuationPrefixSpec extends AnyFunSuite:
  private val source = SourceId("memory:continuation.md")

  private def lossless(text: String): Unit =
    val result = Markdown.parse(SourceInput.fromString(source, text), MarkdownProfile.CommonMark)
    val tokens = result.roots.flatMap(UniNode.sourceTokens)
    val got = tokens.map(_.lexeme).mkString
    assert(got == text,
      s"round-trip differs\n  src ${text.replace("\n", "\\n")}\n  got ${got.replace("\n", "\\n")}")
    assert(tokens.map(_.id) == tokens.indices.map(_.toLong).toVector,
      s"non-monotonic ids for ${text.replace("\n", "\\n")}")

  test("each half on its own — these were already green and must stay so") {
    lossless("1. one\n   two\n")            // list item with a plain continuation
    lossless("`x +\n   y`\n")               // code span crossing a line break, no container
    lossless("text `a\n b` more\n")         // code span inside a paragraph, no container
  }

  test("the combination: a code span crossing the break INSIDE a list item") {
    lossless("2. a (`x +\n   y`) b\n")      // the reported reproduction
    lossless("1. `a\n   b`\n")              // span is the whole item body
    lossless("- x `a\n  b` y\n")            // bullet item, two-space continuation
  }

  test("more than one swallowed break, and more than one span") {
    lossless("1. `a\n   b\n   c`\n")        // one span swallowing TWO breaks
    lossless("1. `a\n   b` and `c\n   d`\n")// two spans, one break each
    lossless("1. one\n   `a\n   b`\n")      // a normal break BEFORE a swallowed one
  }

  test("nesting deeper: the prefix is the container's, not the item's") {
    lossless("- - x `a\n    b` y\n")        // nested bullets, four-space continuation
    lossless("> 1. `a\n>    b`\n")          // blockquote + list
  }

  test("a swallowed break with NO continuation prefix must not gain one") {
    // The control. The splice is driven by the segment table, so a paragraph outside any
    // container — where every prefix is empty — must come back byte-identical. If this ever fails,
    // the fix is inventing indentation rather than restoring it.
    lossless("`a\nb`\n")
    lossless("x `a\nb` y\n")
    lossless("para `a\nb` more\nsecond line\n")
  }
