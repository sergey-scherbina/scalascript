package scalascript.uniml.dialect.yaml

import scalascript.uniml.*

private[yaml] final case class YamlLexResult(
    tokens: Vector[SourceToken],
    diagnostics: Vector[Diagnostic],
)

/** The scanner's whole state, threaded as a pure fold over the input — one field per former `var`,
  * in the order the imperative original declared them, so a historical diff matches one to one. */
private[yaml] final case class YamlLexState(
    tokens: Vector[SourceToken],
    diagnostics: Vector[Diagnostic],
    index: Int,
    position: SourcePosition,
    tokenId: Long,
    atLineStart: Boolean,
    firstContent: Boolean,
    lastSpan: Option[SourceSpan],
    flowClosers: Vector[Char],
    linePlainScalarActive: Boolean,
    // Preserve the node context even while the legacy token stream splits block-plain punctuation.
    plainFlowDepth: Int,
    lineIndent: Int,
    lineSawValueIndicator: Boolean,
    lineHasValueContent: Boolean,
    lineValueStartedPlain: Boolean,
    plainContinuationIndent: Option[Int],
    inPlainContinuation: Boolean,
)

/** Pure YAML lexer: a single fold over the whole source. Scanning state is an immutable
  * [[YamlLexState]] record threaded `step(state, chunk)`-style — no `var` crosses a step
  * boundary. Each scanner first FINDS its token's end with a pure walk over the input, then
  * makes one state transition; the imperative shell's `while` is a tail-recursive drive.
  * `advance` and `validateLines` keep local imperative cursors DELIBERATELY (see each). */
private[yaml] object YamlLexer:
  def scan(source: SourceId, input: String, limits: YamlLimits): YamlLexResult =

    def report(s: YamlLexState, code: String, message: String, severity: Severity, span: SourceSpan): YamlLexState =
      s.copy(diagnostics = s.diagnostics :+ Diagnostic(code, message, severity, Some(span), Some(YamlDialect.id)))

    def tokensSpanLast(s: YamlLexState): SourceSpan =
      s.lastSpan.getOrElse(SourceSpan(source, s.position, s.position))

    // The one place the line-tracking flags move, exactly as the imperative emitRange was the one
    // place they were assigned. The kind/channel conditions are UNCHANGED; only the spelling is.
    def emitRange(s: YamlLexState, start: Int, end: Int, kind: String, channel: TokenChannel): YamlLexState =
      val lexeme = input.substring(start, end)
      val startPosition = s.position
      val nextPosition = advance(startPosition, lexeme)
      val span = SourceSpan(source, startPosition, nextPosition)
      val contentful = kind != "yaml.indentation" && kind != "yaml.whitespace"
      val syntactic = channel == TokenChannel.Syntax || channel == TokenChannel.Error
      val valueContentToken = syntactic && kind != "yaml.value-indicator" &&
        s.lineSawValueIndicator &&
        kind != "yaml.indentation" && kind != "yaml.whitespace" &&
        kind != "yaml.comment" && kind != "yaml.line-break"
      s.copy(
        index = end,
        tokens = s.tokens :+ SourceToken(s.tokenId, kind, lexeme, span, channel),
        lastSpan = Some(span),
        tokenId = s.tokenId + 1,
        position = nextPosition,
        atLineStart = if contentful then false else s.atLineStart,
        firstContent =
          if contentful && kind != "yaml.comment" && kind != "yaml.line-break" then false else s.firstContent,
        lineSawValueIndicator =
          if syntactic && kind == "yaml.value-indicator" then true else s.lineSawValueIndicator,
        lineValueStartedPlain =
          if valueContentToken && !s.lineHasValueContent then kind == "yaml.scalar.plain"
          else s.lineValueStartedPlain,
        lineHasValueContent = if valueContentToken then true else s.lineHasValueContent,
      )

    def emitWidth(s: YamlLexState, width: Int, kind: String, channel: TokenChannel): YamlLexState =
      emitRange(s, s.index, s.index + width, kind, channel)

    def markerAt(start: Int, marker: String): Boolean =
      input.startsWith(marker, start) && {
        val end = start + marker.length
        end >= input.length || isSeparation(input.charAt(end)) || input.charAt(end) == '#'
      }

    def indicatorAt(at: Int): Boolean =
      val next = at + 1
      next >= input.length || isSeparation(input.charAt(next)) || isFlow(input.charAt(next))

    def isBlockIndicator(s: YamlLexState, char: Char): Boolean =
      (char == ':' || char == '-' || char == '?') && indicatorAt(s.index)

    def codePointOffset(utf16Index: Int): Int = Unicode.codePointCount(input.substring(0, utf16Index))

    def scalarLimit(s: YamlLexState, start: Int, end: Int): YamlLexState =
      if Unicode.codePointCount(input.substring(start, end)) > limits.maxScalarCodePoints then
        report(
          s,
          "uniml.yaml.limit.scalar",
          s"YAML scalar exceeds the ${limits.maxScalarCodePoints} code-point limit",
          Severity.Fatal,
          tokensSpanLast(s),
        )
      else s

    def scanBreak(s: YamlLexState): YamlLexState =
      val nextContinuationIndent =
        Option.when(s.lineHasValueContent && s.lineValueStartedPlain)(s.lineIndent)
      val width =
        if input.charAt(s.index) == '\r' && s.index + 1 < input.length && input.charAt(s.index + 1) == '\n' then 2
        else 1
      emitWidth(s, width, "yaml.line-break", TokenChannel.Trivia).copy(
        atLineStart = true,
        firstContent = true,
        linePlainScalarActive = false,
        plainFlowDepth = 0,
        lineIndent = 0,
        lineSawValueIndicator = false,
        lineHasValueContent = false,
        lineValueStartedPlain = false,
        plainContinuationIndent = nextContinuationIndent,
        inPlainContinuation = false,
      )

    // Pure end-finder: where does a run of blanks end, and did it contain a tab?
    def blankEnd(from: Int, sawTab: Boolean): (Int, Boolean) =
      if from < input.length && (input.charAt(from) == ' ' || input.charAt(from) == '\t') then
        blankEnd(from + 1, sawTab || input.charAt(from) == '\t')
      else (from, sawTab)

    def scanIndentation(s: YamlLexState): YamlLexState =
      val start = s.index
      val (end, hasTab) = blankEnd(start, sawTab = false)
      val emitted = emitRange(s, start, end, "yaml.indentation", if hasTab then TokenChannel.Error else TokenChannel.Trivia)
      val indentationLength = end - start
      val s1 = emitted.copy(
        lineIndent = indentationLength,
        inPlainContinuation = emitted.plainContinuationIndent.exists(parent => indentationLength > parent),
      )
      val s2 =
        if hasTab then
          report(s1, "uniml.yaml.tab-indentation", "tabs are not allowed in YAML indentation", Severity.Error, tokensSpanLast(s1))
        else s1
      val s3 =
        if indentationLength > limits.maxIndentation then
          report(
            s2,
            "uniml.yaml.limit.indentation",
            s"indentation exceeds the ${limits.maxIndentation} character limit",
            Severity.Fatal,
            tokensSpanLast(s2),
          )
        else s2
      s3.copy(atLineStart = false)

    def scanWhitespace(s: YamlLexState): YamlLexState =
      val (end, _) = blankEnd(s.index, sawTab = false)
      emitRange(s, s.index, end, "yaml.whitespace", TokenChannel.Trivia)

    def lineEnd(from: Int): Int =
      if from < input.length && !isBreak(input.charAt(from)) then lineEnd(from + 1) else from

    def scanToLineEnd(s: YamlLexState, kind: String, channel: TokenChannel): YamlLexState =
      emitRange(s, s.index, lineEnd(s.index), kind, channel)

    // Pure end-finders for the quoted forms: the end index plus whether the closer was found.
    def singleQuotedEnd(from: Int): (Int, Boolean) =
      if from >= input.length then (from, false)
      else if input.charAt(from) == '\'' then
        if from + 1 < input.length && input.charAt(from + 1) == '\'' then singleQuotedEnd(from + 2)
        else (from + 1, true)
      else singleQuotedEnd(from + 1)

    def scanSingleQuoted(s: YamlLexState): YamlLexState =
      val start = s.index
      val (end, closed) = singleQuotedEnd(start + 1)
      val emitted = emitRange(s, start, end, "yaml.scalar.single", if closed then TokenChannel.Syntax else TokenChannel.Error)
      val reported =
        if !closed then
          report(emitted, "uniml.yaml.invalid-single-quoted", "unterminated single-quoted scalar", Severity.Error, tokensSpanLast(emitted))
        else emitted
      scalarLimit(reported, start, end)

    def doubleQuotedEnd(from: Int): (Int, Boolean) =
      if from >= input.length then (from, false)
      else input.charAt(from) match
        case '\\' if from + 1 < input.length => doubleQuotedEnd(from + 2)
        case '"'                              => (from + 1, true)
        case _                                => doubleQuotedEnd(from + 1)

    def scanDoubleQuoted(s: YamlLexState): YamlLexState =
      val start = s.index
      val (end, closed) = doubleQuotedEnd(start + 1)
      val emitted = emitRange(s, start, end, "yaml.scalar.double", if closed then TokenChannel.Syntax else TokenChannel.Error)
      val reported =
        if !closed then
          report(emitted, "uniml.yaml.invalid-double-quoted", "unterminated double-quoted scalar", Severity.Error, tokensSpanLast(emitted))
        else emitted
      scalarLimit(reported, start, end)

    def scanProperty(s: YamlLexState, kind: String): YamlLexState =
      val start = s.index
      val propertyKind = kind match
        case "yaml.tag"    => YamlPropertyKind.Tag
        case "yaml.anchor" => YamlPropertyKind.Anchor
        case _             => YamlPropertyKind.Alias
      val scanned = YamlPropertySyntax.scan(input, start, propertyKind)
      val failure =
        if s.inPlainContinuation then None
        else YamlPropertySyntax.boundaryFailure(scanned, s.flowClosers.lastOption)
      val channel =
        if failure.nonEmpty then TokenChannel.Error else TokenChannel.Syntax
      val emitted = emitRange(s, start, scanned.end, kind, channel)
      failure.fold(emitted) { problem =>
        val suffix = kind.stripPrefix("yaml.")
        report(
          emitted,
          s"uniml.yaml.invalid-$suffix",
          s"invalid YAML $suffix at UTF-16 offset ${problem.offset}: ${problem.message}",
          Severity.Error,
          tokensSpanLast(emitted),
        )
      }

    def scanFlow(s: YamlLexState, char: Char): YamlLexState =
      val preserveBlockPlain = s.linePlainScalarActive && s.plainFlowDepth == 0
      val emitted = emitWidth(s, 1, flowKind(char), TokenChannel.Syntax)
      val withClosers = char match
        case '[' => emitted.copy(flowClosers = emitted.flowClosers :+ ']')
        case '{' => emitted.copy(flowClosers = emitted.flowClosers :+ '}')
        case ']' | '}' if emitted.flowClosers.lastOption.contains(char) =>
          emitted.copy(flowClosers = emitted.flowClosers.dropRight(1))
        case _ => emitted
      if !preserveBlockPlain then withClosers.copy(linePlainScalarActive = false) else withClosers

    def blockHeaderEnd(from: Int): Int =
      if from < input.length && !isBreak(input.charAt(from)) && input.charAt(from) != '#' &&
        input.charAt(from) != ' ' && input.charAt(from) != '\t'
      then blockHeaderEnd(from + 1)
      else from

    def scanBlockHeader(s: YamlLexState, style: Char): YamlLexState =
      val start = s.index
      val end = blockHeaderEnd(start + 1)
      emitRange(s, start, end, if style == '|' then "yaml.scalar.literal" else "yaml.scalar.folded", TokenChannel.Syntax)

    def plainEnd(from: Int): Int =
      if from >= input.length then from
      else
        val char = input.charAt(from)
        if isSeparation(char) || isFlow(char) || char == ':' then from
        else if (char == '-' || char == '?') && indicatorAt(from) then from
        else plainEnd(from + 1)

    def scanPlain(s: YamlLexState): YamlLexState =
      val start = s.index
      val found = plainEnd(start)
      val end = if found == start then start + 1 else found
      val emitted = emitRange(s, start, end, "yaml.scalar.plain", TokenChannel.Syntax)
      val marked = emitted.copy(
        plainFlowDepth = if !s.linePlainScalarActive then s.flowClosers.size else emitted.plainFlowDepth,
        linePlainScalarActive = true,
      )
      scalarLimit(marked, start, end)

    def scanOne(s: YamlLexState): YamlLexState =
      val char = input.charAt(s.index)
      if isBreak(char) then scanBreak(s)
      else if s.atLineStart && (char == ' ' || char == '\t') then scanIndentation(s)
      else if char == ' ' || char == '\t' then scanWhitespace(s)
      else if s.firstContent && s.position.column == 1 && char == '%' then scanToLineEnd(s, "yaml.directive", TokenChannel.Syntax)
      else if s.firstContent && s.position.column == 1 && markerAt(s.index, "---") then emitWidth(s, 3, "yaml.document-start", TokenChannel.Syntax)
      else if s.firstContent && s.position.column == 1 && markerAt(s.index, "...") then emitWidth(s, 3, "yaml.document-end", TokenChannel.Syntax)
      else if char == '#' then scanToLineEnd(s, "yaml.comment", TokenChannel.Comment)
      else if char == '\'' then scanSingleQuoted(s)
      else if char == '"' then scanDoubleQuoted(s)
      else if s.linePlainScalarActive && (char == '!' || char == '&' || char == '*') then scanPlain(s)
      else if char == '!' then scanProperty(s, "yaml.tag")
      else if char == '&' then scanProperty(s, "yaml.anchor")
      else if char == '*' then scanProperty(s, "yaml.alias")
      else if char == '|' || char == '>' then scanBlockHeader(s, char)
      else if isFlow(char) then scanFlow(s, char)
      else if isBlockIndicator(s, char) then
        val emitted = emitWidth(s, 1, blockKind(char), TokenChannel.Syntax)
        if char == ':' then emitted.copy(linePlainScalarActive = false) else emitted
      else scanPlain(s)

    // Line-length and character validation over the RAW input. Two straight-line walks; the
    // earlier entry parked them imperative on the validNumber argument, and the stage-10 closing
    // pass converted them anyway: item 10 allows parking only on a MEASUREMENT, and a
    // shape-preserving recursion mirrors the walk exactly as the loop did.
    def validateLines(s0: YamlLexState): YamlLexState =
      def lines(s: YamlLexState, lineStart: Int, cursor: Int, lineNumber: Int): YamlLexState =
        if cursor > input.length then s
        else
          val atEnd = cursor == input.length
          val atBreak = !atEnd && isBreak(input.charAt(cursor))
          if atEnd || atBreak then
            val text = input.substring(lineStart, cursor)
            val count = Unicode.codePointCount(text)
            val s1 =
              if count > limits.maxLineCodePoints then
                val start = SourcePosition(codePointOffset(lineStart), lineNumber, 1)
                val end = SourcePosition(start.offset + count, lineNumber, count + 1)
                report(
                  s,
                  "uniml.yaml.limit.line",
                  s"YAML line exceeds the ${limits.maxLineCodePoints} code-point limit",
                  Severity.Fatal,
                  SourceSpan(source, start, end),
                )
              else s
            if atBreak then
              val next =
                if input.charAt(cursor) == '\r' && cursor + 1 < input.length && input.charAt(cursor + 1) == '\n' then cursor + 2
                else cursor + 1
              lines(s1, next, next, lineNumber + 1)
            else lines(s1, lineStart, cursor + 1, lineNumber)
          else lines(s, lineStart, cursor + 1, lineNumber)

      def chars(s: YamlLexState, i: Int, pos: SourcePosition): YamlLexState =
        if i >= input.length then s
        else
          val char = input.charAt(i)
          val width =
            if Unicode.isHighSurrogate(char) && i + 1 < input.length && Unicode.isLowSurrogate(input.charAt(i + 1)) then 2
            else 1
          val lexeme = input.substring(i, i + width)
          val next = advance(pos, lexeme)
          val s1 =
            if (Unicode.isHighSurrogate(char) && width == 1) || Unicode.isLowSurrogate(char) then
              report(
                s,
                "uniml.yaml.invalid-character",
                "YAML source contains an unpaired UTF-16 surrogate",
                Severity.Error,
                SourceSpan(source, pos, next),
              )
            else if char < ' ' && char != '\t' && char != '\r' && char != '\n' then
              report(
                s,
                "uniml.yaml.invalid-character",
                f"YAML source contains forbidden control character U+${char.toInt}%04X",
                Severity.Error,
                SourceSpan(source, pos, next),
              )
            else s
          chars(s1, i + width, next)
      chars(lines(s0, 0, 0, 1), 0, SourcePosition.Start)

    // The drive: tail-recursive, so the depth is O(1) on both scalac and Scala.js.
    def drive(s: YamlLexState): YamlLexState =
      if s.index >= input.length then s else drive(scanOne(s))

    val initial = YamlLexState(
      tokens = Vector.empty,
      diagnostics = Vector.empty,
      index = 0,
      position = SourcePosition.Start,
      tokenId = 0L,
      atLineStart = true,
      firstContent = true,
      lastSpan = None,
      flowClosers = Vector.empty,
      linePlainScalarActive = false,
      plainFlowDepth = 0,
      lineIndent = 0,
      lineSawValueIndicator = false,
      lineHasValueContent = false,
      lineValueStartedPlain = false,
      plainContinuationIndent = None,
      inPlainContinuation = false,
    )
    val finished = drive(validateLines(initial))
    YamlLexResult(finished.tokens, finished.diagnostics)

  private def isFlow(char: Char): Boolean = char == '[' || char == ']' || char == '{' || char == '}' || char == ','

  private def isSeparation(char: Char): Boolean = char == ' ' || char == '\t' || isBreak(char)

  private def isBreak(char: Char): Boolean = char == '\r' || char == '\n'

  private def blockKind(char: Char): String = char match
    case ':' => "yaml.value-indicator"
    case '-' => "yaml.sequence-indicator"
    case '?' => "yaml.explicit-key"
    case _   => "yaml.invalid"

  private def flowKind(char: Char): String = char match
    case '[' | '{' => "yaml.flow-open"
    case ']' | '}' => "yaml.flow-close"
    case ','       => "yaml.flow-separator"
    case _         => "yaml.invalid"

  // Position arithmetic over one lexeme — the same index recursion Unicode.advance now uses
  // (this one also folds CRLF as a single line break, which is why it is not that function).
  private def advance(start: SourcePosition, text: String): SourcePosition =
    def walk(cursor: Int, offset: Int, line: Int, column: Int): SourcePosition =
      if cursor >= text.length then SourcePosition(offset, line, column)
      else
        val char = text.charAt(cursor)
        if char == '\r' then
          if cursor + 1 < text.length && text.charAt(cursor + 1) == '\n' then walk(cursor + 2, offset + 2, line + 1, 1)
          else walk(cursor + 1, offset + 1, line + 1, 1)
        else if char == '\n' then walk(cursor + 1, offset + 1, line + 1, 1)
        else
          val width =
            if Unicode.isHighSurrogate(char) && cursor + 1 < text.length && Unicode.isLowSurrogate(text.charAt(cursor + 1)) then 2
            else 1
          walk(cursor + width, offset + 1, line, column + 1)
    walk(0, start.offset, start.line, start.column)
