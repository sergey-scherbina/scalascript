package scalascript.blockchain.evm.abi

/** High-level entry point for the ABI codec. Most callers use these
 *  helpers rather than reaching into `AbiEncoder` / `AbiDecoder`
 *  directly. */
object Abi:

  /** Encode a function call: 4-byte selector || ABI-encoded args. */
  def encodeFunctionCall(name: String, paramTypes: Seq[AbiType], args: Seq[AbiValue]): Array[Byte] =
    Selector.forFunction(name, paramTypes) ++ AbiEncoder.encodeTuple(paramTypes, args)

  /** Decode a function's return tuple. */
  def decodeReturn(returnTypes: Seq[AbiType], bytes: Array[Byte]): Seq[AbiValue] =
    AbiDecoder.decodeTuple(returnTypes, bytes)

  /** Encode a single typed value. */
  def encode(typ: AbiType, value: AbiValue): Array[Byte] =
    AbiEncoder.encode(typ, value)

  /** Decode a single typed value. */
  def decode(typ: AbiType, bytes: Array[Byte]): AbiValue =
    AbiDecoder.decode(typ, bytes)
