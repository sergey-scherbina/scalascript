package scalascript.uniml.dialect.yaml

private[yaml] final case class YamlTagEnvironment(
    handles: Map[String, String],
    declared: Set[String],
):
  def register(handle: String, rawPrefix: String): Either[String, YamlTagEnvironment] =
    if !YamlTagEnvironment.validHandle(handle) then
      Left(s"invalid YAML tag handle '$handle'")
    else if declared.contains(handle) then
      Left(s"duplicate YAML tag handle '$handle'")
    else
      YamlTagEnvironment.validatePercentEscapes(rawPrefix) match
        case Left(message) => Left(s"invalid YAML tag prefix '$rawPrefix': $message")
        case Right(_) =>
          Right(copy(handles = handles + (handle -> rawPrefix), declared = declared + handle))

  def expand(rawTag: String): Either[String, String] =
    if rawTag == "!" then Right("!")
    else if rawTag.startsWith("!<") then
      if !rawTag.endsWith(">") || rawTag.length < 4 then
        Left(s"invalid verbatim YAML tag '$rawTag'")
      else
        val spelling = rawTag.substring(2, rawTag.length - 1)
        YamlTagEnvironment.validatePercentEscapes(spelling).map(_ => spelling)
    else if !rawTag.startsWith("!") then Left(s"invalid YAML tag '$rawTag'")
    else
      val namedEnd = rawTag.indexOf('!', 1)
      val (handle, suffix) =
        if rawTag.startsWith("!!") then "!!" -> rawTag.drop(2)
        else if namedEnd >= 0 then rawTag.take(namedEnd + 1) -> rawTag.drop(namedEnd + 1)
        else "!" -> rawTag.drop(1)
      if suffix.isEmpty && rawTag != "!" then Left(s"empty suffix in YAML tag '$rawTag'")
      else
        handles.get(handle) match
          case None => Left(s"undefined YAML tag handle '$handle'")
          case Some(prefix) =>
            YamlTagEnvironment.validatePercentEscapes(suffix) match
              case Left(message) => Left(message)
              case Right(_)      => Right(prefix + suffix)

private[yaml] object YamlTagEnvironment:
  val defaults: YamlTagEnvironment = YamlTagEnvironment(
    handles = Map(
      "!" -> "!",
      "!!" -> "tag:yaml.org,2002:",
    ),
    declared = Set.empty,
  )

  def directiveParts(value: String): Option[(String, String)] =
    val parts = words(value)
    if parts.size == 2 then Some(parts.head -> parts(1)) else None

  private[yaml] def validHandle(value: String): Boolean =
    if value == "!" || value == "!!" then true
    else
      value.length >= 3 &&
        value.head == '!' &&
        value.last == '!' &&
        value.substring(1, value.length - 1).forall(isHandleChar)

  private def validatePercentEscapes(value: String): Either[String, Unit] =
    var cursor = 0
    var error: Option[String] = None
    while cursor < value.length && error.isEmpty do
      if value.charAt(cursor) != '%' then cursor += 1
      else if cursor + 2 >= value.length then error = Some("truncated percent escape")
      else
        val high = hexDigit(value.charAt(cursor + 1))
        val low = hexDigit(value.charAt(cursor + 2))
        if high < 0 || low < 0 then error = Some("non-hexadecimal percent escape")
        else cursor += 3
    error match
      case Some(message) => Left(message)
      case None          => Right(())

  private def words(value: String): Vector[String] =
    var result: Vector[String] = Vector.empty
    var cursor = 0
    while cursor < value.length do
      while cursor < value.length && isWhitespace(value.charAt(cursor)) do cursor += 1
      val start = cursor
      while cursor < value.length && !isWhitespace(value.charAt(cursor)) do cursor += 1
      if cursor > start then result = result :+ value.substring(start, cursor)
    result

  private def hexDigit(value: Char): Int =
    if value >= '0' && value <= '9' then value - '0'
    else if value >= 'a' && value <= 'f' then value - 'a' + 10
    else if value >= 'A' && value <= 'F' then value - 'A' + 10
    else -1

  private def isHandleChar(value: Char): Boolean =
    (value >= '0' && value <= '9') ||
      (value >= 'a' && value <= 'z') ||
      (value >= 'A' && value <= 'Z') ||
      value == '-'

  private def isWhitespace(value: Char): Boolean = value == ' ' || value == '\t'
