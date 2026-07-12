package scalascript.uniml.dialect.json

import scalascript.uniml.SourceSpan

enum JsonValue:
  case ObjectValue(members: Vector[JsonMember])
  case ArrayValue(values: Vector[JsonValue])
  case StringValue(value: String, lexeme: String)
  case NumberValue(lexeme: String)
  case BooleanValue(value: Boolean)
  case NullValue

final case class JsonMember(
    name: String,
    nameLexeme: String,
    value: JsonValue,
    span: SourceSpan,
)

final case class JsonProjectionResult(
    value: Option[JsonValue],
    diagnostics: Vector[scalascript.uniml.Diagnostic],
)

enum DuplicateKeyPolicy:
  case Reject, FirstWins, LastWins
