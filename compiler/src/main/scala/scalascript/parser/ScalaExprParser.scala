package scalascript.parser

import scalascript.ast.*
import fastparse.*
import fastparse.ScalaWhitespace.*

/** Parser for Scala expressions inside code blocks using fastparse */
class ScalaExprParser(source: String):

  def parseStatements(): List[Statement] =
    parse(source, statements(_)) match
      case Parsed.Success(stmts, _) => stmts
      case Parsed.Failure(_, index, extra) =>
        // Return empty on parse failure for now
        // println(s"Parse error at $index: ${extra.trace().longMsg}")
        Nil

  // ============================================
  // Whitespace and Comments
  // ============================================

  private def comment[$: P]: P[Unit] =
    P("//" ~ CharsWhile(_ != '\n', 0)) | P("/*" ~ (!"*/" ~ AnyChar).rep ~ "*/")

  private def ws[$: P]: P[Unit] =
    P(CharsWhileIn(" \t\r\n", 0) ~ comment.rep ~ CharsWhileIn(" \t\r\n", 0))

  // ============================================
  // Lexical
  // ============================================

  private def letter[$: P]: P[Unit] = P(CharIn("a-zA-Z_$"))
  private def digit[$: P]: P[Unit] = P(CharIn("0-9"))
  private def hexDigit[$: P]: P[Unit] = P(CharIn("0-9a-fA-F"))

  private def keywords = Set(
    "abstract", "case", "catch", "class", "def", "do", "else", "enum",
    "extends", "false", "final", "finally", "for", "given", "if",
    "implicit", "import", "lazy", "match", "new", "null", "object",
    "override", "package", "private", "protected", "return", "sealed",
    "super", "then", "this", "throw", "trait", "true", "try", "type",
    "val", "var", "while", "with", "yield", "export"
  )

  private def ident[$: P]: P[String] =
    P((letter ~ (letter | digit).rep).!).filter(s => !keywords.contains(s))

  private def operator[$: P]: P[String] =
    P(CharsWhileIn("+-*/%&|^<>=!?:~", 1).!)

  // ============================================
  // Literals
  // ============================================

  private def intLiteral[$: P]: P[LiteralValue] =
    P(("0x" ~ hexDigit.rep(1)).! | digit.rep(1).!).map(s =>
      if s.startsWith("0x") then LiteralValue.IntLit(java.lang.Long.parseLong(s.drop(2), 16))
      else LiteralValue.IntLit(s.toLong)
    )

  private def longLiteral[$: P]: P[LiteralValue] =
    P((digit.rep(1) ~ StringIn("l", "L")).!).map(s => LiteralValue.IntLit(s.dropRight(1).toLong))

  private def doubleLiteral[$: P]: P[LiteralValue] =
    P((digit.rep(1) ~ "." ~ digit.rep(1) ~ (StringIn("e", "E") ~ CharPred(c => c == '+' || c == '-').? ~ digit.rep(1)).?).!)
      .map(s => LiteralValue.DoubleLit(s.toDouble))

  private def stringLiteral[$: P]: P[LiteralValue] =
    P("\"\"\"" ~ (!"\"\"\"" ~ AnyChar).rep.! ~ "\"\"\"" |
      "\"" ~ (CharPred(c => c != '"' && c != '\n' && c != '\\') | ("\\" ~ AnyChar)).rep.! ~ "\"")
      .map(LiteralValue.StringLit(_))

  private def charLiteral[$: P]: P[LiteralValue] =
    P("'" ~ (CharPred(_ != '\'') | ("\\" ~ AnyChar)).! ~ "'")
      .map(s => LiteralValue.CharLit(if s.startsWith("\\") then parseEscape(s) else s.head))

  private def parseEscape(s: String): Char = s match
    case "\\n" => '\n'
    case "\\r" => '\r'
    case "\\t" => '\t'
    case "\\\\" => '\\'
    case "\\'" => '\''
    case "\\\"" => '"'
    case _ => s.last

  private def boolLiteral[$: P]: P[LiteralValue] =
    P("true").map(_ => LiteralValue.BoolLit(true)) |
    P("false").map(_ => LiteralValue.BoolLit(false))

  private def nullLiteral[$: P]: P[LiteralValue] =
    P("null").map(_ => LiteralValue.NullLit)

  private def literal[$: P]: P[Expr] =
    P(doubleLiteral | longLiteral | intLiteral | stringLiteral | charLiteral | boolLiteral | nullLiteral)
      .map(Expr.Literal(_))

  // ============================================
  // Types
  // ============================================

  private def simpleType[$: P]: P[Type] =
    P(ident ~ ("[" ~ tpe.rep(1, sep = ",") ~ "]").?).map {
      case (name, None) => Type.Named(name, Nil)
      case (name, Some(args)) => Type.Named(name, args.toList)
    }

  private def tupleType[$: P]: P[Type] =
    P("(" ~ tpe.rep(2, sep = ",") ~ ")").map(ts => Type.Tuple(ts.toList))

  private def functionType[$: P]: P[Type] =
    P((tupleType | simpleType | ("(" ~ ")").map(_ => Type.Unit)) ~ "=>" ~ tpe).map {
      case (params, result) => params match
        case Type.Tuple(ts, _) => Type.Function(ts, result)
        case t if t == Type.Unit => Type.Function(Nil, result)
        case t => Type.Function(scala.List(t), result)
    }

  private def unionType[$: P]: P[Type] =
    P(simpleType ~ ("|" ~ simpleType).rep(1)).map {
      case (first, rest) => Type.Union((first :: rest.toList))
    }

  private def tpe[$: P]: P[Type] =
    P(functionType | unionType | tupleType | simpleType)

  private def typeAnnotation[$: P]: P[Type] =
    P(":" ~ tpe)

  // ============================================
  // Patterns
  // ============================================

  private def wildcardPattern[$: P]: P[Pattern] =
    P("_").map(_ => Pattern.Wildcard())

  private def literalPattern[$: P]: P[Pattern] =
    P(doubleLiteral | longLiteral | intLiteral | stringLiteral | charLiteral | boolLiteral | nullLiteral)
      .map(Pattern.Literal(_))

  private def bindingPattern[$: P]: P[Pattern] =
    P(ident ~ ("@" ~ pattern).?).map {
      case (name, pat) => Pattern.Binding(name, pat)
    }

  private def constructorPattern[$: P]: P[Pattern] =
    P(ident ~ "(" ~ pattern.rep(sep = ",") ~ ")").map {
      case (name, args) => Pattern.Constructor(name, args.toList)
    }

  private def tuplePattern[$: P]: P[Pattern] =
    P("(" ~ pattern.rep(2, sep = ",") ~ ")").map(ps => Pattern.Tuple(ps.toList))

  private def typedPattern[$: P]: P[Pattern] =
    P(ident ~ ":" ~ tpe).map { case (name, t) => Pattern.Typed(Pattern.Binding(name, None), t) }

  private def pattern[$: P]: P[Pattern] =
    P(wildcardPattern | literalPattern | typedPattern | constructorPattern | tuplePattern | bindingPattern)

  // ============================================
  // Expressions
  // ============================================

  private def identExpr[$: P]: P[Expr] =
    P(ident).map(Expr.Ident(_))

  private def parenExpr[$: P]: P[Expr] =
    P("(" ~ ")").map(_ => Expr.Literal(LiteralValue.UnitLit)) |
    P("(" ~ expr ~ ")") |
    P("(" ~ expr.rep(2, sep = ",") ~ ")").map(es => Expr.Tuple(es.toList))

  private def newExpr[$: P]: P[Expr] =
    P("new" ~ simpleType ~ ("(" ~ expr.rep(sep = ",") ~ ")").?).map {
      case (t, args) => Expr.New(t, args.map(_.toList).getOrElse(Nil))
    }

  private def blockExpr[$: P]: P[Expr] =
    P("{" ~ statementOrExpr.rep ~ "}").map { items =>
      val (stmts, lastExpr) = if items.isEmpty then
        (Nil, Expr.Literal(LiteralValue.UnitLit))
      else items.last match
        case Right(e) => (items.init.collect { case Left(s) => s }.toList, e)
        case Left(s) => (items.collect { case Left(s) => s }.toList, Expr.Literal(LiteralValue.UnitLit))
      Expr.Block(stmts, lastExpr)
    }

  private def statementOrExpr[$: P]: P[Either[Statement, Expr]] =
    P(valDef.map(Left(_)) | varDef.map(Left(_)) | defDef.map(Left(_)) |
      typeDef.map(Left(_)) | classDef.map(Left(_)) | enumDef.map(Left(_)) |
      objectDef.map(Left(_)) | traitDef.map(Left(_)) |
      expr.map(Right(_)))

  private def ifExpr[$: P]: P[Expr] =
    P("if" ~ expr ~ "then" ~ expr ~ ("else" ~ expr).?).map {
      case (cond, thenp, elsep) => Expr.If(cond, thenp, elsep)
    }

  private def matchExpr[$: P]: P[Expr] =
    P(simpleExpr ~ "match" ~ "{".? ~ caseClause.rep(1) ~ "}".?).map {
      case (scrutinee, cases) => Expr.Match(scrutinee, cases.toList)
    }

  private def caseClause[$: P]: P[CaseClause] =
    P("case" ~ pattern ~ ("if" ~ expr).? ~ "=>" ~ expr).map {
      case (pat, guard, body) => CaseClause(pat, guard, body)
    }

  private def forExpr[$: P]: P[Expr] =
    P("for" ~ enumerator.rep(1, sep = ";".? ~ &(ident ~ "<-")) ~ "yield" ~ expr).map {
      case (enums, body) =>
        // Transform to nested flatMap/map calls
        enums.foldRight(body) { case ((name, iter), acc) =>
          Expr.Apply(Expr.Select(iter, "map"), List(Expr.Lambda(List(Param(name, None, None)), acc)))
        }
    }

  private def enumerator[$: P]: P[(String, Expr)] =
    P(ident ~ "<-" ~ expr)

  private def whileExpr[$: P]: P[Expr] =
    P("while" ~ expr ~ "do" ~ expr).map {
      case (cond, body) => Expr.Block(Nil, body) // Simplified
    }

  private def lambdaExpr[$: P]: P[Expr] =
    P(lambdaParams ~ "=>" ~ expr).map {
      case (params, body) => Expr.Lambda(params, body)
    }

  private def lambdaParams[$: P]: P[List[Param]] =
    P(ident.map(n => List(Param(n, None, None)))) |
    P("(" ~ param.rep(sep = ",") ~ ")").map(_.toList)

  private def param[$: P]: P[Param] =
    P(ident ~ typeAnnotation.? ~ ("=" ~ expr).?).map {
      case (name, tpe, default) => Param(name, tpe, default)
    }

  private def simpleExpr[$: P]: P[Expr] =
    P(literal | newExpr | blockExpr | parenExpr | identExpr)

  private def selectOrApply[$: P]: P[Expr] =
    P(simpleExpr ~ (
      ("." ~ ident).map(Left(_)) |
      ("[" ~ tpe.rep(1, sep = ",") ~ "]").map(ts => Right(Left(ts.toList))) |
      ("(" ~ expr.rep(sep = ",") ~ ")").map(as => Right(Right(as.toList)))
    ).rep).map { case (base, suffixes) =>
      suffixes.foldLeft(base) {
        case (e, Left(name)) => Expr.Select(e, name)
        case (e, Right(Left(targs))) => Expr.TypeApply(e, targs)
        case (e, Right(Right(args))) => Expr.Apply(e, args)
      }
    }

  private def prefixExpr[$: P]: P[Expr] =
    P(StringIn("!", "~", "+", "-").!.? ~ selectOrApply).map {
      case (Some(op), e) => Expr.Prefix(op, e)
      case (None, e) => e
    }

  private def infixExpr[$: P]: P[Expr] =
    P(prefixExpr ~ (operator ~ prefixExpr).rep).map { case (first, rest) =>
      rest.foldLeft(first) { case (lhs, (op, rhs)) => Expr.Infix(lhs, op, rhs) }
    }

  private def ascriptionExpr[$: P]: P[Expr] =
    P(infixExpr ~ (":" ~ tpe).?).map {
      case (e, Some(t)) => Expr.Ascription(e, t)
      case (e, None) => e
    }

  private def expr[$: P]: P[Expr] =
    P(ifExpr | matchExpr | forExpr | whileExpr | lambdaExpr | ascriptionExpr)

  // ============================================
  // Definitions
  // ============================================

  private def valDef[$: P]: P[Statement] =
    P("val" ~ ident ~ typeAnnotation.? ~ "=" ~ expr).map {
      case (name, tpe, rhs) => Statement.ValDef(name, tpe, rhs)
    }

  private def varDef[$: P]: P[Statement] =
    P("var" ~ ident ~ typeAnnotation.? ~ "=" ~ expr).map {
      case (name, tpe, rhs) => Statement.VarDef(name, tpe, rhs)
    }

  private def typeParams[$: P]: P[List[TypeParam]] =
    P("[" ~ typeParam.rep(1, sep = ",") ~ "]").map(_.toList)

  private def typeParam[$: P]: P[TypeParam] =
    P(StringIn("+", "-").!.? ~ ident ~ typeBounds).map {
      case (variance, name, bounds) =>
        val v = variance match
          case Some("+") => Variance.Covariant
          case Some("-") => Variance.Contravariant
          case _ => Variance.Invariant
        TypeParam(name, v, bounds)
    }

  private def typeBounds[$: P]: P[TypeBounds] =
    P((">:" ~ tpe).? ~ ("<:" ~ tpe).?).map {
      case (lower, upper) => TypeBounds(lower, upper)
    }

  private def paramClause[$: P]: P[List[Param]] =
    P("(" ~ param.rep(sep = ",") ~ ")").map(_.toList)

  private def defDef[$: P]: P[Statement] =
    P("def" ~ ident ~ typeParams.? ~ paramClause.rep ~ typeAnnotation.? ~ "=" ~ expr).map {
      case (name, tparams, params, retTpe, body) =>
        Statement.DefDef(name, tparams.getOrElse(Nil), params.toList, retTpe, body)
    }

  private def typeDef[$: P]: P[Statement] =
    P("type" ~ ident ~ typeParams.? ~ "=" ~ tpe).map {
      case (name, tparams, rhs) => Statement.TypeAlias(name, tparams.getOrElse(Nil), rhs)
    }

  private def classParams[$: P]: P[List[Param]] =
    P("(" ~ classParam.rep(sep = ",") ~ ")").map(_.toList)

  private def classParam[$: P]: P[Param] =
    P(("val" | "var").!.? ~ ident ~ typeAnnotation ~ ("=" ~ expr).?).map {
      case (_, name, tpe, default) => Param(name, Some(tpe), default)
    }

  private def parents[$: P]: P[List[Type]] =
    P("extends" ~ simpleType ~ ("with" ~ simpleType).rep).map {
      case (first, rest) => first :: rest.toList
    }

  private def classBody[$: P]: P[List[Statement]] =
    P(":" ~ statement.rep).map(_.toList) |
    P("{" ~ statement.rep ~ "}").map(_.toList)

  private def classDef[$: P]: P[Statement] =
    P("case".!.? ~ "class" ~ ident ~ typeParams.? ~ classParams.? ~ parents.? ~ classBody.?).map {
      case (isCase, name, tparams, params, parents, body) =>
        Statement.ClassDef(name, tparams.getOrElse(Nil), params.getOrElse(Nil),
          parents.getOrElse(Nil), body.getOrElse(Nil), isCase.isDefined)
    }

  private def objectDef[$: P]: P[Statement] =
    P("case".!.? ~ "object" ~ ident ~ parents.? ~ classBody.?).map {
      case (isCase, name, parents, body) =>
        Statement.ObjectDef(name, parents.getOrElse(Nil), body.getOrElse(Nil), isCase.isDefined)
    }

  private def traitDef[$: P]: P[Statement] =
    P("trait" ~ ident ~ typeParams.? ~ parents.? ~ classBody.?).map {
      case (name, tparams, parents, body) =>
        Statement.TraitDef(name, tparams.getOrElse(Nil), parents.getOrElse(Nil), body.getOrElse(Nil))
    }

  private def enumCase[$: P]: P[EnumCase] =
    P("case" ~ ident ~ classParams.?).map {
      case (name, params) => EnumCase(name, params.getOrElse(Nil))
    }

  private def enumDef[$: P]: P[Statement] =
    P("enum" ~ ident ~ typeParams.? ~ parents.? ~ ":" ~ enumCase.rep(1)).map {
      case (name, tparams, parents, cases) =>
        Statement.EnumDef(name, tparams.getOrElse(Nil), parents.getOrElse(Nil), cases.toList)
    }

  private def statement[$: P]: P[Statement] =
    P(valDef | varDef | defDef | typeDef | classDef | enumDef | objectDef | traitDef |
      expr.map(Statement.ExprStmt(_)))

  private def statements[$: P]: P[List[Statement]] =
    P(ws ~ statement.rep(sep = ws) ~ ws ~ End).map(_.toList)
