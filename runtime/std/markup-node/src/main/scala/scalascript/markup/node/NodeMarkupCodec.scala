package scalascript.markup.node

import scala.scalajs.js

import scalascript.markup.*

/** Node.js [[MarkupCodec]] backed by `@xmldom/xmldom`.
 *
 *  - `parse` — calls `DOMParser.parseFromString(src, "text/xml")` from
 *    `@xmldom/xmldom`; walks the DOM tree into a `Markup.Doc`.
 *  - `serialize` — delegates to `PureMarkupCodec.serialize` (correct,
 *    handles all edge cases; `XMLSerializer` output is also available
 *    via `XmldomFacades` but PureMarkupCodec is preferred).
 *  - `validate` — not supported; throws `UnsupportedOperationException`.
 *
 *  Register at Node.js app init: call [[NodeMarkupPlugin.install]] from
 *  Scala, or invoke the exported `registerNodeMarkupCodec()` from JS. */
object NodeMarkupCodec extends MarkupCodec:

  val id = "node-xmldom"

  // ── Parse ─────────────────────────────────────────────────────────────────

  def parse(src: String, dialect: Dialect = Dialect.Xml1_0): Either[ParseError, Markup.Doc] =
    try
      val parser = new XmldomFacades.DOMParser()
      val domDoc = parser.parseFromString(src, "text/xml")
      // @xmldom/xmldom signals parse errors via a <parsererror> element
      // as the document root (note: "parsererror" with an 'r' — different
      // from the browser variant "parseerror").  Check for either name.
      val root = domDoc.documentElement
      if root == null || root.nodeName == null then
        Left(ParseError("@xmldom/xmldom returned empty document", 1, 1))
      else if root.localName == "parsererror" || root.localName == "parseerror" ||
              root.nodeName  == "parsererror" || root.nodeName  == "parseerror" then
        val msg = Option(root.asInstanceOf[js.Dynamic].textContent.asInstanceOf[String])
                    .getOrElse("XML parse error")
        Left(ParseError(msg, 1, 1))
      else
        Right(buildDoc(domDoc))
    catch case e: Throwable =>
      Left(ParseError(Option(e.getMessage).getOrElse("@xmldom/xmldom error"), 0, 0))

  // ── Serialize ─────────────────────────────────────────────────────────────

  def serialize(doc: Markup.Doc, opts: SerializeOpts = SerializeOpts.default): String =
    PureMarkupCodec.serialize(doc, opts)

  // ── DOM → Markup.Doc ──────────────────────────────────────────────────────

  private def buildDoc(domDoc: XmldomFacades.XmldomDocument): Markup.Doc =
    val root = buildElement(domDoc.documentElement)
    Markup.Doc(decl = Some(Markup.XmlDecl("1.0")), root = root)

  private def buildElement(elem: XmldomFacades.XmldomElement): Markup.Element =
    val name     = buildQName(elem)
    val attrs    = buildAttrs(elem.attributes)
    val children = childNodes(elem.childNodes).map(buildNode).flatten
    Markup.Element(name, attrs, children)

  private def buildNode(node: XmldomFacades.XmldomNode): Option[Markup.Node] =
    node.nodeType match
      case 1 => // ELEMENT_NODE
        Some(buildElement(node.asInstanceOf[XmldomFacades.XmldomElement]))
      case 3 => // TEXT_NODE
        val text = node.asInstanceOf[js.Dynamic].nodeValue.asInstanceOf[String]
        if text != null && text.nonEmpty then Some(Markup.Text(text)) else None
      case 4 => // CDATA_SECTION_NODE
        val text = node.asInstanceOf[js.Dynamic].nodeValue.asInstanceOf[String]
        if text != null then Some(Markup.CData(text)) else None
      case 7 => // PROCESSING_INSTRUCTION_NODE
        val target = node.asInstanceOf[js.Dynamic].target.asInstanceOf[String]
        val data   = node.asInstanceOf[js.Dynamic].data.asInstanceOf[String]
        Some(Markup.PI(Option(target).getOrElse(""), Option(data).getOrElse("")))
      case 8 => // COMMENT_NODE
        val text = node.asInstanceOf[js.Dynamic].nodeValue.asInstanceOf[String]
        Some(Markup.Comment(Option(text).getOrElse("")))
      case _ => None

  private def buildQName(elem: XmldomFacades.XmldomElement): Markup.QName =
    val local  = Option(elem.localName).getOrElse(elem.nodeName)
    val prefix = Option(elem.prefix).filter(s => s != null && s.nonEmpty)
    val ns     = Option(elem.namespaceURI).filter(s => s != null && s.nonEmpty)
    Markup.QName(prefix, local, ns)

  private def buildAttrs(map: XmldomFacades.XmldomNamedNodeMap): List[Markup.Attr] =
    if map == null then Nil
    else (0 until map.length).toList.map { i =>
      val a     = map.item(i)
      val local = Option(a.localName).getOrElse(a.nodeName)
      val pfx   = Option(a.prefix).filter(s => s != null && s.nonEmpty)
      Markup.Attr(Markup.QName(pfx, local, None), Option(a.value).getOrElse(""))
    }

  private def childNodes(nl: XmldomFacades.XmldomNodeList): List[XmldomFacades.XmldomNode] =
    if nl == null then Nil
    else (0 until nl.length).toList.map(nl.item)
