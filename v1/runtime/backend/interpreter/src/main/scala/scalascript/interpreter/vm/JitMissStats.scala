package scalascript.interpreter.vm

import scalascript.interpreter.vm.jit.JitBailReason

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import scala.jdk.CollectionConverters.*

/** Compile-time JIT miss counters for all three engines (vm, javac, asm).
 *
 *  Enable with `SSC_JIT_STATS=1` (or any value other than `0`/`off`).
 *  Stats are printed to stderr at JVM shutdown, grouped by engine.
 */
object JitMissStats:

  val enabled: Boolean =
    sys.env.get("SSC_JIT_STATS").exists(v => v != "0" && v != "off" && v.nonEmpty)

  private val counts = new ConcurrentHashMap[String, LongAdder]()
  private val total  = new LongAdder()

  /** Record a miss for `engine` (`"vm"`, `"javac"`, or `"asm"`) with a
   *  typed [[JitBailReason]]. */
  def record(engine: String, reason: JitBailReason): Unit =
    if enabled then
      val key = s"[$engine] ${reason.tag}"
      counts.computeIfAbsent(key, _ => new LongAdder()).increment()
      total.increment()

  /** Backwards-compatible overload: raw string reason mapped to
   *  [[JitBailReason.Other]] under the `"vm"` engine. */
  def record(reason: String): Unit =
    record("vm", JitBailReason.Other(reason))

  def report(): String =
    val n = total.sum()
    if n == 0L then return ""
    val rows = counts.entrySet().asScala
      .map(e => e.getKey -> e.getValue.sum())
      .toSeq.sortBy(-_._2)
    rows.map((k, v) => f"  $v%6d  $k")
      .mkString(s"JIT miss stats ($n functions disabled):\n", "\n", "")

  if enabled then
    Runtime.getRuntime.addShutdownHook(Thread(() =>
      val r = report()
      if r.nonEmpty then System.err.println(r)
    ))
