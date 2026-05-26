package scalascript.compiler.plugin

import scalascript.backend.spi.{Backend, InteractiveBackend}
import scalascript.logging.Logger
import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*

/** Central registry for `Backend` plugins.
 *
 *  Per docs/backend-spi.md §12.  Combines two discovery mechanisms:
 *
 *    1. **In-process / ServiceLoader (§12.1)** — every backend JAR on
 *       the classpath that ships a META-INF/services entry.  This is
 *       how the bundled `jvm` / `js` / `scalajs-spa` / `int` backends
 *       register.
 *
 *    2. **Out-of-process / plugin.yaml (§12.2)** — every `plugin.yaml`
 *       found under `$SCALASCRIPT_PLUGIN_PATH` or `~/.scalascript/compiler/plugins/`.
 *       Each manifest spawns a subprocess via `SubprocessBackend`.
 *
 *  Out-of-process plugins are lazy: the subprocess is only spawned
 *  when the plugin is actually looked up.  This keeps `--list-backends`
 *  fast (it reads manifests but doesn't fork). */
object BackendRegistry:

  private val log = Logger(getClass)

  /** Discard the cached subprocess plugins so the next lookup re-scans
   *  the filesystem.  Tests use this when installing plugins
   *  mid-suite. */
  def reload(): Unit =
    val toShutdown = pluginCache.values.toList
    pluginCache.clear()
    toShutdown.foreach { case p: SubprocessBackend => p.shutdown(); case _ => () }
    manifestCache = null
    inProcessCache = null
    extraJarPaths.clear()
    extraPluginDirs.clear()
    extraPreambles.clear()
    extraSourcePaths.clear()
    loadedPkgIds.clear()
    pkgSourceDirCache.clear()

  // ── Extension points wired by CLI / tests ──────────────────────────

  private val extraJarPaths      = scala.collection.mutable.ListBuffer.empty[os.Path]
  private val extraPluginDirs    = scala.collection.mutable.ListBuffer.empty[os.Path]

  /** Add a plugin JAR.  In normal JVM mode: registered for URLClassLoader-based
   *  ServiceLoader discovery (§12.1).  In GraalVM native-image mode: URLClassLoader
   *  is unavailable, so the JAR is bridged via a spawned `ssc-plugin-host` JVM
   *  subprocess speaking the existing stdio wire protocol (§12.2 / Phase 3).
   *
   *  If `jar` ends with `.sscpkg`, delegates to `loadSscpkg` instead. */
  def addPluginJar(jar: os.Path): Unit =
    if jar.ext == "sscpkg" then loadSscpkg(jar)
    else if isNativeImage then spawnPluginBridge(jar)
    else
      extraJarPaths += jar
      inProcessCache = null      // force re-scan of ServiceLoader

  /** True when running inside a GraalVM native-image executable.
   *  Uses reflection so the code compiles and runs fine on a regular JVM. */
  private[plugin] def isNativeImagePublic: Boolean = isNativeImage

  private def isNativeImage: Boolean =
    try
      Class.forName("org.graalvm.nativeimage.ImageInfo")
        .getMethod("inImageRuntimeCode")
        .invoke(null)
        .asInstanceOf[Boolean]
    catch case _: Throwable => false

  /** Spawn `ssc-plugin-host` as a JVM subprocess wrapping the given JAR,
   *  then register the resulting `SubprocessBackend` in the plugin cache.
   *  Emits diagnostic warnings if `java` or `ssc-plugin-host.jar` is absent. */
  private def spawnPluginBridge(jar: os.Path): Unit =
    val hostJarOpt  = findPluginHostJar()
    val javaExeOpt  = findJavaExecutable()
    (hostJarOpt, javaExeOpt) match
      case (None, _) =>
        log.warn(
          s"[ssc] warning: --plugin ${jar.last}: ssc-plugin-host.jar not found; " +
          "cannot load JAR plugins in native mode. " +
          "Ensure ssc-plugin-host.jar is in the same directory as the ssc binary " +
          "or in $$SSC_HOME/lib/.")
      case (_, None) =>
        log.warn(
          s"[ssc] warning: --plugin ${jar.last}: 'java' not found on PATH; " +
          "cannot load JAR plugins in native mode.")
      case (Some(hostJar), Some(java)) =>
        val sep = _root_.java.io.File.pathSeparator
        val cp = s"${jar}${sep}${hostJar}"
        SubprocessBackend.spawn(
          executable = java,
          args       = List("-cp", cp, "scalascript.plugin.SubprocessHost", jar.toString)
        ) match
          case scala.util.Success(b) => pluginCache.put(b.id, b)
          case scala.util.Failure(t) =>
            log.warn(s"[ssc] warning: failed to start plugin bridge for ${jar.last}: ${t.getMessage}")

  /** Locate `ssc-plugin-host.jar`.
   *  Search order: (1) sibling of the running native binary, (2) `$SSC_HOME/lib/`. */
  private def findPluginHostJar(): Option[String] =
    val candidates = scala.collection.mutable.ListBuffer.empty[java.io.File]
    // (1) next to the current executable
    Option(ProcessHandle.current().info().command().orElse(null)).foreach { exe =>
      val siblingLib = java.io.File(exe).getParentFile
      candidates += java.io.File(siblingLib, "ssc-plugin-host.jar")
      candidates += java.io.File(siblingLib, "lib/ssc-plugin-host.jar")
    }
    // (2) $SSC_HOME/lib/
    Option(System.getenv("SSC_HOME")).foreach { home =>
      candidates += java.io.File(home, "lib/ssc-plugin-host.jar")
    }
    candidates.find(_.isFile).map(_.getAbsolutePath)

  /** Find `java` executable on PATH; returns the first hit or None. */
  private def findJavaExecutable(): Option[String] =
    val javaHome = System.getProperty("java.home")
    val fromHome = if javaHome != null then
      val f = java.io.File(javaHome, "bin/java")
      if f.canExecute then Some(f.getAbsolutePath) else None
    else None
    fromHome.orElse {
      val sep = java.io.File.pathSeparator
      Option(System.getenv("PATH")).flatMap { path =>
        path.split(sep).view
          .map(dir => java.io.File(dir, "java"))
          .find(_.canExecute)
          .map(_.getAbsolutePath)
      }
    }

  /** Supported SPI version for compatibility checks. */
  val SpiVersion = "0.1.0"

  /** IDs of .sscpkg archives already fully loaded — used for cycle detection. */
  private val loadedPkgIds = scala.collection.mutable.Set.empty[String]

  /** Load a `.sscpkg` archive: register intrinsic JARs with the
   *  ServiceLoader, cache per-backend runtime preamble strings, and
   *  record source paths for prelude injection.  Resolves transitive
   *  dependencies (cycle-safe via `loadedPkgIds`).
   *  See docs/milestones.md §v1.7 Tier 2–3. */
  def loadSscpkg(pkg: os.Path): Unit =
    loadSscpkgWith(pkg, visited = scala.collection.mutable.Set.empty)

  private def loadSscpkgWith(pkg: os.Path, visited: scala.collection.mutable.Set[String]): Unit =
    val result   = SscpkgLoader.load(pkg)
    val manifest = result.manifest
    if loadedPkgIds.contains(manifest.id) then return  // already loaded
    if visited.contains(manifest.id) then
      throw RuntimeException(s"Dependency cycle detected involving '${manifest.id}'")
    visited += manifest.id

    // SPI compatibility warning (not hard failure — keeps old plugins usable).
    if manifest.spiVersion != SpiVersion then
      log.warn(
        s"[ssc] warning: plugin '${manifest.id}' spiVersion=${manifest.spiVersion}," +
        s" compiler supports $SpiVersion — may be incompatible")

    // Resolve transitive dependencies first (depth-first, post-order).
    if manifest.dependencies.nonEmpty then
      val searchPaths = allPluginDirs()
      for (depId, _) <- manifest.dependencies if !loadedPkgIds.contains(depId) do
        val depPkg = searchPaths.flatMap { dir =>
          if os.isDir(dir) then
            os.list(dir).filter(p => p.ext == "sscpkg" && p.last.startsWith(depId + "-") || p.last == depId + ".sscpkg")
          else Nil
        }.headOption.getOrElse(
          throw RuntimeException(s"Missing dependency '$depId' for plugin '${manifest.id}'"))
        loadSscpkgWith(depPkg, visited)

    result.intrinsicJars.foreach { jar =>
      extraJarPaths += jar
      inProcessCache = null
    }
    result.runtimeStrings.foreach { (backendId, code) =>
      extraPreambles.getOrElseUpdate(backendId, new StringBuilder).append('\n').append(code)
    }
    result.sourcePaths.foreach { p =>
      extraSourcePaths.getOrElseUpdate(pkg, scala.collection.mutable.ListBuffer.empty) += p
    }
    loadedPkgIds += manifest.id

  // ── Per-backend preamble strings from loaded .sscpkg files ──────────

  private val extraPreambles   = scala.collection.mutable.Map.empty[String, StringBuilder]
  private val extraSourcePaths = scala.collection.mutable.Map.empty[os.Path, scala.collection.mutable.ListBuffer[String]]

  /** Accumulated runtime helper code from all loaded `.sscpkg` files
   *  for the given backend.  Returns empty string if none loaded. */
  def preambleFor(backendId: String): String =
    extraPreambles.get(backendId).map(_.toString.stripLeading()).getOrElse("")

  /** Add a plugin-discovery directory (peer of `~/.scalascript/compiler/plugins/`). */
  def addPluginDir(dir: os.Path): Unit =
    extraPluginDirs += dir
    manifestCache = null

  // ── In-process backends (ServiceLoader) ─────────────────────────────

  private var inProcessCache: List[Backend] = null

  def inProcess: List[Backend] =
    if inProcessCache == null then
      val loader =
        if extraJarPaths.isEmpty then classOf[Backend].getClassLoader
        else
          val urls = extraJarPaths.map(_.toIO.toURI.toURL).toArray
          new java.net.URLClassLoader(urls, classOf[Backend].getClassLoader)
      inProcessCache = ServiceLoader
        .load(classOf[Backend], loader)
        .iterator
        .asScala
        .toList
    inProcessCache

  /** All directories where `.sscpkg` archives may be found (for dep resolution). */
  private def allPluginDirs(): List[os.Path] =
    (PluginManifest.defaultSearchPaths ++ extraPluginDirs.toList).distinct

  // ── Out-of-process plugins (plugin.yaml) ────────────────────────────

  private val pluginCache = new java.util.concurrent.ConcurrentHashMap[String, Backend]().asScala
  private var manifestCache: List[PluginManifest] = null

  /** Lazy discovery of subprocess plugin manifests.  Cached on first
   *  call; clear with `reload()`. */
  def manifests: List[PluginManifest] =
    if manifestCache == null then
      val paths = PluginManifest.defaultSearchPaths ++ extraPluginDirs.toList
      manifestCache = PluginManifest.discover(paths)
    manifestCache

  /** Build (or retrieve cached) a SubprocessBackend for the manifest. */
  private def subprocessBackendFor(m: PluginManifest): Option[Backend] =
    pluginCache.get(m.id).orElse {
      SubprocessBackend.spawn(
        executable = m.executablePath,
        args       = m.args,
        workingDir = m.manifestPath.map(_ / os.up),
        framing    = WireFraming.fromManifest(m.protocol)
      ).toOption.map { b =>
        pluginCache.put(m.id, b)
        b
      }
    }

  // ── Public surface ──────────────────────────────────────────────────

  /** Every backend visible to the runtime — in-process first, then
   *  bridge-spawned subprocess backends (from `--plugin foo.jar` in native
   *  mode).  Note that calling `.all` does NOT spawn manifest-based subprocess
   *  plugins; use `lookup` for that. */
  def all: List[Backend] =
    // Bridge-spawned JARs (native mode) land in pluginCache without a manifest.
    val manifestIds = manifests.map(_.id).toSet
    inProcess ++ pluginCache.values.filter(b => !manifestIds.contains(b.id)).toList

  /** Combined list of in-process backends + out-of-process manifests
   *  (as identifying descriptors).  Useful for `--list-backends` so
   *  the user sees subprocess plugins without us spawning them. */
  case class Visible(id: String, displayName: String, spiVersion: String, kind: String)
  def listVisible: List[Visible] =
    val manifestIds = manifests.map(_.id).toSet
    val bridged = pluginCache.values.filter(b => !manifestIds.contains(b.id))
      .map(b => Visible(b.id, b.displayName, b.spiVersion, "bridge-subprocess")).toList
    inProcess.map(b => Visible(b.id, b.displayName, b.spiVersion, "in-process")) ++
      bridged ++
      manifests.map(m => Visible(m.id, m.displayName, m.spiVersion, s"subprocess (${m.protocol})"))

  /** Look up a backend by its declared id.  For subprocess plugins,
   *  spawns the process on first lookup and caches the handle. */
  def lookup(id: String): Option[Backend] =
    inProcess.find(_.id == id)
      .orElse(pluginCache.get(id))   // bridge-spawned JARs (native mode)
      .orElse(manifests.find(_.id == id).flatMap(subprocessBackendFor))

  /** Backends that declare they can embed a given source language. */
  def acceptingSource(language: String): List[Backend] =
    all.filter(_.acceptedSources.contains(language))

  /** All interactive backends — the subset used by `ssc serve` and
   *  future REPL modes.  Includes subprocess plugins that declare
   *  `interactive: true` (Stage 6+/B). */
  def interactive: List[InteractiveBackend] =
    val inProc = inProcess.collect { case b: InteractiveBackend => b }
    val sub    = manifests.filter(_.interactive).flatMap(subprocessBackendFor).collect {
      case b: InteractiveBackend => b
    }
    inProc ++ sub

  /** One-line description per backend, intended for `--list-backends`.
   *  Includes both in-process and out-of-process plugins; subprocess
   *  plugins remain unspawned. */
  def describe: String =
    val rows = listVisible
    if rows.isEmpty then "(no backends registered)"
    else
      rows
        .map(v => f"${v.id}%-14s ${v.displayName}  [spi=${v.spiVersion}, ${v.kind}]")
        .mkString("\n")

  /** Idempotent shutdown of every spawned subprocess plugin.  Called
   *  by CLI shutdown hooks. */
  def shutdownAll(): Unit =
    pluginCache.values.foreach {
      case p: SubprocessBackend => p.shutdown()
      case _                    => ()
    }
    pluginCache.clear()

  // ── pkg: URI support ───────────────────────────────────────────────

  /** Find an installed `.sscpkg` file matching `coord`.
   *
   *  `coord` is `org/name:version`, `name:version`, or plain `name`.
   *  Candidate filenames tried (in order), searching all plugin dirs:
   *    1. `<org>.<name>-<version>.sscpkg`
   *    2. `<name>-<version>.sscpkg`
   *    3. `scalascript-<name>-<version>.sscpkg`
   *    4. Any file starting with `<qualifiedId>-` or `<name>-` (newest wins)
   *    5. (no-version case) `<qualifiedId>.sscpkg` or `<name>.sscpkg`
   *
   *  Searches `PluginManifest.defaultSearchPaths` plus any extra dirs
   *  registered via `addPluginDir`. */
  def findInstalledPkg(coord: String): Option[os.Path] =
    // Parse coord: optional "org/" prefix, required name, optional ":version"
    val colonIdx = coord.lastIndexOf(':')
    val (orgName, version) =
      if colonIdx >= 0 then (coord.substring(0, colonIdx), Some(coord.substring(colonIdx + 1)))
      else (coord, None)
    val slashIdx = orgName.indexOf('/')
    val (org, name) =
      if slashIdx >= 0 then (orgName.substring(0, slashIdx), orgName.substring(slashIdx + 1))
      else ("", orgName)
    val qualifiedId = if org.nonEmpty then s"$org.$name" else name
    val dirs = (PluginManifest.defaultSearchPaths ++ extraPluginDirs.toList).distinct
    def scanDirs(pred: String => Boolean): Option[os.Path] =
      dirs.flatMap { dir =>
        if !os.isDir(dir) then Nil
        else os.list(dir).filter(p => p.ext == "sscpkg" && pred(p.last))
      }.headOption
    version match
      case Some(v) =>
        scanDirs(f =>
          f == s"$qualifiedId-$v.sscpkg" || f == s"$name-$v.sscpkg" ||
          f == s"scalascript-$name-$v.sscpkg"
        ).orElse(
          scanDirs(f => f.startsWith(s"$qualifiedId-") || f.startsWith(s"$name-"))
        )
      case None =>
        scanDirs(f => f == s"$qualifiedId.sscpkg" || f == s"$name.sscpkg")
          .orElse(
            scanDirs(f => f.startsWith(s"$qualifiedId-") || f.startsWith(s"$name-"))
          )

  /** Cache of already-extracted pkg source directories (pkgPath → extractedDir). */
  private val pkgSourceDirCache =
    scala.collection.mutable.Map.empty[os.Path, os.Path]

  /** Load a `.sscpkg` (registering its intrinsics) and extract its sources
   *  to a temp directory.  Returns the directory containing the unpacked
   *  source .ssc files.  Idempotent: repeated calls return the same dir.
   *
   *  Callers should pick the entry-point `.ssc` from the returned directory:
   *  prefer `index.ssc` if present, otherwise the lexicographically first `.ssc`. */
  def loadAndExtract(pkg: os.Path): os.Path =
    pkgSourceDirCache.getOrElseUpdate(pkg, {
      loadSscpkg(pkg)
      SscpkgLoader.extractSources(pkg)
    })
