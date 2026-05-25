package scalascript.typeddata

import org.scalatest.funsuite.AnyFunSuite

class RowCodecTest extends AnyFunSuite:

  final case class UserRow(id: String, age: Int, active: Boolean, score: Option[Double]) derives RowCodec

  test("derives RowCodec maps case classes to column maps"):
    val row = UserRow("u1", 42, active = true, score = Some(9.5))

    assert(RowCodec[UserRow].encode(row) == Map(
      "id" -> RowValue.Str("u1"),
      "age" -> RowValue.Num(42),
      "active" -> RowValue.Bool(true),
      "score" -> RowValue.Num(BigDecimal(9.5))
    ))

  test("derived RowCodec decodes rows and nullable option columns"):
    val decoded = RowCodec[UserRow].decode(Map(
      "id" -> RowValue.Str("u1"),
      "age" -> RowValue.Num(42),
      "active" -> RowValue.Bool(true),
      "score" -> RowValue.Null
    ))

    assert(decoded == Right(UserRow("u1", 42, active = true, score = None)))

  test("derived RowCodec reports column paths"):
    val error = RowCodec[UserRow].decode(Map(
      "id" -> RowValue.Str("u1"),
      "age" -> RowValue.Str("old"),
      "active" -> RowValue.Bool(true),
      "score" -> RowValue.Null
    )).left.toOption.get

    assert(error.render == "$.age: expected number column, got string")
