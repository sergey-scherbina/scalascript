package scalascript.blockchain.evm.abi

/** ABI runtime values. Each variant corresponds to one `AbiType`; the
 *  codec round-trips a (type, value) pair.
 *
 *  Names use `SInt` and `Arr` rather than `Int` / `Array` to avoid
 *  shadowing the corresponding scala types in callers. */
sealed trait AbiValue

object AbiValue:
  case class UInt(width: scala.Int, value: BigInt) extends AbiValue
  case class SInt(width: scala.Int, value: BigInt) extends AbiValue
  case class Address(value: String) extends AbiValue
  case class Bool(value: Boolean) extends AbiValue
  case class FixedBytes(width: scala.Int, value: scala.Array[Byte]) extends AbiValue:
    require(value.length == width, s"bytes$width expected $width bytes, got ${value.length}")
  case class Bytes(value: scala.Array[Byte]) extends AbiValue
  case class Str(value: String) extends AbiValue
  case class Arr(values: Seq[AbiValue]) extends AbiValue
  case class Tuple(values: Seq[AbiValue]) extends AbiValue
