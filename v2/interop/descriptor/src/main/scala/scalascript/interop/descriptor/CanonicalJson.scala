package scalascript.interop.descriptor

private[descriptor] object CanonicalJson:

  def bytes(value: ujson.Value): Either[DescriptorError, Array[Byte]] =
    Jcs.bytes(value)

  def text(value: ujson.Value): Either[DescriptorError, String] =
    Jcs.render(value)

  def bytesWithoutRootField(
      value: ujson.Value,
      field: String
  ): Either[DescriptorError, Array[Byte]] =
    value match
      case obj: ujson.Obj =>
        val filtered = obj.value.iterator.filterNot(_._1 == field).toSeq
        Jcs.bytes(ujson.Obj.from(filtered))
      case _ =>
        Left(DescriptorError(
          "INVALID_DESCRIPTOR_ROOT",
          "$",
          "descriptor root must encode as a JSON object"
        ))
