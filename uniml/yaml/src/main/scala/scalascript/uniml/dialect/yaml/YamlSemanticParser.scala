package scalascript.uniml.dialect.yaml

import scalascript.uniml.*

private[yaml] final case class YamlSemanticResult(
    stream: YamlValue.Stream,
    diagnostics: Vector[Diagnostic],
)

private[yaml] object YamlSemanticParser:

  private final case class Line(
      raw: String,
      lineBreak: String,
      number: Int,
      startOffset: Int,
  ):
    def span(source: SourceId): SourceSpan =
      SourceSpan(
        source,
        SourcePosition(startOffset, number, 1),
        SourcePosition(startOffset + Unicode.codePointCount(raw), number, Unicode.codePointCount(raw) + 1),
      )

  private final case class Properties(tag: Option[String], anchor: Option[String])

  def parse(source: SourceId, input: String, schema: YamlSchema): YamlSemanticResult =
    val lines = splitLines(input)
    var diagnostics: Vector[Diagnostic] = Vector.empty
    var index = 0

    def problem(
        code: String,
        message: String,
        span: SourceSpan,
        severity: Severity = Severity.Error,
    ): Unit = diagnostics = diagnostics :+ Diagnostic(code, message, severity, Some(span), Some(YamlDialect.id))

    def skipBlank(): Unit =
      while index < lines.size && clean(lines(index)).isEmpty do index += 1

    def currentSpan(): SourceSpan =
      if index < lines.size then lines(index).span(source)
      else
        val offset = Unicode.codePointCount(input)
        val line = if lines.isEmpty then 1 else lines.last.number + Option.when(lines.last.lineBreak.nonEmpty)(1).getOrElse(0)
        SourceSpan(source, SourcePosition(offset, line, 1), SourcePosition(offset, line, 1))

    def parseValue(minIndent: Int, depth: Int): YamlValue =
      if depth > 512 then
        problem("uniml.yaml.limit.depth", "YAML semantic depth exceeds 512", currentSpan(), Severity.Fatal)
        return nullValue("")
      skipBlank()
      if index >= lines.size || isDocumentBoundary(lines(index)) || indentOf(lines(index)) < minIndent then nullValue("")
      else
        val line = lines(index)
        val indentation = indentOf(line)
        val text = clean(line).drop(indentation)
        if isSequenceLine(text) then parseBlockSequence(indentation, depth + 1)
        else if findKeyColon(text) >= 0 || text.startsWith("? ") then parseBlockMapping(indentation, depth + 1)
        else
          index += 1
          parseInline(text, line.span(source), depth + 1)

    def parseBlockMapping(mapIndent: Int, depth: Int): YamlValue =
      var entries: Vector[YamlEntry] = Vector.empty
      var continue = true
      while continue && index < lines.size do
        skipBlank()
        if index >= lines.size || isDocumentBoundary(lines(index)) || indentOf(lines(index)) != mapIndent then continue = false
        else
          val line = lines(index)
          val text = clean(line).drop(mapIndent)
          if text.startsWith("? ") then
            index += 1
            val key = parseInline(text.drop(2).trim, line.span(source), depth + 1)
            skipBlank()
            if index < lines.size && indentOf(lines(index)) == mapIndent && clean(lines(index)).drop(mapIndent).startsWith(":") then
              val valueLine = lines(index)
              val rest = clean(valueLine).drop(mapIndent + 1).trim
              index += 1
              val value = parseAfterIndicator(rest, mapIndent, valueLine, depth + 1)
              entries = entries :+ YamlEntry(key, value, mergeSpan(line.span(source), valueLine.span(source)))
            else
              problem("uniml.yaml.expected-value", "explicit mapping key has no ':' value indicator", line.span(source))
              entries = entries :+ YamlEntry(key, nullValue(""), line.span(source))
          else
            val colon = findKeyColon(text)
            if colon < 0 then continue = false
            else
              index += 1
              val rawKey = text.take(colon).trim
              val rawValue = text.drop(colon + 1).trim
              val key = if rawKey.isEmpty then nullValue("") else parseInline(rawKey, line.span(source), depth + 1)
              val value = parseAfterIndicator(rawValue, mapIndent, line, depth + 1)
              entries = entries :+ YamlEntry(key, value, line.span(source))
      YamlValue.Mapping(entries, None, None)

    def parseBlockSequence(sequenceIndent: Int, depth: Int): YamlValue =
      var values: Vector[YamlValue] = Vector.empty
      var continue = true
      while continue && index < lines.size do
        skipBlank()
        if index >= lines.size || isDocumentBoundary(lines(index)) || indentOf(lines(index)) != sequenceIndent then continue = false
        else
          val line = lines(index)
          val text = clean(line).drop(sequenceIndent)
          if !isSequenceLine(text) then continue = false
          else
            index += 1
            val after = text.drop(1).trim
            if after.isEmpty then values = values :+ nestedOrNull(sequenceIndent, depth + 1)
            else if blockHeader(after).nonEmpty then values = values :+ parseBlockScalar(after, sequenceIndent, line)
            else
              val (properties, rest) = splitPropertiesNoDiagnostic(after)
              if findKeyColon(rest) >= 0 then
                val mapping = parseCompactMapping(rest, sequenceIndent, line, depth + 1)
                if properties == Properties(None, None) then values = values :+ mapping
                else
                  val (validated, _) = splitProperties(after, line.span(source))
                  values = values :+ applyProperties(mapping, validated, line.span(source))
              else values = values :+ parseAfterIndicator(after, sequenceIndent, line, depth + 1)
      YamlValue.Sequence(values, None, None)

    def parseCompactMapping(first: String, parentIndent: Int, firstLine: Line, depth: Int): YamlValue =
      var entries: Vector[YamlEntry] = Vector.empty
      entries = appendCompactEntry(first, parentIndent, firstLine, depth, entries)
      skipBlank()
      var continue = true
      var mapIndent = -1
      while continue && index < lines.size && !isDocumentBoundary(lines(index)) do
        val line = lines(index)
        val indentation = indentOf(line)
        val text = clean(line).drop(indentation)
        if indentation <= parentIndent || findKeyColon(text) < 0 then continue = false
        else if mapIndent >= 0 && indentation != mapIndent then continue = false
        else
          if mapIndent < 0 then mapIndent = indentation
          index += 1
          entries = appendCompactEntry(text, indentation, line, depth, entries)
          skipBlank()
      YamlValue.Mapping(entries, None, None)

    def appendCompactEntry(
        text: String,
        parentIndent: Int,
        line: Line,
        depth: Int,
        entries: Vector[YamlEntry],
    ): Vector[YamlEntry] =
      val colon = findKeyColon(text)
      if colon < 0 then
        problem("uniml.yaml.expected-key", "compact mapping entry has no ':'", line.span(source))
        entries
      else
        val keyText = text.take(colon).trim
        val valueText = text.drop(colon + 1).trim
        val key = if keyText.isEmpty then nullValue("") else parseInline(keyText, line.span(source), depth + 1)
        val value = parseAfterIndicator(valueText, parentIndent, line, depth + 1)
        entries :+ YamlEntry(key, value, line.span(source))

    def parseAfterIndicator(text: String, parentIndent: Int, line: Line, depth: Int): YamlValue =
      if text.isEmpty then nestedOrNull(parentIndent, depth + 1)
      else if blockHeader(text).nonEmpty then parseBlockScalar(text, parentIndent, line)
      else
        val (properties, rest) = splitPropertiesNoDiagnostic(text)
        if properties != Properties(None, None) && rest.isEmpty then
          val (validated, _) = splitProperties(text, line.span(source))
          applyProperties(nestedOrNull(parentIndent, depth + 1), validated, line.span(source))
        else parseInline(text, line.span(source), depth + 1)

    def nestedOrNull(parentIndent: Int, depth: Int): YamlValue =
      skipBlank()
      if index < lines.size && !isDocumentBoundary(lines(index)) && indentOf(lines(index)) > parentIndent then
        parseValue(indentOf(lines(index)), depth + 1)
      else nullValue("")

    def parseBlockScalar(headerText: String, parentIndent: Int, headerLine: Line): YamlValue =
      val (properties, header) = splitProperties(headerText, headerLine.span(source))
      val headerValue = blockHeader(header).getOrElse {
        problem("uniml.yaml.invalid-block-scalar", "invalid block scalar header", headerLine.span(source))
        ('|', None, None)
      }
      val (styleChar, chomping, explicitIndent) = headerValue
      val contentStart = index
      var detectedIndent = explicitIndent.map(parentIndent + _).getOrElse(-1)
      if detectedIndent < 0 then
        var cursor = contentStart
        while cursor < lines.size && clean(lines(cursor)).isEmpty do cursor += 1
        detectedIndent = if cursor < lines.size then math.max(parentIndent + 1, indentOf(lines(cursor))) else parentIndent + 1

      var rawLines: Vector[String] = Vector.empty
      var lexeme = headerText + headerLine.lineBreak
      var continue = true
      while continue && index < lines.size && !isDocumentBoundary(lines(index)) do
        val line = lines(index)
        val blank = clean(line).isEmpty
        if !blank && indentOf(line) < detectedIndent then continue = false
        else
          val content = if blank then "" else line.raw.drop(math.min(detectedIndent, line.raw.length))
          rawLines = rawLines :+ content
          lexeme = lexeme + line.raw + line.lineBreak
          index += 1

      val normalized =
        if styleChar == '|' then rawLines.mkString("\n")
        else foldLines(rawLines)
      val withBreak = if contentStart < index then normalized + "\n" else normalized
      val cooked = chomping match
        case Some('-') => withBreak.reverse.dropWhile(_ == '\n').reverse
        case Some('+') => withBreak
        case _         => withBreak.reverse.dropWhile(_ == '\n').reverse + Option.when(withBreak.nonEmpty)("\n").getOrElse("")
      val style = if styleChar == '|' then ScalarStyle.Literal else ScalarStyle.Folded
      applyProperties(YamlValue.Scalar(YamlScalar.StringValue(cooked, lexeme, style), None, None), properties, headerLine.span(source))

    def parseInline(text: String, span: SourceSpan, depth: Int): YamlValue =
      val withoutComment = stripComment(text).trim
      val (properties, rest) = splitProperties(withoutComment, span)
      val value =
        if rest.startsWith("[") then flowParse(rest, span, depth, isSequence = true)
        else if rest.startsWith("{") then flowParse(rest, span, depth, isSequence = false)
        else if rest.startsWith("*") then
          val name = rest.drop(1).takeWhile(char => !isWs(char) && !",]}".contains(char))
          if name.isEmpty then
            problem("uniml.yaml.invalid-alias", "empty YAML alias", span)
            nullValue(rest)
          else YamlValue.Alias(name)
        else if rest.startsWith("'") then quotedSingle(rest, span)
        else if rest.startsWith("\"") then quotedDouble(rest, span)
        else plainScalar(rest, properties.tag)
      applyProperties(value, properties, span)

    def plainScalar(lexeme: String, explicitTag: Option[String]): YamlValue =
      val tag = explicitTag.map(normalizeTag)
      val scalar =
        if tag.contains("tag:yaml.org,2002:str") || tag.exists(value => !knownScalarTag(value)) then
          YamlScalar.StringValue(lexeme, lexeme, ScalarStyle.Plain)
        else if tag.contains("tag:yaml.org,2002:null") then YamlScalar.NullValue(lexeme)
        else if tag.contains("tag:yaml.org,2002:bool") then YamlScalar.BooleanValue(lexeme.equalsIgnoreCase("true"), lexeme)
        else if tag.contains("tag:yaml.org,2002:int") then YamlScalar.IntegerValue(lexeme)
        else if tag.contains("tag:yaml.org,2002:float") then YamlScalar.FloatValue(lexeme)
        else resolveImplicit(lexeme)
      YamlValue.Scalar(scalar, None, None)

    def resolveImplicit(lexeme: String): YamlScalar = schema match
      case YamlSchema.Failsafe => YamlScalar.StringValue(lexeme, lexeme, ScalarStyle.Plain)
      case YamlSchema.Json =>
        if lexeme == "null" then YamlScalar.NullValue(lexeme)
        else if lexeme == "true" || lexeme == "false" then YamlScalar.BooleanValue(lexeme == "true", lexeme)
        else if matchesJsonInteger(lexeme) then YamlScalar.IntegerValue(lexeme)
        else if matchesJsonFloat(lexeme) then YamlScalar.FloatValue(lexeme)
        else YamlScalar.StringValue(lexeme, lexeme, ScalarStyle.Plain)
      case YamlSchema.Core =>
        if matchesCoreNull(lexeme) then YamlScalar.NullValue(lexeme)
        else if matchesCoreTrue(lexeme) then YamlScalar.BooleanValue(true, lexeme)
        else if matchesCoreFalse(lexeme) then YamlScalar.BooleanValue(false, lexeme)
        else if matchesCoreInteger(lexeme) then YamlScalar.IntegerValue(lexeme)
        else if matchesCoreFloat(lexeme) then YamlScalar.FloatValue(lexeme)
        else YamlScalar.StringValue(lexeme, lexeme, ScalarStyle.Plain)

    def quotedSingle(text: String, span: SourceSpan): YamlValue =
      if text.length < 2 || text.last != '\'' then
        problem("uniml.yaml.invalid-single-quoted", "unterminated single-quoted scalar", span)
        YamlValue.Scalar(YamlScalar.StringValue(text.drop(1), text, ScalarStyle.SingleQuoted), None, None)
      else
        val cooked = text.substring(1, text.length - 1).replace("''", "'")
        YamlValue.Scalar(YamlScalar.StringValue(cooked, text, ScalarStyle.SingleQuoted), None, None)

    def quotedDouble(text: String, span: SourceSpan): YamlValue =
      if text.length < 2 || text.last != '"' then
        problem("uniml.yaml.invalid-double-quoted", "unterminated double-quoted scalar", span)
        YamlValue.Scalar(YamlScalar.StringValue(text.drop(1), text, ScalarStyle.DoubleQuoted), None, None)
      else
        decodeDouble(text.substring(1, text.length - 1)) match
          case Some(cooked) => YamlValue.Scalar(YamlScalar.StringValue(cooked, text, ScalarStyle.DoubleQuoted), None, None)
          case None =>
            problem("uniml.yaml.invalid-double-quoted", "invalid escape in double-quoted scalar", span)
            YamlValue.Scalar(YamlScalar.StringValue(text.substring(1, text.length - 1), text, ScalarStyle.DoubleQuoted), None, None)

    def parseDirective(line: Line): YamlDirective =
      val lexeme = clean(line)
      val body = lexeme.drop(1)
      val split = body.indexWhere(c => isWs(c))
      val (name, value) = if split < 0 then body -> "" else body.take(split) -> body.drop(split).trim
      if name != "YAML" && name != "TAG" then
        problem("uniml.yaml.invalid-directive", s"reserved YAML directive '$name' is preserved but not interpreted", line.span(source), Severity.Warning)
      YamlDirective(name, value, lexeme, line.span(source))

    def splitProperties(text: String, span: SourceSpan): (Properties, String) =
      var rest = text.trim
      var tag: Option[String] = None
      var anchor: Option[String] = None
      var continue = true
      while continue && rest.nonEmpty do
        if rest.startsWith("!") then
          val length = propertyLength(rest)
          val value = rest.take(length)
          if tag.nonEmpty then problem("uniml.yaml.invalid-tag", "a YAML node cannot have two tags", span)
          tag = Some(value)
          rest = rest.drop(length).trim
        else if rest.startsWith("&") then
          val length = propertyLength(rest)
          val value = rest.slice(1, length)
          if value.isEmpty then problem("uniml.yaml.invalid-anchor", "empty YAML anchor", span)
          if anchor.nonEmpty then problem("uniml.yaml.invalid-anchor", "a YAML node cannot have two anchors", span)
          anchor = Some(value)
          rest = rest.drop(length).trim
        else continue = false
      Properties(tag, anchor) -> rest

    def applyProperties(value: YamlValue, properties: Properties, span: SourceSpan): YamlValue = value match
      case YamlValue.Mapping(entries, tag, anchor) =>
        YamlValue.Mapping(entries, properties.tag.orElse(tag), properties.anchor.orElse(anchor))
      case YamlValue.Sequence(values, tag, anchor) =>
        YamlValue.Sequence(values, properties.tag.orElse(tag), properties.anchor.orElse(anchor))
      case YamlValue.Scalar(scalar, tag, anchor) =>
        YamlValue.Scalar(scalar, properties.tag.orElse(tag), properties.anchor.orElse(anchor))
      case alias: YamlValue.Alias =>
        if properties.tag.nonEmpty || properties.anchor.nonEmpty then
          problem("uniml.yaml.invalid-alias", "aliases cannot have tag or anchor properties", span)
        alias
      case stream: YamlValue.Stream => stream

    def flowParse(text: String, span: SourceSpan, depth: Int, isSequence: Boolean): YamlValue =
      var cursor = 0

      def skipSpaces(): Unit =
        while cursor < text.length && isWs(text.charAt(cursor)) do cursor += 1

      def parseNode(stopAtColon: Boolean = false): YamlValue =
        skipSpaces()
        val start = cursor
        if cursor >= text.length then nullValue("")
        else text.charAt(cursor) match
          case '[' => parseSequence()
          case '{' => parseMapping()
          case '\'' =>
            cursor += 1
            var closed = false
            while cursor < text.length && !closed do
              if text.charAt(cursor) == '\'' then
                if cursor + 1 < text.length && text.charAt(cursor + 1) == '\'' then cursor += 2
                else
                  cursor += 1
                  closed = true
              else cursor += 1
            quotedSingle(text.substring(start, cursor), span)
          case '"' =>
            cursor += 1
            var escaped = false
            var closed = false
            while cursor < text.length && !closed do
              val char = text.charAt(cursor)
              if escaped then escaped = false
              else if char == '\\' then escaped = true
              else if char == '"' then closed = true
              cursor += 1
            quotedDouble(text.substring(start, cursor), span)
          case '*' =>
            cursor += 1
            val nameStart = cursor
            while cursor < text.length && !isWs(text.charAt(cursor)) && !",]}".contains(text.charAt(cursor)) do cursor += 1
            YamlValue.Alias(text.substring(nameStart, cursor))
          case _ =>
            while cursor < text.length && !",]}".contains(text.charAt(cursor)) &&
                !(stopAtColon && text.charAt(cursor) == ':') do cursor += 1
            parseInline(text.substring(start, cursor).trim, span, depth + 1)

      def parseSequence(): YamlValue =
        cursor += 1
        var values: Vector[YamlValue] = Vector.empty
        skipSpaces()
        while cursor < text.length && text.charAt(cursor) != ']' do
          values = values :+ parseNode()
          skipSpaces()
          if cursor < text.length && text.charAt(cursor) == ',' then
            cursor += 1
            skipSpaces()
          else if cursor < text.length && text.charAt(cursor) != ']' then
            problem("uniml.yaml.expected-separator", "expected ',' or ']' in flow sequence", span)
            cursor = text.length
        if cursor < text.length && text.charAt(cursor) == ']' then cursor += 1
        else problem("uniml.yaml.unclosed-flow", "unclosed flow sequence", span)
        YamlValue.Sequence(values, None, None)

      def parseMapping(): YamlValue =
        cursor += 1
        var entries: Vector[YamlEntry] = Vector.empty
        skipSpaces()
        while cursor < text.length && text.charAt(cursor) != '}' do
          val key = parseNode(stopAtColon = true)
          skipSpaces()
          if cursor >= text.length || text.charAt(cursor) != ':' then
            problem("uniml.yaml.expected-value", "expected ':' in flow mapping", span)
            cursor = text.length
          else
            cursor += 1
            skipSpaces()
            val value = if cursor < text.length && text.charAt(cursor) != ',' && text.charAt(cursor) != '}' then parseNode() else nullValue("")
            entries = entries :+ YamlEntry(key, value, span)
            skipSpaces()
            if cursor < text.length && text.charAt(cursor) == ',' then
              cursor += 1
              skipSpaces()
            else if cursor < text.length && text.charAt(cursor) != '}' then
              problem("uniml.yaml.expected-separator", "expected ',' or '}' in flow mapping", span)
              cursor = text.length
        if cursor < text.length && text.charAt(cursor) == '}' then cursor += 1
        else problem("uniml.yaml.unclosed-flow", "unclosed flow mapping", span)
        YamlValue.Mapping(entries, None, None)

      if isSequence then parseSequence() else parseMapping()

    var documents: Vector[YamlDocument] = Vector.empty
    skipBlank()
    while index < lines.size do
      var directives: Vector[YamlDirective] = Vector.empty
      while index < lines.size && clean(lines(index)).startsWith("%") do
        directives = directives :+ parseDirective(lines(index))
        index += 1
        skipBlank()

      val directiveValues = directives
      val explicitStart = index < lines.size && clean(lines(index)) == "---"
      if directiveValues.nonEmpty && !explicitStart then
        problem("uniml.yaml.directive-position", "directives must be followed by an explicit document start", currentSpan())
      if explicitStart then
        index += 1
        skipBlank()

      if index >= lines.size then documents = documents :+ YamlDocument(None, directiveValues)
      else if clean(lines(index)) == "..." then
        index += 1
        documents = documents :+ YamlDocument(None, directiveValues)
      else if clean(lines(index)) == "---" then
        documents = documents :+ YamlDocument(None, directiveValues)
      else
        val startIndex = index
        val value = parseValue(indentOf(lines(index)), 0)
        if index == startIndex then index += 1
        skipBlank()
        if index < lines.size && clean(lines(index)) == "..." then index += 1
        documents = documents :+ YamlDocument(Some(value), directiveValues)

      skipBlank()
      if index < lines.size && clean(lines(index)) != "---" && !clean(lines(index)).startsWith("%") then
        problem("uniml.yaml.expected-node", "unexpected content after YAML document root", lines(index).span(source))
        index += 1
        skipBlank()

    YamlSemanticResult(YamlValue.Stream(documents), diagnostics)

  private def nullValue(lexeme: String): YamlValue = YamlValue.Scalar(YamlScalar.NullValue(lexeme), None, None)

  private def clean(line: Line): String = stripComment(line.raw).stripTrailing()

  private def mergeSpan(first: SourceSpan, last: SourceSpan): SourceSpan =
    SourceSpan(first.source, first.start, last.end)

  private def isDocumentBoundary(line: Line): Boolean =
    val value = clean(line)
    value == "---" || value == "..." || value.startsWith("%")

  private def indentOf(line: Line): Int = line.raw.takeWhile(_ == ' ').length

  private def isSequenceLine(text: String): Boolean =
    text == "-" || text.startsWith("- ") || text.startsWith("-\t")

  private def blockHeader(text: String): Option[(Char, Option[Char], Option[Int])] =
    val (_, rest) = splitPropertiesNoDiagnostic(text)
    if rest.isEmpty || (rest.head != '|' && rest.head != '>') then None
    else
      var chomping: Option[Char] = None
      var indentation: Option[Int] = None
      var cursor = 1
      var valid = true
      while cursor < rest.length && !isWs(rest.charAt(cursor)) && rest.charAt(cursor) != '#' do
        val char = rest.charAt(cursor)
        if (char == '+' || char == '-') && chomping.isEmpty then chomping = Some(char)
        else if char >= '1' && char <= '9' && indentation.isEmpty then indentation = Some(char - '0')
        else valid = false
        cursor += 1
      Option.when(valid)((rest.head, chomping, indentation))

  private def splitPropertiesNoDiagnostic(text: String): (Properties, String) =
    var rest = text.trim
    var tag: Option[String] = None
    var anchor: Option[String] = None
    var continue = true
    while continue && rest.nonEmpty do
      if rest.startsWith("!") then
        val length = propertyLength(rest); tag = Some(rest.take(length)); rest = rest.drop(length).trim
      else if rest.startsWith("&") then
        val length = propertyLength(rest); anchor = Some(rest.slice(1, length)); rest = rest.drop(length).trim
      else continue = false
    Properties(tag, anchor) -> rest

  private def propertyLength(text: String): Int =
    if text.startsWith("!<") then
      val close = text.indexOf('>')
      if close < 0 then text.length else close + 1
    else
      val separator = text.indexWhere(char => isWs(char) || "[]{},".contains(char))
      if separator < 0 then text.length else separator

  private def normalizeTag(tag: String): String = tag match
    case "!!str"   => "tag:yaml.org,2002:str"
    case "!!null"  => "tag:yaml.org,2002:null"
    case "!!bool"  => "tag:yaml.org,2002:bool"
    case "!!int"   => "tag:yaml.org,2002:int"
    case "!!float" => "tag:yaml.org,2002:float"
    case value if value.startsWith("!<") && value.endsWith(">") => value.substring(2, value.length - 1)
    case value => value

  private def knownScalarTag(tag: String): Boolean =
    tag == "tag:yaml.org,2002:str" || tag == "tag:yaml.org,2002:null" ||
      tag == "tag:yaml.org,2002:bool" || tag == "tag:yaml.org,2002:int" ||
      tag == "tag:yaml.org,2002:float"

  private def decodeDouble(text: String): Option[String] =
    var result = ""
    var cursor = 0
    var valid = true
    while cursor < text.length && valid do
      val char = text.charAt(cursor)
      if char != '\\' then
        result = result + char.toString
        cursor += 1
      else if cursor + 1 >= text.length then valid = false
      else
        text.charAt(cursor + 1) match
          case '0' => result = result + "\u0000"; cursor += 2
          case 'a' => result = result + "\u0007"; cursor += 2
          case 'b' => result = result + "\b"; cursor += 2
          case 't' | '\t' => result = result + "\t"; cursor += 2
          case 'n' => result = result + "\n"; cursor += 2
          case 'v' => result = result + "\u000B"; cursor += 2
          case 'f' => result = result + "\f"; cursor += 2
          case 'r' => result = result + "\r"; cursor += 2
          case 'e' => result = result + "\u001B"; cursor += 2
          case ' ' => result = result + " "; cursor += 2
          case '"' => result = result + "\""; cursor += 2
          case '/' => result = result + "/"; cursor += 2
          case '\\' => result = result + "\\"; cursor += 2
          case 'N' => result = result + "\u0085"; cursor += 2
          case '_' => result = result + "\u00A0"; cursor += 2
          case 'L' => result = result + "\u2028"; cursor += 2
          case 'P' => result = result + "\u2029"; cursor += 2
          case 'x' =>
            parseHexEscape(text, cursor + 2, 2) match
              case Some(value) => result = result + codePointToString(value); cursor += 4
              case None        => valid = false
          case 'u' =>
            parseHexEscape(text, cursor + 2, 4) match
              case Some(value) => result = result + codePointToString(value); cursor += 6
              case None        => valid = false
          case 'U' =>
            parseHexEscape(text, cursor + 2, 8) match
              case Some(value) if value <= 0x10ffff => result = result + codePointToString(value); cursor += 10
              case _                                => valid = false
          case _ => valid = false
    Option.when(valid)(result)

  private def parseHexEscape(text: String, start: Int, length: Int): Option[Int] =
    if start + length > text.length then None
    else
      var value = 0
      var cursor = start
      var valid = true
      while cursor < start + length && valid do
        val digit = hexDigit(text.charAt(cursor))
        if digit < 0 then valid = false else value = value * 16 + digit
        cursor += 1
      Option.when(valid)(value)

  private def codePointToString(value: Int): String =
    if value <= 0xffff then value.toChar.toString
    else
      val adjusted = value - 0x10000
      val high = ((adjusted >>> 10) + 0xd800).toChar
      val low = ((adjusted & 0x3ff) + 0xdc00).toChar
      high.toString + low.toString

  private def foldLines(values: Vector[String]): String =
    var result = ""
    var position = 0
    while position < values.size do
      val value = values(position)
      result = result + value
      if position + 1 < values.size then
        result = result + (if value.isEmpty || values(position + 1).isEmpty then "\n" else " ")
      position += 1
    result

  private def splitLines(input: String): Vector[Line] =
    var result: Vector[Line] = Vector.empty
    var cursor = 0
    var start = 0
    var line = 1
    var offset = 0
    while cursor < input.length do
      val char = input.charAt(cursor)
      if char == '\r' || char == '\n' then
        val breakWidth = if char == '\r' && cursor + 1 < input.length && input.charAt(cursor + 1) == '\n' then 2 else 1
        val raw = input.substring(start, cursor)
        val lineBreak = input.substring(cursor, cursor + breakWidth)
        result = result :+ Line(raw, lineBreak, line, offset)
        offset += Unicode.codePointCount(raw) + breakWidth
        cursor += breakWidth
        start = cursor
        line += 1
      else cursor += 1
    if start < input.length || input.isEmpty then result = result :+ Line(input.substring(start), "", line, offset)
    result

  private def stripComment(text: String): String =
    var single = false
    var double = false
    var cursor = 0
    var comment = -1
    while cursor < text.length && comment < 0 do
      text.charAt(cursor) match
        case '\'' if !double =>
          if single && cursor + 1 < text.length && text.charAt(cursor + 1) == '\'' then cursor += 1
          else single = !single
        case '"' if !single => double = !double
        case '\\' if double && cursor + 1 < text.length => cursor += 1
        case '#' if !single && !double && (cursor == 0 || isWs(text.charAt(cursor - 1))) => comment = cursor
        case _ => ()
      cursor += 1
    if comment < 0 then text else text.take(comment)

  private def findKeyColon(text: String): Int =
    var single = false
    var double = false
    var flowDepth = 0
    var cursor = 0
    var found = -1
    while cursor < text.length && found < 0 do
      text.charAt(cursor) match
        case '\'' if !double => single = !single
        case '"' if !single => double = !double
        case '\\' if double && cursor + 1 < text.length => cursor += 1
        case '[' | '{' if !single && !double => flowDepth += 1
        case ']' | '}' if !single && !double => flowDepth = math.max(0, flowDepth - 1)
        case ':' if !single && !double && flowDepth == 0 &&
            (cursor + 1 >= text.length || isWs(text.charAt(cursor + 1))) => found = cursor
        case _ => ()
      cursor += 1
    found

  private def isDigit(c: Char): Boolean = c >= '0' && c <= '9'
  private def isOctDigit(c: Char): Boolean = c >= '0' && c <= '7'
  private def isHexDigit(c: Char): Boolean = isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')

  private def hexDigit(c: Char): Int =
    if c >= '0' && c <= '9' then c - '0'
    else if c >= 'a' && c <= 'f' then c - 'a' + 10
    else if c >= 'A' && c <= 'F' then c - 'A' + 10
    else -1

  private def isWs(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r' || (c >= '\u001C' && c <= '\u001F')

  // all chars in [from, s.length) satisfy pred (zero-or-more; true if from >= length)
  private def allFrom(s: String, from: Int, pred: Char => Boolean): Boolean =
    var i = from
    var ok = true
    while i < s.length && ok do
      if !pred(s.charAt(i)) then ok = false
      i += 1
    ok

  private def matchesCoreNull(s: String): Boolean = s == "~" || s == "null" || s == "Null" || s == "NULL"
  private def matchesCoreTrue(s: String): Boolean = s == "true" || s == "True" || s == "TRUE"
  private def matchesCoreFalse(s: String): Boolean = s == "false" || s == "False" || s == "FALSE"

  // ^(?:[-+]?[0-9]+|0o[0-7]+|0x[0-9a-fA-F]+)$
  private def matchesCoreInteger(s: String): Boolean =
    if s.startsWith("0o") then s.length > 2 && allFrom(s, 2, isOctDigit)
    else if s.startsWith("0x") then s.length > 2 && allFrom(s, 2, isHexDigit)
    else
      val from = if s.nonEmpty && (s.charAt(0) == '+' || s.charAt(0) == '-') then 1 else 0
      s.length > from && allFrom(s, from, isDigit)

  // ^-?(?:0|[1-9][0-9]*)$
  private def matchesJsonInteger(s: String): Boolean =
    val from = if s.nonEmpty && s.charAt(0) == '-' then 1 else 0
    val body = s.substring(from)
    body == "0" || (body.nonEmpty && body.charAt(0) >= '1' && body.charAt(0) <= '9' && allFrom(body, 1, isDigit))

  // ^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][-+]?[0-9]+)?$
  private def matchesJsonFloat(s: String): Boolean =
    var i = if s.nonEmpty && s.charAt(0) == '-' then 1 else 0
    val n = s.length
    var ok = i < n
    if ok then
      if s.charAt(i) == '0' then i += 1
      else if s.charAt(i) >= '1' && s.charAt(i) <= '9' then { i += 1; while i < n && isDigit(s.charAt(i)) do i += 1 }
      else ok = false
    if ok && i < n && s.charAt(i) == '.' then
      i += 1; val fs = i; while i < n && isDigit(s.charAt(i)) do i += 1
      if i == fs then ok = false
    if ok && i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E') then
      i += 1; if i < n && (s.charAt(i) == '+' || s.charAt(i) == '-') then i += 1
      val es = i; while i < n && isDigit(s.charAt(i)) do i += 1
      if i == es then ok = false
    ok && i == n

  // ^(?:[-+]?(?:[0-9]+\.[0-9]*|\.[0-9]+)(?:[eE][-+]?[0-9]+)?|[-+]?[0-9]+[eE][-+]?[0-9]+|[-+]?\.(?:inf|Inf|INF)|\.(?:nan|NaN|NAN))$
  private def matchesCoreFloat(s: String): Boolean =
    if s == ".nan" || s == ".NaN" || s == ".NAN" then true
    else
      var i = if s.nonEmpty && (s.charAt(0) == '+' || s.charAt(0) == '-') then 1 else 0
      val n = s.length
      val rest = s.substring(i)
      if rest == ".inf" || rest == ".Inf" || rest == ".INF" then true
      else
        var ok = i < n
        val intStart = i
        while i < n && isDigit(s.charAt(i)) do i += 1
        val intDigits = i - intStart
        var hasDot = false
        if i < n && s.charAt(i) == '.' then
          hasDot = true; i += 1; val fs = i
          while i < n && isDigit(s.charAt(i)) do i += 1
          val fracDigits = i - fs
          if intDigits < 1 && fracDigits < 1 then ok = false
        else if intDigits < 1 then ok = false
        var hadExp = false
        if ok && i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E') then
          hadExp = true; i += 1; if i < n && (s.charAt(i) == '+' || s.charAt(i) == '-') then i += 1
          val es = i; while i < n && isDigit(s.charAt(i)) do i += 1
          if i == es then ok = false
        if ok && !hasDot && !hadExp then ok = false
        ok && i == n
