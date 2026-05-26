package scalascript.typeddata

final case class ObjectValue(fields: Map[String, JsonValue]):
  def json: JsonValue = JsonValue.Obj(fields)

object ObjectValue:
  def obj(fields: (String, JsonValue)*): ObjectValue =
    ObjectValue(fields.toMap)
