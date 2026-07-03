package scalascript.markup

import org.scalatest.funsuite.AnyFunSuite

/** Unit tests for JvmMarkupCodec — SAX-based parse, PureMarkupCodec-backed
 *  serialize, and XSD validation via javax.xml.validation. */
class JvmMarkupCodecTest extends AnyFunSuite:

  private val codec: MarkupCodec = JvmMarkupCodec

  // ── parse — valid XML ──────────────────────────────────────────────────

  test("parse — simple element"):
    val result = codec.parse("<root/>")
    assert(result.isRight, s"expected Right, got: $result")
    val doc = result.toOption.get
    assert(doc.root.name.localName == "root")
    assert(doc.root.children.isEmpty)

  test("parse — element with text content"):
    val result = codec.parse("<greeting>Hello, World!</greeting>")
    assert(result.isRight, s"expected Right, got: $result")
    val doc = result.toOption.get
    assert(doc.root.name.localName == "greeting")
    val texts = doc.root.children.collect { case Markup.Text(t) => t }
    assert(texts.mkString.contains("Hello, World!"))

  test("parse — nested elements and attributes"):
    val xml =
      """<person id="42">
        |  <name>Alice</name>
        |  <age>30</age>
        |</person>""".stripMargin
    val result = codec.parse(xml)
    assert(result.isRight, s"expected Right, got: $result")
    val doc = result.toOption.get
    assert(doc.root.name.localName == "person")
    assert(doc.root.attrs.exists(a => a.name.localName == "id" && a.value == "42"))
    val names = doc.root.children.collect { case e: Markup.Element => e }
    assert(names.exists(_.name.localName == "name"))
    assert(names.exists(_.name.localName == "age"))

  test("parse — XML declaration is captured"):
    val xml = """<?xml version="1.0" encoding="UTF-8"?><root/>"""
    val result = codec.parse(xml)
    assert(result.isRight, s"expected Right, got: $result")
    val doc = result.toOption.get
    assert(doc.decl.isDefined)
    assert(doc.decl.get.version == "1.0")

  // ── parse — malformed XML ──────────────────────────────────────────────

  test("parse — malformed XML returns Left(ParseError)"):
    val result = codec.parse("<unclosed>")
    assert(result.isLeft, s"expected Left for malformed XML, got: $result")
    val err = result.left.toOption.get
    assert(err.message.nonEmpty, "ParseError should have a non-empty message")

  test("parse — mismatched tags returns Left(ParseError)"):
    val result = codec.parse("<a><b></a>")
    assert(result.isLeft, s"expected Left for mismatched tags, got: $result")

  test("parse — empty string returns Left(ParseError)"):
    val result = codec.parse("")
    assert(result.isLeft, s"expected Left for empty input, got: $result")

  // ── serialize round-trip ───────────────────────────────────────────────

  test("serialize — delegates to PureMarkupCodec, round-trip preserves structure"):
    val xml    = """<?xml version="1.0"?><root><child attr="v">text</child></root>"""
    val parsed = codec.parse(xml)
    assert(parsed.isRight, s"parse failed: $parsed")
    val serialized = codec.serialize(parsed.toOption.get)
    // Re-parse to verify structural equivalence (whitespace may differ)
    val reparsed = codec.parse(serialized)
    assert(reparsed.isRight, s"re-parse failed: $reparsed")
    val doc2 = reparsed.toOption.get
    assert(doc2.root.name.localName == "root")
    val child = doc2.root.children.collect { case e: Markup.Element => e }.head
    assert(child.name.localName == "child")
    assert(child.attrs.exists(a => a.name.localName == "attr" && a.value == "v"))
    val childTexts = child.children.collect { case Markup.Text(t) => t }
    assert(childTexts.mkString == "text")

  test("serialize — pretty option produces indented output"):
    val xml = "<root><child/></root>"
    val parsed = codec.parse(xml).toOption.get
    val pretty  = codec.serialize(parsed, SerializeOpts.pretty)
    assert(pretty.contains("\n"), "pretty serialization should contain newlines")

  // ── validate ──────────────────────────────────────────────────────────

  test("validate — valid doc against matching XSD returns empty error list"):
    val xsd =
      """<?xml version="1.0"?>
        |<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
        |  <xs:element name="person">
        |    <xs:complexType>
        |      <xs:sequence>
        |        <xs:element name="name" type="xs:string"/>
        |        <xs:element name="age"  type="xs:integer"/>
        |      </xs:sequence>
        |    </xs:complexType>
        |  </xs:element>
        |</xs:schema>""".stripMargin
    val xml  = "<person><name>Alice</name><age>30</age></person>"
    val doc  = codec.parse(xml).toOption.get
    val errs = codec.validate(doc, xsd)
    assert(errs.isEmpty, s"expected no validation errors, got: $errs")

  test("validate — doc violating XSD constraint returns non-empty error list"):
    val xsd =
      """<?xml version="1.0"?>
        |<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
        |  <xs:element name="person">
        |    <xs:complexType>
        |      <xs:sequence>
        |        <xs:element name="name" type="xs:string"/>
        |        <xs:element name="age"  type="xs:integer"/>
        |      </xs:sequence>
        |    </xs:complexType>
        |  </xs:element>
        |</xs:schema>""".stripMargin
    // Missing required <age> element
    val xml  = "<person><name>Bob</name></person>"
    val doc  = codec.parse(xml).toOption.get
    val errs = codec.validate(doc, xsd)
    assert(errs.nonEmpty, "expected validation errors for missing required element")

  // ── codec id ──────────────────────────────────────────────────────────

  test("JvmMarkupCodec id is 'jvm-sax'"):
    assert(codec.id == "jvm-sax")
