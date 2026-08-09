package scalascript.compiler.plugin.scljetjdbc

import java.nio.channels.{FileChannel, FileLock, OverlappingFileLockException}
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.sql.SQLException
import java.util.concurrent.ConcurrentHashMap

/** A held write lock on a host database: release it when the connection closes. */
final class HostLockHandle(
    private val key: String,
    private val channel: FileChannel,
    private val lock: FileLock,
    private val lockPath: Path):
  def release(): Unit =
    try lock.release() catch case _: Throwable => ()
    try channel.close() catch case _: Throwable => ()
    // Best-effort: remove the sidecar once we no longer hold it. If another waiter grabbed it
    // between release and delete, the delete simply fails and they keep their lock.
    try Files.deleteIfExists(lockPath) catch case _: Throwable => ()
    HostFileLock.jvmHeld.remove(key)

/** Single-writer-per-host-file locking for the JDBC shim.
 *
 *  A writable host-file connection reads the whole image at open and holds it for its lifetime, so
 *  the read-modify-rewrite window is the WHOLE connection. Two writers therefore silently lose each
 *  other's updates unless they are mutually excluded for that whole window — a write-time-only lock
 *  would not help, because each writer already snapshotted at open. So the lock is held from open to
 *  close.
 *
 *  Two levels, because one is not enough:
 *   - a **JVM-wide** guard (`jvmHeld`), because two connections in the SAME process asking a
 *     `FileChannel` to lock the same file get an `OverlappingFileLockException`, not a clean "held";
 *   - a cross-process advisory **`FileLock`** on a SIDECAR file `<db>.scljet-lock`.
 *
 *  The lock is on the sidecar, NOT the database file, on purpose: the durable write replaces the db
 *  via an atomic rename (D1), which swaps the inode — a lock held on the db file would silently stop
 *  protecting the new file. The sidecar is never renamed, so the lock is stable across every flush.
 *  It also means the lock does not interfere with a real `sqlite3`/Xerial process reading the db
 *  file itself; coordinating scljet-vs-real-sqlite writers on one file is out of scope (do not mix
 *  drivers on a live file).
 */
object HostFileLock:
  private[scljetjdbc] val jvmHeld = ConcurrentHashMap[String, AnyRef]()
  private val RetryStepMillis = 25L
  private val HELD: AnyRef = new Object

  private def sidecar(db: Path): Path =
    val abs = db.toAbsolutePath.normalize()
    val parent = Option(abs.getParent).getOrElse(Paths.get("."))
    parent.resolve("." + abs.getFileName.toString + ".scljet-lock")

  /** Acquire the exclusive write lock for `db`, retrying up to `busyTimeoutMillis` (0 = fail fast).
   *  Throws `SQLException` ("database is locked", SQLState 55P03/lock-not-available family) when the
   *  file is being written by another connection or process. */
  def acquire(db: Path, busyTimeoutMillis: Long): HostLockHandle =
    val key = db.toAbsolutePath.normalize().toString
    val deadlineNanos = System.nanoTime() + math.max(0L, busyTimeoutMillis) * 1000000L

    // Level 1 — claim the JVM-wide slot so two connections in this process can't both proceed.
    while jvmHeld.putIfAbsent(key, HELD) != null do
      if System.nanoTime() >= deadlineNanos then
        throw new SQLException(s"scljet JDBC: database is locked (another connection in this JVM is writing $db)", "55P03")
      Thread.sleep(RetryStepMillis)

    // Level 2 — the cross-process advisory lock on the sidecar. On any failure, give the JVM slot back.
    try
      val lockPath = sidecar(db)
      Option(lockPath.getParent).foreach(p => if !Files.exists(p) then Files.createDirectories(p))
      val channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      try
        var lock: FileLock | Null = tryLockOnce(channel)
        while lock == null do
          if System.nanoTime() >= deadlineNanos then
            channel.close()
            throw new SQLException(s"scljet JDBC: database is locked (another process is writing $db)", "55P03")
          Thread.sleep(RetryStepMillis)
          lock = tryLockOnce(channel)
        HostLockHandle(key, channel, lock.nn, lockPath)
      catch
        case e: Throwable =>
          try channel.close() catch case _: Throwable => ()
          throw e
    catch
      case e: Throwable =>
        jvmHeld.remove(key)
        throw e

  /** `tryLock` returns null when another PROCESS holds it, but throws when THIS JVM already does —
   *  both mean "contended", so normalise the exception to null. (The JVM-level guard above should
   *  make the exception path unreachable, but be defensive.) */
  private def tryLockOnce(channel: FileChannel): FileLock | Null =
    try channel.tryLock()
    catch case _: OverlappingFileLockException => null
