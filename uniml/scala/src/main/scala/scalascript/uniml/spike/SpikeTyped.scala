package scalascript.uniml.spike

import scalascript.uniml.*
import SpikeAst.*

/** CST → typed AST. The ScalaScript analogue of `MarkdownProjection`.
  *
  * Deliberately SEPARATE from `SpikeProject`, which renders ssc0 source text for
  * the v2 front. That one is a serialiser aimed at an existing consumer; this is
  * the AST a compiler would hold. They read the same CST, which is the point of
  * having one lossless tree underneath.
  *
  * Nothing is dropped: a shape this does not model becomes `Unsupported(kind)`,
  * so coverage is a number rather than an impression. */
object SpikeTyped:

  private def span(n: UniNode): SourceSpan = n match
    case UniNode.Token(t)          => t.span
    case b: UniNode.Branch         => b.span

  private def lex(n: UniNode): String = n match
    case UniNode.Token(t) => t.lexeme
    case _                => ""

  private def kind(n: UniNode): String = n match
    case b: UniNode.Branch => b.kind
    case UniNode.Token(_)  => "token"

  /** Children minus the tokens that are not part of the parse — the same filter
    * the text projection uses, for the same reason. */
  private def kids(n: UniNode): Vector[(Option[String], UniNode)] = n match
    case b: UniNode.Branch =>
      b.edges.collect {
        case UniEdge(role, c)
            if !role.contains("trivia") && !role.contains("unparsed") &&
              !(c.isInstanceOf[UniNode.Token] && c.asInstanceOf[UniNode.Token].value.kind == "spike.ws") =>
          (role, c)
      }
    case _ => Vector.empty

  private def byRole(n: UniNode, role: String): Option[UniNode] =
    kids(n).collectFirst { case (Some(r), c) if r == role => c }

  private def allByRole(n: UniNode, role: String): Vector[UniNode] =
    kids(n).collect { case (Some(r), c) if r == role => c }

  def module(root: UniNode): Module =
    Module(kids(root).map((_, c) => decl(c)), span(root))

  private def decl(n: UniNode): Decl = kind(n) match
    case "spike.def"       => defDecl(n)
    case "spike.casecls"   =>
      CaseClass(
        byRole(n, "cc.name").map(lex).getOrElse("_"),
        allByRole(n, "cc.field").map(lex),
        byRole(n, "cc.parent").map(lex),
        span(n),
      )
    case "spike.enum"      => EnumDecl(byRole(n, "enum.name").map(lex).getOrElse("_"), allByRole(n, "enum.case").map(lex), span(n))
    case "spike.given"     => Given(byRole(n, "given.name").map(lex), span(n))
    case "spike.extension" => Extension(kids(n).collect { case (_, c) if kind(c) == "spike.def" => defDecl(c) }, span(n))
    case "spike.exprStmt"  => TopExpr(byRole(n, "stmt.expr").map(expr).getOrElse(UnitLit(span(n))), span(n))
    case "spike.object"    => ObjectDecl(byRole(n, "obj.name").map(lex).getOrElse("_"), allByRole(n, "obj.member").map(decl), span(n))
    case "spike.val"       => TopExpr(valOf(n, "val"), span(n))
    case "spike.var"       => TopExpr(valOf(n, "var"), span(n))
    case other             => UnsupportedDecl(other, span(n))

  private def valOf(n: UniNode, kw: String): Expr =
    ValDef(byRole(n, s"$kw.name").map(lex).getOrElse("_"),
           byRole(n, s"$kw.rhs").map(expr).getOrElse(Unsupported("missing.rhs", span(n))), span(n))

  private def defDecl(n: UniNode): Def =
    Def(
      byRole(n, "def.name").map(lex).getOrElse("_"),
      allByRole(n, "def.param").map(p => Param(lex(p), None, using_ = false, span(p))),
      byRole(n, "def.body").map(expr).getOrElse(UnitLit(span(n))),
      span(n),
    )

  def expr(n: UniNode): Expr = n match
    case UniNode.Token(t) =>
      t.kind match
        case "spike.int"   => IntLit(SpikeNum.decode(t.lexeme), t.span)
        case "spike.float" => FloatLit(SpikeNum.decode(t.lexeme), t.span)
        case "spike.str"   => StrLit(SpikeStr.decode(t.lexeme), t.span)
        case "spike.id"    => Ident(t.lexeme, t.span)
        case "spike.uid"   => Ident(t.lexeme, t.span)
        case other         => Unsupported(other, t.span)
    case b: UniNode.Branch =>
      b.kind match
        case "spike.infix" =>
          Infix(
            byRole(b, "bin.op").map(c => SpikeOp.meaning(lex(c))).getOrElse("+"),
            byRole(b, "bin.left").map(expr).getOrElse(Unsupported("missing.left", span(b))),
            byRole(b, "bin.right").map(expr).getOrElse(Unsupported("missing.right", span(b))),
            span(b),
          )
        case "spike.pre" =>
          Prefix(byRole(b, "pre.op").map(lex).getOrElse("-"),
                 byRole(b, "pre.sub").map(expr).getOrElse(Unsupported("missing.operand", span(b))), span(b))
        case "spike.call" =>
          Apply(byRole(b, "call.fn").map(expr).getOrElse(Unsupported("missing.fn", span(b))),
                allByRole(b, "call.arg").map(expr), span(b))
        case "spike.sel" =>
          Select(byRole(b, "sel.obj").map(expr).getOrElse(Unsupported("missing.recv", span(b))),
                 byRole(b, "sel.field").map(lex).getOrElse("_"), span(b))
        case "spike.if" =>
          If(byRole(b, "if.cond").map(expr).getOrElse(Unsupported("missing.cond", span(b))),
             byRole(b, "if.then").map(expr).getOrElse(UnitLit(span(b))),
             byRole(b, "if.else").map(expr), span(b))
        case "spike.block"    => Block(kids(b).map((_, c) => expr(c)), span(b))
        case "spike.exprStmt" => byRole(b, "stmt.expr").map(expr).getOrElse(UnitLit(span(b)))
        case "spike.val"      => valOf(b, "val")
        case "spike.var"      => valOf(b, "var")
        case "spike.while" =>
          While(byRole(b, "while.cond").map(expr).getOrElse(Unsupported("missing.cond", span(b))),
                byRole(b, "while.body").map(expr).getOrElse(UnitLit(span(b))), span(b))
        case "spike.tuple"    => Tuple(kids(b).collect { case (_, c) if kind(c) != "token" => expr(c) }, span(b))
        case "spike.paren"    => kids(b).collectFirst { case (_, c) if kind(c) != "token" => expr(c) }.getOrElse(UnitLit(span(b)))
        case "spike.match" =>
          Match(byRole(b, "match.scrut").map(expr).getOrElse(Unsupported("missing.scrutinee", span(b))),
                kids(b).collect { case (_, c) if kind(c) == "spike.arm" => arm(c) }, span(b))
        case "spike.lambda" =>
          Lambda(allByRole(b, "lam.param").map(lex),
                 byRole(b, "lam.body").map(expr).getOrElse(UnitLit(span(b))), span(b))
        case "spike.assign" =>
          Assign(byRole(b, "assign.name").map(lex).getOrElse("_"),
                 byRole(b, "assign.rhs").map(expr).getOrElse(Unsupported("missing.rhs", span(b))), span(b))
        case other => Unsupported(other, span(b))

  private def arm(n: UniNode): Arm =
    Arm(
      byRole(n, "case.pat").map(lex).getOrElse("_"),
      byRole(n, "case.guard").map(expr),
      byRole(n, "case.body").map(expr).getOrElse(UnitLit(span(n))),
      span(n),
    )
