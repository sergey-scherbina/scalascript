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

  // ── the XML alphabet: the GRAMMAR is the oracle, not the host ────────────────────────────
  //
  // These predicates used to be `c.isLetter` / `c.isLetterOrDigit` / `Char.isWhitespace`, which
  // answer from the host's Unicode tables — so the same document could parse differently on a
  // different runtime (UNIML-SSC3-ALPHABET), AND the borrowed notion of "letter" was not the
  // production this parser implements. XML 1.0 5th ed. section 2.3 spells its own ranges.

  test("a colon SPLITS a name — these are NCNames, not Names") {
    // XML 1.0's NameStartChar does include ":", and spelling it that way here was WRONG: this
    // parser implements namespaces by letting the colon terminate the name scan, so admitting it
    // made `ns:root` one undivided name and the prefix came back None. Namespaces in XML defines
    // NCName as Name minus ":", and that is the production this parser wants. The existing
    // `namespaced element and attribute` test is what caught it.
    val doc = codec.parse("<a:b/>").toOption.get
    assert(doc.root.name.prefix == Some("a"), "the colon stopped splitting the name")
    assert(doc.root.name.localName == "b")
  }

  test("a name may contain a middle dot and a combining mark") {
    // NameChar adds #xB7 and [#x0300-#x036F]; `isLetterOrDigit` says no to both.
    assert(codec.parse("<a" + 0x00B7.toChar + "b/>").isRight, "#xB7 was refused in a name")
    assert(codec.parse("<a" + 0x0301.toChar + "b/>").isRight, "a combining acute was refused in a name")
  }

  test("form feed is NOT XML whitespace — the host says it is") {
    // XML's S is exactly #x20, #x9, #xD, #xA. `Char.isWhitespace` also admits VT, FF and the
    // separators, so a form feed between attributes used to be accepted silently. Stricter is the
    // correct direction: the grammar is the authority, not the runtime.
    assert(codec.parse("<a" + 0x000C.toChar + "b='1'/>").isLeft,
           "a form feed was accepted as attribute whitespace")
    assert(codec.parse("<a b='1'/>").isRight, "a real space stopped working")
    assert(codec.parse("<a" + 0x0009.toChar + "b='1'/>").isRight, "a tab stopped working")
    assert(codec.parse("<a" + 0x000A.toChar + "b='1'/>").isRight, "a newline stopped working")
  }

  test("a name may not start with a digit, a dot or a hyphen") {
    // The controls: these are NameChar but not NameStartChar, and a rule admitting them would
    // make `<1a/>` well-formed.
    assert(codec.parse("<1a/>").isLeft, "a digit was accepted as a name start")
    assert(codec.parse("<.a/>").isLeft, "a dot was accepted as a name start")
    assert(codec.parse("<-a/>").isLeft, "a hyphen was accepted as a name start")
  }

  test("an ordinary ASCII document is unaffected") {
    assert(codec.parse("<root attr='v'><child>text</child></root>").isRight, "the ordinary path regressed")
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
    // Parser unescapes entities: text content is the original string
    val text = doc.root.children.collectFirst { case Markup.Text(t) => t }.get
    assert(text == dangerous)
    // Crucially, no child <script> element was injected — just a text node
    assert(!doc.root.children.exists(_.isInstanceOf[Markup.Element]))
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
