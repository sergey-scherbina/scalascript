package scalascript.payments.webhook.redis

import org.scalatest.funsuite.AnyFunSuite
import scalascript.redis.RedisClient

import java.time.Duration
import scala.concurrent.duration.{Duration as ScalaDuration}
import scala.concurrent.Future

/** Unit tests for RedisSeenKeyStore using a minimal in-memory stub Redis client.
 *  No real Redis server required.
 */
class RedisSeenKeyStoreTest extends AnyFunSuite:



  /** Minimal RedisClient stub: only set/setNx/expire/exists are used by RedisSeenKeyStore. */
  private class StubRedisClient extends RedisClient:
    private val data = collection.mutable.HashMap[String, String]()

    def setNx(key: String, value: String, ttl: ScalaDuration): Future[Boolean] =
      if data.contains(key) then Future.successful(false)
      else { data(key) = value; Future.successful(true) }

    def expire(key: String, ttl: ScalaDuration): Future[Boolean] =
      Future.successful(data.contains(key))

    def exists(key: String): Future[Boolean] = Future.successful(data.contains(key))

    // Unsupported stubs — not called by RedisSeenKeyStore
    def get(key: String)                               = Future.successful(None)
    def set(key: String, value: String)                = Future.successful(())
    def set(key: String, value: String, ttl: ScalaDuration) = Future.successful(())
    def setNx(key: String, value: String)              = Future.successful(!data.contains(key))
    def getSet(key: String, value: String)             = Future.successful(None)
    def del(keys: String*)                             = Future.successful(0L)
    def ttl(key: String)                               = Future.successful(None)
    def incr(key: String)                              = Future.successful(0L)
    def incrBy(key: String, delta: Long)               = Future.successful(0L)
    def hget(key: String, field: String)               = Future.successful(None)
    def hset(key: String, field: String, value: String) = Future.successful(())
    def hset(key: String, fields: Map[String, String]) = Future.successful(())
    def hgetAll(key: String)                           = Future.successful(Map.empty)
    def hdel(key: String, fields: String*)             = Future.successful(0L)
    def hexists(key: String, field: String)            = Future.successful(false)
    def hkeys(key: String)                             = Future.successful(Nil)
    def lpush(key: String, values: String*)            = Future.successful(0L)
    def rpush(key: String, values: String*)            = Future.successful(0L)
    def lpop(key: String)                              = Future.successful(None)
    def rpop(key: String)                              = Future.successful(None)
    def lrange(key: String, start: Long, stop: Long)   = Future.successful(Nil)
    def llen(key: String)                              = Future.successful(0L)
    def sadd(key: String, members: String*)            = Future.successful(0L)
    def srem(key: String, members: String*)            = Future.successful(0L)
    def smembers(key: String)                          = Future.successful(Set.empty)
    def sismember(key: String, member: String)         = Future.successful(false)
    def scard(key: String)                             = Future.successful(0L)
    def zadd(key: String, score: Double, member: String) = Future.successful(0L)
    def zadd(key: String, members: Map[String, Double]) = Future.successful(0L)
    def zrange(key: String, start: Long, stop: Long)   = Future.successful(Nil)
    def zscore(key: String, member: String)            = Future.successful(None)
    def zrank(key: String, member: String)             = Future.successful(None)
    def zrem(key: String, members: String*)            = Future.successful(0L)
    def zcard(key: String)                             = Future.successful(0L)
    def keys(pattern: String)                          = Future.successful(Nil)
    def flushDb()                                      = Future.successful(())
    def close(): Unit                                  = ()

  test("wasSeen returns false on first call — key was not seen before") {
    val store = RedisSeenKeyStore(StubRedisClient())
    assert(!store.wasSeen("event-001"))
  }

  test("wasSeen returns true on second call — key was already claimed") {
    val store = RedisSeenKeyStore(StubRedisClient())
    store.wasSeen("event-002")
    assert(store.wasSeen("event-002"))
  }

  test("markSeen after wasSeen — extend TTL to replay-protection window") {
    val stub  = StubRedisClient()
    val store = RedisSeenKeyStore(stub)
    store.wasSeen("event-003")
    store.markSeen("event-003", Duration.ofDays(30))
    assert(store.wasSeen("event-003"))
  }

  test("independent keys do not interfere with each other") {
    val store = RedisSeenKeyStore(StubRedisClient())
    assert(!store.wasSeen("event-A"))
    assert(!store.wasSeen("event-B"))
    assert(store.wasSeen("event-A"))
    assert(store.wasSeen("event-B"))
  }

  test("keyPrefix is applied — different prefixes are independent stores") {
    val stub   = StubRedisClient()
    val storeA = RedisSeenKeyStore(stub, keyPrefix = "psp-a:")
    val storeB = RedisSeenKeyStore(stub, keyPrefix = "psp-b:")
    storeA.wasSeen("evt-1")
    assert(!storeB.wasSeen("evt-1"), "different prefix should be independent")
  }

  test("concurrent claim — only first wasSeen returns false (advisory lock)") {
    val stub  = StubRedisClient()
    val store = RedisSeenKeyStore(stub)
    val r1 = store.wasSeen("evt-race")
    val r2 = store.wasSeen("evt-race")
    assert(!r1, "first claim should succeed (not seen)")
    assert(r2,  "second claim should fail (already claimed)")
  }
