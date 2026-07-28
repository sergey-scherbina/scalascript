package scalascript.uniml.dialect.yaml

import scalascript.uniml.Unicode

private[yaml] enum YamlPropertyKind:
  case Tag, Anchor, Alias

private[yaml] enum YamlPropertyBoundary:
  case End
  case Separation
  case Flow(value: Char)
  case Adjacent(value: Char)

private[yaml] final case class YamlPropertyRange(start: Int, end: Int)

private[yaml] final case class YamlPropertyFailure(offset: Int, message: String)

private[yaml] final case class YamlPropertyScan(
    kind: YamlPropertyKind,
    start: Int,
    end: Int,
    handle: Option[YamlPropertyRange],
    suffix: Option[YamlPropertyRange],
    boundary: YamlPropertyBoundary,
    hadSeparation: Boolean,
    failure: Option[YamlPropertyFailure],
)

/** YAML 1.2.2 lexical authority for tags, anchors, and aliases.
  *
  * The scanner is deliberately context-free: it records the delimiter it saw,
  * while `boundaryFailure` applies the caller's active flow closer. This keeps
  * the lexer and semantic parser on one spelling grammar without teaching the
  * scanner either parser's state machine.
  */
private[yaml] object YamlPropertySyntax:
  def scan(
      input: String,
      start: Int,
      kind: YamlPropertyKind,
  ): YamlPropertyScan =
    val expected = indicator(kind)
    if start < 0 || start >= input.length || input.charAt(start) != expected then
      YamlPropertyScan(
        kind,
        start,
        start,
        None,
        None,
        boundaryAt(input, start),
        hadSeparation = false,
        Some(YamlPropertyFailure(0, s"expected '$expected'")),
      )
    else
      var end = start + 1
      if kind == YamlPropertyKind.Tag &&
          end < input.length &&
          input.charAt(end) == '<' then
        end += 1
        var closed = false
        while end < input.length && !closed && !isSeparation(input.charAt(end)) do
          if input.charAt(end) == '>' then
            end += 1
            closed = true
          else end += 1
      else
        while end < input.length &&
            !isSeparation(input.charAt(end)) &&
            !isFlow(input.charAt(end)) do
          end += 1

      val boundary = boundaryAt(input, end)
      val spelling = input.substring(start, end)
      val spellingFailure = kind match
        case YamlPropertyKind.Tag =>
          validateTagSpelling(spelling).left.toOption
        case YamlPropertyKind.Anchor =>
          validateAnchorName(spelling.drop(1)).left.toOption
        case YamlPropertyKind.Alias =>
          validateAliasName(spelling.drop(1)).left.toOption
      val ranges =
        if kind == YamlPropertyKind.Tag then tagRanges(input, start, end)
        else None -> None
      YamlPropertyScan(
        kind,
        start,
        end,
        ranges._1,
        ranges._2,
        boundary,
        boundary == YamlPropertyBoundary.Separation,
        spellingFailure,
      )

  def boundaryFailure(
      scan: YamlPropertyScan,
      activeFlowCloser: Option[Char],
  ): Option[YamlPropertyFailure] =
    scan.failure.orElse {
      val offset = scan.end - scan.start
      scan.boundary match
        case YamlPropertyBoundary.End | YamlPropertyBoundary.Separation => None
        case YamlPropertyBoundary.Flow('[' | '{') =>
          Some(YamlPropertyFailure(offset, "an opening flow collection requires separation"))
        case YamlPropertyBoundary.Flow(',') =>
          Option.when(activeFlowCloser.isEmpty)(
            YamlPropertyFailure(offset, "a comma terminates a property only inside a flow collection")
          )
        case YamlPropertyBoundary.Flow(value @ (']' | '}')) =>
          Option.when(!activeFlowCloser.contains(value))(
            YamlPropertyFailure(offset, s"'$value' does not close the active flow collection")
          )
        case YamlPropertyBoundary.Flow(value) =>
          Some(YamlPropertyFailure(offset, s"invalid property boundary '$value'"))
        case YamlPropertyBoundary.Adjacent(value) =>
          Some(YamlPropertyFailure(offset, s"property requires separation before '$value'"))
    }

  def validateTagSpelling(value: String): Either[YamlPropertyFailure, Unit] =
    if value.isEmpty || value.charAt(0) != '!' then
      Left(YamlPropertyFailure(0, "a tag spelling must begin with '!'"))
    else if value == "!" then Right(())
    else if value.startsWith("!<") then
      if !value.endsWith(">") then
        Left(YamlPropertyFailure(value.length, "an unterminated verbatim tag requires '>'"))
      else if value.length == 3 then
        Left(YamlPropertyFailure(2, "a verbatim tag body cannot be empty"))
      else validateUriCharacters(value.substring(2, value.length - 1)).left.map(failure =>
        failure.copy(offset = failure.offset + 2)
      )
    else
      val handleEnd =
        if value.startsWith("!!") then 2
        else
          val namedEnd = value.indexOf('!', 1)
          if namedEnd >= 0 then namedEnd + 1 else 1
      val handle = value.substring(0, handleEnd)
      if !validHandle(handle) then
        Left(YamlPropertyFailure(0, "invalid YAML tag handle"))
      else if handleEnd >= value.length then
        Left(YamlPropertyFailure(handleEnd, "a shorthand tag suffix cannot be empty"))
      else validateTagCharacters(value.substring(handleEnd)).left.map(failure =>
        failure.copy(offset = failure.offset + handleEnd)
      )

  def validateTagPrefix(value: String): Either[YamlPropertyFailure, Unit] =
    if value.isEmpty then Left(YamlPropertyFailure(0, "a tag prefix cannot be empty"))
    else if value.charAt(0) == '!' then validateUriCharacters(value.substring(1)).left.map(failure =>
      failure.copy(offset = failure.offset + 1)
    )
    else
      consumeUriUnit(value, 0, tagCharacter = true) match
        case Left(failure) => Left(failure)
        case Right(next) =>
          validateUriCharacters(value.substring(next)).left.map(failure =>
            failure.copy(offset = failure.offset + next)
          )

  def validateUriCharacters(value: String): Either[YamlPropertyFailure, Unit] =
    validateUnits(value, tagCharacters = false)

  def validateTagCharacters(value: String): Either[YamlPropertyFailure, Unit] =
    validateUnits(value, tagCharacters = true)

  def validateAnchorName(value: String): Either[YamlPropertyFailure, Unit] =
    validateAnchorLike(value, "anchor")

  def validateAliasName(value: String): Either[YamlPropertyFailure, Unit] =
    validateAnchorLike(value, "alias")

  def validHandle(value: String): Boolean =
    if value == "!" || value == "!!" then true
    else
      var cursor = 1
      var valid = value.length >= 3 && value.charAt(0) == '!' && value.last == '!'
      while cursor < value.length - 1 && valid do
        valid = isHandleChar(value.charAt(cursor))
        cursor += 1
      valid

  private def validateUnits(
      value: String,
      tagCharacters: Boolean,
  ): Either[YamlPropertyFailure, Unit] =
    var cursor = 0
    var failure: Option[YamlPropertyFailure] = None
    while cursor < value.length && failure.isEmpty do
      consumeUriUnit(value, cursor, tagCharacters) match
        case Left(problem) => failure = Some(problem)
        case Right(next)   => cursor = next
    failure match
      case Some(problem) => Left(problem)
      case None          => Right(())

  private def consumeUriUnit(
      value: String,
      cursor: Int,
      tagCharacter: Boolean,
  ): Either[YamlPropertyFailure, Int] =
    val char = value.charAt(cursor)
    if char == '%' then
      if cursor + 2 >= value.length then
        Left(YamlPropertyFailure(cursor, "truncated percent triplet"))
      else if !isHex(value.charAt(cursor + 1)) || !isHex(value.charAt(cursor + 2)) then
        Left(YamlPropertyFailure(cursor, "percent triplet requires two hexadecimal digits"))
      else Right(cursor + 3)
    else
      val accepted =
        if tagCharacter then isRawTagChar(char)
        else isRawUriChar(char)
      if accepted then Right(cursor + 1)
      else Left(YamlPropertyFailure(cursor, "character is outside the YAML tag URI production"))

  private def validateAnchorLike(
      value: String,
      label: String,
  ): Either[YamlPropertyFailure, Unit] =
    if value.isEmpty then Left(YamlPropertyFailure(0, s"a YAML $label name cannot be empty"))
    else
      var cursor = 0
      var failure: Option[YamlPropertyFailure] = None
      while cursor < value.length && failure.isEmpty do
        val char = value.charAt(cursor)
        if isFlow(char) || isSeparation(char) || char == '\uFEFF' then
          failure = Some(YamlPropertyFailure(cursor, s"invalid character in YAML $label name"))
        else if Unicode.isHighSurrogate(char) &&
            cursor + 1 < value.length &&
            Unicode.isLowSurrogate(value.charAt(cursor + 1)) then
          cursor += 2
        else if Unicode.isHighSurrogate(char) || Unicode.isLowSurrogate(char) then
          failure = Some(YamlPropertyFailure(cursor, s"invalid UTF-16 in YAML $label name"))
        else if isNsChar(char.toInt) then cursor += 1
        else failure = Some(YamlPropertyFailure(cursor, s"non-printable character in YAML $label name"))
      failure match
        case Some(problem) => Left(problem)
        case None          => Right(())

  private def tagRanges(
      input: String,
      start: Int,
      end: Int,
  ): (Option[YamlPropertyRange], Option[YamlPropertyRange]) =
    if end <= start || input.charAt(start) != '!' then None -> None
    else if start + 1 < end && input.charAt(start + 1) == '<' then
      val suffixEnd =
        if input.charAt(end - 1) == '>' then end - 1
        else end
      None -> Some(YamlPropertyRange(start + 2, suffixEnd))
    else if end == start + 1 then
      Some(YamlPropertyRange(start, end)) -> None
    else
      val handleEnd =
        if start + 1 < end && input.charAt(start + 1) == '!' then start + 2
        else
          val named = input.indexOf('!', start + 1)
          if named >= 0 && named < end then named + 1 else start + 1
      Some(YamlPropertyRange(start, handleEnd)) ->
        Some(YamlPropertyRange(handleEnd, end))

  private def boundaryAt(input: String, end: Int): YamlPropertyBoundary =
    if end < 0 || end >= input.length then YamlPropertyBoundary.End
    else
      val char = input.charAt(end)
      if isSeparation(char) then YamlPropertyBoundary.Separation
      else if isFlow(char) then YamlPropertyBoundary.Flow(char)
      else YamlPropertyBoundary.Adjacent(char)

  private def indicator(kind: YamlPropertyKind): Char = kind match
    case YamlPropertyKind.Tag    => '!'
    case YamlPropertyKind.Anchor => '&'
    case YamlPropertyKind.Alias  => '*'

  private def isRawUriChar(value: Char): Boolean =
    isAlpha(value) ||
      isDigit(value) ||
      value == '-' ||
      "#;/?:@&=+$,_.!~*'()[]".contains(value)

  private def isRawTagChar(value: Char): Boolean =
    isRawUriChar(value) && value != '!' && value != ',' && value != '[' && value != ']'

  private def isNsChar(value: Int): Boolean =
    (value >= 0x21 && value <= 0x7e) ||
      value == 0x85 ||
      (value >= 0xa0 && value <= 0xd7ff) ||
      (value >= 0xe000 && value <= 0xfffd)

  private def isHandleChar(value: Char): Boolean =
    isAlpha(value) || isDigit(value) || value == '-'

  private def isAlpha(value: Char): Boolean =
    (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z')

  private def isDigit(value: Char): Boolean = value >= '0' && value <= '9'

  private def isHex(value: Char): Boolean =
    isDigit(value) ||
      (value >= 'a' && value <= 'f') ||
      (value >= 'A' && value <= 'F')

  private def isSeparation(value: Char): Boolean =
    value == ' ' || value == '\t' || value == '\r' || value == '\n'

  private def isFlow(value: Char): Boolean =
    value == '[' || value == ']' || value == '{' || value == '}' || value == ','

/** Portable, allocation-bounded validator for the RFC 3986 `URI` production.
  * It validates syntax only: no percent decoding, normalization, DNS, or
  * scheme-specific behavior. */
private[yaml] object Rfc3986UriSyntax:
  final case class Failure(offset: Int, expected: String)

  def validateUri(value: String): Either[Failure, Unit] =
    if value.isEmpty then Left(Failure(0, "URI scheme"))
    else if !isAlpha(value.charAt(0)) then Left(Failure(0, "ASCII scheme letter"))
    else
      var cursor = 1
      var colon = -1
      var failure: Option[Failure] = None
      while cursor < value.length && colon < 0 && failure.isEmpty do
        val char = value.charAt(cursor)
        if char == ':' then colon = cursor
        else if isSchemeChar(char) then cursor += 1
        else failure = Some(Failure(cursor, "URI scheme character or ':'"))
      if failure.nonEmpty then Left(failure.get)
      else if colon < 0 then Left(Failure(value.length, "':' after URI scheme"))
      else
        val fragment = value.indexOf('#', colon + 1)
        val querySearchEnd = if fragment < 0 then value.length else fragment
        val query = indexOf(value, '?', colon + 1, querySearchEnd)
        val hierEnd =
          if query >= 0 then query
          else if fragment >= 0 then fragment
          else value.length
        validateHierPart(value, colon + 1, hierEnd) match
          case Left(problem) => Left(problem)
          case Right(_) =>
            val queryEnd = if fragment < 0 then value.length else fragment
            val queryResult =
              if query < 0 then Right(())
              else validateComponent(value, query + 1, queryEnd, Component.Query)
            queryResult match
              case Left(problem) => Left(problem)
              case Right(_) =>
                if fragment < 0 then Right(())
                else validateComponent(value, fragment + 1, value.length, Component.Query)

  def validIpv4(value: String): Boolean =
    validIpv4Range(value, 0, value.length)

  def validIpv6(value: String): Boolean =
    if value.isEmpty then false
    else
      var compression = -1
      var cursor = 0
      var valid = true
      while cursor + 1 < value.length && valid do
        if value.charAt(cursor) == ':' && value.charAt(cursor + 1) == ':' then
          if compression >= 0 then valid = false
          else
            compression = cursor
            cursor += 2
        else cursor += 1
      if !valid then false
      else if compression < 0 then
        ipv6SideWeight(value, 0, value.length, allowIpv4 = true) == 8
      else
        val left = ipv6SideWeight(value, 0, compression, allowIpv4 = false)
        val right = ipv6SideWeight(value, compression + 2, value.length, allowIpv4 = true)
        left >= 0 && right >= 0 && left + right < 8

  def validIpvFuture(value: String): Boolean =
    if value.length < 4 || (value.charAt(0) != 'v' && value.charAt(0) != 'V') then false
    else
      var cursor = 1
      while cursor < value.length && isHex(value.charAt(cursor)) do cursor += 1
      if cursor == 1 || cursor >= value.length || value.charAt(cursor) != '.' then false
      else
        cursor += 1
        val payloadStart = cursor
        var valid = true
        while cursor < value.length && valid do
          val char = value.charAt(cursor)
          valid = isUnreserved(char) || isSubDelimiter(char) || char == ':'
          cursor += 1
        valid && cursor > payloadStart

  private enum Component:
    case PChar, Query, UserInfo, RegName

  private def validateHierPart(
      value: String,
      start: Int,
      end: Int,
  ): Either[Failure, Unit] =
    if start == end then Right(())
    else if start + 1 < end && value.charAt(start) == '/' && value.charAt(start + 1) == '/' then
      var authorityEnd = start + 2
      while authorityEnd < end && value.charAt(authorityEnd) != '/' do authorityEnd += 1
      validateAuthority(value, start + 2, authorityEnd) match
        case Left(problem) => Left(problem)
        case Right(_)      => validatePath(value, authorityEnd, end, requireFirstSegment = false)
    else if value.charAt(start) == '/' then
      if start + 1 == end then Right(())
      else if value.charAt(start + 1) == '/' then
        Left(Failure(start + 1, "non-empty first path-absolute segment"))
      else validatePath(value, start + 1, end, requireFirstSegment = true)
    else validatePath(value, start, end, requireFirstSegment = true)

  private def validateAuthority(
      value: String,
      start: Int,
      end: Int,
  ): Either[Failure, Unit] =
    var at = -1
    var cursor = start
    var duplicateAt = -1
    while cursor < end && duplicateAt < 0 do
      if value.charAt(cursor) == '@' then
        if at >= 0 then duplicateAt = cursor else at = cursor
      cursor += 1
    if duplicateAt >= 0 then Left(Failure(duplicateAt, "at most one raw '@' in authority"))
    else
      val hostStart =
        if at < 0 then start
        else at + 1
      val userInfoResult =
        if at < 0 then Right(())
        else validateComponent(value, start, at, Component.UserInfo)
      userInfoResult match
        case Left(problem) => Left(problem)
        case Right(_) =>
          if hostStart < end && value.charAt(hostStart) == '[' then
            validateBracketedHost(value, hostStart, end)
          else
            var colon = hostStart
            while colon < end && value.charAt(colon) != ':' do colon += 1
            val hostResult =
              if validIpv4Range(value, hostStart, colon) then Right(())
              else validateComponent(value, hostStart, colon, Component.RegName)
            hostResult match
              case Left(problem) => Left(problem)
              case Right(_) =>
                if colon >= end then Right(())
                else validatePort(value, colon + 1, end)

  private def validateBracketedHost(
      value: String,
      start: Int,
      end: Int,
  ): Either[Failure, Unit] =
    var close = start + 1
    while close < end && value.charAt(close) != ']' do close += 1
    if close >= end then Left(Failure(end, "']' after IP literal"))
    else
      val literal = value.substring(start + 1, close)
      if !validIpv6(literal) && !validIpvFuture(literal) then
        Left(Failure(start + 1, "IPv6 or IPvFuture literal"))
      else if close + 1 == end then Right(())
      else if value.charAt(close + 1) != ':' then
        Left(Failure(close + 1, "':' and port after IP literal"))
      else validatePort(value, close + 2, end)

  private def validatePort(
      value: String,
      start: Int,
      end: Int,
  ): Either[Failure, Unit] =
    var cursor = start
    while cursor < end && isDigit(value.charAt(cursor)) do cursor += 1
    if cursor == end then Right(())
    else Left(Failure(cursor, "decimal port digit"))

  private def validatePath(
      value: String,
      start: Int,
      end: Int,
      requireFirstSegment: Boolean,
  ): Either[Failure, Unit] =
    if requireFirstSegment && start >= end then Left(Failure(start, "non-empty path segment"))
    else
      var firstSegmentEnd = start
      while firstSegmentEnd < end && value.charAt(firstSegmentEnd) != '/' do firstSegmentEnd += 1
      if requireFirstSegment && firstSegmentEnd == start then
        Left(Failure(start, "non-empty first path segment"))
      else
        var cursor = start
        var failure: Option[Failure] = None
        while cursor < end && failure.isEmpty do
          if value.charAt(cursor) == '/' then cursor += 1
          else
            consumeComponentUnit(value, cursor, end, Component.PChar) match
              case Left(problem) => failure = Some(problem)
              case Right(next)   => cursor = next
        failure match
          case Some(problem) => Left(problem)
          case None          => Right(())

  private def validateComponent(
      value: String,
      start: Int,
      end: Int,
      component: Component,
  ): Either[Failure, Unit] =
    var cursor = start
    var failure: Option[Failure] = None
    while cursor < end && failure.isEmpty do
      consumeComponentUnit(value, cursor, end, component) match
        case Left(problem) => failure = Some(problem)
        case Right(next)   => cursor = next
    failure match
      case Some(problem) => Left(problem)
      case None          => Right(())

  private def consumeComponentUnit(
      value: String,
      cursor: Int,
      end: Int,
      component: Component,
  ): Either[Failure, Int] =
    val char = value.charAt(cursor)
    if char == '%' then
      if cursor + 2 >= end then Left(Failure(cursor, "complete percent triplet"))
      else if !isHex(value.charAt(cursor + 1)) || !isHex(value.charAt(cursor + 2)) then
        Left(Failure(cursor, "two hexadecimal percent digits"))
      else Right(cursor + 3)
    else
      val allowed = component match
        case Component.PChar =>
          isPChar(char)
        case Component.Query =>
          isPChar(char) || char == '/' || char == '?'
        case Component.UserInfo =>
          isUnreserved(char) || isSubDelimiter(char) || char == ':'
        case Component.RegName =>
          isUnreserved(char) || isSubDelimiter(char)
      if allowed then Right(cursor + 1)
      else Left(Failure(cursor, "RFC 3986 component character"))

  private def validIpv4Range(value: String, start: Int, end: Int): Boolean =
    var cursor = start
    var components = 0
    var valid = start < end
    while cursor < end && valid do
      val componentStart = cursor
      var numeric = 0
      while cursor < end && value.charAt(cursor) != '.' && valid do
        val char = value.charAt(cursor)
        if !isDigit(char) then valid = false
        else
          numeric = numeric * 10 + (char - '0')
          cursor += 1
      val length = cursor - componentStart
      if length < 1 || length > 3 || numeric > 255 then valid = false
      else if length > 1 && value.charAt(componentStart) == '0' then valid = false
      components += 1
      if valid && cursor < end then
        cursor += 1
        if cursor == end then valid = false
    valid && components == 4 && cursor == end

  private def ipv6SideWeight(
      value: String,
      start: Int,
      end: Int,
      allowIpv4: Boolean,
  ): Int =
    if start == end then 0
    else
      var cursor = start
      var weight = 0
      var valid = true
      while cursor < end && valid do
        val tokenStart = cursor
        while cursor < end && value.charAt(cursor) != ':' do cursor += 1
        val tokenEnd = cursor
        if tokenStart == tokenEnd then valid = false
        else
          var dot = tokenStart
          while dot < tokenEnd && value.charAt(dot) != '.' do dot += 1
          if dot < tokenEnd then
            if allowIpv4 && tokenEnd == end && validIpv4Range(value, tokenStart, tokenEnd) then
              weight += 2
            else valid = false
          else
            val length = tokenEnd - tokenStart
            var hex = length >= 1 && length <= 4
            var hexCursor = tokenStart
            while hexCursor < tokenEnd && hex do
              hex = isHex(value.charAt(hexCursor))
              hexCursor += 1
            if hex then weight += 1 else valid = false
        if valid && cursor < end then
          cursor += 1
          if cursor == end then valid = false
      if valid then weight else -1

  private def indexOf(
      value: String,
      target: Char,
      start: Int,
      end: Int,
  ): Int =
    var cursor = start
    var found = -1
    while cursor < end && found < 0 do
      if value.charAt(cursor) == target then found = cursor
      cursor += 1
    found

  private def isSchemeChar(value: Char): Boolean =
    isAlpha(value) || isDigit(value) || value == '+' || value == '-' || value == '.'

  private def isPChar(value: Char): Boolean =
    isUnreserved(value) || isSubDelimiter(value) || value == ':' || value == '@'

  private def isUnreserved(value: Char): Boolean =
    isAlpha(value) || isDigit(value) || value == '-' || value == '.' || value == '_' || value == '~'

  private def isSubDelimiter(value: Char): Boolean =
    "!$&'()*+,;=".contains(value)

  private def isAlpha(value: Char): Boolean =
    (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z')

  private def isDigit(value: Char): Boolean = value >= '0' && value <= '9'

  private def isHex(value: Char): Boolean =
    isDigit(value) ||
      (value >= 'a' && value <= 'f') ||
      (value >= 'A' && value <= 'F')
