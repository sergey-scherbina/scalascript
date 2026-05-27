package scalascript.markup

/** Parser / serializer SPI.  One implementation per backend.
 *  JVM uses javax.xml.parsers; JS uses DOMParser; Native uses PureMarkupCodec.
 *  Backends that don't ship a codec cannot use xml"..." or xml fenced blocks. */
trait MarkupCodec:
  def id: String

  def parse(src: String, dialect: Dialect = Dialect.Xml1_0): Either[ParseError, Markup.Doc]

  def serialize(doc: Markup.Doc, opts: SerializeOpts = SerializeOpts.default): String

  /** Validate against an XSD schema string.  JVM only — other backends
   *  throw UnsupportedOperationException. */
  def validate(doc: Markup.Doc, xsd: String): List[ValidationError] =
    throw UnsupportedOperationException(s"XSD validation not supported by codec '$id'")

object MarkupCodec:
  private var _default: MarkupCodec = PureMarkupCodec

  def default: MarkupCodec = _default

  /** Override the default codec (used by JVM backend at startup). */
  def setDefault(codec: MarkupCodec): Unit = _default = codec

  def named(id: String): MarkupCodec = id match
    case "pure" => PureMarkupCodec
    case other  => throw new NoSuchElementException(s"No MarkupCodec registered for id '$other'")

enum Dialect:
  case Xml1_0, Xml1_1, Html5, Svg

case class ParseError(message: String, line: Int, column: Int) extends RuntimeException(
  s"XML parse error at $line:$column — $message"
)

case class ValidationError(message: String, line: Int, column: Int)

case class SerializeOpts(
  pretty:      Boolean = false,
  indent:      String  = "  ",
  omitXmlDecl: Boolean = false,
)
object SerializeOpts:
  val default: SerializeOpts = SerializeOpts()
  val pretty:  SerializeOpts = SerializeOpts(pretty = true)
