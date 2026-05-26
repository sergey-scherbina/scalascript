package scalascript.typeddata

import scala.compiletime.error
import scala.deriving.Mirror
import scala.quoted.*

trait RdfCodec[A] extends Codec[A, RdfValue]:
  def rdfClass: Option[String]
  def subject(value: A): RdfNode

object RdfCodec:
  val RdfType = "rdf:type"

  def apply[A](using codec: RdfCodec[A]): RdfCodec[A] = codec

  inline given derived[A](using mirror: Mirror.Of[A]): RdfCodec[A] =
    inline mirror match
      case product: Mirror.ProductOf[A] => derivedProduct[A](using product)
      case _: Mirror.SumOf[A] => error("RdfCodec derivation currently supports case classes only")

  def instance[A](
      classIri: Option[String],
      encodeRdf: A => RdfValue,
      decodeRdf: RdfValue => Either[DecodeError, A],
      subjectOf: A => RdfNode
  ): RdfCodec[A] =
    new RdfCodec[A]:
      def rdfClass: Option[String] = classIri
      def subject(value: A): RdfNode = subjectOf(value)
      def encode(value: A): RdfValue = encodeRdf(value)
      def decode(repr: RdfValue): Either[DecodeError, A] = decodeRdf(repr)

  private inline def derivedProduct[A](using mirror: Mirror.ProductOf[A]): RdfCodec[A] =
    ${ DerivedSchemaMacros.rdfProduct[A]('mirror) }
