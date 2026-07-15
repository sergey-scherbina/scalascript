package scalascript.interop.descriptor

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}

private[descriptor] object Jcs:
  private val Hex = "0123456789abcdef"

  def parseUtf8(bytes: Array[Byte]): Either[DescriptorError, ujson.Value] =
    if bytes.length > DescriptorCodec.MaxBytes then
      Left(DescriptorError(
        "INPUT_TOO_LARGE",
        "$",
        s"descriptor JSON exceeds ${DescriptorCodec.MaxBytes} bytes"
      ))
    else
      decodeUtf8(bytes).flatMap { text =>
        preScan(text).flatMap { _ =>
          try Right(ujson.read(text))
          catch
            case e: Exception =>
              Left(DescriptorError(
                "INVALID_JSON",
                "$",
                Option(e.getMessage).getOrElse(e.getClass.getName)
              ))
        }
      }

  def bytes(value: ujson.Value): Either[DescriptorError, Array[Byte]] =
    render(value).flatMap { text =>
      val bytes = text.getBytes(StandardCharsets.UTF_8)
      if bytes.length <= DescriptorCodec.MaxBytes then Right(bytes)
      else Left(DescriptorError(
        "OUTPUT_TOO_LARGE",
        "$",
        s"canonical descriptor JSON exceeds ${DescriptorCodec.MaxBytes} bytes"
      ))
    }

  def render(value: ujson.Value): Either[DescriptorError, String] =
    val out = new java.lang.StringBuilder()
    append(value, out, "$", depth = 0).map(_ => out.toString)

  private def append(
      value: ujson.Value,
      out: java.lang.StringBuilder,
      path: String,
      depth: Int
  ): Either[DescriptorError, Unit] =
    value match
        case ujson.Null => out.append("null"); Right(())
        case ujson.True => out.append("true"); Right(())
        case ujson.False => out.append("false"); Right(())
        case ujson.Str(s) => appendString(s, out, path)
        case ujson.Num(n) =>
          if !n.isFinite || n < 0.0 || n > Int.MaxValue.toDouble || n != math.rint(n) then
            Left(DescriptorError(
              "INVALID_JSON_NUMBER",
              path,
              "descriptor numbers must be integral indices in 0..2147483647"
            ))
          else
            out.append(n.toLong)
            Right(())
        case arr: ujson.Arr =>
          if depth >= DescriptorCodec.MaxDepth then
            Left(DescriptorError(
              "JSON_DEPTH_LIMIT",
              path,
              s"descriptor JSON nesting exceeds ${DescriptorCodec.MaxDepth}"
            ))
          else if arr.value.length > DescriptorCodec.MaxContainerItems then
            Left(DescriptorError(
              "JSON_CONTAINER_LIMIT",
              path,
              s"array exceeds ${DescriptorCodec.MaxContainerItems} items"
            ))
          else
            out.append('[')
            var i = 0
            var failure: Option[DescriptorError] = None
            while i < arr.value.length && failure.isEmpty do
              if i > 0 then out.append(',')
              append(arr.value(i), out, s"$path[$i]", depth + 1) match
                case Left(err) => failure = Some(err)
                case Right(()) => ()
              i += 1
            failure match
              case Some(err) => Left(err)
              case None      => out.append(']'); Right(())
        case obj: ujson.Obj =>
          if depth >= DescriptorCodec.MaxDepth then
            Left(DescriptorError(
              "JSON_DEPTH_LIMIT",
              path,
              s"descriptor JSON nesting exceeds ${DescriptorCodec.MaxDepth}"
            ))
          else if obj.value.size > DescriptorCodec.MaxContainerItems then
            Left(DescriptorError(
              "JSON_CONTAINER_LIMIT",
              path,
              s"object exceeds ${DescriptorCodec.MaxContainerItems} members"
            ))
          else
            out.append('{')
            val fields = obj.value.toVector.sortWith((left, right) => compareUtf16(left._1, right._1) < 0)
            var i = 0
            var failure: Option[DescriptorError] = None
            while i < fields.length && failure.isEmpty do
              if i > 0 then out.append(',')
              val (key, fieldValue) = fields(i)
              appendString(key, out, s"$path.<key>") match
                case Left(err) => failure = Some(err)
                case Right(()) =>
                  out.append(':')
                  append(fieldValue, out, s"$path.$key", depth + 1) match
                    case Left(err) => failure = Some(err)
                    case Right(()) => ()
              i += 1
            failure match
              case Some(err) => Left(err)
              case None      => out.append('}'); Right(())

  private def decodeUtf8(bytes: Array[Byte]): Either[DescriptorError, String] =
    val decoder = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    try
      val text = decoder.decode(ByteBuffer.wrap(bytes)).toString
      if text.nonEmpty && text.charAt(0) == '\ufeff' then
        Left(DescriptorError("INVALID_UTF8", "$", "descriptor JSON must not contain a UTF-8 BOM"))
      else Right(text)
    catch
      case e: Exception =>
        Left(DescriptorError("INVALID_UTF8", "$", Option(e.getMessage).getOrElse(e.getClass.getName)))

  private final case class ScanFrame(close: Char, commas: Int, hasContent: Boolean)

  /** String-aware structural bounds are enforced before the recursive JSON parser. */
  private def preScan(text: String): Either[DescriptorError, Unit] =
    var frames = List.empty[ScanFrame]
    var inString = false
    var escaped = false
    var index = 0

    def markContent(): Unit = frames match
      case head :: tail if !head.hasContent => frames = head.copy(hasContent = true) :: tail
      case _                                => ()

    def invalidNumber(token: String): Left[DescriptorError, Nothing] =
      Left(DescriptorError(
        "INVALID_JSON_NUMBER",
        "$",
        s"descriptor number '$token' must be an integral index in 0..2147483647"
      ))

    def scanNumber(start: Int): Either[DescriptorError, Int] =
      var end = start
      while end < text.length && {
        val current = text.charAt(end)
        !Character.isWhitespace(current) && current != ',' && current != ']' && current != '}'
      } do end += 1

      val token = text.substring(start, end)
      if token == "0" then Right(end)
      else if token.isEmpty || token.charAt(0) < '1' || token.charAt(0) > '9' then
        invalidNumber(token)
      else
        var value = 0
        var digitIndex = 0
        while digitIndex < token.length do
          val digit = token.charAt(digitIndex)
          if digit < '0' || digit > '9' then return invalidNumber(token)
          val numeric = digit - '0'
          if value > (Int.MaxValue - numeric) / 10 then return invalidNumber(token)
          value = value * 10 + numeric
          digitIndex += 1
        Right(end)

    while index < text.length do
      val ch = text.charAt(index)
      if inString then
        if escaped then escaped = false
        else if ch == '\\' then escaped = true
        else if ch == '"' then inString = false
      else
        ch match
          case '"' => markContent(); inString = true
          case '{' | '[' =>
            markContent()
            if frames.size >= DescriptorCodec.MaxDepth then
              return Left(DescriptorError(
                "JSON_DEPTH_LIMIT",
                "$",
                s"descriptor JSON nesting exceeds ${DescriptorCodec.MaxDepth}"
              ))
            val close = if ch == '{' then '}' else ']'
            frames = ScanFrame(close, commas = 0, hasContent = false) :: frames
          case ',' if frames.nonEmpty =>
            val head = frames.head
            val commas = head.commas + 1
            if commas >= DescriptorCodec.MaxContainerItems then
              return Left(DescriptorError(
                "JSON_CONTAINER_LIMIT",
                "$",
                s"container exceeds ${DescriptorCodec.MaxContainerItems} items"
              ))
            frames = head.copy(commas = commas, hasContent = false) :: frames.tail
          case '}' | ']' if frames.nonEmpty && frames.head.close == ch =>
            val head = frames.head
            val items = if head.hasContent then head.commas + 1 else head.commas
            if items > DescriptorCodec.MaxContainerItems then
              return Left(DescriptorError(
                "JSON_CONTAINER_LIMIT",
                "$",
                s"container exceeds ${DescriptorCodec.MaxContainerItems} items"
              ))
            frames = frames.tail
          case c if c == '-' || c == '+' || (c >= '0' && c <= '9') =>
            markContent()
            scanNumber(index) match
              case Left(error) => return Left(error)
              case Right(end)  => index = end - 1
          case c if !Character.isWhitespace(c) => markContent()
          case _ => ()
      index += 1
    Right(())

  private def appendString(
      value: String,
      out: java.lang.StringBuilder,
      path: String
  ): Either[DescriptorError, Unit] =
    out.append('"')
    var i = 0
    while i < value.length do
      val ch = value.charAt(i)
      ch match
        case '"' => out.append("\\\"")
        case '\\' => out.append("\\\\")
        case '\b' => out.append("\\b")
        case '\t' => out.append("\\t")
        case '\n' => out.append("\\n")
        case '\f' => out.append("\\f")
        case '\r' => out.append("\\r")
        case c if c <= 0x1f =>
          out.append("\\u00")
          out.append(Hex.charAt((c.toInt >>> 4) & 0x0f))
          out.append(Hex.charAt(c.toInt & 0x0f))
        case c if Character.isHighSurrogate(c) =>
          if i + 1 >= value.length || !Character.isLowSurrogate(value.charAt(i + 1)) then
            return Left(DescriptorError("INVALID_UNICODE", path, "lone high surrogate in JSON string"))
          out.append(c)
          i += 1
          out.append(value.charAt(i))
        case c if Character.isLowSurrogate(c) =>
          return Left(DescriptorError("INVALID_UNICODE", path, "lone low surrogate in JSON string"))
        case c => out.append(c)
      i += 1
    out.append('"')
    Right(())

  /** RFC 8785 orders property names by their raw UTF-16 code units. */
  private def compareUtf16(left: String, right: String): Int =
    val common = math.min(left.length, right.length)
    var i = 0
    while i < common do
      val delta = left.charAt(i).toInt - right.charAt(i).toInt
      if delta != 0 then return delta
      i += 1
    left.length - right.length
