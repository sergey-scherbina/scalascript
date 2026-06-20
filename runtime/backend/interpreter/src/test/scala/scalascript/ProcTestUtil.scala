package scalascript

import java.util.concurrent.TimeUnit

/** Subprocess helpers for the run-via-node / compile-via-scala-cli tests.
 *  Every wait is bounded: a wedged subprocess (e.g. scala-cli stuck fetching
 *  deps) can never park `waitFor()` forever and hang the whole suite — it is
 *  force-killed and reported as a failure instead. */
object ProcTestUtil:

  /** True iff `cmd <versionArg>` exits 0 within `secs`. Any failure → false. */
  def commandOk(cmd: String, versionArg: String = "--version", secs: Int = 30): Boolean =
    try
      val p = ProcessBuilder(cmd, versionArg).start()
      if p.waitFor(secs, TimeUnit.SECONDS) then p.exitValue() == 0
      else { p.destroyForcibly(); false }
    catch case _: Throwable => false

  /** waitFor with a timeout; on timeout kills the process and returns -1
   *  (so callers checking `!= 0` / `shouldBe 0` treat a hang as a failure). */
  def awaitExit(p: Process, secs: Int = 180): Int =
    if p.waitFor(secs, TimeUnit.SECONDS) then p.exitValue()
    else { p.destroyForcibly(); -1 }

  /** Result of a bounded subprocess run. `timedOut` is true when the process was
   *  force-killed for exceeding the deadline (then `exit` is -1). */
  final case class ProcResult(exit: Int, out: String, err: String, timedOut: Boolean)

  /** Run a command, capturing stdout+stderr, with a HARD timeout that actually
   *  holds.
   *
   *  The naive pattern `Source.fromInputStream(p.getInputStream).mkString` then
   *  `awaitExit` is NOT bounded: the blocking read to EOF parks BEFORE `awaitExit`
   *  is ever reached, so a subprocess that never closes its streams (a wedged
   *  scala-cli / corrupt bloop daemon) hangs the whole suite forever — observed
   *  firsthand. It can also deadlock: a child that fills the stderr pipe buffer
   *  while we block on stdout never drains.
   *
   *  This drains stdout AND stderr on separate daemon threads (no pipe-buffer
   *  deadlock), waits at most `secs`, force-kills on timeout (which closes the
   *  streams so the drain threads finish), and joins them briefly. */
  def runCaptured(cmd: Seq[String], secs: Int = 180): ProcResult =
    val p = ProcessBuilder(cmd*).start()
    val outBuf = new StringBuilder
    val errBuf = new StringBuilder
    def drain(in: java.io.InputStream, sb: StringBuilder): Thread =
      val t = new Thread(() =>
        try
          val r = new java.io.BufferedReader(new java.io.InputStreamReader(in))
          var line = r.readLine()
          while line != null do { sb.synchronized { sb.append(line).append('\n') }; line = r.readLine() }
        catch case _: Throwable => ()   // stream closed by destroyForcibly → done
      )
      t.setDaemon(true); t.start(); t
    val ot = drain(p.getInputStream, outBuf)
    val et = drain(p.getErrorStream, errBuf)
    val finished = p.waitFor(secs, TimeUnit.SECONDS)
    if !finished then p.destroyForcibly()
    ot.join(5000); et.join(5000)
    val exit = if finished then p.exitValue() else -1
    ProcResult(exit, outBuf.toString.trim, errBuf.toString.trim, timedOut = !finished)
