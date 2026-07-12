package scalascript.uniml.dialect.yaml

import scalascript.uniml.*

final case class YamlLimits(
    core: Limits = Limits.default,
    maxSourceCodePoints: Long = 64L * 1024 * 1024,
    maxLineCodePoints: Int = 1024 * 1024,
    maxScalarCodePoints: Int = 16 * 1024 * 1024,
    maxIndentation: Int = 4096,
    maxAnchors: Int = 1_000_000,
    maxAliases: Int = 1_000_000,
)

object YamlLimits:
  val default: YamlLimits = YamlLimits()

object YamlDialect extends DialectAdapter:
  val id: String = "yaml.1.2.2"

  override val aliases: Set[String] = Set("yaml", "yml", "application/yaml")

  def instructions(source: SourceInput): Processor[SourceChunk, VmToken] =
    YamlInstructionProcessor(source.source, YamlLimits.default)

  def withLimits(limits: YamlLimits): DialectAdapter = ConfiguredYamlDialect(limits)

private final case class ConfiguredYamlDialect(limits: YamlLimits) extends DialectAdapter:
  val id: String = YamlDialect.id

  override val aliases: Set[String] = YamlDialect.aliases

  def instructions(source: SourceInput): Processor[SourceChunk, VmToken] =
    YamlInstructionProcessor(source.source, limits)

object Yaml:
  def parse(source: SourceInput, limits: YamlLimits = YamlLimits.default): ParseResult =
    UniML.parse(source, YamlDialect.withLimits(limits), limits.core)

  def project(
      result: ParseResult,
      options: YamlProjectionOptions = YamlProjectionOptions.default,
  ): YamlProjectionResult = YamlProjection.project(result, options)

private final class YamlInstructionProcessor(source: SourceId, limits: YamlLimits)
    extends Processor[SourceChunk, VmToken]:
  private val input = StringBuilder()
  private var codePoints = 0L
  private var finished = false
  private var sourceLimitReported = false

  def push(chunk: SourceChunk): ProcessBatch[VmToken] =
    if finished then ProcessBatch(Vector.empty, Vector(afterFinishDiagnostic))
    else
      val chunkPoints = Unicode.codePointCount(chunk.text).toLong
      if codePoints + chunkPoints > limits.maxSourceCodePoints then
        sourceLimitReported = true
        ProcessBatch(Vector.empty, Vector(Diagnostic(
          code = "uniml.yaml.limit.source",
          message = s"YAML source exceeds the ${limits.maxSourceCodePoints} code-point limit",
          severity = Severity.Fatal,
          span = None,
          dialect = Some(YamlDialect.id),
        )))
      else
        input.append(chunk.text)
        codePoints += chunkPoints
        ProcessBatch.empty

  def finish(): ProcessBatch[VmToken] =
    if finished then ProcessBatch(Vector.empty, Vector(afterFinishDiagnostic))
    else
      finished = true
      if sourceLimitReported then ProcessBatch.empty
      else
        val lexed = YamlLexer.scan(source, input.result(), limits)
        val structured = YamlStructure.assign(lexed.tokens)
        ProcessBatch(structured, lexed.diagnostics)

  private val afterFinishDiagnostic = Diagnostic(
    code = "uniml.yaml.finished",
    message = "YAML dialect processor cannot accept input or finish more than once",
    severity = Severity.Error,
    span = None,
    dialect = Some(YamlDialect.id),
  )
