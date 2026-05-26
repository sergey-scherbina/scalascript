package scalascript.typeddata

import scala.compiletime.error
import scala.deriving.Mirror
import scala.quoted.*

trait VertexCodec[A] extends Codec[A, VertexValue]:
  def label: String
  def id(value: A): String

object VertexCodec:
  def apply[A](using codec: VertexCodec[A]): VertexCodec[A] = codec

  inline given derived[A](using mirror: Mirror.Of[A]): VertexCodec[A] =
    inline mirror match
      case product: Mirror.ProductOf[A] => derivedProduct[A](using product)
      case _: Mirror.SumOf[A] => error("VertexCodec derivation currently supports case classes only")

  def instance[A](
      vertexLabel: String,
      encodeVertex: A => VertexValue,
      decodeVertex: VertexValue => Either[DecodeError, A],
      vertexId: A => String
  ): VertexCodec[A] =
    new VertexCodec[A]:
      def label: String = vertexLabel
      def id(value: A): String = vertexId(value)
      def encode(value: A): VertexValue = encodeVertex(value)
      def decode(repr: VertexValue): Either[DecodeError, A] = decodeVertex(repr)

  private inline def derivedProduct[A](using mirror: Mirror.ProductOf[A]): VertexCodec[A] =
    ${ DerivedSchemaMacros.vertexProduct[A]('mirror) }
