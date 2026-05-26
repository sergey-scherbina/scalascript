package scalascript.typeddata

import org.scalatest.funsuite.AnyFunSuite

class SparkSchemaCodecTest extends AnyFunSuite:

  final case class SparkUser(
      @key id: Long,
      @fieldName("display_name") @aliases("name") name: String,
      active: Boolean,
      score: Option[Double],
      tags: List[String]
  ) derives SparkSchemaCodec

  test("derived SparkSchemaCodec uses shared field annotations and nullability"):
    assert(SparkSchemaCodec[SparkUser].schema == SparkSchema(Vector(
      SparkSchemaField("id", SparkSchemaType.LongType, nullable = false, key = true),
      SparkSchemaField("display_name", SparkSchemaType.StringType, nullable = false),
      SparkSchemaField("active", SparkSchemaType.BooleanType, nullable = false),
      SparkSchemaField("score", SparkSchemaType.DoubleType, nullable = true),
      SparkSchemaField(
        "tags",
        SparkSchemaType.ArrayType(SparkSchemaType.StringType, containsNull = false),
        nullable = false
      )
    )))
