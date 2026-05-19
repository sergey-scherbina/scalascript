package scalascript.redis

import io.lettuce.core.{RedisClient as LettuceClient, SetArgs}
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters.*
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit

case class PubSubMessage(channel: String, message: String)

trait RedisClient:
  // Strings
  def get(key: String): Future[Option[String]]
  def set(key: String, value: String): Future[Unit]
  def set(key: String, value: String, ttl: Duration): Future[Unit]
  def setNx(key: String, value: String): Future[Boolean]
  def setNx(key: String, value: String, ttl: Duration): Future[Boolean]
  def getSet(key: String, value: String): Future[Option[String]]
  def del(keys: String*): Future[Long]
  def exists(key: String): Future[Boolean]
  def expire(key: String, ttl: Duration): Future[Boolean]
  def ttl(key: String): Future[Option[Duration]]
  def incr(key: String): Future[Long]
  def incrBy(key: String, delta: Long): Future[Long]

  // Hashes
  def hget(key: String, field: String): Future[Option[String]]
  def hset(key: String, field: String, value: String): Future[Unit]
  def hset(key: String, fields: Map[String, String]): Future[Unit]
  def hgetAll(key: String): Future[Map[String, String]]
  def hdel(key: String, fields: String*): Future[Long]
  def hexists(key: String, field: String): Future[Boolean]
  def hkeys(key: String): Future[List[String]]

  // Lists
  def lpush(key: String, values: String*): Future[Long]
  def rpush(key: String, values: String*): Future[Long]
  def lpop(key: String): Future[Option[String]]
  def rpop(key: String): Future[Option[String]]
  def lrange(key: String, start: Long, stop: Long): Future[List[String]]
  def llen(key: String): Future[Long]

  // Sets
  def sadd(key: String, members: String*): Future[Long]
  def srem(key: String, members: String*): Future[Long]
  def smembers(key: String): Future[Set[String]]
  def sismember(key: String, member: String): Future[Boolean]
  def scard(key: String): Future[Long]

  // Sorted sets
  def zadd(key: String, score: Double, member: String): Future[Long]
  def zadd(key: String, members: Map[String, Double]): Future[Long]
  def zrange(key: String, start: Long, stop: Long): Future[List[String]]
  def zscore(key: String, member: String): Future[Option[Double]]
  def zrank(key: String, member: String): Future[Option[Long]]
  def zrem(key: String, members: String*): Future[Long]
  def zcard(key: String): Future[Long]

  // Keys
  def keys(pattern: String): Future[List[String]]
  def flushDb(): Future[Unit]

  def close(): Unit

object Redis:
  def connect(config: RedisConfig)(using ExecutionContext): RedisClient =
    val client = LettuceClient.create(config.uri)
    new LettuceRedisClient(client, client.connect())

  def connect(uri: String)(using ExecutionContext): RedisClient =
    val client = LettuceClient.create(uri)
    new LettuceRedisClient(client, client.connect())

private class LettuceRedisClient(
  client: LettuceClient,
  conn:   StatefulRedisConnection[String, String],
)(using ec: ExecutionContext) extends RedisClient:

  private val cmd: RedisAsyncCommands[String, String] = conn.async()

  private def f[A](fa: => io.lettuce.core.RedisFuture[A]): Future[A] =
    fa.toCompletableFuture.asScala

  private def fUnit[A](fa: => io.lettuce.core.RedisFuture[A]): Future[Unit] =
    fa.toCompletableFuture.asScala.map(_ => ())

  def get(key: String): Future[Option[String]] =
    f(cmd.get(key)).map(Option(_))

  def set(key: String, value: String): Future[Unit] =
    fUnit(cmd.set(key, value))

  def set(key: String, value: String, ttl: Duration): Future[Unit] =
    fUnit(cmd.setex(key, ttl.toSeconds, value))

  def setNx(key: String, value: String): Future[Boolean] =
    f(cmd.setnx(key, value)).map(_.booleanValue())

  def setNx(key: String, value: String, ttl: Duration): Future[Boolean] =
    f(cmd.set(key, value, SetArgs.Builder.nx().ex(ttl.toSeconds))).map(_ != null)

  def getSet(key: String, value: String): Future[Option[String]] =
    f(cmd.getset(key, value)).map(Option(_))

  def del(keys: String*): Future[Long] =
    f(cmd.del(keys*)).map(_.longValue())

  def exists(key: String): Future[Boolean] =
    f(cmd.exists(key)).map(_ > 0)

  def expire(key: String, ttl: Duration): Future[Boolean] =
    f(cmd.expire(key, ttl.toSeconds)).map(_.booleanValue())

  def ttl(key: String): Future[Option[Duration]] =
    f(cmd.ttl(key)).map { v =>
      val secs = v.longValue()
      if secs < 0 then None else Some(Duration(secs, TimeUnit.SECONDS))
    }

  def incr(key: String): Future[Long] =
    f(cmd.incr(key)).map(_.longValue())

  def incrBy(key: String, delta: Long): Future[Long] =
    f(cmd.incrby(key, delta)).map(_.longValue())

  def hget(key: String, field: String): Future[Option[String]] =
    f(cmd.hget(key, field)).map(Option(_))

  def hset(key: String, field: String, value: String): Future[Unit] =
    fUnit(cmd.hset(key, field, value))

  def hset(key: String, fields: Map[String, String]): Future[Unit] =
    fUnit(cmd.hset(key, fields.asJava))

  def hgetAll(key: String): Future[Map[String, String]] =
    f(cmd.hgetall(key)).map(_.asScala.toMap)

  def hdel(key: String, fields: String*): Future[Long] =
    f(cmd.hdel(key, fields*)).map(_.longValue())

  def hexists(key: String, field: String): Future[Boolean] =
    f(cmd.hexists(key, field)).map(_.booleanValue())

  def hkeys(key: String): Future[List[String]] =
    f(cmd.hkeys(key)).map(_.asScala.toList)

  def lpush(key: String, values: String*): Future[Long] =
    f(cmd.lpush(key, values*)).map(_.longValue())

  def rpush(key: String, values: String*): Future[Long] =
    f(cmd.rpush(key, values*)).map(_.longValue())

  def lpop(key: String): Future[Option[String]] =
    f(cmd.lpop(key)).map(Option(_))

  def rpop(key: String): Future[Option[String]] =
    f(cmd.rpop(key)).map(Option(_))

  def lrange(key: String, start: Long, stop: Long): Future[List[String]] =
    f(cmd.lrange(key, start, stop)).map(_.asScala.toList)

  def llen(key: String): Future[Long] =
    f(cmd.llen(key)).map(_.longValue())

  def sadd(key: String, members: String*): Future[Long] =
    f(cmd.sadd(key, members*)).map(_.longValue())

  def srem(key: String, members: String*): Future[Long] =
    f(cmd.srem(key, members*)).map(_.longValue())

  def smembers(key: String): Future[Set[String]] =
    f(cmd.smembers(key)).map(_.asScala.toSet)

  def sismember(key: String, member: String): Future[Boolean] =
    f(cmd.sismember(key, member)).map(_.booleanValue())

  def scard(key: String): Future[Long] =
    f(cmd.scard(key)).map(_.longValue())

  def zadd(key: String, score: Double, member: String): Future[Long] =
    f(cmd.zadd(key, score, member)).map(_.longValue())

  def zadd(key: String, members: Map[String, Double]): Future[Long] =
    val scoredValues = members.map { (m, s) =>
      io.lettuce.core.ScoredValue.just(s, m)
    }.toArray
    f(cmd.zadd(key, scoredValues*)).map(_.longValue())

  def zrange(key: String, start: Long, stop: Long): Future[List[String]] =
    f(cmd.zrange(key, start, stop)).map(_.asScala.toList)

  def zscore(key: String, member: String): Future[Option[Double]] =
    f(cmd.zscore(key, member)).map(v => Option(v).map(_.doubleValue()))

  def zrank(key: String, member: String): Future[Option[Long]] =
    f(cmd.zrank(key, member)).map(v => Option(v).map(_.longValue()))

  def zrem(key: String, members: String*): Future[Long] =
    f(cmd.zrem(key, members*)).map(_.longValue())

  def zcard(key: String): Future[Long] =
    f(cmd.zcard(key)).map(_.longValue())

  def keys(pattern: String): Future[List[String]] =
    f(cmd.keys(pattern)).map(_.asScala.toList)

  def flushDb(): Future[Unit] =
    fUnit(cmd.flushdb())

  def close(): Unit =
    conn.close()
    client.shutdown()
