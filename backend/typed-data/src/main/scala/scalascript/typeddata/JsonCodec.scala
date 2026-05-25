package scalascript.typeddata

import scala.compiletime.{constValue, erasedValue, error, summonInline}
import scala.deriving.Mirror

trait JsonCodec[A] extends Codec[A, JsonValue]

object JsonCodec:
  def apply[A](using codec: JsonCodec[A]): JsonCodec[A] = codec

  inline given derived[A](using mirror: Mirror.Of[A]): JsonCodec[A] =
    inline mirror match
      case product: Mirror.ProductOf[A] => derivedProduct[A](using product)
      case _: Mirror.SumOf[A] => error("JsonCodec derivation currently supports case classes only")

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

  private inline def derivedProduct[A](using mirror: Mirror.ProductOf[A]): JsonCodec[A] =
    objectCodec[A](
      value =>
        val labels = elementLabels[mirror.MirroredElemLabels]
        val encoded = encodeElements[mirror.MirroredElemTypes](value.asInstanceOf[Product].productIterator)
        labels.zip(encoded).toMap
      ,
      fields =>
        decodeElements[mirror.MirroredElemTypes, mirror.MirroredElemLabels](fields)
          .map(values => mirror.fromProduct(Tuple.fromArray(values.toArray)))
    )

  private inline def elementLabels[Labels <: Tuple]: List[String] =
    inline erasedValue[Labels] match
      case _: EmptyTuple => Nil
      case _: (label *: labels) =>
        constValue[label].asInstanceOf[String] :: elementLabels[labels]

  private inline def encodeElements[Types <: Tuple](values: Iterator[Any]): List[JsonValue] =
    inline erasedValue[Types] match
      case _: EmptyTuple => Nil
      case _: (t *: ts) =>
        summonInline[JsonCodec[t]].encode(values.next().asInstanceOf[t]) :: encodeElements[ts](values)

  private inline def decodeElements[Types <: Tuple, Labels <: Tuple](fields: Map[String, JsonValue]): Either[DecodeError, List[Any]] =
    inline erasedValue[(Types, Labels)] match
      case _: (EmptyTuple, EmptyTuple) => Right(Nil)
      case _: ((t *: ts), (label *: labels)) =>
        val name = constValue[label].asInstanceOf[String]
        fields.get(name) match
          case Some(json) =>
            summonInline[JsonCodec[t]].decode(json).left.map(_.at(name)).flatMap { value =>
              decodeElements[ts, labels](fields).map(value :: _)
            }
          case None =>
            Left(DecodeError(s"missing field '$name'").at(name))
