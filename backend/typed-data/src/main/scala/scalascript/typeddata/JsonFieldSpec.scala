package scalascript.typeddata

final case class JsonFieldSpec[A](
    name:    String,
    aliases: List[String] = Nil,
    default: Option[A] = None
)(using val codec: JsonCodec[A]):
  def names: List[String] = name :: aliases

object JsonFieldSpec:
  def required[A: JsonCodec](name: String, aliases: String*): JsonFieldSpec[A] =
    JsonFieldSpec[A](name, aliases.toList, None)

  def withDefault[A: JsonCodec](name: String, default: A, aliases: String*): JsonFieldSpec[A] =
    JsonFieldSpec[A](name, aliases.toList, Some(default))
