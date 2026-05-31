package scalascript.cli

import scalascript.parser.Parser
import scalascript.ast.*
import scalascript.typer.Typer
import scalascript.codegen.{JsGen, JvmGen}
import scalascript.artifact.{InterfaceExtractor, ArtifactIO, JvmArtifactIO, JsArtifactIO}
import RenderHelpers.*

/** Compile `path` via JvmGen and write the result to a `.scjvm` artifact at
 *  `scjvmPath` for future cache hits.  Returns the generated Scala 3 source. */
private[cli] def compileJvmAndCache(
    path:            os.Path,
    baseName:        String,
    scjvmPath:       os.Path,
    frontendOverride: Option[String] = None
): String =
  val module    = Parser.parse(os.read(path))
  val baseDir   = Some(path / os.up)
  val source    = JvmGen.generate(module, baseDir, frontendOverride = frontendOverride)
  scala.util.Try {
    val sourceHash = InterfaceExtractor.sha256(os.read.bytes(path))
    val moduleId   = module.manifest.flatMap(_.name).getOrElse(baseName)
    val pkg        = module.manifest.flatMap(_.pkg).getOrElse(Nil)
    val moduleName = module.manifest.flatMap(_.name)
    os.makeDir.all(scjvmPath / os.up)
    JvmArtifactIO.writeJvmFile(moduleId, pkg, moduleName, sourceHash, source, Nil, scjvmPath)
  }.recover { case e => System.err.println(s"[warn] artifact write failed: $e") }
  source

/** Compile a single dependency module into `.scim` + `.scjvm` artifacts
 *  living in `artifactDir`.  Used by `compile-jvm` auto-resolution to
 *  pre-build every transitive dep before the target.
 *
 *  Type-checks the dep against the interfaces already present in
 *  `artifactDir` (so chains like `c → b → a` see `a`'s interface when
 *  type-checking `b`).
 *
 *  Throws on type-check failures or codegen exceptions; the caller
 *  catches and surfaces a non-zero exit code. */
private[cli] def compileJvmDepInto(
    dep:         AutoResolve.Node,
    artifactDir: os.Path,
    scimPath:    os.Path,
    scjvmPath:   os.Path,
    bytecode:    Boolean = false
): Unit =
  val module     = dep.module
  // Surface a structured parse-error diagnostic for any failing block in
  // the dep BEFORE we try to type-check / codegen it, then fail hard.
  if reportCodeBlockParseErrors(module, dep.path.toString) then
    throw new RuntimeException(s"parse error in dep ${dep.path.last} (see diagnostic above)")
  // Build the interfaces map from `.scim` files already in the artifact
  // dir.  Deeper deps will have been compiled first (topo order) so
  // their interfaces are available when type-checking this one.
  val interfaces: Map[String, scalascript.ir.ModuleInterface] =
    if os.exists(artifactDir) && os.isDir(artifactDir) then
      os.list(artifactDir).filter(_.ext == "scim").flatMap { p =>
        ArtifactIO.readInterfaceFile(p) match
          case Right(iface) => List(p.last.stripSuffix(".scim") -> iface)
          case Left(_)      => Nil
      }.toMap
    else Map.empty
  val typed =
    if interfaces.isEmpty then Typer.typeCheck(module)
    else Typer.typeCheckWithInterfaces(module, interfaces)
  if typed.hasErrors then
    val msgs = typed.errors.map(_.msg).mkString("; ")
    throw new RuntimeException(s"type errors in dep ${dep.path.last}: $msgs")

  // .scim first so siblings parsed in topo order pick it up.
  val iface = InterfaceExtractor.extract(module, dep.sourceBytes)
  ArtifactIO.writeInterfaceFile(iface, scimPath)

  // Now the .scjvm.  Source-only deps still get the full preamble; in
  // bytecode mode the dep emits user-code-only with `import _ssc_runtime.*`
  // and the shared `_runtime.scjvm-runtime` covers the helpers.
  val baseDir     = Some(dep.path / os.up)
  // Bytecode mode also produces the gen→orig .ssc line map for SMAP
  // injection at link time (see compileJvmCommand for context).
  val (scalaSource, userLineMap): (String, Map[Int, Int]) =
    if bytecode then JvmGen.generateUserOnlyWithLineMap(module, baseDir)
    else            (JvmGen.generate(module, baseDir), Map.empty[Int, Int])
  val pkg         = module.manifest.flatMap(_.pkg).getOrElse(Nil)
  val moduleName  = module.manifest.flatMap(_.name)
  val sourceHash  = InterfaceExtractor.sha256(dep.sourceBytes)
  val baseName    = dep.path.last.stripSuffix(".ssc")
  val rawImports  = collectImports(module.sections)
  val depAliases  = module.manifest.toList.flatMap(_.dependencies.keys)
  val imports     = (rawImports ++ depAliases).distinct.toList
  val moduleId    = moduleName.getOrElse(baseName)

  val moduleCaps: Set[String] =
    if !bytecode then Set.empty
    else JvmGen.detectCapabilities(module, baseDir).map(JvmGen.Capability.encode)

  // When --bytecode is set, also produce a classBundle for this dep so
  // the linker has a real .class artifact to pack.  Wire previously-built
  // deps' classBundles + the shared `_runtime.scjvm-runtime` into the
  // classpath for this scala-cli invocation (transitive deps were
  // compiled first in topo order; runtime is regenerated if needed).
  val classBundleOpt: Option[String] =
    if !bytecode then None
    else
      val depCaps   = unionDepCapabilities(artifactDir)
      val unionCaps = depCaps ++ moduleCaps
      ensureRuntimeArtifact(artifactDir, unionCaps)
      val depCpDir = extractDepBundlesForCompile(artifactDir)
      try
        JvmBytecode.compileAndPack(scalaSource, List(depCpDir), scriptName = moduleId) match
          case Right(b64) => Some(b64)
          case Left(err)  =>
            throw new RuntimeException(s"--bytecode dep ${dep.path.last}: $err")
      finally
        scala.util.Try(os.remove.all(depCpDir))

  val lineMapStr: Map[String, Int] = userLineMap.map { (g, o) => g.toString -> o }
  JvmArtifactIO.writeJvmFile(
    moduleId, pkg, moduleName, sourceHash, scalaSource, imports, scjvmPath,
    classBundleOpt, moduleCaps.toList.sorted,
    sectionHashes = Map.empty, lineMap = lineMapStr)

/** True when `scjvmPath` exists and its `classBundle` is non-empty.
 *  Used by `compile-jvm --bytecode` to detect that an existing source-only
 *  `.scjvm` artifact must be recompiled with a class bundle this round. */
private[cli] def scjvmHasClassBundle(scjvmPath: os.Path): Boolean =
  if !os.exists(scjvmPath) then false
  else JvmArtifactIO.readJvmFile(scjvmPath) match
    case Right(a) => a.classBundle.exists(_.nonEmpty)
    case Left(_)  => false

/** Walk `artifactDir` for `.scjvm` files, extract every non-empty
 *  `classBundle` into a fresh temp dir, and return that dir.
 *
 *  The result is suitable for passing to scala-cli as `--jar <dir>` so
 *  cross-module references in the source being compiled resolve at compile
 *  time.  Caller is responsible for deleting the temp dir.
 *
 *  Returns the temp dir even when no bundles were found — callers can pass
 *  an empty dir to scala-cli without harm. */
private[cli] def extractDepBundlesForCompile(artifactDir: os.Path): os.Path =
  val dest = os.temp.dir(prefix = "ssc-bytecode-deps-")
  if os.isDir(artifactDir) then
    for p <- os.list(artifactDir).filter(_.ext == "scjvm") do
      JvmArtifactIO.readJvmFile(p) match
        case Right(a) =>
          a.classBundle.foreach(b =>
            if b.nonEmpty then JvmBytecode.extractBundleTo(b, dest)
          )
        case Left(_) => () // skip malformed dep artifacts silently
    // v2.0 Phase 2 — also pull the shared `_runtime.scjvm-runtime` classBundle
    // into the dep classpath so user code that references `_show` / `_handle`
    // / `route` (via wildcard import of `_ssc_runtime`) resolves at compile
    // time.  Missing or malformed runtime artifacts are skipped silently —
    // pre-Phase-2 .scjvm files ship the full preamble in their own bundles.
    val runtimePath = artifactDir / "_runtime.scjvm-runtime"
    if os.exists(runtimePath) then
      JvmArtifactIO.readRuntimeFile(runtimePath) match
        case Right(rt) =>
          if rt.classBundle.nonEmpty then
            JvmBytecode.extractBundleTo(rt.classBundle, dest)
        case Left(_) => ()
  dest

/** v2.0 Phase 2 — read every `.scjvm` in `artifactDir` and return the
 *  union of their `capabilities` fields.  Used by `compile-jvm --bytecode`
 *  to compute whether the existing `_runtime.scjvm-runtime` covers the
 *  capability set the current build needs. */
private[cli] def unionDepCapabilities(artifactDir: os.Path): Set[String] =
  if !os.isDir(artifactDir) then Set.empty
  else
    val acc = scala.collection.mutable.Set.empty[String]
    for p <- os.list(artifactDir).filter(_.ext == "scjvm") do
      JvmArtifactIO.readJvmFile(p) match
        case Right(a) => acc ++= a.capabilities
        case Left(_)  => ()
    acc.toSet

/** v2.0 Phase 2 — ensure the shared `_runtime.scjvm-runtime` in
 *  `artifactDir` covers `requiredCapabilities`.  Regenerates it via
 *  `JvmGen.generateRuntime` + `JvmBytecode.compileRuntimeAndPack` when
 *  missing or when the existing runtime's capability set is a strict
 *  subset of the required set.  No-op when the existing runtime already
 *  covers (≥) the required capabilities.
 *
 *  Throws on scala-cli compile failure; caller catches and surfaces a
 *  non-zero exit code.
 *
 *  Returns the path of the (possibly freshly-written) runtime artifact. */
private[cli] def ensureRuntimeArtifact(
    artifactDir:          os.Path,
    requiredCapabilities: Set[String]
): os.Path =
  os.makeDir.all(artifactDir)
  val runtimePath = artifactDir / "_runtime.scjvm-runtime"
  val existing: Option[scalascript.ir.ModuleJvmRuntimeArtifact] =
    if !os.exists(runtimePath) then None
    else JvmArtifactIO.readRuntimeFile(runtimePath).toOption

  // Decode required strings to capabilities.  Unknown strings are
  // surfaced as a hard error — we'd rather fail loudly than silently
  // emit a runtime that's missing a block the module assumes is present.
  val requiredCaps: Set[scalascript.codegen.JvmGen.Capability] =
    requiredCapabilities.map { s =>
      scalascript.codegen.JvmGen.Capability.decode(s).getOrElse(
        throw new RuntimeException(s"Unknown JVM capability: '$s' " +
          "(.scjvm written by a newer compiler version?)")
      )
    }

  val needsRegen = existing match
    case None      => true
    case Some(art) =>
      val have = art.capabilities.toSet
      // Regenerate when the required set is not a subset of what we
      // already have.  Going FROM a superset to a subset is a no-op
      // (the shared runtime stays valid; its classes are still on the
      // classpath, and any unused ones are unused without harm).
      !requiredCapabilities.subsetOf(have)

  if !needsRegen then runtimePath
  else
    val runtimeSource = scalascript.codegen.JvmGen.generateRuntime(requiredCaps)
    val sourceHash    = scalascript.artifact.InterfaceExtractor.sha256(
                          runtimeSource.getBytes("UTF-8"))
    JvmBytecode.compileRuntimeAndPack(runtimeSource) match
      case Right(b64) =>
        JvmArtifactIO.writeRuntimeFile(
          capabilities = requiredCapabilities.toList.sorted,
          sourceHash   = sourceHash,
          classBundle  = b64,
          path         = runtimePath
        )
        runtimePath
      case Left(err) =>
        throw new RuntimeException(s"--bytecode: shared runtime compile failed:\n$err")

/** v2.0 Phase 2 (JS) — read every `.scjs` in `artifactDir` and return the
 *  union of their `capabilities` fields.  Used by `compile-js` to compute
 *  whether the existing `_runtime.scjs-runtime` covers the capability set
 *  the current build needs. */
private[cli] def unionDepCapabilitiesJs(artifactDir: os.Path): Set[String] =
  if !os.isDir(artifactDir) then Set.empty
  else
    val acc = scala.collection.mutable.Set.empty[String]
    for p <- os.list(artifactDir).filter(_.ext == "scjs") do
      JsArtifactIO.readJsFile(p) match
        case Right(a) => acc ++= a.capabilities
        case Left(_)  => ()
    acc.toSet

/** v2.0 Phase 2 (JS) — ensure the shared `_runtime.scjs-runtime` in
 *  `artifactDir` covers `requiredCapabilities`.  Regenerates it via
 *  `JsGen.generateRuntime` when missing or when the existing runtime's
 *  capability set is a strict subset of the required set.  No-op when
 *  the existing runtime already covers (≥) the required capabilities.
 *
 *  Returns the path of the (possibly freshly-written) runtime artifact. */
private[cli] def ensureJsRuntimeArtifact(
    artifactDir:          os.Path,
    requiredCapabilities: Set[String]
): os.Path =
  os.makeDir.all(artifactDir)
  val runtimePath = artifactDir / "_runtime.scjs-runtime"
  val existing: Option[scalascript.ir.ModuleJsRuntimeArtifact] =
    if !os.exists(runtimePath) then None
    else JsArtifactIO.readRuntimeFile(runtimePath).toOption

  // Decode required strings to capabilities.  Unknown strings are a hard
  // error — better to fail loudly than silently emit a runtime missing a
  // block the module assumes is present.
  val requiredCaps: Set[scalascript.codegen.JsGen.Capability] =
    requiredCapabilities.map { s =>
      scalascript.codegen.JsGen.Capability.decode(s).getOrElse(
        throw new RuntimeException(s"Unknown JS capability: '$s' " +
          "(.scjs written by a newer compiler version?)")
      )
    }

  val needsRegen = existing match
    case None      => true
    case Some(art) =>
      val have = art.capabilities.toSet
      !requiredCapabilities.subsetOf(have)

  if !needsRegen then runtimePath
  else
    val runtimeSource = scalascript.codegen.JsGen.generateRuntime(requiredCaps)
    val sourceHash    = scalascript.artifact.InterfaceExtractor.sha256(
                          runtimeSource.getBytes("UTF-8"))
    JsArtifactIO.writeRuntimeFile(
      capabilities = requiredCapabilities.toList.sorted,
      sourceHash   = sourceHash,
      jsSource     = runtimeSource,
      path         = runtimePath
    )
    runtimePath

/** Compile a single dependency module into `.scim` + `.scjs` artifacts
 *  living in `artifactDir`.  Mirror of `compileJvmDepInto`. */
private[cli] def compileJsDepInto(
    dep:         AutoResolve.Node,
    artifactDir: os.Path,
    scimPath:    os.Path,
    scjsPath:    os.Path
): Unit =
  val module = dep.module
  if reportCodeBlockParseErrors(module, dep.path.toString) then
    throw new RuntimeException(s"parse error in dep ${dep.path.last} (see diagnostic above)")
  val interfaces: Map[String, scalascript.ir.ModuleInterface] =
    if os.exists(artifactDir) && os.isDir(artifactDir) then
      os.list(artifactDir).filter(_.ext == "scim").flatMap { p =>
        ArtifactIO.readInterfaceFile(p) match
          case Right(iface) => List(p.last.stripSuffix(".scim") -> iface)
          case Left(_)      => Nil
      }.toMap
    else Map.empty
  val typed =
    if interfaces.isEmpty then Typer.typeCheck(module)
    else Typer.typeCheckWithInterfaces(module, interfaces)
  if typed.hasErrors then
    val msgs = typed.errors.map(_.msg).mkString("; ")
    throw new RuntimeException(s"type errors in dep ${dep.path.last}: $msgs")

  val iface = InterfaceExtractor.extract(module, dep.sourceBytes)
  ArtifactIO.writeInterfaceFile(iface, scimPath)

  val baseDir    = Some(dep.path / os.up)
  // v2.0 Phase 2: user-code-only emit; shared runtime ships separately.
  val jsSource   = JsGen.generateUserOnly(module, baseDir)
  val pkg        = module.manifest.flatMap(_.pkg).getOrElse(Nil)
  val moduleName = module.manifest.flatMap(_.name)
  val sourceHash = InterfaceExtractor.sha256(dep.sourceBytes)
  val baseName   = dep.path.last.stripSuffix(".ssc")
  val rawImports = collectImports(module.sections)
  val depAliases = module.manifest.toList.flatMap(_.dependencies.keys)
  val imports    = (rawImports ++ depAliases).distinct.toList
  val moduleId   = moduleName.getOrElse(baseName)
  val moduleCaps: Set[String] =
    JsGen.detectCapabilities(module, baseDir).map(JsGen.Capability.encode)
  // Top-level caller (`compileJsCommand`) regenerates the shared
  // `_runtime.scjs-runtime` once per build using the union of every
  // module's capabilities.  Here we only persist this dep's capability
  // list — the runtime ensure step happens after all deps + the target
  // have been compiled.
  JsArtifactIO.writeJsFile(moduleId, pkg, moduleName, sourceHash, jsSource, imports, scjsPath, moduleCaps.toList.sorted)

/** Collect raw import paths from a section recursively.  Used by
 *  `compile-jvm` to populate `ModuleJvmArtifact.imports` as a hint for the
 *  linker. */
private[cli] def collectImports(sections: List[Section]): List[String] =
  sections.flatMap { s =>
    s.content.collect { case imp: Content.Import => imp.path } ++
      collectImports(s.subsections)
  }

/** v2.0 Phase 5 — discover pre-compiled artifacts shipped alongside cached
 *  `dep:` sources and stage them into the consumer's artifact dir.
 *
 *  For every `dep:` import in `module`:
 *  - Resolve the cached `.ssc` via `ImportResolver`.
 *  - Look for `.ssc-artifacts/<basename>.<ext>` alongside it.
 *  - If found, copy into `targetArtifactDir` so the typer (and the linker)
 *    see the dep's pre-compiled `.scim` / `.scjvm` / `.scjs` directly.
 *
 *  Returns the count of artifacts staged per extension (for logging).
 *  Source-fallback: when no artifacts ship alongside, the dep's source is
 *  parsed via the regular `JvmGen.inlineImport` / `JsGen` path later, so
 *  callers can be unconditional in invoking this helper.
 *
 *  Corrupt artifacts (bad magic / abi mismatch) surface a clear error and
 *  the file is skipped — the caller can choose to fall back to source. */
private[cli] def stagePrecompiledDepArtifacts(
    module:             Module,
    sourcePath:         os.Path,
    targetArtifactDir:  os.Path,
    desiredExts:        List[String]
): Map[String, Int] =
  import scalascript.imports.ImportResolver
  val deps = module.manifest.map(_.dependencies).getOrElse(Map.empty)
  val baseDir = sourcePath / os.up
  val depImports = collectImports(module.sections).filter(_.startsWith("dep:"))
  if depImports.isEmpty then return Map.empty
  os.makeDir.all(targetArtifactDir)
  val tallies = scala.collection.mutable.Map.empty[String, Int]
  for depUri <- depImports.distinct do
    val resolved =
      try Some(ImportResolver.resolve(depUri, baseDir, deps, lockPath = None))
      catch case _: Throwable => None
    resolved match
      case Some(sscPath) =>
        for ext <- desiredExts do
          ImportResolver.findArtifactAlongside(sscPath, ext) match
            case Some(art) =>
              // Validate envelope before staging — bad magic must surface
              // a clear error so the user knows the .sscpkg is broken.
              val checkResult: Either[String, Unit] = ext match
                case "scim"  =>
                  scalascript.artifact.ArtifactIO.readInterfaceFile(art).map(_ => ())
                case "scjvm" =>
                  scalascript.artifact.JvmArtifactIO.readJvmFile(art).map(_ => ())
                case "scjs"  =>
                  scalascript.artifact.JsArtifactIO.readJsFile(art).map(_ => ())
                case _       => Right(())
              checkResult match
                case Right(_) =>
                  val dest = targetArtifactDir / art.last
                  if !os.exists(dest) then
                    os.copy.over(art, dest, createFolders = true)
                  tallies(ext) = tallies.getOrElse(ext, 0) + 1
                case Left(err) =>
                  throw new RuntimeException(
                    s"compile: corrupt pre-compiled dep artifact $art: $err"
                  )
            case None => ()
      case None => ()
  tallies.toMap
