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
// Top-level (not nested in the object): v2's .ssc frontend does not support type
// declarations nested inside an object body.
private enum DocumentState:
  case Value, End

private enum ObjectState:
  case KeyOrEnd, Key, Colon, Value, CommaOrEnd

private enum ArrayState:
  case ValueOrEnd, Value, CommaOrEnd

private sealed trait Frame
private final case class ObjectFrame(state: ObjectState) extends Frame
private final case class ArrayFrame(state: ArrayState) extends Frame

private object JsonStructure:

  /** The assigner's whole state, threaded as a pure fold over the token stream — the same
    * `step(state, chunk)` shape JsonLexer took, one field per former `var`. */
  private final case class AssignState(
      instructions: Vector[VmToken],
      diagnostics: Vector[Diagnostic],
      stack: Vector[Frame],
      document: DocumentState,
  )

  def assign(tokens: Vector[JsonLexToken], eof: SourcePosition, source: SourceId): JsonStructureBatch =

    def emit(s: AssignState, token: SourceToken, instruction: VmInstruction): AssignState =
      s.copy(instructions = s.instructions :+ VmToken(token, instruction))

    // replace the current top frame with a state-transitioned copy
    def retop(s: AssignState, frame: Frame): AssignState =
      s.copy(stack = s.stack.dropRight(1) :+ frame)

    // The Boolean mirrors the imperative original, where every arm answered `true` and the one
    // caller used it to move the document to End. It is vestigial TODAY; it is kept so the
    // conversion is a re-spelling and not a redesign — collapsing it is a separate, visible change.
    def consumeValue(s: AssignState, token: SourceToken, role: Option[String]): (AssignState, Boolean) =
      token.kind match
        case "json.lbrace" =>
          (emit(s, token, VmInstruction.Open("json.object", role))
            .copy(stack = s.stack :+ ObjectFrame(ObjectState.KeyOrEnd)), true)
        case "json.lbracket" =>
          (emit(s, token, VmInstruction.Open("json.array", role))
            .copy(stack = s.stack :+ ArrayFrame(ArrayState.ValueOrEnd)), true)
        case "json.string" | "json.number" | "json.true" | "json.false" | "json.null" =>
          (emit(s, token, VmInstruction.Emit(role)), true)
        case _ =>
          (emit(s, token, VmInstruction.Report("uniml.json.expected-value", "expected a JSON value")), true)

    def consumeKey(s: AssignState, token: SourceToken, frame: ObjectFrame): AssignState =
      val emitted =
        if token.kind == "json.string" then emit(s, token, VmInstruction.Emit(Some("member.key")))
        else emit(s, token, VmInstruction.Report("uniml.json.expected-key", "expected a quoted JSON object key"))
      retop(emitted, frame.copy(state = ObjectState.Colon))

    def closeObject(s: AssignState, token: SourceToken): AssignState =
      emit(s, token, VmInstruction.Close(Some("json.object"), Some("delimiter.close")))
        .copy(stack = s.stack.dropRight(1))

    def closeArray(s: AssignState, token: SourceToken): AssignState =
      emit(s, token, VmInstruction.Close(Some("json.array"), Some("delimiter.close")))
        .copy(stack = s.stack.dropRight(1))

    def recoverAfterInvalid(s: AssignState): AssignState =
      if s.stack.isEmpty then s.copy(document = DocumentState.End)
      else s.stack.last match
        case frame: ObjectFrame =>
          frame.state match
            case ObjectState.KeyOrEnd | ObjectState.Key => retop(s, frame.copy(state = ObjectState.Colon))
            case ObjectState.Value                      => retop(s, frame.copy(state = ObjectState.CommaOrEnd))
            case _                                      => s
        case frame: ArrayFrame =>
          frame.state match
            case ArrayState.ValueOrEnd | ArrayState.Value => retop(s, frame.copy(state = ArrayState.CommaOrEnd))
            case _                                        => s

    def diag(s: AssignState, token: SourceToken, code: String, message: String): AssignState =
      s.copy(diagnostics = s.diagnostics :+ tokenDiagnostic(token, code, message))

    // The original's `reprocess` while-loop, as bounded recursion. Three arms re-dispatch — a
    // value-start where a colon was due, and a member/element start where a comma was due — and
    // each first moves its frame to the state that consumes the token, so a re-entry takes a
    // different branch and the depth is at most two, exactly as the loop's was.
    def dispatch(s: AssignState, token: SourceToken): AssignState =
      if s.stack.isEmpty then
        s.document match
          case DocumentState.Value =>
            val (consumed, done) = consumeValue(s, token, Some("document.value"))
            if done then consumed.copy(document = DocumentState.End) else consumed
          case DocumentState.End =>
            emit(s, token, VmInstruction.Report(
              "uniml.json.trailing-data",
              "JSON text contains data after the root value",
            ))
      else s.stack.last match
        case frame: ObjectFrame =>
          frame.state match
            case ObjectState.KeyOrEnd =>
              if token.kind == "json.rbrace" then closeObject(s, token)
              else consumeKey(s, token, frame)
            case ObjectState.Key =>
              if token.kind == "json.rbrace" then
                closeObject(diag(s, token, "uniml.json.trailing-comma", "trailing comma in JSON object"), token)
              else consumeKey(s, token, frame)
            case ObjectState.Colon =>
              if token.kind == "json.colon" then
                retop(emit(s, token, VmInstruction.Emit(Some("member.colon"))), frame.copy(state = ObjectState.Value))
              else if isValueStart(token) then
                dispatch(
                  retop(diag(s, token, "uniml.json.expected-colon", "expected ':' after JSON object key"),
                        frame.copy(state = ObjectState.Value)),
                  token,
                )
              else emit(s, token, VmInstruction.Report(
                "uniml.json.expected-colon",
                "expected ':' after JSON object key",
              ))
            case ObjectState.Value =>
              // pre-set the object frame to CommaOrEnd (consumeValue only
              // appends any nested container above it), then consume
              consumeValue(retop(s, frame.copy(state = ObjectState.CommaOrEnd)), token, Some("member.value"))._1
            case ObjectState.CommaOrEnd =>
              token.kind match
                case "json.comma" =>
                  retop(emit(s, token, VmInstruction.Emit(Some("member.separator"))), frame.copy(state = ObjectState.Key))
                case "json.rbrace" => closeObject(s, token)
                case _ if token.kind == "json.string" =>
                  dispatch(
                    retop(diag(s, token, "uniml.json.expected-comma-or-end", "expected ',' or '}' after JSON object member"),
                          frame.copy(state = ObjectState.Key)),
                    token,
                  )
                case _ => emit(s, token, VmInstruction.Report(
                  "uniml.json.expected-comma-or-end",
                  "expected ',' or '}' after JSON object member",
                ))
        case frame: ArrayFrame =>
          frame.state match
            case ArrayState.ValueOrEnd =>
              if token.kind == "json.rbracket" then closeArray(s, token)
              else consumeValue(retop(s, frame.copy(state = ArrayState.CommaOrEnd)), token, Some("array.element"))._1
            case ArrayState.Value =>
              if token.kind == "json.rbracket" then
                closeArray(diag(s, token, "uniml.json.trailing-comma", "trailing comma in JSON array"), token)
              else consumeValue(retop(s, frame.copy(state = ArrayState.CommaOrEnd)), token, Some("array.element"))._1
            case ArrayState.CommaOrEnd =>
              token.kind match
                case "json.comma" =>
                  retop(emit(s, token, VmInstruction.Emit(Some("array.separator"))), frame.copy(state = ArrayState.Value))
                case "json.rbracket" => closeArray(s, token)
                case _ if isValueStart(token) =>
                  dispatch(
                    retop(diag(s, token, "uniml.json.expected-comma-or-end", "expected ',' or ']' after JSON array element"),
                          frame.copy(state = ArrayState.Value)),
                    token,
                  )
                case _ => emit(s, token, VmInstruction.Report(
                  "uniml.json.expected-comma-or-end",
                  "expected ',' or ']' after JSON array element",
                ))

    def feed(s: AssignState, lexed: JsonLexToken): AssignState =
      val token = lexed.token
      if token.kind == "json.whitespace" then emit(s, token, VmInstruction.Emit(Some("trivia")))
      else if token.kind == "json.bom" then
        val issue = lexed.issue.get
        emit(s, token, VmInstruction.Report(issue.code, issue.message, issue.severity))
      else lexed.issue match
        case Some(issue) =>
          recoverAfterInvalid(emit(s, token, VmInstruction.Report(issue.code, issue.message, issue.severity)))
        case None => dispatch(s, token)

    val walked = tokens.foldLeft(AssignState(Vector.empty, Vector.empty, Vector.empty, DocumentState.Value))(feed)

    val eofSpan = SourceSpan(source, eof, eof)
    val withValueEof =
      if walked.document == DocumentState.Value then
        walked.copy(diagnostics = walked.diagnostics :+ Diagnostic(
          code = "uniml.json.unexpected-eof",
          message = "expected a JSON value before end of input",
          severity = Severity.Error,
          span = Some(eofSpan),
          dialect = Some(JsonDialect.id),
        ))
      else walked
    val finished =
      if withValueEof.stack.nonEmpty then
        withValueEof.copy(diagnostics = withValueEof.diagnostics :+ Diagnostic(
          code = "uniml.json.unexpected-eof",
          message = "JSON container is not closed before end of input",
          severity = Severity.Error,
          span = Some(eofSpan),
          dialect = Some(JsonDialect.id),
        ))
      else withValueEof
    JsonStructureBatch(finished.instructions, finished.diagnostics)

  private def isValueStart(token: SourceToken): Boolean =
    token.kind == "json.lbrace" || token.kind == "json.lbracket" || token.kind == "json.string" ||
      token.kind == "json.number" || token.kind == "json.true" || token.kind == "json.false" ||
      token.kind == "json.null"

  private def tokenDiagnostic(token: SourceToken, code: String, message: String): Diagnostic =
    Diagnostic(code, message, Severity.Error, Some(token.span), Some(JsonDialect.id))
