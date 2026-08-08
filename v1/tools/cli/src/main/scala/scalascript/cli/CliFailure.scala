package scalascript.cli

import scala.util.control.NonFatal

/** One place where an uncaught failure becomes what a user reads.
 *
 * Both launchers end up here, and that is the point. `bin/ssc` (`StandardMain`) has printed a clean
 * `ssc: <message>` since it was written; `bin/ssc-tools` (`@main def ssc` in `Main.scala`) had no
 * top-level catch at all, so the same failure arrived as
 * `Exception in thread "main" java.lang.RuntimeException: …` followed by a stack trace. Two
 * launchers, one language, two different ideas of what an error looks like — and the repo already
 * treated the raw form as a defect: `tests/e2e/v2-swift-cli.sh` asserts `Exception in thread` is
 * absent on nine CLI paths. It was absent there because those paths handle their own errors, not
 * because the entry point did.
 *
 * **The trace stays reachable.** `SSC_STACKTRACE=1` prints it after the message. Without that, this
 * would trade a bad error message for an undebuggable compiler: the message is for the person
 * running a program, the trace is for the person fixing `ssc`, and both people exist. */
object CliFailure:

  /** `1` or `true`; anything else — including `0` and unset — means "just the message".
   *
   * Deliberately NOT `sys.env.contains`: `SSC_FASTTIER=0` disabling nothing has already burned this
   * repo once, where an env var was read for presence and a user's `=0` turned the feature ON. */
  def stackTraceRequested: Boolean =
    sys.env.get("SSC_STACKTRACE").exists(v => v == "1" || v == "true")

  /** Print the failure the way a user should see it, then exit non-zero. */
  def report(error: Throwable): Nothing =
    error match
      // A control-flow rejection renders itself: it carries a stable code and diagnostic, and its
      // stack trace is switched off at construction, so there is nothing else worth printing.
      case failure: _root_.ssc.ControlRunFailure =>
        System.err.println(failure.rendered)
      case _ =>
        // No second `ssc: ` — a message that prefixes itself gives the user `ssc: ssc:`, which is
        // exactly what shipped from the Stub arms earlier today.
        System.err.println(s"ssc: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}")
    if stackTraceRequested then error.printStackTrace()
    System.exit(1)
    throw error // unreachable — System.exit does not return; this only gives the method type Nothing

  /** Wrap an entry point. `NonFatal` on purpose: an `OutOfMemoryError` or a `StackOverflowError` is
   * not something to summarise into one line, and the JVM's own report is the useful one there. */
  def guard[A](body: => A): A =
    try body
    catch case NonFatal(error) => report(error)
