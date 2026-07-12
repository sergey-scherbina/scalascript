package scalascript.uniml.dialect.json

import scalascript.uniml.*
import scala.collection.mutable.ArrayBuffer

private final case class JsonStructureBatch(tokens: Vector[VmToken], diagnostics: Vector[Diagnostic])

private object JsonStructure:
  private enum DocumentState:
    case Value, End

  private enum ObjectState:
    case KeyOrEnd, Key, Colon, Value, CommaOrEnd

  private enum ArrayState:
    case ValueOrEnd, Value, CommaOrEnd

  private sealed trait Frame
  private final case class ObjectFrame(var state: ObjectState) extends Frame
  private final case class ArrayFrame(var state: ArrayState) extends Frame

  def assign(tokens: Vector[JsonLexToken], eof: SourcePosition, source: SourceId): JsonStructureBatch =
    val instructions = ArrayBuffer.empty[VmToken]
    val diagnostics = ArrayBuffer.empty[Diagnostic]
    val stack = ArrayBuffer.empty[Frame]
    var document = DocumentState.Value

    tokens.foreach { lexed =>
      val token = lexed.token
      if token.kind == "json.whitespace" then emit(token, VmInstruction.Emit(Some("trivia")), instructions)
      else if token.kind == "json.bom" then
        val issue = lexed.issue.get
        emit(token, VmInstruction.Report(issue.code, issue.message, issue.severity), instructions)
      else lexed.issue match
        case Some(issue) =>
          emit(token, VmInstruction.Report(issue.code, issue.message, issue.severity), instructions)
          recoverAfterInvalid(stack, () => document = DocumentState.End)
        case None =>
          var reprocess = true
          while reprocess do
            reprocess = false
            if stack.isEmpty then
              document match
                case DocumentState.Value =>
                  consumeValue(token, Some("document.value"), stack, instructions, diagnostics) match
                    case true  => document = DocumentState.End
                    case false => ()
                case DocumentState.End =>
                  emit(token, VmInstruction.Report(
                    "uniml.json.trailing-data",
                    "JSON text contains data after the root value",
                  ), instructions)
            else stack.last match
              case frame: ObjectFrame =>
                frame.state match
                  case ObjectState.KeyOrEnd =>
                    if token.kind == "json.rbrace" then closeObject(token, stack, instructions)
                    else consumeKey(token, frame, instructions)
                  case ObjectState.Key =>
                    if token.kind == "json.rbrace" then
                      diagnostics += tokenDiagnostic(token, "uniml.json.trailing-comma", "trailing comma in JSON object")
                      closeObject(token, stack, instructions)
                    else consumeKey(token, frame, instructions)
                  case ObjectState.Colon =>
                    if token.kind == "json.colon" then
                      emit(token, VmInstruction.Emit(Some("member.colon")), instructions)
                      frame.state = ObjectState.Value
                    else if isValueStart(token) then
                      diagnostics += tokenDiagnostic(token, "uniml.json.expected-colon", "expected ':' after JSON object key")
                      frame.state = ObjectState.Value
                      reprocess = true
                    else emit(token, VmInstruction.Report(
                      "uniml.json.expected-colon",
                      "expected ':' after JSON object key",
                    ), instructions)
                  case ObjectState.Value =>
                    if consumeValue(token, Some("member.value"), stack, instructions, diagnostics) then
                      frame.state = ObjectState.CommaOrEnd
                  case ObjectState.CommaOrEnd =>
                    token.kind match
                      case "json.comma" =>
                        emit(token, VmInstruction.Emit(Some("member.separator")), instructions)
                        frame.state = ObjectState.Key
                      case "json.rbrace" => closeObject(token, stack, instructions)
                      case _ if token.kind == "json.string" =>
                        diagnostics += tokenDiagnostic(token, "uniml.json.expected-comma-or-end", "expected ',' or '}' after JSON object member")
                        frame.state = ObjectState.Key
                        reprocess = true
                      case _ => emit(token, VmInstruction.Report(
                        "uniml.json.expected-comma-or-end",
                        "expected ',' or '}' after JSON object member",
                      ), instructions)

              case frame: ArrayFrame =>
                frame.state match
                  case ArrayState.ValueOrEnd =>
                    if token.kind == "json.rbracket" then closeArray(token, stack, instructions)
                    else if consumeValue(token, Some("array.element"), stack, instructions, diagnostics) then
                      frame.state = ArrayState.CommaOrEnd
                  case ArrayState.Value =>
                    if token.kind == "json.rbracket" then
                      diagnostics += tokenDiagnostic(token, "uniml.json.trailing-comma", "trailing comma in JSON array")
                      closeArray(token, stack, instructions)
                    else if consumeValue(token, Some("array.element"), stack, instructions, diagnostics) then
                      frame.state = ArrayState.CommaOrEnd
                  case ArrayState.CommaOrEnd =>
                    token.kind match
                      case "json.comma" =>
                        emit(token, VmInstruction.Emit(Some("array.separator")), instructions)
                        frame.state = ArrayState.Value
                      case "json.rbracket" => closeArray(token, stack, instructions)
                      case _ if isValueStart(token) =>
                        diagnostics += tokenDiagnostic(token, "uniml.json.expected-comma-or-end", "expected ',' or ']' after JSON array element")
                        frame.state = ArrayState.Value
                        reprocess = true
                      case _ => emit(token, VmInstruction.Report(
                        "uniml.json.expected-comma-or-end",
                        "expected ',' or ']' after JSON array element",
                      ), instructions)
    }

    val eofSpan = SourceSpan(source, eof, eof)
    if document == DocumentState.Value then
      diagnostics += Diagnostic(
        code = "uniml.json.unexpected-eof",
        message = "expected a JSON value before end of input",
        severity = Severity.Error,
        span = Some(eofSpan),
        dialect = Some(JsonDialect.id),
      )
    if stack.nonEmpty then
      diagnostics += Diagnostic(
        code = "uniml.json.unexpected-eof",
        message = "JSON container is not closed before end of input",
        severity = Severity.Error,
        span = Some(eofSpan),
        dialect = Some(JsonDialect.id),
      )
    JsonStructureBatch(instructions.toVector, diagnostics.toVector)

  private def consumeKey(token: SourceToken, frame: ObjectFrame, output: ArrayBuffer[VmToken]): Unit =
    if token.kind == "json.string" then
      emit(token, VmInstruction.Emit(Some("member.key")), output)
      frame.state = ObjectState.Colon
    else
      emit(token, VmInstruction.Report("uniml.json.expected-key", "expected a quoted JSON object key"), output)
      frame.state = ObjectState.Colon

  private def consumeValue(
      token: SourceToken,
      role: Option[String],
      stack: ArrayBuffer[Frame],
      output: ArrayBuffer[VmToken],
      diagnostics: ArrayBuffer[Diagnostic],
  ): Boolean =
    token.kind match
      case "json.lbrace" =>
        emit(token, VmInstruction.Open("json.object", role), output)
        stack += ObjectFrame(ObjectState.KeyOrEnd)
        true
      case "json.lbracket" =>
        emit(token, VmInstruction.Open("json.array", role), output)
        stack += ArrayFrame(ArrayState.ValueOrEnd)
        true
      case "json.string" | "json.number" | "json.true" | "json.false" | "json.null" =>
        emit(token, VmInstruction.Emit(role), output)
        true
      case _ =>
        val _ = diagnostics
        emit(token, VmInstruction.Report("uniml.json.expected-value", "expected a JSON value"), output)
        true

  private def closeObject(token: SourceToken, stack: ArrayBuffer[Frame], output: ArrayBuffer[VmToken]): Unit =
    emit(token, VmInstruction.Close(Some("json.object"), Some("delimiter.close")), output)
    stack.remove(stack.size - 1)

  private def closeArray(token: SourceToken, stack: ArrayBuffer[Frame], output: ArrayBuffer[VmToken]): Unit =
    emit(token, VmInstruction.Close(Some("json.array"), Some("delimiter.close")), output)
    stack.remove(stack.size - 1)

  private def recoverAfterInvalid(stack: ArrayBuffer[Frame], endDocument: () => Unit): Unit =
    if stack.isEmpty then endDocument()
    else stack.last match
      case frame: ObjectFrame =>
        frame.state match
          case ObjectState.KeyOrEnd | ObjectState.Key => frame.state = ObjectState.Colon
          case ObjectState.Value                      => frame.state = ObjectState.CommaOrEnd
          case _                                      => ()
      case frame: ArrayFrame =>
        frame.state match
          case ArrayState.ValueOrEnd | ArrayState.Value => frame.state = ArrayState.CommaOrEnd
          case _                                        => ()

  private def isValueStart(token: SourceToken): Boolean =
    token.kind == "json.lbrace" || token.kind == "json.lbracket" || token.kind == "json.string" ||
      token.kind == "json.number" || token.kind == "json.true" || token.kind == "json.false" ||
      token.kind == "json.null"

  private def emit(token: SourceToken, instruction: VmInstruction, output: ArrayBuffer[VmToken]): Unit =
    output += VmToken(token, instruction)

  private def tokenDiagnostic(token: SourceToken, code: String, message: String): Diagnostic =
    Diagnostic(code, message, Severity.Error, Some(token.span), Some(JsonDialect.id))
