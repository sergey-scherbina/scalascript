package scalascript.wallet.walletconnect

import java.util.concurrent.atomic.AtomicLong

/** Helpers to build + parse the JSON-RPC frames the WC v2 relay
 *  expects. The relay protocol is plain JSON-RPC 2.0 carried over
 *  the encrypted ws channel; outbound messages are `irn_publish`
 *  / `irn_subscribe` / `irn_unsubscribe` requests, and inbound
 *  messages we care about are `irn_subscription` notifications
 *  whose `params.data.message` is the base64-encoded ChaCha20-
 *  Poly1305 envelope. */
object RelayJsonRpc:

  /** Default WC relay tags — the relay uses these as a hint for
   *  prioritisation + retention. The full table is in the WC v2
   *  spec; we only need the handful that appear in this slice. */
  object Tag:
    val SessionPropose:         Int = 1100
    val SessionProposeResponse: Int = 1101
    val SessionSettle:          Int = 1102
    val SessionSettleResponse:  Int = 1103
    val SessionUpdate:          Int = 1104
    val SessionUpdateResponse:  Int = 1105
    val SessionExtend:          Int = 1106
    val SessionExtendResponse:  Int = 1107
    val SessionRequest:         Int = 1108
    val SessionRequestResponse: Int = 1109
    val SessionEvent:           Int = 1110
    val SessionEventResponse:   Int = 1111
    val SessionDelete:          Int = 1112
    val SessionDeleteResponse:  Int = 1113
    val SessionPing:            Int = 1114
    val SessionPingResponse:    Int = 1115

  /** Default TTLs in seconds, mirroring `@walletconnect/utils`. */
  object Ttl:
    val FiveMinutes:   Long = 300
    val OneDay:        Long = 86400
    val SevenDays:     Long = 604800

  /** Monotonic JSON-RPC id allocator — thread-safe, never returns
   *  zero. */
  final class IdAllocator(start: Long = 1L):
    private val counter = new AtomicLong(start)
    def next(): Long = counter.getAndIncrement()

  /** Build an `irn_publish` JSON-RPC request object. The relay
   *  acknowledges with `{"id":n,"jsonrpc":"2.0","result":true}`. */
  def buildPublish(
    id:        Long,
    topic:     String,
    message:   String,
    ttl:       Long,
    tag:       Int,
    prompt:    Boolean = false,
  ): ujson.Obj =
    ujson.Obj(
      "id"      -> ujson.Num(id.toDouble),
      "jsonrpc" -> ujson.Str("2.0"),
      "method"  -> ujson.Str("irn_publish"),
      "params"  -> ujson.Obj(
        "topic"   -> ujson.Str(topic),
        "message" -> ujson.Str(message),
        "ttl"     -> ujson.Num(ttl.toDouble),
        "tag"     -> ujson.Num(tag.toDouble),
        "prompt"  -> ujson.Bool(prompt),
      ),
    )

  def buildSubscribe(id: Long, topic: String): ujson.Obj =
    ujson.Obj(
      "id"      -> ujson.Num(id.toDouble),
      "jsonrpc" -> ujson.Str("2.0"),
      "method"  -> ujson.Str("irn_subscribe"),
      "params"  -> ujson.Obj("topic" -> ujson.Str(topic)),
    )

  def buildUnsubscribe(id: Long, topic: String, subscriptionId: Option[String] = None): ujson.Obj =
    val params = ujson.Obj("topic" -> ujson.Str(topic))
    subscriptionId.foreach(sid => params("id") = ujson.Str(sid))
    ujson.Obj(
      "id"      -> ujson.Num(id.toDouble),
      "jsonrpc" -> ujson.Str("2.0"),
      "method"  -> ujson.Str("irn_unsubscribe"),
      "params"  -> params,
    )

  /** Render a JSON-RPC object as the wire string. */
  def render(obj: ujson.Obj): String = obj.render()

  /** Parsed inbound JSON-RPC frame. */
  sealed trait Inbound
  object Inbound:
    /** A relay-side notification: `irn_subscription`. Carries the
     *  topic the publisher sent on + the base64-encoded envelope. */
    final case class Subscription(
      requestId:      Long,
      subscriptionId: String,
      topic:          String,
      message:        String,
      tag:            Option[Int],
      publishedAt:    Option[Long],
    ) extends Inbound

    /** An ack/response for one of our outbound requests. */
    final case class Response(id: Long, result: Option[ujson.Value], error: Option[ujson.Value]) extends Inbound

    /** A request from the relay we don't implement (or any unknown
     *  shape). Kept so the demux can log + drop without exceptions. */
    final case class Other(method: Option[String], raw: ujson.Value) extends Inbound

  /** Parse a raw text frame from the ws. Returns `None` on
   *  malformed JSON; otherwise returns one of the `Inbound`
   *  variants. */
  def parse(text: String): Option[Inbound] =
    try
      val v = ujson.read(text)
      val obj = v.obj
      if obj.contains("method") then
        val method = obj("method").str
        if method == "irn_subscription" then
          val id     = obj.get("id").map(_.num.toLong).getOrElse(0L)
          val params = obj("params").obj
          val subId  = params.get("id").map(_.str).getOrElse("")
          val data   = params("data").obj
          val topic  = data("topic").str
          val msg    = data("message").str
          val tag    = data.get("tag").map(_.num.toInt)
          val pubAt  = data.get("publishedAt").map(_.num.toLong)
          Some(Inbound.Subscription(id, subId, topic, msg, tag, pubAt))
        else
          Some(Inbound.Other(Some(method), v))
      else if obj.contains("id") && (obj.contains("result") || obj.contains("error")) then
        Some(Inbound.Response(
          id     = obj("id").num.toLong,
          result = obj.get("result"),
          error  = obj.get("error"),
        ))
      else
        Some(Inbound.Other(None, v))
    catch case _: Throwable => None
