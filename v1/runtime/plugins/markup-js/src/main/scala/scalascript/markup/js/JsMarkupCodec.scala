package scalascript.markup.js

import scala.scalajs.js

import scalascript.markup.*

/** Scala.js [[MarkupCodec]] backed by the browser's native `DOMParser`
 *  and `XMLSerializer` globals.
 *
 *  - `parse` — delegates to `DOMParser.parseFromString(src, "text/xml")`;
 *    walks the resulting DOM tree and builds a `Markup.Doc`.
 *  - `serialize` — delegates to `PureMarkupCodec.serialize` (the pure
 *    codec's serializer is complete and avoids the namespace-handling
 *    quirks of `XMLSerializer`).
 *  - `validate` — not supported; throws `UnsupportedOperationException`.
 *
 *  Register at app init: call [[JsMarkupPlugin.install]] from Scala, or
 *  invoke the exported `registerJsMarkupCodec()` top-level function from
 *  JS host code. */
object JsMarkupCodec extends MarkupCodec:

  val id = "js-dom"

  // ── Parse ─────────────────────────────────────────────────────────────────

  def parse(src: String, dialect: Dialect = Dialect.Xml1_0): Either[ParseError, Markup.Doc] =
    try
      val parser = new DomFacades.DOMParser()
      val domDoc = parser.parseFromString(src, "text/xml")
      // DOMParser signals errors via a <parseerror> root element in the
      // application/xhtml+xml namespace (Blink) or a top-level
      // <parseerror> (Firefox/Safari). Detect both.
      val root = domDoc.documentElement
      if root.localName == "parseerror" ||
         root.nodeName  == "parseerror" ||
         root.namespaceURI == "http://www.mozilla.org/newlayout/xml/parseerror.xml" then
        Left(ParseError(Option(root.textContent).getOrElse("XML parse error"), 1, 1))
      else
        Right(buildDoc(domDoc))
    catch case e: Throwable =>
      Left(ParseError(Option(e.getMessage).getOrElse("JS DOMParser error"), 0, 0))

  // ── Serialize ─────────────────────────────────────────────────────────────

  def serialize(doc: Markup.Doc, opts: SerializeOpts = SerializeOpts.default): String =
    PureMarkupCodec.serialize(doc, opts)

  // ── DOM → Markup.Doc conversion ──────────────────────────────────────────

  private def buildDoc(domDoc: DomFacades.DomDocument): Markup.Doc =
    val root = buildElement(domDoc.documentElement)
    Markup.Doc(
      decl  = Some(Markup.XmlDecl("1.0")),
      root  = root,
    )

  private def buildElement(elem: DomFacades.DomElement): Markup.Element =
    val name  = buildQName(elem)
    val attrs = buildAttrs(elem.attributes)
    val children = childNodes(elem.childNodes).map(buildNode).flatten
    Markup.Element(name, attrs, children)

  private def buildNode(node: DomFacades.DomNode): Option[Markup.Node] =
    node.nodeType match
      case 1 => // ELEMENT_NODE
        Some(buildElement(node.asInstanceOf[DomFacades.DomElement]))
      case 3 => // TEXT_NODE
        val text = node.asInstanceOf[js.Dynamic].nodeValue.asInstanceOf[String]
        if text != null && text.nonEmpty then Some(Markup.Text(text))
        else None
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

  private def buildQName(elem: DomFacades.DomElement): Markup.QName =
    val local  = Option(elem.localName).getOrElse(elem.nodeName)
    val prefix = Option(elem.prefix).filter(s => s != null && s.nonEmpty)
    val ns     = Option(elem.namespaceURI).filter(s => s != null && s.nonEmpty)
    Markup.QName(prefix, local, ns)

  private def buildAttrs(map: DomFacades.DomNamedNodeMap): List[Markup.Attr] =
    (0 until map.length).toList.map { i =>
      val a     = map.item(i)
      val local = Option(a.localName).getOrElse(a.nodeName)
      val pfx   = Option(a.prefix).filter(s => s != null && s.nonEmpty)
      Markup.Attr(Markup.QName(pfx, local, None), Option(a.value).getOrElse(""))
    }

  private def childNodes(nl: DomFacades.DomNodeList): List[DomFacades.DomNode] =
    (0 until nl.length).toList.map(nl.item)
