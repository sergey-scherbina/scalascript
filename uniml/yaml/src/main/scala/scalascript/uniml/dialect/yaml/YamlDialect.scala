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

  def instructions(source: SourceInput): Processor[String, SourceChunk, VmToken] =
    YamlInstructionProcessor(source.source, YamlLimits.default)

  def withLimits(limits: YamlLimits): DialectAdapter = ConfiguredYamlDialect(limits)

private final case class ConfiguredYamlDialect(limits: YamlLimits) extends DialectAdapter:
  val id: String = YamlDialect.id

  override val aliases: Set[String] = YamlDialect.aliases

  def instructions(source: SourceInput): Processor[String, SourceChunk, VmToken] =
    YamlInstructionProcessor(source.source, limits)

object Yaml:
  def parse(source: SourceInput, limits: YamlLimits = YamlLimits.default): ParseResult =
    UniML.parse(source, YamlDialect.withLimits(limits), limits.core)

  def project(
      result: ParseResult,
      options: YamlProjectionOptions = YamlProjectionOptions.default,
  ): YamlProjectionResult = YamlProjection.project(result, options)

private final case class YamlInstructionProcessor(source: SourceId, limits: YamlLimits)
    extends Processor[String, SourceChunk, VmToken]:
  def start: String = ""

  def step(state: String, input: SourceChunk): Stepped[String, VmToken] =
    Stepped(state + input.text, ProcessBatch.empty)

  def stop(state: String): ProcessBatch[VmToken] =
    if Unicode.codePointCount(state).toLong > limits.maxSourceCodePoints then
      ProcessBatch(Vector.empty, Vector(Diagnostic(
        code = "uniml.yaml.limit.source",
        message = s"YAML source exceeds the ${limits.maxSourceCodePoints} code-point limit",
        severity = Severity.Fatal,
        span = None,
        dialect = Some(YamlDialect.id),
      )))
    else
      val lexed = YamlLexer.scan(source, state, limits)
      val structured = YamlStructure.assign(lexed.tokens)
      var countDiagnostics: Vector[Diagnostic] = Vector.empty
      val anchorCount = lexed.tokens.count(_.kind == "yaml.anchor")
      val aliasCount = lexed.tokens.count(_.kind == "yaml.alias")
      if anchorCount > limits.maxAnchors then
        countDiagnostics = countDiagnostics :+ Diagnostic(
          "uniml.yaml.limit.anchors",
          s"YAML source contains $anchorCount anchors; limit is ${limits.maxAnchors}",
          Severity.Fatal,
          lexed.tokens.find(_.kind == "yaml.anchor").map(_.span),
          Some(YamlDialect.id),
        )
      if aliasCount > limits.maxAliases then
        countDiagnostics = countDiagnostics :+ Diagnostic(
          "uniml.yaml.limit.aliases",
          s"YAML source contains $aliasCount aliases; limit is ${limits.maxAliases}",
          Severity.Fatal,
          lexed.tokens.find(_.kind == "yaml.alias").map(_.span),
          Some(YamlDialect.id),
        )
      ProcessBatch(structured.tokens, lexed.diagnostics ++ structured.diagnostics ++ countDiagnostics)
