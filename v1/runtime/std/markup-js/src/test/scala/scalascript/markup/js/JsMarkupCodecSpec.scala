package scalascript.markup.js

import org.scalatest.funsuite.AnyFunSuite

import scalascript.markup.*

/** Unit tests for the markup-js module.
 *
 *  Tests run under Node.js via the Scala.js test runner.  Because
 *  `DOMParser` and `XMLSerializer` are browser globals not available in
 *  vanilla Node.js, the tests that exercise `JsMarkupCodec.parse` are
 *  implemented using `PureMarkupCodec` as a structural reference —
 *  the point here is to verify the codec contract, plugin wiring, and
 *  serializer delegation.
 *
 *  Full integration (real browser DOMParser) is covered by the e2e tests
 *  in `examples/markup-js/`. */
class JsMarkupCodecSpec extends AnyFunSuite:

  // ── id contract ──────────────────────────────────────────────────────────

  test("JsMarkupCodec has id 'js-dom'"):
    assert(JsMarkupCodec.id == "js-dom")

  // ── serialize delegates to PureMarkupCodec ────────────────────────────────

  test("serialize: simple element round-trips via PureMarkupCodec"):
    val doc = PureMarkupCodec.parse("<root><child>hello</child></root>").toOption.get
    val xml = JsMarkupCodec.serialize(doc, SerializeOpts(omitXmlDecl = true))
    assert(xml.contains("<root>"))
    assert(xml.contains("<child>hello</child>"))

  test("serialize: pretty-print adds newlines"):
    val doc = PureMarkupCodec.parse("<root><a/><b/></root>").toOption.get
    val xml = JsMarkupCodec.serialize(doc, SerializeOpts(pretty = true, omitXmlDecl = true))
    assert(xml.contains("\n"))

  test("serialize: omitXmlDecl omits the declaration"):
    val doc = PureMarkupCodec.parse("""<?xml version="1.0"?><root/>""").toOption.get
    val xml = JsMarkupCodec.serialize(doc, SerializeOpts(omitXmlDecl = true))
    assert(!xml.contains("<?xml"))

  test("serialize: XML declaration is included by default"):
    val doc = PureMarkupCodec.parse("""<?xml version="1.0"?><root/>""").toOption.get
    val xml = JsMarkupCodec.serialize(doc)
    assert(xml.startsWith("<?xml"))

  test("serialize: attribute escaping is preserved"):
    val doc = PureMarkupCodec.parse("""<root id="&amp;foo"/>""").toOption.get
    val xml = JsMarkupCodec.serialize(doc, SerializeOpts(omitXmlDecl = true))
    assert(xml.contains("&amp;"))

  // ── validate: not supported on JS ────────────────────────────────────────

  test("validate throws UnsupportedOperationException on JS codec"):
    val doc = PureMarkupCodec.parse("<root/>").toOption.get
    intercept[UnsupportedOperationException]:
      JsMarkupCodec.validate(doc, "<xs:schema/>")

  // ── JsMarkupPlugin wiring ─────────────────────────────────────────────────

  test("JsMarkupPlugin.install sets JsMarkupCodec as default"):
    val prev = MarkupCodec.default
    JsMarkupPlugin.install()
    assert(MarkupCodec.default eq JsMarkupCodec)
    // restore
    MarkupCodec.setDefault(prev)

  test("JsMarkupPlugin.install is idempotent — second call does not throw"):
    JsMarkupPlugin.install()
    JsMarkupPlugin.install()
    assert(MarkupCodec.default eq JsMarkupCodec)
    MarkupCodec.setDefault(PureMarkupCodec)

  // ── MarkupCodec named registry ────────────────────────────────────────────

  test("PureMarkupCodec is accessible from MarkupCodec.named('pure')"):
    assert(MarkupCodec.named("pure") eq PureMarkupCodec)

  test("MarkupCodec default is overrideable — JsMarkupCodec round-trip"):
    MarkupCodec.setDefault(JsMarkupCodec)
    assert(MarkupCodec.default.id == "js-dom")
    MarkupCodec.setDefault(PureMarkupCodec)
