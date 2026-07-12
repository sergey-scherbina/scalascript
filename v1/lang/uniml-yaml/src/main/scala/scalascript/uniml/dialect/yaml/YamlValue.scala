package scalascript.uniml.dialect.yaml

import scalascript.uniml.{Diagnostic, SourceSpan}

enum ScalarStyle:
  case Plain, SingleQuoted, DoubleQuoted, Literal, Folded

enum YamlScalar:
  case StringValue(value: String, lexeme: String, style: ScalarStyle)
  case NullValue(lexeme: String)
  case BooleanValue(value: Boolean, lexeme: String)
  case IntegerValue(lexeme: String)
  case FloatValue(lexeme: String)

enum YamlValue:
  case Stream(documents: Vector[YamlDocument])
  case Mapping(entries: Vector[YamlEntry], tag: Option[String], anchor: Option[String])
  case Sequence(values: Vector[YamlValue], tag: Option[String], anchor: Option[String])
  case Scalar(value: YamlScalar, tag: Option[String], anchor: Option[String])
  case Alias(name: String)

final case class YamlEntry(key: YamlValue, value: YamlValue, span: SourceSpan)

final case class YamlDirective(
    name: String,
    value: String,
    lexeme: String,
    span: SourceSpan,
)

final case class YamlDocument(
    value: Option[YamlValue],
    directives: Vector[YamlDirective],
)

enum YamlSchema:
  case Failsafe, Json, Core

enum AliasPolicy:
  case Preserve, Resolve

final case class YamlProjectionOptions(
    schema: YamlSchema = YamlSchema.Core,
    aliases: AliasPolicy = AliasPolicy.Preserve,
    maxAliasExpansions: Int = 100_000,
    maxExpandedNodes: Int = 1_000_000,
)

object YamlProjectionOptions:
  val default: YamlProjectionOptions = YamlProjectionOptions()

final case class YamlProjectionResult(
    value: Option[YamlValue],
    diagnostics: Vector[Diagnostic],
)
