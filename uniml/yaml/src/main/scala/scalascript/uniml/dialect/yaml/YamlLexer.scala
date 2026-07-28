package scalascript.uniml.dialect.yaml

import scalascript.uniml.*

private[yaml] final case class YamlLexResult(
    tokens: Vector[SourceToken],
    diagnostics: Vector[Diagnostic],
)

/** Pure YAML lexer: a single fold over the whole source. All scanning state lives
  * in local `var`s inside `scan` (no mutable object fields), with a local
  * imperative shell and immutable `Vector` accumulation; the state-touching
  * helpers are nested defs over those locals, pure combinators stay top-level. */
private[yaml] object YamlLexer:
  def scan(source: SourceId, input: String, limits: YamlLimits): YamlLexResult =
    var tokens: Vector[SourceToken] = Vector.empty
    var diagnostics: Vector[Diagnostic] = Vector.empty
    var index = 0
    var position = SourcePosition.Start
    var tokenId = 0L
    var atLineStart = true
    var firstContent = true
    var lastSpan: Option[SourceSpan] = None
    var flowClosers: Vector[Char] = Vector.empty
    var linePlainScalarActive = false
    // Preserve the node context even while the legacy token stream splits block-plain punctuation.
    var plainFlowDepth = 0
    var lineIndent = 0
    var lineSawValueIndicator = false
    var lineHasValueContent = false
    var lineValueStartedPlain = false
    var plainContinuationIndent: Option[Int] = None
    var inPlainContinuation = false

    def report(code: String, message: String, severity: Severity, span: SourceSpan): Unit =
      diagnostics = diagnostics :+ Diagnostic(code, message, severity, Some(span), Some(YamlDialect.id))

    def tokensSpanLast(): SourceSpan =
      lastSpan.getOrElse(SourceSpan(source, position, position))

    def emitRange(start: Int, end: Int, kind: String, channel: TokenChannel): Unit =
      val lexeme = input.substring(start, end)
      val startPosition = position
      position = advance(position, lexeme)
      val span = SourceSpan(source, startPosition, position)
      tokens = tokens :+ SourceToken(tokenId, kind, lexeme, span, channel)
      lastSpan = Some(span)
      tokenId += 1
      if kind != "yaml.indentation" && kind != "yaml.whitespace" then
        atLineStart = false
        if kind != "yaml.comment" && kind != "yaml.line-break" then firstContent = false
      if channel == TokenChannel.Syntax || channel == TokenChannel.Error then
        if kind == "yaml.value-indicator" then lineSawValueIndicator = true
        else if lineSawValueIndicator &&
            kind != "yaml.indentation" &&
            kind != "yaml.whitespace" &&
            kind != "yaml.comment" &&
            kind != "yaml.line-break" then
          if !lineHasValueContent then lineValueStartedPlain = kind == "yaml.scalar.plain"
          lineHasValueContent = true

    def emitWidth(width: Int, kind: String, channel: TokenChannel): Unit =
      val start = index
      index += width
      emitRange(start, index, kind, channel)

    def markerAt(start: Int, marker: String): Boolean =
      input.startsWith(marker, start) && {
        val end = start + marker.length
        end >= input.length || isSeparation(input.charAt(end)) || input.charAt(end) == '#'
      }

    def indicatorAt(at: Int): Boolean =
      val next = at + 1
      next >= input.length || isSeparation(input.charAt(next)) || isFlow(input.charAt(next))

    def isBlockIndicator(char: Char): Boolean =
      (char == ':' || char == '-' || char == '?') && indicatorAt(index)

    def codePointOffset(utf16Index: Int): Int = Unicode.codePointCount(input.substring(0, utf16Index))

    def scalarLimit(start: Int, end: Int): Unit =
      if Unicode.codePointCount(input.substring(start, end)) > limits.maxScalarCodePoints then
        report(
          "uniml.yaml.limit.scalar",
          s"YAML scalar exceeds the ${limits.maxScalarCodePoints} code-point limit",
          Severity.Fatal,
          tokensSpanLast(),
        )

    def scanBreak(): Unit =
      val nextContinuationIndent =
        Option.when(lineHasValueContent && lineValueStartedPlain)(lineIndent)
      val width = if input.charAt(index) == '\r' && index + 1 < input.length && input.charAt(index + 1) == '\n' then 2 else 1
      emitWidth(width, "yaml.line-break", TokenChannel.Trivia)
      atLineStart = true
      firstContent = true
      linePlainScalarActive = false
      plainFlowDepth = 0
      lineIndent = 0
      lineSawValueIndicator = false
      lineHasValueContent = false
      lineValueStartedPlain = false
      plainContinuationIndent = nextContinuationIndent
      inPlainContinuation = false

    def scanIndentation(): Unit =
      val start = index
      var hasTab = false
      while index < input.length && (input.charAt(index) == ' ' || input.charAt(index) == '\t') do
        hasTab ||= input.charAt(index) == '\t'
        index += 1
      emitRange(start, index, "yaml.indentation", if hasTab then TokenChannel.Error else TokenChannel.Trivia)
      val indentation = input.substring(start, index)
      lineIndent = indentation.length
      inPlainContinuation = plainContinuationIndent.exists(parent => indentation.length > parent)
      if hasTab then report("uniml.yaml.tab-indentation", "tabs are not allowed in YAML indentation", Severity.Error, tokensSpanLast())
      if indentation.length > limits.maxIndentation then
        report(
          "uniml.yaml.limit.indentation",
          s"indentation exceeds the ${limits.maxIndentation} character limit",
          Severity.Fatal,
          tokensSpanLast(),
        )
      atLineStart = false

    def scanWhitespace(): Unit =
      val start = index
      while index < input.length && (input.charAt(index) == ' ' || input.charAt(index) == '\t') do index += 1
      emitRange(start, index, "yaml.whitespace", TokenChannel.Trivia)

    def scanToLineEnd(kind: String, channel: TokenChannel): Unit =
      val start = index
      while index < input.length && !isBreak(input.charAt(index)) do index += 1
      emitRange(start, index, kind, channel)

    def scanSingleQuoted(): Unit =
      val start = index
      index += 1
      var closed = false
      while index < input.length && !closed do
        if input.charAt(index) == '\'' then
          if index + 1 < input.length && input.charAt(index + 1) == '\'' then index += 2
          else
            index += 1
            closed = true
        else index += 1
      emitRange(start, index, "yaml.scalar.single", if closed then TokenChannel.Syntax else TokenChannel.Error)
      if !closed then report("uniml.yaml.invalid-single-quoted", "unterminated single-quoted scalar", Severity.Error, tokensSpanLast())
      scalarLimit(start, index)

    def scanDoubleQuoted(): Unit =
      val start = index
      index += 1
      var closed = false
      while index < input.length && !closed do
        input.charAt(index) match
          case '\\' if index + 1 < input.length => index += 2
          case '"' => index += 1; closed = true
          case _   => index += 1
      emitRange(start, index, "yaml.scalar.double", if closed then TokenChannel.Syntax else TokenChannel.Error)
      if !closed then report("uniml.yaml.invalid-double-quoted", "unterminated double-quoted scalar", Severity.Error, tokensSpanLast())
      scalarLimit(start, index)

    def scanProperty(kind: String): Unit =
      val start = index
      val propertyKind = kind match
        case "yaml.tag"    => YamlPropertyKind.Tag
        case "yaml.anchor" => YamlPropertyKind.Anchor
        case _             => YamlPropertyKind.Alias
      val scanned = YamlPropertySyntax.scan(input, start, propertyKind)
      index = scanned.end
      val failure =
        if inPlainContinuation then None
        else YamlPropertySyntax.boundaryFailure(scanned, flowClosers.lastOption)
      val channel =
        if failure.nonEmpty then TokenChannel.Error else TokenChannel.Syntax
      emitRange(start, index, kind, channel)
      failure.foreach { problem =>
        val suffix = kind.stripPrefix("yaml.")
        report(
          s"uniml.yaml.invalid-$suffix",
          s"invalid YAML $suffix at UTF-16 offset ${problem.offset}: ${problem.message}",
          Severity.Error,
          tokensSpanLast(),
        )
      }

    def scanFlow(char: Char): Unit =
      val preserveBlockPlain = linePlainScalarActive && plainFlowDepth == 0
      emitWidth(1, flowKind(char), TokenChannel.Syntax)
      char match
        case '[' => flowClosers = flowClosers :+ ']'
        case '{' => flowClosers = flowClosers :+ '}'
        case ']' | '}' if flowClosers.lastOption.contains(char) =>
          flowClosers = flowClosers.dropRight(1)
        case _ => ()
      if !preserveBlockPlain then linePlainScalarActive = false

    def scanBlockHeader(style: Char): Unit =
      val start = index
      index += 1
      while index < input.length && !isBreak(input.charAt(index)) && input.charAt(index) != '#' && input.charAt(index) != ' ' && input.charAt(index) != '\t' do
        index += 1
      emitRange(start, index, if style == '|' then "yaml.scalar.literal" else "yaml.scalar.folded", TokenChannel.Syntax)

    def scanPlain(): Unit =
      val start = index
      var done = false
      while index < input.length && !done do
        val char = input.charAt(index)
        if isSeparation(char) || isFlow(char) || char == ':' then done = true
        else if (char == '-' || char == '?') && indicatorAt(index) then done = true
        else index += 1
      if index == start then index += 1
      emitRange(start, index, "yaml.scalar.plain", TokenChannel.Syntax)
      if !linePlainScalarActive then plainFlowDepth = flowClosers.size
      linePlainScalarActive = true
      scalarLimit(start, index)

    def scanOne(): Unit =
      val char = input.charAt(index)
      if isBreak(char) then scanBreak()
      else if atLineStart && (char == ' ' || char == '\t') then scanIndentation()
      else if char == ' ' || char == '\t' then scanWhitespace()
      else if firstContent && position.column == 1 && char == '%' then scanToLineEnd("yaml.directive", TokenChannel.Syntax)
      else if firstContent && position.column == 1 && markerAt(index, "---") then emitWidth(3, "yaml.document-start", TokenChannel.Syntax)
      else if firstContent && position.column == 1 && markerAt(index, "...") then emitWidth(3, "yaml.document-end", TokenChannel.Syntax)
      else if char == '#' then scanToLineEnd("yaml.comment", TokenChannel.Comment)
      else if char == '\'' then scanSingleQuoted()
      else if char == '"' then scanDoubleQuoted()
      else if linePlainScalarActive && (char == '!' || char == '&' || char == '*') then scanPlain()
      else if char == '!' then scanProperty("yaml.tag")
      else if char == '&' then scanProperty("yaml.anchor")
      else if char == '*' then scanProperty("yaml.alias")
      else if char == '|' || char == '>' then scanBlockHeader(char)
      else if isFlow(char) then scanFlow(char)
      else if isBlockIndicator(char) then
        emitWidth(1, blockKind(char), TokenChannel.Syntax)
        if char == ':' then linePlainScalarActive = false
      else scanPlain()

    def validateLines(): Unit =
      var lineStart = 0
      var cursor = 0
      var lineNumber = 1
      while cursor <= input.length do
        val atEnd = cursor == input.length
        val atBreak = !atEnd && isBreak(input.charAt(cursor))
        if atEnd || atBreak then
          val text = input.substring(lineStart, cursor)
          val count = Unicode.codePointCount(text)
          if count > limits.maxLineCodePoints then
            val start = SourcePosition(codePointOffset(lineStart), lineNumber, 1)
            val end = SourcePosition(start.offset + count, lineNumber, count + 1)
            report(
              "uniml.yaml.limit.line",
              s"YAML line exceeds the ${limits.maxLineCodePoints} code-point limit",
              Severity.Fatal,
              SourceSpan(source, start, end),
            )
          if atBreak then
            if input.charAt(cursor) == '\r' && cursor + 1 < input.length && input.charAt(cursor + 1) == '\n' then cursor += 1
            cursor += 1
            lineStart = cursor
            lineNumber += 1
          else cursor += 1
        else cursor += 1

      var surrogate = 0
      var pos = SourcePosition.Start
      while surrogate < input.length do
        val char = input.charAt(surrogate)
        val width =
          if Unicode.isHighSurrogate(char) && surrogate + 1 < input.length && Unicode.isLowSurrogate(input.charAt(surrogate + 1)) then 2
          else 1
        val lexeme = input.substring(surrogate, surrogate + width)
        val next = advance(pos, lexeme)
        if (Unicode.isHighSurrogate(char) && width == 1) || Unicode.isLowSurrogate(char) then
          report(
            "uniml.yaml.invalid-character",
            "YAML source contains an unpaired UTF-16 surrogate",
            Severity.Error,
            SourceSpan(source, pos, next),
          )
        else if char < ' ' && char != '\t' && char != '\r' && char != '\n' then
          report(
            "uniml.yaml.invalid-character",
            f"YAML source contains forbidden control character U+${char.toInt}%04X",
            Severity.Error,
            SourceSpan(source, pos, next),
          )
        pos = next
        surrogate += width

    validateLines()
    while index < input.length do scanOne()
    YamlLexResult(tokens, diagnostics)

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

  private def advance(start: SourcePosition, text: String): SourcePosition =
    var cursor = 0
    var offset = start.offset
    var line = start.line
    var column = start.column
    while cursor < text.length do
      val char = text.charAt(cursor)
      if char == '\r' then
        if cursor + 1 < text.length && text.charAt(cursor + 1) == '\n' then
          cursor += 2
          offset += 2
        else
          cursor += 1
          offset += 1
        line += 1
        column = 1
      else if char == '\n' then
        cursor += 1
        offset += 1
        line += 1
        column = 1
      else
        val width =
          if Unicode.isHighSurrogate(char) && cursor + 1 < text.length && Unicode.isLowSurrogate(text.charAt(cursor + 1)) then 2
          else 1
        cursor += width
        offset += 1
        column += 1
    SourcePosition(offset, line, column)
