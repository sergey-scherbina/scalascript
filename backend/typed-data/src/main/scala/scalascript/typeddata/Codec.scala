package scalascript.typeddata

trait Codec[A, Repr]:
  def encode(value: A): Repr
  def decode(repr: Repr): Either[DecodeError, A]
