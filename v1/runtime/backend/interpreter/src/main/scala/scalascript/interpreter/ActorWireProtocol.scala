package scalascript.interpreter

import scalascript.wire.*
import scalascript.wire.cbor.CborWireCodec
import scalascript.wire.json.JsonWireCodec
import scalascript.wire.msgpack.MsgPackWireCodec

/** Binary WebSocket sub-protocol helpers for actor cluster communication.
 *
 *  v1 ("ssc-actors-v1"): plain JSON text frames — unchanged from before.
 *  v2 ("ssc-actors-v2.{cbor,msgpack,json}"): `WireEnvelope` binary frames
 *  containing a `WireValue.Str(json)` payload.  The internal cluster message
 *  format remains JSON; the wire layer wraps it in an envelope so format
 *  negotiation is transparent to `dispatchPeerEnvelope`. */
private[scalascript] object ActorWireProtocol:

  val V1      = "ssc-actors-v1"
  val V2Json  = "ssc-actors-v2.json"
  val V2MsgPack = "ssc-actors-v2.msgpack"
  val V2Cbor  = "ssc-actors-v2.cbor"

  /** Subprotocol preference order offered by the client (most preferred first). */
  val clientProtocols: List[String] = List(V2Cbor, V2MsgPack, V2Json, V1)

  /** Server-side accepted protocols list for wsRoutes.register. */
  val serverProtocols: List[String] = clientProtocols

  def isV2(proto: String): Boolean = proto.startsWith("ssc-actors-v2.")

  /** Encode a cluster JSON message as a binary WireEnvelope for the given v2 format.
   *  The returned bytes are sent as a binary WS frame. */
  def encodeV2(proto: String, json: String): Array[Byte] =
    val payload = WireValue.Str(json)
    proto match
      case V2Cbor =>
        CborWireCodec.encodeEnvelope(WireEnvelope.actors("cbor", "cluster", payload))
      case V2MsgPack =>
        MsgPackWireCodec.encodeEnvelope(WireEnvelope.actors("msgpack", "cluster", payload))
      case V2Json =>
        JsonWireCodec.encodeEnvelope(WireEnvelope.actors("json", "cluster", payload)).getBytes("UTF-8")
      case _ =>
        throw new IllegalArgumentException(s"not a v2 actor protocol: $proto")

  /** Decode a binary WS frame (stored as an ISO-8859-1 string from the recv queue)
   *  back to a cluster JSON message.  Returns None if the frame is malformed. */
  def decodeV2(proto: String, isoStr: String): Option[String] =
    val bytes = isoStr.getBytes("ISO-8859-1")
    val result: Either[WireDecodeError, WireEnvelope] = proto match
      case V2Cbor    => CborWireCodec.decodeEnvelope(bytes)
      case V2MsgPack => MsgPackWireCodec.decodeEnvelope(bytes)
      case V2Json    => JsonWireCodec.decodeEnvelope(new String(bytes, "UTF-8"))
      case _         => Left(WireDecodeError.MalformedInput(s"not a v2 actor protocol: $proto"))
    result.toOption.collect {
      case WireEnvelope(_, _, _, _, _, _, _, _, WireValue.Str(json)) => json
    }
