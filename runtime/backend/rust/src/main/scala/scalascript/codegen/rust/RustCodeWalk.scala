package scalascript.codegen.rust

import scala.meta as m
import scala.meta.prettyprinters.XtensionSyntax
import scala.meta.transversers.XtensionCollectionLikeUI
import scalascript.ast
import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** Walk an `ast.Module` and emit `src/generated/<crate>.rs`.
 *
 *  Phase R.1.3c is intentionally narrow: only the hello-world shape
 *  is accepted —
 *
 *    `[@main] def name(): Unit = println("string-literal")`
 *
 *  Anything outside that subset returns a `Diagnostic.Generic` so the
 *  caller can convert it into `CompileResult.Failed`.  Each later
 *  R.x slice widens the accepted shapes; the narrow start keeps the
 *  goldens stable while the runtime preamble + main-assembly slices
 *  catch up. */
object RustCodeWalk:

  /** A single rendered `pub fn` extracted from a `Defn.Def`. */
  final case class GeneratedDef(name: String, render: String, isMain: Boolean)

  /** Outcome of walking a module — either a list of diagnostics or
   *  the assembled `src/generated/<crate>.rs` text plus the names of
   *  the defs it contains and the `@main`-annotated entry point, so
   *  the main-assembly slice can stitch a
   *  `fn main() { generated::<crate>::<entry>(); }` shim. */
  final case class WalkResult(
      generated:    String,
      defNames:     List[String],
      mainEntry:    Option[String],
      effectNames:  Set[String]   // effect names used (e.g. "Logger") → drives effects.rs emit
  )

  def walk(
      module:     ast.Module,
      intrinsics: Map[QualifiedName, IntrinsicImpl]
  ): Either[List[Diagnostic], WalkResult] =
    _typeLambdas          = collectTypeLambdaAliases(module)
    val defs              = collectDefs(module)
    _varargDefs = defs.flatMap { d =>
      val ps = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values)
      ps.lastOption match
        case Some(p) if p.decltpe.exists(_.isInstanceOf[m.Type.Repeated]) =>
          Some(d.name.value -> (ps.size - 1))
        case _ => None
    }.toMap
    val enums             = collectEnums(module)
    val traitEnums        = collectSealedTraitEnums(module)
    val standaloneCases   = collectStandaloneCaseClasses(module, traitEnums)
    val rustBlocks        = collectRustBlocks(module)
    val givens            = collectGivens(module)
    _givenNames           = givens.map(_.instanceName).toSet
    val userDefs          = defs.map(_.name.value).toSet
    // Collect names of defs that carry a `T ! EffectName` return type so
    // call sites can thread the `_eff` parameter automatically.
    val effectfulDefs: Set[String] = defs.flatMap(d => defEffectName(d).map(_ => d.name.value)).toSet
    // All user type names (standalone structs + sealed-trait enums) so struct/enum
    // field types that reference another user type map to it, not the i64 default.
    val userTypeNames: Set[String] =
      standaloneCases.map(_.name.value).toSet ++
      traitEnums.map { case SealedTraitEnum(t, _) => t.name.value }.toSet
    val enumRendered =
      (if needsEitherType(module) then List(renderBuiltinEitherEnum()) else Nil) ++
      enums.map(renderEnum) ++
      traitEnums.map { case SealedTraitEnum(t, caseClasses) => renderTraitEnum(t, caseClasses, userTypeNames) }
    val structRendered    = standaloneCases.map(c => renderStruct(c, userTypeNames))
    val (enumErrs, enumOk)     = enumRendered.partitionMap(identity)
    val (structErrs, structOk) = structRendered.partitionMap(identity)
    val ctorMap = enumOk.flatMap(_.ctors).toMap ++
      structOk.map(s => s.structName -> EnumCtor(s.structName, s.fieldNames)).toMap
    // topVals must be collected after ctorMap so enum ctors are resolved correctly.
    // Given instances are injected as `let name = StructName;` bindings.
    val baseTopVals = collectTopVals(module, ctorMap)
    // Populate trait→instance resolver for summon[Trait[A]] (first given wins).
    _summonResolve.clear()
    givens.foreach { g =>
      g.traitName.foreach { tn =>
        if !_summonResolve.contains(tn) then _summonResolve(tn) = g.instanceName
      }
    }
    // Pre-populate _givenInits by doing a dry render so init expressions
    // (including field values for zero-param defs) are available for topVals.
    val ctx0dry = Ctx(intrinsics, userDefs, ctorMap, baseTopVals, "<given>", effectfulDefs)
    givens.foreach(g => renderGiven(g, ctx0dry))
    val givenTopVals: List[(String, String)] = givens.map { g =>
      (g.instanceName, givenInitExpr(g.instanceName))
    }
    val topVals = baseTopVals ++ givenTopVals
    val results = defs.map(renderDef(_, intrinsics, userDefs, ctorMap, topVals, effectfulDefs))
    val (errors, ok) = results.partitionMap(identity)
    val allErrs = enumErrs.flatten ++ structErrs.flatten ++ errors.flatten
    if allErrs.nonEmpty then Left(allErrs)
    else
      val enumBlock = structOk.map(_.render).mkString + enumOk.map(_.render).mkString
      // Render given instances as Rust structs + impls, emitted before the defs.
      val ctx0 = Ctx(intrinsics, userDefs, ctorMap, topVals, "<given>", effectfulDefs)
      val givenBlock = givens.map(g => renderGiven(g, ctx0)).mkString
      val rustVerbatim = rustBlocks.zipWithIndex.map { case (src, i) =>
        s"""// ── rust block ${i + 1} ──
           |$src
           |""".stripMargin
      }.mkString
      val body =
        enumBlock +
        (if enumBlock.nonEmpty || givenBlock.nonEmpty then "\n" else "") +
        givenBlock +
        (if givenBlock.nonEmpty && ok.nonEmpty then "\n" else "") +
        (if ok.isEmpty then "" else ok.map(_.render).mkString("\n")) +
        (if rustBlocks.isEmpty then "" else "\n" + rustVerbatim)
      // Collect effect names from `T ! E` return types AND from runXxx usage.
      val streamEffect  = if usesRunStream(module)  then Set("Stream")  else Set.empty[String]
      val stateEffect   = if usesRunState(module)   then Set("State")   else Set.empty[String]
      val randomEffect  = if usesRunRandom(module)  then Set("Random")  else Set.empty[String]
      val effectNames = defs.flatMap(defEffectName).toSet ++ streamEffect ++ stateEffect ++ randomEffect
      val effectsImport =
        if effectNames.isEmpty then ""
        else "use crate::runtime::effects::*;\n\n"
      val generatedText =
        if body.isEmpty then headerComment
        else headerComment + "\n" + effectsImport + body.dropWhile(_ == '\n')
      Right(WalkResult(
        generated   = generatedText,
        defNames    = ok.map(_.name),
        mainEntry   = ok.find(_.isMain).map(_.name),
        effectNames = effectNames
      ))

  /** Header for the generated file.  Stable so goldens diff cleanly. */
  private val headerComment: String =
    """//! Generated by RustGen.  Do not edit by hand.
      |//!
      |//! One `pub fn` per ScalaScript top-level `def`.  Calls to console
      |//! intrinsics (`println`, `print`) route to `crate::runtime::_*`.
      |//! `rust` fence blocks from the source are appended verbatim.
      |""".stripMargin

  // ── Defn.Given collection (R.6 typeclasses) ──────────────────────────

  private case class GivenInstance(
      instanceName: String,            // e.g. "intSum"
      traitName:    Option[String],    // e.g. Some("Monoid") from `given intSum: Monoid[Int]`
      methods:      List[m.Defn.Def]
  )

  /** Derive the Rust struct name for a given instance: capitalize first char + "Given". */
  private def givenStructName(name: String): String =
    if name.isEmpty then "UnknownGiven"
    else s"${name.head.toUpper}${name.tail}Given"

  /** Collect top-level `given X: T with { defs }` from all code blocks. */
  private def collectGivens(module: ast.Module): List[GivenInstance] =
    module.sections.flatMap(sectionGivens)

  private def sectionGivens(s: ast.Section): List[GivenInstance] =
    s.content.flatMap(contentGivens) ++ s.subsections.flatMap(sectionGivens)

  private def contentGivens(c: ast.Content): List[GivenInstance] = c match
    case ast.Content.CodeBlock(lang, _, Some(node), _, _, _, _)
        if isScalaLang(lang) =>
      node.tree.collect {
        case g: m.Defn.Given if g.templ.body.stats.nonEmpty =>
          // Extract trait name from the first init (e.g. `Monoid[Int]` → "Monoid").
          val traitName: Option[String] = g.templ.inits.headOption.flatMap { init =>
            init.tpe match
              case m.Type.Name(n)                                => Some(n)
              case m.Type.Apply.After_4_6_0(m.Type.Name(n), _)   => Some(n)
              case _                                              => None
          }
          GivenInstance(
            instanceName = g.name.value,
            traitName    = traitName,
            methods      = g.templ.body.stats.collect { case d: m.Defn.Def => d }
          )
      }.toList
    case _ => Nil

  /** Render a given instance as a Rust struct + inherent impl block.
   *  Zero-param defs become struct FIELDS (so `instance.field` works in Rust).
   *  Multi-param defs become impl methods. */
  private def renderGiven(g: GivenInstance, ctx: Ctx): String =
    val sName  = givenStructName(g.instanceName)
    val bodyCtx0 = Ctx(ctx.intrinsics, ctx.userDefs, ctx.ctorMap, ctx.topVals, "<given>", ctx.effectfulDefs)
    // Partition: zero-param defs → fields, others → methods.
    val (zeroDefs, multiDefs) = g.methods.partition { d =>
      d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).isEmpty
    }
    val fields = zeroDefs.map { d =>
      val tRs  = d.decltpe.flatMap(t => mapType(t, d.name.value, ctx.enumNames).toOption).getOrElse("i64")
      val bodyCtx = bodyCtx0.copy(defName = d.name.value)
      val init    = renderTerm(d.body, bodyCtx).getOrElse("Default::default()")
      (d.name.value, tRs, init)
    }
    val fieldDecls = if fields.isEmpty then "" else
      fields.map { case (n, t, _) => s"    pub $n: $t," }.mkString("\n")
    val structDef =
      if fields.isEmpty then s"pub struct $sName;"
      else s"pub struct $sName {\n$fieldDecls\n}"
    val methodRs = multiDefs.map { d =>
      val mName   = d.name.value
      val params  = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values)
      val pList   = params.map { p =>
        val tRs = p.decltpe.flatMap(t => mapType(t, mName, ctx.enumNames).toOption).getOrElse("i64")
        s"${p.name.value}: $tRs"
      }.mkString(", ")
      val retTRs  = d.decltpe.flatMap(t => mapType(t, mName, ctx.enumNames).toOption).getOrElse("i64")
      val bodyCtx = bodyCtx0.copy(defName = mName)
      val bodyRs  = renderTerm(d.body, bodyCtx).getOrElse("unimplemented!()")
      s"    #[allow(dead_code)]\n    pub fn $mName(&self, $pList) -> $retTRs { $bodyRs }"
    }.mkString("\n")
    // Build the struct init expression for the topVal injection.
    val structInit =
      if fields.isEmpty then sName
      else s"$sName { ${fields.map { case (n, _, init) => s"$n: $init" }.mkString(", ")} }"
    // Stash the init string so givenTopVals can use the right expression.
    _givenInits(g.instanceName) = structInit
    val implBlock =
      if multiDefs.isEmpty then ""
      else s"\nimpl $sName {\n$methodRs\n}"
    s"""// ── given instance: ${g.instanceName} ──
       |#[derive(Debug, Clone)]
       |$structDef$implBlock
       |""".stripMargin

  /** Mutable cache: maps given instance name → its Rust constructor expression. */
  private val _givenInits = scala.collection.mutable.Map.empty[String, String]

  /** Returns the struct-init expression for a given instance (populated by renderGiven). */
  private def givenInitExpr(instanceName: String): String =
    _givenInits.getOrElse(instanceName, givenStructName(instanceName))

  /** Mutable cache: trait name → instance name (first wins). Populated during walk(). */
  private val _summonResolve = scala.collection.mutable.Map.empty[String, String]

  /** Resolve `summon[Trait[A]]` → instance name. Returns instance name or None. */
  private def resolveSummon(traitName: String): Option[String] =
    _summonResolve.get(traitName)

  // ── Defn.Def collection ──────────────────────────────────────────────

  /** Top-level defs across every parsed `scalascript`/`ssc`/`scala` block. */
  private def collectDefs(module: ast.Module): List[m.Defn.Def] =
    module.sections.flatMap(sectionDefs)

  private def sectionDefs(s: ast.Section): List[m.Defn.Def] =
    s.content.flatMap(contentDefs) ++ s.subsections.flatMap(sectionDefs)

  private def contentDefs(c: ast.Content): List[m.Defn.Def] = c match
    case ast.Content.CodeBlock(lang, _, Some(node), _, _, _, _)
        if isScalaLang(lang) =>
      node.tree.collect { case d: m.Defn.Def => d }.toList
    case _ => Nil

  private def isScalaLang(lang: String): Boolean =
    val n = lang.toLowerCase
    n == "scalascript" || n == "ssc" || n == "scala"

  // ── Top-level val collection ─────────────────────────────────────────

  /** Collect top-level `val name: T = expr` declarations from scalascript
   *  blocks.  These are emitted as `let name = init;` preamble in every
   *  `Defn.Def` body so that HOF bench fixtures like `val xs: List[Int] =
   *  List(1, 2, 3, …)` are accessible inside `workload()`. */
  private def collectTopVals(module: ast.Module, ctorMap: Map[String, EnumCtor]): List[TopVal] =
    val ctx0 = Ctx(Map.empty, Set.empty, ctorMap, Nil, "<topval>")
    val found = scala.collection.mutable.ListBuffer.empty[TopVal]
    module.sections.foreach(s => sectionTopVals(s, ctx0, found))
    found.toList

  private def sectionTopVals(
      s: ast.Section, ctx: Ctx, found: scala.collection.mutable.ListBuffer[TopVal]
  ): Unit =
    s.content.foreach(c => contentTopVals(c, ctx, found))
    s.subsections.foreach(sub => sectionTopVals(sub, ctx, found))

  private def contentTopVals(
      c: ast.Content, ctx: Ctx, found: scala.collection.mutable.ListBuffer[TopVal]
  ): Unit = c match
    case ast.Content.CodeBlock(lang, _, Some(node), _, _, _, _)
        if isScalaLang(lang) =>
      // Only look at TOP-LEVEL statements — not nested inside Defn.Def bodies.
      // tree.collect traverses the whole tree and would pick up val bindings
      // inside function bodies (e.g. `val r = ...` in a while loop).
      val topStats0: List[m.Tree] = node.tree match
        case m.Source(stats)     => stats.toList
        case m.Term.Block(stats) => stats.toList
        case single              => List(single)
      // Normalize wraps a library module's statements in synthetic namespace
      // `object`s (e.g. `object std { object ui { object theme { … } } }`), so
      // recursively descend into top-level objects to reach their vals.
      def descend(stats: List[m.Tree]): List[m.Tree] = stats.flatMap {
        case obj: m.Defn.Object => descend(obj.templ.body.stats.toList)
        case other              => List(other)
      }
      val topStats: List[m.Tree] = descend(topStats0)
      topStats.collect { case v: m.Defn.Val => v }.foreach { v =>
        v.pats match
          case List(m.Pat.Var(m.Term.Name(name))) =>
            renderTerm(v.rhs, ctx).foreach(init => found += ((name, init)))
          case _ => ()
      }
    case _ => ()

  // ── rust block collection (verbatim passthrough) ─────────────────────

  /** Raw Rust source from `rust` fence blocks — concatenated into the
   *  emitted `src/generated/<crate>.rs` after the ScalaScript-derived
   *  defs.  Cross-block typing (Rust → SS) is a future slice; today
   *  the rust source just contributes top-level items that any SS
   *  emit can reach via `pub fn` / `pub struct` lookups. */
  private def collectRustBlocks(module: ast.Module): List[String] =
    module.sections.flatMap(sectionRustBlocks)

  private def sectionRustBlocks(s: ast.Section): List[String] =
    s.content.flatMap(contentRustBlocks) ++ s.subsections.flatMap(sectionRustBlocks)

  private def contentRustBlocks(c: ast.Content): List[String] = c match
    case ast.Content.CodeBlock(lang, source, _, _, _, _, _)
        if lang.equalsIgnoreCase("rust") =>
      List(source)
    case _ => Nil

  // ── Enum collection + rendering ──────────────────────────────────────

  /** Collect every `Defn.Enum` reachable from a `scalascript` / `ssc` /
   *  `scala` code block.  Order is source order. */
  private def collectEnums(module: ast.Module): List[m.Defn.Enum] =
    module.sections.flatMap(sectionEnums)

  private def sectionEnums(s: ast.Section): List[m.Defn.Enum] =
    s.content.flatMap(contentEnums) ++ s.subsections.flatMap(sectionEnums)

  private def contentEnums(c: ast.Content): List[m.Defn.Enum] = c match
    case ast.Content.CodeBlock(lang, _, Some(node), _, _, _, _)
        if isScalaLang(lang) =>
      node.tree.collect { case d: m.Defn.Enum => d }.toList
    case _ => Nil

  /** Type-lambda aliases: `type Pair = [A] =>> (A, A)` → `"Pair" -> (List("A"),
   *  Type.Tuple([A, A]))`. Set per-compile; consulted by `mapType` to BETA-REDUCE
   *  an application `Pair[Long]` (substitute the params in the body) so rust gets a
   *  real type. Type lambdas are surface-only on the other backends (erased), so
   *  this reduction is rust's equivalent. */
  private var _typeLambdas: Map[String, (List[String], m.Type)] = Map.empty

  /** Stack of placeholder counters for `_`-lambda desugaring (mirrors JsGen):
   *  each `Term.AnonymousFunction` pushes a 0, each `Term.Placeholder` rendered
   *  inside it increments the top and emits `__p<i>`. */
  private var _phCounters: List[Int] = Nil

  /** Defs with a trailing vararg param (`def f(a)(xs: T*)`): name → number of
   *  fixed params before the vararg.  At a call site the trailing args (after the
   *  fixed ones) are wrapped into a single `vec![…]` (Rust has no varargs). */
  private var _varargDefs: Map[String, Int] = Map.empty

  /** `given` instance names — these ARE emitted as named Rust bindings, so a
   *  reference uses the name (unlike a plain top-level `val`, which is inlined). */
  private var _givenNames: Set[String] = Set.empty

  private def collectTypeLambdaAliases(module: ast.Module): Map[String, (List[String], m.Type)] =
    def fromContent(c: ast.Content): List[(String, (List[String], m.Type))] = c match
      case ast.Content.CodeBlock(lang, _, Some(node), _, _, _, _) if isScalaLang(lang) =>
        node.tree.collect {
          // Native: `type Pair = [A] =>> body`.
          case m.Defn.Type.After_4_6_0(_, m.Type.Name(n), _, m.Type.Lambda.After_4_6_0(tparams, body), _) =>
            n -> (tparams.values.map(_.name.value), body)
        }.toList ++
        // Placeholder: `type IntKey = Map[Int, _]` — an alias RHS that gets applied
        // is a type lambda (no existentials in ScalaScript), so each `_` is a param.
        node.tree.collect {
          case m.Defn.Type.After_4_6_0(_, m.Type.Name(n), _, rhs, _)
              if !rhs.isInstanceOf[m.Type.Lambda] =>
            desugarPlaceholders(rhs).map(n -> _)
        }.flatten.toList
      case _ => Nil
    def fromSection(s: ast.Section): List[(String, (List[String], m.Type))] =
      s.content.flatMap(fromContent) ++ s.subsections.flatMap(fromSection)
    module.sections.flatMap(fromSection).toMap

  /** Desugar an alias RHS containing `_` placeholders into a type lambda:
   *  `Map[Int, _]` → `(List("A"), Map[Int, A])`; `Either[_, _]` → `(["A","B"],
   *  Either[A, B])`, binding left→right in source order. Returns `None` if there
   *  are no placeholders. */
  private def desugarPlaceholders(t: m.Type): Option[(List[String], m.Type)] =
    val params = scala.collection.mutable.ListBuffer.empty[String]
    def go(tt: m.Type): m.Type = tt match
      case _: m.Type.Wildcard =>
        val p = ('A' + params.length).toChar.toString
        params += p
        m.Type.Name(p)
      case m.Type.Apply.After_4_6_0(tn, ac) =>
        m.Type.Apply(tn, m.Type.ArgClause(ac.values.toList.map(go)))
      case m.Type.Tuple(elems) => m.Type.Tuple(elems.toList.map(go))
      case other => other
    val body = go(t)
    if params.isEmpty then None else Some((params.toList, body))

  /** Substitute type-param names with concrete types throughout a `Type` tree
   *  (β-reduction of a type lambda body). Covers the shapes `mapType` accepts
   *  (Name / Tuple / generic Apply); rarer shapes pass through unsubstituted. */
  private def substType(t: m.Type, subst: Map[String, m.Type]): m.Type = t match
    case m.Type.Name(n) if subst.contains(n) => subst(n)
    case m.Type.Apply.After_4_6_0(tn, argClause) =>
      m.Type.Apply(tn, m.Type.ArgClause(argClause.values.toList.map(substType(_, subst))))
    case m.Type.Tuple(elems) => m.Type.Tuple(elems.toList.map(substType(_, subst)))
    case other => other

  /** A `sealed trait` + its associated case classes. */
  private case class SealedTraitEnum(
      traitDef:    m.Defn.Trait,
      caseClasses: List[m.Defn.Class]
  )

  /** Standalone `case class`es (not extending any sealed trait) render as Rust `pub struct`. */
  private def collectStandaloneCaseClasses(
      module: ast.Module,
      traitEnums: List[SealedTraitEnum]
  ): List[m.Defn.Class] =
    val traitCaseNames = traitEnums.flatMap(_.caseClasses.map(_.name.value)).toSet
    module.sections.flatMap(sectionClasses)
      .filter(c => isCaseClass(c) && !traitCaseNames.contains(c.name.value))

  private def renderStruct(
      c: m.Defn.Class, typeNames: Set[String]
  ): Either[List[Diagnostic], GeneratedStruct] =
    val name   = c.name.value
    val params = c.ctor.paramClauses.flatMap(_.values).toList
    val fieldRendered = params.map { p =>
      p.decltpe match
        case Some(t) => mapType(t, s"struct $name", typeNames).map(r => (p.name.value, r))
        case None    => Left(List(unsupported(
          s"struct `$name` field `${p.name.value}` has no type annotation"
        )))
    }
    val (errs, ok) = fieldRendered.partitionMap(identity)
    if errs.nonEmpty then Left(errs.flatten)
    else
      val fields = ok.map((n, t) => s"    pub $n: $t").mkString(",\n")
      val allCopy = ok.forall((_, t) => Set("i64", "f64", "bool").contains(t))
      val derives = if allCopy then "Debug, Clone, Copy" else "Debug, Clone"
      val render =
        s"""#[allow(dead_code)]
           |#[derive($derives)]
           |pub struct $name {
           |$fields,
           |}
           |""".stripMargin
      Right(GeneratedStruct(render, name, ok.map(_._1)))

  /** Collect each `sealed trait` and all `case class` extending it in source order. */
  private def collectSealedTraitEnums(module: ast.Module): List[SealedTraitEnum] =
    val traits  = module.sections.flatMap(sectionTraits)
    val classes = module.sections.flatMap(sectionClasses)
    traits
      .map { t =>
        SealedTraitEnum(
          t,
          classes.filter(c => isCaseClass(c) && caseClassExtendsTrait(c, t.name.value))
        )
      }
      .filter(_.caseClasses.nonEmpty)

  private def sectionTraits(s: ast.Section): List[m.Defn.Trait] =
    s.content.flatMap(contentTraits) ++ s.subsections.flatMap(sectionTraits)

  private def contentTraits(c: ast.Content): List[m.Defn.Trait] = c match
    case ast.Content.CodeBlock(lang, _, Some(node), _, _, _, _)
        if isScalaLang(lang) =>
      node.tree.collect {
        case d: m.Defn.Trait if isSealedTrait(d) => d
      }.toList
    case _ => Nil

  private def sectionClasses(s: ast.Section): List[m.Defn.Class] =
    s.content.flatMap(contentClasses) ++ s.subsections.flatMap(sectionClasses)

  private def contentClasses(c: ast.Content): List[m.Defn.Class] = c match
    case ast.Content.CodeBlock(lang, _, Some(node), _, _, _, _)
        if isScalaLang(lang) =>
      node.tree.collect { case d: m.Defn.Class => d }.toList
    case _ => Nil

  private def isSealedTrait(t: m.Defn.Trait): Boolean =
    t.mods.exists {
      case m.Mod.Sealed() => true
      case _              => false
    }

  private def isCaseClass(c: m.Defn.Class): Boolean =
    c.mods.exists {
      case m.Mod.Case() => true
      case _            => false
    }

  private def caseClassExtendsTrait(c: m.Defn.Class, traitName: String): Boolean =
    c.templ.inits.exists(init => parentTypeName(init).contains(traitName))

  private def parentTypeName(init: m.Init): Option[String] = init.tpe match
    case m.Type.Name(n) => Some(n)
    case t: m.Type.Apply =>
      t.tpe match
        case m.Type.Name(n) => Some(n)
        case _              => None
    case _ => None

  /** Lower a Scala 3 `enum E { case A(x: T); … }` to a Rust enum:
   *
   *  ```rust
   *  pub enum E {
   *      A { x: T },
   *      B { y: U },
   *      …
   *  }
   *  ```
   *
   *  Struct-style variants (named fields) keep the field names so the
   *  pattern-match emit can destructure by name. */
  private def renderEnum(e: m.Defn.Enum): Either[List[Diagnostic], GeneratedEnum] =
    val enumName = e.name.value
    val cases    = e.templ.body.stats.collect { case c: m.Defn.EnumCase => c }
    if cases.isEmpty then
      Left(List(unsupported(s"enum `$enumName` has no `case` variants")))
    else
      val rendered = cases.map(c => renderEnumCase(enumName, c))
      val (errs, ok) = rendered.partitionMap(identity)
      if errs.nonEmpty then Left(errs.flatten)
      else
        val variantText = ok.map(_._1).mkString(",\n")
        val src =
          s"""#[allow(dead_code)]
             |#[derive(Debug, Clone)]
             |pub enum $enumName {
             |${indent(variantText)},
             |}
             |""".stripMargin
        val ctors = ok.map(_._2)
        Right(GeneratedEnum(render = src, ctors = ctors))

  /** Lower a `sealed trait` + case-class ADT shape to the same Rust enum
   *  representation as a Scala `enum`.
   *
   *  For example:
   *
   *  ```scala
   *  sealed trait Shape
   *  case class Circle(r: Double) extends Shape
   *  case class Square(s: Double) extends Shape
   *  ```
   *
   *  becomes:
   *
   *  ```rust
   *  pub enum Shape {
   *      Circle { r: f64 },
   *      Square { s: f64 },
   *  }
   *  ```
   */
  private def renderTraitEnum(
      t: m.Defn.Trait,
      caseClasses: List[m.Defn.Class],
      typeNames: Set[String]
  ): Either[List[Diagnostic], GeneratedEnum] =
    val enumName = t.name.value
    val rendered = caseClasses.map(c => renderClassCtor(enumName, c, typeNames))
    val (errs, ok) = rendered.partitionMap(identity)
    if errs.nonEmpty then Left(errs.flatten)
    else
      if ok.isEmpty then
        Left(List(unsupported(s"sealed trait `$enumName` has no `case class` extensions")))
      else
        val variantText = ok.map(_._1).mkString(",\n")
        val src =
          s"""#[allow(dead_code)]
             |#[derive(Debug, Clone)]
             |pub enum $enumName {
             |${indent(variantText)},
             |}
             |""".stripMargin
        val ctors = ok.map(_._2)
        Right(GeneratedEnum(render = src, ctors = ctors))

  /** Fallback `Either` algebraic data type used for `Either[L, R]` lowering.
   *  Added once per crate only when a source term/type references Either. */
  private def renderBuiltinEitherEnum(): Either[List[Diagnostic], GeneratedEnum] =
    Right(GeneratedEnum(
      render =
        """#[allow(dead_code)]
          |#[derive(Debug, Clone)]
          |pub enum Either<L, R> {
          |    Left(L),
          |    Right(R),
          |}
          |""".stripMargin,
      ctors = List(
        ("Left", EnumCtor("Either", Nil)),
        ("Right", EnumCtor("Either", Nil))
      )
    ))

  /** Render one `case class` constructor into a Rust struct-style enum
   *  variant. Returns variant text + ctor metadata for `ctorMap`. */
  private def renderClassCtor(
      enumName: String, c: m.Defn.Class, typeNames: Set[String]
  ): Either[List[Diagnostic], (String, (String, EnumCtor))] =
    val ctor   = c.name.value
    val params = c.ctor.paramClauses.flatMap(_.values).toList
    val fieldRendered = params.map { p =>
      p.decltpe match
        case Some(t) => mapType(t, s"enum $enumName.$ctor", typeNames).map { r =>
          // A field whose type IS the enum itself is direct recursion → `Box` it so
          // the enum has a finite size (Vec<T>/Option<T> already go through the heap).
          if r == enumName then (p.name.value, s"Box<$r>", true)
          else (p.name.value, r, false)
        }
        case None    => Left(List(unsupported(
          s"enum `$enumName.$ctor` parameter `${p.name.value}` has no type annotation"
        )))
    }
    val (errs, ok) = fieldRendered.partitionMap(identity)
    if errs.nonEmpty then Left(errs.flatten)
    else
      val body   =
        if ok.isEmpty then ""
        else " { " + ok.map((n, t, _) => s"$n: $t").mkString(", ") + " }"
      val variant = s"$ctor$body"
      val boxed   = ok.collect { case (n, _, true) => n }.toSet
      Right((variant, (ctor, EnumCtor(enumName, ok.map(_._1), boxed))))

  /** Render one `case Ctor(field1: T1, …)` into a Rust struct-style
   *  enum variant.  Returns the variant text + the ctor metadata. */
  private def renderEnumCase(
      enumName: String, c: m.Defn.EnumCase
  ): Either[List[Diagnostic], (String, (String, EnumCtor))] =
    val ctor   = c.name.value
    val params = c.ctor.paramClauses.flatMap(_.values).toList
    val fieldRendered = params.map { p =>
      p.decltpe match
        case Some(t) => mapType(t, s"enum $enumName.$ctor").map(r => (p.name.value, r))
        case None    => Left(List(unsupported(
          s"enum `$enumName.$ctor` parameter `${p.name.value}` has no type annotation"
        )))
    }
    val (errs, ok) = fieldRendered.partitionMap(identity)
    if errs.nonEmpty then Left(errs.flatten)
    else
      val fields = ok
      val body   =
        if fields.isEmpty then ""
        else " { " + fields.map((n, t) => s"$n: $t").mkString(", ") + " }"
      val variant = s"$ctor$body"
      Right((variant, (ctor, EnumCtor(enumName, fields.map(_._1)))))

  // ── Per-def rendering ────────────────────────────────────────────────

  /** Rendering context: the set of user-defined fn names in scope, plus
   *  the constructor-name → enum-name map collected from `Defn.Enum` and
   *  `sealed trait` + case-class ADTs. */
  /** Top-level val binding: (name, rendered Rust init expression).
   *  Injected as `let name = init;` at the top of every Defn.Def body. */
  private type TopVal = (String, String)

  private case class Ctx(
      intrinsics:   Map[QualifiedName, IntrinsicImpl],
      userDefs:     Set[String],
      ctorMap:      Map[String, EnumCtor],
      topVals:      List[TopVal],
      defName:      String,
      effectfulDefs: Set[String] = Set.empty,  // defs that carry `T ! E` return type
      // collection-rust-array: local val/var names bound to an indexable-seq ctor in this def.
      // `localSeqs` = all (Array/Vector/List → `vec![…]`, O(1)-indexed), so `a(i)` lowers to
      // `a[(i) as usize]`; `localArrays` ⊆ localSeqs are mutable `Array`s, bound `let mut` so
      // `a(i) = x` (→ `a[(i) as usize] = x`) is allowed. Computed by a per-def pre-pass.
      localSeqs:    Set[String] = Set.empty,
      localArrays:  Set[String] = Set.empty,
      // Local val/var names known to hold a String (so `a + b` over them is String concat).
      localStrings: Set[String] = Set.empty,
      // Names bound by the enclosing closure(s) (params + pattern binds).  Inside a
      // closure, a non-Copy bare-name arg that is NOT one of these is a *captured*
      // value, so it's cloned at by-value calls to avoid moving it out of an FnMut.
      closureParams: Set[String] = Set.empty
  ):
    def enumNames: Set[String] = ctorMap.values.map(_.enumName).toSet
    @annotation.unused def topValNames: Set[String] = topVals.map(_._1).toSet

  /** Per-ctor record carrying the enum it belongs to and its field
   *  names (in order).  R.2.3 lowers ctor application as
   *  `EnumName::Ctor { field0: arg0, field1: arg1, … }`. */
  private case class EnumCtor(
      enumName:    String,
      fieldNames:  List[String],
      // Fields whose type is the enum itself (direct recursion) — emitted as
      // `Box<EnumName>`, so they're `Box::new(...)`-wrapped at construction and
      // deref'd (`*field`) at a `match` binding.
      boxedFields: Set[String] = Set.empty
  )

  /** A rendered Rust `enum` block + its ctor table. */
  private case class GeneratedEnum(
      render: String,
      ctors:  List[(String, EnumCtor)]
  )

  /** A rendered Rust `struct` for a standalone ScalaScript `case class`. */
  private case class GeneratedStruct(
      render:     String,
      structName: String,
      fieldNames: List[String]
  )

  /** Extract a single string argument from `@annotName("...")` in a list of mods.
   *  Returns None when the annotation is absent or has no string arg. */
  private def extractRustAnnotArg(mods: List[m.Mod], annotName: String): Option[String] =
    mods.collectFirst {
      case m.Mod.Annot(init) if (init.tpe match
        case m.Type.Name(n)                 => n == annotName
        case m.Type.Select(_, m.Type.Name(n)) => n == annotName
        case _                              => false) =>
        init.argClauses.headOption.flatMap(_.values.collectFirst { case m.Lit.String(s) => s })
    }.flatten

  private def isExternBody(body: m.Term): Boolean = body match
    case m.Term.Name("__extern__") => true
    case _ => false

  private def renderDef(
      d: m.Defn.Def,
      intrinsics:    Map[QualifiedName, IntrinsicImpl],
      userDefs:      Set[String],
      ctorMap:       Map[String, EnumCtor],
      topVals:       List[TopVal],
      effectfulDefs: Set[String]
  ): Either[List[Diagnostic], GeneratedDef] =
    val name    = d.name.value
    // backend-blocks-p6: @rust("expr") on an extern def emits the expression inline.
    // extern defs without @rust are skipped (no Rust-side implementation needed).
    if isExternBody(d.body) then
      extractRustAnnotArg(d.mods, "rust") match
        case Some(expr) =>
          val pNames  = extractParamNames(d)
          val body    = pNames.zipWithIndex.foldLeft(expr) { case (e, (n, i)) => e.replace(s"$$$i", n) }
          val ctx0    = Ctx(intrinsics, userDefs, ctorMap, topVals, name, effectfulDefs)
          for
            params <- renderParams(d, ctx0, None)
            ret    <- renderReturnType(d, ctx0)
          yield
            val sig = if ret.isEmpty then s"pub fn ${rustIdent(name)}($params)"
                      else               s"pub fn ${rustIdent(name)}($params) -> $ret"
            GeneratedDef(name = name, render = s"$sig { $body }\n", isMain = false)
        case None =>
          Right(GeneratedDef(name = name, render = "", isMain = false))
    else
      val (lseqs, larrays) = collectLocalSeqs(d.body)
      val lstrings = collectLocalStrings(d.body)
      val ctx     = Ctx(intrinsics, userDefs, ctorMap, topVals, name, effectfulDefs, lseqs, larrays, lstrings)
      val effName = defEffectName(d)
      val pNames  = extractParamNames(d)
      val useTCO  = pNames.nonEmpty && hasTailCallPath(name, pNames.size, d.body)
      for
        params  <- if useTCO then renderMutParams(d, ctx, effName)
                   else            renderParams(d, ctx, effName)
        ret     <- renderReturnType(d, ctx)
        bodyRs  <- if useTCO then renderTCOBody(name, pNames, d.body, ctx, isUnit = ret.isEmpty)
                   else            renderBody(d.body, ctx, isUnit = ret.isEmpty)
      yield
        val signature = if ret.isEmpty then s"pub fn ${rustIdent(name)}($params)"
                        else                s"pub fn ${rustIdent(name)}($params) -> $ret"
        val topValPreamble =
          if ctx.topVals.isEmpty then ""
          else ctx.topVals.map { case (n, init) => s"let $n = $init;" }.mkString("\n") + "\n"
        val src =
          s"""$signature {
             |${indent(topValPreamble + bodyRs)}
             |}
             |""".stripMargin
        GeneratedDef(name = name, render = src, isMain = hasMainAnnotation(d))

  /** Extract just the parameter names from a def (all parameter groups). */
  private def extractParamNames(d: m.Defn.Def): List[String] =
    d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value)

  /** collection-rust-array: scan a def body for local `val/var x = Array(...)/Vector(...)/List(...)`
   *  bindings. Returns (allIndexableSeqLocals, mutableArrayLocals). A local seq lowers `x(i)` to
   *  `x[(i) as usize]`; a mutable array additionally needs a `let mut` binding so `x(i)=v` stores. */
  private def collectLocalSeqs(body: m.Term): (Set[String], Set[String]) =
    val seqs   = scala.collection.mutable.Set.empty[String]
    val arrays = scala.collection.mutable.Set.empty[String]
    def seqCtor(rhs: m.Term): Option[Boolean] = rhs match  // Some(isArray) iff a seq ctor
      case m.Term.Apply.After_4_6_0(fn, _) =>
        val nm = fn match
          case m.Term.Name(n) => n
          case m.Term.ApplyType.After_4_6_0(m.Term.Name(n), _) => n
          case _ => ""
        if nm == "Array" then Some(true)
        else if nm == "Vector" || nm == "List" then Some(false)
        else None
      case _ => None
    def record(n: String, rhs: m.Term): Unit =
      seqCtor(rhs).foreach { isArr => seqs += n; if isArr then arrays += n }
    def walk(t: m.Tree): Unit =
      t match
        case v: m.Defn.Val => v.pats match
          case List(m.Pat.Var(m.Term.Name(n))) => record(n, v.rhs)
          case _                               => ()
        case v: m.Defn.Var => v.pats match
          case List(m.Pat.Var(m.Term.Name(n))) => record(n, v.body)
          case _                               => ()
        case _ => ()
      t.children.foreach(walk)
    walk(body)
    (seqs.toSet, arrays.toSet)

  /** Local `val`/`var` names bound to a String-valued rhs (literal, `s"…"`,
   *  `.toString`/`.trim`/`.mkString`, an `if` whose branches are strings, a `+`
   *  with a string operand, or another known string val).  Used so a `a + b`
   *  over two such vars lowers to `format!` (String concat) not numeric `+`. */
  private def collectLocalStrings(body: m.Term): Set[String] =
    val strs = scala.collection.mutable.Set.empty[String]
    def isStr(rhs: m.Term): Boolean = rhs match
      case m.Lit.String(_)                            => true
      case m.Term.Interpolate(m.Term.Name("s"), _, _) => true
      case m.Term.Select(_, m.Term.Name("toString" | "trim" | "mkString")) => true
      case m.Term.Apply.After_4_6_0(m.Term.Select(_, m.Term.Name("toString" | "trim" | "mkString")), _) => true
      case m.Term.Name(n)                             => strs.contains(n)
      case ifx: m.Term.If                             => isStr(ifx.thenp) || isStr(ifx.elsep)
      case m.Term.ApplyInfix.After_4_6_0(l, m.Term.Name("+"), _, args) =>
        isStr(l) || args.values.headOption.exists(isStr)
      case m.Term.Block(stats)                        =>
        stats.lastOption.collect { case t: m.Term => isStr(t) }.getOrElse(false)
      case _                                          => false
    def walk(t: m.Tree): Unit =
      t match
        case v: m.Defn.Val => v.pats match
          case List(m.Pat.Var(m.Term.Name(n))) => if isStr(v.rhs) then strs += n
          case _                               => ()
        case v: m.Defn.Var => v.pats match
          case List(m.Pat.Var(m.Term.Name(n))) => if isStr(v.body) then strs += n
          case _                               => ()
        case _ => ()
      t.children.foreach(walk)
    walk(body)
    strs.toSet

  /** Flatten a curried apply chain `base(a1)(a2)…` into `(base, a1 ++ a2 ++ …)`
   *  in source order, so a multi-group call lowers to a single Rust call matching
   *  the flattened def signature. Returns None for a non-Apply term. */
  private def flattenCurried(t: m.Term): Option[(m.Term, List[m.Term])] = t match
    case m.Term.Apply.After_4_6_0(inner: m.Term.Apply, args) =>
      flattenCurried(inner).map { case (base, prev) => (base, prev ++ args.values.toList) }
    case m.Term.Apply.After_4_6_0(base, args) =>
      Some((base, args.values.toList))
    case _ => None

  /** Check if a term in tail-position is a direct self-call to `defName` with `paramCount` args.
   *  Returns `Some(args)` on match, `None` otherwise. */
  private def isSelfTailCall(defName: String, paramCount: Int, term: m.Term): Option[List[m.Term]] = term match
    case m.Term.Apply.After_4_6_0(m.Term.Name(n), args)
        if n == defName && args.values.size == paramCount =>
      Some(args.values.toList)
    case m.Term.Block(stats) => stats.lastOption.flatMap {
      case t: m.Term => isSelfTailCall(defName, paramCount, t)
      case _         => None
    }
    case _ => None

  /** Check if the def body contains any direct self-tail-call (for deciding whether to use TCO). */
  private def hasTailCallPath(defName: String, paramCount: Int, body: m.Term): Boolean = body match
    case ifExpr: m.Term.If =>
      isSelfTailCall(defName, paramCount, ifExpr.thenp).isDefined ||
      isSelfTailCall(defName, paramCount, ifExpr.elsep).isDefined ||
      hasTailCallPath(defName, paramCount, ifExpr.thenp) ||
      hasTailCallPath(defName, paramCount, ifExpr.elsep)
    case m.Term.Block(stats) => stats.lastOption.exists {
      case t: m.Term =>
        isSelfTailCall(defName, paramCount, t).isDefined ||
        hasTailCallPath(defName, paramCount, t)
      case _ => false
    }
    case _ => isSelfTailCall(defName, paramCount, body).isDefined

  /** Render params with `mut` prefix for TCO defs (loop reassigns them). */
  private def renderMutParams(
      d: m.Defn.Def, ctx: Ctx, effName: Option[String]
  ): Either[List[Diagnostic], String] =
    // Curried / using groups flatten into one param list (see renderParams).
    val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values)
    val rendered = params.map { p =>
      p.decltpe match
        case Some(t) => mapType(t, ctx.defName, ctx.enumNames).map(r => s"mut ${p.name.value}: $r")
        case None    => Left(List(unsupported(
          s"def `${ctx.defName}` parameter `${p.name.value}` has no type annotation"
        )))
    }
    val (errs, ok) = rendered.partitionMap(identity)
    if errs.nonEmpty then Left(errs.flatten)
    else
      val effParam = effName.map(n => s"_eff: &mut impl ${n}Effect").toList
      Right((ok ++ effParam).mkString(", "))

  /** Render a tail-recursive function body as a Rust `loop { ... }`.
   *  Self-tail-calls become param reassignments (using temps for ordering safety).
   *  All other returns get an explicit `return`. */
  private def renderTCOBody(
      defName:   String,
      paramNames: List[String],
      body:      m.Term,
      ctx:       Ctx,
      isUnit:    Boolean
  ): Either[List[Diagnostic], String] =
    renderTCOTerm(defName, paramNames, body, ctx, isUnit = isUnit)
      .map(inner => s"loop {\n${indent(inner)}\n}")

  /** Recursively render a term for use inside a TCO loop.
   *  - Self-tail-calls → param assignments (no `return`).
   *  - Non-recursive leaves → `return expr;` (for non-unit), or `expr;` (unit).
   *  - `if/else` branches are rendered recursively so each branch does the right thing. */
  private def renderTCOTerm(
      defName:    String,
      paramNames: List[String],
      term:       m.Term,
      ctx:        Ctx,
      isUnit:     Boolean
  ): Either[List[Diagnostic], String] =
    isSelfTailCall(defName, paramNames.size, term) match
      case Some(newArgs) =>
        // Emit temp bindings then param assignments to avoid update-order issues.
        val argResults = newArgs.zipWithIndex.map { case (a, i) =>
          renderTerm(a, ctx).map(r => (s"_tco_$i", r))
        }
        val (errs, rendered) = argResults.partitionMap(identity)
        if errs.nonEmpty then Left(errs.flatten)
        else
          val lets   = rendered.map { case (tmp, rhs) => s"let $tmp = $rhs;" }.mkString("\n")
          val assigns = paramNames.zipWithIndex.map { case (p, i) => s"$p = _tco_$i;" }.mkString("\n")
          Right(if lets.isEmpty then assigns else s"$lets\n$assigns")
      case None =>
        term match
          case ifExpr: m.Term.If =>
            for
              condRs  <- renderTerm(ifExpr.cond, ctx)
              thenRs  <- renderTCOTerm(defName, paramNames, ifExpr.thenp, ctx, isUnit)
              elseRs  <- renderTCOTerm(defName, paramNames, ifExpr.elsep, ctx, isUnit)
            yield s"if $condRs {\n${indent(thenRs)}\n} else {\n${indent(elseRs)}\n}"
          case m.Term.Block(stats) if stats.nonEmpty =>
            val (initStats, lastStat) = (stats.init, stats.last)
            val initResults = initStats.map(renderStmt(_, ctx))
            val (initErrs, initOk) = initResults.partitionMap(identity)
            if initErrs.nonEmpty then Left(initErrs.flatten)
            else
              lastStat match
                case t: m.Term =>
                  renderTCOTerm(defName, paramNames, t, ctx, isUnit).map { lastRs =>
                    (initOk :+ lastRs).filter(_.nonEmpty).mkString("\n")
                  }
                case other =>
                  renderStmt(other, ctx).map { lastRs =>
                    (initOk :+ lastRs).filter(_.nonEmpty).mkString("\n")
                  }
          case leaf =>
            // Non-recursive leaf — for non-unit returns, emit `return expr`.
            renderTerm(leaf, ctx).map(r =>
              if isUnit then s"$r;" else s"return $r;"
            )

  /** A def is the entry point when it carries an `@main` annotation. */
  private def hasMainAnnotation(d: m.Defn.Def): Boolean =
    d.mods.exists {
      case m.Mod.Annot(m.Init.After_4_6_0(m.Type.Name("main"), _, _)) => true
      case _                                                          => false
    }

  /** Map a Scala type name to its Rust equivalent.  R.2 supports
   *  primitive types and (when `enumNames` is non-empty) any
   *  user-defined enum name passed through verbatim. */
  private def mapType(
      t: m.Type, defName: String, enumNames: Set[String] = Set.empty
  ): Either[List[Diagnostic], String] = t match
    case m.Type.Name("Unit")    => Right("")          // empty → no `-> T` clause
    case m.Type.Name("Boolean") => Right("bool")
    case m.Type.Name("Int")     => Right("i64")       // ScalaScript `Int` widens to 64-bit
    case m.Type.Name("Long")    => Right("i64")
    case m.Type.Name("Double")  => Right("f64")
    case m.Type.Name("Float")   => Right("f64")
    case m.Type.Name("String")  => Right("String")
    // std/ui opaque types map to their Rust runtime representations.
    case m.Type.Name("View")         => Right("crate::runtime::ui::View")
    case m.Type.Name("EventHandler") => Right("crate::value::Value")
    case m.Type.Name("Signal")       => Right("crate::value::Value")
    case m.Type.Apply.After_4_6_0(m.Type.Name("Signal"), _) => Right("crate::value::Value")
    // `Any` (untyped/erased values) → the boxed `Value`.
    case m.Type.Name("Any")          => Right("crate::value::Value")
    case m.Type.Name(n) if enumNames.contains(n) => Right(n)
    // Repeated parameter `T*` → Rust `Vec<T>` (the call site wraps the trailing
    // varargs into `vec![…]` — see the vararg-aware Apply handling).
    case m.Type.Repeated(tpe) =>
      mapType(tpe, defName, enumNames).map(t => s"Vec<$t>")
    // Type-lambda application `Pair[Long]` where `Pair = [A] =>> body` — β-reduce
    // (substitute the params with the args) and map the result. A nullary alias
    // `type X = [] =>> body` reduces with no args.
    case m.Type.Apply.After_4_6_0(m.Type.Name(n), argClause)
        if _typeLambdas.contains(n) && _typeLambdas(n)._1.length == argClause.values.size =>
      val (params, body) = _typeLambdas(n)
      val subst = params.zip(argClause.values.toList).toMap
      mapType(substType(body, subst), defName, enumNames)
    // Option[T] lowers to Rust `Option<T>` for bench-friendly semantics.
    case m.Type.Apply.After_4_6_0(m.Type.Name("Option"), argClause)
        if argClause.values.size == 1 =>
      argClause.values.toList match
        case List(inner) =>
          mapType(inner, defName, enumNames).map(innerType => s"Option<$innerType>")
        case _ =>
          Left(List(unsupported(
            s"def `$defName` uses invalid `Option` application; expected one type arg"
          )))
    // Either[L, R] lowers to `Either<L, R>`.
    case m.Type.Apply.After_4_6_0(m.Type.Name("Either"), argClause)
        if argClause.values.size == 2 =>
      argClause.values.toList match
        case List(l, r) =>
          for
            leftRs <- mapType(l, defName, enumNames)
            rightRs <- mapType(r, defName, enumNames)
          yield s"Either<$leftRs, $rightRs>"
        case _ =>
          Left(List(unsupported(
            s"def `$defName` has `Either` with ${argClause.values.size} type args; expected two"
          )))
    // Map[K, V] lowers to `std::collections::HashMap<K, V>`.
    case m.Type.Apply.After_4_6_0(m.Type.Name("Map"), argClause)
        if argClause.values.size == 2 =>
      argClause.values.toList match
        case List(k, v) =>
          for
            keyRs <- mapType(k, defName, enumNames)
            valRs <- mapType(v, defName, enumNames)
          yield s"std::collections::HashMap<$keyRs, $valRs>"
        case _ =>
          Left(List(unsupported(
            s"def `$defName` has `Map` with ${argClause.values.size} type args; expected two"
          )))
    case m.Type.Tuple(elems) =>
      val rendered = elems.toList.map(mapType(_, defName, enumNames))
      val (errs, ok) = rendered.partitionMap(identity)
      if errs.nonEmpty then Left(errs.flatten)
      else Right(renderTuple(ok))
    // Scala 3 `A => B` and `(A1, A2, …) => B` lower to `impl Fn(…) -> …`.
    // R.2.4 only renders function types at parameter positions; stored
    // closure values (`Box<dyn Fn>` boxing) land in a later slice.
    case ft: m.Type.Function =>
      val params = ft.paramClause.values.toList
      for
        pRs <- traverseTypes(params, defName, enumNames)
        rRs <- mapType(ft.res, defName, enumNames)
      yield
        if rRs.isEmpty then s"impl Fn(${pRs.mkString(", ")})"
        else                s"impl Fn(${pRs.mkString(", ")}) -> $rRs"
    // R.2.5 — `List[T]` / `Vec[T]` / `Vector[T]` / `Seq[T]` / `IndexedSeq[T]` / `Iterable[T]`
    // all lower to `Vec<T>` (eager immutable sequences; identical in Rust's Vec-backed model).
    case m.Type.Apply.After_4_6_0(m.Type.Name("List" | "Vec" | "Vector" | "Seq" | "IndexedSeq" | "Iterable"), argClause) =>
      argClause.values.toList match
        case List(elem) => mapType(elem, defName, enumNames).map(r => s"Vec<$r>")
        case other      => Left(List(unsupported(
          s"def `$defName` has `List`/`Vec` with ${other.size} type args; expected exactly one"
        )))
    // `T ! EffectName` — strip the effect, return the base type.
    // The effect parameter is threaded separately via `_eff` (see renderDef).
    case m.Type.ApplyInfix(baseType, m.Type.Name("!"), _) =>
      mapType(baseType, defName, enumNames)
    // Unknown simple type name — likely a type parameter (e.g. `A` in `combineAll[A: Monoid]`).
    // Fall back to `i64` (the most common ScalaScript numeric type) so generic functions
    // can be emitted. rustc will reject mis-typed code; the diagnostic is better than
    // a codegen error.
    case m.Type.Name(_) => Right("i64")
    case other =>
      Left(List(unsupported(
        s"def `$defName` uses type `${other.syntax}`; R.2 accepts primitives, enums, function types, tuple, and List/Vec"
      )))

  /** Map a list of types, accumulating diagnostics. */
  private def traverseTypes(
      ts: List[m.Type], defName: String, enumNames: Set[String]
  ): Either[List[Diagnostic], List[String]] =
    val rendered = ts.map(t => mapType(t, defName, enumNames))
    val (errs, ok) = rendered.partitionMap(identity)
    if errs.nonEmpty then Left(errs.flatten) else Right(ok)

  private def renderParams(
      d: m.Defn.Def, ctx: Ctx, effName: Option[String]
  ): Either[List[Diagnostic], String] =
    // Multiple parameter groups (curried `def f(a)(b)` / `(using ev)` typeclass
    // evidence) flatten into a single Rust fn param list; the matching curried
    // CALL is flattened the same way in renderTerm.
    val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values)
    val rendered = params.map { p =>
      p.decltpe match
        case Some(t) => mapType(t, ctx.defName, ctx.enumNames).map(r => s"${p.name.value}: $r")
        case None    => Left(List(unsupported(
          s"def `${ctx.defName}` parameter `${p.name.value}` has no type annotation; R.2 requires explicit param types"
        )))
    }
    val (errs, ok) = rendered.partitionMap(identity)
    if errs.nonEmpty then Left(errs.flatten)
    else
      val effParam = effName.map(n => s"_eff: &mut impl ${n}Effect").toList
      Right((ok ++ effParam).mkString(", "))

  /** Extract the effect name from a def's return type if it has `T ! E` form.
   *  Returns `Some("Logger")` for `Int ! Logger`, `None` for plain types. */
  private def defEffectName(d: m.Defn.Def): Option[String] =
    d.decltpe match
      case Some(m.Type.ApplyInfix(_, m.Type.Name("!"), m.Type.Name(effName))) => Some(effName)
      case _ => None

  private def renderReturnType(
      d: m.Defn.Def, ctx: Ctx
  ): Either[List[Diagnostic], String] =
    d.decltpe match
      case None                       => Right("")    // inferred — treat as Unit
      case Some(m.Type.Name("Unit"))  => Right("")
      case Some(other)                => mapType(other, ctx.defName, ctx.enumNames)

  private def renderBody(
      body:   m.Term,
      ctx:    Ctx,
      isUnit: Boolean
  ): Either[List[Diagnostic], String] = body match
    case b: m.Term.Block =>
      // For non-Unit returns the tail term must NOT terminate with `;` —
      // Rust uses block-trailing-expression as the result.
      // For Unit returns we terminate every statement with `;` so the
      // emitted block is purely statement-oriented (matches the R.1
      // golden shape).
      if isUnit then
        val rendered = b.stats.map(renderStmt(_, ctx))
        val (errs, ok) = rendered.partitionMap(identity)
        if errs.nonEmpty then Left(errs.flatten)
        else Right(ok.mkString("\n"))
      else
        val (initStats, tail) = b.stats.splitAt(b.stats.length - 1)
        val initRendered = initStats.map(renderStmt(_, ctx))
        val tailRendered: Either[List[Diagnostic], String] = tail.headOption match
          case Some(t: m.Term) => renderTerm(t, ctx)
          case Some(other)     => Left(List(unsupported(
            s"def `${ctx.defName}` body tail is a non-expression: ${other.productPrefix}"
          )))
          case None            => Right("")
        val (errs1, initOk) = initRendered.partitionMap(identity)
        tailRendered match
          case Left(errs2)   => Left(errs1.flatten ++ errs2)
          case Right(tailRs) =>
            if errs1.nonEmpty then Left(errs1.flatten)
            else
              val parts = (initOk :+ tailRs).filter(_.nonEmpty)
              Right(parts.mkString("\n"))
    case t: m.Term =>
      // Single-expression body — for Unit returns add the trailing `;`
      // so the emitted block matches the statement-oriented R.1 golden.
      // For non-Unit returns the expression IS the return value.
      renderTerm(t, ctx).map(r => if isUnit then s"$r;" else r)

  private def renderStmt(
      stat: m.Stat, ctx: Ctx
  ): Either[List[Diagnostic], String] = stat match
    // `var name: T = init` → `let mut name: T = init;`.  R.2.2 covers
    // single-pat bindings only (no pattern destructuring).
    case v: m.Defn.Var =>
      renderLetBinding(v.pats, v.decltpe, v.body, mutable = true, ctx)
    // `val name: T = init` → `let name: T = init;`.
    case v: m.Defn.Val =>
      renderLetBinding(v.pats, v.decltpe, v.rhs, mutable = false, ctx)
    case t: m.Term => renderTerm(t, ctx).map(_ + ";")
    case other     => Left(List(unsupported(
      s"def `${ctx.defName}` body contains an unsupported statement: ${other.productPrefix}"
    )))

  private def renderTerm(
      t: m.Term, ctx: Ctx
  ): Either[List[Diagnostic], String] = t match
    // Option None constructor — must come before the catch-all Term.Name.
    case m.Term.Name("None") => Right("None")
    // Plain identifier — parameter / in-scope fn name, OR a top-level `val`.
    // Top-level vals aren't emitted as Rust items, so inline their (already-rendered)
    // initializer at the reference site (e.g. `defaultTheme` → `Theme { … }`).
    case m.Term.Name(n) =>
      ctx.topVals.collectFirst { case (vn, init) if vn == n && !_givenNames.contains(n) => init } match
        case Some(init) => Right(init)
        case None       => Right(n)

    // `if (cond) thenp else elsep` — Rust if expression.
    case ifExpr: m.Term.If =>
      for
        c <- renderTerm(ifExpr.cond, ctx)
        t1 <- renderTerm(ifExpr.thenp, ctx)
        e1 <- renderTerm(ifExpr.elsep, ctx)
      yield
        s"if $c { $t1 } else { $e1 }"

    // `while (cond) body` — Rust while loop.  Body always wrapped in
    // a `{ … }` block so multi-stmt bodies stay statement-oriented.
    case w: m.Term.While =>
      for
        c <- renderTerm(w.expr, ctx)
        // Body is Unit-typed by construction.
        b <- renderBody(w.body, ctx, isUnit = true)
      yield
        s"while $c {\n${indent(b)}\n}"

    // `lhs = rhs` — Rust reassignment of a previously declared `var`.
    case a: m.Term.Assign =>
      for
        l <- renderTerm(a.lhs, ctx)
        r <- renderTerm(a.rhs, ctx)
      yield
        s"$l = $r"

    // `subject match { case … => …; … }` — Rust `match` expression.
    case mt: m.Term.Match =>
      renderMatch(mt.expr, mt.casesBlock.cases.toList, ctx)

    // `(params) => body` — Rust closure `move |params| body`.  Optional
    // param types are honoured; missing types defer to Rust's inference.
    case fn: m.Term.Function =>
      renderClosure(fn.paramClause.values.toList, fn.body, ctx)

    // `for x <- xs yield expr` with a single generator → Rust
    // `xs.into_iter().map(|x| expr).collect::<Vec<_>>()`.  Multi-
    // generator / guarded / val-enumerator shapes land later.
    case fy: m.Term.ForYield =>
      renderForYield(fy.enumsBlock.enums.toList, fy.body, ctx)

    // `for x <- xs do body` (statement form, no yield) → Rust `for x in xs { body }`.
    case f: m.Term.For =>
      f.enumsBlock.enums.toList match
        case List(g: m.Enumerator.Generator) =>
          g.pat match
            case m.Pat.Var(m.Term.Name(name)) =>
              for
                xs <- renderTerm(g.rhs, ctx)
                b  <- renderBody(f.body, ctx, isUnit = true)
              yield s"for $name in $xs {\n${indent(b)}\n}"
            case other =>
              Left(List(unsupported(
                s"def `${ctx.defName}` for-do generator pattern `${other.syntax}` is not a plain name"
              )))
        case _ =>
          Left(List(unsupported(
            s"def `${ctx.defName}` for-do has ${f.enumsBlock.enums.size} enumerators; only a single generator is supported"
          )))

    // `(expr: Type)` type ascription — Rust infers types, so emit just the inner
    // expression (the annotation is for the front-end type-checker, not codegen).
    case asc: m.Term.Ascribe =>
      renderTerm(asc.expr, ctx)

    // `xs.size` / `xs.length` / `xs.len` — every shape reads as
    // `xs.len() as i64` (Vec::len returns `usize`).
    case m.Term.Select(qual, m.Term.Name("size" | "length" | "len")) =>
      renderTerm(qual, ctx).map(q => s"($q.len() as i64)")

    // ── P1 Vec method chaining (specs/rust-backend-bench-coverage.md §Gap D+G) ──
    //
    // `xs.foreach(f)` — Rust `for x in xs.iter() { f_body }`
    // `xs.map(f)`     — Rust `xs.iter().map(|p| body).collect::<Vec<_>>()`
    // `xs.filter(f)`  — Rust `xs.iter().cloned().filter(|p| body).collect::<Vec<_>>()`
    // `xs.foldLeft(z)(f)` — two curried applies:
    //       Apply(Select(Apply(Select(xs, "foldLeft"), z), _), f)
    //   → `xs.iter().copied().fold(z, |a, b| body)` for numerics
    //   → `xs.iter().cloned().fold(z, |a, b| body)` for non-numeric
    //
    // All three are pattern-matched as `Term.Apply(Term.Select(...), ...)`.
    //
    // NOTE: these cases sit BEFORE the general Apply catch-all so they fire
    // even when the method name is not in the intrinsic table.

    // `.toList` / `.toSeq` / `.toVector` → a Vec.  `clone().into_iter().collect()` is
    // type-agnostic: identity for a Vec, and turns a HashMap into a `Vec<(K, V)>`.
    // (Range/iterator forms collect to a Vec via the `isRangeExpr`-guarded cases below.)
    case m.Term.Select(qual, m.Term.Name("toList" | "toSeq" | "toVector" | "toIndexedSeq"))
        if !isRangeExpr(qual) =>
      renderTerm(qual, ctx).map(q => s"$q.clone().into_iter().collect::<Vec<_>>()")
    // Numeric coercions — P0 bench fix (specs/rust-backend-bench-coverage.md §Gap A).
    case m.Term.Select(qual, m.Term.Name("toLong")) =>
      renderTerm(qual, ctx).map(q => s"($q as i64)")
    case m.Term.Select(qual, m.Term.Name("toInt")) =>
      renderTerm(qual, ctx).flatMap { q =>
        // ScalaScript `Int` maps to rust `i64` everywhere (mapType line ~800),
        // so `.toInt` must yield an i64 too — otherwise an `as i32` result
        // can't be passed to an `i64` (Int) parameter (E0308).  Truncate to
        // 32-bit for `Int` wraparound semantics, then widen back to i64.
        if isStringToIntExpr(qual) then Right(s"($q.parse::<i64>().unwrap_or(0))")
        else Right(s"($q as i32 as i64)")
      }
    case m.Term.Select(qual, m.Term.Name("toDouble")) =>
      renderTerm(qual, ctx).map(q => s"($q as f64)")
    case m.Term.Select(qual, m.Term.Name("toFloat")) =>
      renderTerm(qual, ctx).map(q => s"($q as f64)")
    case m.Term.Select(qual, m.Term.Name("toString")) =>
      renderTerm(qual, ctx).map(q => s"format!(\"{}\", $q)")
    case m.Term.Select(qual, m.Term.Name("trim")) =>
      renderTerm(qual, ctx).map(q => s"$q.trim().to_string()")
    // ── LazyList → std lazy iterators (Rust iterators are lazy). (lazylist-all-backends.) ──
    // `LazyList.from(n)` → `(n..)` (infinite); `LazyList.range(a,b[,s])`; `LazyList.continually(x)`
    // → `std::iter::repeat(x)`; `LazyList.iterate(s)(f)` → `std::iter::successors(...)`;
    // `LazyList(a,b,c)` → `vec![…].into_iter()`. They are recognised by `isRangeExpr`, so the existing
    // range-chain `.map`/`.filter`/`.toList` handlers + the `.take`/`.drop`/`.sum` ones below apply.
    case m.Term.Apply.After_4_6_0(m.Term.Select(m.Term.Name("LazyList"), m.Term.Name("from")), a)
        if a.values.size == 1 =>
      renderTerm(a.values.head, ctx).map(n => s"($n..)")
    case m.Term.Apply.After_4_6_0(m.Term.Select(m.Term.Name("LazyList"), m.Term.Name("range")), a)
        if a.values.size == 2 =>
      for lo <- renderTerm(a.values.head, ctx); hi <- renderTerm(a.values(1), ctx) yield s"($lo..$hi)"
    case m.Term.Apply.After_4_6_0(m.Term.Select(m.Term.Name("LazyList"), m.Term.Name("continually")), a)
        if a.values.size == 1 =>
      renderTerm(a.values.head, ctx).map(x => s"std::iter::repeat($x)")
    case m.Term.Apply.After_4_6_0(
        m.Term.Apply.After_4_6_0(m.Term.Select(m.Term.Name("LazyList"), m.Term.Name("iterate")), sArgs),
        fArgs) if sArgs.values.size == 1 && fArgs.values.size == 1 =>
      for
        seed <- renderTerm(sArgs.values.head, ctx)
        step <- fArgs.values.head match
          case fn: m.Term.Function =>
            val p = fn.paramClause.values.headOption.map(_.name.value).getOrElse("x")
            renderTerm(fn.body, ctx).map(b => s"|&$p| Some($b)")
          case other => renderTerm(other, ctx).map(f => s"|&x| Some(($f)(x))")
      yield s"std::iter::successors(Some($seed), $step)"
    case m.Term.Apply.After_4_6_0(m.Term.Name("LazyList"), a) =>
      val rs = a.values.map(renderTerm(_, ctx))
      val (errs, ok) = rs.partitionMap(identity)
      if errs.nonEmpty then Left(errs.flatten) else Right(s"vec![${ok.mkString(", ")}].into_iter()")
    // Lazy/force adapters on a range/iterator: take/drop/take_while/skip_while/sum (+ toVector/toArray).
    case m.Term.Apply.After_4_6_0(m.Term.Select(qual, m.Term.Name("take")), a)
        if isRangeExpr(qual) && a.values.size == 1 =>
      for q <- renderTerm(qual, ctx); k <- renderTerm(a.values.head, ctx) yield s"$q.take($k as usize)"
    case m.Term.Apply.After_4_6_0(m.Term.Select(qual, m.Term.Name("drop")), a)
        if isRangeExpr(qual) && a.values.size == 1 =>
      for q <- renderTerm(qual, ctx); k <- renderTerm(a.values.head, ctx) yield s"$q.skip($k as usize)"
    case m.Term.Apply.After_4_6_0(m.Term.Select(qual, m.Term.Name(mw @ ("takeWhile" | "dropWhile"))), a)
        if isRangeExpr(qual) && a.values.size == 1 =>
      val rustMethod = if mw == "takeWhile" then "take_while" else "skip_while"
      for
        q <- renderTerm(qual, ctx)
        f <- a.values.head match
          case fn: m.Term.Function =>
            val p = fn.paramClause.values.headOption.map(_.name.value).getOrElse("x")
            renderTerm(fn.body, ctx).map(b => s"|&$p| { $b }")
          case other => renderTerm(other, ctx).map(f => s"|&x| ($f)(*x)")
      yield s"$q.$rustMethod($f)"
    case m.Term.Select(qual, m.Term.Name("sum")) if isRangeExpr(qual) =>
      renderTerm(qual, ctx).map(q => s"$q.sum::<i64>()")
    case m.Term.Select(qual, m.Term.Name("toVector" | "toArray" | "toSeq")) if isRangeExpr(qual) =>
      renderTerm(qual, ctx).map(q => s"$q.collect::<Vec<_>>()")
    // `.toList` on a Source/range (property access form) — collect to Vec.
    case m.Term.Select(qual, m.Term.Name("toList")) if isRangeExpr(qual) =>
      renderTerm(qual, ctx).map(q => s"$q.collect::<Vec<_>>()")
    // Tuple element access: Scala `t._1` is 1-indexed; Rust tuples are
    // 0-indexed (`t.0`). Map `_N` → `.(N-1)`. (A user struct field literally
    // named `_1` would be rare and is not idiomatic; tuple accessors win.)
    case m.Term.Select(qual, m.Term.Name(field)) if isTupleAccessor(field) =>
      renderTerm(qual, ctx).map(q => s"$q.${field.drop(1).toInt - 1}")
    // Struct / case-class field access: `v.x` → `v.x` in Rust.
    case m.Term.Select(qual, m.Term.Name(field)) =>
      renderTerm(qual, ctx).map(q => s"$q.$field")
    // Either constructors and combinators.
    case m.Term.Apply.After_4_6_0(m.Term.Name("Left"), args)
        if args.values.size == 1 =>
      args.values.headOption match
        case Some(a) => renderTerm(a, ctx).map(v => s"Either::Left($v)")
        case None    => Left(List(unsupported(
          s"def `${ctx.defName}` has invalid `Left` constructor application"
        )))
    case m.Term.Apply.After_4_6_0(m.Term.Name("Right"), args)
        if args.values.size == 1 =>
      args.values.headOption match
        case Some(a) => renderTerm(a, ctx).map(v => s"Either::Right($v)")
        case None    => Left(List(unsupported(
          s"def `${ctx.defName}` has invalid `Right` constructor application"
        )))

    case m.Term.Apply.After_4_6_0(
        m.Term.Select(qual, m.Term.Name("map")),
        args
    ) if isEitherExpr(qual) && args.values.size == 1 =>
      for
        q <- renderTerm(qual, ctx)
        f <- renderTerm(args.values.head, ctx)
      yield s"match $q { Either::Left(v) => Either::Left(v), Either::Right(v) => Either::Right(($f)(v)) }"

    case m.Term.Apply.After_4_6_0(
        m.Term.Select(qual, m.Term.Name("flatMap")),
        args
    ) if isEitherExpr(qual) && args.values.size == 1 =>
      for
        q <- renderTerm(qual, ctx)
        f <- renderTerm(args.values.head, ctx)
      yield s"match $q { Either::Left(v) => Either::Left(v), Either::Right(v) => ($f)(v) }"

    case m.Term.Apply.After_4_6_0(
        m.Term.Apply.After_4_6_0(
          m.Term.Select(qual, m.Term.Name("fold")),
          lArgs
        ),
        rArgs
    ) if isEitherExpr(qual) && lArgs.values.size == 1 && rArgs.values.size == 1 =>
      for
        q <- renderTerm(qual, ctx)
        l <- renderTerm(lArgs.values.head, ctx)
        r <- renderTerm(rArgs.values.head, ctx)
      yield s"match $q { Either::Left(v) => ($l)(v), Either::Right(v) => ($r)(v) }"

    case m.Term.Apply.After_4_6_0(
      m.Term.Select(qual, m.Term.Name("fold")),
      args
    ) if isEitherExpr(qual) && args.values.size == 2 =>
      for
        q <- renderTerm(qual, ctx)
        l <- renderTerm(args.values.head, ctx)
        r <- renderTerm(args.values(1), ctx)
      yield s"match $q { Either::Left(v) => ($l)(v), Either::Right(v) => ($r)(v) }"

    case m.Term.Apply.After_4_6_0(
      m.Term.Select(qual, m.Term.Name("updated")),
      args
    ) if args.values.size == 2 =>
      for
        q <- renderTerm(qual, ctx)
        k <- renderTerm(args.values(0), ctx)
        v <- renderTerm(args.values(1), ctx)
      yield s"{\n    let mut m2 = $q.clone();\n    m2.insert($k, $v);\n    m2\n  }"
    case m.Term.Apply.After_4_6_0(
      m.Term.Select(qual, m.Term.Name("getOrElse")),
      args
    ) if args.values.size == 2 && !isOptionExpr(qual) =>
      for
        q <- renderTerm(qual, ctx)
        k <- renderTerm(args.values(0), ctx)
        d <- renderTerm(args.values(1), ctx)
      yield s"$q.get(&$k).copied().unwrap_or($d)"
    // Partial function `{ case p => body; … }` (e.g. `xs.map { case (k, v) => … }`)
    // → a Rust closure that matches its single argument: `move |__pf| match __pf { … }`.
    case pf: m.Term.PartialFunction =>
      val arms = pf.cases.map { c =>
        // The arm body runs inside the closure: `__pf` + the pattern binders are
        // closure-locals; everything else it touches is captured.
        val bodyCtx = ctx.copy(closureParams = ctx.closureParams ++ patBoundNames(c.pat) + "__pf")
        for
          pat   <- renderPattern(c.pat, ctx)
          guard <- c.cond match
                     case Some(g) => renderTerm(g, bodyCtx).map(gr => s" if $gr")
                     case None    => Right("")
          bod   <- renderTerm(c.body, bodyCtx)
        yield s"$pat$guard => $bod,"
      }
      val (errs, ok) = arms.partitionMap(identity)
      if errs.nonEmpty then Left(errs.flatten)
      else Right(s"move |__pf| match __pf {\n${indent(ok.mkString("\n"))}\n}")

    // Placeholder lambda `_.foo` / `_ + 1` → desugar via a counter stack: push a
    // fresh counter, render the body (each `_` increments it + emits `__p<i>`),
    // then build `move |__p0, …| { body }`.  Mirrors JsGen's stack-based approach.
    case af: m.Term.AnonymousFunction =>
      _phCounters = 0 :: _phCounters
      // Render the body as a closure body: the `__pN` placeholder params aren't known
      // until the body is walked, so flag "inside a closure" with a marker — this turns
      // on capture-cloning (a captured non-Copy like `theme` in `lower(_, theme)` is
      // cloned, not moved out of the resulting `FnMut`).  `__pN` names are excluded from
      // cloning in the arg rule (they're the closure's own params).
      val bodyR = renderTerm(af.body, ctx.copy(closureParams = ctx.closureParams + "__ph"))
      val count = _phCounters.headOption.getOrElse(0)
      _phCounters = _phCounters.drop(1)
      bodyR.map { b =>
        val params = (0 until count).map(i => s"__p$i").mkString(", ")
        s"move |$params| { $b }"
      }

    // `_` placeholder inside an anonymous function → the i-th fresh param.
    case _: m.Term.Placeholder =>
      val i = _phCounters.headOption.getOrElse(0)
      _phCounters = _phCounters match
        case h :: t => (h + 1) :: t
        case Nil    => Nil
      Right(s"__p$i")

    // `try <e>.toInt catch { case _ => fb }` → `<e>.parse().unwrap_or(fb)`.
    // (`.toInt` already lowers to a fallible parse; the catch picks the fallback.
    //  Other try/catch shapes are unsupported — Rust has no exceptions.)
    case m.Term.Try.After_4_9_9(m.Term.Select(qual, m.Term.Name("toInt" | "toLong")), Some(handler), _)
        if handler.cases.size == 1 =>
      for
        q  <- renderTerm(qual, ctx)
        fb <- renderTerm(handler.cases.head.body, ctx)
      yield s"$q.parse::<i64>().unwrap_or($fb)"

    // `(a, b, ...)` — native Rust tuple literal.  Rust requires a
    // trailing comma in `(..., )`, so a 1-tuple emits `(x,)`.
    case m.Term.Tuple(values) =>
      renderTupleElems(values.toList, ctx).map(renderTuple)

    // s"…" interpolation → `format!("…", args)`.
    case m.Term.Interpolate(m.Term.Name("s"), parts, args) =>
      renderStringInterpolation(parts, args, ctx)

    // std/ui `serve(view, port)` — SSR overload (arity 2): render the View and
    // serve it as text/html.  Must bind before the generic intrinsic resolution,
    // which maps `serve` → `_http_serve(port)` (arity 1).
    case m.Term.Apply.After_4_6_0(m.Term.Name("serve"), args) if args.values.size == 2 =>
      for
        v <- renderTerm(args.values(0), ctx)
        p <- renderTerm(args.values(1), ctx)
      yield s"crate::runtime::http::_ui_serve($v, $p)"

    // std/ui `element(tag, attrs, events, children)` — the attrs map is
    // `Map[String, Any]` with mixed value types (String/bool/Value); coerce each
    // attr value to a String via `_ui_attr(...)` so the HashMap is homogeneous.
    // events are EventHandlers (Value) and children are Views — rendered as-is.
    case m.Term.Apply.After_4_6_0(m.Term.Name("element"), args) if args.values.size == 4 =>
      val a = args.values.toList
      for
        tag      <- renderTerm(a(0), ctx)
        attrs    <- renderAttrMap(a(1), ctx)
        events   <- renderTerm(a(2), ctx)
        children <- renderTerm(a(3), ctx)
      yield s"crate::runtime::ui::_ui_element($tag, $attrs, $events, $children)"

    // `xs.mkString(sep)` / `xs.mkString` on a `Vec<String>` → Rust `xs.join(sep)`.
    case m.Term.Apply.After_4_6_0(m.Term.Select(qual, m.Term.Name("mkString")), args) =>
      for
        q   <- renderTerm(qual, ctx)
        sep <- args.values.headOption.map(renderTerm(_, ctx)).getOrElse(Right("\"\".to_string()"))
      yield s"$q.join(($sep).as_str())"

    // ── Vec method chaining via Term.Apply ─────────────────────────────────
    // Matches: xs.foreach(f), xs.map(f), xs.filter(f), xs.foldLeft(z)(f).
    // These bind before the generic Apply so the method name is visible.

    // xs.foreach(f)  → for __x in xs.iter() { f(__x) }
    // The body of the closure is written as a for-loop statement.
    case m.Term.Apply.After_4_6_0(
        m.Term.Select(qual, m.Term.Name("foreach")), args
    ) if args.values.size == 1 =>
      for
        q <- renderTerm(qual, ctx)
        body <- renderVecIterBody(args.values.head, q, ctx, method = "foreach")
      yield body

    // xs.map(f) → xs.iter().cloned().map(move |p| body).collect::<Vec<_>>()
    case m.Term.Apply.After_4_6_0(
        m.Term.Select(qual, m.Term.Name("map")), args
    ) if args.values.size == 1 && !isOptionExpr(qual) && !isRangeExpr(qual) =>
      for
        q <- renderTerm(qual, ctx)
        body <- renderVecIterBody(args.values.head, q, ctx, method = "map")
      yield body

    // Range chains: filter on range/iterator → q.filter(move |&p| { body })
    // Uses |&p| to destructure the &T reference that Iterator::filter passes.
    case m.Term.Apply.After_4_6_0(
        m.Term.Select(qual, m.Term.Name("filter")), args
    ) if isRangeExpr(qual) && args.values.size == 1 =>
      for
        q <- renderTerm(qual, ctx)
        f <- args.values.head match
          case fn2: m.Term.Function =>
            val p = fn2.paramClause.values.headOption.map(_.name.value).getOrElse("x")
            renderTerm(fn2.body, ctx).map(b => s"move |&$p| { $b }")
          case other =>
            renderTerm(other, ctx).map(f => s"|x| ($f)(*x)")
      yield s"$q.filter($f)"

    // xs.filter(f) → xs.iter().cloned().filter(move |p| body).collect::<Vec<_>>()
    // Only for Vec expressions (not range/iterator chains).
    case m.Term.Apply.After_4_6_0(
        m.Term.Select(qual, m.Term.Name("filter")), args
    ) if args.values.size == 1 && !isOptionExpr(qual) && !isRangeExpr(qual) =>
      for
        q <- renderTerm(qual, ctx)
        body <- renderVecIterBody(args.values.head, q, ctx, method = "filter")
      yield body

    // Range chains: map on range/iterator → q.map(f)
    case m.Term.Apply.After_4_6_0(
        m.Term.Select(qual, m.Term.Name("map")),
        args
    ) if isRangeExpr(qual) && args.values.size == 1 =>
      for
        q <- renderTerm(qual, ctx)
        f <- renderTerm(args.values.head, ctx)
      yield s"$q.map($f)"

    // `_tupleConcat(a, b)` — emitted by Normalize from `(a) ++ (b)`.
    // For Rust: flatten both args into a single wider tuple literal.
    case m.Term.Apply.After_4_6_0(m.Term.Name("_tupleConcat"), args) if args.values.size == 2 =>
      val terms = collectTupleConcatFromArgs(args.values(0), args.values(1))
      terms match
        case Some(elems) => renderTupleElems(elems, ctx).map(renderTuple)
        case None => Left(List(unsupported(
          s"def `${ctx.defName}`: _tupleConcat args must be tuple literals for Rust backend"
        )))

    // `summon[Trait[A]]` → resolve to the matching given-instance binding.
    // We resolve by the OUTER trait name (e.g. `Monoid` from `Monoid[Int]`).
    // The instance binding is injected as a topVal `let intSum = IntSumGiven { ... };`,
    // so just emit its name.
    case m.Term.ApplyType.After_4_6_0(m.Term.Name("summon"), argClause) =>
      val traitOpt: Option[String] = argClause.values.toList.headOption.flatMap {
        case m.Type.Name(n)                              => Some(n)
        case m.Type.Apply.After_4_6_0(m.Type.Name(n), _) => Some(n)
        case _                                            => None
      }
      traitOpt.flatMap(resolveSummon) match
        case Some(inst) => Right(inst)
        case None       => Left(List(unsupported(
          s"def `${ctx.defName}`: cannot resolve summon[${argClause.values.headOption.map(_.syntax).getOrElse("?")}] — no matching given instance"
        )))

    // Option-aware method lowering.
    case m.Term.Apply.After_4_6_0(m.Term.Name("Some"), args) if args.values.size == 1 =>
      args.values.headOption match
        case Some(a) => renderTerm(a, ctx).map(v => s"Some($v)")
        case None    => Left(List(unsupported(
          s"def `${ctx.defName}` has invalid `Some` constructor application"
        )))

    case m.Term.Apply.After_4_6_0(
        m.Term.Select(qual, m.Term.Name("map")),
        args
    ) if isOptionExpr(qual) && args.values.size == 1 =>
      for
        q <- renderTerm(qual, ctx)
        f <- renderTerm(args.values.head, ctx)
      yield s"$q.map($f)"

    case m.Term.Apply.After_4_6_0(
        m.Term.Select(qual, m.Term.Name("flatMap")),
        args
    ) if isOptionExpr(qual) && args.values.size == 1 =>
      for
        q <- renderTerm(qual, ctx)
        f <- renderTerm(args.values.head, ctx)
      yield s"$q.and_then($f)"

    case m.Term.Apply.After_4_6_0(
        m.Term.Select(qual, m.Term.Name("getOrElse")),
        args
    ) if isOptionExpr(qual) && args.values.size == 1 =>
      for
        q <- renderTerm(qual, ctx)
        d <- renderTerm(args.values.head, ctx)
      yield s"$q.unwrap_or($d)"

    // xs.foldLeft(z)(f) — outer Apply gives f, inner Apply(Select(xs,foldLeft), [z]) gives z.
    case m.Term.Apply.After_4_6_0(
        m.Term.Apply.After_4_6_0(
          m.Term.Select(qual, m.Term.Name("foldLeft")),
          zArgs
        ),
        fArgs
    ) if zArgs.values.size == 1 && fArgs.values.size == 1 && isRangeExpr(qual) =>
      for
        q  <- renderTerm(qual, ctx)
        z  <- renderTerm(zArgs.values.head, ctx)
        f  <- renderTerm(fArgs.values.head, ctx)
      yield s"$q.fold($z, $f)"

    // xs.foldLeft(z)(f) — outer Apply gives f, inner Apply(Select(xs,foldLeft), [z]) gives z.
    case m.Term.Apply.After_4_6_0(
        m.Term.Apply.After_4_6_0(
          m.Term.Select(qual, m.Term.Name("foldLeft")),
          zArgs
        ),
        fArgs
      ) if zArgs.values.size == 1 && fArgs.values.size == 1 =>
      for
        q  <- renderTerm(qual, ctx)
        z  <- renderTerm(zArgs.values.head, ctx)
        fb <- renderVecIterBody(fArgs.values.head, q, ctx, method = "foldLeft", zero = Some(z))
      yield fb
    // `trim` can appear as zero-arg apply too: `s.trim()`.
    case m.Term.Apply.After_4_6_0(
        m.Term.Select(qual, m.Term.Name("trim")),
        args
    ) if args.values.isEmpty =>
      renderTerm(qual, ctx).map(q => s"$q.trim().to_string()")

    // `(s: String).split(sep, limit)` emits `Vec<String>` with `splitn`.
    // sep must be a &str pattern (not owned String) — render bare literal.
    case m.Term.Apply.After_4_6_0(m.Term.Select(qual, m.Term.Name("split")), args)
        if args.values.size == 2 =>
      for
        q <- renderTerm(qual, ctx)
        sep <- renderStrPatternArg(args.values(0))
        lim <- renderTerm(args.values(1), ctx)
      yield s"$q.splitn($lim as usize, $sep).map(|p| p.to_string()).collect::<Vec<String>>()"

    // `(s: String).split(sep)` emits `Vec<String>`, matching bench expectations.
    // sep must be a &str pattern (not owned String) — render bare literal.
    case m.Term.Apply.After_4_6_0(m.Term.Select(qual, m.Term.Name("split")), args)
        if args.values.size == 1 =>
      for
        q <- renderTerm(qual, ctx)
        sep <- renderStrPatternArg(args.values.head)
      yield s"""$q.split($sep).map(|p| p.to_string()).collect::<Vec<String>>()"""

    // Range methods: `(a until b)` -> Rust range `a..b`, `(a to b)` -> `a..=b`.
    case m.Term.ApplyInfix.After_4_6_0(lhs, m.Term.Name("until"), _, rhs)
        if rhs.values.size == 1 =>
      for
        l <- renderTerm(lhs, ctx)
        r <- renderTerm(rhs.values.head, ctx)
      yield s"($l..$r)"

    case m.Term.ApplyInfix.After_4_6_0(lhs, m.Term.Name("to"), _, rhs)
        if rhs.values.size == 1 =>
      for
        l <- renderTerm(lhs, ctx)
        r <- renderTerm(rhs.values.head, ctx)
      yield s"($l..=$r)"

    // `Source.range(lo, hi)` — inclusive range source (R.6 streams). Lowers to `(lo..=hi)`.
    case m.Term.Apply.After_4_6_0(
        m.Term.Select(m.Term.Name("Source"), m.Term.Name("range")),
        args
    ) if args.values.size == 2 =>
      for
        lo <- renderTerm(args.values.head, ctx)
        hi <- renderTerm(args.values(1), ctx)
      yield s"($lo..=$hi)"

    // `Source.fromList(list)` — source backed by a Vec. Lowers to the list itself
    // so downstream `.map/.filter/.foldLeft` chain naturally.
    case m.Term.Apply.After_4_6_0(
        m.Term.Select(m.Term.Name("Source"), m.Term.Name("fromList")),
        args
    ) if args.values.size == 1 =>
      renderTerm(args.values.head, ctx)

    // `.toList()` (method call form) on a Source/range — collect the iterator into a Vec.
    case m.Term.Apply.After_4_6_0(
        m.Term.Select(qual, m.Term.Name("toList")),
        args
    ) if args.values.isEmpty && isRangeExpr(qual) =>
      renderTerm(qual, ctx).map(q => s"$q.collect::<Vec<_>>()")

    // `Stream.emit(x)` → `_eff.stream_emit(x)` (inside a runStream block).
    case m.Term.Apply.After_4_6_0(
        m.Term.Select(m.Term.Name("Stream"), m.Term.Name("emit")),
        args
    ) if args.values.size == 1 =>
      renderTerm(args.values.head, ctx).map(v => s"_eff.stream_emit($v)")

    // `src.runToList()` → `src.items.clone()` (VecStream field access).
    case m.Term.Apply.After_4_6_0(
        m.Term.Select(qual, m.Term.Name("runToList")),
        args
    ) if args.values.isEmpty =>
      renderTerm(qual, ctx).map(q => s"$q.items.clone()")

    // `runStream { body }` — tagless-final lowering.
    // Introduces a VecStream collector, runs body with it threading _eff,
    // returns a tuple `(stream, ())` matching the `(Source[A], R)` shape.
    case m.Term.Apply.After_4_6_0(
        m.Term.Name("runStream"),
        args
    ) if args.values.size == 1 =>
      val bodyResult: Either[List[Diagnostic], String] = args.values.head match
        case blk: m.Term.Block => renderBody(blk, ctx, isUnit = true)
        case expr              => renderTerm(expr, ctx).map(_ + ";")
      bodyResult.map { body =>
        s"{\n    let mut _eff = VecStream::new();\n    $body\n    (_eff, ())\n}"
      }

    // `runLogger { body }` / `runLoggerJson { body }` / `runLoggerToList { body }` —
    // tagless-final lowering: introduce a NoOpLogger and run body with it.
    case m.Term.Apply.After_4_6_0(
        m.Term.Name(runner),
        args
    ) if runner.startsWith("runLogger") && args.values.size == 1 =>
      val bodyResult: Either[List[Diagnostic], String] = args.values.head match
        case blk: m.Term.Block => renderBody(blk, ctx, isUnit = false)
        case expr              => renderTerm(expr, ctx)
      bodyResult.map { body =>
        s"{\n    let mut _eff = NoOpLogger;\n    $body\n}"
      }

    // `runState(init) { body }` — tagless-final: inject StateHandler with given init value.
    // Returns the body result; final state is discarded (like Haskell evalState).
    case m.Term.Apply.After_4_6_0(
        m.Term.Apply.After_4_6_0(m.Term.Name("runState"), initArgs),
        bodyArgs
    ) if initArgs.values.size == 1 && bodyArgs.values.size == 1 =>
      for
        init <- renderTerm(initArgs.values.head, ctx)
        body <- bodyArgs.values.head match
          case blk: m.Term.Block => renderBody(blk, ctx, isUnit = false)
          case expr              => renderTerm(expr, ctx)
      yield s"{\n    let mut _eff = StateHandler { state: $init };\n    $body\n}"

    // `runRandom(seed) { body }` — tagless-final: inject RandomHandler with given seed.
    case m.Term.Apply.After_4_6_0(
        m.Term.Apply.After_4_6_0(m.Term.Name("runRandom"), seedArgs),
        bodyArgs
    ) if seedArgs.values.size == 1 && bodyArgs.values.size == 1 =>
      for
        seed <- renderTerm(seedArgs.values.head, ctx)
        body <- bodyArgs.values.head match
          case blk: m.Term.Block => renderBody(blk, ctx, isUnit = false)
          case expr              => renderTerm(expr, ctx)
      yield s"{\n    let mut _eff = RandomHandler { seed: $seed as u64 };\n    $body\n}"

    // `State.get()` → `_eff.get_state()`
    case m.Term.Apply.After_4_6_0(
        m.Term.Select(m.Term.Name("State"), m.Term.Name("get")),
        args
    ) if args.values.isEmpty =>
      Right("_eff.get_state()")

    // `State.put(s)` → `_eff.put_state(s)`
    case m.Term.Apply.After_4_6_0(
        m.Term.Select(m.Term.Name("State"), m.Term.Name("put")),
        args
    ) if args.values.size == 1 =>
      renderTerm(args.values.head, ctx).map(s => s"_eff.put_state($s)")

    // `Random.nextInt(bound)` → `_eff.next_int(bound)`
    case m.Term.Apply.After_4_6_0(
        m.Term.Select(m.Term.Name("Random"), m.Term.Name("nextInt")),
        args
    ) if args.values.size == 1 =>
      renderTerm(args.values.head, ctx).map(b => s"_eff.next_int($b)")

    // `Random.nextFloat()` → `_eff.next_float()`
    case m.Term.Apply.After_4_6_0(
        m.Term.Select(m.Term.Name("Random"), m.Term.Name("nextFloat")),
        args
    ) if args.values.isEmpty =>
      Right("_eff.next_float()")

    // Curried call `f(a)(b)…` — the parser nests one `Term.Apply` per param
    // group. Flatten the whole chain into a single-group call so it matches the
    // flattened def signature (renderParams). Method chains (`xs.map(f).filter(g)`)
    // have a `Term.Select` callee and are handled by the cases above, so only a
    // genuine curried call reaches here (callee is itself a `Term.Apply`).
    case m.Term.Apply.After_4_6_0(_: m.Term.Apply, _) =>
      flattenCurried(t) match
        case Some((base, allArgs)) =>
          renderTerm(m.Term.Apply(base, m.Term.ArgClause(allArgs, None)), ctx)
        case None =>
          Left(List(unsupported(
            s"def `${ctx.defName}` has an unsupported curried-call shape: ${t.syntax}"
          )))

    // Named-argument construction `Ctor(field = value, …)` → Rust struct-init
    // `Ctor { field: value, … }` (Rust ignores field order).  Recursive fields are
    // `Box::new`-wrapped.  Mixed positional/named falls through to the generic path.
    case m.Term.Apply.After_4_6_0(m.Term.Name(n), args)
        if ctx.ctorMap.contains(n) && args.values.nonEmpty
        && args.values.forall { case _: m.Term.Assign => true; case _ => false } =>
      val ec = ctx.ctorMap(n)
      val rendered = args.values.toList.map {
        case m.Term.Assign(m.Term.Name(field), value) =>
          renderTerm(value, ctx).map { v =>
            val vv = if ec.boxedFields.contains(field) then s"Box::new($v)" else v
            s"$field: $vv"
          }
        case other =>
          Left(List(unsupported(s"def `${ctx.defName}`: unsupported named-ctor arg ${other.productPrefix}")))
      }
      val (errs, ok) = rendered.partitionMap(identity)
      if errs.nonEmpty then Left(errs.flatten)
      else
        val prefix = if ec.enumName == n then n else s"${ec.enumName}::$n"
        Right(s"$prefix { ${ok.mkString(", ")} }")

    // Application — intrinsic, user-defined fn, or unsupported.
    case m.Term.Apply.After_4_6_0(fn, args) =>
      val callee  = qualifiedName(fn)
      val argList = args.values.map(renderTerm(_, ctx))
      val (errs, renderedArgs0) = argList.partitionMap(identity)
      if errs.nonEmpty then Left(errs.flatten)
      else
        // Clone bare-name args that refer to a topVal — Rust would move them
        // otherwise, breaking calls inside loops.  Skip when callee is a known
        // intrinsic that borrows its args (Borrowed list).  Skip primitives —
        // .clone() on i64 is a no-op but emits a cleaner diff to keep it off.
        val topValNames = ctx.topVals.map(_._1).toSet
        val isPrimLit = renderedArgs0.map(_.matches(raw"-?\d+i64|-?\d+\.\d+f64|true|false"))
        val renderedArgsBase = args.values.toList.zip(renderedArgs0).zip(isPrimLit).map {
          case ((m.Term.Name(n), rendered), false)
              if !n.matches("__p\\d+")
              && (topValNames.contains(n)
                  || (ctx.closureParams.nonEmpty && !ctx.closureParams.contains(n))) =>
            s"$rendered.clone()"
          case ((_, rendered), _) => rendered
        }
        // Vararg call site (`f(a)(xs: T*)`): wrap the trailing args into one `vec![…]`
        // (the curried chain was flattened, so they arrive as plain trailing args).
        val renderedArgs = fn match
          case m.Term.Name(n) if _varargDefs.get(n).exists(_ <= renderedArgsBase.size) =>
            val k = _varargDefs(n)
            renderedArgsBase.take(k) :+ s"vec![${renderedArgsBase.drop(k).mkString(", ")}]"
          case _ => renderedArgsBase
        val joined = renderedArgs.mkString(", ")
        // User-defined struct/enum ctors take priority over stdlib names (e.g. user Vec vs List[Vec]).
        val userCtorName = fn match
          case m.Term.Name(n) if ctx.ctorMap.contains(n) => Some(n)
          case _                                          => None
        // `List(1, 2, 3)` / `Vec(1, 2, 3)` → Rust `vec![1, 2, 3]` unless Vec is a user struct.
        // Vector/Seq/IndexedSeq/Iterable are eager immutable sequences — observably identical to
        // List in Rust's `Vec`-backed model (which is itself O(1)-indexed), so they alias List.
        // collection-rust-array: `Array(...)` is ALSO a `vec![...]` — its mutability comes from the
        // `let mut` binding (collectLocalSeqs marks array locals) + the `a(i)=x` store path below.
        // (LazyList's laziness still needs a distinct runtime → handled via isRangeExpr, not here.)
        // (collection-vector-indexed / collection-rust-array.)
        val isListCtor = userCtorName.isEmpty && (fn match
          case m.Term.Name("List" | "Vec" | "Vector" | "Seq" | "IndexedSeq" | "Iterable" | "Array") => true
          case _                            => false)
        val isMapCtor = fn match
          case m.Term.Name("Map") => true
          case m.Term.ApplyType.After_4_6_0(m.Term.Name("Map"), _) => true
          case _                    => false
        // `seq(i)` indexing — when the callee is a sequence val (a `vec![…]`), Scala's `seq(i)`
        // apply lowers to Rust `seq[i as usize]` (Vec is O(1)-indexed). Covers top-level vals
        // (tracked init) AND local Array/Vector/List vals (collectLocalSeqs). (collection-rust-array.)
        val seqIndexName: Option[String] = fn match
          case m.Term.Name(n) if args.values.size == 1 &&
            (ctx.localSeqs.contains(n) ||
             ctx.topVals.exists { case (vn, init) => vn == n && init.startsWith("vec![") }) => Some(n)
          case _ => None
        if isListCtor then Right(s"vec![$joined]")
        else if isMapCtor then
          if args.values.isEmpty then Right("std::collections::HashMap::new()")
          else
            // `Map(k -> v, …)` → `{ let mut __m = HashMap::new(); __m.insert(k, v); … __m }`
            // (consistent with empty `Map()` → `HashMap::new()`).
            val inserts = args.values.toList.map {
              case m.Term.ApplyInfix.After_4_6_0(k, m.Term.Name("->"), _, vArgs)
                  if vArgs.values.size == 1 =>
                for
                  kr <- renderTerm(k, ctx)
                  vr <- renderTerm(vArgs.values.head, ctx)
                yield s"__m.insert($kr, $vr);"
              case _ =>
                Left(List(unsupported(s"def `${ctx.defName}`: `Map` entry is not `k -> v`")))
            }
            val (errs, ok) = inserts.partitionMap(identity)
            if errs.nonEmpty then Left(errs.flatten)
            else Right(s"{ let mut __m = std::collections::HashMap::new(); ${ok.mkString(" ")} __m }")
        else seqIndexName match
          case Some(n) => Right(s"$n[($joined) as usize]")
          case None    => applyNonListCtor(fn, callee, renderedArgs, joined, ctx)

    // `String + any` / `any + String` — lower to `format!("{}{}", lhs, rhs)`.
    // Triggered when either side is a best-effort string expression.
    case m.Term.ApplyInfix.After_4_6_0(lhs, m.Term.Name("+"), _, args)
        if args.values.size == 1 && {
          def strOp(t: m.Term) = isStringExpr(t) || (t match
            case m.Term.Name(n) => ctx.localStrings.contains(n)
            case _              => false)
          strOp(lhs) || strOp(args.values.head)
        } =>
      for
        l <- renderTerm(lhs, ctx)
        r <- renderTerm(args.values.head, ctx)
      yield s"format!(\"{}{}\", $l, $r)"

    // `a -> b` (Scala's tuple-arrow) → Rust tuple `(a, b)`.  `Map(k -> v, …)`
    // literals already lower to `vec![ <pairs> ]`, so with this each pair
    // becomes a `(K, V)` tuple — e.g. `Map("c" -> "r")` → `vec![("c".to_string(),
    // "r".to_string())]`, matching the attrs surface of the std/ui `element`.
    case m.Term.ApplyInfix.After_4_6_0(lhs, m.Term.Name("->"), _, args)
        if args.values.size == 1 =>
      for
        l <- renderTerm(lhs, ctx)
        r <- renderTerm(args.values.head, ctx)
      yield s"($l, $r)"

    // Infix `++` for tuple literals: flatten tuple-literal concat chains.
    // `(a, b) ++ (c, d) == (a, b, c, d)`, also right-recursive.
    case infix @ m.Term.ApplyInfix.After_4_6_0(lhs, m.Term.Name("++"), _, rargs) =>
      collectTupleConcat(infix) match
        case Some(terms) => renderTupleElems(terms, ctx).map(renderTuple)
        case None if rargs.values.size == 1 =>
          // List/Vec concat `a ++ b` → `[(a), (b)].concat()` (chains nest).
          for
            l <- renderTerm(lhs, ctx)
            r <- renderTerm(rargs.values.head, ctx)
          yield s"[$l, $r].concat()"
        case None => renderInfix(infix, ctx)

    // Infix operators: arithmetic, comparison, boolean.
    case infix @ m.Term.ApplyInfix.After_4_6_0(_, _, _, _) =>
      renderInfix(infix, ctx)

    // Always emit owned `String`s — every user-typed `String` parameter
    // expects an owned value, and the `_println` runtime helper is
    // Display-generic so the extra allocation is harmless.  A future
    // optimisation pass may infer when a borrowed `&str` is enough.
    case m.Lit.String(s)  => Right("\"" + escapeRustString(s) + "\".to_string()")
    case m.Lit.Int(n)     => Right(s"${n}i64")
    case m.Lit.Long(n)    => Right(s"${n}i64")
    case m.Lit.Double(d)  => Right(s"${d}f64")
    case m.Lit.Boolean(b) => Right(b.toString)
    case m.Lit.Unit()     => Right("()")
    // `null` (used as an `Any` sentinel) → the boxed unit value.
    case m.Lit.Null()     => Right("crate::value::Value::Unit")

    // Block `{ stmts; tail }` used as an expression → Rust block expression.
    // (Interpolation splices unwrap single-term blocks via `renderInterpArg`;
    // this covers blocks in argument / match-arm / rhs position.)
    case b: m.Term.Block =>
      renderBody(b, ctx, isUnit = false).map(inner => s"{ $inner }")

    case other =>
      Left(List(unsupported(
        s"def `${ctx.defName}` contains an unsupported expression: ${other.productPrefix} (${other.syntax})"
      )))

  private def renderInfix(
      infix: m.Term.ApplyInfix,
      ctx:   Ctx
  ): Either[List[Diagnostic], String] =
    infix match
      case m.Term.ApplyInfix.After_4_6_0(lhs, m.Term.Name(op), _, args) =>
        val rhsTerms = args.values
        val rendered =
          for
            l <- renderTerm(lhs, ctx)
            rs <- rhsTerms.foldLeft[Either[List[Diagnostic], List[String]]](Right(Nil)) {
              (acc, t) => acc.flatMap(xs => renderTerm(t, ctx).map(xs :+ _))
            }
          yield (l, rs)
        rendered.flatMap { case (l, rs) =>
          mapInfixOp(op, ctx.defName) match
            case Right(rustOp) if rs.size == 1 => Right(s"($l $rustOp ${rs.head})")
            case Right(_)                      => Left(List(unsupported(
              s"def `${ctx.defName}`: infix `$op` with ${rs.size} rhs args"
            )))
            case Left(diags)                   => Left(diags)
        }
      case _ =>
        Left(List(unsupported(
          s"def `${ctx.defName}` contains an unsupported infix expression: ${infix.productPrefix}"
        )))

  // Flatten two tuple terms (possibly nested _tupleConcat chains) into a flat list of elements.
  private def collectTupleConcatFromArgs(lhs: m.Term, rhs: m.Term): Option[List[m.Term]] =
    def flattenTupleArg(t: m.Term): Option[List[m.Term]] = t match
      case m.Term.Tuple(values) => Some(values.toList)
      case m.Term.Apply.After_4_6_0(m.Term.Name("_tupleConcat"), args) if args.values.size == 2 =>
        for
          l <- flattenTupleArg(args.values(0))
          r <- flattenTupleArg(args.values(1))
        yield l ++ r
      case _ => None
    for
      l <- flattenTupleArg(lhs)
      r <- flattenTupleArg(rhs)
    yield l ++ r

  // Render a separator/pattern argument for str::split/splitn as a &str literal.
  // str::split requires Pattern (&str or char), not owned String.
  private def renderStrPatternArg(t: m.Term): Either[List[Diagnostic], String] = t match
    case m.Lit.String(s) => Right("\"" + escapeRustString(s) + "\"")
    case other           => Left(List(unsupported(
      s"split separator must be a string literal, got: ${other.productPrefix}"
    )))

  private def collectTupleConcat(t: m.Term): Option[List[m.Term]] = t match
    case m.Term.Tuple(values) =>
      Some(values.toList)
    // Infix `++` with a single-tuple RHS: `(a, b) ++ tupleTerm`.
    case m.Term.ApplyInfix.After_4_6_0(lhs, m.Term.Name("++"), _, args)
        if args.values.size == 1 =>
      for
        l <- collectTupleConcat(lhs)
        r <- collectTupleConcat(args.values.head)
      yield l ++ r
    // Infix `++` where the RHS is multiple literal args (scalameta parses
    // `(1,2) ++ (3,4)` with two separate rhs args in infix position).
    case m.Term.ApplyInfix.After_4_6_0(lhs, m.Term.Name("++"), _, args)
        if args.values.size > 1 =>
      for
        l <- collectTupleConcat(lhs)
      yield l ++ args.values.toList
    case _ => None

  private def renderTupleElems(
      terms: List[m.Term], ctx: Ctx
  ): Either[List[Diagnostic], List[String]] =
    val rendered = terms.map(renderTerm(_, ctx))
    val (errs, ok) = rendered.partitionMap(identity)
    if errs.nonEmpty then Left(errs.flatten) else Right(ok)

  private def renderTuple(parts: List[String]): String = parts match
    case Nil       => "()"
    case List(one) => s"($one,)"
    case more      => s"(${more.mkString(", ")})"

  private def isStringToIntExpr(term: m.Term): Boolean = term match
    case m.Lit.String(_) => true
    case m.Term.Interpolate(m.Term.Name("s"), _, _) => true
    case m.Term.Select(_, m.Term.Name("toString" | "trim")) => true
    case m.Term.Apply.After_4_6_0(
        m.Term.Select(_, m.Term.Name("toString" | "trim")),
        args
      ) if args.values.isEmpty => true
    case _ => false

  private def isStringExpr(term: m.Term): Boolean = term match
    case m.Lit.String(_) => true
    case m.Term.Interpolate(m.Term.Name("s"), _, _) => true
    case m.Term.Select(_, m.Term.Name("toString" | "trim")) => true
    case m.Term.Apply.After_4_6_0(
      m.Term.Select(_, m.Term.Name("toString" | "trim")),
      args
    ) if args.values.isEmpty => true
    case _ => false

  /** Best-effort check that a term is an `Either`-shaped expression so we can
   *  route `.map/.flatMap/.fold` to Rust `Either`.
   */
  private def isEitherExpr(term: m.Term): Boolean = term match
    case m.Term.Apply.After_4_6_0(m.Term.Name("Left" | "Right"), args)
        if args.values.size == 1 => true
    case m.Term.Apply.After_4_6_0(
      m.Term.Select(inner, m.Term.Name("map" | "flatMap" | "fold")),
      _
    ) if isEitherExpr(inner) => true
    // Top-level user function calls (e.g. `parse(x)`) may return Either.
    // Only Name-based callees qualify — method calls like `csv.split(",")` are
    // NOT treated as Either (they return collections/strings, not Either values).
    case m.Term.Apply.After_4_6_0(m.Term.Name(n), _)
        if n != "Some" && n != "List" && n != "Vec" && n != "Map"
        && n != "Vector" && n != "Seq" && n != "IndexedSeq" && n != "Iterable" => !isOptionExpr(term)
    case _ => false

  /** Best-effort check that a term is an Option-shaped expression so we can
   *  route `.map/.flatMap/.getOrElse` to Rust `Option`.
   */
  private def isOptionExpr(term: m.Term): Boolean = term match
    case m.Term.Name("None") => true
    case m.Term.Apply.After_4_6_0(m.Term.Name("Some"), args) if args.values.size == 1 =>
      true
    case m.Term.Apply.After_4_6_0(m.Term.Select(inner, m.Term.Name("map" | "flatMap" | "getOrElse")), args)
        if isOptionExpr(inner) =>
      // recursive chain case: `Some(x).map(...).getOrElse(...)`
      args.values.size == 1
    case _ => false

  /** Best-effort check that a term is a range or Source expression.
   *  Covers `lo until hi`, `lo to hi`, `Source.range(lo,hi)`,
   *  `Source.fromList(list)`, and common iterator-chain extensions. */
  /** True for a Scala tuple accessor name `_1`..`_22` (an underscore followed
   *  by a 1-based positive integer). Used to remap to Rust's 0-based `.0`.. */
  private def isTupleAccessor(field: String): Boolean =
    field.length >= 2 && field.charAt(0) == '_' &&
      field.charAt(1) != '0' && field.drop(1).forall(_.isDigit)

  private def isRangeExpr(term: m.Term): Boolean = term match
    case m.Term.ApplyInfix.After_4_6_0(_, m.Term.Name("until" | "to"), _, rhs)
        if rhs.values.size == 1 => true
    // Source.range(lo, hi) — backpressured range source (R.6 streams)
    case m.Term.Apply.After_4_6_0(
        m.Term.Select(m.Term.Name("Source"), m.Term.Name("range")), args)
        if args.values.size == 2 => true
    // Source.fromList(list) — source backed by an existing list
    case m.Term.Apply.After_4_6_0(
        m.Term.Select(m.Term.Name("Source"), m.Term.Name("fromList")), args)
        if args.values.size == 1 => true
    case m.Term.Apply.After_4_6_0(
      m.Term.Select(inner, m.Term.Name("map" | "filter" | "foldLeft" | "toList" | "collect"
        | "take" | "drop" | "takeWhile" | "dropWhile")),
      _
    ) if isRangeExpr(inner) => true
    // LazyList constructors are lazy iterators (lazylist-all-backends).
    case m.Term.Apply.After_4_6_0(
      m.Term.Select(m.Term.Name("LazyList"), m.Term.Name("from" | "range" | "continually")), _) => true
    case m.Term.Apply.After_4_6_0(
      m.Term.Apply.After_4_6_0(m.Term.Select(m.Term.Name("LazyList"), m.Term.Name("iterate")), _), _) => true
    case m.Term.Apply.After_4_6_0(m.Term.Name("LazyList"), _) => true
    case _ => false

  private def needsEitherType(module: ast.Module): Boolean =
    module.sections.exists(sectionNeedsEither)

  private def sectionNeedsEither(s: ast.Section): Boolean =
    s.content.exists(contentNeedsEither) || s.subsections.exists(sectionNeedsEither)

  private def contentNeedsEither(c: ast.Content): Boolean = c match
    case ast.Content.CodeBlock(lang, _, Some(node), _, _, _, _)
        if isScalaLang(lang) =>
      node.tree.collect {
        case t @ m.Type.Apply.After_4_6_0(m.Type.Name("Either"), targs)
            if targs.values.size == 2 => t
        case t @ m.Term.Apply.After_4_6_0(m.Term.Name("Left" | "Right"), args)
            if args.values.size == 1 => t
      }.nonEmpty
    case _ => false

  /** RuntimeCall targets whose Rust signature takes args by reference.
   *  Codegen wraps every emitted arg with `&` so callers keep ownership
   *  (and can re-use the same `String` across multiple calls). */
  private val BorrowedArgIntrinsics: Set[String] = Set(
    "crate::runtime::_read_file",
    "crate::runtime::_write_file",
    // R.3.2 — crypto/base64 helpers also take `&str` so callers can
    // re-use the input across encode/decode round-trips.
    "crate::runtime::_sha256",
    "crate::runtime::_base64_encode",
    "crate::runtime::_base64_decode",
    // R.3.3 — JSON helpers also take `&str`.
    "crate::runtime::_json_parse",
    "crate::runtime::_json_stringify",
    // R.3.4 — env() takes its arg by reference.
    "crate::runtime::_env"
  )

  /** Resolve a non-List/Vec `Term.Apply` callee against intrinsics,
   *  enum constructors, in-scope user defs, and finally a passthrough
   *  fallback (see R.2.4). */
  private def applyNonListCtor(
      fn:           m.Term,
      callee:       Option[QualifiedName],
      renderedArgs: List[String],
      joined:       String,
      ctx:          Ctx
  ): Either[List[Diagnostic], String] =
    val intr = callee.flatMap(qn => ctx.intrinsics.get(qn).map(qn -> _))
    intr match
      case Some((_, RuntimeCall(target))) =>
        // A small set of intrinsics take their args by reference so the
        // caller can re-use a `String` after the call (e.g. fs round-trip
        // `writeFile(p, c); readFile(p)`).  Borrow every arg for those.
        if BorrowedArgIntrinsics.contains(target) then
          val borrowed = renderedArgs.map(a => s"&$a").mkString(", ")
          Right(s"$target($borrowed)")
        else
          Right(s"$target($joined)")
      case Some((qn, other)) =>
        Left(List(unsupported(
          s"intrinsic `${qn.value}` uses ${other.getClass.getSimpleName}; rust target accepts only RuntimeCall"
        )))
      case None =>
        // `obj.method(args)` — generic method dispatch via Term.Select callee.
        // Covers given-instance dispatch and arbitrary method calls not matched above.
        fn match
          case m.Term.Select(qual, m.Term.Name(meth)) =>
            renderTerm(qual, ctx).map(q =>
              if joined.isEmpty then s"$q.$meth()" else s"$q.$meth($joined)")
          case _ =>
            val plainName = fn match
              case m.Term.Name(n) => Some(n)
              case _              => None
            plainName.flatMap { n =>
              ctx.ctorMap.get(n).map { ec =>
                val fields = ec.fieldNames.zip(renderedArgs)
                val body   =
                  if fields.isEmpty then ""
                  else " { " + fields.map { (fn, a) =>
                    // Box-wrap a recursive field's argument at construction.
                    val av = if ec.boxedFields.contains(fn) then s"Box::new($a)" else a
                    s"$fn: $av"
                  }.mkString(", ") + " }"
                // Struct: enumName == n (standalone case class) → `StructName { fields }`
                // Enum variant: enumName != n → `EnumName::Variant { fields }`
                if ec.enumName == n then s"$n$body"
                else s"${ec.enumName}::$n$body"
              }
            } match
              case Some(rs) => Right(rs)
              case None     =>
                // Thread `_eff` when calling an effectful user-defined function.
                def withEff(n: String, args: String): String =
                  val rn = rustIdent(n)  // escape a call to a reserved-keyword-named def (e.g. `box`)
                  if ctx.effectfulDefs.contains(n) then
                    if args.isEmpty then s"$rn(&mut _eff)"
                    else s"$rn($args, &mut _eff)"
                  else s"$rn($args)"
                plainName.filter(ctx.userDefs.contains) match
                  case Some(n) => Right(withEff(n, joined))
                  case None    =>
                    // Fallback: assume closure parameter or local binding;
                    // Cargo will reject if not.  See R.2.4.
                    plainName match
                      case Some(n) => Right(withEff(n, joined))
                      case None    => Left(List(unsupported(
                        s"def `${ctx.defName}` calls `${fn.syntax}` which has no resolvable name"
                      )))

  /** Lower a `for x <- xs yield body` (single-generator) to
   *  `xs.into_iter().map(|x| body).collect::<Vec<_>>()`.  Multi-generator
   *  / guarded / val-enumerator shapes are out of scope for R.2.5. */
  private def renderForYield(
      enums: List[m.Enumerator], body: m.Term, ctx: Ctx
  ): Either[List[Diagnostic], String] =
    enums match
      case List(g: m.Enumerator.Generator) =>
        g.pat match
          case m.Pat.Var(m.Term.Name(name)) =>
            for
              xs <- renderTerm(g.rhs, ctx)
              b  <- renderTerm(body, ctx)
            yield
              s"$xs.into_iter().map(move |$name| { $b }).collect::<Vec<_>>()"
          case other =>
            Left(List(unsupported(
              s"def `${ctx.defName}` for-yield generator pattern `${other.syntax}` is not a plain name"
            )))
      case _ =>
        Left(List(unsupported(
          s"def `${ctx.defName}` for-yield has ${enums.size} enumerators; R.2.5 accepts only a single generator"
        )))

  /** Lower a Vec HOF call — `foreach`, `map`, `filter`, `foldLeft`.
   *  `fn` is the lambda argument; `q` is the already-rendered receiver.
   *
   *  foreach  → for __elem in q.iter() { body; }
   *  map      → q.iter().cloned().map(move |param| body).collect::<Vec<_>>()
   *  filter   → q.iter().cloned().filter(move |param| body).collect::<Vec<_>>()
   *  foldLeft → q.iter().copied().fold(zero, move |a, b| body)
   *             (or .cloned() when element type is String/non-Copy)
   */
  private def renderVecIterBody(
      fn:     m.Term,
      q:      String,
      ctx:    Ctx,
      method: String,
      zero:   Option[String] = None
  ): Either[List[Diagnostic], String] = fn match
    // Single or two-param closure `(p: T) => body` or `(a, b) => body`.
    case fn2: m.Term.Function =>
      val params = fn2.paramClause.values.toList
      val p0 = params.lift(0).map(_.name.value).getOrElse("__x")
      val p1 = params.lift(1).map(_.name.value).getOrElse("__b")
      // Block bodies (multi-stmt) use renderBody with isUnit=true for foreach/foldLeft.
      val isUnitCtx = method == "foreach"
      val bodyCtx = ctx.copy(closureParams = ctx.closureParams ++ Set(p0, p1))
      val bodyResult = fn2.body match
        case blk: m.Term.Block => renderBody(blk, bodyCtx, isUnit = isUnitCtx)
        case t                 => renderTerm(t, bodyCtx)
      bodyResult.map { b =>
        method match
          case "foreach"  =>
            val stmtBody = if b.endsWith(";") then b else b + ";"
            s"for $p0 in $q.iter().cloned() {\n${indent(stmtBody)}\n}"
          case "map"      => s"$q.iter().cloned().map(move |$p0| { $b }).collect::<Vec<_>>()"
          case "filter"   => s"$q.iter().cloned().filter(move |$p0| { $b }).collect::<Vec<_>>()"
          case "foldLeft" => s"$q.iter().cloned().fold(${zero.getOrElse("0")}, move |$p0, $p1| { $b })"
          case other      => s"$q.$other(move |$p0| { $b })"
      }
    // Method reference `obj.method` → wrap in a closure so it's a callable value.
    // (Rust can't take a method as a fn pointer without a turbofish bound trait.)
    case m.Term.Select(qual, m.Term.Name(meth)) =>
      renderTerm(qual, ctx).map { q2 =>
        val closure = method match
          case "foldLeft" | "fold" => s"move |__a, __b| $q2.$meth(__a, __b)"
          case _                   => s"move |__x| $q2.$meth(__x)"
        method match
          case "foreach"  => s"$q.iter().cloned().for_each($closure);"
          case "map"      => s"$q.iter().cloned().map($closure).collect::<Vec<_>>()"
          case "filter"   => s"$q.iter().cloned().filter($closure).collect::<Vec<_>>()"
          case "foldLeft" => s"$q.iter().cloned().fold(${zero.getOrElse("0")}, $closure)"
          case other2     => s"$q.$other2($closure)"
      }
    // Fallback — inline the fn as-is and hope Rust accepts it
    case other =>
      renderTerm(other, ctx).map(f => method match
        case "foreach"  => s"$q.iter().cloned().for_each($f);"
        case "map"      => s"$q.iter().cloned().map($f).collect::<Vec<_>>()"
        case "filter"   => s"$q.iter().cloned().filter($f).collect::<Vec<_>>()"
        case "foldLeft" => s"$q.iter().cloned().fold(${zero.getOrElse("0")}, $f)"
        case other2     => s"$q.$other2($f)"
      )

  /** Lower `(params) => body` to a Rust `move |params| body` closure.
   *  Parameter type annotations are honoured when present; otherwise
   *  Rust's inference picks them up from the call site. */
  private def renderClosure(
      params: List[m.Term.Param], body: m.Term, ctx: Ctx
  ): Either[List[Diagnostic], String] =
    val rendered = params.map { p =>
      p.decltpe match
        case None    => Right(p.name.value)
        case Some(t) => mapType(t, ctx.defName, ctx.enumNames).map(r =>
          if r.isEmpty then p.name.value else s"${p.name.value}: $r"
        )
    }
    val (errs, ok) = rendered.partitionMap(identity)
    if errs.nonEmpty then Left(errs.flatten)
    else
      val bodyCtx = ctx.copy(closureParams = ctx.closureParams ++ params.map(_.name.value).toSet)
      for b <- renderTerm(body, bodyCtx)
      yield s"move |${ok.mkString(", ")}| { $b }"

  /** Lower a `Term.Match(subject, cases)` to a Rust `match` expression.
   *  Each case's pattern is lowered via `renderPattern`; the body is
   *  rendered as a non-Unit expression (the match itself is an
   *  expression). */
  private def renderMatch(
      subject: m.Term, cases: List[m.Case], ctx: Ctx
  ): Either[List[Diagnostic], String] =
    val subjRendered = renderTerm(subject, ctx)
    // An identity catch-all (`case x => x`, no guard) trailing a sealed-enum match is the
    // toolkit's JVM-side idempotency passthrough (`lower` handed an already-lowered View).
    // On the statically-typed Rust enum the variant arms are exhaustive, so the catch-all
    // can't fire — and its body (the bound enum value) would mistype against the match's
    // result type.  Drop it when there is at least one constructor arm and let rustc's own
    // exhaustiveness check confirm the variants cover the enum.
    def isIdentityCatchAll(c: m.Case): Boolean = (c.pat, c.body, c.cond) match
      case (m.Pat.Var(m.Term.Name(a)), m.Term.Name(b), None) => a == b
      case _                                                 => false
    val hasCtorArm = cases.exists(_.pat match
      case m.Pat.Extract.After_4_6_0(_, _) => true
      case _                               => false)
    val cases1 =
      if hasCtorArm && cases.lastOption.exists(isIdentityCatchAll)
      then cases.filterNot(isIdentityCatchAll)
      else cases
    // A `String` subject must be matched as `&str` for string-literal patterns
    // (`match s.as_str() { "x" => … }`) — Rust won't match `String` against `&str`.
    val hasStringPat = cases1.exists(c => c.pat match
      case _: m.Lit.String => true
      case _               => false)
    val caseRendered = cases1.map { c =>
      // A case guard `case p if cond =>` maps onto a Rust match-arm guard.
      // A boxed (recursive) field binds as `Box<T>`; deref-rebind it at the top of
      // the arm (`let f = *f;`) so the body sees the unboxed `T`.
      // In a `match s.as_str()`, a var-binding arm (`other => …`) binds `&str`;
      // rebind it to a `String` so a body that needs `String` typechecks (a `String`
      // still coerces back to `&str` where needed).
      val strRebind = (if hasStringPat then c.pat match
        case m.Pat.Var(m.Term.Name(n)) => s"let $n = $n.to_string(); "
        case _                         => "" else "")
      val prefix = boxedFieldDerefs(c.pat, ctx) + strRebind
      for
        pat   <- renderPattern(c.pat, ctx)
        guard <- c.cond match
                   case Some(g) => renderTerm(g, ctx).map(gr => s" if $gr")
                   case None    => Right("")
        bod   <- renderTerm(c.body, ctx)
      yield
        if prefix.isEmpty then s"$pat$guard => $bod,"
        else s"$pat$guard => { $prefix$bod },"
    }
    val (errs, ok) = caseRendered.partitionMap(identity)
    subjRendered.flatMap { s0 =>
      val s = if hasStringPat then s"($s0).as_str()" else s0
      if errs.nonEmpty then Left(errs.flatten)
      else
        val arms = ok.mkString("\n")
        Right(s"match $s {\n${indent(arms)}\n}")
    }

  /** Names a pattern binds (var binders, recursively through tuples/extractors) —
   *  used to mark closure-local names so captured vars can be told apart. */
  private def patBoundNames(p: m.Pat): Set[String] = p match
    case m.Pat.Var(m.Term.Name(n))               => Set(n)
    case m.Pat.Tuple(args)                       => args.flatMap(patBoundNames).toSet
    case m.Pat.Extract.After_4_6_0(_, argClause) => argClause.values.flatMap(patBoundNames).toSet
    case m.Pat.Typed(inner, _)                   => patBoundNames(inner)
    case _                                       => Set.empty

  private def isMapCtorFn(fn: m.Term): Boolean = fn match
    case m.Term.Name("Map")                                  => true
    case m.Term.ApplyType.After_4_6_0(m.Term.Name("Map"), _) => true
    case _                                                   => false

  /** Render an `element(…)` attribute map (`Map[String, Any]` literal) into a
   *  `HashMap<String, String>`, coercing each value with `_ui_attr(...)` so mixed
   *  `String`/`bool`/`Value` attr values become homogeneous strings.  A non-literal
   *  arg (variable / empty) falls back to the generic rendering. */
  private def renderAttrMap(t: m.Term, ctx: Ctx): Either[List[Diagnostic], String] =
    t match
      case m.Term.Apply.After_4_6_0(fn, args) if isMapCtorFn(fn) =>
        if args.values.isEmpty then Right("std::collections::HashMap::new()")
        else
          val inserts = args.values.toList.map {
            case m.Term.ApplyInfix.After_4_6_0(k, m.Term.Name("->"), _, vArgs)
                if vArgs.values.size == 1 =>
              for
                kr <- renderTerm(k, ctx)
                vr <- renderTerm(vArgs.values.head, ctx)
              yield s"__m.insert($kr, crate::runtime::ui::_ui_attr($vr));"
            case _ =>
              Left(List(unsupported(s"def `${ctx.defName}`: attr entry is not `k -> v`")))
          }
          val (errs, ok) = inserts.partitionMap(identity)
          if errs.nonEmpty then Left(errs.flatten)
          else Right(s"{ let mut __m = std::collections::HashMap::new(); ${ok.mkString(" ")} __m }")
      case _ => renderTerm(t, ctx)

  /** For a `Pat.Extract(Ctor, args)` whose ctor has boxed (recursive) fields, emit
   *  `let v = *v; ` deref-rebinds for each boxed field bound as a plain var, so the
   *  arm body sees the unboxed value rather than a `Box<T>`. */
  private def boxedFieldDerefs(pat: m.Pat, ctx: Ctx): String =
    pat match
      case m.Pat.Extract.After_4_6_0(m.Term.Name(ctor), argClause) =>
        ctx.ctorMap.get(ctor) match
          case Some(ec) if ec.boxedFields.nonEmpty =>
            ec.fieldNames.zip(argClause.values).collect {
              case (fieldName, m.Pat.Var(m.Term.Name(v))) if ec.boxedFields.contains(fieldName) =>
                s"let $v = *$v; "
            }.mkString
          case _ => ""
      case _ => ""

  /** Lower a scalameta `Pat` to a Rust pattern.  R.2.3 covers the
   *  shapes the case-class fixture needs: extractor (`Pat.Extract`)
   *  against an enum constructor, var-bind, wildcard, literals. */
  private def renderPattern(p: m.Pat, ctx: Ctx): Either[List[Diagnostic], String] = p match
    case m.Pat.Wildcard()           => Right("_")
    case m.Pat.Var(m.Term.Name(n))  => Right(n)
    case m.Lit.Int(n)               => Right(s"${n}i64")
    case m.Lit.Long(n)              => Right(s"${n}i64")
    case m.Lit.Double(d)            => Right(s"${d}f64")
    case m.Lit.Boolean(b)           => Right(b.toString)
    case m.Lit.String(s)            => Right("\"" + escapeRustString(s) + "\"")
    case m.Pat.Extract.After_4_6_0(m.Term.Name(ctor), argClause) =>
      val args = argClause.values
      ctx.ctorMap.get(ctor) match
        case None =>
          Left(List(unsupported(
            s"def `${ctx.defName}` extracts `$ctor` which is not a known enum constructor"
          )))
        case Some(ec) =>
          val argPats = args.map(renderPattern(_, ctx))
          val (errs, ok) = argPats.partitionMap(identity)
          if errs.nonEmpty then Left(errs.flatten)
          else
            if ec.fieldNames.size != ok.size then
              Left(List(unsupported(
                s"def `${ctx.defName}` extracts `$ctor` with ${ok.size} args, expected ${ec.fieldNames.size}"
              )))
            else
              val fieldBinds = ec.fieldNames.zip(ok)
              val body = if fieldBinds.isEmpty then ""
                         else " { " + fieldBinds.map { case (f, p) =>
                           if f == p then f else s"$f: $p"
                         }.mkString(", ") + " }"
              Right(s"${ec.enumName}::$ctor$body")
    // Tuple pattern `(a, b)` → Rust tuple pattern.
    case m.Pat.Tuple(args) =>
      val ps = args.map(renderPattern(_, ctx))
      val (errs, ok) = ps.partitionMap(identity)
      if errs.nonEmpty then Left(errs.flatten) else Right(s"(${ok.mkString(", ")})")
    // Typed pattern `h: T` — the ascription is the subject's own type (a
    // bind-all on a sealed-trait match), so drop the type and keep the binder.
    case m.Pat.Typed(inner, _) =>
      renderPattern(inner, ctx)
    case other =>
      Left(List(unsupported(
        s"def `${ctx.defName}` has unsupported pattern: ${other.productPrefix} (${other.syntax})"
      )))

  /** Render a `val`/`var` binding to a Rust `let` (optionally `mut`)
   *  statement.  Only single-name patterns are supported; destructuring
   *  binders lower to a structured diagnostic. */
  private def renderLetBinding(
      pats:    List[m.Pat],
      decltpe: Option[m.Type],
      rhs:     m.Term,
      mutable: Boolean,
      ctx:     Ctx
  ): Either[List[Diagnostic], String] =
    pats match
      case List(m.Pat.Var(m.Term.Name(name))) =>
        // collection-rust-array: a `val a = Array(...)` local must bind `let mut` so `a(i)=x` stores.
        val kw = if mutable || ctx.localArrays.contains(name) then "let mut" else "let"
        val annotated: Either[List[Diagnostic], String] = decltpe match
          case None    => Right("")
          case Some(t) => mapType(t, ctx.defName, ctx.enumNames).map { r =>
            if r.isEmpty then "" else s": $r"
          }
        for
          tyAnn <- annotated
          rhsRs <- renderTerm(rhs, ctx)
        yield
          s"$kw $name$tyAnn = $rhsRs;"
      // `val (a, b) = expr` / `val (a, _) = expr` — tuple destructuring.
      case List(m.Pat.Tuple(elems)) =>
        val kw = if mutable then "let mut" else "let"
        val patParts = elems.map {
          case m.Pat.Var(m.Term.Name(n)) => Right(n)
          case m.Pat.Wildcard()          => Right("_")
          case other => Left(List(unsupported(
            s"def `${ctx.defName}` has an unsupported tuple-pattern element: ${other.productPrefix}"
          )))
        }
        val (patErrs, patOk) = patParts.partitionMap(identity)
        if patErrs.nonEmpty then Left(patErrs.flatten)
        else
          renderTerm(rhs, ctx).map { rhsRs =>
            s"$kw (${patOk.mkString(", ")}) = $rhsRs;"
          }
      case _ =>
        Left(List(unsupported(
          s"def `${ctx.defName}` has a non-single-name binding; R.2 accepts only `${if mutable then "var" else "val"} name: T = expr`"
        )))

  /** Lower a Scala infix operator to its Rust equivalent.  R.2 covers
   *  arithmetic + comparison + boolean — enough for fib + a basic
   *  string-interp example. */
  private def mapInfixOp(op: String, defName: String): Either[List[Diagnostic], String] = op match
    case "+" | "-" | "*" | "/" | "%" => Right(op)
    case "<" | "<=" | ">" | ">=" | "==" | "!=" => Right(op)
    case "&&" | "||" => Right(op)
    case other => Left(List(unsupported(
      s"def `$defName` uses unsupported infix operator `$other`"
    )))

  /** Lower `s"prefix ${expr} suffix"` to `format!("prefix {} suffix", expr)`. */
  private def renderStringInterpolation(
      parts: List[m.Lit],
      args:  List[m.Term],
      ctx:   Ctx
  ): Either[List[Diagnostic], String] =
    val partsStr = parts.collect { case m.Lit.String(s) => s }
    if partsStr.size != parts.size then
      Left(List(unsupported(
        s"def `${ctx.defName}` has a non-string interpolation part"
      )))
    else
      val argRendered = args.map(renderInterpArg(_, ctx))
      val (errs, ok)  = argRendered.partitionMap(identity)
      if errs.nonEmpty then Left(errs.flatten)
      else
        val formatStr = partsStr.zipWithIndex.foldLeft(new StringBuilder) { (sb, p) =>
          val (text, i) = p
          sb.append(escapeForFormat(text))
          if i < args.size then sb.append("{}")
          sb
        }.toString
        val tail = if ok.isEmpty then "" else ", " + ok.mkString(", ")
        Right(s"""format!("$formatStr"$tail)""")

  /** Render one interpolation splice. A bare `$name` arrives as a plain
   *  term, but a braced `${ expr }` is wrapped by scalameta in a
   *  `Term.Block`: a single-term block unwraps to its inner expression
   *  (`${1 + 2}` → `(1 + 2)`), a multi-statement block becomes a Rust
   *  block expression `{ … }`. Without this, any compound splice failed
   *  with "unsupported expression: Term.Block". */
  private def renderInterpArg(arg: m.Term, ctx: Ctx): Either[List[Diagnostic], String] =
    arg match
      case m.Term.Block(List(single: m.Term)) => renderTerm(single, ctx)
      case b: m.Term.Block                     => renderBody(b, ctx, isUnit = false).map(inner => s"{ $inner }")
      case other                               => renderTerm(other, ctx)

  /** Escape a string for `format!("…")` — same rules as the Rust lexer
   *  plus `{` / `}` are doubled to avoid being read as format markers. */
  private[rust] def escapeForFormat(s: String): String =
    val sb = new StringBuilder(s.length)
    s.foreach {
      case '\\' => sb.append("\\\\")
      case '"'  => sb.append("\\\"")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case '{'  => sb.append("{{")
      case '}'  => sb.append("}}")
      case c    => sb.append(c)
    }
    sb.toString

  /** Resolve a callee tree to its lookup key in the intrinsic table.
   *  Supports plain names (`println`) and one-level qualified
   *  selects (`Console.println`). */
  private def qualifiedName(fn: m.Term): Option[QualifiedName] = fn match
    case m.Term.Name(n)                                    => Some(QualifiedName(n))
    case m.Term.Select(m.Term.Name(qual), m.Term.Name(n))  => Some(QualifiedName(s"$qual.$n"))
    case _                                                 => None

  /** Returns true if any code block in the module calls `runStream`. */
  private def usesRunStream(module: ast.Module): Boolean =
    usesKeyword(module, "runStream")

  /** Returns true if any code block in the module calls `runState`. */
  private def usesRunState(module: ast.Module): Boolean =
    usesKeyword(module, "runState")

  /** Returns true if any code block in the module calls `runRandom`. */
  private def usesRunRandom(module: ast.Module): Boolean =
    usesKeyword(module, "runRandom")

  private def usesKeyword(module: ast.Module, keyword: String): Boolean =
    module.sections.exists(s => sectionUsesKeyword(s, keyword))

  private def sectionUsesKeyword(s: ast.Section, keyword: String): Boolean =
    s.content.exists(c => contentUsesKeyword(c, keyword)) ||
    s.subsections.exists(sub => sectionUsesKeyword(sub, keyword))

  private def contentUsesKeyword(c: ast.Content, keyword: String): Boolean = c match
    case ast.Content.CodeBlock(lang, source, _, _, _, _, _)
        if lang.equalsIgnoreCase("scalascript") || lang.equalsIgnoreCase("ssc") ||
           lang.equalsIgnoreCase("scala") =>
      source.contains(keyword)
    case _ => false


  // ── Helpers ──────────────────────────────────────────────────────────

  private def unsupported(message: String): Diagnostic =
    Diagnostic.Generic(message = message, source = Some("rust"))

  private def indent(s: String): String =
    s.linesIterator.map(line => if line.isEmpty then "" else "    " + line).mkString("\n")

  /** Rust reserved keywords that a ScalaScript identifier might collide with (e.g. a
   *  `box` widget constructor).  `crate`/`self`/`Self`/`super` can't be raw identifiers
   *  and are left as-is (unlikely as user names). */
  private val rustReserved: Set[String] = Set(
    "as", "break", "const", "continue", "dyn", "else", "enum", "extern", "false", "fn",
    "for", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref",
    "return", "static", "struct", "trait", "true", "type", "unsafe", "use", "where", "while",
    "async", "await", "abstract", "become", "box", "do", "final", "macro", "override", "priv",
    "typeof", "unsized", "virtual", "yield", "try")

  /** Escape a Rust reserved keyword used as an identifier with the raw-identifier prefix. */
  private def rustIdent(name: String): String =
    if rustReserved.contains(name) then s"r#$name" else name

  private[rust] def escapeRustString(s: String): String =
    val sb = new StringBuilder(s.length)
    s.foreach {
      case '\\' => sb.append("\\\\")
      case '"'  => sb.append("\\\"")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case c    => sb.append(c)
    }
    sb.toString

