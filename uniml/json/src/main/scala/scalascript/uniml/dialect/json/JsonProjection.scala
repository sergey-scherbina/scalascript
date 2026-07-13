package scalascript.uniml.dialect.json

import scalascript.uniml.*
import scala.collection.mutable

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
      val values = Vector.newBuilder[JsonValue]
      val warnings = Vector.newBuilder[Diagnostic]
      var failure: Option[Diagnostic] = None
      var index = 0
      while index < edges.size && failure.isEmpty do
        edges(index) match
          case UniEdge(Some("array.element"), child) =>
            projectNode(child) match
              case Right((value, childWarnings)) =>
                values += value
                warnings ++= childWarnings
              case Left(diagnostic) => failure = Some(diagnostic)
          case _ => ()
        index += 1
      failure match
        case Some(diagnostic) => Left(diagnostic)
        case None             => Right(JsonValue.ArrayValue(values.result()) -> warnings.result())
    case UniNode.Branch("json.object", edges, _, _) =>
      val members = Vector.newBuilder[JsonMember]
      val warnings = Vector.newBuilder[Diagnostic]
      var pendingKey: Option[(String, SourceToken)] = None
      var failure: Option[Diagnostic] = None
      var index = 0
      while index < edges.size && failure.isEmpty do
        edges(index) match
          case UniEdge(Some("member.key"), UniNode.Token(token)) =>
            decodeString(token.lexeme) match
              case Some(name) =>
                pendingKey = Some(name -> token)
                warnings ++= stringWarnings(token)
              case None => failure = Some(invalidCst(token.span, "object key is not a valid JSON string token"))
          case UniEdge(Some("member.value"), child) =>
            pendingKey match
              case None => failure = Some(invalidCst(nodeSpan(child), "object value has no preceding key"))
              case Some((name, keyToken)) =>
                projectNode(child) match
                  case Left(diagnostic) => failure = Some(diagnostic)
                  case Right((value, childWarnings)) =>
                    val span = SourceSpan(keyToken.span.source, keyToken.span.start, nodeSpan(child).end)
                    members += JsonMember(name, keyToken.lexeme, value, span)
                    warnings ++= childWarnings
                    pendingKey = None
          case _ => ()
        index += 1
      failure match
        case Some(diagnostic) => Left(diagnostic)
        case None => pendingKey match
          case Some((_, token)) => Left(invalidCst(token.span, "object key has no value"))
          case None =>
            val result = members.result()
            warnings ++= duplicateDiagnostics(result, Severity.Warning)
            Right(JsonValue.ObjectValue(result) -> warnings.result())
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
      val result = StringBuilder()
      var index = 1
      while index < lexeme.length - 1 do
        val char = lexeme.charAt(index)
        if char != '\\' then
          result.append(char)
          index += 1
        else
          if index + 1 >= lexeme.length - 1 then return None
          lexeme.charAt(index + 1) match
            case '"' => result.append('"'); index += 2
            case '\\' => result.append('\\'); index += 2
            case '/' => result.append('/'); index += 2
            case 'b' => result.append('\b'); index += 2
            case 'f' => result.append('\f'); index += 2
            case 'n' => result.append('\n'); index += 2
            case 'r' => result.append('\r'); index += 2
            case 't' => result.append('\t'); index += 2
            case 'u' =>
              if index + 6 > lexeme.length then return None
              val hex = lexeme.substring(index + 2, index + 6)
              parseHex(hex) match
                case Some(value) => result.append(value.toChar); index += 6
                case None        => return None
            case _ => return None
      Some(result.result())

  private def parseHex(value: String): Option[Int] =
    var result = 0
    var index = 0
    while index < value.length do
      val char = value.charAt(index)
      val digit =
        if char >= '0' && char <= '9' then char - '0'
        else if char >= 'a' && char <= 'f' then char - 'a' + 10
        else if char >= 'A' && char <= 'F' then char - 'A' + 10
        else -1
      if digit < 0 then return None
      result = result * 16 + digit
      index += 1
    Some(result)

  private def stringWarnings(token: SourceToken): Vector[Diagnostic] =
    decodeString(token.lexeme) match
      case None => Vector.empty
      case Some(value) =>
        val warnings = Vector.newBuilder[Diagnostic]
        var index = 0
        while index < value.length do
          val char = value.charAt(index)
          if Unicode.isHighSurrogate(char) then
            if index + 1 < value.length && Unicode.isLowSurrogate(value.charAt(index + 1)) then index += 2
            else
              warnings += Diagnostic(
                "uniml.json.unpaired-surrogate",
                "JSON string contains an unpaired surrogate escape",
                Severity.Warning,
                Some(token.span),
                Some(JsonDialect.id),
              )
              index += 1
          else if Unicode.isLowSurrogate(char) then
            warnings += Diagnostic(
              "uniml.json.unpaired-surrogate",
              "JSON string contains an unpaired surrogate escape",
              Severity.Warning,
              Some(token.span),
              Some(JsonDialect.id),
            )
            index += 1
          else index += 1
        warnings.result()

  private def duplicateDiagnostics(members: Vector[JsonMember], severity: Severity): Vector[Diagnostic] =
    val seen = mutable.HashSet.empty[String]
    val diagnostics = Vector.newBuilder[Diagnostic]
    members.foreach { member =>
      if !seen.add(member.name) then
        diagnostics += Diagnostic(
          code = "uniml.json.duplicate-key",
          message = s"duplicate JSON object key '${member.name}'",
          severity = severity,
          span = Some(member.span),
          dialect = Some(JsonDialect.id),
        )
    }
    diagnostics.result()

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
