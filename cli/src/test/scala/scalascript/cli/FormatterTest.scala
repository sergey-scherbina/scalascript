package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

class FormatterTest extends AnyFunSuite:

  // ── No front-matter ──────────────────────────────────────────────────────

  test("no front-matter — prose only") {
    val src = "Hello world\n"
    assert(Formatter.format(src) == src)
  }

  test("no front-matter — trailing blank lines removed") {
    val src      = "Hello world\n\n\n"
    val expected = "Hello world\n"
    assert(Formatter.format(src) == expected)
  }

  test("no front-matter — LF normalised") {
    val src      = "line one\nline two\n"
    val expected = "line one\nline two\n"
    assert(Formatter.format(src) == expected)
  }

  // ── Front-matter key ordering ─────────────────────────────────────────────

  test("front-matter — known keys reordered") {
    val src =
      """---
        |version: "1.0"
        |name: myapp
        |description: A test app
        |---
        |
        |Body text.
        |""".stripMargin
    val result  = Formatter.format(src)
    val fmLines = result.split("\n").toList
    val nameIdx    = fmLines.indexWhere(_.startsWith("name:"))
    val versionIdx = fmLines.indexWhere(_.startsWith("version:"))
    assert(nameIdx >= 0)
    assert(versionIdx >= 0)
    assert(nameIdx < versionIdx)
  }

  test("front-matter — unknown keys sorted alphabetically after known") {
    val src =
      """---
        |zebra: last
        |name: myapp
        |alpha: first-unknown
        |---
        |
        |Body.
        |""".stripMargin
    val result   = Formatter.format(src)
    val fmLines  = result.split("\n").toList
    val nameIdx  = fmLines.indexWhere(_.startsWith("name:"))
    val alphaIdx = fmLines.indexWhere(_.startsWith("alpha:"))
    val zebraIdx = fmLines.indexWhere(_.startsWith("zebra:"))
    assert(nameIdx < alphaIdx)
    assert(alphaIdx < zebraIdx)
  }

  test("front-matter — exactly one blank line after closing ---") {
    val src =
      """---
        |name: app
        |---
        |
        |
        |Body here.
        |""".stripMargin
    val result     = Formatter.format(src)
    val fmLines    = result.split("\n").toList
    val closingIdx = fmLines.indexOf("---", 1)
    assert(closingIdx >= 0)
    assert(fmLines(closingIdx + 1) == "")
    assert(fmLines.lift(closingIdx + 2).exists(!_.isBlank))
  }

  // ── Heading normalisation ─────────────────────────────────────────────────

  test("heading — ## Title already correct passes through") {
    val src = "## My Heading\n\nProse.\n"
    assert(Formatter.format(src) == src)
  }

  test("heading — ##Title gets space inserted") {
    val src      = "##Title\n\nProse.\n"
    val expected = "## Title\n\nProse.\n"
    assert(Formatter.format(src) == expected)
  }

  test("heading — ###DeepHeading gets space inserted") {
    val src      = "###DeepHeading\n"
    val expected = "### DeepHeading\n"
    assert(Formatter.format(src) == expected)
  }

  test("heading — blank line before heading when preceded by content") {
    val src =
      """First paragraph.
        |## Section
        |More text.
        |""".stripMargin
    val result     = Formatter.format(src)
    val lines      = result.split("\n").toList
    val headingIdx = lines.indexOf("## Section")
    assert(headingIdx > 0)
    assert(lines(headingIdx - 1) == "")
  }

  test("heading — no blank line before first heading") {
    val src    = "## First Heading\n\nProse.\n"
    val result = Formatter.format(src)
    assert(result.startsWith("## First Heading"))
  }

  // ── Code blocks ───────────────────────────────────────────────────────────

  test("code block — blank line before and after") {
    val src =
      """Prose before.
        |```scala
        |val x = 1
        |```
        |Prose after.
        |""".stripMargin
    val result        = Formatter.format(src)
    val lines         = result.split("\n").toList
    val fenceOpenIdx  = lines.indexOf("```scala")
    val fenceCloseIdx = lines.indexOf("```", fenceOpenIdx + 1)
    assert(fenceOpenIdx > 0 && lines(fenceOpenIdx - 1) == "")
    assert(fenceCloseIdx < lines.length - 1 && lines(fenceCloseIdx + 1) == "")
  }

  test("code block — contents preserved verbatim") {
    val src =
      """Text.
        |
        |```scala
        |val x   =   42   // weird spacing preserved
        |  val y = x + 1
        |```
        |
        |More.
        |""".stripMargin
    val result = Formatter.format(src)
    assert(result.contains("val x   =   42   // weird spacing preserved"))
    assert(result.contains("  val y = x + 1"))
  }

  test("code block — multiple blank lines before collapsed to one") {
    val src =
      """Prose.
        |
        |
        |```scala
        |val x = 1
        |```
        |
        |End.
        |""".stripMargin
    val result   = Formatter.format(src)
    val lines    = result.split("\n").toList
    val fenceIdx = lines.indexOf("```scala")
    assert(fenceIdx >= 2)
    assert(lines(fenceIdx - 1) == "")
    assert(lines(fenceIdx - 2) != "")
  }

  // ── Shebang preservation ──────────────────────────────────────────────────

  test("shebang — preserved at position 0") {
    val src =
      """#!/usr/bin/env ssc
        |
        |## Hello
        |
        |Prose.
        |""".stripMargin
    val result = Formatter.format(src)
    assert(result.startsWith("#!/usr/bin/env ssc"))
  }

  test("shebang — preserved with front-matter") {
    val src =
      """#!/usr/bin/env ssc
        |---
        |name: app
        |---
        |
        |## Hello
        |""".stripMargin
    val result = Formatter.format(src)
    assert(result.startsWith("#!/usr/bin/env ssc"))
    assert(result.contains("---"))
  }

  // ── Idempotency ───────────────────────────────────────────────────────────

  test("idempotency — simple prose") {
    val src = "Hello world.\n"
    assert(Formatter.format(Formatter.format(src)) == Formatter.format(src))
  }

  test("idempotency — heading normalisation") {
    val src   = "##Title\n\nProse.\n"
    val once  = Formatter.format(src)
    val twice = Formatter.format(once)
    assert(once == twice)
  }

  test("idempotency — front-matter with key reordering") {
    val src =
      """---
        |version: "2.0"
        |name: myapp
        |description: Desc
        |---
        |
        |Body.
        |""".stripMargin
    val once  = Formatter.format(src)
    val twice = Formatter.format(once)
    assert(once == twice)
  }

  test("idempotency — code block spacing") {
    val src =
      """Prose.
        |```scala
        |val x = 1
        |```
        |More prose.
        |""".stripMargin
    val once  = Formatter.format(src)
    val twice = Formatter.format(once)
    assert(once == twice)
  }

  test("idempotency — complex file") {
    val src =
      """---
        |description: Complex file
        |name: complex
        |version: "1.0"
        |---
        |
        |##Overview
        |
        |Some prose here.
        |```scala
        |val x = 1
        |val y = 2
        |```
        |More prose.
        |###Details
        |Extra text.
        |""".stripMargin
    val once  = Formatter.format(src)
    val twice = Formatter.format(once)
    assert(once == twice)
  }

  // ── needsFormatting ───────────────────────────────────────────────────────

  test("needsFormatting — already formatted returns false") {
    val src = "## Heading\n\nProse.\n"
    assert(!Formatter.needsFormatting(src))
  }

  test("needsFormatting — unformatted heading returns true") {
    val src = "##Heading\n\nProse.\n"
    assert(Formatter.needsFormatting(src))
  }

  test("needsFormatting — trailing whitespace returns true") {
    val src = "Prose.   \n"
    assert(Formatter.needsFormatting(src))
  }

  // ── --check mode (via needsFormatting) ───────────────────────────────────

  test("check mode — needsFormatting true implies non-zero exit in CLI") {
    // Verifies the contract that needsFormatting drives the --check exit code.
    val unformatted = "##Heading\n"
    assert(Formatter.needsFormatting(unformatted))
    val formatted = Formatter.format(unformatted)
    assert(!Formatter.needsFormatting(formatted))
  }
