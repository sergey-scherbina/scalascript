package scalascript.compiler.plugin.request

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginError, PluginNative, PluginValue}
import scalascript.plugin.api.PluginValue.{Str, Lst}

object RequestIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    QualifiedName("requireString") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(req, name: String) =>
          reqFieldOf(reqAnyToValue(req), name) match
            case Some(s) => s
            case None    => ctx.validationRecord(name, s"missing field: $name", "")
        case _ => PluginError.raise("requireString(req, name)")
    },

    QualifiedName("optionalString") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(req, name: String) =>
          PluginValue.option(reqFieldOf(reqAnyToValue(req), name).map(PluginValue.string))
        case _ => PluginError.raise("optionalString(req, name)")
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
        case _ => PluginError.raise("requireInt(req, name)")
    },

    QualifiedName("optionalInt") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(req, name: String) =>
          val parsed = reqFieldOf(reqAnyToValue(req), name).flatMap { s =>
            try Some(PluginValue.int(s.toLong))
            catch case _: NumberFormatException => None
          }
          PluginValue.option(parsed)
        case _ => PluginError.raise("optionalInt(req, name)")
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
        case _ => PluginError.raise("requireDouble(req, name)")
    },

    QualifiedName("optionalDouble") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(req, name: String) =>
          val parsed = reqFieldOf(reqAnyToValue(req), name).flatMap { s =>
            try Some(PluginValue.double(s.toDouble))
            catch case _: NumberFormatException => None
          }
          PluginValue.option(parsed)
        case _ => PluginError.raise("optionalDouble(req, name)")
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
        case _ => PluginError.raise("requireBool(req, name)")
    },

    QualifiedName("optionalBool") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(req, name: String) =>
          val parsed = reqFieldOf(reqAnyToValue(req), name).flatMap { s =>
            s.toLowerCase match
              case "true"  | "1" | "yes" | "on"  => Some(PluginValue.bool(true))
              case "false" | "0" | "no"  | "off" => Some(PluginValue.bool(false))
              case _ => None
          }
          PluginValue.option(parsed)
        case _ => PluginError.raise("optionalBool(req, name)")
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
        case _ => PluginError.raise("requireRange(req, name, min, max)")
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
        case _ => PluginError.raise("requireRangeDouble(req, name, min: Double, max: Double)")
    },

    QualifiedName("requireOneOf") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(req, name: String, Lst(opts)) =>
          val allowed = opts.collect { case Str(s) => s }
          reqFieldOf(reqAnyToValue(req), name) match
            case Some(s) if allowed.contains(s) => s
            case Some(s) =>
              ctx.validationRecord(name,
                s"invalid value '$s' for field: $name (expected one of: ${allowed.mkString(", ")})",
                allowed.headOption.getOrElse(""))
            case None =>
              ctx.validationRecord(name, s"missing field: $name",
                allowed.headOption.getOrElse(""))
        case _ => PluginError.raise("requireOneOf(req, name, options: List[String])")
    },

  )

  private def reqFieldOf(req: PluginValue, name: String): Option[String] =
    def lookup(field: String): Option[String] =
      req.asInstance.flatMap { case (_, fields) =>
        fields.get(field).flatMap(_.asMap).flatMap { m =>
          m.get(PluginValue.string(name)).flatMap(_.asString)
        }
      }
    lookup("form").orElse(lookup("query"))

  private def reqAnyToValue(a: Any): PluginValue = a match
    case n: Long    => PluginValue.int(n)
    case i: Int     => PluginValue.int(i.toLong)
    case d: Double  => PluginValue.double(d)
    case s: String  => PluginValue.string(s)
    case b: Boolean => PluginValue.bool(b)
    case ()         => PluginValue.unit
    case other if PluginValue.isRuntimeValue(other) => PluginValue.wrap(other)
    case other      => PluginValue.string(other.toString)
