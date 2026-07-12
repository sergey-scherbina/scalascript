package scalascript.uniml.dialect.json

import scalascript.uniml.*
import scala.collection.mutable.ArrayBuffer

private final case class JsonLexIssue(code: String, message: String, severity: Severity)

private final case class JsonLexToken(token: SourceToken, issue: Option[JsonLexIssue])

private final case class JsonLexBatch(tokens: Vector[JsonLexToken], diagnostics: Vector[Diagnostic])

private object JsonLexer:
  def apply(source: SourceId, limits: JsonLimits): JsonLexer = new JsonLexer(source, limits)

private final class JsonLexer(source: SourceId, limits: JsonLimits):
  private enum Mode:
    case Default
    case Whitespace
    case Atom
    case StringValue

  private enum StringState:
    case Normal
    case Escape
    case UnicodeEscape(remaining: Int)

  private val completed = ArrayBuffer.empty[JsonLexToken]
  private val current = StringBuilder()
  private var mode = Mode.Default
  private var stringState = StringState.Normal
  private var currentStart = SourcePosition.Start
  private var currentIssue: Option[JsonLexIssue] = None
  private var currentCodePoints = 0
  private var nextTokenId = 0L
  private var totalCodePoints = 0L
  private var currentPosition = SourcePosition.Start
  private var pendingHighSurrogate: Option[Char] = None
  private var halted = false
  private var finished = false
  private val pendingDiagnostics = ArrayBuffer.empty[Diagnostic]

  def position: SourcePosition = currentPosition

  def push(chunk: SourceChunk): JsonLexBatch =
    if finished then JsonLexBatch(Vector.empty, Vector(finishedDiagnostic))
    else if halted then drain()
    else
      feedChunk(chunk.text)
      drain()

  def finish(): JsonLexBatch =
    if finished then JsonLexBatch(Vector.empty, Vector(finishedDiagnostic))
    else
      finished = true
      pendingHighSurrogate.foreach { high =>
        pendingHighSurrogate = None
        feedCodePoint(high.toString, rawSurrogate = true)
      }
      if !halted then
        mode match
          case Mode.Default => ()
          case Mode.Whitespace => complete("json.whitespace", TokenChannel.Trivia)
          case Mode.Atom       => completeAtom()
          case Mode.StringValue =>
            setIssue("uniml.json.invalid-string", "unterminated JSON string", Severity.Error)
            complete("json.invalid", TokenChannel.Error)
      drain()

  private def feedChunk(text: String): Unit =
    var index = 0
    pendingHighSurrogate.foreach { high =>
      if text.nonEmpty && Unicode.isLowSurrogate(text.charAt(0)) then
        feedCodePoint(s"$high${text.charAt(0)}", rawSurrogate = false)
        index = 1
      else feedCodePoint(high.toString, rawSurrogate = true)
      pendingHighSurrogate = None
    }
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
          pendingHighSurrogate = Some(char)
          index += 1
      else
        feedCodePoint(char.toString, rawSurrogate = Unicode.isLowSurrogate(char))
        index += 1

  private def feedCodePoint(lexeme: String, rawSurrogate: Boolean): Unit =
    totalCodePoints += 1L
    if totalCodePoints > limits.maxSourceCodePoints then
      halted = true
      pendingDiagnostics += Diagnostic(
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
          case Mode.Default =>
            if isWhitespace(lexeme) then start(Mode.Whitespace, lexeme, rawSurrogate)
            else lexeme match
              case "{" => emitSingle("json.lbrace", lexeme)
              case "}" => emitSingle("json.rbrace", lexeme)
              case "[" => emitSingle("json.lbracket", lexeme)
              case "]" => emitSingle("json.rbracket", lexeme)
              case ":" => emitSingle("json.colon", lexeme)
              case "," => emitSingle("json.comma", lexeme)
              case "\"" =>
                start(Mode.StringValue, lexeme, rawSurrogate)
                stringState = StringState.Normal
              case "\uFEFF" if currentPosition.offset == 0 =>
                start(Mode.Atom, lexeme, rawSurrogate)
                setIssue("uniml.json.bom", "leading JSON byte-order mark was preserved", Severity.Warning)
                complete("json.bom", TokenChannel.Trivia)
              case value if startsAtom(value) => start(Mode.Atom, value, rawSurrogate)
              case _ =>
                start(Mode.Atom, lexeme, rawSurrogate)
                setIssue("uniml.json.invalid-character", "invalid character outside a JSON token", Severity.Error)
                complete("json.invalid", TokenChannel.Error)

          case Mode.Whitespace =>
            if isWhitespace(lexeme) then append(lexeme, rawSurrogate)
            else
              complete("json.whitespace", TokenChannel.Trivia)
              reprocess = true

          case Mode.Atom =>
            if isDelimiter(lexeme) then
              completeAtom()
              reprocess = true
            else append(lexeme, rawSurrogate)

          case Mode.StringValue => feedStringCodePoint(lexeme, rawSurrogate)

  private def feedStringCodePoint(lexeme: String, rawSurrogate: Boolean): Unit =
    append(lexeme, rawSurrogate)
    if rawSurrogate then
      setIssue("uniml.json.invalid-string", "raw unpaired UTF-16 surrogate in JSON string", Severity.Error)
    stringState match
      case StringState.Normal =>
        lexeme match
          case "\"" => complete(if currentIssue.isEmpty then "json.string" else "json.invalid", if currentIssue.isEmpty then TokenChannel.Syntax else TokenChannel.Error)
          case "\\" => stringState = StringState.Escape
          case value if isControl(value) =>
            setIssue("uniml.json.invalid-string", "unescaped control character in JSON string", Severity.Error)
          case _ => ()
      case StringState.Escape =>
        lexeme match
          case "\"" | "\\" | "/" | "b" | "f" | "n" | "r" | "t" => stringState = StringState.Normal
          case "u" => stringState = StringState.UnicodeEscape(4)
          case _ =>
            setIssue("uniml.json.invalid-string", "invalid JSON string escape", Severity.Error)
            stringState = StringState.Normal
      case StringState.UnicodeEscape(remaining) =>
        if isHexDigit(lexeme) then
          if remaining == 1 then stringState = StringState.Normal
          else stringState = StringState.UnicodeEscape(remaining - 1)
        else
          setIssue("uniml.json.invalid-string", "JSON unicode escape requires four hexadecimal digits", Severity.Error)
          if lexeme == "\"" then complete("json.invalid", TokenChannel.Error)
          else if lexeme == "\\" then stringState = StringState.Escape
          else stringState = StringState.Normal

  private def start(nextMode: Mode, lexeme: String, rawSurrogate: Boolean): Unit =
    mode = nextMode
    currentStart = currentPosition
    current.clear()
    currentIssue = None
    currentCodePoints = 0
    append(lexeme, rawSurrogate)

  private def append(lexeme: String, rawSurrogate: Boolean): Unit =
    current.append(lexeme)
    currentCodePoints += 1
    currentPosition = Unicode.advance(currentPosition, lexeme)
    if rawSurrogate && mode != Mode.StringValue then
      setIssue("uniml.json.invalid-character", "raw unpaired UTF-16 surrogate", Severity.Error)
    if mode == Mode.StringValue && currentCodePoints > limits.maxStringCodePoints then
      setIssue("uniml.json.limit.string", s"JSON string exceeds the ${limits.maxStringCodePoints} code-point limit", Severity.Fatal)
      complete("json.invalid", TokenChannel.Error)
      halted = true
    else if mode == Mode.Atom && current.nonEmpty &&
        (current.charAt(0) == '-' || isAsciiDigit(current.charAt(0))) &&
        currentCodePoints > limits.maxNumberCodePoints then
      setIssue("uniml.json.limit.number", s"JSON number exceeds the ${limits.maxNumberCodePoints} code-point limit", Severity.Fatal)
      complete("json.invalid", TokenChannel.Error)
      halted = true

  private def emitSingle(kind: String, lexeme: String): Unit =
    start(Mode.Atom, lexeme, rawSurrogate = false)
    complete(kind, TokenChannel.Syntax)

  private def completeAtom(): Unit =
    val lexeme = current.result()
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

  private def complete(kind: String, channel: TokenChannel): Unit =
    val lexeme = current.result()
    val token = SourceToken(
      id = nextTokenId,
      kind = kind,
      lexeme = lexeme,
      span = SourceSpan(source, currentStart, currentPosition),
      channel = channel,
    )
    completed += JsonLexToken(token, currentIssue)
    nextTokenId += 1L
    mode = Mode.Default
    stringState = StringState.Normal
    current.clear()
    currentIssue = None
    currentCodePoints = 0

  private def setIssue(code: String, message: String, severity: Severity): Unit =
    if currentIssue.isEmpty then currentIssue = Some(JsonLexIssue(code, message, severity))

  private def drain(): JsonLexBatch =
    val tokens = completed.toVector
    val diagnostics = pendingDiagnostics.toVector
    completed.clear()
    pendingDiagnostics.clear()
    JsonLexBatch(tokens, diagnostics)

  private def isWhitespace(lexeme: String): Boolean =
    lexeme == " " || lexeme == "\t" || lexeme == "\n" || lexeme == "\r"

  private def isDelimiter(lexeme: String): Boolean =
    isWhitespace(lexeme) || lexeme == "{" || lexeme == "}" || lexeme == "[" ||
      lexeme == "]" || lexeme == ":" || lexeme == ","

  private def startsAtom(lexeme: String): Boolean =
    startsNumber(lexeme) || (lexeme.nonEmpty && lexeme.charAt(0).isLetter)

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

  private val finishedDiagnostic = Diagnostic(
    code = "uniml.json.lexer.finished",
    message = "JSON lexer cannot finish more than once",
    severity = Severity.Error,
    span = None,
    dialect = Some(JsonDialect.id),
  )
