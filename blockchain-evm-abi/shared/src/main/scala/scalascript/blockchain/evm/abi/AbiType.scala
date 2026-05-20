package scalascript.blockchain.evm.abi

/** Solidity ABI v2 type schema. The codec walks values against types
 *  to encode / decode; types are pure data, no value coupling.
 *
 *  See https://docs.soliditylang.org/en/latest/abi-spec.html for the
 *  full specification. */
sealed trait AbiType:
  /** A type is "dynamic" iff its encoded length is not derivable from
   *  the type alone (depends on the value). Per the ABI spec:
   *  - bytes / string                 — dynamic
   *  - T[] (variable-length array)    — dynamic
   *  - T[k] (fixed) — dynamic iff T is dynamic
   *  - tuple — dynamic iff any field is dynamic
   *  - everything else                — static
   */
  def isDynamic: Boolean

  /** Solidity canonical type name, used for function-selector
   *  derivation (`keccak256(sig)[0..4]` where `sig` is the function
   *  name + tuple-style parameter list). Examples: `"uint256"`,
   *  `"bytes32"`, `"address"`, `"uint256[]"`, `"(address,uint256)"`. */
  def canonical: String

object AbiType:

  /** `uintN`, N ∈ {8, 16, 24, …, 256}. */
  case class UInt(width: scala.Int) extends AbiType:
    require(width >= 8 && width <= 256 && width % 8 == 0, s"uint width invalid: $width")
    def isDynamic = false
    def canonical = s"uint$width"

  /** `intN`, N ∈ {8, 16, 24, …, 256}. Renamed from `Int` to avoid
   *  shadowing `scala.Int`. */
  case class SInt(width: scala.Int) extends AbiType:
    require(width >= 8 && width <= 256 && width % 8 == 0, s"int width invalid: $width")
    def isDynamic = false
    def canonical = s"int$width"

  case object Address extends AbiType:
    def isDynamic = false
    def canonical = "address"

  case object Bool extends AbiType:
    def isDynamic = false
    def canonical = "bool"

  /** `bytesN`, N ∈ {1, 2, …, 32}. */
  case class FixedBytes(width: scala.Int) extends AbiType:
    require(width >= 1 && width <= 32, s"bytesN width invalid: $width")
    def isDynamic = false
    def canonical = s"bytes$width"

  case object Bytes extends AbiType:
    def isDynamic = true
    def canonical = "bytes"

  case object Str extends AbiType:
    def isDynamic = true
    def canonical = "string"

  /** `T[]` — variable-length array. Always dynamic. */
  case class DynArray(elem: AbiType) extends AbiType:
    def isDynamic = true
    def canonical = s"${elem.canonical}[]"

  /** `T[k]` — fixed-length array. Dynamic iff `elem.isDynamic`. */
  case class FixedArray(elem: AbiType, size: scala.Int) extends AbiType:
    require(size >= 0, s"array size must be non-negative: $size")
    def isDynamic = elem.isDynamic
    def canonical = s"${elem.canonical}[$size]"

  /** `(T1, T2, …)` — tuple. Dynamic iff any field is dynamic. */
  case class Tuple(fields: Seq[AbiType]) extends AbiType:
    def isDynamic = fields.exists(_.isDynamic)
    def canonical = fields.map(_.canonical).mkString("(", ",", ")")

  // ── handy aliases ────────────────────────────────────────────────

  val Uint256:  AbiType = UInt(256)
  val Uint8:    AbiType = UInt(8)
  val Int256:   AbiType = SInt(256)
  val Bytes32:  AbiType = FixedBytes(32)
