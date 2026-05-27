package scalascript.payments.webhook.redis

import scalascript.payments.webhook.SeenKeyStore
import scalascript.redis.RedisClient

import java.time.Duration
import scala.concurrent.duration.{Duration as ScalaDuration, SECONDS}
import scala.concurrent.Await

/** Redis-backed SeenKeyStore with atomic claim-on-check.
 *
 *  wasSeen atomically attempts SET key "1" NX EX claimTtlSeconds.
 *  If the SET succeeds the key was not previously seen — wasSeen returns false.
 *  If the SET fails another instance already holds the key — wasSeen returns true.
 *
 *  markSeen extends the TTL to the final replay-protection window (default 30 days).
 *  If the handler throws between wasSeen and markSeen, the key expires after
 *  claimTtlSeconds so the event can be reprocessed after the claim window.
 *
 *  Lettuce uses its own internal event-loop; no ExecutionContext is required here.
 */
class RedisSeenKeyStore(
  client:          RedisClient,
  keyPrefix:       String = "webhook:",
  claimTtlSeconds: Long   = 300,
  awaitTimeoutMs:  Long   = 5_000,
) extends SeenKeyStore:

  private val awaitTimeout = ScalaDuration(awaitTimeoutMs, "ms")

  private def prefixed(key: String): String = s"$keyPrefix$key"

  def wasSeen(key: String): Boolean =
    val claimDuration = ScalaDuration(claimTtlSeconds, SECONDS)
    val claimed = Await.result(client.setNx(prefixed(key), "1", claimDuration), awaitTimeout)
    !claimed

  def markSeen(key: String, expiry: Duration): Unit =
    val secs = expiry.getSeconds
    Await.result(client.expire(prefixed(key), ScalaDuration(secs, SECONDS)), awaitTimeout)
    ()

object RedisSeenKeyStore:
  def apply(
    client:          RedisClient,
    keyPrefix:       String = "webhook:",
    claimTtlSeconds: Long   = 300,
    awaitTimeoutMs:  Long   = 5_000,
  ): RedisSeenKeyStore =
    new RedisSeenKeyStore(client, keyPrefix, claimTtlSeconds, awaitTimeoutMs)
