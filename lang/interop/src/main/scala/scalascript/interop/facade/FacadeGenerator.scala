package scalascript.interop.facade

import scalascript.ir.*
import scalascript.artifact.ArtifactIO

/** Tier 2 of the Scala ↔ ScalaScript interop (docs/specs/scala-interop.md).
 *
 *  Reads `.scim` artifacts and emits Scala 3 source that re-exports the
 *  v2.0 mangled symbols under their natural dotted FQN.  Consumed by:
 *    - the upcoming `sbt-scalascript-interop` plugin via
 *      `Compile / sourceGenerators`,
 *    - the proposed `ssc link --emit-scala-facade` flag (Tier 4),
 *    - any ad-hoc tooling that wants to expose ScalaScript artifacts
 *      to Scala consumers without manual demangling.
 *
 *  Output strategy (conservative v0.1):
 *    1. One `.scala` file per Scala package (the dotted prefix shared
 *       by all symbols in that scope).
 *    2. Each top-level symbol becomes `export _root_._ssc_runtime.<mangled>
 *       as <name>` — Scala 3's `export` keyword does the rest.
 *    3. Deep-nested entries (depth > 1 below the module's `pkg`) are
 *       skipped with a comment.  JvmGen's emission shape for nested
 *       object members is still being pinned down; once the contract
 *       is precise, the generator can re-emit them as member accesses
 *       on the parent's exported alias (no source change for consumers).
 *    4. Header comment notes the source `.scim` so a consumer reading
 *       generated code can trace back to the originating module.
 *
 *  Zero runtime overhead: `export` is a compile-time alias.  Scala 3's
 *  inliner elides the bridge call at the use site.  The interop layer
 *  is purely syntactic.
 *
 *  Forward-compat fallback: artifacts written before Tier 1 (no
 *  `scalaFacade` field) recompute the table on the fly using
 *  `Linker.mangle` so consumers can adopt the library without
 *  recompiling their `.ssc` sources. */
object FacadeGenerator:

  /** Maps relative file path (e.g. `"std/eq.scala"`) → file contents.
   *  Caller decides where to drop them (typically
   *  `target/scala-3.x/src_managed/scalascript-facade/`). */
  type FacadeSources = Map[String, String]

  /** ScalaScript's emitted JVM runtime wraps everything inside this
   *  top-level object (Phase-2 deep refactor — `_runtime.scjvm-runtime`).
   *  Facade `export`s point through here. */
  private val RuntimeObjectPrefix = "_ssc_runtime."

  /** Walk an artifact directory for `.scim` files and emit a facade
   *  source per package.
   *
   *  @param artifactDir  Directory of `.scim` artifacts (typically the
   *                      one `ssc build --incremental` wrote to).
   *  @return             Map of relative file path → file contents.
   *                      Empty map if no `.scim` files were found OR
   *                      none of them had usable facade entries. */
  def generate(artifactDir: os.Path): FacadeSources =
    if !os.exists(artifactDir) || !os.isDir(artifactDir) then return Map.empty
    val scims = os.list(artifactDir).filter(p =>
      os.isFile(p) && p.ext == "scim"
    ).toList
    val ifaces = scims.flatMap(p => ArtifactIO.readInterfaceFile(p).toOption)
    generateFromInterfaces(ifaces)

  /** Same as [[generate]] but for an already-loaded list of interfaces.
   *  Useful for in-memory composition (e.g. a build tool that already
   *  holds the `ModuleInterface`s for caching reasons).  Entries for the
   *  same Scala package across interfaces are MERGED — consumers can
   *  spread a package across many `.ssc` files (std/cluster, std/dsl,
   *  std/mcp etc. do this on purpose). */
  def generateFromInterfaces(ifaces: List[ModuleInterface]): FacadeSources =
    val perPkg = scala.collection.mutable.LinkedHashMap.empty[List[String], scala.collection.mutable.LinkedHashMap[String, String]]
    val perPkgSources = scala.collection.mutable.LinkedHashMap.empty[List[String], scala.collection.mutable.ListBuffer[String]]

    for iface <- ifaces do
      val entries = facadeEntriesOf(iface)
      for (naturalFqn, mangled) <- entries do
        val parts = naturalFqn.split('.').toList
        if parts.length >= 2 then
          val pkg = parts.init
          val leaf = parts.last
          val isTopLevelOfModule = pkg == iface.pkg  // ie one segment beyond pkg
          if isTopLevelOfModule then
            val table = perPkg.getOrElseUpdate(pkg, scala.collection.mutable.LinkedHashMap.empty)
            // First occurrence wins — std-style namespace-sharing means the
            // same alias may appear in multiple .scim; pick whichever loaded
            // first and silently ignore duplicates here (linker collision
            // detection is the right place to flag real conflicts).
            if !table.contains(leaf) then table.update(leaf, mangled)
            perPkgSources.getOrElseUpdate(pkg, scala.collection.mutable.ListBuffer.empty) +=
              iface.moduleName.getOrElse("<unnamed>")
        else if parts.length == 1 then
          // Module with empty `package:` — emit at the root package.
          val table = perPkg.getOrElseUpdate(Nil, scala.collection.mutable.LinkedHashMap.empty)
          if !table.contains(parts.head) then table.update(parts.head, mangled)
          perPkgSources.getOrElseUpdate(Nil, scala.collection.mutable.ListBuffer.empty) +=
            iface.moduleName.getOrElse("<unnamed>")

    perPkg.flatMap { case (pkg, members) =>
      val sources = perPkgSources.getOrElse(pkg, Nil).distinct
      val rendered = renderPackage(pkg, members.toMap, sources.toList)
      // Skip empty results — Tier-5 identity-mapping artifacts produce
      // no facade file (natural FQN works directly; no `export` needed).
      if rendered.isEmpty then None
      else
        val path =
          if pkg.isEmpty then "_root_.scala"
          else pkg.mkString("/") + ".scala"
        Some(path -> rendered)
    }.toMap

  /** Pull facade entries from an interface, falling back to a recomputed
   *  table when the `.scim` predates Tier 1 (legacy artifact with empty
   *  `scalaFacade`).
   *
   *  Returns the natural → mangled map.  Entries identical to what Tier 1
   *  would have emitted; only the source differs. */
  def facadeEntriesOf(iface: ModuleInterface): Map[String, String] =
    if iface.scalaFacade.nonEmpty then iface.scalaFacade
    else recomputeFacade(iface)

  /** Recompute the natural → mangled facade table for a `.scim` whose
   *  `scalaFacade` field is empty (pre-Tier-1 artifact).  Mirrors
   *  `InterfaceExtractor.buildScalaFacade` (Tier-5 semantics) — identity
   *  mapping for `pkg`-decorated modules, empty for the rest. */
  private def recomputeFacade(iface: ModuleInterface): Map[String, String] =
    if iface.pkg.isEmpty then return Map.empty
    val table = scala.collection.mutable.LinkedHashMap.empty[String, String]
    def emit(sym: ExportedSymbol, parentPath: List[String]): Unit =
      val fqn = (parentPath :+ sym.name).mkString(".")
      table(fqn) = fqn
      sym.nested.foreach(child => emit(child, parentPath :+ sym.name))
    iface.exports.foreach(sym => emit(sym, iface.pkg))
    table.toMap

  /** Alias under which we import `_ssc_runtime` at the top of each
   *  facade file.  Used only on legacy paths (Tier-1-style mangled FQN
   *  pointing at `_ssc_runtime.X`); after Tier 5's package-clause
   *  emission, JVM symbols are at their natural FQN and the facade
   *  becomes empty for `package:`-decorated modules. */
  private val RuntimeImportAlias = "Ssc"

  /** Render one package's facade file.
   *
   *  Skips entries where natural FQN == mangled FQN (Tier-5 identity
   *  mapping): a Scala consumer can `import x.y.add` directly and no
   *  facade is needed.  Only entries whose mangled side starts with
   *  `_ssc_runtime.` (legacy Tier-1 artifacts) require a re-export. */
  private def renderPackage(pkg: List[String], members: Map[String, String], sourceModules: List[String]): String =
    val legacyEntries = members.filter { case (natural, mangled) =>
      mangled.startsWith(RuntimeObjectPrefix) && mangled != natural
    }
    if legacyEntries.isEmpty then
      // Post-Tier-5: the .scim's facade is the identity, JVM symbol ==
      // natural FQN, no re-export needed.  Skip the file entirely —
      // returning an empty string causes the caller to omit this package
      // from the source map (no facade file written).
      return ""

    val sb = new StringBuilder
    sb.append("// AUTO-GENERATED by scalascript-interop FacadeGenerator.\n")
    sb.append("// Do not edit by hand — regenerate via your build tool or\n")
    sb.append("// `ssc link --emit-scala-facade`.\n")
    sb.append(s"// Source modules: ${sourceModules.mkString(", ")}\n")
    sb.append("//\n")
    sb.append("// Each `export` aliases a legacy Tier-1 mangled JVM symbol\n")
    sb.append("// (`_ssc_runtime.X`) back to its natural ScalaScript FQN.\n")
    sb.append("// Zero runtime overhead — pure compile-time alias.\n")
    sb.append("// See docs/specs/scala-interop.md.\n\n")
    sb.append(s"import _ssc_runtime as $RuntimeImportAlias\n\n")
    if pkg.isEmpty then
      for (leaf, mangled) <- legacyEntries.toList.sortBy(_._1) do
        val member = mangled.stripPrefix(RuntimeObjectPrefix)
        if member == leaf then sb.append(s"export $RuntimeImportAlias.$member\n")
        else                   sb.append(s"export $RuntimeImportAlias.$member as $leaf\n")
    else
      sb.append(s"package ${pkg.mkString(".")}:\n\n")
      for (leaf, mangled) <- legacyEntries.toList.sortBy(_._1) do
        val member = mangled.stripPrefix(RuntimeObjectPrefix)
        if member == leaf then sb.append(s"  export $RuntimeImportAlias.$member\n")
        else                   sb.append(s"  export $RuntimeImportAlias.$member as $leaf\n")
    sb.toString
