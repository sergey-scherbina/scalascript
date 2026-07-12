package scalascript.uniml

enum Severity:
  case Info, Warning, Error, Fatal

final case class Diagnostic(
    code: String,
    message: String,
    severity: Severity,
    span: Option[SourceSpan],
    dialect: Option[String] = None,
    details: Vector[(String, String)] = Vector.empty,
)

enum CompletionStatus:
  case Complete, Incomplete, Halted
