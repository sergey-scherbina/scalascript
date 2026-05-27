package scalascript.markup.node

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** Scala.js facades for `@xmldom/xmldom` v0.9.x.
 *
 *  Only the subset needed by [[NodeMarkupCodec]] is bound:
 *  - `DOMParser.parseFromString` for parsing.
 *  - `XMLSerializer.serializeToString` for serialization
 *    (unused in practice — we delegate to PureMarkupCodec — but bound
 *    for completeness).
 *  - Minimal DOM node / element / attribute types.
 *
 *  Install: `npm install @xmldom/xmldom` (or add to package.json). */
private[node] object XmldomFacades:

  @js.native
  @JSImport("@xmldom/xmldom", "DOMParser")
  class DOMParser extends js.Object:
    def parseFromString(str: String, mimeType: String): XmldomDocument = js.native

  @js.native
  @JSImport("@xmldom/xmldom", "XMLSerializer")
  class XMLSerializer extends js.Object:
    def serializeToString(node: XmldomNode): String = js.native

  @js.native
  trait XmldomNode extends js.Object:
    val nodeType: Int    = js.native
    val nodeName: String = js.native

  @js.native
  trait XmldomElement extends XmldomNode:
    val localName:    String              = js.native
    val prefix:       String              = js.native
    val namespaceURI: String              = js.native
    val childNodes:   XmldomNodeList      = js.native
    val attributes:   XmldomNamedNodeMap  = js.native

  @js.native
  trait XmldomDocument extends XmldomNode:
    val documentElement: XmldomElement  = js.native

  @js.native
  trait XmldomNodeList extends js.Object:
    val length: Int              = js.native
    def item(i: Int): XmldomNode = js.native

  @js.native
  trait XmldomAttr extends XmldomNode:
    val localName: String = js.native
    val prefix:    String = js.native
    val value:     String = js.native

  @js.native
  trait XmldomNamedNodeMap extends js.Object:
    val length: Int               = js.native
    def item(i: Int): XmldomAttr  = js.native
