package scalascript.typeddata

import scala.compiletime.{constValue, erasedValue, error, summonInline}
import scala.deriving.Mirror

trait RowValueCodec[A]:
  def encode(value: A): RowValue
  def decode(value: RowValue): Either[DecodeError, A]

object RowValueCodec:
  def apply[A](using codec: RowValueCodec[A]): RowValueCodec[A] = codec

  def instance[A](encodeValue: A => RowValue, decodeValue: RowValue => Either[DecodeError, A]): RowValueCodec[A] =
    new RowValueCodec[A]:
      def encode(value: A): RowValue = encodeValue(value)
      def decode(value: RowValue): Either[DecodeError, A] = decodeValue(value)

  given RowValueCodec[String] = instance(
    RowValue.Str.apply,
    {
      case RowValue.Str(value) => Right(value)
      case other => Left(DecodeError(s"expected string column, got ${RowValue.kind(other)}"))
    }
  )

  given RowValueCodec[Boolean] = instance(
    RowValue.Bool.apply,
    {
      case RowValue.Bool(value) => Right(value)
      case other => Left(DecodeError(s"expected boolean column, got ${RowValue.kind(other)}"))
    }
  )

  given RowValueCodec[Int] = instance(
    value => RowValue.Num(BigDecimal(value)),
    {
      case RowValue.Num(value) if value.isValidInt => Right(value.toInt)
      case RowValue.Num(value) => Left(DecodeError(s"expected int column, got $value"))
      case other => Left(DecodeError(s"expected number column, got ${RowValue.kind(other)}"))
    }
  )

  given RowValueCodec[Long] = instance(
    value => RowValue.Num(BigDecimal(value)),
    {
      case RowValue.Num(value) if value.isValidLong => Right(value.toLong)
      case RowValue.Num(value) => Left(DecodeError(s"expected long column, got $value"))
      case other => Left(DecodeError(s"expected number column, got ${RowValue.kind(other)}"))
    }
  )

  given RowValueCodec[Double] = instance(
    value => RowValue.Num(BigDecimal(value)),
    {
      case RowValue.Num(value) => Right(value.toDouble)
      case other => Left(DecodeError(s"expected number column, got ${RowValue.kind(other)}"))
    }
  )

  given [A](using itemCodec: RowValueCodec[A]): RowValueCodec[Option[A]] = instance(
    {
      case Some(value) => itemCodec.encode(value)
      case None => RowValue.Null
    },
    {
      case RowValue.Null => Right(None)
      case other => itemCodec.decode(other).map(Some(_))
    }
  )

trait RowCodec[A] extends Codec[A, Map[String, RowValue]]

object RowCodec:
  def apply[A](using codec: RowCodec[A]): RowCodec[A] = codec

  inline given derived[A](using mirror: Mirror.Of[A]): RowCodec[A] =
    inline mirror match
      case product: Mirror.ProductOf[A] => derivedProduct[A](using product)
      case _: Mirror.SumOf[A] => error("RowCodec derivation currently supports case classes only")

  def instance[A](
      encodeRow: A => Map[String, RowValue],
      decodeRow: Map[String, RowValue] => Either[DecodeError, A]
  ): RowCodec[A] =
    new RowCodec[A]:
      def encode(value: A): Map[String, RowValue] = encodeRow(value)
      def decode(repr: Map[String, RowValue]): Either[DecodeError, A] = decodeRow(repr)

  def field[A](row: Map[String, RowValue], name: String)(using codec: RowValueCodec[A]): Either[DecodeError, A] =
    row.get(name) match
      case Some(value) => codec.decode(value).left.map(_.at(name))
      case None => Left(DecodeError(s"missing column '$name'").at(name))

  private inline def derivedProduct[A](using mirror: Mirror.ProductOf[A]): RowCodec[A] =
    instance[A](
      value =>
        val labels = elementLabels[mirror.MirroredElemLabels]
        val encoded = encodeElements[mirror.MirroredElemTypes](value.asInstanceOf[Product].productIterator)
        labels.zip(encoded).toMap
      ,
      row =>
        decodeElements[mirror.MirroredElemTypes, mirror.MirroredElemLabels](row)
          .map(values => mirror.fromProduct(Tuple.fromArray(values.toArray)))
    )

  private inline def elementLabels[Labels <: Tuple]: List[String] =
    inline erasedValue[Labels] match
      case _: EmptyTuple => Nil
      case _: (label *: labels) => constValue[label].asInstanceOf[String] :: elementLabels[labels]

  private inline def encodeElements[Types <: Tuple](values: Iterator[Any]): List[RowValue] =
    inline erasedValue[Types] match
      case _: EmptyTuple => Nil
      case _: (t *: ts) =>
        summonInline[RowValueCodec[t]].encode(values.next().asInstanceOf[t]) :: encodeElements[ts](values)

  private inline def decodeElements[Types <: Tuple, Labels <: Tuple](row: Map[String, RowValue]): Either[DecodeError, List[Any]] =
    inline erasedValue[(Types, Labels)] match
      case _: (EmptyTuple, EmptyTuple) => Right(Nil)
      case _: ((t *: ts), (label *: labels)) =>
        val name = constValue[label].asInstanceOf[String]
        field[t](row, name)(using summonInline[RowValueCodec[t]]).flatMap { value =>
          decodeElements[ts, labels](row).map(value :: _)
        }
