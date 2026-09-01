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
    // INDEX THE CODE UNITS, NOT THE STRING. `charAt`/`length` are O(1) on the JVM but not on
    // every backend: the ScalaScript Rust backend stores a String as UTF-8 and emulates JVM
    // code-unit indexing over it, so each `charAt(i)` costs O(i) and each `length` costs O(n) —
    // making this scan O(n²) there. A CPU profile of a 256 KB markdown parse, taken once the
    // allocation-side quadratics were gone, was 100% this function and its `charAt`.
    // `text.toVector` pays one O(n) conversion and every index after it is a vector index.
    // Identical semantics on both lanes: `Vector[Char]` is code UNITS, exactly what `charAt`
    // yields, so surrogate pairs still arrive as two elements and `chars.length == text.length`.
    // annotated: the Rust backend cannot infer a CAPTURED local's type inside a lifted
    // walk (BUGS: rust-backend-member-read-through-local-val, the capture relative)
    val chars: Vector[Char] = text.toVector
    def walk(index: Int, count: Int): Int =
      if index >= chars.length then count
      else
        val char = chars(index)
        val width =
          if isHighSurrogate(char) && index + 1 < chars.length && isLowSurrogate(chars(index + 1)) then 2
          else 1
        walk(index + width, count + 1)
    walk(0, 0)

  def advance(position: SourcePosition, lexeme: String): SourcePosition =
    // Same reason as `codePointCount` just above: index the code units, not the string.
    // annotated for the same backend capture rule as codePointCount's chars
    val chars: Vector[Char] = lexeme.toVector
    def walk(index: Int, offset: Int, line: Int, column: Int): SourcePosition =
      if index >= chars.length then SourcePosition(offset, line, column)
      else
        val char = chars(index)
        val width =
          if isHighSurrogate(char) && index + 1 < chars.length && isLowSurrogate(chars(index + 1)) then 2
          else 1
        if char == '\n' then walk(index + width, offset + 1, line + 1, 1)
        else walk(index + width, offset + 1, line, column + 1)
    walk(0, position.offset, position.line, position.column)
