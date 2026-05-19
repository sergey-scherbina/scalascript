package scalascript.artifact

import scalascript.ir.*
import scalascript.ast
import scalascript.transform.EffectAnalysis
import scalascript.typer.{Typer, DefSummary, SType, SymbolKind as TSymbolKind}
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

    // When `package: foo.bar` is set in the front-matter, the parser wraps
    // every code block in nested `object foo: object bar: <body>` shells
    // (see `Parser.wrapSectionInPackage`).  The typer's top-level walk then
    // sees only `object foo` as a top-level definition, NOT the inner defs
    // that consumers actually want to import.  Strip the outer-shell
    // objects from the typer-derived list here; we re-collect the inner
    // user defs from the AST below (`scalaTrees` is populated next).
    if pkg.nonEmpty then
      val shellNames = pkg.toSet
      val filtered = allDefs.filterNot { d =>
        d.kind == TSymbolKind.Object && shellNames.contains(d.name)
      }
      allDefs.clear()
      allDefs ++= filtered

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

    // ── Package-shell walk ──────────────────────────────────────────────
    //
    // `Parser.wrapSectionInPackage` wraps every code block in
    // `object pkg(0): object pkg(1): ... <body>` when the manifest sets
    // `package: foo.bar`.  Descend through the matching `Defn.Object`
    // shells in each parsed `Source` and surface the inner top-level
    // user definitions — without this step, a `case class DocLine`
    // under `package: std.dsl` is invisible to consumers of the `.scim`.
    //
    // Inner stats are also fed back into `allDefs` so they appear in the
    // exports list with the right `fqn` (joined via `_` by `fqn(name)`).
    def unwrapPackage(stats: List[Stat], remaining: List[String]): List[Stat] =
      remaining match
        case Nil => stats
        case head :: tail =>
          stats.collectFirst {
            case obj: Defn.Object if obj.name.value == head =>
              unwrapPackage(obj.templ.body.stats, tail)
          }.getOrElse(Nil)

    /** Inner top-level stats reached after stripping the package shell.
     *  Empty when no `package:` is set, so the rest of the pipeline
     *  (extern / given walks below) sees the original trees unchanged. */
    val packageInnerStats: List[Stat] =
      if pkg.isEmpty then Nil
      else
        scalaTrees.toList.collect { case s: Source => s.stats }
          .flatMap(stats => unwrapPackage(stats, pkg))

    // Synthesize DefSummary entries for the package-inner top-level defs
    // so they flow through the normal `exports` pipeline (including the
    // manifest `exports:` filter).  Types are best-effort `Any` — the
    // typer doesn't currently descend into objects, so we cannot recover
    // richer types without re-running it on the inner stats.
    if pkg.nonEmpty then
      packageInnerStats.foreach {
        case d: Defn.Val =>
          d.pats.foreach {
            case Pat.Var(n) =>
              allDefs += DefSummary(n.value, TSymbolKind.Val, SType.Any, Nil)
            case _ => ()
          }
        case d: Defn.Var =>
          d.pats.foreach {
            case Pat.Var(n) =>
              allDefs += DefSummary(n.value, TSymbolKind.Var, SType.Any, Nil)
            case _ => ()
          }
        case d: Defn.Def =>
          // Skip extern markers — they are reported via `externDefs`, not
          // the generic exports list.
          if !EffectAnalysis.isExternDef(d.body) then
            allDefs += DefSummary(d.name.value, TSymbolKind.Def, SType.Any, Nil)
        case d: Defn.Class  => allDefs += DefSummary(d.name.value, TSymbolKind.Class,  SType.Any, Nil)
        case d: Defn.Object => allDefs += DefSummary(d.name.value, TSymbolKind.Object, SType.Any, Nil)
        case d: Defn.Trait  => allDefs += DefSummary(d.name.value, TSymbolKind.Trait,  SType.Any, Nil)
        case d: Defn.Enum   => allDefs += DefSummary(d.name.value, TSymbolKind.Enum,   SType.Any, Nil)
        case _              => ()
      }

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

    // ── Recursive nested-member extraction ────────────────────────────────
    //
    // Maximum depth for walking nested `Defn.Object` stats.  An object at
    // depth N may carry `nested` entries; an object at depth `MaxNestedDepth`
    // is treated as opaque (its `nested` stays `Nil`).  Three levels cover
    // the common cases (`pkg.sub.member`, `pkg.outer.inner.member`) without
    // unbounded recursion on pathological inputs.
    //
    // TODO(v2.x): lift to unbounded depth once we measure the .scim size /
    // typer cost; the strict-mode resolver doesn't need a hard cap.
    val MaxNestedDepth = 3

    /** Build an `ExportedSymbol` for a single top-level-ish stat, recursing
     *  into `Defn.Object` bodies up to `MaxNestedDepth - depth` further
     *  levels.  Returns `None` for stats that are not user-facing exports
     *  (e.g. `Defn.Given`, extern defs, bare expressions).  `parentFqn`
     *  is the dotted-then-underscore-joined FQN prefix of the enclosing
     *  symbol (e.g. `"pkg_Foo"`); the returned symbol's `fqn` is
     *  `s"${parentFqn}_${name}"` (or just `name` when `parentFqn` is empty). */
    def buildNestedSymbol(stat: Stat, parentFqn: String, depth: Int): Option[ExportedSymbol] =
      def joinFqn(name: String): String =
        if parentFqn.isEmpty then name else s"${parentFqn}_$name"
      stat match
        case d: Defn.Def if !EffectAnalysis.isExternDef(d.body) =>
          Some(ExportedSymbol(
            name = d.name.value,
            fqn  = joinFqn(d.name.value),
            kind = "def",
            tpe  = "Any"
          ))
        case d: Defn.Val =>
          // Multi-pat `val (a, b) = …` is rare here; surface each Pat.Var.
          d.pats.collectFirst { case Pat.Var(n) => n.value }.map { n =>
            ExportedSymbol(name = n, fqn = joinFqn(n), kind = "val", tpe = "Any")
          }
        case d: Defn.Var =>
          d.pats.collectFirst { case Pat.Var(n) => n.value }.map { n =>
            ExportedSymbol(name = n, fqn = joinFqn(n), kind = "var", tpe = "Any")
          }
        case d: Defn.Class =>
          Some(ExportedSymbol(
            name = d.name.value,
            fqn  = joinFqn(d.name.value),
            kind = "class",
            tpe  = "Any"
          ))
        case d: Defn.Trait =>
          Some(ExportedSymbol(
            name = d.name.value,
            fqn  = joinFqn(d.name.value),
            kind = "trait",
            tpe  = "Any"
          ))
        case d: Defn.Enum =>
          Some(ExportedSymbol(
            name = d.name.value,
            fqn  = joinFqn(d.name.value),
            kind = "enum",
            tpe  = "Any"
          ))
        case d: Defn.Object =>
          val nested =
            if depth + 1 >= MaxNestedDepth then Nil
            else d.templ.body.stats.flatMap(s =>
              buildNestedSymbol(s, joinFqn(d.name.value), depth + 1)
            )
          Some(ExportedSymbol(
            name   = d.name.value,
            fqn    = joinFqn(d.name.value),
            kind   = "object",
            tpe    = "Any",
            nested = nested
          ))
        case _ => None

    /** Resolve, in `stats`, the nested-member list for a `Defn.Object`
     *  named `objName`.  Used to back-fill `nested` on top-level exports
     *  whose source-form is `object Foo: …` — the surrounding `rawExports`
     *  build path emits the parent as a flat `ExportedSymbol`, then we
     *  look up its body here and populate `nested` from the AST. */
    def nestedForObject(stats: List[Stat], objName: String, parentFqn: String): List[ExportedSymbol] =
      stats.collectFirst {
        case obj: Defn.Object if obj.name.value == objName =>
          obj.templ.body.stats.flatMap(s =>
            buildNestedSymbol(s, parentFqn, depth = 1)
          )
      }.getOrElse(Nil)

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
      case Source(stats)     =>
        // When a package shell wraps the body, scan the inner stats so
        // `extern def` / `given` declarations below the shell are still
        // discovered.  Otherwise scan the top-level stats as before.
        if pkg.nonEmpty then scanStats(unwrapPackage(stats, pkg))
        else scanStats(stats)
      case Term.Block(stats) => scanStats(stats)
      case other             => other.children.foreach {
        case s: Source     => scanStats(s.stats)
        case b: Term.Block => scanStats(b.stats)
        case _             => ()
      }
    }

    // Build exports from DefSummary list (excluding prelude builtins and params).
    //
    // The prelude-name filter only applies to non-packaged modules.  When
    // `package: foo` is set, the typer's top-level scan saw only the
    // shell `object foo` (already stripped above) — the remaining entries
    // come from the explicit AST package walk, so they are user defs by
    // construction and must NOT be confused with the typer's auto-injected
    // prelude names like `render` or `serve`.
    val preludeNames = Set(
      "println", "print", "assert", "require", "Some", "None",
      "List", "Map", "math", "doc", "render", "serve"
    )
    // Source-level top-level stats from which we recover nested-object
    // member lists.  With `package: foo.bar`, the user's defs live under
    // the synthetic shell and were already unwrapped above; without it,
    // walk every parsed `Source` directly.
    val topLevelStats: List[Stat] =
      if pkg.nonEmpty then packageInnerStats
      else scalaTrees.toList.collect { case s: Source => s.stats }.flatten

    val rawExports = allDefs
      .filterNot { d =>
        d.kind == TSymbolKind.Param ||
          (pkg.isEmpty && preludeNames.contains(d.name))
      }
      .map { d =>
        val nested =
          if d.kind == TSymbolKind.Object then
            nestedForObject(topLevelStats, d.name, fqn(d.name))
          else Nil
        ExportedSymbol(
          name   = d.name,
          fqn    = fqn(d.name),
          kind   = kindString(d.kind),
          tpe    = d.tpe.show,
          nested = nested
        )
      }
      .toList

    // Respect `exports:` in the manifest: if non-empty, expose ONLY those
    // names in the interface so private helpers stay hidden from consumers.
    // An absent / empty `exports:` keeps the default behaviour (export
    // everything top-level).
    val manifestExports = module.manifest.map(_.exports).getOrElse(Nil)
    val exports =
      if manifestExports.isEmpty then rawExports
      else
        val allow = manifestExports.toSet
        rawExports.filter(s => allow.contains(s.name))

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
