package scalascript.typeddata

/** Shared emitted-runtime snippets for the typed JSON codec boundary.
  *
  * The snippets deliberately keep today's minimal JSON behavior, but the
  * function names are the stable ABI that typed route clients call. Future
  * `JsonCodec[A]` derivation can replace these bodies without changing the
  * generated client method shape.
  */
object TypedJsonCodecRuntime:

  val encodeFunctionName = "_ssc_typed_json_encode"
  val decodeResponseFunctionName = "_ssc_typed_json_decode_response"

  val jsFacade: String =
    s"""|// Shared typed JSON codec facade. Phase 4 keeps the implementation in
        |// emitted runtime code, but typed route clients call this stable codec
        |// boundary instead of embedding transport-specific JSON operations.
        |function $encodeFunctionName(value) {
        |  return JSON.stringify(value);
        |}
        |
        |function $decodeResponseFunctionName(text, contentType) {
        |  if (text === "") return undefined;
        |  if (String(contentType || "").includes("application/json")) return JSON.parse(text);
        |  try { return JSON.parse(text); } catch (_) { return text; }
        |}
        |
        |""".stripMargin

  val jvmFacade: String =
    s"""|// Shared typed JSON codec facade. Phase 4 keeps the implementation in
        |// emitted runtime code, but typed route clients call this stable codec
        |// boundary instead of embedding transport-specific JSON operations.
        |private def $encodeFunctionName(value: Any): String =
        |  _toJsonValue(value)
        |
        |private inline def _ssc_typed_json_decode_value[T](value: Any): T =
        |  inline erasedValue[T] match
        |    case _: Unit => ().asInstanceOf[T]
        |    case _: String => value.asInstanceOf[String].asInstanceOf[T]
        |    case _: Int => (value match
        |      case n: Int => n
        |      case n: Long => n.toInt
        |      case n: Double => n.toInt
        |      case s: String => s.toInt
        |      case other => throw RuntimeException("typed route client: expected Int, got " + _show(other))
        |    ).asInstanceOf[T]
        |    case _: Long => (value match
        |      case n: Long => n
        |      case n: Int => n.toLong
        |      case n: Double => n.toLong
        |      case s: String => s.toLong
        |      case other => throw RuntimeException("typed route client: expected Long, got " + _show(other))
        |    ).asInstanceOf[T]
        |    case _: Double => (value match
        |      case n: Double => n
        |      case n: Long => n.toDouble
        |      case n: Int => n.toDouble
        |      case s: String => s.toDouble
        |      case other => throw RuntimeException("typed route client: expected Double, got " + _show(other))
        |    ).asInstanceOf[T]
        |    case _: Boolean => value.asInstanceOf[Boolean].asInstanceOf[T]
        |    case _: Option[t] =>
        |      (value match
        |        case null | None => None
        |        case other => Some(_ssc_typed_json_decode_value[t](other))
        |      ).asInstanceOf[T]
        |    case _: List[t] =>
        |      value.asInstanceOf[List[Any]].map(v => _ssc_typed_json_decode_value[t](v)).asInstanceOf[T]
        |    case _: Response =>
        |      value.asInstanceOf[Response].asInstanceOf[T]
        |    case _ =>
        |      inline summonInline[Mirror.Of[T]] match
        |        case p: Mirror.ProductOf[T] =>
        |          _ssc_typed_json_decode_product[T](value)(using p)
        |
        |private inline def _ssc_typed_json_decode_product[T](value: Any)(using m: Mirror.ProductOf[T]): T =
        |  val fields = value.asInstanceOf[Map[String, Any]]
        |  val values = _ssc_typed_json_decode_fields[m.MirroredElemTypes, m.MirroredElemLabels](fields)
        |  m.fromProduct(Tuple.fromArray(values.toArray))
        |
        |private inline def _ssc_typed_json_decode_fields[Types <: Tuple, Labels <: Tuple](fields: Map[String, Any]): List[Any] =
        |  inline erasedValue[(Types, Labels)] match
        |    case _: (EmptyTuple, EmptyTuple) => Nil
        |    case _: ((t *: ts), (label *: labels)) =>
        |      val name = constValue[label].asInstanceOf[String]
        |      val raw = fields.getOrElse(name, throw RuntimeException("typed route client: missing response field '" + name + "'"))
        |      _ssc_typed_json_decode_value[t](raw) :: _ssc_typed_json_decode_fields[ts, labels](fields)
        |
        |private inline def $decodeResponseFunctionName[T](response: scalascript.backend.spi.BackendResponse): T =
        |  val body = String(response.body, java.nio.charset.StandardCharsets.UTF_8)
        |  inline erasedValue[T] match
        |    case _: Unit => ().asInstanceOf[T]
        |    case _: Response =>
        |      Response(response.status, response.headers, body).asInstanceOf[T]
        |    case _ =>
        |      _ssc_typed_json_decode_value[T](_fromJson(body))
        |
        |""".stripMargin
