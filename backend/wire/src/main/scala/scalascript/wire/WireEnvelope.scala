package scalascript.wire

/** Universal frame envelope for all ScalaScript binary protocols.
 *
 *  Binary transports encode the envelope in the selected format.
 *  Transport-layer framing (WS message / HTTP body / 4-byte length prefix)
 *  wraps one serialised `WireEnvelope` per message.
 *
 *  Spec: specs/distributed-wire-protocol.md §Frame Envelope */
case class WireEnvelope(
  protocol:      String,              // "actors" | "dataset" | "dstream" | "rpc" | "object-sync"
  protocolVer:   Int,                 // per-surface protocol version
  format:        String,              // "json" | "msgpack" | "cbor"
  kind:          String,              // surface-specific message kind
  correlationId: Option[String],      // request/reply, stream chunks, tracing
  schemaId:      Option[String],      // typed payload schema hash/id
  flags:         Set[String],         // "compressed" | "hmac" | "final" | "error" | "chunked"
  headers:       Map[String, String],
  payload:       WireValue,
)

object WireEnvelope:
  val empty: WireEnvelope = WireEnvelope(
    protocol      = "",
    protocolVer   = 1,
    format        = WireFormat.Json,
    kind          = "",
    correlationId = None,
    schemaId      = None,
    flags         = Set.empty,
    headers       = Map.empty,
    payload       = WireValue.Null,
  )

  def apply(
    protocol:    String,
    format:      String,
    kind:        String,
    payload:     WireValue,
  ): WireEnvelope = WireEnvelope(
    protocol      = protocol,
    protocolVer   = 1,
    format        = format,
    kind          = kind,
    correlationId = None,
    schemaId      = None,
    flags         = Set.empty,
    headers       = Map.empty,
    payload       = payload,
  )

  def actors(format: String, kind: String, payload: WireValue): WireEnvelope =
    apply("actors", format, kind, payload)

  def rpc(format: String, kind: String, payload: WireValue, correlationId: Option[String] = None): WireEnvelope =
    WireEnvelope(
      protocol      = "rpc",
      protocolVer   = 1,
      format        = format,
      kind          = kind,
      correlationId = correlationId,
      schemaId      = None,
      flags         = Set.empty,
      headers       = Map.empty,
      payload       = payload,
    )

  /** Chunk headers for large payloads split across multiple envelopes. */
  def chunkHeaders(chunkId: String, index: Int, count: Int): Map[String, String] =
    Map("chunk-id" -> chunkId, "chunk-index" -> index.toString, "chunk-count" -> count.toString)
