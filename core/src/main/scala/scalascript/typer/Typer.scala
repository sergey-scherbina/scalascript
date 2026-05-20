package scalascript.typer

import scalascript.ast.*
import scala.collection.mutable.ListBuffer
import scala.meta.*
import java.security.MessageDigest

/** Optionally-populated map of pre-compiled module interfaces.
 *  Key is the import alias (or the last segment of the package name).
 *  Used by `ssc check --use-interface <dir>` to avoid re-parsing sources.
 *
 *  v2.0 / Stage 4.
 *
 *  @param importedInterfaces map of import alias -> ModuleInterface
 *  @param strict             when true, references to names not in scope
 *                            (neither in the consumer's own defs, nor in any
 *                            imported `.scim` interface, nor in the builtin
 *                            prelude) record a `TypeError`.  Default is
 *                            `false` for backward compatibility with callers
 *                            that rely on the historic permissive behaviour.
 */
class Typer(
    importedInterfaces: Map[String, scalascript.ir.ModuleInterface] = Map.empty,
    strict: Boolean = false
):
  private val errors      = ListBuffer[TypeError]()
  /** Registry of user-defined type aliases: name → (typeParams, expandedRhs).
   *  Populated by `checkStat` when it encounters a `type Name[...] = T` declaration.
   *  Consulted by `typeAnnotToSType` to expand alias names at use-sites. */
  private val typeAliases = collection.mutable.Map.empty[String, (List[String], SType)]

  /** Names of opaque types defined in the current module.
   *  Opaque types are nominal — `String` is NOT assignable to `UserId` outside
   *  the defining scope.  The typer records them here so `isCompatible` can
   *  enforce the sealing rule.  This is a conservative MVP: we treat all
   *  access as outside the defining scope (no two-zone rule yet). */
  private val opaqueTypes = collection.mutable.Set.empty[String]

  /** Field tables for user-defined classes / case classes / enums:
   *  className → ordered (fieldName, fieldType) list.  Populated by
   *  `checkStat` when it encounters a `class`/`case class` declaration
   *  with a constructor parameter clause.  Consulted by [[inferType]]
   *  on `Term.Select(qual, Name(field))` when `qual`'s type is
   *  `Named(className, _)`, so `someFoo.x` returns the declared type
   *  of `x` instead of collapsing to `Any`.
   *
   *  Tier-5 .scim granularity push (Open question #1) — the .scim
   *  artifact records callsite types accurately for the common
   *  "case-class accessor" pattern without paying the cost of full
   *  type-parameter substitution (deferred). */
  private val classFields = collection.mutable.Map.empty[String, List[(String, SType)]]

  /** Diagnostics accumulated so far.  Stable view into the running buffer —
   *  callers should snapshot to a `List` when they need persistence. */
  def diagnostics: List[TypeError] = errors.toList

  def typeCheck(module: Module): TypedModule =
    val prelude  = createPrelude()
    // v2.0: if we have pre-compiled interfaces, build an InterfaceScope layer
    // between the prelude and the module's own top-level scope so that names
    // from imported modules resolve without re-parsing their source.
    val baseScope =
      if importedInterfaces.isEmpty then prelude
      else
        import scalascript.artifact.InterfaceScope
        InterfaceScope.fromInterfaces(importedInterfaces.toList, parent = Some(prelude))
    val sections = module.sections.map(s => typeCheckSection(s, baseScope))
    TypedModule(
      name     = module.manifest.flatMap(_.name).getOrElse("<anonymous>"),
      version  = module.manifest.flatMap(_.version).getOrElse("0.0.0"),
      sections = sections,
      errors   = errors.toList
    )

  /** Incremental type-check variant.
   *
   *  Compares a content hash for each section against the previous run's
   *  [[SectionSnapshot]] list.  Re-types only from the first changed
   *  section forward; sections before it reuse their previously computed
   *  [[TypedSection]] results and the snapshot restores the typer's mutable
   *  state (typeAliases / opaqueTypes) to the value it had just before that
   *  section was originally typed.
   *
   *  @param module        freshly-parsed module (may differ from the previous run)
   *  @param prevSnapshots snapshots produced by the previous call (or `Nil` for
   *                       a cold first run, which re-types all sections)
   *  @return a pair of (TypedModule, new snapshots aligned with module.sections)
   */
  def typeCheckIncremental(
      module: Module,
      prevSnapshots: List[SectionSnapshot]
  ): (TypedModule, List[SectionSnapshot]) =
    val prelude = createPrelude()
    val baseScope =
      if importedInterfaces.isEmpty then prelude
      else
        import scalascript.artifact.InterfaceScope
        InterfaceScope.fromInterfaces(importedInterfaces.toList, parent = Some(prelude))

    // Build hashes for every section in the current parse.
    val currentHashes = module.sections.map(SectionSnapshot.hashSection)

    // Find the first section whose hash differs from the previous snapshot.
    // `firstChangedIdx` == module.sections.length when nothing changed.
    val prevHashList = prevSnapshots.map(_.sectionHash)
    val firstChangedIdx = currentHashes.zipWithIndex.collectFirst {
      case (hash, idx) if prevHashList.lift(idx).forall(_ != hash) => idx
    }.getOrElse(module.sections.length)

    // Restore typer state from the snapshot *before* the first changed section.
    // If firstChangedIdx == 0, we start from an empty state (already the
    // default after construction).
    if firstChangedIdx > 0 then
      val snapBefore = prevSnapshots(firstChangedIdx - 1)
      typeAliases.clear()
      typeAliases ++= snapBefore.typeAliases
      opaqueTypes.clear()
      opaqueTypes ++= snapBefore.opaqueTypes
    // else: typeAliases / opaqueTypes start empty (constructor defaults).

    errors.clear()

    // Restore errors from unchanged sections.  Without this, a type error in
    // section 1 would silently vanish whenever a later section is re-typed,
    // because `errors` only accumulates from the re-typed sections below.
    val unchangedErrors = prevSnapshots.take(firstChangedIdx).flatMap(_.errors)
    errors ++= unchangedErrors

    // Carry forward unchanged TypedSection results.
    val unchangedTyped = prevSnapshots.take(firstChangedIdx).map(_.typedSection)

    // Re-type sections from firstChangedIdx onward, building new snapshots.
    // Track where `errors` sits before each section so we can isolate that
    // section's own errors and store them in its snapshot.
    val newSnapshots = ListBuffer[SectionSnapshot](prevSnapshots.take(firstChangedIdx)*)
    val reTyped = module.sections.zipWithIndex.drop(firstChangedIdx).map { (section, _) =>
      val errorsBefore = errors.length
      val ts = typeCheckSection(section, baseScope)
      val sectionErrors = errors.slice(errorsBefore, errors.length).toList
      newSnapshots += SectionSnapshot(
        sectionHash  = SectionSnapshot.hashSection(section),
        typedSection = ts,
        typeAliases  = typeAliases.toMap,
        opaqueTypes  = opaqueTypes.toSet,
        errors       = sectionErrors
      )
      ts
    }

    val allSections = unchangedTyped ++ reTyped
    val typedModule = TypedModule(
      name     = module.manifest.flatMap(_.name).getOrElse("<anonymous>"),
      version  = module.manifest.flatMap(_.version).getOrElse("0.0.0"),
      sections = allSections,
      errors   = errors.toList
    )
    (typedModule, newSnapshots.toList)

  private def createPrelude(): Scope =
    val s = Scope()
    // I/O / assertions — variadic, single Any param sentinel
    s.define(Symbol("println", SType.Function(List(SType.Any), SType.Unit),  SymbolKind.Def))
    s.define(Symbol("print",   SType.Function(List(SType.Any), SType.Unit),  SymbolKind.Def))
    s.define(Symbol("assert",  SType.Function(List(SType.Any), SType.Unit),  SymbolKind.Def))
    s.define(Symbol("require", SType.Function(List(SType.Any), SType.Unit),  SymbolKind.Def))
    // Option / collection constructors
    s.define(Symbol("Some",    SType.Function(List(SType.Any), SType.option(SType.Any)), SymbolKind.Def))
    s.define(Symbol("None",    SType.option(SType.Nothing), SymbolKind.Val))
    s.define(Symbol("List",    SType.Function(List(SType.Any), SType.list(SType.Any)), SymbolKind.Def))
    s.define(Symbol("Vector",  SType.Function(List(SType.Any), SType.Named("Vector", List(SType.Any))), SymbolKind.Def))
    s.define(Symbol("Set",     SType.Function(List(SType.Any), SType.Named("Set",    List(SType.Any))), SymbolKind.Def))
    s.define(Symbol("Map",     SType.Function(List(SType.Any), SType.map(SType.Any, SType.Any)), SymbolKind.Def))
    s.define(Symbol("Seq",     SType.Function(List(SType.Any), SType.Named("Seq",    List(SType.Any))), SymbolKind.Def))
    s.define(Symbol("Array",   SType.Function(List(SType.Any), SType.Named("Array",  List(SType.Any))), SymbolKind.Def))
    s.define(Symbol("Right",   SType.Function(List(SType.Any), SType.Named("Either", List(SType.Any, SType.Any))), SymbolKind.Def))
    s.define(Symbol("Left",    SType.Function(List(SType.Any), SType.Named("Either", List(SType.Any, SType.Any))), SymbolKind.Def))
    // Standard objects / namespaces
    s.define(Symbol("math",    SType.Named("math", Nil), SymbolKind.Object))
    s.define(Symbol("scala",   SType.Named("scala", Nil), SymbolKind.Object))
    s.define(Symbol("java",    SType.Named("java", Nil), SymbolKind.Object))
    s.define(Symbol("compiletime", SType.Named("compiletime", Nil), SymbolKind.Object))
    // Doc / render / serve
    s.define(Symbol("doc",     SType.Function(List(SType.Any), SType.Any), SymbolKind.Def))
    s.define(Symbol("render",  SType.Function(List(SType.Any), SType.Unit), SymbolKind.Def))
    s.define(Symbol("serve",      SType.Function(List(SType.Any), SType.Unit), SymbolKind.Def))
    s.define(Symbol("serveAsync", SType.Function(List(SType.Any), SType.Unit), SymbolKind.Def))
    // Effect / actor / runtime intrinsics — the interpreter recognises these
    // by name (see backend-interpreter/Interpreter.scala).  Seeding them here
    // prevents strict-mode false-positives for code that uses real ScalaScript
    // effects without an explicit import.
    val variadic = SType.Function(List(SType.Any), SType.Any)
    val effectBuiltins = List(
      "handle", "validate", "computed", "effect", "summon", "summonInline",
      "constValue", "direct", "Focus", "Prism",
      "runActors", "runAsync", "runAsyncParallel", "runAuthWith",
      "runCache", "runCacheBypass",
      "runClock", "runClockAt", "runEnv", "runEnvWith",
      "runEphemeralStorage", "runHttp", "runHttpStub",
      "runLogger", "runLoggerJson", "runLoggerToList",
      "runRandom", "runRandomSeeded", "runRetry", "runRetryNoSleep",
      "runState", "runStorage", "runTx",
      "httpClient", "receive", "timeout",
      // process / actor primitives
      "spawn", "spawnLink", "spawnBounded", "self", "send", "exit",
      "link", "monitor", "demonitor", "trapExit", "processInfo",
      "startNode", "connectNode", "joinCluster",
      "register", "whereis", "globalRegister", "globalWhereis",
      "clusterMembers", "subscribeClusterEvents",
      "phiOf", "isSuspect", "selfNode", "clusterHealth",
      "broadcastHealth", "clusterIsDown",
      "electLeader", "currentLeader", "subscribeLeaderEvents", "setAutoReelect",
      "useRaftLeaderElection", "useExternalCoordinator", "leaderProtocol",
      "leaderHistory",
      "setReconnectPolicy", "requestGossip",
      "clusterConfigSet", "clusterConfigGet", "clusterConfigKeys",
      "subscribeConfigEvents",
      "setDraining", "isDraining", "drainingPeers", "subscribeDrainEvents",
      "clusterMetricSet", "clusterMetricGet", "clusterMetricSum",
      "clusterMetricNames", "subscribeMetricEvents",
      "sendAfter", "sendInterval", "cancelTimer",
      "delay", "async", "await", "parallel", "recvFrom",
      // tests / DSL helpers
      "main", "test", "describe", "it", "expect", "check"
    )
    effectBuiltins.foreach(n => s.define(Symbol(n, variadic, SymbolKind.Def)))
    s

  private def typeCheckSection(section: Section, parent: Scope): TypedSection =
    val scope = parent.child(section.heading.text)
    val defs  = section.content.flatMap(typeCheckContent(_, scope))
    TypedSection(
      name        = section.heading.text,
      level       = section.heading.level,
      definitions = defs,
      subsections = section.subsections.map(s => typeCheckSection(s, scope))
    )

  private def typeCheckContent(content: Content, scope: Scope): Option[TypedDef] =
    content match
      case cb: Content.CodeBlock =>
        val isScala = Lang.isParseable(cb.lang)
        if isScala && cb.tree.isEmpty then
          errors += TypeError(s"Failed to parse ${cb.lang} code block", None)
          Some(TypedDef.CodeBlock(cb.lang, parsed = false, Nil))
        else if isScala then
          val blockDefs = typeCheckBlock(cb, scope)
          Some(TypedDef.CodeBlock(cb.lang, parsed = true, blockDefs))
        else
          Some(TypedDef.CodeBlock(cb.lang, parsed = false, Nil))

      case imp: Content.Import =>
        imp.bindings.foreach { b =>
          scope.define(Symbol(b.name, SType.Any, SymbolKind.Val))
        }
        Some(TypedDef.Import(imp.path, imp.bindings.map(_.name)))

      case _ => None

  /** Walk scalameta statements in a code block, collect definitions into scope,
   *  and return a summary of what was found. */
  private def typeCheckBlock(cb: Content.CodeBlock, scope: Scope): List[DefSummary] =
    val summaries = ListBuffer[DefSummary]()
    cb.tree.foreach { node =>
      ScalaNode.fold(node) {
        case Source(stats)     => stats.foreach(s => checkStat(s, scope, summaries))
        case Term.Block(stats) => stats.foreach(s => checkStat(s, scope, summaries))
        case t: Term           => val _ = inferType(t, scope)
        case _                 => ()
      }
    }
    summaries.toList

  private def checkStat(
      stat: scala.meta.Tree,
      scope: Scope,
      out: ListBuffer[DefSummary]
  ): Unit = stat match

    // val name: T = rhs
    case Defn.Val(_, pats, tpeOpt, rhs) =>
      val rhsType  = inferType(rhs, scope)
      val declType = tpeOpt.map(typeAnnotToSType).getOrElse(rhsType)
      checkAssignable(rhsType, declType, rhs.pos)
      pats.foreach {
        case Pat.Var(name) =>
          scope.define(Symbol(name.value, declType, SymbolKind.Val))
          out += DefSummary(name.value, SymbolKind.Val, declType, Nil)
        case _ => ()
      }

    // var name: T = rhs
    case Defn.Var.After_4_7_2(_, List(Pat.Var(name)), tpeOpt, rhs) =>
      val rhsType  = inferType(rhs, scope)
      val declType = tpeOpt.map(typeAnnotToSType).getOrElse(rhsType)
      checkAssignable(rhsType, declType, rhs.pos)
      scope.define(Symbol(name.value, declType, SymbolKind.Var, mutable = true))
      out += DefSummary(name.value, SymbolKind.Var, declType, Nil)

    // def name(params...): T = body
    case d: Defn.Def =>
      val allParamVals = d.paramClauseGroups
        .flatMap(_.paramClauses)
        .flatMap(_.values)
        .toList
      val paramSTypes = allParamVals
        .map(p => p.decltpe.map(typeAnnotToSType).getOrElse(SType.Any))
      // Type-check body in a child scope with params bound, so we can infer
      // the return type when no explicit annotation is given.
      val bodyScope = scope.child(d.name.value)
      d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).foreach { p =>
        val pt = p.decltpe.map(typeAnnotToSType).getOrElse(SType.Any)
        bodyScope.define(Symbol(p.name.value, pt, SymbolKind.Param))
      }
      // Prefer the declared return type when present; otherwise infer from
      // the body.  Inference is signature-level only (literals, var refs,
      // simple arithmetic, blocks, if/else) — anything richer falls back to
      // SType.Any.
      val declaredRet = d.decltpe.map(typeAnnotToSType)
      val bodyType    = inferType(d.body, bodyScope)
      val retType     = declaredRet.getOrElse(bodyType)
      val fnType      = SType.Function(paramSTypes, retType)
      scope.define(Symbol(d.name.value, fnType, SymbolKind.Def))
      out += DefSummary(d.name.value, SymbolKind.Def, fnType, paramSTypes)
      declaredRet.foreach { declared =>
        if declared != SType.Any then
          checkAssignable(bodyType, declared, d.body.pos)
      }

    // class Name(params...)
    case d: Defn.Class =>
      val params = d.ctor.paramClauses.flatMap(_.values)
      val paramSTypes = params.map { p =>
        p.decltpe.map(typeAnnotToSType).getOrElse(SType.Any)
      }.toList
      val classType = SType.Named(d.name.value, Nil)
      val ctorType  = SType.Function(paramSTypes, classType)
      scope.define(Symbol(d.name.value, ctorType, SymbolKind.Class))
      // Index constructor params by name → type so [[inferType]] on
      // `Term.Select(qual, field)` can resolve a field access on a
      // value of this class type.  case classes expose every ctor
      // param as a public field; plain classes only when declared
      // `val` / `var` — both shapes carry decltpe at the same param
      // index in scalameta, so the table is built the same way.
      val fieldEntries = params.zip(paramSTypes).map { (p, t) => p.name.value -> t }.toList
      classFields(d.name.value) = fieldEntries
      // DefSummary records the *constructor* signature so consumers of the
      // `.scim` interface see `(Int, String) => Foo` for `case class Foo(x, y)`,
      // not just `Foo`.  This matches what the typer stores in the scope.
      out += DefSummary(d.name.value, SymbolKind.Class, ctorType, paramSTypes)

    // object Name
    case d: Defn.Object =>
      val objType = SType.Named(d.name.value, Nil)
      scope.define(Symbol(d.name.value, objType, SymbolKind.Object))
      out += DefSummary(d.name.value, SymbolKind.Object, objType, Nil)

    // enum Name
    case d: Defn.Enum =>
      val enumType = SType.Named(d.name.value, Nil)
      scope.define(Symbol(d.name.value, enumType, SymbolKind.Enum))
      d.templ.body.stats.foreach {
        case ec: Defn.EnumCase =>
          val caseSTypes = ec.ctor.paramClauses.flatMap(_.values).map { p =>
            p.decltpe.map(typeAnnotToSType).getOrElse(SType.Any)
          }.toList
          val caseType =
            if caseSTypes.isEmpty then enumType
            else SType.Function(caseSTypes, enumType)
          scope.define(Symbol(ec.name.value, caseType, SymbolKind.Val))
        case _ => ()
      }
      out += DefSummary(d.name.value, SymbolKind.Enum, enumType, Nil)

    // type Name[A, B] = T  — compile-time only; erased at runtime
    // `opaque type UserId = String` → Defn.Type with Mod.Opaque() in mods
    case d: Defn.Type =>
      val typeName = d.name.value
      val isOpaque = d.mods.exists(_.isInstanceOf[Mod.Opaque])
      if isOpaque then
        // Opaque types are nominal — NOT transparent to the underlying type
        // outside the defining scope (MVP: single-zone, no transparency inside).
        val opaqueNominalType = SType.Named(typeName, Nil)
        opaqueTypes += typeName
        scope.define(Symbol(typeName, opaqueNominalType, SymbolKind.Type))
        out += DefSummary(typeName, SymbolKind.Type, opaqueNominalType, Nil)
      else
        val typeParamNames = d.tparamClause.values.map(_.name.value).toList
        // Parse the RHS in a temporary context where the type params are known as
        // plain named types (so `type Opt[A] = Option[A]` resolves `A` to `SType.Named("A")`).
        val rhsSType = typeAnnotToSType(d.body)
        // Prevent trivially recursive aliases (name appears in its own rhs at top level).
        if isDirectlyRecursive(typeName, rhsSType) then
          errors += TypeError(s"Recursive type alias: $typeName", None)
        else
          typeAliases(typeName) = (typeParamNames, rhsSType)
          scope.defineType(typeName, TypeScheme(Nil, rhsSType))
          out += DefSummary(typeName, SymbolKind.Type, rhsSType, Nil)

    // Top-level expressions
    case t: Term => val _ = inferType(t, scope)

    case _ => ()

  /** Very lightweight type inference — returns an SType label for a term.
   *  Does not attempt full HM inference; focuses on literals and known names. */
  private def inferType(term: scala.meta.Tree, scope: Scope): SType = term match
    case Lit.Int(_)     => SType.Int
    case Lit.Long(_)    => SType.Long
    case Lit.Double(_)  => SType.Double
    case Lit.Float(_)   => SType.Double
    case Lit.String(_)  => SType.String
    case Lit.Boolean(_) => SType.Boolean
    case Lit.Char(_)    => SType.Char
    case Lit.Unit()     => SType.Unit
    case Lit.Null()     => SType.Null

    case t @ Term.Name(name) =>
      scope.lookup(name) match
        case Some(sym) => sym.tpe
        case None      =>
          // Strict mode: record a diagnostic for references to identifiers
          // that are not in any scope (the consumer's defs, any imported
          // `.scim` interface, or the builtin prelude).  Permissive mode
          // (the default) silently returns `SType.Any` to preserve
          // historical behaviour for callers like `ssc compile`.
          //
          // Conservative scoping rules — to avoid false-positives we only
          // flag a name that looks like a top-level term identifier:
          //  * starts with a letter or underscore (not an operator),
          //  * contains no `.` (selects go through `Term.Select`, not here),
          //  * is not a single-underscore placeholder.
          if strict && isFlaggableName(name) then
            errors += TypeError(
              s"Reference to undefined name: $name",
              posToSpan(t.pos)
            )
          SType.Any

    case Term.Apply.After_4_6_0(fun, argClause) =>
      inferType(fun, scope) match
        case SType.Function(paramTypes, retType) =>
          val args = argClause.values
          // Only check arity for non-variadic functions.
          // Variadic: represented as single SType.Any param in our prelude.
          val isVariadic = paramTypes == List(SType.Any)
          // Underflow is permitted — trailing parameters may have defaults that
          // the lightweight typer does not track. Only flag overflow.
          if !isVariadic && paramTypes.nonEmpty && args.length > paramTypes.length then
            errors += TypeError(
              s"Wrong number of arguments: expected ${paramTypes.length}, got ${args.length}",
              posToSpan(argClause.pos)
            )
          // Check argument types for known-param functions (only those provided).
          if !isVariadic then
            args.zip(paramTypes).foreach { (arg, expected) =>
              val actual = inferType(arg, scope)
              checkAssignable(actual, expected, arg.pos)
            }
          retType
        case _ => SType.Any

    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
      val lhsType  = inferType(lhs, scope)
      val rhsType  = argClause.values.headOption.map(inferType(_, scope)).getOrElse(SType.Any)
      checkBinaryOp(lhsType, op.value, rhsType, op.pos)

    case Term.Block(stats) =>
      val blockScope = scope.child("<block>")
      var lastType: SType = SType.Unit
      stats.foreach { s =>
        s match
          case t: Term =>
            lastType = inferType(t, blockScope)
          case stat =>
            val dummyOut = ListBuffer[DefSummary]()
            checkStat(stat, blockScope, dummyOut)
            lastType = SType.Unit
      }
      lastType

    case t: Term.If =>
      val condType = inferType(t.cond, scope)
      if condType != SType.Boolean && condType != SType.Any then
        errors += TypeError(
          s"If condition must be Boolean, found ${condType.show}",
          posToSpan(t.cond.pos)
        )
      val thenType = inferType(t.thenp, scope)
      val elseType = inferType(t.elsep, scope)
      if thenType == elseType then thenType else SType.Any

    case Term.Interpolate(Term.Name(p), _, _) if p == "s" || p == "f" || p == "md" =>
      SType.String

    // ── Strict-mode check for Select chains rooted at imported modules ──────
    //
    // The existing `Term.Name` branch flags references to undefined top-level
    // names.  This branch extends the same check to dotted selects of any
    // depth — a chain `a.b.c.…` walks the qualifier recursively and emits
    // exactly one diagnostic at the deepest point where resolution breaks.
    //
    // Conservative — we only flag chains whose root is a known imported
    // module (alias key in `importedInterfaces`).  This avoids false-
    // positives for:
    //   - method calls on values whose type is `Any` (interpreter intrinsics);
    //   - builtins like `1.toString` / `"x".length` where the typer knows
    //     the receiver type but not its method set;
    //   - same-module values (`val x = 42; x.toString`);
    //   - deep selects whose sub-namespaces were not populated by the
    //     interface extractor (see `ExportedSymbol.nested` — extractor work
    //     is deferred; chains that hit a sub-namespace with empty `nested`
    //     fall back to permissive).
    case t @ Term.Select(_, Term.Name(_)) if strict =>
      checkSelectStrict(t, scope)
      // Strict mode keeps the previous "always Any" return so the
      // qualifier doesn't get walked twice (once by `checkSelectStrict`
      // via `resolveQualifier`, once by [[inferSelectType]] via
      // `inferType`) and double-record the same diagnostic.  Field
      // inference is purely for `.scim` granularity, which runs in
      // permissive mode.
      SType.Any

    case t: Term.Select           => inferSelectType(t, scope)

    // `new Foo(...)` / `new Foo[T](...)` — infer the named-type result
    // from the `Init`'s type annotation.  Without this, every constructor
    // call produced `SType.Any` in the .scim, hiding the most common
    // case-class result.  Tier-5 .scim granularity push (Open question #1).
    case Term.New(init) => typeAnnotToSType(init.tpe)
    case Term.NewAnonymous(tpl) =>
      tpl.inits.headOption.map(init => typeAnnotToSType(init.tpe))
        .getOrElse(SType.Any)

    // `(x: A, y: B) => body` — if every parameter has a type annotation
    // AND the body's type is known, produce `SType.Function(...)`.
    // Otherwise fall back to `Any`.  Tier-5 .scim granularity push.
    case Term.Function.After_4_6_0(paramClause, body) =>
      val params = paramClause.values
      val paramsOpt =
        if params.forall(_.decltpe.isDefined) then
          Some(params.map(p => typeAnnotToSType(p.decltpe.get)))
        else None
      paramsOpt match
        case Some(paramTypes) =>
          // Body inference under a scope where params are bound.  Avoids
          // false-positive strict diagnostics for the params themselves.
          val bodyScope = scope.child("<lambda>")
          params.zip(paramTypes).foreach { (p, pt) =>
            bodyScope.define(Symbol(p.name.value, pt, SymbolKind.Val))
          }
          val retType = inferType(body, bodyScope)
          SType.Function(paramTypes, retType)
        case None => SType.Any

    case _: Term.PartialFunction  => SType.Any
    case _: Term.AnonymousFunction => SType.Any

    // `scrutinee match { case … => body }` — LUB across arms.  Same
    // policy as if/else (line ~458): when every arm body infers to
    // the same type, propagate it; any divergence collapses to Any.
    // Pattern-introduced bindings (e.g. `case Some(x) => x + 1`) are
    // NOT bound in the arm-body scope yet — we walk with the outer
    // scope, so references to pattern vars surface as `Any` and we
    // stay sound by not pretending we know more.
    case t: Term.Match =>
      val armTypes = t.casesBlock.cases.map(c => inferType(c.body, scope))
      armTypes.headOption match
        case None      => SType.Any
        case Some(t0)  => if armTypes.forall(_ == t0) then t0 else SType.Any

    case _                        => SType.Any

  /** Best-effort field-access inference for `qual.field`:
   *
   *  - When `qual` infers to `SType.Named(className, _)` AND `className`
   *    has a recorded constructor-field table (populated by the
   *    Defn.Class arm of [[checkStat]]), return the field's declared
   *    type.  Picks up `someFoo.x` for `case class Foo(x: Int)` (and
   *    plain classes with `val`/`var` params; scalameta carries
   *    `decltpe` on all of them).
   *  - Otherwise return `SType.Any`.  We don't speculate on stdlib
   *    method shapes (`.length` on `String`, `.map` on `List`, etc.)
   *    — those would need a full method table the typer doesn't yet
   *    own.  Reserved for a later push.
   *
   *  Tier-5 .scim granularity push (Open question #1). */
  private def inferSelectType(t: Term.Select, scope: Scope): SType =
    val qualType = inferType(t.qual, scope)
    val fieldName = t.name.value
    qualType match
      case SType.Named(className, _) =>
        classFields.get(className).flatMap(_.collectFirst {
          case (n, fty) if n == fieldName => fty
        }).getOrElse(SType.Any)
      case _ => SType.Any

  /** Check that `actual` is assignable to `expected`, emitting an error if not. */
  private def checkAssignable(actual: SType, expected: SType, pos: scala.meta.Position): Unit =
    if expected == SType.Any || actual == SType.Any || expected == SType.Nothing then ()
    else if isCompatible(actual, expected) then ()
    else
      errors += TypeError(
        s"Type mismatch: expected ${expected.show}, found ${actual.show}",
        posToSpan(pos)
      )

  private def isCompatible(actual: SType, expected: SType): Boolean =
    // Opaque-type sealing: if the expected type is an opaque type and the
    // actual type is the underlying primitive, they are NOT compatible outside
    // the defining scope.  We require the user to go through the companion
    // constructor (`UserId.apply`, `UserId("alice")`) so the types align.
    val expectedIsOpaque = expected match
      case SType.Named(name, Nil) => opaqueTypes.contains(name)
      case _ => false
    if expectedIsOpaque && actual != expected && actual != SType.Any && actual != SType.Nothing then
      return false
    actual == expected ||
    actual == SType.Nothing ||
    expected == SType.Any   ||
    (actual == SType.Null && isNullable(expected)) ||
    // Numeric widening: Int literal is valid where Long or Double is expected
    (actual == SType.Int  && (expected == SType.Long || expected == SType.Double)) ||
    (actual == SType.Long && expected == SType.Double) ||
    // Union subtyping: `A <: A | B` — actual is assignable to a union if it
    // is assignable to at least one member of the union.
    (expected match
      case SType.Union(alts) => alts.exists(isCompatible(actual, _))
      case _                 => false) ||
    // Union on the actual side: `A | B <: C` — every alternative must be
    // assignable to `expected` (conservative; handles `Union <: Union`).
    (actual match
      case SType.Union(alts) => alts.forall(isCompatible(_, expected))
      case _                 => false)

  private def isNullable(t: SType): Boolean = t match
    case SType.Named(_, _) => true
    case _                 => false

  /** Basic type rules for infix operators. */
  private def checkBinaryOp(lhs: SType, op: String, rhs: SType, pos: scala.meta.Position): SType =
    (lhs, op, rhs) match
      // Numeric arithmetic — same type
      case (SType.Int,    op2, SType.Int)    if isArith(op2) => SType.Int
      case (SType.Long,   op2, SType.Long)   if isArith(op2) => SType.Long
      case (SType.Double, op2, SType.Double) if isArith(op2) => SType.Double
      // Widening
      case (SType.Int,    op2, SType.Double) if isArith(op2) => SType.Double
      case (SType.Double, op2, SType.Int)    if isArith(op2) => SType.Double
      case (SType.Long,   op2, SType.Int)    if isArith(op2) => SType.Long
      case (SType.Int,    op2, SType.Long)   if isArith(op2) => SType.Long
      // String concat
      case (SType.String, "+", _)  => SType.String
      case (_, "+", SType.String)  => SType.String
      // Comparison — always Boolean
      case (_, "==" | "!=" | "<" | ">" | "<=" | ">=", _) => SType.Boolean
      // Logical
      case (SType.Boolean, "&&" | "||", SType.Boolean) => SType.Boolean
      // Type mismatch for arithmetic on known incompatible types
      case (l, op2, r)
          if isArith(op2) && l != SType.Any && r != SType.Any &&
             !isNumericOrString(l) =>
        errors += TypeError(
          s"Operator '$op2' is not applicable to ${l.show}",
          posToSpan(pos)
        )
        SType.Error(s"${l.show} $op2 ${r.show}")
      case _ => SType.Any

  /** Returns true if `name` appears directly as a `Named` inside `t` at any depth.
   *  Used to detect trivially recursive type aliases like `type A = List[A]`. */
  private def isDirectlyRecursive(name: String, t: SType): Boolean = t match
    case SType.Named(`name`, _) => true
    case SType.Named(_, args)   => args.exists(isDirectlyRecursive(name, _))
    case SType.Function(ps, r)  => ps.exists(isDirectlyRecursive(name, _)) || isDirectlyRecursive(name, r)
    case SType.Tuple(elems)     => elems.exists(isDirectlyRecursive(name, _))
    case SType.Union(ts)        => ts.exists(isDirectlyRecursive(name, _))
    case SType.Intersection(ts) => ts.exists(isDirectlyRecursive(name, _))
    case _                      => false

  private def isArith(op: String): Boolean = Set("+", "-", "*", "/", "%").contains(op)
  private def isNumericOrString(t: SType): Boolean =
    t == SType.Int || t == SType.Long || t == SType.Double || t == SType.String

  /** Expand a type alias if `name` is registered in `typeAliases`.
   *  For a parameterized alias `type Opt[A] = Option[A]`, `args` holds the
   *  concrete type arguments and we substitute `A → args(0)` in the rhs.
   *  For a simple alias `type UserId = String`, `args` must be empty. */
  private def expandAlias(name: String, args: List[SType]): Option[SType] =
    typeAliases.get(name).map { (params, rhs) =>
      if params.isEmpty then rhs
      else if params.length != args.length then
        // Arity mismatch — record error and return the rhs unexpanded
        errors += TypeError(
          s"Type alias $name expects ${params.length} type parameter(s), got ${args.length}",
          None
        )
        rhs
      else
        // Substitute each param with the corresponding arg.  We use a simple
        // name-based substitution: any `Named(param, Nil)` in the rhs is replaced
        // with the corresponding arg SType.
        val subst: Map[String, SType] = params.zip(args).toMap
        def applySubst(t: SType): SType = t match
          case SType.Named(n, Nil) if subst.contains(n) => subst(n)
          case SType.Named(n, as)  => SType.Named(n, as.map(applySubst))
          case SType.Function(ps, r) => SType.Function(ps.map(applySubst), applySubst(r))
          case SType.Tuple(elems)  => SType.Tuple(elems.map(applySubst))
          case SType.Union(ts)     => SType.Union(ts.map(applySubst))
          case SType.Intersection(ts) => SType.Intersection(ts.map(applySubst))
          case other               => other
        applySubst(rhs)
    }

  /** Convert a scalameta type annotation to our internal SType. */
  private def typeAnnotToSType(tpe: scala.meta.Type): SType = tpe match
    case Type.Name(name) =>
      // Check if this is a known type alias before falling through to primitives.
      expandAlias(name, Nil).getOrElse(primitiveOrNamed(name))
    // Generic application: handle the well-known constructors first so the
    // returned `SType.Named(name, args)` lines up with `SType.list/option/map`
    // — then fall through to a generic `Named(head, args)` for any other
    // user-defined parameterised type (`Set[Int]`, `Vector[A]`, etc.).
    case Type.Apply.After_4_6_0(Type.Name("List"),   argClause) =>
      SType.list(typeAnnotToSType(argClause.values.head))
    case Type.Apply.After_4_6_0(Type.Name("Option"), argClause) =>
      SType.option(typeAnnotToSType(argClause.values.head))
    case Type.Apply.After_4_6_0(Type.Name("Map"), argClause) if argClause.values.length == 2 =>
      SType.map(typeAnnotToSType(argClause.values.head), typeAnnotToSType(argClause.values(1)))
    case Type.Apply.After_4_6_0(Type.Name(other), argClause) =>
      // Check if `other` is a parameterized type alias (e.g. `type Opt[A] = Option[A]`).
      val typeArgs = argClause.values.map(typeAnnotToSType).toList
      expandAlias(other, typeArgs).getOrElse(SType.Named(other, typeArgs))
    case Type.Apply.After_4_6_0(sel: Type.Select, argClause) =>
      SType.Named(showTypePath(sel), argClause.values.map(typeAnnotToSType).toList)
    case Type.Function.After_4_6_0(params, ret) =>
      SType.Function(params.values.map(typeAnnotToSType).toList, typeAnnotToSType(ret))
    case Type.Tuple(elems) => SType.Tuple(elems.map(typeAnnotToSType))
    // Surface-level `A | B` / `A & B` — scalameta exposes them as a
    // `Type.ApplyInfix` whose op is the literal `|` / `&` token.  We
    // flatten chains so `A | B | C` becomes a single `SType.Union(A, B, C)`
    // matching the canonical form the parser produces from `.scim`
    // round-trips.
    case Type.ApplyInfix(lhs, Type.Name("|"), rhs) =>
      val l = typeAnnotToSType(lhs)
      val r = typeAnnotToSType(rhs)
      val left  = l match { case SType.Union(xs) => xs; case other => List(other) }
      val right = r match { case SType.Union(xs) => xs; case other => List(other) }
      SType.Union(left ++ right)
    case Type.ApplyInfix(lhs, Type.Name("&"), rhs) =>
      val l = typeAnnotToSType(lhs)
      val r = typeAnnotToSType(rhs)
      val left  = l match { case SType.Intersection(xs) => xs; case other => List(other) }
      val right = r match { case SType.Intersection(xs) => xs; case other => List(other) }
      SType.Intersection(left ++ right)
    // Qualified type names: `scala.collection.Map`, `std.actors.Spec` etc.
    // Preserve the dotted path verbatim so `SType.show` / `parseSType` can
    // round-trip the interface entry.
    case sel: Type.Select => SType.Named(showTypePath(sel), Nil)
    case _                => SType.Any

  private def primitiveOrNamed(name: String): SType = name match
    case "Int"     => SType.Int
    case "Long"    => SType.Long
    case "Double"  => SType.Double
    case "Float"   => SType.Double
    case "String"  => SType.String
    case "Boolean" => SType.Boolean
    case "Char"    => SType.Char
    case "Unit"    => SType.Unit
    case "Any"     => SType.Any
    case "Nothing" => SType.Nothing
    case "Null"    => SType.Null
    case other     => SType.Named(other, Nil)

  /** Render a `Type.Select` / `Type.Name` chain as a dotted path string. */
  private def showTypePath(t: scala.meta.Type): String = t match
    case Type.Name(n)         => n
    case Type.Select(qual, n) => s"${showTermPath(qual)}.${n.value}"
    case _                    => t.toString

  private def showTermPath(t: scala.meta.Term): String = t match
    case Term.Name(n)         => n
    case Term.Select(qual, n) => s"${showTermPath(qual)}.${n.value}"
    case _                    => t.toString

  /** Should an unresolved identifier be flagged in strict mode?
   *
   *  Operators, placeholders and dotted names are intentionally skipped
   *  (the typer doesn't reliably know about them today).  The remaining
   *  identifiers — bare letters/underscore-led terms — are exactly the
   *  shape that should resolve via the prelude, the module's own defs,
   *  or an imported `.scim` interface.
   */
  private def isFlaggableName(name: String): Boolean =
    if name.isEmpty then false
    else if name == "_" then false
    else if name.contains('.') then false
    else
      val c = name.head
      c.isLetter || c == '_'

  // ── Deep Select-chain resolution for strict mode ────────────────────────
  //
  // Walks a qualifier chain `a.b.c.…` rooted (potentially) at an imported
  // module.  Returns a [[QualResult]] describing where the chain ended up:
  //
  //  - Module / SubNamespace — the chain resolved cleanly; the caller can
  //    use the resulting namespace to validate the final member name.
  //  - SubOpaque             — chain resolved cleanly so far, but the next
  //                            namespace level has no recorded members
  //                            (extractor didn't populate `ExportedSymbol.nested`).
  //                            Treat as permissive: silently accept further
  //                            members.  TODO: lift to a real diagnostic once
  //                            `InterfaceExtractor` records nested members.
  //  - BrokenAt              — chain broke at a known module / sub-namespace
  //                            because `member` is not in its export list.
  //                            The caller emits exactly one diagnostic
  //                            (no cascade for deeper selects).
  //  - UndefinedRoot         — root name is not in any scope and not an
  //                            imported module alias — equivalent to a bare
  //                            undefined-name reference.
  //  - NotAnalysable         — root is a local val or an unrecognised shape
  //                            (e.g. literal receiver); strict mode skips.
  private enum QualResult:
    case Module(name: String, iface: scalascript.ir.ModuleInterface)
    case Sub(path: String, members: List[scalascript.ir.ExportedSymbol])
    case SubOpaque(path: String)
    case BrokenAt(qualPath: String, missing: String, missingPos: scala.meta.Position)
    case UndefinedRoot(name: String, pos: scala.meta.Position)
    case NotAnalysable

  /** Step from one namespace level to the next.  `parentPath` is the
   *  dotted path covering the qualifier so far (for diagnostics).  Looks
   *  up `member` in `members`; on hit, branches on whether the hit
   *  carries `nested` (deeper analysis is possible) or is a leaf
   *  (deeper selects fall through to permissive). */
  private def stepNamespace(
      parentPath: String,
      members:    List[scalascript.ir.ExportedSymbol],
      member:     String,
      memberPos:  scala.meta.Position
  ): QualResult =
    val newPath = s"$parentPath.$member"
    members.find(_.name == member) match
      case None       => QualResult.BrokenAt(parentPath, member, memberPos)
      case Some(hit)  =>
        if hit.nested.nonEmpty then QualResult.Sub(newPath, hit.nested)
        else QualResult.SubOpaque(newPath)

  /** Resolve a qualifier term (the LHS of a Select) to a [[QualResult]]. */
  private def resolveQualifier(qual: scala.meta.Term, scope: Scope): QualResult =
    qual match
      case Term.Name(qname) =>
        importedInterfaces.get(qname) match
          case Some(iface) =>
            QualResult.Module(qname, iface)
          case None =>
            if scope.lookup(qname).isDefined then QualResult.NotAnalysable
            else if isFlaggableName(qname) then
              QualResult.UndefinedRoot(qname, qual.pos)
            else QualResult.NotAnalysable

      case Term.Select(inner, Term.Name(member)) =>
        resolveQualifier(inner, scope) match
          case QualResult.Module(name, iface) =>
            // Look up `member` in this module's exports.  The exports
            // are flat today, but a sub-namespace entry (kind == "object")
            // may carry a `nested` list for deeper analysis.
            val exports = iface.exports ++ iface.externDefs
            exports.find(_.name == member) match
              case None      => QualResult.BrokenAt(name, member, qual.pos)
              case Some(hit) =>
                val newPath = s"$name.$member"
                if hit.nested.nonEmpty then QualResult.Sub(newPath, hit.nested)
                else QualResult.SubOpaque(newPath)

          case QualResult.Sub(path, members) =>
            stepNamespace(path, members, member, qual.pos)

          // Opaque sub-namespace: we can't introspect deeper, so any
          // further name is permissively accepted (and the result stays
          // opaque so still-deeper selects also pass).
          case QualResult.SubOpaque(path) =>
            QualResult.SubOpaque(s"$path.$member")

          // Already broken — keep the original break point so we report
          // it exactly once at the top of the chain.  Don't cascade.
          case b: QualResult.BrokenAt    => b
          case u: QualResult.UndefinedRoot => u
          case QualResult.NotAnalysable  => QualResult.NotAnalysable

      case _ => QualResult.NotAnalysable

  /** Strict-mode entry point for a Select term.  Emits at most one
   *  diagnostic; never cascades.  Idempotent: re-checking the same term
   *  twice would record the same diagnostic twice, but the typer visits
   *  each tree node exactly once. */
  private def checkSelectStrict(t: Term.Select, scope: Scope): Unit =
    val memberName = t.name.value
    resolveQualifier(t.qual, scope) match
      case QualResult.Module(name, iface) =>
        // 2-level base case: q.m where q is an imported module.
        val exported = iface.exports.iterator.map(_.name).toSet ++
                       iface.externDefs.iterator.map(_.name).toSet
        if !exported.contains(memberName) then
          errors += TypeError(
            s"$name has no member $memberName",
            posToSpan(t.pos)
          )

      case QualResult.Sub(path, members) =>
        if !members.exists(_.name == memberName) then
          errors += TypeError(
            s"$path has no member $memberName",
            posToSpan(t.pos)
          )

      // Opaque sub-namespace: silently accept (permissive fallback for
      // the deep-Select case until `InterfaceExtractor` records nested
      // members).
      case QualResult.SubOpaque(_) => ()

      case QualResult.BrokenAt(qualPath, missing, pos) =>
        errors += TypeError(
          s"$qualPath has no member $missing",
          posToSpan(pos)
        )

      case QualResult.UndefinedRoot(name, pos) =>
        errors += TypeError(
          s"Reference to undefined name: $name",
          posToSpan(pos)
        )

      case QualResult.NotAnalysable => ()

  private def posToSpan(pos: scala.meta.Position): Option[Span] =
    if pos.isEmpty then None
    else Some(Span(
      scalascript.ast.Position(pos.startLine, pos.startColumn, pos.start),
      scalascript.ast.Position(pos.endLine, pos.endColumn, pos.end)
    ))

case class TypeError(msg: String, span: Option[Span]):
  def show: String = span match
    case Some(s) => s"$s: $msg"
    case None    => msg

// ─── Summary of a single definition found in a code block ────────

case class DefSummary(name: String, kind: SymbolKind, tpe: SType, paramTypes: List[SType]):
  def show: String =
    val kindStr = kind.toString.toLowerCase
    s"$kindStr $name: ${tpe.show}"

// ─── Typed IR ─────────────────────────────────────────────────────

case class TypedModule(
  name: String,
  version: String,
  sections: List[TypedSection],
  errors: List[TypeError]
):
  def hasErrors: Boolean = errors.nonEmpty
  def show: String =
    val sb = StringBuilder()
    sb ++= s"module $name v$version\n"
    if errors.nonEmpty then
      sb ++= s"Errors (${errors.length}):\n"
      errors.foreach(e => sb ++= s"  - ${e.show}\n")
      sb ++= "\n"
    sections.foreach(s => sb ++= s.show(1))
    sb.toString

case class TypedSection(
  name: String,
  level: Int,
  definitions: List[TypedDef],
  subsections: List[TypedSection]
):
  def show(indent: Int): String =
    val prefix = "  " * indent
    val sb = StringBuilder()
    sb ++= s"$prefix${"#" * level} $name\n"
    definitions.foreach {
      case TypedDef.CodeBlock(lang, ok, defs) =>
        val status = if ok then "OK" else if lang.isEmpty then "untyped" else "PARSE ERROR"
        sb ++= s"$prefix  [$lang: $status]\n"
        defs.foreach(d => sb ++= s"$prefix    ${d.show}\n")
      case TypedDef.Import(path, bindings) =>
        sb ++= s"$prefix  [import $path -> ${bindings.mkString(", ")}]\n"
    }
    subsections.foreach(s => sb ++= s.show(indent + 1))
    sb.toString

enum TypedDef:
  case CodeBlock(lang: String, parsed: Boolean, defs: List[DefSummary])
  case Import(path: String, bindings: List[String])

/** Snapshot of the typer's mutable state after processing a single top-level
 *  section.  Stored alongside the [[TypedSection]] result so that
 *  [[Typer.typeCheckIncremental]] can skip re-typing unchanged sections on
 *  subsequent runs.
 *
 *  @param sectionHash  SHA-256 hex digest of the section's source content
 *                      (heading text + concatenated code-block sources).
 *                      Used to detect whether the section has changed between
 *                      two parse runs.
 *  @param typedSection the typed IR produced for this section on the previous run.
 *  @param typeAliases  snapshot of `Typer.typeAliases` immediately after typing
 *                      this section (i.e. accumulated up to and including it).
 *  @param opaqueTypes  snapshot of `Typer.opaqueTypes` after this section.
 *  @param errors       type errors emitted while typing this section.  Restored
 *                      into `TypedModule.errors` by [[Typer.typeCheckIncremental]]
 *                      when the section is reused from a previous snapshot, so
 *                      that error diagnostics from unchanged sections are not
 *                      silently dropped.
 */
case class SectionSnapshot(
    sectionHash:  String,
    typedSection: TypedSection,
    typeAliases:  Map[String, (List[String], SType)],
    opaqueTypes:  Set[String],
    errors:       List[TypeError] = Nil
)

object SectionSnapshot:
  /** Compute a stable SHA-256 hash for a [[Section]]'s source content.
   *
   *  The hash covers the section's heading text and, in order, the raw
   *  `source` of every [[Content.CodeBlock]] inside `section.content`.
   *  Subsections are intentionally excluded — a subsection change is
   *  detected via its own snapshot entry; the parent section's hash should
   *  not change when only a child changes.
   */
  def hashSection(section: scalascript.ast.Section): String =
    val md  = MessageDigest.getInstance("SHA-256")
    def feed(s: String): Unit = md.update(s.getBytes("UTF-8"))
    feed(section.heading.text)
    section.content.foreach {
      case cb: scalascript.ast.Content.CodeBlock => feed(cb.source)
      case _                                     => ()
    }
    md.digest().map("%02x".format(_)).mkString

object Typer:
  def typeCheck(module: Module): TypedModule = Typer().typeCheck(module)

  /** Type-check a module with pre-compiled interface scopes for its imports.
   *
   *  `interfaces` is a map of import alias → `ModuleInterface` loaded from
   *  pre-compiled `.scim` artifacts.  Names exported by those interfaces are
   *  available in the module's type-checking scope without re-parsing source.
   *
   *  v2.0 / Stage 4.
   */
  def typeCheckWithInterfaces(
      module:     Module,
      interfaces: Map[String, scalascript.ir.ModuleInterface]
  ): TypedModule = Typer(interfaces).typeCheck(module)

  /** Strict variant of `typeCheckWithInterfaces`.
   *
   *  When `strict = true`, references to identifiers that resolve to
   *  nothing (not in the consumer's own defs, not in any imported `.scim`
   *  interface, and not in the builtin prelude) emit a `TypeError` rather
   *  than silently returning `SType.Any`.  Used by `ssc check-with-iface`.
   *
   *  v2.0 — typer strict mode (undefined-name diagnostics).
   */
  def typeCheckWithInterfaces(
      module:     Module,
      interfaces: Map[String, scalascript.ir.ModuleInterface],
      strict:     Boolean
  ): TypedModule = Typer(interfaces, strict).typeCheck(module)

  /** Strict variant for callers that don't have any imported interfaces.
   *
   *  v2.0 — typer strict mode (undefined-name diagnostics).
   */
  def typeCheckStrict(module: Module): TypedModule =
    Typer(Map.empty, strict = true).typeCheck(module)

  /** Incremental type-check — companion factory (no imported interfaces, non-strict).
   *
   *  Delegates to [[Typer.typeCheckIncremental]] on a fresh [[Typer]] instance.
   *  The caller is responsible for threading the returned snapshot list into
   *  the next call.
   *
   *  @param module        freshly-parsed [[Module]]
   *  @param prevSnapshots snapshots from the previous run; pass `Nil` to force
   *                       a full re-check (cold start).
   */
  def typeCheckIncrementalModule(
      module:        Module,
      prevSnapshots: List[SectionSnapshot]
  ): (TypedModule, List[SectionSnapshot]) =
    Typer().typeCheckIncremental(module, prevSnapshots)

  /** Incremental type-check with pre-compiled interface scopes (strict mode).
   *
   *  Use this overload in the LSP server so cross-module name resolution is
   *  preserved across incremental runs.
   */
  def typeCheckIncrementalModule(
      module:        Module,
      prevSnapshots: List[SectionSnapshot],
      interfaces:    Map[String, scalascript.ir.ModuleInterface],
      strict:        Boolean
  ): (TypedModule, List[SectionSnapshot]) =
    Typer(interfaces, strict).typeCheckIncremental(module, prevSnapshots)
