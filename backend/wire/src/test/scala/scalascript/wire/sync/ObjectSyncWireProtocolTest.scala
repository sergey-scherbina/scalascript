package scalascript.wire.sync

import org.scalatest.funsuite.AnyFunSuite
import scalascript.wire.{WireCodec, WireDecodeError, WireEnvelope, WireFormat, WireValue}
import scalascript.wire.json.JsonWireCodec
import scalascript.wire.msgpack.MsgPackWireCodec
import scalascript.wire.cbor.CborWireCodec
import ObjectSyncWireCodec.given

/** Round-trip and envelope-shape tests for the ObjectStore sync wire protocol.
 *
 *  Spec: specs/distributed-wire-protocol.md §Phase 6 */
class ObjectSyncWireProtocolTest extends AnyFunSuite:

  // ── Sample values ─────────────────────────────────────────────────────────

  private val change1 = SyncChange(
    key       = "user-123",
    version   = 42L,
    updatedAt = "2026-05-29T10:00:00Z",
    deleted   = false,
    value     = WireValue.Str("""{"name":"Alice","age":30}"""),
  )

  private val change2 = SyncChange(
    key       = "user-456",
    version   = 43L,
    updatedAt = "2026-05-29T10:01:00Z",
    deleted   = true,
    value     = WireValue.Null,
  )

  private val mutation1 = SyncMutation(
    key             = "user-789",
    value           = WireValue.Str("""{"name":"Bob"}"""),
    deleted         = false,
    expectedVersion = Some(10L),
  )

  private val mutation2 = SyncMutation(
    key             = "user-999",
    value           = WireValue.Null,
    deleted         = true,
    expectedVersion = None,
  )

  private val pullReq = ObjectSyncMsg.PullRequest(store = "users", since = 40L, limit = 100)

  private val pullResp = ObjectSyncMsg.PullResponse(
    store      = "users",
    changes    = Vector(change1, change2),
    nextCursor = 43L,
  )

  private val pushReq = ObjectSyncMsg.PushRequest(
    store     = "users",
    mutations = Vector(mutation1, mutation2),
  )

  private val pushRespClean = ObjectSyncMsg.PushResponse(
    store     = "users",
    applied   = Vector(SyncResult("user-789", 11L, false)),
    conflicts = Vector.empty,
  )

  private val pushRespConflict = ObjectSyncMsg.PushResponse(
    store     = "users",
    applied   = Vector.empty,
    conflicts = Vector(SyncConflict("user-789", 10L, 11L)),
  )

  private val allMessages: List[(String, ObjectSyncMsg)] = List(
    "PullRequest"               -> pullReq,
    "PullResponse(with changes)"-> pullResp,
    "PushRequest(mutations)"    -> pushReq,
    "PushResponse(clean)"       -> pushRespClean,
    "PushResponse(conflict)"    -> pushRespConflict,
  )

  // ── WireValue round-trips ─────────────────────────────────────────────────

  for (name, msg) <- allMessages do
    test(s"WireValue round-trip: $name"):
      val codec   = summon[WireCodec[ObjectSyncMsg]]
      val encoded = codec.encode(msg)
      codec.decode(encoded) match
        case Right(decoded) => assert(decoded == msg, s"decoded $decoded != $msg")
        case Left(err)      => fail(s"decode failed: ${err.message}")

  // ── SyncChange value types ────────────────────────────────────────────────

  for (name, change) <- List("non-deleted" -> change1, "deleted" -> change2) do
    test(s"SyncChange round-trip: $name"):
      val codec   = summon[WireCodec[SyncChange]]
      val encoded = codec.encode(change)
      codec.decode(encoded) match
        case Right(decoded) => assert(decoded == change)
        case Left(err)      => fail(err.message)

  // ── SyncMutation optional expectedVersion ─────────────────────────────────

  test("SyncMutation with expectedVersion round-trips"):
    val codec   = summon[WireCodec[SyncMutation]]
    val encoded = codec.encode(mutation1)
    codec.decode(encoded) match
      case Right(decoded) => assert(decoded.expectedVersion == Some(10L))
      case Left(err)      => fail(err.message)

  test("SyncMutation without expectedVersion round-trips"):
    val codec   = summon[WireCodec[SyncMutation]]
    val encoded = codec.encode(mutation2)
    codec.decode(encoded) match
      case Right(decoded) => assert(decoded.expectedVersion == None)
      case Left(err)      => fail(err.message)

  // ── JSON envelope round-trips ─────────────────────────────────────────────

  for (name, msg) <- allMessages do
    test(s"Envelope JSON round-trip: $name"):
      val env  = ObjectSyncEnvelope(msg, WireFormat.Json)
      val json = JsonWireCodec.encodeEnvelope(env)
      JsonWireCodec.decodeEnvelope(json) match
        case Right(env2) =>
          assert(env2.protocol == ObjectSyncEnvelope.Protocol)
          ObjectSyncEnvelope.decode(env2) match
            case Right(msg2) => assert(msg2 == msg)
            case Left(err)   => fail(err.message)
        case Left(err) => fail(s"JSON decodeEnvelope: $err")

  // ── MsgPack envelope round-trips ─────────────────────────────────────────

  for (name, msg) <- allMessages do
    test(s"Envelope MsgPack round-trip: $name"):
      val env   = ObjectSyncEnvelope(msg, WireFormat.MsgPack)
      val bytes = MsgPackWireCodec.encodeEnvelope(env)
      MsgPackWireCodec.decodeEnvelope(bytes) match
        case Right(env2) =>
          assert(env2.protocol == ObjectSyncEnvelope.Protocol)
          ObjectSyncEnvelope.decode(env2) match
            case Right(msg2) => assert(msg2 == msg)
            case Left(err)   => fail(err.message)
        case Left(err) => fail(s"MsgPack decodeEnvelope: $err")

  // ── CBOR envelope round-trips ─────────────────────────────────────────────

  for (name, msg) <- allMessages do
    test(s"Envelope CBOR round-trip: $name"):
      val env   = ObjectSyncEnvelope(msg, WireFormat.Cbor)
      val bytes = CborWireCodec.encodeEnvelope(env)
      CborWireCodec.decodeEnvelope(bytes) match
        case Right(env2) =>
          assert(env2.protocol == ObjectSyncEnvelope.Protocol)
          ObjectSyncEnvelope.decode(env2) match
            case Right(msg2) => assert(msg2 == msg)
            case Left(err)   => fail(err.message)
        case Left(err) => fail(s"CBOR decodeEnvelope: $err")

  // ── Envelope shape assertions ─────────────────────────────────────────────

  test("PullRequest envelope has correct shape"):
    val env = ObjectSyncEnvelope(pullReq, WireFormat.Json, correlationId = Some("req-1"))
    assert(env.protocol      == "object-sync")
    assert(env.protocolVer   == 1)
    assert(env.kind          == "pull-request")
    assert(env.correlationId == Some("req-1"))
    assert(env.schemaId      == Some("sync.ObjectSyncMsg:0"))

  test("PullResponse envelope has kind=pull-response"):
    assert(ObjectSyncEnvelope(pullResp, WireFormat.Json).kind == "pull-response")

  test("PushRequest envelope has kind=push-request"):
    assert(ObjectSyncEnvelope(pushReq, WireFormat.MsgPack).kind == "push-request")

  test("PushResponse envelope has kind=push-response"):
    assert(ObjectSyncEnvelope(pushRespClean, WireFormat.Cbor).kind == "push-response")

  // ── Error handling ────────────────────────────────────────────────────────

  test("decode unknown type returns MalformedInput"):
    val codec = summon[WireCodec[ObjectSyncMsg]]
    codec.decode(WireValue.Object("Unknown", Vector.empty)) match
      case Left(err) => assert(err.message.contains("Unknown"))
      case Right(_)  => fail("expected decode error")

  test("decode non-object returns TypeMismatch"):
    val codec = summon[WireCodec[ObjectSyncMsg]]
    codec.decode(WireValue.Bool(true)) match
      case Left(_: WireDecodeError.TypeMismatch) => ()
      case Left(other) => fail(s"expected TypeMismatch, got $other")
      case Right(_)    => fail("expected error")

  test("ObjectSyncEnvelope.decode rejects wrong protocol"):
    val env = WireEnvelope(
      protocol = "dstream", protocolVer = 1, format = WireFormat.Json,
      kind = "pull-request", correlationId = None, schemaId = None,
      flags = Set.empty, headers = Map.empty, payload = WireValue.Null,
    )
    ObjectSyncEnvelope.decode(env) match
      case Left(err) => assert(err.message.contains("dstream"))
      case Right(_)  => fail("expected protocol mismatch")
