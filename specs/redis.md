# Redis Client

General-purpose async Redis client for ScalaScript.
Backed by Lettuce (async-native Java Redis client).

## Config

```scalascript
case class RedisConfig(
  host:     String = "localhost",
  port:     Int    = 6379,
  password: Option[String] = None,
  database: Int    = 0,
  poolSize: Int    = 10,
  tls:      Boolean = false,
)
```

## Client

```scalascript
trait RedisClient:
  // ── Strings ─────────────────────────────────────────────────
  def get(key: String): Async[Option[String]]
  def set(key: String, value: String): Async[Unit]
  def set(key: String, value: String, ttl: Duration): Async[Unit]
  def setNx(key: String, value: String): Async[Boolean]
  def setNx(key: String, value: String, ttl: Duration): Async[Boolean]
  def getSet(key: String, value: String): Async[Option[String]]
  def del(keys: String*): Async[Long]
  def exists(key: String): Async[Boolean]
  def expire(key: String, ttl: Duration): Async[Boolean]
  def ttl(key: String): Async[Option[Duration]]
  def incr(key: String): Async[Long]
  def incrBy(key: String, delta: Long): Async[Long]

  // ── Hashes ───────────────────────────────────────────────────
  def hget(key: String, field: String): Async[Option[String]]
  def hset(key: String, field: String, value: String): Async[Unit]
  def hset(key: String, fields: Map[String, String]): Async[Unit]
  def hgetAll(key: String): Async[Map[String, String]]
  def hdel(key: String, fields: String*): Async[Long]
  def hexists(key: String, field: String): Async[Boolean]
  def hkeys(key: String): Async[List[String]]

  // ── Lists ────────────────────────────────────────────────────
  def lpush(key: String, values: String*): Async[Long]
  def rpush(key: String, values: String*): Async[Long]
  def lpop(key: String): Async[Option[String]]
  def rpop(key: String): Async[Option[String]]
  def lrange(key: String, start: Long, stop: Long): Async[List[String]]
  def llen(key: String): Async[Long]

  // ── Sets ─────────────────────────────────────────────────────
  def sadd(key: String, members: String*): Async[Long]
  def srem(key: String, members: String*): Async[Long]
  def smembers(key: String): Async[Set[String]]
  def sismember(key: String, member: String): Async[Boolean]
  def scard(key: String): Async[Long]

  // ── Sorted sets ──────────────────────────────────────────────
  def zadd(key: String, score: Double, member: String): Async[Long]
  def zadd(key: String, members: Map[String, Double]): Async[Long]
  def zrange(key: String, start: Long, stop: Long): Async[List[String]]
  def zrangeWithScores(key: String, start: Long, stop: Long): Async[List[(String, Double)]]
  def zscore(key: String, member: String): Async[Option[Double]]
  def zrank(key: String, member: String): Async[Option[Long]]
  def zrem(key: String, members: String*): Async[Long]
  def zcard(key: String): Async[Long]

  // ── Pub/Sub ──────────────────────────────────────────────────
  def publish(channel: String, message: String): Async[Long]
  def subscribe(channels: String*): AsyncStream[PubSubMessage]

  // ── Transactions / pipelining ─────────────────────────────────
  def transaction[A](f: RedisPipeline => Async[A]): Async[A]

  // ── Keys ─────────────────────────────────────────────────────
  def keys(pattern: String): Async[List[String]]
  def scan(pattern: String = "*", count: Int = 100): AsyncStream[String]
  def flushDb(): Async[Unit]

  def close(): Async[Unit]

case class PubSubMessage(channel: String, message: String)

trait RedisPipeline:
  def get(key: String): Async[Option[String]]
  def set(key: String, value: String): Async[Unit]
  def del(keys: String*): Async[Long]
  // ... all operations available inside pipeline

object Redis:
  def connect(config: RedisConfig): Async[RedisClient]
  def connect(url: String): Async[RedisClient]             // redis://user:pass@host:port/db
```

## Usage

```scalascript
val redis = Redis.connect(RedisConfig(host = "localhost"))

// Cache
redis.set("session:abc123", userId, ttl = 30.minutes)
val session = redis.get("session:abc123")

// Rate limiting
def rateLimit(ip: String, limit: Int): Async[Boolean] =
  val key = s"rate:$ip"
  redis.incr(key).flatMap { count =>
    if count == 1 then redis.expire(key, 1.minute).map(_ => true)
    else Async(count <= limit)
  }

// Pub/Sub
redis.subscribe("notifications", "alerts").foreach { msg =>
  println(s"[${msg.channel}] ${msg.message}")
}

// Distributed lock
def withLock[A](name: String, ttl: Duration)(f: => Async[A]): Async[Option[A]] =
  redis.setNx(s"lock:$name", "1", ttl).flatMap {
    case false => Async(None)
    case true  => f.map(Some(_)).ensuring(redis.del(s"lock:$name"))
  }
```

## Used by

- `x402-nonce-redis` — cluster-safe nonce store (double-spend prevention)
