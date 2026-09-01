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

  /** The parser's shared state — the three former closure vars, threaded through every parse
    * function. `Parsed` is the (state, value) result record; a tuple would need destructuring,
    * which the module's v2 rules forbid in val patterns and lambda parameters. */
  private final case class ParseState(
      index: Int,
      diagnostics: Vector[Diagnostic],
      tagEnvironment: YamlTagEnvironment,
  )
  private final case class Parsed(state: ParseState, value: YamlValue)
  private final case class SplitResult(state: ParseState, properties: Properties, rest: String)

  def parse(source: SourceId, input: String, schema: YamlSchema): YamlSemanticResult =
    val lines = splitLines(input)

    def problem(
        st: ParseState,
        code: String,
        message: String,
        span: SourceSpan,
        severity: Severity = Severity.Error,
    ): ParseState = st.copy(diagnostics = st.diagnostics :+ Diagnostic(code, message, severity, Some(span), Some(YamlDialect.id)))

    def skipBlank(st: ParseState): ParseState =
      if st.index < lines.size && clean(lines(st.index)).isEmpty then skipBlank(st.copy(index = st.index + 1))
      else st

    def currentSpan(st: ParseState): SourceSpan =
      if st.index < lines.size then lines(st.index).span(source)
      else
        val offset = Unicode.codePointCount(input)
        val line = if lines.isEmpty then 1 else lines.last.number + Option.when(lines.last.lineBreak.nonEmpty)(1).getOrElse(0)
        SourceSpan(source, SourcePosition(offset, line, 1), SourcePosition(offset, line, 1))

    def parseValue(st0: ParseState, minIndent: Int, depth: Int): Parsed =
      if depth > 512 then
        Parsed(problem(st0, "uniml.yaml.limit.depth", "YAML semantic depth exceeds 512", currentSpan(st0), Severity.Fatal), nullValue(""))
      else
        val st = skipBlank(st0)
        if st.index >= lines.size || isDocumentBoundary(lines(st.index)) || indentOf(lines(st.index)) < minIndent then
          Parsed(st, nullValue(""))
        else
          val line = lines(st.index)
          val indentation = indentOf(line)
          val text = clean(line).drop(indentation)
          if isSequenceLine(text) then parseBlockSequence(st, indentation, depth + 1)
          else if findKeyColon(text) >= 0 || text.startsWith("? ") then parseBlockMapping(st, indentation, depth + 1)
          else parseInline(st.copy(index = st.index + 1), text, line.span(source), depth + 1)

    final case class EntriesStep(state: ParseState, entries: Vector[YamlEntry])

    def parseBlockMapping(st0: ParseState, mapIndent: Int, depth: Int): Parsed =
      def loop(acc: EntriesStep): EntriesStep =
        if acc.state.index >= lines.size then acc
        else
          val st = skipBlank(acc.state)
          if st.index >= lines.size || isDocumentBoundary(lines(st.index)) || indentOf(lines(st.index)) != mapIndent then
            acc.copy(state = st)
          else
            val line = lines(st.index)
            val text = clean(line).drop(mapIndent)
            if text.startsWith("? ") then
              val keyResult = parseInline(st.copy(index = st.index + 1), text.drop(2).trim, line.span(source), depth + 1)
              val afterKey = skipBlank(keyResult.state)
              if afterKey.index < lines.size && indentOf(lines(afterKey.index)) == mapIndent &&
                  clean(lines(afterKey.index)).drop(mapIndent).startsWith(":") then
                val valueLine = lines(afterKey.index)
                val rest = clean(valueLine).drop(mapIndent + 1).trim
                val valueResult = parseAfterIndicator(afterKey.copy(index = afterKey.index + 1), rest, mapIndent, valueLine, depth + 1)
                loop(EntriesStep(
                  valueResult.state,
                  acc.entries :+ YamlEntry(keyResult.value, valueResult.value, mergeSpan(line.span(source), valueLine.span(source))),
                ))
              else
                val flagged = problem(afterKey, "uniml.yaml.expected-value", "explicit mapping key has no ':' value indicator", line.span(source))
                loop(EntriesStep(flagged, acc.entries :+ YamlEntry(keyResult.value, nullValue(""), line.span(source))))
            else
              val colon = findKeyColon(text)
              if colon < 0 then acc.copy(state = st)
              else
                val stepped = st.copy(index = st.index + 1)
                val rawKey = text.take(colon).trim
                val rawValue = text.drop(colon + 1).trim
                val keyResult =
                  if rawKey.isEmpty then Parsed(stepped, nullValue(""))
                  else parseInline(stepped, rawKey, line.span(source), depth + 1)
                val valueResult = parseAfterIndicator(keyResult.state, rawValue, mapIndent, line, depth + 1)
                loop(EntriesStep(valueResult.state, acc.entries :+ YamlEntry(keyResult.value, valueResult.value, line.span(source))))
      val walked = loop(EntriesStep(st0, Vector.empty))
      Parsed(walked.state, YamlValue.Mapping(walked.entries, None, None))

    final case class ValuesStep(state: ParseState, values: Vector[YamlValue])

    def parseBlockSequence(st0: ParseState, sequenceIndent: Int, depth: Int): Parsed =
      def loop(acc: ValuesStep): ValuesStep =
        if acc.state.index >= lines.size then acc
        else
          val st = skipBlank(acc.state)
          if st.index >= lines.size || isDocumentBoundary(lines(st.index)) || indentOf(lines(st.index)) != sequenceIndent then
            acc.copy(state = st)
          else
            val line = lines(st.index)
            val text = clean(line).drop(sequenceIndent)
            if !isSequenceLine(text) then acc.copy(state = st)
            else
              val stepped = st.copy(index = st.index + 1)
              val after = text.drop(1).trim
              if after.isEmpty then
                val nested = nestedOrNull(stepped, sequenceIndent, depth + 1)
                loop(ValuesStep(nested.state, acc.values :+ nested.value))
              else if blockHeader(after).nonEmpty then
                val scalar = parseBlockScalar(stepped, after, sequenceIndent, line)
                loop(ValuesStep(scalar.state, acc.values :+ scalar.value))
              else
                val propsAndRest = splitPropertiesNoDiagnostic(after)
                val properties = propsAndRest._1
                val rest = propsAndRest._2
                if findKeyColon(rest) >= 0 then
                  val mapping = parseCompactMapping(stepped, rest, sequenceIndent, line, depth + 1)
                  if properties == Properties(None, None) then loop(ValuesStep(mapping.state, acc.values :+ mapping.value))
                  else
                    val validated = splitProperties(mapping.state, after, line.span(source))
                    loop(ValuesStep(validated.state, acc.values :+ applyProperties(validated.state, mapping.value, validated.properties, line.span(source)).value))
                else
                  val value = parseAfterIndicator(stepped, after, sequenceIndent, line, depth + 1)
                  loop(ValuesStep(value.state, acc.values :+ value.value))
      val walked = loop(ValuesStep(st0, Vector.empty))
      Parsed(walked.state, YamlValue.Sequence(walked.values, None, None))

    final case class CompactStep(state: ParseState, entries: Vector[YamlEntry], mapIndent: Int)

    def parseCompactMapping(st0: ParseState, first: String, parentIndent: Int, firstLine: Line, depth: Int): Parsed =
      val firstEntry = appendCompactEntry(st0, first, parentIndent, firstLine, depth, Vector.empty)
      def loop(acc: CompactStep): CompactStep =
        if acc.state.index >= lines.size || isDocumentBoundary(lines(acc.state.index)) then acc
        else
          val line = lines(acc.state.index)
          val indentation = indentOf(line)
          val text = clean(line).drop(indentation)
          if indentation <= parentIndent || findKeyColon(text) < 0 then acc
          else if acc.mapIndent >= 0 && indentation != acc.mapIndent then acc
          else
            val latched = if acc.mapIndent < 0 then indentation else acc.mapIndent
            val stepped = acc.state.copy(index = acc.state.index + 1)
            val appended = appendCompactEntry(stepped, text, indentation, line, depth, acc.entries)
            loop(CompactStep(skipBlank(appended.state), appended.entries, latched))
      val walked = loop(CompactStep(skipBlank(firstEntry.state), firstEntry.entries, -1))
      Parsed(walked.state, YamlValue.Mapping(walked.entries, None, None))

    final case class AppendResult(state: ParseState, entries: Vector[YamlEntry])

    def appendCompactEntry(
        st0: ParseState,
        text: String,
        parentIndent: Int,
        line: Line,
        depth: Int,
        entries: Vector[YamlEntry],
    ): AppendResult =
      val colon = findKeyColon(text)
      if colon < 0 then
        AppendResult(problem(st0, "uniml.yaml.expected-key", "compact mapping entry has no ':'", line.span(source)), entries)
      else
        val keyText = text.take(colon).trim
        val valueText = text.drop(colon + 1).trim
        val keyResult =
          if keyText.isEmpty then Parsed(st0, nullValue(""))
          else parseInline(st0, keyText, line.span(source), depth + 1)
        val valueResult = parseAfterIndicator(keyResult.state, valueText, parentIndent, line, depth + 1)
        AppendResult(valueResult.state, entries :+ YamlEntry(keyResult.value, valueResult.value, line.span(source)))

    def parseAfterIndicator(st: ParseState, text: String, parentIndent: Int, line: Line, depth: Int): Parsed =
      if text.isEmpty then nestedOrNull(st, parentIndent, depth + 1)
      else if blockHeader(text).nonEmpty then parseBlockScalar(st, text, parentIndent, line)
      else
        val propsAndRest = splitPropertiesNoDiagnostic(text)
        val properties = propsAndRest._1
        val rest = propsAndRest._2
        if properties != Properties(None, None) && rest.isEmpty then
          val validated = splitProperties(st, text, line.span(source))
          val nested = nestedOrNull(validated.state, parentIndent, depth + 1)
          applyProperties(nested.state, nested.value, validated.properties, line.span(source))
        else parseInline(st, text, line.span(source), depth + 1)

    def nestedOrNull(st0: ParseState, parentIndent: Int, depth: Int): Parsed =
      val st = skipBlank(st0)
      if st.index < lines.size && !isDocumentBoundary(lines(st.index)) && indentOf(lines(st.index)) > parentIndent then
        parseValue(st, indentOf(lines(st.index)), depth + 1)
      else Parsed(st, nullValue(""))

    def parseBlockScalar(st0: ParseState, headerText: String, parentIndent: Int, headerLine: Line): Parsed =
      val split = splitProperties(st0, headerText, headerLine.span(source))
      val properties = split.properties
      val header = split.rest
      val headerParsed = blockHeader(header)
      val st1 =
        if headerParsed.isEmpty then
          problem(split.state, "uniml.yaml.invalid-block-scalar", "invalid block scalar header", headerLine.span(source))
        else split.state
      val headerValue = headerParsed.getOrElse(('|', None, None))
      val styleChar = headerValue._1
      val chomping = headerValue._2
      val explicitIndent = headerValue._3
      val contentStart = st1.index
      // pure scan for the detected indent — the imperative cursor never escaped
      def firstContentLine(cursor: Int): Int =
        if cursor < lines.size && clean(lines(cursor)).isEmpty then firstContentLine(cursor + 1) else cursor
      val detectedIndent = explicitIndent.map(parentIndent + _).getOrElse {
        val cursor = firstContentLine(contentStart)
        if cursor < lines.size then math.max(parentIndent + 1, indentOf(lines(cursor))) else parentIndent + 1
      }

      final case class ScalarStep(state: ParseState, rawLines: Vector[String], lexeme: String)
      def gather(acc: ScalarStep): ScalarStep =
        if acc.state.index >= lines.size || isDocumentBoundary(lines(acc.state.index)) then acc
        else
          val line = lines(acc.state.index)
          val blank = clean(line).isEmpty
          if !blank && indentOf(line) < detectedIndent then acc
          else
            val content = if blank then "" else line.raw.drop(math.min(detectedIndent, line.raw.length))
            gather(ScalarStep(
              acc.state.copy(index = acc.state.index + 1),
              acc.rawLines :+ content,
              acc.lexeme + line.raw + line.lineBreak,
            ))
      val gathered = gather(ScalarStep(st1, Vector.empty, headerText + headerLine.lineBreak))

      val normalized =
        if styleChar == '|' then gathered.rawLines.mkString("\n")
        else foldLines(gathered.rawLines)
      val withBreak = if contentStart < gathered.state.index then normalized + "\n" else normalized
      val cooked = chomping match
        case Some('-') => withBreak.reverse.dropWhile(_ == '\n').reverse
        case Some('+') => withBreak
        case _         => withBreak.reverse.dropWhile(_ == '\n').reverse + Option.when(withBreak.nonEmpty)("\n").getOrElse("")
      val style = if styleChar == '|' then ScalarStyle.Literal else ScalarStyle.Folded
      applyProperties(
        gathered.state,
        YamlValue.Scalar(YamlScalar.StringValue(cooked, gathered.lexeme, style), None, None),
        properties,
        headerLine.span(source),
      )

    def parseInline(st0: ParseState, text: String, span: SourceSpan, depth: Int): Parsed =
      val withoutComment = stripComment(text).trim
      val split = splitProperties(st0, withoutComment, span)
      val properties = split.properties
      val rest = split.rest
      val valueResult: Parsed =
        if rest.startsWith("[") then flowParse(split.state, rest, span, depth, isSequence = true)
        else if rest.startsWith("{") then flowParse(split.state, rest, span, depth, isSequence = false)
        else if rest.startsWith("*") then
          val scanned = YamlPropertySyntax.scan(rest, 0, YamlPropertyKind.Alias)
          val name = rest.substring(1, scanned.end)
          val boundaryProblem = YamlPropertySyntax.boundaryFailure(scanned, None)
          val trailing = rest.substring(scanned.end).trim
          val flaggedBoundary = boundaryProblem.fold(split.state)(value =>
            problem(
              split.state,
              "uniml.yaml.invalid-alias",
              s"invalid YAML alias at UTF-16 offset ${value.offset}: ${value.message}",
              span,
            )
          )
          val flaggedTrailing =
            if trailing.nonEmpty && name.nonEmpty && boundaryProblem.isEmpty then
              problem(flaggedBoundary, "uniml.yaml.invalid-alias", "a YAML alias cannot have trailing node content", span)
            else flaggedBoundary
          if name.isEmpty || boundaryProblem.nonEmpty then Parsed(flaggedTrailing, nullValue(rest))
          else Parsed(flaggedTrailing, YamlValue.Alias(name))
        else if rest.startsWith("'") then quotedSingle(split.state, rest, span)
        else if rest.startsWith("\"") then quotedDouble(split.state, rest, span)
        else Parsed(split.state, plainScalar(rest, properties.tag))
      applyProperties(valueResult.state, valueResult.value, properties, span)

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

    def quotedSingle(st: ParseState, text: String, span: SourceSpan): Parsed =
      if text.length < 2 || text.last != '\'' then
        Parsed(
          problem(st, "uniml.yaml.invalid-single-quoted", "unterminated single-quoted scalar", span),
          YamlValue.Scalar(YamlScalar.StringValue(text.drop(1), text, ScalarStyle.SingleQuoted), None, None),
        )
      else
        val cooked = text.substring(1, text.length - 1).replace("''", "'")
        Parsed(st, YamlValue.Scalar(YamlScalar.StringValue(cooked, text, ScalarStyle.SingleQuoted), None, None))

    def quotedDouble(st: ParseState, text: String, span: SourceSpan): Parsed =
      if text.length < 2 || text.last != '"' then
        Parsed(
          problem(st, "uniml.yaml.invalid-double-quoted", "unterminated double-quoted scalar", span),
          YamlValue.Scalar(YamlScalar.StringValue(text.drop(1), text, ScalarStyle.DoubleQuoted), None, None),
        )
      else
        decodeDouble(text.substring(1, text.length - 1)) match
          case Some(cooked) => Parsed(st, YamlValue.Scalar(YamlScalar.StringValue(cooked, text, ScalarStyle.DoubleQuoted), None, None))
          case None =>
            Parsed(
              problem(st, "uniml.yaml.invalid-double-quoted", "invalid escape in double-quoted scalar", span),
              YamlValue.Scalar(YamlScalar.StringValue(text.substring(1, text.length - 1), text, ScalarStyle.DoubleQuoted), None, None),
            )

    final case class DirectiveResult(state: ParseState, directive: YamlDirective)

    def parseDirective(st: ParseState, line: Line): DirectiveResult =
      val lexeme = clean(line)
      val body = lexeme.drop(1)
      val split = body.indexWhere(c => isWs(c))
      val name = if split < 0 then body else body.take(split)
      val value = if split < 0 then "" else body.drop(split).trim
      val flagged =
        if name != "YAML" && name != "TAG" then
          problem(st, "uniml.yaml.invalid-directive", s"reserved YAML directive '$name' is preserved but not interpreted", line.span(source), Severity.Warning)
        else st
      DirectiveResult(flagged, YamlDirective(name, value, lexeme, line.span(source)))

    def splitProperties(st0: ParseState, text: String, span: SourceSpan): SplitResult =
      def loop(st: ParseState, rest: String, tag: Option[String], anchor: Option[String]): SplitResult =
        if rest.isEmpty then SplitResult(st, Properties(tag, anchor), rest)
        else if rest.startsWith("!") then
          val scanned = YamlPropertySyntax.scan(rest, 0, YamlPropertyKind.Tag)
          val value = rest.substring(0, scanned.end)
          val flaggedBoundary = YamlPropertySyntax.boundaryFailure(scanned, None).fold(st) { failure =>
            problem(
              st,
              "uniml.yaml.invalid-tag",
              s"invalid YAML tag at UTF-16 offset ${failure.offset}: ${failure.message}",
              span,
            )
          }
          val flaggedDouble =
            if tag.nonEmpty then problem(flaggedBoundary, "uniml.yaml.invalid-tag", "a YAML node cannot have two tags", span)
            else flaggedBoundary
          val expanded = flaggedDouble.tagEnvironment.expand(value) match
            case Right(result) => SplitResult(flaggedDouble, Properties(Some(result), anchor), rest.substring(scanned.end))
            case Left(message) =>
              SplitResult(
                problem(flaggedDouble, "uniml.yaml.invalid-tag", message, span),
                Properties(Some(value), anchor),
                rest.substring(scanned.end),
              )
          if scanned.hadSeparation then loop(expanded.state, expanded.rest.trim, expanded.properties.tag, expanded.properties.anchor)
          else SplitResult(expanded.state, expanded.properties, expanded.rest)
        else if rest.startsWith("&") then
          val scanned = YamlPropertySyntax.scan(rest, 0, YamlPropertyKind.Anchor)
          val value = rest.substring(1, scanned.end)
          val flaggedBoundary = YamlPropertySyntax.boundaryFailure(scanned, None).fold(st) { failure =>
            problem(
              st,
              "uniml.yaml.invalid-anchor",
              s"invalid YAML anchor at UTF-16 offset ${failure.offset}: ${failure.message}",
              span,
            )
          }
          val flaggedDouble =
            if anchor.nonEmpty then problem(flaggedBoundary, "uniml.yaml.invalid-anchor", "a YAML node cannot have two anchors", span)
            else flaggedBoundary
          val nextRest = rest.substring(scanned.end)
          if scanned.hadSeparation then loop(flaggedDouble, nextRest.trim, tag, Some(value))
          else SplitResult(flaggedDouble, Properties(tag, Some(value)), nextRest)
        else SplitResult(st, Properties(tag, anchor), rest)
      loop(st0, text.trim, None, None)

    def applyProperties(st: ParseState, value: YamlValue, properties: Properties, span: SourceSpan): Parsed = value match
      case YamlValue.Mapping(entries, tag, anchor) =>
        Parsed(st, YamlValue.Mapping(entries, properties.tag.orElse(tag), properties.anchor.orElse(anchor)))
      case YamlValue.Sequence(values, tag, anchor) =>
        Parsed(st, YamlValue.Sequence(values, properties.tag.orElse(tag), properties.anchor.orElse(anchor)))
      case YamlValue.Scalar(scalar, tag, anchor) =>
        Parsed(st, YamlValue.Scalar(scalar, properties.tag.orElse(tag), properties.anchor.orElse(anchor)))
      case alias: YamlValue.Alias =>
        if properties.tag.nonEmpty || properties.anchor.nonEmpty then
          Parsed(problem(st, "uniml.yaml.invalid-alias", "aliases cannot have tag or anchor properties", span), alias)
        else Parsed(st, alias)
      case stream: YamlValue.Stream => Parsed(st, stream)

    // The flow machine threads (outer state, local cursor) — FlowStep is its Parsed.
    final case class FlowStep(state: ParseState, cursor: Int, value: YamlValue)

    def flowParse(st0: ParseState, text: String, span: SourceSpan, depth: Int, isSequence: Boolean): Parsed =

      def skipSpaces(cursor: Int): Int =
        if cursor < text.length && isWs(text.charAt(cursor)) then skipSpaces(cursor + 1) else cursor

      def singleEnd(cursor: Int): Int =
        if cursor >= text.length then cursor
        else if text.charAt(cursor) == '\'' then
          if cursor + 1 < text.length && text.charAt(cursor + 1) == '\'' then singleEnd(cursor + 2)
          else cursor + 1
        else singleEnd(cursor + 1)

      def doubleEnd(cursor: Int, escaped: Boolean): Int =
        if cursor >= text.length then cursor
        else
          val char = text.charAt(cursor)
          if escaped then doubleEnd(cursor + 1, false)
          else if char == '\\' then doubleEnd(cursor + 1, true)
          else if char == '"' then cursor + 1
          else doubleEnd(cursor + 1, false)

      def plainNodeEnd(cursor: Int, stopAtColon: Boolean): Int =
        // NOT `!",]}".contains(...)`: a unary op written with NO SPACE directly against a
        // literal (`!",]}"`) is tokenized by this toolchain's parser as one combined
        // "unary-prefixed literal" node — the same adjacency rule that turns `-1` into a
        // NEGATIVE literal rather than `ApplyUnary(-, 1)`, applied here even though `!` means
        // nothing for a `String` — and `.contains(...)` then chains onto that INNER node,
        // which nothing in the Rust backend renders: `error: unsupported expression: Lit.
        // WithUnary (!",]}")`. Explicit parens force the intended grouping regardless.
        if cursor < text.length && !(",]}".contains(text.charAt(cursor))) &&
            !(stopAtColon && text.charAt(cursor) == ':') then plainNodeEnd(cursor + 1, stopAtColon)
        else cursor

      def parseNode(st: ParseState, cursor0: Int, stopAtColon: Boolean): FlowStep =
        val cursor = skipSpaces(cursor0)
        val start = cursor
        if cursor >= text.length then FlowStep(st, cursor, nullValue(""))
        else text.charAt(cursor) match
          case '[' => parseSequence(st, cursor)
          case '{' => parseMapping(st, cursor)
          case '\'' =>
            val end = singleEnd(cursor + 1)
            val quoted = quotedSingle(st, text.substring(start, end), span)
            FlowStep(quoted.state, end, quoted.value)
          case '"' =>
            val end = doubleEnd(cursor + 1, false)
            val quoted = quotedDouble(st, text.substring(start, end), span)
            FlowStep(quoted.state, end, quoted.value)
          case '*' =>
            val scanned = YamlPropertySyntax.scan(text, cursor, YamlPropertyKind.Alias)
            val name = text.substring(start + 1, scanned.end)
            FlowStep(st, scanned.end, YamlValue.Alias(name))
          case _ =>
            val end = plainNodeEnd(cursor, stopAtColon)
            val inline = parseInline(st, text.substring(start, end).trim, span, depth + 1)
            FlowStep(inline.state, end, inline.value)

      final case class FlowSeqStep(state: ParseState, cursor: Int, values: Vector[YamlValue])

      def parseSequence(st0: ParseState, cursor0: Int): FlowStep =
        def loop(acc: FlowSeqStep): FlowSeqStep =
          if acc.cursor >= text.length || text.charAt(acc.cursor) == ']' then acc
          else
            val node = parseNode(acc.state, acc.cursor, stopAtColon = false)
            val afterNode = skipSpaces(node.cursor)
            if afterNode < text.length && text.charAt(afterNode) == ',' then
              loop(FlowSeqStep(node.state, skipSpaces(afterNode + 1), acc.values :+ node.value))
            else if afterNode < text.length && text.charAt(afterNode) != ']' then
              FlowSeqStep(
                problem(node.state, "uniml.yaml.expected-separator", "expected ',' or ']' in flow sequence", span),
                text.length,
                acc.values :+ node.value,
              )
            else FlowSeqStep(node.state, afterNode, acc.values :+ node.value)
        val walked = loop(FlowSeqStep(st0, skipSpaces(cursor0 + 1), Vector.empty))
        if walked.cursor < text.length && text.charAt(walked.cursor) == ']' then
          FlowStep(walked.state, walked.cursor + 1, YamlValue.Sequence(walked.values, None, None))
        else
          FlowStep(
            problem(walked.state, "uniml.yaml.unclosed-flow", "unclosed flow sequence", span),
            walked.cursor,
            YamlValue.Sequence(walked.values, None, None),
          )

      final case class FlowMapStep(state: ParseState, cursor: Int, entries: Vector[YamlEntry])

      def parseMapping(st0: ParseState, cursor0: Int): FlowStep =
        def loop(acc: FlowMapStep): FlowMapStep =
          if acc.cursor >= text.length || text.charAt(acc.cursor) == '}' then acc
          else
            val key = parseNode(acc.state, acc.cursor, stopAtColon = true)
            val afterKey = skipSpaces(key.cursor)
            if afterKey >= text.length || text.charAt(afterKey) != ':' then
              FlowMapStep(
                problem(key.state, "uniml.yaml.expected-value", "expected ':' in flow mapping", span),
                text.length,
                acc.entries,
              )
            else
              val valueStart = skipSpaces(afterKey + 1)
              val value =
                if valueStart < text.length && text.charAt(valueStart) != ',' && text.charAt(valueStart) != '}' then
                  parseNode(key.state, valueStart, stopAtColon = false)
                else FlowStep(key.state, valueStart, nullValue(""))
              val entries = acc.entries :+ YamlEntry(key.value, value.value, span)
              val afterValue = skipSpaces(value.cursor)
              if afterValue < text.length && text.charAt(afterValue) == ',' then
                loop(FlowMapStep(value.state, skipSpaces(afterValue + 1), entries))
              else if afterValue < text.length && text.charAt(afterValue) != '}' then
                FlowMapStep(
                  problem(value.state, "uniml.yaml.expected-separator", "expected ',' or '}' in flow mapping", span),
                  text.length,
                  entries,
                )
              else FlowMapStep(value.state, afterValue, entries)
        val walked = loop(FlowMapStep(st0, skipSpaces(cursor0 + 1), Vector.empty))
        if walked.cursor < text.length && text.charAt(walked.cursor) == '}' then
          FlowStep(walked.state, walked.cursor + 1, YamlValue.Mapping(walked.entries, None, None))
        else
          FlowStep(
            problem(walked.state, "uniml.yaml.unclosed-flow", "unclosed flow mapping", span),
            walked.cursor,
            YamlValue.Mapping(walked.entries, None, None),
          )

      val stepped = if isSequence then parseSequence(st0, 0) else parseMapping(st0, 0)
      Parsed(stepped.state, stepped.value)

    // ── the document driver ──────────────────────────────────────────────────────────────────────
    final case class DirectivesStep(state: ParseState, directives: Vector[YamlDirective])

    def gatherDirectives(acc: DirectivesStep): DirectivesStep =
      if acc.state.index < lines.size && clean(lines(acc.state.index)).startsWith("%") then
        val result = parseDirective(acc.state, lines(acc.state.index))
        val withTag =
          if result.directive.name == "TAG" then
            YamlTagEnvironment.directiveParts(result.directive.value) match
              case None =>
                problem(
                  result.state,
                  "uniml.yaml.invalid-directive",
                  "a %TAG directive requires exactly one handle and one prefix",
                  result.directive.span,
                )
              case Some(parts) =>
                result.state.tagEnvironment.register(parts._1, parts._2) match
                  case Right(updated) => result.state.copy(tagEnvironment = updated)
                  case Left(message)  => problem(result.state, "uniml.yaml.invalid-directive", message, result.directive.span)
          else result.state
        gatherDirectives(DirectivesStep(
          skipBlank(withTag.copy(index = withTag.index + 1)),
          acc.directives :+ result.directive,
        ))
      else acc

    final case class DocumentsStep(state: ParseState, documents: Vector[YamlDocument])

    def parseDocuments(acc: DocumentsStep): DocumentsStep =
      if acc.state.index >= lines.size then acc
      else
        // per-document tag scope: the environment resets to the defaults at each document boundary
        val fresh = acc.state.copy(tagEnvironment = YamlTagEnvironment.defaults)
        val gathered = gatherDirectives(DirectivesStep(fresh, Vector.empty))
        val directiveValues = gathered.directives
        val st1 = gathered.state
        val explicitStart = st1.index < lines.size && clean(lines(st1.index)) == "---"
        val st2 =
          if directiveValues.nonEmpty && !explicitStart then
            problem(st1, "uniml.yaml.directive-position", "directives must be followed by an explicit document start", currentSpan(st1))
          else st1
        val st3 = if explicitStart then skipBlank(st2.copy(index = st2.index + 1)) else st2

        val documented: DocumentsStep =
          if st3.index >= lines.size then DocumentsStep(st3, acc.documents :+ YamlDocument(None, directiveValues))
          else if clean(lines(st3.index)) == "..." then
            DocumentsStep(st3.copy(index = st3.index + 1), acc.documents :+ YamlDocument(None, directiveValues))
          else if clean(lines(st3.index)) == "---" then
            DocumentsStep(st3, acc.documents :+ YamlDocument(None, directiveValues))
          else
            val startIndex = st3.index
            val parsed = parseValue(st3, indentOf(lines(st3.index)), 0)
            val bumped =
              if parsed.state.index == startIndex then parsed.state.copy(index = parsed.state.index + 1)
              else parsed.state
            val blanked = skipBlank(bumped)
            val consumedEnd =
              if blanked.index < lines.size && clean(lines(blanked.index)) == "..." then blanked.copy(index = blanked.index + 1)
              else blanked
            DocumentsStep(consumedEnd, acc.documents :+ YamlDocument(Some(parsed.value), directiveValues))

        val blanked = skipBlank(documented.state)
        val checked =
          if blanked.index < lines.size && clean(lines(blanked.index)) != "---" && !clean(lines(blanked.index)).startsWith("%") then
            skipBlank(
              problem(blanked, "uniml.yaml.expected-node", "unexpected content after YAML document root", lines(blanked.index).span(source))
                .copy(index = blanked.index + 1)
            )
          else blanked
        parseDocuments(DocumentsStep(checked, documented.documents))

    val initial = ParseState(0, Vector.empty, YamlTagEnvironment.defaults)
    val finished = parseDocuments(DocumentsStep(skipBlank(initial), Vector.empty))
    YamlSemanticResult(YamlValue.Stream(finished.documents), finished.state.diagnostics)

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
      def walk(cursor: Int, chomping: Option[Char], indentation: Option[Int]): Option[(Option[Char], Option[Int])] =
        if cursor >= rest.length || isWs(rest.charAt(cursor)) || rest.charAt(cursor) == '#' then
          Some((chomping, indentation))
        else
          val char = rest.charAt(cursor)
          if (char == '+' || char == '-') && chomping.isEmpty then walk(cursor + 1, Some(char), indentation)
          else if char >= '1' && char <= '9' && indentation.isEmpty then walk(cursor + 1, chomping, Some(char - '0'))
          else None
      walk(1, None, None).map(header => (rest.head, header._1, header._2))

  private def splitPropertiesNoDiagnostic(text: String): (Properties, String) =
    def walk(rest: String, tag: Option[String], anchor: Option[String]): (Properties, String) =
      if rest.isEmpty then Properties(tag, anchor) -> rest
      else if rest.startsWith("!") then
        val scanned = YamlPropertySyntax.scan(rest, 0, YamlPropertyKind.Tag)
        val taken = Some(rest.substring(0, scanned.end))
        val remainder = rest.substring(scanned.end)
        if scanned.hadSeparation then walk(remainder.trim, taken, anchor)
        else Properties(taken, anchor) -> remainder
      else if rest.startsWith("&") then
        val scanned = YamlPropertySyntax.scan(rest, 0, YamlPropertyKind.Anchor)
        val taken = Some(rest.substring(1, scanned.end))
        val remainder = rest.substring(scanned.end)
        if scanned.hadSeparation then walk(remainder.trim, tag, taken)
        else Properties(tag, taken) -> remainder
      else Properties(tag, anchor) -> rest
    walk(text.trim, None, None)

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
    // pieces + one mkString rather than repeated string concatenation, same as every other
    // accumulator in the module; None the moment an escape is malformed
    def walk(cursor: Int, pieces: Vector[String]): Option[Vector[String]] =
      if cursor >= text.length then Some(pieces)
      else
        val char = text.charAt(cursor)
        if char != '\\' then walk(cursor + 1, pieces :+ char.toString)
        else if cursor + 1 >= text.length then None
        else
          text.charAt(cursor + 1) match
            case '0' => walk(cursor + 2, pieces :+ "\u0000")
            case 'a' => walk(cursor + 2, pieces :+ "\u0007")
            case 'b' => walk(cursor + 2, pieces :+ "\b")
            case 't' | '\t' => walk(cursor + 2, pieces :+ "\t")
            case 'n' => walk(cursor + 2, pieces :+ "\n")
            case 'v' => walk(cursor + 2, pieces :+ "\u000B")
            case 'f' => walk(cursor + 2, pieces :+ "\f")
            case 'r' => walk(cursor + 2, pieces :+ "\r")
            case 'e' => walk(cursor + 2, pieces :+ "\u001B")
            case ' ' => walk(cursor + 2, pieces :+ " ")
            case '"' => walk(cursor + 2, pieces :+ "\"")
            case '/' => walk(cursor + 2, pieces :+ "/")
            case '\\' => walk(cursor + 2, pieces :+ "\\")
            case 'N' => walk(cursor + 2, pieces :+ "\u0085")
            case '_' => walk(cursor + 2, pieces :+ "\u00A0")
            case 'L' => walk(cursor + 2, pieces :+ "\u2028")
            case 'P' => walk(cursor + 2, pieces :+ "\u2029")
            case 'x' =>
              parseHexEscape(text, cursor + 2, 2) match
                case Some(value) => walk(cursor + 4, pieces :+ codePointToString(value))
                case None        => None
            case 'u' =>
              parseHexEscape(text, cursor + 2, 4) match
                case Some(value) => walk(cursor + 6, pieces :+ codePointToString(value))
                case None        => None
            case 'U' =>
              parseHexEscape(text, cursor + 2, 8) match
                case Some(value) if value <= 0x10ffff => walk(cursor + 10, pieces :+ codePointToString(value))
                case _                                => None
            case _ => None
    walk(0, Vector.empty).map(_.mkString)

  private def parseHexEscape(text: String, start: Int, length: Int): Option[Int] =
    if start + length > text.length then None
    else
      def fold(cursor: Int, value: Int): Option[Int] =
        if cursor >= start + length then Some(value)
        else
          val digit = hexDigit(text.charAt(cursor))
          if digit < 0 then None else fold(cursor + 1, value * 16 + digit)
      fold(start, 0)

  private def codePointToString(value: Int): String =
    if value <= 0xffff then value.toChar.toString
    else
      val adjusted = value - 0x10000
      val high = ((adjusted >>> 10) + 0xd800).toChar
      val low = ((adjusted & 0x3ff) + 0xdc00).toChar
      high.toString + low.toString

  private def foldLines(values: Vector[String]): String =
    values.zipWithIndex.map { (value, position) =>
      if position + 1 < values.size then
        value + (if value.isEmpty || values(position + 1).isEmpty then "\n" else " ")
      else value
    }.mkString

  private def splitLines(input: String): Vector[Line] =
    def walk(cursor: Int, start: Int, line: Int, offset: Int, result: Vector[Line]): Vector[Line] =
      if cursor >= input.length then
        if start < input.length || input.isEmpty then result :+ Line(input.substring(start), "", line, offset)
        else result
      else
        val char = input.charAt(cursor)
        if char == '\r' || char == '\n' then
          val breakWidth = if char == '\r' && cursor + 1 < input.length && input.charAt(cursor + 1) == '\n' then 2 else 1
          val raw = input.substring(start, cursor)
          val lineBreak = input.substring(cursor, cursor + breakWidth)
          walk(cursor + breakWidth, cursor + breakWidth, line + 1, offset + Unicode.codePointCount(raw) + breakWidth,
            result :+ Line(raw, lineBreak, line, offset))
        else walk(cursor + 1, start, line, offset, result)
    walk(0, 0, 1, 0, Vector.empty)

  private def stripComment(text: String): String =
    def walk(cursor: Int, single: Boolean, double: Boolean): Int = // the comment's index, or -1
      if cursor >= text.length then -1
      else
        text.charAt(cursor) match
          case '!' | '&' | '*' if !single && !double =>
            val kind = text.charAt(cursor) match
              case '!' => YamlPropertyKind.Tag
              case '&' => YamlPropertyKind.Anchor
              case _   => YamlPropertyKind.Alias
            val scanned = YamlPropertySyntax.scan(text, cursor, kind)
            walk((if scanned.end > cursor then scanned.end - 1 else cursor) + 1, single, double)
          case '\'' if !double =>
            if single && cursor + 1 < text.length && text.charAt(cursor + 1) == '\'' then walk(cursor + 2, single, double)
            else walk(cursor + 1, !single, double)
          case '"' if !single => walk(cursor + 1, single, !double)
          case '\\' if double && cursor + 1 < text.length => walk(cursor + 2, single, double)
          case '#' if !single && !double && (cursor == 0 || isWs(text.charAt(cursor - 1))) => cursor
          case _ => walk(cursor + 1, single, double)
    val comment = walk(0, false, false)
    if comment < 0 then text else text.take(comment)

  private def findKeyColon(text: String): Int =
    def wsEnd(cursor: Int): Int =
      if cursor < text.length && isWs(text.charAt(cursor)) then wsEnd(cursor + 1) else cursor
    def properties(cursor: Int): Int =
      if cursor >= text.length then cursor
      else
        val kind = text.charAt(cursor) match
          case '!' => Some(YamlPropertyKind.Tag)
          case '&' => Some(YamlPropertyKind.Anchor)
          case '*' => Some(YamlPropertyKind.Alias)
          case _   => None
        kind match
          case None => cursor
          case Some(propertyKind) =>
            val scanned = YamlPropertySyntax.scan(text, cursor, propertyKind)
            if scanned.hadSeparation then
              val next = wsEnd(scanned.end)
              if propertyKind != YamlPropertyKind.Alias && next < text.length &&
                  (text.charAt(next) == '!' || text.charAt(next) == '&') then properties(next)
              else next
            else scanned.end
    def scan(cursor: Int, single: Boolean, double: Boolean, flowDepth: Int): Int =
      if cursor >= text.length then -1
      else
        text.charAt(cursor) match
          case '\'' if !double => scan(cursor + 1, !single, double, flowDepth)
          case '"' if !single => scan(cursor + 1, single, !double, flowDepth)
          case '\\' if double && cursor + 1 < text.length => scan(cursor + 2, single, double, flowDepth)
          case '[' | '{' if !single && !double => scan(cursor + 1, single, double, flowDepth + 1)
          case ']' | '}' if !single && !double => scan(cursor + 1, single, double, math.max(0, flowDepth - 1))
          case ':' if !single && !double && flowDepth == 0 &&
              (cursor + 1 >= text.length || isWs(text.charAt(cursor + 1))) => cursor
          case _ => scan(cursor + 1, single, double, flowDepth)
    scan(properties(wsEnd(0)), false, false, 0)

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
    from >= s.length || (pred(s.charAt(from)) && allFrom(s, from + 1, pred))

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

  // ^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][-+]?[0-9]+)?$ — one helper per regex group,
  // -1 the failure sentinel each group propagates (the validNumber shape)
  private def matchesJsonFloat(s: String): Boolean =
    val n = s.length
    def digitsEnd(i: Int): Int = if i < n && isDigit(s.charAt(i)) then digitsEnd(i + 1) else i
    def intPart(i: Int): Int =
      if i >= n then -1
      else if s.charAt(i) == '0' then i + 1
      else if s.charAt(i) >= '1' && s.charAt(i) <= '9' then digitsEnd(i + 1)
      else -1
    def fracPart(i: Int): Int =
      if i < 0 then -1
      else if i < n && s.charAt(i) == '.' then
        val end = digitsEnd(i + 1)
        if end == i + 1 then -1 else end
      else i
    def expPart(i: Int): Int =
      if i < 0 then -1
      else if i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E') then
        val signed = if i + 1 < n && (s.charAt(i + 1) == '+' || s.charAt(i + 1) == '-') then i + 2 else i + 1
        val end = digitsEnd(signed)
        if end == signed then -1 else end
      else i
    val from = if s.nonEmpty && s.charAt(0) == '-' then 1 else 0
    expPart(fracPart(intPart(from))) == n

  // ^(?:[-+]?(?:[0-9]+\.[0-9]*|\.[0-9]+)(?:[eE][-+]?[0-9]+)?|[-+]?[0-9]+[eE][-+]?[0-9]+|[-+]?\.(?:inf|Inf|INF)|\.(?:nan|NaN|NAN))$
  private def matchesCoreFloat(s: String): Boolean =
    if s == ".nan" || s == ".NaN" || s == ".NAN" then true
    else
      val i0 = if s.nonEmpty && (s.charAt(0) == '+' || s.charAt(0) == '-') then 1 else 0
      val n = s.length
      val rest = s.substring(i0)
      if rest == ".inf" || rest == ".Inf" || rest == ".INF" then true
      else if i0 >= n then false
      else
        def digitsEnd(i: Int): Int = if i < n && isDigit(s.charAt(i)) then digitsEnd(i + 1) else i
        // after the mantissa: an exponent makes the float; otherwise the dot must have
        def expTail(i: Int, hasDot: Boolean): Boolean =
          if i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E') then
            val signed = if i + 1 < n && (s.charAt(i + 1) == '+' || s.charAt(i + 1) == '-') then i + 2 else i + 1
            val end = digitsEnd(signed)
            end != signed && end == n
          else hasDot && i == n
        val intEnd = digitsEnd(i0)
        val intDigits = intEnd - i0
        if intEnd < n && s.charAt(intEnd) == '.' then
          val fracEnd = digitsEnd(intEnd + 1)
          val fracDigits = fracEnd - (intEnd + 1)
          if intDigits < 1 && fracDigits < 1 then false
          else expTail(fracEnd, hasDot = true)
        else if intDigits < 1 then false
        else expTail(intEnd, hasDot = false)
