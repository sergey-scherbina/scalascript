package scalascript.x402.queue

import scalascript.x402.*
import scalascript.kafka.{KafkaProducer, RecordMeta}
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import ExecutionContext.Implicits.global

class KafkaSettlementQueueTest extends AnyFunSuite:

  private val testReq = PaymentRequirements(
    scheme      = PaymentScheme.Exact(1_000_000L),
    network     = Network.Base,
    asset       = Assets.USDC_BASE,
    payTo       = "0xpayTo",
    resource    = "/api/premium",
    description = "Test",
  )

  private val testPayload = PaymentPayload(
    scheme        = PaymentScheme.Exact(1_000_000L),
    network       = Network.Base,
    authorization = TransferAuthorization(
      from        = "0xfrom",
      to          = "0xpayTo",
      value       = BigInt(1_000_000),
      validAfter  = BigInt(0),
      validBefore = BigInt(9_999_999_999L),
      nonce       = "0x" + "ab" * 32,
    ),
    signature     = "0x" + "cc" * 65,
  )

  private def mockProducer(): (KafkaProducer, scala.collection.mutable.Buffer[(String, String)]) =
    val sent = scala.collection.mutable.Buffer.empty[(String, String)]
    val producer = new KafkaProducer:
      def send(topic: String, value: String): Future[RecordMeta] =
        sent += (topic -> value)
        Future.successful(RecordMeta(topic, 0, sent.size.toLong))
      def send(topic: String, key: String, value: String): Future[RecordMeta] =
        sent += (topic -> value)
        Future.successful(RecordMeta(topic, 0, sent.size.toLong))
      def sendBytes(topic: String, key: Array[Byte], value: Array[Byte]): Future[RecordMeta] = ???
      def flush(): Future[Unit] = Future.unit
      def close(): Unit = ()
    (producer, sent)

  test("enqueue sends JSON to the configured topic") {
    val (producer, sent) = mockProducer()
    val queue = KafkaSettlementQueue(producer, "x402-settlements")
    Await.result(queue.enqueue(testPayload, testReq), 5.seconds)
    assert(sent.size == 1)
    assert(sent.head._1 == "x402-settlements")
    val j = ujson.read(sent.head._2)
    assert(j("payload")("network").str == "Base")
    assert(j("requirements")("payTo").str == "0xpayTo")
  }

  test("enqueue serializes scheme correctly") {
    val (producer, sent) = mockProducer()
    val queue = KafkaSettlementQueue(producer)
    Await.result(queue.enqueue(testPayload, testReq), 5.seconds)
    val j = ujson.read(sent.head._2)
    assert(j("payload")("scheme")("type").str == "exact")
    assert(j("payload")("scheme")("amount").str == "1000000")
  }

  test("enqueue serializes authorization nonce") {
    val (producer, sent) = mockProducer()
    val queue = KafkaSettlementQueue(producer)
    Await.result(queue.enqueue(testPayload, testReq), 5.seconds)
    val j = ujson.read(sent.head._2)
    assert(j("payload")("authorization")("nonce").str == testPayload.authorization.nonce)
  }

  test("process is a no-op (drain is application-side)") {
    val (producer, _) = mockProducer()
    val queue = KafkaSettlementQueue(producer)
    Await.result(queue.process(Facilitators.testnet()), 5.seconds)  // should not throw
  }

  test("default topic is x402-settlements") {
    val (producer, sent) = mockProducer()
    val queue = KafkaSettlementQueue(producer)
    Await.result(queue.enqueue(testPayload, testReq), 5.seconds)
    assert(sent.head._1 == "x402-settlements")
  }
