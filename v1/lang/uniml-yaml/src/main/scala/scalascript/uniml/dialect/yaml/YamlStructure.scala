package scalascript.uniml.dialect.yaml

import scalascript.uniml.*
import scala.collection.mutable

private[yaml] object YamlStructure:
  private final case class Range(
      kind: String,
      role: Option[String],
      start: Int,
      end: Int,
      rank: Int,
  )

  private final class BlockFrame(
      val indent: Int,
      val kind: String,
      val role: Option[String],
      val start: Int,
      var last: Int,
  )

  def assign(tokens: Vector[SourceToken]): Vector[VmToken] =
    if tokens.isEmpty then Vector.empty
    else
      val ranges = streamAndDocuments(tokens) ++ blockRanges(tokens) ++ flowRanges(tokens)
      val opens = ranges.groupBy(_.start)
      val closes = ranges.groupBy(_.end)
      tokens.indices.map { index =>
        val opening = opens.getOrElse(index, Vector.empty).sortBy(range => (range.rank, -range.end))
        val closing = closes.getOrElse(index, Vector.empty).sortBy(range => (-range.rank, -range.start))
        val instruction =
          if opening.nonEmpty || closing.nonEmpty then
            VmInstruction.Reframe(
              open = opening.map(range => FrameSpec(range.kind, range.role)),
              closeAfter = closing.map(_.kind),
              role = tokenRole(tokens(index)),
            )
          else VmInstruction.Emit(tokenRole(tokens(index)))
        VmToken(tokens(index), instruction)
      }.toVector

  private def streamAndDocuments(tokens: Vector[SourceToken]): Vector[Range] =
    val result = Vector.newBuilder[Range]
    result += Range("yaml.stream", None, 0, tokens.size - 1, 0)
    val documentStarts = tokens.indices.filter(index => tokens(index).kind == "yaml.document-start").toVector
    if documentStarts.isEmpty then result += Range("yaml.document", Some("stream.document"), 0, tokens.size - 1, 1)
    else
      val starts = if documentStarts.head == 0 then documentStarts else 0 +: documentStarts
      starts.indices.foreach { position =>
        val start = starts(position)
        val end = if position + 1 < starts.size then starts(position + 1) - 1 else tokens.size - 1
        if start <= end then result += Range("yaml.document", Some("stream.document"), start, end, 1)
      }
    result.result()

  private def blockRanges(tokens: Vector[SourceToken]): Vector[Range] =
    val result = Vector.newBuilder[Range]
    val frames = mutable.ArrayBuffer.empty[BlockFrame]
    val byLine = tokens.indices.groupBy(index => tokens(index).span.start.line).toVector.sortBy(_._1)
    var previousLineEnd = 0

    def closeTop(end: Int): Unit =
      val frame = frames.remove(frames.size - 1)
      result += Range(frame.kind, frame.role, frame.start, math.max(frame.start, end), 2 + frames.size)

    byLine.foreach { (_, rawIndices) =>
      val indices = rawIndices.sorted
      val lineEnd = indices.last
      val significant = indices.filter { index =>
        val token = tokens(index)
        token.channel != TokenChannel.Trivia && token.channel != TokenChannel.Comment &&
          token.kind != "yaml.directive"
      }
      val marker = significant.headOption.exists(index =>
        tokens(index).kind == "yaml.document-start" || tokens(index).kind == "yaml.document-end"
      )
      if marker then
        while frames.nonEmpty do closeTop(previousLineEnd)
      else if significant.nonEmpty then
        val indentation = indices.headOption.filter(index => tokens(index).kind == "yaml.indentation")
          .map(index => tokens(index).lexeme.takeWhile(_ == ' ').length).getOrElse(0)
        while frames.nonEmpty && frames.last.indent > indentation do closeTop(previousLineEnd)
        val kind = lineKind(significant, tokens)
        if frames.nonEmpty && frames.last.indent == indentation && kind.exists(_ != frames.last.kind) then
          closeTop(previousLineEnd)
        kind.foreach { value =>
          val alreadyOpen = frames.lastOption.exists(frame => frame.indent == indentation && frame.kind == value)
          if !alreadyOpen then
            val role = frames.lastOption.map { parent =>
              if parent.kind == "yaml.sequence" then "sequence.item" else "mapping.value"
            }.orElse(Some("document.value"))
            frames += BlockFrame(indentation, value, role, significant.head, lineEnd)
        }
        frames.foreach(_.last = lineEnd)
      else frames.foreach(_.last = lineEnd)
      previousLineEnd = lineEnd
    }
    while frames.nonEmpty do closeTop(frames.last.last)
    result.result()

  private def lineKind(significant: IndexedSeq[Int], tokens: Vector[SourceToken]): Option[String] =
    significant.headOption.flatMap { first =>
      if tokens(first).kind == "yaml.sequence-indicator" then Some("yaml.sequence")
      else
        var flowDepth = 0
        val hasBlockColon = significant.exists { index =>
          val token = tokens(index)
          if token.kind == "yaml.flow-open" then flowDepth += 1
          val isColon = token.kind == "yaml.value-indicator" && flowDepth == 0
          if token.kind == "yaml.flow-close" then flowDepth = math.max(0, flowDepth - 1)
          isColon
        }
        Option.when(hasBlockColon)("yaml.mapping")
    }

  private def flowRanges(tokens: Vector[SourceToken]): Vector[Range] =
    val result = Vector.newBuilder[Range]
    val stack = mutable.ArrayBuffer.empty[(Char, Int)]
    tokens.indices.foreach { index =>
      tokens(index).lexeme match
        case "[" | "{" => stack += tokens(index).lexeme.head -> index
        case "]" | "}" if stack.nonEmpty =>
          val expected = if tokens(index).lexeme == "]" then '[' else '{'
          if stack.last._1 == expected then
            val (open, start) = stack.remove(stack.size - 1)
            val kind = if open == '[' then "yaml.sequence.flow" else "yaml.mapping.flow"
            result += Range(kind, Some(flowRole(stack.lastOption.map(_._1))), start, index, 100 + stack.size)
        case _ => ()
    }
    result.result()

  private def flowRole(parent: Option[Char]): String = parent match
    case Some('[') => "sequence.item"
    case Some('{') => "mapping.value"
    case _         => "node.flow"

  private def tokenRole(token: SourceToken): Option[String] = token.channel match
    case TokenChannel.Trivia  => Some("presentation.trivia")
    case TokenChannel.Comment => Some("presentation.comment")
    case TokenChannel.Error   => Some("presentation.error")
    case _ => token.kind match
      case "yaml.anchor"             => Some("node.anchor")
      case "yaml.alias"              => Some("node.alias")
      case "yaml.tag"                => Some("node.tag")
      case "yaml.sequence-indicator" => Some("sequence.indicator")
      case "yaml.explicit-key"       => Some("mapping.key-indicator")
      case "yaml.value-indicator"    => Some("mapping.value-indicator")
      case "yaml.flow-open"          => Some("flow.open")
      case "yaml.flow-close"         => Some("flow.close")
      case "yaml.flow-separator"     => Some("flow.separator")
      case "yaml.directive"          => Some("document.directive")
      case "yaml.document-start"     => Some("document.start")
      case "yaml.document-end"       => Some("document.end")
      case _                           => Some("node.content")
