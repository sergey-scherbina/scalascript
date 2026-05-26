package scalascript.typeddata

import scala.deriving.Mirror

private[typeddata] final case class JsonProductField(
    name:    String,
    aliases: List[String],
    key:     Boolean,
    encode:  Any => JsonValue,
    decode:  Map[String, JsonValue] => Either[DecodeError, Any]
):
  def names: List[String] = name :: aliases

private[typeddata] final case class RowProductField(
    name:    String,
    aliases: List[String],
    key:     Boolean,
    encode:  Any => RowValue,
    decode:  Map[String, RowValue] => Either[DecodeError, Any]
):
  def names: List[String] = name :: aliases

private[typeddata] object DerivedProductCodecs:
  def jsonProductCodec[A](
      mirror: Mirror.ProductOf[A],
      fields: Vector[JsonProductField],
      rejectUnknown: Boolean
  ): JsonCodec[A] =
    JsonCodec.instance(
      value =>
        val values = value.asInstanceOf[Product].productIterator
        JsonValue.Obj(fields.iterator.zip(values).map { (field, raw) =>
          field.name -> field.encode(raw)
        }.toMap)
      ,
      {
        case JsonValue.Obj(rawFields) =>
          rejectUnknownJson(rawFields, fields, rejectUnknown).flatMap { _ =>
            decodeProduct(rawFields, fields.map(_.decode), mirror)
          }
        case other => Left(DecodeError(s"expected object, got ${JsonValue.kind(other)}"))
      }
    )

  def rowProductCodec[A](
      mirror: Mirror.ProductOf[A],
      fields: Vector[RowProductField],
      rejectUnknown: Boolean
  ): RowCodec[A] =
    RowCodec.instance(
      value =>
        val values = value.asInstanceOf[Product].productIterator
        fields.iterator.zip(values).map { (field, raw) =>
          field.name -> field.encode(raw)
        }.toMap
      ,
      row =>
        rejectUnknownRow(row, fields, rejectUnknown).flatMap { _ =>
          decodeProduct(row, fields.map(_.decode), mirror)
        }
    )

  def objectProductCodec[A](
      mirror: Mirror.ProductOf[A],
      fields: Vector[JsonProductField],
      rejectUnknown: Boolean
  ): ObjectCodec[A] =
    ObjectCodec.instance(
      value =>
        val values = value.asInstanceOf[Product].productIterator
        ObjectValue(fields.iterator.zip(values).map { (field, raw) =>
          field.name -> field.encode(raw)
        }.toMap)
      ,
      objectValue =>
        rejectUnknownJson(objectValue.fields, fields, rejectUnknown).flatMap { _ =>
          decodeProduct(objectValue.fields, fields.map(_.decode), mirror)
        },
      fields.find(_.key).map(_.name)
    )

  private def decodeProduct[A, Repr](
      repr: Repr,
      decoders: Vector[Repr => Either[DecodeError, Any]],
      mirror: Mirror.ProductOf[A]
  ): Either[DecodeError, A] =
    val values = Array.ofDim[Any](decoders.size)
    var index = 0
    while index < decoders.size do
      decoders(index)(repr) match
        case Right(value) => values(index) = value
        case Left(error) => return Left(error)
      index += 1
    Right(mirror.fromProduct(Tuple.fromArray(values)))

  private def rejectUnknownJson(
      rawFields: Map[String, JsonValue],
      fields: Vector[JsonProductField],
      rejectUnknown: Boolean
  ): Either[DecodeError, Unit] =
    if !rejectUnknown then Right(())
    else
      val known = fields.iterator.flatMap(_.names).toSet
      rawFields.keys.find(!known.contains(_)) match
        case Some(name) => Left(DecodeError(s"unknown field '$name'").at(name))
        case None => Right(())

  private def rejectUnknownRow(
      row: Map[String, RowValue],
      fields: Vector[RowProductField],
      rejectUnknown: Boolean
  ): Either[DecodeError, Unit] =
    if !rejectUnknown then Right(())
    else
      val known = fields.iterator.flatMap(_.names).map(_.toLowerCase(java.util.Locale.ROOT)).toSet
      row.keys.find(name => !known.contains(name.toLowerCase(java.util.Locale.ROOT))) match
        case Some(name) => Left(DecodeError(s"unknown column '$name'").at(name))
        case None => Right(())
