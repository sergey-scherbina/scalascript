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
      YamlTagEnvironment.decodePercentEscapes(rawPrefix) match
        case Left(message) => Left(s"invalid YAML tag prefix '$rawPrefix': $message")
        case Right(prefix) =>
          Right(copy(handles = handles + (handle -> prefix), declared = declared + handle))

  def expand(rawTag: String): Either[String, String] =
    if rawTag.startsWith("!<") then
      if !rawTag.endsWith(">") || rawTag.length < 4 then
        Left(s"invalid verbatim YAML tag '$rawTag'")
      else YamlTagEnvironment.decodePercentEscapes(rawTag.substring(2, rawTag.length - 1))
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
            YamlTagEnvironment.decodePercentEscapes(suffix) match
              case Left(message) => Left(message)
              case Right(decoded) => Right(prefix + decoded)

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

  private[yaml] def decodePercentEscapes(value: String): Either[String, String] =
    var result = ""
    var cursor = 0
    var error: Option[String] = None
    while cursor < value.length && error.isEmpty do
      if value.charAt(cursor) != '%' then
        val width =
          if
            isHighSurrogate(value.charAt(cursor)) &&
            cursor + 1 < value.length &&
            isLowSurrogate(value.charAt(cursor + 1))
          then 2
          else 1
        if isSurrogate(value.charAt(cursor)) && width == 1 then
          error = Some("unpaired UTF-16 surrogate")
        else
          result = result + value.substring(cursor, cursor + width)
          cursor += width
      else
        var bytes: Vector[Int] = Vector.empty
        while cursor < value.length && value.charAt(cursor) == '%' && error.isEmpty do
          if cursor + 2 >= value.length then error = Some("truncated percent escape")
          else
            val high = hexDigit(value.charAt(cursor + 1))
            val low = hexDigit(value.charAt(cursor + 2))
            if high < 0 || low < 0 then error = Some("non-hexadecimal percent escape")
            else
              bytes = bytes :+ (high * 16 + low)
              cursor += 3
        if error.isEmpty then
          decodeUtf8(bytes) match
            case Left(message) => error = Some(message)
            case Right(decoded) => result = result + decoded
    error match
      case Some(message) => Left(message)
      case None          => Right(result)

  private def decodeUtf8(bytes: Vector[Int]): Either[String, String] =
    var result = ""
    var index = 0
    var error: Option[String] = None
    while index < bytes.size && error.isEmpty do
      val first = bytes(index)
      val decoding =
        if first <= 0x7f then (1, 0, first)
        else if first >= 0xc2 && first <= 0xdf then (2, 0x80, first & 0x1f)
        else if first >= 0xe0 && first <= 0xef then (3, 0x800, first & 0x0f)
        else if first >= 0xf0 && first <= 0xf4 then (4, 0x10000, first & 0x07)
        else (0, 0, 0)
      val width = decoding._1
      val minimum = decoding._2
      val initial = decoding._3
      if width == 0 then error = Some("invalid UTF-8 leading byte")
      else if index + width > bytes.size then error = Some("truncated UTF-8 sequence")
      else
        var codePoint = initial
        var continuation = 1
        while continuation < width && error.isEmpty do
          val next = bytes(index + continuation)
          if next < 0x80 || next > 0xbf then error = Some("invalid UTF-8 continuation byte")
          else codePoint = (codePoint << 6) | (next & 0x3f)
          continuation += 1
        if error.isEmpty then
          if codePoint < minimum then error = Some("overlong UTF-8 sequence")
          else if codePoint >= 0xd800 && codePoint <= 0xdfff then
            error = Some("UTF-8 sequence decodes to a surrogate")
          else if codePoint > 0x10ffff then error = Some("UTF-8 code point is out of range")
          else
            result = result + codePointString(codePoint)
            index += width
    error match
      case Some(message) => Left(message)
      case None          => Right(result)

  private def words(value: String): Vector[String] =
    var result: Vector[String] = Vector.empty
    var cursor = 0
    while cursor < value.length do
      while cursor < value.length && isWhitespace(value.charAt(cursor)) do cursor += 1
      val start = cursor
      while cursor < value.length && !isWhitespace(value.charAt(cursor)) do cursor += 1
      if cursor > start then result = result :+ value.substring(start, cursor)
    result

  private def codePointString(value: Int): String =
    if value <= 0xffff then value.toChar.toString
    else
      val adjusted = value - 0x10000
      (((adjusted >>> 10) + 0xd800).toChar.toString) +
        (((adjusted & 0x3ff) + 0xdc00).toChar.toString)

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
  private def isHighSurrogate(value: Char): Boolean = value >= '\ud800' && value <= '\udbff'
  private def isLowSurrogate(value: Char): Boolean = value >= '\udc00' && value <= '\udfff'
  private def isSurrogate(value: Char): Boolean = value >= '\ud800' && value <= '\udfff'
