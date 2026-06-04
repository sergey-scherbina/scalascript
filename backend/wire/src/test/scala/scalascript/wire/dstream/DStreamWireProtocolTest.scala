package scalascript.wire.dstream

import org.scalatest.funsuite.AnyFunSuite
import scalascript.wire.{WireCodec, WireDecodeError, WireEnvelope, WireFormat, WireValue}
import scalascript.wire.json.JsonWireCodec
import scalascript.wire.msgpack.MsgPackWireCodec
import scalascript.wire.cbor.CborWireCodec
import DStreamWireCodec.given

/** Round-trip and envelope-shape tests for the native DStream wire protocol.
 *
 *  Every message kind is exercised through JSON, MsgPack, and CBOR using
 *  `DStreamEnvelope.apply` + `DStreamEnvelope.decode`.
 *
 *  Spec: specs/distributed-wire-protocol.md §Phase 5 */
class DStreamWireProtocolTest extends AnyFunSuite:

  // ── Sample messages ───────────────────────────────────────────────────────

  private val batch = DStreamMsg.ElementBatch(
    pipelineId = "pipe-1",
    stageId    = "map-stage",
    elements   = Vector(WireValue.Int64(1), WireValue.Int64(2), WireValue.Int64(3)),
    isFinal    = false,
  )

  private val batchFinal = DStreamMsg.ElementBatch(
    pipelineId = "pipe-1",
    stageId    = "sink-stage",
    elements   = Vector.empty,
    isFinal    = true,
  )

  private val watermark = DStreamMsg.Watermark(
    pipelineId   = "pipe-2",
    stageId      = "source-stage",
    timestampMs  = 1_700_000_000_000L,
    sideInputTag = None,
  )

  private val watermarkTagged = DStreamMsg.Watermark(
    pipelineId   = "pipe-2",
    stageId      = "source-stage",
    timestampMs  = 1_700_000_001_000L,
    sideInputTag = Some("slow-changing-data"),
  )

  private val triggerEvent = DStreamMsg.Trigger(
    pipelineId = "pipe-3",
    windowId   = "win-5min",
    kind       = TriggerKind.EventTime,
    data       = None,
  )

  private val triggerCount = DStreamMsg.Trigger(
    pipelineId = "pipe-3",
    windowId   = "win-count",
    kind       = TriggerKind.CountBased,
    data       = Some(WireValue.Int64(1000)),
  )

  private val sideInput = DStreamMsg.SideInput(
    pipelineId = "pipe-4",
    stageId    = "join-stage",
    tag        = "lookup-table",
    elements   = Vector(WireValue.Str("row-1"), WireValue.Int64(42)),
  )

  private val sideOutput = DStreamMsg.SideOutput(
    pipelineId = "pipe-4",
    stageId    = "split-stage",
    tag        = "dead-letters",
    elements   = Vector(WireValue.Str("bad-record-1"), WireValue.Str("bad-record-2")),
  )

  private val checkpoint = DStreamMsg.CheckpointMetadata(
    pipelineId   = "pipe-5",
    checkpointId = "ckpt-00042",
    sequenceNum  = 42L,
    stateKeys    = Map("partition-0" -> "offset:1234", "partition-1" -> "offset:5678"),
    offsetsJson  = """{"partition-0":1234,"partition-1":5678}""",
  )

  private val streamError = DStreamMsg.DStreamError(
    pipelineId = "pipe-6",
    stageId    = "udf-stage",
    code       = "UDF_EXCEPTION",
    message    = "NullPointerException in user function",
    cause      = Some("java.lang.NullPointerException: null"),
  )

  private val streamErrorNoCause = DStreamMsg.DStreamError(
    pipelineId = "pipe-7",
    stageId    = "network-stage",
    code       = "NETWORK_TIMEOUT",
    message    = "Connection to peer timed out",
    cause      = None,
  )

  private val allMessages: List[(String, DStreamMsg)] = List(
    "ElementBatch(non-empty)"    -> batch,
    "ElementBatch(final/empty)"  -> batchFinal,
    "Watermark(no tag)"          -> watermark,
    "Watermark(with tag)"        -> watermarkTagged,
    "Trigger(EventTime)"         -> triggerEvent,
    "Trigger(CountBased+data)"   -> triggerCount,
    "SideInput"                  -> sideInput,
    "SideOutput"                 -> sideOutput,
    "CheckpointMetadata"         -> checkpoint,
    "DStreamError(with cause)"   -> streamError,
    "DStreamError(no cause)"     -> streamErrorNoCause,
  )

  // ── WireValue round-trips ─────────────────────────────────────────────────

  for (name, msg) <- allMessages do
    test(s"WireValue round-trip: $name"):
      val codec = summon[WireCodec[DStreamMsg]]
      val encoded = codec.encode(msg)
      codec.decode(encoded) match
        case Right(decoded) => assert(decoded == msg, s"decoded $decoded != $msg")
        case Left(err)      => fail(s"decode failed: ${err.message}")

  // ── JSON envelope round-trips ─────────────────────────────────────────────

  for (name, msg) <- allMessages do
    test(s"Envelope JSON round-trip: $name"):
      val env     = DStreamEnvelope(msg, WireFormat.Json)
      val json    = JsonWireCodec.encodeEnvelope(env)
      JsonWireCodec.decodeEnvelope(json) match
        case Right(env2) =>
          assert(env2.protocol == DStreamEnvelope.Protocol)
          DStreamEnvelope.decode(env2) match
            case Right(msg2) => assert(msg2 == msg)
            case Left(err)   => fail(s"decode failed: ${err.message}")
        case Left(err) => fail(s"JSON decodeEnvelope failed: $err")

  // ── MsgPack envelope round-trips ─────────────────────────────────────────

  for (name, msg) <- allMessages do
    test(s"Envelope MsgPack round-trip: $name"):
      val env   = DStreamEnvelope(msg, WireFormat.MsgPack)
      val bytes = MsgPackWireCodec.encodeEnvelope(env)
      MsgPackWireCodec.decodeEnvelope(bytes) match
        case Right(env2) =>
          assert(env2.protocol == DStreamEnvelope.Protocol)
          DStreamEnvelope.decode(env2) match
            case Right(msg2) => assert(msg2 == msg)
            case Left(err)   => fail(s"decode failed: ${err.message}")
        case Left(err) => fail(s"MsgPack decodeEnvelope failed: $err")

  // ── CBOR envelope round-trips ─────────────────────────────────────────────

  for (name, msg) <- allMessages do
    test(s"Envelope CBOR round-trip: $name"):
      val env   = DStreamEnvelope(msg, WireFormat.Cbor)
      val bytes = CborWireCodec.encodeEnvelope(env)
      CborWireCodec.decodeEnvelope(bytes) match
        case Right(env2) =>
          assert(env2.protocol == DStreamEnvelope.Protocol)
          DStreamEnvelope.decode(env2) match
            case Right(msg2) => assert(msg2 == msg)
            case Left(err)   => fail(s"decode failed: ${err.message}")
        case Left(err) => fail(s"CBOR decodeEnvelope failed: $err")

  // ── Envelope shape assertions ─────────────────────────────────────────────

  test("ElementBatch envelope has correct kind and protocol"):
    val env = DStreamEnvelope(batch, WireFormat.Json)
    assert(env.protocol    == "dstream")
    assert(env.protocolVer == 1)
    assert(env.kind        == "element-batch")
    assert(env.schemaId    == Some("dstream.DStreamMsg:0"))

  test("Watermark envelope has kind=watermark"):
    val env = DStreamEnvelope(watermark, WireFormat.MsgPack)
    assert(env.kind   == "watermark")
    assert(env.format == WireFormat.MsgPack)

  test("Trigger envelope has kind=trigger"):
    assert(DStreamEnvelope(triggerEvent, WireFormat.Cbor).kind == "trigger")

  test("SideInput envelope has kind=side-input"):
    assert(DStreamEnvelope(sideInput, WireFormat.Json).kind == "side-input")

  test("SideOutput envelope has kind=side-output"):
    assert(DStreamEnvelope(sideOutput, WireFormat.Json).kind == "side-output")

  test("CheckpointMetadata envelope has kind=checkpoint"):
    assert(DStreamEnvelope(checkpoint, WireFormat.Json).kind == "checkpoint")

  test("DStreamError envelope has kind=error"):
    assert(DStreamEnvelope(streamError, WireFormat.Json).kind == "error")

  // ── TriggerKind round-trip ────────────────────────────────────────────────

  for kind <- TriggerKind.values do
    test(s"TriggerKind round-trip: $kind"):
      val codec   = summon[WireCodec[TriggerKind]]
      val encoded = codec.encode(kind)
      codec.decode(encoded) match
        case Right(decoded) => assert(decoded == kind)
        case Left(err)      => fail(s"decode failed: ${err.message}")

  // ── Error handling ────────────────────────────────────────────────────────

  test("decode unknown object type returns MalformedInput"):
    val codec = summon[WireCodec[DStreamMsg]]
    codec.decode(WireValue.Object("UnknownMsg", Vector.empty)) match
      case Left(err) => assert(err.message.contains("Unknown DStreamMsg"))
      case Right(_)  => fail("expected decode error for unknown type")

  test("decode non-object returns TypeMismatch"):
    val codec = summon[WireCodec[DStreamMsg]]
    codec.decode(WireValue.Int64(42)) match
      case Left(_: WireDecodeError.TypeMismatch) => ()
      case Left(other) => fail(s"expected TypeMismatch, got $other")
      case Right(_)    => fail("expected decode error")

  test("DStreamEnvelope.decode rejects wrong protocol"):
    val env = WireEnvelope(
      protocol = "actors", protocolVer = 1, format = WireFormat.Json,
      kind = "element-batch", correlationId = None, schemaId = None,
      flags = Set.empty, headers = Map.empty, payload = WireValue.Null,
    )
    DStreamEnvelope.decode(env) match
      case Left(err) => assert(err.message.contains("actors"))
      case Right(_)  => fail("expected protocol mismatch error")
