package scalascript.typeddata

final case class RowFieldSpec[A](
    name:    String,
    aliases: List[String] = Nil,
    default: Option[A] = None,
    key:     Boolean = false
)(using val codec: RowValueCodec[A]):
  def names: List[String] = name :: aliases

object RowFieldSpec:
  def required[A: RowValueCodec](name: String, aliases: String*): RowFieldSpec[A] =
    RowFieldSpec[A](name, aliases.toList, None)

  def withDefault[A: RowValueCodec](name: String, default: A, aliases: String*): RowFieldSpec[A] =
    RowFieldSpec[A](name, aliases.toList, Some(default))

  def key[A: RowValueCodec](name: String, aliases: String*): RowFieldSpec[A] =
    RowFieldSpec[A](name, aliases.toList, None, key = true)
