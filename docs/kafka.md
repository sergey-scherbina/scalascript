# Kafka Client

General-purpose async Kafka producer/consumer for ScalaScript.
Backed by the `kafka-clients` JVM library, wrapped in `Async`.

## Config

```scalascript
case class KafkaConfig(
  brokers:  List[String],
  clientId: String = "scalascript",
  groupId:  String = "scalascript",
)
```

## Types

```scalascript
case class KafkaRecord(
  topic:     String,
  partition: Int,
  offset:    Long,
  key:       Option[String],
  value:     String,
  timestamp: Long,
)

case class RecordMeta(topic: String, partition: Int, offset: Long)
```

## Producer

```scalascript
trait KafkaProducer:
  def send(topic: String, value: String): Async[RecordMeta]
  def send(topic: String, key: String, value: String): Async[RecordMeta]
  def sendBytes(topic: String, key: Array[Byte], value: Array[Byte]): Async[RecordMeta]
  def flush(): Async[Unit]
  def close(): Async[Unit]
```

## Consumer

```scalascript
trait KafkaConsumer:
  def subscribe(topics: String*): Async[Unit]
  def poll(timeout: Duration = 1.second): Async[List[KafkaRecord]]
  def commit(): Async[Unit]
  def commitAsync(): Async[Unit]
  def stream(topics: String*): AsyncStream[KafkaRecord]
  def close(): Async[Unit]
```

## Factory

```scalascript
object Kafka:
  def producer(config: KafkaConfig): Async[KafkaProducer]
  def consumer(config: KafkaConfig): Async[KafkaConsumer]
```

## Usage

```scalascript
val producer = Kafka.producer(KafkaConfig(brokers = List("localhost:9092")))
producer.send("events", """{"type":"click","user":42}""")

val consumer = Kafka.consumer(KafkaConfig(
  brokers = List("localhost:9092"),
  groupId = "workers",
))
consumer.stream("events", "alerts").foreach { record =>
  println(s"[${record.topic}] ${record.value}")
}
```

## Used by

- `x402-queue-kafka` — async settlement queue
