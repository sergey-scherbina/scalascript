package scalascript.compiler.plugin.yaml

import scalascript.backend.spi.*
import scalascript.interpreter.Value
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginComputation, PluginNative, PluginValue}
import scalascript.parser.SimpleYaml

import scala.jdk.CollectionConverters.*

object YamlIntrinsics:

  private def native(f: List[Any] => Value): NativeImpl =
    PluginNative.eval { (_, args) =>
      PluginComputation.pure(PluginValue.wrap(f(args.map(_.unwrap))))
    }

  // ── Any (from SimpleYaml) → YamlValue (as InstanceV) ─────────────────────

  private def fromAny(raw: Any): Value = raw match
    case null =>
      Value.InstanceV("YNull", Map.empty)
    case b: java.lang.Boolean =>
      Value.InstanceV("YBool", Map("value" -> Value.boolV(b.booleanValue)))
    case i: java.lang.Integer =>
      Value.InstanceV("YNum", Map("value" -> Value.DoubleV(i.doubleValue)))
    case l: java.lang.Long =>
      Value.InstanceV("YNum", Map("value" -> Value.DoubleV(l.doubleValue)))
    case d: java.lang.Double =>
      Value.InstanceV("YNum", Map("value" -> Value.DoubleV(d.doubleValue)))
    case f: java.lang.Float =>
      Value.InstanceV("YNum", Map("value" -> Value.DoubleV(f.doubleValue)))
    case s: String =>
      Value.InstanceV("YStr", Map("value" -> Value.StringV(s)))
    case m: java.util.Map[?, ?] =>
      val fields = m.asScala.map { case (k, v) =>
        Value.StringV(k.toString).asInstanceOf[Value] -> fromAny(v)
      }.toMap
      Value.InstanceV("YObj", Map("fields" -> Value.MapV(fields)))
    case lst: java.util.List[?] =>
      val items = lst.asScala.map(fromAny).toList
      Value.InstanceV("YArr", Map("items" -> Value.ListV(items)))
    case other =>
      Value.InstanceV("YStr", Map("value" -> Value.StringV(other.toString)))

  // ── YamlValue (InstanceV) → YAML string ──────────────────────────────────

  private def toYamlStr(v: Value, indent: Int = 0): String =
    val pad = " " * indent
    v match
      case Value.InstanceV("YNull", _) => "null"
      case Value.InstanceV("YBool", fields) =>
        fields.get("value").map {
          case Value.BoolV(b) => b.toString
          case other          => other.toString
        }.getOrElse("null")
      case Value.InstanceV("YNum", fields) =>
        fields.get("value").map {
          case Value.DoubleV(d) =>
            if d == d.toLong.toDouble && !d.isInfinite && !d.isNaN then d.toLong.toString
            else d.toString
          case Value.IntV(n) => n.toString
          case other         => other.toString
        }.getOrElse("0")
      case Value.InstanceV("YStr", fields) =>
        fields.get("value").map {
          case Value.StringV(s) => quoteYamlStr(s)
          case other            => quoteYamlStr(other.toString)
        }.getOrElse("''")
      case Value.InstanceV("YArr", fields) =>
        fields.get("items") match
          case Some(Value.ListV(items)) if items.isEmpty => "[]"
          case Some(Value.ListV(items)) =>
            items.map { item =>
              val rendered = toYamlStr(item, indent + 2)
              if rendered.contains('\n') then s"$pad- |\n${rendered.split('\n').map(l => pad + "  " + l).mkString("\n")}"
              else s"$pad- $rendered"
            }.mkString("\n")
          case _ => "[]"
      case Value.InstanceV("YObj", fields) =>
        fields.get("fields") match
          case Some(Value.MapV(m)) if m.isEmpty => "{}"
          case Some(Value.MapV(m)) =>
            m.toList.sortBy { case (k, _) => k match
              case Value.StringV(s) => s
              case other            => other.toString
            }.map { case (k, vv) =>
              val key = k match { case Value.StringV(s) => quoteYamlKey(s); case other => other.toString }
              val rendered = toYamlStr(vv, indent + 2)
              if rendered.contains('\n') then s"$pad$key:\n$rendered"
              else s"$pad$key: $rendered"
            }.mkString("\n")
          case _ => "{}"
      case other => quoteYamlStr(other.toString)

  private val needsQuoting = Set(':', '#', '[', ']', '{', '}', ',', '&', '*', '!', '|', '>', '\'', '"', '%', '@', '`')

  private def quoteYamlStr(s: String): String =
    if s.isEmpty then "''"
    else if s == "null" || s == "true" || s == "false" || s == "~" then s"'$s'"
    else if s.exists(needsQuoting.contains) || s.head == ' ' || s.last == ' '
         || s.contains(": ") || s.startsWith("- ") then
      "'" + s.replace("'", "''") + "'"
    else s

  private def quoteYamlKey(s: String): String =
    if s.isEmpty || s.exists(needsQuoting.contains) || s.head == ' ' || s.last == ' ' then
      "'" + s.replace("'", "''") + "'"
    else s

  // ── intrinsic table ───────────────────────────────────────────────────────

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    QualifiedName("parseYaml") -> native {
      case List(s: String) =>
        try fromAny(SimpleYaml.load[Any](s))
        catch case e: SimpleYaml.ParseError =>
          throw RuntimeException(s"YAML parse error: ${e.getMessage}", e)
      case _ => Value.InstanceV("YNull", Map.empty)
    },

    QualifiedName("toYaml") -> native {
      case List(v: Value.InstanceV) => Value.StringV(toYamlStr(v) + "\n")
      case List(other: Value)       => Value.StringV(toYamlStr(other) + "\n")
      case _                        => Value.StringV("null\n")
    },

    // Helper: extract type tag ("YStr", "YNum", "YBool", "YNull", "YArr", "YObj")
    QualifiedName("yamlType") -> native {
      case List(v: Value.InstanceV) => Value.StringV(v.typeName)
      case _                        => Value.StringV("unknown")
    },

    // Helper: extract string value from YStr
    QualifiedName("yamlStr") -> native {
      case List(v: Value.InstanceV) if v.typeName == "YStr" =>
        v.fields.getOrElse("value", Value.NullV)
      case _ => Value.NullV
    },

    // Helper: extract numeric value from YNum
    QualifiedName("yamlNum") -> native {
      case List(v: Value.InstanceV) if v.typeName == "YNum" =>
        v.fields.getOrElse("value", Value.DoubleV(0.0))
      case _ => Value.DoubleV(0.0)
    },

    // Helper: extract bool from YBool
    QualifiedName("yamlBool") -> native {
      case List(v: Value.InstanceV) if v.typeName == "YBool" =>
        v.fields.getOrElse("value", Value.False)
      case _ => Value.False
    },

    // Helper: extract list from YArr
    QualifiedName("yamlArr") -> native {
      case List(v: Value.InstanceV) if v.typeName == "YArr" =>
        v.fields.getOrElse("items", Value.ListV(Nil))
      case _ => Value.ListV(Nil)
    },

    // Helper: get a field from YObj by key
    QualifiedName("yamlGet") -> native {
      case List(v: Value.InstanceV, key: String) if v.typeName == "YObj" =>
        v.fields.get("fields") match
          case Some(Value.MapV(m)) =>
            m.getOrElse(Value.StringV(key), Value.InstanceV("YNull", Map.empty))
          case _ => Value.InstanceV("YNull", Map.empty)
      case _ => Value.InstanceV("YNull", Map.empty)
    },

  )
