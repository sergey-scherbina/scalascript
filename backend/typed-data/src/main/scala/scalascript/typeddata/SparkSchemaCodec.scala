package scalascript.typeddata

import scala.compiletime.error
import scala.deriving.Mirror

enum SparkSchemaType:
  case StringType
  case BooleanType
  case ByteType
  case ShortType
  case IntegerType
  case LongType
  case FloatType
  case DoubleType
  case DecimalType
  case ArrayType(element: SparkSchemaType, containsNull: Boolean)
  case MapType(key: SparkSchemaType, value: SparkSchemaType, valueContainsNull: Boolean)
  case StructType(name: String)

final case class SparkSchemaField(
    name:     String,
    dataType: SparkSchemaType,
    nullable: Boolean,
    key:      Boolean = false,
    scalaName: String = ""
):
  def scalaFieldName: String =
    if scalaName.isEmpty then name else scalaName

final case class SparkSchema(fields: Vector[SparkSchemaField])

trait SparkSchemaCodec[A]:
  def schema: SparkSchema

object SparkSchemaCodec:
  def apply[A](using codec: SparkSchemaCodec[A]): SparkSchemaCodec[A] = codec

  def instance[A](schemaValue: SparkSchema): SparkSchemaCodec[A] =
    new SparkSchemaCodec[A]:
      def schema: SparkSchema = schemaValue

  inline given derived[A](using mirror: Mirror.Of[A]): SparkSchemaCodec[A] =
    inline mirror match
      case product: Mirror.ProductOf[A] => derivedProduct[A](using product)
      case _: Mirror.SumOf[A] => error("SparkSchemaCodec derivation currently supports case classes only")

  private inline def derivedProduct[A](using mirror: Mirror.ProductOf[A]): SparkSchemaCodec[A] =
    ${ DerivedSchemaMacros.sparkSchemaProduct[A] }
