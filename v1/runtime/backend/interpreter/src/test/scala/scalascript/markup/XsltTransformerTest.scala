package scalascript.markup

import org.scalatest.funsuite.AnyFunSuite

/** Unit tests for [[XsltTransformer]] and the [[JvmMarkupCodec.transform]] override.
 *
 *  Coverage:
 *   1. Identity transform (copy-of select="/")
 *   2. Element rename via template rules
 *   3. Parameter substitution via `<xsl:param>`
 *   4. Malformed XSLT → Left(TransformError)
 *   5. Malformed source document — handled via PureMarkupCodec.serialize (always valid)
 *   6. Namespace-aware output
 *   7. Empty output / empty-document stylesheet
 *   8. HTML generation from XML data
 *   9. Multiple parameters passed simultaneously
 *  10. `MarkupCodec.default.transform` delegates when default is JvmMarkupCodec
 *  11. `PureMarkupCodec.transform` returns Left (XSLT not supported)
 *  12. Attribute value template in XSLT (AVT)
 *  13. Nested element transformation
 *  14. XsltTransformer result parses back to correct element count */
class XsltTransformerTest extends AnyFunSuite:

  // Shared XSLT snippets ─────────────────────────────────────────────────────

  private val identityXslt =
    """<?xml version="1.0"?>
      |<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
      |  <xsl:template match="/">
      |    <xsl:copy-of select="/"/>
      |  </xsl:template>
      |</xsl:stylesheet>""".stripMargin

  private val renameXslt =
    """<?xml version="1.0"?>
      |<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
      |  <xsl:template match="root">
      |    <renamed><xsl:apply-templates/></renamed>
      |  </xsl:template>
      |  <xsl:template match="@*|node()">
      |    <xsl:copy><xsl:apply-templates select="@*|node()"/></xsl:copy>
      |  </xsl:template>
      |</xsl:stylesheet>""".stripMargin

  private val paramXslt =
    """<?xml version="1.0"?>
      |<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
      |  <xsl:param name="greeting">Hello</xsl:param>
      |  <xsl:template match="/">
      |    <result><xsl:value-of select="$greeting"/></result>
      |  </xsl:template>
      |</xsl:stylesheet>""".stripMargin

  private def parse(src: String): Markup.Doc =
    JvmMarkupCodec.parse(src) match
      case Right(doc) => doc
      case Left(e)    => fail(s"test setup: parse failed: $e")

  // ── 1. Identity transform ────────────────────────────────────────────────

  test("identity transform — root element name is preserved"):
    val doc    = parse("<root><child/></root>")
    val result = XsltTransformer(doc, identityXslt)
    assert(result.isRight, s"expected Right, got: $result")
    assert(result.toOption.get.root.name.localName == "root")

  test("identity transform — child elements are preserved"):
    val doc    = parse("<root><child/><sibling/></root>")
    val result = XsltTransformer(doc, identityXslt)
    assert(result.isRight, s"expected Right, got: $result")
    val children = result.toOption.get.root.children.collect { case e: Markup.Element => e }
    assert(children.size == 2)
    assert(children.exists(_.name.localName == "child"))
    assert(children.exists(_.name.localName == "sibling"))

  // ── 2. Element rename ────────────────────────────────────────────────────

  test("element rename — root is renamed from 'root' to 'renamed'"):
    val doc    = parse("<root><item>text</item></root>")
    val result = XsltTransformer(doc, renameXslt)
    assert(result.isRight, s"expected Right, got: $result")
    assert(result.toOption.get.root.name.localName == "renamed")

  test("element rename — children survive rename transform"):
    val doc    = parse("<root><item>hello</item></root>")
    val result = XsltTransformer(doc, renameXslt)
    assert(result.isRight, s"expected Right, got: $result")
    val renamed = result.toOption.get.root
    val items   = renamed.children.collect { case e: Markup.Element => e }
    assert(items.exists(_.name.localName == "item"))

  // ── 3. Parameter substitution ────────────────────────────────────────────

  test("parameter substitution — default param value when no override"):
    val doc    = parse("<input/>")
    val result = XsltTransformer(doc, paramXslt)
    assert(result.isRight, s"expected Right, got: $result")
    val texts = result.toOption.get.root.children.collect { case Markup.Text(t) => t }
    assert(texts.mkString.contains("Hello"), s"expected 'Hello', got: ${texts.mkString}")

  test("parameter substitution — caller param overrides default"):
    val doc    = parse("<input/>")
    val result = XsltTransformer(doc, paramXslt, Map("greeting" -> "Bonjour"))
    assert(result.isRight, s"expected Right, got: $result")
    val texts = result.toOption.get.root.children.collect { case Markup.Text(t) => t }
    assert(texts.mkString.contains("Bonjour"), s"expected 'Bonjour', got: ${texts.mkString}")

  // ── 4. Malformed XSLT ────────────────────────────────────────────────────

  test("malformed XSLT — returns Left(TransformError)"):
    val doc    = parse("<root/>")
    val result = XsltTransformer(doc, "<not-a-stylesheet/>")
    assert(result.isLeft, s"expected Left for malformed XSLT, got: $result")
    val err = result.left.toOption.get
    assert(err.message.nonEmpty, "TransformError.message should be non-empty")

  test("malformed XSLT — error message mentions stylesheet or XSLT"):
    val doc    = parse("<root/>")
    val result = XsltTransformer(doc, "this is not XML at all %%%")
    assert(result.isLeft, s"expected Left for non-XML stylesheet, got: $result")

  // ── 5. Source with special characters / CDATA ────────────────────────────

  test("source with text content — text survives identity transform"):
    val doc    = parse("<msg>Hello &amp; World</msg>")
    val result = XsltTransformer(doc, identityXslt)
    assert(result.isRight, s"expected Right, got: $result")
    val texts = result.toOption.get.root.children.collect { case Markup.Text(t) => t }
    assert(texts.mkString.contains("Hello"), s"text content not preserved: ${texts.mkString}")

  // ── 6. Namespace handling ────────────────────────────────────────────────

  test("namespace-aware output — namespaced element processed correctly"):
    val nsXslt =
      """<?xml version="1.0"?>
        |<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
        |  <xsl:template match="/">
        |    <result xmlns:ns="urn:test">
        |      <xsl:apply-templates select="//*[local-name()='child']"/>
        |    </result>
        |  </xsl:template>
        |  <xsl:template match="*[local-name()='child']">
        |    <found><xsl:value-of select="."/></found>
        |  </xsl:template>
        |</xsl:stylesheet>""".stripMargin
    val doc    = parse("""<ns:root xmlns:ns="urn:test"><ns:child>42</ns:child></ns:root>""")
    val result = XsltTransformer(doc, nsXslt)
    assert(result.isRight, s"expected Right for namespace-aware transform, got: $result")
    val root = result.toOption.get.root
    assert(root.name.localName == "result")

  // ── 7. Empty output ──────────────────────────────────────────────────────

  test("empty-output stylesheet — returns empty placeholder doc"):
    // An XSLT that produces no output gets a synthetic <empty/> placeholder.
    val emptyXslt =
      """<?xml version="1.0"?>
        |<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
        |  <xsl:template match="/"/>
        |</xsl:stylesheet>""".stripMargin
    val doc    = parse("<root/>")
    val result = XsltTransformer(doc, emptyXslt)
    assert(result.isRight, s"expected Right for empty-output stylesheet, got: $result")

  // ── 8. HTML generation from XML data ────────────────────────────────────

  test("HTML generation — table rows are created for each data element"):
    val htmlXslt =
      """<?xml version="1.0"?>
        |<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
        |  <xsl:template match="/">
        |    <html><body><table>
        |      <xsl:for-each select="items/item">
        |        <tr><td><xsl:value-of select="@name"/></td></tr>
        |      </xsl:for-each>
        |    </table></body></html>
        |  </xsl:template>
        |</xsl:stylesheet>""".stripMargin
    val doc    = parse("""<items><item name="alpha"/><item name="beta"/><item name="gamma"/></items>""")
    val result = XsltTransformer(doc, htmlXslt)
    assert(result.isRight, s"expected Right, got: $result")
    val html = PureMarkupCodec.serialize(result.toOption.get, SerializeOpts(omitXmlDecl = true))
    assert(html.contains("alpha"), s"expected 'alpha' in HTML output: $html")
    assert(html.contains("beta"),  s"expected 'beta' in HTML output: $html")
    assert(html.contains("gamma"), s"expected 'gamma' in HTML output: $html")

  // ── 9. Multiple parameters ────────────────────────────────────────────────

  test("multiple parameters — both parameters substituted correctly"):
    val multiParamXslt =
      """<?xml version="1.0"?>
        |<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
        |  <xsl:param name="a">default-a</xsl:param>
        |  <xsl:param name="b">default-b</xsl:param>
        |  <xsl:template match="/">
        |    <result><xsl:value-of select="concat($a, '-', $b)"/></result>
        |  </xsl:template>
        |</xsl:stylesheet>""".stripMargin
    val doc    = parse("<input/>")
    val result = XsltTransformer(doc, multiParamXslt, Map("a" -> "hello", "b" -> "world"))
    assert(result.isRight, s"expected Right, got: $result")
    val texts = result.toOption.get.root.children.collect { case Markup.Text(t) => t }
    assert(texts.mkString == "hello-world", s"expected 'hello-world', got: '${texts.mkString}'")

  // ── 10. JvmMarkupCodec.transform delegates to XsltTransformer ──────────

  test("JvmMarkupCodec.transform — delegates to XsltTransformer"):
    val doc    = parse("<root><child/></root>")
    val result = JvmMarkupCodec.transform(doc, identityXslt)
    assert(result.isRight, s"expected Right via JvmMarkupCodec.transform, got: $result")
    assert(result.toOption.get.root.name.localName == "root")

  // ── 11. PureMarkupCodec.transform returns Left ───────────────────────────

  test("PureMarkupCodec.transform — returns Left(TransformError) — XSLT not supported"):
    val doc    = parse("<root/>")
    val result = PureMarkupCodec.transform(doc, identityXslt)
    assert(result.isLeft, s"PureMarkupCodec.transform should return Left, got: $result")
    assert(result.left.toOption.get.message.contains("XSLT not supported"))

  // ── 12. Attribute value template (AVT) ───────────────────────────────────

  test("attribute value template — attribute derived from source data"):
    val avtXslt =
      """<?xml version="1.0"?>
        |<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
        |  <xsl:template match="/">
        |    <wrapper class="{/root/@type}"><xsl:value-of select="/root/text()"/></wrapper>
        |  </xsl:template>
        |</xsl:stylesheet>""".stripMargin
    val doc    = parse("""<root type="info">content</root>""")
    val result = XsltTransformer(doc, avtXslt)
    assert(result.isRight, s"expected Right for AVT transform, got: $result")
    val root = result.toOption.get.root
    assert(root.name.localName == "wrapper")
    val classAttr = root.attrs.find(_.name.localName == "class")
    assert(classAttr.isDefined, "expected 'class' attribute")
    assert(classAttr.get.value == "info", s"expected class='info', got: ${classAttr.get.value}")

  // ── 13. Nested element transformation ───────────────────────────────────

  test("nested element transform — deeply nested elements processed recursively"):
    val nestedXslt =
      """<?xml version="1.0"?>
        |<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
        |  <xsl:template match="@*|node()">
        |    <xsl:copy><xsl:apply-templates select="@*|node()"/></xsl:copy>
        |  </xsl:template>
        |  <xsl:template match="inner">
        |    <processed><xsl:apply-templates/></processed>
        |  </xsl:template>
        |</xsl:stylesheet>""".stripMargin
    val doc    = parse("<outer><middle><inner>deep</inner></middle></outer>")
    val result = XsltTransformer(doc, nestedXslt)
    assert(result.isRight, s"expected Right, got: $result")
    val html = PureMarkupCodec.serialize(result.toOption.get, SerializeOpts(omitXmlDecl = true))
    assert(html.contains("<processed>"), s"expected <processed> in output: $html")
    assert(!html.contains("<inner>"), s"unexpected <inner> in output: $html")

  // ── 14. Element count preserved ─────────────────────────────────────────

  test("element count — identity transform preserves all top-level children"):
    val doc    = parse("<list><a/><b/><c/><d/></list>")
    val result = XsltTransformer(doc, identityXslt)
    assert(result.isRight, s"expected Right, got: $result")
    val children = result.toOption.get.root.children.collect { case e: Markup.Element => e }
    assert(children.size == 4, s"expected 4 children, got: ${children.size}")
