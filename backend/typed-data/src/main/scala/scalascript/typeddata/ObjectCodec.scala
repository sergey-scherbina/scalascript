package scalascript.typeddata

import scala.compiletime.error
import scala.deriving.Mirror
import scala.quoted.*

trait ObjectCodec[A] extends Codec[A, ObjectValue]:
  def keyField: Option[String] = None
  def key(value: A): Option[String] =
    keyField.flatMap(field => ObjectCodec.scalarKey(encode(value).fields.get(field)))

object ObjectCodec:
  def apply[A](using codec: ObjectCodec[A]): ObjectCodec[A] = codec

  inline given derived[A](using mirror: Mirror.Of[A]): ObjectCodec[A] =
    inline mirror match
      case product: Mirror.ProductOf[A] => derivedProduct[A](using product)
      case _: Mirror.SumOf[A] => error("ObjectCodec derivation currently supports case classes only")

  def instance[A](
      encodeObject: A => ObjectValue,
      decodeObject: ObjectValue => Either[DecodeError, A],
      keyFieldName: Option[String] = None
  ): ObjectCodec[A] =
    new ObjectCodec[A]:
      override def keyField: Option[String] = keyFieldName
      def encode(value: A): ObjectValue = encodeObject(value)
      def decode(repr: ObjectValue): Either[DecodeError, A] = decodeObject(repr)

  def fromJsonCodec[A](keyFieldName: Option[String] = None)(using codec: JsonCodec[A]): ObjectCodec[A] =
    instance[A](
      value =>
        codec.encode(value) match
          case JsonValue.Obj(fields) => ObjectValue(fields)
          case other => ObjectValue(Map("value" -> other))
      ,
      objectValue =>
        codec.decode(JsonValue.Obj(objectValue.fields)) match
          case Right(value) => Right(value)
          case Left(objectError) =>
            objectValue.fields.get("value") match
              case Some(value) => codec.decode(value).left.map(_.at("value"))
              case None => Left(objectError)
      ,
      keyFieldName
    )

  def field[A](fields: Map[String, JsonValue], name: String)(using codec: JsonCodec[A]): Either[DecodeError, A] =
    JsonCodec.field(fields, name)

  def field[A](fields: Map[String, JsonValue], spec: ObjectFieldSpec[A]): Either[DecodeError, A] =
    spec.names.collectFirst(Function.unlift(fields.get)) match
      case Some(value) => spec.codec.decode(value).left.map(_.at(spec.name))
      case None =>
        spec.default match
          case Some(value) => Right(value)
          case None => Left(DecodeError(s"missing field '${spec.name}'").at(spec.name))

  def rejectUnknownFields(fields: Map[String, JsonValue], specs: Iterable[ObjectFieldSpec[?]]): Either[DecodeError, Unit] =
    val known = specs.iterator.flatMap(_.names).toSet
    fields.keys.find(!known.contains(_)) match
      case Some(name) => Left(DecodeError(s"unknown field '$name'").at(name))
      case None => Right(())

  def objectCodec[A](
      encodeFields: A => Map[String, JsonValue],
      decodeFields: Map[String, JsonValue] => Either[DecodeError, A],
      fields: Iterable[ObjectFieldSpec[?]] = Nil,
      rejectUnknown: Boolean = false
  ): ObjectCodec[A] =
    instance(
      value => ObjectValue(encodeFields(value)),
      objectValue =>
        if rejectUnknown then rejectUnknownFields(objectValue.fields, fields).flatMap(_ => decodeFields(objectValue.fields))
        else decodeFields(objectValue.fields),
      fields.find(_.key).map(_.name)
    )

  private[typeddata] def scalarKey(value: Option[JsonValue]): Option[String] =
    value.collect {
      case JsonValue.Str(value) => value
      case JsonValue.Num(value) => value.bigDecimal.stripTrailingZeros.toPlainString
      case JsonValue.Bool(value) => value.toString
    }

  private inline def derivedProduct[A](using mirror: Mirror.ProductOf[A]): ObjectCodec[A] =
    ${ DerivedSchemaMacros.objectProduct[A]('mirror) }
