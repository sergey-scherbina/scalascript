package scalascript.config

import scalascript.markup.{Markup, PureMarkupCodec}

import scala.collection.immutable.Map as IMap

/** Parses an XML document into a [[ConfigValue]] tree.
 *
 *  Mapping rules:
 *
 *  - Root element's child elements → top-level keys.
 *  - Element with only text content → `ConfigValue.Str`.
 *  - Element with child elements → `ConfigValue.Map` (nested object).
 *  - Element with both text and child elements → text is ignored; child
 *    elements win (keeps the mapping simple and predictable).
 *  - Multiple child elements with the same tag name → `ConfigValue.Lst`.
 *  - Attributes → nested `_attrs` sub-object with `@name` keys.
 *  - Empty element (no text, no children, no attrs) → `ConfigValue.Str("")`.
 *
 *  Example:
 *  {{{
 *    <config>
 *      <database host="localhost" port="5432">
 *        <name>mydb</name>
 *      </database>
 *      <debug>true</debug>
 *    </config>
 *  }}}
 *  becomes:
 *  {{{
 *    Map(
 *      "database" -> Map(
 *        "_attrs"  -> Map("@host" -> Str("localhost"), "@port" -> Str("5432")),
 *        "name"    -> Str("mydb"),
 *      ),
 *      "debug" -> Str("true"),
 *    )
 *  }}}
 */
object XmlConfigParser:

  def parse(content: String): Either[ConfigError, ConfigValue] =
    PureMarkupCodec.parse(content) match
      case Left(e)    => Left(ConfigError.ParseError(e.getMessage))
      case Right(doc) => Right(elementToValue(doc.root))

  /** Convert a [[Markup.Element]] to a [[ConfigValue]]. */
  private def elementToValue(elem: Markup.Element): ConfigValue =
    val childElems = elem.children.collect { case e: Markup.Element => e }
    if childElems.isEmpty then
      // Leaf element — text content or empty
      val text = elem.children.collect {
        case Markup.Text(t)  => t
        case Markup.CData(t) => t
      }.mkString
      val base: ConfigValue = ConfigValue.Str(text.trim)
      if elem.attrs.isEmpty then base
      else
        ConfigValue.Map(IMap(
          "_attrs" -> attrsToValue(elem.attrs),
        ) + ("_text" -> base))
    else
      // Container element — child elements become map keys
      val grouped = childElems.groupBy(_.name.localName)
      val children: IMap[String, ConfigValue] = grouped.map { (key, elems) =>
        if elems.size == 1 then key -> elementToValue(elems.head)
        else key -> ConfigValue.Lst(elems.map(elementToValue).toList)
      }
      val withAttrs: IMap[String, ConfigValue] =
        if elem.attrs.isEmpty then children
        else children + ("_attrs" -> attrsToValue(elem.attrs))
      ConfigValue.Map(withAttrs)

  private def attrsToValue(attrs: List[Markup.Attr]): ConfigValue =
    ConfigValue.Map(attrs.map(a => s"@${a.name.localName}" -> ConfigValue.Str(a.value)).toMap)
