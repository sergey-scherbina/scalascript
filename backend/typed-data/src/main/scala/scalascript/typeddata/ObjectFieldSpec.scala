package scalascript.typeddata

final case class ObjectFieldSpec[A](
    name:    String,
    aliases: List[String] = Nil,
    default: Option[A] = None,
    key:     Boolean = false
)(using val codec: JsonCodec[A]):
  def names: List[String] = name :: aliases

object ObjectFieldSpec:
  def required[A: JsonCodec](name: String, aliases: String*): ObjectFieldSpec[A] =
    ObjectFieldSpec[A](name, aliases.toList, None)

  def withDefault[A: JsonCodec](name: String, default: A, aliases: String*): ObjectFieldSpec[A] =
    ObjectFieldSpec[A](name, aliases.toList, Some(default))

  def key[A: JsonCodec](name: String, aliases: String*): ObjectFieldSpec[A] =
    ObjectFieldSpec[A](name, aliases.toList, None, key = true)
