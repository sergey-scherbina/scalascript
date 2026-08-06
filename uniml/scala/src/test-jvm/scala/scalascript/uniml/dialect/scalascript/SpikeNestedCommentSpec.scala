package scalascript.uniml.dialect.scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

/** A block comment NESTS, as it does in Scala.
  *
  * `40-front-on-uniml.md` section 5b item 7. A nested block comment used to stop at the FIRST
  * closing delimiter, leaving the remainder of the outer comment to be lexed as CODE — which
  * surfaced as `missing.right`, a complaint about an operator several tokens past the construct
  * that actually broke.
  *
  * This makes the dialect MORE PERMISSIVE than the reference front, deliberately. ssc1-front's
  * `skipBlockComment` does not nest either, and this lexer's comment used to say it was matching
  * that. v3 supports nesting because Scala does, so ssc1-front's gap is shared rather than
  * authoritative. The divergence runs in the only safe direction — strictly more input parses — so
  * no file that parses today can stop parsing, and the corpus counts are checked rather than
  * assumed.
  *
  * Losslessness is untouched: the comment is still ONE trivia token holding its text verbatim, and
  * only where that token ENDS has changed.
  *
  * (This docstring deliberately spells no comment delimiter literally. The first version did, the
  * inner one closed the docstring, and the file did not compile — the bug being fixed, committed
  * by the test that documents it.) */
final class SpikeNestedCommentSpec extends AnyFunSuite:

  private def parse(text: String) =
    UniML.parse(SourceInput.fromString(SourceId("memory:comment"), text), SpikeDialect)

  private def clean(text: String): Unit =
    val r = parse(text)
    assert(r.diagnostics.isEmpty, s"diagnostics: ${r.diagnostics.map(_.message).mkString("; ")}")

  test("a nested block comment is one comment") {
    clean("def f(): Int =\n  /* a /* b */ c */\n  1\n")
  }

  test("nesting several deep still closes exactly once") {
    clean("def f(): Int =\n  /* a /* b /* c */ d */ e */\n  1\n")
  }

  test("an ordinary block comment still works") {
    clean("def f(): Int =\n  /* plain */\n  1\n")
  }

  test("a comment containing an unmatched open is still terminated by the file") {
    // `/* a /* b */` — the inner open makes the outer one unterminated. It must consume to EOF
    // rather than close early; the point is that it does not crash or emit code tokens.
    val r = parse("def f(): Int =\n  1\n/* a /* b */\n")
    assert(r.roots.nonEmpty, "the file produced no tree")
  }

  test("the comment's TEXT survives verbatim — losslessness does not care where it ends") {
    val src = "def f(): Int =\n  /* a /* b */ c */\n  1\n"
    val toks = UniNode.sourceTokens(parse(src).roots.head)
    assert(toks.map(_.lexeme).mkString.contains("/* a /* b */ c */"),
           "the nested comment's text was not kept verbatim")
  }

  test("division is not a comment") {
    // The control. `a / b` and `a /* …` differ by one character, and a lexer that got greedy here
    // would silently swallow the rest of the file as a comment.
    clean("def f(a: Int, b: Int): Int =\n  a / b\n")
  }
