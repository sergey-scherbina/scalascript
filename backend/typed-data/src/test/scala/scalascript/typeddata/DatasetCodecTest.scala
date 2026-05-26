package scalascript.typeddata

import org.scalatest.funsuite.AnyFunSuite

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
