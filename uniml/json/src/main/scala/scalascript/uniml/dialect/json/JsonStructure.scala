package scalascript.uniml.dialect.json

import scalascript.uniml.*

private final case class JsonStructureBatch(tokens: Vector[VmToken], diagnostics: Vector[Diagnostic])

/** Assigns VM instructions to the JSON token stream via a container state machine.
  * All state (instruction/diagnostic accumulators, the container stack, the frame
  * states) is immutable: local `var`s inside `assign` threaded through nested defs,
  * with `Vector` accumulation and a `Vector` frame-stack (push `:+`, pop
  * `dropRight`, state-transition = replace the top with a fresh copy). Frame states
  * are immutable case classes (no mutable object fields). Uses only v2-supported
  * constructs. */
private object JsonStructure:
  private enum DocumentState:
    case Value, End

  private enum ObjectState:
    case KeyOrEnd, Key, Colon, Value, CommaOrEnd

  private enum ArrayState:
    case ValueOrEnd, Value, CommaOrEnd

  private sealed trait Frame
  private final case class ObjectFrame(state: ObjectState) extends Frame
  private final case class ArrayFrame(state: ArrayState) extends Frame

  def assign(tokens: Vector[JsonLexToken], eof: SourcePosition, source: SourceId): JsonStructureBatch =
    var instructions: Vector[VmToken] = Vector.empty
    var diagnostics: Vector[Diagnostic] = Vector.empty
    var stack: Vector[Frame] = Vector.empty
    var document = DocumentState.Value

    def emit(token: SourceToken, instruction: VmInstruction): Unit =
      instructions = instructions :+ VmToken(token, instruction)

    // replace the current top frame with a state-transitioned copy
    def retop(frame: Frame): Unit =
      stack = stack.dropRight(1) :+ frame

    def consumeValue(token: SourceToken, role: Option[String]): Boolean =
      token.kind match
        case "json.lbrace" =>
          emit(token, VmInstruction.Open("json.object", role))
          stack = stack :+ ObjectFrame(ObjectState.KeyOrEnd)
          true
        case "json.lbracket" =>
          emit(token, VmInstruction.Open("json.array", role))
          stack = stack :+ ArrayFrame(ArrayState.ValueOrEnd)
          true
        case "json.string" | "json.number" | "json.true" | "json.false" | "json.null" =>
          emit(token, VmInstruction.Emit(role))
          true
        case _ =>
          emit(token, VmInstruction.Report("uniml.json.expected-value", "expected a JSON value"))
          true

    def consumeKey(token: SourceToken, frame: ObjectFrame): Unit =
      if token.kind == "json.string" then
        emit(token, VmInstruction.Emit(Some("member.key")))
      else
        emit(token, VmInstruction.Report("uniml.json.expected-key", "expected a quoted JSON object key"))
      retop(frame.copy(state = ObjectState.Colon))

    def closeObject(token: SourceToken): Unit =
      emit(token, VmInstruction.Close(Some("json.object"), Some("delimiter.close")))
      stack = stack.dropRight(1)

    def closeArray(token: SourceToken): Unit =
      emit(token, VmInstruction.Close(Some("json.array"), Some("delimiter.close")))
      stack = stack.dropRight(1)

    def recoverAfterInvalid(): Unit =
      if stack.isEmpty then document = DocumentState.End
      else stack.last match
        case frame: ObjectFrame =>
          frame.state match
            case ObjectState.KeyOrEnd | ObjectState.Key => retop(frame.copy(state = ObjectState.Colon))
            case ObjectState.Value                      => retop(frame.copy(state = ObjectState.CommaOrEnd))
            case _                                      => ()
        case frame: ArrayFrame =>
          frame.state match
            case ArrayState.ValueOrEnd | ArrayState.Value => retop(frame.copy(state = ArrayState.CommaOrEnd))
            case _                                        => ()

    tokens.foreach { lexed =>
      val token = lexed.token
      if token.kind == "json.whitespace" then emit(token, VmInstruction.Emit(Some("trivia")))
      else if token.kind == "json.bom" then
        val issue = lexed.issue.get
        emit(token, VmInstruction.Report(issue.code, issue.message, issue.severity))
      else lexed.issue match
        case Some(issue) =>
          emit(token, VmInstruction.Report(issue.code, issue.message, issue.severity))
          recoverAfterInvalid()
        case None =>
          var reprocess = true
          while reprocess do
            reprocess = false
            if stack.isEmpty then
              document match
                case DocumentState.Value =>
                  if consumeValue(token, Some("document.value")) then document = DocumentState.End
                case DocumentState.End =>
                  emit(token, VmInstruction.Report(
                    "uniml.json.trailing-data",
                    "JSON text contains data after the root value",
                  ))
            else stack.last match
              case frame: ObjectFrame =>
                frame.state match
                  case ObjectState.KeyOrEnd =>
                    if token.kind == "json.rbrace" then closeObject(token)
                    else consumeKey(token, frame)
                  case ObjectState.Key =>
                    if token.kind == "json.rbrace" then
                      diagnostics = diagnostics :+ tokenDiagnostic(token, "uniml.json.trailing-comma", "trailing comma in JSON object")
                      closeObject(token)
                    else consumeKey(token, frame)
                  case ObjectState.Colon =>
                    if token.kind == "json.colon" then
                      emit(token, VmInstruction.Emit(Some("member.colon")))
                      retop(frame.copy(state = ObjectState.Value))
                    else if isValueStart(token) then
                      diagnostics = diagnostics :+ tokenDiagnostic(token, "uniml.json.expected-colon", "expected ':' after JSON object key")
                      retop(frame.copy(state = ObjectState.Value))
                      reprocess = true
                    else emit(token, VmInstruction.Report(
                      "uniml.json.expected-colon",
                      "expected ':' after JSON object key",
                    ))
                  case ObjectState.Value =>
                    // pre-set the object frame to CommaOrEnd (consumeValue only
                    // appends any nested container above it), then consume
                    retop(frame.copy(state = ObjectState.CommaOrEnd))
                    consumeValue(token, Some("member.value"))
                  case ObjectState.CommaOrEnd =>
                    token.kind match
                      case "json.comma" =>
                        emit(token, VmInstruction.Emit(Some("member.separator")))
                        retop(frame.copy(state = ObjectState.Key))
                      case "json.rbrace" => closeObject(token)
                      case _ if token.kind == "json.string" =>
                        diagnostics = diagnostics :+ tokenDiagnostic(token, "uniml.json.expected-comma-or-end", "expected ',' or '}' after JSON object member")
                        retop(frame.copy(state = ObjectState.Key))
                        reprocess = true
                      case _ => emit(token, VmInstruction.Report(
                        "uniml.json.expected-comma-or-end",
                        "expected ',' or '}' after JSON object member",
                      ))

              case frame: ArrayFrame =>
                frame.state match
                  case ArrayState.ValueOrEnd =>
                    if token.kind == "json.rbracket" then closeArray(token)
                    else
                      retop(frame.copy(state = ArrayState.CommaOrEnd))
                      consumeValue(token, Some("array.element"))
                  case ArrayState.Value =>
                    if token.kind == "json.rbracket" then
                      diagnostics = diagnostics :+ tokenDiagnostic(token, "uniml.json.trailing-comma", "trailing comma in JSON array")
                      closeArray(token)
                    else
                      retop(frame.copy(state = ArrayState.CommaOrEnd))
                      consumeValue(token, Some("array.element"))
                  case ArrayState.CommaOrEnd =>
                    token.kind match
                      case "json.comma" =>
                        emit(token, VmInstruction.Emit(Some("array.separator")))
                        retop(frame.copy(state = ArrayState.Value))
                      case "json.rbracket" => closeArray(token)
                      case _ if isValueStart(token) =>
                        diagnostics = diagnostics :+ tokenDiagnostic(token, "uniml.json.expected-comma-or-end", "expected ',' or ']' after JSON array element")
                        retop(frame.copy(state = ArrayState.Value))
                        reprocess = true
                      case _ => emit(token, VmInstruction.Report(
                        "uniml.json.expected-comma-or-end",
                        "expected ',' or ']' after JSON array element",
                      ))
    }

    val eofSpan = SourceSpan(source, eof, eof)
    if document == DocumentState.Value then
      diagnostics = diagnostics :+ Diagnostic(
        code = "uniml.json.unexpected-eof",
        message = "expected a JSON value before end of input",
        severity = Severity.Error,
        span = Some(eofSpan),
        dialect = Some(JsonDialect.id),
      )
    if stack.nonEmpty then
      diagnostics = diagnostics :+ Diagnostic(
        code = "uniml.json.unexpected-eof",
        message = "JSON container is not closed before end of input",
        severity = Severity.Error,
        span = Some(eofSpan),
        dialect = Some(JsonDialect.id),
      )
    JsonStructureBatch(instructions, diagnostics)

  private def isValueStart(token: SourceToken): Boolean =
    token.kind == "json.lbrace" || token.kind == "json.lbracket" || token.kind == "json.string" ||
      token.kind == "json.number" || token.kind == "json.true" || token.kind == "json.false" ||
      token.kind == "json.null"

  private def tokenDiagnostic(token: SourceToken, code: String, message: String): Diagnostic =
    Diagnostic(code, message, Severity.Error, Some(token.span), Some(JsonDialect.id))
