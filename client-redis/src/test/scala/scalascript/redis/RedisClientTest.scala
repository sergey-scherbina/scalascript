package scalascript.redis

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*

class RedisClientTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  given ExecutionContext = ExecutionContext.global

  private def await[A](f: scala.concurrent.Future[A]): A = Await.result(f, 5.seconds)

  private lazy val hasRedis: Boolean =
    try
      val r = Redis.connect(RedisConfig())
      await(r.flushDb())
      r.close()
      true
    catch case _: Throwable => false

  private lazy val redis: RedisClient = Redis.connect(RedisConfig())

  override def afterAll(): Unit =
    if hasRedis then
      await(redis.flushDb())
      redis.close()

  // ── Strings ──────────────────────────────────────────────────

  test("set and get"):
    assume(hasRedis, "Redis not available")
    await(redis.set("k1", "hello"))
    await(redis.get("k1")) shouldBe Some("hello")

  test("get returns None for missing key"):
    assume(hasRedis, "Redis not available")
    await(redis.get("no-such-key-xyz")) shouldBe None

  test("set with TTL"):
    assume(hasRedis, "Redis not available")
    await(redis.set("k-ttl", "v", 60.seconds))
    await(redis.get("k-ttl")) shouldBe Some("v")
    await(redis.ttl("k-ttl")).isDefined shouldBe true

  test("setNx sets only when absent"):
    assume(hasRedis, "Redis not available")
    await(redis.del("nx-key"))
    await(redis.setNx("nx-key", "first"))  shouldBe true
    await(redis.setNx("nx-key", "second")) shouldBe false
    await(redis.get("nx-key")) shouldBe Some("first")

  test("del removes keys"):
    assume(hasRedis, "Redis not available")
    await(redis.set("d1", "x"))
    await(redis.set("d2", "y"))
    await(redis.del("d1", "d2")) shouldBe 2L
    await(redis.exists("d1")) shouldBe false

  test("incr and incrBy"):
    assume(hasRedis, "Redis not available")
    await(redis.del("counter"))
    await(redis.incr("counter"))     shouldBe 1L
    await(redis.incr("counter"))     shouldBe 2L
    await(redis.incrBy("counter", 8)) shouldBe 10L

  // ── Hashes ───────────────────────────────────────────────────

  test("hset / hget / hgetAll"):
    assume(hasRedis, "Redis not available")
    await(redis.hset("user:1", Map("name" -> "Alice", "email" -> "alice@x.com")))
    await(redis.hget("user:1", "name"))  shouldBe Some("Alice")
    await(redis.hget("user:1", "email")) shouldBe Some("alice@x.com")
    val all = await(redis.hgetAll("user:1"))
    all shouldBe Map("name" -> "Alice", "email" -> "alice@x.com")

  test("hdel removes fields"):
    assume(hasRedis, "Redis not available")
    await(redis.hset("user:2", "name", "Bob"))
    await(redis.hdel("user:2", "name")) shouldBe 1L
    await(redis.hget("user:2", "name")) shouldBe None

  // ── Lists ────────────────────────────────────────────────────

  test("rpush / lrange / llen"):
    assume(hasRedis, "Redis not available")
    await(redis.del("mylist"))
    await(redis.rpush("mylist", "a", "b", "c"))
    await(redis.llen("mylist"))           shouldBe 3L
    await(redis.lrange("mylist", 0, -1)) shouldBe List("a", "b", "c")

  test("lpop / rpop"):
    assume(hasRedis, "Redis not available")
    await(redis.del("poplist"))
    await(redis.rpush("poplist", "x", "y", "z"))
    await(redis.lpop("poplist")) shouldBe Some("x")
    await(redis.rpop("poplist")) shouldBe Some("z")

  // ── Sets ─────────────────────────────────────────────────────

  test("sadd / smembers / sismember / scard"):
    assume(hasRedis, "Redis not available")
    await(redis.del("myset"))
    await(redis.sadd("myset", "a", "b", "c"))
    await(redis.scard("myset"))          shouldBe 3L
    await(redis.sismember("myset", "b")) shouldBe true
    await(redis.sismember("myset", "z")) shouldBe false
    await(redis.smembers("myset"))       shouldBe Set("a", "b", "c")

  // ── Sorted sets ──────────────────────────────────────────────

  test("zadd / zrange / zscore / zrank"):
    assume(hasRedis, "Redis not available")
    await(redis.del("scores"))
    await(redis.zadd("scores", Map("alice" -> 95.0, "bob" -> 80.0, "carol" -> 88.0)))
    await(redis.zrange("scores", 0, -1)) shouldBe List("bob", "carol", "alice")
    await(redis.zscore("scores", "alice")) shouldBe Some(95.0)
    await(redis.zrank("scores", "bob"))    shouldBe Some(0L)
