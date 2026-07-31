package scalascript.markup

/** Common AST for tree-structured markup: XML 1.0, HTML5, SVG.
 *  All four ScalaScript surfaces (xml"..." interpolator, fenced xml blocks,
 *  .xml config files, element-literal expressions) share this model. */
object Markup:

  sealed trait Node

  case class Doc(
    decl:     Option[XmlDecl]  = None,
    docType:  Option[DocType]  = None,
    root:     Element,
    trailing: List[Node]       = Nil,    // PIs/comments after root
  ) extends Node

  case class Element(
    name:     QName,
    attrs:    List[Attr] = Nil,
    children: List[Node] = Nil,
  ) extends Node

  case class Attr(name: QName, value: String)
  case class Text(chars: String)              extends Node
  case class CData(chars: String)             extends Node
  case class PI(target: String, data: String) extends Node
  case class Comment(text: String)            extends Node

  case class DocType(name: String, publicId: Option[String], systemId: Option[String])

  case class XmlDecl(
    version:    String,
    encoding:   Option[String]  = None,
    standalone: Option[Boolean] = None,
  )

  /** Qualified name: optional namespace prefix + local name.
   *  `namespace` is populated by the codec after prefix resolution. */
  case class QName(
    prefix:    Option[String] = None,
    localName: String,
    namespace: Option[String] = None,
  ):
    def toXml: String = prefix.fold(localName)(p => s"$p:$localName")

  object QName:
    def local(name: String): QName = QName(None, name, None)
    def prefixed(prefix: String, local: String): QName = QName(Some(prefix), local, None)

  /** Opt-out of XML escaping.  The serializer emits `chars` verbatim.
   *  Caller is responsible for well-formedness. */
  case class Raw(chars: String) extends Node

  def raw(chars: String): Raw = Raw(chars)
