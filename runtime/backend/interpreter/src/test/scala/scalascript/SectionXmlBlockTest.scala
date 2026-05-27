package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.{Interpreter, InterpretError, Value}
import scalascript.parser.Parser
import scalascript.markup.Markup

/** Conformance tests for v1.55.2 — fenced ```xml blocks in .ssc sections. */
class SectionXmlBlockTest extends AnyFunSuite with Matchers:

  private def run(ssc: String): Map[String, Value] =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val interp = Interpreter(ps)
    interp.run(Parser.parse(ssc))
    interp.exportedGlobals

  private def xmlOf(globals: Map[String, Value], key: String): Markup.Doc =
    globals(key) match
      case Value.InstanceV(_, fields) =>
        fields("xml") match
          case Value.MarkupV(doc) => doc
          case other => fail(s"expected MarkupV, got $other")
      case other => fail(s"expected InstanceV for '$key', got $other")

  // ── Basic fenced xml block binds to section ident ────────────────────────

  test("fenced xml block is bound to section ident as Markup.Doc"):
    val ssc =
      """## MySection
        |
        |```xml
        |<root><child>hello</child></root>
        |```
        |""".stripMargin
    val g = run(ssc)
    val doc = xmlOf(g, "MySection")
    doc.root.name.localName.shouldBe("root")
    doc.root.children.head match
      case Markup.Element(qn, _, _) => qn.localName.shouldBe("child")
      case other => fail(s"expected child element, got $other")

  test("section ident preserves first word capitalisation"):
    val ssc =
      """## Sepa Payment
        |
        |```xml
        |<doc/>
        |```
        |""".stripMargin
    val g = run(ssc)
    g.contains("SepaPayment").shouldBe(true)

  // ── ${expr} interpolation with XML escaping ───────────────────────────────

  test("interpolated string value is XML-unescaped in parsed text content"):
    val ssc =
      """## Invoice
        |
        |```scala
        |val company = "A&B <Corp>"
        |```
        |
        |```xml
        |<invoice><name>${company}</name></invoice>
        |```
        |""".stripMargin
    val g = run(ssc)
    val doc = xmlOf(g, "Invoice")
    val text = doc.root.children.head match
      case Markup.Element(_, _, children) =>
        children.collect { case Markup.Text(t) => t }.mkString
      case other => fail(s"unexpected root child: $other")
    // The parser unescapes entity refs, so we get back the raw string.
    // The important guarantee is that dangerous chars can't inject child elements.
    text.shouldBe("A&B <Corp>")

  test("interpolated integer value appears as text"):
    val ssc =
      """## Order
        |
        |```scala
        |val qty = 42
        |```
        |
        |```xml
        |<order><qty>${qty}</qty></order>
        |```
        |""".stripMargin
    val g = run(ssc)
    val doc = xmlOf(g, "Order")
    val text = doc.root.children.head match
      case Markup.Element(_, _, children) =>
        children.collect { case Markup.Text(t) => t }.mkString
      case other => fail(s"unexpected root child: $other")
    text.shouldBe("42")

  // ── Attributes from interpolation ────────────────────────────────────────

  test("interpolated value in attribute is preserved through parse"):
    val ssc =
      """## Config
        |
        |```scala
        |val host = "my-server"
        |```
        |
        |```xml
        |<cfg host="${host}"/>
        |```
        |""".stripMargin
    val g = run(ssc)
    val doc = xmlOf(g, "Config")
    val attr = doc.root.attrs.find(_.name.localName == "host")
    attr.map(_.value).shouldBe(Some("my-server"))

  // ── Namespaced XML preserved ──────────────────────────────────────────────

  test("namespaced root element is preserved in parsed doc"):
    val ssc =
      """## Iso Payment
        |
        |```xml
        |<ns:Document xmlns:ns="urn:example">
        |  <ns:Id>123</ns:Id>
        |</ns:Document>
        |```
        |""".stripMargin
    val g = run(ssc)
    val doc = xmlOf(g, "IsoPayment")
    doc.root.name.prefix.shouldBe(Some("ns"))
    doc.root.name.localName.shouldBe("Document")

  // ── XML parse error surfaces ──────────────────────────────────────────────

  test("malformed xml block raises InterpretError with line info"):
    val ssc =
      """## Bad
        |
        |```xml
        |<< invalid xml >>
        |```
        |""".stripMargin
    val ex = intercept[InterpretError] { run(ssc) }
    ex.getMessage.should(include("XML parse error"))

  // ── xml binding coexists with other block types ───────────────────────────

  test("section can have both scala and xml blocks"):
    val ssc =
      """## Report
        |
        |```scala
        |val title = "Monthly"
        |```
        |
        |```xml
        |<report><title>${title}</title></report>
        |```
        |""".stripMargin
    val g = run(ssc)
    val doc = xmlOf(g, "Report")
    val text = doc.root.children.head match
      case Markup.Element(_, _, children) =>
        children.collect { case Markup.Text(t) => t }.mkString
      case other => fail(s"unexpected child: $other")
    text.shouldBe("Monthly")
