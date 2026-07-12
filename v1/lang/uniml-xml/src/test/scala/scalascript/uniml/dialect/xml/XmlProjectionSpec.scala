package scalascript.uniml.dialect.xml

import org.scalatest.funsuite.AnyFunSuite
import scalascript.markup.Markup
import scalascript.uniml.*

final class XmlProjectionSpec extends AnyFunSuite:
  private val source = SourceId("memory:projection.xml")

  test("projects secure mixed content through the existing Markup model") {
    val parsed = parse("<?xml version=\"1.0\"?><r a='x&amp;y'>a&lt;b<![CDATA[<c>]]><!--m--><?p d?></r>")
    val projected = Xml.projectMarkup(parsed)
    assert(projected.document.nonEmpty, projected.diagnostics.toString)
    val root = projected.document.get.root
    assert(root.name.localName == "r")
    assert(root.attrs.head.value == "x&y")
    assert(root.children.exists { case Markup.Text("a<b") => true; case _ => false })
    assert(root.children.exists { case Markup.CData("<c>") => true; case _ => false })
    assert(root.children.exists { case Markup.Comment("m") => true; case _ => false })
  }

  test("resolves default and prefixed namespaces and excludes xmlns attributes") {
    val parsed = parse("<root xmlns='urn:d' xmlns:p='urn:p' p:id='1'><p:child plain='x'/></root>")
    val validation = Xml.validate(parsed)
    assert(validation.complete, validation.diagnostics.toString)
    val root = Xml.projectMarkup(parsed).document.get.root
    assert(root.name.namespace.contains("urn:d"))
    assert(root.attrs.map(_.name.localName) == List("id"))
    assert(root.attrs.head.name.namespace.contains("urn:p"))
    val child = root.children.collectFirst { case element: Markup.Element => element }.get
    assert(child.name.namespace.contains("urn:p"))
    assert(child.attrs.head.name.namespace.isEmpty)
  }

  test("rejects unbound prefixes and duplicate expanded attributes") {
    val unbound = Xml.validate(parse("<p:r/>"))
    assert(!unbound.complete)
    assert(unbound.diagnostics.exists(_.code == "uniml.xml.invalid-namespace-binding"))

    val duplicate = Xml.validate(parse("<r xmlns:a='urn:x' xmlns:b='urn:x' a:k='1' b:k='2'/>"))
    assert(!duplicate.complete)
    assert(duplicate.diagnostics.exists(_.message.contains("duplicate expanded attribute")))

    val reserved = Xml.validate(parse("<r xmlns:xml='urn:wrong'/>") )
    assert(!reserved.complete)
    assert(reserved.diagnostics.exists(_.message.contains("invalid namespace binding")))
  }

  test("custom entity references stay lossless and block semantic projection") {
    val parsed = parse("<!DOCTYPE r [<!ENTITY x 'value'>]><r>&x;</r>")
    assert(parsed.status == CompletionStatus.Complete)
    assert(parsed.roots.flatMap(UniNode.sourceTokens).exists(_.lexeme == "&x;"))
    val projected = Xml.projectMarkup(parsed)
    assert(projected.document.isEmpty)
    assert(projected.diagnostics.exists(_.code == "uniml.xml.unresolved-entity"))
  }

  test("validation rejects a mismatched DOCTYPE root and malformed attribute reference") {
    val doctype = Xml.validate(parse("<!DOCTYPE expected><actual/>"))
    assert(!doctype.complete)
    assert(doctype.diagnostics.exists(_.code == "uniml.xml.invalid-doctype"))

    val attribute = parse("<r a='&broken'/>")
    assert(attribute.status == CompletionStatus.Incomplete)
    assert(attribute.diagnostics.exists(_.code == "uniml.xml.expected-attribute-value"))
  }

  test("warns when pre-root misc cannot fit Markup.Doc") {
    val projected = Xml.projectMarkup(parse("<!--before--><r/>"))
    assert(projected.document.nonEmpty)
    assert(projected.diagnostics.exists(_.code == "uniml.xml.projection-lossy-prolog"))
  }

  private def parse(text: String): ParseResult = Xml.parse(SourceInput.fromString(source, text))
