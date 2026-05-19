package scalascript.x402.nonce

import scalascript.redis.RedisClient
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*

class RedisNonceStoreTest extends AnyFunSuite:

  private class MockRedis(setNxResult: Boolean = true) extends RedisClient:
    val calls = scala.collection.mutable.Buffer.empty[(String, String, Option[Duration])]

    def setNx(key: String, value: String): Future[Boolean] =
      calls += ((key, value, None)); Future.successful(setNxResult)
    def setNx(key: String, value: String, ttl: Duration): Future[Boolean] =
      calls += ((key, value, Some(ttl))); Future.successful(setNxResult)

    def get(key: String): Future[Option[String]]                              = ???
    def set(key: String, value: String): Future[Unit]                         = ???
    def set(key: String, value: String, ttl: Duration): Future[Unit]          = ???
    def getSet(key: String, value: String): Future[Option[String]]            = ???
    def del(keys: String*): Future[Long]                                      = ???
    def exists(key: String): Future[Boolean]                                  = ???
    def expire(key: String, ttl: Duration): Future[Boolean]                   = ???
    def ttl(key: String): Future[Option[Duration]]                            = ???
    def incr(key: String): Future[Long]                                       = ???
    def incrBy(key: String, delta: Long): Future[Long]                        = ???
    def hget(key: String, field: String): Future[Option[String]]              = ???
    def hset(key: String, field: String, value: String): Future[Unit]         = ???
    def hset(key: String, fields: Map[String, String]): Future[Unit]          = ???
    def hgetAll(key: String): Future[Map[String, String]]                     = ???
    def hdel(key: String, fields: String*): Future[Long]                      = ???
    def hexists(key: String, field: String): Future[Boolean]                  = ???
    def hkeys(key: String): Future[List[String]]                              = ???
    def lpush(key: String, values: String*): Future[Long]                     = ???
    def rpush(key: String, values: String*): Future[Long]                     = ???
    def lpop(key: String): Future[Option[String]]                             = ???
    def rpop(key: String): Future[Option[String]]                             = ???
    def lrange(key: String, start: Long, stop: Long): Future[List[String]]    = ???
    def llen(key: String): Future[Long]                                       = ???
    def sadd(key: String, members: String*): Future[Long]                     = ???
    def srem(key: String, members: String*): Future[Long]                     = ???
    def smembers(key: String): Future[Set[String]]                            = ???
    def sismember(key: String, member: String): Future[Boolean]               = ???
    def scard(key: String): Future[Long]                                      = ???
    def zadd(key: String, score: Double, member: String): Future[Long]        = ???
    def zadd(key: String, members: Map[String, Double]): Future[Long]         = ???
    def zrange(key: String, start: Long, stop: Long): Future[List[String]]    = ???
    def zscore(key: String, member: String): Future[Option[Double]]           = ???
    def zrank(key: String, member: String): Future[Option[Long]]              = ???
    def zrem(key: String, members: String*): Future[Long]                     = ???
    def zcard(key: String): Future[Long]                                      = ???
    def keys(pattern: String): Future[List[String]]                           = ???
    def flushDb(): Future[Unit]                                               = ???
    def close(): Unit                                                         = ()

  // ── claim ─────────────────────────────────────────────────────────────────────

  test("claim returns true when setNx succeeds") {
    val redis  = MockRedis(true)
    val store  = RedisNonceStore(redis)
    val result = Await.result(store.claim("0x" + "ab" * 32, BigInt(9_999_999_999L)), 5.seconds)
    assert(result)
  }

  test("claim returns false when key already exists") {
    val redis  = MockRedis(false)
    val store  = RedisNonceStore(redis)
    val result = Await.result(store.claim("0x" + "aa" * 32, BigInt(9_999_999_999L)), 5.seconds)
    assert(!result)
  }

  test("claim uses key prefix x402:nonce:") {
    val redis = MockRedis(true)
    val store = RedisNonceStore(redis)
    Await.result(store.claim("0xabcdef", BigInt(9_999_999_999L)), 5.seconds)
    assert(redis.calls.head._1 == "x402:nonce:0xabcdef")
  }

  test("claim sets a TTL based on validBefore - now") {
    val redis  = MockRedis(true)
    val store  = RedisNonceStore(redis)
    val future = BigInt(System.currentTimeMillis() / 1000) + BigInt(300)
    Await.result(store.claim("0xnonce", future), 5.seconds)
    val ttl = redis.calls.head._3
    assert(ttl.isDefined)
    assert(ttl.get.toSeconds > 0)
  }

  test("claim with past validBefore uses minimum TTL of 1s") {
    val redis = MockRedis(true)
    val store = RedisNonceStore(redis)
    Await.result(store.claim("0xold", BigInt(1)), 5.seconds)
    val ttl = redis.calls.head._3
    assert(ttl.isDefined)
    assert(ttl.get.toSeconds >= 1)
  }

  // ── cleanup ───────────────────────────────────────────────────────────────────

  test("cleanup is a no-op — TTL handles expiry, no Redis calls made") {
    val redis = MockRedis(true)
    val store = RedisNonceStore(redis)
    Await.result(store.cleanup(), 5.seconds)
    assert(redis.calls.isEmpty)
  }
