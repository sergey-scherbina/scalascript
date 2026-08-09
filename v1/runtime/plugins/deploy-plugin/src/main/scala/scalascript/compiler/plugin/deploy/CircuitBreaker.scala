package scalascript.compiler.plugin.deploy

import java.util.concurrent.ConcurrentHashMap

/** Per-worker circuit breaker.
 *  After `threshold` consecutive failures the circuit opens and calls are rejected
 *  until `resetAfterMs` milliseconds have elapsed. */
class CircuitBreaker(val threshold: Int = 5, val resetAfterMs: Long = 30_000L):

  private case class State(failures: Long, openedAt: Long, open: Boolean)

  private val states = new ConcurrentHashMap[String, State]()

  def isOpen(workerId: String): Boolean =
    val s = states.get(workerId)
    if s == null then false
    else if !s.open then false
    else if System.currentTimeMillis() - s.openedAt >= resetAfterMs then
      states.remove(workerId)
      false
    else true

  def recordSuccess(workerId: String): Unit =
    states.remove(workerId)

  def recordFailure(workerId: String): Boolean =
    val now     = System.currentTimeMillis()
    val prev    = states.getOrDefault(workerId, State(0, 0, false))
    val failures = prev.failures + 1
    val opens   = failures >= threshold
    states.put(workerId, State(failures, if opens then now else prev.openedAt, opens))
    opens

  def reset(workerId: String): Unit =
    states.remove(workerId)

  def allOpen: List[String] =
    val now = System.currentTimeMillis()
    val buf = List.newBuilder[String]
    states.forEach { (k, s) =>
      if s.open && now - s.openedAt < resetAfterMs then buf += k
    }
    buf.result()

  def failureCount(workerId: String): Long =
    val s = states.get(workerId)
    if s == null then 0L else s.failures

object CircuitBreaker:
  val global: CircuitBreaker = new CircuitBreaker()
