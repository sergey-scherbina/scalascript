package scalascript.markup.node

import org.scalatest.funsuite.AnyFunSuite

import scalascript.markup.*

/** Integration tests for [[NodeMarkupCodec]].
 *
 *  Tests run under Node.js via the Scala.js test runner.
 *  Requires `npm install` to have populated `markup-node/node_modules/`
 *  with `@xmldom/xmldom`.  See `markup-node/package.json`. */
class NodeMarkupCodecSpec extends AnyFunSuite:

  private def parseOk(src: String): Markup.Doc =
    NodeMarkupCodec.parse(src) match
      case Right(doc) => doc
      case Left(e)    => fail(s"parse failed: $e")

  // ── id contract ──────────────────────────────────────────────────────────

  test("NodeMarkupCodec has id 'node-xmldom'"):
    assert(NodeMarkupCodec.id == "node-xmldom")

  // ── parse — well-formed XML ──────────────────────────────────────────────

  test("parse: simple root element"):
    val doc = parseOk("<root/>")
    assert(doc.root.name.localName == "root")
    assert(doc.root.children.isEmpty)

  test("parse: element with text content"):
    val doc = parseOk("<greeting>hello</greeting>")
    val text = doc.root.children.collectFirst { case Markup.Text(t) => t }
    assert(text.exists(_.contains("hello")))

  test("parse: element with attributes"):
    val doc = parseOk("""<item id="1" name="foo"/>""")
    assert(doc.root.attrs.exists(a => a.name.localName == "id" && a.value == "1"))
    assert(doc.root.attrs.exists(a => a.name.localName == "name" && a.value == "foo"))

  test("parse: nested elements"):
    val doc = parseOk("<parent><child><grandchild/></child></parent>")
    val child = doc.root.children.collectFirst { case e: Markup.Element => e }.get
    assert(child.name.localName == "child")
    val gc = child.children.collectFirst { case e: Markup.Element => e }.get
    assert(gc.name.localName == "grandchild")

  test("parse: namespaced element"):
    val doc = parseOk("""<ns:root xmlns:ns="urn:example"/>""")
    assert(doc.root.name.localName == "root")
    assert(doc.root.name.prefix.contains("ns"))

  test("parse: XML with declaration"):
    val doc = parseOk("""<?xml version="1.0" encoding="UTF-8"?><root/>""")
    assert(doc.root.name.localName == "root")

  test("parse: malformed XML returns Left(ParseError)"):
    val result = NodeMarkupCodec.parse("<unclosed>")
    assert(result.isLeft)
    result match
      case Left(e) => assert(e.isInstanceOf[ParseError])
      case _       => fail("expected Left(ParseError)")

  // ── serialize ────────────────────────────────────────────────────────────

  test("serialize: delegates to PureMarkupCodec — simple element"):
    val doc = parseOk("<root><child>text</child></root>")
    val xml = NodeMarkupCodec.serialize(doc, SerializeOpts(omitXmlDecl = true))
    assert(xml.contains("<root>"))
    assert(xml.contains("<child>text</child>"))

  test("serialize: pretty-print"):
    val doc = parseOk("<root><a/><b/></root>")
    val xml = NodeMarkupCodec.serialize(doc, SerializeOpts(pretty = true, omitXmlDecl = true))
    assert(xml.contains("\n"))

  test("serialize: XML declaration emitted by default"):
    val doc = parseOk("<root/>")
    val xml = NodeMarkupCodec.serialize(doc)
    assert(xml.startsWith("<?xml"))

  // ── validate: not supported ───────────────────────────────────────────────

  test("validate throws UnsupportedOperationException"):
    val doc = parseOk("<root/>")
    intercept[UnsupportedOperationException]:
      NodeMarkupCodec.validate(doc, "<xs:schema/>")

  // ── NodeMarkupPlugin wiring ───────────────────────────────────────────────

  test("NodeMarkupPlugin.install sets NodeMarkupCodec as default"):
    val prev = MarkupCodec.default
    NodeMarkupPlugin.install()
    assert(MarkupCodec.default eq NodeMarkupCodec)
    MarkupCodec.setDefault(prev)

  test("NodeMarkupPlugin.install is idempotent — second call does not throw"):
    NodeMarkupPlugin.install()
    NodeMarkupPlugin.install()
    assert(MarkupCodec.default eq NodeMarkupCodec)
    MarkupCodec.setDefault(PureMarkupCodec)
