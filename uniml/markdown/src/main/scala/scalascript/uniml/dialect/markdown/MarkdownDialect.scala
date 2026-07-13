package scalascript.uniml.dialect.markdown

import scalascript.uniml.*

/** Baseline CommonMark 0.31.2 dialect. */
object CommonMarkDialect extends DialectAdapter:
  val id: String = "markdown.commonmark.0.31.2"
  override val aliases: Set[String] = Set("markdown", "commonmark", "text/markdown")
  def instructions(source: SourceInput): Processor[String, SourceChunk, VmToken] =
    MarkdownInstructionProcessor(source.source, MarkdownProfile.CommonMark, MarkdownLimits.default)

/** GitHub Flavored Markdown 0.29 dialect (tables, task items, strikethrough,
  * extended autolinks) — an explicit opt-in over CommonMark. */
object GfmDialect extends DialectAdapter:
  val id: String = "markdown.gfm.0.29"
  override val aliases: Set[String] = Set("gfm")
  def instructions(source: SourceInput): Processor[String, SourceChunk, VmToken] =
    MarkdownInstructionProcessor(source.source, MarkdownProfile.Gfm, MarkdownLimits.default)

/** ScalaScript Markdown dialect: front matter, typed fences and `${expr}`
  * boundaries, all inert until an explicitly selected downstream processor runs. */
object ScalaScriptMarkdownDialect extends DialectAdapter:
  val id: String = "markdown.scalascript"
  override val aliases: Set[String] = Set("ssc-markdown")
  def instructions(source: SourceInput): Processor[String, SourceChunk, VmToken] =
    MarkdownInstructionProcessor(source.source, MarkdownProfile.ScalaScript, MarkdownLimits.default)

private def dialectFor(profile: MarkdownProfile): DialectAdapter = profile match
  case MarkdownProfile.CommonMark   => CommonMarkDialect
  case MarkdownProfile.Gfm          => GfmDialect
  case MarkdownProfile.ScalaScript  => ScalaScriptMarkdownDialect

private def dialectId(profile: MarkdownProfile): String = dialectFor(profile).id

/** Facade over the three profiles. */
object Markdown:
  def parse(
      source: SourceInput,
      profile: MarkdownProfile = MarkdownProfile.CommonMark,
      limits: MarkdownLimits = MarkdownLimits.default,
  ): ParseResult =
    UniML.parse(source, ConfiguredMarkdownDialect(profile, limits), limits.core)

  def project(result: ParseResult, profile: MarkdownProfile = MarkdownProfile.CommonMark): MarkdownProjectionResult =
    MarkdownProjection.project(result, profile)

private final case class ConfiguredMarkdownDialect(profile: MarkdownProfile, limits: MarkdownLimits)
    extends DialectAdapter:
  val id: String = dialectId(profile)
  override val aliases: Set[String] = Set.empty
  def instructions(source: SourceInput): Processor[String, SourceChunk, VmToken] =
    MarkdownInstructionProcessor(source.source, profile, limits)

/** Pure processor: accumulates the source (immutable `String` state) and runs the
  * whole-source block pass at `stop`, so tokens, CST and diagnostics are identical
  * for any transport chunk split. */
private final case class MarkdownInstructionProcessor(
    source: SourceId,
    profile: MarkdownProfile,
    limits: MarkdownLimits,
) extends Processor[String, SourceChunk, VmToken]:
  def start: String = ""

  def step(state: String, input: SourceChunk): Stepped[String, VmToken] =
    Stepped(state + input.text, ProcessBatch.empty)

  def stop(state: String): ProcessBatch[VmToken] =
    val result = MarkdownBlocks(source, profile, limits).parse(state)
    ProcessBatch(result.tokens, result.diagnostics)
