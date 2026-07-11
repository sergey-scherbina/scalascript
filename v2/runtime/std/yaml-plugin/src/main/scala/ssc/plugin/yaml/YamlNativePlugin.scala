package ssc.plugin.yaml

import scala.jdk.CollectionConverters.*
import ssc.Value
import ssc.plugin.{NativePlugin, NativePluginContext}
import scalascript.parser.SimpleYaml

/** Core-free std.yaml parser, accessors, and deterministic serializer. */
final class YamlNativePlugin extends NativePlugin:
  def id: String = "45-yaml"

  private val yNull = Value.DataV("YNull", Vector.empty)

  private def list(values: IterableOnce[Value]): Value =
    values.iterator.toList.foldRight[Value](Value.DataV("Nil", Vector.empty)) { (value, rest) =>
      Value.DataV("Cons", Vector(value, rest))
    }

  private def unlist(value: Value): List[Value] =
    val out = collection.mutable.ListBuffer.empty[Value]
    var current = value
    var done = false
    while !done do
      current match
        case Value.DataV("Cons", Seq(head, tail)) => out += head; current = tail
        case Value.DataV("Nil", _) => done = true
        case _ => throw new IllegalArgumentException("YArr.items must be a List")
    out.toList

  private def fromAny(raw: Any): Value = raw match
    case null => yNull
    case value: java.lang.Boolean => Value.DataV("YBool", Vector(Value.BoolV(value)))
    case value: java.lang.Integer => Value.DataV("YNum", Vector(Value.FloatV(value.toDouble)))
    case value: java.lang.Long => Value.DataV("YNum", Vector(Value.FloatV(value.toDouble)))
    case value: java.lang.Double => Value.DataV("YNum", Vector(Value.FloatV(value)))
    case value: java.lang.Float => Value.DataV("YNum", Vector(Value.FloatV(value.toDouble)))
    case value: String => Value.DataV("YStr", Vector(Value.StrV(value)))
    case values: java.util.List[?] =>
      Value.DataV("YArr", Vector(list(values.asScala.map(fromAny))))
    case values: java.util.Map[?, ?] =>
      val fields = values.asScala.iterator.map { case (key, value) =>
        Value.StrV(key.toString) -> fromAny(value)
      }
      Value.DataV("YObj", Vector(Value.MapV.from(fields)))
    case other => Value.DataV("YStr", Vector(Value.StrV(other.toString)))

  private val needsQuoting = Set(':', '#', '[', ']', '{', '}', ',', '&', '*', '!', '|', '>', '\'', '"', '%', '@', '`')

  private def doubleQuote(value: String): String =
    "\"" + value
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t") + "\""

  private def scalarLike(value: String): Boolean =
    value == "~" || value == "null" || value.equalsIgnoreCase("true") ||
      value.equalsIgnoreCase("false") || value.toIntOption.nonEmpty ||
      value.toDoubleOption.nonEmpty

  private def quote(value: String): String =
    if value.isEmpty then "''"
    else if value.exists(ch => ch == '\n' || ch == '\r' || ch == '\t') then doubleQuote(value)
    else if scalarLike(value) then s"'$value'"
    else if value.exists(needsQuoting.contains) || value.head == ' ' || value.last == ' ' ||
        value.contains(": ") || value.startsWith("- ") then
      "'" + value.replace("'", "''") + "'"
    else value

  private def quoteKey(value: String): String =
    if value.isEmpty || value.exists(needsQuoting.contains) || value.head == ' ' || value.last == ' ' then
      "'" + value.replace("'", "''") + "'"
    else value

  private def isInlineValue(value: Value): Boolean = value match
    case Value.DataV("YNull" | "YBool" | "YNum" | "YStr", _) => true
    case Value.DataV("YArr", Seq(items)) => unlist(items).isEmpty
    case Value.DataV("YObj", Seq(Value.MapV(fields))) => fields.isEmpty
    case _ => true

  private def renderInline(value: Value): String = value match
    case Value.DataV("YNull", _) => "null"
    case Value.DataV("YBool", Seq(Value.BoolV(boolean))) => boolean.toString
    case Value.DataV("YNum", Seq(Value.FloatV(number))) =>
      if number == number.toLong.toDouble && !number.isInfinite && !number.isNaN then number.toLong.toString
      else number.toString
    case Value.DataV("YStr", Seq(Value.StrV(text))) => quote(text)
    case Value.DataV("YArr", Seq(items)) if unlist(items).isEmpty => "[]"
    case Value.DataV("YObj", Seq(Value.MapV(fields))) if fields.isEmpty => "{}"
    case _ => "null"

  private def render(value: Value, indent: Int = 0): String =
    val pad = " " * indent
    value match
      case Value.DataV("YArr", Seq(items)) =>
        unlist(items) match
          case Nil => "[]"
          case values => values.map { item =>
            if isInlineValue(item) then s"$pad- ${renderInline(item)}"
            else s"$pad-\n${render(item, indent + 2)}"
          }.mkString("\n")
      case Value.DataV("YObj", Seq(Value.MapV(fields))) =>
        if fields.isEmpty then "{}"
        else fields.iterator.collect { case (Value.StrV(key), item) => key -> item }.toList
          .sortBy(_._1).map { case (key, item) =>
            if isInlineValue(item) then s"$pad${quoteKey(key)}: ${renderInline(item)}"
            else s"$pad${quoteKey(key)}:\n${render(item, indent + 2)}"
          }.mkString("\n")
      case _ => renderInline(value)

  def install(context: NativePluginContext): Unit =
    context.registerGlobal("parseYaml", 1) {
      case Value.StrV(source) :: Nil =>
        try fromAny(SimpleYaml.load[Any](source))
        catch case error: SimpleYaml.ParseError =>
          throw new IllegalArgumentException(s"YAML parse error: ${error.getMessage}", error)
      case _ => throw new IllegalArgumentException("parseYaml(text)")
    }
    context.registerGlobal("toYaml", 1) {
      case value :: Nil => Value.StrV(render(value) + "\n")
      case _ => throw new IllegalArgumentException("toYaml(value)")
    }
    context.registerGlobal("yamlType", 1) {
      case Value.DataV(tag @ ("YStr" | "YNum" | "YBool" | "YNull" | "YArr" | "YObj"), _) :: Nil => Value.StrV(tag)
      case _ => Value.StrV("unknown")
    }
    context.registerGlobal("yamlStr", 1) {
      case Value.DataV("YStr", Seq(value: Value.StrV)) :: Nil => value
      case _ => Value.UnitV
    }
    context.registerGlobal("yamlNum", 1) {
      case Value.DataV("YNum", Seq(value: Value.FloatV)) :: Nil => value
      case _ => Value.FloatV(0.0)
    }
    context.registerGlobal("yamlBool", 1) {
      case Value.DataV("YBool", Seq(value: Value.BoolV)) :: Nil => value
      case _ => Value.BoolV(false)
    }
    context.registerGlobal("yamlArr", 1) {
      case Value.DataV("YArr", Seq(items)) :: Nil => items
      case _ => Value.DataV("Nil", Vector.empty)
    }
    context.registerGlobal("yamlGet", 2) {
      case Value.DataV("YObj", Seq(Value.MapV(fields))) :: Value.StrV(key) :: Nil =>
        fields.getOrElse(Value.StrV(key), yNull)
      case _ => yNull
    }
    context.registerGlobal("__yamlSection__", 1) {
      case Value.StrV(source) :: Nil =>
        V2YamlSection.parse(source)
      case _ => throw new IllegalArgumentException("__yamlSection__(text)")
    }

    context.registerFields("YStr", Vector("value"))
    context.registerFields("YNum", Vector("value"))
    context.registerFields("YBool", Vector("value"))
    context.registerFields("YArr", Vector("items"))
    context.registerFields("YObj", Vector("fields"))

  private object V2YamlSection:
    def parse(source: String): Value =
      try fromAny(SimpleYaml.load[Any](source))
      catch case error: SimpleYaml.ParseError =>
        throw new IllegalArgumentException(s"YAML parse error: ${error.getMessage}", error)
