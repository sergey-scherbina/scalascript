package scalascript.config

import java.nio.file.{FileSystems, Path, WatchKey, StandardWatchEventKinds => WEK, Files}
import java.util.concurrent.{Executors, TimeUnit}
import scala.jdk.CollectionConverters.*

/** Watches external config files for changes and triggers a reload callback.
 *
 *  Uses Java NIO `WatchService` — no external dependencies.
 *  One watcher instance per `ssc watch` session; call `close()` to stop.
 *
 *  {{{
 *  val watcher = ConfigWatcher(
 *    paths    = List(Path.of("app.yaml"), Path.of("prod.hocon")),
 *    onChange = () => println("Config changed — reloading")
 *  )
 *  // ... run your script loop ...
 *  watcher.close()
 *  }}}
 */
final class ConfigWatcher(
  paths:    List[Path],
  onChange: () => Unit,
) extends AutoCloseable:

  private val service  = FileSystems.getDefault.newWatchService()
  private val executor = Executors.newSingleThreadExecutor(r =>
    val t = new Thread(r, "ssc-config-watcher")
    t.setDaemon(true)
    t
  )

  // Register unique parent directories
  private val watchedFiles: Set[Path] = paths.map(_.toAbsolutePath.normalize).toSet
  private val registeredDirs: Map[WatchKey, Path] =
    watchedFiles
      .map(_.getParent)
      .filter(dir => dir != null && Files.isDirectory(dir))
      .map { dir =>
        val key = dir.register(service, WEK.ENTRY_MODIFY, WEK.ENTRY_CREATE)
        key -> dir
      }.toMap

  // Start background polling
  private val future = executor.submit(new Runnable:
    override def run(): Unit =
      var running = true
      while running do
        try
          val key = service.poll(500, TimeUnit.MILLISECONDS)
          if key != null then
            val dir = registeredDirs.getOrElse(key, Path.of("."))
            val changed = key.pollEvents().asScala.exists { ev =>
              val name = ev.context().asInstanceOf[Path]
              watchedFiles.contains(dir.resolve(name).normalize)
            }
            if changed then onChange()
            key.reset()
        catch case _: InterruptedException =>
          running = false
  )

  override def close(): Unit =
    future.cancel(true)
    executor.shutdownNow()
    service.close()

object ConfigWatcher:
  /** Build a watcher from a `ConfigLoader`'s external file list.
   *  Returns `None` if there are no external files to watch. */
  def fromLoader(loader: ConfigLoader, onChange: () => Unit): Option[ConfigWatcher] =
    val paths = loader.externalFiles.map(f => f.basePath.resolve(f.path).toAbsolutePath)
    if paths.isEmpty then None
    else Some(new ConfigWatcher(paths, onChange))
