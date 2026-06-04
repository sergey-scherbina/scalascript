package scalascript.wire.compat

import org.scalatest.funsuite.AnyFunSuite
import scalascript.wire.{WireCodec, WireDecodeError, WireEnvelope, WireFormat, WireValue}
import scalascript.wire.json.JsonWireCodec
import scalascript.wire.msgpack.MsgPackWireCodec

/** Tests for schema-id hashing, evolution rules, compatibility guard,
 *  and golden vectors.
 *
 *  Spec: specs/distributed-wire-protocol.md §Phase 8 */
class WireCompatibilityTest extends AnyFunSuite:

  // ── WireSchemaId ──────────────────────────────────────────────────────────

  test("WireSchemaId.hash produces a sha256: prefixed string"):
    val id = WireSchemaId.hash("object:Point{x:int64,y:int64}")
    assert(id.startsWith("sha256:"))
    assert(id.length == "sha256:".length + 16)

  test("WireSchemaId.hash is deterministic"):
    val def1 = "object:User{name:string,age:int64}"
    assert(WireSchemaId.hash(def1) == WireSchemaId.hash(def1))

  test("WireSchemaId.hash differs for different definitions"):
    val id1 = WireSchemaId.hash("object:A{x:int64}")
    val id2 = WireSchemaId.hash("object:A{y:int64}")
    assert(id1 != id2)

  test("WireSchemaId.verify succeeds for matching definition"):
    val definition = "object:Event{type:string,ts:int64}"
    val id         = WireSchemaId.hash(definition)
    assert(WireSchemaId.verify(definition, id))

  test("WireSchemaId.verify fails for tampered definition"):
    val id = WireSchemaId.hash("object:Event{type:string,ts:int64}")
    assert(!WireSchemaId.verify("object:Event{type:string,ts:int64,extra:bool}", id))

  // ── WireSchemaRegistry ────────────────────────────────────────────────────

  test("registry.check returns Identical for equal ids"):
    val reg = WireSchemaRegistry()
    val id  = "sync.PullRequest:0"
    reg.check(id, id) match
      case CompatibilityResult.Identical => ()
      case other => fail(s"expected Identical, got $other")

  test("registry.check returns Unknown for unregistered pair"):
    val reg = WireSchemaRegistry()
    reg.check("schema:v2", "schema:v1") match
      case CompatibilityResult.Unknown(_, _) => ()
      case other => fail(s"expected Unknown, got $other")

  test("registry.check returns Compatible after registerEvolution"):
    val reg = WireSchemaRegistry()
    reg.registerEvolution("schema:v1", "schema:v2", "added optional field 'meta'")
    reg.check("schema:v2", "schema:v1") match
      case CompatibilityResult.Compatible(reason) =>
        assert(reason.contains("optional field"))
      case other => fail(s"expected Compatible, got $other")

  test("evolution registration is directional (old→new, not new→old)"):
    val reg = WireSchemaRegistry()
    reg.registerEvolution("schema:v1", "schema:v2", "additive")
    // v2 sender → v1 receiver is NOT registered
    reg.check("schema:v1", "schema:v2") match
      case CompatibilityResult.Unknown(_, _) => ()
      case other => fail(s"expected Unknown for reverse direction, got $other")

  test("registry.size tracks registered pairs"):
    val reg = WireSchemaRegistry()
    assert(reg.size == 0)
    reg.registerEvolution("id:a", "id:b", "reason 1")
    reg.registerEvolution("id:b", "id:c", "reason 2")
    assert(reg.size == 2)

  // ── WireCompatibilityGuard ────────────────────────────────────────────────

  private def makeEnv(schemaId: Option[String]): WireEnvelope =
    WireEnvelope(
      protocol = "rpc", protocolVer = 1, format = WireFormat.Json,
      kind = "request", correlationId = None, schemaId = schemaId,
      flags = Set.empty, headers = Map.empty, payload = WireValue.Null,
    )

  test("guard passes when envelope and local schemaId are identical"):
    val env = makeEnv(Some("rpc.Request:0"))
    WireCompatibilityGuard.check(env, "rpc.Request:0") match
      case Right(CompatibilityResult.Identical) => ()
      case other => fail(s"expected Identical, got $other")

  test("guard passes unknown by default (allowUnknown=true)"):
    val env = makeEnv(Some("rpc.Request:99"))
    WireCompatibilityGuard.check(env, "rpc.Request:0") match
      case Right(CompatibilityResult.Unknown(_, _)) => ()
      case other => fail(s"expected Unknown (allowed), got $other")

  test("guard rejects unknown when allowUnknown=false"):
    val env = makeEnv(Some("rpc.Request:99"))
    WireCompatibilityGuard.check(env, "rpc.Request:0", allowUnknown = false) match
      case Left(_: WireDecodeError.SchemaIdMismatch) => ()
      case other => fail(s"expected SchemaIdMismatch, got $other")

  test("guard passes when no schemaId and requireSchemaId=false"):
    val env = makeEnv(None)
    WireCompatibilityGuard.check(env, "rpc.Request:0") match
      case Right(_) => ()
      case Left(err) => fail(err.message)

  test("guard rejects missing schemaId when requireSchemaId=true"):
    val env = makeEnv(None)
    WireCompatibilityGuard.check(env, "rpc.Request:0", requireSchemaId = true) match
      case Left(err) => assert(err.message.contains("schemaId"))
      case Right(_)  => fail("expected error for missing schemaId")

  test("guard passes Compatible evolution"):
    val reg = WireSchemaRegistry()
    reg.registerEvolution("rpc.Request:0", "rpc.Request:1", "added 'timeout' optional field")
    val env = makeEnv(Some("rpc.Request:0"))
    WireCompatibilityGuard.check(env, "rpc.Request:1", registry = reg) match
      case Right(CompatibilityResult.Compatible(_)) => ()
      case other => fail(s"expected Compatible, got $other")

  // ── Golden vector registry ────────────────────────────────────────────────

  test("WireGoldenVectorRegistry stores and retrieves vectors"):
    val reg     = WireGoldenVectorRegistry()
    val payload = WireValue.Object("Point", Vector("x" -> WireValue.Int64(3), "y" -> WireValue.Int64(4)))
    val bytes   = MsgPackWireCodec.encodeToBytes(payload)
    reg.register("Point(3,4)", WireFormat.MsgPack, bytes, "geo.Point:0")
    assert(reg.size == 1)
    val vec = reg.all.head
    assert(vec.name   == "Point(3,4)")
    assert(vec.format == WireFormat.MsgPack)

  test("Golden MsgPack vector decodes back to original value"):
    val payload  = WireValue.Lst(Vector(WireValue.Int64(1), WireValue.Int64(2), WireValue.Int64(3)))
    val bytes    = MsgPackWireCodec.encodeToBytes(payload)
    val reg      = WireGoldenVectorRegistry()
    reg.register("int list", WireFormat.MsgPack, bytes, "list(int64):0")
    val vec      = reg.all.head
    val decoded  = MsgPackWireCodec.decodeFromBytes(reg.decode(vec))
    decoded match
      case Right(v) => assert(v == payload)
      case Left(e)  => fail(s"decode failed: $e")

  test("Golden JSON vector decodes back to original envelope"):
    val env   = WireEnvelope(
      protocol = "rpc", protocolVer = 1, format = WireFormat.Json,
      kind = "request", correlationId = Some("golden-1"), schemaId = Some("rpc.Req:0"),
      flags = Set.empty, headers = Map.empty,
      payload = WireValue.Str("hello from v1"),
    )
    val json  = JsonWireCodec.encodeEnvelope(env)
    val reg   = WireGoldenVectorRegistry()
    reg.register("rpc-request-v1", WireFormat.Json, json.getBytes("UTF-8"), "rpc.Req:0")
    val vec   = reg.all.head
    val bytes = reg.decode(vec)
    JsonWireCodec.decodeEnvelope(new String(bytes, "UTF-8")) match
      case Right(decoded) =>
        assert(decoded.protocol      == "rpc")
        assert(decoded.kind          == "request")
        assert(decoded.correlationId == Some("golden-1"))
      case Left(err) => fail(s"decode failed: $err")

  test("byFormat filters vectors correctly"):
    val reg = WireGoldenVectorRegistry()
    reg.register(WireGoldenVector("a", WireFormat.Json,    "anA==", "id:0"))
    reg.register(WireGoldenVector("b", WireFormat.MsgPack, "anA==", "id:0"))
    reg.register(WireGoldenVector("c", WireFormat.Json,    "anA==", "id:0"))
    assert(reg.byFormat(WireFormat.Json).length    == 2)
    assert(reg.byFormat(WireFormat.MsgPack).length == 1)
    assert(reg.byFormat(WireFormat.Cbor).length    == 0)

  // ── Additive evolution: unknown fields are silently ignored ────────────────

  test("WireCodec.decode ignores extra fields in Object (forward-compat)"):
    // Simulate a v2 sender adding a 'meta' field unknown to the v1 receiver.
    // v1 decoder only looks for 'key' and 'value'.
    val v2Payload = WireValue.Object("SyncResult", Vector(
      "key"     -> WireValue.Str("user-1"),
      "version" -> WireValue.Int64(5L),
      "deleted" -> WireValue.Bool(false),
      "meta"    -> WireValue.Str("added in v2 — should be ignored"),
    ))
    import scalascript.wire.sync.ObjectSyncWireCodec.given
    summon[WireCodec[scalascript.wire.sync.SyncResult]].decode(v2Payload) match
      case Right(r) =>
        assert(r.key     == "user-1")
        assert(r.version == 5L)
      case Left(err) => fail(s"extra-field decode failed: ${err.message}")
