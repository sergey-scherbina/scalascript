package scalascript.imports

import scalascript.backend.spi.{DepResolver, DepSpec}

import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*

/** Resolves Markdown-import link destinations to a local `os.Path`.
 *
 *  Four cases:
 *
 *  1. `dep:org.example/lib:1.2` → looked up through the dep-sources
 *     chain (`~/.config/scalascript/dep-sources`), cached at
 *     `~/.cache/scalascript/deps/`, integrity-verified via `ssc.lock`.
 *  2. Absolute URL (`http://…` / `https://…`) → fetch into the user
 *     cache `~/.cache/ssc/<host>/<path>` on first access.  If a
 *     `lockPath` is supplied the URL must be in `ssc.lock` and its
 *     SHA-256 must match.
 *  3. Relative path against a *cached* base directory → derive the
 *     corresponding URL and fetch (same lock rules as #2).
 *  4. Plain relative path → resolve against `baseDir` (no network).
 *
 *  Set `SSC_NO_NETWORK=1` to disallow any outbound fetch.
 *  Supply `lockPath` to enforce v1.19 integrity-check semantics.
 */
object ImportResolver:
  private val cacheRoot: os.Path     = os.home / ".cache" / "ssc"
  private val depCacheRoot: os.Path  = os.home / ".cache" / "scalascript" / "deps"
  private val libCacheRoot: os.Path  = os.home / ".cache" / "scalascript" / "libs"
  private val depSourcesPath: os.Path = os.home / ".config" / "scalascript" / "dep-sources"

  /** Root for library (non-relative) imports — the parent of `std/`.
   *  Set by the launcher as `-Dssc.lib.path=<dir>` (e.g. the project root).
   *  Falls back to `SSC_LIB_PATH` env var.  When absent, only relative and
   *  URL imports resolve; bare paths like `std/actors.ssc` fall through to
   *  the usual "file not found" error. */
  val libPath: Option[os.Path] =
    sys.props.get("ssc.lib.path")
      .orElse(sys.env.get("SSC_LIB_PATH"))
      .map(s => os.Path(s, os.pwd))
      .filter(os.exists)

  /** Directory of the running `ssc.jar` (or the classes dir in a dev/sbt run).
   *  Derived from the code source location; `None` if it can't be determined. */
  private[imports] def jarDir: Option[os.Path] =
    try
      val loc = getClass.getProtectionDomain.getCodeSource.getLocation
      if loc == null then None
      else
        val p = os.Path(java.nio.file.Paths.get(loc.toURI))
        Some(if os.isFile(p) then p / os.up else p)
    catch case _: Throwable => None

  /** Pure, testable std-root discovery (see `specs/std-root-resolution.md §3`).
   *  Returns the directory that *contains* a `std/` subdirectory.  First
   *  existing candidate wins:
   *    1. `ssc.std.path`  (override)
   *    2. `$SSC_STD_PATH`  (override)
   *    3. `libPath`  (`ssc.lib.path` / `$SSC_LIB_PATH`)
   *    4. `<jarDir>` when it has a `std/`  (packaged release)
   *    5. nearest ancestor of `jarDir` containing `runtime/std` → `<ancestor>/runtime`  (dev tree)
   *    6. `<home>/.scalascript` when it has a `std/`  (per-user install) */
  private[imports] def discoverStdRoot(
      prop:   Option[String],
      env:    Option[String],
      lib:    Option[os.Path],
      jar:    Option[os.Path],
      home:   os.Path
  ): Option[os.Path] =
    def existing(p: os.Path): Option[os.Path] = if os.exists(p) then Some(p) else None
    def hasStd(root: os.Path): Boolean = os.exists(root / "std")
    def devWalkUp(start: os.Path): Option[os.Path] =
      var cur = start
      var found: Option[os.Path] = None
      var continue = true
      while continue && found.isEmpty do
        if os.exists(cur / "runtime" / "std") then found = Some(cur / "runtime")
        else if cur.segmentCount == 0 then continue = false  // reached filesystem root
        else cur = cur / os.up
      found
    prop.map(s => os.Path(s, os.pwd)).flatMap(existing)
      .orElse(env.map(s => os.Path(s, os.pwd)).flatMap(existing))
      .orElse(lib)
      .orElse(jar.filter(hasStd))
      .orElse(jar.flatMap(devWalkUp))
      .orElse(existing(home / ".scalascript").filter(hasStd))

  /** Separate root searched for stdlib imports (`std/foo.ssc`).
   *  See `discoverStdRoot` for the full precedence; `ssc.std.path` remains the
   *  authoritative override, so existing launchers/tests are unaffected. */
  val stdPath: Option[os.Path] =
    discoverStdRoot(
      sys.props.get("ssc.std.path"),
      sys.env.get("SSC_STD_PATH"),
      libPath,
      jarDir,
      os.home
    )

  /** First-class SclJet library root (see `specs/scljet-standalone-library.md`).
   *  SclJet is a standalone library that lives at the repo-root `scljet/` — NOT
   *  under `runtime/std/` — yet is imported as `std/scljet/…`.  A packaged/staged
   *  install keeps a real `runtime/std/scljet/` tree, so the normal std-root
   *  resolution finds it there; this root is only consulted as a *fallback* when
   *  that fails (dev tree, after the compat symlink `v1/runtime/std/scljet` was
   *  dropped).  First existing candidate wins:
   *    1. `ssc.scljet.path`   (override)
   *    2. `$SSC_SCLJET_PATH`   (override)
   *    3. `<libPath>/scljet`   (launcher: install / repo root + `/scljet`)
   *    4. nearest ancestor of stdPath / libPath / jarDir containing `scljet/index.ssc` */
  private[imports] def discoverScljetRoot(
      prop: Option[String],
      env:  Option[String],
      lib:  Option[os.Path],
      std:  Option[os.Path],
      jar:  Option[os.Path]
  ): Option[os.Path] =
    def existing(p: os.Path): Option[os.Path] = if os.exists(p) then Some(p) else None
    def walkUp(start: os.Path): Option[os.Path] =
      var cur = start
      var found: Option[os.Path] = None
      var continue = true
      while continue && found.isEmpty do
        if os.exists(cur / "scljet" / "index.ssc") then found = Some(cur / "scljet")
        else if cur.segmentCount == 0 then continue = false  // reached filesystem root
        else cur = cur / os.up
      found
    prop.map(s => os.Path(s, os.pwd)).flatMap(existing)
      .orElse(env.map(s => os.Path(s, os.pwd)).flatMap(existing))
      .orElse(lib.map(_ / "scljet").flatMap(existing))
      .orElse(std.flatMap(walkUp))
      .orElse(lib.flatMap(walkUp))
      .orElse(jar.flatMap(walkUp))

  val scljetPath: Option[os.Path] =
    discoverScljetRoot(
      sys.props.get("ssc.scljet.path"),
      sys.env.get("SSC_SCLJET_PATH"),
      libPath,
      stdPath,
      jarDir
    )

  /** Map a `std/scljet/<rest>` library import to the first-class SclJet root
   *  (`<scljetRoot>/<rest>`).  Returns `None` for any other path, so non-scljet
   *  resolution is entirely unaffected. */
  private def scljetLibPath(rawPath: String): Option[os.Path] =
    if rawPath == "std/scljet" then scljetPath
    else if rawPath.startsWith("std/scljet/") then
      scljetPath.map(_ / os.RelPath(rawPath.stripPrefix("std/scljet/")))
    else None

  def resolve(rawPath: String, baseDir: os.Path): os.Path =
    resolve(rawPath, baseDir, Map.empty, lockPath = None)

  /** Same as `resolve(rawPath, baseDir)` but with the importing module's
   *  manifest `dependencies:` map in scope and an optional `ssc.lock` path
   *  for v1.19 integrity enforcement. */
  def resolve(
      rawPath:  String,
      baseDir:  os.Path,
      deps:     Map[String, String],
      lockPath: Option[os.Path] = None
  ): os.Path =
    val depSpec = parseDepSpec(rawPath)
    if depSpec.raw.startsWith("github:") || depSpec.raw.startsWith("jitpack:") then
      return resolveExternalDep(depSpec)

    // 1. pkg: scheme — installed plugin packages
    if depSpec.raw.startsWith("pkg:") then
      return resolvePkg(depSpec.raw)

    // 2. dep: scheme — always resolved through dep-sources chain
    if depSpec.raw.startsWith("dep:") then
      if MavenDepResolver.isMavenCoordinate(depSpec.raw) then
        return resolveExternalDep(depSpec)
      return resolveDep(depSpec.raw, lockPath)

    val pathThroughDep = applyDeps(depSpec.raw, deps).getOrElse(depSpec.raw)
    val resolved =
      if isUrl(pathThroughDep) then fetchToCache(pathThroughDep, lockPath)
      else
        val local = baseDir / os.RelPath(pathThroughDep)
        if os.exists(local) then local
        else
          // Library fallback: bare paths like `std/actors.ssc` that don't
          // exist relative to the importing file are re-resolved against
          // ssc.std.path (dev: runtime/, installed: same as lib.path).
          // Secondary fallback: libPath/runtime/<path> covers the dev-tree
          // layout (runtime/std/…) when ssc.std.path is not set separately.
          val fromLib =
            stdPath.map(_ / os.RelPath(pathThroughDep)).filter(os.exists)
              .orElse(libPath.map(_ / "runtime" / os.RelPath(pathThroughDep)).filter(os.exists))
              // First-class SclJet library root: `std/scljet/…` → `<scljetRoot>/…`
              // (dev tree, after the compat symlink was dropped).
              .orElse(scljetLibPath(pathThroughDep).filter(os.exists))
          fromLib.getOrElse(
            cacheBackedRelative(pathThroughDep, baseDir, lockPath).getOrElse(local)
          )
    // v0.9.1 — directory-as-index: `[Name](./pack)` and the path points to
    // a directory ⇒ look for `<dir>/index.ssc` inside.
    if os.exists(resolved) && os.isDir(resolved) then
      val index = resolved / "index.ssc"
      if os.exists(index) then index else resolved
    else resolved

  private def parseDepSpec(rawPath: String): DepSpec =
    val marker = " sha256:"
    val idx = rawPath.indexOf(marker)
    if idx < 0 then DepSpec(raw = rawPath)
    else
      val raw = rawPath.substring(0, idx).trim
      val sha = rawPath.substring(idx + marker.length).trim
      if sha.isEmpty then throw new RuntimeException(s"empty sha256 pin in import '$rawPath'")
      DepSpec(raw = raw, sha256 = Some(sha))

  private lazy val depResolvers: List[DepResolver] =
    val loaded = ServiceLoader.load(classOf[DepResolver]).iterator().asScala.toList
    (new GithubReleaseResolver :: new MavenDepResolver :: new JitpackResolver :: loaded)
      .groupBy(_.scheme).values.map(_.head).toList

  private def resolveExternalDep(spec: DepSpec): os.Path =
    val scheme = spec.raw.takeWhile(_ != ':')
    depResolvers.find(_.scheme == scheme) match
      case Some(resolver) => os.Path(resolver.resolve(spec))
      case None => throw new RuntimeException(s"no DepResolver registered for scheme '$scheme'")

  /** Rewrite a `<name>://<sub>` path to the URL from the deps map. */
  private def applyDeps(rawPath: String, deps: Map[String, String]): Option[String] =
    val schemeIdx = rawPath.indexOf("://")
    if schemeIdx <= 0 then return None
    val scheme = rawPath.substring(0, schemeIdx)
    if scheme == "http" || scheme == "https" || scheme == "file" then return None
    deps.get(scheme).filter(isUrl).map { base =>
      val sub = rawPath.substring(schemeIdx + 3).stripPrefix("/")
      if base.endsWith("/") then base + sub
      else base + "/" + sub
    }

  private def isUrl(s: String): Boolean =
    s.startsWith("http://") || s.startsWith("https://")

  // ─── pkg: scheme ─────────────────────────────────────────────────

  /** Default directory where installed `.sscpkg` files live.
   *  Mirrors the `pluginsDir` constant in the CLI. */
  private val pkgPluginsDir: os.Path =
    os.home / ".scalascript" / "compiler" / "plugins"

  /** Resolve `pkg:org/name:version` to the entry-point `.ssc` extracted
   *  from the matching installed `.sscpkg` archive.
   *
   *  Resolution order:
   *  1. Search `~/.scalascript/compiler/plugins/` (and any dirs registered
   *     via `BackendRegistry.addPluginDir`) for a matching `.sscpkg`.
   *  2. If not found locally, look in `LocalRegistry` for a download URL
   *     and call `ssc plugin install` logic to fetch + install first.
   *  3. If still not found, throw a clear "not installed" error.
   *
   *  When a matching archive is found, `BackendRegistry.loadAndExtract`
   *  loads the intrinsics and unpacks the source `.ssc` files to a temp dir.
   *  The entry point is `index.ssc` if present, otherwise the first `.ssc`.
   *
   *  Additive: scripts that don't use `pkg:` are unaffected. */
  private def resolvePkg(pkgUri: String): os.Path =
    val coord = pkgUri.stripPrefix("pkg:")
    import scalascript.compiler.plugin.{BackendRegistry, LocalRegistry, RemotePluginInstaller}

    // Try to find an already-installed .sscpkg.
    val maybePkg = BackendRegistry.findInstalledPkg(coord)

    val pkgPath = maybePkg.getOrElse {
      // Not installed locally — check the local registry for a URL.
      val entry = LocalRegistry.resolve(coord)
      entry match
        case None =>
          throw new RuntimeException(
            s"plugin '$coord' is not installed.\n" +
            s"Run: ssc install $coord"
          )
        case Some(e) =>
          RemotePluginInstaller.install(e.url, pkgPluginsDir).path
    }

    // Load the plugin (registers intrinsics) and extract sources.
    val srcDir = BackendRegistry.loadAndExtract(pkgPath)

    // Pick the entry-point .ssc file.
    if !os.isDir(srcDir) || os.list(srcDir).isEmpty then
      throw new RuntimeException(
        s"pkg '$coord': archive has no sources (resolved to $pkgPath)"
      )
    val index = srcDir / "index.ssc"
    if os.exists(index) then index
    else os.list(srcDir).filter(_.ext == "ssc").sorted.headOption.getOrElse(
      throw new RuntimeException(
        s"pkg '$coord': no .ssc source found in extracted archive $pkgPath"
      )
    )

  // ─── dep: scheme ─────────────────────────────────────────────────

  /** Mutable state for one BFS transitive-resolution session.
   *  Passed down the call chain so all recursive `resolveDep` calls share context. */
  private final class ResolutionState(val strictDeps: Boolean):
    val resolved: scala.collection.mutable.LinkedHashMap[String, String] =
      scala.collection.mutable.LinkedHashMap.empty  // org/name → chosen version
    val visiting: scala.collection.mutable.HashSet[String] =
      scala.collection.mutable.HashSet.empty        // currently-being-resolved keys (cycle detection)

  /** Resolve all `dep:` URIs transitively, pre-populating the local cache.
   *
   *  Returns a `SscLibLock` mapping every transitively-encountered `org/name` to
   *  its resolved version.  Called by `ssc update`.
   *
   *  @param strictDeps  when `true`, a version conflict is a hard error;
   *                     when `false` (default), the higher version wins. */
  def resolveAll(
      depUris:   List[String],
      lockPath:  Option[os.Path] = None,
      strictDeps: Boolean        = false,
  ): SscLibLock =
    val state = ResolutionState(strictDeps)
    depUris.foreach { uri =>
      if uri.startsWith("dep:") then resolveDep(uri, lockPath, Some(state))
    }
    SscLibLock(state.resolved.toMap)

  /** Resolve `dep:org.example/lib:1.2` through the dep-sources chain.
   *
   *  Resolution order:
   *  1. Local dep cache `~/.cache/scalascript/deps/<org>/<name>/<version>.ssc`
   *  2. Extracted `.ssclib` cache `~/.cache/scalascript/libs/<org>/<name>/<version>/`
   *  3. Each line in `~/.config/scalascript/dep-sources` treated as a base URL.
   *     Per source, `.ssclib` is tried before `.ssc` for backwards compat.
   *  4. Fail with a clear message pointing to `dep-sources` config.
   *
   *  When `state` is present (transitive-resolution session), cycle detection and
   *  version-conflict tracking are performed.
   *
   *  The remote registry CLI search/add path is separate from this dep-source fallback.
   */
  private def resolveDep(
      depUri:   String,
      lockPath: Option[os.Path],
      state:    Option[ResolutionState] = None,
  ): os.Path =
    val rest = depUri.stripPrefix("dep:")
    val slashIdx = rest.indexOf('/')
    if slashIdx < 0 then throw new RuntimeException(
      s"Invalid dep: URI format '$depUri' — expected dep:<org>/<name>:<version>"
    )
    val org   = rest.substring(0, slashIdx)
    val rest2 = rest.substring(slashIdx + 1)
    val colonIdx = rest2.indexOf(':')
    if colonIdx < 0 then throw new RuntimeException(
      s"Invalid dep: URI format '$depUri' — expected dep:<org>/<name>:<version>"
    )
    val name    = rest2.substring(0, colonIdx)
    val version = rest2.substring(colonIdx + 1)
    val depKey  = s"$org/$name"

    // Cycle detection
    state match
      case Some(s) if s.visiting.contains(depKey) =>
        throw new RuntimeException(
          s"Dependency cycle detected: '$depKey' depends on itself (transitively)"
        )
      case _ => ()

    // Version-conflict deduplication: compute an early-exit path if already resolved.
    val earlyExit: Option[os.Path] = state.flatMap { s =>
      s.resolved.get(depKey) match
        case Some(resolvedVer) if resolvedVer == version =>
          Some(resolveCached(depUri, version, org, name, lockPath))
        case Some(resolvedVer) if SemVer.compare(resolvedVer, version) >= 0 =>
          // Already have a ≥ version — skip (latest-wins already applied earlier)
          Some(resolveCached(depUri, resolvedVer, org, name, lockPath))
        case Some(resolvedVer) if s.strictDeps =>
          throw new RuntimeException(
            s"Version conflict: '$depKey' requires '$version' but '$resolvedVer' was already selected. " +
            s"Use --no-strict-deps to allow latest-wins resolution."
          )
        case _ => None
    }
    if earlyExit.isDefined then return earlyExit.get

    state.foreach(_.visiting += depKey)
    try
      // 1. Plain .ssc cache
      val sscCached = depCacheRoot / os.RelPath(s"$org/$name/$version.ssc")
      if os.exists(sscCached) then
        lockPath.foreach { lp =>
          val content = os.read.bytes(sscCached)
          LockFile.read(lp).fold(
            err  => throw new RuntimeException(err.getMessage),
            lock => lock.check(depUri, content).fold(err => throw new RuntimeException(err), identity)
          )
        }
        state.foreach(_.resolved(depKey) = version)
        return sscCached

      // 2. Extracted .ssclib cache
      val libDir = libCacheRoot / org / name / version
      if os.exists(libDir) && os.list(libDir).nonEmpty then
        val manifestFile = libDir / SsclibManifest.FileName
        if os.exists(manifestFile) then
          SsclibManifest.parseString(os.read(manifestFile)) match
            case scala.util.Success(manifest) =>
              val entryPath = libDir / os.RelPath(manifest.entry)
              if os.exists(entryPath) then
                state.foreach(_.resolved(depKey) = version)
                prefetchTransitiveDeps(manifest, depUri, lockPath, state)
                return entryPath
            case _ => ()

      // 3. Fetch from dep-sources: try .ssclib first, then .ssc
      val sources = depSources()
      if sources.isEmpty then
        throw new RuntimeException(
          s"No dep-sources configured for '$depUri'.\n" +
          s"Add an endpoint to ~/.config/scalascript/dep-sources, e.g.:\n" +
          s"  https://packages.example.com/ssc/"
        )

      // For each source try .ssclib then .ssc; take the first that resolves.
      val fetchResult: Option[(String, Array[Byte], Boolean)] =
        sources.iterator.flatMap { base =>
          val b      = if base.endsWith("/") then base else base + "/"
          val libUrl = s"${b}$org/$name/$version.ssclib"
          val sscUrl = s"${b}$org/$name/$version.ssc"
          Iterator(
            tryFetch(libUrl).toOption.map(pair => (pair._1, pair._2, true)),
            tryFetch(sscUrl).toOption.map(pair => (pair._1, pair._2, false)),
          ).flatten
        }.nextOption()

      fetchResult match
        case None =>
          val endpoints = sources.mkString(", ")
          throw new RuntimeException(
            s"dep '$depUri' not found at any configured endpoint ($endpoints)"
          )
        case Some((resolvedUrl, bytes, true)) =>
          // .ssclib archive — extract to libs cache
          lockPath.foreach { lp =>
            val lock0 = LockFile.read(lp).getOrElse(LockFile.empty)
            val lock1 = lock0.pin(depUri, bytes, resolvedUrl = Some(resolvedUrl))
            LockFile.write(lock1, lp)
          }
          state.foreach(_.resolved(depKey) = version)
          extractSsclib(bytes, org, name, version, depUri, lockPath, state)
        case Some((resolvedUrl, bytes, false)) =>
          // Plain .ssc file — cache as before
          val sscCached = depCacheRoot / os.RelPath(s"$org/$name/$version.ssc")
          os.makeDir.all(sscCached / os.up)
          os.write(sscCached, bytes)
          lockPath.foreach { lp =>
            val lock0 = LockFile.read(lp).getOrElse(LockFile.empty)
            val lock1 = lock0.pin(depUri, bytes, resolvedUrl = Some(resolvedUrl))
            LockFile.write(lock1, lp)
          }
          state.foreach(_.resolved(depKey) = version)
          sscCached
    finally
      state.foreach(_.visiting -= depKey)

  /** Return entry for a dep that's already in the cache (used during conflict resolution). */
  private def resolveCached(
      depUri: String, version: String, org: String, name: String,
      lockPath: Option[os.Path],
  ): os.Path =
    val sscCached = depCacheRoot / os.RelPath(s"$org/$name/$version.ssc")
    if os.exists(sscCached) then return sscCached
    val libDir = libCacheRoot / org / name / version
    if os.exists(libDir) then
      val manifestFile = libDir / SsclibManifest.FileName
      if os.exists(manifestFile) then
        val fromLib = SsclibManifest.parseString(os.read(manifestFile)).toOption.flatMap { manifest =>
          val entryPath = libDir / os.RelPath(manifest.entry)
          if os.exists(entryPath) then Some(entryPath) else None
        }
        fromLib match
          case Some(ep) => return ep
          case None     => ()
    // Not in cache — re-resolve normally (rare: was cleared between sessions)
    resolveDep(depUri, lockPath)

  /** Extract a `.ssclib` ZIP to `~/.cache/scalascript/libs/<org>/<name>/<version>/`,
   *  pre-fetch transitive deps, and return the entry-point path. */
  private def extractSsclib(
      zipBytes: Array[Byte],
      org:      String,
      name:     String,
      version:  String,
      depUri:   String,
      lockPath: Option[os.Path],
      state:    Option[ResolutionState],
  ): os.Path =
    import java.util.zip.ZipInputStream
    import java.io.ByteArrayInputStream

    val destDir = libCacheRoot / org / name / version
    os.makeDir.all(destDir)

    val zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))
    try
      var entry = zis.getNextEntry
      while entry != null do
        if !entry.isDirectory then
          val outPath = destDir / os.RelPath(entry.getName)
          os.makeDir.all(outPath / os.up)
          os.write.over(outPath, zis.readAllBytes())
        entry = zis.getNextEntry
    finally zis.close()

    val manifestFile = destDir / SsclibManifest.FileName
    if !os.exists(manifestFile) then
      throw new RuntimeException(
        s"dep '$depUri': archive missing '${SsclibManifest.FileName}'"
      )
    val manifest = SsclibManifest.parseString(os.read(manifestFile)).getOrElse(
      throw new RuntimeException(
        s"dep '$depUri': cannot parse '${SsclibManifest.FileName}'"
      )
    )
    val entryPath = destDir / os.RelPath(manifest.entry)
    if !os.exists(entryPath) then
      throw new RuntimeException(
        s"dep '$depUri': entry '${manifest.entry}' not found in archive"
      )
    // Eagerly pre-fetch transitive deps so they are in cache before the compiler needs them.
    prefetchTransitiveDeps(manifest, depUri, lockPath, state)
    // Wire glue.jar into the JVM classpath if declared in the manifest.
    manifest.glueJvm.foreach { glueArchivePath =>
      val jarPath = destDir / os.RelPath(glueArchivePath)
      if os.exists(jarPath) then addGlueJarToClasspath(jarPath)
      else
        System.err.println(s"[ssc] warn: dep '$depUri' declares glue.jvm '$glueArchivePath' but file not found in extracted archive")
    }
    // Register glue.js preamble content if declared in the manifest (Phase 4 FFI).
    manifest.glueJs.foreach { glueJsPath =>
      val jsFile = destDir / os.RelPath(glueJsPath)
      if os.exists(jsFile) then
        GlueJsPreambleRegistry.addPreamble(depUri, os.read(jsFile))
      else
        System.err.println(s"[ssc] warn: dep '$depUri' declares glue.js '$glueJsPath' but file not found in extracted archive")
    }
    entryPath

  /** Register a glue.jar on the JVM classpath (Phase 3+4 FFI).
   *
   *  Uses `java.net.URLClassLoader` thread-context trick: replaces the
   *  context class loader with a URL class loader that extends the existing
   *  one.  Falls back gracefully — if the loader is not a URLClassLoader the
   *  jar is added to `GlueClasspathRegistry` only (for build-time use).
   *
   *  Also registers the JAR with `BackendRegistry` so that any
   *  `META-INF/services/scalascript.backend.spi.Backend` entries in the
   *  glue.jar are loaded into the backend registry (Phase 4). */
  private def addGlueJarToClasspath(jarPath: os.Path): Unit =
    GlueClasspathRegistry.addJar(jarPath)
    val url = jarPath.toIO.toURI.toURL
    val existing = Thread.currentThread().getContextClassLoader
    existing match
      case ucl: java.net.URLClassLoader =>
        val extended = new java.net.URLClassLoader(Array(url), ucl)
        Thread.currentThread().setContextClassLoader(extended)
      case _ =>
        // Non-URLClassLoader (e.g. app class loader in JDK 9+).
        // Fall through — glue is available via GlueClasspathRegistry.
    // Register for ServiceLoader-based Backend discovery (Phase 4).
    import scalascript.compiler.plugin.BackendRegistry
    BackendRegistry.addPluginJar(jarPath)

  /** BFS step: resolve all direct dependencies declared in `manifest`.
   *  Errors from transitive deps are wrapped with context. */
  private def prefetchTransitiveDeps(
      manifest: SsclibManifest,
      parentUri: String,
      lockPath:  Option[os.Path],
      state:     Option[ResolutionState],
  ): Unit =
    manifest.dependencies.foreach { dep =>
      if dep.startsWith("dep:") then
        try resolveDep(dep, lockPath, state)
        catch case e: RuntimeException =>
          throw new RuntimeException(
            s"Transitive dep error for '$parentUri': ${e.getMessage}", e
          )
    }

  private def depSources(): List[String] =
    if !os.exists(depSourcesPath) then Nil
    else os.read.lines(depSourcesPath).filter(l => l.nonEmpty && !l.startsWith("#")).toList

  private def tryFetch(url: String): Either[String, (String, Array[Byte])] =
    if sys.env.get("SSC_NO_NETWORK").contains("1") then
      return Left(s"SSC_NO_NETWORK=1, skipping $url")
    scala.util.Try {
      val u    = java.net.URI.create(url).toURL
      val conn = u.openConnection
      conn.setConnectTimeout(10_000)
      conn.setReadTimeout(20_000)
      val in = conn.getInputStream
      try in.readAllBytes()
      finally in.close()
    }.toEither.map(bytes => (url, bytes)).left.map(_.getMessage)

  // ─── URL imports + lock check ────────────────────────────────────

  /** If `baseDir` lives inside the user cache, reconstruct the URL
   *  and fetch the relative dependency from the same origin. */
  private def cacheBackedRelative(
      rawPath:  String,
      baseDir:  os.Path,
      lockPath: Option[os.Path]
  ): Option[os.Path] =
    if !baseDir.startsWith(cacheRoot) then return None
    val rel  = baseDir.relativeTo(cacheRoot)
    val segs = rel.segments.toList
    if segs.length < 2 then return None
    val scheme    = segs.head
    val authority = segs(1)
    val base      = segs.drop(2).mkString("/")
    val combined  =
      if base.isEmpty then rawPath.stripPrefix("./")
      else base + "/" + rawPath.stripPrefix("./")
    val normalised = normalisePath(combined)
    if normalised.startsWith("..") then None
    else
      val url = s"$scheme://$authority/$normalised"
      Some(fetchToCache(url, lockPath))

  private def normalisePath(p: String): String =
    val out = scala.collection.mutable.ArrayBuffer.empty[String]
    p.split("/").foreach {
      case "" | "." => ()
      case ".."     => if out.nonEmpty && out.last != ".." then out.remove(out.length - 1)
                       else out += ".."
      case seg      => out += seg
    }
    out.mkString("/")

  /** Cache path for a URL. */
  private def cachePath(url: String): os.Path =
    val u = java.net.URI.create(url).toURL
    val scheme    = u.getProtocol
    val port      = u.getPort
    val authority = if port < 0 then u.getHost else s"${u.getHost}:$port"
    val path      = u.getPath.stripPrefix("/")
    if path.isEmpty then cacheRoot / scheme / authority / "index.ssc"
    else cacheRoot / scheme / authority / os.SubPath(path)

  /** Fetch `url`, verify against `ssc.lock` when `lockPath` is present,
   *  write to disk cache, and update the lock file on first pin.
   *
   *  v1.19 lock semantics:
   *  - `lockPath = None`  → fetch freely (legacy / no-lock mode)
   *  - `lockPath = Some(p)` and lock file exists and URL not in it
   *                         → build error (run `ssc lock` first)
   *  - `lockPath = Some(p)` and lock file exists and URL in it
   *                         → fetch + verify SHA-256
   *  - `lockPath = Some(p)` and lock file absent
   *                         → fetch + write lock (first-time `ssc lock` call) */
  private[imports] def fetchToCache(url: String, lockPath: Option[os.Path] = None): os.Path =
    val out = cachePath(url)
    lockPath match
      case None =>
        // Legacy mode: fetch without lock enforcement
        if os.exists(out) then out
        else doFetch(url, out, lockPath = None)

      case Some(lp) =>
        LockFile.read(lp) match
          case scala.util.Failure(err) if os.exists(lp) =>
            // Lock file exists but unreadable
            throw new RuntimeException(s"Cannot read ssc.lock at $lp: ${err.getMessage}")
          case scala.util.Failure(_) =>
            // No lock file yet — first-time setup (only reached from `ssc lock`)
            if os.exists(out) then
              // Already cached — add to new lock file
              val content = os.read.bytes(out)
              val lock = LockFile.empty.pin(url, content)
              LockFile.write(lock, lp)
              out
            else doFetch(url, out, lockPath = Some(lp))
          case scala.util.Success(lock) =>
            lock.entries.get(url) match
              case None =>
                // URL not in lock — refuse (per v1.19 hard-no)
                throw new RuntimeException(
                  s"URL not in ssc.lock: $url\nRun `ssc lock` to add it."
                )
              case Some(_) =>
                // URL in lock — fetch (if not cached) then verify
                if !os.exists(out) then doFetch(url, out, lockPath = Some(lp))
                else
                  val content = os.read.bytes(out)
                  lock.check(url, content).fold(
                    err => throw new RuntimeException(err),
                    _   => out
                  )

  // ─── v2.0 Phase 5 — pre-compiled artifact discovery ──────────────

  /** Look for a pre-compiled artifact next to a resolved source file.
   *
   *  Layout (auto-detected, no manifest schema change):
   *  {{{
   *    <dir>/<basename>.ssc              ← `sscPath`
   *    <dir>/.ssc-artifacts/<basename>.<ext>   ← returned
   *  }}}
   *
   *  Returns `Some(artifactPath)` if the artifact file exists, else `None`.
   *  Used by `compile-jvm` / `compile-js` to short-circuit dep parsing when
   *  a `.sscpkg` was built with `--with-artifacts` and a `.scim` / `.scjvm`
   *  / `.scjs` ships alongside the cached `.ssc`.
   *
   *  v2.0 Phase 5 — pre-compiled artifact distribution in `.sscpkg`. */
  def findArtifactAlongside(sscPath: os.Path, ext: String): Option[os.Path] =
    if !sscPath.last.endsWith(".ssc") then return None
    val baseName = sscPath.last.stripSuffix(".ssc")
    val candidate = sscPath / os.up / ".ssc-artifacts" / s"$baseName.$ext"
    if os.exists(candidate) then Some(candidate) else None

  /** Perform the actual HTTP fetch, write to cache, optionally update lock. */
  private def doFetch(url: String, out: os.Path, lockPath: Option[os.Path]): os.Path =
    if sys.env.get("SSC_NO_NETWORK").contains("1") then
      throw new RuntimeException(
        s"URL import not in cache and SSC_NO_NETWORK=1: $url"
      )
    val u    = java.net.URI.create(url).toURL
    val conn = u.openConnection
    conn.setConnectTimeout(10_000)
    conn.setReadTimeout(20_000)
    val in      = conn.getInputStream
    val content = try in.readAllBytes() finally in.close()
    os.makeDir.all(out / os.up)
    os.write(out, content)
    // Update / create lock file on fetch
    lockPath.foreach { lp =>
      val lock0 = LockFile.read(lp).getOrElse(LockFile.empty)
      val lock1 = lock0.pin(url, content)
      LockFile.write(lock1, lp)
    }
    out
