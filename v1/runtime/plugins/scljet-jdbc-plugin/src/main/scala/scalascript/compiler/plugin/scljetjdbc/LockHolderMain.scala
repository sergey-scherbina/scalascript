package scalascript.compiler.plugin.scljetjdbc

import java.nio.file.Paths

/** A tiny second-process harness: acquire the host write lock for `argv(0)`, print `LOCKED`, and
 *  hold it until stdin closes. Used only by `ScljetDriverTest` to prove the lock is genuinely
 *  cross-PROCESS (a same-JVM guard could otherwise mask a broken `FileLock`). Not part of the
 *  public surface. */
object LockHolderMain:
  def main(argv: Array[String]): Unit =
    val handle = HostFileLock.acquire(Paths.get(argv(0)), 0L)
    println("LOCKED")
    System.out.flush()
    // Block until the parent closes our stdin (or kills us), then release.
    try System.in.read() catch case _: Throwable => ()
    handle.release()
