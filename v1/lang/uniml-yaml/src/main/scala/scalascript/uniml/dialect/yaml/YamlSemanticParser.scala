package scalascript.uniml.dialect.yaml

import scalascript.uniml.*
import scala.collection.mutable

private[yaml] final case class YamlSemanticResult(
    stream: YamlValue.Stream,
    diagnostics: Vector[Diagnostic],
)

private[yaml] object YamlSemanticParser:
  def parse(source: SourceId, input: String, schema: YamlSchema): YamlSemanticResult =
    Parser(source, input, schema).run()

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

  private final class Parser(source: SourceId, input: String, schema: YamlSchema):
    private val lines = splitLines(input)
    private val diagnostics = Vector.newBuilder[Diagnostic]
    private var index = 0

    def run(): YamlSemanticResult =
      val documents = Vector.newBuilder[YamlDocument]
      skipBlank()
      while index < lines.size do
        val directives = Vector.newBuilder[YamlDirective]
        while index < lines.size && clean(lines(index)).startsWith("%") do
          directives += parseDirective(lines(index))
          index += 1
          skipBlank()

        val directiveValues = directives.result()
        val explicitStart = index < lines.size && clean(lines(index)) == "---"
        if directiveValues.nonEmpty && !explicitStart then
          problem("uniml.yaml.directive-position", "directives must be followed by an explicit document start", currentSpan())
        if explicitStart then
          index += 1
          skipBlank()

        if index >= lines.size then documents += YamlDocument(None, directiveValues)
        else if clean(lines(index)) == "..." then
          index += 1
          documents += YamlDocument(None, directiveValues)
        else if clean(lines(index)) == "---" then
          documents += YamlDocument(None, directiveValues)
        else
          val startIndex = index
          val value = parseValue(indentOf(lines(index)), 0)
          if index == startIndex then index += 1
          skipBlank()
          if index < lines.size && clean(lines(index)) == "..." then index += 1
          documents += YamlDocument(Some(value), directiveValues)

        skipBlank()
        if index < lines.size && clean(lines(index)) != "---" && !clean(lines(index)).startsWith("%") then
          problem("uniml.yaml.expected-node", "unexpected content after YAML document root", lines(index).span(source))
          index += 1
          skipBlank()

      YamlSemanticResult(YamlValue.Stream(documents.result()), diagnostics.result())

    private def parseValue(minIndent: Int, depth: Int): YamlValue =
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

    private def parseBlockMapping(mapIndent: Int, depth: Int): YamlValue =
      val entries = Vector.newBuilder[YamlEntry]
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
              entries += YamlEntry(key, value, mergeSpan(line.span(source), valueLine.span(source)))
            else
              problem("uniml.yaml.expected-value", "explicit mapping key has no ':' value indicator", line.span(source))
              entries += YamlEntry(key, nullValue(""), line.span(source))
          else
            val colon = findKeyColon(text)
            if colon < 0 then continue = false
            else
              index += 1
              val rawKey = text.take(colon).trim
              val rawValue = text.drop(colon + 1).trim
              val key = if rawKey.isEmpty then nullValue("") else parseInline(rawKey, line.span(source), depth + 1)
              val value = parseAfterIndicator(rawValue, mapIndent, line, depth + 1)
              entries += YamlEntry(key, value, line.span(source))
      YamlValue.Mapping(entries.result(), None, None)

    private def parseBlockSequence(sequenceIndent: Int, depth: Int): YamlValue =
      val values = Vector.newBuilder[YamlValue]
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
            if after.isEmpty then values += nestedOrNull(sequenceIndent, depth + 1)
            else if blockHeader(after).nonEmpty then values += parseBlockScalar(after, sequenceIndent, line)
            else
              val (properties, rest) = splitPropertiesNoDiagnostic(after)
              if properties != Properties(None, None) && findKeyColon(rest) >= 0 then
                val (validated, _) = splitProperties(after, line.span(source))
                val mapping = parseCompactMapping(rest, sequenceIndent, line, depth + 1)
                values += applyProperties(mapping, validated, line.span(source))
              else values += parseAfterIndicator(after, sequenceIndent, line, depth + 1)
      YamlValue.Sequence(values.result(), None, None)

    private def parseCompactMapping(first: String, parentIndent: Int, firstLine: Line, depth: Int): YamlValue =
      val entries = Vector.newBuilder[YamlEntry]
      appendCompactEntry(first, parentIndent, firstLine, depth, entries)
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
          appendCompactEntry(text, indentation, line, depth, entries)
          skipBlank()
      YamlValue.Mapping(entries.result(), None, None)

    private def appendCompactEntry(
        text: String,
        parentIndent: Int,
        line: Line,
        depth: Int,
        entries: mutable.Builder[YamlEntry, Vector[YamlEntry]],
    ): Unit =
      val colon = findKeyColon(text)
      if colon < 0 then problem("uniml.yaml.expected-key", "compact mapping entry has no ':'", line.span(source))
      else
        val keyText = text.take(colon).trim
        val valueText = text.drop(colon + 1).trim
        val key = if keyText.isEmpty then nullValue("") else parseInline(keyText, line.span(source), depth + 1)
        val value = parseAfterIndicator(valueText, parentIndent, line, depth + 1)
        entries += YamlEntry(key, value, line.span(source))

    private def parseAfterIndicator(text: String, parentIndent: Int, line: Line, depth: Int): YamlValue =
      if text.isEmpty then nestedOrNull(parentIndent, depth + 1)
      else if blockHeader(text).nonEmpty then parseBlockScalar(text, parentIndent, line)
      else
        val (properties, rest) = splitPropertiesNoDiagnostic(text)
        if properties != Properties(None, None) && rest.isEmpty then
          val (validated, _) = splitProperties(text, line.span(source))
          applyProperties(nestedOrNull(parentIndent, depth + 1), validated, line.span(source))
        else parseInline(text, line.span(source), depth + 1)

    private def nestedOrNull(parentIndent: Int, depth: Int): YamlValue =
      skipBlank()
      if index < lines.size && !isDocumentBoundary(lines(index)) && indentOf(lines(index)) > parentIndent then
        parseValue(indentOf(lines(index)), depth + 1)
      else nullValue("")

    private def parseBlockScalar(headerText: String, parentIndent: Int, headerLine: Line): YamlValue =
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

      val rawLines = Vector.newBuilder[String]
      val lexeme = StringBuilder(headerText).append(headerLine.lineBreak)
      var continue = true
      while continue && index < lines.size && !isDocumentBoundary(lines(index)) do
        val line = lines(index)
        val blank = clean(line).isEmpty
        if !blank && indentOf(line) < detectedIndent then continue = false
        else
          val content = if blank then "" else line.raw.drop(math.min(detectedIndent, line.raw.length))
          rawLines += content
          lexeme.append(line.raw).append(line.lineBreak)
          index += 1

      val normalized =
        if styleChar == '|' then rawLines.result().mkString("\n")
        else foldLines(rawLines.result())
      val withBreak = if contentStart < index then normalized + "\n" else normalized
      val cooked = chomping match
        case Some('-') => withBreak.reverse.dropWhile(_ == '\n').reverse
        case Some('+') => withBreak
        case _         => withBreak.reverse.dropWhile(_ == '\n').reverse + Option.when(withBreak.nonEmpty)("\n").getOrElse("")
      val style = if styleChar == '|' then ScalarStyle.Literal else ScalarStyle.Folded
      applyProperties(YamlValue.Scalar(YamlScalar.StringValue(cooked, lexeme.result(), style), None, None), properties, headerLine.span(source))

    private def parseInline(text: String, span: SourceSpan, depth: Int): YamlValue =
      val withoutComment = stripComment(text).trim
      val (properties, rest) = splitProperties(withoutComment, span)
      val value =
        if rest.startsWith("[") then FlowParser(rest, span, depth, this).parseSequence()
        else if rest.startsWith("{") then FlowParser(rest, span, depth, this).parseMapping()
        else if rest.startsWith("*") then
          val name = rest.drop(1).takeWhile(char => !char.isWhitespace && !",]}".contains(char))
          if name.isEmpty then
            problem("uniml.yaml.invalid-alias", "empty YAML alias", span)
            nullValue(rest)
          else YamlValue.Alias(name)
        else if rest.startsWith("'") then quotedSingle(rest, span)
        else if rest.startsWith("\"") then quotedDouble(rest, span)
        else plainScalar(rest, properties.tag)
      applyProperties(value, properties, span)

    private def plainScalar(lexeme: String, explicitTag: Option[String]): YamlValue =
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

    private def resolveImplicit(lexeme: String): YamlScalar = schema match
      case YamlSchema.Failsafe => YamlScalar.StringValue(lexeme, lexeme, ScalarStyle.Plain)
      case YamlSchema.Json =>
        if lexeme == "null" then YamlScalar.NullValue(lexeme)
        else if lexeme == "true" || lexeme == "false" then YamlScalar.BooleanValue(lexeme == "true", lexeme)
        else if jsonInteger.matches(lexeme) then YamlScalar.IntegerValue(lexeme)
        else if jsonFloat.matches(lexeme) then YamlScalar.FloatValue(lexeme)
        else YamlScalar.StringValue(lexeme, lexeme, ScalarStyle.Plain)
      case YamlSchema.Core =>
        if coreNull.matches(lexeme) then YamlScalar.NullValue(lexeme)
        else if coreTrue.matches(lexeme) then YamlScalar.BooleanValue(true, lexeme)
        else if coreFalse.matches(lexeme) then YamlScalar.BooleanValue(false, lexeme)
        else if coreInteger.matches(lexeme) then YamlScalar.IntegerValue(lexeme)
        else if coreFloat.matches(lexeme) then YamlScalar.FloatValue(lexeme)
        else YamlScalar.StringValue(lexeme, lexeme, ScalarStyle.Plain)

    private def quotedSingle(text: String, span: SourceSpan): YamlValue =
      if text.length < 2 || text.last != '\'' then
        problem("uniml.yaml.invalid-single-quoted", "unterminated single-quoted scalar", span)
        YamlValue.Scalar(YamlScalar.StringValue(text.drop(1), text, ScalarStyle.SingleQuoted), None, None)
      else
        val cooked = text.substring(1, text.length - 1).replace("''", "'")
        YamlValue.Scalar(YamlScalar.StringValue(cooked, text, ScalarStyle.SingleQuoted), None, None)

    private def quotedDouble(text: String, span: SourceSpan): YamlValue =
      if text.length < 2 || text.last != '"' then
        problem("uniml.yaml.invalid-double-quoted", "unterminated double-quoted scalar", span)
        YamlValue.Scalar(YamlScalar.StringValue(text.drop(1), text, ScalarStyle.DoubleQuoted), None, None)
      else
        decodeDouble(text.substring(1, text.length - 1)) match
          case Some(cooked) => YamlValue.Scalar(YamlScalar.StringValue(cooked, text, ScalarStyle.DoubleQuoted), None, None)
          case None =>
            problem("uniml.yaml.invalid-double-quoted", "invalid escape in double-quoted scalar", span)
            YamlValue.Scalar(YamlScalar.StringValue(text.substring(1, text.length - 1), text, ScalarStyle.DoubleQuoted), None, None)

    private def parseDirective(line: Line): YamlDirective =
      val lexeme = clean(line)
      val body = lexeme.drop(1)
      val split = body.indexWhere(_.isWhitespace)
      val (name, value) = if split < 0 then body -> "" else body.take(split) -> body.drop(split).trim
      if name != "YAML" && name != "TAG" then
        problem("uniml.yaml.invalid-directive", s"reserved YAML directive '$name' is preserved but not interpreted", line.span(source), Severity.Warning)
      YamlDirective(name, value, lexeme, line.span(source))

    private def splitProperties(text: String, span: SourceSpan): (Properties, String) =
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

    private def applyProperties(value: YamlValue, properties: Properties, span: SourceSpan): YamlValue = value match
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

    private def nullValue(lexeme: String): YamlValue = YamlValue.Scalar(YamlScalar.NullValue(lexeme), None, None)

    private def skipBlank(): Unit =
      while index < lines.size && clean(lines(index)).isEmpty do index += 1

    private def clean(line: Line): String = stripComment(line.raw).stripTrailing()

    private def currentSpan(): SourceSpan =
      if index < lines.size then lines(index).span(source)
      else
        val offset = Unicode.codePointCount(input)
        val line = if lines.isEmpty then 1 else lines.last.number + Option.when(lines.last.lineBreak.nonEmpty)(1).getOrElse(0)
        SourceSpan(source, SourcePosition(offset, line, 1), SourcePosition(offset, line, 1))

    private def problem(
        code: String,
        message: String,
        span: SourceSpan,
        severity: Severity = Severity.Error,
    ): Unit = diagnostics += Diagnostic(code, message, severity, Some(span), Some(YamlDialect.id))

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
        while cursor < rest.length && !rest.charAt(cursor).isWhitespace && rest.charAt(cursor) != '#' do
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
        val separator = text.indexWhere(char => char.isWhitespace || "[]{},".contains(char))
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
      val result = StringBuilder()
      var cursor = 0
      var valid = true
      while cursor < text.length && valid do
        val char = text.charAt(cursor)
        if char != '\\' then
          result.append(char)
          cursor += 1
        else if cursor + 1 >= text.length then valid = false
        else
          text.charAt(cursor + 1) match
            case '0' => result.append('\u0000'); cursor += 2
            case 'a' => result.append('\u0007'); cursor += 2
            case 'b' => result.append('\b'); cursor += 2
            case 't' | '\t' => result.append('\t'); cursor += 2
            case 'n' => result.append('\n'); cursor += 2
            case 'v' => result.append('\u000B'); cursor += 2
            case 'f' => result.append('\f'); cursor += 2
            case 'r' => result.append('\r'); cursor += 2
            case 'e' => result.append('\u001B'); cursor += 2
            case ' ' => result.append(' '); cursor += 2
            case '"' => result.append('"'); cursor += 2
            case '/' => result.append('/'); cursor += 2
            case '\\' => result.append('\\'); cursor += 2
            case 'N' => result.append('\u0085'); cursor += 2
            case '_' => result.append('\u00A0'); cursor += 2
            case 'L' => result.append('\u2028'); cursor += 2
            case 'P' => result.append('\u2029'); cursor += 2
            case 'x' =>
              parseHexEscape(text, cursor + 2, 2) match
                case Some(value) => appendCodePoint(result, value); cursor += 4
                case None        => valid = false
            case 'u' =>
              parseHexEscape(text, cursor + 2, 4) match
                case Some(value) => appendCodePoint(result, value); cursor += 6
                case None        => valid = false
            case 'U' =>
              parseHexEscape(text, cursor + 2, 8) match
                case Some(value) if value <= 0x10ffff => appendCodePoint(result, value); cursor += 10
                case _                                => valid = false
            case _ => valid = false
      Option.when(valid)(result.result())

    private def parseHexEscape(text: String, start: Int, length: Int): Option[Int] =
      if start + length > text.length then None
      else
        var value = 0
        var cursor = start
        var valid = true
        while cursor < start + length && valid do
          val digit = Character.digit(text.charAt(cursor), 16)
          if digit < 0 then valid = false else value = value * 16 + digit
          cursor += 1
        Option.when(valid)(value)

    private def appendCodePoint(builder: StringBuilder, value: Int): Unit =
      if value <= 0xffff then builder.append(value.toChar)
      else
        val adjusted = value - 0x10000
        builder.append(((adjusted >>> 10) + 0xd800).toChar)
        builder.append(((adjusted & 0x3ff) + 0xdc00).toChar)

    private def foldLines(values: Vector[String]): String =
      val result = StringBuilder()
      values.indices.foreach { position =>
        val value = values(position)
        result.append(value)
        if position + 1 < values.size then
          if value.isEmpty || values(position + 1).isEmpty then result.append('\n') else result.append(' ')
      }
      result.result()

    private final case class Properties(tag: Option[String], anchor: Option[String])

    private final class FlowParser(text: String, span: SourceSpan, depth: Int, owner: Parser):
      private var cursor = 0

      def parseSequence(): YamlValue =
        cursor += 1
        val values = Vector.newBuilder[YamlValue]
        skipSpaces()
        while cursor < text.length && text.charAt(cursor) != ']' do
          values += parseNode()
          skipSpaces()
          if cursor < text.length && text.charAt(cursor) == ',' then
            cursor += 1
            skipSpaces()
          else if cursor < text.length && text.charAt(cursor) != ']' then
            owner.problem("uniml.yaml.expected-separator", "expected ',' or ']' in flow sequence", span)
            cursor = text.length
        if cursor < text.length && text.charAt(cursor) == ']' then cursor += 1
        else owner.problem("uniml.yaml.unclosed-flow", "unclosed flow sequence", span)
        YamlValue.Sequence(values.result(), None, None)

      def parseMapping(): YamlValue =
        cursor += 1
        val entries = Vector.newBuilder[YamlEntry]
        skipSpaces()
        while cursor < text.length && text.charAt(cursor) != '}' do
          val key = parseNode(stopAtColon = true)
          skipSpaces()
          if cursor >= text.length || text.charAt(cursor) != ':' then
            owner.problem("uniml.yaml.expected-value", "expected ':' in flow mapping", span)
            cursor = text.length
          else
            cursor += 1
            skipSpaces()
            val value = if cursor < text.length && text.charAt(cursor) != ',' && text.charAt(cursor) != '}' then parseNode() else owner.nullValue("")
            entries += YamlEntry(key, value, span)
            skipSpaces()
            if cursor < text.length && text.charAt(cursor) == ',' then
              cursor += 1
              skipSpaces()
            else if cursor < text.length && text.charAt(cursor) != '}' then
              owner.problem("uniml.yaml.expected-separator", "expected ',' or '}' in flow mapping", span)
              cursor = text.length
        if cursor < text.length && text.charAt(cursor) == '}' then cursor += 1
        else owner.problem("uniml.yaml.unclosed-flow", "unclosed flow mapping", span)
        YamlValue.Mapping(entries.result(), None, None)

      private def parseNode(stopAtColon: Boolean = false): YamlValue =
        skipSpaces()
        val start = cursor
        if cursor >= text.length then owner.nullValue("")
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
            owner.quotedSingle(text.substring(start, cursor), span)
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
            owner.quotedDouble(text.substring(start, cursor), span)
          case '*' =>
            cursor += 1
            val nameStart = cursor
            while cursor < text.length && !text.charAt(cursor).isWhitespace && !",]}".contains(text.charAt(cursor)) do cursor += 1
            YamlValue.Alias(text.substring(nameStart, cursor))
          case _ =>
            while cursor < text.length && !",]}".contains(text.charAt(cursor)) &&
                !(stopAtColon && text.charAt(cursor) == ':') do cursor += 1
            owner.parseInline(text.substring(start, cursor).trim, span, depth + 1)

      private def skipSpaces(): Unit =
        while cursor < text.length && text.charAt(cursor).isWhitespace do cursor += 1

    private val coreNull = "^(?:~|null|Null|NULL)$".r
    private val coreTrue = "^(?:true|True|TRUE)$".r
    private val coreFalse = "^(?:false|False|FALSE)$".r
    private val coreInteger = "^(?:[-+]?[0-9]+|0o[0-7]+|0x[0-9a-fA-F]+)$".r
    private val coreFloat = "^(?:[-+]?(?:[0-9]+\\.[0-9]*|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?|[-+]?[0-9]+[eE][-+]?[0-9]+|[-+]?\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN))$".r
    private val jsonInteger = "^-?(?:0|[1-9][0-9]*)$".r
    private val jsonFloat = "^-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?$".r

  private def splitLines(input: String): Vector[Line] =
    val result = Vector.newBuilder[Line]
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
        result += Line(raw, lineBreak, line, offset)
        offset += Unicode.codePointCount(raw) + breakWidth
        cursor += breakWidth
        start = cursor
        line += 1
      else cursor += 1
    if start < input.length || input.isEmpty then result += Line(input.substring(start), "", line, offset)
    result.result()

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
        case '#' if !single && !double && (cursor == 0 || text.charAt(cursor - 1).isWhitespace) => comment = cursor
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
            (cursor + 1 >= text.length || text.charAt(cursor + 1).isWhitespace) => found = cursor
        case _ => ()
      cursor += 1
    found
