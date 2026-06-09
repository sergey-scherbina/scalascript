package scalascript.parser

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ast.*

/** v2.0 — structured parse-error diagnostics with line/col/snippet.
 *
 *  Until this milestone, a scalameta-rejected code block surfaced only as
 *  `Content.CodeBlock(tree = None)` — the CLI emitted the opaque single-line
 *  message "Failed to parse scalascript code block" with no positional info.
 *  These tests pin the new behaviour: `Content.CodeBlock.parseError` is
 *  populated with a `CodeBlockParseError(message, line, column, snippet)`
 *  whose line/column point at the failing token and whose snippet shows the
 *  failing line ± one line of context with a `^` caret marker.
 *
 *  Test design:
 *    - Each test parses a tiny `.ssc` module with a fenced `scalascript`
 *      block that contains a deliberate syntax error.
 *    - We then walk the parsed module to grab the single `CodeBlock` and
 *      assert on its `parseError`.
 *    - We do NOT pin the exact column (scalameta's `pos.startColumn` may
 *      shift across minor versions); we DO pin the line and a substring of
 *      the snippet that proves context was captured.
 */
class ParseErrorPositionTest extends AnyFunSuite:

  /** Helper: parse the given `.ssc` source and return the first code block
   *  found by depth-first walk.  All test fixtures here have exactly one. */
  private def firstCodeBlock(src: String): Content.CodeBlock =
    val module = Parser.parse(src)
    def walk(sections: List[Section]): Option[Content.CodeBlock] =
      sections.iterator.flatMap { s =>
        s.content.iterator.collectFirst { case cb: Content.CodeBlock => cb } match
          case Some(cb) => Iterator.single(cb)
          case None     => walk(s.subsections).iterator
      }.nextOption()
    walk(module.sections).getOrElse(
      fail(s"no Content.CodeBlock found in parsed module; source:\n$src")
    )

  test("tiny block, one-line syntax error → parseError on line 1, snippet shows the line"):
    val src =
      """# Bad
        |
        |```scalascript
        |def f(: Int = 1
        |```
        |""".stripMargin
    val cb = firstCodeBlock(src)
    assert(cb.tree.isEmpty, "expected scalameta to reject the block")
    assert(cb.parseError.isDefined,
      s"expected parseError to be populated; got cb=$cb")
    val pe = cb.parseError.get
    // Line 1 of the block body — not of the .ssc file.
    assert(pe.line == 1,
      s"expected line=1; got ${pe.line}")
    // Column should point somewhere on the failing line; scalameta reports
    // the position of the unexpected token (`:` here is at column 7 in
    // `def f(: Int = 1`, 1-indexed) — we just assert it's a plausible
    // column rather than pinning the exact number.
    assert(pe.column >= 1 && pe.column <= "def f(: Int = 1".length + 1,
      s"expected column within the failing line; got ${pe.column}")
    // The snippet must include the failing line verbatim.
    assert(pe.snippet.contains("def f(: Int = 1"),
      s"snippet must include the failing line; got:\n${pe.snippet}")
    // And a `^` caret on its own line.
    assert(pe.snippet.linesIterator.exists(_.trim == "^"),
      s"snippet must contain a `^` caret line; got:\n${pe.snippet}")
    // Message must be non-empty.
    assert(pe.message.nonEmpty,
      s"message must be non-empty; got '${pe.message}'")

  test("multi-line block, error on line 3 → parseError.line == 3, snippet includes context"):
    // 5-line block; the `def g(` on line 3 is malformed.
    val src =
      """# Bad multi
        |
        |```scalascript
        |val a = 1
        |val b = 2
        |def g(: Int = 3
        |val c = 4
        |val d = 5
        |```
        |""".stripMargin
    val cb = firstCodeBlock(src)
    assert(cb.tree.isEmpty, "expected scalameta to reject the block")
    assert(cb.parseError.isDefined,
      s"expected parseError; got cb=$cb")
    val pe = cb.parseError.get
    assert(pe.line == 3,
      s"expected line=3 (the malformed def line); got ${pe.line}")
    // Snippet must include the failing line and at least one of its neighbours.
    assert(pe.snippet.contains("def g(: Int = 3"),
      s"snippet must include the failing line; got:\n${pe.snippet}")
    val hasContext = pe.snippet.contains("val b = 2") || pe.snippet.contains("val c = 4")
    assert(hasContext,
      s"snippet must include at least one context line (val b = 2 / val c = 4); got:\n${pe.snippet}")
    // Caret line present.
    assert(pe.snippet.linesIterator.exists(_.trim == "^"),
      s"snippet must contain a `^` caret line; got:\n${pe.snippet}")

  test("syntactically valid block → parseError is None"):
    val src =
      """# Good
        |
        |```scalascript
        |val x = 1
        |def f(a: Int) = a + 1
        |```
        |""".stripMargin
    val cb = firstCodeBlock(src)
    assert(cb.tree.isDefined,
      s"expected scalameta success for a valid block; tree=${cb.tree}")
    assert(cb.parseError.isEmpty,
      s"valid block must have parseError = None; got ${cb.parseError}")

  test("non-parseable lang (html) → parseError is None even with bogus content"):
    // Sanity: parseError is only populated for blocks the parser even tries
    // to parse.  An HTML block doesn't get fed to scalameta.
    val src =
      """# HTML
        |
        |```html
        |<p>this is not Scala but that's fine
        |```
        |""".stripMargin
    val cb = firstCodeBlock(src)
    assert(cb.lang == "html")
    assert(cb.tree.isEmpty)
    assert(cb.parseError.isEmpty,
      s"non-parseable lang must not carry a parseError; got ${cb.parseError}")

  // ui-bug-jobj-failloud: scalameta throws a raw NullPointerException (from its
  // `termParam` token handling) on certain truncated inputs like `def f(` /
  // `def f(using ` — previously this escaped parseScalaWithDiagnostic and
  // crashed/hung the whole pipeline. The parser must now fail loudly: a
  // populated `parseError`, never a thrown exception.
  for frag <- List("def f(", "def f(using ", "val g = (a: Int,", "class C(a: ") do
    test(s"truncated input '$frag' yields a diagnostic, not a crash"):
      val src = s"# Bad\n\n```scalascript\n$frag\n```\n"
      // The key assertion is simply that this does not throw.
      val cb = firstCodeBlock(src)
      assert(cb.tree.isEmpty, s"expected scalameta to reject '$frag'")
      assert(cb.parseError.isDefined,
        s"expected a populated parseError for '$frag'; got cb=$cb")
      assert(cb.parseError.get.message.nonEmpty,
        s"diagnostic message must be non-empty for '$frag'")
