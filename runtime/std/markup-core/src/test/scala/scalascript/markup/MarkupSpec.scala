package scalascript.markup

import org.scalatest.funsuite.AnyFunSuite

class MarkupSpec extends AnyFunSuite:

  val codec = PureMarkupCodec

  private def parseOk(src: String): Markup.Doc =
    codec.parse(src) match
      case Right(doc) => doc
      case Left(e)    => fail(s"parse failed: $e")

  private def roundTrip(src: String): Unit =
    val doc      = parseOk(src)
    val reserialized = codec.serialize(doc)
    val doc2     = codec.parse(reserialized) match
      case Right(d) => d
      case Left(e)  => fail(s"re-parse failed: $e — serialized: $reserialized")
    assert(doc2.root.name.localName == doc.root.name.localName)

  // ── Parse/serialize tests ─────────────────────────────────────────────────

  test("empty element — self-closing") {
    val doc = parseOk("<root/>")
    assert(doc.root.name.localName == "root")
    assert(doc.root.children.isEmpty)
  }

  test("attributes") {
    val doc = parseOk("""<root id="42" name="hello"/>""")
    assert(doc.root.attrs.size == 2)
    assert(doc.root.attrs.find(_.name.localName == "id").get.value == "42")
    assert(doc.root.attrs.find(_.name.localName == "name").get.value == "hello")
  }

  test("namespaced element and attribute") {
    val doc = parseOk("""<ns:root xmlns:ns="urn:example" ns:id="1"/>""")
    assert(doc.root.name.prefix == Some("ns"))
    assert(doc.root.name.localName == "root")
    val idAttr = doc.root.attrs.find(_.name.localName == "id").get
    assert(idAttr.name.prefix == Some("ns"))
  }

  test("CDATA section") {
    val doc = parseOk("<root><![CDATA[hello <world>]]></root>")
    val cdata = doc.root.children.collectFirst { case Markup.CData(c) => c }
    assert(cdata.contains("hello <world>"))
  }

  test("comments") {
    val doc = parseOk("<root><!-- a comment --></root>")
    val comment = doc.root.children.collectFirst { case Markup.Comment(c) => c }
    assert(comment.isDefined)
  }

  test("processing instructions") {
    val doc = parseOk("""<?xml version="1.0"?><?app data="1"?><root/>""")
    assert(doc.decl.isDefined)
    assert(doc.decl.get.version == "1.0")
    assert(doc.trailing.isEmpty)
  }

  test("nested elements") {
    val doc = parseOk("<a><b><c/></b></a>")
    val b = doc.root.children.collectFirst { case e: Markup.Element => e }.get
    assert(b.name.localName == "b")
    val c = b.children.collectFirst { case e: Markup.Element => e }.get
    assert(c.name.localName == "c")
  }

  test("mixed content — text and elements") {
    val doc = parseOk("<p>Hello <b>world</b>!</p>")
    assert(doc.root.children.size == 3)
    val texts = doc.root.children.collect { case Markup.Text(t) => t }
    assert(texts.contains("Hello "))
    assert(texts.contains("!"))
  }

  test("XML declaration with encoding") {
    val doc = parseOk("""<?xml version="1.0" encoding="UTF-8"?><root/>""")
    assert(doc.decl.get.encoding.contains("UTF-8"))
  }

  test("entity escaping round-trip") {
    val doc = parseOk("<root>&amp;&lt;&gt;&quot;&apos;</root>")
    val text = doc.root.children.collectFirst { case Markup.Text(t) => t }.get
    assert(text == "&<>\"'")
    val serialized = codec.serialize(doc, SerializeOpts(omitXmlDecl = true))
    assert(serialized.contains("&amp;"))
    assert(serialized.contains("&lt;"))
  }

  test("round-trip: namespace-heavy SEPA-style doc") {
    roundTrip(
      """<?xml version="1.0" encoding="UTF-8"?>""" +
      """<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.001.001.03">""" +
      """<CstmrCdtTrfInitn><GrpHdr><MsgId>MSG001</MsgId></GrpHdr></CstmrCdtTrfInitn>""" +
      """</Document>"""
    )
  }

class XmlInterpolatorSpec extends AnyFunSuite:

  import scalascript.markup.*

  test("xml interpolator — plain") {
    val doc = xml"<root/>"
    assert(doc.root.name.localName == "root")
  }

  test("xml interpolator — string arg is escaped") {
    val dangerous = "<script>alert(1)</script>"
    val doc = xml"<msg>${dangerous}</msg>"
    val text = doc.root.children.collectFirst { case Markup.Text(t) => t }.get
    assert(text.contains("&lt;script"))
    assert(!text.contains("<script>"))
  }

  test("xml interpolator — Markup.raw passes through verbatim") {
    val raw = Markup.raw("<inner/>")
    val doc = xml"<root>${raw}</root>"
    val serialized = PureMarkupCodec.serialize(doc, SerializeOpts(omitXmlDecl = true))
    assert(serialized.contains("<inner/>"))
  }

  test("xml interpolator — Markup.Element splice") {
    val child = Markup.Element(Markup.QName.local("child"))
    val doc = xml"<root>${child}</root>"
    val serialized = PureMarkupCodec.serialize(doc, SerializeOpts(omitXmlDecl = true))
    assert(serialized.contains("<child/>"))
  }

  test("xml interpolator — numeric arg") {
    val n = 42
    val doc = xml"<value>${n}</value>"
    val text = doc.root.children.collectFirst { case Markup.Text(t) => t }.get
    assert(text == "42")
  }

  test("xml interpolator — malformed XML throws ParseError") {
    intercept[ParseError] {
      xml"<unclosed>"
    }
  }
