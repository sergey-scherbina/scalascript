package scalascript.uniml

final case class SourceId(value: String)

final case class SourcePosition(offset: Int, line: Int, column: Int)

object SourcePosition:
  val Start: SourcePosition = SourcePosition(offset = 0, line = 1, column = 1)

final case class SourceSpan(source: SourceId, start: SourcePosition, end: SourcePosition)

final case class SourceChunk(text: String)

final case class SourceInput(source: SourceId, chunks: Vector[SourceChunk])

object SourceInput:
  def fromString(source: SourceId, text: String): SourceInput =
    SourceInput(source, Vector(SourceChunk(text)))

enum TokenChannel:
  case Syntax, Trivia, Comment, Embedded, Error

final case class SourceToken(
    id: Long,
    kind: String,
    lexeme: String,
    span: SourceSpan,
    channel: TokenChannel = TokenChannel.Syntax,
)

private[uniml] object Unicode:
  def isHighSurrogate(char: Char): Boolean = char >= '\uD800' && char <= '\uDBFF'

  def isLowSurrogate(char: Char): Boolean = char >= '\uDC00' && char <= '\uDFFF'

  def codePointCount(text: String): Int =
    var index = 0
    var count = 0
    while index < text.length do
      val char = text.charAt(index)
      if isHighSurrogate(char) && index + 1 < text.length && isLowSurrogate(text.charAt(index + 1)) then
        index += 2
      else index += 1
      count += 1
    count

  def advance(position: SourcePosition, lexeme: String): SourcePosition =
    var index = 0
    var offset = position.offset
    var line = position.line
    var column = position.column
    while index < lexeme.length do
      val char = lexeme.charAt(index)
      val width =
        if isHighSurrogate(char) && index + 1 < lexeme.length && isLowSurrogate(lexeme.charAt(index + 1)) then 2
        else 1
      if char == '\n' then
        line += 1
        column = 1
      else column += 1
      offset += 1
      index += width
    SourcePosition(offset, line, column)
