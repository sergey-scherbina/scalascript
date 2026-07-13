package scalascript.uniml.dialect

import scalascript.uniml.*

object Literal extends DialectAdapter:
  val id: String = "uniml.literal"

  override val aliases: Set[String] = Set("literal", "text", "unknown")

  def instructions(source: SourceInput): Processor[SourceChunk, VmToken] =
    LiteralProcessor(source.source)

private final class LiteralProcessor(source: SourceId) extends Processor[SourceChunk, VmToken]:
  private var position = SourcePosition.Start
  private var nextTokenId = 0L
  private var pendingHighSurrogate: Option[Char] = None
  private var finished = false

  def push(chunk: SourceChunk): ProcessBatch[VmToken] =
    if finished then ProcessBatch(Vector.empty, Vector(afterFinishDiagnostic))
    else
      val output = Vector.newBuilder[VmToken]
      var index = 0
      pendingHighSurrogate.foreach { high =>
        if chunk.text.nonEmpty && Unicode.isLowSurrogate(chunk.text.charAt(0)) then
          output += emit(s"$high${chunk.text.charAt(0)}", valid = true)
          index = 1
        else output += emit(high.toString, valid = false)
        pendingHighSurrogate = None
      }
      while index < chunk.text.length do
        val char = chunk.text.charAt(index)
        if Unicode.isHighSurrogate(char) then
          if index + 1 < chunk.text.length then
            val next = chunk.text.charAt(index + 1)
            if Unicode.isLowSurrogate(next) then
              output += emit(s"$char$next", valid = true)
              index += 2
            else
              output += emit(char.toString, valid = false)
              index += 1
          else
            pendingHighSurrogate = Some(char)
            index += 1
        else if Unicode.isLowSurrogate(char) then
          output += emit(char.toString, valid = false)
          index += 1
        else
          output += emit(char.toString, valid = true)
          index += 1
      ProcessBatch(output.result(), Vector.empty)

  def finish(): ProcessBatch[VmToken] =
    if finished then ProcessBatch(Vector.empty, Vector(afterFinishDiagnostic))
    else
      finished = true
      val values = pendingHighSurrogate match
        case Some(high) =>
          pendingHighSurrogate = None
          Vector(emit(high.toString, valid = false))
        case None => Vector.empty
      ProcessBatch(values, Vector.empty)

  private def emit(lexeme: String, valid: Boolean): VmToken =
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
    VmToken(token, instruction)

  private val afterFinishDiagnostic = Diagnostic(
    code = "uniml.literal.finished",
    message = "literal processor cannot accept input or finish more than once",
    severity = Severity.Error,
    span = None,
    dialect = Some(Literal.id),
  )
