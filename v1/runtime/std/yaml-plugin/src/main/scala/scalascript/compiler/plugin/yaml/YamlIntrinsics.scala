package scalascript.compiler.plugin.yaml

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginComputation, PluginNative, PluginValue}
import scalascript.parser.SimpleYaml

import scala.jdk.CollectionConverters.*

object YamlIntrinsics:

  private def native(f: List[Any] => PluginValue): NativeImpl =
    PluginNative.eval { (_, args) =>
      PluginComputation.pure(PluginValue.wrap(f(args.map(_.unwrap))))
    }

  // ── Any (from SimpleYaml) → YamlValue (as InstanceV) ─────────────────────

  private def fromAny(raw: Any): PluginValue = raw match
    case null =>
      PluginValue.instance("YNull", Map.empty)
    case b: java.lang.Boolean =>
      PluginValue.instance("YBool", Map("value" -> PluginValue.bool(b.booleanValue)))
    case i: java.lang.Integer =>
      PluginValue.instance("YNum", Map("value" -> PluginValue.double(i.doubleValue)))
    case l: java.lang.Long =>
      PluginValue.instance("YNum", Map("value" -> PluginValue.double(l.doubleValue)))
    case d: java.lang.Double =>
      PluginValue.instance("YNum", Map("value" -> PluginValue.double(d.doubleValue)))
    case f: java.lang.Float =>
      PluginValue.instance("YNum", Map("value" -> PluginValue.double(f.doubleValue)))
    case s: String =>
      PluginValue.instance("YStr", Map("value" -> PluginValue.string(s)))
    case m: java.util.Map[?, ?] =>
      val fields = m.asScala.map { case (k, v) =>
        PluginValue.string(k.toString) -> fromAny(v)
      }.toMap
      PluginValue.instance("YObj", Map("fields" -> PluginValue.mapOf(fields)))
    case lst: java.util.List[?] =>
      val items = lst.asScala.map(fromAny).toList
      PluginValue.instance("YArr", Map("items" -> PluginValue.list(items)))
    case other =>
      PluginValue.instance("YStr", Map("value" -> PluginValue.string(other.toString)))

  // ── YamlValue (InstanceV) → YAML string ──────────────────────────────────

  private def toYamlStr(v: Any, indent: Int = 0): String =
    val pad = " " * indent
    v match
      case PluginValue.Inst("YNull", _) => "null"
      case PluginValue.Inst("YBool", fields) =>
        fields.get("value").map {
          case PluginValue.Bool(b) => b.toString
          case other          => other.toString
        }.getOrElse("null")
      case PluginValue.Inst("YNum", fields) =>
        fields.get("value").map {
          case PluginValue.Dbl(d) =>
            if d == d.toLong.toDouble && !d.isInfinite && !d.isNaN then d.toLong.toString
            else d.toString
          case PluginValue.Num(n) => n.toString
          case other         => other.toString
        }.getOrElse("0")
      case PluginValue.Inst("YStr", fields) =>
        fields.get("value").map {
          case PluginValue.Str(s) => quoteYamlStr(s)
          case other            => quoteYamlStr(other.toString)
        }.getOrElse("''")
      case PluginValue.Inst("YArr", fields) =>
        fields.get("items") match
          case Some(PluginValue.Lst(items)) if items.isEmpty => "[]"
          case Some(PluginValue.Lst(items)) =>
            items.map { item =>
              val rendered = toYamlStr(item, indent + 2)
              if rendered.contains('\n') then s"$pad- |\n${rendered.split('\n').map(l => pad + "  " + l).mkString("\n")}"
              else s"$pad- $rendered"
            }.mkString("\n")
          case _ => "[]"
      case PluginValue.Inst("YObj", fields) =>
        fields.get("fields") match
          case Some(PluginValue.MapVal(m)) if m.isEmpty => "{}"
          case Some(PluginValue.MapVal(m)) =>
            m.toList.sortBy { case (k, _) => k match
              case PluginValue.Str(s) => s
              case other            => other.toString
            }.map { case (k, vv) =>
              val key = k match { case PluginValue.Str(s) => quoteYamlKey(s); case other => other.toString }
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
      case _ => PluginValue.instance("YNull", Map.empty)
    },

    QualifiedName("toYaml") -> native {
      case List(v) => PluginValue.string(toYamlStr(v) + "\n")
      case _                        => PluginValue.string("null\n")
    },

    // Helper: extract type tag ("YStr", "YNum", "YBool", "YNull", "YArr", "YObj")
    QualifiedName("yamlType") -> native {
      case List(PluginValue.Inst(tn, _)) => PluginValue.string(tn)
      case _                        => PluginValue.string("unknown")
    },

    // Helper: extract string value from YStr
    QualifiedName("yamlStr") -> native {
      case List(PluginValue.Inst("YStr", _vf)) =>
        _vf.getOrElse("value", PluginValue.nullV)
      case _ => PluginValue.nullV
    },

    // Helper: extract numeric value from YNum
    QualifiedName("yamlNum") -> native {
      case List(PluginValue.Inst("YNum", _vf)) =>
        _vf.getOrElse("value", PluginValue.double(0.0))
      case _ => PluginValue.double(0.0)
    },

    // Helper: extract bool from YBool
    QualifiedName("yamlBool") -> native {
      case List(PluginValue.Inst("YBool", _vf)) =>
        _vf.getOrElse("value", PluginValue.bool(false))
      case _ => PluginValue.bool(false)
    },

    // Helper: extract list from YArr
    QualifiedName("yamlArr") -> native {
      case List(PluginValue.Inst("YArr", _vf)) =>
        _vf.getOrElse("items", PluginValue.list(Nil))
      case _ => PluginValue.list(Nil)
    },

    // Helper: get a field from YObj by key
    QualifiedName("yamlGet") -> native {
      case List(PluginValue.Inst("YObj", _vf), key: String) =>
        _vf.get("fields") match
          case Some(PluginValue.MapVal(m)) =>
            m.getOrElse(PluginValue.string(key), PluginValue.instance("YNull", Map.empty))
          case _ => PluginValue.instance("YNull", Map.empty)
      case _ => PluginValue.instance("YNull", Map.empty)
    },

  )
