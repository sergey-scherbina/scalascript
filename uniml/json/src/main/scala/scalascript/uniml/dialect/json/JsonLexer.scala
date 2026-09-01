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

/** The whole of the lexer's state, threaded through a pure fold. One value per former `var`, in
  * the order the imperative original declared them — the record IS the conversion, so a reader
  * diffing against history can match field to variable one for one. */
private final case class JsonLexState(
    completed: Vector[JsonLexToken],
    diagnostics: Vector[Diagnostic],
    current: Vector[String],
    mode: JsonMode,
    stringState: JsonStringState,
    currentStart: SourcePosition,
    currentIssue: Option[JsonLexIssue],
    currentCodePoints: Int,
    nextTokenId: Long,
    totalCodePoints: Long,
    currentPosition: SourcePosition,
    halted: Boolean,
)

/** Pure JSON lexer: a single fold over the whole source that returns the token
  * vector, diagnostics, and the final position. Lexing state is an immutable
  * [[JsonLexState]] record threaded `step(state, chunk)`-style — the shape the
  * portable-program decision names — with NO `var` anywhere: the former local
  * imperative shell is a tail-recursive walk. The in-progress lexeme buffer
  * stays a `Vector[String]` of code-point lexemes joined with `.mkString("")`.
  * Uses only v2-supported constructs (no `StringBuilder`). */
private object JsonLexer:
  def scan(source: SourceId, text: String, limits: JsonLimits): JsonLexResult =

    def setIssue(s: JsonLexState, code: String, message: String, severity: Severity): JsonLexState =
      if s.currentIssue.isEmpty then s.copy(currentIssue = Some(JsonLexIssue(code, message, severity)))
      else s

    def complete(s: JsonLexState, kind: String, channel: TokenChannel): JsonLexState =
      val lexeme = s.current.mkString("")
      s.copy(
        completed = s.completed :+ JsonLexToken(
          SourceToken(
            id = s.nextTokenId,
            kind = kind,
            lexeme = lexeme,
            span = SourceSpan(source, s.currentStart, s.currentPosition),
            channel = channel,
          ),
          s.currentIssue,
        ),
        nextTokenId = s.nextTokenId + 1L,
        mode = JsonMode.Default,
        stringState = JsonStringState.Normal,
        current = Vector.empty,
        currentIssue = None,
        currentCodePoints = 0,
      )

    // Order preserved from the original: append and advance FIRST, then the surrogate issue (only
    // outside a string — the string path raises its own), then the limit checks, whose `complete`
    // must see the advanced position and the appended lexeme.
    def append(s0: JsonLexState, lexeme: String, rawSurrogate: Boolean): JsonLexState =
      val s1 = s0.copy(
        current = s0.current :+ lexeme,
        currentCodePoints = s0.currentCodePoints + 1,
        currentPosition = Unicode.advance(s0.currentPosition, lexeme),
      )
      val s2 =
        if rawSurrogate && s1.mode != JsonMode.StringValue then
          setIssue(s1, "uniml.json.invalid-character", "raw unpaired UTF-16 surrogate", Severity.Error)
        else s1
      if s2.mode == JsonMode.StringValue && s2.currentCodePoints > limits.maxStringCodePoints then
        complete(
          setIssue(s2, "uniml.json.limit.string", s"JSON string exceeds the ${limits.maxStringCodePoints} code-point limit", Severity.Fatal),
          "json.invalid", TokenChannel.Error,
        ).copy(halted = true)
      else if s2.mode == JsonMode.Atom && s2.current.nonEmpty &&
          (s2.current.head.charAt(0) == '-' || isAsciiDigit(s2.current.head.charAt(0))) &&
          s2.currentCodePoints > limits.maxNumberCodePoints then
        complete(
          setIssue(s2, "uniml.json.limit.number", s"JSON number exceeds the ${limits.maxNumberCodePoints} code-point limit", Severity.Fatal),
          "json.invalid", TokenChannel.Error,
        ).copy(halted = true)
      else s2

    def start(s: JsonLexState, nextMode: JsonMode, lexeme: String, rawSurrogate: Boolean): JsonLexState =
      append(
        s.copy(
          mode = nextMode,
          currentStart = s.currentPosition,
          current = Vector.empty,
          currentIssue = None,
          currentCodePoints = 0,
        ),
        lexeme, rawSurrogate,
      )

    def emitSingle(s: JsonLexState, kind: String, lexeme: String): JsonLexState =
      complete(start(s, JsonMode.Atom, lexeme, rawSurrogate = false), kind, TokenChannel.Syntax)

    def completeAtom(s: JsonLexState): JsonLexState =
      val lexeme = s.current.mkString("")
      if s.currentIssue.nonEmpty then complete(s, "json.invalid", TokenChannel.Error)
      else lexeme match
        case "true"  => complete(s, "json.true", TokenChannel.Syntax)
        case "false" => complete(s, "json.false", TokenChannel.Syntax)
        case "null"  => complete(s, "json.null", TokenChannel.Syntax)
        case value if startsNumber(value) && validNumber(value) => complete(s, "json.number", TokenChannel.Syntax)
        case value if startsNumber(value) =>
          complete(
            setIssue(s, "uniml.json.invalid-number", s"invalid RFC 8259 number '$value'", Severity.Error),
            "json.invalid", TokenChannel.Error,
          )
        case value =>
          complete(
            setIssue(s, "uniml.json.invalid-literal", s"invalid JSON literal '$value'", Severity.Error),
            "json.invalid", TokenChannel.Error,
          )

    def feedStringCodePoint(s0: JsonLexState, lexeme: String, rawSurrogate: Boolean): JsonLexState =
      val s1 = append(s0, lexeme, rawSurrogate)
      val s2 =
        if rawSurrogate then
          setIssue(s1, "uniml.json.invalid-string", "raw unpaired UTF-16 surrogate in JSON string", Severity.Error)
        else s1
      s2.stringState match
        case JsonStringState.Normal =>
          lexeme match
            case "\"" =>
              if s2.currentIssue.isEmpty then complete(s2, "json.string", TokenChannel.Syntax)
              else complete(s2, "json.invalid", TokenChannel.Error)
            case "\\" => s2.copy(stringState = JsonStringState.Escape)
            case value if isControl(value) =>
              setIssue(s2, "uniml.json.invalid-string", "unescaped control character in JSON string", Severity.Error)
            case _ => s2
        case JsonStringState.Escape =>
          lexeme match
            case "\"" | "\\" | "/" | "b" | "f" | "n" | "r" | "t" => s2.copy(stringState = JsonStringState.Normal)
            case "u" => s2.copy(stringState = JsonStringState.UnicodeEscape(4))
            case _ =>
              setIssue(s2, "uniml.json.invalid-string", "invalid JSON string escape", Severity.Error)
                .copy(stringState = JsonStringState.Normal)
        case JsonStringState.UnicodeEscape(remaining) =>
          if isHexDigit(lexeme) then
            if remaining == 1 then s2.copy(stringState = JsonStringState.Normal)
            else s2.copy(stringState = JsonStringState.UnicodeEscape(remaining - 1))
          else
            val s3 = setIssue(s2, "uniml.json.invalid-string", "JSON unicode escape requires four hexadecimal digits", Severity.Error)
            if lexeme == "\"" then complete(s3, "json.invalid", TokenChannel.Error)
            else if lexeme == "\\" then s3.copy(stringState = JsonStringState.Escape)
            else s3.copy(stringState = JsonStringState.Normal)

    // The original's `reprocess` while-loop runs at most twice: only the Whitespace and Atom arms
    // request reprocessing, both complete into Default first, and Default never requests it. The
    // recursion below has exactly that depth, and the halted guard sits where the loop condition
    // (`reprocess && !halted`) had it — a limit-`complete` inside `append` must suppress the rerun.
    def dispatch(s: JsonLexState, lexeme: String, rawSurrogate: Boolean): JsonLexState =
      s.mode match
        case JsonMode.Default =>
          if isWhitespace(lexeme) then start(s, JsonMode.Whitespace, lexeme, rawSurrogate)
          else lexeme match
            case "{" => emitSingle(s, "json.lbrace", lexeme)
            case "}" => emitSingle(s, "json.rbrace", lexeme)
            case "[" => emitSingle(s, "json.lbracket", lexeme)
            case "]" => emitSingle(s, "json.rbracket", lexeme)
            case ":" => emitSingle(s, "json.colon", lexeme)
            case "," => emitSingle(s, "json.comma", lexeme)
            case "\"" =>
              start(s, JsonMode.StringValue, lexeme, rawSurrogate).copy(stringState = JsonStringState.Normal)
            case "\uFEFF" if s.currentPosition.offset == 0 =>
              complete(
                setIssue(
                  start(s, JsonMode.Atom, lexeme, rawSurrogate),
                  "uniml.json.bom", "leading JSON byte-order mark was preserved", Severity.Warning,
                ),
                "json.bom", TokenChannel.Trivia,
              )
            case value if startsAtom(value) => start(s, JsonMode.Atom, value, rawSurrogate)
            case _ =>
              complete(
                setIssue(
                  start(s, JsonMode.Atom, lexeme, rawSurrogate),
                  "uniml.json.invalid-character", "invalid character outside a JSON token", Severity.Error,
                ),
                "json.invalid", TokenChannel.Error,
              )
        case JsonMode.Whitespace =>
          if isWhitespace(lexeme) then append(s, lexeme, rawSurrogate)
          else
            val done = complete(s, "json.whitespace", TokenChannel.Trivia)
            if done.halted then done else dispatch(done, lexeme, rawSurrogate)
        case JsonMode.Atom =>
          if isDelimiter(lexeme) then
            val done = completeAtom(s)
            if done.halted then done else dispatch(done, lexeme, rawSurrogate)
          else append(s, lexeme, rawSurrogate)
        case JsonMode.StringValue => feedStringCodePoint(s, lexeme, rawSurrogate)

    def feedCodePoint(s: JsonLexState, lexeme: String, rawSurrogate: Boolean): JsonLexState =
      val counted = s.copy(totalCodePoints = s.totalCodePoints + 1L)
      if counted.totalCodePoints > limits.maxSourceCodePoints then
        counted.copy(
          halted = true,
          diagnostics = counted.diagnostics :+ Diagnostic(
            code = "uniml.json.limit.source",
            message = s"JSON source exceeds the ${limits.maxSourceCodePoints} code-point limit",
            severity = Severity.Fatal,
            span = Some(SourceSpan(source, counted.currentPosition, counted.currentPosition)),
            dialect = Some(JsonDialect.id),
          ),
        )
      else dispatch(counted, lexeme, rawSurrogate)

    // Walk the whole source once, pairing UTF-16 surrogates as we go. Tail-recursive: the shape
    // scalac and Scala.js both compile to a loop, so the depth is O(1) regardless of input size.
    // Lexemes are built by SLICING the source, never `char.toString` / s"$char": a `Char` kept for
    // classification is portable, but turning it back into text is not (ScalaScript v2 has no Char
    // box, so `char.toString` yields the decimal code). `substring` returns the actual
    // character(s) on both scalac and v2.
    def walk(s: JsonLexState, index: Int): JsonLexState =
      if index >= text.length || s.halted then s
      else
        val char = text.charAt(index)
        if Unicode.isHighSurrogate(char) then
          if index + 1 < text.length && Unicode.isLowSurrogate(text.charAt(index + 1)) then
            walk(feedCodePoint(s, text.substring(index, index + 2), rawSurrogate = false), index + 2)
          else
            walk(feedCodePoint(s, text.substring(index, index + 1), rawSurrogate = true), index + 1)
        else
          walk(feedCodePoint(s, text.substring(index, index + 1), rawSurrogate = Unicode.isLowSurrogate(char)), index + 1)

    val walked = walk(
      JsonLexState(
        completed = Vector.empty,
        diagnostics = Vector.empty,
        current = Vector.empty,
        mode = JsonMode.Default,
        stringState = JsonStringState.Normal,
        currentStart = SourcePosition.Start,
        currentIssue = None,
        currentCodePoints = 0,
        nextTokenId = 0L,
        totalCodePoints = 0L,
        currentPosition = SourcePosition.Start,
        halted = false,
      ),
      0,
    )

    // Finalize any token left open at end of input.
    val finished =
      if walked.halted then walked
      else walked.mode match
        case JsonMode.Default    => walked
        case JsonMode.Whitespace => complete(walked, "json.whitespace", TokenChannel.Trivia)
        case JsonMode.Atom       => completeAtom(walked)
        case JsonMode.StringValue =>
          complete(
            setIssue(walked, "uniml.json.invalid-string", "unterminated JSON string", Severity.Error),
            "json.invalid", TokenChannel.Error,
          )

    JsonLexResult(finished.completed, finished.diagnostics, finished.currentPosition)

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

  // RFC 8259 §6's number grammar, one helper per clause with -1 as the failure sentinel each
  // clause propagates. The earlier entry kept this imperative as "the validNumber argument" —
  // the exemplar the other holdouts cited — and the stage-10 closing pass converted it anyway:
  // item 10 allows parking only on a MEASUREMENT, and the clause-per-helper shape mirrors the
  // grammar exactly as the return-laden loop did.
  private def validNumber(value: String): Boolean =
    def digitsEnd(i: Int): Int =
      if i < value.length && isAsciiDigit(value.charAt(i)) then digitsEnd(i + 1) else i
    def intPart(i0: Int): Int =
      val i = if i0 < value.length && value.charAt(i0) == '-' then i0 + 1 else i0
      if i >= value.length then -1
      else if value.charAt(i) == '0' then
        if i + 1 < value.length && isAsciiDigit(value.charAt(i + 1)) then -1 else i + 1
      else if value.charAt(i) >= '1' && value.charAt(i) <= '9' then digitsEnd(i + 1)
      else -1
    def fracPart(i: Int): Int =
      if i < 0 then -1
      else if i < value.length && value.charAt(i) == '.' then
        val end = digitsEnd(i + 1)
        if end == i + 1 then -1 else end
      else i
    def expPart(i: Int): Int =
      if i < 0 then -1
      else if i < value.length && (value.charAt(i) == 'e' || value.charAt(i) == 'E') then
        val signed = if i + 1 < value.length && (value.charAt(i + 1) == '+' || value.charAt(i + 1) == '-') then i + 2 else i + 1
        val end = digitsEnd(signed)
        if end == signed then -1 else end
      else i
    expPart(fracPart(intPart(0))) == value.length

  private def isAsciiDigit(char: Char): Boolean = char >= '0' && char <= '9'
