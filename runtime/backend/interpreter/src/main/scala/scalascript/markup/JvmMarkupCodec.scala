package scalascript.markup

import javax.xml.parsers.SAXParserFactory
import javax.xml.validation.SchemaFactory
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import org.xml.sax.helpers.DefaultHandler
import org.xml.sax.{Attributes, Locator, SAXParseException}
import org.xml.sax.ext.LexicalHandler
import java.io.StringReader

/** JVM-native XML codec backed by `javax.xml.parsers.SAXParser`.
 *
 *  - `parse` — full SAX parse into `Markup.Doc`; handles namespace
 *    prefixes, attributes, PIs, comments, and the XML declaration.
 *  - `serialize` — delegates to `PureMarkupCodec.serialize` (SAX is
 *    parse-only; the pure codec's serializer is complete and correct).
 *  - `validate` — W3C XSD validation via `javax.xml.validation.SchemaFactory`
 *    with `W3C_XML_SCHEMA_NS_URI`; returns all collected errors or `Nil`.
 *
 *  Registered as the default `MarkupCodec` at JVM / interpreter startup
 *  by calling `MarkupCodec.setDefault(JvmMarkupCodec)`. */
object JvmMarkupCodec extends MarkupCodec:

  val id = "jvm-sax"

  // ── Parse ──────────────────────────────────────────────────────────────

  def parse(src: String, dialect: Dialect = Dialect.Xml1_0): Either[ParseError, Markup.Doc] =
    val handler = new BuildingHandler()
    try
      val factory = SAXParserFactory.newInstance()
      factory.setNamespaceAware(true)
      factory.setFeature("http://xml.org/sax/features/namespace-prefixes", true)
      // Disable external entity processing (XXE prevention)
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
      try factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
      catch case _: org.xml.sax.SAXNotRecognizedException => () // not all parsers support this
      val parser = factory.newSAXParser()
      // Register as LexicalHandler so comment() / startCDATA() fire
      parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
      parser.parse(new org.xml.sax.InputSource(new StringReader(src)), handler)
      handler.result() match
        case Some(doc) => Right(doc)
        case None      => Left(ParseError("SAX parse produced no document root", 0, 0))
    catch
      case e: SAXParseException =>
        Left(ParseError(e.getMessage, e.getLineNumber, e.getColumnNumber))
      case e: org.xml.sax.SAXException =>
        Left(ParseError(Option(e.getMessage).getOrElse("XML parse error"), 0, 0))
      case e: java.io.IOException =>
        Left(ParseError(Option(e.getMessage).getOrElse("IO error"), 0, 0))

  // ── Serialize ─────────────────────────────────────────────────────────

  def serialize(doc: Markup.Doc, opts: SerializeOpts = SerializeOpts.default): String =
    PureMarkupCodec.serialize(doc, opts)

  // ── Validate ──────────────────────────────────────────────────────────

  override def validate(doc: Markup.Doc, xsd: String): List[ValidationError] =
    val errors = scala.collection.mutable.ListBuffer.empty[ValidationError]
    val errorHandler = new org.xml.sax.ErrorHandler:
      def warning(e: SAXParseException): Unit   = errors += ValidationError(e.getMessage, e.getLineNumber, e.getColumnNumber)
      def error(e: SAXParseException): Unit     = errors += ValidationError(e.getMessage, e.getLineNumber, e.getColumnNumber)
      def fatalError(e: SAXParseException): Unit = errors += ValidationError(e.getMessage, e.getLineNumber, e.getColumnNumber)

    try
      val schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
      val schema        = schemaFactory.newSchema(new StreamSource(new StringReader(xsd)))
      val validator     = schema.newValidator()
      validator.setErrorHandler(errorHandler)
      val docXml        = serialize(doc, SerializeOpts.default)
      validator.validate(new StreamSource(new StringReader(docXml)))
    catch
      case e: org.xml.sax.SAXException =>
        errors += ValidationError(Option(e.getMessage).getOrElse("SAX error"), 0, 0)
      case e: java.io.IOException =>
        errors += ValidationError(Option(e.getMessage).getOrElse("IO error"), 0, 0)

    errors.toList

  // ── SAX content handler + LexicalHandler ─────────────────────────────

  private class BuildingHandler extends DefaultHandler with LexicalHandler:
    // Stack of elements being built: (qname, attrs, accumulated children)
    private val stack   = scala.collection.mutable.ArrayDeque.empty[
      (Markup.QName, List[Markup.Attr], scala.collection.mutable.ListBuffer[Markup.Node])
    ]
    private val textBuf      = StringBuilder()
    private var inCData      = false
    private var xmlDecl: Option[Markup.XmlDecl] = None
    private var docType: Option[Markup.DocType]  = None

    // Nodes collected before root (leading PIs/comments) and after (trailing).
    private val leading  = scala.collection.mutable.ListBuffer.empty[Markup.Node]
    private val trailing = scala.collection.mutable.ListBuffer.empty[Markup.Node]
    private var rootElement: Option[Markup.Element] = None

    private def aboveRoot = rootElement.isDefined && stack.isEmpty

    override def setDocumentLocator(l: Locator): Unit = ()

    // SAX does not expose the XML declaration directly; synthesise default.
    override def startDocument(): Unit =
      xmlDecl = Some(Markup.XmlDecl("1.0"))

    override def startPrefixMapping(prefix: String, uri: String): Unit = ()
    override def endPrefixMapping(prefix: String): Unit = ()

    override def startElement(uri: String, localName: String, qName: String, attrs: Attributes): Unit =
      flushText()
      val name     = parseQName(qName, uri)
      val attrList = (0 until attrs.getLength).toList.map { i =>
        Markup.Attr(parseQName(attrs.getQName(i), attrs.getURI(i)), attrs.getValue(i))
      }
      stack.prepend((name, attrList, scala.collection.mutable.ListBuffer.empty))

    override def endElement(uri: String, localName: String, qName: String): Unit =
      flushText()
      if stack.nonEmpty then
        val (name, attrs, children) = stack.removeHead()
        val element = Markup.Element(name, attrs, children.toList)
        if stack.isEmpty then rootElement = Some(element)
        else stack.head._3 += element

    override def characters(ch: Array[Char], start: Int, length: Int): Unit =
      textBuf.appendAll(ch, start, length)

    override def ignorableWhitespace(ch: Array[Char], start: Int, length: Int): Unit =
      textBuf.appendAll(ch, start, length)

    override def processingInstruction(target: String, data: String): Unit =
      flushText()
      val pi = Markup.PI(target, Option(data).getOrElse(""))
      appendNode(pi)

    override def error(e: SAXParseException): Unit = throw e
    override def fatalError(e: SAXParseException): Unit = throw e
    override def warning(e: SAXParseException): Unit = ()

    // ── LexicalHandler ───────────────────────────────────────────────────

    override def startDTD(name: String, publicId: String, systemId: String): Unit =
      docType = Some(Markup.DocType(
        name     = name,
        publicId = Option(publicId).filter(_.nonEmpty),
        systemId = Option(systemId).filter(_.nonEmpty),
      ))

    override def endDTD(): Unit = ()

    override def startEntity(name: String): Unit = ()
    override def endEntity(name: String): Unit   = ()

    override def startCDATA(): Unit =
      flushText()
      inCData = true

    override def endCDATA(): Unit =
      if inCData then
        val text = textBuf.toString
        textBuf.clear()
        inCData = false
        if text.nonEmpty then
          val node = Markup.CData(text)
          if stack.nonEmpty then stack.head._3 += node
          // CDATA outside root is ignored (not well-formed XML anyway)

    override def comment(ch: Array[Char], start: Int, length: Int): Unit =
      flushText()
      val text = new String(ch, start, length)
      appendNode(Markup.Comment(text))

    // ── Internals ────────────────────────────────────────────────────────

    private def appendNode(node: Markup.Node): Unit =
      if stack.nonEmpty then stack.head._3 += node
      else if aboveRoot then trailing += node
      else leading += node

    private def flushText(): Unit =
      if textBuf.nonEmpty && !inCData then
        val text = textBuf.toString
        textBuf.clear()
        // Only emit text nodes that are non-whitespace or inside an element.
        if text.trim.nonEmpty || stack.nonEmpty then
          appendNode(Markup.Text(text))
      else if textBuf.nonEmpty && inCData then
        () // buffered for endCDATA
      else
        textBuf.clear()

    private def parseQName(qName: String, uri: String): Markup.QName =
      val ns = Option(uri).filter(_.nonEmpty)
      if qName != null && qName.contains(':') then
        val colon  = qName.indexOf(':')
        val prefix = qName.substring(0, colon)
        val local  = qName.substring(colon + 1)
        Markup.QName(Some(prefix), local, ns)
      else
        Markup.QName(None, Option(qName).getOrElse(""), ns)

    def result(): Option[Markup.Doc] =
      rootElement.map { root =>
        Markup.Doc(
          decl     = xmlDecl,
          docType  = docType,
          root     = root,
          trailing = trailing.toList,
        )
      }
