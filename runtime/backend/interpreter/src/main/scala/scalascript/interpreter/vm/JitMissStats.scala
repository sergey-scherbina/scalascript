package scalascript.interpreter.vm

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import scala.jdk.CollectionConverters.*

/** Compile-time JIT miss counters. Each call to [[VmCompiler.compile]] that
 *  returns `None` increments the counter for its bail reason.
 *
 *  Enable with `SSC_JIT_STATS=1` (or any value other than `0`/`off`).
 *  Stats are printed to stderr at JVM shutdown.
 *
 *  Use to answer "what should I add JIT support for next?" — the highest
 *  count is the construct blocking the most functions from being compiled.
 */
object JitMissStats:

  val enabled: Boolean =
    sys.env.get("SSC_JIT_STATS").exists(v => v != "0" && v != "off" && v.nonEmpty)

  private val counts   = new ConcurrentHashMap[String, LongAdder]()
  private val total    = new LongAdder()

  def record(reason: String): Unit =
    if enabled then
      counts.computeIfAbsent(reason, _ => new LongAdder()).increment()
      total.increment()

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
