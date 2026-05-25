package scalascript.typeddata

enum JsonValue:
  case Null
  case Bool(value: Boolean)
  case Num(value: BigDecimal)
  case Str(value: String)
  case Arr(values: Vector[JsonValue])
  case Obj(fields: Map[String, JsonValue])

  def field(name: String): Either[DecodeError, JsonValue] =
    this match
      case JsonValue.Obj(fields) =>
        fields.get(name).toRight(DecodeError(s"missing field '$name'").at(name))
      case other =>
        Left(DecodeError(s"expected JSON object, got ${JsonValue.kind(other)}"))

object JsonValue:
  def obj(fields: (String, JsonValue)*): JsonValue = Obj(fields.toMap)
  def arr(values: JsonValue*): JsonValue = Arr(values.toVector)

  def kind(value: JsonValue): String = value match
    case Null   => "null"
    case Bool(_) => "boolean"
    case Num(_) => "number"
    case Str(_) => "string"
    case Arr(_) => "array"
    case Obj(_) => "object"
