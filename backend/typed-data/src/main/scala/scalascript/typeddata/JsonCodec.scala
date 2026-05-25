package scalascript.typeddata

trait JsonCodec[A] extends Codec[A, JsonValue]

object JsonCodec:
  def apply[A](using codec: JsonCodec[A]): JsonCodec[A] = codec

  def instance[A](
      encodeValue: A => JsonValue,
      decodeValue: JsonValue => Either[DecodeError, A]
  ): JsonCodec[A] =
    new JsonCodec[A]:
      def encode(value: A): JsonValue = encodeValue(value)
      def decode(repr: JsonValue): Either[DecodeError, A] = decodeValue(repr)

  given JsonCodec[String] = instance(
    JsonValue.Str.apply,
    {
      case JsonValue.Str(value) => Right(value)
      case other => Left(DecodeError(s"expected string, got ${JsonValue.kind(other)}"))
    }
  )

  given JsonCodec[Boolean] = instance(
    JsonValue.Bool.apply,
    {
      case JsonValue.Bool(value) => Right(value)
      case other => Left(DecodeError(s"expected boolean, got ${JsonValue.kind(other)}"))
    }
  )

  given JsonCodec[Int] = instance(
    value => JsonValue.Num(BigDecimal(value)),
    {
      case JsonValue.Num(value) if value.isValidInt => Right(value.toInt)
      case JsonValue.Num(value) => Left(DecodeError(s"expected int, got $value"))
      case other => Left(DecodeError(s"expected number, got ${JsonValue.kind(other)}"))
    }
  )

  given JsonCodec[Long] = instance(
    value => JsonValue.Num(BigDecimal(value)),
    {
      case JsonValue.Num(value) if value.isValidLong => Right(value.toLong)
      case JsonValue.Num(value) => Left(DecodeError(s"expected long, got $value"))
      case other => Left(DecodeError(s"expected number, got ${JsonValue.kind(other)}"))
    }
  )

  given JsonCodec[Double] = instance(
    value => JsonValue.Num(BigDecimal(value)),
    {
      case JsonValue.Num(value) => Right(value.toDouble)
      case other => Left(DecodeError(s"expected number, got ${JsonValue.kind(other)}"))
    }
  )

  given [A](using itemCodec: JsonCodec[A]): JsonCodec[List[A]] = instance(
    values => JsonValue.Arr(values.map(itemCodec.encode).toVector),
    {
      case JsonValue.Arr(values) =>
        values.zipWithIndex.foldRight[Either[DecodeError, List[A]]](Right(Nil)) {
          case ((value, index), Right(acc)) =>
            itemCodec.decode(value).left.map(_.at(index.toString)).map(_ :: acc)
          case (_, left @ Left(_)) => left
        }
      case other => Left(DecodeError(s"expected array, got ${JsonValue.kind(other)}"))
    }
  )

  given [A](using itemCodec: JsonCodec[A]): JsonCodec[Option[A]] = instance(
    {
      case Some(value) => itemCodec.encode(value)
      case None => JsonValue.Null
    },
    {
      case JsonValue.Null => Right(None)
      case other => itemCodec.decode(other).map(Some(_))
    }
  )

  def field[A](fields: Map[String, JsonValue], name: String)(using codec: JsonCodec[A]): Either[DecodeError, A] =
    fields.get(name) match
      case Some(value) => codec.decode(value).left.map(_.at(name))
      case None => Left(DecodeError(s"missing field '$name'").at(name))

  def objectCodec[A](
      encodeFields: A => Map[String, JsonValue],
      decodeFields: Map[String, JsonValue] => Either[DecodeError, A]
  ): JsonCodec[A] =
    instance(
      value => JsonValue.Obj(encodeFields(value)),
      {
        case JsonValue.Obj(fields) => decodeFields(fields)
        case other => Left(DecodeError(s"expected object, got ${JsonValue.kind(other)}"))
      }
    )
