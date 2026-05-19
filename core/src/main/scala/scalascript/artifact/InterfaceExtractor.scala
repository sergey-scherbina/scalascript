package scalascript.artifact

import scalascript.ir.*
import scalascript.ast
import scalascript.transform.EffectAnalysis
import scalascript.typer.{Typer, DefSummary, SymbolKind as TSymbolKind}
import scala.meta.*
import scala.collection.mutable.ListBuffer

/** Extracts a `ModuleInterface` from a compiled `NormalizedModule` + its
 *  original AST `Module`.
 *
 *  Strategy:
 *  1. Run `Typer` over the AST to collect `DefSummary` entries (names +
 *     best-effort types) for every top-level definition.
 *  2. Walk the AST to detect `given` instances and `extern def` declarations.
 *  3. Detect capability usage from `serve()`, `fetch()`, `connect()` etc.
 *  4. Build the `ModuleInterface` with the ABI magic + version guard.
 *
 *  The extractor is intentionally conservative: if it can't determine a
 *  type it falls back to `"Any"`.  Richer type information will arrive when
 *  the typer is extended in a later sprint.
 *
 *  v2.0 / Stage 2 — interface extraction.
 */
object InterfaceExtractor:

  /** Compute a SHA-256 hex digest of raw bytes. */
  def sha256(bytes: Array[Byte]): String =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.digest(bytes).map("%02x".format(_)).mkString

  /** Extract a `ModuleInterface` from a parsed AST module + its source bytes.
   *
   *  @param module     The parsed AST module (from `Parser.parse`).
   *  @param sourceBytes The raw `.ssc` source bytes for the SHA-256 hash.
   *  @return A `ModuleInterface` ready to be serialised to `.scim` JSON.
   */
  def extract(module: ast.Module, sourceBytes: Array[Byte]): ModuleInterface =
    val pkg         = module.manifest.flatMap(_.pkg).getOrElse(Nil)
    val moduleName  = module.manifest.flatMap(_.name)
    val moduleVer   = module.manifest.flatMap(_.version)
    val deps        = module.manifest.map(_.dependencies).getOrElse(Map.empty)
    val srcHash     = sha256(sourceBytes)

    // Run the typer to collect definitions with best-effort types.
    val typed = Typer.typeCheck(module)

    // Collect all DefSummary entries from the typed module.
    val allDefs = ListBuffer.empty[DefSummary]
    def gatherSection(s: scalascript.typer.TypedSection): Unit =
      s.definitions.foreach {
        case scalascript.typer.TypedDef.CodeBlock(_, _, defs) => allDefs ++= defs
        case _ => ()
      }
      s.subsections.foreach(gatherSection)
    typed.sections.foreach(gatherSection)

    // Also scan for `given` instances and `extern def` by walking the AST.
    val instances  = ListBuffer.empty[InstanceDecl]
    val externDefs = ListBuffer.empty[ExportedSymbol]

    // Collect every parsed scalameta tree across all code blocks — used by
    // both the structural extern walk and the AST capability detector.
    val scalaTrees = ListBuffer.empty[scala.meta.Tree]
    def collectTrees(sec: ast.Section): Unit =
      sec.content.foreach {
        case cb: ast.Content.CodeBlock if ast.Lang.isParseable(cb.lang) =>
          cb.tree.foreach { node =>
            scalascript.ast.ScalaNode.fold(node)(scalaTrees += _)
          }
        case _ => ()
      }
      sec.subsections.foreach(collectTrees)
    module.sections.foreach(collectTrees)

    def fqn(name: String): String =
      if pkg.isEmpty then name
      else (pkg :+ name).mkString("_")

    /** Render a parameter `T` annotation (or `Any` when absent). */
    def typeToString(t: Option[Type]): String =
      t.map(_.toString).getOrElse("Any")

    /** Render a `def`'s signature as `(p1: T1, p2: T2): R` so the
     *  extern entry preserves more than the return type alone. */
    def defSignature(d: Defn.Def): String =
      val groups = d.paramClauseGroups.flatMap(_.paramClauses)
      val paramText = groups.map { clause =>
        val items = clause.values.map { p =>
          s"${p.name.value}: ${typeToString(p.decltpe)}"
        }
        items.mkString("(", ", ", ")")
      }.mkString
      val ret = typeToString(d.decltpe)
      if paramText.isEmpty then ret else s"$paramText: $ret"

    def scanStats(stats: List[Stat]): Unit =
      stats.foreach {
        // given instance: `given eqInt: Eq[Int] = ...`  or `given Eq[Int] = ...`
        case d: Defn.Given =>
          val witnessName = d.name.value
          // Attempt to pull the typeclass + type-param from the template
          // (best-effort: look at the parent type list).
          d.templ.inits.headOption.foreach { init =>
            init.tpe match
              case ta: Type.Apply =>
                val tc = ta.tpe match { case Type.Name(n) => n; case _ => "" }
                if tc.nonEmpty then
                  val typeParam = ta.argClause.values.headOption.map(_.toString).getOrElse("_")
                  instances += InstanceDecl(
                    typeclass   = tc,
                    typeParam   = typeParam,
                    witnessName = witnessName,
                    fqn         = fqn(witnessName)
                  )
              case Type.Name(tc) =>
                instances += InstanceDecl(
                  typeclass   = tc,
                  typeParam   = "_",
                  witnessName = witnessName,
                  fqn         = fqn(witnessName)
                )
              case _ => ()
          }
        // extern def: `extern def foo(...): T` is rewritten by
        // `Parser.preprocessExtern` to `def foo(...): T = __extern__`
        // *before* parsing, so the AST body is literally the marker
        // `Term.Name("__extern__")`.  `EffectAnalysis.isExternDef` is
        // the authoritative recogniser — reuse it instead of guessing.
        case dd: Defn.Def if EffectAnalysis.isExternDef(dd.body) =>
          externDefs += ExportedSymbol(
            name = dd.name.value,
            fqn  = fqn(dd.name.value),
            kind = "extern",
            tpe  = defSignature(dd)
          )
        case _ => ()
      }

    scalaTrees.foreach {
      case Source(stats)     => scanStats(stats)
      case Term.Block(stats) => scanStats(stats)
      case other             => other.children.foreach {
        case s: Source     => scanStats(s.stats)
        case b: Term.Block => scanStats(b.stats)
        case _             => ()
      }
    }

    // Build exports from DefSummary list (excluding prelude builtins and params).
    val preludeNames = Set(
      "println", "print", "assert", "require", "Some", "None",
      "List", "Map", "math", "doc", "render", "serve"
    )
    val exports = allDefs
      .filterNot(d => preludeNames.contains(d.name) || d.kind == TSymbolKind.Param)
      .map { d =>
        ExportedSymbol(
          name = d.name,
          fqn  = fqn(d.name),
          kind = kindString(d.kind),
          tpe  = d.tpe.show
        )
      }
      .toList

    // Detect capabilities by walking the parsed scalameta trees.  This
    // is the v2.0 AST-based replacement for the prior string-grep heuristic
    // (which fired inside string literals, comments, and renames).
    val capabilities = detectCapabilities(scalaTrees.toList)

    ModuleInterface(
      magic         = ArtifactVersion.magic,
      abiVersion    = ArtifactVersion.current,
      pkg           = pkg,
      moduleName    = moduleName,
      moduleVersion = moduleVer,
      sourceHash    = srcHash,
      exports       = exports,
      instances     = instances.toList,
      capabilities  = capabilities,
      externDefs    = externDefs.toList,
      dependencies  = deps
    )

  /** Detect well-known capability markers by structurally walking the
   *  parsed scalameta trees and matching call-site identifiers against
   *  the canonical intrinsic table in [[CapabilityRegistry]].
   *
   *  This replaces the v1 raw-text heuristic, which:
   *    1. fired inside string literals (`val msg = "serve(...)"` falsely
   *       reported `Http`),
   *    2. fired inside `// serve(...)` comments,
   *    3. silently broke if an intrinsic was renamed without updating
   *       the grep list.
   *
   *  Best-effort shadowing avoidance: if a name is also defined as a
   *  top-level `Defn.Def` / `Defn.Val` in the module, calls to that name
   *  are assumed to refer to the user's definition and are NOT counted
   *  as capability uses.  Qualified calls (`Response.html`,
   *  `Dataset.of`) are always counted — qualified names cannot be
   *  shadowed by a bare local def.
   *
   *  Known limitations:
   *    - Local lexical scoping is not tracked: a `def serve` inside one
   *      block masks ALL uses of `serve` in the module, even from
   *      sibling blocks.  Refinement awaits Stage 5 IR-level analysis.
   *    - Imports renaming an intrinsic (`import std.http.serve as srv`)
   *      are not followed.
   */
  private def detectCapabilities(trees: List[scala.meta.Tree]): List[CapabilityDecl] =
    // Collect user-declared top-level names so we can skip calls that
    // resolve to a user def of the same simple name.
    val declared = scala.collection.mutable.Set.empty[String]
    def collectDecls(stats: List[Stat]): Unit = stats.foreach {
      // `extern def foo` is a declaration of an intrinsic, NOT a user
      // implementation that should shadow it.  Skip externs so calls to
      // `foo` are still recognised as capability uses.
      case d: Defn.Def if !EffectAnalysis.isExternDef(d.body) =>
        declared += d.name.value
      case d: Defn.Val    => d.pats.foreach { case Pat.Var(n) => declared += n.value; case _ => () }
      case d: Defn.Var    => d.pats.foreach { case Pat.Var(n) => declared += n.value; case _ => () }
      case d: Defn.Object => declared += d.name.value
      case d: Defn.Class  => declared += d.name.value
      case d: Defn.Trait  => declared += d.name.value
      case _              => ()
    }
    trees.foreach {
      case Source(stats)     => collectDecls(stats)
      case Term.Block(stats) => collectDecls(stats)
      case other             => other.children.foreach {
        case s: Source     => collectDecls(s.stats)
        case b: Term.Block => collectDecls(b.stats)
        case _             => ()
      }
    }

    val caps = scala.collection.mutable.Set.empty[String]

    /** Record a bare-name call if it isn't shadowed by a user decl. */
    def hitBare(name: String): Unit =
      if !declared.contains(name) then
        CapabilityRegistry.capabilityFor(name).foreach(caps += _)

    /** Record a qualified call `Qual.method`.  Qualified names aren't
     *  shadowable by a top-level bare def, so always count. */
    def hitQualified(qual: String, method: String): Unit =
      val full = s"$qual.$method"
      CapabilityRegistry.capabilityFor(full).foreach(caps += _)
      // Pure qualifier surface (e.g. `crypto.*`) maps wholesale to a capability.
      CapabilityRegistry.capabilityForQualifier(qual).foreach(caps += _)

    def walk(tree: scala.meta.Tree): Unit =
      tree match
        // f(args) where f is a bare name
        case Term.Apply.After_4_6_0(Term.Name(n), _) =>
          hitBare(n)
        // qual.method(args)
        case Term.Apply.After_4_6_0(Term.Select(Term.Name(qual), Term.Name(method)), _) =>
          hitQualified(qual, method)
        // bare `qual.method` reference (no parens) — e.g. used as a value.
        case Term.Select(Term.Name(qual), Term.Name(method)) =>
          hitQualified(qual, method)
        case _ => ()
      tree.children.foreach(walk)

    trees.foreach(walk)
    caps.toList.sorted.map(CapabilityDecl(_))

  private def kindString(k: TSymbolKind): String = k match
    case TSymbolKind.Val     => "val"
    case TSymbolKind.Var     => "var"
    case TSymbolKind.Def     => "def"
    case TSymbolKind.Type    => "type"
    case TSymbolKind.Class   => "class"
    case TSymbolKind.Object  => "object"
    case TSymbolKind.Trait   => "trait"
    case TSymbolKind.Enum    => "enum"
    case TSymbolKind.Param   => "param"
    case TSymbolKind.TypeParam => "typeparam"
