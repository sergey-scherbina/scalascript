package scalascript.typeddata

import scala.compiletime.error
import scala.deriving.Mirror
import scala.quoted.*

trait EdgeCodec[A] extends Codec[A, EdgeValue]:
  def label: String
  def from(value: A): String
  def to(value: A): String

object EdgeCodec:
  def apply[A](using codec: EdgeCodec[A]): EdgeCodec[A] = codec

  inline given derived[A](using mirror: Mirror.Of[A]): EdgeCodec[A] =
    inline mirror match
      case product: Mirror.ProductOf[A] => derivedProduct[A](using product)
      case _: Mirror.SumOf[A] => error("EdgeCodec derivation currently supports case classes only")

  def instance[A](
      edgeLabel: String,
      encodeEdge: A => EdgeValue,
      decodeEdge: EdgeValue => Either[DecodeError, A],
      edgeFrom: A => String,
      edgeTo: A => String
  ): EdgeCodec[A] =
    new EdgeCodec[A]:
      def label: String = edgeLabel
      def from(value: A): String = edgeFrom(value)
      def to(value: A): String = edgeTo(value)
      def encode(value: A): EdgeValue = encodeEdge(value)
      def decode(repr: EdgeValue): Either[DecodeError, A] = decodeEdge(repr)

  private inline def derivedProduct[A](using mirror: Mirror.ProductOf[A]): EdgeCodec[A] =
    ${ DerivedSchemaMacros.edgeProduct[A]('mirror) }
