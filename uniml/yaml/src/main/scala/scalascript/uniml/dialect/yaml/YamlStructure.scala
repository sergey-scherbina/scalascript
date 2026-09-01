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
              // `closeBefore` (a leading defaulted field) is passed EXPLICITLY, in
              // field order: ScalaScript v2 does not reorder an all-named enum-case
              // construction that omits a non-trailing default — it places the named
              // values positionally, shifting every field. Same result on scalac.
              closeBefore = Vector.empty,
              open = opening.map(range => FrameSpec(range.kind, range.role)),
              closeAfter = closing.map(_.kind),
              role = tokenRole(tokens(index)),
            )
          else VmInstruction.Emit(tokenRole(tokens(index)))
        VmToken(tokens(index), instruction)
      }.toVector
      Result(assigned, validateFlow(tokens))

  private def streamAndDocuments(tokens: Vector[SourceToken]): Vector[Range] =
    val stream = Range("yaml.stream", None, 0, tokens.size - 1, 0)
    val documentStarts = tokens.indices.filter(index => tokens(index).kind == "yaml.document-start").toVector
    val documents =
      if documentStarts.isEmpty then Vector(Range("yaml.document", Some("stream.document"), 0, tokens.size - 1, 1))
      else
        val meaningfulBeforeFirst = tokens.take(documentStarts.head).exists(token =>
          token.channel == TokenChannel.Syntax && token.kind != "yaml.directive"
        )
        val starts =
          if meaningfulBeforeFirst then 0 +: documentStarts
          else 0 +: documentStarts.tail
        starts.indices.toVector.flatMap { position =>
          val start = starts(position)
          val end = if position + 1 < starts.size then starts(position + 1) - 1 else tokens.size - 1
          if start <= end then Vector(Range("yaml.document", Some("stream.document"), start, end, 1))
          else Vector.empty
        }
    stream +: documents

  // The fold's accumulator, one field per former var. A named record rather than a tuple, so no
  // lambda has to destructure one (the v2 rule this file already records).
  private final case class BlockAcc(
      result: Vector[Range],
      frames: Vector[BlockFrame],
      previousLineEnd: Int,
  )

  private def blockRanges(tokens: Vector[SourceToken]): Vector[Range] =
    val byLine = tokens.indices.groupBy(index => tokens(index).span.start.line).toVector.sortBy(_._1)

    // The imperative closeTop popped `frames` and appended to `result`; the rank reads the stack
    // size AFTER the pop, and that order is load-bearing — preserved by computing on `popped`.
    def closeTop(acc: BlockAcc, end: Int): BlockAcc =
      val frame = acc.frames.last
      val popped = acc.frames.dropRight(1)
      BlockAcc(
        acc.result :+ Range(frame.kind, frame.role, frame.start, math.max(frame.start, end), 2 + popped.size),
        popped,
        acc.previousLineEnd,
      )

    // Each `while frames.nonEmpty && <cond> do closeTop(...)` becomes drainWhile with the same
    // predicate — the predicate sees the CURRENT stack each round, exactly as the loop did.
    def drainWhile(acc: BlockAcc, end: Int, keepDraining: Vector[BlockFrame] => Boolean): BlockAcc =
      if acc.frames.nonEmpty && keepDraining(acc.frames) then drainWhile(closeTop(acc, end), end, keepDraining)
      else acc

    def feedLine(acc: BlockAcc, pair: (Int, IndexedSeq[Int])): BlockAcc =
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
      val stepped =
        if marker then drainWhile(acc, acc.previousLineEnd, _ => true)
        else if significant.nonEmpty then
          val indentation = indices.headOption.filter(index => tokens(index).kind == "yaml.indentation")
            .map(index => tokens(index).lexeme.takeWhile(_ == ' ').length).getOrElse(0)
          val drained = drainWhile(acc, acc.previousLineEnd, frames => frames.last.indent > indentation)
          val kind = lineKind(significant, tokens)
          val kindClosed =
            if drained.frames.nonEmpty && drained.frames.last.indent == indentation &&
              kind.exists(_ != drained.frames.last.kind)
            then closeTop(drained, drained.previousLineEnd)
            else drained
          val opened = kind.fold(kindClosed) { value =>
            val alreadyOpen = kindClosed.frames.lastOption.exists(frame => frame.indent == indentation && frame.kind == value)
            if !alreadyOpen then
              val role = kindClosed.frames.lastOption.map { parent =>
                if parent.kind == "yaml.sequence" then "sequence.item" else "mapping.value"
              }.orElse(Some("document.value"))
              kindClosed.copy(frames = kindClosed.frames :+ BlockFrame(indentation, value, role, significant.head, lineEnd))
            else kindClosed
          }
          opened.copy(frames = opened.frames.map(_.copy(last = lineEnd)))
        else acc.copy(frames = acc.frames.map(_.copy(last = lineEnd)))
      stepped.copy(previousLineEnd = lineEnd)

    val walked = byLine.foldLeft(BlockAcc(Vector.empty, Vector.empty, 0))(feedLine)
    // The final drain closes each frame at ITS OWN `last` — the imperative loop re-read
    // `frames.last.last` every round, so the predicate form keeps that per-round read.
    def drainFinal(acc: BlockAcc): BlockAcc =
      if acc.frames.nonEmpty then drainFinal(closeTop(acc, acc.frames.last.last)) else acc
    drainFinal(walked).result

  private def lineKind(significant: IndexedSeq[Int], tokens: Vector[SourceToken]): Option[String] =
    significant.headOption.flatMap { first =>
      if tokens(first).kind == "yaml.sequence-indicator" then Some("yaml.sequence")
      else
        // The imperative original threaded flowDepth through an `exists` via side effects; here the
        // depth is an explicit recursion parameter, and the early exit is the recursion stopping.
        def blockColonAt(position: Int, flowDepth: Int): Boolean =
          if position >= significant.length then false
          else
            val token = tokens(significant(position))
            val opened = if token.kind == "yaml.flow-open" then flowDepth + 1 else flowDepth
            if token.kind == "yaml.value-indicator" && opened == 0 then true
            else
              val closed = if token.kind == "yaml.flow-close" then math.max(0, opened - 1) else opened
              blockColonAt(position + 1, closed)
        val hasBlockColon = blockColonAt(0, 0)
        // `if … then Some … else None` rather than `Option.when(…)(…)`: the curried
        // `Option.when` companion is not portable to ScalaScript v2. Same result.
        if hasBlockColon then Some("yaml.mapping") else None
    }

  // Accumulator as a named record, not a tuple: the fold's lambda must not destructure a tuple
  // parameter (v2 does not auto-destructure one — the rule this file already records for foreach).
  private final case class FlowAcc(result: Vector[Range], stack: Vector[(Char, Int)])

  private def flowRanges(tokens: Vector[SourceToken]): Vector[Range] =
    tokens.indices.foldLeft(FlowAcc(Vector.empty, Vector.empty)) { (acc, index) =>
      tokens(index).lexeme match
        case "[" | "{" => acc.copy(stack = acc.stack :+ (tokens(index).lexeme.charAt(0), index))
        // Guard is INSIDE the body (not `case … if stack.nonEmpty`): ScalaScript v2
        // does not support a guard on an alternation pattern (`A | B if …`).
        case "]" | "}" =>
          if acc.stack.nonEmpty then
            val expected = if tokens(index).lexeme == "]" then '[' else '{'
            if acc.stack.last._1 == expected then
              // `._1`/`._2` rather than a `val (open, start) = …` tuple-pattern binding:
              // ScalaScript v2 does not destructure a tuple in a val pattern.
              val open = acc.stack.last._1
              val start = acc.stack.last._2
              val popped = acc.stack.dropRight(1)
              val kind = if open == '[' then "yaml.sequence.flow" else "yaml.mapping.flow"
              FlowAcc(
                acc.result :+ Range(kind, Some(flowRole(popped.lastOption.map(_._1))), start, index, 100 + popped.size),
                popped,
              )
            else acc
          else acc
        case _ => acc
    }.result

  private final case class ValidateAcc(diagnostics: Vector[Diagnostic], stack: Vector[(Char, SourceToken)])

  private def validateFlow(tokens: Vector[SourceToken]): Vector[Diagnostic] =
    val walked = tokens.foldLeft(ValidateAcc(Vector.empty, Vector.empty)) { (acc, token) =>
      token.lexeme match
        case "[" | "{" => acc.copy(stack = acc.stack :+ (token.lexeme.charAt(0), token))
        case "]" | "}" =>
          val expected = if token.lexeme == "]" then '[' else '{'
          if acc.stack.isEmpty || acc.stack.last._1 != expected then
            acc.copy(diagnostics = acc.diagnostics :+ Diagnostic(
              "uniml.yaml.unexpected-flow-close",
              // NOT `s"… '${token.lexeme}' …"`: a string-interpolation splice immediately
              // wrapped in a bare `'…'` (no other text between the quote and the `${`) trips
              // this toolchain's parser somewhere downstream of this file in a large enough
              // merged program — `` `)` expected but `macro` found `` at the interpolation's
              // OWN position, reproducible only in combination with unrelated preceding
              // content (see the sibling occurrence in `YamlProjection.scala`'s `validateCst`,
              // same shape, same fix). Plain concatenation sidesteps whatever in the parser
              // that pattern confuses.
              "unexpected YAML flow delimiter '" + token.lexeme + "'",
              Severity.Error,
              Some(token.span),
              Some(YamlDialect.id),
            ))
          else acc.copy(stack = acc.stack.dropRight(1))
        case _ => acc
    }
    walked.diagnostics ++ walked.stack.map { pair =>
      // `pair._1`/`._2` rather than a `(delimiter, token)` destructuring lambda
      // param — v2 does not auto-destructure a tuple lambda parameter.
      val delimiter = pair._1
      val token = pair._2
      Diagnostic(
        "uniml.yaml.unclosed-flow",
        s"unclosed YAML flow delimiter '$delimiter'",
        Severity.Error,
        Some(token.span),
        Some(YamlDialect.id),
      )
    }

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
