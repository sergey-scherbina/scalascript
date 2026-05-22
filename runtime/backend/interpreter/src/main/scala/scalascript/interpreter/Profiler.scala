package scalascript.interpreter

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import scala.jdk.CollectionConverters.*

/** Lightweight call-level profiler for the ScalaScript interpreter.
 *
 *  When `enabled` is true, `callFun` wraps each user-defined function
 *  invocation with `System.nanoTime()` before/after and accumulates:
 *    - call count  (via `LongAdder` — thread-safe, cheap under contention)
 *    - total wall-clock nanoseconds
 *
 *  Only named user functions are counted (anonymous lambdas with an empty
 *  name are skipped — too noisy).  Native built-ins are never counted here.
 *
 *  The object is process-global (singleton) so the CLI can enable it
 *  before running a module and read results afterwards without threading
 *  any extra state through the Interpreter constructor.
 */
object Profiler:

  @volatile var enabled: Boolean = false

  private val counts = new ConcurrentHashMap[String, LongAdder]()
  private val nanos  = new ConcurrentHashMap[String, LongAdder]()

  /** Record one invocation of `name` that took `elapsedNs` nanoseconds. */
  def record(name: String, elapsedNs: Long): Unit =
    counts.computeIfAbsent(name, _ => new LongAdder()).increment()
    nanos.computeIfAbsent(name, _ => new LongAdder()).add(elapsedNs)

  /** Return top-N entries sorted by total wall time descending.
   *  Each element is `(functionName, callCount, totalNanos)`. */
  def topN(n: Int): List[(String, Long, Long)] =
    val names = counts.keySet().asScala
    names.toList
      .map(name => (name, counts.get(name).sum(), nanos.get(name).sum()))
      .sortBy(-_._3)
      .take(n)

  /** Reset all accumulated data (called before a new run). */
  def reset(): Unit =
    counts.clear()
    nanos.clear()

  /** Total wall-clock nanoseconds across all recorded functions. */
  def totalNanos: Long =
    nanos.values().asScala.map(_.sum()).sum

  /** Render a human-readable table of the top-N hotspots.
   *
   *  Example:
   *  {{{
   *  ── Profile ──────────────────────────────────────────────
   *    calls      time(ms)  function
   *    ──────────────────────────────
   *    832040         1523  fib
   *         1           12  main
   *    ──────────────────────────────
   *    Total wall time: 1547 ms
   *  }}}
   */
  def renderTable(n: Int = 20): String =
    val rows = topN(n)
    if rows.isEmpty then return "  (no user functions recorded)\n"
    val sb = new StringBuilder
    val hdr = "── Profile " + "─" * 48
    sb.append(hdr).append('\n')
    sb.append(f"  ${"calls"}%8s  ${"time(ms)"}%10s  function\n")
    val sep = "  " + "─" * 34
    sb.append(sep).append('\n')
    for (name, calls, ns) <- rows do
      val ms = ns / 1_000_000L
      sb.append(f"  $calls%8d  $ms%10d  $name\n")
    sb.append(sep).append('\n')
    val totalMs = totalNanos / 1_000_000L
    sb.append(s"  Total wall time: $totalMs ms\n")
    sb.toString()
