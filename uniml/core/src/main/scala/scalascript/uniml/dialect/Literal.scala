package scalascript.uniml.dialect

import scalascript.uniml.*

object Literal extends DialectAdapter:
  val id: String = "uniml.literal"

  override val aliases: Set[String] = Set("literal", "text", "unknown")

  def instructions(source: SourceInput): Processor[String, SourceChunk, VmToken] =
    LiteralProcessor(source.source)

/** The literal emit walk's threaded state (top level: v2 does not lower type decls
  * nested in a plain-class body). */
private final case class LiteralEmit(position: SourcePosition, nextTokenId: Long, out: Vector[VmToken])

/** Pure processor: accumulates the source (chunk-invariant by construction) and
  * tokenizes the whole string at `stop`, one token per code point. */
private final case class LiteralProcessor(source: SourceId) extends Processor[String, SourceChunk, VmToken]:
  def start: String = ""

  def step(state: String, input: SourceChunk): Stepped[String, VmToken] =
    Stepped(state + input.text, ProcessBatch.empty)

  def stop(text: String): ProcessBatch[VmToken] =
    def emit(st: LiteralEmit, lexeme: String, valid: Boolean): LiteralEmit =
      val start = st.position
      val end = Unicode.advance(start, lexeme)
      val token = SourceToken(
        id = st.nextTokenId,
        kind = if valid then "literal.code-point" else "literal.unpaired-surrogate",
        lexeme = lexeme,
        span = SourceSpan(source, start, end),
        channel = if valid then TokenChannel.Syntax else TokenChannel.Error,
      )
      val instruction =
        if valid then VmInstruction.Emit()
        else VmInstruction.Report(
          code = "uniml.literal.unpaired-surrogate",
          message = "input contains an unpaired UTF-16 surrogate",
        )
      LiteralEmit(end, st.nextTokenId + 1, st.out :+ VmToken(token, instruction))
    def walk(st: LiteralEmit, index: Int): LiteralEmit =
      if index >= text.length then st
      else
        val char = text.charAt(index)
        if Unicode.isHighSurrogate(char) && index + 1 < text.length && Unicode.isLowSurrogate(text.charAt(index + 1)) then
          walk(emit(st, s"$char${text.charAt(index + 1)}", valid = true), index + 2)
        else if Unicode.isHighSurrogate(char) || Unicode.isLowSurrogate(char) then
          walk(emit(st, char.toString, valid = false), index + 1)
        else
          walk(emit(st, char.toString, valid = true), index + 1)
    ProcessBatch(walk(LiteralEmit(SourcePosition.Start, 0L, Vector.empty), 0).out, Vector.empty)
