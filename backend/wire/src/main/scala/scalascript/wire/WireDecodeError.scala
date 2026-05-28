package scalascript.wire

/** Decode errors for `WireCodec[A].decode`. */
enum WireDecodeError(val message: String) extends Exception(message):
  case TypeMismatch(expected: String, got: String)
      extends WireDecodeError(s"expected $expected, got $got")
  case MissingField(fieldName: String, typeName: String)
      extends WireDecodeError(s"missing field '$fieldName' in $typeName")
  case InvalidEnum(typeName: String, caseName: String)
      extends WireDecodeError(s"unknown enum case '$caseName' for $typeName")
  case SizeExceeded(limit: Long, actual: Long)
      extends WireDecodeError(s"payload size $actual exceeds limit $limit bytes")
  case DepthExceeded(limit: Int)
      extends WireDecodeError(s"nesting depth exceeds limit $limit")
  case MalformedInput(detail: String)
      extends WireDecodeError(s"malformed wire input: $detail")
  case SchemaIdMismatch(expected: String, got: String)
      extends WireDecodeError(s"schema id mismatch: expected $expected, got $got")

object WireDecodeError:
  def typeError(expected: String, got: WireValue): WireDecodeError =
    TypeMismatch(expected, WireValue.kindOf(got))
