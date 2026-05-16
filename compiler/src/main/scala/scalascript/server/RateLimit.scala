package scalascript.server

import java.util.concurrent.ConcurrentHashMap

/** Lightweight fixed-window rate limiter for protecting hot endpoints
 *  (login, password reset, OTP submit) against brute-force attempts.
 *
 *  `tryAcquire(key, limit, windowSeconds)` returns `true` if the call
 *  is allowed and bumps the counter for the current window; `false`
 *  if `limit` requests have already happened within `windowSeconds`.
 *  Different keys are tracked independently — the usual pattern is to
 *  key on `s"login:${clientIp(req)}"` or per-user.
 *
 *  Fixed-window is the simplest counter scheme — gets up to 2× the
 *  nominal rate at window boundaries.  For finer accuracy a sliding
 *  window or token bucket would do, but for an HTTP auth guard the
 *  approximation is fine.  No background thread; entries are GC'd
 *  lazily on next access. */
object RateLimit:
  private case class Bucket(count: java.util.concurrent.atomic.AtomicLong, windowStartMs: Long)

  private val buckets = ConcurrentHashMap[String, Bucket]()

  def tryAcquire(key: String, limit: Long, windowSeconds: Long): Boolean =
    val now      = java.lang.System.currentTimeMillis()
    val windowMs = windowSeconds * 1000L
    val current  = buckets.get(key)
    if current == null || now - current.windowStartMs >= windowMs then
      val fresh = Bucket(java.util.concurrent.atomic.AtomicLong(1L), now)
      val prior = buckets.put(key, fresh)
      // If someone else just inserted at the same moment, fold their count in.
      if prior != null && now - prior.windowStartMs < windowMs then
        fresh.count.addAndGet(prior.count.get())
      fresh.count.get() <= limit
    else
      current.count.incrementAndGet() <= limit

  /** Reset the counter for a key — handy after a successful login. */
  def reset(key: String): Unit =
    buckets.remove(key)

  /** Wipe everything (tests). */
  def clear(): Unit = buckets.clear()
