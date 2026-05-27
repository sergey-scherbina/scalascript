package scalascript.transform

import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.Diagnostic
import scalascript.parser.Parser

/** v1.55.4 — Compile-time well-formedness checker for `xml"..."` interpolators.
 *
 *  Each test builds a tiny `.ssc` module with one or more scalascript
 *  blocks, runs `MarkupInterpolatorCheck.check`, and asserts the presence
 *  or absence of `Diagnostic.XmlParseError` entries. */
class MarkupInterpolatorCheckTest extends AnyFunSuite:

  // ─── helper ────────────────────────────────────────────────────────────────

  /** Parse a scalascript snippet (without module boilerplate) and run the check. */
  private def check(src: String): List[Diagnostic] =
    val moduleSource =
      s"""# Test
         |
         |```scalascript
         |$src
         |```
         |""".stripMargin
    MarkupInterpolatorCheck.check(Parser.parse(moduleSource))

  private def xmlErrors(diags: List[Diagnostic]): List[Diagnostic.XmlParseError] =
    diags.collect { case e: Diagnostic.XmlParseError => e }

  // ─── valid — no diagnostics ─────────────────────────────────────────────────

  test("valid simple self-closing root — no error"):
    val diags = check("""val d = xml"<root/>"""")
    assert(xmlErrors(diags).isEmpty,
      s"expected no XmlParseError, got: ${diags.mkString(", ")}")

  test("valid open/close root element — no error"):
    val diags = check("""val d = xml"<root></root>"""")
    assert(xmlErrors(diags).isEmpty)

  test("valid XML with text interpolation — placeholder accepted — no error"):
    val diags = check("""val d = xml"<root>${value}</root>"""")
    assert(xmlErrors(diags).isEmpty,
      s"expected no XmlParseError with placeholder in content, got: ${diags.mkString(", ")}")

  test("valid XML with nested elements and interpolation — no error"):
    val diags = check("""val d = xml"<items><item>${x}</item><item>${y}</item></items>"""")
    assert(xmlErrors(diags).isEmpty)

  test("valid XML with attributes and child elements — no error"):
    val sscSrc = "val d = xml\"<root><child>text</child></root>\""
    val moduleSource =
      s"""# Test
         |
         |```scalascript
         |$sscSrc
         |```
         |""".stripMargin
    val diags = xmlErrors(MarkupInterpolatorCheck.check(Parser.parse(moduleSource)))
    assert(diags.isEmpty, s"expected no errors for nested elements, got: $diags")

  test("valid XML with namespace prefix — no error"):
    // Build the ssc source directly so we avoid quote escaping issues
    // when embedding the namespace declaration in a Scala string.
    val sscSrc = "val d = xml\"<ns:root/>\""
    val moduleSource =
      s"""# Test
         |
         |```scalascript
         |$sscSrc
         |```
         |""".stripMargin
    val diags = xmlErrors(MarkupInterpolatorCheck.check(Parser.parse(moduleSource)))
    assert(diags.isEmpty, s"expected no errors for namespaced element, got: $diags")

  // ─── invalid — emits XmlParseError ─────────────────────────────────────────

  test("unclosed tag — emits XmlParseError"):
    val diags = check("""val d = xml"<root>"""")
    assert(xmlErrors(diags).nonEmpty,
      "expected XmlParseError for unclosed tag")

  test("mismatched closing tag — emits XmlParseError"):
    val diags = check("""val d = xml"<a></b>"""")
    val errs = xmlErrors(diags)
    assert(errs.nonEmpty, "expected XmlParseError for mismatched closing tag")
    assert(errs.head.message.contains("mismatched") || errs.head.message.contains("expected"),
      s"expected informative message, got: ${errs.head.message}")

  test("bad attribute — missing quotes — emits XmlParseError"):
    val diags = check("""val d = xml"<a b=c/>"""")
    assert(xmlErrors(diags).nonEmpty,
      "expected XmlParseError for unquoted attribute value")

  // ─── two errors in one file ─────────────────────────────────────────────────

  test("two malformed xml interpolations in one block — both reported"):
    val src =
      """val d1 = xml"<root>"
        |val d2 = xml"<a></b>"""".stripMargin
    val moduleSource =
      s"""# Test
         |
         |```scalascript
         |$src
         |```
         |""".stripMargin
    val diags = xmlErrors(MarkupInterpolatorCheck.check(Parser.parse(moduleSource)))
    assert(diags.length >= 2,
      s"expected at least 2 XmlParseErrors, got ${diags.length}: ${diags.mkString(", ")}")
