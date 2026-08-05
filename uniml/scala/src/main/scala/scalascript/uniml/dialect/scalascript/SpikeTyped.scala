package scalascript.uniml.dialect.scalascript

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

  /** The source text of a node, branch or token.
    *
    * `lex` returns `""` for a branch, which is right where a role is known to hold one token and
    * catastrophic where it is not: `Arm.pattern` used `lex` on `case.pat`, whose child is a
    * `spike.cpat` BRANCH for any structured pattern, so 4,579 patterns in the corpus projected as
    * the empty string. A silent `""` is the string-typed twin of the `if` bug — a well-formed
    * value standing in for a subtree nobody read. Types are captured as token runs
    * (`ScalaSpike.captureType`), so concatenating is exactly how the reference reads them. */
  private def text(n: UniNode): String = n match
    case UniNode.Token(t)  => t.lexeme
    case b: UniNode.Branch => b.edges.map(e => text(e.child)).mkString

  private def typeRef(n: UniNode): TypeRef = TypeRef(text(n), span(n))

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
        // A field's type (`cc.fieldType`) and default (`cc.dflt`) FOLLOW their `cc.field`, so the
        // grouping is positional — the same walk the reference does (`ScalaSpike.scala:2448`).
        slots(n, "cc.field", Set("cc.fieldType"), "cc.dflt"),
        byRole(n, "cc.parent").map(lex),
        allByRole(n, "cc.method").collect { case m if kind(m) == "spike.def" => defDecl(m) },
        span(n),
      )
    case "spike.enum"      =>
      // `enum.case` is a role the dialect NEVER EMITS: a case is a `spike.enumcase` child BRANCH
      // (`ScalaSpike.scala:883`), which is how the reference reads them (:2334). Asking for the
      // role returned nothing and every EnumDecl in the corpus carried an empty case list.
      EnumDecl(
        byRole(n, "enum.name").map(lex).getOrElse("_"),
        kids(n).collect { case (_, c) if kind(c) == "spike.enumcase" => enumCase(c) },
        span(n),
      )
    case "spike.given"     =>
      Given(byRole(n, "given.name").map(lex), byRole(n, "given.type").map(typeRef),
            byRole(n, "given.body").map(expr), span(n))
    case "spike.extension" => Extension(kids(n).collect { case (_, c) if kind(c) == "spike.def" => defDecl(c) }, span(n))
    case "spike.exprStmt"  => TopExpr(byRole(n, "stmt.expr").map(expr).getOrElse(UnitLit(span(n))), span(n))
    case "spike.object"    => ObjectDecl(byRole(n, "obj.name").map(lex).getOrElse("_"), allByRole(n, "obj.member").map(decl), span(n))
    case "spike.val"       => TopExpr(valOf(n, "val"), span(n))
    case "spike.var"       => TopExpr(valOf(n, "var"), span(n))
    case other             => UnsupportedDecl(other, span(n))

  private def valOf(n: UniNode, kw: String): Expr =
    ValDef(byRole(n, s"$kw.name").map(lex).getOrElse("_"),
           byRole(n, s"$kw.rhs").map(expr).getOrElse(Unsupported("missing.rhs", span(n))), span(n))

  /** Group a run of sibling roles into `Param`s.
    *
    * A parameter's type and default FOLLOW its name among the same siblings rather than nesting
    * under it, so the grouping is positional: everything between one name and the next belongs to
    * that name. This is the walk the reference performs (`ScalaSpike.scala:2419` for defs, :2448
    * for case classes). Collecting each role independently and zipping would pair the wrong type
    * with the wrong parameter as soon as one parameter omits a default and another has one. */
  private def slots(n: UniNode, nameRole: String, typeRoles: Set[String], dfltRole: String): Vector[Param] =
    val out = Vector.newBuilder[Param]
    var cur: Option[(String, SourceSpan)] = None
    var tpe: Option[TypeRef] = None
    var dflt: Option[Expr] = None
    var isUsing = false
    def flush(): Unit = cur.foreach((nm, sp) => out += Param(nm, tpe, dflt, isUsing, sp))
    kids(n).foreach { (role, c) =>
      role match
        case Some(r) if r == nameRole =>
          flush(); cur = Some((lex(c), span(c))); tpe = None; dflt = None; isUsing = false
        case Some(r) if typeRoles.contains(r) =>
          tpe = Some(typeRef(c)); if r.endsWith("usingtype") then isUsing = true
        case Some(r) if r == dfltRole => dflt = Some(expr(c))
        case _                        => ()
    }
    flush()
    out.result()

  private def enumCase(n: UniNode): EnumCase =
    EnumCase(byRole(n, "ec.name").map(lex).getOrElse("_"),
             slots(n, "ec.field", Set("ec.fieldType"), "ec.dflt"), span(n))

  private def defDecl(n: UniNode): Def =
    Def(
      byRole(n, "def.name").map(lex).getOrElse("_"),
      slots(n, "def.param", Set("def.paramType", "def.usingtype"), "def.dflt"),
      byRole(n, "def.retType").map(typeRef),
      byRole(n, "def.body").map(expr).getOrElse(UnitLit(span(n))),
      span(n),
    )

  /** CST → `Pattern`, mirroring the reference's `patProj` (`ScalaSpike.scala:2630`) role for role.
    *
    * The token cases matter as much as the branches: a pattern's own role (`pat.lit`, `pat.var`)
    * is OVERWRITTEN by the slot it is placed in (`withRole("cpat.arg")`), so the edge role cannot
    * be used to tell a literal from a binder. Dispatch is on the token KIND, exactly as the
    * reference does — and `true`/`false` lex as identifiers, so they need naming before the
    * general identifier case or they become binders that match everything. */
  private def pattern(n: UniNode): Pattern = n match
    case UniNode.Token(t) =>
      t.kind match
        case "spike.int" | "spike.float"                          => PatLit(t.lexeme, t.span)
        case "spike.str"                                          => PatLit(SpikeStr.decode(t.lexeme), t.span)
        case "spike.id" if t.lexeme == "_"                        => PatWild(t.span)
        case "spike.id" if t.lexeme == "true" || t.lexeme == "false" => PatLit(t.lexeme, t.span)
        case "spike.id"                                           => PatVar(t.lexeme, t.span)
        case other                                                => PatUnsupported(other, t.span)
    case b: UniNode.Branch =>
      b.kind match
        case "spike.cpat" =>
          PatCtor(byRole(b, "cpat.name").map(lex).getOrElse("_"), allByRole(b, "cpat.arg").map(pattern), span(b))
        case "spike.conspat" =>
          val as = allByRole(b, "conspat.arg").map(pattern)
          PatCons(as.headOption.getOrElse(PatWild(span(b))), as.lift(1).getOrElse(PatWild(span(b))), span(b))
        case "spike.tuppat" => PatTuple(allByRole(b, "tup.arg").map(pattern), span(b))
        case "spike.apat"   => PatAlt(allByRole(b, "apat.alt").map(pattern), span(b))
        case "spike.bpat" =>
          PatBind(byRole(b, "bpat.alias").map(lex).getOrElse("_"),
                  byRole(b, "bpat.inner").map(pattern).getOrElse(PatWild(span(b))), span(b))
        case "spike.tpat" =>
          PatTyped(byRole(b, "tpat.pat").map(pattern).getOrElse(PatWild(span(b))),
                   byRole(b, "tpat.type").map(typeRef), span(b))
        case other => PatUnsupported(other, span(b))

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
          // `if.then`/`if.else` are the KEYWORD tokens; the branches are `if.thenE`/`if.elseE`.
          // Reading the keyword roles projected `then` and `else` themselves as the branches, so
          // every `if` in the corpus modelled both arms as Unsupported("spike.kw") — 2,120 of the
          // 4,851 recorded gaps, 44%, and the If node looked present while its children were the
          // syntax that separates them.
          If(byRole(b, "if.cond").map(expr).getOrElse(Unsupported("missing.cond", span(b))),
             byRole(b, "if.thenE").map(expr).getOrElse(UnitLit(span(b))),
             byRole(b, "if.elseE").map(expr), span(b))
        case "spike.block"    => Block(kids(b).map((_, c) => expr(c)), span(b))
        case "spike.exprStmt" => byRole(b, "stmt.expr").map(expr).getOrElse(UnitLit(span(b)))
        case "spike.val"      => valOf(b, "val")
        case "spike.var"      => valOf(b, "var")
        case "spike.while" =>
          While(byRole(b, "while.cond").map(expr).getOrElse(Unsupported("missing.cond", span(b))),
                byRole(b, "while.body").map(expr).getOrElse(UnitLit(span(b))), span(b))
        // Elements carry the role `group.elem` (`ScalaSpike.scala:1920`), and selecting them by
        // "not a token" instead kept a parenthesised CALL and discarded a parenthesised
        // IDENTIFIER: `(x)` projected as `UnitLit` and `(1, 2)` as an EMPTY tuple. Both are
        // well-formed nodes, so nothing said a word. Select on the role the dialect emits, as the
        // reference does (:2511-2512).
        // `()` is framed as a `spike.tuple` with NO elements (`ScalaSpike.scala:1920` sends both
        // the tuple case and the empty case to that kind), so unit arrives here rather than at
        // `spike.paren`. A zero-element tuple is not a thing the language has; unit is.
        case "spike.tuple"    => allByRole(b, "group.elem").map(expr) match
          case Vector() => UnitLit(span(b))
          case elems    => Tuple(elems, span(b))
        case "spike.paren"    => allByRole(b, "group.elem").map(expr) match
          case Vector(one) => one
          case _           => UnitLit(span(b))
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
      byRole(n, "case.pat").map(pattern).getOrElse(PatWild(span(n))),
      byRole(n, "case.guard").map(expr),
      byRole(n, "case.body").map(expr).getOrElse(UnitLit(span(n))),
      span(n),
    )
