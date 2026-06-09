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
    val defs              = collectDefs(module)
    val enums             = collectEnums(module)
    val traitEnums        = collectSealedTraitEnums(module)
    val standaloneCases   = collectStandaloneCaseClasses(module, traitEnums)
    val rustBlocks        = collectRustBlocks(module)
    val givens            = collectGivens(module)
    val userDefs          = defs.map(_.name.value).toSet
    // Collect names of defs that carry a `T ! EffectName` return type so
    // call sites can thread the `_eff` parameter automatically.
    val effectfulDefs: Set[String] = defs.flatMap(d => defEffectName(d).map(_ => d.name.value)).toSet
    val enumRendered =
      (if needsEitherType(module) then List(renderBuiltinEitherEnum()) else Nil) ++
      enums.map(renderEnum) ++
      traitEnums.map { case SealedTraitEnum(t, caseClasses) => renderTraitEnum(t, caseClasses) }
    val structRendered    = standaloneCases.map(renderStruct)
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
      val topStats: List[m.Tree] = node.tree match
        case m.Source(stats)     => stats.toList
        case m.Term.Block(stats) => stats.toList
        case single              => List(single)
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

  private def renderStruct(c: m.Defn.Class): Either[List[Diagnostic], GeneratedStruct] =
    val name   = c.name.value
    val params = c.ctor.paramClauses.flatMap(_.values).toList
    val fieldRendered = params.map { p =>
      p.decltpe match
        case Some(t) => mapType(t, s"struct $name").map(r => (p.name.value, r))
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
      caseClasses: List[m.Defn.Class]
  ): Either[List[Diagnostic], GeneratedEnum] =
    val enumName = t.name.value
    val rendered = caseClasses.map(c => renderClassCtor(enumName, c))
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
      enumName: String, c: m.Defn.Class
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
      effectfulDefs: Set[String] = Set.empty  // defs that carry `T ! E` return type
  ):
    def enumNames: Set[String] = ctorMap.values.map(_.enumName).toSet
    @annotation.unused def topValNames: Set[String] = topVals.map(_._1).toSet

  /** Per-ctor record carrying the enum it belongs to and its field
   *  names (in order).  R.2.3 lowers ctor application as
   *  `EnumName::Ctor { field0: arg0, field1: arg1, … }`. */
  private case class EnumCtor(
      enumName:   String,
      fieldNames: List[String]
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
            val sig = if ret.isEmpty then s"pub fn $name($params)"
                      else               s"pub fn $name($params) -> $ret"
            GeneratedDef(name = name, render = s"$sig { $body }\n", isMain = false)
        case None =>
          Right(GeneratedDef(name = name, render = "", isMain = false))
    else
      val ctx     = Ctx(intrinsics, userDefs, ctorMap, topVals, name, effectfulDefs)
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
        val signature = if ret.isEmpty then s"pub fn $name($params)"
                        else                s"pub fn $name($params) -> $ret"
        val topValPreamble =
          if ctx.topVals.isEmpty then ""
          else ctx.topVals.map { case (n, init) => s"let $n = $init;" }.mkString("\n") + "\n"
        val src =
          s"""$signature {
             |${indent(topValPreamble + bodyRs)}
             |}
             |""".stripMargin
        GeneratedDef(name = name, render = src, isMain = hasMainAnnotation(d))

  /** Extract just the parameter names from a def (single parameter group). */
  private def extractParamNames(d: m.Defn.Def): List[String] =
    d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value)

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
    val groups = d.paramClauseGroups.flatMap(_.paramClauses)
    if groups.size > 1 then
      Left(List(unsupported(
        s"def `${ctx.defName}` has multiple parameter groups; R.2 accepts a single `(…)` group"
      )))
    else
      val params = groups.flatMap(_.values)
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
    case m.Type.Name(n) if enumNames.contains(n) => Right(n)
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
    // R.2.5 — `List[T]` / `Vec[T]` lower to `Vec<T>`.
    case m.Type.Apply.After_4_6_0(m.Type.Name("List" | "Vec"), argClause) =>
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
    val groups = d.paramClauseGroups.flatMap(_.paramClauses)
    if groups.size > 1 then
      Left(List(unsupported(
        s"def `${ctx.defName}` has multiple parameter groups; R.2 accepts a single `(…)` group"
      )))
    else
      val params = groups.flatMap(_.values)
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
    // Plain identifier — parameter reference or in-scope fn name.
    case m.Term.Name(n) =>
      Right(n)

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

    // Numeric coercions — P0 bench fix (specs/rust-backend-bench-coverage.md §Gap A).
    case m.Term.Select(qual, m.Term.Name("toLong")) =>
      renderTerm(qual, ctx).map(q => s"($q as i64)")
    case m.Term.Select(qual, m.Term.Name("toInt")) =>
      renderTerm(qual, ctx).flatMap { q =>
        if isStringToIntExpr(qual) then Right(s"($q.parse::<i64>().unwrap_or(0))")
        else Right(s"($q as i32)")
      }
    case m.Term.Select(qual, m.Term.Name("toDouble")) =>
      renderTerm(qual, ctx).map(q => s"($q as f64)")
    case m.Term.Select(qual, m.Term.Name("toFloat")) =>
      renderTerm(qual, ctx).map(q => s"($q as f64)")
    case m.Term.Select(qual, m.Term.Name("toString")) =>
      renderTerm(qual, ctx).map(q => s"format!(\"{}\", $q)")
    case m.Term.Select(qual, m.Term.Name("trim")) =>
      renderTerm(qual, ctx).map(q => s"$q.trim().to_string()")
    // `.toList` on a Source/range (property access form) — collect to Vec.
    case m.Term.Select(qual, m.Term.Name("toList")) if isRangeExpr(qual) =>
      renderTerm(qual, ctx).map(q => s"$q.collect::<Vec<_>>()")
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
    // Some scalameta versions parse `x => body` as `Term.AnonymousFunction`
    // with a placeholder; cover the same shape conservatively.
    case _: m.Term.AnonymousFunction =>
      Left(List(unsupported(
        s"def `${ctx.defName}` uses an anonymous-function placeholder; R.2.4 accepts only explicit `(params) => body`"
      )))

    // `(a, b, ...)` — native Rust tuple literal.  Rust requires a
    // trailing comma in `(..., )`, so a 1-tuple emits `(x,)`.
    case m.Term.Tuple(values) =>
      renderTupleElems(values.toList, ctx).map(renderTuple)

    // s"…" interpolation → `format!("…", args)`.
    case m.Term.Interpolate(m.Term.Name("s"), parts, args) =>
      renderStringInterpolation(parts, args, ctx)

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
        val renderedArgs = args.values.toList.zip(renderedArgs0).zip(isPrimLit).map {
          case ((m.Term.Name(n), rendered), false) if topValNames.contains(n) =>
            s"$rendered.clone()"
          case ((_, rendered), _) => rendered
        }
        val joined = renderedArgs.mkString(", ")
        // User-defined struct/enum ctors take priority over stdlib names (e.g. user Vec vs List[Vec]).
        val userCtorName = fn match
          case m.Term.Name(n) if ctx.ctorMap.contains(n) => Some(n)
          case _                                          => None
        // `List(1, 2, 3)` / `Vec(1, 2, 3)` → Rust `vec![1, 2, 3]` unless Vec is a user struct.
        val isListCtor = userCtorName.isEmpty && (fn match
          case m.Term.Name("List" | "Vec") => true
          case _                            => false)
        val isMapCtor = fn match
          case m.Term.Name("Map") => true
          case m.Term.ApplyType.After_4_6_0(m.Term.Name("Map"), _) => true
          case _                    => false
        if isListCtor then Right(s"vec![$joined]")
        else if isMapCtor then
          if args.values.isEmpty then Right("std::collections::HashMap::new()")
          else Left(List(unsupported(s"def `${ctx.defName}` uses `Map` with ${args.values.size} args; only empty `Map()` is supported")))
        else applyNonListCtor(fn, callee, renderedArgs, joined, ctx)

    // `String + any` / `any + String` — lower to `format!("{}{}", lhs, rhs)`.
    // Triggered when either side is a best-effort string expression.
    case m.Term.ApplyInfix.After_4_6_0(lhs, m.Term.Name("+"), _, args)
        if args.values.size == 1 && (isStringExpr(lhs) || isStringExpr(args.values.head)) =>
      for
        l <- renderTerm(lhs, ctx)
        r <- renderTerm(args.values.head, ctx)
      yield s"format!(\"{}{}\", $l, $r)"

    // Infix `++` for tuple literals: flatten tuple-literal concat chains.
    // `(a, b) ++ (c, d) == (a, b, c, d)`, also right-recursive.
    case infix @ m.Term.ApplyInfix.After_4_6_0(_, m.Term.Name("++"), _, _) =>
      collectTupleConcat(infix) match
        case Some(terms) => renderTupleElems(terms, ctx).map(renderTuple)
        case None        => renderInfix(infix, ctx)

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
        if n != "Some" && n != "List" && n != "Vec" && n != "Map" => !isOptionExpr(term)
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
      m.Term.Select(inner, m.Term.Name("map" | "filter" | "foldLeft" | "toList" | "collect")),
      _
    ) if isRangeExpr(inner) => true
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
                  else " { " + fields.map((fn, a) => s"$fn: $a").mkString(", ") + " }"
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
                  if ctx.effectfulDefs.contains(n) then
                    if args.isEmpty then s"$n(&mut _eff)"
                    else s"$n($args, &mut _eff)"
                  else s"$n($args)"
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
      val bodyResult = fn2.body match
        case blk: m.Term.Block => renderBody(blk, ctx, isUnit = isUnitCtx)
        case t                 => renderTerm(t, ctx)
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
      for b <- renderTerm(body, ctx)
      yield s"move |${ok.mkString(", ")}| { $b }"

  /** Lower a `Term.Match(subject, cases)` to a Rust `match` expression.
   *  Each case's pattern is lowered via `renderPattern`; the body is
   *  rendered as a non-Unit expression (the match itself is an
   *  expression). */
  private def renderMatch(
      subject: m.Term, cases: List[m.Case], ctx: Ctx
  ): Either[List[Diagnostic], String] =
    val subjRendered = renderTerm(subject, ctx)
    val caseRendered = cases.map { c =>
      if c.cond.nonEmpty then
        Left(List(unsupported(
          s"def `${ctx.defName}` has a match-case guard (`if …`); R.2.3 accepts only plain patterns"
        )))
      else
        for
          pat <- renderPattern(c.pat, ctx)
          bod <- renderTerm(c.body, ctx)
        yield s"$pat => $bod,"
    }
    val (errs, ok) = caseRendered.partitionMap(identity)
    subjRendered.flatMap { s =>
      if errs.nonEmpty then Left(errs.flatten)
      else
        val arms = ok.mkString("\n")
        Right(s"match $s {\n${indent(arms)}\n}")
    }

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
        val kw = if mutable then "let mut" else "let"
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
      val argRendered = args.map(renderTerm(_, ctx))
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

