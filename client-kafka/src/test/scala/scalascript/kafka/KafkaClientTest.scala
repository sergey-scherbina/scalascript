package scalascript.kafka

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*

/** Integration tests — skipped when Kafka is not available on localhost:9092. */
class KafkaClientTest extends AnyFunSuite with Matchers:

  given ExecutionContext = ExecutionContext.global

  private def await[A](f: scala.concurrent.Future[A]): A = Await.result(f, 15.seconds)

  private val config = KafkaConfig(
    brokers  = List("localhost:9092"),
    clientId = "scalascript-test",
    groupId  = "scalascript-test-group",
  )

  private lazy val hasKafka: Boolean =
    try
      val p = Kafka.producer(config)
      await(p.send("__test_probe__", "ping"))
      p.close()
      true
    catch case _: Throwable => false

  test("producer sends and consumer receives a message"):
    assume(hasKafka, "Kafka not available on localhost:9092")
    val topic    = s"test-topic-${System.currentTimeMillis()}"
    val producer = Kafka.producer(config)
    val consumer = Kafka.consumer(config)
    try
      val meta = await(producer.send(topic, "key1", "hello kafka"))
      meta.topic    shouldBe topic
      meta.partition shouldBe 0

      consumer.subscribe(topic)
      val records = (1 to 5).flatMap { _ =>
        await(consumer.poll(2.seconds))
      }.toList
      records.map(_.value) should contain("hello kafka")
    finally
      producer.close()
      consumer.close()

  test("producer send with key"):
    assume(hasKafka, "Kafka not available on localhost:9092")
    val topic    = s"test-keyed-${System.currentTimeMillis()}"
    val producer = Kafka.producer(config)
    try
      val meta = await(producer.send(topic, "mykey", "myvalue"))
      meta.topic shouldBe topic
    finally
      producer.close()

  test("producer flush completes without error"):
    assume(hasKafka, "Kafka not available on localhost:9092")
    val producer = Kafka.producer(config)
    try
      await(producer.send("flush-test", "v1"))
      await(producer.flush())
    finally
      producer.close()

  test("consumer commit completes without error"):
    assume(hasKafka, "Kafka not available on localhost:9092")
    val topic    = s"test-commit-${System.currentTimeMillis()}"
    val producer = Kafka.producer(config)
    val consumer = Kafka.consumer(config)
    try
      await(producer.send(topic, "val"))
      consumer.subscribe(topic)
      await(consumer.poll(2.seconds))
      await(consumer.commit())
    finally
      producer.close()
      consumer.close()
