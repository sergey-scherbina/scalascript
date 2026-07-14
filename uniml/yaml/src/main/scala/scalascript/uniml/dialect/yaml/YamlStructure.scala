package scalascript.uniml.dialect.yaml

import scalascript.uniml.*

private[yaml] object YamlStructure:
  private[yaml] final case class Result(
      tokens: Vector[VmToken],
      diagnostics: Vector[Diagnostic],
  )

  private final case class Range(
      kind: String,
      role: Option[String],
      start: Int,
      end: Int,
      rank: Int,
  )

  // immutable block frame: `last` advances by replacing the frame in the stack
  private final case class BlockFrame(
      indent: Int,
      kind: String,
      role: Option[String],
      start: Int,
      last: Int,
  )

  def assign(tokens: Vector[SourceToken]): Result =
    if tokens.isEmpty then Result(Vector.empty, Vector.empty)
    else
      val ranges = streamAndDocuments(tokens) ++ blockRanges(tokens) ++ flowRanges(tokens)
      val assigned = tokens.indices.map { index =>
        val opening = ranges.filter(_.start == index).sortBy(range => (range.rank, -range.end))
        val closing = ranges.filter(_.end == index).sortBy(range => (-range.rank, -range.start))
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
      Result(assigned, validateFlow(tokens))

  private def streamAndDocuments(tokens: Vector[SourceToken]): Vector[Range] =
    var result: Vector[Range] = Vector.empty
    result = result :+ Range("yaml.stream", None, 0, tokens.size - 1, 0)
    val documentStarts = tokens.indices.filter(index => tokens(index).kind == "yaml.document-start").toVector
    if documentStarts.isEmpty then result = result :+ Range("yaml.document", Some("stream.document"), 0, tokens.size - 1, 1)
    else
      val meaningfulBeforeFirst = tokens.take(documentStarts.head).exists(token =>
        token.channel == TokenChannel.Syntax && token.kind != "yaml.directive"
      )
      val starts =
        // `Vector(0) ++ …` rather than `0 +: …`: the `+:` prepend operator is not
        // portable to ScalaScript v2. Behaviour-identical.
        if meaningfulBeforeFirst then Vector(0) ++ documentStarts
        else Vector(0) ++ documentStarts.tail
      starts.indices.foreach { position =>
        val start = starts(position)
        val end = if position + 1 < starts.size then starts(position + 1) - 1 else tokens.size - 1
        if start <= end then result = result :+ Range("yaml.document", Some("stream.document"), start, end, 1)
      }
    result

  private def blockRanges(tokens: Vector[SourceToken]): Vector[Range] =
    var result: Vector[Range] = Vector.empty
    var frames: Vector[BlockFrame] = Vector.empty
    val byLine = tokens.indices.groupBy(index => tokens(index).span.start.line).toVector.sortBy(_._1)
    var previousLineEnd = 0

    def closeTop(end: Int): Unit =
      val frame = frames.last
      frames = frames.dropRight(1)
      result = result :+ Range(frame.kind, frame.role, frame.start, math.max(frame.start, end), 2 + frames.size)

    byLine.foreach { pair =>
      // `pair._2` rather than a `(_, rawIndices)` destructuring lambda param:
      // ScalaScript v2 does not auto-destructure a tuple lambda parameter.
      val rawIndices = pair._2
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
            frames = frames :+ BlockFrame(indentation, value, role, significant.head, lineEnd)
        }
        frames = frames.map(_.copy(last = lineEnd))
      else frames = frames.map(_.copy(last = lineEnd))
      previousLineEnd = lineEnd
    }
    while frames.nonEmpty do closeTop(frames.last.last)
    result

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
        // `if … then Some … else None` rather than `Option.when(…)(…)`: the curried
        // `Option.when` companion is not portable to ScalaScript v2. Same result.
        if hasBlockColon then Some("yaml.mapping") else None
    }

  private def flowRanges(tokens: Vector[SourceToken]): Vector[Range] =
    var result: Vector[Range] = Vector.empty
    var stack: Vector[(Char, Int)] = Vector.empty
    tokens.indices.foreach { index =>
      tokens(index).lexeme match
        case "[" | "{" => stack = stack :+ (tokens(index).lexeme.head, index)
        case "]" | "}" if stack.nonEmpty =>
          val expected = if tokens(index).lexeme == "]" then '[' else '{'
          if stack.last._1 == expected then
            val (open, start) = stack.last
            stack = stack.dropRight(1)
            val kind = if open == '[' then "yaml.sequence.flow" else "yaml.mapping.flow"
            result = result :+ Range(kind, Some(flowRole(stack.lastOption.map(_._1))), start, index, 100 + stack.size)
        case _ => ()
    }
    result

  private def validateFlow(tokens: Vector[SourceToken]): Vector[Diagnostic] =
    var diagnostics: Vector[Diagnostic] = Vector.empty
    var stack: Vector[(Char, SourceToken)] = Vector.empty
    tokens.foreach { token =>
      token.lexeme match
        case "[" | "{" => stack = stack :+ (token.lexeme.head, token)
        case "]" | "}" =>
          val expected = if token.lexeme == "]" then '[' else '{'
          if stack.isEmpty || stack.last._1 != expected then
            diagnostics = diagnostics :+ Diagnostic(
              "uniml.yaml.unexpected-flow-close",
              s"unexpected YAML flow delimiter '${token.lexeme}'",
              Severity.Error,
              Some(token.span),
              Some(YamlDialect.id),
            )
          else stack = stack.dropRight(1)
        case _ => ()
    }
    stack.foreach { pair =>
      // `pair._1`/`._2` rather than a `(delimiter, token)` destructuring lambda
      // param — v2 does not auto-destructure a tuple lambda parameter.
      val delimiter = pair._1
      val token = pair._2
      diagnostics = diagnostics :+ Diagnostic(
        "uniml.yaml.unclosed-flow",
        s"unclosed YAML flow delimiter '$delimiter'",
        Severity.Error,
        Some(token.span),
        Some(YamlDialect.id),
      )
    }
    diagnostics

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
