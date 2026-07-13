package scalascript.uniml.dialect

import scalascript.uniml.*

object Literal extends DialectAdapter:
  val id: String = "uniml.literal"

  override val aliases: Set[String] = Set("literal", "text", "unknown")

  def instructions(source: SourceInput): Processor[String, SourceChunk, VmToken] =
    LiteralProcessor(source.source)

/** Pure processor: accumulates the source (chunk-invariant by construction) and
  * tokenizes the whole string at `stop`, one token per code point. */
private final case class LiteralProcessor(source: SourceId) extends Processor[String, SourceChunk, VmToken]:
  def start: String = ""

  def step(state: String, input: SourceChunk): Stepped[String, VmToken] =
    Stepped(state + input.text, ProcessBatch.empty)

  def stop(text: String): ProcessBatch[VmToken] =
    var position = SourcePosition.Start
    var nextTokenId = 0L
    var out: Vector[VmToken] = Vector.empty
    def emit(lexeme: String, valid: Boolean): Unit =
      val start = position
      val end = Unicode.advance(start, lexeme)
      position = end
      val token = SourceToken(
        id = nextTokenId,
        kind = if valid then "literal.code-point" else "literal.unpaired-surrogate",
        lexeme = lexeme,
        span = SourceSpan(source, start, end),
        channel = if valid then TokenChannel.Syntax else TokenChannel.Error,
      )
      nextTokenId += 1
      val instruction =
        if valid then VmInstruction.Emit()
        else VmInstruction.Report(
          code = "uniml.literal.unpaired-surrogate",
          message = "input contains an unpaired UTF-16 surrogate",
        )
      out = out :+ VmToken(token, instruction)
    var index = 0
    while index < text.length do
      val char = text.charAt(index)
      if Unicode.isHighSurrogate(char) && index + 1 < text.length && Unicode.isLowSurrogate(text.charAt(index + 1)) then
        emit(s"$char${text.charAt(index + 1)}", valid = true)
        index += 2
      else if Unicode.isHighSurrogate(char) || Unicode.isLowSurrogate(char) then
        emit(char.toString, valid = false)
        index += 1
      else
        emit(char.toString, valid = true)
        index += 1
    ProcessBatch(out, Vector.empty)
