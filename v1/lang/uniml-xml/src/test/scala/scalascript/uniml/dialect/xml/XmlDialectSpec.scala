package scalascript.uniml.dialect.xml

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

final class XmlDialectSpec extends AnyFunSuite:
  private val source = SourceId("memory:test.xml")

  test("preserves declarations, doctype, tags, attributes, and mixed content") {
    val text = "<?xml version=\"1.0\"?>\n<!DOCTYPE r [<!ELEMENT r ANY>]>\n<r a='1'>x&amp;<![CDATA[<y>]]><!--c--><?p d?><b/></r>"
    val result = parse(text)
    assert(result.status == CompletionStatus.Complete, result.diagnostics.toString)
    val tokens = allTokens(result)
    assert(tokens.map(_.lexeme).mkString == text)
    assert(tokens.map(_.id) == tokens.indices.map(_.toLong).toVector)
    assert(tokens.exists(_.kind == "xml.declaration"))
    assert(tokens.exists(_.kind == "xml.doctype"))
    assert(tokens.exists(_.kind == "xml.cdata"))
    assert(result.roots.exists { case UniNode.Branch("xml.element", _, _, _) => true; case _ => false })
  }

  test("is identical at every chunk boundary including a surrogate split") {
    val text = "<r a=\"😀\">before&amp;after<b/>tail</r>"
    val baseline = parse(text)
    assert(baseline.status == CompletionStatus.Complete)
    (0 to text.length).foreach { split =>
      val chunked = Xml.parse(SourceInput(source, Vector(
        SourceChunk(text.substring(0, split)),
        SourceChunk(text.substring(split)),
      )))
      assert(chunked == baseline, s"split $split changed XML result")
    }
  }

  test("rejects mismatched tags, duplicate attributes, multiple roots, and outside text") {
    val cases = Vector(
      "<a></b>" -> "uniml.xml.mismatched-end-tag",
      "<a x='1' x='2'/>" -> "uniml.xml.duplicate-attribute",
      "<a/><b/>" -> "uniml.xml.multiple-roots",
      "text<a/>" -> "uniml.xml.text-outside-root",
      "<a>" -> "uniml.xml.unexpected-eof",
    )
    cases.foreach { case (text, code) =>
      val result = parse(text)
      assert(result.status != CompletionStatus.Complete, text)
      assert(result.diagnostics.exists(_.code == code), s"$text: ${result.diagnostics.map(_.code)}")
    }
  }

  test("rejects malformed comments, declarations, references, and attributes without hanging") {
    val cases = Vector(
      "<!--a--b--><r/>" -> "uniml.xml.invalid-comment",
      " <?xml version=\"1.0\"?><r/>" -> "uniml.xml.declaration-position",
      "<r>&bad</r>" -> "uniml.xml.invalid-reference",
      "<r>&#0;</r>" -> "uniml.xml.invalid-reference",
      "<![CDATA[x]]><r/>" -> "uniml.xml.invalid-cdata",
      "<r>\u0000</r>" -> "uniml.xml.invalid-character",
      "<r a=x/>" -> "uniml.xml.expected-attribute-value",
    )
    cases.foreach { case (text, code) =>
      val result = parse(text)
      assert(result.status != CompletionStatus.Complete)
      assert(result.diagnostics.exists(_.code == code), s"$text: ${result.diagnostics.map(_.code)}")
    }
  }

  test("enforces source and core depth limits") {
    val sourceLimited = Xml.parse(SourceInput.fromString(source, "<root/>"), XmlLimits(maxSourceCodePoints = 3))
    assert(sourceLimited.status == CompletionStatus.Halted)
    assert(sourceLimited.diagnostics.exists(_.code == "uniml.xml.limit.source"))

    val depthLimited = Xml.parse(
      SourceInput.fromString(source, "<a><b><c/></b></a>"),
      XmlLimits(core = Limits(maxDepth = 2)),
    )
    assert(depthLimited.status == CompletionStatus.Halted)
    assert(depthLimited.diagnostics.exists(_.code == "uniml.limit.depth"))

    val nameLimited = Xml.parse(
      SourceInput.fromString(source, "<abcd/>"),
      XmlLimits(maxNameCodePoints = 3),
    )
    assert(nameLimited.status == CompletionStatus.Halted)
    assert(nameLimited.diagnostics.exists(_.code == "uniml.xml.limit.name"))
  }

  test("implements XML 1.0 Fifth Edition name code-point ranges") {
    assert(parse("<𐀀/>").status == CompletionStatus.Complete)
    val excluded = parse("<a×b/>")
    assert(excluded.status == CompletionStatus.Incomplete)
    assert(excluded.diagnostics.exists(_.code == "uniml.xml.invalid-name"))
  }

  test("processor flushes once") {
    val input = SourceInput.fromString(source, "<r/>")
    val processor = XmlDialect.instructions(input)
    assert(processor.push(input.chunks.head).values.isEmpty)
    assert(processor.finish().values.nonEmpty)
    assert(processor.finish().diagnostics.map(_.code) == Vector("uniml.xml.finished"))
  }

  private def parse(text: String): ParseResult = Xml.parse(SourceInput.fromString(source, text))
  private def allTokens(result: ParseResult): Vector[SourceToken] = result.roots.flatMap(UniNode.sourceTokens).sortBy(_.id)
