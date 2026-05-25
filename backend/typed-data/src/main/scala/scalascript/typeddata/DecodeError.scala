package scalascript.typeddata

final case class DecodeError(path: List[String], message: String):
  def at(field: String): DecodeError = copy(path = field :: path)

  def render: String =
    val renderedPath = if path.isEmpty then "$" else path.mkString("$.", ".", "")
    s"$renderedPath: $message"

object DecodeError:
  def apply(message: String): DecodeError = DecodeError(Nil, message)
