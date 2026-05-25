package scalascript.typeddata

enum RowValue:
  case Null
  case Bool(value: Boolean)
  case Num(value: BigDecimal)
  case Str(value: String)

object RowValue:
  def kind(value: RowValue): String = value match
    case Null => "null"
    case Bool(_) => "boolean"
    case Num(_) => "number"
    case Str(_) => "string"
