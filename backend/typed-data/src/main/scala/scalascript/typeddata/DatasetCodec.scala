package scalascript.typeddata

/** Element-level codec for Dataset / MapReduce data movement.
 *
 *  Dataset operations keep their own execution model.  This typeclass only
 *  defines the stable serialized element representation used when moving typed
 *  values into or out of local/distributed Dataset pipelines.
 */
trait DatasetCodec[A] extends Codec[A, JsonValue]

object DatasetCodec:
  def apply[A](using codec: DatasetCodec[A]): DatasetCodec[A] = codec

  def instance[A](
      encodeValue: A => JsonValue,
      decodeValue: JsonValue => Either[DecodeError, A]
  ): DatasetCodec[A] =
    new DatasetCodec[A]:
      def encode(value: A): JsonValue = encodeValue(value)
      def decode(repr: JsonValue): Either[DecodeError, A] = decodeValue(repr)

  def fromJsonCodec[A](using jsonCodec: JsonCodec[A]): DatasetCodec[A] =
    instance(jsonCodec.encode, jsonCodec.decode)

  given [A](using jsonCodec: JsonCodec[A]): DatasetCodec[A] =
    fromJsonCodec[A]

  def encodeAll[A](values: Iterable[A])(using codec: DatasetCodec[A]): Vector[JsonValue] =
    values.iterator.map(codec.encode).toVector

  def decodeAll[A](values: Iterable[JsonValue])(using codec: DatasetCodec[A]): Either[DecodeError, Vector[A]] =
    values.iterator.zipWithIndex.foldLeft[Either[DecodeError, Vector[A]]](Right(Vector.empty)) {
      case (Right(acc), (value, index)) =>
        codec.decode(value).left.map(_.at(index.toString)).map(acc :+ _)
      case (left @ Left(_), _) => left
    }
