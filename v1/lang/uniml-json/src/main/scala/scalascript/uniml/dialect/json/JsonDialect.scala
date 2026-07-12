package scalascript.uniml.dialect.json

import scalascript.uniml.*

final case class JsonLimits(
    core: Limits = Limits.default,
    maxSourceCodePoints: Long = 64L * 1024 * 1024,
    maxNumberCodePoints: Int = 4096,
    maxStringCodePoints: Int = 16 * 1024 * 1024,
)

object JsonLimits:
  val default: JsonLimits = JsonLimits()

object JsonDialect extends DialectAdapter:
  val id: String = "json.rfc8259"

  override val aliases: Set[String] = Set("json", "application/json")

  def instructions(source: SourceInput): Processor[SourceChunk, VmToken] =
    JsonInstructionProcessor(source.source, JsonLimits.default)

  def withLimits(limits: JsonLimits): DialectAdapter = ConfiguredJsonDialect(limits)

private final case class ConfiguredJsonDialect(limits: JsonLimits) extends DialectAdapter:
  val id: String = JsonDialect.id

  override val aliases: Set[String] = JsonDialect.aliases

  def instructions(source: SourceInput): Processor[SourceChunk, VmToken] =
    JsonInstructionProcessor(source.source, limits)

object Json:
  def parse(source: SourceInput, limits: JsonLimits = JsonLimits.default): ParseResult =
    UniML.parse(source, JsonDialect.withLimits(limits), limits.core)

  def project(result: ParseResult): JsonProjectionResult = JsonProjection.project(result)

private final class JsonInstructionProcessor(source: SourceId, limits: JsonLimits)
    extends Processor[SourceChunk, VmToken]:
  private val lexer = JsonLexer(source, limits)
  private val lexedTokens = scala.collection.mutable.ArrayBuffer.empty[JsonLexToken]
  private var finished = false

  def push(chunk: SourceChunk): ProcessBatch[VmToken] =
    if finished then ProcessBatch(Vector.empty, Vector(afterFinishDiagnostic))
    else
      val lexed = lexer.push(chunk)
      lexedTokens ++= lexed.tokens
      ProcessBatch(Vector.empty, lexed.diagnostics)

  def finish(): ProcessBatch[VmToken] =
    if finished then ProcessBatch(Vector.empty, Vector(afterFinishDiagnostic))
    else
      finished = true
      val lexed = lexer.finish()
      lexedTokens ++= lexed.tokens
      val structured = JsonStructure.assign(lexedTokens.toVector, lexer.position, source)
      ProcessBatch(
        structured.tokens,
        lexed.diagnostics ++ structured.diagnostics,
      )

  private val afterFinishDiagnostic = Diagnostic(
    code = "uniml.json.finished",
    message = "JSON dialect processor cannot accept input or finish more than once",
    severity = Severity.Error,
    span = None,
    dialect = Some(JsonDialect.id),
  )
