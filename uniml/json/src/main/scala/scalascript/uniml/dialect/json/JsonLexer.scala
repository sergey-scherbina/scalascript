package scalascript.uniml.dialect.json

import scalascript.uniml.*

private final case class JsonLexIssue(code: String, message: String, severity: Severity)

private final case class JsonLexToken(token: SourceToken, issue: Option[JsonLexIssue])

private final case class JsonLexResult(
    tokens: Vector[JsonLexToken],
    diagnostics: Vector[Diagnostic],
    position: SourcePosition,
)

private enum JsonMode:
  case Default
  case Whitespace
  case Atom
  case StringValue

private enum JsonStringState:
  case Normal
  case Escape
  case UnicodeEscape(remaining: Int)

/** Pure JSON lexer: a single fold over the whole source that returns the token
  * vector, diagnostics, and the final position. All lexing state lives in local
  * `var`s inside `scan` (no mutable object fields), with a local imperative shell
  * and immutable `Vector` accumulation — including the in-progress lexeme buffer
  * `current` (a `Vector[String]` of code-point lexemes, joined with `.mkString`).
  * Uses only v2-supported constructs (no `StringBuilder`). */
private object JsonLexer:
  def scan(source: SourceId, text: String, limits: JsonLimits): JsonLexResult =
    var completed: Vector[JsonLexToken] = Vector.empty
    var diagnostics: Vector[Diagnostic] = Vector.empty
    // token buffer as an immutable Vector of code-point lexemes (v2 has no
    // StringBuilder; Vector `:+`/`.mkString` are supported and fully immutable)
    var current: Vector[String] = Vector.empty
    var mode = JsonMode.Default
    var stringState = JsonStringState.Normal
    var currentStart = SourcePosition.Start
    var currentIssue: Option[JsonLexIssue] = None
    var currentCodePoints = 0
    var nextTokenId = 0L
    var totalCodePoints = 0L
    var currentPosition = SourcePosition.Start
    var halted = false

    def setIssue(code: String, message: String, severity: Severity): Unit =
      if currentIssue.isEmpty then currentIssue = Some(JsonLexIssue(code, message, severity))

    def complete(kind: String, channel: TokenChannel): Unit =
      val lexeme = current.mkString
      completed = completed :+ JsonLexToken(
        SourceToken(
          id = nextTokenId,
          kind = kind,
          lexeme = lexeme,
          span = SourceSpan(source, currentStart, currentPosition),
          channel = channel,
        ),
        currentIssue,
      )
      nextTokenId += 1L
      mode = JsonMode.Default
      stringState = JsonStringState.Normal
      current = Vector.empty
      currentIssue = None
      currentCodePoints = 0

    def append(lexeme: String, rawSurrogate: Boolean): Unit =
      current = current :+ lexeme
      currentCodePoints += 1
      currentPosition = Unicode.advance(currentPosition, lexeme)
      if rawSurrogate && mode != JsonMode.StringValue then
        setIssue("uniml.json.invalid-character", "raw unpaired UTF-16 surrogate", Severity.Error)
      if mode == JsonMode.StringValue && currentCodePoints > limits.maxStringCodePoints then
        setIssue("uniml.json.limit.string", s"JSON string exceeds the ${limits.maxStringCodePoints} code-point limit", Severity.Fatal)
        complete("json.invalid", TokenChannel.Error)
        halted = true
      else if mode == JsonMode.Atom && current.nonEmpty &&
          (current.head.charAt(0) == '-' || isAsciiDigit(current.head.charAt(0))) &&
          currentCodePoints > limits.maxNumberCodePoints then
        setIssue("uniml.json.limit.number", s"JSON number exceeds the ${limits.maxNumberCodePoints} code-point limit", Severity.Fatal)
        complete("json.invalid", TokenChannel.Error)
        halted = true

    def start(nextMode: JsonMode, lexeme: String, rawSurrogate: Boolean): Unit =
      mode = nextMode
      currentStart = currentPosition
      current = Vector.empty
      currentIssue = None
      currentCodePoints = 0
      append(lexeme, rawSurrogate)

    def emitSingle(kind: String, lexeme: String): Unit =
      start(JsonMode.Atom, lexeme, rawSurrogate = false)
      complete(kind, TokenChannel.Syntax)

    def completeAtom(): Unit =
      val lexeme = current.mkString
      if currentIssue.nonEmpty then complete("json.invalid", TokenChannel.Error)
      else lexeme match
        case "true"  => complete("json.true", TokenChannel.Syntax)
        case "false" => complete("json.false", TokenChannel.Syntax)
        case "null"  => complete("json.null", TokenChannel.Syntax)
        case value if startsNumber(value) && validNumber(value) => complete("json.number", TokenChannel.Syntax)
        case value if startsNumber(value) =>
          setIssue("uniml.json.invalid-number", s"invalid RFC 8259 number '$value'", Severity.Error)
          complete("json.invalid", TokenChannel.Error)
        case value =>
          setIssue("uniml.json.invalid-literal", s"invalid JSON literal '$value'", Severity.Error)
          complete("json.invalid", TokenChannel.Error)

    def feedStringCodePoint(lexeme: String, rawSurrogate: Boolean): Unit =
      append(lexeme, rawSurrogate)
      if rawSurrogate then
        setIssue("uniml.json.invalid-string", "raw unpaired UTF-16 surrogate in JSON string", Severity.Error)
      stringState match
        case JsonStringState.Normal =>
          lexeme match
            case "\"" => complete(if currentIssue.isEmpty then "json.string" else "json.invalid", if currentIssue.isEmpty then TokenChannel.Syntax else TokenChannel.Error)
            case "\\" => stringState = JsonStringState.Escape
            case value if isControl(value) =>
              setIssue("uniml.json.invalid-string", "unescaped control character in JSON string", Severity.Error)
            case _ => ()
        case JsonStringState.Escape =>
          lexeme match
            case "\"" | "\\" | "/" | "b" | "f" | "n" | "r" | "t" => stringState = JsonStringState.Normal
            case "u" => stringState = JsonStringState.UnicodeEscape(4)
            case _ =>
              setIssue("uniml.json.invalid-string", "invalid JSON string escape", Severity.Error)
              stringState = JsonStringState.Normal
        case JsonStringState.UnicodeEscape(remaining) =>
          if isHexDigit(lexeme) then
            if remaining == 1 then stringState = JsonStringState.Normal
            else stringState = JsonStringState.UnicodeEscape(remaining - 1)
          else
            setIssue("uniml.json.invalid-string", "JSON unicode escape requires four hexadecimal digits", Severity.Error)
            if lexeme == "\"" then complete("json.invalid", TokenChannel.Error)
            else if lexeme == "\\" then stringState = JsonStringState.Escape
            else stringState = JsonStringState.Normal

    def feedCodePoint(lexeme: String, rawSurrogate: Boolean): Unit =
      totalCodePoints += 1L
      if totalCodePoints > limits.maxSourceCodePoints then
        halted = true
        diagnostics = diagnostics :+ Diagnostic(
          code = "uniml.json.limit.source",
          message = s"JSON source exceeds the ${limits.maxSourceCodePoints} code-point limit",
          severity = Severity.Fatal,
          span = Some(SourceSpan(source, currentPosition, currentPosition)),
          dialect = Some(JsonDialect.id),
        )
      else
        var reprocess = true
        while reprocess && !halted do
          reprocess = false
          mode match
            case JsonMode.Default =>
              if isWhitespace(lexeme) then start(JsonMode.Whitespace, lexeme, rawSurrogate)
              else lexeme match
                case "{" => emitSingle("json.lbrace", lexeme)
                case "}" => emitSingle("json.rbrace", lexeme)
                case "[" => emitSingle("json.lbracket", lexeme)
                case "]" => emitSingle("json.rbracket", lexeme)
                case ":" => emitSingle("json.colon", lexeme)
                case "," => emitSingle("json.comma", lexeme)
                case "\"" =>
                  start(JsonMode.StringValue, lexeme, rawSurrogate)
                  stringState = JsonStringState.Normal
                case "\uFEFF" if currentPosition.offset == 0 =>
                  start(JsonMode.Atom, lexeme, rawSurrogate)
                  setIssue("uniml.json.bom", "leading JSON byte-order mark was preserved", Severity.Warning)
                  complete("json.bom", TokenChannel.Trivia)
                case value if startsAtom(value) => start(JsonMode.Atom, value, rawSurrogate)
                case _ =>
                  start(JsonMode.Atom, lexeme, rawSurrogate)
                  setIssue("uniml.json.invalid-character", "invalid character outside a JSON token", Severity.Error)
                  complete("json.invalid", TokenChannel.Error)

            case JsonMode.Whitespace =>
              if isWhitespace(lexeme) then append(lexeme, rawSurrogate)
              else
                complete("json.whitespace", TokenChannel.Trivia)
                reprocess = true

            case JsonMode.Atom =>
              if isDelimiter(lexeme) then
                completeAtom()
                reprocess = true
              else append(lexeme, rawSurrogate)

            case JsonMode.StringValue => feedStringCodePoint(lexeme, rawSurrogate)

    // Walk the whole source once, pairing UTF-16 surrogates as we go.
    var index = 0
    while index < text.length && !halted do
      val char = text.charAt(index)
      if Unicode.isHighSurrogate(char) then
        if index + 1 < text.length then
          val next = text.charAt(index + 1)
          if Unicode.isLowSurrogate(next) then
            feedCodePoint(s"$char$next", rawSurrogate = false)
            index += 2
          else
            feedCodePoint(char.toString, rawSurrogate = true)
            index += 1
        else
          feedCodePoint(char.toString, rawSurrogate = true)
          index += 1
      else
        feedCodePoint(char.toString, rawSurrogate = Unicode.isLowSurrogate(char))
        index += 1

    // Finalize any token left open at end of input.
    if !halted then
      mode match
        case JsonMode.Default    => ()
        case JsonMode.Whitespace => complete("json.whitespace", TokenChannel.Trivia)
        case JsonMode.Atom       => completeAtom()
        case JsonMode.StringValue =>
          setIssue("uniml.json.invalid-string", "unterminated JSON string", Severity.Error)
          complete("json.invalid", TokenChannel.Error)

    JsonLexResult(completed, diagnostics, currentPosition)

  private def isWhitespace(lexeme: String): Boolean =
    lexeme == " " || lexeme == "\t" || lexeme == "\n" || lexeme == "\r"

  private def isDelimiter(lexeme: String): Boolean =
    isWhitespace(lexeme) || lexeme == "{" || lexeme == "}" || lexeme == "[" ||
      lexeme == "]" || lexeme == ":" || lexeme == ","

  private def startsAtom(lexeme: String): Boolean =
    // JSON literals (true/false/null) are ASCII; a non-ASCII leading char is
    // handled by the invalid-character path either way.
    startsNumber(lexeme) || (lexeme.nonEmpty && isAsciiLetter(lexeme.charAt(0)))

  private def isAsciiLetter(char: Char): Boolean =
    (char >= 'a' && char <= 'z') || (char >= 'A' && char <= 'Z')

  private def startsNumber(value: String): Boolean =
    value.nonEmpty && (value.charAt(0) == '-' || isAsciiDigit(value.charAt(0)))

  private def isControl(lexeme: String): Boolean = lexeme.nonEmpty && lexeme.charAt(0) < ' '

  private def isHexDigit(lexeme: String): Boolean =
    lexeme.length == 1 && {
      val char = lexeme.charAt(0)
      isAsciiDigit(char) || (char >= 'a' && char <= 'f') || (char >= 'A' && char <= 'F')
    }

  private def validNumber(value: String): Boolean =
    var index = 0
    if index < value.length && value.charAt(index) == '-' then index += 1
    if index >= value.length then return false
    if value.charAt(index) == '0' then
      index += 1
      if index < value.length && isAsciiDigit(value.charAt(index)) then return false
    else if value.charAt(index) >= '1' && value.charAt(index) <= '9' then
      index += 1
      while index < value.length && isAsciiDigit(value.charAt(index)) do index += 1
    else return false
    if index < value.length && value.charAt(index) == '.' then
      index += 1
      val start = index
      while index < value.length && isAsciiDigit(value.charAt(index)) do index += 1
      if index == start then return false
    if index < value.length && (value.charAt(index) == 'e' || value.charAt(index) == 'E') then
      index += 1
      if index < value.length && (value.charAt(index) == '+' || value.charAt(index) == '-') then index += 1
      val start = index
      while index < value.length && isAsciiDigit(value.charAt(index)) do index += 1
      if index == start then return false
    index == value.length

  private def isAsciiDigit(char: Char): Boolean = char >= '0' && char <= '9'
