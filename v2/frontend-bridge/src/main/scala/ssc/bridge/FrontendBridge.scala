package ssc.bridge

import scala.meta.*
import scala.meta.dialects.Scala3
import ssc.{Term as CT, Const, Def as CDef, Arm, Program}

/** Converts v1 scalameta AST (output of v1 Parser+Typer+Linker) to v2 Core IR.
 *
 *  Entry point: FrontendBridge.convertSource(src: String): Program
 *  Or:          FrontendBridge.convertTrees(trees: List[Tree]): Program
 *
 *  De Bruijn scope: List[String] newest-first.
 *    Local(0) = scope.head  (most recently bound)
 *    Local(i) = scope(i)
 *  Names NOT in scope → Global(name).
 *
 *  Special scope prefixes (same as ssc1c):
 *    "@name"  = cell ref for `var name` (cell.new)
 *    "@@name" = long-cell ref for `var name = <int-lit>` (lcell.new, avoids IntV boxing) */
object FrontendBridge:

  /** Case class / enum field map: className → ordered field names.
   *  Built during first pass over stats; used when resolving field selects. */
  private val fieldRegistry = collection.mutable.HashMap[String, Vector[String]]()

  /** Lookup field index for a class member field, if known. */
  private def fieldIndex(name: String): Option[Int] =
    fieldRegistry.values.collectFirst {
      case fields if fields.contains(name) => fields.indexOf(name)
    }

  /** Lookup field index for a specific class's field. */
  private def fieldIndexOf(className: String, name: String): Option[Int] =
    fieldRegistry.get(className).flatMap { fields =>
      val i = fields.indexOf(name); if i >= 0 then Some(i) else None
    }

  /** Register a case class definition and its fields. */
  private def registerCaseClass(name: String, params: List[Term.Param]): Unit =
    fieldRegistry(name) = params.map(_.name.value).toVector

  /** Extension method name registry: method name → receiver param name. */
  private val extensionMethods = collection.mutable.HashSet[String]()

  /** First pass: scan all stats and collect case class / enum / extension definitions. */
  private def registerTypes(stats: List[Stat]): Unit = stats.foreach {
    case d: Defn.Class if d.mods.exists(_.is[Mod.Case]) =>
      val params = d.ctor.paramClauses.flatMap(_.values).toList
      registerCaseClass(d.name.value, params)
    case d: Defn.Trait => ()
    case d: Defn.Object => ()
    case Defn.Enum(_, name, _, _, templ) =>
      templ.stats.foreach {
        case ec: Defn.EnumCase =>
          val params = ec.ctor.paramClauses.flatMap(_.values).toList
          registerCaseClass(ec.name.value, params)
        case _ => ()
      }
    case eg: Defn.ExtensionGroup =>
      extMethods(eg).foreach { m => extensionMethods += m.name.value }
    case _ => ()
  }

  private def extMethods(eg: Defn.ExtensionGroup): List[Defn.Def] =
    eg.body match
      case m: Defn.Def           => List(m)
      case Term.Block(stats)     => stats.collect { case m: Defn.Def => m }
      case _                     => Nil

  private def extReceiverName(eg: Defn.ExtensionGroup): String =
    eg.paramClauseGroup
      .flatMap(_.paramClauses.headOption)
      .flatMap(_.values.headOption)
      .map(_.name.value)
      .getOrElse("_self_")

  // ── Entry points ─────────────────────────────────────────────────────────────

  /** Parse a source string and convert to Core IR Program.
   *  Supports script mode: bare expressions at the top level are wrapped in a block. */
  def convertSource(src: String): Program = convertStats(parseStats(src))

  /** Convert a list of scalameta Trees (parsed body of a .ssc module) to Program. */
  def convertTrees(trees: List[Tree]): Program =
    val stats: List[Stat] = trees.flatMap {
      case Source(ss)      => ss
      case b: Term.Block   => b.stats
      case s: Stat         => List(s)
      case t: Term         => List(t)
      case _               => Nil
    }
    convertStats(stats)

  /** Parse raw ssc source to stats (script mode: expressions allowed at top level). */
  def parseStats(src: String): List[Stat] =
    given Dialect = Scala3
    src.parse[Source] match
      case Parsed.Success(tree)             => tree.stats.toList
      case _: Parsed.Error                  =>
        s"{\n$src\n}".parse[Term] match
          case Parsed.Success(Term.Block(ss)) => ss.toList
          case Parsed.Success(t: Term)        => List(t)
          case err: Parsed.Error              => throw err.details

  // ── Top-level stats → Program ─────────────────────────────────────────────────

  def convertStats(stats: List[Stat]): Program =
    registerTypes(stats)  // first pass: populate field registry

    val defsB  = List.newBuilder[CDef]
    val entryB = List.newBuilder[Stat]

    val userDefNames = (stats.collect {
      case d: Defn.Def                        => List(d.name.value)
      case v: Defn.Val if isSimplePat(v.pats) => List(patName(v.pats.head))
      case g: Defn.Given                      => List(g.name.value)
      case eg: Defn.ExtensionGroup            => extMethods(eg).map(_.name.value)
    }).flatten.toSet

    stats.foreach {
      case d: Defn.Def =>
        val params = allParams(d)
        val scope  = params.reverse  // Local(0) = last param
        val body   = convertExpr(d.body, scope)
        val lam    = if params.isEmpty then CT.Lam(0, body) else CT.Lam(params.length, body)
        defsB += CDef(d.name.value, lam)
      case v: Defn.Val if isSimplePat(v.pats) =>
        val name = patName(v.pats.head)
        val rhs  = convertExpr(v.rhs, Nil)
        defsB += CDef(name, rhs)
      case g: Defn.Given =>
        // given name: T with { def m1(a...) = ...; def m2(b...) = ... }
        // → val name = __mk_method_obj__(["m1", lam..., "m2", lam...])
        defsB += CDef(g.name.value, convertGiven(g))
      case eg: Defn.ExtensionGroup =>
        // extension (recv: T) def m(a...) = ... → global def m(recv, a...) = ...
        val recvName = extReceiverName(eg)
        extMethods(eg).foreach { m =>
          val params = recvName :: allParams(m)
          val scope  = params.reverse
          val body   = convertExpr(m.body, scope)
          val lam    = CT.Lam(params.length, body)
          defsB += CDef(m.name.value, lam)
        }
      case other =>
        entryB += other
    }

    val stdDefs = standardPrelude.filterNot(d => userDefNames.contains(d.name))
    val defs  = stdDefs ++ defsB.result()
    val entryStmts = entryB.result()
    val entry =
      if entryStmts.nonEmpty then convertBlock(entryStmts, Nil)
      else if userDefNames.contains("main") then CT.App(CT.Global("main"), Nil)
      else CT.Lit(Const.CUnit)
    Program(defs, entry)

  /** Standard prelude defs injected into every converted program.
   *  Maps common v1 scalascript globals to v2 primitives. */
  private val standardPrelude: List[CDef] = List(
    CDef("println",   CT.Lam(1, CT.Prim("io.println", List(CT.Local(0))))),
    CDef("print",     CT.Lam(1, CT.Prim("io.print",   List(CT.Local(0))))),
    CDef("identity",  CT.Lam(1, CT.Local(0))),
    CDef("not",       CT.Lam(1, CT.If(CT.Local(0), CT.Lit(Const.CBool(false)), CT.Lit(Const.CBool(true))))),
    CDef("math",          CT.Prim("__math_obj__", Nil)),
    CDef("__unsupported__", CT.Lam(1, CT.Prim("io.println",
      List(CT.Prim("__arith__", List(CT.Lit(Const.CStr("++")),
        CT.Lit(Const.CStr("Unsupported: ")), CT.Local(0))))))),
  )

  // ── Block lowering ────────────────────────────────────────────────────────────

  def convertBlock(stats: List[Stat], scope: List[String]): CT =
    stats match
      case Nil => CT.Lit(Const.CUnit)

      // val name = rhs; rest
      case (v: Defn.Val) :: rest if isSimplePat(v.pats) =>
        val name = patName(v.pats.head)
        val rhs  = convertExpr(v.rhs, scope)
        CT.Let(List(rhs), convertBlock(rest, name :: scope))

      // val (a, b) = rhs; rest (tuple destructuring)
      case (v: Defn.Val) :: rest =>
        val rhs   = convertExpr(v.rhs, scope)
        val names = v.pats.flatMap(patNames)
        if names.isEmpty then
          CT.Let(List(rhs), convertBlock(rest, "_blk_" :: scope))
        else
          // Bind tuple to "_tup_", then extract each field
          val tupName  = "_tup_"
          val withTup  = tupName :: scope
          def chain(remaining: List[(String, Int)], sc: List[String]): CT =
            remaining match
              case Nil              => convertBlock(rest, sc)
              case (nm, i) :: tail =>
                CT.Let(
                  List(CT.Prim("fieldAt", List(lookupVar(tupName, sc), CT.Lit(Const.CInt(i))))),
                  chain(tail, nm :: sc)
                )
          CT.Let(List(rhs), chain(names.zipWithIndex.toList, withTup))

      // var name = rhs; rest (mutable cell)
      case (v: Defn.Var) :: rest =>
        val names = v.pats.flatMap(patNames)
        val rhs   = varRhs(v)
        val rhsIr = convertExpr(rhs, scope)
        val (cellOp, prefix) =
          if isIntLit(rhs) then ("lcell.new", "@@")
          else                   ("cell.new",  "@")
        val cellName = names.headOption.map(n => s"$prefix$n").getOrElse("@_")
        CT.Let(List(CT.Prim(cellOp, List(rhsIr))), convertBlock(rest, cellName :: scope))

      // def name(params) = body; rest (local LetRec for self-recursion)
      case (d: Defn.Def) :: rest =>
        val name      = d.name.value
        val params    = allParams(d)
        val letrecSc  = name :: scope
        val defSc     = params.reverse ++ letrecSc
        val body      = convertExpr(d.body, defSc)
        val lam       = CT.Lam(params.length, body)
        CT.LetRec(List(lam), convertBlock(rest, letrecSc))

      // while (cond) body; rest
      case (w: Term.While) :: rest =>
        val loop = CT.While(convertExpr(w.expr, scope), convertExpr(w.body, scope))
        rest match
          case Nil => loop
          case _   => CT.Let(List(loop), convertBlock(rest, "_blk_" :: scope))

      // x = rhs (assignment to mutable var)
      case (a: Term.Assign) :: rest =>
        val setIr = convertAssign(a, scope)
        rest match
          case Nil => setIr
          case _   => CT.Seq(List(setIr, convertBlock(rest, scope)))

      // expression statement (not last)
      case (t: Term) :: (rest @ (_ :: _)) =>
        CT.Seq(List(convertExpr(t, scope), convertBlock(rest, scope)))

      // last expression — the block's value
      case (t: Term) :: Nil =>
        convertExpr(t, scope)

      // non-Term at end (shouldn't happen in well-formed code)
      case _ :: rest => convertBlock(rest, scope)

  // ── Expression conversion ─────────────────────────────────────────────────────

  def convertExpr(term: Term, scope: List[String]): CT = term match

    // ── Literals ──────────────────────────────────────────────────────────────
    // Note: scalameta Lit.Int/Double/Float hold the literal as String from source
    case Lit.Int(n)     => CT.Lit(Const.CInt(n.toLong))
    case Lit.Long(n)    => CT.Lit(Const.CInt(n.toLong))
    case Lit.Double(d)  => CT.Lit(Const.CFloat(d.toDouble))
    case Lit.Float(f)   => CT.Lit(Const.CFloat(f.toDouble))
    case Lit.String(s)  => CT.Lit(Const.CStr(s))
    case Lit.Boolean(b) => CT.Lit(Const.CBool(b))
    case Lit.Unit()     => CT.Lit(Const.CUnit)
    case Lit.Char(c)    => CT.Lit(Const.CInt(c.toLong))
    case Lit.Null()     => CT.Ctor("None", Nil)

    // ── Variables ─────────────────────────────────────────────────────────────
    case Term.Name(n)   => lookupVarFull(n, scope)

    // ── Block ─────────────────────────────────────────────────────────────────
    case Term.Block(ss) => convertBlock(ss, scope)

    // ── If / else ─────────────────────────────────────────────────────────────
    case t: Term.If =>
      val c = convertExpr(t.cond, scope)
      val th = convertExpr(t.thenp, scope)
      // elsep is Lit.Unit() or Term.Block(Nil) when no else branch
      val el = t.elsep match
        case Lit.Unit()        => CT.Lit(Const.CUnit)
        case Term.Block(Nil)   => CT.Lit(Const.CUnit)
        case e                 => convertExpr(e, scope)
      CT.If(c, th, el)

    // ── Lambda ────────────────────────────────────────────────────────────────
    case Term.Function.After_4_6_0(paramClause, body) =>
      val names  = paramClause.values.map(_.name.value)
      val newSc  = names.reverse.toList ++ scope
      CT.Lam(names.length, convertExpr(body, newSc))

    case Term.AnonymousFunction(body) =>
      val arity = countPlaceholders(body)
      if arity == 0 then CT.Lam(1, convertExpr(body, "_" :: scope))
      else
        // _ph_0 is arg0 (outer), _ph_{n-1} is innermost (Local(0) is newest-bound)
        // With arity=2 and names=[_ph_0, _ph_1]: scope = _ph_1 :: _ph_0 :: outer
        // so Local(0)=_ph_1, Local(1)=_ph_0
        val paramNames = (0 until arity).map(i => s"_ph_$i").toList
        val innerScope = paramNames.reverse ++ scope  // newest-first
        val counter = Array(0)
        def go(t: Term): CT = t match
          case _: Term.Placeholder =>
            val i = counter(0); counter(0) += 1
            val name = paramNames(i)
            CT.Local(innerScope.indexOf(name))
          case Term.Select(q, Term.Name(mname)) =>
            val recv = go(q)
            if extensionMethods.contains(mname) then CT.App(CT.Global(mname), List(recv))
            else fieldIndex(mname) match
              case Some(i) => CT.Prim("fieldAt", List(recv, CT.Lit(Const.CInt(i))))
              case None    => CT.Prim("__method__", List(CT.Lit(Const.CStr(mname)), recv))
          case Term.Apply.After_4_6_0(fn, ac) =>
            convertApply(fn, ac.values.toList.map(a => if hasPH(a) then Term.AnonymousFunction(a) else a), innerScope.drop(arity))
          case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
            val l = go(lhs)
            val r = argClause.values.headOption.map(go).getOrElse(CT.Lit(Const.CUnit))
            convertInfix(op.value, l, r, innerScope)
          case _ => convertExpr(t, innerScope)
        CT.Lam(arity, go(body))

    // ── Tuple ─────────────────────────────────────────────────────────────────
    case Term.Tuple(elems) =>
      CT.Ctor(s"Tuple${elems.length}", elems.map(e => convertExpr(e, scope)))

    // ── New ───────────────────────────────────────────────────────────────────
    case Term.New(init) =>
      val name = ctorName(init.tpe)
      val args = init.argClauses.flatMap(_.values).map(e => convertExpr(e, scope)).toList
      CT.Ctor(name, args)

    // ── Pattern match ─────────────────────────────────────────────────────────
    case t: Term.Match =>
      convertMatch(convertExpr(t.expr, scope), t.cases, scope)

    // ── Infix ─────────────────────────────────────────────────────────────────
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
      val l = convertExpr(lhs, scope)
      val r = argClause.values.headOption.map(e => convertExpr(e, scope)).getOrElse(CT.Lit(Const.CUnit))
      convertInfix(op.value, l, r, scope)

    // ── Unary prefix ──────────────────────────────────────────────────────────
    case Term.ApplyUnary(op, expr) =>
      CT.Prim("__unary__", List(CT.Lit(Const.CStr(op.value)), convertExpr(expr, scope)))

    // ── Type application — erase type args (summon[T] → resolve given) ──────
    case Term.ApplyType.After_4_6_0(Term.Name("summon"), argClause) =>
      val typeSig = argClause match
        case ac: Type.ArgClause => ac.values.headOption.fold("?")(_.syntax)
        case _ => "?"
      givenRegistry.get(typeSig) match
        case Some(name) => CT.Global(name)
        case None       => CT.App(CT.Global("__unsupported__"), List(CT.Lit(Const.CStr(s"summon[$typeSig]"))))
    case Term.ApplyType.After_4_6_0(fn, _) =>
      convertExpr(fn, scope)

    // ── Function application ──────────────────────────────────────────────────
    case Term.Apply.After_4_6_0(fn, argClause) =>
      convertApply(fn, argClause.values.toList, scope)

    // ── Member select (field/method as value, no args) ────────────────────────
    case Term.Select(qual, Term.Name(name)) =>
      val q = convertExpr(qual, scope)
      if extensionMethods.contains(name) then
        CT.App(CT.Global(name), List(q))
      else
        fieldIndex(name) match
          case Some(i) => CT.Prim("fieldAt", List(q, CT.Lit(Const.CInt(i))))
          case None    => CT.Prim("__method__", List(CT.Lit(Const.CStr(name)), q))

    // ── Assign ────────────────────────────────────────────────────────────────
    case a: Term.Assign => convertAssign(a, scope)

    // ── While ─────────────────────────────────────────────────────────────────
    case t: Term.While =>
      CT.While(convertExpr(t.expr, scope), convertExpr(t.body, scope))

    // ── Return ────────────────────────────────────────────────────────────────
    case Term.Return(expr) => convertExpr(expr, scope)

    // ── Eta expansion ─────────────────────────────────────────────────────────
    case Term.Eta(expr) => convertExpr(expr, scope)

    // ── Type ascription ───────────────────────────────────────────────────────
    case Term.Ascribe(expr, _) => convertExpr(expr, scope)

    // ── Throw ─────────────────────────────────────────────────────────────────
    case Term.Throw(expr) =>
      CT.App(CT.Global("__throw__"), List(convertExpr(expr, scope)))

    // ── Try/catch (run body, ignore handlers for now) ─────────────────────────
    case Term.Try(expr, _, _) => convertExpr(expr, scope)

    // ── Partial function {case p => e} as lambda+match ────────────────────────
    case Term.PartialFunction(cases) =>
      CT.Lam(1, convertMatch(CT.Local(0), cases, "_pfn_" :: scope))

    // ── Anonymous function (placeholder syntax: _.foo, _ + 1, etc.) ─────────
    case Term.AnonymousFunction(body) =>
      val (n, replaced) = collectPlaceholders(body)
      val paramNames = (0 until n).map(i => s"_p$i").toList
      val innerScope = paramNames.reverse ++ scope
      CT.Lam(n, convertExpr(replaced, innerScope))

    // ── For/yield ────────────────────────────────────────────────────────────
    case Term.ForYield(enums, body) =>
      convertForYield(enums, body, scope)

    // ── For/do (imperative) ───────────────────────────────────────────────────
    case Term.For(enums, body) =>
      convertForDo(enums, body, scope)

    // ── String interpolation s"... $x ..." ───────────────────────────────────
    case Term.Interpolate(Term.Name("s"), parts, args) =>
      val strs = parts.map {
        case Lit.String(s) => CT.Lit(Const.CStr(s))
        case _             => CT.Lit(Const.CStr(""))
      }
      val vals = args.map { e =>
        CT.Prim("__method__", List(CT.Lit(Const.CStr("toString")), convertExpr(e, scope)))
      }
      interleaveConcat(strs, vals)

    // ── Unknown — emit stub that errors at runtime ────────────────────────────
    case other =>
      CT.App(CT.Global("__unsupported__"),
        List(CT.Lit(Const.CStr(other.getClass.getSimpleName))))

  // ── Helpers ───────────────────────────────────────────────────────────────────

  private def lookupVar(name: String, scope: List[String]): CT =
    val i = scope.indexOf(name)
    if i >= 0 then CT.Local(i) else CT.Global(name)

  private def lookupVarFull(name: String, scope: List[String]): CT =
    val i = scope.indexOf(name)
    if i >= 0 then CT.Local(i)
    else
      val lcell = scope.indexOf(s"@@$name")
      val cell  = scope.indexOf(s"@$name")
      if lcell >= 0 then CT.Prim("lcell.get", List(CT.Local(lcell)))
      else if cell >= 0 then CT.Prim("cell.get", List(CT.Local(cell)))
      else if isCtorName(name) then CT.Ctor(name, Nil)  // e.g. Nil, None, True
      else CT.Global(name)

  private def convertAssign(a: Term.Assign, scope: List[String]): CT =
    a.lhs match
      case Term.Name(name) =>
        val lcell = scope.indexOf(s"@@$name")
        val cell  = scope.indexOf(s"@$name")
        val rhs   = convertExpr(a.rhs, scope)
        if lcell >= 0 then CT.Prim("lcell.set", List(CT.Local(lcell), rhs))
        else if cell >= 0 then CT.Prim("cell.set", List(CT.Local(cell), rhs))
        else CT.Prim("cell.set", List(CT.Global(s"@$name"), rhs))
      case Term.Apply.After_4_6_0(fn, argClause) =>
        val arr = convertExpr(fn, scope)
        val idx = argClause.values.headOption.map(e => convertExpr(e, scope)).getOrElse(CT.Lit(Const.CInt(0)))
        CT.Prim("arr.set", List(arr, idx, convertExpr(a.rhs, scope)))
      case other =>
        CT.Prim("__assign__", List(convertExpr(other, scope), convertExpr(a.rhs, scope)))

  private def convertApply(fn: Term, rawArgs: List[Term], scope: List[String]): CT =
    val args = rawArgs.map(e => convertExpr(wrapIfPH(e), scope))
    fn match
      // List/Seq/Vector factory → linked list
      case Term.Name("List") | Term.Name("Seq") | Term.Name("Vector") | Term.Name("IArray") =>
        val nil: CT = CT.Ctor("Nil", Nil)
        args.foldRight(nil)((h, t) => CT.Ctor("Cons", List(h, t)))
      // Map factory: Map(k1->v1, k2->v2) → map.new + map.put
      case Term.Name("Map") =>
        CT.Prim("__mk_map__", args)
      // Set factory → just build a deduplicated list for now
      case Term.Name("Set") =>
        val nil: CT = CT.Ctor("Nil", Nil)
        args.foldRight(nil)((h, t) => CT.Ctor("Cons", List(h, t)))
      // Constructor application
      case Term.Name(name) if isCtorName(name) => CT.Ctor(name, args)
      // Method call: qual.method(args)
      case Term.Select(qual, Term.Name(mname)) =>
        if isCtorName(mname) then CT.Ctor(mname, args)
        else
          val q = convertExpr(qual, scope)
          // Extension method → global call with receiver as first arg
          if extensionMethods.contains(mname) then
            CT.App(CT.Global(mname), q :: args)
          // If it's a known field accessor with no args, use fieldAt
          else if args.isEmpty then
            fieldIndex(mname) match
              case Some(i) => CT.Prim("fieldAt", List(q, CT.Lit(Const.CInt(i))))
              case None    => CT.Prim("__method__", CT.Lit(Const.CStr(mname)) :: q :: args)
          else
            CT.Prim("__method__", CT.Lit(Const.CStr(mname)) :: q :: args)
      // Curried method application: qual.method(a)(b) — merge into one __method__ call
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name(mname)), innerClause) if !isCtorName(mname) =>
        val q     = convertExpr(qual, scope)
        val inner = innerClause.values.toList.map(e => convertExpr(e, scope))
        CT.Prim("__method__", CT.Lit(Const.CStr(mname)) :: q :: (inner ++ args))
      // summon[T] — resolve given by type string
      case Term.ApplyType.After_4_6_0(Term.Name("summon"), argClause) =>
        val typeSig = argClause match
          case ac: Type.ArgClause => ac.values.headOption.fold("?")(_.syntax)
          case _ => "?"
        givenRegistry.get(typeSig) match
          case Some(name) => CT.Global(name)
          case None       => CT.App(CT.Global("__unsupported__"), List(CT.Lit(Const.CStr(s"summon[$typeSig]"))))
      // Type-applied call List[T](...) — strip type args
      case Term.ApplyType.After_4_6_0(inner, _) =>
        convertApply(inner, rawArgs, scope)
      // Curried constructor application f(a)(b)
      case Term.Apply.After_4_6_0(inner, innerClause) =>
        val innerApp = convertApply(inner, innerClause.values.toList, scope)
        CT.App(innerApp, args)
      // Regular function call
      case other => CT.App(convertExpr(other, scope), args)

  private def convertInfix(op: String, l: CT, r: CT, scope: List[String]): CT = op match
    case "&&" => CT.If(l, r, CT.Lit(Const.CBool(false)))
    case "||" => CT.If(l, CT.Lit(Const.CBool(true)), r)
    case "::"  => CT.Ctor("Cons", List(l, r))
    case _    => CT.Prim("__arith__", List(CT.Lit(Const.CStr(op)), l, r))

  private def convertMatch(scrut: CT, cases: List[Case], scope: List[String]): CT =
    val hasCtorArms = cases.exists { c =>
      c.pat match
        case Pat.Extract.After_4_6_0(_, _) | Pat.ExtractInfix.After_4_6_0(_, _, _) |
             Pat.Tuple(_)                                                              => true
        case Term.Name(n) if isCtorName(n)                                            => true
        case Pat.Var(Term.Name(n)) if isCtorName(n)                                  => true
        case _                                                                         => false
    }
    val hasLitArms      = cases.exists(c => c.pat.is[Lit])
    val hasNestedOrDup  = hasLitArms || needsGeneralChain(cases)
    val sc = "_sc_" :: scope

    if hasNestedOrDup || (!hasCtorArms && cases.exists(_.cond.nonEmpty)) then
      // General if-chain: each case becomes a condition (flat, using nested fieldAt) + bindings + body
      val scrutRef = CT.Local(0)
      def caseChain(cs: List[Case]): CT = cs match
        case Nil => CT.App(CT.Global("__match_fail__"), Nil)
        case c :: rest =>
          val (conds, binds) = flattenPattern(c.pat, scrutRef, sc)
          val failCT  = caseChain(rest)
          // CT.Let is SEQUENTIAL: each binding shifts Local(k) by 1 for subsequent binds.
          // Shift the scrutinee reference (L0 at this scope) by k for the k-th binding.
          val bindRhs = binds.zipWithIndex.map { case ((expr, _), k) => shiftLocals(expr, k) }
          val bindNms = binds.map(_._2)
          val bodyScope = bindNms.reverse ++ sc
          val bodyExpr  = c.cond match
            case Some(g) => CT.If(convertExpr(g, bodyScope), convertExpr(c.body, bodyScope), failCT)
            case None    => convertExpr(c.body, bodyScope)
          val withBinds = if binds.isEmpty then bodyExpr else CT.Let(bindRhs, bodyExpr)
          conds.foldRight(withBinds) { (k, t) => CT.If(k, t, failCT) }
      CT.Let(List(scrut), caseChain(cases))
    else
      // Simple ctor match (no duplicates, no nested ctors) → CT.Match
      var default: Option[CT] = None
      val arms = List.newBuilder[Arm]
      cases.foreach { c =>
        val (ctorOpt, names) = convertPat(c.pat)
        val bodyScope        = names ++ scope
        val rawBody          = convertExpr(c.body, bodyScope)
        val body             = c.cond match
          case Some(g) => CT.If(convertExpr(g, bodyScope), rawBody, CT.App(CT.Global("__match_fail__"), Nil))
          case None    => rawBody
        ctorOpt match
          case None               => default = Some(body)
          case Some((tag, arity)) => arms += Arm(tag, arity, body)
      }
      CT.Match(scrut, arms.result(), default)

  private def needsGeneralChain(cases: List[Case]): Boolean =
    // True if there are duplicate outer ctor tags or any arm has complex sub-patterns
    val seen = collection.mutable.HashSet[String]()
    cases.exists { c =>
      c.pat match
        case Pat.Extract.After_4_6_0(ctor, ac) =>
          val key = s"${ctorPatName(ctor)}/${ac.values.length}"
          val dup = !seen.add(key)
          dup || ac.values.exists {
            case _: Pat.Extract | _: Lit => true
            case Pat.Var(Term.Name(n)) if isCtorName(n) => true
            case _ => false
          }
        case _ => false
    }

  /** Flatten a pattern into (conditions, ordered variable bindings).
   *  All CTs in conditions + bindings are evaluated in the SAME scope (before any Let).
   *  This avoids De Bruijn shifting issues when generating if-chains. */
  private def flattenPattern(pat: Pat, scrutRef: CT, scope: List[String]): (List[CT], List[(CT, String)]) = pat match
    case Pat.Wildcard() | Pat.Var(Term.Name("_")) => (Nil, Nil)
    case Pat.Var(Term.Name(n)) if !isCtorName(n) => (Nil, List((scrutRef, n)))
    case Pat.Var(Term.Name(n)) if isCtorName(n) =>
      (List(CT.Prim("__isTag__", List(scrutRef, CT.Lit(Const.CStr(n)), CT.Lit(Const.CInt(0))))), Nil)
    case Term.Name(n) if isCtorName(n) =>
      (List(CT.Prim("__isTag__", List(scrutRef, CT.Lit(Const.CStr(n)), CT.Lit(Const.CInt(0))))), Nil)
    case l: Lit =>
      (List(CT.Prim("__arith__", List(CT.Lit(Const.CStr("==")), scrutRef, convertExpr(l, scope)))), Nil)
    case Pat.Typed(inner, _) => flattenPattern(inner, scrutRef, scope)
    case Pat.ExtractInfix.After_4_6_0(h, Term.Name("::"), t) =>
      val head = h; val tail = t.values.headOption.getOrElse(Pat.Wildcard())
      flattenCtorPat("Cons", List(head, tail), scrutRef, scope)
    case Pat.Tuple(pats) =>
      flattenCtorPat(s"Tuple${pats.length}", pats.toList, scrutRef, scope)
    case Pat.Extract.After_4_6_0(ctor, ac) =>
      flattenCtorPat(ctorPatName(ctor), ac.values.toList, scrutRef, scope)
    case Pat.Alternative(alts) =>
      // Simplified: use first alternative only
      flattenPattern(alts.head, scrutRef, scope)
    case _ => (Nil, Nil)

  private def flattenCtorPat(tag: String, pats: List[Pat], scrutRef: CT, scope: List[String]): (List[CT], List[(CT, String)]) =
    val arity   = pats.length
    val tagCond = CT.Prim("__isTag__", List(scrutRef, CT.Lit(Const.CStr(tag)), CT.Lit(Const.CInt(arity))))
    val (subConds, subBinds) = pats.zipWithIndex.map { case (p, i) =>
      val fref = CT.Prim("fieldAt", List(scrutRef, CT.Lit(Const.CInt(i))))
      flattenPattern(p, fref, scope)  // all use current scope, no scope extension here
    }.unzip
    (tagCond :: subConds.flatten, subBinds.flatten)

  /** Shift all Local indices by `amount` in a pure expression (no binders). */
  private def shiftLocals(expr: CT, amount: Int): CT =
    if amount == 0 then expr
    else expr match
      case CT.Local(k)       => CT.Local(k + amount)
      case CT.Prim(op, args) => CT.Prim(op, args.map(a => shiftLocals(a, amount)))
      case other             => other  // Lit, Global — no locals

  /** Produce an optional condition term for a literal pattern match. */
  private def matchCond(pat: Pat, scrutRef: CT, scope: List[String]): Option[CT] = pat match
    case Pat.Wildcard()               => None
    case Pat.Var(Term.Name(n)) if !isCtorName(n) => None
    case l: Lit =>
      Some(CT.Prim("__arith__", List(CT.Lit(Const.CStr("==")), scrutRef, convertExpr(l, scope))))
    case _                            => None

  /** Returns (Some((tag, arity)) for ctor patterns, None for wildcard/var),
   *  and the list of bound names (newest-first for de Bruijn). */
  private def convertPat(pat: Pat): (Option[(String, Int)], List[String]) =
    pat match
      case Pat.Wildcard()                                   => (None, List("_"))
      // Scala3: uppercase stable-id patterns are Term.Name (not Pat.Var)
      case Term.Name(n) if isCtorName(n)                   => (Some((n, 0)), Nil)
      // Uppercase name in Pat.Var = constructor (enum case or zero-arity case class)
      case Pat.Var(Term.Name(n)) if isCtorName(n)          => (Some((n, 0)), Nil)
      case Pat.Var(Term.Name(n))                            => (None, List(n))
      case Pat.Typed(Pat.Wildcard(), _)                     => (None, List("_"))
      case Pat.Typed(Pat.Var(Term.Name(n)), _) if isCtorName(n) => (Some((n, 0)), Nil)
      case Pat.Typed(Pat.Var(Term.Name(n)), _)              => (None, List(n))
      case Pat.Extract.After_4_6_0(ctor, argClause) =>
        val tag   = ctorPatName(ctor)
        val pats  = argClause.values
        val names = pats.toList.flatMap {
          case Pat.Var(Term.Name(n))               => List(n)
          case Pat.Wildcard()                      => List("_")
          case Pat.Typed(Pat.Var(Term.Name(n)), _) => List(n)
          case _                                   => List("_")
        }.reverse  // reverse: last field = Local(0)
        (Some((tag, pats.length)), names)
      case Pat.ExtractInfix.After_4_6_0(head, Term.Name("::"), tail) =>
        val headName = head match { case Pat.Var(Term.Name(n)) => n; case _ => "_" }
        val tailName = tail.values.headOption match
          case Some(Pat.Var(Term.Name(n))) => n
          case _                           => "_"
        (Some(("Cons", 2)), List(tailName, headName))  // reversed: tail=Local(0), head=Local(1)
      case Pat.Tuple(pats) =>
        val arity = pats.length
        val names = pats.toList.flatMap {
          case Pat.Var(Term.Name(n)) => List(n)
          case Pat.Wildcard()        => List("_")
          case _                     => List("_")
        }.reverse
        (Some((s"Tuple$arity", arity)), names)
      case Pat.Alternative(alts) =>
        // Use first alternative (simplified — guards not supported for alts)
        convertPat(alts.head)
      case _: Lit =>
        // Literal pattern — becomes default + guard (simplified: emit as wildcard)
        (None, List("_"))
      case _ =>
        (None, List("_"))

  private def convertForYield(enums: List[Enumerator], body: Term, scope: List[String]): CT =
    enums match
      case Nil =>
        convertExpr(body, scope)
      case Enumerator.Generator(pat, rhs) :: rest =>
        val name     = pat match { case Pat.Var(Term.Name(n)) => n; case Pat.Tuple(_) => "_tup_"; case _ => "_gen_" }
        val q        = convertExpr(rhs, scope)
        val newScope = name :: scope
        val hasMoreGens = rest.exists { case _: Enumerator.Generator => true; case _ => false }
        if hasMoreGens then
          // Not last generator: flatMap to produce sub-lists
          val inner = CT.Lam(1, convertForYield(rest, body, newScope))
          CT.Prim("__method__", List(CT.Lit(Const.CStr("flatMap")), q, inner))
        else
          // Last generator: apply guards as filter, then map body
          val filtered = rest.foldLeft(q) {
            case (acc, Enumerator.Guard(cond)) =>
              CT.Prim("__method__", List(CT.Lit(Const.CStr("filter")), acc,
                CT.Lam(1, convertExpr(cond, newScope))))
            case (acc, _) => acc
          }
          // Handle tuple destructuring in last generator via PartialFunction pattern
          val bodyLam = pat match
            case Pat.Tuple(_) =>
              CT.Lam(1, convertMatch(CT.Local(0),
                List(Case(pat, None, body)), "_gen_" :: newScope))
            case _ => CT.Lam(1, convertExpr(body, newScope))
          CT.Prim("__method__", List(CT.Lit(Const.CStr("map")), filtered, bodyLam))
      case Enumerator.Val(pat, rhs) :: rest =>
        val name = pat match { case Pat.Var(Term.Name(n)) => n; case _ => "_val_" }
        CT.Let(List(convertExpr(rhs, scope)), convertForYield(rest, body, name :: scope))
      case Enumerator.Guard(cond) :: rest =>
        CT.If(convertExpr(cond, scope), convertForYield(rest, body, scope), CT.Lit(Const.CUnit))
      case _ :: rest =>
        convertForYield(rest, body, scope)

  /** True if the term contains a placeholder anywhere. */
  private def hasPH(t: Term): Boolean = t match
    case Term.Placeholder()                            => true
    case Term.Select(q, _)                             => hasPH(q)
    case Term.Apply.After_4_6_0(f, ac)                => hasPH(f) || ac.values.exists(hasPH)
    case Term.ApplyInfix.After_4_6_0(l, _, _, rc)     => hasPH(l) || rc.values.exists(hasPH)
    case Term.ApplyUnary(_, x)                         => hasPH(x)
    case _                                             => false

  /** Wrap a placeholder-containing expression into an anonymous function. */
  private def wrapIfPH(t: Term): Term =
    if hasPH(t) then Term.AnonymousFunction(t) else t

  /** Replace placeholders left-to-right with fresh names; returns (count, rewritten tree). */
  private def collectPlaceholders(t: Term): (Int, Term) =
    var count = 0
    def fresh(): Term.Name = { val n = Term.Name(s"_p$count"); count += 1; n }
    def walk(node: Term): Term = node match
      case Term.Placeholder()                                => fresh()
      case Term.Select(q, n)                                 => Term.Select(walk(q), n)
      case Term.Apply.After_4_6_0(f, argClause)             =>
        Term.Apply.After_4_6_0(walk(f), Term.ArgClause(argClause.values.map(walk)))
      case Term.ApplyInfix.After_4_6_0(l, op, tgs, rc)     =>
        Term.ApplyInfix.After_4_6_0(walk(l), op, tgs, Term.ArgClause(rc.values.map(walk)))
      case Term.ApplyUnary(op, x)                           => Term.ApplyUnary(op, walk(x))
      case other                                             => other
    val replaced = walk(t)
    (count, replaced)

  private def convertForDo(enums: List[Enumerator], body: Term, scope: List[String]): CT =
    enums match
      case Nil => convertExpr(body, scope)
      case Enumerator.Generator(pat, rhs) :: rest =>
        val name     = pat match { case Pat.Var(Term.Name(n)) => n; case Pat.Tuple(_) => "_tup_"; case _ => "_gen_" }
        val q        = convertExpr(rhs, scope)
        val newScope = name :: scope
        val filtered = rest.foldLeft(q) {
          case (acc, Enumerator.Guard(cond)) =>
            CT.Prim("__method__", List(CT.Lit(Const.CStr("filter")), acc,
              CT.Lam(1, convertExpr(cond, newScope))))
          case (acc, _) => acc
        }
        val bodyLam = pat match
          case Pat.Tuple(_) =>
            CT.Lam(1, convertMatch(CT.Local(0), List(Case(pat, None, body)), "_gen_" :: newScope))
          case _ => CT.Lam(1, convertForDo(rest.filterNot(_.isInstanceOf[Enumerator.Guard]), body, newScope))
        CT.Prim("__method__", List(CT.Lit(Const.CStr("foreach")), filtered, bodyLam))
      case _ :: rest => convertForDo(rest, body, scope)

  private def interleaveConcat(strs: List[CT], vals: List[CT]): CT =
    (strs, vals) match
      case (Nil, Nil)        => CT.Lit(Const.CStr(""))
      case (s :: Nil, Nil)   => s
      case (Nil, v :: Nil)   => v
      case (s :: sr, v :: vr) =>
        val sv   = CT.Prim("__arith__", List(CT.Lit(Const.CStr("++")), s, v))
        val rest = interleaveConcat(sr, vr)
        CT.Prim("__arith__", List(CT.Lit(Const.CStr("++")), sv, rest))
      case (s :: sr, Nil) => interleaveConcat(sr, Nil) match
        case CT.Lit(Const.CStr("")) => s
        case rest => CT.Prim("__arith__", List(CT.Lit(Const.CStr("++")), s, rest))
      case (Nil, v :: vr) =>
        CT.Prim("__arith__", List(CT.Lit(Const.CStr("++")), v, interleaveConcat(Nil, vr)))

  // ── Pattern / def helpers ──────────────────────────────────────────────────────

  private def ctorName(tpe: Type): String = tpe match
    case Type.Name(n)         => n
    case Type.Apply(t, _)     => ctorName(t)
    case Type.Select(_, Type.Name(n)) => n
    case _                    => tpe.syntax

  private def ctorPatName(ctor: Term): String = ctor match
    case Term.Name(n)                  => n
    case Term.Select(_, Term.Name(n)) => n
    case _                             => "Unknown"

  private def isCtorName(n: String): Boolean =
    n.nonEmpty && Character.isUpperCase(n.charAt(0))

  private def allParams(d: Defn.Def): List[String] =
    d.paramClauseGroups.flatMap(_.paramClauses.flatMap(_.values.map(_.name.value)))

  private def isSimplePat(pats: List[Pat]): Boolean =
    pats.length == 1 && (pats.head match
      case Pat.Var(_) => true
      case _          => false)

  private def patName(pat: Pat): String = pat match
    case Pat.Var(Term.Name(n)) => n
    case _                     => "_"

  private def patNames(pat: Pat): List[String] = pat match
    case Pat.Var(Term.Name(n))  => List(n)
    case Pat.Tuple(pats)        => pats.flatMap(patNames).toList
    case Pat.Typed(inner, _)    => patNames(inner)
    case Pat.Extract.After_4_6_0(_, argClause) =>
      argClause.values.flatMap(patNames).toList
    case _                      => Nil

  private def countPlaceholders(t: Tree): Int = t match
    case _: Term.Placeholder => 1
    case _                   => t.children.map(countPlaceholders).sum

  private def isIntLit(t: Term): Boolean = t match
    case Lit.Int(_) | Lit.Long(_) => true
    case _                        => false

  private def varRhs(v: Defn.Var): Term = v match
    case Defn.Var.After_4_7_2(_, _, _, body: Term) => body
    case _                                          => Lit.Unit()

  /** Given registry: type-string → given name (for summon) */
  private val givenRegistry = collection.mutable.HashMap[String, String]()

  /** Convert a `given name: T with { defs }` to a method-object prim. */
  private def convertGiven(g: Defn.Given): CT =
    // Extract the parent type sig (e.g. "Show[Int]") from template.inits
    val typeSig = g.templ.inits.headOption.fold("")(_.tpe.syntax)
    givenRegistry(typeSig) = g.name.value
    val methods = g.templ.stats.collect { case m: Defn.Def => m }
    val pairs = methods.flatMap { m =>
      val params = allParams(m)
      val scope2 = params.reverse
      val body   = convertExpr(m.body, scope2)
      val lam    = if params.isEmpty then CT.Lam(0, body) else CT.Lam(params.length, body)
      List(CT.Lit(Const.CStr(m.name.value)), lam)
    }
    CT.Prim("__mk_method_obj__", pairs)
