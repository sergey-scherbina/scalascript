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

  def instructions(source: SourceInput): Processor[String, SourceChunk, VmToken] =
    JsonInstructionProcessor(source.source, JsonLimits.default)

  def withLimits(limits: JsonLimits): DialectAdapter = ConfiguredJsonDialect(limits)

private final case class ConfiguredJsonDialect(limits: JsonLimits) extends DialectAdapter:
  val id: String = JsonDialect.id

  override val aliases: Set[String] = JsonDialect.aliases

  def instructions(source: SourceInput): Processor[String, SourceChunk, VmToken] =
    JsonInstructionProcessor(source.source, limits)

object Json:
  def parse(source: SourceInput, limits: JsonLimits = JsonLimits.default): ParseResult =
    UniML.parse(source, JsonDialect.withLimits(limits), limits.core)

  def project(result: ParseResult): JsonProjectionResult = JsonProjection.project(result)

private final case class JsonInstructionProcessor(source: SourceId, limits: JsonLimits)
    extends Processor[String, SourceChunk, VmToken]:
  def start: String = ""

  def step(state: String, input: SourceChunk): Stepped[String, VmToken] =
    Stepped(state + input.text, ProcessBatch.empty)

  def stop(state: String): ProcessBatch[VmToken] =
    val lexer = JsonLexer(source, limits)
    val lexed1 = lexer.push(SourceChunk(state))
    val lexed2 = lexer.finish()
    val tokens = lexed1.tokens ++ lexed2.tokens
    val structured = JsonStructure.assign(tokens, lexer.position, source)
    ProcessBatch(structured.tokens, lexed1.diagnostics ++ lexed2.diagnostics ++ structured.diagnostics)
