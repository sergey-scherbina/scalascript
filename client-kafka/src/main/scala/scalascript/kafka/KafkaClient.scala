package scalascript.kafka

import org.apache.kafka.clients.producer.{KafkaProducer as JProducer, ProducerRecord}
import org.apache.kafka.clients.consumer.{KafkaConsumer as JConsumer, ConsumerRecords}
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer, ByteArraySerializer}
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*
import java.time.Duration as JDuration
import java.util.Properties

case class KafkaConfig(
  brokers:  List[String],
  clientId: String = "scalascript",
  groupId:  String = "scalascript",
)

case class KafkaRecord(
  topic:     String,
  partition: Int,
  offset:    Long,
  key:       Option[String],
  value:     String,
  timestamp: Long,
)

case class RecordMeta(topic: String, partition: Int, offset: Long)

trait KafkaProducer:
  def send(topic: String, value: String): Future[RecordMeta]
  def send(topic: String, key: String, value: String): Future[RecordMeta]
  def sendBytes(topic: String, key: Array[Byte], value: Array[Byte]): Future[RecordMeta]
  def flush(): Future[Unit]
  def close(): Unit

trait KafkaConsumer:
  def subscribe(topics: String*): Unit
  def poll(timeout: Duration = 1.second): Future[List[KafkaRecord]]
  def commit(): Future[Unit]
  def close(): Unit

object Kafka:
  def producer(config: KafkaConfig)(using ExecutionContext): KafkaProducer =
    val props = new Properties()
    props.put("bootstrap.servers",  config.brokers.mkString(","))
    props.put("client.id",          config.clientId)
    props.put("key.serializer",     classOf[StringSerializer].getName)
    props.put("value.serializer",   classOf[StringSerializer].getName)
    props.put("acks",               "all")
    new StringKafkaProducer(new JProducer(props))

  def bytesProducer(config: KafkaConfig)(using ExecutionContext): KafkaProducer =
    val props = new Properties()
    props.put("bootstrap.servers",  config.brokers.mkString(","))
    props.put("client.id",          config.clientId)
    props.put("key.serializer",     classOf[ByteArraySerializer].getName)
    props.put("value.serializer",   classOf[ByteArraySerializer].getName)
    props.put("acks",               "all")
    new BytesKafkaProducer(new JProducer(props))

  def consumer(config: KafkaConfig)(using ExecutionContext): KafkaConsumer =
    val props = new Properties()
    props.put("bootstrap.servers",   config.brokers.mkString(","))
    props.put("group.id",            config.groupId)
    props.put("client.id",           config.clientId)
    props.put("key.deserializer",    classOf[StringDeserializer].getName)
    props.put("value.deserializer",  classOf[StringDeserializer].getName)
    props.put("auto.offset.reset",   "earliest")
    props.put("enable.auto.commit",  "false")
    new StringKafkaConsumer(new JConsumer(props))

private class StringKafkaProducer(p: JProducer[String, String])(using ec: ExecutionContext)
    extends KafkaProducer:

  def send(topic: String, value: String): Future[RecordMeta] =
    send(topic, null, value)

  def send(topic: String, key: String, value: String): Future[RecordMeta] =
    Future(blocking {
      val meta = p.send(ProducerRecord(topic, key, value)).get()
      RecordMeta(meta.topic(), meta.partition(), meta.offset())
    })

  def sendBytes(topic: String, key: Array[Byte], value: Array[Byte]): Future[RecordMeta] =
    send(topic, new String(key, "UTF-8"), new String(value, "UTF-8"))

  def flush(): Future[Unit] = Future(blocking(p.flush()))
  def close(): Unit = p.close()

private class BytesKafkaProducer(p: JProducer[Array[Byte], Array[Byte]])(using ec: ExecutionContext)
    extends KafkaProducer:

  def send(topic: String, value: String): Future[RecordMeta] =
    sendBytes(topic, Array.emptyByteArray, value.getBytes("UTF-8"))

  def send(topic: String, key: String, value: String): Future[RecordMeta] =
    sendBytes(topic, key.getBytes("UTF-8"), value.getBytes("UTF-8"))

  def sendBytes(topic: String, key: Array[Byte], value: Array[Byte]): Future[RecordMeta] =
    Future(blocking {
      val meta = p.send(ProducerRecord(topic, key, value)).get()
      RecordMeta(meta.topic(), meta.partition(), meta.offset())
    })

  def flush(): Future[Unit] = Future(blocking(p.flush()))
  def close(): Unit = p.close()

private class StringKafkaConsumer(c: JConsumer[String, String])(using ec: ExecutionContext)
    extends KafkaConsumer:

  def subscribe(topics: String*): Unit =
    c.subscribe(topics.toList.asJava)

  def poll(timeout: Duration = 1.second): Future[List[KafkaRecord]] =
    Future(blocking {
      val records: ConsumerRecords[String, String] =
        c.poll(JDuration.ofMillis(timeout.toMillis))
      records.iterator().asScala.map { r =>
        KafkaRecord(r.topic(), r.partition(), r.offset(), Option(r.key()), r.value(), r.timestamp())
      }.toList
    })

  def commit(): Future[Unit] = Future(blocking(c.commitSync()))
  def close(): Unit = c.close()
