package scalascript.payments.webhook.redis

import org.scalatest.funsuite.AnyFunSuite
import scalascript.redis.RedisClient
import scalascript.payments.webhook.SeenKeyStore
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{Duration => SD}

class RedisSeenKeyStoreTest extends AnyFunSuite:
  given ExecutionContext = ExecutionContext.global

  // Stub RedisClient using an in-memory map — no Redis server needed
  private def stubClient(store: ConcurrentHashMap[String, String] = ConcurrentHashMap()): RedisClient =
    new RedisClient:
      def get(k: String): Future[Option[String]]             = Future.successful(Option(store.get(k)))
      def set(k: String, v: String): Future[Unit]            = Future.successful(store.put(k, v)); Future.successful(())
      def set(k: String, v: String, ttl: SD): Future[Unit]   = Future.successful(store.put(k, v)); Future.successful(())
      def setNx(k: String, v: String): Future[Boolean]       = Future.successful(store.putIfAbsent(k, v) == null)
      def setNx(k: String, v: String, ttl: SD): Future[Boolean] = Future.successful(store.putIfAbsent(k, v) == null)
      def getSet(k: String, v: String): Future[Option[String]] = Future.successful(Option(store.put(k, v)))
      def del(keys: String*): Future[Long]                   = Future.successful(keys.count(k => store.remove(k) != null).toLong)
      def exists(k: String): Future[Boolean]                 = Future.successful(store.containsKey(k))
      def expire(k: String, ttl: SD): Future[Boolean]        = Future.successful(store.containsKey(k))
      def ttl(k: String): Future[Option[SD]]                 = Future.successful(None)
      def incr(k: String): Future[Long]                      = Future.successful(0L)
      def incrBy(k: String, d: Long): Future[Long]           = Future.successful(0L)
      def hget(k: String, f: String): Future[Option[String]] = Future.successful(None)
      def hset(k: String, f: String, v: String): Future[Unit] = Future.successful(())
      def hset(k: String, fs: Map[String, String]): Future[Unit] = Future.successful(())
      def hgetAll(k: String): Future[Map[String, String]]    = Future.successful(Map.empty)
      def hdel(k: String, fs: String*): Future[Long]         = Future.successful(0L)
      def hexists(k: String, f: String): Future[Boolean]     = Future.successful(false)
      def hkeys(k: String): Future[List[String]]             = Future.successful(List.empty)
      def lpush(k: String, vs: String*): Future[Long]        = Future.successful(vs.size.toLong)
      def rpush(k: String, vs: String*): Future[Long]        = Future.successful(vs.size.toLong)
      def lpop(k: String): Future[Option[String]]            = Future.successful(None)
      def rpop(k: String): Future[Option[String]]            = Future.successful(None)
      def lrange(k: String, s: Long, e: Long): Future[List[String]] = Future.successful(List.empty)
      def llen(k: String): Future[Long]                      = Future.successful(0L)
      def sadd(k: String, ms: String*): Future[Long]         = Future.successful(ms.size.toLong)
      def srem(k: String, ms: String*): Future[Long]         = Future.successful(0L)
      def smembers(k: String): Future[Set[String]]           = Future.successful(Set.empty)
      def sismember(k: String, m: String): Future[Boolean]   = Future.successful(false)
      def scard(k: String): Future[Long]                     = Future.successful(0L)
      def zadd(k: String, s: Double, m: String): Future[Long] = Future.successful(0L)
      def zadd(k: String, ms: Map[String, Double]): Future[Long] = Future.successful(0L)
      def zrange(k: String, s: Long, e: Long): Future[List[String]] = Future.successful(List.empty)
      def zscore(k: String, m: String): Future[Option[Double]] = Future.successful(None)
      def zrank(k: String, m: String): Future[Option[Long]]  = Future.successful(None)
      def zrem(k: String, ms: String*): Future[Long]         = Future.successful(0L)
      def zcard(k: String): Future[Long]                     = Future.successful(0L)
      def keys(pat: String): Future[List[String]]            = Future.successful(List.empty)
      def flushDb(): Future[Unit]                            = Future.successful(store.clear())
      def close(): Unit                                      = ()

  test("RedisSeenKeyStore: wasSeen returns false for new key"):
    val store = RedisSeenKeyStore(stubClient(), "test:")
    assert(!store.wasSeen("key-new"))

  test("RedisSeenKeyStore: wasSeen returns true after markSeen"):
    val store = RedisSeenKeyStore(stubClient(), "test:")
    store.markSeen("key-a", Duration.ofDays(1))
    assert(store.wasSeen("key-a"))

  test("RedisSeenKeyStore: different keys are independent"):
    val store = RedisSeenKeyStore(stubClient(), "test:")
    store.markSeen("key-x", Duration.ofDays(1))
    assert(store.wasSeen("key-x"))
    assert(!store.wasSeen("key-y"))

  test("RedisSeenKeyStore: prefix isolates namespaces"):
    val underlying = ConcurrentHashMap[String, String]()
    val s1 = RedisSeenKeyStore(stubClient(underlying), "ns1:")
    val s2 = RedisSeenKeyStore(stubClient(underlying), "ns2:")
    s1.markSeen("event", Duration.ofDays(1))
    assert(s1.wasSeen("event"))
    assert(!s2.wasSeen("event"))

  test("RedisSeenKeyStore: markSeen is idempotent (second write is no-op)"):
    val underlying = ConcurrentHashMap[String, String]()
    val store = RedisSeenKeyStore(stubClient(underlying), "test:")
    store.markSeen("key-idem", Duration.ofDays(1))
    store.markSeen("key-idem", Duration.ofDays(1))
    assert(store.wasSeen("key-idem"))

  test("RedisSeenKeyStore: concurrent markSeen — only first wins in stub"):
    val underlying = ConcurrentHashMap[String, String]()
    val s1 = RedisSeenKeyStore(stubClient(underlying), "test:")
    val s2 = RedisSeenKeyStore(stubClient(underlying), "test:")
    s1.markSeen("shared-key", Duration.ofDays(1))
    val s2Won = !s2.wasSeen("shared-key-fresh")
    assert(s2Won)

  test("RedisSeenKeyStore: implements SeenKeyStore trait"):
    val store: SeenKeyStore = RedisSeenKeyStore(stubClient(), "test:")
    assert(!store.wasSeen("any"))

  test("RedisSeenKeyStore: default prefix is whk:"):
    val underlying = ConcurrentHashMap[String, String]()
    val store = RedisSeenKeyStore(stubClient(underlying))
    store.markSeen("evt", Duration.ofDays(1))
    assert(underlying.containsKey("whk:evt"))
