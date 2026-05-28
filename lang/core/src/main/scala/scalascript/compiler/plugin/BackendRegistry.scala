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

  /** Add an in-process plugin JAR — picked up on next ServiceLoader scan.
   *  Per spec §12.1, the JAR runs in a `URLClassLoader` whose parent is
   *  the SPI loader only so plugins can't see each other's deps.
   *
   *  If `jar` ends with `.sscpkg`, delegates to `loadSscpkg` instead.
   *
   *  In native-image mode (`ssc` native binary), URLClassLoader cannot load
   *  class files at runtime.  Instead, the plugin is loaded in a JVM
   *  subprocess via `ssc-plugin-host.jar` (v1.50-native-p3). */
  def addPluginJar(jar: os.Path): Unit =
    if jar.ext == "sscpkg" then loadSscpkg(jar)
    else if isNativeImage then addPluginJarViaBridge(jar)
    else
      extraJarPaths += jar
      inProcessCache = null      // force re-scan of ServiceLoader

  /** True when running inside a GraalVM native-image binary. */
  private def isNativeImage: Boolean =
    System.getProperty("org.graalvm.nativeimage.imagecode") != null

  /** Native-image bridge: spawn ssc-plugin-host as a subprocess and
   *  register it like any other out-of-process plugin. */
  private def addPluginJarViaBridge(jar: os.Path): Unit =
    val hostJar  = findPluginHostJar()
    val javaExec = findJava()
    (hostJar, javaExec) match
      case (None, _) =>
        log.warn(
          s"[ssc] --plugin ${jar.last} skipped: ssc-plugin-host.jar not found next to" +
          s" the ssc binary or in $$SSC_HOME/lib/. Re-install ssc to fix.")
      case (_, None) =>
        log.warn(
          s"[ssc] --plugin ${jar.last} skipped: 'java' not found on PATH." +
          s" Install a JRE and make sure 'java' is reachable.")
      case (Some(host), Some(javaExe)) =>
        val sep = java.io.File.pathSeparator
        val cp  = s"${jar.toIO.getAbsolutePath}$sep${host.toIO.getAbsolutePath}"
        SubprocessBackend.spawn(
          executable = javaExe,
          args       = List("-cp", cp, "scalascript.plugin.SubprocessHost", jar.toIO.getAbsolutePath)
        ) match
          case scala.util.Success(b) =>
            pluginCache.put(b.id, b)
            log.info(s"[ssc] loaded plugin ${b.id} via subprocess bridge")
          case scala.util.Failure(t) =>
            log.warn(s"[ssc] --plugin ${jar.last}: bridge spawn failed — ${t.getMessage}")

  /** Look for ssc-plugin-host.jar in:
   *   1. $SSC_HOME/lib/ssc-plugin-host.jar
   *   2. <binary-dir>/lib/ssc-plugin-host.jar  (matches release archive layout)
   *   3. <binary-dir>/ssc-plugin-host.jar      (flat install / dev override) */
  private def findPluginHostJar(): Option[os.Path] =
    val fromEnv = Option(System.getenv("SSC_HOME"))
      .map(h => os.Path(java.nio.file.Paths.get(h).toAbsolutePath) / "lib" / "ssc-plugin-host.jar")
    val fromBin = scala.util.Try {
      val cmd = ProcessHandle.current().info().command()
      if cmd.isPresent then
        val binDir = os.Path(java.nio.file.Paths.get(cmd.get).toAbsolutePath) / os.up
        List(binDir / "lib" / "ssc-plugin-host.jar", binDir / "ssc-plugin-host.jar")
      else Nil
    }.getOrElse(Nil)
    (fromEnv.toList ++ fromBin).find(os.exists(_))

  /** Return "java" if it is on PATH, or the JAVA_HOME bin path if set. */
  private def findJava(): Option[String] =
    Option(System.getProperty("java.home"))
      .map(h => java.nio.file.Paths.get(h, "bin", "java").toAbsolutePath.toString)
      .filter(p => new java.io.File(p).isFile)
      .orElse(Some("java"))

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
   *  out-of-process descriptors.  Note that calling `.all` *does NOT*
   *  spawn subprocess plugins; use `lookup` for that. */
  def all: List[Backend] = inProcess

  /** Combined list of in-process backends + out-of-process manifests
   *  (as identifying descriptors).  Useful for `--list-backends` so
   *  the user sees subprocess plugins without us spawning them. */
  case class Visible(id: String, displayName: String, spiVersion: String, kind: String)
  def listVisible: List[Visible] =
    inProcess.map(b => Visible(b.id, b.displayName, b.spiVersion, "in-process")) ++
      manifests.map(m => Visible(m.id, m.displayName, m.spiVersion, s"subprocess (${m.protocol})"))

  /** Look up a backend by its declared id.  For subprocess plugins,
   *  spawns the process on first lookup and caches the handle. */
  def lookup(id: String): Option[Backend] =
    inProcess.find(_.id == id).orElse {
      manifests.find(_.id == id).flatMap(subprocessBackendFor)
    }

  /** Backends that declare they can embed a given source language. */
  def acceptingSource(language: String): List[Backend] =
    inProcess.filter(_.acceptedSources.contains(language))

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
