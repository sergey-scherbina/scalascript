package scalascript.typeddata

import org.scalatest.funsuite.AnyFunSuite
import scalascript.wire.WireFormat

class DatasetCodecTest extends AnyFunSuite:

  final case class Event(id: String, amount: Int, tags: List[String]) derives JsonCodec

  test("DatasetCodec derives from JsonCodec for dataset element movement"):
    val event = Event("e1", 42, List("typed", "dataset"))
    val encoded = DatasetCodec[Event].encode(event)

    assert(encoded == JsonValue.obj(
      "id" -> JsonValue.Str("e1"),
      "amount" -> JsonValue.Num(42),
      "tags" -> JsonValue.arr(JsonValue.Str("typed"), JsonValue.Str("dataset"))
    ))
    assert(DatasetCodec[Event].decode(encoded) == Right(event))

  test("DatasetCodec encodes and decodes batches with indexed decode paths"):
    val values = Vector(Event("e1", 1, Nil), Event("e2", 2, List("ok")))
    val encoded = DatasetCodec.encodeAll(values)

    assert(DatasetCodec.decodeAll[Event](encoded) == Right(values))

    val bad = encoded.updated(1, JsonValue.obj(
      "id" -> JsonValue.Str("e2"),
      "amount" -> JsonValue.Str("wrong"),
      "tags" -> JsonValue.arr()
    ))
    assert(DatasetCodec.decodeAll[Event](bad).left.toOption.exists(_.render == "$.1.amount: expected number, got string"))

  test("DatasetCodec encodes worker partitions with stable partition ids"):
    val partitions = DatasetCodec.encodePartitions(Vector(
      Vector(Event("e1", 1, Nil)),
      Vector(Event("e2", 2, List("ok")), Event("e3", 3, Nil))
    ))

    assert(partitions.map(_.partitionId) == Vector(0, 1))
    assert(DatasetCodec.decodePartitions[Event](partitions) == Right(Vector(
      DatasetPartition(0, Vector(Event("e1", 1, Nil))),
      DatasetPartition(1, Vector(Event("e2", 2, List("ok")), Event("e3", 3, Nil)))
    )))

    val bad = partitions.updated(
      1,
      partitions(1).copy(values = partitions(1).values.updated(0, JsonValue.obj(
        "id" -> JsonValue.Str("e2"),
        "amount" -> JsonValue.Str("wrong"),
        "tags" -> JsonValue.arr()
      )))
    )
    assert(DatasetCodec.decodePartitions[Event](bad).left.toOption.exists(
      _.render == "$.partition[1].0.amount: expected number, got string"
    ))

  test("DatasetWire round-trips partitions through JSON, MsgPack, and CBOR envelopes"):
    val partition = DatasetCodec.encodePartition(7, Vector(
      Event("e1", 1, List("json")),
      Event("e2", 2, List("wire", "binary"))
    ))

    List(WireFormat.Json, WireFormat.MsgPack, WireFormat.Cbor).foreach { format =>
      val bytes = DatasetWire.encodePartition(partition, format).toOption.get
      val decoded = DatasetWire.decodePartition(bytes, format).toOption.get
      assert(decoded == partition, s"format=$format")
    }

  test("DatasetWire preserves JSON numbers exactly across binary profiles"):
    val partition = DatasetWirePartition(1, Vector(JsonValue.obj(
      "big" -> JsonValue.Num(BigDecimal("12345678901234567890.123456789"))
    )))

    val bytes = DatasetWire.encodePartition(partition, WireFormat.Cbor).toOption.get
    val decoded = DatasetWire.decodePartition(bytes, WireFormat.Cbor).toOption.get

    assert(decoded == partition)

  test("DatasetWire chunks large partitions and reassembles them in order"):
    val values = (1 to 12).toVector.map(i => JsonValue.obj(
      "id" -> JsonValue.Str(s"e$i"),
      "payload" -> JsonValue.Str("x" * 80)
    ))
    val partition = DatasetWirePartition(42, values)

    val full = DatasetWire.encodePartition(partition, WireFormat.MsgPack, maxFrameBytes = 256)
    assert(full.left.toOption.exists(_.message.contains("exceeds limit")))

    val chunks = DatasetWire.encodePartitionChunks(partition, WireFormat.MsgPack, maxFrameBytes = 512, chunkId = "chunk-a").toOption.get
    assert(chunks.size > 1)
    assert(chunks.forall(_.length <= 512))

    val decoded = DatasetWire.decodePartitionChunks(chunks.reverse, WireFormat.MsgPack, maxFrameBytes = 512).toOption.get
    assert(decoded == partition)
