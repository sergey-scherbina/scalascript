package scalascript.uniml.dialect.json

import scalascript.uniml.*

object JsonProjection:
  def project(result: ParseResult): JsonProjectionResult =
    if result.diagnostics.exists(d => d.severity == Severity.Error || d.severity == Severity.Fatal) then
      JsonProjectionResult(None, result.diagnostics)
    else
      val significant = result.roots.filterNot(isTriviaNode)
      if significant.size != 1 then
        val diagnostic = Diagnostic(
          code = "uniml.json.projection-invalid-cst",
          message = s"expected one significant JSON root, found ${significant.size}",
          severity = Severity.Error,
          span = significant.headOption.map(nodeSpan),
          dialect = Some(JsonDialect.id),
        )
        JsonProjectionResult(None, result.diagnostics :+ diagnostic)
      else projectNode(significant.head) match
        case Left(diagnostic) => JsonProjectionResult(None, result.diagnostics :+ diagnostic)
        case Right((value, warnings)) => JsonProjectionResult(Some(value), result.diagnostics ++ warnings)

  def objectMap(
      value: JsonValue.ObjectValue,
      policy: DuplicateKeyPolicy,
  ): Either[Vector[Diagnostic], Map[String, JsonValue]] =
    val duplicates = duplicateDiagnostics(value.members, Severity.Error)
    policy match
      case DuplicateKeyPolicy.Reject if duplicates.nonEmpty => Left(duplicates)
      case DuplicateKeyPolicy.FirstWins =>
        Right(value.members.foldLeft(Map.empty[String, JsonValue]) { (result, member) =>
          if result.contains(member.name) then result else result.updated(member.name, member.value)
        })
      case DuplicateKeyPolicy.LastWins | DuplicateKeyPolicy.Reject =>
        Right(value.members.foldLeft(Map.empty[String, JsonValue]) { (result, member) =>
          result.updated(member.name, member.value)
        })

  private def projectNode(node: UniNode): Either[Diagnostic, (JsonValue, Vector[Diagnostic])] = node match
    case UniNode.Token(token) => projectToken(token).map(value => value -> stringWarnings(token))
    case UniNode.Branch("json.array", edges, _, _) =>
      // Tail recursion rather than foldLeft, so the FIRST failure stops the walk exactly where the
      // imperative `while … && failure.isEmpty` did — a fold would keep visiting elements whose
      // projections are then discarded, identical observably but not in work done on the error path.
      def walkArray(index: Int, values: Vector[JsonValue], warnings: Vector[Diagnostic]): Either[Diagnostic, (JsonValue, Vector[Diagnostic])] =
        if index >= edges.size then Right(JsonValue.ArrayValue(values) -> warnings)
        else edges(index) match
          case UniEdge(Some("array.element"), child) =>
            projectNode(child) match
              case Right((value, childWarnings)) => walkArray(index + 1, values :+ value, warnings ++ childWarnings)
              case Left(diagnostic)              => Left(diagnostic)
          case _ => walkArray(index + 1, values, warnings)
      walkArray(0, Vector.empty, Vector.empty)
    case UniNode.Branch("json.object", edges, _, _) =>
      def walkObject(
          index: Int,
          members: Vector[JsonMember],
          warnings: Vector[Diagnostic],
          pendingKey: Option[(String, SourceToken)],
      ): Either[Diagnostic, (JsonValue, Vector[Diagnostic])] =
        if index >= edges.size then pendingKey match
          case Some((_, token)) => Left(invalidCst(token.span, "object key has no value"))
          case None =>
            Right(JsonValue.ObjectValue(members) -> (warnings ++ duplicateDiagnostics(members, Severity.Warning)))
        else edges(index) match
          case UniEdge(Some("member.key"), UniNode.Token(token)) =>
            decodeString(token.lexeme) match
              case Some(name) => walkObject(index + 1, members, warnings ++ stringWarnings(token), Some(name -> token))
              case None       => Left(invalidCst(token.span, "object key is not a valid JSON string token"))
          case UniEdge(Some("member.value"), child) =>
            pendingKey match
              case None => Left(invalidCst(nodeSpan(child), "object value has no preceding key"))
              case Some((name, keyToken)) =>
                projectNode(child) match
                  case Left(diagnostic) => Left(diagnostic)
                  case Right((value, childWarnings)) =>
                    val span = SourceSpan(keyToken.span.source, keyToken.span.start, nodeSpan(child).end)
                    walkObject(index + 1, members :+ JsonMember(name, keyToken.lexeme, value, span), warnings ++ childWarnings, None)
          case _ => walkObject(index + 1, members, warnings, pendingKey)
      walkObject(0, Vector.empty, Vector.empty, None)
    case UniNode.Branch(kind, _, span, _) => Left(invalidCst(span, s"unsupported JSON branch '$kind'"))

  private def projectToken(token: SourceToken): Either[Diagnostic, JsonValue] = token.kind match
    case "json.string" => decodeString(token.lexeme) match
      case Some(value) => Right(JsonValue.StringValue(value, token.lexeme))
      case None        => Left(invalidCst(token.span, "invalid JSON string token"))
    case "json.number" => Right(JsonValue.NumberValue(token.lexeme))
    case "json.true"   => Right(JsonValue.BooleanValue(true))
    case "json.false"  => Right(JsonValue.BooleanValue(false))
    case "json.null"   => Right(JsonValue.NullValue)
    case other          => Left(invalidCst(token.span, s"unsupported JSON token '$other'"))

  private def decodeString(lexeme: String): Option[String] =
    if lexeme.length < 2 || lexeme.head != '"' || lexeme.last != '"' then None
    else
      // Variable stride (1 for a plain code unit, 2 for a short escape, 6 for \uXXXX) is why this
      // is recursion over an index and not a fold over characters. Every `return None` of the
      // original is a plain `None` leaf here.
      def decode(index: Int, result: Vector[String]): Option[Vector[String]] =
        if index >= lexeme.length - 1 then Some(result)
        else
          val char = lexeme.charAt(index)
          if char != '\\' then
            // substring slice, not `char.toString` (v2 has no Char box → decimal code)
            decode(index + 1, result :+ lexeme.substring(index, index + 1))
          else if index + 1 >= lexeme.length - 1 then None
          else lexeme.charAt(index + 1) match
            case '"' => decode(index + 2, result :+ "\"")
            case '\\' => decode(index + 2, result :+ "\\")
            case '/' => decode(index + 2, result :+ "/")
            case 'b' => decode(index + 2, result :+ "\b")
            case 'f' => decode(index + 2, result :+ "\f")
            case 'n' => decode(index + 2, result :+ "\n")
            case 'r' => decode(index + 2, result :+ "\r")
            case 't' => decode(index + 2, result :+ "\t")
            case 'u' =>
              if index + 6 > lexeme.length then None
              else parseHex(lexeme.substring(index + 2, index + 6)) match
                case Some(value) => decode(index + 6, result :+ value.toChar.toString)
                case None        => None
            case _ => None
      decode(1, Vector.empty).map(_.mkString)

  private def parseHex(value: String): Option[Int] =
    value.foldLeft(Option(0)) { (acc, char) =>
      val digit =
        if char >= '0' && char <= '9' then char - '0'
        else if char >= 'a' && char <= 'f' then char - 'a' + 10
        else if char >= 'A' && char <= 'F' then char - 'A' + 10
        else -1
      acc.filter(_ => digit >= 0).map(_ * 16 + digit)
    }

  private def stringWarnings(token: SourceToken): Vector[Diagnostic] =
    decodeString(token.lexeme) match
      case None => Vector.empty
      case Some(value) =>
        def unpaired: Diagnostic = Diagnostic(
          "uniml.json.unpaired-surrogate",
          "JSON string contains an unpaired surrogate escape",
          Severity.Warning,
          Some(token.span),
          Some(JsonDialect.id),
        )
        // Stride 2 over a well-formed pair, 1 otherwise — recursion over an index, as decodeString.
        def scanSurrogates(index: Int, warnings: Vector[Diagnostic]): Vector[Diagnostic] =
          if index >= value.length then warnings
          else
            val char = value.charAt(index)
            if Unicode.isHighSurrogate(char) then
              if index + 1 < value.length && Unicode.isLowSurrogate(value.charAt(index + 1)) then
                scanSurrogates(index + 2, warnings)
              else scanSurrogates(index + 1, warnings :+ unpaired)
            else if Unicode.isLowSurrogate(char) then scanSurrogates(index + 1, warnings :+ unpaired)
            else scanSurrogates(index + 1, warnings)
        scanSurrogates(0, Vector.empty)

  private def duplicateDiagnostics(members: Vector[JsonMember], severity: Severity): Vector[Diagnostic] =
    // A textbook fold: (seen, diagnostics) is the accumulator, `combine` is one member at a time.
    members.foldLeft((Set.empty[String], Vector.empty[Diagnostic])) { case ((seen, diagnostics), member) =>
      if seen.contains(member.name) then
        (seen, diagnostics :+ Diagnostic(
          code = "uniml.json.duplicate-key",
          message = s"duplicate JSON object key '${member.name}'",
          severity = severity,
          span = Some(member.span),
          dialect = Some(JsonDialect.id),
        ))
      else (seen + member.name, diagnostics)
    }._2

  private def isTriviaNode(node: UniNode): Boolean = node match
    case UniNode.Token(token) => token.kind == "json.whitespace" || token.kind == "json.bom"
    case _                    => false

  private def nodeSpan(node: UniNode): SourceSpan = node match
    case UniNode.Token(token)            => token.span
    case UniNode.Branch(_, _, span, _)   => span

  private def invalidCst(span: SourceSpan, message: String): Diagnostic =
    Diagnostic(
      code = "uniml.json.projection-invalid-cst",
      message = message,
      severity = Severity.Error,
      span = Some(span),
      dialect = Some(JsonDialect.id),
    )
