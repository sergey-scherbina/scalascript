package scalascript.plugin

import scalascript.backend.spi.{Backend, InteractiveBackend}
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
 *       found under `$SCALASCRIPT_PLUGIN_PATH` or `~/.scalascript/plugins/`.
 *       Each manifest spawns a subprocess via `SubprocessBackend`.
 *
 *  Out-of-process plugins are lazy: the subprocess is only spawned
 *  when the plugin is actually looked up.  This keeps `--list-backends`
 *  fast (it reads manifests but doesn't fork). */
object BackendRegistry:

  /** Discard the cached subprocess plugins so the next lookup re-scans
   *  the filesystem.  Tests use this when installing plugins
   *  mid-suite. */
  def reload(): Unit =
    val toShutdown = pluginCache.values.toList
    pluginCache.clear()
    toShutdown.foreach { case p: SubprocessBackend => p.shutdown(); case _ => () }
    manifestCache = null
    inProcessCache = null

  // ── Extension points wired by CLI / tests ──────────────────────────

  private val extraJarPaths      = scala.collection.mutable.ListBuffer.empty[os.Path]
  private val extraPluginDirs    = scala.collection.mutable.ListBuffer.empty[os.Path]

  /** Add an in-process plugin JAR — picked up on next ServiceLoader scan.
   *  Per spec §12.1, the JAR runs in a `URLClassLoader` whose parent is
   *  the SPI loader only so plugins can't see each other's deps. */
  def addPluginJar(jar: os.Path): Unit =
    extraJarPaths += jar
    inProcessCache = null      // force re-scan of ServiceLoader

  /** Add a plugin-discovery directory (peer of `~/.scalascript/plugins/`). */
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
        workingDir = m.manifestPath.map(_ / os.up)
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
   *  future REPL modes. */
  def interactive: List[InteractiveBackend] =
    inProcess.collect { case b: InteractiveBackend => b }

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
