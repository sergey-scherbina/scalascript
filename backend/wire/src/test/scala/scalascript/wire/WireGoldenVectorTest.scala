package scalascript.wire

import org.scalatest.funsuite.AnyFunSuite
import scalascript.wire.json.JsonWireCodec
import scalascript.wire.msgpack.MsgPackWireCodec
import scalascript.wire.cbor.CborWireCodec

/** Cross-format golden vectors — every WireValue case round-trips through
 *  JSON, MsgPack, and CBOR.
 *
 *  Spec: docs/specs/distributed-wire-protocol.md §Phase 1 Testing Strategy */
class WireGoldenVectorTest extends AnyFunSuite:

  private val vectors: List[(String, WireValue)] = List(
    "Null"            -> WireValue.Null,
    "Unit"            -> WireValue.Unit,
    "Bool(true)"      -> WireValue.Bool(true),
    "Bool(false)"     -> WireValue.Bool(false),
    "Int64(0)"        -> WireValue.Int64(0L),
    "Int64(42)"       -> WireValue.Int64(42L),
    "Int64(-1)"       -> WireValue.Int64(-1L),
    "Int64(Long.MaxValue)" -> WireValue.Int64(Long.MaxValue),
    "Int64(Long.MinValue)" -> WireValue.Int64(Long.MinValue),
    "Float64(0.0)"    -> WireValue.Float64(0.0),
    "Float64(3.14)"   -> WireValue.Float64(3.14),
    "Float64(-Inf)"   -> WireValue.Float64(Double.NegativeInfinity),
    "Str(empty)"      -> WireValue.Str(""),
    "Str(ascii)"      -> WireValue.Str("hello, world"),
    "Str(unicode)"    -> WireValue.Str("日本語テスト"),
    "Bytes(empty)"    -> WireValue.Bytes(Array.emptyByteArray),
    "Bytes([1,2,3])"  -> WireValue.Bytes(Array(1, 2, 3)),
    "Lst(empty)"      -> WireValue.Lst(Vector.empty),
    "Lst(ints)"       -> WireValue.Lst(Vector(WireValue.Int64(1), WireValue.Int64(2), WireValue.Int64(3))),
    "Lst(mixed)"      -> WireValue.Lst(Vector(WireValue.Str("a"), WireValue.Bool(true), WireValue.Null)),
    "Map(empty)"      -> WireValue.Map(Vector.empty),
    "Map(str->int)"   -> WireValue.Map(Vector(WireValue.Str("x") -> WireValue.Int64(1))),
    "Object(empty)"   -> WireValue.Object("Foo", Vector.empty),
    "Object(fields)"  -> WireValue.Object("Point", Vector("x" -> WireValue.Int64(1), "y" -> WireValue.Int64(2))),
    "Tuple(empty)"    -> WireValue.Tuple(Vector.empty),
    "Tuple(2)"        -> WireValue.Tuple(Vector(WireValue.Int64(1), WireValue.Str("a"))),
    "Enum(no value)"  -> WireValue.Enum("Color", "Red", None),
    "Enum(with value)"-> WireValue.Enum("Shape", "Circle", Some(WireValue.Float64(5.0))),
    "Pid"             -> WireValue.Pid("node-1", 42L),
    "Error(no detail)"-> WireValue.Error("NOT_FOUND", "resource not found", None),
    "Error(detail)"   -> WireValue.Error("DECODE_ERROR", "bad payload",
                           Some(WireValue.Str("expected int"))),
    "nested"          -> WireValue.Object("Outer", Vector(
                           "inner" -> WireValue.Object("Inner", Vector(
                             "val" -> WireValue.Int64(99)
                           )),
                         )),
  )

  // ── JSON round-trips ──────────────────────────────────────────────────────

  for (name, v) <- vectors do
    test(s"JSON round-trip: $name"):
      val json = JsonWireCodec.encodeToString(v)
      val decoded = JsonWireCodec.decodeFromString(json)
      decoded match
        case Left(err) => fail(s"JSON decode error: $err\n  input: $json")
        case Right(got) => assertWireValueEqual(v, got, s"JSON: $name")

  test("JSON encode Bytes uses base64"):
    val json = JsonWireCodec.encodeToString(WireValue.Bytes(Array(0, 1, 2)))
    assert(json.contains("$bytes"), s"Expected $$bytes in: $json")

  test("JSON encode Object includes type"):
    val json = JsonWireCodec.encodeToString(WireValue.Object("Foo", Vector("x" -> WireValue.Int64(1))))
    assert(json.contains("$type"), s"Expected $$type in: $json")
    assert(json.contains("Foo"), s"Expected type name in: $json")

  // ── MsgPack round-trips ───────────────────────────────────────────────────

  for (name, v) <- vectors do
    test(s"MsgPack round-trip: $name"):
      val bytes = MsgPackWireCodec.encodeToBytes(v)
      val decoded = MsgPackWireCodec.decodeFromBytes(bytes)
      decoded match
        case Left(err) => fail(s"MsgPack decode error: $err")
        case Right(got) => assertWireValueEqual(v, got, s"MsgPack: $name")

  test("MsgPack Bytes round-trip preserves binary"):
    val originalBytes = Array(0xFF.toByte, 0x00.toByte, 0x7F.toByte)
    val original = WireValue.Bytes(originalBytes)
    val bytes    = MsgPackWireCodec.encodeToBytes(original)
    val decoded  = MsgPackWireCodec.decodeFromBytes(bytes).getOrElse(fail("decode failed"))
    decoded match
      case WireValue.Bytes(bs) => assert(bs.toSeq == originalBytes.toSeq)
      case other               => fail(s"Expected Bytes, got $other")

  // ── CBOR round-trips ──────────────────────────────────────────────────────

  for (name, v) <- vectors do
    test(s"CBOR round-trip: $name"):
      val bytes = CborWireCodec.encodeToBytes(v)
      val decoded = CborWireCodec.decodeFromBytes(bytes)
      decoded match
        case Left(err) => fail(s"CBOR decode error: $err\n  bytes: ${bytes.map("%02x".format(_)).mkString(" ")}")
        case Right(got) => assertWireValueEqual(v, got, s"CBOR: $name")

  test("CBOR small ints are compact"):
    // Small ints 0-23 should encode as single byte
    val bytes = CborWireCodec.encodeToBytes(WireValue.Int64(0))
    assert(bytes.length == 1, s"Expected 1 byte for Int64(0), got ${bytes.length}")

    val bytes23 = CborWireCodec.encodeToBytes(WireValue.Int64(23))
    assert(bytes23.length == 1, s"Expected 1 byte for Int64(23), got ${bytes23.length}")

    val bytes24 = CborWireCodec.encodeToBytes(WireValue.Int64(24))
    assert(bytes24.length == 2, s"Expected 2 bytes for Int64(24), got ${bytes24.length}")

  // ── Cross-format consistency ──────────────────────────────────────────────

  for (name, v) <- vectors.filter { case (n, _) => !n.contains("Bytes") } do
    test(s"Cross-format consistency: $name"):
      val json    = JsonWireCodec.decodeFromString(JsonWireCodec.encodeToString(v))
      val msgpack = MsgPackWireCodec.decodeFromBytes(MsgPackWireCodec.encodeToBytes(v))
      val cbor    = CborWireCodec.decodeFromBytes(CborWireCodec.encodeToBytes(v))

      (json, msgpack, cbor) match
        case (Right(j), Right(m), Right(c)) =>
          assertWireValueEqual(j, m, s"JSON vs MsgPack: $name")
          assertWireValueEqual(j, c, s"JSON vs CBOR: $name")
        case _ =>
          fail(s"Decode failed for $name: json=$json msgpack=$msgpack cbor=$cbor")

  // ── WireCodec[A] given instances ─────────────────────────────────────────

  test("WireCodec[Long] round-trips"):
    val codec = summon[WireCodec[Long]]
    assert(codec.decode(codec.encode(42L)) == Right(42L))
    assert(codec.decode(codec.encode(-99L)) == Right(-99L))

  test("WireCodec[String] round-trips"):
    val codec = summon[WireCodec[String]]
    assert(codec.decode(codec.encode("hello")) == Right("hello"))
    assert(codec.decode(codec.encode("")) == Right(""))

  test("WireCodec[Option[Int]] round-trips"):
    val codec = summon[WireCodec[Option[Int]]]
    assert(codec.decode(codec.encode(None)) == Right(None))
    assert(codec.decode(codec.encode(Some(42))) == Right(Some(42)))

  test("WireCodec[List[String]] round-trips"):
    val codec = summon[WireCodec[List[String]]]
    assert(codec.decode(codec.encode(List("a", "b", "c"))) == Right(List("a", "b", "c")))
    assert(codec.decode(codec.encode(Nil)) == Right(Nil))

  test("WireCodec[WireValue] is identity"):
    val codec = summon[WireCodec[WireValue]]
    val v = WireValue.Int64(42)
    assert(codec.encode(v) == v)
    assert(codec.decode(v) == Right(v))

  // ── WireConfig fromFrontMatter ────────────────────────────────────────────

  test("WireConfig.fromFrontMatter parses enabled + format"):
    val cfg = WireConfig.fromFrontMatter(Map("enabled" -> true, "format" -> "msgpack"))
    assert(cfg.enabled)
    assert(cfg.format == WireFormat.MsgPack)

  test("WireConfig.fromFrontMatter defaults to json for unknown format"):
    val cfg = WireConfig.fromFrontMatter(Map("enabled" -> true, "format" -> "avro"))
    assert(cfg.format == WireFormat.Json)

  test("WireConfig.fromFrontMatter parses surfaces"):
    val cfg = WireConfig.fromFrontMatter(Map(
      "enabled" -> true,
      "format"  -> "cbor",
      "surfaces" -> Map("actors" -> true, "rpc" -> true),
    ))
    assert(cfg.surfaces.actors)
    assert(cfg.surfaces.rpc)
    assert(!cfg.surfaces.dataset)

  // ── WireEnvelope JSON round-trip ──────────────────────────────────────────

  test("WireEnvelope JSON round-trip"):
    val env = WireEnvelope.actors("json", "user_msg", WireValue.Str("hello"))
    val json = JsonWireCodec.encodeEnvelope(env)
    val decoded = JsonWireCodec.decodeEnvelope(json)
    decoded match
      case Left(err) => fail(s"Envelope decode error: $err")
      case Right(got) =>
        assert(got.protocol == "actors")
        assert(got.kind     == "user_msg")
        assert(got.payload  == WireValue.Str("hello"))

  // ── WireEnvelope MsgPack round-trip ──────────────────────────────────────

  test("WireEnvelope MsgPack round-trip"):
    val env = WireEnvelope.rpc("msgpack", "call", WireValue.Int64(99), Some("corr-1"))
    val bytes = MsgPackWireCodec.encodeEnvelope(env)
    val decoded = MsgPackWireCodec.decodeEnvelope(bytes)
    decoded match
      case Left(err) => fail(s"MsgPack envelope decode error: $err")
      case Right(got) =>
        assert(got.protocol      == "rpc")
        assert(got.correlationId == Some("corr-1"))
        assert(got.payload       == WireValue.Int64(99))

  // ── WireEnvelope CBOR round-trip ─────────────────────────────────────────

  test("WireEnvelope CBOR round-trip"):
    val env = WireEnvelope(
      protocol      = "dataset",
      protocolVer   = 1,
      format        = "cbor",
      kind          = "partition",
      correlationId = Some("chunk-7"),
      schemaId      = Some("hash:abc123"),
      flags         = Set("chunked"),
      headers       = Map("chunk-id" -> "xyz", "chunk-index" -> "0", "chunk-count" -> "12"),
      payload       = WireValue.Lst(Vector(WireValue.Int64(1), WireValue.Int64(2))),
    )
    val bytes = CborWireCodec.encodeEnvelope(env)
    val decoded = CborWireCodec.decodeEnvelope(bytes)
    decoded match
      case Left(err) => fail(s"CBOR envelope decode error: $err")
      case Right(got) =>
        assert(got.protocol    == "dataset")
        assert(got.flags       == Set("chunked"))
        assert(got.schemaId    == Some("hash:abc123"))

  // ── Helpers ───────────────────────────────────────────────────────────────

  private def assertWireValueEqual(expected: WireValue, got: WireValue, ctx: String): Unit =
    (expected, got) match
      case (WireValue.Bytes(a), WireValue.Bytes(b)) =>
        assert(a.toSeq == b.toSeq, s"$ctx: Bytes differ")
      case (WireValue.Float64(a), WireValue.Float64(b)) =>
        if a.isNaN && b.isNaN then () // NaN != NaN by definition
        else if a.isInfinite && b.isInfinite && a.sign == b.sign then ()
        else assert(a == b, s"$ctx: Float64 differ: $a vs $b")
      case (WireValue.Lst(as), WireValue.Lst(bs)) =>
        assert(as.length == bs.length, s"$ctx: Lst length differ")
        as.zip(bs).foreach { (a, b) => assertWireValueEqual(a, b, ctx) }
      case (WireValue.Map(as), WireValue.Map(bs)) =>
        assert(as.length == bs.length, s"$ctx: Map length differ")
        as.zip(bs).foreach { case ((k1, v1), (k2, v2)) =>
          assertWireValueEqual(k1, k2, s"$ctx map key")
          assertWireValueEqual(v1, v2, s"$ctx map val")
        }
      case (WireValue.Object(t1, f1), WireValue.Object(t2, f2)) =>
        assert(t1 == t2, s"$ctx: Object type name differ: '$t1' vs '$t2'")
        assert(f1.length == f2.length, s"$ctx: Object field count differ")
        f1.zip(f2).foreach { case ((n1, v1), (n2, v2)) =>
          assert(n1 == n2, s"$ctx: Object field name differ: '$n1' vs '$n2'")
          assertWireValueEqual(v1, v2, s"$ctx field '$n1'")
        }
      case (WireValue.Tuple(as), WireValue.Tuple(bs)) =>
        assert(as.length == bs.length, s"$ctx: Tuple length differ")
        as.zip(bs).foreach { (a, b) => assertWireValueEqual(a, b, ctx) }
      case (WireValue.Enum(t1, c1, v1), WireValue.Enum(t2, c2, v2)) =>
        assert(t1 == t2 && c1 == c2, s"$ctx: Enum name differ")
        (v1, v2) match
          case (None, None)       => ()
          case (Some(a), Some(b)) => assertWireValueEqual(a, b, ctx)
          case _                  => fail(s"$ctx: Enum value presence differs")
      case (WireValue.Error(c1, m1, d1), WireValue.Error(c2, m2, d2)) =>
        assert(c1 == c2 && m1 == m2, s"$ctx: Error code/message differ")
        (d1, d2) match
          case (None, None) | (Some(_), Some(_)) => ()
          case _ => fail(s"$ctx: Error details presence differs")
      case _ =>
        assert(expected == got, s"$ctx: WireValue differ: $expected vs $got")
