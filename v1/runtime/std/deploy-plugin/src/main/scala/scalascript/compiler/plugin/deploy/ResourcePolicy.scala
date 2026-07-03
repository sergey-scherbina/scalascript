package scalascript.compiler.plugin.deploy

/** Resource limits applied to a worker slot.
 *  Values of 0 mean "unlimited" (default). */
case class ResourcePolicy(
  maxCpuMs:     Long = 0L,     // max CPU time per invocation in ms (0 = unlimited)
  maxMemoryMb:  Long = 0L,     // max JVM heap allocation in MB (0 = unlimited)
  maxThreads:   Int  = 0,      // max concurrent invocations (0 = unlimited)
  maxQueueDepth:Int  = 0,      // load-shedding queue depth (0 = unlimited)
)

object ResourcePolicy:
  val unlimited: ResourcePolicy = ResourcePolicy()

  def parse(raw: Map[String, Any]): ResourcePolicy =
    ResourcePolicy(
      maxCpuMs     = raw.get("max_cpu_ms").collect { case n: Integer => n.toLong; case n: Long => n }.getOrElse(0L),
      maxMemoryMb  = raw.get("max_memory_mb").collect { case n: Integer => n.toLong; case n: Long => n }.getOrElse(0L),
      maxThreads   = raw.get("max_threads").collect { case n: Integer => n.toInt }.getOrElse(0),
      maxQueueDepth= raw.get("max_queue_depth").collect { case n: Integer => n.toInt }.getOrElse(0),
    )

/** Tracks per-worker active invocation count for load shedding. */
class LoadTracker:
  private val counts = new java.util.concurrent.ConcurrentHashMap[String, java.util.concurrent.atomic.AtomicLong]()

  def acquire(workerId: String, policy: ResourcePolicy): Boolean =
    if policy.maxQueueDepth <= 0 then true
    else
      val counter = counts.computeIfAbsent(workerId, _ => new java.util.concurrent.atomic.AtomicLong(0))
      val current = counter.get()
      if current >= policy.maxQueueDepth then false
      else
        counter.incrementAndGet()
        true

  def release(workerId: String): Unit =
    val counter = counts.get(workerId)
    if counter != null then counter.updateAndGet(n => math.max(0L, n - 1))

  def activeCount(workerId: String): Long =
    val c = counts.get(workerId)
    if c == null then 0L else c.get()

object LoadTracker:
  val global: LoadTracker = new LoadTracker()
