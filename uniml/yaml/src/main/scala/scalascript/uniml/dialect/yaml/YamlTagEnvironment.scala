package scalascript.uniml.dialect.yaml

private[yaml] final case class YamlTagEnvironment(
    handles: Map[String, String],
    declared: Set[String],
):
  def register(handle: String, rawPrefix: String): Either[String, YamlTagEnvironment] =
    if !YamlTagEnvironment.validHandle(handle) then
      Left("invalid YAML tag handle")
    else if declared.contains(handle) then
      Left("duplicate YAML tag handle")
    else
      YamlPropertySyntax.validateTagPrefix(rawPrefix) match
        case Left(failure) =>
          Left(YamlTagEnvironment.syntaxMessage("invalid YAML tag prefix", failure))
        case Right(_) =>
          Right(copy(handles = handles + (handle -> rawPrefix), declared = declared + handle))

  def expand(rawTag: String): Either[String, String] =
    if rawTag == "!" then Right("!")
    else
      YamlPropertySyntax.validateTagSpelling(rawTag) match
        case Left(failure) =>
          Left(YamlTagEnvironment.syntaxMessage("invalid YAML tag", failure))
        case Right(_) if rawTag.startsWith("!<") =>
          val spelling = rawTag.substring(2, rawTag.length - 1)
          YamlTagEnvironment.validateEffective(spelling, verbatim = true)
        case Right(_) =>
          val namedEnd = rawTag.indexOf('!', 1)
          val (handle, suffix) =
            if rawTag.startsWith("!!") then "!!" -> rawTag.drop(2)
            else if namedEnd >= 0 then rawTag.take(namedEnd + 1) -> rawTag.drop(namedEnd + 1)
            else "!" -> rawTag.drop(1)
          handles.get(handle) match
            case None => Left("undefined YAML tag handle")
            case Some(prefix) =>
              if suffix.length > Int.MaxValue - prefix.length then
                Left("expanded YAML tag exceeds the platform string limit")
              else
                val effective = prefix + suffix
                YamlTagEnvironment.validateEffective(effective, verbatim = false)

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
    YamlPropertySyntax.validHandle(value)

  private def validateEffective(
      value: String,
      verbatim: Boolean,
  ): Either[String, String] =
    if value.startsWith("!") then
      if value.length == 1 then
        Left(
          if verbatim then "a verbatim local tag must contain a character after '!'"
          else "an expanded local tag must contain a suffix"
        )
      else
        YamlPropertySyntax.validateUriCharacters(value.substring(1)) match
          case Left(failure) => Left(syntaxMessage("invalid expanded local tag", failure))
          case Right(_)      => Right(value)
    else
      Rfc3986UriSyntax.validateUri(value) match
        case Left(failure) =>
          Left(s"invalid effective YAML tag URI at UTF-16 offset ${failure.offset}: ${failure.expected}")
        case Right(_) => Right(value)

  private def syntaxMessage(
      prefix: String,
      failure: YamlPropertyFailure,
  ): String =
    s"$prefix at UTF-16 offset ${failure.offset}: ${failure.message}"

  private def words(value: String): Vector[String] =
    def wsEnd(i: Int): Int = if i < value.length && isWhitespace(value.charAt(i)) then wsEnd(i + 1) else i
    def wordEnd(i: Int): Int = if i < value.length && !isWhitespace(value.charAt(i)) then wordEnd(i + 1) else i
    def walk(cursor: Int, result: Vector[String]): Vector[String] =
      if cursor >= value.length then result
      else
        val start = wsEnd(cursor)
        val stop = wordEnd(start)
        if stop > start then walk(stop, result :+ value.substring(start, stop))
        else walk(stop, result)
    walk(0, Vector.empty)

  private def isWhitespace(value: Char): Boolean = value == ' ' || value == '\t'
