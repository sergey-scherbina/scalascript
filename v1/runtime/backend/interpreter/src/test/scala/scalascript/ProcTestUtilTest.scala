package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Proves the subprocess timeout actually fires — the bug `runCaptured` fixes was
 *  that a wedged child hung the WHOLE suite forever (the blocking stream read
 *  parked before the timeout could be reached). */
class ProcTestUtilTest extends AnyFunSuite with Matchers:

  test("runCaptured captures stdout and exit 0"):
    val r = ProcTestUtil.runCaptured(Seq("sh", "-c", "printf 'hello\\nworld'"), secs = 30)
    r.exit shouldBe 0
    r.timedOut shouldBe false
    r.out shouldBe "hello\nworld"

  test("runCaptured surfaces a non-zero exit + stderr"):
    val r = ProcTestUtil.runCaptured(Seq("sh", "-c", "echo boom >&2; exit 3"), secs = 30)
    r.exit shouldBe 3
    r.timedOut shouldBe false
    r.err should include("boom")

  test("runCaptured force-kills a hung child within the deadline (the firsthand fix)"):
    val t0 = System.nanoTime()
    // A child that never exits and holds its streams open — the exact shape that
    // used to hang the suite forever. Bound it to 2s; assert it returns promptly.
    val r = ProcTestUtil.runCaptured(Seq("sh", "-c", "sleep 60"), secs = 2)
    val elapsedMs = (System.nanoTime() - t0) / 1000000
    r.timedOut shouldBe true
    r.exit shouldBe -1
    withClue(s"returned in ${elapsedMs}ms, expected ~2s not ~60s: ") {
      elapsedMs should be < 15000L
    }

  test("runCaptured does not deadlock on a child that floods stderr"):
    // Lots of stderr output while we'd otherwise block on stdout — the classic
    // pipe-buffer deadlock the threaded drain avoids.
    val r = ProcTestUtil.runCaptured(
      Seq("sh", "-c", "i=0; while [ $i -lt 5000 ]; do echo line$i >&2; i=$((i+1)); done; echo done"),
      secs = 30)
    r.timedOut shouldBe false
    r.exit shouldBe 0
    r.out shouldBe "done"
    r.err.count(_ == '\n') should be > 4000
