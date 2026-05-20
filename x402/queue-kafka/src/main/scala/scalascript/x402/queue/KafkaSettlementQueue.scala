package scalascript.x402.queue

import scalascript.x402.*
import scalascript.kafka.KafkaProducer
import scala.concurrent.{ExecutionContext, Future}
import ujson.*

// ── Kafka-backed SettlementQueue ──────────────────────────────────────────────
// Produces serialized (PaymentPayload, PaymentRequirements) to a Kafka topic.
// process() consumes from the topic and settles each item.
// For process() to work, a matching consumer must be provided externally.
// The drain loop is left to the application (call process(fac) on a schedule).

private class KafkaSettlementQueueImpl(
  producer: KafkaProducer,
  topic:    String,
)(using ec: ExecutionContext) extends SettlementQueue:

  def enqueue(payload: PaymentPayload, req: PaymentRequirements): Future[Unit] =
    val json = encode(payload, req)
    producer.send(topic, json).map(_ => ())

  def process(facilitator: Facilitator): Future[Unit] =
    // Drain is application-side: use a KafkaConsumer to poll + settle.
    // This is intentionally a no-op in the enqueue-only path.
    Future.unit

  private def encode(payload: PaymentPayload, req: PaymentRequirements): String =
    val auth = payload.authorization
    ujson.Obj(
      "payload"     -> ujson.Obj(
        "x402Version"   -> payload.x402Version,
        "scheme"        -> schemeJson(payload.scheme),
        "network"       -> payload.network.toString,
        "authorization" -> ujson.Obj(
          "from"        -> auth.from,
          "to"          -> auth.to,
          "value"       -> auth.value.toString,
          "validAfter"  -> auth.validAfter.toString,
          "validBefore" -> auth.validBefore.toString,
          "nonce"       -> auth.nonce,
        ),
        "signature"     -> payload.signature,
      ),
      "requirements" -> ujson.Obj(
        "network"           -> req.network.toString,
        "payTo"             -> req.payTo,
        "resource"          -> req.resource,
        "description"       -> req.description,
        "maxTimeoutSeconds" -> req.maxTimeoutSeconds,
        "asset"             -> ujson.Obj(
          "address"  -> req.asset.address,
          "symbol"   -> req.asset.symbol,
          "decimals" -> req.asset.decimals,
        ),
        "scheme"            -> schemeJson(req.scheme),
      ),
    ).toString

  private def schemeJson(scheme: PaymentScheme): ujson.Value = scheme match
    case PaymentScheme.Exact(amount) =>
      ujson.Obj("type" -> "exact", "amount" -> amount.toString)
    case PaymentScheme.Stream(rate, unit, maxU, maxA) =>
      ujson.Obj("type" -> "stream", "ratePerUnit" -> rate.toString,
        "unitName" -> unit, "maxUnits" -> maxU, "maxAmount" -> maxA.toString)
    case PaymentScheme.CardanoExact(lovelace, asset) =>
      val o = ujson.Obj("type" -> "cardanoExact", "lovelace" -> lovelace.toString)
      asset.foreach(a => o("asset") = ujson.Obj(
        "policyId" -> a.policyId, "assetName" -> a.assetName, "symbol" -> a.symbol))
      o

object KafkaSettlementQueue:
  def apply(producer: KafkaProducer, topic: String = "x402-settlements")(using ExecutionContext): SettlementQueue =
    new KafkaSettlementQueueImpl(producer, topic)
