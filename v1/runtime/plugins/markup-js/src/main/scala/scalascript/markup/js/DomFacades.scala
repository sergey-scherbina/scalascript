package scalascript.markup.js

import scala.scalajs.js

/** Minimal Scala.js facades for the browser `DOMParser` and
 *  `XMLSerializer` globals.  We bind only the subset we actually need —
 *  `parseFromString` / `serializeToString` — so the facade compiles
 *  cleanly against scalajs-dom 2.x without introducing a version
 *  dependency on that library. */
private[js] object DomFacades:

  // ── DOMParser ────────────────────────────────────────────────────────────

  @js.native
  @js.annotation.JSGlobal("DOMParser")
  class DOMParser extends js.Object:
    def parseFromString(str: String, mimeType: String): DomDocument = js.native

  // ── XMLSerializer ────────────────────────────────────────────────────────

  @js.native
  @js.annotation.JSGlobal("XMLSerializer")
  class XMLSerializer extends js.Object:
    def serializeToString(node: DomNode): String = js.native

  // ── Minimal DOM node / element / document facades ────────────────────────

  @js.native
  trait DomNode extends js.Object:
    val nodeType: Int    = js.native
    val nodeName: String = js.native

  @js.native
  trait DomElement extends DomNode:
    val localName:   String         = js.native
    val prefix:      String         = js.native   // null if no prefix
    val namespaceURI: String        = js.native   // null if no ns
    val textContent: String         = js.native
    val childNodes:  DomNodeList    = js.native
    val attributes:  DomNamedNodeMap = js.native

  @js.native
  trait DomDocument extends DomNode:
    val documentElement: DomElement = js.native
    // parseError element (IE/Edge legacy — check for null before using)
    def getElementsByTagName(name: String): DomNodeList = js.native

  @js.native
  trait DomNodeList extends js.Object:
    val length: Int                  = js.native
    def item(i: Int): DomNode        = js.native

  @js.native
  trait DomAttr extends DomNode:
    val localName: String  = js.native
    val prefix:    String  = js.native
    val value:     String  = js.native

  @js.native
  trait DomNamedNodeMap extends js.Object:
    val length: Int               = js.native
    def item(i: Int): DomAttr     = js.native
