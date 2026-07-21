package scalascript.cli

import org.scalatest.exceptions.{TestCanceledException, TestFailedException}

/** Bounded retry for the real-WS multi-node cluster integration tests.
 *
 *  These tests spawn 2-5 real `ssc.jar` subprocesses that converge a
 *  Bully leader-election over loopback WebSockets, then snapshot the
 *  elected leader once, at a FIXED `sendAfter` window baked into each
 *  node's `.ssc` script.  The election LOGIC is correct — see BUGS.md
 *  `v1-cluster-bully-no-convergence-over-ws`, the WS-transport defect
 *  that actually broke convergence was fixed 2026-07-20.  The residual
 *  is pure timing: under CI contention (many JVMs, GC pauses) an
 *  election occasionally misses its fixed window, so the node prints an
 *  empty/stale leader and the single-shot assertion fails.  A node that
 *  prints only once cannot be recovered by test-side polling, which is
 *  why the sister `ClusterBullyStatusConvergenceTest` (which polls
 *  `/_ssc-cluster/status` until convergence) does NOT flake.
 *
 *  [[retrying]] re-runs the whole scenario — fresh ports, fresh
 *  processes — up to `attempts` times.  A genuine regression fails
 *  EVERY attempt (so this never masks a real break: the last failure is
 *  rethrown verbatim); a transient timing miss passes on a later
 *  attempt.  `TestCanceledException` (e.g. missing `ssc.jar`) is never
 *  retried — a cancel stays a cancel. */
object ClusterTestSupport:

  def retrying(attempts: Int)(body: => Unit): Unit =
    require(attempts >= 1, s"attempts must be >= 1, got $attempts")
    var attempt = 0
    var lastFailure: TestFailedException = null
    while attempt < attempts do
      attempt += 1
      try
        body
        return
      catch
        case c: TestCanceledException => throw c
        case f: TestFailedException =>
          lastFailure = f
          if attempt < attempts then
            System.err.println(
              s"[cluster-test-retry] attempt $attempt/$attempts failed " +
              s"(transient election-timing miss?), retrying: ${f.getMessage}")
    throw lastFailure
