package scalascript.compiler.plugin.scljet

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

import java.nio.file.{Files, Path}
import java.sql.DriverManager
import org.sqlite.SQLiteConfig

object SclJetVfsLockProbe:
  def main(args: Array[String]): Unit =
    val opened = SclJetJvmVfsHost.open(args(0), readOnly = false, create = false)
    val code = opened.value match
      case Some(handle) =>
        val result = SclJetJvmVfsHost.lock(handle, HostLockLevel.Shared)
        SclJetJvmVfsHost.close(handle)
        result.code
      case None => opened.code
    println(code)

object SclJetSqliteLockProbe:
  // A large busy timeout is essential: with busy_timeout=0 the official driver
  // returns SQLITE_BUSY *immediately* on a lock conflict and never waits, so it
  // could never demonstrate blocking on the host lock. With a large timeout it
  // enters SQLite's busy-retry loop and genuinely waits until the lock is freed.
  private val BusyTimeoutMs = 30000
  def main(args: Array[String]): Unit =
    println("ready")
    System.out.flush()
    Class.forName("org.sqlite.JDBC")
    val config = SQLiteConfig()
    config.setBusyTimeout(BusyTimeoutMs)
    val sqlite = DriverManager.getConnection(s"jdbc:sqlite:${args(0)}", config.toProperties)
    try
      sqlite.createStatement().execute(s"pragma busy_timeout=$BusyTimeoutMs")
      // Signal, right before the read query that must block on the host exclusive
      // lock, so the parent synchronizes on this line instead of a fixed sleep.
      println("querying")
      System.out.flush()
      try
        val rows = sqlite.createStatement().executeQuery("select * from t")
        rows.close()
        println("ok")
      catch case _: java.sql.SQLException => println("busy")
    finally sqlite.close()

class SclJetJvmVfsHostTest extends AnyFunSuite:

  private def runIntrinsic(code: String): String =
    val buffer = java.io.ByteArrayOutputStream()
    val out = java.io.PrintStream(buffer, true)
    val interpreter = Interpreter(out = out)
    interpreter.installPlugins(List(SclJetVfsInterpreterPlugin()))
    interpreter.run(Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n"))
    out.flush()
    buffer.toString.trim

  private def withTempDb[A](body: (Path, Path) => A): A =
    val dir = Files.createTempDirectory("scljet-vfs-")
    val db = dir.resolve("test.db")
    try body(dir, db)
    finally
      Option(dir.toFile.listFiles()).toList.flatten.foreach(_.delete())
      dir.toFile.delete()

  private def opened(path: Path): Long =
    val result = SclJetJvmVfsHost.open(path.toString, readOnly = false, create = true)
    assert(result.isOk, result)
    result.value.get

  test("positioned I/O, truncate, sync, size, and canonical identity"):
    withTempDb { (_, path) =>
      val handle = opened(path)
      try
        assert(SclJetJvmVfsHost.writeAt(handle, 2L, Array[Byte](1, 2, 3)).value.contains(3))
        val read = SclJetJvmVfsHost.readAt(handle, 1L, 6).value.get
        assert(read.bytes.toList == List[Byte](0, 1, 2, 3, 0, 0))
        assert(read.short)
        assert(SclJetJvmVfsHost.size(handle).value.contains(5L))
        assert(SclJetJvmVfsHost.truncate(handle, 3L).isOk)
        assert(SclJetJvmVfsHost.sync(handle, dataOnly = false).isOk)
        assert(SclJetJvmVfsHost.size(handle).value.contains(3L))
        assert(SclJetJvmVfsHost.fullPath(path.toString).value.contains(path.toRealPath().toString))
      finally SclJetJvmVfsHost.close(handle)
    }

  test("plugin intrinsic returns the declared bounded result record"):
    withTempDb { (_, path) =>
      val escaped = path.toString.replace("\\", "\\\\")
      val output = runIntrinsic(
        s"""case class JvmVfsResult(code: String, message: String, value: Any)
           |extern def jvmVfsExists(path: String): JvmVfsResult
           |val result = jvmVfsExists("$escaped")
           |println(result.code)
           |println(result.value)""".stripMargin)
      assert(output == "ok\nfalse", output)
    }

  test("rollback coordinator enforces shared/reserved/pending/exclusive transitions"):
    withTempDb { (_, path) =>
      val a = opened(path); val b = opened(path)
      try
        assert(SclJetJvmVfsHost.lock(a, HostLockLevel.Shared).isOk)
        assert(SclJetJvmVfsHost.lock(b, HostLockLevel.Shared).isOk)
        assert(SclJetJvmVfsHost.lock(a, HostLockLevel.Reserved).isOk)
        assert(SclJetJvmVfsHost.lock(b, HostLockLevel.Reserved).code == "busy")
        assert(SclJetJvmVfsHost.lock(a, HostLockLevel.Exclusive).code == "busy")
        assert(SclJetJvmVfsHost.unlock(b, HostLockLevel.None).isOk)
        assert(SclJetJvmVfsHost.lock(a, HostLockLevel.Pending).isOk)
        assert(SclJetJvmVfsHost.lock(a, HostLockLevel.Exclusive).isOk)
        assert(SclJetJvmVfsHost.checkReserved(b).value.contains(true))
      finally
        SclJetJvmVfsHost.close(b); SclJetJvmVfsHost.close(a)
    }

  test("WAL shared memory maps regions and enforces the eight lock bytes"):
    withTempDb { (_, path) =>
      val a = opened(path); val b = opened(path)
      try
        assert(SclJetJvmVfsHost.shmMap(a, 0, 32768, extend = true).isOk)
        assert(SclJetJvmVfsHost.shmMap(b, 0, 32768, extend = false).isOk)
        assert(SclJetJvmVfsHost.shmLock(a, 3, 2, HostShmMode.SharedLock).isOk)
        assert(SclJetJvmVfsHost.shmLock(b, 3, 2, HostShmMode.SharedLock).isOk)
        assert(SclJetJvmVfsHost.shmLock(a, 3, 2, HostShmMode.ExclusiveLock).code == "busy")
        assert(SclJetJvmVfsHost.shmLock(b, 3, 2, HostShmMode.SharedUnlock).isOk)
        assert(SclJetJvmVfsHost.shmLock(a, 3, 2, HostShmMode.ExclusiveLock).isOk)
        assert(SclJetJvmVfsHost.shmWrite(a, 0, 2, Array[Byte](7, 8)).isOk)
        assert(SclJetJvmVfsHost.shmBarrier(a).isOk)
        assert(SclJetJvmVfsHost.shmRead(b, 0, 0, 5).value.get.toList == List[Byte](0, 0, 7, 8, 0))
        assert(SclJetJvmVfsHost.shmUnmap(b, delete = false).isOk)
        assert(SclJetJvmVfsHost.shmUnmap(a, delete = true).isOk)
      finally
        SclJetJvmVfsHost.close(b); SclJetJvmVfsHost.close(a)
    }

  test("exclusive rollback lock is visible to a subprocess"):
    withTempDb { (_, path) =>
      val handle = opened(path)
      try
        assert(SclJetJvmVfsHost.lock(handle, HostLockLevel.Shared).isOk)
        assert(SclJetJvmVfsHost.lock(handle, HostLockLevel.Reserved).isOk)
        assert(SclJetJvmVfsHost.lock(handle, HostLockLevel.Pending).isOk)
        assert(SclJetJvmVfsHost.lock(handle, HostLockLevel.Exclusive).isOk)
        val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString
        val process = ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
          "scalascript.compiler.plugin.scljet.SclJetVfsLockProbe", path.toString)
          .redirectErrorStream(true).start()
        assert(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS))
        val output = String(process.getInputStream.readAllBytes()).trim
        assert(process.exitValue() == 0, output)
        assert(output == "busy", output)
      finally SclJetJvmVfsHost.close(handle)
    }

  test("exclusive host lock blocks official SQLite in another process"):
    withTempDb { (_, path) =>
      Class.forName("org.sqlite.JDBC")
      val setup = DriverManager.getConnection(s"jdbc:sqlite:$path")
      try setup.createStatement().execute("create table t(x integer)")
      finally setup.close()

      val handle = opened(path)
      try
        assert(SclJetJvmVfsHost.lock(handle, HostLockLevel.Shared).isOk)
        assert(SclJetJvmVfsHost.lock(handle, HostLockLevel.Reserved).isOk)
        assert(SclJetJvmVfsHost.lock(handle, HostLockLevel.Pending).isOk)
        assert(SclJetJvmVfsHost.lock(handle, HostLockLevel.Exclusive).isOk)
        val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString
        val process = ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
          "scalascript.compiler.plugin.scljet.SclJetSqliteLockProbe", path.toString)
          .start()
        val reader = process.inputReader()
        assert(reader.readLine() == "ready")
        // Deterministic handshake: the probe prints "querying" immediately before the
        // read query that must block on our exclusive lock, so we no longer guess with
        // a sleep. Once we've seen it, the query is holding on the host lock.
        assert(reader.readLine() == "querying")
        // While we hold the exclusive host lock the query MUST wait: it cannot return
        // until we release. A correct cross-process lock keeps the subprocess blocked
        // for the whole window; a broken one would let the query finish in a few ms and
        // the process would exit here. (busy_timeout in the probe is 30s >> this window.)
        assert(!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS),
          "SQLite query did not wait on the host exclusive lock")
        // Now release: the waiting query acquires its SHARED lock and completes.
        assert(SclJetJvmVfsHost.unlock(handle, HostLockLevel.None).isOk)
        assert(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS))
        val output = reader.readLine()
        val error = String(process.getErrorStream.readAllBytes()).trim
        assert(process.exitValue() == 0, output + "\n" + error)
        assert(output == "ok", output + "\n" + error)
      finally SclJetJvmVfsHost.close(handle)
    }
