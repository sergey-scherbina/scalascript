package scalascript.compiler.plugin.request

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.interpreter.{Value, InterpretError}
import scalascript.plugin.api.PluginNative

object RequestIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    QualifiedName("requireString") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(req, name: String) =>
          reqFieldOf(reqAnyToValue(req), name) match
            case Some(s) => s
            case None    => ctx.validationRecord(name, s"missing field: $name", "")
        case _ => throw InterpretError("requireString(req, name)")
    },

    QualifiedName("optionalString") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(req, name: String) =>
          Value.OptionV(reqFieldOf(reqAnyToValue(req), name).map(Value.StringV(_)))
        case _ => throw InterpretError("optionalString(req, name)")
    },

    QualifiedName("requireInt") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(req, name: String) =>
          reqFieldOf(reqAnyToValue(req), name) match
            case Some(s) =>
              try s.toLong
              catch case _: NumberFormatException =>
                ctx.validationRecord(name, s"invalid integer for field: $name", 0L)
            case None =>
              ctx.validationRecord(name, s"missing field: $name", 0L)
        case _ => throw InterpretError("requireInt(req, name)")
    },

    QualifiedName("optionalInt") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(req, name: String) =>
          val parsed = reqFieldOf(reqAnyToValue(req), name).flatMap { s =>
            try Some(Value.intV(s.toLong))
            catch case _: NumberFormatException => None
          }
          Value.OptionV(parsed)
        case _ => throw InterpretError("optionalInt(req, name)")
    },

    QualifiedName("requireDouble") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(req, name: String) =>
          reqFieldOf(reqAnyToValue(req), name) match
            case Some(s) =>
              try s.toDouble
              catch case _: NumberFormatException =>
                ctx.validationRecord(name, s"invalid number for field: $name", 0.0)
            case None =>
              ctx.validationRecord(name, s"missing field: $name", 0.0)
        case _ => throw InterpretError("requireDouble(req, name)")
    },

    QualifiedName("optionalDouble") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(req, name: String) =>
          val parsed = reqFieldOf(reqAnyToValue(req), name).flatMap { s =>
            try Some(Value.doubleV(s.toDouble))
            catch case _: NumberFormatException => None
          }
          Value.OptionV(parsed)
        case _ => throw InterpretError("optionalDouble(req, name)")
    },

    QualifiedName("requireBool") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(req, name: String) =>
          reqFieldOf(reqAnyToValue(req), name) match
            case Some(s) => s.toLowerCase match
              case "true"  | "1" | "yes" | "on"  => true
              case "false" | "0" | "no"  | "off" => false
              case _ => ctx.validationRecord(name, s"invalid boolean for field: $name", false)
            case None =>
              ctx.validationRecord(name, s"missing field: $name", false)
        case _ => throw InterpretError("requireBool(req, name)")
    },

    QualifiedName("optionalBool") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(req, name: String) =>
          val parsed = reqFieldOf(reqAnyToValue(req), name).flatMap { s =>
            s.toLowerCase match
              case "true"  | "1" | "yes" | "on"  => Some(Value.True)
              case "false" | "0" | "no"  | "off" => Some(Value.False)
              case _ => None
          }
          Value.OptionV(parsed)
        case _ => throw InterpretError("optionalBool(req, name)")
    },

    QualifiedName("requireRange") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(req, name: String, min: Long, max: Long) =>
          reqFieldOf(reqAnyToValue(req), name) match
            case Some(s) =>
              try
                val n = s.toLong
                if n < min || n > max then
                  ctx.validationRecord(name, s"out of range [$min..$max] for field: $name", min)
                else n
              catch case _: NumberFormatException =>
                ctx.validationRecord(name, s"invalid integer for field: $name", min)
            case None =>
              ctx.validationRecord(name, s"missing field: $name", min)
        case _ => throw InterpretError("requireRange(req, name, min, max)")
    },

    QualifiedName("requireRangeDouble") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(req, name: String, min: Double, max: Double) =>
          reqFieldOf(reqAnyToValue(req), name) match
            case Some(s) =>
              try
                val n = s.toDouble
                if n < min || n > max then
                  ctx.validationRecord(name, s"out of range [$min..$max] for field: $name", min)
                else n
              catch case _: NumberFormatException =>
                ctx.validationRecord(name, s"invalid number for field: $name", min)
            case None =>
              ctx.validationRecord(name, s"missing field: $name", min)
        case _ => throw InterpretError("requireRangeDouble(req, name, min: Double, max: Double)")
    },

    QualifiedName("requireOneOf") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(req, name: String, Value.ListV(opts)) =>
          val allowed = opts.collect { case Value.StringV(s) => s }
          reqFieldOf(reqAnyToValue(req), name) match
            case Some(s) if allowed.contains(s) => s
            case Some(s) =>
              ctx.validationRecord(name,
                s"invalid value '$s' for field: $name (expected one of: ${allowed.mkString(", ")})",
                allowed.headOption.getOrElse(""))
            case None =>
              ctx.validationRecord(name, s"missing field: $name",
                allowed.headOption.getOrElse(""))
        case _ => throw InterpretError("requireOneOf(req, name, options: List[String])")
    },

  )

  private def reqFieldOf(req: Value, name: String): Option[String] =
    def lookup(field: String): Option[String] = req match
      case Value.InstanceV(_, fields) => fields.get(field) match
        case Some(Value.MapV(m)) => m.get(Value.StringV(name)) match
          case Some(Value.StringV(s)) => Some(s)
          case _                      => None
        case _ => None
      case _ => None
    lookup("form").orElse(lookup("query"))

  private def reqAnyToValue(a: Any): Value = a match
    case n: Long    => Value.intV(n)
    case i: Int     => Value.intV(i.toLong)
    case d: Double  => Value.doubleV(d)
    case s: String  => Value.StringV(s)
    case b: Boolean => Value.boolV(b)
    case ()         => Value.UnitV
    case v: Value   => v
    case other      => Value.StringV(other.toString)
