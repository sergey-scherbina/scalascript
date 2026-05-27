package scalascript.payments.webhook.redis

import scalascript.payments.webhook.SeenKeyStore
import scalascript.redis.RedisClient
import java.time.Duration
import scala.concurrent.Await
import scala.concurrent.duration.{Duration => SD, MILLISECONDS}

/** Cluster-safe idempotency store backed by Redis.
 *
 *  `wasSeen` does a plain EXISTS check.
 *  `markSeen` uses SET NX EX — atomic set-if-not-exists with TTL — so that
 *  even under concurrent retries across multiple instances, at most one node
 *  permanently records the key.  The window between `wasSeen` and `markSeen`
 *  is handled by the atomic NX write: if two instances race on the same key,
 *  only one `setNx` succeeds; the loser's handler will see the key on its
 *  next re-check (PSP retries with a delay, so re-entrancy is not a concern).
 *
 *  @param redis      Connected Lettuce RedisClient.
 *  @param prefix     Key namespace prefix (default: "whk:").
 *  @param timeoutMs  Await timeout per Redis call in milliseconds (default: 5000). */
class RedisSeenKeyStore(
    redis:     RedisClient,
    prefix:    String = "whk:",
    timeoutMs: Long   = 5000L,
) extends SeenKeyStore:

  private def sync[A](f: scala.concurrent.Future[A]): A =
    Await.result(f, SD(timeoutMs, MILLISECONDS))

  def wasSeen(key: String): Boolean =
    sync(redis.exists(s"$prefix$key"))

  def markSeen(key: String, expiry: Duration): Unit =
    sync(redis.setNx(s"$prefix$key", "1", SD(expiry.toMillis, MILLISECONDS)))
    ()
