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

  /** Spans read AS TEXT during a `traced` projection — the census's missing half.
    *
    * A projected node carries the span of the CST node it came from, so anything that becomes a
    * node is provably consumed. A token read for its LEXEME becomes a `String` field instead, and
    * leaves no span behind: `def.name`, `sel.field`, `cc.field` are all consumed and all invisible.
    * The drop census therefore had to restrict itself to BRANCH children, and that blind spot is
    * exactly where `group.elem` hid — `(x)` projecting as `UnitLit` was found by reading the
    * dialect, not by measuring, and the next one like it might not be.
    *
    * Every text read in this file goes through `lex` or `text`, so recording in those two places
    * is complete by construction rather than by a list someone maintains. A `ThreadLocal` keeps
    * concurrent test classes from seeing each other's reads; untraced projection pays one null
    * check per read and allocates nothing. */
  private val trace = new ThreadLocal[scala.collection.mutable.Set[SourceSpan]]

  private def touch(n: UniNode): Unit =
    val t = trace.get()
    if t != null then t += span(n)

  /** Project, and report every CST span the projection actually consumed — as a node OR as text.
    *
    * This exists so the coverage census can ask "did this reach the AST" of a TOKEN and get a
    * true answer instead of an inference. It is also the honest basis for a source map later. */
  def traced(root: UniNode): (Module, Set[SourceSpan]) =
    val seen = scala.collection.mutable.Set.empty[SourceSpan]
    trace.set(seen)
    try (module(root), seen.toSet) finally trace.remove()

  private def span(n: UniNode): SourceSpan = n match
    case UniNode.Token(t)          => t.span
    case b: UniNode.Branch         => b.span

  private def lex(n: UniNode): String = n match
    case UniNode.Token(t) => touch(n); t.lexeme
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
    case UniNode.Token(t)  => touch(n); t.lexeme
    case b: UniNode.Branch => touch(n); b.edges.map(e => text(e.child)).mkString

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

  /** A role read as a FLAG — its presence is the whole payload (`for.yield`, `eff.multi`,
    * `imp.wildcard`).
    *
    * It goes through `lex` rather than `isDefined` so the read is RECORDED. Testing presence alone
    * consumes the token without leaving a trace, and the drop census — which learns what was
    * consumed from the projection itself — then reports 22 perfectly-handled `yield`s and 13
    * `multi`s as dropped. Found exactly that way. */
  private def flag(n: UniNode, role: String): Boolean = byRole(n, role).map(lex).isDefined

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
    case "spike.extension" =>
      Extension(slots(n, "ext.recv", Set("ext.recvType"), "ext.dflt").headOption,
                kids(n).collect { case (_, c) if kind(c) == "spike.def" => defDecl(c) }, span(n))
    case "spike.exprStmt"  => TopExpr(byRole(n, "stmt.expr").map(expr).getOrElse(UnitLit(span(n))), span(n))
    case "spike.object"    =>
      ObjectDecl(byRole(n, "obj.name").map(lex).getOrElse("_"), allByRole(n, "td.parent").map(lex),
                 allByRole(n, "obj.member").map(decl), flag(n, "obj.case"), span(n))
    case "spike.val"       => TopExpr(valOf(n, "val"), span(n))
    case "spike.var"       => TopExpr(valOf(n, "var"), span(n))
    // Statements that `.ssc` also allows at TOP LEVEL. `expr` already models each of them; only
    // the declaration slot was missing, so they read as unmodelled constructs while the identical
    // node inside a block projected fine — 65 of them, and a pure routing gap.
    case "spike.while" | "spike.assign" | "spike.idxassign" | "spike.compoundassign" | "spike.tuppatval" =>
      TopExpr(expr(n), span(n))
    // `given n: T with { defs }` — MEMBERS, not a right-hand side, which is why it is not `Given`.
    case "spike.givenobj"  =>
      GivenObject(byRole(n, "given.name").map(lex), byRole(n, "given.type").map(typeRef),
                  allByRole(n, "obj.member").map(decl), span(n))
    // `given n = body` with no ascribed type — a plain val in given's clothing.
    case "spike.givenval"  =>
      Given(byRole(n, "given.name").map(lex), None, byRole(n, "given.body").map(expr), span(n))
    // Reachable only since traits kept their bodies — an abstract `val id: String`, no `=`.
    case "spike.valdecl"   => AbstractVal(byRole(n, "val.name").map(lex).getOrElse("_"), span(n))
    case "spike.effectdecl" =>
      EffectDecl(byRole(n, "eff.name").map(lex).getOrElse("_"), flag(n, "eff.multi"),
                 allByRole(n, "eff.op").map(decl), span(n))
    // `import a.b.c`, a link-import and an anonymous `given` all share the `spike.sealed` kind.
    // The first two now carry their path (`imp.seg` / `imp.tok`); the third genuinely carries
    // nothing, and telling them apart is what having the roles buys.
    case "spike.sealed" =>
      // FOUR constructs share this kind now, and the roles are what tell them apart: a trait or
      // class (`td.kw`), an import (`imp.seg`/`imp.tok`), and an anonymous `given`, which alone
      // genuinely carries nothing.
      val segs = allByRole(n, "imp.seg").map(lex) ++ allByRole(n, "imp.tok").map(lex).filter(_.nonEmpty)
      if byRole(n, "td.kw").isDefined then
        TraitDecl(byRole(n, "td.kw").map(lex).getOrElse("trait"),
                  byRole(n, "td.name").map(lex).getOrElse("_"),
                  allByRole(n, "td.parent").map(lex),
                  allByRole(n, "obj.member").map(decl), span(n))
      else if segs.isEmpty then NoOpDecl(span(n))
      else
        ImportDecl(allByRole(n, "imp.seg").map(lex).mkString("."),
                   allByRole(n, "imp.sel").map(lex),
                   flag(n, "imp.wildcard"), span(n))
    case other             => UnsupportedDecl(other, span(n))

  private def valOf(n: UniNode, kw: String): Expr =
    ValDef(byRole(n, s"$kw.name").map(lex).getOrElse("_"),
           byRole(n, s"$kw.rhs").map(expr).getOrElse(Unsupported("missing.rhs", span(n))),
           isVar = kw == "var", span(n))

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
    var byName = false
    def flush(): Unit = cur.foreach((nm, sp) => out += Param(nm, tpe, dflt, isUsing, sp, byName))
    kids(n).foreach { (role, c) =>
      role match
        case Some(r) if r == nameRole =>
          flush(); cur = Some((lex(c), span(c))); tpe = None; dflt = None; isUsing = false; byName = false
        case Some(r) if typeRoles.contains(r) =>
          tpe = Some(typeRef(c)); if r.endsWith("usingtype") then isUsing = true
        // A `using` parameter's TYPE ARGUMENTS, appended to the type it belongs to as they
        // arrive. `Show` and `Show[A]` are the same head and different types, and resolution
        // matches on the whole of it.
        case Some("def.typearg") =>
          tpe = tpe.map { t =>
            val txt = if t.text.endsWith("]") then t.text.dropRight(1) + "," + lex(c) + "]"
                      else t.text + "[" + lex(c) + "]"
            TypeRef(txt, t.span)
          }
        // The arrow the grammar now keeps. It arrives BEFORE the type, which is why it is read as
        // its own role rather than inferred from `tpe`.
        case Some(r) if r.endsWith("byname") => byName = true
        // VARARGS. Carried in the type TEXT rather than as a field on `Param`, because the text is
        // already what every consumer reads and `def.typearg` directly above rewrites it the same
        // way. `T*` is `List[T]` at Tier 0; the marker exists so the CALL SITE can collect its
        // tail, and `v3/src/Parser.scala` reconstructs the identical text from its own tokens.
        //
        // AFTER the type and after its arguments, so `tpe` is already the whole of `List[Int]`.
        case Some(r) if r.endsWith("vararg") =>
          tpe = tpe.map(t => TypeRef(t.text + "*", t.span))
        case Some(r) if r == dfltRole => dflt = Some(expr(c))
        case _                        => ()
    }
    flush()
    out.result()

  /** One `x <- xs if p`. Two or more binders mean a TUPLE binder — the dialect records them flat
    * and lets the count carry that, so the projection does the same rather than inventing a shape
    * the CST does not have. */
  private def forGen(n: UniNode): ForGen =
    ForGen(allByRole(n, "gen.binder").map(lex),
           byRole(n, "gen.gen").map(expr).getOrElse(Unsupported("missing.source", span(n))),
           byRole(n, "gen.guard").map(expr), span(n))

  private def enumCase(n: UniNode): EnumCase =
    EnumCase(byRole(n, "ec.name").map(lex).getOrElse("_"),
             slots(n, "ec.field", Set("ec.fieldType"), "ec.dflt"), span(n))

  private def defDecl(n: UniNode): Def =
    Def(
      // A DOTTED name is `def.name` plus one `def.nameseg` per segment — `def Source.distributed`
      // (`ScalaSpike.scala:644`). Reading only `def.name` kept `Source` and lost the method, for
      // 40 defs in the corpus. The reference has the same blind spot; it does not need the name.
      (byRole(n, "def.name").map(lex).getOrElse("_") +: allByRole(n, "def.nameseg").map(lex)).mkString("."),
      slots(n, "def.param", Set("def.paramType", "def.usingtype"), "def.dflt"),
      byRole(n, "def.retType").map(typeRef),
      // NO `def.eq`, no body: an ABSTRACT signature — a trait method or an effect op. The
      // placeholder used to be `UnitLit`, which made it indistinguishable from `def f(): Unit = ()`
      // — a real implementation. A front reading this tree has to know the difference: one
      // dispatches to a subclass, the other runs. `NotImplemented` is the node that already means
      // "declared, no body", so no new shape is introduced.
      byRole(n, "def.body").map(expr).getOrElse(NotImplemented(span(n))),
      span(n),
      allByRole(n, "def.tparam").map(lex),
      // PAIRED BY ORDER, which is why `kids` is walked instead of two `allByRole` calls: a bound
      // belongs to the name that precedes it, and two flat lists cannot say which — `[A: Monoid,
      // B: Pretty]` would come out as both bounds on `A`.
      kids(n).foldLeft((Vector.empty[(String, String)], "")) { case ((acc, cur), (role, k)) =>
        role match
          case Some("def.tparam") => (acc, lex(k))
          case Some("def.tbound") => (if cur.isEmpty then acc else acc :+ (cur, lex(k)), cur)
          case _                  => (acc, cur)
      }._1,
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
        // Delegated to `expr`, which already has an arm per literal kind — including the char,
        // which is a `spike.int` whose lexeme starts with a quote. Writing the decode a second
        // time here is what put `case '\n'` on character 92.
        case "spike.int" | "spike.float" | "spike.str"            => PatLit(expr(n), t.span)
        case "spike.id" if t.lexeme == "_"                        => PatWild(t.span)
        case "spike.id" if t.lexeme == "true" || t.lexeme == "false" => PatLit(expr(n), t.span)
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
        // A char lexes as `spike.int` with the QUOTES kept in the lexeme, so `'x'` and `120` are
        // distinguishable here and nowhere later. Reading only the decoded value collapsed them.
        case "spike.int" if t.lexeme.startsWith("'") => CharLit(SpikeNum.decode(t.lexeme), t.span)
        case "spike.int"   => IntLit(SpikeNum.decode(t.lexeme), t.span)
        case "spike.float" => FloatLit(SpikeNum.decode(t.lexeme), t.span)
        case "spike.str"   => StrLit(SpikeStr.decode(t.lexeme), t.span)
        case "spike.id"    => Ident(t.lexeme, t.span)
        case "spike.uid"   => Ident(t.lexeme, t.span)
        case "spike.qname" => QuotedName(t.lexeme, t.span)
        // `???` lexes as an operator; the dialect gives it its own leaf role because it lowers to
        // a prim. Named here so it stops reading as an unmodelled operator.
        case "spike.op" if t.lexeme == "???" => NotImplemented(t.span)
        case other         => Unsupported(other, t.span)
    case b: UniNode.Branch =>
      b.kind match
        case "spike.infix" =>
          Infix(
            // The WRITTEN operator, not `SpikeOp.meaning`. That rewrite (`+:` -> `::`,
            // `:::` -> `++`) is true for v2, where every Seq is a Cons-list, and its docstring
            // says so. It is not true in general: `x +: xs` on anything that is not a List
            // disagrees with `x :: xs`. A typed AST says what was WRITTEN and lets the consumer
            // decide the meaning — the CST keeps the spelling precisely so this is possible.
            byRole(b, "bin.op").map(lex).getOrElse("+"),
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
        // TWO kinds for one construct: `spike.lam` (`ScalaSpike.scala:1443`) and `spike.lambda`
        // (:1476, :1680, :1688) carry the same roles, and handling only the second left the first
        // reported as an unmodelled construct it is not.
        case "spike.lambda" | "spike.lam" =>
          Lambda(allByRole(b, "lam.param").map(lex),
                 byRole(b, "lam.body").map(expr).getOrElse(UnitLit(span(b))), span(b))
        case "spike.narg" =>
          NamedArg(byRole(b, "narg.name").map(lex).getOrElse("_"),
                   byRole(b, "narg.val").map(expr).getOrElse(Unsupported("missing.narg.val", span(b))), span(b))
        case "spike.listlit"  => ListLit(allByRole(b, "list.el").map(expr), span(b))
        case "spike.blockapp" =>
          BlockApply(byRole(b, "blkapp.fn").map(expr).getOrElse(Unsupported("missing.fn", span(b))),
                     byRole(b, "blkapp.arg").map(expr).getOrElse(UnitLit(span(b))), span(b))
        case "spike.interp" =>
          Interp(byRole(b, "interp.prefix").map(lex).getOrElse("s"),
                 byRole(b, "interp.raw").map(t => SpikeStr.decode(lex(t))).getOrElse(""), span(b))
        case "spike.throw" =>
          Throw(byRole(b, "throw.expr").map(expr).getOrElse(Unsupported("missing.throw", span(b))), span(b))
        case "spike.try" =>
          Try(byRole(b, "try.body").map(expr).getOrElse(UnitLit(span(b))),
              byRole(b, "try.catch").map(expr), byRole(b, "try.finally").map(expr), span(b))
        case "spike.for" =>
          For(allByRole(b, "for.gen").map(forGen), byRole(b, "for.body").map(expr).getOrElse(UnitLit(span(b))),
              flag(b, "for.yield"), span(b))
        case "spike.rangeop" =>
          RangeOp(byRole(b, "range.op").map(lex).getOrElse("to"),
                  byRole(b, "range.lhs").map(expr).getOrElse(Unsupported("missing.lhs", span(b))),
                  byRole(b, "range.rhs").map(expr).getOrElse(Unsupported("missing.rhs", span(b))), span(b))
        // The WHOLE type application, joined without separators — `Show[Int]`, not `Show`. The
        // reference is explicit that keeping only the head never matches an instance.
        case "spike.summon"  => Summon(allByRole(b, "summon.tok").map(lex).mkString, span(b))
        case "spike.pfblock" => PartialFn(kids(b).collect { case (_, c) if kind(c) == "spike.arm" => arm(c) }, span(b))
        case "spike.quote"   => Quote(byRole(b, "quote.body").map(expr).getOrElse(UnitLit(span(b))), span(b))
        case "spike.splice"  => Splice(byRole(b, "splice.body").map(expr).getOrElse(UnitLit(span(b))), span(b))
        case "spike.def"     => LocalDef(defDecl(b), span(b))
        case "spike.idxassign" =>
          IndexAssign(byRole(b, "idxassign.lhs").map(expr).getOrElse(Unsupported("missing.lhs", span(b))),
                      byRole(b, "idxassign.rhs").map(expr).getOrElse(Unsupported("missing.rhs", span(b))), span(b))
        case "spike.tuppatval" =>
          TupleVal(allByRole(b, "tup.name").map(lex),
                   byRole(b, "val.rhs").map(expr).getOrElse(Unsupported("missing.rhs", span(b))), span(b))
        case "spike.direct" =>
          Marker("direct", byRole(b, "direct.block").map(expr),
                 kids(b).collect { case (r, c) if !r.contains("direct.block") => lex(c) }.filter(_.nonEmpty), span(b))
        case "spike.focusmarker" | "spike.prism" | "spike.directmarker" =>
          val cs = kids(b).map((_, c) => c)
          Marker(b.kind.stripPrefix("spike."), cs.headOption.map(expr), cs.drop(1).map(lex).filter(_.nonEmpty), span(b))
        case "spike.assign" =>
          // A DOTTED target — `Cfg.count = 7` — is head plus `assign.nameseg` per segment, the same
          // shape `defDecl` reads for `def Source.distributed`. Reading only the head assigned to
          // `Cfg`, which is a different variable and a silently wrong program.
          Assign((byRole(b, "assign.name").map(lex).getOrElse("_") +:
                    allByRole(b, "assign.nameseg").map(lex)).mkString("."),
                 byRole(b, "assign.rhs").map(expr).getOrElse(Unsupported("missing.rhs", span(b))), span(b))
        // `x += 1`. The base operator is the lexeme minus its `=`, and keeping the WRITTEN form
        // rather than desugaring to `x = x + 1` is the projection's job: the CST says `+=`.
        case "spike.compoundassign" =>
          CompoundAssign(byRole(b, "ca.name").map(lex).getOrElse("_"),
                         byRole(b, "ca.op").map(lex).getOrElse("+="),
                         byRole(b, "ca.rhs").map(expr).getOrElse(Unsupported("missing.rhs", span(b))), span(b))
        case other => Unsupported(other, span(b))

  private def arm(n: UniNode): Arm =
    Arm(
      byRole(n, "case.pat").map(pattern).getOrElse(PatWild(span(n))),
      byRole(n, "case.guard").map(expr),
      byRole(n, "case.body").map(expr).getOrElse(UnitLit(span(n))),
      span(n),
    )
