package scalascript.compiler.plugin.deploy

/** Migrates state records between two `StateBackend` implementations.
 *
 *  Corresponds to `ssc deploy state migrate --from local --to s3`.
 *
 *  The migrator reads all known keys from `src`, writes them to `dst`,
 *  and optionally deletes them from `src` (--delete-source).
 */
object StateMigrator:

  case class MigrateResult(
    copied:  Int,
    skipped: Int,
    failed:  List[String],
  )

  /** Migrate a known set of keys from `src` to `dst`. */
  def migrate(
    src:          StateBackend,
    dst:          StateBackend,
    keys:         List[StateKey],
    deleteSource: Boolean = false,
    dryRun:       Boolean = false,
    verbose:      Boolean = false,
  ): MigrateResult =
    var copied  = 0
    var skipped = 0
    val failed  = scala.collection.mutable.ListBuffer.empty[String]

    for key <- keys do
      src.read(key) match
        case None =>
          if verbose then println(s"[migrate] skip ${fmtKey(key)} — not found in source")
          skipped += 1
        case Some(record) =>
          if dryRun then
            println(s"[dry-run] Would migrate ${fmtKey(key)}")
            copied += 1
          else
            try
              dst.write(key, record)
              if deleteSource then deleteFrom(src, key)
              if verbose then println(s"[migrate] copied ${fmtKey(key)}")
              copied += 1
            catch case e: Exception =>
              val msg = s"${fmtKey(key)}: ${e.getMessage}"
              failed += msg
              System.err.println(s"[migrate/error] $msg")

    MigrateResult(copied, skipped, failed.toList)

  private def fmtKey(k: StateKey): String =
    val slot = k.slot.map(s => s"/$s").getOrElse("")
    s"${k.env}/${k.target}$slot"

  private def deleteFrom(backend: StateBackend, key: StateKey): Unit =
    backend match
      case _: LocalFileStateBackend =>
        import java.nio.file.{Files, Paths}
        val root   = Paths.get(System.getProperty("user.home"), ".ssc-state")
        val slot   = key.slot.map(s => s".$s").getOrElse("")
        val p      = root.resolve("app").resolve(key.env).resolve(s"${key.target}$slot.json")
        Files.deleteIfExists(p)
      case _ =>
        // S3/Consul/Etcd: write a tombstone sentinel (empty revision) or leave in place
        ()
