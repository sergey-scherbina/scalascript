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
