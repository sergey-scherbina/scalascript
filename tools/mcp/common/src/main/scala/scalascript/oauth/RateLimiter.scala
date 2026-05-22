package scalascript.oauth

import java.util.concurrent.ConcurrentHashMap

/** v1.17.x — token-bucket rate limiter for the OAuth AS hot paths
 *  (`/token`, `/authorize`).  Pluggable + thread-safe.  Defeats
 *  credential-brute-force against `client_secret` and password-
 *  spraying against any human-facing authorize-step UI.
 *
 *  Algorithm: each key maintains a bucket holding `capacity` tokens,
 *  refilled at `refillRatePerSec` (continuous, computed lazily on
 *  each `allow()` call so we don't need a background thread).  An
 *  `allow()` consumes one token; over-budget calls return false.
 *
 *  Keys are caller-chosen — typical mixes:
 *    - clientId          → per-app budget
 *    - clientIp           → per-IP budget (use behind a trusted proxy
 *                          that sets X-Forwarded-For!)
 *    - (clientId, IP)    → per-app-from-IP, tightest */
trait RateLimiter:
  /** Returns true when the request is within budget; false when it
   *  should be rejected with 429 Too Many Requests. */
  def allow(key: String): Boolean

object RateLimiter:

  /** No-op limiter — every call passes.  Default for AuthServer when
   *  the user hasn't wired one in. */
  object Disabled extends RateLimiter:
    def allow(key: String): Boolean = true

  /** Continuous-refill token bucket.  Single instance covers many
   *  keys; per-key buckets live in a ConcurrentHashMap. */
  class TokenBucket(capacity: Int, refillRatePerSec: Double) extends RateLimiter:
    private val buckets = ConcurrentHashMap[String, Bucket]()

    def allow(key: String): Boolean =
      val b   = buckets.computeIfAbsent(key, _ => new Bucket(capacity.toDouble))
      val now = System.nanoTime()
      b.consume(now, capacity.toDouble, refillRatePerSec)

  private class Bucket(initial: Double):
    @volatile private var tokens: Double = initial
    @volatile private var lastRefillNs: Long = System.nanoTime()
    /** Returns true iff a token was successfully consumed.  Refills
     *  in-place based on wall-clock time elapsed since the last call;
     *  small races between threads can over-refill by one token —
     *  acceptable for security limits that round in the caller's
     *  favour.  Hard locking would add contention to the hot path. */
    def consume(now: Long, cap: Double, ratePerSec: Double): Boolean = this.synchronized {
      val elapsedSec = (now - lastRefillNs).toDouble / 1_000_000_000.0
      val refilled   = math.min(cap, tokens + elapsedSec * ratePerSec)
      lastRefillNs   = now
      if refilled >= 1.0 then
        tokens = refilled - 1.0
        true
      else
        tokens = refilled
        false
    }
